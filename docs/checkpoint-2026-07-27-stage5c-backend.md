# Checkpoint · 阶段 5C 图谱增强后端半程（2026-07-27）

> 依据：docs/optimization-plan-v5-2026-07-27.md §4.3 5C。本档记录 5C 后端子图端点 + 缓存头修复 + 测试的落地状态。
> 本会话在已有未提交代码基础上增量完成，遵循「不 reset/checkout/stash/还原」纪律。
> 非本会话移入的改动（docs/optimization-plan-v5-2026-07-27.md 的移交快照）原样保留、不编辑、不归为成果。

## 1. 完成项

### 1.1 固化子图端点

`GET /api/v1/graph/nodes/{center}?depth=1..3`

- `GraphController.getSubgraph()` — `@RequestParam(defaultValue = "2") @Min(1) @Max(3) int depth`
- 默认 depth=2；非法 depth（0/4 等）由 `ConstraintViolationException` → `GlobalExceptionHandler` → 400
- 未知 center 由 `extractSubgraph` 抛 `NotFoundException` → `GlobalExceptionHandler` → 404
- 复用 `buildGraph(CurrentUser.isAuthenticated())` 的 P1-5 Caffeine 缓存

### 1.2 BFS 无向子图

`GraphService.extractSubgraph(GraphResponse graph, String centerId, int depth)`（纯函数 static）

- `LinkedHashSet` 保序 BFS；边过滤：两端均在 visited 节点集内
- 整图确定性顺序保持（`TYPE_ORDER` + `NODE_ORDER`/`EDGE_ORDER` 已由 buildGraph 保证）

### 1.3 L-16 身份隔离

- 子图端点在 controller 层调用 `buildGraph(CurrentUser.isAuthenticated())`，游客图没有 NOTE 节点
- 游客请求 `n-*` center → NotFoundException → 404（对游客不可见即「不存在」）
- 登录用户 subgraph 内含 NOTE 节点及其边

### 1.4 图谱缓存头修复（5C 缓存风险）

`WebConfiguration.java` 修改：

- 图谱端点添加 `Vary: Authorization`（保留已有 Vary 头不覆盖）
- 游客（CurrentUser.isAuthenticated()=false）：`public, max-age=300`
- 登录用户（CurrentUser.isAuthenticated()=true）：`private, max-age=300`
- 全图 `/api/v1/graph/nodes` 与子图 `/api/v1/graph/nodes/{center}` 一致
- 其他端点（posts/dishes/notes 等）策略不变

### 1.5 测试

**GraphServiceTest（纯函数，Mockito）** — 新增 3 用例 + 已有 4 子图用例保持：

| 测试 | 覆盖 |
|------|------|
| `subgraphEdgesOnlyConnectNodesWithinResult` | 边两端均在子图节点集；同类 center 跨 hub 可达，异类不可达 |
| `subgraphOnGuestGraphHidesNoteCenters` | 游客图 `n-*` center → NotFound；POST center 正常 |
| `subgraphDepthFourReachesFurtherNodes` | depth=1/2/4 递增覆盖验证（实际四跳链需 depth=4） |

已有子图用例（来自移交前在途代码）：`subgraphDepthOneKeepsCenterAndDirectNeighborsOnly`、`subgraphDepthTwoReachesPostsSharingAHub`、`subgraphUnknownCenterThrowsNotFound`。

**BlogApiIntegrationTest（端点集成，`@Order(61)`、`@Order(62)`）**：

| @Order | 覆盖 |
|--------|------|
| 61 | 默认 depth=2 返回、显式 depth=1 少于 2、depth 0/4 → 400、未知 center → 404、游客 n-* center → 404 |
| 62 | 全图游客 public + Vary:Authorization、全图登录 private + Vary:Authorization、子图游客 public + Vary、子图登录 private + Vary |

**旧全图不回归**：`@Order(43) guestVisibilityLockdownHidesNotesEverywhere` 已验证全图 NOTE 隔离。

## 2. 验证结果

```
mvn --batch-mode -Dtest=GraphServiceTest test → 14/14 ✅
mvn --batch-mode test                          → 379/379 ✅
git diff --check                               → 无警告
```

## 3. 契约变更（增量，无破坏性）

| 端点 | 变更 |
|------|------|
| `GET /api/v1/graph/nodes/{center}?depth=2` | **新增**。默认 2，合法 1–3。返回 `ApiResponse<GraphResponse>`（data.nodes + data.edges）。 |
| `GET /api/v1/graph/nodes`（已有） | 响应增加 `Vary: Authorization`；游客 `Cache-Control: public, max-age=300`；登录 `Cache-Control: private, max-age=300`。 |

### 错误码

| 条件 | HTTP | body.status |
|------|------|-------------|
| 未知 center | 404 | 404 |
| depth < 1 或 > 3 | 400 | 400 |
| depth 非数字 | 400 | 400 |
| 游客请求 n-* center | 404 | 404 |

### 身份边界

| 身份 | 全图 | 子图 |
|------|------|------|
| 游客 | 无 NOTE 节点 | 不可达 `n-*` center，POST/DISH/SERIES center 返回无 NOTE 子图 |
| 登录（任意角色） | 含 NOTE | 可达 `n-*` center |

### 缓存策略

| 身份 | Cache-Control | Vary |
|------|---------------|------|
| 游客 | `public, max-age=300` | `Authorization`（追加） |
| 登录 | `private, max-age=300` | `Authorization`（追加） |

## 4. 前端交接

- **子图端点**：`GET /api/v1/graph/nodes/{centerId}?depth=N` — 响应包络与全图一致（`GraphResponse`），前端调用时传当前聚焦节点 id 与期望深度
- **身份传递**：`/api/v1/graph/nodes/{center}` 随 Authorization header 自动区分；前端带 token 登录用户、不带 token 游客，无需额外参数
- **缓存**：前端 fetch 不需额外缓存头处理，CDN / nginx 需按 `Vary: Authorization` 正确分缓

## 5. 不涉及（明确排除）

- 本阶段不涉及前端改动
- 不涉及数据库迁移（复用已有 V25 及以下）
- 不涉及依赖变更（零新依赖）
- 不涉及 SecurityConfiguration 变动（graph 已在 permitAll）
- 不涉及 docs/optimization-plan-v5-2026-07-27.md 的编辑（移交快照改动原样保留）

## 6. 下一步

- 5D 相关推荐（未开工，移交 v6 第一阶段）
- 前端 5C：双击/键盘展开、>300 节点自动局部模式、保留 relation 交互（L-13/D-15 已移类型 tab/type 参数）
