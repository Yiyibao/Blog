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

## Sitemap

The backend generates a dynamic XML Sitemap at `GET /sitemap.xml`.

**Endpoint:** `/sitemap.xml`
**Content-Type:** `application/xml`
**Caching:** Not cached (regenerated on each request; database queries are lightweight projections).

**Included pages:**
- Static pages: `/`, `/articles`, `/notes`, `/recipes`, `/about`
- All published posts (`PostStatus.PUBLISHED`)
- All published dishes (`published = true`)
- All public notes (`NoteStatus.PUBLISHED`)

**Excluded content:**
- Draft posts
- Unpublished or archived dishes/notes
- Admin or API paths

**URL generation:**
- All URLs are absolute using the configured `app.site-url` (default `http://localhost:5173`).
- Post URLs: `{siteUrl}/articles/{slug}`
- Dish URLs: `{siteUrl}/recipes?dish={slug}`
- Note URLs: `{siteUrl}/notes?note={id}`

**lastmod:**
- Posts: `published_date` (LocalDate)
- Dishes: `updatedAt` (Instant, ISO-8601)
- Notes: `updatedAt` (Instant, ISO-8601)
- Static pages: no lastmod

**XML generation:** Uses `javax.xml.stream.XMLStreamWriter` (StAX) for proper namespace, encoding, and character escaping.

**Configuration:**
```yaml
app:
  site-url: ${APP_SITE_URL:http://localhost:5173}
```

**Source files:**
- `sitemap/SitemapController.java` — endpoint
- `sitemap/SitemapService.java` — logic and XML serialization
- `sitemap/SitemapEntry.java` — record type
- `config/SiteUrlConfig.java` — site URL config holder
- Repository `findPublishedSitemap()` projection queries in `PostRepository`, `DishRepository`, `NoteRepository`

**Tests:**
- `sitemap/SitemapServiceTest.java` — unit tests (Mockito)
- `BlogApiIntegrationTest.Order(10)` — integration test verifying live content filtering

## Page Meta management

Page `<title>`, `<meta>`, `<link rel="canonical">`, Open Graph and Twitter Card
tags are managed centrally through `frontend/src/composables/usePageMeta.ts`.

**Title format:**

  content_title | site_name

Home page shows only the site name (or `site_name | site_subtitle` when
subtitle is set).

**Entry point:**

- `App.vue` watches `route.fullPath` and applies basic page meta (description,
  canonical, OG) for every route change.
- Detail pages (ArticlePage, PublicNotes, FoodSection) call `apply()` with
  content-specific data when async data loads.

**Canonical URL rules:**

- Built from the configured `siteUrl` via `resolveUrl()` in `src/config/site.ts`.
- Static pages: `/articles`, `/notes`, `/recipes`, `/about`.
- Detail pages: `/articles/:slug`, `/notes?note=:id`, `/recipes?dish=:slug`.
- Necessary query parameters for content identity are preserved.
- Removed when switching between unrelated pages.
- Never uses admin URLs.

**Detail page metadata:**

- Article: title + excerpt, OG type `article`.
- Note: title + generated plain-text excerpt from Markdown body, OG type `article`.
- Dish: name + summary, OG type `article`.

**Admin pages** (all `/admin/*` routes): set `noindex, nofollow`.
Admin pages never inherit public metadata from the previous route.

**404 / not-found:** also set `noindex, nofollow`; clear all OG tags and canonical.

**Async safety:** The composable uses a monotonic `applyId` counter. Stale
responses from slow API calls do not override newer route meta.

**Dependencies:** Reads `VITE_SITE_NAME`, `VITE_SITE_URL` and
`VITE_SOCIAL_IMAGE` from the site config (`src/config/site.ts`).

**Tests:** `src/test/usePageMeta.test.ts` covers title formatting, description
cleanup, canonical generation, OG/Twitter tag lifecycle, route-switch
cleanup, async ordering, and 404 handling.
