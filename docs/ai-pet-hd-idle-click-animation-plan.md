# AI 宠物高清化、待机动作与聊天点击动作实施方案

## 1. 已确认的产品决定

- 保持当前显示尺寸不变：桌面端宽 `307px`，移动端宽 `243px`，高度继续按 `208 / 192` 比例计算。
- “高清化”只提高源素材分辨率、缩放采样质量和逐帧一致性，不再放大 UI 中的宠物。
- 鼠标连续 `30_000ms` 没有悬浮到宠物按钮上，且宠物处于可待机状态时，随机播放三组待机动作之一。
- 待机动作完整播放一次后回到普通 `idle`，并从零重新计时 `30_000ms`。
- 鼠标进入宠物按钮的实际命中区域时，立即清零计时并取消正在播放的待机动作；鼠标离开后重新从零计时。
- 点击宠物打开聊天面板时播放一组新的、自然可爱的专用动作；面板立即响应打开，不让动画阻塞交互。

## 2. 当前实现结论

当前关键文件：

- `frontend/src/components/admin-pet/PetSprite.vue`
- `frontend/src/components/admin-pet/petAnimations.ts`
- `frontend/src/components/admin-pet/AdminPetAssistant.vue`
- `frontend/public/pets/xinn/spritesheet.webp`
- `frontend/src/test/PetSprite.test.ts`
- `frontend/src/test/AdminPetAssistant.test.ts`
- `frontend/scripts/pet-panel-layout-check.mjs`

现有图集是 Codex v2 的 `8×11` 图集，单格 `192×208`，整图 `1536×2288`。桌面端却以 `307px` 宽显示单格，相当于把 `192px` 宽的帧放大约 `1.60` 倍，因此发饰、眼睛、衣纹、袖口和透明边缘必然变软。CSS 滤镜或 `image-rendering` 无法恢复不存在的细节；只把旧图机械放大到 2 倍也不能作为高清验收通过。

现有行为状态包含标准 `idle`、启动 `waving`、聊天 `waiting/running/review/failed` 和 16 方向 `look`，但不存在独立的 30 秒待机调度器。点击目前直接切换聊天面板，没有专用的 `chat-open` 动作。

## 3. 总体架构

### 3.1 素材层：网站专用的 2× 行图集

保留现有 Codex v2 图集作为身份、动作语义和兼容基线，不把网站扩展动作强塞进标准 11 行。新增网站专用高清行图集：

```text
frontend/public/pets/xinn/hd/
  idle.webp
  running-right.webp
  running-left.webp
  waving.webp
  jumping.webp
  failed.webp
  waiting.webp
  running.webp
  review.webp
  look-row-9.webp
  look-row-10.webp
  idle-curious.webp
  idle-sleeve.webp
  idle-sway.webp
  chat-open.webp
```

每个文件固定为一行 8 格：

- 源单格：`384×416`。
- 行图尺寸：`3072×416`。
- 未使用格完全透明。
- 页面仍渲染为桌面 `307px`、移动 `243px`，因此是缩小高清源图，不是放大低清源图。
- 使用透明 WebP；优先 lossless 或 near-lossless 高质量编码，透明像素下的隐藏 RGB 必须清空。

选择“按行拆分”而不是一张 `3072×4576` 的 2× 大图，是为了避免浏览器一次解码约 56MB RGBA；任一时刻只需解码当前行，关键动作可预加载，其他状态按需加载。图片继续走现有 Workbox `CacheFirst` 运行时缓存，不进入 PWA 预缓存。

如果图像生成链路能够稳定输出更高原生分辨率，可保留 3× 母版作为制作源，但前端交付默认使用 2× 行图。不得把 1× 旧图通过普通插值放大后冒充 2× 原生高清交付。

### 3.2 动画元数据层

重构 `petAnimations.ts`，把“逻辑显示尺寸”和“素材像素尺寸”分开：

```ts
interface SpriteSource {
  url: string
  sourceCellWidth: 384
  sourceCellHeight: 416
  columns: 8
  rows: 1
}

interface AnimationSpec {
  source: SpriteSource
  frames: number
  durations: readonly number[]
  loop: boolean
  kind: 'standard' | 'idle-action' | 'interaction'
}
```

`PetSprite.vue` 的裁切、整图 CSS 宽高和位移全部依据 `sourceCellWidth/sourceCellHeight` 计算；组件对外的 `size` 仍是 `307/243`。16 方向视线根据方向索引在 `look-row-9.webp` 与 `look-row-10.webp` 之间选择。

为防拆图后切换资源出现空白或旧资源 `load` 回调串台：

- 状态改变时清理旧 timer、回到第 0 帧并递增播放 token。
- 当前图片已完成加载时立即调度；否则等待对应 token 的 `load/decode` 完成再调度。
- 过期 token 的加载回调不得启动旧动画。
- `finished` 事件携带实际完成的动画 id；父组件只清理仍匹配的动作。

### 3.3 行为层：单一可取消调度器

建议新增纯逻辑文件：

```text
frontend/src/components/admin-pet/petIdleScheduler.ts
```

职责仅包括：30 秒计时、随机选择、暂停/清零、动作结束后重启和销毁清理。随机函数作为依赖注入，生产使用 `Math.random`，测试注入固定值。

状态建议：

```text
disabled -> counting -> playing-idle-action -> counting
                 |               |
                 +-- reset ------+
```

待机计时的必要条件：

- 宠物可见且用户仍有 staff 权限；
- 页面 `visibilityState === 'visible'`；
- 鼠标不在宠物按钮内；
- 未拖动；
- 聊天面板关闭；
- 未流式生成；
- 没有更高优先级的一次性动作；
- 未启用 `prefers-reduced-motion`。

精确定义：

1. 组件挂载后的首次 `waving` 播完，从零开始 30 秒计时。
2. `pointerenter` 到宠物按钮：清 timer、累计归零、取消当前待机动作，进入现有 `look` 或普通交互状态。
3. `pointerleave`：若满足必要条件，从零启动新的 30 秒 timer。
4. 到达 30 秒：均匀随机选择 `idle-curious | idle-sleeve | idle-sway`，只播一轮。
5. 该轮 `finished`：回普通 `idle`，并从零重新计时。
6. 点击、拖动、打开面板、开始生成、隐藏宠物、退出登录、进入登录路由、页面 hidden、组件卸载：清 timer；恢复后均从零重新计时，不补播后台错过的动作。
7. 面板关闭：从零重新计时。
8. 触屏没有 hover；`pointerdown` 视为一次用户接触并清零。移动端若无后续触摸，在其余条件成立时仍可于 30 秒后播放待机动作。
9. 同一时刻最多存在一个待机 timer，不使用持续 60fps 轮询。

随机选择使用 `Math.floor(random() * 3)`，三组各占 `[0, 1/3)`、`[1/3, 2/3)`、`[2/3, 1)`；允许连续两次抽到同一组，保证语义是每次独立随机。

### 3.4 状态优先级

`displayState` 使用明确优先级，避免多个布尔量互相覆盖：

```text
dragging
> failed
> streaming/running
> chat-open
> startup-waving / review
> idle-action
> look
> panel waiting
> idle
```

特殊中断规则：

- 实际 hover、点击或拖动都可立即中断 `idle-action`。
- `chat-open` 不被 `panelOpen` 的 `waiting` 覆盖；动作结束后自然落到 `waiting`。
- `failed` 和流式 `running` 永远高于装饰性动作。
- `prefers-reduced-motion` 下禁用自动待机动作和 `chat-open` 播放，点击仍立即打开/聚焦聊天，宠物显示稳定的 `waiting/idle` 首帧，不能卡在不会 emit `finished` 的一次性动作。

## 4. 四组新动作设计

所有动作保持同一人物身份、脸型、五官、发冠、花饰、流苏、金色披帛、蓝白裙装和身体比例。脚底基线稳定；动作通过表情、头颈、手臂、袖摆和轻微重心变化表达，不增加新道具、文字、气泡、星星、速度线、光晕、地面阴影或分离特效。

### 4.1 待机一：`idle-curious`（好奇眨眼歪头）

- 8 帧，建议时长：`[180, 140, 140, 220, 180, 180, 240, 320]`，总长 `1600ms`。
- 动作：正常呼吸 → 轻眨眼 → 眼睛先看向一侧 → 头部轻歪约 3–5° → 发饰与两侧流苏略滞后跟随 → 小幅回弹 → 回到中立姿势。
- 可爱点来自眼神、微笑和轻歪头，不使用夸张拉伸。
- 验收重点：流苏不能瞬移，瞳孔不能滑出眼眶，头饰不能变形或左右互换。

### 4.2 待机二：`idle-sleeve`（害羞整理袖口）

- 8 帧，建议时长：`[200, 180, 180, 220, 200, 180, 260, 360]`，总长 `1780ms`。
- 动作：视线轻轻下移 → 双手在身前靠近 → 一只手小幅整理另一侧袖口/披帛 → 眼睛弯成温柔笑意 → 放手 → 袖摆二次跟随 → 回中立。
- 身体只做很小的前倾，双脚不移动。
- 验收重点：手必须始终连在袖内，不能出现多余手指、断袖或衣料穿插。

### 4.3 待机三：`idle-sway`（轻踮脚裙摆摇曳）

- 8 帧，建议时长：`[160, 160, 180, 180, 180, 180, 220, 340]`，总长 `1600ms`。
- 动作：重心轻移 → 脚跟微抬但脚尖仍接地 → 身体向一侧轻摆 → 裙摆和披帛迟半拍跟随 → 小幅向另一侧回弹 → 站稳 → 衣摆自然收束。
- 整体位移控制在单格宽度的 3% 内，不做跳跃或明显走步。
- 验收重点：脚底基线不能上下跳，裙摆体积不能忽大忽小，披帛不能与身体脱离。

### 4.4 点击聊天：`chat-open`（欣喜迎接小欠身）

- 8 帧，建议时长：`[90, 90, 110, 130, 150, 160, 180, 220]`，总长 `1130ms`。
- 动作：点击瞬间注意力集中/快速眨眼 → 眼睛亮起并微笑 → 双手轻合到身前 → 很小的欢迎式欠身 → 一只手自然抬起作“请开始说吧”的迎接姿势 → 袖摆跟随 → 回稳并衔接 `waiting`。
- 动作保持正面、方向中性，因为聊天面板可能根据宠物位置向左、向右、向上或向下翻转。
- 点击后面板 DOM 立即打开；视觉面板可在 `120–180ms` 内淡入/轻移，但输入框在 `nextTick` 后立即可聚焦，不能等 1130ms 动作结束。
- 再次点击关闭面板时不反向播放该动作，直接关闭并重新开始待机计时。

## 5. 高清素材制作与 QA 流程

1. 以现有 `xinn`、用户参考图和现有通过验收的标准图集作为身份参考。
2. 使用 `hatch-pet` 的身份一致性、透明背景、动作语义和逐行动画 QA 规则；视觉生成只使用 `$imagegen`。
3. 先批准一张高清中立基准帧，重点锁定脸型、眼睛、发冠花饰、流苏长度、披帛纹样、裙装配色和脚部比例。
4. 先制作并验收 `idle.webp` 与 `chat-open.webp`，证明高清管线、透明边缘和前端渲染正确，再继续其余标准行与三组待机行。
5. 每行作为一个连贯的 8 帧动作族生成；若其中一帧身份或结构失败，修复整行，不把不同生成批次的单帧拼入最终行。
6. 使用确定性脚本完成抠图、统一缩放、脚底/躯干锚点对齐、透明边缘清理和 `3072×416` 行图装配。
7. 生成以下 QA 产物：逐行动画 GIF/WebP 预览、透明/深色/浅色三背景联系表、旧版与新版在 `307px` 下的 1:1 对比截图、所有新动作总览联系表、结构验证 JSON、文件体积清单。
8. 只在最终行图上执行一次确定性透明边缘清理；通过后不得因主观“像有色边”反复模糊边缘。

高清素材硬门槛：

- 每个有效格确实来自高分辨率生成/重绘母版，具有 `384×416` 的真实细节；禁止仅用双线性、双三次或 Lanczos 将 `192×208` 放大充数。
- 使用格非空，未使用格完全透明；无白底、黑底、色键底、内部透明断层和跨格内容。
- 四边至少保留 8 源像素安全区；任何头饰、袖摆、流苏或裙摆不得裁切。
- 相邻帧脚底基线漂移折算到 307px 显示尺寸不超过 2 CSS px；非设计需要的身体高度变化不超过 2%。
- 同一动作内脸、眼睛、发饰、服装配色和装饰位置无身份漂移。
- 单个行图建议不超过 1.5MB；首屏关键加载（idle + chat-open）建议不超过 3MB；所有高清行图总编码体积目标不超过 20MB。若超过，优先优化 WebP 编码和按需加载，不降低有效分辨率或破坏透明边缘。

## 6. 代码改动清单

新增：

- `frontend/public/pets/xinn/hd/*.webp`
- `frontend/src/components/admin-pet/petIdleScheduler.ts`
- `frontend/src/test/petIdleScheduler.test.ts`
- `frontend/scripts/pet-animation-asset-check.mjs`
- `frontend/scripts/pet-animation-browser-check.mjs`（也可扩展现有 `pet-panel-layout-check.mjs`）

修改：

- `frontend/src/components/admin-pet/petAnimations.ts`
- `frontend/src/components/admin-pet/PetSprite.vue`
- `frontend/src/components/admin-pet/AdminPetAssistant.vue`
- `frontend/src/test/PetSprite.test.ts`
- `frontend/src/test/AdminPetAssistant.test.ts`
- `frontend/scripts/pet-panel-layout-check.mjs`
- `frontend/src/test/AcceptanceScriptsContract.test.ts`（若新增验收脚本）

不应修改：

- 当前桌面/移动宠物尺寸常量 `307/243`。
- AI 后端、SSE 请求、provider/model 选择、鉴权和聊天记录格式。
- `/admin/ai` 只存在一个完整聊天实例的现有约束。

## 7. 自动化测试矩阵

### 7.1 素材结构检查

`pet-animation-asset-check.mjs` 必须逐文件验证：

- WebP 尺寸精确为 `3072×416`。
- 每格源尺寸为 `384×416`，有效帧数量与元数据一致。
- 未使用格 alpha 全零；有效格 alpha 非空。
- 外围安全区、跨格像素、内部整行透明断层、色键残留和文件体积。
- 配置引用的资源全部存在，不允许孤儿配置或漏配文件。

### 7.2 `petAnimations.ts` 单元测试

- 15 个动画 id 的资源、帧数、逐帧时长、loop/kind 精确匹配。
- 三组随机区间分别映射到三个待机动作。
- 16 个 gaze 角度在两个高清 look 行之间映射正确。
- `totalDuration` 对四个新动作和旧动作均正确。

### 7.3 `PetSprite.vue` 单元测试

- `size=307` 时裁切窗口仍为 `307×332.7...` CSS px，图片按 `384` 源像素单格缩小，不改变 UI 尺寸。
- 逐帧位移按高清源单格换算，不能继续硬编码 `192×208`。
- 资源未加载前不推进 timer；加载完成后从第 0 帧开始。
- 快速切换两个不同资源时，旧资源的迟到 `load` 不得启动旧状态。
- one-shot 在精确累计时长 emit 一次带动画 id 的 `finished`；循环动画不 emit。
- 页面 hidden、卸载、状态切换时 timer 全部清理。
- reduced-motion 不启动 timer，也不依赖 `finished` 才能完成业务交互。

### 7.4 待机调度器单元测试（fake timers）

- `29_999ms` 不触发，`30_000ms` 恰好触发一次。
- 固定随机值 `0/0.34/0.67` 分别选择三组动作。
- 动作播放期间不再创建第二个 30 秒 timer。
- `finished` 后从零计时；完成后 `29_999ms` 不触发，下一毫秒触发。
- hover 前已累计任意时间时，`pointerenter` 清零；长时间 hover 不触发；`pointerleave` 后重新完整等待 30 秒。
- hover 能立即取消正在播放的待机动作。
- 点击、拖动、面板打开、streaming、hidden、登出、隐藏宠物、登录路由、unmount 均清理 timer。
- 条件恢复后从零开始，不沿用暂停前剩余时间。
- 任意事件序列下 `vi.getTimerCount()` 的待机 timer 不超过 1。

### 7.5 `AdminPetAssistant.vue` 集成测试

- 首次 waving 完成后才开始 30 秒待机计时。
- 待机动作完成后回 `idle`；三组动作都可通过注入随机值覆盖。
- 点击关闭状态下的宠物：`chat-open` 立即成为显示状态，同时聊天面板已挂载、输入框获得焦点；1130ms 后进入 `waiting`。
- 点击发生在待机动作中：待机动作被取消，`chat-open` 从第 0 帧开始。
- 点击发生在 `/admin/ai`：不创建 compact 面板，仍聚焦完整页输入框；可播放同一短动作或在 reduced-motion 下直接静态聚焦。
- 面板关闭后重新从零计时。
- SSE `running/failed/review` 优先级不受新动作破坏。
- 拖动结束的抑制 click 行为继续通过。
- reduced-motion 下点击不因一次性动画无 `finished` 而卡住。

### 7.6 真实浏览器验收

在 Edge/Chrome headless 或可见浏览器中至少覆盖：

- 视口：`1440×900`、`1024×768`、`390×844`、`360×800`。
- DPR：1 与 2；桌面尺寸仍为 307px，移动尺寸仍为 243px。
- 深浅主题与透明背景。
- 等待 30 秒自动动作、hover 清零、动作完成再计时、点击开窗动作。
- 资源请求：游客仍不请求任何宠物图；staff 初始只请求关键高清行，不一次下载所有 15 行。
- 快速 hover/click/拖动/开关面板 20 次后无重复 timer、无空白帧、无控制台异常。
- `/admin/ai` 仍只有一个聊天实例并正确聚焦。

## 8. 最终验收条件

只有全部满足才可宣布完成：

### 清晰度

- 宠物显示尺寸与当前版本逐像素一致：桌面 307px、移动 243px，不允许变大或变小。
- 在 307px 实际显示截图中，眼睫、瞳孔边缘、发冠金边、花饰、流苏、袖口纹样和裙摆轮廓均比旧版清晰；无明显插值马赛克、重影、白边或过度锐化光环。
- 2× 素材结构检查全部通过，且不是旧图机械放大的伪高清。

### 动作质量

- 三组待机动作在轮廓和语义上明显不同，均可爱、克制、完整回到中立姿势。
- 点击动作在不依赖面板方向的情况下自然表达“注意到用户并欢迎对话”，并顺滑衔接 `waiting`。
- 所有动作无脸部漂移、五官跳动、多手指、断袖、发饰变形、流苏瞬移、裙摆体积跳变、脚底漂移、裁切和跨格污染。
- 联系表与逐行动画预览经过独立视觉 QA 或用户逐项确认。

### 计时与交互

- 30 秒定义、hover 清零、动作完成重计时完全符合第 3.3 节。
- 点击开窗立即响应，动画不阻塞面板挂载和输入框聚焦。
- 新行为不破坏拖动、隐藏/恢复、快捷键、聊天 SSE、路由、权限、游客不加载和 reduced-motion。

### 工程质量

- 新增素材脚本、单元测试、组件集成测试和真实浏览器验收全部通过。
- 无泄漏 timer、rAF、事件监听器或图片加载回调。
- `git diff --check` 通过；不提交、不部署；不覆盖用户已有未提交文件。

## 9. 交给实现 Agent 的完整提示词

```text
你要在仓库 D:\Office\Study\code\BlogDemo 中直接完成 AI 宠物高清化、三组 30 秒待机动作、点击聊天开窗动作、自动化测试和最终验收。不要只给方案，不要创建 git commit，不要部署。

先完整阅读：
1. D:\Office\Study\code\BlogDemo\docs\ai-pet-hd-idle-click-animation-plan.md
2. C:\Users\Hfff\.codex\skills\hatch-pet\SKILL.md
3. hatch-pet SKILL.md 所要求的相关 references，尤其 animation-rows.md
4. C:\Users\Hfff\.codex\skills\.system\imagegen\SKILL.md（开始任何视觉生成前）

第一步运行 git status --short 和相关 git diff。当前工作树可能有用户未提交文件；只做增量修改，严禁 git reset、git checkout --、清理未知文件、覆盖用户改动或大范围无关格式化。所有文本编辑使用 apply_patch。

不可更改的产品决定：
- 当前显示大小就是最终大小：桌面宽 307px，移动宽 243px，高度保持 208/192 比例。高清化不能改变 UI 尺寸。
- 现有每帧 192×208 在桌面被放大约 1.60 倍，是模糊根因。交付至少 384×416 的原生高清帧，并在现有尺寸下缩小渲染。
- 不接受把旧 192×208 图用普通插值放大后冒充高清。
- 鼠标 30_000ms 未悬浮到宠物按钮才触发待机；每次 pointerenter 清零并取消待机动作，pointerleave 后从零重新计时；动作播完后也从零重新计时。
- 三组待机动作每次独立均匀随机选择，允许连续重复。
- 点击打开聊天面板时播放 chat-open，但面板必须立即挂载并聚焦，不能等动画结束。
- reduced-motion 禁用自动待机和 chat-open 动画，但点击/聚焦业务必须立即完成。

视觉工作必须使用 hatch-pet 规则和 $imagegen。以用户参考图、现有 xinn 图集和已批准中立帧锁定身份。新动作是：
1. idle-curious：好奇眨眼、轻歪头、发饰流苏自然跟随；8 帧。
2. idle-sleeve：害羞整理袖口、轻微低头微笑、袖摆二次跟随；8 帧。
3. idle-sway：轻踮脚、裙摆与披帛迟滞摇曳、自然站稳；8 帧。
4. chat-open：注意力集中、欣喜微笑、双手轻合、小欠身、欢迎手势、衔接 waiting；8 帧，方向中性。

禁止新增道具、文字、气泡、星星、速度线、光晕、地面阴影或分离特效。保持脸型、眼睛、发冠、花饰、流苏、披帛、裙装配色和身体比例。若一帧身份/结构失败，修复整行，不拼接不同生成批次的单帧。

采用 docs 方案中的网站专用 2× 行图集：每个文件 3072×416，一行 8 格，每格 384×416。保留标准 Codex v2 图集作为基线，不把扩展动作塞进标准 11 行。先完成并验收 idle.webp 与 chat-open.webp，再继续其他高清标准行、两行 look 和三组待机行。生成联系表、逐行动画预览、307px 旧新对比和结构 QA 产物。

代码实现：
- 重构 petAnimations.ts，将 CSS 显示 size 与 sourceCellWidth/sourceCellHeight 分离。
- PetSprite.vue 支持按动画行切换资源；处理 load/decode、播放 token、旧加载回调失效、精确逐帧时长、带动画 id 的 finished。
- 新增 petIdleScheduler.ts，使用单个可取消 setTimeout；随机函数可注入以便测试。
- AdminPetAssistant.vue 实现精确状态优先级、hover/click/drag/panel/stream/visibility/auth/route/reduced-motion 规则。
- 保持 AI 后端、SSE、provider/model、鉴权、聊天记录和 /admin/ai 单实例行为不变。
- 保持 DESKTOP_SIZE=307、MOBILE_SIZE=243。

必须新增/完善：
- 素材结构检查脚本，验证尺寸、alpha、有效/未使用格、跨格、安全边距、内部透明断层、资源存在性和体积。
- petAnimations、PetSprite、petIdleScheduler、AdminPetAssistant 的 Vitest 测试。
- 真实浏览器验收，覆盖 1440/1024/390/360、DPR 1/2、30 秒边界、hover 清零、点击开窗动作、快速交互、游客不加载、/admin/ai 单实例和深浅主题。
- 视觉 QA：所有动作逐行预览、浅/深/透明背景联系表、307px 旧新 1:1 对比；独立视觉 QA 或明确交给用户确认。

任何 timer 验收必须用 fake timers 精确证明：29_999ms 不触发，30_000ms 触发；完成动作后重新完整等待 30 秒；同一时刻待机 timer 不超过 1。不要使用轮询或持续 60fps 循环。

完成前至少执行并记录：
cd D:\Office\Study\code\BlogDemo\frontend
npm.cmd test
npm.cmd run test:typecheck
npm.cmd run build
node scripts\pet-animation-asset-check.mjs
node scripts\pet-panel-layout-check.mjs
node scripts\pet-animation-browser-check.mjs（若独立新增）
cd ..
git diff --check
git status --short
git diff --stat

若改动触及后端或完整验收编排，再运行 backend Maven tests 与现有 acceptance-run；未触及时不要为了形式修改后端。

最终回复必须包含：
- 实际实现架构和状态机说明；
- 修改/新增文件清单；
- 素材规格、文件体积和“非机械放大”的证据；
- 每条测试命令的真实结果；
- 307px/243px 尺寸未改变的证据；
- 三组待机动作、30 秒/hover 重置、chat-open、reduced-motion、游客不加载、/admin/ai 单实例的验收证据；
- QA 联系表与动画预览的绝对路径；
- 已知问题，没有则写“无已知阻断问题”。

达到全部硬门槛后停止，等待用户验收；不要提交或部署。
```

## 10. 推荐执行顺序

1. 冻结现状并补齐纯逻辑失败测试。
2. 完成高清 `idle` 与 `chat-open` 两行，跑素材检查和 307px 视觉对比。
3. 改造渲染器与资源加载，确认大小完全不变且切图无闪烁。
4. 实现 30 秒调度器和状态优先级，跑 fake-timer 测试。
5. 完成三组待机动作，再补齐其余标准高清行与 look 行。
6. 跑全量单测、类型检查、构建、素材验证和真实浏览器验收。
7. 独立视觉 QA；只修失败的整行并重新跑相应验证。
8. 输出最终证据，等待用户确认，不提交、不部署。
