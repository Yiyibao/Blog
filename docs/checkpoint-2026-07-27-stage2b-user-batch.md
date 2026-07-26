# Checkpoint · 阶段 2B「用户新增重点批次 L-13~L-16」收尾（2026-07-27）

> 执行会话：主优化会话（与美食专项/FD 会话并行共写同一工作区，全程 `git commit --only` + 隔离 worktree 容器模式验证）。
> D-15~D-18 按计划默认建议执行（用户批准的缺省授权）。

## 一、提交清单（codex/blogdemo，时序）

| 提交 | 条目 | 摘要 |
| --- | --- | --- |
| 2fa4160 | L-13 | 归档图谱重排（去视图切换/分类 tab，常驻时间轴上方单页流，旧 ?view=graph 兼容）+ 动态化（transform 布局过渡、错峰入场、连线描线、呼吸漂浮、哈希抖动有机布局）+ 平移/缩放/复位；reduced-motion 全降级（D-15 保留关联交互、D-16 零依赖自绘） |
| d58ebc6 | L-15 | HumanVerifyModal 点击式验证弹窗——PoW 进度/打勾、图形码升级同弹窗、429 冷却倒计时；协议零后端改动；useLoginForm 改弹窗门，关闭=中止；focus trap + aria-live；发现并规避 auth.ts memorySession 测试泄漏 |
| 1c9e8c9 | L-16 后端 | 游客收权（D-17 真隐藏）：/notes、/note-assets 出白名单 401；图谱 includeNotes 双缓存条目、搜索三分支剔除、sitemap 退收录；CurrentUser 统一判定；IT @Order(43) 四联断言；容器模式 333/333 |
| 8ec61f2 | L-16 前端 | EntryGate 入口大屏（D-18 根路径首访 + localStorage 记忆 + 深链直达）；/notes requiresAuth；三处导航笔记登录可见；管理员"进入后台"入口；归档游客不请求笔记 |
| 646c919 | L-14 | lowlight 3.3.0 + code-block-lowlight 3.28.0（exact 锁版）；11 门语言按需注册；编辑器语言选择器（围栏标记往返）；PublicNotes 同管线高亮；工具栏清晰化；lock 平台裁剪复发按既有手法补 @emnapi/runtime entry 并 docker 双端验证；code-highlight 独立懒 chunk 49KB，公开首屏不变 |

## 二、验证终值

- 后端：容器模式（Testcontainers，双会话彻底隔离）333/333 全绿（L-16 后端批）。
- 前端：310/310 + typecheck + build 全绿（L-14 收尾态；含并行会话全部已落库测试）。
- 产物：code-highlight 异步 chunk 49KB(gzip 15.4KB) 由 /notes 与后台笔记页共享；index 主 chunk 体积不变（P1-7 守住）。

## 三、决策执行记录（默认授权）

| 决策 | 执行 |
| --- | --- |
| D-15 | 仅移除类型分类 tab，保留节点点击关联（relation）筛选 ✅ |
| D-16 | 零依赖自绘（环形基座+哈希抖动+CSS 动效），未引入 d3-force ✅ |
| D-17 | 后端同步收权（真隐藏，笔记退出 SEO）✅ |
| D-18 | 入口屏仅根路径首访 + localStorage 记忆 + 深链直达游客态 ✅ |

## 四、遗留与移交

- 游客搜索 UI 的"学习笔记"分组：后端已恒空，前端分组按空数组自然不展示，无需改动（走查确认）。
- L-16 与美食专项鉴权耦合：角色统一走 authStore（FD-8 体系），无第二套逻辑；FD 会话后续批次若调整角色面需对表本 checkpoint。
- 阶段 2 顺延项不变：P2-7 renormalize（等美食专项收口静默窗，连带 NF-9 移交项与 NF-10）、NB-9（用户 flyway repair 决策）、NF-12（美食专项 V20）。
- 下一阶段：阶段 3（渲染管线统一与创作体验，3A Markdown 化为主项）。
