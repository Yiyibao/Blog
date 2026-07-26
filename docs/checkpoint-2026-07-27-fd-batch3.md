# Checkpoint · 美食专项第三批「有了回忆」（FD-15 ~ FD-19）· 2026-07-27

> 用户指令下的连续自动推进（第一批→第二批→第三批不停顿）。至此专项一、二、三批全部完成，第四批为可选延后项。

## 一、已完成项（编号 + 提交）

| 条目 | 内容摘要 |
| --- | --- |
| FD-15 打卡 API | MealLog 实体（V19 表）；POST/GET/DELETE /kitchen/meal-logs；POST /kitchen/menus/check-in（同日同名同餐次幂等）；GET /kitchen/dish-stats 聚合（标量投影 join slug）；限流 meallog:{uid}；单测 7 + 集成 @Order(53-55) 含 ON DELETE SET NULL 回归 |
| FD-16 N+1 防线 | ListQueryBatchingTest 追加时间线 ≤2 prepare 断言；实证并留档"共享 IT 库残留集成测试提交数据"的隔离手法 |
| FD-17 时光机 | FoodTimeline 胶片时间线：按日分组/餐次筛选进 URL/加载更早/删自己的/零数据邀请文案/菜名深链 |
| FD-18 一键打卡 | 菜单卡每道菜 44px ✓ + 抽屉头图行"今天吃了 ✓"；一次点击完成记录，toast+时光机就地刷新 |
| FD-19 榜单双口径 | 登录且有做菜数据：主口径"你们做过 N 次"（dish-stats）+ 副口径"大家点亮 M 次"；匿名/零做菜回退点亮榜 |

FD-17/18/19 因在 FoodSection 同一编排壳深度交织而合并为一个提交（例外留档；一条目一提交原则的其余部分全程遵守）。

## 二、验证结果（真实数字）

- 后端隔离 worktree 验证：FD-15 后 327/327、FD-16 后 328/328（此后并行会话继续推进，合流数字以其后续提交为准）。
- 前端 **296/296** + typecheck + build 全绿（专项净增测试：一批 +49、二批 +51、三批 +9 ≈ 109 例）。
- 未验证项（人工验收清单，沿用一批 checkpoint）：44px 触达、亮暗主题截图、375px 真机、reduced-motion、读屏、双设备真实协作、nginx 部署。

## 三、契约变更 / 缺口

1. 新端点五个（meal-logs CRUD/check-in/dish-stats），全部 kitchen 前缀（登录双角色、no-store、uid 限流）。
2. kitchen.ts 新增 MealLog/MealLogDraft/DishCookStat 类型与五个函数。
3. 时光机餐次筛选为客户端过滤——服务端 meal-logs 暂无 slot 参数（数据量小可接受；列为潜在后续）。
4. 打卡 rating/note 在 API 与时间线展示已支持，但一键打卡入口未内联收集（计划原案"toast 内联追加评分"未实现，需自定义 toast 组件；如需要列后续条目）。
5. FD-19 榜单行依赖收藏榜条目提供名字/图——做过但从未被点亮且不在收藏榜 Top5 的菜不会上榜（v1 取舍，留档）。

## 四、新发现（只记录不实施）

1. 共享 IT 库（yubai_blog_it）会累积集成测试提交的业务数据；@DataJpaTest 断言必须限定自种数据（FD-16 有注释示范）。
2. L-15（并行会话）重写了 useLoginForm 为人机验证弹窗模式，并妥善适配了本专项的 LoginPage/adminSession/LoginPage.test——双线在登录面的交接干净完成。
3. kitchen 一键打卡直发 POST /meal-logs 无幂等（仅 check-in 端点有）；用户主动重复点击会记两笔，属预期行为。

## 五、待用户执行（汇总不变）

nginx 部署（FD-1）、生产口令轮换（部署前）、部署后 V17/V18/V19 迁移自动执行、双设备协作验收、人工验收清单。

## 六、下一步

- 专项主体完成。第四批延后项（FD-20 滑动窗口、FD-21 全局映射、FD-22 抽卡甜点、NF-10/NF-12）归还 v5 主线排期。
- 潜在小条目：meal-logs 服务端 slot 筛选、打卡评分内联收集、榜单 cook 行独立取名/图（摆脱收藏榜依赖）。
