# Checkpoint · 美食专项第二批「她进来了」（FD-8 ~ FD-14 + FD-25）· 2026-07-27

> 用户指令：批次间不停顿自动推进；FD-25 自第四批提前并入本批（用户确认）；需用户执行的项跳过留档。

## 一、已完成项（编号 + 提交）

| 条目 | 提交 | 内容摘要 |
| --- | --- | --- |
| FD-8 前端角色感知 | 07f33a2 | authStore 加 role/displayName（fail-closed），旧会话启动清理；requiresAdmin 拆 requiresAuth+requiresRole；PARTNER 访问 /admin 重定向 /recipes |
| FD-9 登录页与会话 | b4dff28 | /login 美食皮肤 + useLoginForm 提取；remember→PT24H+localStorage；SessionValidityFilter（sessions_valid_from 止损阀，秒级截断防同秒误杀）；守卫统一 /login?next= |
| FD-25 自助改密 | dd91650 | PUT /api/v1/auth/password（验旧+强度+推进 sessions_valid_from 踢全设备）；ChangePasswordForm + /account 页；400 走独立 advice 防前端 401 拦截器误清会话 |
| FD-10 菜单 API | 7056d2c | **V19** 三表迁移；append 可交换无版本（ON CONFLICT 首创竞态）；PUT FORCE_INCREMENT+按 id diff 保署名；限流键 uid；集成 @Order 46-51 含真并发 |
| FD-11 缓存收口 | ca194b7 | /api/v1/kitchen/** 全方法 no-store；@Order(52) 三重断言（**该测试经由并行会话 bc949de 的批量提交入库**，见"新发现"） |
| FD-12 kitchen API 层 | （FD-12 提交） | api/kitchen.ts 第三实例（401 清会话不导航、classifyError 八类、Retry-After）；foodStore（todayISO sv-SE、竞态守卫、乐观 append、409 自动拉新、可见性轮询、arrivals 检测） |
| FD-13 今日菜单 UI | （FD-13 提交） | TodayMenuCard 信笺餐牌 + 封蜡"膳" + 空态餐盘 + 到达动画；TodayMenuBoard 协作编辑；英雄区登录换卡、?view=menu&date= 进 URL；robots Disallow 收口 |
| FD-14 意图接续 | （本批末提交） | 匿名邀请入口 → /login?next=…&intent=addDish → 回来自动进编辑态 + 欢迎语 + intent 消费 |

## 二、验证结果（真实数字）

- 后端合流态最近实测 **316/316**（FD-11 后；此后并行会话又有多笔提交，各自带隔离验证）。本专项各条目均以 `git worktree(基点)+staged patch` 隔离验证后提交，逐条数字见各提交信息。
- 前端 **287/287** + typecheck + build 全绿（基线 187 → 一批 236 → 二批 287）。
- 集成测试新增：@Order 43/44（remember TTL、sessions_valid_from 踢下线）、45（改密全循环）、46-51（菜单协作/真并发/权限/校验/限流）、52（缓存头三重断言）。
- **未验证项**：44px 触达/亮暗主题/375px 真机/读屏/reduced-motion 实机观感；两台设备真实协作（A 加菜 B 30 秒见到达动画）——人工验收清单，见第一批 checkpoint。

## 三、契约变更 / 缺口

1. `POST /auth/login`：请求可选 `remember`；`remember-ttl` 配置 `APP_JWT_REMEMBER_TTL`（默认 PT24H）。
2. 新端点：`PUT /auth/password`（任意角色，5 次/10 分钟/用户名限流，204；错误 400 信封）。
3. kitchen 全套端点（M1-M5 + history）见 FD-10 提交信息与 KitchenMenuController；全部 no-store；写限流 `kitchen:{uid}` 30/分。
4. 前端路由新增：`/login`、`/account`（requiresAuth）；美食页 `?view=menu&date=&intent=` 三个 query 语义。
5. robots.txt 新增 Disallow：/login、/account、/*?*view=menu。
6. **JwtService 构造签名变更**：(encoder, ttl, rememberTtl)——并行会话的 JwtServiceRejectionTest 已适配。
7. 本期菜单项不带份数（NF-12/base_servings 未落地，规划 V20）。

## 四、新发现（只记录不实施）

1. **@Order 号段台账失效**：第一批预留 40-44 给 FD，但并行会话已用 40-42；此后新增用例一律以文件实际为准，不再预留号段。
2. **提交归属混线一例**：FD-11 的 @Order(52) 测试经并行会话 bc949de 的整文件提交入库（暂存手术后恢复的工作区副本被其批量收走）。内容完整无损，归属混淆仅此一处。
3. **authStore 模块级 memorySession 在测试间泄漏**：sessionStorage.clear() 清不掉，匿名场景测试必须显式 clearSession()（TodayMenu.test 有注释示范）。
4. **本地 .env.properties 渗入测试上下文**：APP_ADMIN_DISPLAY_NAME 会进集成测试（spring.config.import），已在 DynamicPropertySource 固定 app.admin.display-name="测试站长" 隔离。
5. `@RestControllerAdvice` 无序时按 bean 名序匹配——common.GlobalExceptionHandler 的 Exception 兜底会先于 K 字头 advice 吞掉专用异常；kitchen/auth 两个 advice 已加 @Order(0)。后续新增包内 advice 需同样处理。
6. kitchen append 无单菜单条数上限（PUT 限 30），依靠 30/分限流兜底；如需硬上限列后续条目。
7. usePageMeta 未支持 robots meta（noindex 只靠 robots.txt）；如需页面级 noindex 需扩展 usePageMeta。

## 五、待用户执行

1. 沿用第一批：nginx 部署（FD-1）、生产口令轮换（建议部署第二批前完成，见第一批 checkpoint 第五节）。
2. 部署第二批后生产库将执行 V17/V18/V19 三个迁移（V18 为并行会话 pg_trgm）。
3. 两台设备真实协作验收（A 加菜，B 30 秒内看到到达动画与署名）。

## 六、下一步

- **第三批「有了回忆」（FD-15 ~ FD-19）**：打卡 API（meal_logs 实体 + check-in 一键打卡 + dish-stats 聚合，集成 @Order 以文件实际为准）→ N+1 防线（接口投影，ListQueryBatchingTest 追加）→ FoodTimeline 时光机 UI → 一键打卡入口（≤2 次交互）→ 榜单主口径换"我们做过 N 次"。
- 第四批余项不变：FD-20/21/22/23/24（NF-10/NF-12 等在册条目仍归 v5 主线）。
