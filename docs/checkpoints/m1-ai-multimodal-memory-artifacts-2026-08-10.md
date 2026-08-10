# M1 Checkpoint: AI 多模态、真实记忆与生成物闭环

结果：**完成（Internal Alpha，生产默认关闭）**
下一月允许启动：**YES（M2 仅限本地/测试；不授权开启 Production GA）**

## 1. 入口门与本批次范围

- 上月退出条件：年度路线图在本批次创建，M1 无前置月 checkpoint；以 2026-08-10 事实基线为入口。
- 工作树：`main` 上已有约 233 项用户/其他批次未提交修改；本批次未清理、移动、提交或覆盖无关文件。
- 迁移核验：动态扫描到 V52，核对 V51/V52 和专用测试库历史后使用下一个可用版本 V53。
- 实际最高迁移版本 / 新迁移：V53 / `V53__create_ai_multimodal_platform.sql`。
- 允许修改范围：网站级 AI 核心、受控文件/记忆/artifact、旧 AI 图片 owner 修复、AI Workspace、契约/测试和相关文档/config。
- 明确未做：领域工具、RAG、写操作审批、匿名 AI、任意代码/路径/URL 工具、AI Production GA 和真实模型调用。代码与 expand-only schema 已在后续明确授权下发布，详见第 10 节；功能开关保持关闭。

## 2. 本批次完成项

- 新建 `ai_sessions`、`ai_tasks`、`ai_task_parts`、`ai_task_events`、`ai_files`、`ai_memories`、`ai_artifacts`，并为旧 AI 图片会话补外键。
- 实现持久任务状态、owner+幂等键、乐观终态 CAS、取消、启动恢复、事件序列锁、`afterSequence`/`Last-Event-ID` 回放和 429 有界并发门。
- 实现 PNG/JPEG/WebP、PDF、DOCX、TXT/Markdown、CSV、JSON 的受控上传、bounded read、owner 数量/字节配额及格式结构校验。
- 建立显式 provider capability matrix；OpenAI Responses adapter 真实发送 `input_text`、`input_image`、`input_file` 且 `store=false`。能力不足在 HTTP 前失败，不丢附件降级。
- 实现用户记忆 CRUD、模型来源 `PROPOSED → 用户确认 → ACTIVE`、跨会话召回、编辑、启停、拒绝和遗忘；敏感内容默认拒绝。
- 实现 Markdown、TXT、JSON、CSV、现有 AI 图片 artifact；记录 owner/task/MIME/size/SHA/expiry，CSV 防公式注入，下载强制 attachment + `private, no-store` + `nosniff`。
- 实现创建失败字节补偿和按小时过期清理；元数据事务提交后删除字节，删除失败可在下一轮重试。
- 修复旧 AI 图片 content/delete 只按 publicId 访问的 IDOR：现在会重新校验图片会话 owner。
- 新增 AI Workspace、TaskComposer、AttachmentTray、MessageList、TaskTimeline、ArtifactCard、MemoryPanel、SessionSidebar、独立 `api/ai.ts` 与 `aiTaskStore`；生产默认仍显示兼容聊天。
- 同步运行时 OpenAPI 快照、TypeScript 生成类型、ADR、架构和数据字典。

## 3. 修改文件

- 后端核心：`backend/src/main/java/com/yubai/blog/ai/**`、`AiPlatformProperties`、`SecurityConfiguration`。
- 兼容修复：`AiImageService`、`AdminAiImageController`。
- 数据/config：`V53__create_ai_multimodal_platform.sql`、`application.yml`、`.env.example`、`pom.xml`。
- 后端测试：`backend/src/test/java/com/yubai/blog/ai/**`、`AiImageServiceTest`。
- 前端：`frontend/src/api/ai.ts`、`src/stores/aiTaskStore.ts`、`src/components/ai/**`、`src/pages/AdminAiPage.vue`、env 类型/样例和 M1 Vitest。
- 契约：`frontend/openapi/blog-api.json`、`frontend/src/api/generated.ts`。
- 文档：AI ADR、architecture、AI 数据字典、年度路线图和本 checkpoint。

## 4. 验证证据

### 后端与迁移

- 最终发布门禁 `mvn clean verify`：**755 tests，0 failure，0 error，JAR BUILD SUCCESS**；全局 Spotless 同步通过。
- `mvn -Dtest=AiMultimodalPlatformIntegrationTest test`：**2/2 通过**。真实 PostgreSQL 随机隔离 schema、真实 `StorageService`、本地 deterministic fake HTTP `/responses`；请求同时包含 PNG、PDF、DOCX、Markdown、CSV 对应 parts。
- 同一集成测试证明：能力不足得到 `AI_PROVIDER_ERROR_400` 且 fake HTTP 请求计数不增加；ACTIVE 记忆跨会话注入，禁用/遗忘后召回为零；file/task/memory/artifact 跨 owner 均返回 NotFound。
- `AiPlatformMigrationTest`：fresh V1→V53 和 upgrade V52→V53 均通过；52 个迁移文件校验成功。
- `AiResourceLifecycleTest`：文件数量配额、过期清理、artifact DB 失败字节补偿通过。
- `AiTaskRecoveryTest`：重启遗留 RUNNING 任务进入明确 FAILED 终态并持久事件。
- `AiPlatformControllerTest`：受控 attachment 下载头与 `Last-Event-ID=5` 仅回放 sequence 6 通过。
- `AiImageServiceTest`：攻击者不能 find/read/delete 其他 owner 图片，且不会读取/删除存储字节。
- M1 Java 白名单 `spotless:check`：通过。

### 前端、契约与构建

- `npm run lint`：通过，0 warning。
- `npm run test:coverage`：**67 files / 811 tests 全部通过**；statements 64.78%、branches 59.34%、functions 55.18%、lines 67.08%。
- `npm run test:typecheck`：通过。
- `npm run build`：通过；PWA 90 entries，JS 总量 1,430,965 bytes，build budget 通过。
- 全局 `npm run format:check`：通过。
- `npm run test:e2e`：通过。
- `npm run api:types:check`：通过；OpenAPI 含 18 个 `/api/v1/ai/**` path，生成类型同步。
- `git diff --check`：通过。

### 权限、文件安全和外部调用

- `/api/v1/ai/**` 要求 `AI_USAGE` capability；服务层所有资源再次按 owner 查询。
- 文件仅接受 multipart bytes，API 没有路径参数；存储 key 不暴露；读取时复核 size+SHA。
- 测试期间真实模型外呼：**0**。仅访问 `127.0.0.1` fake provider。
- 其他网络：首次解析新增 Maven 依赖时经用户批准访问 Maven Central；不涉及模型、生产服务或业务数据。

## 5. 二元退出条件

| 条件 | 结果 | 证据 |
| ---- | ---- | ---- |
| PNG 与 PDF/DOCX/TXT/Markdown/CSV parts 真实进入 fake provider | YES | 集成测试断言 `input_image`、4 个 `input_file` 及对应 filename |
| provider 无 VISION/FILE_INPUT 时明确失败 | YES | 文本-only provider 返回 `AI_PROVIDER_ERROR_400`/`VISION`，HTTP 计数不变 |
| ACTIVE 记忆跨会话；编辑/禁用/删除可控 | YES | 记忆服务单测 + 集成请求体断言 |
| 用户 A 不能访问用户 B 的 AI 资源 | YES | file/task/memory/artifact 集成隔离 + AI image IDOR 单测 |
| 文本、结构化数据、图片三类 artifact 可下载 | YES | MD、JSON、CSV、PNG 受控读取 + 控制器 attachment 下载契约 |
| 重启与 SSE 重连不丢持久状态 | YES | V53 持久表、恢复测试、事件回放与 Last-Event-ID 契约 |
| 失败、取消、过期、删除无不可解释孤儿 | YES | 存储原子写、DB 失败补偿、过期 after-commit 清理/重试、删除 tombstone |
| 旧文本聊天无回归 | YES | 全量 verify 包含旧 chat/history/image controller/service 测试 |
| 真实模型外呼为零 | YES | fake 仅绑定 127.0.0.1；未使用真实模型凭据 |

## 6. 指标变化

- 后端历史基线 737 → 最终发布门禁 755。
- 前端历史基线 806 → 811。
- OpenAPI AI paths：0 → 18。
- 数据库最高版本：V52 → V53。
- 新生产 feature flags：4 个，默认全部 `false`。

## 7. 数据、兼容与回滚

- 新迁移：V53 为 expand-only；不修改 V46 聊天历史，不删除旧 API/表。
- Feature flags：`APP_AI_PLATFORM_TASKS_ENABLED`、`MULTIMODAL_ENABLED`、`MEMORY_ENABLED`、`ARTIFACTS_ENABLED` 默认关闭；前端 `VITE_AI_PLATFORM_ENABLED=false`。
- 兼容窗口：生产和未显式开启的新环境继续使用 `AdminAiChat`；M2 可在隔离环境继续扩展网站入口。
- 回滚：关闭四个后端 flag 和前端 flag 即停止新入口；代码回滚不伪装 DB 回滚，V53 表保留等待兼容代码。
- 补偿/reconciler：文件/artifact 写存储后 DB 失败会删除字节；过期资源循环重试删除；M5 再纳入备份/恢复和全域资源 reconciler 演练。

## 8. 剩余风险与已关闭门禁

- 发布前已统一格式化本批次完整暂存快照；全局 Prettier、Spotless 与 `git diff --check` 均通过。
- 尚未新增独立 Playwright“真实前后端进程”AI 浏览器作业；当前用户路径由真实后端/DB/存储/fake-provider 集成测试与 Vue store/component 测试共同覆盖。M7 仍负责正式双浏览器 full-stack E2E、断流与故障矩阵。
- PostgreSQL 测试实例为 18.4，当前 Flyway 日志提示官方验证上限为 PostgreSQL 17；fresh/upgrade/全量测试均通过，但 M5 必须固化支持矩阵。
- M1 `/tasks/{id}/stream` 聚焦持久回放并在 replay 后关闭；长连接实时增量、heartbeat/断流故障矩阵在 M3/M7 继续增强，不能据此开放 Production GA。

以上风险不否定 M1 二元退出条件，但继续阻止 Production GA。M2 可在相同默认关闭边界内启动。

## 9. 建议提交切片

- `feat(ai): add persistent multimodal task and file core`
- `feat(ai): add confirmed memory and controlled artifacts`
- `feat(frontend): add internal alpha AI workspace`
- `test(ai): cover multimodal provider ownership recovery and lifecycle`
- `docs(ai): record M1 architecture contracts and checkpoint`

## 10. 2026-08-10 发布跟进

- 发布提交：`5bcc83c0a725ad9a04a35f3e9ae986078fb2a613`，已推送 `origin/main`；CI run `31353583919` 成功。
- 发布标签：`v1.1.0-ai-platform-m1`；Deploy run `31353750071` 的 validate、build、deploy 三个 job 全部成功，原子切换未触发回滚。
- 迁移前备份：`SHA256SUMS-20260810T034405Z`；数据库 dump 与附件归档 SHA-256 均为 OK，`pg_restore --list` 可读取 295 个对象。
- 生产结果：`/opt/yubai-blog/current` 指向 `release-20260810035513-5bcc83c`；应用、nginx、PostgreSQL active；Flyway V51→V53，失败迁移 0，七张 M1 核心表存在。
- 线上验收：首页、登录、后台登录、文章/菜品 API、sitemap、RSS、robots、health 共 9 条 HTTPS 冒烟路径全部 200；未认证 AI API 返回 401；HSTS、CSP 与 CSP-Report-Only 生效。
- 发布边界：四个 `APP_AI_PLATFORM_*_ENABLED` 未启用，`VITE_AI_PLATFORM_ENABLED=false`；本次发布代码与 schema，但未开放 AI Workspace Production GA，也未执行真实模型调用。
