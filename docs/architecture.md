# Architecture

## Runtime boundaries

- `frontend` owns presentation, routing and browser-only preferences.
- `backend` owns content, validation, persistence and future authentication.
- PostgreSQL is the authoritative store for posts, projects and learning-note Markdown.
- Imported Markdown is validated and stored as text; the original upload is never executed or retained as an arbitrary server file.

## API conventions

- Public APIs are versioned under `/api/v1`.
- Successful responses use `{ data, timestamp }`.
- Errors use an HTTP status plus `{ status, message, timestamp }`.
- Database changes are applied only through Flyway migrations.

## Planned modules

1. Public content: posts, categories, tags and projects.
2. Administration: login, JWT, content CRUD and audit metadata.
3. Learning notes: note library, Markdown upload/export, Typora-style editor, optimistic locking and autosave.
4. Publishing: draft, public reading, archive and Markdown export workflows.

## Learning notes

- `learning_notes.version` uses JPA optimistic locking so a stale browser tab cannot overwrite a newer edit.
- `/api/v1/admin/notes/**` requires an administrator JWT; `/api/v1/notes` returns published notes only.
- The Vue editor uses Tiptap 3 to convert between a ProseMirror document and Markdown source.
- Editing is saved after a one-second debounce. The server-returned version becomes the precondition for the next save.
- Imports accept `.md`, `.markdown` and `.txt` up to 2 MB. Exports use UTF-8 `text/markdown` downloads.

## Administration security

- Admin passwords are stored only as BCrypt hashes.
- The login endpoint issues an HS256 JWT with a two-hour default lifetime.
- Spring Security validates every bearer token server-side and requires the `ADMIN` role for write APIs.
- The SPA keeps its token in `sessionStorage`, not long-lived local storage.
- Bootstrap credentials and signing secrets stay in the ignored `backend/.env.properties` file.
