# 前端 Agent 提示词：实现全站知识关联图谱 V2

你正在修改 `D:\Office\Study\code\BlogDemo` 的 Vue 3 + TypeScript 前端。请直接实现并测试，不要改后端，不要提交或部署。现有工作树可能包含其他人的改动，必须保留。

## 目标

把归档页现有 `KnowledgeGraph.vue` 升级为参考图风格的“花园式全站知识关联图谱”：奶白/淡粉玻璃背景、中心花朵节点、五个彩色知识枝系、轻微自然动画、图例与统计卡、搜索、缩放、复位、全屏和小地图。不要机械临摹图片；延续项目现有视觉语言并兼容深色主题。

参考图：`C:\Users\Hfff\Desktop\a.png`

后端已经提供：

- `GET /api/v1/graph/overview`
- 原有 `GET /api/v1/graph/nodes`
- 原有 `GET /api/v1/graph/nodes/{center}?depth=1..3`

先阅读：

- `docs/knowledge-graph-v2-implementation-plan.md`
- `frontend/src/components/KnowledgeGraph.vue`
- `frontend/src/composables/useGraphSubgraph.ts`
- `frontend/src/api/content.ts`
- `frontend/src/pages/ArchivePage.vue`
- `frontend/src/test/KnowledgeGraph.test.ts`
- `frontend/src/test/GraphAuth.test.ts`

## API 类型

在 `frontend/src/api/content.ts` 添加 `fetchGraphOverview()` 和完整类型：

```ts
type GraphNodeKind = 'ROOT' | 'GROUP' | 'CONTENT'
type GraphEdgeKind = 'STRUCTURE' | 'RELATION'

interface GraphOverview {
  schemaVersion: string
  stats: {
    contentNodeCount: number
    visualNodeCount: number
    relationCount: number
    lastUpdatedAt: string | null
    recommendedCenterId: string
    localModeRecommended: boolean
  }
  legend: Array<{ type: string; label: string; color: string; count: number }>
  nodes: Array<{
    id: string
    label: string
    type: string
    kind: GraphNodeKind
    groupId: string | null
    url: string | null
    subtitle: string | null
    imageUrl: string | null
    updatedAt: string | null
    degree: number
    importance: number
  }>
  edges: Array<{
    source: string
    target: string
    kind: GraphEdgeKind
    strength: number
  }>
}
```

保持 `/graph/` 自动附带登录 token 的现有行为。

## 组件拆分

当前 `KnowledgeGraph.vue` 已接近千行，必须拆分，建议：

- `components/knowledge-graph/GraphCanvas.vue`：SVG、viewport transform、节点和边。
- `GraphToolbar.vue`：放大、缩小、复位、刷新、全屏。
- `GraphSidebar.vue`：图例、统计、说明卡。
- `GraphSearch.vue`：搜索与结果。
- `GraphMiniMap.vue`：缩略图和视口矩形。
- `GraphSelectionPanel.vue`：选中节点详情与操作。
- `composables/useGraphViewport.ts`：缩放、平移、全屏、复位、事件清理。
- `composables/useGraphLayout.ts`：确定性花园布局。
- 保留并适配 `useGraphSubgraph.ts`。

不要为了拆分改变现有公开页面路由或归档时间轴结构。

## 布局算法

1. ROOT 固定在逻辑画布中心。
2. GROUP 使用固定扇区和柔和非对称半径，避免机械圆环：
   - POST：上方偏左/蓝色
   - NOTE：左侧/绿色
   - DISH：左下或下方/橙色
   - TAG：右上/紫色
   - SERIES：右侧/粉色
3. CONTENT 在各 GROUP 周围按稳定哈希分层排列。
4. 节点半径由 `kind + importance` 决定，并限制上下界。
5. 实现有限轮次的圆碰撞消解；不得每帧持续运行力导向模拟。
6. 结构边使用柔和三次贝塞尔曲线；关系边更细、更淡、可虚线。
7. 使用稳定 ID 哈希产生小幅角度/半径扰动，刷新后布局必须一致。

## 视觉

- 背景使用低对比奶白到淡粉渐变和玻璃卡片；暗色主题使用现有 CSS 变量正确降级。
- 不要导入外部图片作为背景。花瓣/叶片只用少量 CSS 或 SVG 装饰，不能遮挡信息。
- ROOT 可用简洁花朵 SVG 图标；GROUP 使用项目已有图标体系或内联 SVG，禁止 emoji 作为核心图标。
- 菜谱节点若 `imageUrl` 存在，使用 `<clipPath>` 圆形裁切并提供加载失败回退。
- 节点文字最多两行，超长省略；通过 `<title>`、详情面板和 aria-label 提供完整标题。
- 响应式：桌面侧栏 + 大画布；窄屏侧栏折叠成横向统计条，小地图隐藏。

## 交互与轻动画

- 平移：鼠标/触控 Pointer Events 拖动画布。
- 缩放：滚轮围绕指针缩放，范围建议 0.55–2.2；工具栏同步。
- 单击：锁定节点，突出一跳邻居和关联边，其余淡化。
- 双击 CONTENT：调用现有两层子图逻辑。
- 单击详情“打开内容”：路由跳转。
- 搜索：匹配 label/subtitle/type，选择结果后自动平滑居中。
- 全屏：使用 Fullscreen API，并处理 `fullscreenchange`。
- 小地图：显示简化节点和当前 viewport，可拖动视口矩形。
- 首次入场分 ROOT/GROUP/CONTENT 错峰，总时长不超过 1100ms。
- ROOT/GROUP 呼吸幅度不超过 2.5%，周期 4–7 秒并错开相位。
- 只让选中/hover 边产生缓慢描线；普通边静止。
- 拖动和缩放期间禁用耗时阴影或滤镜。
- `prefers-reduced-motion: reduce` 下取消入场、呼吸、漂移、平滑滚动和描线，只保留即时交互状态。

## 状态与容错

- loading 使用与最终布局接近的骨架，避免页面跳动。
- error 显示重试按钮，旧 `/nodes` 可作为只读降级数据源，但不要伪造统计。
- 空数据保持 ROOT 和说明文案。
- `localModeRecommended=true` 时复用现有推荐中心/子图机制，明确显示“局部视图”与“返回全图”。
- 请求竞态继续使用现有序列号守卫；组件卸载必须取消 RAF、移除全局监听。

## 测试

保留全部现有测试并新增：

- overview API 类型和 URL。
- ROOT/GROUP/CONTENT 渲染与图例计数。
- 布局对同一输入完全稳定。
- 搜索命中并居中。
- hover/selected 邻接高亮。
- 工具栏缩放边界、复位和刷新布局。
- 全屏状态同步。
- 菜谱图片失败回退。
- `prefers-reduced-motion` 降级。
- 卸载后无残留监听器/RAF。
- 超过 300 节点进入局部模式。

运行：

```powershell
npm.cmd run test -- --run
npm.cmd run build
```

## 完成后交付

给出：

1. 修改文件清单。
2. 测试和构建结果。
3. 与参考图的对应点。
4. 已知差异或限制。
5. 不要提交、不要推送、不要部署，等待主 Agent 最终验收。
