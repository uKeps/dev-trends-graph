# Repository conventions

This file documents rules that apply to the entire repository. Any AI or human contributor should follow them without being told.

## Language

- **All repository-facing text is in English.** That includes code comments, JavaDoc, commit messages, log lines, error messages exposed via the API, workflow names and step descriptions, Docker and SQL comments, properties files, and any other file that is read by maintainers or tooling.
- **User-facing product copy stays in the language it is intended for.** The Portuguese strings inside `frontend/src/lib/i18n.ts` are intentional — they are the `pt` dictionary surfaced by the language switcher. Do not translate them.
- **LLM prompts are intentional in their original language.** Some prompts in `backend/src/main/java/com/dev/trends/service/GraphExtractionService.java` are intentionally in Portuguese (the extraction system prompt, the user message, `SUMMARY_PROMPT_PT`). They instruct the model and changing the prompt language changes model behavior. They carry comments above them explaining this. Leave them in Portuguese.
- **Data literals that match historical rows are not "documentation".** For example, the SQL string `'Conceito em destaque no ecossistema.'` in `NodeRepository` is the literal value that an older version of the pipeline used to write to the `nodes.summary` column. The startup migration clears rows where this literal is present. Treat it as data, not as copy to translate.

## Emojis

- **Do not introduce emojis** in workflow step names, comments, log messages, error messages, or documentation. The only legitimate uses are inside intentional user-facing copy (for example, UI text in `frontend/src/lib/i18n.ts`).

## Code style

- Match the existing conventions of the file you are editing (imports, formatting, naming). Read the surrounding code before making changes.
- Java code uses 4-space indentation and standard Spring Boot conventions.
- TypeScript/React code uses 2-space indentation and the patterns already present in `frontend/src/`.
- Workflow files use 2-space indentation and `name:` for every step.

## Commit messages

- Follow the project's existing commit-message style: `<type>(<scope>): <subject>` in English, lowercase subject, no trailing period. Types in use include `feat`, `fix`, `chore`, `ci`, `perf`, `refactor`, `i18n`, `test`, `docs`.

## CI

- Every push to `main` runs `.github/workflows/deploy.yml` (backend tests + Docker dry-run + Render trigger; frontend type-check + build; Vercel deploy is handled by Vercel's native GitHub integration).
- `.github/workflows/ingest.yml` runs on a 6-hour schedule and can also be triggered manually from the Actions tab.
- Keep both workflows green. A workflow that fails should be fixed before merging.
