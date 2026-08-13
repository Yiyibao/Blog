# M9 搜索、内容发现与 AI 检索质量 checkpoint

日期：2026-08-13

状态：完成（代码、契约与隔离 PostgreSQL 验证完成；生产部署仍按 M12 统一发布门执行）

## 入口与范围

- M8 已完成，V57–V59 和 `m8-authoring-media-2026-08-13.md` 已验证；M9 不部署、不调用真实付费 AI。
- 本批次只处理公开搜索、typed search、GlobalSearch、SearchPage、AI 只读检索工具、检索最小指标与契约同步。
- 现有 `/categories`、`/categories/{slug}`、导航、sitemap 和页面 structured data 均保留并验证为可达；没有留下死 URL。

## 完成项

- `SearchRequest` 统一承载 `type/tag/categorySlug/from/to/sort/page/size`；SearchPage 将 q、type、tag、category、date、sort、page 写入 URL，刷新和分享可以复现同一查询。
- GlobalSearch、SearchPage 和 AI `search_content` 均使用 typed `POST /api/v1/search`；ALL 只读查询返回同一组授权范围内的来源引用，SearchPage 不再拆成三次语义不同的请求。
- 文章分类、标签、日期范围在服务端分页查询中生效；菜谱和笔记日期范围也在数据库查询中生效。可选参数通过显式过滤开关与日期哨兵传递，避免 PostgreSQL nullable bind 类型歧义。
- 增加确定性中文规范化：教程/教学、食谱/料理、博文、常见 JavaScript/PostgreSQL 错拼映射；短词、标题、标签和零结果走同一 LIKE/trigram PostgreSQL 路径。
- 访客不返回 NOTE；登录用户沿用现有应用授权边界读取已发布学习笔记。当前笔记表没有独立 owner 列，因此本批次不虚构多 owner 语义；AI 只返回 type/id/title/url 来源引用，不返回正文、memory 或 token。
- V60 增加 `search_query_events`，只保存规范化查询 SHA-256、scope、result count、zero-result、latency 和首次 click position；AI 工具调用参数落库为脱敏占位，不持久化原始查询。
- OpenAPI 快照由真实 `/v3/api-docs` 导出，生成 TypeScript 类型同步到前端；新增点击事件契约。

## 修改与迁移

- 新迁移：`V60__add_minimal_search_telemetry.sql`；兼容窗口更新为 `SCHEMA_TARGET=60`，仍为 expand-only，旧代码最低 schema 保持 V53。
- 主要后端：SearchService/Repository/Controller、SearchRequest/Response、SearchQueryNormalizer、AiReadOnlySearchService、AiToolOrchestrator、SearchTelemetry*；Flyway callback 保持 V55 历史双存储审计行在 V58 回填时可升级，未改写冻结迁移。
- 主要前端：`content.ts`、`useSearch.ts`、`SearchPage.vue`、`GlobalSearch.vue`、`TagPage.vue`、OpenAPI 快照和生成类型。
- 保留既有未提交的用户部署文件 `.github/workflows/deploy-fast.yml` 与 `docs/deploy-fast.md`，不纳入 M9 提交。

## 验证证据

- `mvn -q "-DskipTests" compile`：通过。
- `mvn -q clean test`：通过；后端 103 个测试类、803 个测试，0 failures / 0 errors。
- `mvn -q "-Dtest=StagedResourceMigrationTest" test`：通过；验证 V55 历史双存储行在 V58 升级时仍可审计且新双存储写入继续被拒绝。
- `mvn -q "-Dtest=SearchQueryNormalizerTest,SearchServiceTest,AiToolOrchestratorTest,OpenAiResponsesMultimodalClientTest,BlogApiIntegrationTest#searchFiltersAndTelemetryStayServerSideAndAnonymous" test`：通过；Normalizer、SearchService、AI 工具、Responses client 与真实 MockMvc/PostgreSQL 集成用例均通过。
- M9 集成用例真实执行 V1–V60 Flyway，验证服务端 tag/date 分页计数、匿名笔记隔离、telemetry UUID、SHA-256 不含原文、click position 和 zero-result。
- `mvn -q "-Dtest=AiMultimodalPlatformIntegrationTest#openApiPublishesTheAiPlatformContract" "-Dai.openapi.output=../frontend/openapi/blog-api.json" test`：通过，契约由真实 Springdoc 输出更新。
- `npm test -- --run`：通过；71 个测试文件、822 个测试。
- `npm run lint`、`npm run build`、`npm run test:e2e:offline`：通过；离线 Chromium 3 个测试，构建 48 个 JS 文件、1,481,913 bytes，预渲染 7 个 SEO/noindex 路由。
- `npm run api:types`、`npm run test:typecheck`、`npm run format:check`、`npm test -- --run src/test/ProductIteration.test.ts`：通过；前端定向测试 7 个通过。
- `node scripts/verify-migration-manifest.mjs`、`git diff --check`：通过；迁移 manifest 校验到 V60。

## 评测、指标与未做项

- 固定单测覆盖同义词、错字、空白；集成夹具覆盖标题/标签/短查询、日期/标签筛选、零结果、公开/登录笔记隔离和 AI 来源只返回引用。
- 本机 PostgreSQL 18.4 的定向查询未证明 PostgreSQL 方案不足，因此没有引入 embedding、外部搜索引擎或新的索引服务；继续使用已有 trigram/LIKE 索引路径。
- 本批次没有真实网络、网页抓取或付费 AI 外呼；未做生产 p95 结论。延迟字段已记录，可在恢复的生产样本上按 hash/scope 聚合计算 p95。
- 当前 notes 数据模型没有 per-owner 字段；若未来开放多笔记 owner，需要先做独立 owner schema 与领域授权，再扩展 AI 检索，不得把当前登录边界误称为多 owner 隔离。

## 回滚与下一步

- V60 仅新增表和索引，代码可回滚至 V53 以上旧版本；telemetry 表可保留，不影响旧搜索响应（响应新增字段可空）。
- 新增搜索点击接口仅写入已存在事件且位置限制为 1–100，重复点击保持首次位置；失败不阻断内容导航。
- M9 退出条件已满足，下一月允许启动：YES（M10 图谱关系层）。Production GA：NO，继续等待 M12 灾备、性能、可访问性与发布门。

建议提交切片：`feat(search): unify retrieval contract and privacy-safe telemetry`
