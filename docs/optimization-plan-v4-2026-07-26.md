# 余白博客 · 长期优化与功能拓展计划 v4

> 状态：**待批准**。批准后取代 docs/optimization-plan-v3-2026-07-26.md 成为唯一执行依据（v3 的阶段 0 已完成并留档于 checkpoint-2026-07-26-phase0.md）。
>
> 定位：整合 v3 未完成部分（性能 / 工程质量两个优化阶段 + 四期功能拓展），按**前端 / 后端 / 数据库 / 部署**四条线细化分工，并向后延伸长期演进与常态机制。
>
> 排期口径：**按阶段推进，不设硬日历时限**；每项给出估时，阶段间按依赖顺序执行，阶段内可按精力灵活穿插。
>
> 权重决策（2026-07-26 确认）：**创作与管理效率、性能与稳定运维、检索与知识组织**为优先方向；**读者增长类**（RSS 订阅、相邻文章导航、SEO 预渲染、评论）本轮明确降权，保留在计划中但不占主线排期。
>
> 二次修订（2026-07-26）：应用户要求新增两个重点——**AI 助手平台化**（后台全局侧边栏 + 多模型接入 + 完整安全设计，升格为阶段 4 主项 4A）与**后台登录人机验证**（L-7，入阶段 2，设计见 3.6）。

---

## 一、基线（2026-07-26 · 阶段 0 完成后）

### 1.1 技术栈与部署形态

| 线 | 技术 | 备注 |
| --- | --- | --- |
| 后端 | Java 21 · Spring Boot 3.5.16 · Spring Security + Nimbus JWT(HS256) · Spring Data JPA · Flyway V1–V14 · jsoup 1.18.3 | `denyAll()` 兜底 + 显式白名单；进程内固定窗口限流 |
| 前端 | Vue 3.5.22 · TS 5.9.3 · Vite 8.1.5(Rolldown) · Pinia 3 · Tiptap 3.28 · KaTeX 0.17 · DOMPurify 3.2.7 · vite-plugin-pwa | Node ≥22；测试 Vitest 4 + Vue Test Utils |
| 数据库 | PostgreSQL（本地 18.4 / CI 17 / 生产以服务器实际为准） | 附件以 bytea 入库；集成测试库 yubai_blog_it |
| 部署 | Ubuntu · systemd(yubai-blog.service) · nginx(hxnf.top) · pg_dump 定时备份(timer) | nginx 已含 HSTS、登录限速、sitemap/robots 转发 |
| CI | GitHub Actions：frontend(test/typecheck/build/audit) + backend(mvn test, PG17 service) + quality(git diff --check) | 无 CD；npm audit 因上游 brace-expansion 预期失败 |

### 1.2 模块现状盘点

| 模块 | 公开能力 | 管理能力 | 遗留问题（对应条目） |
| --- | --- | --- | --- |
| 文章 | 分页列表、详情、点赞(原子+限流)、统计、分类聚合 | CRUD，正文仍为原始 HTML 文本域 | 浏览量恒 0（P1-8）；列表带全文（P1-2）；假文章种子（NF-4） |
| 学习笔记 | 已发布列表/详情、笔记图片 | 工作台：双模编辑、乐观锁、导入导出、附件(magic-byte 校验) | 附件解压炸弹预检（NB-4） |
| 菜谱 | 列表、详情、收藏(纯计数)、榜单 | CRUD | 份量基准硬编码 2（NF-12） |
| 搜索 | GET 分组 + POST 分页（LIKE 已转义） | — | 全表扫描（P1-4）；GET/POST 结构不一致（NB-11） |
| 知识图谱 | 全量节点/边（确定性 SVG 布局） | — | 无缓存、加载全文（P1-5 / NB-5） |
| 音乐 / 语录 | 曲目列表、语录 | **无管理接口（仅种子数据）** | quotes/daily 返回全表（NB-6）；播放器 loop 缺陷（NF-11） |
| AI 助手 | — | DeepSeek 对话（限额、脱敏、非流式、独立页面、单供应商 env 配置） | 平台化：侧边栏 + 多模型 + 流式 + 场景化（阶段 4 主项 4A） |
| SEO | sitemap.xml、robots.txt、JSON-LD、meta | — | nginx 转发已修（ND-1），待线上 curl 验证 |
| 合集 series | 仅 V11 数据库表 | 零代码引用 | 已批准第二期实现（阶段 4） |

前端路由：/、/articles(+detail)、/notes、/recipes、/archive(时间轴/图谱)、/about、/admin(login/总览/notes/ai)。

测试基线：后端 **155/155**（阶段 0 后）；前端 **130/130** + 阶段 0 新增 4 个测试文件（adminSession、contentStore、sanitizeHtml、publicNotesXss）**待本机 npm install 后运行**。

### 1.3 阶段 0 成果与收尾清单

阶段 0（安全与正确性）已完成 10 个独立提交：NF-1 登录态单一事实源、NF-2 DOMPurify 前置消毒、NF-3 currentPost 响应式修复、ND-1/P0-10/P0-3 nginx 三项、P0-1/NB-3 denyAll + 拒绝占位密钥、P0-2/P0-3 进程内限流、P0-4/P0-7 原子计数 + 收藏纯计数、P0-6 magic-byte 嗅探、P0-9 LIKE 转义、P0-5 XSS 测试固化。

**收尾清单（进入阶段 1 前完成，均为本机/服务器操作）：**

1. 前端验证：`cd frontend && npm install && npm test`（拉取 dompurify，跑新增 4 个测试文件）。
2. P0-8 口令轮换：数据库口令 ALTER USER + 同步 .env.properties；管理员口令按 checkpoint-phase0 步骤重建；生产确认 APP_JWT_SECRET 为随机值。
3. 生产部署 nginx 更新：`nginx -t && systemctl reload nginx`；验证 `curl -I https://hxnf.top/sitemap.xml` 返回 XML、响应含 Strict-Transport-Security 头。

### 1.4 继承的已批准决策（全部沿用）

① 收藏改纯计数；② 接受列表瘦身（P1-2）；③ 第一期不做评论；④ 文章存储改 Markdown + 工具栏编辑器；⑤ 允许安装 PG 扩展；⑥ NF-2 两步走（DOMPurify 已上，Markdown 迁移根治）；⑦ series 合集第二期实现；⑧ 浏览量做真实统计；⑨ 删除 Cloudflare worker。

---

## 二、总路线图

### 2.1 阶段总览

| 阶段 | 主题 | 核心产出 | 估时 | 前置 |
| --- | --- | --- | --- | --- |
| 1 | 性能优化 | 查询 N+1 与索引、缓存、真分页、构建与资源瘦身 | 4–5 天 | 阶段 0 收尾清单 |
| 2 | 工程质量与交付流水线 | 错误格式统一、API 文档、Testcontainers、覆盖率、仓库清理、**CD 流水线（自第四期提前）**、**登录人机验证（L-7）** | 6–8 天 | 阶段 1 |
| 3 | 渲染管线统一与创作体验 | **文章 Markdown 化（主项）**、编辑器工具栏、TOC AST 重建、浏览量扩展 | 2–3 周 | 阶段 2 |
| 4 | 管理端增强 | **AI 助手平台化（主项：侧边栏 + 多模型 + 流式 + 安全设计）**、series 合集、版本历史、仪表盘趋势、图片管理、曲目/语录管理 | 3.5–4.5 周 | 阶段 3 |
| 5 | 检索与知识组织 | 中文全文检索、标签一等公民、图谱子图、相关推荐 | 2–3 周 | 阶段 4（可与 4 尾部并行） |
| 6 | 平台化与稳定运维 | 监控告警、对象存储、多用户令牌、备份演练、SEO 预渲染（触发制） | 长期迭代池 | 按项独立 |
| 常态 | 贯穿机制 | 性能预算、依赖升级节奏、安全复审、文档惯例 | 持续 | — |

### 2.2 与 v3 的对照与调整说明

为保持可追溯，v4 不重编已有条目号（P1-\*、P2-\*、NB-\*、NF-\*、ND-\* 含义与 v3 一致），新增项以 **L-\*** 编号。相对 v3 的实质调整共七处：

1. **CD 流水线自第四期提前至阶段 2**（原第四期第 1 项）。理由：权重决策选定「性能与稳定运维」，且 CD 消除手工 outputs/\*.tar.gz 流程的收益在每次上线都兑现，越早越省；与 P2-7/ND-4 仓库清理（outputs/ 移出 git）天然同批。
2. **RSS/Atom 与相邻文章导航降权**为阶段 3 可选尾项（原第一期第 2、3 项）。理由：读者增长方向未入选优先级；两项均不阻塞其他工作，留作阶段 3 主项完成后的弹性产能。
3. **SEO 预渲染改为触发条件制**（v3 遗留待决策）：不提前、保持在阶段 6，当「月均搜索/社交带来的访问显著增长」或「开始主动分发内容」时再启动评估。本条即对 v3 待决策问题 4 的处置建议。
4. **新增 L-1 音乐曲目与语录管理接口**（阶段 4）：现状仅种子数据、无任何管理能力，与「创作与管理效率」权重直接相关。
5. **新增 L-2~L-6 常态与部署侧小项**（nginx gzip/HTTP2、生产 sourcemap 策略、备份恢复演练与异机副本、依赖升级节奏、大组件择机拆分），见 3.2/3.4 与第四、五章。
6. **AI 助手升格为阶段 4 主项**（用户 2026-07-26 明确要求）：由 v3 的「SSE + 场景化」两点扩展为完整平台化——后台全局侧边栏形态、多供应商多模型接入、密钥加密与 SSRF 防护等安全设计，拆为 4A-1~4A-6 六个子步，详见 4.2。
7. **新增 L-7 后台登录人机验证**（用户 2026-07-26 明确要求）：自托管分层方案（隐形 PoW 常开 + 连续失败触发图形验证码 + 冷却），排入阶段 2，完整设计见 3.6。

其余条目全部继承 v3 原文与原梯队，仅按前端/后端/数据库/部署四条线重新组织。

---

## 三、优化方案明细（阶段 1–2）

### 3.1 后端优化

**阶段 1 · 性能（后端部分）**

| # | 问题 | 位置 | 方案 | 验收 | 估时 |
| --- | --- | --- | --- | --- | --- |
| P1-1 | EAGER `@ElementCollection` N+1：每页 10 篇 = 11 条 SQL，菜谱 1+2N | PostEntity / DishEntity / NoteEntity | `@BatchSize(size=50)`（最小改动）；列表热点路径评估 EntityGraph | 开 SQL 日志确认列表页 ≤3 条查询 | 0.5 天 |
| P1-2 | 列表接口返回全文，序列化浪费带宽 | PostService、NoteResponse | 列表专用 Summary DTO（不含正文），详情保留全文；与前端 NF-5 真分页同一次联动改造 | 列表响应体积明显下降，前端不回归 | 1 天（含前端联动） |
| P1-3 | 读路径重复 jsoup 消毒（写入已消毒） | PostResponse.from 调用链 | 读路径直接返回已存储的消毒内容 | 单测确认写一次消毒、读不再消毒 | 0.25 天 |
| P1-4 | `LIKE '%..%'` 全表扫描 | PostRepository 及三表搜索 | Flyway 新迁移启用 pg_trgm + GIN 索引（决策⑤已允许），查询保持 LIKE 即受益 | EXPLAIN 走索引；搜索测试全绿 | 0.5 天 |
| P1-5 | 只读热点无缓存（graph 全量扫三表、sitemap、quotes、music） | GraphService、SitemapService 等 | 引入 Caffeine + `@Cacheable`（TTL 5–10 分钟），admin 写操作 `@CacheEvict` | 二次请求命中缓存（日志验证） | 0.5 天 |
| P1-6 | 附件响应 `no-store`，不可变 UUID 资源反而禁缓存 | PublicNoteAssetController | 改 `max-age=31536000, immutable` | 响应头验证 | 0.25 天 |
| P1-8 | viewsCount 死字段，浏览量恒 0（决策⑧：做真实统计） | PostEntity、PostService | 详情读取时数据库端原子 +1；复用阶段 0 限流器基建做 IP+slug 短窗去重（不落 IP 明文） | 并发单测计数无丢失；stats 返回真实值 | 0.5 天 |
| NB-1 | (category_slug, status) 无索引 | PostRepository、V1/V9 | 与 P1-4 同一个迁移窗口加复合索引 | EXPLAIN 验证 | 随 P1-4 |
| NB-5 | graph/search/sitemap 为出标题而加载全文 | 三个 Service | 接口投影（只取 id/标题/slug/时间），与 P1-5 缓存同批改避免二次返工 | SQL 不再 select 正文列 | 0.5 天 |

**阶段 2 · 工程质量（后端部分）**

| # | 问题 | 方案 | 验收 | 估时 |
| --- | --- | --- | --- | --- |
| P2-1 | 错误响应三种格式并存 | 统一对齐 ApiResponse；补 `@ExceptionHandler(Exception.class)` 与 TypeMismatch 兜底 | 集成测试断言各错误场景格式一致 | 0.5 天 |
| P2-2 | 分页/limit 无声明式校验 | `@Min/@Max` + `@Validated`；SearchRequest 移除使 `@NotNull` 失效的 null 回退 | 非法参数统一 400 | 0.25 天 |
| P2-3 | 无 API 文档 | springdoc-openapi，/swagger-ui 仅 dev profile | 本地可浏览全部接口 | 0.25 天 |
| P2-4 | 集成测试依赖本机真实 PG | 迁移 Testcontainers（CI 已有 Docker），保留本地快速模式 | CI 不再手工配置 service 容器 | 1 天 |
| P2-5 | 无覆盖率工具 | JaCoCo，初始阈值按现状设定，CI 上传报告 | CI 产出覆盖率报告 | 0.25 天 |
| P2-8 | AI 校验散落 Controller/Service 两处 | 校验收敛 Service 一处 | 单测更新 | 0.25 天 |
| P2-9 | 无请求日志/TraceId | logback + MDC filter（requestId） | 日志含 requestId | 0.25 天 |
| P2-10 | 认证模块无单测 | JwtService 签发/过期/坏签名、AdminBootstrap 幂等 | 新增测试通过 | 0.5 天 |
| NB-4 | 附件图片解压炸弹：ImageIO.read 无尺寸预检 | 解码前 ImageReader 读宽高上限（如 ≤8000×8000）再解码 | 超限图返回 400，单测覆盖 | 0.25 天 |
| NB-6 | quotes/daily 名不副实返回全表 | 按日确定性选取（day-of-year 取模），或改名 /quotes | 行为与命名一致 | 0.25 天 |
| NB-7 | 缓存拦截器错配：stats/favorites 被 5 分钟 public 缓存冻结 | 按端点精细化 Cache-Control（计数类 no-cache，静态类可缓存） | 响应头逐端点验证 | 0.25 天 |
| NB-8 | 三处硬编码前端 URL 形态 | 抽统一 URL builder（也是 RSS 的前置条件） | 单测覆盖 URL 生成 | 0.25 天 |
| NB-9 | 演示数据内嵌 schema 迁移（V1/V6/V13/V14） | 新库 seed profile 分离；存量库不动 | 新库可选择无演示内容初始化 | 0.5 天 |
| NB-11 | 契约小缺陷：NoteRequest.status 被忽略、201 响应 code=200、GET/POST search 结构不一致 | 随 P2-1/P2-2 一并修正 | 契约测试固化 | 随 P2-1 |

### 3.2 前端优化

**阶段 1 · 性能（前端部分）**

| # | 问题 | 位置 | 方案 | 验收 | 估时 |
| --- | --- | --- | --- | --- | --- |
| NF-5 | 一次取 50 条前端分页，第 51 篇起不可见 | contentStore、ArchivePage | 改服务端真分页（page/size 入参、URL ?page=N 同步），与 P1-2 同一次改造 | 翻页可见全部文章 | 随 P1-2 |
| NF-6 | PWA 预缓存命中大图；manifest 图标/favicon 指向 2.2MB 非方形 og.png | vite.config.ts、index.html、public/ | 大图移 runtimeCaching；重制 192/512 方形图标与 favicon；og:image 重制 1200×630 并用绝对 URL；public/ 图片压缩（hero 转 WebP、菜品图缩尺寸，5.9MB → 目标 <1MB） | 首次安装预缓存体积对比留档 | 1 天 |
| NF-7 | 三组件绕过统一 API 层 raw fetch | KnowledgeGraph、AmbientSound、InspirationCard | 迁回 api/content.ts，统一错误处理与 baseURL | 无组件内裸 fetch | 0.5 天 |
| NF-10 | styles.css 1,948 行（118KB）全路由加载阻塞首绘 | src/styles.css、main.ts | 拆五个文件（tokens/公共/文章笔记/美食/后台），后台样式随路由懒加载 | 首屏 CSS 体积对比留档 | 1 天 |
| P1-7 | KaTeX/Tiptap 分块过大（744KB） | vite.config.ts | `manualChunks` 拆 katex/tiptap；编辑器依赖仅管理路由异步加载 | build 产物分析确认首屏 chunk 减小 | 0.5 天 |
| L-3（新增） | `build.sourcemap: true` 产出全量 .map，而 nginx 已对 .map 返回 404 | vite.config.ts、deploy | 生产构建改 `sourcemap: 'hidden'` 或关闭；如需线上排错，map 仅存本地不上服务器 | dist 体积下降；排错流程写入 README | 0.25 天 |

**阶段 2 · 工程质量（前端部分）**

| # | 问题 | 方案 | 估时 |
| --- | --- | --- | --- |
| NF-4 | 内置 5 篇假文章生产可见 | 种子数据仅 dev 门控（import.meta.env.DEV）；生产改骨架屏 + 空态组件 | 0.5 天 |
| NF-8 | 错误态与可访问性缺口 | 清单逐项：归档部分失败提示条、灯箱 Esc/焦点陷阱、播放器 aria-label、装饰图标 aria-hidden | 1 天 |
| NF-9 | 死代码：uiStore.menuOpen、data.ts categories、FoodSection 幽灵收藏、无效 preconnect、死样式段 | 删除，随 P2-7 仓库清理同批 | 0.5 天 |
| NF-11 | 播放器 audio.loop=true 无法自动切歌；兜底曲目外链 Pixabay | 改 onended 切歌；外链曲目本地化入库 | 0.5 天 |
| NF-12 | 菜谱份量缩放基准硬编码 2 | dishes 表加 base_servings（迁移），前端按其缩放 | 0.5 天 |
| 补测 | authStore + 路由守卫、AdminDashboard、AdminLogin 无测试 | 按阶段 0 惯例补 vitest（contentStore 已在阶段 0 补） | 1 天 |
| L-6（新增·择机） | 大文件可维护性：FoodSection.vue ~40KB、NotesWorkspace.vue ~22KB、AdminDashboard.vue ~17KB 单文件 | 不设专门排期；每次触碰这些文件的需求顺手拆子组件，禁止纯重构大提交 | 择机 |

### 3.3 数据库优化与迁移台账

原则沿用：**迁移只增不改（自 V15 起顺序分配）**；下表版本号为规划示意，执行时以实际下一个可用版本为准。

| 规划版本 | 内容 | 归属阶段 |
| --- | --- | --- |
| V15 | `CREATE EXTENSION pg_trgm` + 三表搜索列 GIN 索引 + (category_slug, status) 复合索引 | 阶段 1（P1-4/NB-1 同窗口） |
| V16 | dishes 加 base_servings | 阶段 2（NF-12） |
| V17 | posts 加 markdown_content（可空）+ content_format 标记 | 阶段 3（Markdown 化） |
| V18 | 存量正文迁移完成后的清理（视验证结果决定是否删旧列，单独评审） | 阶段 3 末 |
| V19 | post_revisions / note_revisions 版本历史表 | 阶段 4 |
| V20 | view_daily 按日聚合表（访问趋势） | 阶段 4 |
| V21 | ai_providers（供应商注册表，密钥加密列）+ ai_usage（用量审计） | 阶段 4（4A AI 平台化） |
| V22 | post_tags / learning_note_tags 的 tag 列索引 | 阶段 5 |
| V23+ | 中文分词（zhparser/pgroonga）相关对象，以 spike 结论为准 | 阶段 5 |

配套惯例：每个含索引/扩展的迁移，提交说明里附 EXPLAIN (ANALYZE) 前后对比；series 表已由 V11 建立，阶段 4 实现前先复核其结构是否满足设计，缺列则新迁移补列（不改 V11）。

PG 版本策略：本地 18.4 超出当前 Flyway 官方测试范围，CI 锁 17；跟踪 Flyway 对 PG18 的官方支持公告，支持后再统一 CI 与文档口径；生产升级另行计划，不在本计划内自动发生。

### 3.4 部署与 CI/CD 优化

**CD 流水线（自第四期提前至阶段 2，估 1–2 天）**

- 触发：push tag（如 v\*）或手动 workflow_dispatch，**加 GitHub Environment 人工批准门**——沿用「未经明确授权不部署」的既有原则，CD 只是把手工步骤自动化，不改变授权语义。
- 构建：复用现有 CI job 产物（frontend dist + backend jar），不重复跑测试通过则不部署。
- 发布：rsync over SSH（GH Secrets 存部署专用账号私钥）→ 服务器原子切换（新目录 + symlink 或 rsync --delete 到 releases/N）→ `systemctl restart yubai-blog` → curl /actuator/health 健康检查，失败保留上一版本目录可手工回滚。
- 收尾：outputs/\*.tar.gz 流程废弃，outputs/ 移出 git（并入 ND-4）。
- 前置（需用户操作）：服务器建 deploy 用户与 SSH key、GitHub 仓库配 Secrets 与 Environment。

**CI 与仓库清理**

| # | 内容 | 方案 | 估时 |
| --- | --- | --- | --- |
| P2-6 | npm audit 高危阻塞 CI（brace-expansion 传递依赖，仅测试/构建链） | audit 步骤改为非阻塞记录（continue-on-error）或仅审 production 依赖；每月复查上游修复，可修即恢复阻塞 | 0.25 天 |
| P2-7/ND-4 | 仓库杂物：vite-live\*.log、README.md~、outputs/ 入库；CRLF 噪音 | 删除 + .gitignore 补充 + **.gitattributes 统一行尾**（阶段 0 checkpoint 遗留问题） | 0.5 天 |
| ND-2 | Cloudflare worker 双轨（决策⑨：删除） | 删 frontend/worker/、scripts/verify-worker.mjs，核查 prepare-sites-build.mjs 与 .openai/hosting.json 是否随之简化 build 脚本链 | 0.25 天 |
| L-2（新增） | nginx 未启用 gzip/HTTP2 | `listen 443 ssl http2;` + gzip on（text/css/js/json/svg/xml，min length 1k）；上线后对比传输体积 | 0.25 天 |
| — | RSS 上线时 nginx 需同步加 /feed.xml 转发 | 与阶段 3 可选项联动，先记录在案 | — |

### 3.5 阶段 1/2 验收门

- 阶段 1：全量测试绿；SQL 日志确认列表页 ≤3 条查询；首页传输体积（HTML+CSS+JS+图）前后对比写入 checkpoint；Lighthouse 移动端跑分留档为长期基线。
- 阶段 2：CI 全绿（含 audit 策略调整后）；swagger-ui 本地可用；JaCoCo 报告产出；CD 完成一次带人工批准的真实发布演练并 curl 验证；仓库无 outputs/、无 worker/、无日志杂物；**登录人机验证三层状态机集成测试通过（见 3.6 验收）**。

### 3.6 后台登录人机验证设计（L-7 · 阶段 2）

**威胁模型与定位。** 防御对象是**脚本化暴力破解与撞库**，不是专业打码平台。现有纵深已有三层：nginx `limit_req` 5 次/分/IP、应用层固定窗口限流（阶段 0）、强口令与 BCrypt（P0-8）。人机验证补的是「慢速分布式尝试」这一缺口——攻击者换 IP 或压在限流阈值内时，仍需为每次尝试付出计算成本或人工成本。

**方案：自托管三层防御（推荐，D-7）**

| 层 | 触发条件 | 机制 | 用户感知 |
| --- | --- | --- | --- |
| 层 1 · PoW 工作量证明 | **常开** | 登录前先 `GET /api/v1/auth/challenge` 取 `{challengeId, salt, difficulty}`；前端在 Web Worker 穷举 nonce 使 `SHA-256(salt + nonce)` 满足难度前缀（目标普通设备 0.3–1s）；登录请求携带 challengeId + nonce | 无感（按钮短暂显示「安全校验中」） |
| 层 2 · 图形验证码 | 同 IP **或**同用户名 15 分钟内失败 ≥3 次 | challenge 响应升级为 image 类型：服务端 Java2D 生成 4–5 位扭曲字符 PNG（base64 内联下发，答案只存哈希），需 PoW + 图形答案双通过 | 显示验证码图 + 输入框 + 「换一张」 |
| 层 3 · 冷却 | 同 IP 15 分钟内失败 ≥10 次 | 该 IP 冷却 30 分钟，登录直接 429（含 Retry-After） | 明确提示稍后再试 |

**服务端设计（后端，1–1.5 天）**

- 新增 `ChallengeService`：challenge 进程内存储（复用 Caffeine，TTL 5 分钟）、**一次性使用**（验证即作废，无论成败）、绑定下发 IP；答案/nonce 校验用恒定时间比较。
- `AuthController` 改造：登录请求体增加 `challengeId / nonce / captchaAnswer` 字段；服务端按风险状态判定必填级别；所有失败返回**同一文案**——不泄露用户名是否存在、不泄露当前处于哪一层、不泄露还差几次触发升级。
- 失败计数复用阶段 0 限流器基建，键为 IP 与 username 双维度；登录成功清零。
- 配置化：PoW 难度、触发阈值、冷却时长走 application.yml（env 可覆盖），难度可按服务器观测调整。
- 依赖约束：零新第三方依赖（SHA-256 用 JDK MessageDigest，图形码用 Java2D）。

**前端设计（0.5–1 天）**

- AdminLogin.vue：提交前取 challenge → Web Worker 算 PoW（不阻塞 UI）→ 按 challenge 类型渲染图形码输入区；升级、冷却、challenge 过期各有明确错误态与重试路径。
- 无障碍：图形码提供「换一张」；输入不区分大小写；错误提示可被屏幕阅读器朗读（aria-live）。
- 组件测试覆盖三层状态机：无感通过 → 触发图形码 → 冷却 429。

**为什么不用第三方验证码。** 单管理员站点 + 境内访问环境：reCAPTCHA 境内不可达，Turnstile 连通性不稳定，极验/数美等为商业服务——自托管方案零外部依赖、零隐私外泄、零费用，且防御目标（脚本暴力破解）用 PoW+图形+限流+强口令的纵深已足够。若未来开放注册或评论，再重评第三方方案。**更强的下一步**是 TOTP 两步验证，与阶段 6C 多用户令牌体系同批评估，不在本项范围。

**测试与验收**

- 单测：challenge 过期/重放/错解/跨 IP 使用均拒绝；恒定时间比较；图形码大小写不敏感；难度参数生效。
- 集成测试：无 challenge 登录 400；第 3 次失败后无图形答案被拒；全要素正确登录成功；第 10 次失败进入冷却 429；成功登录后计数清零。
- 验收：脚本重放同一 challenge 不可行（自动化验证）；正常登录额外延时 ≤1s；与 nginx 限流叠加行为符合预期（应用层 429 不被 nginx 提前吞掉）。

估时合计约 **2–2.5 天**（含测试）。与阶段 2 的 P2-9 请求日志同批上线，失败尝试全部带 requestId 落日志，便于事后审计。

---

## 四、功能拓展明细（阶段 3–6）

每项按 目标 → 后端 → 前端 → 数据库 → 测试 → 验收 拆分；估时含测试。

### 4.1 阶段 3 · 渲染管线统一与创作体验（2–3 周）

**3A · 文章 Markdown 化（主项，决策④⑥的根治步）**

分五个可独立提交的子步：

| 子步 | 内容 | 线 | 估时 |
| --- | --- | --- | --- |
| 3A-1 | V17 迁移：posts 加 markdown_content（可空）+ content_format('HTML'/'MARKDOWN')；Post 实体与 DTO 双字段读写 | 数据库/后端 | 1 天 |
| 3A-2 | 存量迁移工具：jsoup 辅助 HTML→Markdown 转换命令（admin 一次性端点或 main 方法），产出「人工校对清单」（表格/公式/嵌套列表等高风险片段逐篇标记） | 后端 | 1–2 天 |
| 3A-3 | 管理端编辑器：AdminDashboard 文章表单弃原始 HTML 文本域，复用 TyporaEditor（笔记同款），按决策④补齐工具栏按钮（标题层级/有序无序列表/代码块/引用/表格/图片） | 前端 | 2–3 天 |
| 3A-4 | 公开渲染统一：文章详情走「Markdown → 受控渲染」管线，与笔记只读渲染同一套（Tiptap 只读或 markdown 渲染器 + DOMPurify 兜底），前后端双层防线 | 前端/后端 | 1–2 天 |
| 3A-5 | 收尾：全部存量校对通过后，读路径切 MARKDOWN；旧 HTML 列去留单独评审（V18） | 全 | 0.5 天 + 校对时间 |

- 测试：转换工具对高风险片段的快照测试；编辑器保存-重开往返一致性；公开渲染 XSS 测试沿用阶段 0 的 publicNotesXss 模式扩展到文章。
- 验收：新建文章全程 Markdown；任一存量文章前后台展示与迁移前视觉一致（校对清单签收）；DOMPurify 从「主防线」退为「兜底」。
- 风险：存量 HTML 结构不规则导致转换失真——对策是 3A-2 的清单化人工校对，且切换前双字段并存可随时回退。

**3B · 文章 TOC 与阅读进度重建（0.5–1 天，前端）**

现有 ArticlePage 已有 scrollspy TOC 与进度条（基于 HTML）；Markdown 化后改为基于 AST/heading 列表生成，去掉对渲染后 DOM 结构的脆弱依赖。验收：长文目录锚点跳转准确、进度条不回归。

**3C · 浏览量扩展至笔记/菜谱（0.5 天，后端为主）**

P1-8 在文章落地后，同一模式（详情读原子 +1 + 短窗去重）推广到 notes/dishes；stats 与 sitemap 不受影响。验收：三类内容 stats 均出真实浏览数。

**3D · 可选尾项（降权，读者增长类，不占主线）**

| 项 | 内容 | 依赖 | 估时 |
| --- | --- | --- | --- |
| RSS/Atom | GET /feed.xml，复用 sitemap 的 StAX 模式，最近 20 篇已发布文章；nginx 加转发；index.html 加 alternate link | NB-8 URL builder | 0.5 天 |
| 相邻文章导航 | 详情响应带 prev/next（slug+title），前端详情页底部导航 | 无 | 0.5 天 |

### 4.2 阶段 4 · 管理端增强（3.5–4.5 周）

执行顺序：4A（AI 平台化，主项）先行，其余按字母序，4F 可随时穿插。

**4A · AI 助手平台化（本阶段主项，1.5–2 周）**

现状：DeepSeek 单供应商、env 配置需重启、非流式、独立页面 /admin/ai；限额与脱敏机制已有。目标形态：**后台全域右侧侧边栏**——任意 /admin 页面可唤起，随时对照正在编辑的内容提问；**多供应商多模型**在管理界面注册与切换，不改代码不重启；流式输出；场景化动作一键注入编辑器。公开站点侧 AI（访客问答）**不在本项范围**，列入机会池。

分六个可独立提交的子步：

| 子步 | 内容 | 线 | 估时 |
| --- | --- | --- | --- |
| 4A-1 | **供应商抽象与注册表**：AiProvider 接口（chat / stream / listModels）；第一实现为 **OpenAI 兼容协议**（DeepSeek、OpenAI、通义 Qwen、智谱 GLM、Kimi、本地 Ollama 等均可走此协议，一个实现覆盖绝大多数供应商）；Anthropic 原生协议作为第二实现按需后置。V21 迁移建 ai_providers 表（name、base_url、models、api_key_encrypted、enabled、日预算字段）；现有 env 配置在首启时 seed 为第一行，保持向后兼容 | 后端/数据库 | 2 天 |
| 4A-2 | **SSE 流式**：/admin/ai/chat 增流式模式，SseEmitter 转发上游 chunk，心跳与空闲超时；保留非流式回退。注意：浏览器 EventSource 无法带 Authorization 头，前端改用 **fetch + ReadableStream 手工解析 SSE**，JWT 走正常 header，不把 token 放 URL | 后端+前端 | 1 天 |
| 4A-3 | **供应商管理 UI**：admin 设置区块——供应商增删改、密钥输入（**只写不回显**，界面仅展示尾 4 位）、「测试连通」按钮（后端代发一次最小请求）、默认模型选择 | 前端 | 1–1.5 天 |
| 4A-4 | **侧边栏形态**：新组件 AdminAiSidebar，挂在 admin 布局层，所有 /admin 路由可用；可折叠、宽度可拖、移动端转全屏抽屉；顶部模型切换下拉；流式渲染 + 停止生成按钮；会话仍存 sessionStorage 不落库（与现状一致，隐私最小化）；/admin/ai 路由保留为全屏视图，复用同一组件；快捷键 Ctrl+Shift+A（与编辑器既有快捷键无冲突） | 前端 | 2–3 天 |
| 4A-5 | **场景化动作与编辑器注入**：上下文感知——正在编辑文章/笔记时侧边栏出现动作 chips（总结 / 标题建议 / 标签建议 / 润色 / 续写），自动附当前编辑内容为上下文（超长按既有 max-total-chars 截断）；结构化结果一键填入对应表单字段，**只填入不保存**，字段仍走既有校验 | 前端+后端 | 2 天 |
| 4A-6 | **用量与审计**：V21 同批建 ai_usage 表（时间、provider、model、输入/输出 token、时延、状态；**默认不存消息内容**）；provider 级每日 token/请求预算，超限 429；仪表盘出用量卡片（并入 4D） | 后端 | 1 天 |

**安全设计（本项验收的一部分，非附注）**

1. **密钥安全**：密钥只存在后端；DB 中以 AES-GCM 加密存储，主密钥 `APP_AI_MASTER_KEY` 放 .env.properties（不入库、不入 git），缺失则 AI 模块整体禁用；任何 API 响应与日志不得出现密钥（现有脱敏机制扩展密钥 pattern 过滤，并加测试断言）；管理 UI 永不回显完整密钥。
2. **SSRF 防护**（base_url 可由管理界面配置，这是新攻击面）：仅允许 https；创建/修改时解析 DNS 并**拒绝私网、环回、链路本地地址**；本地供应商（如服务器上的 Ollama）需 `app.ai.allow-local-endpoints=true`，该开关**只能改 env 重启生效**——被盗管理 token 无法把 AI 出口指向内网服务（如云元数据端点）；HTTP 客户端禁跟随重定向、响应体积上限、连接/读取双超时。
3. **输出即不可信**：AI 回复渲染前过 DOMPurify（若升级 Markdown 渲染）或维持纯文本（现状）；注入编辑器的内容等同用户手输，走既有消毒管线；永不自动保存、永不执行（本期不开放工具调用/函数调用）。
4. **提示注入面收敛**：system prompt 后端固化、前端不可控（现状保持）；上下文只含当前编辑内容与用户输入，不含任何配置/凭据；AI 建议的 slug、标签等入库前走既有字段校验。
5. **权限与配额**：全部端点（含流式、连通性测试）仍限 ADMIN JWT 且过 Security 过滤器；连通性测试端点单独限流，防止被当作出网探测器；provider 日预算 + 单请求限额（沿用 max-\* 配置）双层封顶。
6. **可用性隔离**：任一供应商故障不影响博客其他功能（沿用现有 503 语义）；上游超时不占满连接池（独立超时配置）。

- 测试：加密往返与密钥不泄露断言；SSRF 校验单测（私网/环回/重定向/http 均拒）；SSE 分帧、中断、超时；预算超限 429；侧边栏状态机与注入不绕过表单校验（组件测试）。
- 验收：新增一个供应商全程只在管理界面完成；DeepSeek、任一 OpenAI 兼容云端点、（可选）本地 Ollama 三类均跑通流式对话；DB dump 中密钥不可读；编辑文章时侧边栏一键生成摘要并填入摘要框全链路走通。
- 风险：各家「OpenAI 兼容」实现存在细节差异（SSE 事件格式、finish_reason、错误体）——对策：兼容层宽松解析 + 每接入一家留一条最小联通测试记录；Anthropic 原生协议若确需再作为 4A-1 扩展单排。

**4B · series 合集（决策⑦，1 周）**

- 数据库：复核 V11 既有表结构；缺列（如排序、封面、简介）以新迁移补齐。
- 后端：SeriesEntity/Repository/Service/Controller；admin CRUD + 文章↔合集关联端点；公开端：合集列表、详情（含文章序）；接入 sitemap 与知识图谱（新增 SERIES 节点类型）；**polymorphic 引用补应用层删除钩子**（删文章时清关联，删合集不删文章）。
- 前端：admin 合集管理页（列表/编辑/拖拽排序）；文章详情页「本文属于合集 X（第 n/N 篇）」导航条；归档图谱识别 SERIES 节点。
- 测试：关联完整性（删除钩子）、公开端只见含已发布文章的合集、图谱节点去重。
- 验收：从 admin 建合集 → 挂文章 → 公开页可按序阅读全链路走通。

**4C · 草稿版本历史（3–4 天）**

- 数据库：V19 revisions 表（content_type、content_id、payload、created_at），保留最近 N 版（**建议 N=10，待确认**），超出滚动删除。
- 后端：保存时异步写版本；admin 端点：版本列表、查看、恢复（恢复=以旧版内容新建一次保存，不改历史）。
- 前端：编辑器侧栏「历史版本」抽屉，diff 预览（先做纯文本 diff，不引重库）。
- 验收：误删段落后可从任意历史版本恢复；乐观锁行为不受影响。

**4D · 仪表盘访问趋势与存储占用（3–4 天）**

- 数据库：V20 view_daily(content_type, content_id, day, count) 按日聚合，UPSERT 累加，保留 180 天定时清理；**不存 IP/UA，无隐私负担**。
- 后端：浏览量 +1 时同步 UPSERT 当日行；stats 端点扩展：近 30 天趋势数组、热门 TOP5、附件总大小（sum(length(bytea))）、各状态内容计数。
- 前端：AdminDashboard 加趋势折线（纯 SVG/CSS，不引 chart 库，沿用图谱的确定性 SVG 经验）、TOP5 列表、存储占用卡片。
- 验收：趋势图与 view_daily 数据一致；仪表盘首屏仍一次请求出全部统计。

**4E · 图片/附件管理与孤儿清理（2–3 天）**

- 后端：admin 附件总览端点（分页、按笔记聚合、总大小）；孤儿判定（笔记正文不再引用 && 创建超 7 天）；清理走「标记 → 回收站列表 → 手动确认删除」，不做静默自动删。
- 前端：admin 新「附件」页：网格预览、按笔记筛选、孤儿标记与批量删除确认。
- 验收：删除孤儿后笔记渲染无 404；总大小与 4D 存储卡片一致。

**4F · L-1 音乐曲目与语录管理（新增，1–2 天）**

- 后端：music_tracks、quotes 的 admin CRUD（表已由 V13/V14 建立）；公开端不变。
- 前端：AdminDashboard 增两个简单管理区块（列表 + 表单，复用菜品管理交互）。
- 验收：不再需要改迁移/种子数据即可增删曲目与语录；NB-6 的按日选取逻辑同批确认。

### 4.3 阶段 5 · 检索与知识组织（2–3 周）

**5A · 中文全文检索（1–1.5 周，两步走）**

- 第一步 spike（0.5–1 天，先行）：在生产同版本 PG 上验证 zhparser 与 pgroonga 的安装可行性与运维成本（决策⑤已允许装扩展）；不可行则回退方案：pg_trgm（阶段 1 已建）+ 标题/摘要加权排序，不引扩展。
- 第二步实施：按 spike 结论建 tsvector 生成列 + GIN 索引（V23+）；SearchService 改向量检索、ts_rank 排序、ts_headline 高亮片段；LIKE 保留为降级路径。
- 前端：GlobalSearch 结果高亮（`<mark>`，消毒后插入）；搜索为空/降级状态提示。
- 验收：中文词组检索召回明显优于 LIKE（用真实文章对比留档）；高亮不引入 XSS（测试固化）。

**5B · 标签一等公民（3–4 天）**

- 数据库：V22 为 post_tags/learning_note_tags 的 tag 列建索引。
- 后端：/api/v1/tags（聚合三类内容计数）、/api/v1/tags/{tag}（分页聚合内容）；接入 sitemap。
- 前端：/tags/:tag 聚合页；文章/笔记详情的标签可点击；归档图谱 TAG 节点 url 从 null 改为标签页链接（修正 ai-archive 轮的遗留占位）。
- 验收：任意标签页可达且分页正确；图谱标签节点可跳转。

**5C · 知识图谱增强（3–4 天）**

- 后端：局部子图端点（center + depth 参数，默认 depth=2），复用 P1-5 缓存与 NB-5 投影。
- 前端：节点双击展开邻居、按需增量加载；类型过滤保持既有 URL 参数同步惯例；大图性能预算（首帧 <1s，节点 >300 时自动切子图模式）。
- 验收：全量与子图模式切换流畅；键盘可达性不回归（沿用 ai-archive 轮标准）。

**5D · 相关内容推荐（1–2 天）**

- 后端：详情响应附带「共享标签最多的同类内容 TOP3~5」（一条聚合查询 + 缓存）。
- 前端：文章/笔记/菜谱详情页底部推荐卡片。
- 验收：无共享标签时区块隐藏；查询不引入 N+1。

### 4.4 阶段 6 · 平台化与稳定运维（长期迭代池，按项独立启动）

| 项 | 内容 | 触发/前置 | 估时 |
| --- | --- | --- | --- |
| 6A 监控告警 | micrometer-registry-prometheus 暴露 /actuator/prometheus（仅内网）；PG 开慢查询日志（log_min_duration_statement=500ms）与 pg_stat_statements；外部拨测（如 Uptime Kuma 或第三方）监控 hxnf.top 与 /actuator/health | 服务器操作授权 | 1–2 天 |
| 6B 附件对象存储 | bytea → 本地磁盘（或 S3 兼容）+ nginx 直出；迁移工具 + URL 兼容层（旧 UUID 路径 301）；pg_dump 备份策略同步调整（附件改文件级备份） | 附件总量接近 GB 级或备份耗时显著时启动 | 3–5 天 |
| 6C 多用户与令牌 | refresh token（短 access + 可撤销 refresh）；roles 去硬编码 `["ADMIN"]`；admin_users 支持多账号与角色列 | 出现第二位写作者需求 | 3–4 天 |
| 6D SEO 预渲染 | vite-ssg 对公开页 SSG，或 nginx 按爬虫 UA 注入 meta 的轻方案 | **触发条件制**：搜索/社交流量显著或开始主动分发 | 评估 1 天 + 实施 2–4 天 |
| 6E L-4 备份恢复演练 | 每季度一次：从最近 pg_dump 在干净库完整恢复 + 抽查内容；备份增加**异机/对象存储副本**（当前 pg_dump timer 仅落在服务器本地盘，盘故障=数据与备份同失）；演练结果记 checkpoint | 无 | 首次 0.5 天，此后每季 0.25 天 |
| 6F L-5 依赖升级节奏 | 每月：npm audit / mvn versions 复查，patch 级随手升；每季度：minor 升级窗口（Spring Boot 3.5.x、Vite、Tiptap），major 单独评审；持续跟踪：brace-expansion 上游修复（恢复 audit 阻塞）、Flyway×PG18、Vitest/rolldown 平台二进制在 CI 与本机差异 | 无 | 每月 ≤0.5 天 |
| i18n | 全站中文硬编码，改造成本高 | 长期搁置（沿用 v3 结论） | — |

---

## 五、常态机制（贯穿全程）

### 5.1 性能预算（阶段 1 建立基线，此后每阶段末复测留档）

| 指标 | 基线 | 目标 | 测法 |
| --- | --- | --- | --- |
| 首页传输体积（冷缓存） | 阶段 1 开始时实测记录 | 较基线 −50% 以上（NF-6/NF-10/P1-7 合力） | 浏览器 DevTools / Lighthouse |
| Lighthouse 移动端性能分 | 阶段 1 实测记录 | ≥90 并保持 | 每阶段末跑一次留档 |
| 列表页 SQL 条数 | 11+（N+1 现状） | ≤3 | SQL 日志 |
| 公开 API 详情 P95 | 阶段 6A 上监控后记录 | <200ms（服务器本地口径） | Prometheus |
| 首屏 JS（gzip） | 构建产物分析记录 | 公开页不含编辑器/KaTeX chunk | build 分析 |

### 5.2 质量门禁（沿用并升级）

- 每次提交：改动范围相称的测试 + `git diff --check`；前端另跑 test:typecheck。
- 每阶段末：后端 `mvn test` 全量、前端 `npm test` + build、Lighthouse + 关键 EXPLAIN 留档、checkpoint 文档（沿用 docs/ 惯例）。
- 覆盖率（P2-5 后）：JaCoCo 阈值只升不降；新增模块（series、revisions、tags）必须带测试进主干。

### 5.3 安全复审（每季度 0.5 天）

对照阶段 0 清单复查：denyAll 白名单是否被新路由破坏、限流阈值、依赖 CVE、nginx 头、JWT 密钥与口令年龄、DOMPurify/jsoup 版本；AI 平台化上线后加查：供应商密钥年龄、base_url 清单是否仍全部指向预期端点、allow-local-endpoints 开关状态、ai_usage 有无异常用量；人机验证上线后加查：PoW 难度是否仍匹配主流设备、失败日志有无绕过尝试模式。发现新面（如 SSE、对象存储直出）随功能设计时先过一遍威胁模型再写码。

### 5.4 机会池（不排期，条件成熟再评审）

评论系统（决策③暂不做，若读者互动需求出现再重评）、公开站点访客 AI 问答（成本与滥用面大，需独立预算与防滥用设计，明确不随 4A 顺带做）、Webmention、站点统计对外页、PWA 离线阅读增强、图谱导出图片、菜谱购物清单导出。

---

## 六、执行原则

沿用 v3 七条，新增第八条：

1. 严格按阶段顺序推进，阶段内单项可穿插，但**不跨阶段预支功能**。
2. 每项独立提交（feat/fix/test + 中文摘要）。
3. 测试先行：先补失败测试再修复/实现。
4. 迁移只增不改（V15+ 顺序分配，禁动历史迁移）。
5. 公开 API 不做未经批准的破坏性变更。
6. 每阶段更新文档并出 checkpoint。
7. 每阶段末跑 Lighthouse 与 EXPLAIN 留档，性能改进可量化回溯。
8. **涉及服务器/凭据/部署授权的操作（CD Secrets、PG 扩展安装、监控组件、口令轮换）一律列明清单请用户执行或书面授权，代码侧只做无凭据可运行的部分。**

**配套 agent 提示词与协作循环。** 本计划配三份可直接注入的提示词，置于 `.agents/`：`V4-FRONTEND-AGENT.md`（前端执行，只动 frontend/）、`V4-BACKEND-AGENT.md`（后端执行，只动 backend/、deploy/、CI 与迁移）、`V4-ACCEPTANCE-AGENT.md`（独立验收，只读代码 + 复跑命令 + 出验收报告，不写业务代码）。使用循环：用户在提示词末尾「本次任务」填入条目编号 → 执行 agent 测试先行实现并出 checkpoint → 验收 agent 独立复跑、逐条对照验收标准出 `docs/acceptance-*.md` 报告 → 用户依据报告决定推送与部署。跨端条目由后端 agent 先落契约（API + README/architecture 文档），前端按文档化契约实现；agent 会话间无共享记忆，docs/ 下的 checkpoint 与验收报告是唯一交接媒介。本计划后续修订时，须同步检查三份提示词中引用的条目号、迁移号与命令。

---

## 七、决策记录与待决策

### 7.1 已批准（沿用，见 1.4）

九项既有决策全部继承，不再重复。

### 7.2 本文新增、待批准

| # | 建议 | 默认建议 |
| --- | --- | --- |
| D-1 | CD 流水线自第四期提前至阶段 2 | 同意提前 |
| D-2 | RSS/相邻导航降权为阶段 3 可选尾项；SEO 预渲染改触发条件制留在阶段 6 | 同意（与权重决策一致） |
| D-3 | 新增 L-1 曲目/语录管理（阶段 4）、L-2 nginx gzip/HTTP2、L-3 生产 sourcemap 策略、L-4 备份恢复演练与异机副本、L-5 依赖升级节奏、L-6 大组件择机拆分 | 全部纳入 |
| D-4 | 版本历史保留份数 N=10 | 待确认数值 |
| D-5 | 3A-5 存量迁移验证通过后是否删旧 HTML 列（V18） | 到期单独评审，默认保留一个阶段再删 |
| D-6 | AI 多模型配置存储方式：**推荐「DB 注册表 + AES-GCM 加密密钥、主密钥在 .env」**（界面管理、不重启生效）；备选「纯 env 多组配置」（更简单，但增删供应商需改文件重启） | 推荐前者 |
| D-7 | 登录人机验证形态：**推荐「隐形 PoW 常开 + 失败 ≥3 次触发自托管图形验证码 + 失败 ≥10 次冷却」**；备选「仅图形验证码常开」（有感、无感门槛低）或「接入第三方（Turnstile/极验等）」（境内连通性与商业依赖需另评） | 推荐自托管三层 |

### 7.3 开放问题（不阻塞启动）

1. CD 前置：服务器 deploy 用户 + GitHub Secrets/Environment 何时配置（阶段 2 启动前需就绪）。
2. 5A spike 的服务器操作窗口（zhparser/pgroonga 试装）。
3. 6B 对象存储选型（本地磁盘 vs S3 兼容）留到触发时定。

---

*本文基于 2026-07-26 工作区代码与 docs/ 全部既有文档（v3 计划、阶段 0 checkpoint、architecture、历次 checkpoint）整理；条目号 P/NB/NF/ND 与 v3 完全对应，L/D 为本文新增。*



