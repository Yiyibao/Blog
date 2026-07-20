# Architecture

## Runtime boundaries

- `frontend` owns presentation, routing and browser-only preferences.
- `backend` owns content, validation, persistence and future authentication.
- PostgreSQL is the authoritative store for posts, projects and future learning notes.
- Uploaded note files will live under `storage` in local development and behind a storage adapter in production.

## API conventions

- Public APIs are versioned under `/api/v1`.
- Successful responses use `{ data, timestamp }`.
- Errors use an HTTP status plus `{ status, message, timestamp }`.
- Database changes are applied only through Flyway migrations.

## Planned modules

1. Public content: posts, categories, tags and projects.
2. Administration: login, JWT, content CRUD and audit metadata.
3. Learning notes: note library, Markdown upload, attachment storage and editor autosave.
4. Publishing: draft, preview, publish and export workflows.

## Administration security

- Admin passwords are stored only as BCrypt hashes.
- The login endpoint issues an HS256 JWT with a two-hour default lifetime.
- Spring Security validates every bearer token server-side and requires the `ADMIN` role for write APIs.
- The SPA keeps its token in `sessionStorage`, not long-lived local storage.
- Bootstrap credentials and signing secrets stay in the ignored `backend/.env.properties` file.
