# 项目检查点（2026-07-23）

## 当前状态

本检查点将近期尚未提交的全栈改动整理为一个可回退、可继续开发的稳定基线。

### 后端

- 为文章增加草稿、发布和归档状态，并补充内容清洗与分页支持。
- 完善学习笔记的状态变更、附件访问、分页和并发保存相关接口。
- 新增菜品模块及后台管理接口，菜品图片改为项目内静态资源。
- 移除已废弃的项目展示模块及对应数据库结构。
- 数据库迁移已推进至 V8。
- 增加覆盖文章、笔记、菜品和鉴权流程的 API 集成测试。

### 前端

- 更新博客首页、后台管理界面、登录页和整体视觉样式。
- 新增菜品展示区及配套本地图片。
- 完善文章发布状态管理和学习笔记工作区。
- 修复笔记自动保存、切换笔记、附件预览 URL 和离开页面保护等稳定性问题。
- 建立 Vitest、Vue Test Utils 和 jsdom 测试基线，并增加独立的测试类型检查配置。

### 文档与维护

- 更新架构说明、README、界面设计规范和项目审计报告。
- 保留 Windows 端 Vite 5173 端口故障排查脚本。
- 忽略本地 Vite 日志、脚本日志和前端覆盖率产物。

## 已完成验证

- 前端单元测试：21/21 通过，连续运行 3 次稳定。
- 前端测试类型检查：通过。
- 前端生产构建：通过。
- 后端 Maven 测试：9/9 通过。
- `git diff --check`：通过。
- 未发现 `skip`、`only` 或 `todo` 测试。
- 未发现准备提交文件中疑似硬编码的真实密钥。

## 已知非阻塞事项

- 项目暂未配置前端 lint。
- KaTeX 构建产物约 744 KB，Vite 会给出大 chunk 警告。
- 当前 Flyway 版本提示尚未正式验证 PostgreSQL 18。
- Mockito 的动态 Java Agent 加载方式将在未来 JDK 中受限。
- 早期曾报告约 28 个前端测试，但当时测试目录未受 Git 跟踪，无法恢复其历史；当前稳定基线为 21 个测试。

## 后续建议

1. 配置 Git 远端并推送本检查点。
2. ~~建立基础 CI，在每次推送时运行前端测试、测试类型检查、构建和后端测试。~~ ✅ 已完成
3. 再开始下一批功能扩展，避免继续积累未提交的大范围改动。

---

### 2026-07-23 补充 — 已建立 CI

- 在 `.github/workflows/ci.yml` 中配置了 GitHub Actions CI 质量门禁。
- 触发条件：`push` 到 `main`、`pull_request` 指向 `main`、`workflow_dispatch` 手动执行。
- **前端 Job**：Node.js 22 + npm 缓存，执行 `npm ci` → `npm test` → `npm run test:typecheck` → `npm run build` → `npm audit --audit-level=high`。
- **后端 Job**：Java 21 Temurin + Maven 缓存 + PostgreSQL 17 service（健康检查），测试凭据 `yubai_blog_it / yubai_app / ci-test-password`，执行 `mvn --batch-mode test`。
  - CI 环境变量（`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`）通过 workflow 传递，不读取本地 `.env.properties`。
  - `BlogApiIntegrationTest.loadEnv()` 已增加环境变量回退支持。
- **仓库质量检查**：检查提交中的行尾空格和冲突标记（对 PR 和首次提交适配）。
- 所有步骤均不使用 `continue-on-error`。`npm audit` 未使用 `--force`。
- 最小权限：`permissions: contents: read`。
