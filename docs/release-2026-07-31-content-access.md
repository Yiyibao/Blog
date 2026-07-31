# Release record: content access and seed data

Date: 2026-07-31

## Delivered

- Flyway `V39__seed_twenty_posts_and_dishes.sql` keeps the public catalog at 20 published posts and 20 published dishes. Existing V1/V34 posts are preserved; V39 adds five new posts and ten new dishes.
- The ten new dishes use the local generated SVG illustrations under `frontend/public/food/generated/`. They do not depend on remote image hosts.
- Production frontend builds render the ICP record `\u82cfICP\u59072026052529\u53f7-1` in the public footer and link it to `https://beian.miit.gov.cn/`.
- Authenticated sessions whose role is not `ADMIN` can only enter `/articles`, `/articles/:slug`, and `/recipes`. The router redirects every other destination to `/articles`; desktop, mobile, and footer navigation hide those destinations as well.

## Verification

- Frontend: `npm.cmd run test` -> 49 files, 499 tests passed.
- Frontend: `npm.cmd run test:typecheck` and `npm.cmd run build` passed; Vite prerendered six public routes and two noindex login routes.
- Backend: `mvn test` -> 658 tests passed; Flyway migrated a clean database through v39.
- `git diff --check` passed.

## Deployment checklist

- Build a new release from the commit containing this record.
- Verify `/actuator/health`, `/api/v1/posts?size=100`, and `/api/v1/dishes?size=100` after restart.
- Verify `https://hxnf.top/` contains the ICP record and that a non-admin session is redirected from `/about`, `/notes`, and `/admin` to `/articles`.

## Deployment result

- Commit: `903f464` (`feat: restrict member content and seed public catalog`).
- Release: `/opt/yubai-blog/releases/release-20260731-903f464` (atomic symlink switch from `release-20260731-9ad751d`).
- Server health: `UP` after restart; Flyway log reports version `v39`.
- Server-side HTTPS check: `HTTP/2 200`, `cache-control: no-cache`.
- Server-side API checks: posts `totalElements=20`; dishes `totalElements=20`.
- Public domain checks from this workstation are currently blocked because local DNS resolves `hxnf.top` to `127.0.0.1`; the same hostname over HTTPS was verified on the server with `--resolve`.
