# 管理员与 PARTNER 宠物助手——第二轮最终修复提示词

你要在仓库 `D:\Office\Study\code\BlogDemo` 中修复宠物 AI 助手第二轮验收发现的问题。请做最小增量修改，不要重写已经通过的功能，不要重新生成、压缩或覆盖宠物图集，不要创建 commit，不要部署。

## 开始前

1. 阅读并遵守：
   - `docs/admin-pet-assistant-implementation-plan.md`
   - `docs/prompts/admin-pet-assistant-agent.md`
   - `docs/prompts/admin-pet-assistant-fix-agent.md`
2. 检查 `git status --short` 和相关 diff。工作区存在用户及其他 Agent 的未提交改动，必须保留，不得清理、回退、覆盖或格式化无关文件。
3. 本轮只处理下列三个问题；若发现必须扩大范围，先在最终报告中说明，不要擅自重构。

## 必须修复的问题

### 1. P1：宠物聊天在深色主题下仍显示大面积浅色块

当前 `frontend/src/components/AdminAiChat.vue` 的聊天主体仍硬编码以下颜色：

- `rgba(255, 255, 255, 0.6)`
- `#ffffff`
- `#faf8f5`
- `#20211e`

典型位置包括聊天容器、助手消息气泡、输入区、输入框、输入框聚焦态、模型选择器及其聚焦态。与此同时，`frontend/src/admin.css` 中 `--console-ink`、`--console-muted`、`--console-line` 只定义了浅色值，没有随根节点 `.dark` 正确切换。

修复要求：

1. 聊天区域必须使用项目已有主题 token，例如：
   - `--surface`
   - `--surface-solid`
   - `--ink`
   - `--muted`
   - `--line`
   - `--line-strong`
   - `--accent`
   - 必要时为 `--console-*` 提供明确的 `.dark` 值。
2. 禁止通过简单追加一块不完整的暗色 CSS 掩盖问题；浅色和深色都必须保持可读的文字、边框、输入框、气泡、错误提示和焦点状态。
3. 全屏 `/admin/ai` 和宠物紧凑聊天必须同时适配，不能只修其中一种形态。
4. 不得破坏管理员后台其他页面现有主题表现。
5. 增加自动化回归测试，至少验证：
   - 根节点没有 `.dark` 时聊天关键区域使用浅色主题计算结果；
   - 根节点有 `.dark` 时聊天容器、助手气泡、输入区、输入框不再计算为硬编码纯白背景；
   - 深色主题下文字与背景不是同色或低可读性组合；
   - compact 与 full-page 两种形态均覆盖。
6. 浏览器人工验收必须分别截图或记录浅色、深色状态下的关键计算样式与可见结果。

### 2. P2：指针视线测试依赖真实墙钟，完整套件中偶发失败

失败用例位于：

`frontend/src/test/AdminPetAssistant.test.ts`

当前用例调用 `vi.useRealTimers()`，然后通过：

```ts
await new Promise((resolve) => setTimeout(resolve, totalDuration('waving') + 60))
```

等待 waving 完成。完整并行套件或机器负载较高时，计时器回调可能晚于断言，实际出现过：预期 `look`、实际仍为 `waving`。

修复要求：

1. 将该测试改为确定性测试，优先全程使用 Vitest fake timers 和 `advanceTimersByTimeAsync`。
2. 不得通过把 `+60ms` 改成更大的任意等待值解决。
3. 指针事件、`requestAnimationFrame` 节流和动画完成必须由测试明确驱动；必要时稳定 mock `requestAnimationFrame`，并在用例结束后恢复。
4. 测试必须继续真实断言：
   - waving 完成后回到 idle；
   - 指针进入有效半径后进入 `look`；
   - 方向映射到预期 row/column；
   - 指针离开有效半径后回到 idle；
   - reduced-motion 不启用 gaze。
5. 禁止删除、跳过、降低断言或设置 retry 来隐藏不稳定性。
6. 至少连续运行三次完整前端测试，三次必须全部通过。

### 3. P2：`pet-panel-layout-check.mjs` 在 CDP 就绪后可能无限等待

文件：

`frontend/scripts/pet-panel-layout-check.mjs`

验收时该脚本连续两次只输出浏览器和 CDP 已就绪，随后分别在 90 秒和外层 240 秒超时，没有输出 PASS/FAIL。当前 `send()` 只有 pending 回调，没有单请求超时、WebSocket `close/error` 拒绝逻辑，也没有覆盖所有异常路径的统一清理。

修复要求：

1. 为每个 CDP `send()` 请求增加有界超时；超时信息必须包含 method，便于定位。
2. WebSocket `error` 或 `close` 时，拒绝所有 pending 请求并清理 pending map。
3. 使用 `try/finally` 统一清理：
   - WebSocket；
   - 本地 HTTP 静态服务；
   - 本脚本启动的 Edge/Chrome 子进程；
   - 临时 user-data-dir（只能删除本脚本创建且已验证位于系统临时目录下的精确目录）。
4. 浏览器 target 选择必须可靠：
   - 不要无条件选择 `/json/list` 中第一个 `type === 'page'`；
   - 应创建或锁定本脚本自己的 page target，并验证 target URL/ID；
   - target 消失时快速失败，不能无限挂起。
5. 静态服务必须正确支持 SPA 路由回退：不存在的非资源路径返回 `dist/client/index.html`，静态资源缺失仍返回 404。这样脚本才能加入 `/admin/ai` 的真实路由验证。
6. 将下列真实浏览器检查纳入脚本并输出逐项结果：
   - 390px、360px 下 ADMIN 面板左右边界位于 viewport 内且无水平滚动；
   - PARTNER 与 ADMIN 行为一致；
   - 游客不渲染宠物、不请求 `spritesheet.webp`；
   - 面板打开后导航到 `/admin/ai`，compact 实例销毁；
   - 在 `/admin/ai` 首次点击宠物后，完整页 `textarea[data-testid="ai-chat-input"]` 获得焦点；
   - 浅色和深色主题下聊天区域关键计算样式正确；
   - 任一检查失败时进程以非零码结束；全部通过时输出明确的 `ALL PASS`。
7. 若脚本无法获得真实后端会话，不得伪造“已通过真实聊天”。允许把需要后端登录的用例拆成明确的完整环境脚本，但静态布局脚本本身必须可靠结束，不能挂死。

## 不得破坏的已通过行为

以下行为已通过，必须保留：

- `ADMIN` 与 `PARTNER` 能力集合完全一致，但角色值和显示身份仍分别保持 `ADMIN`、`PARTNER`。
- 游客、过期会话、登录页不显示宠物；游客不挂载宠物异步组件，也不请求或预缓存图集。
- 宠物仅在管理角色有效登录后显示于右下角。
- 可手动打开/关闭聊天；隐藏状态使用 `sessionStorage`；快捷键可恢复；登出清理隐藏状态。
- `/admin/ai` 只存在完整页聊天实例，不得同时挂载 compact 聊天。
- 从打开面板导航到 `/admin/ai` 时关闭面板、销毁 compact 实例并中止其流式请求。
- `/admin/ai` 点击宠物只聚焦完整页输入框，不打开第二个面板。
- provider/model 选择、默认 provider、defaultModel 回退和请求参数透传保持正确。
- provider 加载失败时仍可使用后端默认配置聊天。
- SSE 开始、首增量、完成、失败、取消对应的宠物动画状态保持正确。
- matchMedia 的注册和卸载使用同一个回调引用。
- reduced-motion、键盘焦点、Escape、ARIA、safe area、回到顶部避让保持有效。
- `frontend/public/pets/xinn/spritesheet.webp` 必须逐字节保持不变；禁止重新生成、压缩或覆盖。

## 强制验证

### 前端

在 `frontend` 目录运行：

```powershell
npm.cmd run test:typecheck
npm.cmd run build
npm.cmd test
npm.cmd test
npm.cmd test
node scripts\pet-panel-layout-check.mjs
```

要求：

- 三次完整测试都必须全绿，不能只运行相关文件。
- 布局脚本必须在有界时间内结束并输出 `ALL PASS`。
- 不允许存在未处理 Promise rejection、残留 Node/Edge/Chrome 进程或临时 profile。

### 后端

在 `backend` 目录运行：

```powershell
& 'C:\Program Files\apache-maven-3.9.16\bin\mvn.cmd' test
```

要求：658 项或当前最新完整测试集全部通过，PARTNER/ADMIN 同权测试不得删减。

### 图集与仓库

```powershell
git diff --check
git status --short
```

同时验证：

- 图集仍为 WebP RGBA、1536×2288、8×11、Codex v2；
- 修复前后 `frontend/public/pets/xinn/spritesheet.webp` 的 SHA-256 不变；
- 不提交测试日志、临时截图、浏览器 profile、构建日志或其他临时文件；
- 已存在的 `frontend/vite-test.log`、`frontend/vite-test-err.log` 若不是本轮创建，不得擅自删除，只在报告中说明。

## 最终回复格式

完成后请按以下格式回复：

1. `修改文件`：逐个列出并说明用途。
2. `问题修复`：分别说明深色主题、确定性计时测试、CDP 脚本可靠性如何修复。
3. `测试结果`：列出每条命令、退出码、测试数量、三次前端完整测试结果和布局脚本结果。
4. `浏览器证据`：列出 viewport、角色、主题、路由、焦点及资源请求的实测结果。
5. `保留行为`：确认 ADMIN/PARTNER 同权、游客隐藏、单聊天实例、provider/model、SSE、资源不变。
6. `未完成/风险`：必须如实列出；不得用 jsdom 结果冒充完整浏览器或真实后端聊天验证。

不要声称“无已知阻断问题”，除非全部命令、三次完整测试和真实浏览器脚本均已通过。
