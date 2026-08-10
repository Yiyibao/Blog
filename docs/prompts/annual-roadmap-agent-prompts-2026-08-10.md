# BlogDemo AI-first 年度路线图 Agent 执行提示词

> 对应计划：docs/annual-repair-optimization-roadmap-2026-08-10.md
>
> 使用方式：严格按 M1 → M12 执行。每月提示词应交给一个主执行 Agent，由它按 A/B/C/D 批次逐批完成和验证；不得把整月改动压成一个不可审查的大 diff。
>
> 默认授权：只修改本地仓库，不提交、不推送、不部署、不写生产数据库、不调用真实付费 AI。生产、外部账号、真实 provider smoke 和部署动作必须另行获得明确授权。

## 1. 全体任务通用协议

每个执行 Agent 都必须遵守：

1. 第一件事运行 git status --short --branch、git diff --stat，并阅读年度路线图、本文通用协议、上一月 checkpoint、README.md 和 docs/architecture.md。
2. 当前工作树包含大量用户和其他 Agent 的未提交修改。禁止 git reset、git checkout --、删除未知文件、覆盖用户修改、清理非本批次文件或全仓无关格式化。
3. 开始前列出本批次允许修改的目录/文件。遇到重叠改动先读 diff、保留现有意图；无法安全合并时停止该批并报告，不得擅自还原。
4. 动态扫描 backend/src/main/resources/db/migration 和目标测试库 flyway_schema_history。当前工作树观察到最高 V52，候选下一号为 V53，但必须以执行时事实为准。禁止修改任何既有迁移；新迁移使用 next available 版本。发现迁移号冲突时停止迁移批次，不抢号、不改历史。
5. schema 变化遵循 expand → backfill → contract，并至少保留一个代码版本的兼容窗口。每个新迁移都要有 fresh 和 upgrade 测试；不能等到 M5 才补 M1–M3 的迁移安全。
6. 每次只完成一个可回滚主题。缺陷先写复现测试；重构先写契约测试。一个批次失败时不要继续后续批次，也不要用“月底目标”掩盖部分完成。
7. 不降低覆盖率、构建预算、安全规则、权限或断言来让 CI 通过；不删除失败测试；不把 timeout、未运行或网络跳过写成通过。
8. 自动化测试只使用 deterministic fake HTTP provider 和隔离数据库/存储。fake 必须穿过真实 JSON、multipart、HTTP、SSE 和文件序列化边界；只 mock service 返回值不能作为 AI 月份退出证据。
9. 测试期间默认禁止真实 AI、网页抓取、yt-dlp 和付费 API 外呼。可选真实 smoke 必须先获授权、使用非生产数据，并且不能成为 CI 硬门禁。
10. 所有新 API 同步 OpenAPI、前端类型和契约测试。旧 API 要么兼容，要么提供明确迁移窗口；不能悄悄改变错误码、事件名或权限。
11. 所有生产可见新能力置于独立 feature flag 下，默认生产关闭；关闭后旧文本聊天和既有业务必须继续可用。
12. 每批运行定向测试；月末运行全量门禁。执行过的命令、真实测试数量、覆盖率、失败信息、未执行项和外部网络情况必须如实记录。
13. 在 docs/checkpoints/ 新增当月 checkpoint，包含入口门、范围、修改文件、迁移、验证、指标、feature flag、兼容/回滚、未完成项、退出条件和建议提交切片。
14. 不创建 commit，等待用户验收。最终回复使用本文末尾模板，并明确“下一月允许启动：YES/NO”。

## 2. AI 任务永久安全不变量

M1–M3 以及后续任何触及 AI 的月份，都必须遵守以下不变量。

### 2.1 文件

- AI API 只接受 fileId/artifactId，不接受 file://、绝对路径、目录路径、storageKey、shell 命令或任意服务器路径。
- 浏览器只上传用户主动选择的字节；网站 AI 不扫描、读取、写入或删除用户电脑上的其他文件。
- MIME、扩展名、magic bytes、解码结果、大小和结构必须同时校验；不一致时拒绝或隔离。
- 文件记录必须有 owner、SHA-256、size、mediaType、status、retention/expiresAt 和引用。
- 每次模型处理、预览、下载和删除前重新鉴权，禁止只在列表查询时校验 owner。
- 首版拒绝可执行文件、脚本、任意压缩包、宏文档、加密文档和主动内容。
- 文件正文、私有原文件名、storageKey、本机路径、key 和 token 不进入普通日志、trace 或 checkpoint。
- 删除必须同步处理任务引用、临时 provider 文件、知识索引、缓存和派生副本。

### 2.2 真实记忆

- provider conversation ID、previous response ID、responses-store、localStorage/sessionStorage 和聊天历史都不等于真实记忆。
- 应用数据库是记忆事实源；用户能查看、创建、确认、编辑、禁用和删除。
- 长期记忆默认 PROPOSED，只有用户确认后才 ACTIVE；上传文件中的指令不能自动变成记忆。
- 密码、token、健康、身份等敏感属性默认不保存；任何例外都需要更高等级的显式确认和审计。
- 删除或“忘记”必须移除正文、embedding、索引、摘要缓存和派生副本；后续召回必须为零。
- 每次召回重新校验 owner、scope 和源对象权限。

### 2.3 Artifact

- provider 临时容器或临时链接不是持久下载方案；任务结束前必须立即转存到应用受控存储。
- artifact 必须有 owner、task、name、MIME、size、SHA-256、status、expiresAt 和审计。
- 下载使用登录态鉴权或短期一次性票据，不暴露磁盘路径，不提供永久公共裸链接。
- 响应使用安全 Content-Disposition、nosniff、private/no-store；主动内容只能 attachment 下载。
- CSV 生成要防公式注入；HTML/SVG 不能在同源后台直接执行。
- 幂等重试不能生成多份逻辑产物；数据库/存储部分失败由补偿或 reconciler 收口。

### 2.4 工具与审批

- 工具必须 allowlist，使用严格 schema 并拒绝未知字段；限制循环次数、调用次数、输入和输出大小。
- 只读工具每次重新鉴权；写工具只能产生 proposal/diff。
- 审批绑定 taskId + userId + toolName + argsHash + targetVersion + expiresAt + nonce。
- 审批执行前再次鉴权和检查目标版本；过期、重放、参数变化和版本冲突全部拒绝。
- 最终写入只调用现有领域 service；模型不能直连 repository、数据库或发布流程。
- 自动发布、物理删除、权限修改、凭据操作和任意网络/文件系统工具始终禁止。

## 3. AI 测试最低矩阵

M1–M3 的 deterministic fake provider 和 E2E 至少覆盖：

- PNG/JPEG/WebP 正常、伪造扩展、错误 magic、像素炸弹；
- PDF 正常、含页面图片、页数超限、损坏文件；
- DOCX 正常、宏/错误容器、ZIP entry 或解压总量超限；
- TXT/Markdown/CSV/JSON 正常、超大、截断、深度/行列超限；
- provider 缺少 VISION、FILE_INPUT、FUNCTION_CALLING 或 ARTIFACT 能力；
- SSE 正常分片、半个 JSON、重复终态、断流、重连和 Last-Event-ID；
- 429、5xx、连接超时、读取超时、超大响应、队列满和预算拒绝；
- 合法/非法 tool schema、未知字段、调用循环上限；
- memory proposal、确认、编辑、禁用、删除、重复/冲突和跨用户访问；
- artifact 生成、转存、下载、过期、重复完成、取消竞态和孤儿修复；
- prompt injection 试图伪造审批、保存敏感记忆、读取本地路径或扩大工具权限；
- 用户 A 的 file/task/memory/artifact/context/proposal 对用户 B 全部拒绝。

## 4. M1：AI 多模态、真实记忆与生成物闭环 v1

```text
你是 BlogDemo M1 AI 平台主执行 Agent。仓库为 D:\Office\Study\code\BlogDemo。目标是在第一个四周窗口内交付“图片/文件上传与处理 + 真实记忆 + 文件/图片生成 + 受控下载”的完整 Internal Alpha；不能只搭接口或写设计文档。

授权边界：
- 只改本地仓库，不 commit、push、deploy，不接触生产数据库，不调用真实 AI。
- 网站 AI 不操控客户端本地文件或任意服务器路径。
- 遵守本文“全体任务通用协议”“AI 任务永久安全不变量”和“AI 测试最低矩阵”。

入口门与事实核验：
1. 运行 git status --short --branch、git diff --stat，建立本批次文件白名单和工作树归属台账。
2. 动态扫描全部 Flyway 文件。当前观察最高 V52 且 V48–V52 为未跟踪内容；先核对它们和测试库 schema history，再选择 next available，禁止写死或抢占 V53。
3. 阅读：
   - docs/annual-repair-optimization-roadmap-2026-08-10.md
   - docs/checkpoints/execution-progress-2026-08-09.md
   - backend/src/main/java/com/yubai/blog/admin/AdminAiController.java
   - backend/src/main/java/com/yubai/blog/admin/AdminAiChatHistoryController.java
   - backend/src/main/java/com/yubai/blog/admin/AdminAiImageController.java
   - backend/src/main/java/com/yubai/blog/admin/ai/AiChatService.java
   - backend/src/main/java/com/yubai/blog/admin/ai/ChatMessage.java
   - backend/src/main/java/com/yubai/blog/admin/ai/ChatHistoryService.java
   - backend/src/main/java/com/yubai/blog/admin/ai/OpenAiResponsesClient.java
   - backend/src/main/java/com/yubai/blog/admin/ai/AiProviderService.java
   - backend/src/main/java/com/yubai/blog/admin/ai/AiUsageService.java
   - backend/src/main/java/com/yubai/blog/admin/ai/AiImageService.java
   - backend/src/main/java/com/yubai/blog/storage/StorageService.java
   - backend/src/main/java/com/yubai/blog/storage/LocalFileStorage.java
   - backend/src/main/java/com/yubai/blog/note/NoteAttachmentService.java
   - backend/src/main/resources/db/migration/V46__create_ai_chat_sessions.sql
   - backend/src/main/resources/db/migration/V47__create_ai_image_sessions.sql
   - frontend/src/components/AdminAiChat.vue
   - frontend/src/stores/aiStore.ts
   - frontend/src/api/admin.ts
   - 相关 AI/storage/auth 测试。

成功定义：
- 上传一张图片和至少 PDF、DOCX、TXT/Markdown、CSV 中各一种文件，模型请求真实包含对应 parts；
- 用户确认的长期记忆在新会话中生效，并能查看、编辑、禁用和删除；
- 能生成 Markdown/TXT/JSON/CSV 和现有 AI 图片 artifact，并受控下载；
- 任务、事件、文件引用、记忆和 artifact 在重启后保持一致；
- 旧文本聊天继续工作。

按以下批次顺序执行，前一批测试失败不得进入下一批。

批次 A：边界、迁移和任务内核
1. 写 ADR：网站级 AI、受控文件、真实记忆、artifact、人工审批、无本地文件操控。
2. 新增生产默认关闭的 task/multimodal/memory/artifact feature flags。
3. 新增 expand-only 表或等价模型：ai_sessions、ai_tasks、ai_task_parts、ai_task_events。
4. 实现 QUEUED/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/CANCELLED 状态机、终态 CAS、幂等键、取消和重启恢复。
5. 实现持久 sequence 事件与 afterSequence/Last-Event-ID SSE；保留 heartbeat 和断开取消。
6. 新增网站级 /api/v1/ai/tasks 契约。旧 /api/v1/admin/ai/chat 和 V46 数据保持兼容，不删除、不改历史。
7. 新增每用户、全局和供应商维度的有界并发/队列；队列满返回 429 + Retry-After。
8. 结构化 part 至少支持 TEXT、IMAGE_REF、FILE_REF、ARTIFACT_REF、TOOL_CALL、TOOL_RESULT、SOURCE_REF。

批次 B：安全文件与多模态 provider
1. 新增 AiFileService/ParserRegistry 和 ai_files，复用 StorageService 安全根目录，但不要把绑定 note 的 NoteAttachmentService 直接冒充通用文件服务。
2. 校验 owner、文件数、大小、总量、MIME、magic、图像像素、PDF 页数、DOCX 解压 entry/总量、文本字符和 CSV 行列。
3. 只通过 fileId 访问；禁止路径输入。文件字节处理必须有硬上限，不得出现无界 readAllBytes。
4. 新增显式 provider/model 能力矩阵：TEXT、VISION、FILE_INPUT、FUNCTION_CALLING、STRUCTURED_OUTPUT、CODE_INTERPRETER、FILE_SEARCH、IMAGE_GENERATION、STATEFUL_CONVERSATION。默认未声明即不支持。
5. 建立结构化 AiModelRequest/AiModelResponse；先实现 OpenAI Responses 和 fake HTTP provider 的 image/file parts。其他 provider 能力不足时提前返回明确错误，禁止静默丢附件。
6. provider 临时 file ID 有 task 引用、取消和过期清理；私有字节和 ID 不进日志。

批次 C：真实记忆
1. 新增 ai_memories 和 AiMemoryService；最少支持会话摘要、PROPOSED 长期记忆和 ACTIVE 长期记忆。
2. 长期记忆只能由用户创建或确认；模型不能静默写入。
3. 提供列表、详情、创建、确认、编辑、启用/禁用、删除 API 和 owner/scope/source/version/expiry。
4. 上下文按系统策略→当前页面对象→ACTIVE 记忆→知识→摘要→最近消息顺序组装，并给每层独立 token 预算。
5. 删除/禁用后立即从新会话召回中消失；删除正文、派生摘要和缓存。
6. compact/pet 不再以 sessionStorage 作为事实源。

批次 D：artifact 与下载
1. 新增 ai_artifacts、AiArtifactService 和状态 PENDING/READY/FAILED/EXPIRED/DELETED。
2. 生成 Markdown、TXT、JSON、CSV；CSV 防公式注入。
3. 复用 AiImageService，但将生成图片统一登记为 artifact。
4. 上游临时产物在任务完成前复制到应用存储，记录 SHA-256、size、MIME 和 expiresAt。
5. 实现鉴权下载、过期清理、原子发布、失败补偿和孤儿 reconciler。
6. 修复并测试现有 AI 图片 content/delete 只按 publicId 查询、未重新校验 owner 的 IDOR 风险；审查 V47 session_id 无外键和图片写存储后 DB 失败的孤儿窗口，做最小兼容修复或登记到 M5，不能忽略。

批次 E：前端 Workspace 与完整 E2E
1. 将 AdminAiChat.vue 降为兼容壳，新增或等价拆分：
   AiWorkspace、AiTaskComposer、AiAttachmentTray、AiMessageList、
   AiTaskTimeline、AiArtifactCard、AiMemoryPanel、AiSessionSidebar。
2. 新增独立 frontend/src/api/ai.ts 和 aiTaskStore；不要继续扩大 frontend/src/api/admin.ts。
3. 支持上传、校验状态、取消、重试、SSE 续传、记忆确认、artifact 下载和错误可访问性。
4. 使用真实测试 PostgreSQL、真实应用存储、真实前后端和 deterministic fake HTTP provider 完成 E2E。

定向测试至少包含：
- AiTask 状态/幂等/取消/恢复/事件序列；
- AiFile 正常与恶意格式、owner 隔离、清理；
- OpenAI Responses 实际 image/file JSON；
- memory proposal/确认/跨会话/禁用/删除/跨用户；
- artifact 原子发布/下载/过期/重复/孤儿；
- AI 图片 IDOR；
- SSE 断流续传、429、预算、provider 能力不足；
- 旧聊天与旧历史兼容；
- 前端上传托盘、记忆面板、artifact 卡和完整 E2E。

月末运行：
- backend 定向测试后 mvn verify、Flyway fresh/upgrade 测试和 JaCoCo；
- frontend lint、format:check、test:typecheck、test:coverage、build、offline smoke 和新的 AI online E2E；
- OpenAPI/type generation、git diff --check、git status --short、git diff --stat；
- 证明测试期间没有真实外部模型请求。

二元退出条件：
- 图片与文档 parts 确实进入 fake provider；
- capability 不匹配明确失败；
- ACTIVE 记忆跨会话可用，禁用/删除后召回为零；
- 用户 A 无法访问用户 B 的任何 AI 资源；
- 至少文本、结构化数据和图片三类 artifact 可下载；
- 重启与 SSE 重连不丢状态；
- 无不可解释孤儿文件；
- 旧文本聊天无回归；
- 未通过任何一项时 checkpoint 标记 M1 未完成，M2 不得启动。

新增 docs/checkpoints/m1-ai-multimodal-memory-artifacts-YYYY-MM-DD.md。
```

## 5. M2：全站 Agent、领域工具与富文件

```text
你是 BlogDemo M2 全站 Agent 主执行 Agent。只有 M1 checkpoint 的全部二元退出条件为 YES 才能开始。只改本地仓库，不部署、不调用真实付费 AI。

先阅读通用协议、AI 永久安全不变量、年度路线图、M1 checkpoint，以及 M1 新增的 ai 领域、PostService/PostWorkflowService/PostRevisionService、NoteService、DishService/DishImportService、SearchService、AdminDashboard、NotesWorkspace、AiActionChips、权限与版本测试。

目标：聊天成为一个入口；文章、笔记、菜谱、媒体和后台至少五个页面都能使用同一任务/记忆/artifact 能力。所有写操作只能 proposal→diff→approval→现有领域 service。

批次 A：上下文协议
1. 建立 AiContextRef(type,id,version,anchor/selection)；客户端只传引用，服务端重新读取、鉴权并记录版本快照。
2. 建立 AiContextAssembler 的独立 token 预算和 source refs。
3. 为文章、笔记、菜谱、媒体、后台增加统一 AiContextActions；页面入口与 Workspace 共用 API。

批次 B：只读工具
1. 建立 AiToolRegistry 和严格 schema，拒绝未知字段。
2. 首批只读工具控制在 6–10 个：读取文章/笔记/菜谱/媒体、站内搜索、后台摘要等。
3. 每次调用重新鉴权，限制工具循环、调用数、结果字节和时间。
4. 上传正文始终视为不可信材料，不能改变系统策略或工具权限。

批次 C：变更提案与审批
1. 新增 ai_action_proposals/AiApprovalService。
2. 写工具仅生成 proposal 和可视 diff，不直接保存。
3. 审批绑定 taskId/userId/toolName/argsHash/targetVersion/expiresAt/nonce。
4. 执行前重新鉴权和检查版本，只调用现有业务 service。
5. 测试过期、重放、参数篡改、版本冲突、权限撤销和幂等。
6. publish、delete、权限和凭据工具不注册。

批次 D：富文件
1. 在明确格式矩阵下增加 PPTX/XLSX 解析和 slide/sheet/row/column 锚点。
2. 根据依赖/安全/许可证 ADR，用应用自有 renderer 或隔离生成器增加 PDF、DOCX、XLSX artifact。
3. 不实现“任意格式转换”；失败必须明确且不留下临时字节。

批次 E：验证
1. fake provider 完整记录 context、tool call、tool result 和 proposal。
2. 完成五个页面的组件/契约测试和 E2E。
3. 验证批准前数据库不变，批准后审计/版本正确。

月末运行定向测试、后端 mvn verify、前端全量门禁、OpenAPI/type check、AI online E2E 和 git diff --check。新增 docs/checkpoints/m2-site-agent-tools-YYYY-MM-DD.md。

退出条件：
- 至少五个业务页面可直接调起同一 AI 平台；
- 所有上下文均服务端鉴权且有版本/source ref；
- 未审批、过期、重放和版本冲突均不能写入；
- 所有成功写入只经现有领域 service；
- 富文件来源锚点和下载可用；
- 任一失败时 M3 不得启动。
```

## 6. M3：知识检索、可靠性、评测与 Release Candidate

```text
你是 BlogDemo M3 AI 可信度与可靠性主执行 Agent。只有 M2 checkpoint 全部退出条件为 YES 才开始。只使用隔离数据库/存储与 deterministic fake provider。

先阅读年度路线图、M1/M2 checkpoint、全部新 AI task/file/memory/artifact/tool/context 代码、AiUsageService、AiCallReliabilityPolicy、AiStreamExecutorConfig、搜索实现、权限实现和日志脱敏测试。

目标：让 M1–M2 的全站 Agent 成为可恢复、可删除、可评测、可观测的 Release Candidate。

批次 A：权限安全知识检索
1. 新增 ai_knowledge_documents/chunks 或等价模型。
2. 文章、笔记、菜谱和用户显式纳入的文件增量索引，保留 sourceType/id/version/anchor/checksum。
3. 检索前过滤 owner、visibility 和实时源权限；删除或撤权立即失效。
4. 第一版优先 PostgreSQL 结构化/全文/trigram；只有固定评测证明不足才引入 embedding/pgvector。
5. 删除源、memory 或 file 时删除派生索引和缓存。

批次 B：Prompt 与 eval
1. 建立 Prompt Registry：templateId、version、purpose、inputSchema、modelConfig、owner、active。
2. 每次运行记录 provider/model/capability snapshot/templateVersion/token/cost/latency/status。
3. 建立固定 eval：图片理解、文件引用、记忆召回/遗忘、知识引用、中文质量、工具权限、artifact 结构、prompt injection。
4. CI 只用确定性 fixture；真实模型评估若获授权只能作为非阻断报告。

批次 C：任务可靠性
1. 完善有界 worker、数据库 lease/heartbeat、过期 RUNNING 接管、幂等重试和终态 CAS。
2. 取消传播到上游 HTTP、SSE、文件解析、provider 临时文件和 artifact 临时目录。
3. failover 只用于能力、隐私和语义等价请求，并记录原因。
4. 指标至少含 queue depth/oldest age/running/status/duration/token/cost/file bytes/artifact failure/memory proposal/tool approval。

批次 D：保留、遗忘与安全
1. 实现过期上传、artifact、task event 和 provider 临时文件清理。
2. 验证“忘记”后正文、embedding、索引、摘要和缓存召回为零。
3. 对注入、伪造审批、本地路径请求、超大/损坏文件、429/5xx/断流、日志泄漏做故障注入。

批次 E：完整 AI E2E
上传→图片/文件理解→记忆提案→确认→新会话召回→知识引用→artifact→下载→领域 proposal→审批→审计；同时覆盖取消、重启、SSE 续传、预算拒绝和版本冲突。

运行后端 mvn verify、前端全量门禁、AI online E2E、覆盖率、安全 scan、迁移 fresh/upgrade 和 git diff --check。新增 docs/checkpoints/m3-ai-release-candidate-YYYY-MM-DD.md、Prompt 数据字典和 eval 报告。

退出条件：
- 固定 eval 达到事先记录阈值；
- 删除/撤权后无内容重现；
- 注入无法扩大权限或伪造审批；
- 任务/事件/文件在故障和重启下保持一致；
- AI E2E 稳定；
- 任一失败时只能维持 Beta，禁止 Production GA。
```

## 7. M4：基线、公开访问与质量止血

```text
你是 BlogDemo M4 基线与公开契约主执行 Agent。M1–M3 未完成不会授权你改其核心，但可以修复独立的公开访问和质量问题。只改本地仓库，不部署。

先阅读年度路线图、M3 checkpoint、当前 git 状态、frontend/src/router/index.ts、App.vue、SitemapService、PublicUrls、Playwright/Vitest/JaCoCo/CI 配置和相关测试。

目标：收口工作树、公开路由/SEO 漂移、offline smoke 假绿和覆盖率不阻断问题。

批次：
A. 动态建立工作树领域归属与建议提交切片，不写死 261/277，不移动用户文件。
B. 固化游客/登录/ADMIN/PARTNER 可见性矩阵；修复 series、series-detail、tag、archive、not-found 深链，保护 notes/account/admin。
C. 没有真实路由的 URL 从 sitemap 移除；route/navigation/API/sitemap/robots/canonical 建契约测试。
D. offline shell smoke 和 online E2E 分离；API 拒绝时在线路径不能通过降级数据冒充成功。
E. Vitest/JaCoCo 启用不下降阈值和 AI/权限关键包阈值；精确 ignore 运行产物。

运行前后端全量门禁、offline smoke、路由/sitemap 测试、OpenAPI/type check 和 git diff --check。新增 docs/checkpoints/m4-baseline-visibility-YYYY-MM-DD.md。

退出条件：公开 URL 契约一致；私有路由受保护；offline/online 语义不混淆；覆盖率回落阻断；用户工作树全部保留。
```

## 8. M5：迁移、备份、部署前置与资源生命周期

```text
你是 BlogDemo M5 数据与发布安全主执行 Agent。只使用隔离数据库和 dry-run；不连接生产、不部署。

先阅读年度路线图、M4 checkpoint、全部迁移与 FlywayUpgradePathTest、deploy/、CI/CD、migration-preflight、restore-and-migrate-drill、StorageService、附件/菜品/AI 文件与 artifact 清理实现。

目标：fresh、upgrade、restore 三路径可靠，并把 AI 数据纳入备份、恢复、配额和清理。

批次：
A. 动态冻结全部既有迁移 checksum；建立 Spring Boot/Flyway/PostgreSQL 支持矩阵。禁止回改历史迁移。
B. 建立不含演示数据的 fresh baseline；保留旧库升级。V34/V39 seed 与 V36 历史警告用新机制收口。
C. 部署前必须验证最近备份/checksum、Flyway validate/info、磁盘、目标版本和迁移兼容声明；SSH host key 使用固定指纹。
D. 对 DB、附件、菜品图、AI files/artifacts/memories/tasks/events/knowledge index 做恢复演练。
E. staged dish、AI upload、artifact 统一 owner/TTL/配额/引用/清理；收口 V51/V52 双存储与孤儿，不武断删除历史数据。
F. 验证旧 JAR 在 expand/contract 窗口内可运行；代码回滚不得假装回滚 DB。

测试空库、多个历史版本升级、恢复库、过期清理、引用保留、配额并发、非法双存储、孤儿修复和部署前置失败。运行 mvn verify、前端资源测试、脚本 dry-run、git diff --check。新增 docs/checkpoints/m5-data-release-safety-YYYY-MM-DD.md。

退出条件：三路径无未解释警告；AI/附件/图片字节与元数据可恢复；兼容窗口被测试证明；否则 Production GA 不得开启。
```

## 9. M6：异步任务、调度与遗留模块收口

```text
你是 BlogDemo M6 任务可靠性与架构主执行 Agent。目标是把 AI 任务已验证的可靠性原则应用到菜谱和发布调度，并降低遗留大模块修改半径。只改本地仓库。

先阅读年度路线图、M5 checkpoint、RecipeExtractionService/JobEntity/Repository/ExecutorConfig、VideoRecipeSourceExtractor、PostWorkflowService、AdminDashboard.vue、FoodSection.vue、api/admin.ts、DishImportService、BlogApiIntegrationTest 和相关测试。

按独立批次执行，禁止一次性全仓重构：
A. recipe job：attempts/lease/heartbeat/errorCode/idempotencyKey、原子 claim、重启恢复、唯一终态。
B. 取消/外部调用：传播到 HTTP/AI/yt-dlp/临时目录；统一超时、退避、熔断、bounded response、脱敏错误。
C. 定时发布：数据库 claim/锁、Clock 注入、重复扫描幂等审计。
D. 前端：拆 AdminDashboard、FoodSection、admin.ts；保留 props/events/API/testid/aria/视觉/深链，公共入口不加载 admin/AI 大包。
E. 后端：拆 DishImportService、RecipeExtractionService 和大型集成测试；网络/长文件/AI 不持有 DB 长事务。
F. 架构测试：controller 不直连 repository、domain 不依赖 web DTO、adapter 不反向依赖 controller。

每批先契约测试后重构。月末运行 mvn verify、前端全量门禁、在线任务 E2E、架构测试、构建预算和 git diff --check。新增 docs/checkpoints/m6-jobs-modularity-YYYY-MM-DD.md。

退出条件：重启/重复/取消竞态不留永久 RUNNING 或重复资源；核心热点职责清晰；API/视觉/权限无回归；失败时不要机械切文件凑行数。
```

## 10. M7：真实全栈 E2E、安全供应链与 SLO

```text
你是 BlogDemo M7 质量与安全主执行 Agent。只使用隔离 PostgreSQL、真实本地前后端和 deterministic fake 外部服务，不使用生产凭据、不部署。

先阅读年度路线图、M6 checkpoint、CI/CD、Playwright、认证 challenge/TOTP/refresh、TestDatabase、OpenAPI、Prometheus、备份脚本和当前所有安全豁免。

必须完成：
1. online/full-stack E2E job 启动隔离 DB、后端、前端与 fake provider；offline smoke 保持独立。
2. 覆盖登录/refresh/退出/权限、文章流程、笔记附件、菜品图、菜谱任务，以及 AI 上传→处理→记忆→artifact→审批→下载。
3. 覆盖跨用户、重启、SSE 断流、provider 故障、预算拒绝、数据恢复和无降级假绿。
4. Chromium/Firefox，关键桌面/移动路径运行 axe；少量截图不能替代语义断言。
5. dependency review、Dependabot/Renovate、SAST、secret scan、SBOM；critical/high 阻断或有 owner/到期日豁免。
6. HTTP、任务、AI 成本、存储、下载、DB pool、发布延迟、备份新鲜度 SLO/告警。
7. 失败日志、fake provider 请求记录和 Playwright trace 保存为 artifact。

运行完整 CI 等价命令、online/offline E2E、两浏览器、安全审计、SBOM、覆盖率和 git diff --check。新增 docs/checkpoints/m7-e2e-security-slo-YYYY-MM-DD.md。

退出条件：关键路径失败会阻断；没有降级数据假绿；0 个未豁免 critical/high；告警可测试触发。
```

## 11. M8：创作预览、统一媒体与发布质量

```text
你是 BlogDemo M8 创作与媒体主执行 Agent。只有 M7 发布门稳定后开始。不自动发布、不部署。

先阅读年度路线图、M7 checkpoint、PostWorkflowService/PostRevision、NotesWorkspace/TyporaEditor、附件、DishAsset、AI image/artifact、媒体权限与审计。

必须完成：
1. 短期、随机、只存 hash、可撤销、绑定资源版本的只读 preview token；noindex/no-store，默认 fail closed。
2. 统一媒体视图聚合笔记附件、菜品图、AI 图片和可展示 artifact，但保留各领域所有权。
3. 增加 alt、来源、许可、SHA-256、引用计数、createdBy、状态和回收站；引用非零拒绝物理删除。
4. 发布前检查 title/slug/summary/SEO/cover alt/失效链接/定时时间；错误阻断，建议忽略需审计。
5. 版本冲突展示服务器/本地 diff，支持放弃、复制或人工合并，禁止 last-write-wins。
6. AI 只产生候选/提案，任何路径不能 publish。

测试 preview 猜测/过期/撤销/版本变化、越权、XSS、资源去重/删除竞态、artifact 生命周期、发布检查和乐观锁。运行全量门禁、online E2E、安全检查和 git diff --check。新增 docs/checkpoints/m8-authoring-media-YYYY-MM-DD.md。
```

## 12. M9：搜索、内容发现与 AI 检索质量

```text
你是 BlogDemo M9 搜索与检索质量主执行 Agent。不部署。

先阅读年度路线图、M8 checkpoint、SearchService/Repository/Controller、SearchPage、GlobalSearch、useSearch、series/tag/archive/category、SitemapService、PublicUrls、页面 meta，以及 M3 AI knowledge/search 工具。

必须完成：
1. 正式实现 /categories 与详情，或从 sitemap/导航/结构化数据彻底移除，不能保留死 URL。
2. type/tag/category/date/sort/page 同步 URL，刷新与分享可复现。
3. 统一 GlobalSearch、SearchPage 与 AI 只读搜索工具的查询语义，但保持公开/私有权限隔离。
4. 固定中文相关性评测集：同义词、标题、标签、短词、错字、零结果、私有笔记隔离和 AI 引用正确性。
5. 最小化指标：匿名 query hash/聚合、zero-result、click position、latency；不存私有正文、memory、token。
6. 只有评测证明 PostgreSQL 方案不足时才做新引擎/embedding spike。

运行全量测试、online E2E、prerender、sitemap/robots/RSS 契约、查询计划/指标和 git diff --check。新增 docs/checkpoints/m9-search-retrieval-YYYY-MM-DD.md。

退出条件：公开/私有不串权；URL/canonical/sitemap 一致；相关性与 p95 可重复；AI 引用来自真实授权来源。
```

## 13. M10：知识图谱、反向链接与 AI 关系建议

```text
你是 BlogDemo M10 图谱主执行 Agent。不部署。

先阅读年度路线图、M9 checkpoint、GraphService/Controller/tests、SeriesService、KnowledgeGraph 及子组件/composables、现有导入导出，以及 AI tool/proposal 机制。

必须完成：
1. 显式 relation：source/target/type/origin/createdBy/timestamps/version，区分自动与人工。
2. 反向链接与后台关系 CRUD；处理重复、自环、悬挂边、乐观锁和审计。
3. 增量失效/物化更新；删除、改名、撤回后关系一致。
4. 1,000 节点局部图、确定性布局和 API/布局/内存/帧率基准。
5. 与画布等价的列表/关系表视图，键盘和读屏可操作。
6. AI 只能产生关系候选 proposal，确认后由 GraphService 执行，不能直接写边。
7. JSON/SVG 导出带 schemaVersion；导入只预览权限与冲突。

运行全量测试、online E2E、性能、axe、构建预算和 git diff --check。新增 docs/checkpoints/m10-graph-relations-YYYY-MM-DD.md。
```

## 14. M11：厨房闭环、PWA 与 AI 菜谱辅助

```text
你是 BlogDemo M11 厨房与 PWA 主执行 Agent。不扩展为营养、电商或复杂库存，不部署。

先阅读年度路线图、M10 checkpoint、kitchen 后端、WeeklyKitchenPlanner、FoodSection、foodStore、PWA runtimeCaching、auth/logout、权限测试和 AI recipe proposal。

必须完成：
1. 持久化 shopping list/item；周菜单生成清单，同单位规范化合并，保留来源菜谱/原始数量，不同单位不猜测。
2. 勾选、分类、手工项、备注、清理、打印/导出；owner/version/updatedAt/乐观锁。
3. 删除菜谱不破坏已生成清单快照；常备项只做简单建议。
4. AI 提取、归一化和购物建议只走 proposal/approval，不自动改菜单或清单。
5. Service Worker 禁止缓存 auth/admin/notes/kitchen/AI task/memory/file/artifact 私有响应。
6. 离线队列有界、可观测、幂等；重连冲突显示 diff，登出清私有 cache 和待同步项。

运行全量测试、移动 online E2E、离线/弱网 E2E、PWA 构建、权限/缓存审计和 git diff --check。新增 docs/checkpoints/m11-kitchen-pwa-YYYY-MM-DD.md。
```

## 15. M12：灾备、性能、可访问性与年度收口

```text
你是 BlogDemo M12 年度验收 Agent。以隔离演练、审计和修复为主；没有明确授权不得连接生产、部署、迁移生产库或 reload nginx。

先阅读年度路线图、M1–M11 全部 checkpoint、README/architecture/API/ADR/数据字典、CI/CD、部署、备份恢复、监控、安全报告和豁免。

必须完成：
1. 隔离恢复 DB、附件、菜品图、AI files/artifacts/memories/tasks/events/knowledge index、配置样例和发布产物；验证 checksum、数量、hash、登录、公开读取和 Flyway 版本。
2. 演练 provider 不可用、队列积压、artifact 磁盘接近满、下载失败、索引损坏、DB pool 耗尽、备份过期、部署健康失败和旧 JAR 回滚。
3. 验证 memory/file/source 删除后正文、embedding、索引、摘要缓存和派生副本不会重现。
4. 对公开页、后台、编辑器、AI Workspace/AttachmentTray/MemoryPanel/ApprovalCard/ArtifactCard、媒体、图谱、厨房做 WCAG 2.2 AA 自动+键盘+读屏审计。
5. 复测 Core Web Vitals、bundle、API/AI p95、慢查询、内存、任务 SLA、token/cost、存储增长、CI 时长、RPO/RTO。
6. 升级受支持依赖，关闭到期豁免、弃用 API、TODO 和无 owner 风险。
7. 用真实指标决定 Redis、多实例或独立 worker；未命中阈值则写 ADR 保持简单。
8. 收口 README、architecture、API、数据字典、ADR、运行/恢复/告警/权限手册。
9. 输出 docs/annual-audit-2027-07.md 和最终 checkpoint，逐项记录真实完成、未完成、顺延和下一年优先级。

运行仓库全部 CI 等价命令、online/offline E2E、两浏览器、覆盖率、安全扫描、SBOM、构建预算、fresh/upgrade/restore、脚本 dry-run 和 git diff --check。

退出条件：RPO≤24h、RTO≤60min；0 个开放 P1；无 serious/critical 可访问性问题；所有报告引用真实本次证据。
```

## 16. Agent 最终回复与 checkpoint 模板

```text
结果：完成 / 部分完成 / 阻塞
下一月允许启动：YES / NO

1. 入口门与本批次范围
- 上月退出条件：...
- 实际最高迁移版本 / 新迁移：...
- 允许修改范围：...
- 明确未做：...

2. 本批次完成项
- ...

3. 修改文件
- ...

4. 验证证据
- 命令：...
- 结果：...
- 测试数量 / 覆盖率 / 构建体积：...
- 权限、跨用户、文件安全、迁移路径：...
- 外部网络调用：无 / 已授权的具体调用

5. 指标变化
- 基线 → 当前：...

6. 数据、兼容与回滚
- 新迁移：...
- feature flag：...
- 兼容窗口：...
- 回滚/补偿/reconciler：...

7. 未通过的退出条件与风险
- ...

8. 建议提交切片
- <type>: <scope> <summary>

未执行：commit、push、部署、生产写入。
```
