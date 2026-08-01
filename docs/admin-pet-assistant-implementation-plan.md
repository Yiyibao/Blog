# 管理员与 PARTNER 宠物助手实现方案

## 1. 目标与边界

把本机 Codex 自定义宠物 `xinn` 接入博客网页，形成固定在右下角的已登录管理角色专属 AI 宠物助手。`ADMIN` 与 `PARTNER` 保留不同角色身份，但拥有完全一致的功能权限。

目标行为：

- `ADMIN` 或 `PARTNER` 有效登录后渲染；游客、过期会话不显示、不挂载聊天组件，也不请求宠物图集。
- `ADMIN` 与 `PARTNER` 登录后，在公开页面和后台页面均显示宠物；登录页不显示。
- 单击宠物打开/收起紧凑聊天面板，直接复用现有 AI provider、模型选择、SSE 流式聊天、停止生成和会话记录能力。
- `/admin/ai` 已有完整聊天页：该路由仍显示宠物，但单击宠物只聚焦/提示当前完整聊天页，不创建第二个 `AdminAiChat` 实例。
- 面板关闭后宠物保留。宠物本身提供“隐藏本次登录会话”操作；隐藏后可用 `Ctrl/Cmd + Shift + A` 恢复并打开。
- 退出登录后清除宠物隐藏状态，下一次有效登录默认重新显示。
- 宠物根据交互状态播放 Codex v2 图集动画，并在系统启用“减少动态效果”时退化为静态首帧。

非目标：

- 不重新生成或修改宠物形象。
- 不新增 AI 后端接口，不改变鉴权模型、provider 配置或聊天限额。
- 不向游客开放 AI 能力；静态图片本身不是机密，但游客页面不得渲染组件或主动加载图片。
- 首版不做宠物自由拖拽、跨设备偏好同步、语音输入或服务端保存聊天记录。

## 2. 已确认的项目事实

- 前端为 Vue 3 + TypeScript + Pinia + Vue Router + Vitest。
- 当前 `useAuthStore().isAdmin` 只识别 `ADMIN`，不能继续作为“有管理权限角色”的总开关。应新增语义清晰的计算属性（建议 `isStaff` 或 `hasAdminAccess`），其条件为有效登录且角色是 `ADMIN` 或 `PARTNER`；`isAdmin` 继续保持严格角色语义，避免破坏审计和既有调用。
- 现有聊天核心位于 `frontend/src/components/AdminAiChat.vue`，已支持 provider/model 选择、SSE、AbortController、20 条会话窗口和 sessionStorage。
- 现有 `frontend/src/components/AdminAiSidebar.vue` 仅在 `/admin` 路由工作，可作为行为迁移参考，但新需求需要全站管理角色可见。
- 当前 `RolePermissions` 中 `PARTNER` 只有 `ACCOUNT_ACCESS` 和 `KITCHEN_ACCESS`，且前端路由还有 `!auth.isAdmin` 的角色级阻断。实现必须让 `PARTNER` 获得与 `ADMIN` 完全相同的 capability 集合，并将后台放行逻辑改为 capability 驱动或 `isStaff` 驱动。
- 后端 `/api/v1/admin/ai/**` 已要求 `AI_USAGE` 权限；同权后 `PARTNER` 也必须拥有 `AI_USAGE` 和 `AI_MANAGE`，可使用完整聊天页并管理 provider。
- 宠物源文件：
  - `C:\Users\Hfff\.codex\pets\xinn\pet.json`
  - `C:\Users\Hfff\.codex\pets\xinn\spritesheet.webp`
- 图集遵循 Codex v2：8 列 × 11 行，每格 192 × 208，最终图集应为 1536 × 2288。
- 当前工作树已有用户未提交修改，尤其包括 `App.vue`、`AdminAiChat.vue` 和对应测试；实现时必须在现状上做增量修改，禁止覆盖、回退或格式化无关代码。

## 3. 推荐架构

### 3.1 文件布局

建议新增：

```text
frontend/public/pets/xinn/spritesheet.webp
frontend/src/components/admin-pet/AdminPetAssistant.vue
frontend/src/components/admin-pet/PetSprite.vue
frontend/src/components/admin-pet/petAnimations.ts
frontend/src/test/AdminPetAssistant.test.ts
frontend/src/test/PetSprite.test.ts
```

建议修改：

```text
frontend/src/App.vue
frontend/src/components/AdminAiChat.vue
frontend/src/test/AdminAiChat.test.ts
frontend/src/test/AdminAiSidebarDock.test.ts（若旧侧栏被替换或改为兼容壳）
```

`AdminAiSidebar.vue` 有两种可接受处理方式：

1. 推荐：由 `AdminPetAssistant.vue` 完整替代，移除 App 中旧侧栏挂载；确认无引用后删除旧组件及过时测试。
2. 保守：保留旧文件作为薄兼容壳，但 App 只挂载新宠物助手，绝不同时挂载两个聊天实例。

### 3.2 组件职责

`PetSprite.vue`：

- 只负责图集裁切、逐帧定时、状态切换和 reduced-motion。
- 接收 `state`、`size`、可选 `lookDirection`；不读取 auth、不发网络请求。
- 使用 `<img>` + 固定裁切窗口显示原图局部，避免依赖容易算错的百分比 `background-position`。
- 原始逻辑尺寸固定为 192 × 208；按 CSS 变量统一缩放整张 1536 × 2288 图集。
- 图片使用固定路径 `/pets/xinn/spritesheet.webp`，提供明确 `width`/`height`，避免布局抖动；宠物为交互按钮时图像本身 `alt=""`，可访问名称放在按钮上。

`petAnimations.ts`：

- 集中定义行号、有效帧数和逐帧时长，不在组件中散落魔法数字。
- 标准行映射：
  - `idle`: row 0, cols 0-5, `[280,110,110,140,140,320]`
  - `runningRight`: row 1, cols 0-7, 前 7 帧 120ms，末帧 220ms
  - `runningLeft`: row 2，同上
  - `waving`: row 3, cols 0-3, `[140,140,140,280]`
  - `jumping`: row 4, cols 0-4, `[140,140,140,140,280]`
  - `failed`: row 5, cols 0-7, 前 7 帧 140ms，末帧 240ms
  - `waiting`: row 6, cols 0-5, 前 5 帧 150ms，末帧 260ms
  - `running`: row 7, cols 0-5, 前 5 帧 120ms，末帧 220ms
  - `review`: row 8, cols 0-5, 前 5 帧 150ms，末帧 280ms
  - rows 9-10 是 16 个顺时针视线方向，000° 为向上。
- 状态改变时从新状态第 0 帧开始；组件卸载、页面隐藏或 reduced-motion 时清理 timer。

`AdminPetAssistant.vue`：

- 作为唯一的全局宠物/紧凑聊天宿主。
- `App.vue` 使用异步组件，并仅在 `auth.isStaff && route.name !== 'admin-login' && route.name !== 'login'` 时挂载。
- 维护 `panelOpen`、`petHidden`、`petState`、provider/model、焦点恢复和快捷键。
- 聊天面板仅在打开时挂载 `AdminAiChat compact`，关闭后销毁实例；聊天记录继续由既有 sessionStorage 恢复。
- `/admin/ai` 不挂载紧凑聊天；点击宠物可将焦点移到完整聊天输入框，找不到时给出 toast，不得出现重复会话实例。
- provider 列表只在有管理权限角色打开聊天面板后加载，失败时沿用现有默认 provider 兜底。

`AdminAiChat.vue`：

- 保留现有全部行为；通过类型化 emits 向宿主报告：
  - `stream-start`
  - `stream-first-delta`
  - `stream-complete`
  - `stream-error`
  - `stream-abort`
- 事件只用于宠物动画，不复制聊天状态和请求逻辑。
- 给输入框增加稳定的选择器或 `ref`/`data-testid`，供 `/admin/ai` 的宠物点击逻辑聚焦。
- 401 仍走原有 logout + 跳转，不允许宠物组件吞掉认证错误。

## 4. 交互和动画状态机

状态优先级建议为：`failed > running > waiting > waving > review > idle/look`。

| 触发 | 宠物状态 | 结束条件 |
| --- | --- | --- |
| 首次显示 | `waving` 一轮 | 自动回 `idle` |
| 默认静置 | `idle` | hover、点击或聊天事件 |
| 指针在宠物附近移动且未聊天 | rows 9-10 静态方向帧 | 指针离开有效区域后回 `idle` |
| 打开聊天、输入为空 | `waiting` | 开始生成或关闭面板 |
| SSE 请求开始/生成中 | `running` | 完成、失败或取消 |
| 正常完成 | `review` 一轮 | 自动回 `idle` 或 `waiting` |
| 请求失败 | `failed` 一轮 | 自动回 `waiting`；错误条仍由聊天组件显示 |
| 主动取消 | `idle`/`waiting` | 根据面板是否打开决定 |

实现要求：

- 一次性动画应按累计帧时长结束，不使用与帧时长脱节的硬编码延迟。
- pointer gaze 只在非触屏、非 reduced-motion、非聊天生成、宠物可见时启用；使用 `requestAnimationFrame` 节流。
- 方向计算以宠物中心到指针的向量为准：0°=上，顺时针量化为 16 档；row 9 对应 0°-157.5°，row 10 对应 180°-337.5°。
- 页面 `visibilitychange` 为 hidden 时暂停动画，恢复时从当前状态安全重启。
- 不使用全局持续 60fps 循环；标准动画用单个 `setTimeout` 调度即可。

## 5. UI 规格

- 桌面宠物建议显示约 96 × 104 CSS px，右 20px、下 18px，考虑 `env(safe-area-inset-bottom)`。
- 移动端建议约 76 × 82px；聊天面板为底部全宽抽屉，最大高度约 `min(72dvh, 620px)`。
- 桌面聊天面板建议宽 380px，可保留现有 320-640px 拖拽宽度能力；位置在宠物上方或右侧贴边，不能遮住宠物关闭按钮。
- 管理角色在公开页面浏览时，现有“回到顶部”按钮不得与宠物重叠；通过 App 根 class/CSS 变量移动其中一个控件。
- 面板关闭按钮只关闭面板；宠物 hover/focus 后显示独立的“隐藏宠物”按钮。
- `Escape` 关闭面板并把焦点还给宠物按钮；`Ctrl/Cmd + Shift + A` 切换聊天，若宠物被隐藏则恢复宠物后打开。
- 面板使用 `role="complementary"` 或非模态 dialog，提供可读标题；图标按钮都有中文 `aria-label` 和可见 focus ring。
- 不抢占页面初始焦点，不自动弹出聊天，不播放声音。
- 浅色、深色主题都应使用现有 token；禁止大面积硬编码白色导致暗色主题失真。

## 6. 会话与持久化

- 继续使用现有消息键 `yubai-admin-ai-messages`，不迁移、不复制聊天记录。
- 建议保留现有 provider/model/width/open 偏好键，或迁移到明确的新键时提供一次兼容读取。
- 宠物隐藏键使用 sessionStorage，例如 `yubai-admin-pet-hidden`，只影响当前标签页/登录会话。
- watch 到 `auth.isStaff` 从 true 变 false 时，立即关闭面板、停止动画、清除隐藏键和任何定时器；再次登录默认显示。
- 不把 JWT、提示词或聊天内容写入 localStorage；遵守现有 sessionStorage 安全边界。

## 7. 性能与安全

- 图集复制到前端 public 目录后可长期缓存；文件名若不带 hash，则由 Vite 部署策略控制缓存，不能设置永久 immutable。
- 只有 `ADMIN`/`PARTNER` 管理角色分支成立时才挂载异步组件，确保游客不会因主包导入或 CSS 预加载主动请求 2MB 图集。
- 不能仅靠前端隐藏保护 AI 接口；继续依赖现有后端 `AI_USAGE` 权限。
- 所有流式请求沿用 `streamAiChat` 的 Authorization header，禁止把 token 放到 URL/EventSource。
- 组件卸载、logout、路由切换到登录页时必须 Abort 正在进行的流式请求，避免后台继续消耗配额。
- 不引入第三方动画库；当前需求用 Vue + CSS + timer 足够。

## 8. 测试与验收标准

自动化至少覆盖：

1. 游客和过期会话不出现宠物，也不挂载 `AdminAiChat`。
2. `ADMIN` 与 `PARTNER` 在公开页和后台页均出现宠物；登录页不出现。
3. `PARTNER` 的 capability 集合与 `ADMIN` 完全一致，可访问全部后台路由、完整 AI 页和 provider 管理；角色值仍为 `PARTNER`。
4. `/admin/ai` 宠物存在但紧凑聊天实例不存在。
5. 单击宠物打开聊天，关闭面板后宠物仍在；Escape 关闭且焦点返回。
6. 隐藏宠物写入 sessionStorage；快捷键可以恢复；logout 清理状态。
7. provider/model 正确传入 compact chat，现有模型选择测试继续通过。
8. SSE start/delta/complete/error/abort 分别驱动预期宠物状态，fake timers 下不会泄漏 timer。
9. 图集状态的行、帧数、帧时长和坐标换算准确；状态切换从第 0 帧开始。
10. reduced-motion 下只显示稳定帧，不启动循环 timer 或 pointer gaze。
11. 聊天面板打开时只存在一个 `AdminAiChat`；连续开关不会重复注册键盘监听器或 provider 请求。
12. 宠物和回到顶部按钮在桌面/移动端无重叠，面板不溢出视口。
13. `npm test`、`npm run test:typecheck`、`npm run build`、后端相关权限测试及完整后端测试全部通过，`git diff --check` 通过。

人工验收：

- 分别使用真实 `ADMIN` 和 `PARTNER` 登录，逐页检查首页、文章页、菜谱页、后台页、完整 AI 页和 provider 管理页。
- 实际发送一条流式消息，确认生成中、完成和失败动画与 UI 同步，停止生成可立即停止状态。
- 刷新页面确认聊天记录恢复；退出登录确认宠物立即消失；游客网络面板确认没有请求宠物图集。
- 检查 1440px、768px、390px 宽度以及浅/深色主题。
- 开启系统“减少动态效果”再次检查。
- 视觉检查宠物没有裁切、缩放抖动、透明背景黑边、错误行列或方向颠倒。

## 9. 实施顺序

1. 记录当前 `git status` 和相关 diff，保护用户改动。
2. 复制并校验 `xinn` 图集尺寸，建立动画元数据和纯渲染组件。
3. 为 `PetSprite` 写 fake-timer 单元测试。
4. 给 `AdminAiChat` 增加最小事件接口，不改变请求逻辑。
5. 实现 `AdminPetAssistant` 的鉴权、浮层、状态机、持久化和响应式样式。
6. 在 auth store 中新增管理角色总开关，令 `PARTNER` 与 `ADMIN` capability 完全一致，并清除 router/App/navigation 中阻断 PARTNER 后台访问的硬编码角色判断。
7. 在 `App.vue` 做管理角色条件异步挂载，处理回到顶部控件冲突。
8. 替代旧 `AdminAiSidebar` 的 App 挂载，确保不存在双聊天实例。
9. 补齐前后端权限测试、组件测试和现有测试兼容。
10. 运行完整前后端验证并修复失败。
11. 输出修改文件、验证命令、人工验证结果和已知限制，等待本任务验收。
