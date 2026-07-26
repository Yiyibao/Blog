# Checkpoint · 美食专项第一批（FD-0 ~ FD-7）· 2026-07-27

> 专项背景：美食页面专项优化（FD-\*，v5 计划 §1.2 在册并行专项）。方案经 4 设计线 + 3 对抗性审查视角 + 综合裁定，计划文件由用户批准。本批为「安全前置 + 纯前端可见成果 + 多用户地基」。

## 一、已完成项（编号 + 提交）

| 条目 | 提交 | 内容摘要 |
| --- | --- | --- |
| FD-0 安全前置 | 7cb9614 | ClientIps 转发头信任门槛（loopback 反代才采信，X-Real-IP 优先、XFF 取末位）；登录冷却键 IP→(IP,用户名) 配对，修家庭共用 Wi-Fi 互锁；登录成功 INFO 审计 |
| FD-1 nginx 加固 | b28190e | /api/ 全局兜底限速 60r/m burst=120；预置 /api/v1/kitchen/ location（128k 请求体上限）。**未部署验证**（本机无 nginx） |
| FD-2 抽屉主题化 | 7a465ce | 恒深色改 7 个 --cinema-\* 令牌（挂 :root，亮/暗各一组）；DishPanel.vue 拆出（L-6 第 1 块）；useFocusTrap；Esc 挂 window；.tap-44 约定类；焦点环回落 #0071e3 |
| FD-3 真实收藏接入 | 022bac5 | Dish.favoriteCount 类型补齐（同提交改 admin.ts Omit）；抽屉 48px 爱心（乐观更新+429 回滚+toast）；卡片只读徽章；**榜单换 GET /dishes/favorites 全站真实数据**（全零/失败整块不渲染）；食材勾选清单落地；**NF-9 死代码删除（提前执行，原计划随 P2-7，此处销案）**；utils/localStore.ts |
| FD-4 深链竞态修复 | 27cf96f | closeDish 清 ?dish=（修刷新弹开）；打开写 URL；detailCache 不插列表头（修 featured 误判）；useRequestToken 竞态守卫。6 例回归先红后绿 |
| FD-5 抽卡转盘+杂项 | 19ff245 | DishRoulette（rng 可注入、结果先定后动画、连点闸门、reduced-motion 直出）；useReveals 提取 + .reveal-lite；dish-card 指针光晕；Hero 统计单调累积；搜索空态；骨架网格镜像；移动端筛选去 sticky + 抽卡 FAB |
| FD-6 角色模型 V17 | c17ff03 | admin_users 加 role/display_name/sessions_valid_from；JwtService 去硬编码（roles/uid/name claim）；LoginResponse 加 role/displayName；MemberBootstrap 只建不改（口令 ≥12 强制）；v5 台账 V17 落账 |
| FD-7 kitchen 授权 | a2242cc | /api/v1/kitchen/\*\* hasAnyRole(ADMIN,PARTNER)（在 /api/\*\* 之前）；集成 @Order 35–39：PARTNER 打 12 管理端点逐条 403、解 JWT 精确断言、防用户名枚举、kitchen 前缀匿名 401 |

## 二、验证结果（真实数字）

- 后端：**265/265**（基线 239 → FD-0 +7 → FD-6 +13 → FD-7 +5 → 并行会话 P1-3 +1）。含本机 PG 集成测试实跑（yubai_blog_it，V17+V18 迁移实际执行、partner bootstrap 生效）。
- 前端：**236/236**（基线 187 + DishPanel 16 + FoodSectionFavorites 7 + DeepLink 6 + Roulette 9 + FoodSection 11）+ `test:typecheck` + `build` 全绿。
- 合流验证：FD-6/FD-7 因并行会话在途改 PostService 曾以 `git worktree + staged patch` 隔离验证；并行会话（P1-3/P1-4）落地后又在 HEAD（a2242cc）就地全量复验，双端全绿。
- git diff --check 说明：对 CRLF 索引态文件（BlogApiIntegrationTest.java、SecurityConfiguration.java）的 CR 报行尾空白系已知假阳性，新增行与文件既有行尾一致（git ls-files --eol 复核 w/crlf 均匀）。
- **未验证项**：FD-1 nginx（需用户 VPS 部署 + `nginx -t`）；44px 触达区/亮暗主题截图/375px 真机/读屏/reduced-motion 实机观感（jsdom 无布局引擎，依计划列人工验收清单）。

## 三、契约变更 / 缺口

1. **`POST /api/v1/auth/login` 响应变更（非破坏，字段追加）**：`LoginResponse` 新增 `role`（单数 String："ADMIN"|"PARTNER"）与 `displayName`。前端 authStore 尚未消费（FD-8，第二批）。旧前端不受影响。
2. **JWT claims 追加**：`uid`（账号 id）、`name`（展示名）；`roles` 不再恒为 ["ADMIN"]。
3. **新配置项**：`app.admin.display-name`、`app.partner.{username,password,display-name}`（.env.example 已示例，均可选）。partner 口令 ≥12 强制、不得与站长口令相同、占位符拒绝；**只建不改**——改 .env 口令不会更新已存在账号（既有语义，勿误解为 bug）。
4. **授权面新增**：`/api/v1/kitchen/**` → hasAnyRole(ADMIN, PARTNER)，当前无控制器（命中 404），FD-10 落地。
5. **前端契约**：`Dish` 类型新增必填 `favoriteCount`；`content.ts` 新增 `favoriteDish`/`fetchDishFavorites`。计数回显只信写端点响应（GET 详情带 max-age=300 公共缓存，禁止回写）。
6. 与 L-16（角色化路由）的耦合点：authStore 单一事实源不得分叉——FD-8 将加 `role?` 可选字段并 fail-closed，L-16 会话请以本 checkpoint 契约为准。

## 四、新发现（只记录不实施）

1. **V18 被 pg_trgm 抢号**：本批规划 kitchen 三表用 V18，并行会话同刻落盘 `V18__add_trgm_indexes`（1ec23c7），对方已修正台账（e92a43c）——**FD-10 执行时 kitchen 三表用 V19**，写 SQL 前仍须 `ls db/migration/` 复核。
2. `GET /api/v1/dishes/favorites` 仍被 WebConfiguration 拦截器加 `Cache-Control: max-age=300`——收藏后刷新最长 5 分钟看不到最新榜单。FD-11（第二批）缓存分流解决；本批前端已按"只信写端点响应"规避了按钮计数的滞后，榜单区块仍受影响。
3. 集成测试 @Order 号段台账：35–39 已用（FD-7），**40–44 预留 FD 后续**，45–60 预留 kitchen/打卡（FD-10/15）。
4. `sweet-sour-pork` 菜品图片仍指向 wikimedia 外链（V7 漏改），拓展时可顺手修（后端调研发现，未列编号）。
5. Bash 跨 worktree 操作时 `git diff --cached` 必须在主仓库 cwd 执行（worktree 各有独立 index），本批曾因此产生一次空补丁与一次暂存丢失（已恢复，无损失）。

## 五、待用户执行

1. **U-1/U-3**：在 `backend/.env.properties` 写入 `APP_PARTNER_USERNAME` / `APP_PARTNER_PASSWORD`（≥12 位，建议中文短语）/ `APP_PARTNER_DISPLAY_NAME`，及 `APP_ADMIN_DISPLAY_NAME`；重启后端后伴侣账号自动创建。我未接触任何明文口令。
2. **U-2**：本机 `APP_ADMIN_PASSWORD` 仅 7 位（启动会 WARN 不阻断）；生产建议换 ≥16 位。
3. **FD-1 部署**：`deploy/hxnf.top.nginx` 变更需在 VPS `nginx -t` 后 reload；若线上配有 proxy_cache，后续 FD-11 上线时需手工清 `/api/v1/dishes/favorites` 副本。
4. push 与部署照例由用户执行（本批 8 个提交在 codex/blogdemo）。

## 六、下一步

- **第二批「她进来了」（FD-8 ~ FD-14）**：前端角色感知（authStore role fail-closed）→ /login 通用登录页 + remember 24h + sessions_valid_from 校验 → kitchen 数据层与菜单 API（**V19**，OPTIMISTIC_FORCE_INCREMENT、PUT 按 item.id diff、append 无 version）→ 缓存头分流（FD-11）→ api/kitchen.ts + foodStore → 今日菜单 UI（信笺餐牌、到达动画、可见性轮询、noindex/robots 收口）→ 匿名意图接续。
- 第三批「有了回忆」（FD-15 ~ FD-19）：打卡 API 与时光机、一键打卡、榜单主口径换"我们做过 N 次"。
- 集成测试新增失败登录用例时记得 `rateLimiter.reset(); attemptTracker.reset();`；新增收藏用例排 @Order(11) 之后。
