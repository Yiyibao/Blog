# M7 端到端、安全与可观测性检查点

日期：2026-08-13

范围：全栈 E2E、浏览器矩阵、依赖与安全门禁、AI 运行指标、SLO 与告警规则。

## 交付结果

- 新增确定性的本地 fake provider 与隔离全栈启动器，覆盖登录、刷新/登出、未认证访问、供应商注册、文件上传、幂等任务创建、任务执行、事件回放、记忆确认、artifact 下载、跨用户资源隔离，以及真实公开页面渲染。
- 在线契约测试扩展到 Chromium、Firefox、移动 Chromium；全栈测试仅在 Chromium 运行，避免重复执行外部副作用链路。
- CI 增加 PostgreSQL 17 隔离全栈作业，并上传后端、前端、fake provider 与 Playwright 失败证据。
- 增加 Dependabot、CodeQL、依赖审查、gitleaks 与 SPDX SBOM 工作流；这些安全扫描由 CI 执行，本地未伪造扫描结果。
- 增加 `AiPlatformMetrics`，暴露任务状态、文件字节数与 artifact 字节数指标；新增 SLO 目标和 Prometheus 告警规则模板。

## 验证证据

- `frontend`: Prettier、TypeScript typecheck、ESLint 通过。
- `frontend`: Vitest 71 个文件、822 个测试全部通过。
- `frontend`: offline Playwright 3/3 通过。
- `frontend`: `node frontend/scripts/run-full-stack-e2e.mjs` 通过 7/7：全栈 Chromium 1 个用例，加上 Chromium/Firefox/移动 Chromium 的在线契约 6 个用例。
- `backend`: `AiMultimodalPlatformIntegrationTest`、`MonitoringIntegrationTest`、`AiResourceLifecycleTest` 通过；Spotless 与 compile 通过。
- `git diff --check` 通过。

## 版本与边界

- 当前最高数据库迁移仍为 V56；M7 未新增迁移。
- 本地验证使用 PostgreSQL 18.4，Flyway 记录了其正式支持上限为 PostgreSQL 17；CI 全栈作业使用 PostgreSQL 17。
- 生产 AI 开关、真实供应商凭据、Prometheus 接入和生产发布仍由 M12 发布门统一控制；本检查点不宣称 Production GA。

## 变更记录

- `ci: add full-stack quality and security gates`（待本检查点一并提交）
