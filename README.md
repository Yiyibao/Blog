# 余白 · 前后端分离博客

[![CI](https://github.com/Yiyibao/Blog/actions/workflows/ci.yml/badge.svg)](https://github.com/Yiyibao/Blog/actions/workflows/ci.yml)

一个由 Vue 3 + TypeScript 前端与 Spring Boot + PostgreSQL 后端组成的个人博客。前后端拥有独立的依赖、构建和运行流程，通过 REST API 通信。

> 当前唯一执行路线图为 [`docs/project-audit-and-execution-plan-2026-08-09.md`](docs/project-audit-and-execution-plan-2026-08-09.md)。`docs/checkpoints/` 保存阶段验收记录；其余带日期的计划与 checkpoint 仅作历史依据。

## 生产运行约束

- 唯一生产路线为 **nginx 静态托管前端 + 单实例 Spring Boot + PostgreSQL**；`deploy/` 与 `.github/workflows/deploy.yml` 是部署事实来源。
- 当前限流、人机验证、登录失败跟踪和 TOTP 挑战使用进程内状态，因此后端副本数必须保持为 **1**。扩容前须先迁移为共享状态或在 API Gateway 统一处理。
- 已移除旧 Worker/Sites 配置，前端生产构建只生成供 nginx 发布的 `dist/client`。
- 数据库变更只能追加 Flyway 迁移；部署前必须执行 `scripts/migration-preflight.ps1` 并确认已有可恢复备份。
- 数据/发布支持矩阵、fresh/upgrade/rollback 和完整恢复边界见 `docs/release-safety.md`。

## 项目结构

```text
BlogDemo/
├── frontend/   Vue 3、TypeScript、Vite、Pinia、Axios
├── backend/    Java 21、Spring Boot、Spring Data JPA、Flyway
└── docs/       项目文档
```

## 首次配置数据库

在仓库根目录执行：

```powershell
psql -U postgres -f backend/database/bootstrap.sql
```

脚本会提示输入新建数据库用户 `yubai_app` 的密码，并创建 `yubai_blog` 数据库。随后复制本地配置模板：

```powershell
Copy-Item backend/.env.example backend/.env.properties
```

打开 `backend/.env.properties`，把 `DB_PASSWORD` 改成刚才设置的密码。该文件已被 Git 忽略，不会提交真实凭据。

后台管理还需要在同一文件中配置：

```properties
APP_JWT_SECRET=至少32位的随机字符串
APP_ADMIN_USERNAME=你的管理员用户名
APP_ADMIN_PASSWORD=你的强密码
```

首次启动时，后端会用 BCrypt 加密管理员密码并写入数据库。此后不会保存明文密码；JWT 默认两小时过期。

## 启动后端

```powershell
cd backend
mvn spring-boot:run
```

后端默认地址为 `http://localhost:8080`，健康检查为 `http://localhost:8080/actuator/health`。

### 全站搜索

`Ctrl/Cmd + K`（或点击搜索按钮）打开全站搜索面板。输入关键词后 300ms 防抖自动向 `/api/v1/search` 发起请求，结果按文章、美食、学习笔记分组展示，支持键盘上下导航、Enter 跳转和 Escape 关闭。搜索面板使用独立 `AbortController` 取消过期请求，加载/错误/空状态均有对应 UI。

搜索结果使用 `?note={id}` 直接打开指定公开笔记，使用 `?dish={slug}` 直接打开指定菜品。文章归档的本地搜索与全局搜索状态完全独立。

首批只读接口：

- `GET /api/v1/posts?page=0&size=10&categorySlug=&sort=desc`：仅返回 `PUBLISHED` 文章，分页字段为 `items/page/size/totalElements/totalPages`；P1-2 起列表为摘要 DTO（PostSummary，**不含 content 正文**），`categorySlug` 可选过滤分类，`sort=asc` 最早优先（缺省最新优先）
- `GET /api/v1/posts/{slug}`：仅返回已发布文章（PostResponse，含正文，正文仅在详情返回）
- `GET /api/v1/categories`：返回所有至少关联一篇已发布文章的分类（CategorySummary[]，含 name、slug、publishedPostCount）
- `GET /api/v1/categories/{slug}?page=0&size=10`：返回指定分类详情及已发布文章（CategoryDetail，含 name、slug、description、total、posts 分页列表）
- `GET /api/v1/dishes?page=0&size=20`：分页返回已发布菜品，并按精选和展示顺序排序
- `GET /api/v1/dishes/{slug}`：读取菜品、食材、步骤和图片替代文本
- `GET /api/v1/notes?page=0&size=20`、`GET /api/v1/notes/{id}`（仅返回公开学习笔记；P1-2 起列表为摘要 DTO（NoteSummary，**不含 markdownContent**），正文经 `/{id}` 详情获取）
- `GET /api/v1/note-assets/{publicId}`（仅当所属笔记已公开时读取笔记内图片）
- `GET /api/v1/graph/nodes`：返回全量知识图谱节点与边（L-16/D-17 游客不可见 NOTE 节点，登录用户可见全部）
- `GET /api/v1/graph/nodes/{center}?depth=2`：5C 子图——以 `{center}` 节点为圆心 BFS `depth` 层（1–3，缺省 2），返回子图节点与两端均在节点集的边；未知 center 返回 404，非法 depth 返回 400；身份隔离与全图一致
- `GET /api/v1/search?q=关键词&limit=5`：按 `articles/notes/dishes` 分组返回公开内容，每组最多 1–10 条

管理接口：

- `POST /api/v1/auth/login`
- `GET /api/v1/admin/posts?page=0&size=20&status=DRAFT|PUBLISHED`：可按状态筛选；P1-2 起列表为摘要 DTO（不含正文），编辑前先经 `GET /{id}` 拉取全文
- `POST /api/v1/admin/posts`：创建文章，`status` 支持 `DRAFT` / `PUBLISHED`
- `GET|PUT|DELETE /api/v1/admin/posts/{id}`
- `GET /api/v1/admin/dishes?page=0&size=20`、`POST /api/v1/admin/dishes`
- `GET|PUT|DELETE /api/v1/admin/dishes/{id}`
- `GET /api/v1/admin/notes?page=0&size=20&status=DRAFT|PUBLISHED|ARCHIVED`、`POST /api/v1/admin/notes`；P1-2 起列表为摘要 DTO（不含 markdownContent），选中笔记时经 `GET /{id}` 拉取全文
- `GET|PUT|DELETE /api/v1/admin/notes/{id}`：笔记读取、自动保存与删除
- `PUT /api/v1/admin/notes/{id}/publish|unpublish|archive`：使用当前版本号发布、恢复草稿或归档笔记
- `POST /api/v1/admin/notes/import`：上传 `.md`、`.markdown` 或 `.txt`（最大 2 MB）
- `GET /api/v1/admin/notes/{id}/export`：导出 UTF-8 Markdown
- `GET|POST /api/v1/admin/notes/{id}/attachments`：列出或上传笔记图片
- `DELETE /api/v1/admin/notes/{id}/attachments/{attachmentId}`：删除笔记图片

- `POST /api/v1/admin/ai/chat`：AI 对话（请求体可选 `providerId`/`model` 指定供应商与模型，`reasoningEffort` 可取 `none`/`minimal`/`low`/`medium`/`high`/`xhigh`；缺省使用供应商默认值）
- `GET /api/v1/admin/ai/providers`：AI 供应商列表（密钥永不回显，仅 `hasKey` 与 `keyTail` 尾 4 位）
- `POST /api/v1/admin/ai/providers`：新增供应商 `{name, baseUrl, apiKey?, models?, defaultModel, enabled?}`；`baseUrl` 经 SSRF 校验（仅 https，禁内网/环回，本地端点需 `APP_AI_ALLOW_LOCAL_ENDPOINTS=true`）
- `PUT /api/v1/admin/ai/providers/{id}`：更新供应商；`apiKey` 留空表示保留原密钥
- `DELETE /api/v1/admin/ai/providers/{id}`：删除供应商（默认供应商被删后自动顺延）
- `PUT /api/v1/admin/ai/providers/{id}/default`：设为默认供应商
- `POST /api/v1/admin/ai/providers/{id}/test`：连通性测试（后端代发 `GET /models`，返回 `{ok, message, models}`）

除登录外，所有管理接口都必须携带有效的管理员 Bearer Token。
所有内容列表统一返回 `items/page/size/totalElements/totalPages`，页码从 `0` 开始，单页最大 `50` 条。

## AI 对话（可选，多供应商）

AI 助手支持多供应商注册表：DeepSeek、OpenAI、通义、智谱、Kimi、本地 Ollama 等一切 OpenAI 兼容端点都可在管理界面注册与切换，也支持原生 Anthropic Claude Messages API。API 密钥以 AES-256-GCM 加密存入数据库。启用注册表需在 `.env.properties` 配置加密主密钥：

```properties
APP_AI_MASTER_KEY=至少32位随机字符串（openssl rand -base64 48）
# 如需本地模型服务（如 Ollama），显式放开内网端点（改动需重启）：
APP_AI_ALLOW_LOCAL_ENDPOINTS=false
```

新增 Anthropic 供应商时，协议类型选择 `Anthropic Claude`，Base URL 填 `https://api.anthropic.com`（自建 OpenAI 兼容网关仍选择 `OPENAI_COMPATIBLE`）。后端会调用 `/v1/messages` 与 `/v1/models`，使用 `x-api-key` 和 `anthropic-version: 2023-06-01`，并支持非流式、SSE 流式聊天及连通性测试。

未配置主密钥时注册表不可用，仅剩下方环境变量单供应商回退。传统 `AI_*` env 配置仅在注册表为空时迁移为 `deepseek`；下方 Anthropic env 配置会同步为独立的 `Anthropic (env)` 供应商。

如需通过服务端环境变量接入原生 Anthropic/Claude 中转，可配置（Token 只放在服务环境中，不要提交到仓库）：

```properties
ANTHROPIC_BASE_URL=https://your-relay.example
ANTHROPIC_AUTH_TOKEN=your-test-token
ANTHROPIC_MODEL=claude-sonnet-5
ANTHROPIC_MODELS=claude-fable-5,claude-haiku-4-5,claude-haiku-4-5-20251001,claude-opus-4-6,claude-opus-4-7,claude-opus-4-8,claude-opus-5,claude-sonnet-4-6,claude-sonnet-5
APP_AI_MASTER_KEY=至少32位随机字符串
```

启动时会把该配置同步为加密保存的 `Anthropic (env)` 供应商；只有没有其他默认供应商时才会设为默认。`ANTHROPIC_MODEL` 未配置时使用 `claude-sonnet-5`。

如需接入 OpenAI Responses API 中转，可使用独立的 `OpenAI Responses` 供应商类型。服务启动时会把下面的环境变量同步为加密保存的 `GPT (Responses)` 供应商；已有默认供应商（例如 OpenCode）保持不变：

```properties
APP_AI_RESPONSES_ENABLED=true
APP_AI_RESPONSES_BASE_URL=https://xinyue.mom
APP_AI_RESPONSES_API_KEY=your-relay-key
APP_AI_RESPONSES_MODEL=gpt-5.5
APP_AI_RESPONSES_MODELS=gpt-5.3-codex-spark,gpt-5.4,gpt-5.5,gpt-5.6-luna,gpt-5.6-sol,gpt-5.6-terra
APP_AI_RESPONSES_HEADER_NAME=x-openai-actor-authorization
APP_AI_RESPONSES_HEADER_VALUE=local-image-extension
APP_AI_RESPONSES_REASONING_EFFORT=xhigh
APP_AI_RESPONSES_STORE=false
APP_AI_MASTER_KEY=至少32位随机字符串
```

该适配器请求 `/responses`，支持非流式与 SSE 流式聊天，并通过 `output[].content[].text` 和 `response.output_text.delta` 解析文本。

兼容回退（单供应商 env 配置）：

```properties
AI_ENABLED=true
AI_API_KEY=sk-your-deepseek-api-key
```

启用后 `POST /api/v1/admin/ai/chat` 会通过 DeepSeek 官方 API 返回回复。其他可选项：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AI_BASE_URL` | `https://api.deepseek.com` | API 基础地址 |
| `AI_MODEL` | `deepseek-v4-flash` | 模型名称 |
| `AI_REQUEST_TIMEOUT` | `60` | 请求超时（秒） |
| `AI_MAX_INPUT_CHARS` | `32000` | 单条消息最大字符数 |
| `AI_MAX_HISTORY_MESSAGES` | `20` | 历史消息条数上限 |
| `AI_MAX_TOTAL_CHARS` | `160000` | 所有消息累计字符数上限 |
| `AI_MAX_OUTPUT_TOKENS` | `2048` | 最大输出 token 数 |

如果 `AI_ENABLED` 为 `false` 或 `AI_API_KEY` 为空，该接口返回 `503`。前端不会看到 API key、模型、base URL 或 system role。

## 启动前端

另开一个 PowerShell：

```powershell
cd frontend
npm install
npm run dev
```

前端默认地址为 `http://localhost:5173`，开发代理会把 `/api` 请求转发到 Spring Boot。后端暂未启动时，页面会使用内置内容作为安全回退，不影响现有公开站点。

## IDE 一键启动

仓库内的 `.run` 目录提供 JetBrains IDE 共享运行配置。使用 IntelliJ IDEA 打开项目并等待 Maven 与 npm 完成索引后，在右上角运行配置中选择：

```text
BlogDemo - Full Stack
```

点击运行即可同时启动后端 `spring-boot:run` 和前端 `npm run dev`。也可以分别选择 `BlogDemo - Backend` 或 `BlogDemo - Frontend` 单独启动。后端仍从 `backend/.env.properties` 读取本地数据库、管理员和 AI 配置。

美食模块入口为 `http://localhost:5173/recipes`，页面从 PostgreSQL 菜品 API 读取真实内容，提供分类筛选、Bento 菜谱画廊、评分排行、食材清单、制作步骤和图片来源，并支持键盘关闭详情面板。管理员可在内容工作台的“菜品管理”中增删改查菜品。

后台入口为 `http://localhost:5173/admin/login`。登录令牌仅保存在当前浏览器会话中，关闭该会话后需要重新登录。

登录后从内容工作台进入 `http://localhost:5173/admin/notes`。学习笔记工作室支持所见即所得与 Markdown 源码双模式、标题大纲、任务清单、表格、代码块、KaTeX 公式、多笔记标签页、标签/目录/状态管理、显式发布与撤回、本地 Markdown 导入、导出和 1 秒防抖自动保存。发布前会先保存当前编辑内容，成功后公开页面立即可见。PNG、JPEG、WebP、GIF 图片可直接粘贴、拖入或从工具栏上传，单张最大 8 MB；输入 `/` 可打开快速插入菜单。`Ctrl/Cmd + S` 可立即保存，`Ctrl/Cmd + Shift + M` 切换源码模式，`Ctrl/Cmd + Shift + F` 切换专注模式。

## 构建

```powershell
cd backend
mvn clean package

cd ..\frontend
npm run build
```

## 测试

集成测试使用本地独立库 `yubai_blog_it`（不会改写开发库）。首次执行前：

```powershell
psql -U postgres -c "CREATE DATABASE yubai_blog_it OWNER yubai_app;"
psql -U postgres -d yubai_blog_it -c "ALTER SCHEMA public OWNER TO yubai_app;"
```

然后：

```powershell
cd backend
mvn test
```

## AI 生图（Grok / GPT 中转）

管理后台的 `/admin/ai/images` 提供受权限保护的文本生图入口。后端根据 `AI_IMAGE_GROK_*` 和 `AI_IMAGE_GPT_*` 两组环境变量选择中转商，不把密钥下发浏览器；生成的图片写入附件存储，元数据保存到 `ai_generated_images`，图片内容必须携带管理员 JWT 才能读取。

最小线上配置示例（密钥只放在 systemd `EnvironmentFile`，不要提交 git）：

```dotenv
AI_IMAGE_ENABLED=true
AI_IMAGE_MAX_PROMPT_CHARS=32000
AI_IMAGE_GROK_ENABLED=true
AI_IMAGE_GROK_BASE_URL=https://xinyue.mom/v1
AI_IMAGE_GROK_API_KEY=replace-with-relay-key
AI_IMAGE_GROK_MODELS=grok-imagine-image
AI_IMAGE_GROK_MODEL=grok-imagine-image
AI_IMAGE_GROK_WIRE_API=images

AI_IMAGE_GPT_ENABLED=true
AI_IMAGE_GPT_BASE_URL=https://xinyue.mom
AI_IMAGE_GPT_API_KEY=replace-with-relay-key
AI_IMAGE_GPT_MODELS=gpt-image-2
AI_IMAGE_GPT_MODEL=gpt-image-2
AI_IMAGE_GPT_WIRE_API=images
AI_IMAGE_GPT_HEADER_NAME=x-openai-actor-authorization
AI_IMAGE_GPT_HEADER_VALUE=local-image-extension
```

请求路径为 `GET /api/v1/admin/ai/images/models`、`POST /api/v1/admin/ai/images` 和 `GET/DELETE /api/v1/admin/ai/images/{publicId}`。默认每次生成 1 张、每个来源 IP 每分钟 3 次，单张响应上限 15 MB；需要 Responses API 的 GPT 中转时把 `AI_IMAGE_GPT_WIRE_API` 改为 `responses`，客户端会发送 `image_generation` tool 并解析 `output[].result`。AI 生图页面还支持上传 PNG、JPG/JPEG、WebP 或 GIF 参考图：上传请求以 multipart 发送，服务端校验后按对应 provider 的图片编辑/Responses 输入格式转发，生成结果仍保存到 `ai_generated_images`。
