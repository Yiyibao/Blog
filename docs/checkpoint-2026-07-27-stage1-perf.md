# 阶段 1（性能优化）收尾检查点（2026-07-27）

执行依据：v5 计划 3.1/3.2 阶段 1 剩余项；用户 2026-07-27 全权授权（按阶段执行、阶段末更新计划书并推送、需用户操作项跳过留档）。

## 已完成项（本阶段 6 笔提交）

| 条目 | 提交 | 内容与量化证据 |
| --- | --- | --- |
| P1-3 | ac19026 | 读路径零消毒（写入已消毒入库，PostResponse 直接返回存储值），回归测试 clearInvocations+verifyNoInteractions |
| P1-4/NB-1 | 1ec23c7 | **V18** pg_trgm 三表 GIN + (category_slug,status) 复合索引；EXPLAIN ANALYZE 3000 行基准：**Seq Scan 20.5ms → BitmapOr 四路 trigram 0.22ms（≈93×）**；复合索引低选择率下计划器合理选顺扫 |
| P1-5/NB-5/L-12/P1-8 | 39f9d43 | Caffeine 缓存（graph/sitemap/quotes/music，TTL 5min + 全部 admin 写操作跨模块 evict，CacheBehaviorTest 2 例）；图谱/搜索全投影不载正文；列表 Repository 投影 + 标签 IN 批查询（ListQueryBatchingTest 收紧 **≤2 prepares** 且改走 Service 真实路径）；viewsCount 原子自增 + IP+slug 10 分钟去重（IP 不落库），集成测试 @Order(40) |
| P1-6 | 6067800 | 公开附件 no-store → max-age=31536000+public+immutable（撤回后服务端仍 404，缓存副本残留为批准取舍）；管理端预览 1h private；IT 断言同步 |
| NF-7 | 563dc6e | AmbientSound/KnowledgeGraph/InspirationCard 裸 fetch 收编 api 层，回退语义逐一保持；测试改 mock api 模块 |
| NF-6/P1-7/L-3 | b7ea796 | **PWA 预缓存 6088KB→2306KB（−62%）**：位图退预缓存改运行时 CacheFirst + 1MB 兜底；真实方形图标 192/512+favicon；og 重制 1200×630（路径零改动）+ 静态 meta 绝对 URL；hero 1.91MB→171KB JPEG；food 图全部 ≤140KB，**public/ 5.84MB→约 2.5MB**。manualChunks：**katex 257KB / tiptap 549KB 独立懒载**（原 744KB 混合块消除），公开首屏 index 133KB gzip 50KB。sourcemap hidden |

## 验证结果

- 后端：隔离 worktree（HEAD + 本批 diff）`mvn test` **271/271，0 失败 0 错误**（基线 265 + 新增 6：读零消毒、registerView、缓存 2 例、列表标签组装 2 例；含 V18 迁移实跑与 IT 全量）。
- 前端：主树 `npm test` **236/236**（24 文件）、`test:typecheck`、`npm run build` 全绿。
- 全程与美食专项会话（FD）并行执行零冲突：迁移号 V18 抢号事件当场修正台账（e92a43c）、共享 DB 竞态改用 bench schema 采证、提交一律 `git commit --only` 显式路径。

## 阶段 1 验收门对照（v5 3.5）

- ✅ 全量测试绿（双端数字如上）。
- ✅ 列表页 SQL ≤2 条且不含正文列（ListQueryBatchingTest 固化断言 + 投影结构保证）。
- ✅ EXPLAIN 留档（本文与提交说明）。
- ✅ 预缓存体积对比留档（6088→2306KB）。
- ⚠️ Lighthouse 移动端跑分：本机无 lighthouse CLI，**未验证**——建议用户在 Chrome DevTools 手动跑一次留档作为长期基线（生产部署后测更准）。
- ⚠️ 首页传输体积冷缓存对比：资产层已量化（public/ −57%、hero −91%）；整页 E2E 数值待生产部署后实测。

## 计划内顺延

- **NF-10（styles.css 拆五文件 + 后台样式懒加载）**：与美食专项第二批（kitchen 前端，重度使用 styles.css）物理冲突不可避免，按并行会话纪律顺延至 FD 第二批收口后立即执行，仍归属阶段 1 口径。

## 新发现（只记录）

1. og.png 转 JPEG 可再省 ~1MB，但引用改动涉及 FoodSection 等 FD 辖区文件，留待协调（原图备份于会话 scratchpad nf6-originals/）。
2. KaTeX 字体文件约 60 个仍进预缓存（~700KB），字体子集化可作后续小项（未立项）。
3. quotes/music 的缓存无写失效入口（无管理端），4F 上线时必须补 @CacheEvict——已在 CacheConfig 注释与 4F 条目中双向标注。

## 待用户执行（跳过项台账）

1. 生产部署本批后：`nginx -t && systemctl reload nginx` 无需改动（本批未动 nginx）；但 V17+V18 迁移随下次部署自动执行，**V18 的 pg_trgm 在生产库执行需库 owner 权限**（trusted 扩展，yubai_app 即可，无需超管——bootstrap 建库时 owner 正确则零操作）。
2. Lighthouse 手动跑分留档（见验收门 ⚠️）。
3. 前置 ②（P0-8 口令轮换 + nginx 部署验证）仍未销账；FD 检查点另有 partner 凭据配置待办。

## 下一步

阶段 2 启动（工程质量与交付流水线）：P2-1/P2-2/NB-11 契约收口 → P2-3 springdoc → P2-9 requestId → P2-10 认证单测 → NB-4/6/7/8 → NF-4/8/11/12 → L-8/L-9/L-11 → P2-4 Testcontainers → P2-5 JaCoCo → P2-7 仓库清理(.gitattributes renormalize，需与 FD 协调时机) → CD 流水线（Secrets 部分跳过留档）。NF-10 与 FD 收口联动插入。
