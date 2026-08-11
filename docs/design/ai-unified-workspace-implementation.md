# Unified AI Workspace — implementation contract

The reference image `ai-unified-workspace-ui-v2.png` is implemented as a strict two-column layout:

```text
┌──────────────────────┬─────────────────────────────────────────┐
│ projects / sessions  │ title + project/memory pills             │
│ new chat / new project│ provider + model + reasoning (one state) │
│ recent chats          │ continuous messages                     │
│                      │ composer + attachments + artifacts      │
│                      │ memory / timeline details                │
└──────────────────────┴─────────────────────────────────────────┘
```

The right side is the only conversation surface. Detail panels are disclosure sections inside that surface, so the UI does not fork the active context into a third inspector. `AiStore` owns the provider/model/reasoning selection and `AiTaskStore` owns projects, sessions, messages, files, memories, tasks, and artifacts.

## State and routing

1. A session is the UI conversation unit; each submitted prompt is a task/turn.
2. The selected provider and model are persisted in local storage only as a selection preference. The server validates the requested values and persists requested/resolved routing fields on the task.
3. Capability rows are the source of truth. The client shows an unknown-capability state when a model has no explicit row; it does not infer support from the model name or URL.
4. Multimodal and generation requirements are resolved before upstream execution. If no enabled candidate explicitly satisfies the set, the task fails with a client-visible error and a route reason.

## Artifact contract

Generated artifacts are stored under the controlled storage abstraction and downloaded through owner-checked endpoints. PDF output embeds a static open font and is reopened with PDFBox; DOCX/XLSX output is reopened with Apache POI. XLSX formulas are restricted to an allowlist, and cells beginning with `=`, `+`, `-`, or `@` are written as text unless they are explicitly allowed aggregate formulas.
