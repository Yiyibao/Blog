# Architecture

## Runtime boundaries

- `frontend` owns presentation, routing and browser-only preferences.
- `backend` owns content, validation, persistence and future authentication.
- PostgreSQL is the authoritative store for posts, dishes and learning-note Markdown.
- Imported Markdown is validated and stored as text; the original upload is never executed or retained as an arbitrary server file.

## API conventions

- Public APIs are versioned under `/api/v1`.
- Successful responses use `{ data, timestamp }`.
- Errors use an HTTP status plus `{ status, message, timestamp }`.
- Database changes are applied only through Flyway migrations.

## Modules

1. Public content: posts, categories and published dishes (posts support `DRAFT` / `PUBLISHED`).
2. Administration: login, JWT, post/dish CRUD and audit metadata.
3. Learning notes: note library, Markdown upload/export, Typora-style editor, optimistic locking and autosave.
4. Publishing: draft/public reading for posts and notes, Markdown export for notes.
5. Pagination: public and admin lists for posts, dishes and notes return `PageResponse` (`items`, `page`, `size`, `totalElements`, `totalPages`); page size is clamped to 1–50.
6. Food: `dishes` stores presentation metadata and image attribution; ordered child tables store ingredients and preparation steps.

## Learning notes

- `learning_notes.version` uses JPA optimistic locking so a stale browser tab cannot overwrite a newer edit.
- `/api/v1/admin/notes/**` requires an administrator JWT; `/api/v1/notes` returns published notes only.
- The Vue editor uses Tiptap 3 to convert between a ProseMirror document and Markdown source.
- Editing is saved after a one-second debounce. The server-returned version becomes the precondition for the next save.
- Imports accept `.md`, `.markdown` and `.txt` up to 2 MB. Exports use UTF-8 `text/markdown` downloads.
- Note images are stored as PostgreSQL `bytea` values with searchable metadata and an unguessable UUID resource URL. Accepted formats are PNG, JPEG, WebP and GIF up to 8 MB.
- Tiptap's image/file-handler extensions support paste, drop and picker uploads; the Mathematics extension serializes LaTeX back to Markdown and KaTeX renders it in both editor and public reader.

## Administration security

- Admin passwords are stored only as BCrypt hashes.
- The login endpoint issues an HS256 JWT with a two-hour default lifetime.
- Spring Security validates every bearer token server-side and requires the `ADMIN` role for write APIs.
- The SPA keeps its token in `sessionStorage`, not long-lived local storage.
- Bootstrap credentials and signing secrets stay in the ignored `backend/.env.properties` file.
