# Reticle

Trend mapper for the software development and AI community. It ingests content from sources such as Hacker News, Dev.to, Stack Overflow and Lobsters, extracts the emerging topics and presents them as graphs and grids organized by category.

The web interface ships in English by default, with a pt-BR translation available from the EN/PT switch in the header.

## Architecture

- **Backend**: Java 21 with Spring Boot 3 (hosted on Render).
- **Database**: PostgreSQL with pgvector on Supabase.
- **Frontend**: Next.js 14 with React Flow (hosted on Vercel).
- **LLM provider**: Groq API (`openai/gpt-oss-20b`).

## Project Structure

- `backend/`: Spring Boot REST API responsible for collecting articles, extracting concepts and persisting them to the database.
- `frontend/`: Web app for interactive graph visualization, search and per-technology summaries.
- `.github/workflows/`: CI/CD pipeline for tests, Docker build validation and deploy.

## Main Endpoints

- `GET /health`: API health check.
- `GET /api/v1/graph?days=7`: Returns nodes and edges filtered by period.
- `GET /api/v1/articles?days=7&limit=100`: Returns the collected articles, grouped by topic in the UI.
- `GET /api/v1/trends`: Returns the topics with the highest relevance score.
- `GET /api/v1/nodes/{id}/summary`: Returns (or generates on demand) the technical summary of a topic.
- `POST /api/v1/ingest`: Manually triggers the ingestion pipeline.

## Running Locally

### Backend

```bash
cd backend
mvn spring-boot:run
```

Required environment variables:
- `DATABASE_URL`
- `DB_PASSWORD`
- `GROQ_API_KEY`

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Required environment variable:
- `NEXT_PUBLIC_API_URL`

## License

MIT
