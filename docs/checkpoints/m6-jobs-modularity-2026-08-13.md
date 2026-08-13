# M6 异步任务、调度与遗留模块收口 Checkpoint

日期：2026-08-13
范围：recipe extraction job、定时发布、外部调用可靠性、前后端热点拆分、架构约束

## 交付结果

- `recipe_extraction_jobs` 已具备 attempts、lease、heartbeat、errorCode、idempotencyKey 和合法状态转换；数据库原子 claim、过期 lease 恢复、取消竞态和唯一终态均由服务与集成测试覆盖。
- 菜谱外部调用统一经过超时、重试/退避、熔断、bounded response 和脱敏错误策略；取消会传播到 HTTP、AI、yt-dlp 和临时目录清理，外部 I/O 不再持有数据库长事务。
- 定时文章发布使用数据库 claim 与注入的 Clock；重复扫描不会重复写发布审计，失败会释放 claim 并保留可诊断状态。
- 前端已按风险拆分 `AdminDashboard.vue` 的编辑器、样式/API 热点，`FoodSection.vue` 与 `api/admin.ts` 的既有契约保持；公共入口不引入后台编辑器大组件。
- `DishImportService` 已拆出 staging writer 与 Yrecipe archive codec；`RecipeExtractionService` 已拆出 source material、payload extraction 和 package writer，编排层只负责可恢复任务生命周期。
- `BlogApiIntegrationTest` 已将 refresh-token 生命周期与并发竞态移到 `BlogApiAuthTokenIntegrationTest`，保留登录 challenge、cookie、重放、注销和并发刷新断言，降低全栈测试单类修改半径。
- 新增 recipe source/architecture 测试，约束 controller 不直连 repository、domain 不依赖 web DTO、adapter 不反向依赖 controller，且编排服务不重新吸收 Jsoup、HTTP、ZIP 和文件系统职责。

## 版本与兼容窗口

- 当前仓库最高迁移为 V56（V53 AI 平台、V54 workspace/provider 能力、V55 staged resource lifecycle、V56 recipe job recovery）。M6 本批未新增迁移文件。
- `deploy/release-compatibility.env` 当前为 `SCHEMA_TARGET=56`、`ROLLBACK_APP_MIN_SCHEMA=53`、`MIGRATION_MODE=expand-only`；旧应用回滚仍只切应用 release，不回滚数据库。
- 生产 AI 开关、真实 provider 凭据和生产发布未在本 checkpoint 打开；生产部署需等 M7–M12 的质量、安全、产品和收口门完成，并通过发布前备份/迁移预检。

## 验证证据

- 后端 recipe 定向集成：`RecipeExtractionServiceTest`、`RecipeExtractionReliabilityIntegrationTest`、`RecipeSourceHttpClientTest`、`VideoRecipeSourceExtractorTest` 通过；另有 `RecipeArchitectureTest`、`RecipeSourceMaterialServiceTest` 通过。
- 拆分后的 API 集成：`BlogApiAuthTokenIntegrationTest` 6 项通过，`BlogApiIntegrationTest` 68 项通过；均使用本地专用 PostgreSQL 18.4 和 V1–V56 fresh migration。Flyway 对 PostgreSQL 18.4 的“正式支持到 17”提示已记录，不能据此宣称生产 18 支持。
- 前端门禁：71 个 Vitest 文件、822 项测试通过；TypeScript typecheck、ESLint、Prettier format check 和生产构建通过。
- 质量检查：Maven Spotless、架构约束、`git diff --check` 通过。
- Docker 当前不可用，因此本 checkpoint 的集成证据使用可达本地 PostgreSQL；需要 Testcontainers 的独立环境仍由 CI 发布门执行。

## 变更记录

- `eddac99 refactor(recipe): split dish import codec and staging`
- `ca8d4ea refactor(recipe): isolate extraction pipeline stages`
- `700badf refactor(web): isolate admin editor modal`
- 本 checkpoint 记录的测试拆分变更将在本文件随代码一起提交。

## 结论

M6 的 A–F 批次已完成并通过定向回归；核心异步任务和遗留热点已收口到可验证的职责边界。M7 才开始全站真实全栈 E2E、供应链安全和 SLO 发布门，当前不宣称 Production GA。
