# 配色与滚动动效说明 v2

## 配色方案：暖余白 × 冷灰层次

在 Apple 式冷灰基础上，加入可控的辅色层次，避免整站发灰发闷。

| Token | 浅色 | 作用 |
|-------|------|------|
| `--paper` | `#f3f1eb` | 暖灰纸底 |
| `--accent` | `#b07a45` | 品牌棕（主强调） |
| `--accent-2` | `#3d6b63` | 苔绿（项目/自然感） |
| `--accent-3` / `--sky` | 雾蓝系 | 天空高光、分区 wash |
| `--sand` | `#e8dfd0` | 沙色条带 |
| `--mist` | `#dce8e4` | 青雾条带 |

### 分区着色

- **背景全局**：四角径向 wash（蓝 / 棕 / 绿 / 紫）+ 纸色竖向渐变
- **精选 `band-featured`**：青雾 + 淡紫 wash
- **最近更新 `band-latest`**：沙色 + 暖琥珀 wash
- **Manifesto 卡**：棕 + 天空双光斑白卡
- **项目区**：苔绿 wash
- **卡片边框**：按序号轻微变色（棕 / 绿 / 蓝）
- **Hero 艺术卡**：深棕绿渐变 + accent 光晕（彩色锚点）

## 滚动动画

| 元素 | 行为 |
|------|------|
| section / heading / 卡片 | 上浮 28px + 轻 blur → 清晰 |
| featured-card | scale 0.985 → 1 |
| project / related | 左右交错 `data-reveal` |
| 列表项 | `--reveal-delay` 每项 +70ms stagger |
| ticker / newsletter | 轻量入场 |

- 触发：`IntersectionObserver`，`threshold: 0.12`
- 时长：约 0.65–0.75s，`cubic-bezier(0.16,1,0.3,1)`
- `prefers-reduced-motion: reduce`：直接显示，无位移

## 刷新方式

硬刷新首页后向下滚动即可看到分区色带与入场动画。
