# Checkpoint · 阶段 3「渲染管线统一与创作体验」收尾（2026-07-27）

> 执行会话：主优化会话。全程隔离 worktree 容器模式（Testcontainers）验证 + `git commit --only`。

## 一、提交清单（codex/blogdemo，时序）

| 提交 | 条目 | 摘要 |
| --- | --- | --- |
| 4f1a455 | 3A-1 / 3A-2 | V21 双字段（markdown_content/content_format + trgm 索引入检索面）；条件校验契约（HTML 篇必带 content、MARKDOWN 篇必带 markdownContent）；jsoup 转换器（表格 GFM 尽力/嵌套列表/公式类/未知标签全落风险清单）+ 幂等转换端点（响应即校对清单） |
| 81dc380 | 3A-3 / 3A-4 / 3B | 管理端文章表单换 TyporaEditor（存量篇一键转换、保存即按篇切 MARKDOWN、HTML 快照保留可回退）；ControlledMarkdown 受控渲染（Tiptap 只读同笔记管线 + lowlight，HTML 篇 DOMPurify 兜底双防线）；TOC 从 Markdown 源提取（h-N 顺序 id 两侧对齐） |
| ea32aa0 | 3C / 3D | V22 笔记/菜谱 views_count + P1-8 模式推广（原子自增 + 10 分钟去重窗）；RSS 2.0 feed（/rss.xml，RSS 缓存 + 文章写操作 evict）；相邻文章导航（(date,id) 序 previous/next 进详情响应 + 前端双卡片） |

## 二、验证终值

- 后端：容器模式 341/341 全绿（含 IT @Order(44)/(53) 新链路断言）。
- 前端：314/314 + typecheck + build 全绿；tiptap/katex 继续走既有拆分 chunk，公开首屏不背编辑器体积。
- 途中修复：3D 邻居查询对未持久化夹具实体的 NPE（防御性跳过 + 空列表判定）。
- 竞态记录：一轮全量中 FD-25/kitchen 用例出现顺序性 500→401 级联，复跑与单测均绿，未再复现；IT 用例已知对执行顺序敏感（种子口令还原依赖），留观。

## 三、条目销案对照

| 条目 | 状态 | 备注 |
| --- | --- | --- |
| 3A-1~3A-4 | ✅ | 新文章全程 Markdown；双字段并存可回退 |
| 3A-5 | ✅（机制）/ ⏭（全局签收=用户环节） | 按篇切换已内建：编辑并保存即该篇签收切 MARKDOWN；全量转换端点产出校对清单，**逐篇人工校对与批量签收属用户操作**，完成前存量篇继续走 HTML 渲染；旧 HTML 列去留评审（原 V22 预留）待签收后再议，迁移号顺延 |
| 3B | ✅ | TOC 源文提取，DOM 依赖解除 |
| 3C | ✅ | 三类内容均出真实浏览数（posts/notes/dishes） |
| 3D | ✅ | RSS + 相邻导航双尾项全做（未降权跳过） |

## 四、待用户执行台账（增量）

- **3A-5 存量校对签收**：后台执行「一键转换」（或 POST /api/v1/admin/posts/convert-markdown）后，按响应清单逐篇核对高风险标记（表格/嵌套列表/公式），在后台编辑并保存即完成该篇切换。
- RSS 上线后可在页面 head 加 `<link rel="alternate" type="application/rss+xml">`（前端一行，随下阶段顺带）。

## 五、下一阶段

阶段 4 · 管理端增强（4A-4~4A-6、4B~4F）；4A-3 已由并行会话完成。
