# BlogDemo 项目整体审查与执行计划

> 审查日期：2026-08-09
> 项目路径：`D:/Office/Study/code/BlogDemo`
> 文档状态：已执行完成（生产部署变更除外；评论系统按本计划的安全建议明确暂缓；详见 `docs/checkpoints/execution-progress-2026-08-09.md`）
> 目标：为后续对话提供可直接执行的优化与功能拓展路线图

## 1. 结论摘要

BlogDemo 已经发展为一个综合内容平台，包含：

- 公开博客、文章系列、标签、归档和 RSS/SEO；
- 私人学习笔记、附件和知识图谱；
- 菜谱、菜品收藏、今日菜单和用餐记录；
- 后台内容管理、AI 对话、AI 图片、供应商管理和用量统计；
- JWT/TOTP/刷新令牌、人机验证、限流、Prometheus、备份和自动回滚。

当前没有发现必须立即停站的 P0 级故障。主要矛盾已经从“功能不够”转变为：

1. 复合模块过多导致维护成本快速上升；
2. 数据迁移和演示数据边界不够安全；
3. AI/外部抓取任务仍有同步阻塞和并发治理问题；
4. 依赖、CSP、生产配置和多实例能力需要加强；
5. 构建产物、部署资产和历史文档存在漂移；
6. 内容创作工作流和搜索中心仍有较大的产品提升空间。

建议执行顺序：

> 正确性与安全 → 数据治理 → 异步任务 → 架构拆分与质量门禁 → 性能与部署收口 → 产品功能拓展

## 2. 当前验证基线

### 2.1 已通过验证

- 前端 Vitest：64 个测试文件，797 个测试通过；
- 前端 TypeScript/Vue 类型检查通过；
- 前端生产构建通过，PWA 和预渲染流程通过；
- 后端 Maven 测试：717 个测试通过；
- `git diff --check` 通过；
- 后端已有 JaCoCo 报告；
- 认证、刷新令牌轮换、附件 magic-byte 校验、路径穿越防护、健康检查和发布回滚已有实现。

### 2.2 当前警告和不完整项

- `npm audit --omit=dev` 返回 3 项漏洞：DOMPurify、nanoid、PostCSS；其中包含 high 级传递依赖；
- 前端构建提示 Tiptap chunk 约 572KB、KaTeX chunk 约 258KB；
- PWA precache 约 2.7MB；
- 后端总覆盖率约为 instruction 75%、branch 57%；`admin.recipe` 包覆盖率明显偏低；
- PostgreSQL 18.4 高于当前 Flyway 已验证支持版本 17；
- V36 迁移对 `dish_import_staging` 重复添加已有列，会产生迁移警告；
- Mockito/ByteBuddy 依赖动态附加 Agent，未来 JDK 可能默认禁止；
- 前端没有 ESLint/Prettier 脚本；
- 前端没有正式的 coverage、E2E、可访问性和性能预算门禁；
- 当前工作区有未跟踪生成物和日志：
  - `frontend/outputs/food-real-20260803/`
  - `frontend/vite-test.log`
  - `frontend/vite-test-err.log`

## 3. 优先级问题清单

### R-01：浏览统计会污染全站日趋势，优先级 P1

文章、菜谱、笔记详情接口调用 `registerView()` 后，不检查数据库实际更新行数，直接执行 `viewDaily.bump()`。

涉及文件：

- `backend/src/main/java/com/yubai/blog/post/PostController.java`
- `backend/src/main/java/com/yubai/blog/dish/DishController.java`
- `backend/src/main/java/com/yubai/blog/note/PublicNoteController.java`

风险：访问不存在或未发布的 slug/id 时，内容浏览量可能不变，但全站趋势仍然增加。

处理方案：

1. `registerView()` 返回 `1` 时才执行 `viewDaily.bump()`；
2. 封装统一的 `ViewCounter`，避免三套控制器逻辑继续分叉；
3. 增加不存在、未发布、限流、并发访问回归测试；
4. 检查统计仪表盘和趋势接口是否与真实数据库计数一致。

### R-02：Flyway 中混入破坏性演示数据，优先级 P1

`V34__publish_ten_posts_and_replace_dishes.sql` 包含：

```sql
delete from dishes;
delete from dish_categories;
```

V39 继续写入大量演示文章和菜谱。

处理方案：

1. 后续不得修改已经执行过的 Flyway 文件；
2. 将演示数据迁移到独立的开发初始化脚本或 `dev-seed` 命令；
3. 生产启动时禁止自动写入演示数据；
4. 对空数据库和生产近似数据库各执行一次迁移测试；
5. 增加迁移前置检查、备份确认和危险迁移阻断；
6. 对 V36 的重复列定义保留历史事实，后续通过新迁移或文档台账说明，不直接重写已执行脚本。

### R-03：前端依赖审计未通过，优先级 P1

当前审计发现：

- `dompurify`：直接依赖，当前解析版本存在安全公告；
- `nanoid`：Vite/PostCSS 传递依赖，包含 high 级公告；
- `postcss`：构建链依赖，存在安全公告。

处理方案：

1. 升级直接依赖和相关构建链；
2. 重新生成 `package-lock.json`；
3. 运行完整测试、构建和审计；
4. 对无法立即升级的依赖建立临时豁免记录和到期日期；
5. 恢复 CI 中生产依赖审计的阻断作用。

### R-04：菜谱提取接口同步执行，优先级 P1

`RecipeExtractionService.create()` 虽然有 `recipe_extraction_jobs` 表和任务状态，但在一次 HTTP 请求中同步完成：

- 网页抓取；
- `yt-dlp` 子进程；
- AI 调用；
- 结果校验；
- 图片存储和 `.yrecipe` 生成。

风险：慢请求占用 Web 线程；取消接口无法真正取消正在执行的同一请求；并发任务可能耗尽 CPU、临时目录或外部服务配额。

处理方案：

1. 创建接口只创建任务并返回 202；
2. 使用有界线程池或持久化任务队列；
3. 增加最大并发数、超时、重试、取消和过期清理；
4. 对 `yt-dlp` 设置临时目录、CPU、文件大小和网络出口限制；
5. 对每个管理员或账号增加任务配额；
6. 前端轮询任务状态，失败时展示安全错误信息。

### R-05：AI 日预算在并发下可能超额，优先级 P1

当前逻辑是先聚合当天用量，确认未超限后调用 AI，调用结束再写入用量表。多个并发请求可能同时通过检查。

处理方案：

- 建立按 provider/day 的原子计数表；
- 或使用数据库锁/序列化事务；
- 分别记录请求数、输入 token、输出 token、错误数、延迟和估算费用；
- 为 AI 图片生成单独设置额度；
- 增加预算接近阈值时的告警。

### R-06：限流、验证码和登录失败状态只存在进程内，优先级 P1/P2

`RateLimiter`、`ChallengeService`、`LoginAttemptTracker`、`TotpChallengeStore` 均使用进程内 Map。

当前单实例部署可以接受，但多实例部署时会出现：

- 不同实例限流不一致；
- 验证码在实例间不可见；
- 重启后状态丢失；
- 登录防护强度下降。

处理方案：

1. 当前阶段在部署文档中明确“单实例约束”；
2. 未来扩容时统一迁移到 Redis 或 nginx/API Gateway；
3. 不要在没有共享状态方案前直接增加多实例副本。

### R-07：核心文件过大，优先级 P2

重点文件包括：

- `frontend/src/api/admin.ts`
- `frontend/src/components/AdminDashboard.vue`
- `frontend/src/components/food/FoodSection.vue`
- `frontend/src/components/AdminAiChat.vue`
- `backend/src/main/java/com/yubai/blog/dish/DishImportService.java`
- `backend/src/main/java/com/yubai/blog/admin/recipe/RecipeExtractionService.java`
- 多个 AI Provider Client

拆分原则：

- 前端按 `content / ai / recipe / attachment / auth / kitchen` 拆 API；
- 页面拆为容器组件、数据 Composable 和展示组件；
- 后端将任务编排、外部调用、数据转换、文件存储分开；
- AI 客户端统一 Adapter、超时、重试、错误和用量处理；
- 每次拆分只改一个领域，并保持测试持续通过。

### R-08：部署资产和文档漂移，优先级 P2

项目同时存在：

- `frontend/.openai/hosting.json`；
- `frontend/worker/`；
- `frontend/scripts/prepare-sites-build.mjs`；
- nginx 静态部署；
- 旧文档中“删除 worker/hosting 配置”的决策。

需要明确唯一生产路线：

1. nginx + Spring Boot 单体部署；或
2. Sites/Cloudflare Worker 部署。

如果生产实际使用 nginx，应删除或隔离未使用的 Worker 构建产物，并更新所有文档和 CI 检查。

## 4. 功能拓展建议

### F-01：内容创作工作流，最高产品优先级

- 草稿、预览、定时发布和撤回；
- 自动保存；
- 文章/笔记版本历史和恢复；
- 批量发布、归档、打标签；
- 发布审计日志；
- 附件回收站、重复检测、容量配额。

### F-02：搜索中心

- 独立搜索页面；
- 文章、笔记、菜谱、标签、分类和时间筛选；
- 相关性排序和关键词高亮；
- URL 同步搜索条件；
- 搜索历史和常用搜索。

当前 PostgreSQL `LIKE + pg_trgm` 方案可以继续使用。只有在数据规模和查询延迟达到阈值后，再评估中文分词、PGroonga 或其他方案，不建议直接切换到不适合中文的默认 `tsvector`。

### F-03：AI 内容工作台

- 基于文章/笔记/菜谱的上下文引用；
- 一键生成标题、摘要、标签、SEO 描述；
- Prompt 模板；
- AI 任务历史和结果复用；
- 异步任务、重试和供应商故障转移；
- 费用、延迟、成功率和 token 看板。

### F-04：菜谱和厨房增强

- 多道菜合并购物清单；
- 购物清单导出和打印；
- 一周菜单规划；
- 已做菜谱和收藏夹筛选；
- 菜谱图片、步骤和食材的批量编辑。

### F-05：知识图谱增强

- 节点类型筛选；
- 中心节点局部展开；
- 超过节点阈值时自动切换子图；
- 图谱导出 JSON/图片；
- 关系来源、更新时间和手工维护能力。

### F-06：第二阶段体验功能

- PWA 离线阅读和离线收藏；
- 动态文章分享卡片；
- 阅读进度和阅读历史；
- RSS/Atom 订阅增强；
- 多角色协作。

评论系统建议暂缓，必须先设计审核、反垃圾、举报、封禁和隐私策略。

## 5. 分阶段实施计划

### 阶段 0：建立基线和冻结决策，0.5—1 天

任务：

1. 保存测试、覆盖率、构建体积和依赖审计结果；
2. 确认生产部署路线；
3. 备份数据库并记录当前 Flyway 版本；
4. 建立唯一有效的当前路线图；
5. 明确单实例部署约束；
6. 补充 `.gitignore`，隔离构建产物和日志。

验收：所有后续任务都有明确的基线、责任边界和验证方式。

### 阶段 1：正确性与安全，2—3 天

任务：

1. 修复 R-01 浏览趋势误计数；
2. 补充浏览统计回归测试；
3. 升级 DOMPurify、Vite、PostCSS、nanoid 等依赖；
4. 增加 CSP 报告模式；
5. 生产环境关键变量缺失时构建失败；
6. 统一错误响应和 `Retry-After`；
7. 复核外部 URL 的 DNS 重绑定、重定向和出口限制。

验收：测试、类型检查、构建和依赖审计全部通过；统计数据不再被无效 URL 污染。

### 阶段 2：数据库和内容治理，2—4 天

任务：

1. 停止在 Flyway 中写入演示数据；
2. 建立独立开发数据初始化流程；
3. 对空库和生产近似库执行迁移测试；
4. 增加迁移前置检查和备份确认；
5. 设计附件回收站、孤儿附件清理和容量配额；
6. 不重写已执行的历史迁移脚本。

验收：迁移不会删除生产内容；完成一次备份、迁移、恢复、校验演练。

### 阶段 3：异步任务和可靠性，3—5 天

任务：

1. 菜谱提取改为异步 Job；
2. 为 `yt-dlp` 增加有界并发和资源隔离；
3. 增加任务取消、重试和超时；
4. AI 预算改为原子计数；
5. 抽象 Provider Adapter；
6. 为外部服务调用统一超时、错误码、重试和熔断。

验收：提交任务接口快速返回；并发任务不会耗尽 Web 线程；AI 预算并发下准确。

### 阶段 4：架构拆分和质量门禁，4—7 天

任务：

1. 拆分前端 admin API；
2. 拆分后台大页面；
3. 拆分菜谱导入、AI Provider 和外部调用服务；
4. 启用 OpenAPI 并生成 TypeScript API 类型；
5. 增加 ESLint、Prettier、后端格式检查；
6. 增加前端 coverage、E2E、键盘和可访问性测试。

验收：新功能不再集中修改超大文件；CI 能检查格式、接口、测试、依赖和关键用户路径。

### 阶段 5：性能和部署收口，3—5 天

任务：

1. 控制首屏 JS、CSS 和图片预算；
2. 后台编辑器和 KaTeX 不进入首屏预缓存；
3. 为公开接口增加 ETag/条件请求；
4. 使用 `EXPLAIN ANALYZE` 和 `pg_stat_statements` 审查高频查询；
5. 完善 nginx CSP、缓存和健康检查；
6. 统一 Worker/Sites/nginx 部署路线；
7. 增加真实生产冒烟测试。

验收目标：

- 首屏不加载后台编辑器和 AI 大型依赖；
- 移动端 LCP 目标小于 2.5 秒；
- 发布后自动检查首页、登录、文章、菜谱、Sitemap 和健康检查；
- 发布失败可以自动回滚。

### 阶段 6：产品功能迭代，2—4 周

推荐顺序：

1. 内容发布工作流；
2. 搜索中心；
3. AI 内容工作台；
4. 菜谱购物清单和周菜单；
5. 知识图谱子图和筛选；
6. PWA 离线能力；
7. 评论和协作功能。

## 6. 总体验收标准

- 前端测试、类型检查和构建全部通过；
- 后端测试全部通过；
- 覆盖率只升不降，尤其提升 `admin.recipe` 和 AI 异常分支；
- 生产依赖无未处理 high 级漏洞；
- 生产迁移无破坏性删除；
- 浏览趋势与数据库实际更新行数一致；
- 菜谱提取不再同步阻塞 HTTP 请求；
- 首屏不加载后台编辑器和 KaTeX 大型依赖；
- CI 增加 lint、E2E、可访问性和构建体积检查；
- 文档只保留一份当前路线图，历史 checkpoint 统一归档；
- 工作区不再出现未跟踪日志和生成目录。

## 7. 下一个对话的启动方式

建议下一次对话直接使用以下任务描述：

> 请读取 `docs/project-audit-and-execution-plan-2026-08-09.md`，先执行阶段 1 的 R-01：修复文章、菜谱、笔记浏览统计对全站日趋势的误计数问题，并补充回归测试。完成后运行相关测试和全量验证，暂不处理其他阶段任务。

推荐第一批提交拆分为：

1. `fix: correct view analytics counting`
2. `chore: remediate frontend dependency vulnerabilities`
3. `refactor: move recipe extraction to async jobs`
4. `chore: separate demo seeds from production migrations`

每批只处理一个主题，完成测试后再进入下一批，避免在当前复合项目中一次性引入大量难以回滚的改动。

## 8. 执行原则

1. 先修正确性和数据安全，再增加新功能；
2. 不直接修改已执行的 Flyway 迁移；
3. 不在没有共享状态方案时扩展为多实例；
4. 每个重构任务必须有对应测试或契约保护；
5. 每阶段结束都要更新本文件或建立新的 checkpoint；
6. 不把演示数据、真实备案信息和生产密钥写入默认配置；
7. 不以增加微服务、Redis 或复杂基础设施作为默认答案，只有达到规模阈值后才引入。
