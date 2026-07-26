# 优化与功能拓展计划（2026-07-26）

> 状态：**已批准（2026-07-26），执行中**。决策：① P0-7 收藏改为纯计数语义；② 接受 P1-2 列表瘦身；③ 评论暂不做（第一期移除）；④ 文章存储改 Markdown，编辑器需带工具栏按钮（标题/有序无序列表/代码块等）；⑤ 允许安装 PG 扩展。
>
> 原始说明：本计划基于对后端、前端、部署、CI 的全量代码审查，分为「优化阶段」（优先执行）与「功能拓展阶段」（长期）。每项标注优先级、涉及文件、验收标准。

---

## 一、项目现状摘要

| 维度 | 现状 |
|---|---|
| 后端 | Java 21 + Spring Boot 3.5.16 + JPA + Flyway(V1-V14) + PostgreSQL，JWT(HS256) 单管理员 |
| 前端 | Vue 3.5 + TS 5.9 + Vite 8 + Pinia 3 + TipTap 3 + KaTeX + PWA |
| 功能 | 文章/分类/点赞、笔记(Markdown+附件)、菜谱/收藏、全站搜索、知识图谱、音乐、语录、AI 助手(DeepSeek)、sitemap/robots/JSON-LD |
| 测试 | 后端 15 个测试类（集成测试依赖真实 PG）；前端 12 个 vitest 文件 |
| 部署 | Ubuntu + systemd + nginx + pg_dump 定时备份；CI 仅测试无 CD |

---

## 二、优化阶段（优先执行）

### P0 — 安全与正确性（1~2 天）

| # | 问题 | 位置 | 方案 | 验收 |
|---|---|---|---|---|
| P0-1 | `anyRequest().permitAll()` 兜底放行，新路由默认公开 | `SecurityConfiguration.java:47` | 改为 `denyAll()`，显式白名单 | 集成测试：未知路径返回 401/403 |
| P0-2 | 公开写接口可无限刷计数（点赞/收藏） | `PostController.java:35`、`DishController.java:35` | 加基于 IP+slug 的内存限流（如 Bucket4j 或简单 Caffeine 计数窗）；前端 localStorage 去重 | 同 IP 短时重复请求被 429 拒绝 |
| P0-3 | 登录接口无速率限制，可暴力猜解 | `AuthController.java:25` | 同上限流（按 IP，5 次/分钟）；nginx 层加 `limit_req` 双保险 | 超限返回 429；nginx 配置更新 |
| P0-4 | 计数并发丢失更新（读-改-写） | `PostService.java:46-51`、`DishService.java:31-37` | 改为 `@Modifying UPDATE ... SET like_count = like_count + 1` 原子更新 | 并发单测通过，计数准确 |
| P0-5 | 笔记 markdown 公开返回无后端消毒，XSS 防线全靠前端 | `NoteService.java:46-54` | 公开接口输出前对渲染危险片段消毒，或明确前端 TipTap 只读渲染不执行 HTML（验证并加测试固化） | 注入 `<script>`/`<img onerror>` 的笔记公开渲染无脚本执行 |
| P0-6 | 附件 MIME 只信任客户端 Content-Type | `NoteAttachmentService.java:62-64` | 加 magic-byte 嗅探（PNG/JPEG/WebP/GIF 头校验） | 伪造 Content-Type 上传被拒 |
| P0-7 | `toggleFavorite` 名实不符（只增不减，恒返回 favorited=true） | `DishService.java:32-37` | 语义改为 `favorite`（纯计数）或实现真 toggle（需客户端标识）；接口文档同步 | 行为与命名一致，测试更新 |
| P0-8 | 弱口令（DB 123123 / 管理员 7 位） | `backend/.env.properties` | 本地重新生成强口令；确认生产用 bootstrap 随机凭据；文档写明口令策略 | 本地/生产凭据均 ≥16 位随机 |
| P0-9 | LIKE 通配符未转义（`%`/`_` 注入） | `SearchService.java:37,75` | 查询前转义特殊字符 | 单测覆盖 `%`、`_`、`\` 输入 |
| P0-10 | nginx 缺 HSTS 头 | `deploy/hxnf.top.nginx` | 加 `Strict-Transport-Security: max-age=31536000` | 线上响应头验证 |

### P1 — 性能（2~3 天）

| # | 问题 | 位置 | 方案 | 验收 |
|---|---|---|---|---|
| P1-1 | N+1：EAGER `@ElementCollection`（tags/ingredients/steps），每页 10 篇 = 11 条 SQL，菜谱 1+2N | `PostEntity.java:49-53`、`DishEntity.java:74-84`、`NoteEntity.java:43-47` | 加 `@BatchSize(size=50)`（最小改动）或列表查询用 EntityGraph | 开启 SQL 日志验证每页 ≤3 条查询 |
| P1-2 | 列表接口返回全文 content/markdownContent，序列化浪费带宽 | `PostService.java:25-29`、`NoteResponse` | 新增列表专用 Summary DTO（不含正文）；详情接口保留全文。**注意前端归档/图谱是否依赖列表全文，需同步改** | 列表响应体积明显下降，前端功能不回归 |
| P1-3 | 每次读取重复 jsoup 消毒（写入时已消毒） | `PostResponse.from` 调用链、`PostService.java:28` | 读路径直接返回已存储的消毒内容 | 单测确认写入消毒一次、读取不再消毒 |
| P1-4 | `LIKE '%..%'` 全表扫描 | `PostRepository.java:71`、V9 迁移自述 | 新增 Flyway 迁移启用 `pg_trgm` 扩展 + GIN 索引；查询保持 LIKE 不变即可受益 | EXPLAIN 走索引；搜索测试全绿 |
| P1-5 | 只读热点无缓存（graph 全量扫三表、sitemap、quotes、music） | `GraphService`、`SitemapService` 等 | 引入 Caffeine + `@Cacheable`（TTL 5~10 分钟），admin 写操作 `@CacheEvict` | 二次请求命中缓存（日志/指标验证） |
| P1-6 | 附件响应 `no-store`，不可变 UUID 资源反而禁缓存 | `PublicNoteAssetController.java:27` | 改 `max-age=31536000, immutable` | 响应头验证 |
| P1-7 | KaTeX/TipTap 分块过大（checkpoint 记录 744KB） | 前端构建 | `vite.config.ts` 加 `manualChunks` 拆分 katex/tiptap；PublicNotes 与 TyporaEditor 的编辑器依赖按需异步加载 | 首屏 chunk 减小，`npm run build` 分析确认 |
| P1-8 | `viewsCount` 死字段，stats 永远返回 0 浏览量 | `PostEntity.java:74`、`PostService` | 在 `findBySlug` 详情读取时原子递增（与 P0-4 同方式），或明确删除该字段 | stats 返回真实浏览数 |

### P2 — 工程质量与一致性（2~3 天）

| # | 问题 | 位置 | 方案 | 验收 |
|---|---|---|---|---|
| P2-1 | 错误响应三种格式（ApiResponse / Map / Spring 白标） | `GlobalExceptionHandler.java:89-95` | 统一错误结构（对齐 ApiResponse），补兜底 `@ExceptionHandler(Exception.class)` 与 `MethodArgumentTypeMismatchException` | 集成测试断言各错误场景格式一致 |
| P2-2 | 分页/limit 参数无声明式校验 | `PostController.java:24-25`、`SearchController.java:27` 等 | 加 `@Min/@Max` + `@Validated`；`SearchRequest.java:14` 移除使 `@NotNull` 失效的 null 回退 | 非法参数返回统一 400 |
| P2-3 | 无 API 文档 | pom.xml | 引入 springdoc-openapi，`/swagger-ui` 仅 dev profile 开放 | 本地可浏览全部接口文档 |
| P2-4 | 集成测试依赖本机真实 PG，CI 可移植性差 | `BlogApiIntegrationTest.java:44-68` | 迁移到 Testcontainers（CI 已有 Docker）；保留本地快速模式 | CI 不再需要 service 容器手工配置 |
| P2-5 | 无覆盖率工具 | pom.xml | 加 JaCoCo，初始阈值按现状设定，CI 上传报告 | CI 产出覆盖率报告 |
| P2-6 | 前端 `npm audit` 高危导致 CI 失败（brace-expansion 传递依赖） | `.github/workflows/ci.yml`、checkpoint 文档 | `npm audit fix` / overrides 固定版本；audit 步骤改为不阻塞或仅审 production 依赖 | CI 全绿 |
| P2-7 | 死代码清理 | `PostRepository.java:56-62`（未用方法）、`README.md~`、`frontend/vite-live*.log` | 删除；`.gitignore` 补充 | 构建与测试全绿 |
| P2-8 | AI RestClient 构造时固化配置、Controller/Service 重复校验 | `DeepSeekChatService.java:39-61`、`AdminAiController.java:37-41` | 校验收敛到 Service 一处；配置支持 `@RefreshScope` 级别可不做（低优先） | 单测更新 |
| P2-9 | 无请求日志/TraceId | 后端全局 | logback 配置 + 简单 MDC filter（requestId） | 日志含 requestId，错误可追踪 |
| P2-10 | 认证模块无单测 | `AuthController`/`JwtService`/`AdminBootstrap` | 补单测：签发/过期/坏签名、bootstrap 幂等 | 新增测试通过 |

**优化阶段合计估时：约 5~8 个工作日。每完成一个 P 级别提交一次，跑全量测试（后端 `mvn test`，前端 `npm test` + `test:typecheck`）。**

---

## 三、功能拓展阶段（长期）

按依赖关系与价值排序，分四期。每期结束出 checkpoint 文档（沿用 docs/ 惯例）。

### 第一期：内容体验补全（1~2 周）

1. **评论系统**（自建，匿名+审核制）
   - 后端：`comments` 表（Flyway V15）、公开 POST（限流+内容长度校验+jsoup 消毒）、admin 审核接口（列表/通过/删除）
   - 前端：文章页评论区组件、admin 审核面板
   - 防滥用：P0-2 的限流基建复用；蜜罐字段防机器人
2. **RSS/Atom 订阅**
   - `GET /feed.xml`（复用 sitemap 的 StAX 生成方式），nginx 缓存
3. **文章目录（TOC）与阅读进度**
   - 前端从文章 HTML 提取 h2/h3 生成侧边目录；滚动进度条
4. **相邻文章导航**（上一篇/下一篇）
   - 后端详情响应附带 prev/next slug+title
5. **浏览量真实统计**（依赖 P1-8）：文章/笔记/菜谱统一 views 方案

### 第二期：管理端增强（2~3 周）

1. **管理端文章编辑器**：目前 admin 只有笔记工作台，文章 CRUD 仅有 API 无 UI。复用 TyporaEditor，Markdown 写作 → 服务端转 HTML（或直接存 Markdown 统一渲染管线，需评审决定）
2. **菜谱管理 UI**：admin 菜谱 CRUD 页面（API 已有）
3. **草稿自动保存与版本历史**：笔记已有乐观锁，扩展保存历史版本（保留最近 N 版）
4. **仪表盘增强**：访问趋势图（基于 views 数据）、最近评论待审、存储占用（附件总大小）
5. **图片管理**：附件列表页、孤儿附件清理任务（笔记删除后的遗留附件）
6. **AI 助手增强**：写作辅助场景化（摘要生成、标题建议、tag 推荐），流式响应（SSE）

### 第三期：检索与发现（2~3 周）

1. **全文检索升级**：pg_trgm（P1-4）之上引入 tsvector + 中文分词（zhparser 或 pgroonga，需服务器评估）；搜索结果高亮
2. **标签系统一等公民化**：标签页 `/tags/:tag`、聚合三类内容（graph 已有 TAG 节点数据基础）
3. **知识图谱交互增强**：节点点击跳转、按类型过滤、局部子图视图
4. **相关内容推荐**：基于共享 tag 的简单推荐（文章详情页底部）

### 第四期：平台化与运维（长期迭代）

1. **CD 流水线**：GitHub Actions 构建产物 → scp/rsync 到服务器 + systemd 重启（替代手工 outputs/*.tar.gz 流程）；`outputs/` 目录移出 git
2. **对象存储迁移**：附件从 PG bytea 迁到本地磁盘/S3 兼容存储 + nginx 直出（当前 8MB 上限内可缓行，作为规模化前置项）
3. **监控告警**：actuator + Prometheus 指标暴露（内网），慢查询日志
4. **多用户/角色**：JWT 加 refresh token；roles 不再硬编码 `["ADMIN"]`（`JwtService.java:33`）；为将来协作者/编辑角色铺路
5. **SSR/预渲染评估**：dist/server 已有产物痕迹但路由为纯 CSR + 异步组件；评估对 SEO 的实际收益后决定是否启用 SSG（vite-ssg）对公开页预渲染
6. **国际化预留**（低优先）：文案目前硬编码中文，若无英文读者需求可长期搁置

---

## 四、执行原则

1. **顺序**：P0 → P1 → P2 → 第一期 → … 严格先优化后拓展；P0 内部按表格顺序。
2. **每项独立提交**，commit message 沿用现有风格（`feat:`/`fix:`/`test:` + 中文摘要）。
3. **测试先行**：每个修复先补失败测试再修复；集成测试库 `yubai_blog_it` 保持可用。
4. **迁移不可变**：数据库变更只增 Flyway 新版本（V15+），不改历史迁移。
5. **接口兼容**：公开 API 不做破坏性变更；确需变更（如 P0-7）在计划批准时明确。
6. **每完成一个阶段**更新本文档进度并出 checkpoint。

---

## 五、待你决策的问题

1. P0-7 收藏语义：改名为纯计数 `favorite`，还是实现真 toggle（需要客户端匿名标识）？
2. P1-2 列表瘦身属于响应结构变更，前端需同步改造，是否接受？
3. 评论系统：自建（本计划方案）还是暂不做评论？
4. 第二期文章编辑器：文章存储改 Markdown 统一管线，还是维持 HTML 存储 + 编辑器输出 HTML？
5. 第三期中文分词需要服务器安装 PG 扩展（zhparser/pgroonga），是否有服务器操作权限与意愿？
