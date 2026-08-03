#!/usr/bin/env bash
# =============================================================
# setup-repo.sh — Script de criação do repositório Git
# Mapeador de Tendências da Bolha Dev e IA em Grafos
#
# Uso:
#   1. Certifique-se de estar na raiz do projeto (dev-trends-graph/)
#   2. Execute: chmod +x setup-repo.sh && ./setup-repo.sh
#   3. Quando solicitado, insira a URL do seu repositório remoto
#
# Convenção de Commits: Conventional Commits (https://conventionalcommits.org)
# Formato: type(scope): description
# =============================================================

set -euo pipefail

# ── Verificações de pré-requisitos ────────────────────────────
if ! command -v git &>/dev/null; then
  echo "❌ Git não encontrado. Instale o Git antes de continuar."
  exit 1
fi

echo "============================================"
echo "  Dev Trends Graph — Inicialização do Repo"
echo "============================================"
echo ""

# ── 1. Inicializa o repositório Git ───────────────────────────
echo "📁 Inicializando repositório Git..."
git init
git checkout -b main

# ── 2. Cria o .gitignore ──────────────────────────────────────
cat > .gitignore << 'GITIGNORE'
# Java / Maven
target/
*.class
*.jar
*.war
*.ear
.mvn/wrapper/maven-wrapper.jar
!**/src/main/**/target/
!**/src/test/**/target/

# Node / Next.js
node_modules/
frontend/.next/
frontend/out/
frontend/.vercel/
frontend/dist/
frontend/build/

# Variáveis de ambiente sensíveis (NUNCA comitar)
.env
.env.local
.env.*.local
.env.production

# IDEs
.idea/
*.iml
*.iws
.vscode/
*.suo
*.ntvs*
*.njsproj
*.sln

# OS
.DS_Store
Thumbs.db
desktop.ini

# Logs
*.log
logs/

# Docker
*.dockerignore
GITIGNORE

echo "✅ .gitignore criado."

# ── 3. Cria o README.md ───────────────────────────────────────
cat > README.md << 'README'
# 🌐 Dev Trends Graph
> Mapeador de Tendências da Bolha Dev & IA em Grafos Interativos

Visualização em tempo real das principais tecnologias, frameworks e conceitos
emergentes no ecossistema de desenvolvimento, extraídos automaticamente do
Hacker News via LLM e exibidos em um grafo de conhecimento interativo.

## 🏗️ Arquitetura

```
Hacker News API
      ↓
Java 21 (Spring Boot 3) — Render
      ↓  (extração via LLM)
Supabase (PostgreSQL + pgvector)
      ↓  (API REST)
Next.js + React Flow — Vercel
```

## 🚀 Stack Técnica

| Camada     | Tecnologia              | Hospedagem |
|------------|-------------------------|------------|
| Backend    | Java 21 + Spring Boot 3 | Render     |
| Database   | PostgreSQL + pgvector   | Supabase   |
| Frontend   | Next.js 14 + React Flow | Vercel     |
| CI/CD      | GitHub Actions          | —          |

## ⚙️ Configuração

### 1. Banco de Dados (Supabase)
Execute `backend/src/main/resources/schema.sql` no SQL Editor do Supabase.

### 2. Backend (Render)
Configure as variáveis de ambiente:
```
DATABASE_URL=jdbc:postgresql://<host>:5432/<db>?sslmode=require
DB_PASSWORD=<senha>
GROQ_API_KEY=gsk_...
```

### 3. Frontend (Vercel)
Configure a variável de ambiente:
```
NEXT_PUBLIC_API_URL=https://sua-api.onrender.com
```

### 4. GitHub Actions (Secrets)
```
RENDER_DEPLOY_HOOK_URL  → Webhook do Render
VERCEL_TOKEN            → Token da Vercel
VERCEL_ORG_ID           → ID da organização Vercel
VERCEL_PROJECT_ID       → ID do projeto Vercel
NEXT_PUBLIC_API_URL     → URL do backend no Render
```

## 📦 Desenvolvimento Local

```bash
# Backend
cd backend
mvn spring-boot:run

# Frontend
cd frontend
npm install
npm run dev
```

## 📄 Licença
MIT
README

echo "✅ README.md criado."

# ─────────────────────────────────────────────────────────────
# COMMITS — Conventional Commits
# ─────────────────────────────────────────────────────────────

echo ""
echo "📝 Criando commits com Conventional Commits..."
echo ""

# ── COMMIT 1: Schema do banco de dados ───────────────────────
echo "➕ [1/5] feat(db): add supabase postgresql schema with pgvector"
git add \
  backend/src/main/resources/schema.sql \
  .gitignore \
  README.md

git commit -m "feat(db): add supabase postgresql schema with pgvector

- Enable pgvector extension for future semantic embeddings
- Create 'posts' table to store HN articles
- Create 'nodes' table for tech concepts with hype_score
- Create 'edges' table for semantic relationships
- Create 'ingestion_log' table for pipeline tracking
- Add B-Tree indexes on date, label and hype_score columns
- Add HNSW-ready structure via pgvector extension
- Add upsert_node() and upsert_edge() PL/pgSQL functions
- Add v_graph_data view for simplified graph queries"

echo "✅ Commit 1 criado."
echo ""

# ── COMMIT 2: Ingestão e extração IA em Java 21 ──────────────
echo "➕ [2/5] feat(ingestion): add hacker news collector and LLM extractor in java 21"
git add \
  backend/src/main/java/com/dev/trends/service/GraphExtractionService.java \
  backend/src/main/java/com/dev/trends/model/ExtractionResult.java \
  backend/src/main/java/com/dev/trends/model/NodeRequest.java \
  backend/src/main/java/com/dev/trends/model/EdgeRequest.java \
  backend/src/main/java/com/dev/trends/model/Node.java \
  backend/src/main/java/com/dev/trends/model/Edge.java \
  backend/src/main/java/com/dev/trends/repository/NodeRepository.java \
  backend/src/main/java/com/dev/trends/repository/EdgeRepository.java

git commit -m "feat(ingestion): add hacker news collector and LLM extractor in java 21

- Use java.net.http.HttpClient (native Java 21) for HTTP calls
- Fetch top 30 articles from HN API with parallel CompletableFuture
- Send article titles to Groq/OpenAI API with strict JSON system prompt
- Parse LLM JSON response using Jackson (nodes + edges extraction)
- Persist nodes via upsert_node() PostgreSQL function (JDBC)
- Persist edges via upsert_edge() PostgreSQL function (JDBC)
- Add keyword-based fallback extractor when LLM is unavailable
- Use Java 21 Records for NodeRequest, EdgeRequest, ExtractionResult
- Add NodeRepository and EdgeRepository with Spring JDBC"

echo "✅ Commit 2 criado."
echo ""

# ── COMMIT 3: API REST e Dockerfile ──────────────────────────
echo "➕ [3/5] feat(api): create java rest endpoints and dockerfile for render"
git add \
  backend/src/main/java/com/dev/trends/controller/GraphController.java \
  backend/src/main/java/com/dev/trends/DevTrendsApplication.java \
  backend/src/main/resources/application.properties \
  backend/pom.xml \
  backend/Dockerfile

git commit -m "feat(api): create java rest endpoints and dockerfile for render

- GET /health: health check endpoint for Render startup verification
- GET /api/v1/graph?days=N: returns nodes+edges formatted for React Flow
- GET /api/v1/trends?limit=N: returns top N nodes ordered by hype_score
- POST /api/v1/ingest: manually trigger ingestion pipeline (API key protected)
- Enable CORS for Vercel frontend consumption
- Add Spring @Scheduled ingestion every 6 hours
- Multi-stage Docker build: builder (JDK 21 Alpine) + runtime (JRE Alpine)
- JVM flags optimized for 512MB RAM: -Xmx256m -Xss512k -XX:+UseSerialGC
- Dynamic PORT support via Render environment variable
- Layered JAR extraction for optimized Docker layer caching
- Non-root user (appuser) for container security
- HikariCP pool tuned to max 3 connections (Supabase free tier)"

echo "✅ Commit 3 criado."
echo ""

# ── COMMIT 4: Frontend Next.js + React Flow ───────────────────
echo "➕ [4/5] feat(ui): implement interactive graph component with react flow"
git add \
  frontend/src/components/GraphView.tsx \
  frontend/src/app/layout.tsx \
  frontend/src/app/page.tsx \
  frontend/src/app/globals.css \
  frontend/package.json \
  frontend/tsconfig.json \
  frontend/next.config.js

git commit -m "feat(ui): implement interactive graph component with react flow

- Use @xyflow/react for interactive graph visualization
- Full dark mode with deep space color palette
- Custom TechNode component with category-colored glow effects
- Hype score progress bar and mention count on each node
- Animated edges (dashed) for high-weight relationships
- Relation type labels on edges (USES, COMPETES_WITH, etc.)
- Color-coded edges by relation type (8 relation types)
- TrendsPanel sidebar showing top 8 nodes by hype_score
- Node detail panel on click with firstSeen, mentionCount, hypeScore
- Time filter buttons (3d, 7d, 14d, 30d) in header
- Category legend with color dots in bottom-left panel
- Pan, zoom and drag support via React Flow built-ins
- MiniMap with category-colored nodes
- Loading spinner and error state with retry button
- Stats counter (concepts + relations) in header
- Radial/spiral automatic layout based on hype_score
- Google Fonts Inter for premium typography
- Next.js 14 App Router with proper metadata for SEO"

echo "✅ Commit 4 criado."
echo ""

# ── COMMIT 5: CI/CD GitHub Actions ───────────────────────────
echo "➕ [5/5] ci(deploy): add github actions workflow for render and vercel"
git add \
  .github/workflows/deploy.yml

git commit -m "ci(deploy): add github actions workflow for render and vercel

- Trigger on push to main branch and PRs
- Job 1 (backend): setup JDK 21 Temurin with Maven cache
- Job 1 (backend): run mvn verify with H2 in-memory for tests
- Job 1 (backend): upload surefire test reports as artifacts
- Job 1 (backend): trigger Render deploy via webhook (RENDER_DEPLOY_HOOK_URL)
- Job 2 (frontend): setup Node 20 with npm cache
- Job 2 (frontend): run TypeScript type-check and ESLint
- Job 2 (frontend): build Next.js production bundle
- Job 2 (frontend): deploy to Vercel via amondnet/vercel-action@v25
- Job 3: summary notification with backend/frontend deploy status
- All secrets via GitHub repository Secrets (never hardcoded)"

echo "✅ Commit 5 criado."
echo ""

# ─────────────────────────────────────────────────────────────
# CONFIGURAÇÃO DO REMOTE (opcional)
# ─────────────────────────────────────────────────────────────

echo "============================================"
echo "  Repositório inicializado com sucesso! 🎉"
echo "============================================"
echo ""
echo "Commits criados:"
git log --oneline
echo ""
echo "Para conectar ao GitHub, execute:"
echo "  git remote add origin https://github.com/SEU_USUARIO/dev-trends-graph.git"
echo "  git push -u origin main"
echo ""
echo "📋 Próximos passos:"
echo "  1. Execute o schema.sql no SQL Editor do Supabase"
echo "  2. Configure as variáveis de ambiente no Render"
echo "  3. Configure NEXT_PUBLIC_API_URL na Vercel"
echo "  4. Adicione os 5 Secrets no GitHub (Settings → Secrets)"
echo "  5. Faça push para main para acionar o pipeline CI/CD"
