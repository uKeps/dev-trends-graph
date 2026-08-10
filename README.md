# Dev Trends Graph

Mapeador de tendências da comunidade de desenvolvimento e inteligência artificial. A aplicação realiza a ingestão e extração de tópicos emergentes a partir de fontes como Hacker News, Dev.to, StackOverflow e Lobsters, apresentando os conceitos em grafos e grids organizados por categorias.

## Arquitetura

- **Backend**: Java 21 com Spring Boot 3 (hospedado no Render).
- **Banco de Dados**: PostgreSQL com pgvector no Supabase.
- **Frontend**: Next.js 14 com React Flow e Tailwind CSS (hospedado na Vercel).
- **Provedor LLM**: Groq API (`openai/gpt-oss-20b`).

## Estrutura do Projeto

- `backend/`: API REST em Spring Boot responsável pela coleta de artigos, extração de conceitos e persistência no banco de dados.
- `frontend/`: Aplicação web para visualização interativa do grafo, busca e consulta de resumos por tecnologia.
- `.github/workflows/`: Pipeline CI/CD para testes, validação de Docker build e deploy.

## Endpoints Principais

- `GET /health`: Health check da API.
- `GET /api/v1/graph?days=7`: Retorna nós e arestas filtrados por período.
- `GET /api/v1/trends`: Retorna os tópicos com maior pontuação de relevância.
- `GET /api/v1/nodes/{id}/summary`: Retorna ou gera dinamicamente o resumo técnico de um tópico específico.
- `POST /api/v1/ingest`: Dispara manualmente o pipeline de ingestão de dados.

## Execução Local

### Backend

```bash
cd backend
mvn spring-boot:run
```

Variáveis de ambiente requeridas:
- `DATABASE_URL`
- `DB_PASSWORD`
- `GROQ_API_KEY`

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Variável de ambiente requerida:
- `NEXT_PUBLIC_API_URL`

## Licença

MIT
