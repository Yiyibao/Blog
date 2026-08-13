# M11 厨房闭环与 PWA checkpoint（2026-08-13）

## 状态

M11 已完成并通过专项与全量验收，尚未部署；Production GA 继续关闭。

## 已交付

- V62 新增按 owner/周起始日唯一的 `shopping_lists` 与 `shopping_list_items`；清单条目保存来源菜谱、原始数量和生成时快照，不依赖菜谱外键。
- 周菜单生成清单：食材名和单位规范化；同名同单位合并并相加可解析数量；不同单位不换算、不猜测，保留独立条目。
- 后端支持版本号/更新时间、勾选、分类、手工项、备注、清理已勾选、重新生成；更新使用乐观锁，并支持 `Idempotency-Key` 重试。
- 前端周计划接入持久化清单，提供分类/来源/原始数量、手工项、备注、打印/TXT 导出、离线快照、最多 50 条有界队列、重连回放和版本冲突差异提示。
- 菜谱删除不影响已生成清单快照；常备项仅提供“食用油/盐/黑胡椒”静态手工建议，不自动修改菜单、库存或购物清单。
- Service Worker 运行时缓存仍只匹配公开 GET 内容 API；auth/admin/notes/kitchen/AI 响应不进入缓存；登出清理厨房离线队列、快照和私有 cache 名称。
- AI 菜谱提取、归一化与购物建议仍沿用既有 preview/proposal → 人工确认边界，本批次没有 AI 直写菜谱或购物清单。

## 已验证

- 后端 `mvn -q clean test`：817 tests，0 failures/errors；真实 PostgreSQL `ShoppingListIntegrationTest` 通过，覆盖 owner 隔离、幂等、乐观锁、删除菜谱后的快照保留。
- 前端 `npm test -- --run`：75 files / 835 tests 全部通过；覆盖率 Statements 61.98%、Branches 55.45%、Functions 51.66%、Lines 64.68%。
- `npm run lint`、`format:check`、`test:typecheck`、`npm run build`、`npm run test:e2e:offline` 均通过；生产构建 49 个 JS / 1,499,749 bytes，离线/弱网 E2E 5/5。
- 隔离 PostgreSQL + Spring Boot + fake provider 在线 E2E：Chromium、Firefox、移动 Chromium 共 7/7；未发起真实外部模型请求。
- OpenAPI 重新生成并通过契约审计，V62 migration manifest 与 `git diff --check` 通过；PWA runtime cache 仅保留公开 GET 内容和图片缓存规则。

## 待完成

- 本批次全量门禁与在线/离线 E2E 已完成；待执行最终 commit/push 后进入 M12。
- M12 年度恢复/灾备/可访问性/性能收口完成前不部署生产。
