# 余白 · UI 视觉规范 v1

> 主方案：**A · 余白·Apple 叙事**
> 动效：**克制优雅**
> 一期范围：**首页 Hero**
> 色彩：**冷灰白 + 一抹品牌色**
> 状态：静态视觉稿说明（尚未改代码）

---

## 1. 设计原则（从 Apple 提炼）

| 原则 | 含义 | 落地到「余白」 |
|------|------|----------------|
| **One idea per screen** | 一屏只说一件事 | Hero 只讲「思考与记录」，不堆功能入口 |
| **Typography first** | 字号层级即设计 | 标题极大、副文克制、元信息等宽小字 |
| **Breathing room** | 留白是产品 | 区块间距 ≥ 内容密度，拒绝拥挤 |
| **Quiet motion** | 动效解释状态，不抢内容 | 200–400ms，ease-out，尊重 `prefers-reduced-motion` |
| **Material honesty** | 表面克制 | 冷灰背景 + 细边框，少渐变、少阴影堆叠 |
| **Focus on content** | CTA 少而明确 | Hero 最多 2 个按钮 |

参考气质：Apple 官网首页的「大标题 + 短句 + 单焦点视觉」；Vision Pro 页的「章节滚动叙事」节奏（本期仅 Hero，不引入整页 scroll-jacking）。

---

## 2. 色彩系统

### 2.1 语义色板（Light）

| Token | 值 | 用途 |
|-------|-----|------|
| `--bg` | `#F5F5F7` | 页面底（Apple 冷灰） |
| `--surface` | `#FFFFFF` | 卡片 / Hero 内表面 |
| `--ink` | `#1D1D1F` | 主文字 |
| `--muted` | `#6E6E73` | 次级文字 |
| `--faint` | `#86868B` | 元信息 / eyebrow |
| `--line` | `rgba(0,0,0,0.08)` | 分割线 / 边框 |
| `--accent` | `#A6784C` | **唯一品牌强调色**（沿用余白暖棕，作点缀） |
| `--accent-soft` | `rgba(166,120,76,0.12)` | 标签底、细高光 |
| `--focus` | `#0071E3` | 仅键盘焦点环（可访问性，不进品牌主色） |

### 2.2 Dark（预留，二期）

| Token | 值 |
|-------|-----|
| `--bg` | `#000000` / `#1D1D1F` 分层 |
| `--surface` | `#161617` |
| `--ink` | `#F5F5F7` |
| `--line` | `rgba(255,255,255,0.12)` |
| `--accent` | 同 `#A6784C` 或略提亮 `#C4A07A` |

### 2.3 使用纪律

- 页面 **90%** 为灰白黑；accent **仅**用于：文字强调词、主 CTA 边/字、小号 stamp、链接 hover。
- 禁止大面积橙色块、彩虹渐变、强拟物阴影。
- 代码块 / 引用可用极浅灰底，不用彩色条纹。

---

## 3. 字体与字阶

### 3.1 字体栈

```css
/* 展示 / 标题 */
--font-display: "SF Pro Display", "Segoe UI", "PingFang SC", "Hiragino Sans GB",
  "Noto Sans SC", system-ui, sans-serif;

/* 正文 */
--font-body: "SF Pro Text", "Segoe UI", "PingFang SC", "Noto Sans SC",
  system-ui, sans-serif;

/* 元信息 / 编号 */
--font-mono: "SF Mono", "JetBrains Mono", "Cascadia Code", ui-monospace, monospace;
```

> Windows 无 SF 时自然回退到 Segoe / 系统中文；观感仍保持「无衬线、冷、清晰」。

### 3.2 字阶（Desktop ≥ 1024px）

| 角色 | 规格 | 备注 |
|------|------|------|
| Hero H1 | `clamp(48px, 7vw, 80px)` / weight 600 / lh 1.05 / tracking `-0.03em` | 最多 2 行 |
| Hero 强调词 | 同 H1，`font-style: italic` 或 accent 色 | 一句里只强调 2–6 字 |
| Lead | `19–21px` / weight 400 / lh 1.6 / color muted | 最大宽度 ~36em |
| Eyebrow | `11px` mono / tracking `0.16em` / uppercase-ish | 左侧小竖条 + 文案 |
| CTA | `15px` / weight 500 | 高度 44–48px |
| Caption | `12px` mono / faint | 视觉角标 |

### 3.3 中文注意

- 中文标题避免过负 letter-spacing（`-0.02em` 封顶）。
- 中英文混排时，英文专名可用稍小一级或 mono 标注（如 `YUBAI / NOTES`）。

---

## 4. 间距与网格

| Token | 值 |
|-------|-----|
| 页边距 | `clamp(20px, 5vw, 48px)` |
| 内容最大宽 | `1120px`（Hero 文案区可 `980px`） |
| 区块纵向 | Hero 上下 `clamp(72px, 12vh, 120px)` |
| 元素间距 | 8 的倍数：8 / 16 / 24 / 32 / 48 / 64 |
| 圆角 | 控件 `980px`（胶囊）；卡片 `20–28px`；大视觉 `28–36px` |

**栅格（Hero）**

```
|← margin →|  copy  (minmax 0, 1fr)  |  gap 48–64  |  art (420–520px)  |← margin →|
```

- ≥1100px：左右分栏
- ＜900px：上下堆叠，art 在文案下，高度收敛

---

## 5. 首页 Hero 静态结构（线框）

```
┌─────────────────────────────────────────────────────────────┐
│  [nav 已有，本期不改结构，仅对齐色与字重]                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   ▌ A TINY STUDIO OF IDEAS          ← eyebrow               │
│                                                             │
│   在代码与日常之间，                   ← H1                   │
│   记录正在发生的思考。                 ← 第二行可 italic/accent │
│                                                             │
│   这里收藏关于设计、工程与生活方式的     ← lead，max ~520px     │
│   长期笔记。……                                               │
│                                                             │
│   [ 开始阅读  → ]   [ 认识我 ]         ← primary / secondary  │
│                                                             │
│                          ┌──────────────────────┐           │
│                          │   07                 │  art card │
│                          │   CODE × DESIGN      │  冷灰底   │
│                          │   [细环 accent]      │  细边框   │
│                          │   ──── paper note ── │           │
│                          │   慢一点，想清楚。    │           │
│                          └──────────────────────┘           │
│                          PERSONAL STUDIO ✦   ← caption      │
│                                                             │
└─────────────────────────────────────────────────────────────┘
         ↓ 极细 ticker 或直接进入「本期精选」（二期）
```

### 5.1 文案规范（可沿用现有，建议微调）

| 元素 | 建议文案 |
|------|----------|
| Eyebrow | `A TINY STUDIO OF IDEAS` |
| H1 | `在代码与日常之间，` + 换行 + `记录正在发生的思考。` |
| 强调 | 「思考」可用 italic 或 accent，二选一 |
| Lead | 保持现有一段，控制在 60–90 字 |
| Primary CTA | `开始阅读` → `/articles` |
| Secondary CTA | `认识我` → `/about` |

### 5.2 Art 视觉卡（静态）

- **主卡**：圆角 28px，底 `#FFFFFF`，边 `1px solid var(--line)`，内边距 36–44px。
- **编号 `07`**：display 字，`ink`，不过分装饰。
- **副文**：mono 11–12px，`muted`，两行。
- **细环**：1.5px stroke，`accent`，直径 ~72px，绝对定位右上或中部，**低透明度 0.85**。
- **纸片 note**（可选叠层）：略偏移 `translate(12%, 18%)`，底 `#F5F5F7`，字「慢一点，想清楚。」
- **禁止**：厚重投影、强 3D 倾斜、多色块拼贴（当前 lime/orange 点可删或降为 1 个 accent 点）。

### 5.3 CTA 样式

| 类型 | 外观 |
|------|------|
| Primary | 填充 `ink`（`#1D1D1F`），字 `#F5F5F7`，胶囊；hover 微抬升 2px + 阴影 `0 8px 24px rgba(0,0,0,.12)` |
| Secondary | 透明底 + `1px line` 边，字 `ink`；hover 底 `rgba(0,0,0,.04)` |
| 不用 | 大面积 accent 填充主按钮（accent 留给文字/图标点缀） |

---

## 6. 动效规范（克制优雅）

### 6.1 全局

```text
duration-fast: 180ms
duration:      280ms
duration-slow: 400ms
easing:        cubic-bezier(0.25, 0.1, 0.25, 1)   /* 接近 ease */
easing-out:    cubic-bezier(0.16, 1, 0.3, 1)      /* 入场 */
```

- 一律尊重：

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
```

### 6.2 Hero 入场（首屏一次）

| 步骤 | 元素 | 行为 | 时长 / 延迟 |
|------|------|------|-------------|
| 1 | eyebrow | opacity 0→1，y 8→0 | 280ms / 0ms |
| 2 | H1 | 同上，可按行 stagger 60ms | 360ms / 80ms |
| 3 | lead | 同上 | 320ms / 160ms |
| 4 | CTAs | 同上，按钮整体 | 280ms / 220ms |
| 5 | art | opacity 0→1，y 16→0，**无弹跳** | 400ms / 160ms |

禁止：弹性 overshoot、旋转入场、模糊扫光。

### 6.3 交互微动效

| 交互 | 行为 |
|------|------|
| CTA hover | `translateY(-2px)` + 阴影，280ms |
| CTA active | `translateY(0)`，180ms |
| Art hover（可选） | 纸片 note 仅 `translateY(-4px)`，整体不 3D 翻转 |
| 链接 | 颜色 → accent，underline 从 0 宽到 100%（可选，280ms） |
| 焦点 | 2px `focus` 色环，offset 2px |

### 6.4 明确不做（一期）

- Scroll-jacking / 全屏强制分页
- 鼠标强磁吸跟随
- 背景视频自动播放
- 粒子、噪点动画循环

---

## 7. 与现状差异（改版对照）

| 项目 | 当前倾向 | v1 目标 |
|------|----------|---------|
| 背景 | 暖纸色、多彩色点 | 冷灰 `#F5F5F7` |
| Hero 艺术 | 蓝/橙/lime 多色块 | 白卡 + 单 accent 环 |
| 主按钮 | 偏彩色 primary | 近黑填充，更 Apple |
| 阴影 | 偏重、多向 | 轻、单一方向 |
| 动效 | 指针倾斜 3D 等 | 减弱 3D，保留轻入场 |
| 信息密度 | 中等偏花 | 更疏、更静 |

---

## 8. 组件清单（仅 Hero 相关）

1. `HeroEyebrow` — 竖线 + mono 文案
2. `HeroTitle` — 双行标题 + 可选强调 span
3. `HeroLead` — 限制宽度段落
4. `HeroActions` — primary + secondary
5. `HeroArt` — 编号卡 + 可选 paper note + caption
6. `MotionEnter` — 统一入场 class（`data-enter` + stagger index）

---

## 9. 响应式断点

| 断点 | Hero 布局 |
|------|-----------|
| ≥1100px | 文案 \| 艺术 两栏 |
| 900–1099px | 两栏，art 缩小至 360px |
| ＜900px | 单栏；art 全宽 max 400px 居中；H1 用 clamp 下限 |
| ＜480px | CTA 可纵向堆叠全宽 |

---

## 10. 无障碍

- 对比度：正文 `ink` on `bg` ≥ 7:1；muted on bg ≥ 4.5:1
- 所有 CTA 可键盘到达；焦点环可见
- Art 装饰 `aria-hidden="true"`（若纯装饰）
- 动效可关闭（见 6.1）
- 不依赖颜色表达唯一信息

---

## 11. 交付与验收清单（实现阶段用）

- [ ] Hero 背景为冷灰，无大面积暖黄底
- [ ] 仅一处系统级 accent（棕）点缀
- [ ] H1 字号在 1440 宽接近 72–80px
- [ ] 入场总时长 ≤ 700ms，无弹跳
- [ ] `prefers-reduced-motion: reduce` 下无位移动画
- [ ] 移动端无横向溢出
- [ ] Primary / Secondary 样式符合第 5.3 节

---

## 12. 二期预告（本文件不实施）

1. 精选文章区：Apple 式大卡 + 静音标签
2. 文章详情：更宽 measure、顶栏进度更细
3. 全局导航毛玻璃（`backdrop-filter` 轻量）
4. Dark mode token 全面启用
5. 章节式「关于」页（弱 scroll 叙事）

---

## 13. 已确认决策

1. Hero 强调词「思考」→ **斜体**（继承主色，不用 accent）
2. Art 区 → **单卡极简**
3. 主 CTA → **近黑填充**
4. 一期已落地首页 Hero 代码（`App.vue` + `styles.css` token/Hero）

---

*文档版本：v1.1 · 方案 A 已实现 Hero*
