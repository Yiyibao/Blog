# M4 Checkpoint：公开访问与质量门禁

日期：2026-08-13
结果：完成
下一月允许启动：YES（M5；生产发布仍需年度路线图全部门禁通过）

## 本批次交付

- 建立显式路由可见性契约：公开页面使用 `meta.visibility = public`，未标记的未知/内部页面不会被访客绕过；系列、标签、归档、菜谱、分类、关于和 404 深链可公开访问。
- 新增分类索引与分类详情页，详情页使用后端分页 API，不再把离线种子数据伪装成在线结果；网络错误、空数据和不存在分类均有明确状态。
- 导航、页脚、移动菜单、页面 meta、静态预渲染路由和路由单测同步更新，避免可点击链接与 sitemap 漂移。
- 将 E2E 明确拆为 `offline-chromium` 与 `online-chromium`；在线测试必须显式提供 `E2E_BASE_URL`，没有真实后端时直接失败，不允许离线壳冒充 full-stack 通过。
- 提升前端覆盖率门槛至本次基线的保守下限，并在 JaCoCo bundle 级别设置 instruction 75%、branch 55% 检查。
- 修复首页离线/背景图不可用时的 WCAG AA 对比度问题；未降低 axe 断言。

## 修改范围

- 前端：分类页面/API、公开路由契约、导航与 meta、预渲染、Playwright 配置/在线契约、覆盖率阈值、测试。
- 后端/CI：JaCoCo 门禁、前端离线门禁、可选在线 E2E job、构建产物忽略规则。
- 文档：本 checkpoint。
- 数据库迁移：无。

## 验收证据

- `frontend`: `npm run test:coverage -- --reporter=dot` —— 71 个测试文件、822 个测试通过；statements 61.99%、branches 55.62%、functions 50.92%、lines 64.65%，均达到门槛。
- `frontend`: `npm run test:typecheck`、`npm run lint`、`npm run format:check`、`npm run api:types:check` —— 全部通过。
- `frontend`: `npm run build` —— 构建、5 条静态预渲染路由、2 条 noindex 路由、PWA precache 和构建预算全部通过。
- `frontend`: `npm run test:e2e:offline` —— 3/3 通过，包含键盘导航、axe 严重/关键问题门禁、搜索 Esc 关闭和移动 LCP/产物预算。
- `backend`: `mvn -q -DskipTests compile`、`mvn -q spotless:check` —— 通过。
- `backend`: 本机完整 Testcontainers 集成测试因 Docker Desktop Linux engine 不可用而未执行；CI 保留真实 PostgreSQL/Testcontainers 门禁，M5 将继续补齐迁移/备份相关验证。
- `git diff --check` —— 提交前执行。

## 可复现命令

```text
cd frontend
npm run test:coverage -- --reporter=dot
npm run test:typecheck
npm run lint
npm run format:check
npm run api:types:check
npm run build
npm run test:e2e:offline

cd ../backend
mvn -q -DskipTests compile
mvn -q spotless:check
```
