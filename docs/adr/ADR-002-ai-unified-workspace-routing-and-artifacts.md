# ADR-002: Unified workspace routing and safe artifact generation

- Status: Accepted (Internal Alpha)
- Date: 2026-08-11
- Supersedes: none; extends ADR-001

## Decision

The unified workspace treats a session as the conversation boundary and a task as one turn. A
project is an owner-private grouping of sessions. Project archive is non-cascading, and moving a
session clears its derived summary before the next context window is built.

Provider routing is capability-first. Every task records the requested and resolved provider,
model and reasoning effort, the required capability set and a route reason. A requested text-only
provider may be auto-routed only when another enabled provider/model explicitly declares every
required capability; otherwise the task fails before the upstream request. No model-name or URL
heuristics are used for routing.

Only allowlisted tools can create outputs. Document generation is limited to bounded PDF, DOCX,
XLSX, Markdown, text and structured formats; image generation uses the existing owner-checked
image service. Tool calls/results and artifact references are durable task parts. Downloads are
owner-checked and integrity-verified, and generated Office workbooks reject formula injection.

## Consequences

- The frontend can present one continuous conversation and explain routing decisions without
  exposing provider secrets.
- A provider administrator must maintain explicit model capability metadata when native support
  differs from the provider-type defaults.
- Real provider evidence remains a separate gate from fake-provider integration tests; feature
  flags and production rollout stay closed until that gate is complete.
