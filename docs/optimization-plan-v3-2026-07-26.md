# 余白博客 · 优化与功能拓展计划 v3

基于 2026\-07\-26 全量复审 · **已批准，执行中**（新决策：NF-2 两步走；series 第二期实现；浏览量做真实统计；删除 Cloudflare worker；SEO 预渲染时点待第一期后决定） · 覆盖后端 / 前端 / 数据库 / 部署 / CI

## 一、审查范围与方法

本轮对仓库进行了独立全量审查：后端 94 个主源码文件与 16 个测试类（Spring Boot 3.5 / Java 21 / JPA / Flyway V1–V14）、前端全部 SFC / store / composable / 测试（Vue 3.5 / TS / Vite 8 / Pinia / Tiptap / PWA）、14 个数据库迁移、nginx 与 systemd 部署配置、GitHub Actions CI，以及 docs/ 下全部既有文档。所有结论均在当前工作区代码中逐条核实过（含今日最新改动）。

## 二、与既有计划的关系

docs/optimization\-and\-roadmap\-2026\-07\-26.md（下称「原计划」）今日已批准并标注执行中。经代码核对：**原计划的 P0/P1/P2 各项均尚未落地**——`SecurityConfiguration` 仍是 `anyRequest().permitAll()` 兜底、点赞/收藏仍是读\-改\-写、`toggleFavorite` 仍只增不减、附件仍只校验客户端 Content\-Type。因此本文是原计划的**修订与扩充版**：完整继承其条目与已批准的五项决策（收藏改纯计数、接受列表瘦身、第一期不做评论、文章存储改 Markdown \+ 工具栏编辑器、允许安装 PG 扩展），并新增本轮发现的 28 个问题，其中 3 个为原计划未覆盖的 P0 级缺陷，均在前端。批准后本文取代原计划作为唯一执行依据。

## 三、功能全景（现状盘点）

| 模块 | 公开能力 | 管理能力 | 状态 |
| --- | --- | --- | --- |
| 文章 | 分页列表、详情、点赞、统计、分类聚合 | CRUD（正文为原始 HTML 文本域） | 可用；浏览量恒为 0 |
| 学习笔记 | 已发布列表/详情、笔记图片 | 工作台：双模编辑、乐观锁、导入导出、附件 | 功能最完整的模块 |
| 菜谱 | 列表、详情、收藏、榜单 | CRUD | 收藏语义待改（已批准） |
| 搜索 | GET 分组搜索 \+ POST 分页搜索 | — | LIKE 全表扫描，待升级 |
| 知识图谱 | 全量节点/边（文章\+笔记\+菜谱\+标签） | — | 无缓存、全文加载 |
| 音乐 / 语录 | 曲目列表、语录 | 无管理接口（仅种子数据） | quotes/daily 实际返回全表 |
| AI 助手 | — | DeepSeek 对话（限额、脱敏） | 非流式 |
| SEO | sitemap.xml、robots.txt、JSON\-LD、meta | — | **生产环境不可达（ND\-1）** |
| 合集 series | 仅数据库表（V11） | 无任何 Java/前端代码引用 | 半成品，需决策 |

前端路由：/、/articles(\+detail)、/notes、/recipes、/archive(时间轴/图谱)、/about、/admin(login/总览/notes/ai)。测试基线：后端 139/139，前端 130/130 通过；CI 中 npm audit 步骤预期失败（上游传递依赖）。

## 四、本轮新增发现

以下为原计划未覆盖的问题，编号 N\-\*，已逐条在代码中验证。

### 4\.1 新增 P0（安全与正确性）

**NF\-1 · 登录状态双轨导致重定向死循环。** 路由守卫读 Pinia `useAuthStore`（stores/auth.ts），而登录组件调用的是 api/admin.ts 的 `saveAdminSession()`，只写 sessionStorage；store 仅在实例化瞬间读一次 sessionStorage，其 `saveSession` 在生产代码中零调用（仅测试引用），二者永不同步。复现路径：先访问 /admin 被弹回登录页（此时 store 已以「未登录」实例化）→ 登录成功写入 sessionStorage → `router.replace('/admin')` → 守卫仍读到过期的未登录状态 → 弹回登录页 → 登录页 onMounted 检测到有效会话又跳 /admin，两页互相 replace 直至 vue\-router 中止。修复：登录/登出统一经 authStore 单一事实源，api 拦截器从 store 取 token，并补守卫与登录流程测试。

**NF\-2 · 文章正文 v\-html 无前端消毒。** ArticlePage.vue:174 直接以 `v-html` 渲染后端返回的 HTML，前端无 DOMPurify（package.json 无此依赖）。后端写入时虽有 jsoup 白名单，但管理端以原始 HTML 文本域撰写正文，依赖链任何一环出问题即成存储型 XSS，可直接窃取 sessionStorage 中的管理 token。短期：引入 DOMPurify 包裹该渲染点（约半天）；根治：随已批准的 Markdown 存储迁移统一「Markdown → 受控渲染」管线，前后端双层防线。

**NF\-3 · currentPost 非响应式，跳转渲染旧文章。** contentStore.ts:41 的 `currentPost` computed 从 `window.location.pathname` 解析 slug——它不是响应式依赖；详情又只在 ArticlePage onMounted 拉取、无 slug watch。从文章 A 点击相关文章 B 时，computed 依赖不变化，旧文章正文/标题/JSON\-LD 挂在新 URL 下继续展示。修复：以 `route.params.slug` 为响应式输入，watch slug 重拉详情，并补 contentStore 测试。

### 4\.2 新增 P1（重要）

| \# | 问题 | 位置 | 方案 |
| --- | --- | --- | --- |
| ND\-1 | **生产 nginx 未转发 /sitemap.xml 与 /robots.txt**，两者落入 SPA fallback 返回 index.html——后端整套 SEO 输出在线上不可达 | deploy/hxnf.top.nginx（仅 /api/ 转发） | 增加两条 `location =` proxy\_pass；上线后 curl 验证 |
| NB\-1 | 分类页查询 (category\_slug, status) 无索引 | PostRepository、V1/V9 迁移 | Flyway V15：复合索引 |
| NB\-3 | .env.example 的 JWT 占位符本身 ≥32 字符，可通过启动长度校验——照抄模板即以公开已知密钥上线 | SecurityConfiguration、.env.example | 启动时拒绝已知占位串 |
| NF\-4 | 内置 5 篇假文章：API 未返回前生产用户先看到假内容；API 返回空列表时假文章继续保留 | data.ts、contentStore.ts:8,73 | 种子数据仅 dev 门控；生产改骨架屏 \+ 空态 |
| NF\-5 | 列表一次性只取 50 条并前端分页，第 51 篇起永远不可见 | contentStore、ArchivePage | 改真分页（与 P1\-2 联动改造） |
| NF\-6 | PWA 预缓存 1.9MB 首页大图；manifest 图标与 favicon 指向 2.2MB 非方形 og.png | vite.config.ts、index.html | 大图移 runtimeCaching；重制图标；og:image 绝对 URL |
| NF\-7 | 三个组件绕过统一 API 层，raw fetch \+ 硬编码路径 | KnowledgeGraph、AmbientSound、InspirationCard | 迁回 api/content.ts |
| NF\-10 | styles.css 1,948 行单文件全路由加载，阻塞首绘（P1\-7 只覆盖 JS） | src/styles.css、main.ts | 拆分五个文件，后台样式随路由按需加载 |
| NF\-8 | 错误态与可访问性缺口：归档部分失败静默；灯箱 Esc 永不触发、无焦点陷阱；播放器无 aria\-label | ArchivePage、ArticlePage、AmbientSound | 按清单逐项补齐（见执行计划） |

### 4\.3 新增 P2（工程质量）

| \# | 问题 | 方案 |
| --- | --- | --- |
| NB\-2 | series 合集表（V11）零代码引用，纯 schema 半成品 | **待决策**：第二期实现，或 V15 删表 |
| NB\-4 | 附件图片解压炸弹：ImageIO.read 无尺寸预检，可致 OOM | 解码前用 ImageReader 校验宽高上限 |
| NB\-5 | graph/search/sitemap 为出标题而加载全文，无投影 | 接口投影 \+ 配合 P1\-5 缓存 |
| NB\-6 | quotes/daily 名不副实，每次返回全表 | 按日确定性选取，或改名 |
| NB\-7 | 缓存拦截器错配：stats/favorites 计数被 5 分钟 public 缓存冻结；quotes/graph 无缓存头 | 按端点精细化 Cache\-Control |
| NB\-8 | 后端三处硬编码前端 URL 形态 | 抽取统一 URL builder（RSS 前置条件） |
| NB\-9 | 演示数据内嵌 schema 迁移（V1/V6/V13/V14），生产库必带演示内容 | 新库 seed profile 分离；存量库不动 |
| NB\-11 | 契约小缺陷：NoteRequest.status 被静默忽略；201 响应 code\=200；GET/POST search 结构不一致 | 随 P2\-1/P2\-2 一并修正 |
| ND\-2 | Cloudflare worker 与 nginx 部署双轨，worker 无 /api 路由不可用 | 决策弃用则删 worker/，或补文档 |
| ND\-4 | 仓库杂物：vite\-live\*.log、README.md\~、outputs/ 入库 | 删除 \+ .gitignore（并入 P2\-7） |
| NF\-9 | 前端死代码：uiStore.menuOpen、data.ts categories、FoodSection 幽灵收藏、styles.css 死样式段、无效 preconnect | 清理（并入 P2\-7） |
| NF\-11 | 播放器 audio.loop\=true 使自动切歌不可能；兜底曲目外链 Pixabay | 改 onended 切歌；外链曲目本地化 |
| NF\-12 | 菜谱份量缩放基准硬编码为 2 | dishes 表加 base\_servings 字段 |

## 五、优化阶段执行计划（修订版）

顺序执行，每项独立提交、测试先行。原计划编号照旧，新增项以 N\-\* 插入对应梯队。

**阶段 0 · 安全与正确性（约 3–4 天）**

1. 原 P0\-1 \~ P0\-10 全部照原计划执行（denyAll 兜底、双端限流、原子计数、笔记消毒验证、magic\-byte 嗅探、收藏改纯计数、强口令、LIKE 转义、HSTS）。
2. NF\-1 登录状态合一（0.5 天）——修复即解锁后台可用性，建议列全阶段第一位。
3. NF\-2 DOMPurify 最小接入（0.5 天，长期方案随 Markdown 迁移）。
4. NF\-3 currentPost 响应式修复 \+ slug watch（0.5 天）。
5. NB\-3 拒绝占位 JWT 密钥（0.25 天）。
6. ND\-1 nginx sitemap/robots 转发（0.25 天，改动一行级，随本阶段一并上线）。

验收门：全部后端/前端测试绿；新增并发计数测试、伪造 Content\-Type 上传拒绝测试、登录跳转 E2E 式组件测试通过；线上 curl 验证 sitemap.xml 返回 XML。

**阶段 1 · 性能（约 4–5 天）**

1. 原 P1\-1 \~ P1\-8 照原计划（@BatchSize、列表 Summary DTO、消毒一次、pg\_trgm、Caffeine 缓存、附件 immutable 缓存、编辑器分块、浏览量落地）。
2. NB\-1 category\_slug 索引（随 pg\_trgm 同一个迁移窗口）。
3. NB\-5 graph/search/sitemap 投影化（与 P1\-5 缓存同批改，避免二次返工）。
4. NF\-5 真分页（与 P1\-2 列表瘦身是同一次前后端联动改造）。
5. NF\-6 PWA 预缓存与图标修复 \+ public/ 图片压缩（5.9MB → 预计 \<1MB：hero 转 WebP、菜品图缩到展示尺寸、og.png 重制 1200×630）。
6. NF\-10 styles.css 拆分按路由加载。
7. NF\-7 统一 API 访问层。

验收门：SQL 日志确认列表页查询 ≤3 条；首页传输体积对比记录在 checkpoint；Lighthouse 移动端性能分留档基线。

**阶段 2 · 工程质量（约 3–4 天）**

1. 原 P2\-1 \~ P2\-10 照原计划（统一错误格式、参数校验、springdoc、Testcontainers、JaCoCo、audit 解阻、死代码、AI 校验收敛、请求日志、认证单测）。
2. NF\-4 种子数据 dev 门控 \+ 骨架屏/空态。
3. NF\-8 错误态与可访问性清单：归档部分失败提示、灯箱焦点管理、播放器 aria\-label、导航装饰图标 aria\-hidden。
4. NB\-6 / NB\-7 / NB\-11 契约与缓存头修正。
5. ND\-2 / ND\-4 / NF\-9 部署双轨决断与仓库清理。
6. 前端补测：authStore \+ 路由守卫、contentStore、AdminDashboard、AdminLogin。

**优化阶段合计约 10–13 个工作日**（原计划估 5–8 天，未含新增 14 项与前端补测）。

## 六、功能拓展阶段（长期，修订版）

继承已批准决策：第一期不做评论；文章存储迁移 Markdown。四期结构如下，每期末出 checkpoint。

**第一期 · 渲染管线统一与内容体验（2–3 周）**

1. **文章 Markdown 化（本期主项，已批准）**：posts 表增 markdown\_content（V15\+），存量 HTML 迁移（jsoup 辅助转换 \+ 人工校对清单）；服务端渲染为消毒 HTML 或前端受控渲染（建议后者，与笔记管线统一）；管理端文章编辑器复用 TyporaEditor，按决策补齐工具栏按钮（标题/有序无序列表/代码块等）。这是 NF\-2 的根治方案，也解除 AdminDashboard 原始 HTML 文本域的风险。
2. RSS/Atom（依赖 NB\-8 URL builder；复用 sitemap 的 StAX 模式；nginx 同步加转发）。
3. 相邻文章导航（详情响应带 prev/next）。
4. 浏览量真实统计（P1\-8 落地后扩展到笔记/菜谱）。
5. 文章 TOC 与阅读进度：**核对说明**——前端 ArticlePage 已有 TOC scrollspy 与进度条实现，本项调整为「Markdown 迁移后基于 AST 重建 TOC」，工作量下调。

**第二期 · 管理端增强（2–3 周）**

1. series 合集功能（若决策为「做」）：实体/CRUD/公开端 \+ 文章页合集导航 \+ 图谱/站点地图接入；polymorphic 引用需补应用层完整性删除钩子。
2. 草稿版本历史（保留最近 N 版）。
3. 仪表盘增强：访问趋势（依赖浏览量数据）、存储占用。
4. 图片管理：附件总览、孤儿附件清理任务。
5. AI 助手增强：SSE 流式输出、场景化动作（摘要/标题/标签建议，可直接注入文章编辑器）。
6. **核对说明**：原计划所列「菜谱管理 UI」已存在（AdminDashboard 含菜品 CRUD），从清单移除。

**第三期 · 检索与发现（2–3 周）**

1. 中文全文检索：pg\_trgm 之上评估 zhparser/pgroonga（决策⑤已允许装扩展；建议先在服务器验证安装可行性再排期）；搜索结果高亮。
2. 标签一等公民：/tags/:tag 聚合页 \+ post\_tags/learning\_note\_tags 的 tag 列索引（当前无索引）。
3. 图谱增强：局部子图、双击展开。
4. 相关内容推荐（共享 tag 简单版，文章详情页底部）。

**第四期 · 平台化与运维（长期迭代）**

1. CD 流水线：Actions 构建 → rsync → systemd 重启；outputs/ 移出 git。
2. SEO 预渲染：纯 CSR 下社交爬虫拿不到 OG/meta（ND\-5），建议 vite\-ssg 对公开页 SSG，或 nginx 侧为爬虫 UA 注入 meta 的轻方案；**建议评估提前到第二期之后**，它是内容型站点的核心收益。
3. 附件对象存储/本地磁盘 \+ nginx 直出（bytea 规模化前置项）。
4. 监控：Prometheus 指标、慢查询日志、错误追踪。
5. 多用户/角色：refresh token、roles 去硬编码。
6. i18n 长期搁置（全站中文硬编码，改造成本高、暂无需求）。

## 七、执行原则

沿用原计划六条：严格先优化后拓展；每项独立提交（feat/fix/test \+ 中文摘要）；测试先行；迁移只增不改（V15\+）；公开 API 不做未经批准的破坏性变更；每阶段更新文档并出 checkpoint。补充第七条：**每阶段结束跑一次 Lighthouse 与 EXPLAIN 留档**，让性能改进可量化回溯。

## 八、待你决策的问题

1. **series 合集**：第二期实现，还是 V15 删表止损？（当前是零代码引用的孤立 schema）
2. **NF\-2 节奏**：接受「阶段 0 先上 DOMPurify、第一期 Markdown 迁移根治」的两步走，还是直接等第一期一步到位（期间保持现状风险）？
3. **Cloudflare worker**：生产既然走 nginx，frontend/worker 与相关脚本是否删除？
4. **SEO 预渲染**：是否同意从第四期提前，作为第一期 Markdown 迁移后的下一优先项？
5. **原计划 P1\-8 浏览量**：确认做真实统计（详情读取原子 \+1），还是删除 viewsCount 字段？
