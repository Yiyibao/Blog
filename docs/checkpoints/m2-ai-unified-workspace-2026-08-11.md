# M2 统一 AI 对话与多模态工作台实施记录

日期：2026-08-11
状态：Internal Alpha / feature flags 默认关闭

## 交付范围

- 严格两栏工作台：左侧项目树与最近会话，右侧连续对话主栏；记忆、附件、产物和任务时间线在主栏内折叠，不再使用第三个 inspector 栏。
- `ai_projects`、会话归档/恢复/移动/软删除，以及会话连续消息查询接口。
- `PROJECT:{id}` 记忆作用域；项目归档不级联删除会话，移动会话会清除旧摘要，避免跨项目污染。
- provider/model 的显式 capability registry：`VISION`、`FILE_INPUT`、`TOOL_CALLING`、`STRUCTURED_OUTPUT`、`REASONING` 等。任务保存 requested/resolved provider、model、reasoning、required capabilities 和 route reason。
- Responses 适配器支持 `input_text`、`image`、`file`、`store=false` 与结构化函数调用；非 Responses 适配器在缺少多模态能力时请求前失败，不降级为纯文本。
- allowlist 工具 `generate_image` / `generate_document`，工具调用、工具结果和 `ARTIFACT_REF` 均持久化；同一稳定 tool-call 已存在可用产物时复用结果。
- PDF、DOCX、XLSX 生成与验证：PDF 嵌入静态 Noto Sans SC，PDFBox 重开校验，POI 重开校验 DOCX/XLSX，并对表格公式注入做文本化处理。
- 受控文件下载继续按 owner、状态、过期时间、大小和 SHA-256 校验；未引入任意路径、shell、URL 抓取或宏执行能力。

## 数据库与契约

新增且未修改历史迁移：

- `backend/src/main/resources/db/migration/V54__extend_ai_workspace_projects_and_provider_capabilities.sql`
- `ai_projects`
- `ai_sessions.project_id/status/archived_at`
- `ai_tasks` requested/resolved routing fields
- `ai_provider_models`

OpenAPI 已由集成测试重新导出到 `frontend/openapi/blog-api.json`，并重新生成 `frontend/src/api/generated.ts`。

## 主要入口

- 后端统一工作台：`backend/src/main/java/com/yubai/blog/ai`
- 项目与会话控制器：`AiProjectController`
- 路由与 capability：`AiModelGateway`、`AiProviderCapabilityRegistry`
- 结构化工具：`AiToolOrchestrator`
- 文档生成：`AiDocumentRenderer`
- 前端工作台：`frontend/src/components/ai/AiWorkspace.vue`
- 前端连续会话状态：`frontend/src/stores/aiTaskStore.ts`

## 验证记录

- 前端：`npm run test` —— 68 个测试文件、814 个测试通过。
- 前端：`npm run test:typecheck` —— 通过。
- 前端：`npm run lint` —— 通过。
- 后端定向：provider capability、migration、Responses function-call、PDF/DOCX/XLSX renderer、结构化三产物集成测试均通过。
- OpenAPI：`AiMultimodalPlatformIntegrationTest#openApiPublishesTheAiPlatformContract` 通过，并包含 projects、project sessions、conversation 路径断言。

## 真实供应商门禁

当前工作区只发现 `.env.example` 与本地 `.env.properties` 文件名，未读取或暴露任何密钥；进程环境未提供可验证的真实 provider 配置。因此 B1（Responses）、B2（文件）、B3（生图）、B4（生成/下载）真实供应商证据仍为 BLOCKED。fake Responses 集成测试已覆盖同等数据库、路由、工具和产物闭环；接入真实凭据后应在隔离账号/目录中补跑四组测试，并保留请求模型、响应状态、artifact 元数据和下载校验摘要，禁止记录密钥或文件原文。
