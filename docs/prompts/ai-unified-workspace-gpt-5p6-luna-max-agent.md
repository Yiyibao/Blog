# GPT-5.6 Luna Max 执行提示词：统一 AI 对话与多模态工作台

## 建议代理配置

- Model：`gpt-5.6-luna`
- Reasoning effort：`max`
- Workspace：当前工作区 `D:\Office\Study\code\BlogDemo`
- 任务性质：端到端实现、测试、视觉验收与证据归档

以下内容整体作为执行代理的初始提示词。接收者按“零上下文”处理，不依赖此前对话。

---

## Task

在 `D:\Office\Study\code\BlogDemo` 中完整实现已审核通过的“统一 AI 对话与多模态工作台”。不要只写计划或 UI 外壳；必须完成前端、后端、数据库迁移、供应商能力路由、项目级记忆、真实多模态输入、对话内图片/文件生成、受控下载、测试、文档和视觉验收。

持续工作到全部验收条件满足。实现期间可运行针对性测试；全部代码完成后，必须先执行“常规完整测试门禁”。常规门禁全部通过后，才能执行后文四组“真实专项测试”。真实专项测试不得用纯 mock、静态断言或只检查 UI 状态冒充通过。

## Context

这是一个 Vue 3 + TypeScript + Pinia 前端、Spring Boot + PostgreSQL 后端的个人网站。AI 功能仅向已认证且具备 `AI_USAGE` 能力的 `ADMIN/PARTNER` 开放。网站不允许注册；普通用户只浏览前台。不要扩大权限范围，不要给匿名或普通前台用户开放 AI。

现有代码已经包含 Internal Alpha 级 AI 平台：

- `ai_sessions`、`ai_tasks`、`ai_task_parts`、`ai_task_events`；
- 受控上传的 `ai_files`；
- 用户确认后生效的 `ai_memories`；
- 可下载的 `ai_artifacts`；
- OpenAI Responses 多模态适配、供应商管理、旧聊天、AI 生图和持久任务；
- Markdown、TXT、JSON、CSV、已有 AI 图片 artifact；
- PDFBox 与 Apache POI 依赖已存在；
- 新平台由 `APP_AI_PLATFORM_*` 与 `VITE_AI_PLATFORM_ENABLED` 控制。

当前 UI 还是工作台式三栏与独立生图入口，核心数据也缺少项目分组、项目级记忆、完整会话视图、对话内工具交付，以及完善的 PDF/DOCX/XLSX 生成闭环。

已审核通过的 UI 基准图：

- `docs/design/ai-unified-workspace-ui-v2.png`

设计基准的关键不变量：

- 两栏布局：左侧 AI 导航，右侧单一聊天面板；不得恢复第三检查器栏；
- 左侧依次包含“新对话”“真实记忆”“项目”“最近聊天”；
- 项目下展示会话；项目首版只实现会话分组和项目级记忆，不实现项目级文件库或项目指令；
- 右上角使用统一的三段式配置控件，依次切换“供应商、模型、推理等级”；
- 底部输入框左侧 `+` 菜单包含“上传图片、上传文件、选择最近文件”；
- AI 生成图片直接显示在聊天中；生成的 PDF/DOCX/XLSX 等以聊天内文件卡片提供鉴权下载；
- 保持现有中文网站设计系统与可访问性，不照搬任何第三方 logo 或商标。

OpenAI 官方参考：

- `https://developers.openai.com/api/docs/models/gpt-5.6-luna`
- `https://developers.openai.com/api/docs/guides/latest-model`

使用 Responses API 承载需要推理、工具调用和多轮上下文的链路。`gpt-5.6-luna` 支持图像输入、函数调用、结构化输出和 `none/low/medium/high/xhigh/max` 推理等级；但网站 harness 不得把这些能力硬编码为只服务某一个模型。

## Current state

工作区不是干净基线。以下文件已有用户未提交修改，必须视为有效起点，逐项阅读并在其上整合；不得 reset、checkout、覆盖或丢弃：

- `backend/src/main/java/com/yubai/blog/ai/AiFileParserRegistry.java`
- `backend/src/main/java/com/yubai/blog/ai/AiPlatformController.java`
- `backend/src/test/java/com/yubai/blog/ai/AiFileParserRegistryTest.java`
- `backend/src/test/java/com/yubai/blog/ai/AiMultimodalPlatformIntegrationTest.java`
- `backend/src/test/java/com/yubai/blog/ai/AiPlatformControllerTest.java`
- `frontend/src/api/ai.ts`
- `frontend/src/components/ai/AiArtifactCard.vue`
- `frontend/src/components/ai/AiAttachmentTray.vue`
- `frontend/src/components/ai/AiMessageList.vue`
- `frontend/src/components/ai/AiSessionSidebar.vue`
- `frontend/src/components/ai/AiTaskComposer.vue`
- `frontend/src/components/ai/AiTaskTimeline.vue`
- `frontend/src/components/ai/AiWorkspace.vue`
- `frontend/src/pages/AdminAiImagesPage.vue`
- `frontend/src/stores/aiTaskStore.ts`
- `frontend/src/test/AiTaskStore.test.ts`

这些修改已经增强文件预览/下载、附件粘贴与拖拽、任务类型、回答导出和 Workspace 视觉。`frontend/src/stores/aiStore.ts` 已开始统一持久化 `providerId + model + reasoningEffort`，并声明供应商页、AI 助手与宠物面板应实时同步。先审查实际 diff 与测试，再继续实现，避免重复或回归。

## Relevant files

- `docs/design/ai-unified-workspace-ui-v2.png` — 已批准的视觉基准。
- `docs/annual-repair-optimization-roadmap-2026-08-10.md` — AI 平台长期架构、安全边界与 M2 方向。
- `docs/adr/ADR-001-ai-multimodal-platform.md` — 现有 AI 平台决策。
- `docs/ai-platform-data-dictionary.md` — V53 数据字典。
- `backend/src/main/resources/db/migration/V53__create_ai_multimodal_platform.sql` — 现有 AI schema；新迁移必须 expand-first，禁止修改历史迁移。
- `backend/src/main/java/com/yubai/blog/ai/**` — 任务、会话、上下文、记忆、附件、artifact、事件和 Responses 适配核心。
- `backend/src/main/java/com/yubai/blog/admin/ai/**` — 供应商、旧聊天和生图服务。
- `backend/src/main/java/com/yubai/blog/admin/AdminAiImageController.java` — 现有 AI 生图入口。
- `backend/src/main/java/com/yubai/blog/config/AiPlatformProperties.java` — 平台功能开关与限制。
- `backend/src/main/java/com/yubai/blog/config/SecurityConfiguration.java` — AI 权限边界。
- `frontend/src/pages/AdminAiPage.vue` — 统一 AI 页面入口。
- `frontend/src/components/ai/**` — 新工作区组件。
- `frontend/src/components/AdminAiChat.vue` — 兼容聊天。
- `frontend/src/components/AdminAiProviders.vue` — 供应商管理和页面级模型切换。
- `frontend/src/components/admin-pet/AdminPetAssistant.vue` — 复用旧聊天的宠物面板。
- `frontend/src/stores/aiStore.ts` — 供应商、模型、推理等级的跨页面事实源。
- `frontend/src/stores/aiTaskStore.ts` — 新会话/任务工作区状态。
- `frontend/src/api/ai.ts`、`frontend/src/api/admin.ts` — AI API。
- `frontend/openapi/blog-api.json`、`frontend/src/api/generated.ts` — 契约与生成类型。

## Decisions

1. AI 只面向 `ADMIN/PARTNER`；不增加注册，不改变普通用户前台权限。
2. 项目首版包含项目 CRUD/归档、会话分组/移动、项目级记忆；不实现项目级文件或项目指令。
3. 项目删除采用安全归档，不级联删除会话、任务、文件、artifact 或记忆。
4. 真实记忆必须以应用数据库为事实源。新增 `PROJECT:{projectId}` 作用域；继续支持全局和会话作用域。
5. 会话是 UI 的基本单位，task 是会话中的一次 turn/执行。最近聊天和项目树按 session 展示，右侧聚合该 session 的完整连续消息，不按单个 task 割裂。
6. 供应商、模型、推理等级必须来自同一状态源，并作为每次任务的显式请求/解析结果持久化；不能只在浏览器里换文案。
7. 供应商/模型能力必须显式声明。禁止根据模型名称字符串猜测 vision、file input、reasoning、tool calling 或 image generation 能力。
8. 文本任务尊重用户手动选择。任务需要图片/文件/工具而当前模型能力不足时，harness 应从已启用且明确声明能力的供应商/模型中自动选择可用候选，并记录 requested 与 resolved 配置，在 UI 中向用户说明自动切换结果。没有合格候选时请求前失败并给出可操作错误，不能静默丢附件或降级成纯文本。
9. 上游模型本身回答质量不足不是网站缺陷；但上传字节、能力选择、请求组装、模型实际接收、响应持久化和 UI 呈现必须完整可证。
10. 生成物必须立即复制/生成到 BlogDemo 自有受控存储，不依赖上游临时 URL。下载时重新鉴权。
11. PDF 是核心能力，必须达到中文报告级质量；DOCX、XLSX 也是本批硬验收项。
12. 保留旧 API 的兼容窗口和 feature-flag 回滚路径。统一入口稳定后，可隐藏独立生图主导航，但不要破坏既有 API。

## Required implementation

### 1. 项目、会话与项目级记忆

- 新增下一可用 Flyway 迁移，不修改 V53 或更早迁移。
- 建议新增 `ai_projects`，至少包含 owner、title、状态/归档时间、排序信息、version、created/updated 时间。
- 为 `ai_sessions` 增加可空 `project_id` 与必要索引/外键；所有服务查询再次按 owner 校验。
- 完成项目创建、列表、重命名、归档，以及会话移动/移出项目的 REST API。
- 完成会话重命名、归档/删除语义、按 session 获取连续 turns/messages 与分页；避免前端加载所有任务后自行 N² 关联。
- 记忆作用域至少支持：`USER`、`PROJECT:{projectId}`、`SESSION:{sessionId}`。项目记忆只注入该项目会话；未归属或其他项目会话不得召回。
- 明确上下文组合顺序和独立 token 预算。项目记忆编辑、禁用、拒绝、遗忘或会话移动项目后，派生摘要/缓存立即失效或重建，不得继续泄露旧上下文。
- 前端“真实记忆”成为一级视图，支持按全局/项目/会话和状态筛选、创建、确认、编辑、启停、拒绝、遗忘。

### 2. 供应商、模型、推理等级统一与能力注册

- 审查现有 `aiStore`，建立唯一的前端选择状态，供应商页、统一工作台和宠物聊天共享且实时同步。
- 右上角按 V2 设计实现一个三段式统一控件：供应商、模型、推理等级；提供键盘操作、焦点样式、禁用/加载/错误状态和移动端退化方案。
- 后端 task/session 请求与实体持久化 requested provider/model/reasoning 和 resolved provider/model/reasoning；历史记录可追溯。
- 推理等级至少覆盖 `none`、`low`、`medium`、`high`、`xhigh`、`max`。对不支持的供应商/模型应禁用或显示“不支持”，不得发送无效参数。
- 在供应商管理模块维护明确的 provider/model capability metadata，至少覆盖：`TEXT`、`VISION`、`FILE_INPUT`、`REASONING`、`TOOL_CALLING`、`IMAGE_GENERATION`。设计可扩展 schema/API，不以模型名猜测。
- 供应商被停用、删除、默认模型变化或模型列表变化后，统一工作台应无刷新同步并安全回退；不得继续发送陈旧 providerId/model。
- 各 provider adapter 按其真实协议映射推理参数。若协议不支持，明确声明 capability=false；不要伪造兼容。

### 3. 统一两栏 UI 与连续聊天

- 严格以 `docs/design/ai-unified-workspace-ui-v2.png` 为信息架构与视觉基准，在现有 token/component 上实现，不做无关重设计。
- 左栏实现新对话、真实记忆、项目树、新建/重命名/归档项目、项目内会话、最近聊天。
- 右栏实现会话标题、项目/记忆状态、统一 AI 配置控件、连续消息流和底部固定 composer。
- `+` 菜单支持上传图片、上传文件、选择最近文件；保留拖拽和剪贴板粘贴。待发送附件以 draft chip 显示，发送后固化到用户消息。
- 富消息渲染至少支持 Markdown 文本、用户附件、工具状态、内联图片、artifact 文件卡片、失败/取消/重试和记忆建议确认。
- 长会话做分页/增量加载；消息追加、任务运行、取消和刷新恢复不丢状态。
- 桌面、窄屏和移动端均可用；移动端左栏改抽屉。完成可访问性语义、键盘、焦点、对比度和 reduced-motion。

### 4. 多模态 harness 与自动能力路由

- 保持文件只通过用户主动上传的 bytes 进入；继续校验 MIME、magic bytes、大小、页数、OOXML ZIP 安全、配额、owner、SHA-256 和生命周期。
- 发送前从 task parts 计算 required capabilities：图片要求 `VISION`，文档要求 `FILE_INPUT`，对话内生成要求相应工具能力。
- 当前手选模型满足能力时必须使用手选配置；不满足时按明确、可测试、确定性的策略选择另一个已启用候选。记录并展示自动路由原因。
- 图片实际进入 provider 的 image input；PDF/DOCX/XLSX/TXT/Markdown/CSV/JSON 按 provider 支持使用原始 file input 或受控提取文本，不能丢失引用或静默改成没有附件的请求。
- 真实 Responses adapter 必须处理 `input_text`、`input_image`、`input_file` 以及后续工具调用。保持 `store=false` 或现有隐私策略。
- 若 provider/model 无能力且没有候选，HTTP 外呼前失败；错误指出缺少的 capability 与可修复方式。

### 5. 对话内图片和文件生成工具

- 建立 allowlist 工具编排层，而不是用脆弱关键词解析。至少支持 `generate_image` 与受控文档/工作簿生成工具。
- 模型工具调用、工具结果和最终 artifact 引用持久化为结构化 task parts/events，包含幂等键；重试不能重复生成同一逻辑文件。
- `generate_image` 复用现有 `AiImageService`，将结果登记为当前 task 的 artifact，在同一 assistant message 中内联预览并提供下载。
- 文档工具必须支持自然语言请求后直接生成并回传：
  - PDF（核心）；
  - DOCX；
  - XLSX；
  - 保留已有 Markdown/TXT/JSON/CSV。
- 不要求用户在旁边手工选择格式。模型通过结构化工具参数给出文件名、文档结构和内容；服务端渲染器负责安全生成。

PDF 最低质量标准：

- 内嵌可再分发的开源中文字体，禁止依赖服务器偶然安装的字体；记录字体许可证与来源；
- 标题层级、段落、粗体/斜体、列表、表格、代码块、图片；
- 中文自动换行、长单词/URL 处理、分页、孤行控制、表头跨页、页码、页眉页脚与生成时间；
- 图片尺寸与像素限制，超大内容有明确上限；
- 生成后用 PDFBox 重新打开，检查 magic bytes、页数、可提取文本、元数据、大小和 SHA-256；失败不得标记 READY。

DOCX 最低质量标准：

- 使用 Apache POI 生成无宏 OOXML；
- 支持标题、段落、列表、表格、图片、页眉页脚；
- 生成后重新打开并验证核心段落/表格/图片关系。

XLSX 最低质量标准：

- 使用 Apache POI；支持多个 sheet、表头、基本格式、列宽、冻结窗格和必要公式；
- 防止外部链接、宏、危险公式/CSV 注入式内容；
- 生成后重新打开，验证 sheet、单元格、类型和公式；
- 大数据量使用受限的流式或有界策略。

所有 artifact：

- 写入应用受控存储，记录 owner/task/name/mediaType/size/SHA/status/expiry；
- assistant 消息中追加 `ARTIFACT_REF`；图片同时可预览；
- 下载强制 owner 鉴权、`Content-Disposition: attachment`、`private, no-store`、`nosniff`；
- 文件卡展示名称、格式、大小、状态、下载/删除；刷新后仍能恢复。

### 6. 兼容与收尾

- `AdminAiChat`、宠物助手和供应商页不得维持三套互相冲突的选择状态；复用统一 store/API。
- 保留兼容 API 与安全回滚开关。必要时让旧入口映射到统一会话，而不是复制实现。
- 更新 OpenAPI 快照与 TypeScript 生成类型。
- 更新 ADR、架构文档、数据字典、环境变量示例和本次 checkpoint。
- 添加必要观测：task 路由 requested/resolved、artifact 失败、memory confirm/reject、文件字节和工具失败指标；日志不得包含 key、token、私有正文、storageKey 或记忆正文。

## What was tried

- M1 已完成持久 task/file/memory/artifact、安全上传、owner 隔离和假 provider 多模态集成测试。
- 现有 UI 已做一轮未提交增强，但仍是三栏工作台并跳转独立生图页。
- 现有 `AiArtifactService` 只生成 Markdown/TXT/JSON/CSV 或复制已有图片，没有 PDF/DOCX/XLSX 渲染器。
- 现有 provider capability 主要按 provider 类型声明，尚不足以完成每个模型的显式能力选择与自动多模态路由。
- 不采用关键字判断“生成 PDF/图片”，不采用模型名猜能力，不采用上游临时下载链接。

## Implementation workflow

1. 先阅读仓库说明、当前 git diff、设计图、AI ADR、数据字典、M1 checkpoint、前后端测试和现有 provider adapters。
2. 输出一个简短执行更新后直接实施；不要停在计划阶段。
3. 先建立/更新数据库与领域契约，再完成 API/store/UI，再完成工具渲染和兼容迁移。
4. 每个切片运行最相关的针对性测试，尽早发现迁移、权限和契约问题。
5. 渲染并实际浏览 UI，对照 V2 设计图检查布局、间距、溢出、响应式和交互状态；有偏差就修正。
6. 全部实现完成后，严格按下文顺序跑最终验证。

## Final validation order

### A. 常规完整测试门禁（必须先全部通过）

后端：

- 在 `backend` 运行 `mvn clean verify`；若系统 PATH 没有 Maven，使用 `C:\Program Files\apache-maven-3.9.16\bin\mvn.cmd`。
- 确认 Flyway fresh V1→最新与上一版本→最新升级路径通过。
- 确认 Spotless、JaCoCo 门禁、全部单元/集成测试和 JAR 构建通过。

前端：

- 在 `frontend` 运行 `npm run lint`；
- `npm run test:coverage`；
- `npm run test:typecheck`；
- `npm run build`；
- `npm run format:check`；
- `npm run api:types:check`；
- `npm run test:e2e`。

仓库：

- `git diff --check`；
- 审查 `git status --short` 和完整 diff，确认没有覆盖用户原有修改、没有 secret、日志、临时数据库或大体积无关文件。

如果任一常规门禁失败，先修复并从受影响层重新跑，常规门禁未全部通过前不得开始真实专项测试。

### B. 真实专项测试（常规门禁通过后执行）

真实测试使用本机已有的安全配置和已启用供应商，严格限制调用次数和输出规模。允许为验收进行必要的真实模型请求，但不得打印、截图或写入 API key/token。不得访问生产数据库或执行生产部署；使用隔离本地/测试数据库、独立端口和可清理的测试资源。

#### B1. 供应商、模型、推理等级真实切换与同步

- 至少选择两个实际可用的供应商配置，或同一供应商下两个实际模型；每组使用不同的推理等级，其中一组必须为 `max`（前提是该 provider/model 明确支持）。
- 从统一工作台实际切换供应商、模型、推理等级并发送带唯一 nonce 的最小文本请求。
- 证明浏览器请求、后端 task persisted requested 配置、adapter 发出的真实配置、resolved 配置和实际响应一致。
- 打开供应商管理页面，证明当前选择与工作台实时同步；从供应商页修改后返回工作台，也应立即同步。
- 停用或切换默认供应商后验证安全回退和清晰提示。
- 对不支持 reasoning 的配置，UI 必须禁用/说明，后端不得伪造已发送。

#### B2. 项目级真实记忆

- 创建项目 A、项目 B 和各自会话。
- 在项目 A 创建并确认一条含唯一 nonce 的项目记忆，例如只有项目 A 应知道的稳定偏好。
- 在项目 A 新会话发起不重复 nonce 的询问，真实模型应从注入上下文中正确使用该项目记忆。
- 在项目 B 与无项目会话发送同类询问，确认不会召回项目 A 记忆。
- 禁用或遗忘该记忆后再次验证项目 A 召回为零；将会话从 A 移到 B 后不得继续使用 A 的记忆。
- 保留服务端上下文/测试证据，但归档时只保存脱敏 metadata、nonce 和结果摘要，不保存真实私人记忆正文。

#### B3. 文件与图片真实多模态处理及 harness 自动路由

- 制作两个小型确定性测试 fixture：
  - 一张包含独特视觉元素或短 nonce 的 PNG；
  - 一个包含另一独特 nonce 与可提问事实的 PDF/DOCX/TXT（至少一个文档类型，最好覆盖 PDF 和 DOCX）。
- 在工作台通过 `+` 菜单实际上传，确认文件 bytes 通过服务端校验、存储、引用并加入 task parts。
- 若当前手选模型缺少 VISION/FILE_INPUT，验证 harness 自动选择显式声明支持所需能力的模型，并在 UI 显示 requested→resolved 变化；这不是测试某个模型是否万能，而是测试网站能否正确路由。
- 向真实支持模型提问，回答必须体现 fixture 中的独特视觉/文本事实，证明模型实际收到并处理，而不是只验证 HTTP 200。
- 在可控 fake provider 集成测试中继续断言实际 wire payload 含 `input_image`/`input_file`；真实测试与 fake wire 证据共同组成通过条件。
- 当没有任何合格模型时验证 HTTP 外呼前明确失败，附件不得静默丢失。

#### B4. 对话内真实生成 PDF、DOCX、XLSX

- 在同一真实对话中自然语言要求 AI 基于一组小型结构化数据生成：
  - 一份中文 PDF 报告；
  - 一份 Word `.docx`；
  - 一份 Excel `.xlsx`。
- 必须由工具调用/结构化生成链路完成，并在聊天内出现三个可下载 artifact 卡片；不得通过手工后台表单补造。
- 通过 UI 实际下载三个文件，再分别用 PDFBox/Apache POI 打开并验证：
  - PDF：magic bytes、页数、中文关键文本、表格/图片或结构、页码、字体可用性；
  - DOCX：关键段落、标题、表格/图片关系；
  - XLSX：sheet 名、关键单元格、数据类型、格式和安全公式。
- 刷新页面后三个 artifact 仍在相同 assistant turn 中可恢复和下载。
- 验证错误 owner 无法下载；删除/过期后不能继续读取；metadata size/SHA 与实际 bytes 一致。

## Acceptance criteria

- [ ] V2 两栏 UI 落地，桌面/移动端、键盘和可访问性通过。
- [ ] 左栏支持新对话、真实记忆、项目分组和最近会话。
- [ ] 会话显示完整连续消息，不再按单 task 割裂。
- [ ] 项目 CRUD/归档、会话移动和项目级记忆端到端可用。
- [ ] 供应商、模型、推理等级统一切换、持久化、协议映射并与供应商模块实时同步。
- [ ] provider/model capabilities 显式配置，harness 能按任务需要自动选择真正支持多模态/工具的模型。
- [ ] 图片和文件真实进入模型请求并能被实际处理；无能力候选时请求前失败。
- [ ] AI 生图在聊天内直接展示并作为受控 artifact 持久化。
- [ ] PDF、DOCX、XLSX 可由自然语言在对话内真实生成、校验、恢复和鉴权下载。
- [ ] PDF 达到完善中文报告质量，不依赖系统偶然字体。
- [ ] 所有新资源保持 owner 隔离、幂等、生命周期、审计和删除语义。
- [ ] OpenAPI、生成类型、配置示例、ADR、数据字典和 checkpoint 同步。
- [ ] 常规完整测试门禁全部通过。
- [ ] B1–B4 真实专项测试全部完成并有脱敏证据；不能执行的项必须明确标记 BLOCKED，禁止伪报 PASS。

## Constraints

- 不得执行 `git reset --hard`、`git checkout --` 或任何会丢失当前修改的操作。
- 不得删除、清理、覆盖用户现有未提交文件；遇到重叠必须逐段合并。
- 不提交、不 push、不部署生产，除非用户另行明确授权。
- 不修改历史 Flyway 文件；新 schema 使用下一可用版本，expand-first。
- 不读取或输出密钥/token；真实测试报告只保留脱敏证据。
- 不使用生产数据库做测试，不进行破坏性数据操作。
- 不接收浏览器本地路径、服务端路径、storageKey 或任意 URL 抓取。
- 不执行模型生成的任意 shell、SQL 或宏；文件生成只走 allowlist 渲染器。
- 不用模型名字符串猜能力；能力必须来自显式注册与管理数据。
- 不把不支持多模态的单个模型判为平台失败；平台通过条件是正确能力发现、选择、传输和失败行为。
- 不静默降级、丢附件、伪造 reasoning、伪造工具成功或伪造真实测试。
- 不扩大 AI 到普通用户或匿名访客。
- 保持旧聊天/生图 API 的兼容与 feature-flag 回滚能力。

## Evidence and final output

把真实专项测试的脱敏证据保存到版本化但不含秘密的目录，例如：

- `outputs/ai-unified-workspace-acceptance/<timestamp>/`

至少包含：

- `README.md`：环境、测试步骤、PASS/FAIL/BLOCKED 矩阵；
- 关键 UI 截图；
- requested/resolved provider/model/reasoning 的脱敏记录；
- project memory 隔离结果摘要；
- 多模态 fixture 的 SHA-256 与模型结果摘要；
- PDF/DOCX/XLSX 下载样本或其受控测试副本、SHA-256 和解析校验结果；
- 常规完整测试的命令、退出码、测试数与覆盖率摘要。

最终答复必须先给结论，然后列出：

1. 完成的产品能力；
2. 数据库迁移和关键架构；
3. 修改文件概览；
4. 常规完整测试精确结果；
5. B1–B4 真实专项测试的逐项结果与证据路径；
6. 视觉验收结果；
7. 剩余风险或真实 blocker；
8. `git status --short` 与 `git diff --stat` 摘要。

只有实现、常规门禁、真实专项验证和证据归档都完成后，才算任务结束。
