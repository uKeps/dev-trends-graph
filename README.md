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
