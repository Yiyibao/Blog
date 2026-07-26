# Checkpoint · 阶段 4 管理端增强收尾（2026-07-27）

依据：docs/optimization-plan-v5-2026-07-27.md §4.2。本档记录阶段 4 全部子项的落地提交、验证终值、决策与台账，作为销案凭据。

## 1. 提交清单（本阶段八笔功能提交，全部已推送 codex/blogdemo）

| 子项 | 提交 | 摘要 |
| --- | --- | --- |
| 4A-3 供应商管理 UI | ba607c2（前一批已档：checkpoint 补档见 5714097） | 注册表全程界面化，密钥只写不回显尾 4 位 |
| 4A-6 用量与审计 | cee875c | ai_usage 开始写入；日预算双限 429；REQUIRES_NEW 旁路审计；/admin/ai/usage 聚合 |
| 4A-4 侧边栏形态 | 9414fb2 | AdminAiSidebar 停靠栏：折叠/拖宽 320–640/移动抽屉/供应商模型切换/Ctrl+Shift+A |
| 4A-5 场景化动作 | c743738 | 编辑器 AI chips 五动作，7000 字截断，结果只填入不保存 |
| 4F 曲目与语录管理 | 69b899f | V13/V14 接上 admin CRUD，写校验杜绝占位外链，evict MUSIC/QUOTES；NB-6 同批确认 |
| 4B series 合集 | 654e365 | V11 全链路：admin CRUD+整表排序 409、公开按序读剔除未发布、文章合集条、sitemap/图谱 SERIES、删除钩子 |
| 4C 草稿版本历史 | 56a7e6c | V23 post_revisions：保存即快照留 10 版、列表/查看/恢复（恢复=回写+再快照）、前端历史抽屉+LCS diff |
| 4D+4E 趋势与附件 | e7a76b7 | V24 view_daily UPSERT+180 天清理、stats 扩展（趋势/TOP5/状态/容量/AI 卡片）、SVG 折线；附件总览+孤儿标记+删除 |

## 2. 验证终值

- 后端：容器模式隔离 worktree（Testcontainers postgres:17，删 .env.properties 强制）全量 **368/368 绿**（阶段起点 348 → +20：series 9 + revisions 4 + viewDaily 3 + IT @Order(56)(57)(58) 及既有适配）。
- 前端：**330/330 绿**（+16：停靠栏 4、chips 4、series 页 4、textDiff 4）+ vue-tsc typecheck + vite build（PWA 110 entries）全绿。
- 迁移台账推进：V23 post_revisions、V24 view_daily 落地（「实际最高号+1」规则；V20 仍为美食专项 NF-12 预留）。

## 3. 实现决策与计划偏差（均属默认授权范围）

1. **编排层挂点原则**（4B/4C/4D 一以贯之）：删除钩子、版本快照、趋势 bump 都由 Controller 编排调用新服务，避免 PostService 等核心服务构造器涟漪与 post↔series 包循环；PostSeriesRef 因此定义在 post 包。
2. **4C 保留版本数 N=10**：计划书「待确认」，按默认值执行，可后续调 KEEP 常量。
3. **4E 简化回收站**：计划书「标记→回收站→手动确认删除」实现为「孤儿标记 + 逐个确认删除」（无回收站中转表）——附件删除本身已有 window.confirm 双确认，7 天宽限期防误判新附件；如需真回收站属后续增强。
4. **4D 日窗口 Asia/Shanghai**：与 4A-6 AI 日预算同约定；bump 走 REQUIRES_NEW 旁路吞异常，趋势统计永不影响详情读主流程。
5. **ResponseStatusException 专属处理器**（4B 随批修复）：原先被 Exception 500 兜底吃掉状态码，现按自带状态码回包——series 的 400 语义因此正确；全库仅 series 使用该异常，无行为回退面。

## 4. 过程修复记录

- 4B 首轮全量红 11 例：GraphServiceTest/CacheBehaviorTest 缺 SeriesService 依赖（补 @Mock/@MockitoBean，默认空 Map 不出 SERIES 节点）；IT 重复成员期望 400 实得 500（上述 ResponseStatusException 处理器修复）。复跑 359/359 绿。
- 4B setEntries 乐观锁：字段无变化时 Hibernate 不置脏、@Version 不推进——SeriesEntity.touch() 显式置脏修复（有 IT 断言 version 推进）。
- 4D 前端：AdminSidebar 测试 mock 仍是旧 stats 形状致 DashboardTrends 崩溃——渲染前防御 viewTrend 数组存在（顺带保护滚动部署窗口内旧后端响应）。

## 5. 用户台账（挂起项不变，新增 0 项）

- 4F 占位外链（cdn.example.com 曲目）替换需真实音频地址——用户内容决策；管理页已高亮 ⚠ 提示。
- 3A-5 全局存量签收、NB-9 flyway repair、P2-7 renormalize（连带 NF-9/NF-10）、Lighthouse 手测、CD Secrets——沿用前档，等待用户或静默窗。

## 6. 下一步

阶段 5 · 检索与知识组织（5A 中文全文检索 spike 先行，5B 标签一等公民，5C 图谱子图，5D 相关推荐——注意 5A 的 tsvector 迁移取号需按台账「实际最高号+1」，计划书原文 V23+ 已被 4C/4D 占用，实际从 V25 起）。
