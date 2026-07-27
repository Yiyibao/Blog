# Checkpoint · 阶段 5C 图谱增强前端半程（2026-07-27）

> 依据：docs/optimization-plan-v5-2026-07-27.md §4.3 5C。本档记录 5C 前端子图交互 + API 鉴权 + 测试的落地状态。
> 后端子图端点契约已固化于 docs/checkpoint-2026-07-27-stage5c-backend.md。
> 本会话在前端工作区增量完成，不提交。

## 1. 完成项

### 1.1 API 层（content.ts）

- `fetchGraphSubgraph(center, depth=2)` — `center` 经 `encodeURIComponent`，`depth` 默认 2，复用 `GraphApiNode`/`GraphApiEdge` 类型
- axios 请求拦截器：`/graph/nodes` 路径自动注入 `Authorization: Bearer <token>`（authStore.isAuthenticated 时）
- 过期 token 由 authStore.clearSession() 自动清理，拦截器不发送 Authorization
- 非图谱 API（posts/dishes/categories/search 等）不受影响

### 1.2 useGraphSubgraph composable（composables/useGraphSubgraph.ts）

- `useRequestToken` 序列号竞态守卫：`expandSubgraph` 调用前 `next()`，响应落地时 `isCurrent(seq)` 校验，迟到响应静默丢弃
- 去重合并：节点按 id 去重，边按 `source|target` 去重
- 快照：`saveSnapshot(nodes, edges)` 记录首次全图，`restoreOverview()` 返回快照
- >300 自动局部模式：`findStableCenter()` 选取最高度 TAG（平局按 id localeCompare 升序），`autoLocalSubgraph()` 请求 depth=2 只为首次渲染
- 状态暴露：subgraphLoading、subgraphError、subgraphActive、localMode、localModeCenter

### 1.3 KnowledgeGraph.vue 交互

- **双击节点**：`handleNodeDblClick` → 选中该节点 → 调用 `expandSubgraph(node.id, 2)`
- **选择面板展开按钮**：`展开两层关联`，disabled 态，键盘/触屏可用
- **返回全图概览按钮**：subgraphActive 时工具栏显示 `← 全图概览`，调用 `doReturnToOverview` 恢复快照并 resetView
- **aria-live 状态宣告**：`aria-live="polite"` 区域动态播报加载/错误/局部模式状态
- **局部模式指示**：>300 自动切换时显示横幅「局部图谱模式 · 聚焦「xxx」」+ 返回全图按钮
- **loading/error 覆盖**：subgraphLoading 显示「正在展开关联节点…」；subgraphError 显示错误信息 + 重试按钮；失败保留原图
- **expandedFromIds 保活**：子图新增节点不受 MAX_DISPLAY_NODES=40 截断

### 1.4 >300 自动局部模式

- 全图加载完成后检测 `nodes.length > 300`
- 首次渲染前（loading 期间）调用 `autoLocalSubgraph`，首帧仅挂载子图 SVG 节点
- 稳定中心选取策略：`findStableCenter` — TAG 节点按度降序，平局按 id localeCompare 升序
- 若子图加载失败或无可选 TAG 中心，回退显示全图

### 1.5 测试

**KnowledgeGraph.test.ts**（新增 11 用例 + 原有 6 不回归）：

| 测试 | 覆盖 |
|------|------|
| 双击节点触发子图请求 depth=2 | 双击事件 → `fetchGraphSubgraph('p1', 2)` |
| 展开两层关联按钮触发子图 | 按钮点击 → `fetchGraphSubgraph('p1', 2)` |
| 子图节点去重合并：重复 id 不新增 | 重复 id 节点只渲染一次 |
| 子图失败保留原图 | mock 拒绝 → 错误文案显示 + 原图节点仍在 |
| 返回全图概览恢复首次快照 | 展开后点击返回 → 节点数恢复 |
| useRequestToken 序列号守卫 | 序列号机制丢弃迟到响应 |
| expandSubgraph 逆序丢弃迟到响应 | 两个可控 Promise 直接调用实际 expandSubgraph：第一请求先发后完成，第二后发先完成，断言第二结果落地、第一为 null |
| findStableCenter 平局规则 | 最高度优先，同度按 id 升序 localeCompare < 0 |
| >300 自动局部模式 | 310 节点含 3 TAG，最高度 TAG 选中，子图不超 40，局部提示，返回全图按 MAX_DISPLAY_NODES 渲染 |
| API encoding: real fetchGraphSubgraph 编码 | 调用真实 fetchGraphSubgraph('t 前端/空格',2)，断言 axios URL 精确编码，params.depth=2 |
| API: depth 参数默认 2 | 默认 depth=2 传入 |

**GraphAuth.test.ts**（新增 6 用例）：

| 测试 | 覆盖 |
|------|------|
| 游客调用 fetchGraphSubgraph 不带 Authorization | headers 无 Authorization |
| 登录用户调用 fetchGraphSubgraph 带 Bearer token | headers 含 `Bearer my-token-123` |
| 过期 token 不携带 Authorization | 过期 token → Authorization 为空 |
| 游客调用 fetchGraphNodes 不带 Authorization | headers 无 Authorization |
| 登录用户调用 fetchGraphNodes 带 Bearer token | headers 含 `Bearer graph-node-token` |
| 非图谱 API 不携带 Authorization | fetchCategories 无 Authorization |

**ArchivePage.test.ts**（L-13 行为：8 用例全部通过，零回归）

## 2. 验证结果

```
npm test -- KnowledgeGraph.test.ts    → 17/17 ✅（原有 6 + 新增 11）
npm test -- ArchivePage.test.ts       → 8/8   ✅
npm test -- GraphAuth.test.ts         → 6/6   ✅
npm test                               → 353/353 ✅（原 336 + 新增 17）
npm run test:typecheck                 → 通过  ✅
npm run build                          → 通过  ✅
git diff --check                       → 无警告 ✅
```

## 3. 契约变更（前端层）

| 文件 | 变更 |
|------|------|
| `frontend/src/api/content.ts` | 新增 `fetchGraphSubgraph(center, depth=2)`；新增 axios 拦截器为 `/graph/nodes` 注入 Authorization |
| `frontend/src/composables/useGraphSubgraph.ts` | **新增**。子图状态管理与去重合并逻辑 |
| `frontend/src/components/KnowledgeGraph.vue` | 双击/展开按钮/返回全图/aria-live/局部模式/expandedFromIds |
| `frontend/src/test/KnowledgeGraph.test.ts` | 新增 8 子图行为测试 |
| `frontend/src/test/GraphAuth.test.ts` | **新增**。6 API 鉴权/参数测试 |

### API 鉴权契约

| 身份 | fetchGraphNodes | fetchGraphSubgraph | 其他公开 API |
|------|----------------|-------------------|-------------|
| 游客 | 无 Authorization | 无 Authorization | 无 Authorization |
| 登录（有效 token） | `Authorization: Bearer <token>` | `Authorization: Bearer <token>` | 无 Authorization |
| 登录（过期 token） | 无（authStore 已清理） | 无 | 无 |

## 4. 不涉及（明确排除）

- 本阶段不涉及后端改动（后端 5C 已于 checkpoint-backend 固化）
- 不涉及 README、docs/optimization-plan-v5、迁移脚本、deploy/.github 编辑
- 不新增 npm 依赖
- 不恢复 L-13 已删类型 tab/type URL 参数
- 无提交/push/部署

## 5. 下一步

- 5D 相关推荐（未开工，移交 v6 第一阶段）
- 分离大组件：KnowledgeGraph.vue 当前 959 行，后续阶段可按 L-6 拆 SVG 渲染/SelectionPanel 子组件

## 6. 文件清单

```
M frontend/src/api/content.ts          — fetchGraphSubgraph + axios 拦截器
M frontend/src/components/KnowledgeGraph.vue — 子图交互 UI（双击/按钮/返回/自动局部）
M frontend/src/test/KnowledgeGraph.test.ts   — 子图行为测试 8 用例
A frontend/src/composables/useGraphSubgraph.ts — 子图 composable（状态/合并/序列号）
A frontend/src/test/GraphAuth.test.ts         — API 鉴权测试 6 用例
A docs/checkpoint-2026-07-27-stage5c-frontend.md — 本档
```
