# 管理员与 PARTNER 宠物助手——验收问题修复提示词

你要在仓库 `D:\Office\Study\code\BlogDemo` 中修复 Codex 宠物 AI 助手的验收问题。现有实现的总体架构、权限同权、宠物图集和大部分测试已经正确，请做最小增量修复，不要重写功能，不要重新生成宠物图片，也不要创建 git commit。

## 开始前

先阅读：

- `D:\Office\Study\code\BlogDemo\docs\admin-pet-assistant-implementation-plan.md`
- `D:\Office\Study\code\BlogDemo\frontend\src\components\admin-pet\AdminPetAssistant.vue`
- `D:\Office\Study\code\BlogDemo\frontend\src\components\admin-pet\PetSprite.vue`
- `D:\Office\Study\code\BlogDemo\frontend\src\components\AdminAiChat.vue`
- 对应的 `AdminPetAssistant.test.ts`、`AppPetMount.test.ts`、`PetSprite.test.ts` 和 `AdminAiChat.test.ts`

第一步必须运行 `git status --short` 和相关 `git diff`。当前工作树包含用户及其他 Agent 的未提交修改，必须在现状上增量编辑。禁止使用 `git reset`、`git checkout --`、覆盖用户修改、删除未知文件或进行无关格式化。

## 必须修复的问题

### 1. P1：移动端聊天面板横向溢出

文件：`frontend/src/components/admin-pet/AdminPetAssistant.vue`，移动端 `.pet-chat-panel` 样式附近。

当前面板是相对右下角窄 `.pet-assistant` 容器的绝对定位元素，却在移动端同时使用：

```css
left: 0;
right: 0;
width: 100vw;
```

这会使面板从宠物容器附近开始向右延伸，大部分内容落到视口之外。

修复要求：

- 移动端面板必须以 viewport 为定位基准，推荐使用 `position: fixed`。
- 在 390px、360px 宽视口下，面板的 `left >= 0`、`right <= viewportWidth`，没有水平滚动条。
- 保留底部 safe area，并确保面板与宠物、输入框均可见。
- 桌面端 380px 浮层行为不能回归。
- 不要只修改 `max-width` 掩盖定位基准错误。

必须新增能够验证实际几何边界的测试。若 jsdom 无法可靠计算布局，增加浏览器级或现有项目可运行的布局检查，并在最终报告中说明验证方式。

### 2. P2：恢复紧凑聊天的 provider/model 切换

文件：`frontend/src/components/admin-pet/AdminPetAssistant.vue`。

当前代码会调用 `fetchAiProviders()` 并自动选择默认 provider/model，但面板没有渲染选择控件。旧 `AdminAiSidebar` 删除后，紧凑聊天失去了 provider/model 切换能力。

修复要求：

- 在宠物聊天面板头部或输入区域上方提供 provider 和 model 原生 `<select>`。
- provider 列表只显示 `enabled` 项。
- 默认选择 `isDefault` provider，否则选择第一项。
- 切换 provider 后，model 自动切换到该 provider 的 `defaultModel`；若默认模型缺失，则选模型列表第一项。
- model 列表需包含 `defaultModel`，即使它未出现在 `models` 数组中。
- provider 只有一个时可选择隐藏 provider 控件，但 model 有多个时必须可切换；若为可见控件，必须有明确中文 `aria-label`。
- 选择结果通过既有 `provider-id` 和 `model` props 传给唯一的 compact `AdminAiChat`，禁止复制聊天请求逻辑。
- provider 加载失败时保持现有后端默认 provider 兜底，面板仍可聊天。
- `/admin/ai` 仍不得挂载第二个 `AdminAiChat`。

必须补充测试：

- 多 provider 时可切换，默认 provider 正确。
- 切换 provider 后模型跟随其默认值。
- 同 provider 下可切换模型，下一次消息使用新选择。
- defaultModel 不在 models 中时仍出现在选项中。
- 连续打开/关闭面板不会重复加载 provider。

### 3. P2：进入 `/admin/ai` 后清理残留 panelOpen

文件：`frontend/src/components/admin-pet/AdminPetAssistant.vue`，路由 watch 附近。

复现路径：

1. 在首页或其他页面打开宠物紧凑聊天。
2. 在面板仍为打开状态时导航到 `/admin/ai`。
3. 模板会隐藏 compact chat，但 `panelOpen` 仍是 `true`。
4. 第一次点击宠物只会执行 `closePanel()`，不会聚焦完整页输入框。

修复要求：

- 进入 `/admin/ai` 时立即关闭紧凑面板并清理对应状态。
- 进入后第一次点击宠物就聚焦完整页面的 `[data-testid="ai-chat-input"]`。
- 导航过程中若 compact chat 正在流式生成，其卸载必须触发既有 AbortController 中止逻辑。
- 从 `/admin/ai` 离开后，宠物保持正常可用，但不要自动弹开面板。

必须新增按上述完整路由转换路径编写的测试，不能只测试“组件直接挂载在 `/admin/ai`”的情况。

### 4. P3：清理移动断点 matchMedia 监听器

文件：`frontend/src/components/admin-pet/AdminPetAssistant.vue`，`onMounted`/`onBeforeUnmount` 附近。

当前 `matchMedia('(max-width: 720px)')` 的 `change` 回调定义在 `onMounted` 局部作用域，卸载时无法移除。

修复要求：

- 将 MediaQueryList 和回调保存在组件作用域。
- `onBeforeUnmount` 中调用同一个回调引用执行 `removeEventListener('change', callback)`。
- 兼容不支持 `addEventListener` 的测试/旧环境时，不得抛错。
- 增加单元测试，明确断言注册和卸载使用的是同一个回调引用。

## 不得破坏的已通过行为

- `ADMIN` 与 `PARTNER` capability 完全一致，但角色值分别保持 `ADMIN`、`PARTNER`。
- 两种角色在公开页和后台页均显示宠物，游客、过期会话和登录页不显示。
- 游客不挂载宠物异步组件、不请求 `/pets/xinn/spritesheet.webp`，PWA 不预缓存该图集。
- `/admin/ai` 始终只有完整页的一个 `AdminAiChat` 实例。
- 宠物隐藏使用 sessionStorage，快捷键 `Ctrl/Cmd+Shift+A` 可以恢复，logout 后清除隐藏状态。
- SSE start/delta/complete/error/abort 动画状态映射保持正确。
- reduced-motion、页面可见性暂停、pointer gaze 和逐帧时长不能回归。
- 图集文件必须保持原样：`frontend/public/pets/xinn/spritesheet.webp`，禁止重新压缩、覆盖或生成。
- 现有消息键 `yubai-admin-ai-messages`、Authorization header 和后端 AI 接口保持不变。

## 验证要求

完成后必须运行：

```powershell
cd D:\Office\Study\code\BlogDemo\frontend
npm.cmd test
npm.cmd run test:typecheck
npm.cmd run build

cd D:\Office\Study\code\BlogDemo\backend
& 'C:\Program Files\apache-maven-3.9.16\bin\mvn.cmd' test

cd D:\Office\Study\code\BlogDemo
git diff --check
git status --short
git diff --stat
```

还必须进行浏览器人工检查：

- 390px、360px、768px、1440px 视口。
- ADMIN 与 PARTNER 各检查一次。
- 打开/关闭/隐藏/快捷键恢复宠物。
- 切换 provider 和 model 后发送消息，确认实际请求使用所选值。
- 从打开的紧凑聊天导航到 `/admin/ai`，首次点击宠物立即聚焦完整页输入框。
- 浅色、深色和 reduced-motion。
- 游客网络资源中没有宠物图集。

## 最终回复格式

完成后回复必须包含：

1. 四个问题分别如何修复。
2. 修改和新增文件列表。
3. 新增测试名称及其覆盖场景。
4. 每条验证命令的真实结果和测试数量。
5. 浏览器检查的视口、角色和结果。
6. `git diff --check` 结果。
7. 已知剩余问题；没有则写“无已知阻断问题”。

不要提交、不要部署。完成后停下，等待验收方复验。
