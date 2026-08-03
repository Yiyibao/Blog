# 交给实现 Agent 的完整提示词

你要在仓库 `D:\Office\Study\code\BlogDemo` 中实现“ADMIN 与 PARTNER 专属 Codex 宠物 AI 助手”，并把 `PARTNER` 的功能权限调整为与 `ADMIN` 完全一致。请直接完成代码、测试和验证，不要只给方案，也不要创建提交。

开始前必须完整阅读：

- `D:\Office\Study\code\BlogDemo\docs\admin-pet-assistant-implementation-plan.md`
- `C:\Users\Hfff\.codex\skills\hatch-pet\SKILL.md`
- `C:\Users\Hfff\.codex\skills\hatch-pet\references\animation-rows.md`

宠物素材已经制作完成，不要重新生成图片：

- `C:\Users\Hfff\.codex\pets\xinn\pet.json`
- `C:\Users\Hfff\.codex\pets\xinn\spritesheet.webp`

核心需求：

1. 将 `xinn/spritesheet.webp` 复制到前端可部署的静态资源目录，按 Codex v2 的 8×11 图集、192×208 单元格实现动画播放器。
2. `ADMIN` 或 `PARTNER` 有效登录后在网页右下角显示宠物。游客和过期会话不得显示、挂载聊天组件或主动请求图集。
3. `ADMIN` 与 `PARTNER` 登录后，公开页面和后台页面都显示；登录页不显示。
4. 点击宠物直接打开紧凑聊天面板，复用现有 `AdminAiChat.vue`、provider/model 选择、`streamAiChat` SSE、停止生成和 `yubai-admin-ai-messages` 会话存储，不新建后端接口。
5. `/admin/ai` 已有完整聊天页：宠物仍显示，但绝对不能再挂载第二个 `AdminAiChat`。点击时聚焦完整页输入框或显示清晰提示。
6. 面板的关闭只收起面板，宠物保留；另提供“隐藏宠物”操作，隐藏仅存 sessionStorage。`Ctrl/Cmd+Shift+A` 可恢复并打开，logout 后清理隐藏状态。
7. 动画状态：首次显示 waving；默认 idle；打开聊天等待时 waiting；SSE 生成时 running；成功后 review 一轮；失败后 failed 一轮；主动取消回 waiting/idle。空闲时可按方案实现 16 方向 pointer gaze；reduced-motion 必须静态退化。
8. 完整处理卸载、logout、路由变化、页面 hidden、timer、requestAnimationFrame、AbortController 和键盘监听器清理。
9. 桌面与移动端均可用，不能与现有“回到顶部”按钮重叠；支持浅/深色、safe area、键盘焦点、Escape 和 aria 标签。
10. 将 `PARTNER` 的 capability 集合设置为与 `ADMIN` 完全一致，包括但不限于 `ACCOUNT_ACCESS`、`CONTENT_MANAGE`、`AI_MANAGE`、`AI_USAGE`、`KITCHEN_ACCESS`、`KITCHEN_DELETE_ANY`、`DASHBOARD_VIEW`、`ATTACHMENTS_MANAGE`、`LIBRARY_MANAGE`、`METRICS_VIEW`。角色值仍须保留为 `PARTNER`，不能把它篡改成 `ADMIN`。
11. 同步修改前端 auth store、router guard、导航显隐和所有 `!auth.isAdmin` 的角色级阻断：新增 `isStaff`/`hasAdminAccess` 一类语义明确的总开关，允许 `ADMIN` 与 `PARTNER` 访问相同后台功能；`isAdmin` 继续严格表示角色为 `ADMIN`。
12. 保持后端 Authorization header 机制不变，不将 token/聊天内容写入 localStorage。

工程约束：

- 当前工作树存在用户未提交改动。第一步先运行 `git status --short` 和相关 `git diff`，随后只做增量编辑。严禁 `git reset`、`git checkout --`、覆盖用户修改、清理未知文件或大范围无关格式化。
- 尤其注意 `frontend/src/App.vue`、`frontend/src/components/AdminAiChat.vue`、`frontend/src/router/index.ts` 及现有测试已有改动，必须合并保留。
- 权限同权不能只改 `RolePermissions`：必须检查并修复 router、App、导航、组件中所有硬编码 `isAdmin`/`PARTNER` 分支，确保能力和实际可达页面一致。
- 使用现有 Vue/TypeScript/CSS，不引入新的运行时依赖或动画库。
- 不复制聊天请求逻辑到宠物组件；通过 `AdminAiChat` 的类型化 emits 报告流状态。
- 不能同时保留 App 中旧 `AdminAiSidebar` 和新宠物聊天宿主导致双实例。旧组件可以删除，或保留为无重复挂载的兼容壳。
- 图集行号、帧数和时长必须以 `animation-rows.md` 为准，不把透明未使用单元格当动画帧。
- 使用 `apply_patch` 编辑文本文件；复制二进制图集可用正常文件复制命令。
- 不要提交 git commit。

建议新增：

- `frontend/public/pets/xinn/spritesheet.webp`
- `frontend/src/components/admin-pet/AdminPetAssistant.vue`
- `frontend/src/components/admin-pet/PetSprite.vue`
- `frontend/src/components/admin-pet/petAnimations.ts`
- `frontend/src/test/AdminPetAssistant.test.ts`
- `frontend/src/test/PetSprite.test.ts`

必须执行并报告：

```powershell
cd D:\Office\Study\code\BlogDemo\frontend
npm.cmd test
npm.cmd run test:typecheck
npm.cmd run build
cd ..\backend
& 'C:\Program Files\apache-maven-3.9.16\bin\mvn.cmd' test
cd ..
git diff --check
git status --short
git diff --stat
```

实现完成后不要只说“已完成”。最终回复必须包含：

- 实际架构与关键交互说明
- 修改/新增/删除文件列表
- 每条验证命令的结果
- 游客、PARTNER、ADMIN、两角色 capability 等价、后台路由等价、`/admin/ai` 单实例、隐藏/恢复、reduced-motion 的测试证据
- 是否完成真实浏览器人工检查；若环境无法做，明确列出仍需由验收方执行的步骤
- 未解决问题或风险；没有则写“无已知阻断问题”

完成后停下，等待验收，不要自行提交或部署。
