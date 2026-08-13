# M10 图谱关系 checkpoint（2026-08-13）

## 状态

M10 已完成本地实现与验收，未部署。M11 可启动；Production GA 仍保持关闭。

## 已交付

- V61 新增 `graph_relations` 与 `graph_relation_audits`，显式记录 source/target/type/origin/actor/timestamps/version。
- `GraphRelationService` 统一执行节点存在性、重复、自环、悬挂边过滤、乐观锁、审计和缓存失效；删除关系不删除审计历史。
- 公开图谱合并显式关系，提供反向链接端点；关系仅在当前派生图节点仍存在时展示，因此内容删除、撤回和权限视图不会产生公开悬挂边。
- 后台 `/admin/graph` 支持关系 CRUD、审计查询、源/目标筛选和导入 JSON 预览；导入只报告可接受项与冲突，不批量直写。
- AI 增加 `propose_graph_relation` 工具，生成 `graph.relation.create` proposal；审批先调用 `GraphRelationService`，成功后才将 proposal 标记为 APPROVED。
- 图谱 JSON/SVG 导出包含 `schemaVersion: 2.0`；画布提供键盘/读屏可用的节点表和关系列表等价视图。
- OpenAPI `frontend/openapi/blog-api.json` 与 `frontend/src/api/generated.ts` 已同步。

## 验证证据

- 后端 `mvn clean test` 与 `mvn verify` 通过；关系/图谱/AI 专项测试、`GraphRelationServiceTest`、`AdminGraphRelationControllerTest` 与 1,000 节点确定性/性能基准通过。
- V61 fresh/upgrade/preflight 回归通过；迁移清单验证通过：60 个 immutable migration files through V61。
- 前端 72 个测试文件、825 项测试全部通过；`npm run test:coverage` 达到 Statements 61.85%、Branches 55.33%、Functions 51.00%、Lines 64.56%，均高于项目阈值。
- `npm run lint`、`npm run format:check`、类型检查、生产构建/预算、离线 3 项 E2E（含 axe 严重问题检查）通过。
- 本地隔离全栈在线 E2E 通过 7/7：Chromium、Firefox、移动 Chromium，覆盖真实 PostgreSQL、Spring Boot、Vite、fake provider、认证隔离、AI 任务/记忆/artifact 与公开页面。
- OpenAPI 生成与前端类型同步、`git diff --check` 通过；未发起真实外部模型请求。

## 发布边界

- M10 按提示词要求不部署生产；提交/推送后进入 M11，Production GA 继续保持关闭。
