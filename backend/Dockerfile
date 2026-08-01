# =============================================================
# Dockerfile — Multi-stage build otimizado para Render Free Tier
# Mapeador de Tendências da Bolha Dev e IA em Grafos
# =============================================================
# Stage 1 (builder): Compila o JAR usando Maven + JDK 21
# Stage 2 (runtime): Executa o JAR em JRE Alpine mínimo
# RAM target: ≤ 512MB (Render free tier)
# =============================================================

# ---- STAGE 1: Build -----------------------------------------
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

LABEL stage="builder"

WORKDIR /app

# Copia apenas o pom.xml primeiro para aproveitar o cache de layers
# do Docker. As dependências só são re-baixadas se o pom.xml mudar.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B --quiet

# Copia o código-fonte e compila
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -B --quiet -DskipTests

# Extrai as layers do fat JAR para otimizar o cache de imagens Docker.
# O Spring Boot Layered JARs separam: dependencies, spring-boot-loader,
# snapshot-dependencies e application (código da aplicação).
WORKDIR /app/extracted
RUN java -Djarmode=layertools -jar /app/target/*.jar extract

# ---- STAGE 2: Runtime (imagem mínima) -----------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="dev-trends-graph"
LABEL version="1.0.0"

# Cria usuário não-root para segurança (boa prática em containers)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copia as layers na ordem correta (menos volátil → mais volátil)
# para maximizar a reutilização de layers no registry do Render.
COPY --from=builder --chown=appuser:appgroup /app/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /app/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /app/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /app/extracted/application/ ./

USER appuser

# Expõe a porta dinâmica do Render (injetada via env var PORT)
EXPOSE ${PORT:-8080}

# Health check interno do Docker (complementar ao health check do Render)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:${PORT:-8080}/health || exit 1

# =============================================================
# Flags JVM otimizadas para ambientes com pouca RAM (512MB):
#   -Xmx256m           → Heap máximo de 256MB
#   -Xms64m            → Heap inicial de 64MB (inicia menor, evita OOM no startup)
#   -Xss512k           → Stack por thread reduzido para 512KB
#   -XX:+UseSerialGC   → GC Serial consome menos overhead em instâncias pequenas
#   -XX:MaxMetaspaceSize=128m → Limita o Metaspace
#   -XX:+TieredCompilation    → Compilação JIT em camadas (melhora startup)
#   -XX:TieredStopAtLevel=1  → Para compilação no nível 1 (interpretado+baseline)
#                               para startup mais rápido no cold start do Render
#   -Djava.security.egd      → Fonte de entropia mais rápida para SSL
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
