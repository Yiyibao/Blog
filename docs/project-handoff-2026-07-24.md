# BlogDemo 项目报告与账号迁移记忆

> 更新日期：2026-07-24  
> 用途：更换 Codex/OpenCode/GitHub 操作账号或开启新会话后，快速恢复项目上下文。  
> 安全说明：本文不保存密码、Token、JWT 密钥、数据库口令、SSH 私钥或生产服务器凭据。

## 1. 一页摘要

- 项目名称：Yubai Blog / BlogDemo
- 仓库：`https://github.com/Yiyibao/Blog.git`
- 本地目录：`D:\Office\Study\code\BlogDemo`
- 主分支：`main`
- 当前提交：`46f37b8c628aea9e346d736dc7441f51c3b182c8`
- 当前代码基线：本地 `main` 与 `origin/main` 一致；仅本交接文档尚待提交
- 产品形态：前后端分离的个人博客与内容管理平台
- 前端：Vue 3、TypeScript、Vite、Vue Router、Pinia、Tiptap、Vitest
- 后端：Java 21、Spring Boot 3.5、Spring Security、Spring Data JPA、Flyway
- 数据库：PostgreSQL
- 已实现内容：文章、公开学习笔记、笔记附件、菜品、后台管理、JWT 登录、全站搜索
- 当前最高优先级：修复 GitHub Actions CI，再开始下一 Sprint

## 2. 当前 Git 基线

最近的关键提交：

```text
46f37b8 实现全站内容搜索
c241999 建立持续集成质量门禁
0b0bc53 建立全栈博客稳定化检查点
9db000e 博客网站前后端项目初始化
```

迁移账号后先执行：

```powershell
cd D:\Office\Study\code\BlogDemo
git remote -v
git status --short --branch
git log -5 --oneline --decorate
git ls-remote origin refs/heads/main
```

预期本地和远端 `main` 都指向 `46f37b8` 或其后续提交。

Git 操作原则：

- 禁止 `git push --force`。
- 禁止未经明确授权进行部署。
- 提交前必须运行与改动范围相称的测试。
- 推送前检查 `git diff --check`、暂存差异和目标远端。
- 不覆盖或丢弃用户已有的未提交改动。

## 3. 目录与架构

```text
BlogDemo/
├─ frontend/                 Vue 单页应用
│  ├─ src/App.vue            公共页面壳与主要展示
│  ├─ src/main.ts            路由和应用入口
│  ├─ src/api/               公共与后台 API 客户端
│  ├─ src/components/        搜索、笔记、菜品、后台组件
│  ├─ src/composables/       可复用交互逻辑
│  └─ src/test/              Vitest 测试
├─ backend/                  Spring Boot REST API
│  ├─ src/main/java/...      业务模块
│  ├─ src/main/resources/    配置与 Flyway migration
│  └─ src/test/              API 集成测试
├─ docs/                     架构、设计、审计与交接文档
├─ deploy/                   部署相关脚本；本阶段未授权部署
├─ .github/workflows/ci.yml  GitHub Actions
└─ README.md                 本地启动与主要接口说明
```

运行边界：

- 前端负责路由、展示、浏览器交互和客户端偏好。
- 后端负责内容权限、校验、持久化、搜索和管理鉴权。
- PostgreSQL 是文章、菜品、笔记、附件的权威数据源。
- 数据库结构只通过 Flyway 迁移演进，目前到 `V8`。
- 管理接口使用 Bearer JWT；公开接口只返回已发布内容。

## 4. 已完成功能

### 4.1 文章

- 公开文章列表、分类、详情和分页。
- 后台文章 CRUD。
- `DRAFT` / `PUBLISHED` 状态。
- 正文清洗、标签、精选文章、阅读时间等元数据。

### 4.2 学习笔记

- 后台笔记 CRUD、分页、目录、标签和 Markdown 编辑。
- Tiptap/Typora 风格编辑器。
- 一秒防抖自动保存。
- JPA 乐观锁，防止旧标签页覆盖新版本。
- 发布、取消发布、归档状态流转。
- Markdown 导入与 UTF-8 导出。
- 图片附件存入 PostgreSQL `bytea`，公开状态决定访问权限。
- 公开笔记列表与详情。

### 4.3 菜品

- 公开菜品列表和详情。
- 后台菜品 CRUD。
- 食材、步骤、分类、评分、精选和图片署名。
- 菜品图片使用项目内静态资源。

### 4.4 管理与安全

- 管理员登录。
- BCrypt 密码哈希。
- HS256 JWT，默认有效期两小时。
- `/api/v1/admin/**` 需要 `ADMIN` 角色。
- SPA 将 Token 保存在 `sessionStorage`。
- 本地敏感配置放在已忽略的 `backend/.env.properties`。

### 4.5 Sprint 1：全站搜索

提交：`46f37b8`

后端：

- 公开接口：`GET /api/v1/search?q={关键词}&limit={每组数量}`
- `limit` 默认 5，每类限制为 1–10。
- 响应按 `articles`、`notes`、`dishes` 分组，并提供 `total`。
- 只搜索已发布文章、公开笔记和已发布菜品。
- 文章覆盖标题、摘要、分类、正文和标签。
- 笔记覆盖标题、目录、Markdown 正文和标签。
- 菜品覆盖名称、摘要、分类、食材和步骤。
- 搜索 URL 可直达：
  - 文章：`/articles/{slug}`
  - 笔记：`/notes?note={id}`
  - 菜品：`/recipes?dish={slug}`

前端：

- `Ctrl/Cmd + K` 或顶部按钮打开全站搜索。
- 全站搜索与文章归档筛选状态独立。
- 300 ms 防抖。
- `AbortController` 取消旧请求，请求序号阻止旧响应覆盖。
- 加载、错误、重试、空结果和三类分组状态。
- 上下键跨分组导航、Enter 跳转、Escape 关闭。
- combobox/listbox/option ARIA 和激活项滚动。
- 关闭后恢复到打开搜索的按钮。
- 笔记和菜品页面响应 URL 查询参数并打开指定公开内容。
- 禁止在后端失败时用已加载文章冒充全站搜索结果。

## 5. 当前验证基线

Sprint 1 提交前的本地验收结果：

- 后端：10 个测试通过。
- 前端：34 个测试通过。
- 前端测试类型检查通过。
- 前端生产构建通过。
- `git diff --check` 通过。
- 未发现 `.only`、`.skip`、`todo` 或调试日志残留。

常用验证命令：

```powershell
cd D:\Office\Study\code\BlogDemo\frontend
npm.cmd ci
npm.cmd test
npm.cmd run test:typecheck
npm.cmd run build
npm.cmd audit --audit-level=high
```

```powershell
cd D:\Office\Study\code\BlogDemo\backend
& 'C:\Program Files\apache-maven-3.9.16\bin\mvn.cmd' test
```

```powershell
cd D:\Office\Study\code\BlogDemo
git diff --check
git status --short --branch
```

## 6. 环境配置

### 6.1 前端

要求：

- Node.js `>=22.13.0`
- npm

启动：

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```

默认前端地址：`http://127.0.0.1:5173`

可选环境变量：

```text
VITE_API_BASE_URL=/api/v1
VITE_ALLOW_BUNDLED_CONTENT=false
```

### 6.2 后端

要求：

- Java 21
- Maven 3.9+
- PostgreSQL

本地 `backend/.env.properties` 只保存到本机，不提交：

```properties
DB_URL=jdbc:postgresql://localhost:5432/yubai_blog
DB_USERNAME=...
DB_PASSWORD=...
APP_JWT_SECRET=...
APP_ADMIN_USERNAME=...
APP_ADMIN_PASSWORD=...
APP_CORS_ALLOWED_ORIGINS=http://localhost:5173
```

启动：

```powershell
cd backend
& 'C:\Program Files\apache-maven-3.9.16\bin\mvn.cmd' spring-boot:run
```

默认后端地址：`http://localhost:8080`  
健康检查：`http://localhost:8080/actuator/health`

集成测试使用独立数据库 `yubai_blog_it`。不要让测试连接生产数据库。

## 7. 主要公开路由与 API

前端路由：

```text
/
/articles
/articles/:slug
/about
/notes
/recipes
/admin/login
/admin
/admin/notes
```

公开 API：

```text
GET /api/v1/posts
GET /api/v1/posts/{slug}
GET /api/v1/categories
GET /api/v1/notes
GET /api/v1/notes/{id}
GET /api/v1/note-assets/{publicId}
GET /api/v1/dishes
GET /api/v1/dishes/{slug}
GET /api/v1/search?q=...&limit=...
```

管理 API 位于：

```text
POST /api/v1/auth/login
/api/v1/admin/posts/**
/api/v1/admin/notes/**
/api/v1/admin/dishes/**
```

成功响应约定为：

```json
{
  "data": {},
  "timestamp": "ISO-8601"
}
```

## 8. 当前未完成事项：CI 必须优先修复

失败运行：

- Workflow：`CI`
- Run ID：`30019958277`
- 提交：`46f37b8`
- 结果：`quality` 成功，`frontend` 和 `backend` 失败

已确认：

1. `frontend` 失败在 `npm ci`。
2. 使用同一提交的干净临时副本进行以下验证均成功：
   - 普通 `npm ci`
   - Linux x64 目标安装
   - 全新空 npm 缓存安装
3. 因此尚无证据表明 `package.json` 与 `package-lock.json` 不一致；更可能是 GitHub/npm 临时环境问题。下一次 CI 若仍失败，必须读取展开后的原始日志再修改。
4. `backend` workflow 的 PostgreSQL service 没有向宿主 runner 映射 `5432` 端口，但 Maven 测试连接 `localhost:5432`。这是明确缺陷。

建议的第一项修复：

```yaml
services:
  postgres:
    image: postgres:17
    ports:
      - 5432:5432
```

然后：

1. 本地复查 workflow 差异。
2. 提交 CI 修复。
3. 明确授权后普通推送到 `origin/main`。
4. 等待新的 GitHub Actions 运行结束。
5. 若前端继续失败，读取该次 `npm ci` 原始日志；不要猜测性升级全部依赖。

## 9. 已知技术债与风险

- 前端暂未配置 ESLint。
- KaTeX 构建 chunk 约 744 KB，Vite 会提示大 chunk。
- 当前 Flyway 版本对本机 PostgreSQL 18 给出“尚未正式验证”的警告；CI 使用 PostgreSQL 17。
- Mockito 动态加载 Java Agent 的方式将在未来 JDK 中受限。
- GitHub Actions 中 `actions/checkout@v4`、`setup-node@v4`、`setup-java@v4` 会出现 Node 20 runtime 弃用警告；这不是本次失败主因，升级前应查官方变更说明。
- 全站搜索当前使用数据库 `LIKE` 查询；内容量显著增长后再评估 PostgreSQL 全文检索、`pg_trgm`、权重和高亮。
- `App.vue` 仍承担多个公共页面，后续可继续拆分路由组件。
- 不要在没有基准测试和迁移方案时直接引入 Elasticsearch。

## 10. 建议后续路线

优先级 P0：

1. 修复 CI PostgreSQL 端口映射。
2. 触发并监督 CI 到全绿。
3. 将 CI 修复结果补充到本交接文档。

优先级 P1：

1. 为搜索结果增加关键词高亮和更明确的相关性排序。
2. 增加搜索接口单元测试和前端 URL 直达组件测试。
3. 增加前端 ESLint 与格式检查。
4. 优化 KaTeX 和编辑器相关代码分包。

优先级 P2：

1. SEO、Open Graph、站点地图和结构化数据。
2. 阅读列表跨设备同步。
3. 内容统计与管理后台仪表盘。
4. 搜索分析、零结果关键词和内容运营能力。

## 11. Codex 主导、DeepSeek 实现的协作约定

历史协作方式：

- Codex 负责需求澄清、拆解、计划、验收标准、代码审查、测试、Git 提交和推送监督。
- DeepSeek V4 Flash 负责按边界明确的任务实现细节。
- DeepSeek 通过本机 OpenCode 调用：
  - OpenCode：`D:\Office\nodejs\node_global\opencode.cmd`
  - 模型：`opencode-go/deepseek-v4-flash`
- 不再依赖 Paseo。

执行原则：

1. Codex 先检查仓库和未提交改动。
2. Codex 写出可验证的接口与交互契约。
3. 将后端、前端、测试拆成范围有限的 DeepSeek 任务。
4. DeepSeek 不得提交、推送、部署或修改任务外文件。
5. Codex 必须逐项检查实现，不能把“能编译”视为验收通过。
6. Codex 独立运行测试、类型检查、构建和差异审计。
7. 发现缺陷后要求返工或由 Codex 做审查性修正。
8. 验收通过后才允许提交。
9. 推送必须明确目标远端、分支和提交；禁止强推。
10. 部署需要单独授权。

外部数据说明：

- 用户曾授权将当前项目源码交给本机 OpenCode Go/DeepSeek 用于实现。
- 更换账号或宿主环境后，仍应遵循新环境的外部数据策略；如系统要求，应重新获得明确授权。
- 不得向模型发送 `.env.properties`、Token、私钥、生产凭据或数据库导出。

## 12. 新账号首条提示词

将下面内容连同本文件路径交给新的 Codex 会话：

```text
请接管 D:\Office\Study\code\BlogDemo 项目。

首先完整阅读：
1. docs/project-handoff-2026-07-24.md
2. README.md
3. docs/architecture.md
4. .github/workflows/ci.yml

工作方式：
- 由 Codex 主导需求澄清、拆解、计划、验收、测试和 Git。
- 实现细节可交给本机 OpenCode 的 opencode-go/deepseek-v4-flash。
- DeepSeek 不得自行提交、推送或部署。
- Codex 必须独立检查代码并运行测试。
- 验收通过后可以提交和普通推送，但禁止强推；部署必须另行确认。
- 不读取或外发任何密钥、Token、私钥和 .env.properties 内容。

接管后的第一项任务：
- 核对 git status、HEAD、origin/main。
- 修复 GitHub Actions 后端 PostgreSQL service 缺少 5432 端口映射的问题。
- 本地验证 workflow 和项目测试。
- 提交前向我报告具体改动与验证结果。
- 未得到推送确认前不要推送。
```

## 13. 交接完成检查表

新账号接管时逐项确认：

- [ ] 能读取本仓库和本交接文档。
- [ ] `git status` 没有未知的用户改动。
- [ ] 本地 `HEAD` 与预期提交一致。
- [ ] `origin` 指向 `Yiyibao/Blog.git`。
- [ ] 本地 Node、Java、Maven、PostgreSQL 可用。
- [ ] 敏感配置仍只存在本机忽略文件或安全环境变量中。
- [ ] 前端测试、类型检查和构建可以运行。
- [ ] 后端集成测试连接的是测试数据库。
- [ ] CI 修复完成并达到全绿。
- [ ] 后续 Sprint 重新建立清晰验收标准。
