# P1-2 前端联动 + NF-5 真分页检查点（2026-07-26 · 本机会话）

执行依据：docs/optimization-plan-v4-2026-07-26.md 3.1（P1-2）/3.2（NF-5）。本轮为 **P1-2 后端契约（commit 2298b55）的前端联动半程**，并顺带清偿两个 checkpoint 的「待本机验证」欠账。

## 背景：为什么这是「正在操作的问题」

P1-2 后端已提交：公开与管理端的文章/笔记**列表不再携带正文**（PostSummary / NoteSummary），正文仅由详情接口返回。但前端仍按旧契约把列表项当全文使用，造成四处真实故障，其中两处有**数据丢失风险**：

| # | 位置 | 故障 | 严重度 |
| --- | --- | --- | --- |
| 1 | ArticlePage（经 contentStore.currentPost） | 文章详情页正文渲染为空（列表项无 content） | 站点故障 |
| 2 | AdminDashboard.editPost | 用摘要项展开填表单，content 字段残留上一次的旧值，保存即覆盖真实正文 | **数据丢失** |
| 3 | NotesWorkspace（load/selectNote/closeTab/removeCurrent） | 摘要项直接 applyNote，编辑器空白且自动保存会把空正文写回 | **数据丢失** |
| 4 | PublicNotes | 选中笔记渲染列表项 markdownContent，正文为空 | 站点故障 |

## 本轮完成项

### A. 待本机验证欠账（阶段 0 收尾 #1 + 4A-2 checkpoint）

1. `frontend && npm install`：补齐 dompurify 等依赖（此前 sanitizeHtml 测试文件因缺依赖无法加载）。
2. 4A-2 前端首次本机验证发现两处遗留并修复：
   - AdminAiChat.test.ts「停止生成」用例断言错误：send 按钮在输入清空时本应禁用（正常 UX），改为断言真正的空闲态信号（textarea 解禁、停止按钮消失、重新输入后按钮恢复可用）。
   - AdminAiChat.vue 流式空气泡字面量被 TS 拓宽导致 `vue-tsc` 报错（云端无法跑 typecheck 的预存问题），显式标注 `AiChatMessage` 修复。

### B. P1-2 前端联动（详情按需拉取）

- 类型层（data.ts / api/admin.ts）：拆出 `PostSummary`（无 content）与 `AdminNoteSummary`（无 markdownContent），`Post`/`AdminNote` 继承之作为详情类型；**PostPayload 仍要求 content**，类型系统从此阻止「用摘要保存」这类回归。
- API 层：新增 `fetchAdminPost(id)`、`fetchAdminNote(id)`（对应既有 GET /admin/posts/{id}、GET /admin/notes/{id}）、`fetchCategories()`、`searchPosts()`。
- AdminDashboard.editPost：先 `fetchAdminPost` 取全文再开编辑器，失败给出错误提示且不打开表单。
- NotesWorkspace：新增 `resolveFullNote()`——列表摘要项先经详情接口补齐正文；保存/新建/导入返回的全量笔记直接使用。覆盖 load 初选、selectNote、closeTab、removeCurrent 四个入口；拉取失败不切换选中，避免空正文进入自动保存。
- PublicNotes：选中摘要项即拉详情并回写列表；正文到达前编辑器置空（不闪现上一篇内容）；meta 描述空值兜底。
- ArticlePage/contentStore：新增 `currentContent`（正文仅可能来自详情或内置种子），`ensureArticleDetail` 在列表项**无 content 时必定拉详情**（原逻辑只在 slug 不在列表时才拉）。

### C. NF-5 文章归档服务端真分页（与 P1-2 同一次改造，决策继承 v3/v4）

contentStore 重写归档数据流，四种模式：

| 模式 | 触发 | 数据来源 |
| --- | --- | --- |
| 浏览 | 默认 | `GET /posts?page&size=6&categorySlug&sort`（P1-2 新参数），分页元数据来自服务端，第 51 篇起可见 |
| 搜索 | 搜索框非空（防抖 300ms） | `POST /search {query,type:POST,page,size}` 分页，覆盖全部已发布文章（原先只能搜前 50 条） |
| 收藏 | ★ 收藏筛选 | 收藏 slug 仅存本地，全量摘要（50/页×≤20 页，会话缓存）后客户端过滤分页 |
| 回退 | dev/VITE_ALLOW_BUNDLED_CONTENT 且后端不可用 | 内置种子客户端过滤分页（行为与旧版一致） |

- ArticlesPage：URL `?page=N`（1 起）与 store 双向同步，刷新/分享落同一页；分类 tab 改由 `GET /categories` 驱动（name↔slug 映射）；搜索命中卡片对缺失的 date/readTime/tags 做模板守卫。
- 页码越界（筛选后总页数变小、URL 手填大页码）统一回夹到最后一页。
- 首页改用 `posts`（第 0 页 12 条摘要）+ `postTotal`，featuredPost 语义不变。

## 验证结果

- 前端 `npm test`：**153/153 通过**（基线 141 → 修复加载失败文件 147 → 新增 6 个回归测试 153）。新增：contentStore 5 个（摘要必拉详情、种子不重复拉取、服务端分页参数、sort=asc 映射、搜索分页映射）+ NotesWorkspace 1 个（摘要选中先取详情）。
- 前端 `npm run test:typecheck`：通过（此前因 4A-2 遗留报错）。
- 前端 `npm run build`：通过（产物含既有 katex 744KB 大 chunk，为 P1-7 已排期问题，非本轮引入）。
- 后端：本机主工作区含**另一会话正在编写的 L-7 半成品**（见下），无法直接验证。改在 **HEAD 隔离 worktree** 复跑 `mvn test`：**183 例，179 通过，4 例报错均为同一环境原因**——本机缺集成测试库（`Create PostgreSQL database 'yubai_blog_it' for user 'yubai_app'`），非代码问题；建库命令见「待用户执行」。

## 对抗性 review 与修复（24 agent 多维审查，13 条确认 / 2 条驳回）

对本轮 diff 跑了「四维审查 → 逐条对抗验证」工作流，确认缺陷已当轮修复的 7 处：

| 级别 | 缺陷 | 修复 |
| --- | --- | --- |
| critical | ensureArticleDetail 信任本地种子正文即跳过拉取——种子 slug 与后端种子重叠（clarity-by-design 等 5 个），硬刷新详情页时命中早退，随后列表被摘要替换 → 正文永久空白/误报 404 | 仅内置回退模式（usingFallback）才信任种子；后端在线路径正文一律来自详情接口；新增回归测试 |
| major | 搜索模式丢失已选分类过滤（旧 filteredPosts 为分类+关键词叠加语义） | 搜索命中按 hit.category 客户端过滤；服务端过滤需扩展 SearchRequest，立项 L-8 |
| minor | 收藏视图对 toggleFavorite 无响应（过滤移入过程式 loadArchive 后无人监听 favorites） | watch(favorites) 在收藏视图下重载 |
| minor | 搜索+收藏组合下当前页可能过滤为空而分页导航整体隐藏，后续页收藏命中不可达 | 分页导航 v-if 改判 archiveTotalPages>1 |
| minor | ensureArticleDetail 无竞态守卫，弱网乱序返回时旧文章覆盖新文章 → 正文空白 | requestId 守卫，迟到响应丢弃；新增乱序回归测试 |
| minor | 详情在途时详情页闪现「文章不存在」并写入 noindex meta | 新增 articleDetailLoading；404 呈现与 noindex 均待拉取结束才生效 |
| minor | NotesWorkspace 切换笔记的详情拉取窗口期编辑被静默丢弃；resolveFullNote 失败误标「保存失败」触发无谓离开拦截；AdminDashboard editPost 迟到响应可覆盖新建表单 | selectNote/closeTab 在 applyNote 前二次 flush；失败只提示不动 saveState；editPost 加请求令牌 + editorOpen 守卫 |

确认但**移交计划立项**（本轮不修，见 v5 计划）：featuredPost 仅在最近 12 篇内检索（需后端 featured 查询参数）；返回 /articles 丢失页码（URL 语义取舍）；搜索命中伪 date/readTime 渗入 knownPosts（已在文章头模板守卫，根治需搜索契约补字段）。驳回 2 条（属既有行为且本轮未恶化）。

修复后全量复跑：**前端 165/165 通过、typecheck 通过**（数字含并行会话同期新增的登录相关测试文件）。

## 已知取舍（记录在案，不阻塞）

1. 搜索模式卡片缺 date/readTime/tags（SearchResult 未携带），模板按空值隐藏；如需补齐属后端契约扩展，另行排期。
2. relatedPosts 改从「本会话已见摘要」中匹配，覆盖面小于旧版的 50 条全集；阶段 5 的 5D 相关推荐会根治（服务端聚合）。
3. ArchivePage 时间轴（三类内容合并视图）维持一次 50 条上限——合并流的服务端分页需要后端合并 feed，超出 NF-5 范围。
4. 收藏模式全量摘要上限 50×20=1000 篇，超出后收藏视图不完整（当前文章量级下不可达）。

## 并行会话提醒（重要）

本会话进行中（23:33–23:35），另一 agent 会话向主工作区写入了 **L-7 登录人机验证** 的未完成实现（backend/src/main/java/com/yubai/blog/auth/ 下 7 个新文件 + AuthController/LoginRequest/BlogApiIntegrationTest 修改，均未提交）。当前该半成品会导致主工作区 `mvn test` 上下文加载失败（ChallengeService 无法实例化），属预期中的在途状态。本轮提交**只暂存 frontend/ 与 docs/ 本文件**，未触碰任何 backend 在途文件。

## 待用户执行

1. 本机建集成测试库（一次性，之后 `mvn test` 可全绿）：
   ```
   psql -U postgres -c "CREATE DATABASE yubai_blog_it OWNER yubai_app;"
   ```
   若本机尚无 `yubai_app` 用户，先 `CREATE USER yubai_app LOGIN PASSWORD '<口令>'` 并在 backend/.env.properties 配 `DB_USERNAME/DB_PASSWORD`。
2. 阶段 0 收尾清单第 2、3 条（P0-8 口令轮换、生产 nginx reload 验证）仍未销账。

## 下一步

按阶段 1 顺序：P1-3（读路径去重复消毒）→ P1-4/NB-1（V16 起的 pg_trgm+索引迁移，注意 V15 已被 4A 占用）→ P1-5/NB-5（Caffeine 缓存+投影）→ P1-6 → P1-8；前端侧 NF-6/NF-7/NF-10/P1-7/L-3。L-7 由并行会话继续。
