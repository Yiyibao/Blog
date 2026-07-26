# Checkpoint · 阶段 2「工程质量与交付流水线」收尾（2026-07-27）

> 执行会话：主优化会话（与美食专项/FD 会话并行共写同一工作区）。
> 本阶段全部批次均遵循执行原则第 9 条：显式路径 `git commit --only`、隔离 worktree 验证、共享文件与并行会话错峰提交。

## 一、本阶段提交清单（codex/blogdemo，时序）

| 提交 | 条目 | 摘要 |
| --- | --- | --- |
| 6a3a941 | P2-1 / P2-2 / NB-11 / P2-10(部分) | 全局异常五类兜底统一包络；8 控制器声明式分页校验（越界如实 400）；创建端点 201 对齐、NoteRequest.status 不再静默改写；AdminBootstrap 幂等 3 例 |
| 1075712 | P2-9 | RequestIdFilter（透传合法上游 ID/受控字符集防日志注入/MDC+响应头）+ logback 控制台格式加 [requestId] |
| bb69086 | NB-7 / NB-8 | 缓存拦截器按端点分型（计数类 no-cache；/graph、/quotes 入可缓存列表）；PublicUrls 统一公开页 URL 出处（RSS 前置） |
| 89514f1 | L-8 / L-9 后端 | SearchRequest+categorySlug/sort 下推数据库；SearchResult+date/readTime/tags（POST 分支实装，批量补标签）；GET /posts featured=true 直查 |
| 1418198 | L-8 / L-9 / L-11 前端 | 搜索过滤/排序透传删客户端补偿；精选专用请求（出窗可命中）；详情返回链接还原 ?page=N |
| d215985 | NF-4 / NF-8 / NF-11 | 播放器 onended 切歌；灯箱无障碍对话框（Esc/焦点陷阱/还原）；归档部分失败提示条；种子 dev 门控+骨架屏 |
| ec927fd | D-11 / P2-5(CI) / CD | quality job 全区间检查；覆盖率工件上传；deploy.yml（tag/手动+Environment 批准门+rsync 原子切换+健康检查+保留 5 版） |
| bc949de | P2-3 / P2-5(pom) / P2-10(余) / NB-4 / NB-6 | springdoc 默认关闭 env 门控（关闭态如实 404）；JaCoCo 0.8.13；JWT 过期/坏签名拒收单测；附件尺寸预检 ≤8000×8000；quotes/daily 按日轮转 |
| c8b77c1 | P2-4 / NF-9(部分) | TestDatabase 支撑类统一双模式（可达直连快速模式，否则自动起 postgres:17 容器；CI 撤手工 service）；死代码清理（uiStore.menuOpen、data.ts categories、fonts preconnect×2） |

并行会话同窗落库（供合并对账）：07f33a2 FD-8、dd91650 FD-25、7056d2c FD-10（V19 kitchen）、ca194b7 FD-11。

## 二、验证记录

- 每批均在隔离 worktree（scratchpad/stage1-verify）+ 本机 PG 全量验证后提交；后端从 275 → 306+（随双会话批次累进），前端 236 → 258，typecheck/build 全绿。
- 共库竞态实录：双会话同刻跑 IT 会互相 drop schema / 抢占登录状态，本阶段出现 3 次批量假失败（26/25/1 例），均复跑即绿。P2-4 落地后本会话 worktree 验证改走 Testcontainers 容器模式（删 .env.properties 触发），从根上隔离。
- P2-4 容器模式验证：无 .env.properties 环境下全量后端套件在自起 postgres:17 容器内 **316/316 全绿**，JaCoCo 报告同轮产出；前端收尾态 258/258 + typecheck + build 全绿。
- JaCoCo 基线：报告随 mvn test 产出 target/site/jacoco，CI 上传工件；阈值按「只升不降」原则待下阶段以基线数字固化（6C）。

## 三、条目销案对照

| 条目 | 状态 | 备注 |
| --- | --- | --- |
| P2-1/P2-2/P2-3/P2-4/P2-5/P2-9/P2-10 | ✅ | P2-6 上阶段已完成 |
| NB-4/NB-6/NB-7/NB-8/NB-11 | ✅ | |
| L-8/L-9/L-11 | ✅ | 契约扩展为非破坏性增量 |
| NF-4/NF-8/NF-11 | ✅ | NF-8 播放器/灯箱/归档提示条/aria 四点全落 |
| NF-9 | ✅(3/4) | FoodSection favoriteDishes 只写不读一项属美食专项文件，移交其销案 |
| P2-8 | ✅（并行覆盖） | AI 限额校验已由 AI 加固收敛批次归一至 AiChatService 单入口（类注释明示"唯一入口"） |
| CD 流水线 | ✅（代码侧） | deploy.yml 已落地；deploy 用户/SSH key/GH Secrets/Environment 为用户操作，配置前不会误触发 |
| D-11 | ✅ | 随 CI 批落地（未做 renormalize，历史区间检查以 push 区间为界，安全） |
| P2-7 (.gitattributes renormalize) | ⏸ 顺延 | 全库重写波及面大，必须等美食专项收口的静默窗执行；届时连带 NF-9 移交项、NF-10（阶段 1 顺延项）同批 |
| NB-9 (seed profile 分离) | ⏭ 跳过（需用户操作） | 迁移文件去种子会改 V1/V6/V13/V14 校验和，存量库需用户决策并执行 flyway repair；纯新库诉求暂无，降权挂起 |
| NF-12 (base_servings) | ⏭ 移交 | 计划明示美食专项顺带销案，DishPanel 已留注释位（V20 号预留） |

## 四、遗留与下一步

- 阶段 2B（用户优先批次 L-13~L-16）为下一阶段，依赖 L-7（已完成）；L-16 与美食专项的鉴权耦合需在开工时对表。
- 待用户执行台账（不阻塞开发）：Lighthouse 手测留档、P0-8 服务器口令轮换、nginx 部署、CD Secrets/Environment 配置、（新增）FD-11 部署注意——线上 nginx 若配 proxy_cache 需手工清 /api/v1/dishes/favorites 副本、（新增）NB-9 若要新库无种子需 flyway repair 决策。
- main 分支在本 checkpoint 后快进至阶段 2 收尾提交。
