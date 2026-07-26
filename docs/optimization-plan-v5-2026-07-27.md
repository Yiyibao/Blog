# 余白博客 · 长期优化与功能拓展计划 v5

> 状态：**待批准**。批准后取代 docs/optimization-plan-v4-2026-07-26.md 成为唯一执行依据（v4 从未正式批准即被部分执行，本文以 2026-07-27 全项目实测盘点重定基线，吸收既成事实）。
>
> 定位：整合 v4 未完成部分，按**前端 / 后端 / 数据库 / 部署**四条线细化分工；修正 v4 与实际代码不符的记载（迁移号、仓库杂物、组件规模）；纳入 P1-2/NF-5 联动改造对抗性 review 移交的新条目（L-8~L-12）；并针对「多 agent 并行共写同一工作区」的实际工况新增协作纪律（执行原则第 9 条）。
>
> 排期口径：**按阶段推进，不设硬日历时限**；每项给出估时，阶段间按依赖顺序执行，阶段内可按精力灵活穿插。
>
> 权重决策（继承 2026-07-26 确认）：**创作与管理效率、性能与稳定运维、检索与知识组织**为优先方向；读者增长类（RSS、相邻导航、SEO 预渲染、评论）继续降权。
>
> 配套提示词：`.agents/V5-FRONTEND-AGENT.md`、`.agents/V5-BACKEND-AGENT.md`、`.agents/V5-ACCEPTANCE-AGENT.md`（随本文同步修订，引用条目号/迁移号/命令与本文一致）。

---

## 一、基线（2026-07-27 实测盘点）

### 1.1 技术栈与部署形态

| 线 | 技术 | 实测备注 |
| --- | --- | --- |
| 后端 | Java 21 · Spring Boot 3.5.16 · Spring Security + JWT(HS256) · Spring Data JPA · Flyway **V1–V15** · jsoup | `denyAll()` 兜底；进程内固定窗口限流（阈值硬编码 Java 常量，无 yml 配置）；open-in-view=false；分页 size 钳制 1–50 |
| 前端 | Vue 3.5 · TS(strict) · Vite 8(Rolldown) · Pinia 3 · Tiptap 3 · KaTeX · DOMPurify · vite-plugin-pwa | Node ≥22；测试 Vitest 4；无路径别名、无 manualChunks、`sourcemap: true` |
| 数据库 | PostgreSQL（本地 18.4 · CI 17 · 生产以服务器为准） | 附件 bytea 入库（JPEG 压缩至 1920/0.85）；集成测试库 `yubai_blog_it`（**本机尚未创建**，见 1.6） |
| 部署 | Ubuntu · systemd(yubai-blog.service, 加固项齐全) · nginx(hxnf.top: HSTS、登录 5r/m + challenge 15r/m 双限速、/assets 7d、.map 404、sitemap/robots 反代) · pg_dump 每日 03:20 UTC 保留 14 天（**仅本地盘**） | bootstrap-server.sh 一次性初始化脚本在库 |
| CI | 单一 ci.yml：frontend(test/typecheck/build/audit-high 阻塞) + backend(mvn test, PG17 service 容器) + quality(git diff --check) | **无 CD**；push 事件 quality 只查 HEAD~1 |

### 1.2 完成度台账（相对 v4 条目）

**已完成（有提交与测试凭据）**：

| 条目 | 内容 | 凭据 |
| --- | --- | --- |
| 阶段 0 全部 | 安全与正确性 10 项 | checkpoint-2026-07-26-phase0 |
| 4A-1 | AI 供应商注册表（V15 迁移、AES-GCM、SSRF 防护、管理端 CRUD/连通测试） | checkpoint-2026-07-26-4a-ai |
| 4A-2 | SSE 流式对话（后端 SseEmitter + 前端 fetch/ReadableStream、停止生成） | 同上；遗留的测试断言与 vue-tsc 报错已修（commit a2092b2） |
| P1-1 | `@ElementCollection` @BatchSize(50)×4 + 查询计数回归测试 | commit b974b77 |
| P1-2 | 列表摘要 DTO（前后端全程）：后端 PostSummary/NoteSummary + categorySlug/sort 参数（commit 2298b55）；前端详情按需拉取（ArticlePage/AdminDashboard/NotesWorkspace/PublicNotes 四处，堵住两处数据丢失风险） | commit 07d25a7 + checkpoint-2026-07-26-p12-nf5-frontend |
| NF-5 | 归档服务端真分页 + URL ?page=N 同步 + 搜索走 POST /search 分页 + 收藏视图全量摘要过滤 | commit 07d25a7（含 24-agent 对抗性 review 的 7 处修复） |

**在途（另一会话正在收尾，工作区未提交）**：

| 条目 | 状态 | 剩余动作 |
| --- | --- | --- |
| L-7 登录人机验证 | 三层防御（PoW 常开/失败≥3 图形码/失败≥10 冷却）后端 8 类 + 前端 Worker/AdminLogin 状态机 + nginx challenge 限速 + 24 后端用例 + 9 前端用例**均已写就**；配置键 app.auth.challenge.* 六项已入 yml | ① 本机建 `yubai_blog_it` 库后 `mvn test` 全绿验证；② 按条目拆分提交 + checkpoint；③ 验收 agent 对照 3.6 设计逐条核验（challenge 一次性/绑 IP/恒定时间比较/统一文案） |

**v4 记载与实际不符的修正**：

1. **迁移台账整体后移一位**：V15 已被 4A-1 的 ai_providers/ai_usage 占用，v4 规划的 V15~V22 全部 +1（见 3.3）。
2. outputs/ 实际**未入库**（.gitignore 已忽略，磁盘遗留 7.7MB 旧产物）——ND-4 缩水为本地清理项。
3. styles.css 已增长至 **2,867 行 / 115KB**（v4 记 1,948 行），NF-10 拆分收益更大。
4. 大组件榜首换人：KnowledgeGraph.vue 617 行 > AdminAiChat.vue 494 > AmbientSound.vue 483 > NotesWorkspace.vue 451 > FoodSection.vue 449（L-6 择机拆分对象更新）。
5. **仓库无 .gitattributes 且部分文件库内即 CRLF**（如 ArticlePage.vue 索引态为 CRLF、admin.ts 曾为 CRLF）——P2-7 必须包含一次显式 renormalize 提交，且在此之前所有 agent 改这些文件时保持其现有行尾。
6. 4A-6 **未完成**：ai_usage 表建了但全后端无写入方，daily_request_limit/daily_token_limit 列无执行代码——保留为阶段 4 待办。

### 1.3 模块现状盘点（2026-07-27）

| 模块 | 公开能力 | 管理能力 | 遗留问题（对应条目） |
| --- | --- | --- | --- |
| 文章 | 摘要分页列表(categorySlug/sort)、详情、点赞(原子+限流)、统计、分类聚合 | CRUD（列表摘要、编辑先取详情）、正文仍为原始 HTML 文本域 | 浏览量恒 0（P1-8）；读路径重复 jsoup 消毒（P1-3）；列表底层查询仍读 content 列（L-12）；假文章种子与后端种子 slug 重叠（NF-4，已升权重） |
| 学习笔记 | 摘要列表/详情、笔记图片 | 工作台：双模编辑、乐观锁、导入导出、附件(magic-byte) | 附件解压炸弹预检（NB-4）；附件响应 no-store（P1-6） |
| 菜谱 | 列表（仍全量 DTO 含食材步骤）、详情、收藏计数、榜单 | CRUD | 份量基准硬编码 2（NF-12） |
| 搜索 | GET 分组 + POST 类型化分页（LIKE 已转义） | — | LIKE 全表扫描（P1-4）；POST 无 categorySlug/排序/命中缺 date/tags（**L-8**）；GET/POST 结构不一致（NB-11） |
| 知识图谱 | 全量节点/边 | — | 每请求 3 个全表查询且加载全文、无缓存（P1-5/NB-5）；组件裸 fetch（NF-7） |
| 音乐/语录 | 曲目列表、语录 | **无管理接口**（种子含 cdn.example.com 占位外链） | quotes/daily 返回全表（NB-6）；播放器 loop 缺陷（NF-11）；L-1 管理端 |
| AI 助手 | — | 多供应商注册表 + 流式对话（独立页面） | 4A-3 供应商 UI、4A-4 侧边栏、4A-5 场景化、4A-6 用量预算（未完成） |
| 认证 | 登录 + L-7 三层人机验证（在途收尾） | — | TOTP 与多用户留阶段 6C |
| SEO | sitemap.xml、robots.txt、JSON-LD、meta | — | 线上 curl 验证仍待做（阶段 0 收尾 #3） |
| 合集 series | 仅 V11 表，零代码引用 | — | 阶段 4 实现（4B） |

前端路由与缓存拦截器现状：公开 GET 缓存头覆盖 /posts、/dishes、/notes、/categories、/search、/music（max-age=300），**未覆盖** /note-assets（no-store，P1-6）、/graph、/quotes（NB-7 扩展面）。

### 1.4 测试与验证基线

- 后端（HEAD）：21 个测试类 **210 例**；本机因缺 `yubai_blog_it` 库仅能跑 179 例（全绿），集成测试需先建库（见 1.6 待办 ①）。CI（PG17 容器）可全量。
- 前端：**165/165**（17 文件，含 L-7 在途新增 9 例）+ typecheck + build 全绿（2026-07-27 本机实测）。
- 构建产物：PWA precache **90 项 / 6,088KB**（NF-6 治理对象）；katex chunk **744KB**（P1-7）；hero-sakura-lake.png 1.91MB、og.png 2.11MB（非方形，兼任 favicon 与 manifest 图标，声明 192/512 与实际 1734×907 不符）。

### 1.5 继承的已批准决策（全部沿用）

① 收藏纯计数；② 列表瘦身（已完成）；③ 第一期不做评论；④ 文章存储改 Markdown + 工具栏编辑器；⑤ 允许安装 PG 扩展；⑥ NF-2 两步走；⑦ series 第二期；⑧ 浏览量真实统计；⑨ 删除 Cloudflare worker。

### 1.6 立即待办（不占阶段排期，多为用户操作）

1. **本机建集成测试库**（L-7 验证与今后所有后端工作的前置）：
   `psql -U postgres -c "CREATE DATABASE yubai_blog_it OWNER yubai_app;"`（无 yubai_app 用户则先建并在 backend/.env.properties 配 DB_USERNAME/DB_PASSWORD）。
2. L-7 收尾：`mvn test` 全绿 → 拆分提交 → checkpoint → 验收。
3. 阶段 0 收尾清单第 2、3 条仍未销账：P0-8 口令轮换；生产 nginx reload + `curl -I https://hxnf.top/sitemap.xml` 验证。
4. 本地磁盘清理（用户确认后执行）：_to_delete/ 511MB、outputs/ 7.7MB、根 dist/ 3.1MB、.npm-cache 206MB、frontend/vite-live*.log、README.md~。

---

## 二、总路线图

| 阶段 | 主题 | 核心产出 | 估时 | 前置 |
| --- | --- | --- | --- | --- |
| 1（剩余） | 性能优化 | P1-3/4/5/6/8、NB-1/5、L-12、NF-6/7/10、P1-7、L-3 | 3–4 天 | 1.6 待办 ① |
| 2 | 工程质量与交付流水线 | 错误格式统一、API 文档、Testcontainers、覆盖率、仓库清理(.gitattributes renormalize)、CD 流水线、NB-4/6/7/8/9/11、NF-4/8/9/11/12、L-8/L-9/L-11 | 6–8 天 | 阶段 1 |
| 3 | 渲染管线统一与创作体验 | 文章 Markdown 化（主项 3A）、TOC AST 重建（3B）、浏览量扩展（3C）、可选 RSS/相邻导航（3D） | 2–3 周 | 阶段 2 |
| 4 | 管理端增强 | 4A-3~4A-6（AI 平台化剩余）、4B series、4C 版本历史、4D 仪表盘趋势、4E 附件管理、4F 曲目/语录管理 | 3–4 周 | 阶段 3 |
| 5 | 检索与知识组织 | 中文全文检索、标签一等公民、图谱子图、相关推荐 | 2–3 周 | 阶段 4（可与尾部并行） |
| 6 | 平台化与稳定运维 | 监控告警、对象存储、多用户令牌、备份演练、SEO 预渲染（触发制） | 长期迭代池 | 按项独立 |
| 常态 | 贯穿机制 | 性能预算、依赖升级节奏、安全复审、文档惯例、**并行会话纪律** | 持续 | — |

条目号沿用 v4（P1-\*、P2-\*、NB-\*、NF-\*、ND-\*、L-1~L-7 含义不变）；本文新增 **L-8~L-12**。

---

## 三、优化方案明细（阶段 1–2）

### 3.1 后端优化

**阶段 1 剩余 · 性能（后端）**

| # | 问题（实测确认） | 位置 | 方案 | 验收 | 估时 |
| --- | --- | --- | --- | --- | --- |
| P1-3 | 读路径重复消毒：PostResponse.from 每次 `sanitizer.sanitize(content)`，而写入时 PostEntity 已消毒 | PostResponse.java:28、PostEntity.java:104 | 读路径直接返回已存储内容；保留写入消毒 + 前端 DOMPurify 双防线 | 单测确认写一次消毒、读零消毒；XSS 集成测试仍绿 | 0.25 天 |
| P1-4 | 搜索 LIKE '%..%' 对 posts.content/notes.markdownContent 全表扫描（V9 注释自证 pg_trgm 未装） | SearchService、PostRepository | **V16 迁移**：`CREATE EXTENSION pg_trgm` + 三表搜索列 GIN 索引；查询保持 LIKE 即受益 | EXPLAIN (ANALYZE) 走索引，提交说明附前后对比；搜索测试全绿 | 0.5 天 |
| NB-1 | (category_slug, status) 无复合索引 | posts 表 | 并入 V16 同一迁移窗口 | EXPLAIN 验证 | 随 P1-4 |
| P1-5 | 只读热点无缓存：GraphService 每请求 3 个全表查询、sitemap 每请求 4 查询、quotes/music 每请求 findAll | GraphService、SitemapService、QuoteService、MusicTrackService | 引入 Caffeine + `@Cacheable`（TTL 5–10 分钟）；admin 写操作 `@CacheEvict`（笔记发布/文章增删改/菜品增删改均需失效图谱与 sitemap） | 二次请求命中缓存（日志/单测验证）；写后失效有测试 | 0.5 天 |
| NB-5 | graph 为出标题而加载全文（findAllPublishedWithTags 拉整实体含 content/markdownContent）；search 命中拉整实体只为拼 excerpt | GraphService、SearchService | 接口投影（只取 id/标题/slug/时间/标签），与 P1-5 同批改造避免二次返工；sitemap 已是投影，不动 | SQL 不再 select 正文列（开 SQL 日志验证） | 0.5 天 |
| **L-12**（新增） | P1-2 后列表接口虽只序列化摘要，但底层仍是 `Page<PostEntity>`/`Page<NoteEntity>`，正文列每页照读后丢弃 | PostService.findPublished、NoteService 列表路径 | Repository 层投影查询（interface projection 或 DTO 构造器查询），列表路径彻底不触正文列 | SQL 日志确认列表查询无 content/markdown_content 列 | 0.5 天 |
| P1-6 | 附件响应 no-store：公开与管理端两个下载口均禁缓存，UUID 不可变资源每次读整段 bytea | PublicNoteAssetController:27、AdminNoteAttachmentController:45 | 公开口改 `max-age=31536000, immutable`（publicId 不可变）；管理端口可保守 max-age=3600 | 响应头断言测试 | 0.25 天 |
| P1-8 | viewsCount 死字段：V12 建列建索引、三个 DTO 外露，但全后端无写入方 | PostEntity、PostService | 详情读取时数据库端原子 +1（仿 incrementLikeCount）；复用限流器基建做 IP+slug 短窗去重（不落 IP 明文） | 并发单测计数无丢失；stats 返回真实值 | 0.5 天 |

**阶段 2 · 工程质量（后端）**

| # | 问题 | 方案 | 验收 | 估时 |
| --- | --- | --- | --- | --- |
| P2-1 | 错误响应格式并存 | 统一 ApiResponse；补 `@ExceptionHandler(Exception.class)` 与 TypeMismatch 兜底（GlobalExceptionHandler 现 11 个 handler 基础上收口） | 集成测试断言各错误场景格式一致 | 0.5 天 |
| P2-2 | 分页/limit 无声明式校验 | `@Min/@Max` + `@Validated`；SearchRequest 移除使 `@NotNull` 失效的 null 回退 | 非法参数统一 400 | 0.25 天 |
| P2-3 | 无 API 文档 | springdoc-openapi，/swagger-ui 仅 dev profile | 本地可浏览全部接口 | 0.25 天 |
| P2-4 | 集成测试依赖本机真实 PG | Testcontainers（CI 已有 Docker），保留本地快速模式（本机已建库者直连） | CI 不再手工配置 service 容器 | 1 天 |
| P2-5 | 无覆盖率工具 | JaCoCo，阈值按现状设定只升不降，CI 上传报告 | CI 产出覆盖率报告 | 0.25 天 |
| P2-8 | AI 校验散落两处 | 校验收敛 Service 一处 | 单测更新 | 0.25 天 |
| P2-9 | 无请求日志/TraceId | logback + MDC filter（requestId）；登录失败尝试全部带 requestId 落日志（衔接 L-7 审计） | 日志含 requestId | 0.25 天 |
| P2-10 | 认证模块单测（L-7 已带 24 例） | 补 JwtService 签发/过期/坏签名、AdminBootstrap 幂等 | 新增测试通过 | 0.5 天 |
| **L-8**（新增·契约扩展） | 归档搜索模式的分类过滤只能在当前页客户端补偿；命中缺 date/readTime/tags 致前端伪造空值（review 确认项） | SearchRequest 增可选 `categorySlug`、`sort`；SearchResult 增 `date`、`readTime`、`tags`（POST 分页分支实装，GET 分组分支不动）；前端透传并删除客户端过滤补偿 | 分类+关键词组合的分页计数一致；文章头不再出现空 time/0 MIN READ | 0.5 天（后端）+ 0.25 天（前端） |
| **L-9**（新增） | featuredPost 只在首页前 12 条摘要内检索，精选文章一旦出窗即回退第一篇（review 确认项） | GET /posts 增可选 `featured=true` 过滤；前端 loadRemoteContent 单独取精选（1 条）与最近列表 | 精选文章任意日期均正确展示 | 0.25 天（后端）+ 0.25 天（前端） |
| NB-4 | 附件解压炸弹：ImageIO.read 无尺寸预检 | 解码前 ImageReader 读宽高上限（≤8000×8000）再解码 | 超限图 400，单测覆盖 | 0.25 天 |
| NB-6 | quotes/daily 名不副实返回全表 | 按日确定性选取（day-of-year 取模）；与 4F 管理端同批确认 | 行为与命名一致 | 0.25 天 |
| NB-7 | 缓存拦截器错配：stats/favorites 被 5 分钟 public 缓存冻结；/graph、/quotes 无缓存头；/note-assets 由 P1-6 单独处理 | 按端点精细化：计数类（stats/favorites/like 响应）no-cache；/graph、/quotes 纳入可缓存列表（与 P1-5 服务端缓存 TTL 对齐） | 响应头逐端点断言 | 0.25 天 |
| NB-8 | 三处硬编码前端 URL 形态 | 抽统一 URL builder（RSS 前置） | 单测覆盖 | 0.25 天 |
| NB-9 | 演示数据内嵌 schema 迁移（V1/V6/V13/V14） | 新库 seed profile 分离；存量库不动；**与 NF-4 联动**（前端种子 slug 与 V1 种子重叠已引发一次 critical 级前端缺陷，根治靠双端同步去种子） | 新库可无演示内容初始化 | 0.5 天 |
| NB-11 | 契约小缺陷：NoteRequest.status 被忽略、201 响应 code=200、GET/POST search 结构不一致 | 随 P2-1/P2-2 一并修正 | 契约测试固化 | 随 P2-1 |

### 3.2 前端优化

**阶段 1 剩余 · 性能（前端）**

| # | 问题（实测确认） | 位置 | 方案 | 验收 | 估时 |
| --- | --- | --- | --- | --- | --- |
| NF-6 | PWA precache 90 项 6,088KB：globPatterns 含 png/jpg 全量；og.png 2.11MB（1734×907 非方形）兼任 favicon+manifest 图标且声明 192/512 与实际不符；hero 1.91MB；food 图 5 张最大 526KB | vite.config.ts、index.html、public/ | 大图移出 precache（globIgnores 或 runtimeCaching CacheFirst）；重制 192/512 方形图标与独立 favicon；og:image 重制 1200×630 绝对 URL；hero 转 WebP、food 图缩尺寸（目标 public/ <1MB） | 首次安装 precache 体积对比留档（当前 6,088KB 为基线） | 1 天 |
| NF-7 | 三组件裸 fetch：AmbientSound.vue:48(/music/tracks)、KnowledgeGraph.vue:65(/graph/nodes)、InspirationCard.vue:42(/quotes/daily) | 三组件 | 迁回 api/content.ts，统一错误处理与 baseURL | 组件内无裸 fetch（api 层内的 SSE fetch 除外） | 0.5 天 |
| NF-10 | styles.css 2,867 行/115KB 全路由阻塞首绘 | src/styles.css、main.ts | 拆五个文件（tokens/公共/文章笔记/美食/后台），后台样式随路由懒加载；**拆分提交同时是 CRLF renormalize 的自然时机（与 P2-7 协调）** | 首屏 CSS 体积对比留档 | 1 天 |
| P1-7 | katex chunk 744KB；无 manualChunks | vite.config.ts | manualChunks 拆 katex/tiptap；编辑器依赖仅管理路由异步加载 | 公开页首屏不含编辑器/KaTeX chunk | 0.5 天 |
| L-3 | `sourcemap: true` 产全量 .map 而 nginx 对 .map 返回 404 | vite.config.ts | 生产改 `sourcemap: 'hidden'` 或关闭；map 仅存本地 | dist 体积下降；排错流程写入 README | 0.25 天 |

**阶段 2 · 工程质量（前端）**

| # | 问题 | 方案 | 估时 |
| --- | --- | --- | --- |
| NF-4 | 内置 5 篇假文章生产可见，且 **slug 与后端 V1 种子完全重叠**（clarity-by-design 等）——本轮已实证该重叠会诱发详情渲染缺陷 | 种子仅 dev 门控（import.meta.env.DEV）；生产骨架屏+空态；与 NB-9 后端去种子联动；contentStore 的 usingFallback 分支保留 | 0.5 天 |
| NF-8 | 错误态与可访问性缺口 | 归档部分失败提示条、灯箱 Esc/焦点陷阱、播放器 aria-label、装饰图标 aria-hidden | 1 天 |
| NF-9 | 死代码（实测核准）：uiStore.menuOpen（0 引用，App.vue 另有局部同名 ref）、data.ts categories 导出（0 引用）、FoodSection favoriteDishes（只写不读）、index.html 两条无对应请求的 fonts preconnect | 删除，随 P2-7 仓库清理同批 | 0.5 天 |
| NF-11 | 播放器 audio.loop=true 无法自动切歌；V13 种子 audio_url 指向 cdn.example.com 占位 | onended 切歌；曲目本地化与 4F 管理端联动 | 0.5 天 |
| NF-12 | 菜谱份量基准硬编码 2 | **V17 迁移**加 dishes.base_servings，前端按其缩放 | 0.5 天 |
| **L-10**（新增） | NotesWorkspace 切换笔记为异步两段式后，仅靠二次 flush 缓解窗口期编辑丢失；缺完整的会话令牌机制（review 确认项的根治） | 切换流程引入 session token：进入切换即冻结编辑器输入（或缓冲输入），resolveFullNote 返回后校验 token 再 applyNote；配组件测试模拟慢详情+窗口期键入 | 0.5 天 |
| **L-11**（新增） | 文章详情「返回文章」链接指向裸 /articles，丢失来路页码（review 确认项） | 详情页记录来路 query（history.state 或 route query 透传），返回链接还原 ?page=N | 0.25 天 |
| 补测 | authStore+路由守卫、AdminDashboard、AdminLogin（L-7 已带 9 例） | 按既有惯例补 vitest | 1 天 |
| L-6（择机） | 大文件拆分对象更新：KnowledgeGraph 617 行、AmbientSound 483、NotesWorkspace 451、FoodSection 449 | 触碰即顺手拆子组件，禁纯重构大提交 | 择机 |

### 3.3 数据库迁移台账（v5 重排，自 V16 起）

原则不变：**迁移只增不改，版本号以执行时实际最高号 +1 为准**（V15 已被 ai_providers/ai_usage 占用是 v4→v5 全表后移的原因，引以为鉴）。

| 规划版本 | 内容 | 归属 |
| --- | --- | --- |
| V16 | `CREATE EXTENSION pg_trgm` + 三表搜索列 GIN 索引 + (category_slug, status) 复合索引 | 阶段 1（P1-4/NB-1） |
| V17 | dishes.base_servings | 阶段 2（NF-12） |
| V18 | posts 加 markdown_content（可空）+ content_format | 阶段 3（3A-1） |
| V19 | 存量正文迁移后的清理（是否删旧列单独评审） | 阶段 3 末 |
| V20 | post_revisions / note_revisions | 阶段 4（4C） |
| V21 | view_daily 按日聚合 | 阶段 4（4D） |
| V22 | post_tags / learning_note_tags 的 tag 列索引 | 阶段 5（5B） |
| V23+ | 中文分词（zhparser/pgroonga）对象，以 spike 结论为准 | 阶段 5（5A） |

配套惯例：含索引/扩展的迁移提交附 EXPLAIN (ANALYZE) 前后对比；series 表（V11）在 4B 实现前先复核结构，缺列以新迁移补。PG 版本策略沿用 v4（本地 18.4 / CI 锁 17，跟踪 Flyway 官方支持公告）。

### 3.4 部署与 CI/CD

**CD 流水线（阶段 2，估 1–2 天）**——方案沿用 v4 原文：tag/手动触发 + GitHub Environment 人工批准门；复用 CI 产物；rsync over SSH → 原子切换 → systemctl restart → /actuator/health 健康检查；前置（用户操作）：deploy 用户 + SSH key + GH Secrets/Environment。

**CI 与仓库清理（按 2026-07-27 实测修订）**

| # | 内容 | 方案 | 估时 |
| --- | --- | --- | --- |
| P2-6 | npm audit 高危阻塞 CI（brace-expansion 传递依赖） | audit 改非阻塞记录或仅审 production；每月复查，可修即恢复阻塞 | 0.25 天 |
| P2-7 | **无 .gitattributes、库内混有 CRLF 文件**（ArticlePage.vue 等）；README.md~、vite-live*.log 等杂物（均已被忽略但磁盘遗留） | 新增 .gitattributes（`* text=auto eol=lf`，图片/字体 binary）+ 一次显式 `git add --renormalize .` 独立提交；.gitignore 补遗；本地杂物清理入 1.6 待办 ④ | 0.5 天 |
| ND-2 | Cloudflare worker 双轨（决策⑨）：frontend/worker/index.js、frontend/scripts/{prepare-sites-build,verify-worker}.mjs、frontend/.openai/hosting.json 均仍在库 | 删除四者，核查 package.json scripts 链是否随之简化 | 0.25 天 |
| ND-4 | outputs/ 修正：未入库，仅磁盘 7.7MB 旧产物 | 并入 1.6 本地清理；CD 上线后该产物流程自然废弃 | — |
| L-2 | nginx 未启用 gzip/HTTP2 | `listen 443 ssl http2;` + gzip on（text/css/js/json/svg/xml，min 1k）；上线后对比传输体积 | 0.25 天 |
| — | quality job 盲区：push 事件只查 HEAD~1，一次推多提交时中间提交不检查 | 改为对 push 的全区间 `git diff --check`（fetch-depth 已为 0） | 0.1 天 |

### 3.5 阶段 1/2 验收门

- 阶段 1：全量测试绿；SQL 日志确认列表页 ≤3 条查询**且不含正文列**（L-12）；首页传输体积前后对比写入 checkpoint；PWA precache 体积对比（基线 6,088KB）；Lighthouse 移动端跑分留档为长期基线。
- 阶段 2：CI 全绿（含 audit 策略与 quality 区间修正）；swagger-ui 本地可用；JaCoCo 报告产出；CD 完成一次带人工批准的真实发布演练；`git ls-files --eol` 无 i/crlf 文件；仓库无 worker/、无 hosting.json；L-7 验收报告归档（若此前未完成）。

### 3.6 后台登录人机验证（L-7）——设计定稿与收尾清单

v4 3.6 的三层设计**已按图实现**（在途）：层 1 PoW 常开（难度 4、TTL 5 分钟、一次性、绑 IP、恒定时间比较）；层 2 失败≥3 图形码（Java2D 160×56、字符集剔除易混形、答案哈希存储、大小写不敏感）；层 3 失败≥10 冷却 30 分钟（429 + Retry-After）；配置化 app.auth.challenge.*（六键，env 可覆盖）；nginx challenge 15r/m 专属限速；零新依赖。收尾三步见 1.2 在途表。验收 agent 按 v4 3.6「测试与验收」小节原文逐条核验，另加两条：challenge 端点在冷却期同样 429；risk 升级后旧 POW challenge 作废（已有单测，验收复核）。

---

## 四、功能拓展明细（阶段 3–6）

### 4.1 阶段 3 · 渲染管线统一与创作体验（2–3 周）

**3A · 文章 Markdown 化（主项，决策④⑥根治步）**——五个可独立提交的子步，迁移号按 3.3 重排：

| 子步 | 内容 | 线 | 估时 |
| --- | --- | --- | --- |
| 3A-1 | **V18** 迁移：posts 加 markdown_content（可空）+ content_format('HTML'/'MARKDOWN')；实体与 DTO 双字段读写 | 数据库/后端 | 1 天 |
| 3A-2 | 存量迁移工具：jsoup 辅助 HTML→Markdown 一次性转换（admin 端点或 main 方法），产出人工校对清单（表格/公式/嵌套列表逐篇标记） | 后端 | 1–2 天 |
| 3A-3 | 管理端编辑器：AdminDashboard 弃 HTML 文本域，复用 TyporaEditor，按决策④补齐工具栏（标题/列表/代码块/引用/表格/图片） | 前端 | 2–3 天 |
| 3A-4 | 公开渲染统一：详情走「Markdown → 受控渲染」与笔记同管线（Tiptap 只读 + DOMPurify 兜底），前后端双层防线 | 前端/后端 | 1–2 天 |
| 3A-5 | 收尾：存量校对签收后读路径切 MARKDOWN；旧 HTML 列去留单独评审（**V19**） | 全 | 0.5 天+校对 |

- 测试：转换工具高风险片段快照；编辑器保存-重开往返一致；publicNotesXss 模式扩展到文章。
- 验收：新文章全程 Markdown；存量前后台视觉一致（清单签收）；DOMPurify 退为兜底。
- 风险与对策沿用 v4（双字段并存可回退）。

**3B · TOC 与阅读进度重建（0.5–1 天，前端）**：Markdown 化后基于 AST/heading 生成目录，去除对渲染 DOM 的脆弱依赖。验收：长文锚点准确、进度条不回归。

**3C · 浏览量扩展至笔记/菜谱（0.5 天，后端为主）**：P1-8 模式（原子 +1 + 短窗去重）推广到 notes/dishes。验收：三类 stats 均出真实浏览数。

**3D · 可选尾项（降权保留）**：RSS/Atom（依赖 NB-8，0.5 天）；相邻文章导航（0.5 天）。

### 4.2 阶段 4 · 管理端增强（3–4 周）

执行顺序：4A 剩余子步先行，其余按字母序，4F 可穿插。

**4A · AI 助手平台化剩余（4A-3~4A-6，约 1–1.5 周）**

| 子步 | 内容 | 线 | 估时 |
| --- | --- | --- | --- |
| 4A-3 | 供应商管理 UI：admin 设置区块——增删改、密钥只写不回显（尾 4 位）、测试连通按钮、默认模型选择（后端端点已备齐：GET/POST/PUT/DELETE + /default + /test） | 前端 | 1–1.5 天 |
| 4A-4 | 侧边栏形态：AdminAiSidebar 挂 admin 布局层，全 /admin 路由可用；可折叠、宽度可拖、移动端全屏抽屉；顶部模型切换；流式渲染+停止；会话 sessionStorage；/admin/ai 保留全屏视图复用组件；快捷键 Ctrl+Shift+A | 前端 | 2–3 天 |
| 4A-5 | 场景化动作：编辑文章/笔记时出动作 chips（总结/标题/标签/润色/续写），自动附当前内容为上下文（max-total-chars 截断）；结果一键填表单，**只填入不保存** | 前端+后端 | 2 天 |
| 4A-6 | **用量与审计补完**（表已建、代码缺位）：AI 调用写 ai_usage（时间/provider/model/tokens/时延/状态，不存消息内容）；provider 日预算检查超限 429；仪表盘用量卡片（并入 4D） | 后端 | 1 天 |

**安全设计六条**（密钥安全/SSRF/输出不可信/提示注入收敛/权限配额/可用性隔离）沿用 v4 4.2 原文，验收 agent 逐条核验；4A-1/4A-2 已实现部分（AES-GCM、BaseUrlValidator、SSE header 鉴权）在 4A-3~6 改动中不得回退。

**4B · series 合集（1 周）**：复核 V11 表结构（缺列新迁移补）；SeriesEntity/Service/Controller + admin CRUD + 公开列表/详情；接入 sitemap 与图谱（SERIES 节点）；删除钩子（删文章清关联）；前端管理页（拖拽排序）+ 详情页「本文属于合集 X（n/N）」+ 图谱识别。验收：建合集→挂文章→按序阅读全链路。

**4C · 草稿版本历史（3–4 天）**：**V20** revisions 表（保留 N=10 待确认）；保存异步写版本；admin 列表/查看/恢复（恢复=新建保存不改历史）；前端历史抽屉 + 纯文本 diff。验收：任意历史版本可恢复；乐观锁不受影响。

**4D · 仪表盘趋势与存储占用（3–4 天）**：**V21** view_daily（UPSERT 累加，180 天清理，不存 IP/UA）；stats 扩展：30 天趋势、TOP5、附件总大小、各状态计数、AI 用量卡片（4A-6）；前端纯 SVG 折线。验收：趋势与表数据一致；仪表盘一次请求出全部统计。

**4E · 附件管理与孤儿清理（2–3 天）**：admin 附件总览（分页/按笔记聚合/总大小）；孤儿判定（正文不引用 && 超 7 天）；标记→回收站→手动确认删除；前端附件页（网格/筛选/批量确认）。验收：清理后渲染无 404；总大小与 4D 一致。

**4F · L-1 曲目与语录管理（1–2 天）**：music_tracks、quotes 的 admin CRUD（V13/V14 表已在）；前端两个管理区块；顺带替换 cdn.example.com 占位外链（与 NF-11 联动）；NB-6 按日选取同批确认。验收：不改迁移即可增删曲目/语录。

### 4.3 阶段 5 · 检索与知识组织（2–3 周）

- **5A 中文全文检索（1–1.5 周，两步走）**：先 spike（0.5–1 天）验证 zhparser/pgroonga 在生产同版本 PG 的可行性；不可行回退 pg_trgm（阶段 1 已建）+ 加权排序。实施：tsvector 生成列 + GIN（**V23+**）；SearchService 向量检索 + ts_rank + ts_headline；LIKE 保留为降级。前端高亮（`<mark>` 消毒后插入）。验收：中文词组召回对比留档；高亮无 XSS。
- **5B 标签一等公民（3–4 天）**：**V22** tag 索引；/api/v1/tags 与 /tags/{tag} 聚合端点 + sitemap；前端 /tags/:tag 页、详情标签可点、图谱 TAG 节点 url 补链。
- **5C 图谱增强（3–4 天）**：局部子图端点（center+depth，默认 2），复用 P1-5 缓存与 NB-5 投影；前端双击展开、类型过滤 URL 同步、>300 节点自动子图模式（首帧 <1s）。
- **5D 相关推荐（1–2 天）**：详情响应附共享标签 TOP3~5（聚合查询+缓存）；前端底部推荐卡（**同时根治 relatedPosts 客户端窗口局限**——本轮 review 记录的已知取舍）。验收：无共享标签隐藏区块；无 N+1。

### 4.4 阶段 6 · 平台化与稳定运维（长期迭代池）

| 项 | 内容 | 触发/前置 | 估时 |
| --- | --- | --- | --- |
| 6A 监控告警 | micrometer-prometheus 暴露 /actuator/prometheus（仅内网）；PG 慢查询日志 + pg_stat_statements；外部拨测 hxnf.top 与 /actuator/health | 服务器操作授权 | 1–2 天 |
| 6B 附件对象存储 | bytea → 本地磁盘/S3 兼容 + nginx 直出；迁移工具 + 旧 UUID 路径 301；备份策略同步调整 | 附件近 GB 级或备份耗时显著 | 3–5 天 |
| 6C 多用户与令牌 | refresh token（短 access+可撤销 refresh）；roles 去硬编码；多账号与角色列；TOTP 两步验证与 L-7 衔接评估 | 第二写作者需求 | 3–4 天 |
| 6D SEO 预渲染 | vite-ssg 或 nginx 按爬虫 UA 注入 meta | 触发条件制（流量增长/主动分发） | 评估 1 天 + 实施 2–4 天 |
| 6E L-4 备份恢复演练 | 每季度干净库完整恢复 + 抽查；备份加**异机/对象存储副本**（当前 pg_dump 仅落服务器本地盘，盘故障=数据与备份同失） | 无 | 首次 0.5 天，每季 0.25 天 |
| 6F L-5 依赖升级节奏 | 每月 audit/versions 复查 patch 随手升；每季 minor 窗口；major 单独评审；跟踪 brace-expansion、Flyway×PG18、Vitest/rolldown 平台差异 | 无 | 每月 ≤0.5 天 |
| i18n | 全站中文硬编码 | 长期搁置 | — |

---

## 五、常态机制（贯穿全程）

### 5.1 性能预算（阶段 1 建基线，每阶段末复测留档）

| 指标 | 基线（2026-07-27 实测） | 目标 | 测法 |
| --- | --- | --- | --- |
| PWA precache 体积 | 90 项 / 6,088KB | <1,500KB | build 输出 |
| public/ 图片总量 | 5.84MB（og 2.11 + hero 1.91 + food 1.8） | <1MB | 磁盘统计 |
| 首页传输体积（冷缓存） | 阶段 1 开始实测记录 | 较基线 −50% | DevTools/Lighthouse |
| Lighthouse 移动端 | 阶段 1 实测记录 | ≥90 并保持 | 每阶段末留档 |
| 列表页 SQL | ≤3 条（P1-1 后） | ≤3 条且无正文列（L-12） | SQL 日志 |
| 首屏 JS（gzip） | katex chunk 744KB 现混入 | 公开页不含编辑器/KaTeX chunk | build 分析 |
| 公开详情 P95 | 6A 上监控后记录 | <200ms（本地口径） | Prometheus |

### 5.2 质量门禁

- 每次提交：改动范围相称的测试 + `git diff --check`（注意库内 CRLF 文件在 P2-7 之前的既有噪音，新增行不得引入新噪音）；前端另跑 typecheck。
- 每阶段末：`mvn test` 全量、`npm test`+build、Lighthouse + 关键 EXPLAIN 留档、checkpoint 文档。
- 覆盖率（P2-5 后）：阈值只升不降；新模块必带测试进主干。

### 5.3 安全复审（每季度 0.5 天）

沿用 v4 清单（denyAll 白名单、限流阈值、依赖 CVE、nginx 头、JWT/口令年龄、消毒库版本；AI 上线后加查密钥年龄/base_url 清单/allow-local-endpoints/ai_usage 异常；人机验证上线后加查 PoW 难度匹配度/绕过尝试模式）。新增一条：**并行会话审计**——检查是否存在„他人在途文件被误提交"（对照各 checkpoint 的文件清单抽查 `git log --stat`）。

### 5.4 机会池（不排期）

评论系统、公开站点访客 AI 问答、Webmention、站点统计对外页、PWA 离线阅读增强、图谱导出图片、菜谱购物清单导出、搜索结果页（独立路由承载 POST /search 分页，替代归档页内嵌搜索模式）。

---

## 六、执行原则

沿用 v4 八条，新增第九条：

1. 严格按阶段顺序推进，阶段内可穿插，**不跨阶段预支功能**（L-7 提前属用户明示指令的既成事实，不作先例）。
2. 每项独立提交（feat/fix/test/chore + 中文摘要）。
3. 测试先行：先补失败测试再实现。
4. 迁移只增不改（**V16+ 顺序分配，以执行时实际最高号为准**，禁动历史迁移）。
5. 公开 API 不做未经批准的破坏性变更（响应包络与分页结构不得偏离）。
6. 每阶段更新文档并出 checkpoint。
7. 每阶段末跑 Lighthouse 与 EXPLAIN 留档。
8. 涉及服务器/凭据/部署授权的操作一律列清单请用户执行或书面授权。
9. **并行会话纪律**（2026-07-26/27 实战总结，全部 agent 强制遵守）：
   - 会话开头的 git status 快照不可信——**每次提交前重新 `git status --short` 全量核对**；
   - 只用**显式文件路径** `git add`，永远禁止 `git add -A`/`git add .`/`git commit -a`；
   - 工作区中他人在途的未提交改动**不触碰、不暂存、不 stash、不还原**；与自己改动共存于同一文件时（如本轮 api/admin.ts），用暂存区手术（`git hash-object`/`git update-index` 或补丁分离）只提交属于自己的 hunks，并在 checkpoint 记录；
   - 需要验证已提交状态时用 `git worktree add <临时目录> HEAD` 隔离运行，不污染共享工作区；
   - `.git/index.lock` 存在时先判断：文件年龄 >30 分钟且无 git 进程方可删除（历史成因：VM 会话对锁文件无删除权限）；新鲜锁一律等待；
   - 库内既有 CRLF 文件（P2-7 治理前）修改时保持该文件现有行尾，不顺手整文件重排。

**协作循环**沿用 v4：用户在提示词末尾「本次任务」填条目编号 → 执行 agent 测试先行实现并出 checkpoint → 验收 agent 独立复跑出 `docs/acceptance-*.md` → 用户依据报告决定推送与部署。跨端条目后端先落契约；docs/ 下 checkpoint 是唯一交接媒介。本计划修订时须同步检查三份提示词的条目号、迁移号与命令。

---

## 七、决策记录与待决策

### 7.1 已批准并已执行

v4 的 D-1（CD 提前阶段 2）、D-2（读者增长降权）、D-3（L-1~L-6 纳入）、D-6（AI 配置 DB 注册表 + AES-GCM，已实现）、D-7（自托管三层人机验证，已实现在途）。

### 7.2 本文新增、待批准

| # | 建议 | 默认建议 |
| --- | --- | --- |
| D-8 | L-8 搜索契约扩展（POST /search 增 categorySlug/sort，命中补 date/readTime/tags）——公开 API 增量，非破坏性 | 批准，入阶段 2 |
| D-9 | L-9 featured 过滤参数、L-11 返回保留页码、L-10 笔记切换会话守卫——review 移交三项 | 批准，入阶段 2 |
| D-10 | L-12 列表查询真投影入阶段 1（与 NB-5 同批） | 批准 |
| D-11 | quality job 改全区间检查（3.4 末行） | 批准，随 P2-7 |
| D-12 | 版本历史保留份数 N=10（v4 D-4 顺延） | 待确认数值 |
| D-13 | 3A-5 后旧 HTML 列去留（v4 D-5 顺延） | 到期评审，默认保留一阶段 |
| D-14 | 「搜索结果页」独立路由（机会池新条目）是否提前——当前归档页内嵌搜索模式在分类/收藏组合下语义受限（本轮 review 记录） | 暂留机会池，5A 全文检索落地时一并评估 |

### 7.3 开放问题（不阻塞启动）

1. CD 前置：服务器 deploy 用户 + GitHub Secrets/Environment 配置时点（阶段 2 启动前）。
2. 5A spike 的服务器操作窗口（zhparser/pgroonga 试装）。
3. 6B 对象存储选型（本地磁盘 vs S3 兼容）留到触发时定。
4. 本机 PostgreSQL 18.4 与 CI 17 的版本口径统一时点（跟踪 Flyway 公告）。

---

*本文基于 2026-07-27 工作区实测（4 域并行盘点：部署/CI、前端资产、后端模块、L-7 在途快照）与 docs/ 全部既有文档整理；条目号 P/NB/NF/ND 与 v3/v4 完全对应，L-1~L-7 沿用 v4，L-8~L-12 与 D-8~D-14 为本文新增。*
