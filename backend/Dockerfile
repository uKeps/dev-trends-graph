# =============================================================
# Dockerfile - Multi-stage build tuned for Render's free tier.
# Trend mapper for the software development and AI bubble.
# =============================================================
# Stage 1 (builder): compiles the JAR using Maven + JDK 21.
# Stage 2 (runtime): runs the JAR on a minimal Alpine JRE.
# RAM target: <= 512MB (Render free tier).
# =============================================================

# ---- STAGE 1: Build -----------------------------------------
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

LABEL stage="builder"

WORKDIR /app

# Copy pom.xml first so we can take advantage of Docker layer caching.
# Dependencies are only re-downloaded when pom.xml changes.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B --quiet

# Copy source and compile.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -B --quiet -DskipTests

# Extract the fat-JAR layers to optimize image caching.
# Spring Boot layered JARs split: dependencies, spring-boot-loader,
# snapshot-dependencies, and application (project code).
WORKDIR /app/extracted
RUN java -Djarmode=layertools -jar /app/target/*.jar extract

# ---- STAGE 2: Runtime (minimal image) -----------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="reticle"
LABEL version="1.0.0"

# Create a non-root user for security (best practice in containers).
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the layers in the correct order (least volatile -> most volatile)
# to maximize layer reuse in Render's registry.
COPY --from=builder --chown=appuser:appgroup /app/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /app/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /app/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /app/extracted/application/ ./

USER appuser

# Expose the dynamic Render port (injected via the PORT env var).
EXPOSE ${PORT:-8080}

# In-container health check (complementary to Render's health check).
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:${PORT:-8080}/health || exit 1

# =============================================================
# JVM flags tuned for low-RAM environments (512MB):
#   -Xmx256m           -> Max heap of 256MB.
#   -Xms64m            -> Initial heap of 64MB (start small to avoid OOM at boot).
#   -Xss512k           -> Reduced per-thread stack size of 512KB.
#   -XX:+UseSerialGC   -> Serial GC has less overhead on small instances.
#   -XX:MaxMetaspaceSize=128m -> Cap metaspace.
#   -XX:+TieredCompilation    -> Tiered JIT compilation (faster startup).
#   -XX:TieredStopAtLevel=1  -> Stop compilation at level 1 (interpreted+baseline)
#                               for faster startup on Render's cold start.
#   -Djava.security.egd      -> Faster entropy source for SSL.
# =============================================================
ENTRYPOINT ["java", \
    "-Xmx256m", \
    "-Xms64m", \
    "-Xss512k", \
    "-XX:+UseSerialGC", \
    "-XX:MaxMetaspaceSize=128m", \
    "-XX:+TieredCompilation", \
    "-XX:TieredStopAtLevel=1", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dserver.port=${PORT:-8080}", \
    "org.springframework.boot.loader.launch.JarLauncher"]
