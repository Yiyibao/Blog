# AI Platform Data Dictionary (M1)

> Effective from Flyway `V53__create_ai_multimodal_platform.sql`.
> This is an Internal Alpha schema. Production feature flags remain disabled by default.

## Ownership and identifiers

- AI resources are private. `owner` is the authenticated principal name and is rechecked on every
  read, mutation and download.
- Public APIs accept UUID/ID references only. They never accept a storage key or filesystem path.
- Timestamps use UTC `timestamptz`. Entity versions are optimistic-lock counters.

## Tables

### `ai_sessions`

| Column | Meaning / constraint |
| ------ | -------------------- |
| `id` | bigint identity primary key |
| `owner` | required private owner, indexed |
| `title` | optional display title, max 200 |
| `mode` | required workspace mode; M1 uses `WORKSPACE` |
| `summary` | optional owner-visible server-side summary of completed turns outside the recent-message window |
| `version` | optimistic-lock version |
| `created_at`, `updated_at` | required lifecycle timestamps |

### `ai_tasks`

| Column | Meaning / constraint |
| ------ | -------------------- |
| `id` | UUID primary key |
| `owner`, `session_id` | private owner and required session FK |
| `task_type` | required task discriminator; M1 uses `CHAT` |
| `status` | `QUEUED`, `RUNNING`, `WAITING_APPROVAL`, `COMPLETED`, `FAILED`, `CANCELLED` |
| `provider_id`, `provider_type`, `model` | requested/resolved provider metadata |
| `idempotency_key` | required; unique with owner |
| `error_code`, `error_message` | sanitized terminal failure information |
| `started_at`, `finished_at` | execution timestamps |
| `version` | optimistic-lock terminal CAS |
| `created_at`, `updated_at` | required lifecycle timestamps |

### `ai_task_parts`

| Column | Meaning / constraint |
| ------ | -------------------- |
| `id` | bigint identity primary key |
| `task_id`, `sequence` | task FK and unique ordered position |
| `role` | `SYSTEM`, `USER`, `ASSISTANT`, `TOOL` |
| `kind` | `TEXT`, `IMAGE_REF`, `FILE_REF`, `ARTIFACT_REF`, `TOOL_CALL`, `TOOL_RESULT`, `SOURCE_REF` |
| `text_content`, `json_payload` | bounded text or structured payload |
| `file_id`, `artifact_id`, `source_ref` | controlled references; no paths |
| `created_at` | immutable creation timestamp |

### `ai_task_events`

| Column | Meaning / constraint |
| ------ | -------------------- |
| `id` | bigint identity primary key |
| `task_id`, `sequence` | task FK and unique monotonic event sequence |
| `event_type` | stable event name such as `task.started` or `artifact.created` |
| `payload_json` | sanitized JSON payload; private file bytes/keys are excluded |
| `created_at` | immutable creation timestamp |

Appending an event pessimistically locks the owning task row before allocating `max(sequence)+1`,
so concurrent writers cannot allocate the same sequence.

### `ai_files`

| Column | Meaning / constraint |
| ------ | -------------------- |
| `id` | UUID primary key |
| `owner` | required private owner, indexed |
| `storage_key` | unique server-controlled key; never returned as a path |
| `original_name`, `media_type` | sanitized filename and verified media type |
| `size_bytes`, `sha256` | integrity metadata |
| `status` | `UPLOADING`, `READY`, `FAILED`, `EXPIRED`, `DELETED` |
| `retention` | `SEVEN_DAYS`, `THIRTY_DAYS`, `PINNED` |
| `expires_at` | nullable only for pinned files |
| `reference_count` | number of persistent task references |
| `extracted_text` | bounded parser output; cleared on expire/delete |
| `created_at`, `updated_at` | required lifecycle timestamps |

### `ai_memories`

| Column | Meaning / constraint |
| ------ | -------------------- |
| `id` | UUID primary key |
| `owner`, `scope`, `kind` | private owner and memory classification; scope is global (`USER`/`GLOBAL`/`SITE`) or `SESSION:{ownedId}` |
| `content` | memory body; cleared when forgotten |
| `source_task_id`, `source_ref` | optional provenance |
| `status` | `PROPOSED`, `ACTIVE`, `DISABLED`, `REJECTED`, `DELETED` |
| `confidence`, `expires_at` | optional proposal confidence/expiry |
| `version` | optimistic-lock edit version |
| `created_at`, `updated_at` | required lifecycle timestamps |

Model-derived memories begin as `PROPOSED`. Only explicit user confirmation makes them `ACTIVE`.
Sensitive password/token/identity/health-like content is rejected by the M1 service policy.
Active, unexpired memories are recalled by owner and scope. The body is nulled on forget, and
server-side session summaries for that owner are invalidated because they are derived context.

### `ai_artifacts`

| Column | Meaning / constraint |
| ------ | -------------------- |
| `id` | UUID primary key |
| `owner`, `task_id` | private owner and source task FK |
| `storage_key` | unique server-controlled key |
| `name`, `media_type` | safe attachment name and MIME |
| `size_bytes`, `sha256` | integrity metadata |
| `status` | `PENDING`, `READY`, `FAILED`, `EXPIRED`, `DELETED` |
| `expires_at` | retention deadline |
| `created_at`, `updated_at` | required lifecycle timestamps |

`(task_id, name)` is unique for idempotent logical materialization. M1 supports Markdown, TXT,
validated JSON, formula-safe CSV and copies of owner-authorized generated images.

## Lifecycle defaults

- One file: 15 MB; one owner: 100 files / 100 MB by default.
- PDF: 100 pages; extracted text: 120,000 characters.
- DOCX: 2,000 ZIP entries / 40 MB expanded bytes.
- CSV: 10,000 rows / 200 columns.
- Ordinary files and artifacts: 30 days unless explicitly pinned where supported.
- Context defaults: 1,500 estimated memory tokens, 1,000 summary tokens, 4,000 recent-message
  tokens and 16 recent text messages. Each layer is independently bounded.
- Cleanup runs hourly by default. It marks metadata expired in a transaction, then purges controlled
  bytes after commit; deletion failures remain retryable.

## V54 workspace extension

`V54__extend_ai_workspace_projects_and_provider_capabilities.sql` adds the project and explicit
routing contract without changing V53:

- `ai_projects`: owner-private title, `ACTIVE`/`ARCHIVED` status, archive timestamp, sort order and
  optimistic-lock version. Archiving does not cascade to sessions.
- `ai_sessions.project_id`, `status` and `archived_at`: a session can be moved out of a project,
  archived, or soft-deleted while its task history remains queryable by the owner.
- `ai_tasks.requested_*`, `resolved_*`, `required_capabilities` and `route_reason`: requested and
  resolved provider/model/reasoning are both retained for auditability and failed routing is
  visible instead of silently falling back.
- `ai_provider_models`: one row per provider/model with explicit capability and reasoning-effort
  sets. Capability routing reads this row; it does not infer support from model names.

Project memory uses the existing `ai_memories.scope` field as `PROJECT:{ownedProjectId}` and is
included only when the active session belongs to that same project.
