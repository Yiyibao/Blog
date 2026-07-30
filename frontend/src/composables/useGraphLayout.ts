import type { GraphNodeKind, GraphEdgeKind, GraphOverviewNode, GraphOverviewEdge } from '../api/content'

export interface VisualNode {
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
  x: number
  y: number
  radius: number
  color: string
  order: number
}

export interface VisualEdge {
  id: string
  source: string
  target: string
  kind: GraphEdgeKind
  strength: number
  pathD: string
  isStructure: boolean
  color: string
  leafX: number
  leafY: number
  leafRotation: number
}

function stringHash(str: string, salt = 0): number {
  let hash = salt
  for (let i = 0; i < str.length; i++) {
    hash = (hash * 31 + str.charCodeAt(i)) | 0
  }
  return hash
}

function pseudoRandom(hash: number): number {
  const val = Math.sin(hash) * 10000
  return val - Math.floor(val) - 0.5
}

export const GROUP_CONFIGS: Record<string, {
  label: string
  xRatio: number
  yRatio: number
  directionDeg: number
  color: string
}> = {
  POST: { label: '文章', xRatio: 0.53, yRatio: 0.32, directionDeg: -90, color: '#4a9af7' },
  NOTE: { label: '学习笔记', xRatio: 0.33, yRatio: 0.49, directionDeg: 180, color: '#ef6c9a' },
  DISH: { label: '美食菜谱', xRatio: 0.67, yRatio: 0.51, directionDeg: 0, color: '#f4aa54' },
}

export const ROOT_COLOR = '#f58cab'
export const MAX_DISPLAY_NODES = 40
export const TREE_GROUP_TYPES = ['POST', 'NOTE', 'DISH'] as const

export function computeGardenLayout(
  rawNodes: Partial<GraphOverviewNode>[],
  rawEdges: Partial<GraphOverviewEdge>[],
  baseWidth = 1000,
  baseHeight = 680
) {
  const cx = baseWidth / 2
  const cy = baseHeight / 2

  const nodesMap = new Map<string, VisualNode>()
  const isV2Overview = rawNodes.some((n) => n.kind === 'ROOT' || n.kind === 'GROUP' || n.id === 'hub-root')

  if (!isV2Overview) {
    const legacyContent = rawNodes
      .filter((node) => TREE_GROUP_TYPES.includes(node.type as (typeof TREE_GROUP_TYPES)[number]))
      .map((node) => ({
        ...node,
        kind: 'CONTENT' as const,
        groupId: `hub-${node.type!.toLowerCase()}`,
      }))
    const treeNodes: Partial<GraphOverviewNode>[] = [
      {
        id: 'root-knowledge',
        label: '全站知识',
        type: 'ROOT',
        kind: 'ROOT',
        groupId: null,
      },
      ...TREE_GROUP_TYPES.map((type) => ({
        id: `hub-${type.toLowerCase()}`,
        label: GROUP_CONFIGS[type].label,
        type,
        kind: 'GROUP' as const,
        groupId: 'root-knowledge',
      })),
      ...legacyContent,
    ]
    const contentIds = new Set(legacyContent.map((node) => node.id))
    const treeEdges = rawEdges.filter((edge) =>
      Boolean(edge.source && edge.target && contentIds.has(edge.source) && contentIds.has(edge.target))
    )
    return computeGardenLayout(treeNodes, treeEdges, baseWidth, baseHeight)
  }

  // Calculate node degrees
  const degreeMap = new Map<string, number>()
  rawEdges.forEach((e) => {
    if (e.source && e.target) {
      degreeMap.set(e.source, (degreeMap.get(e.source) || 0) + 1)
      degreeMap.set(e.target, (degreeMap.get(e.target) || 0) + 1)
    }
  })

  if (isV2Overview) {
    // V2 Overview Mode with ROOT & GROUP hubs
    const groupHubs = new Map<string, VisualNode>()

    // Ensure ROOT
    let rootNode = rawNodes.find((n) => n.kind === 'ROOT' || n.id === 'hub-root')
    if (!rootNode) {
      rootNode = {
        id: 'hub-root',
        label: '全站知识',
        type: 'ROOT',
        kind: 'ROOT',
        groupId: null,
        url: null,
        subtitle: '知识花园中心',
        imageUrl: null,
        updatedAt: null,
        degree: 10,
        importance: 5,
      }
    }

    const vRoot: VisualNode = {
      id: rootNode.id || 'hub-root',
      label: rootNode.label || '全站知识',
      type: rootNode.type || 'ROOT',
      kind: 'ROOT',
      groupId: null,
      url: rootNode.url || null,
      subtitle: rootNode.subtitle || null,
      imageUrl: rootNode.imageUrl || null,
      updatedAt: rootNode.updatedAt || null,
      degree: rootNode.degree || 10,
      importance: rootNode.importance || 5,
      x: cx,
      y: cy,
      radius: 36,
      color: ROOT_COLOR,
      order: 0,
    }
    nodesMap.set(vRoot.id, vRoot)

    const rootId = vRoot.id

    // The overview is a stable three-branch tree, including an empty NOTE branch for guests.
    TREE_GROUP_TYPES.forEach((type, idx) => {
      const hubId = `hub-${type.toLowerCase()}`
      const existing = rawNodes.find((n) => n.id === hubId || (n.kind === 'GROUP' && n.type === type))
      const config = GROUP_CONFIGS[type]
      const gx = baseWidth * config.xRatio
      const gy = baseHeight * config.yRatio

      const vGroup: VisualNode = {
        id: existing?.id || hubId,
        label: existing?.label || config.label,
        type,
        kind: 'GROUP',
        groupId: rootId,
        url: null,
        subtitle: existing?.subtitle || `${config.label}枢纽`,
        imageUrl: null,
        updatedAt: null,
        degree: existing?.degree || 5,
        importance: existing?.importance || 4,
        x: gx,
        y: gy,
        radius: 25,
        color: config.color,
        order: idx + 1,
      }
      groupHubs.set(type, vGroup)
      nodesMap.set(vGroup.id, vGroup)
    })

    // Filter & Cap CONTENT nodes
    const contentNodes = rawNodes.filter((n) =>
      TREE_GROUP_TYPES.includes(n.type as (typeof TREE_GROUP_TYPES)[number])
      && (n.kind === 'CONTENT' || (!n.kind && n.id !== 'hub-root' && !n.id?.startsWith('hub-')))
    )
    const sortedContent = [...contentNodes].sort((a, b) => {
      const degA = a.degree ?? degreeMap.get(a.id || '') ?? 0
      const degB = b.degree ?? degreeMap.get(b.id || '') ?? 0
      return degB - degA || (a.id || '').localeCompare(b.id || '')
    })
    const cappedContent = sortedContent.slice(0, MAX_DISPLAY_NODES)

    // Group content nodes
    const contentByGroup = new Map<string, Partial<GraphOverviewNode>[]>()
    cappedContent.forEach((n) => {
      const key = n.groupId || n.type || 'POST'
      const list = contentByGroup.get(key) || []
      list.push(n)
      contentByGroup.set(key, list)
    })

    cappedContent.forEach((n, idx) => {
      const typeKey = (n.type && n.type in GROUP_CONFIGS ? n.type : 'POST') as keyof typeof GROUP_CONFIGS
      const hub = groupHubs.get(typeKey) || groupHubs.get('POST')!
      const config = GROUP_CONFIGS[typeKey] || GROUP_CONFIGS['POST']

      const groupList = contentByGroup.get(n.groupId || n.type || '') || [n]
      const indexInGroup = groupList.findIndex((item) => item.id === n.id)
      const countInGroup = groupList.length

      const baseAngleRad = (config.directionDeg * Math.PI) / 180
      const h1 = stringHash(n.id || '', 13)
      const h2 = stringHash(n.id || '', 37)

      const tierSize = 6
      const tier = Math.floor(indexInGroup / tierSize)
      const tierStart = tier * tierSize
      const nodesInTier = Math.min(tierSize, countInGroup - tierStart)
      const slot = indexInGroup - tierStart
      const offset = (slot - (nodesInTier - 1) / 2) * 43 + pseudoRandom(h1) * 8
      const depth = 82 + tier * 67 + (slot % 2) * 14 + pseudoRandom(h2) * 8
      const dirX = Math.cos(baseAngleRad)
      const dirY = Math.sin(baseAngleRad)
      const perpX = -dirY
      const perpY = dirX

      const px = Math.max(48, Math.min(baseWidth - 48, hub.x + dirX * depth + perpX * offset))
      const py = Math.max(48, Math.min(baseHeight - 48, hub.y + dirY * depth + perpY * offset))

      const importance = Math.max(1, n.importance || 1)
      const radius = 10.5 + Math.min(7, Math.sqrt(importance) * 1.35)

      const vNode: VisualNode = {
        id: n.id || 'unknown',
        label: n.label || n.id || '',
        type: n.type || 'POST',
        kind: 'CONTENT',
        groupId: n.groupId || hub.id,
        url: n.url || null,
        subtitle: n.subtitle || null,
        imageUrl: n.imageUrl || null,
        updatedAt: n.updatedAt || null,
        degree: n.degree ?? degreeMap.get(n.id || '') ?? 1,
        importance,
        x: px,
        y: py,
        radius,
        color: config.color,
        order: 4 + idx,
      }
      nodesMap.set(vNode.id, vNode)
    })

    // Resolve circle collisions while keeping ROOT/GROUP anchors fixed.
    const allList = Array.from(nodesMap.values())
    for (let iter = 0; iter < 20; iter++) {
      for (let i = 0; i < allList.length; i++) {
        const nodeA = allList[i]

        for (let j = i + 1; j < allList.length; j++) {
          const nodeB = allList[j]
          const dx = nodeB.x - nodeA.x
          const dy = nodeB.y - nodeA.y
          const dist = Math.hypot(dx, dy) || 0.001
          const minDist = nodeA.radius + nodeB.radius + 12

          if (dist < minDist) {
            const overlap = (minDist - dist) / 2
            const nx = (dx / dist) * overlap
            const ny = (dy / dist) * overlap

            const factorA = nodeA.kind === 'CONTENT' ? 0.5 : 0
            nodeA.x -= nx * factorA
            nodeA.y -= ny * factorA

            const factorB = nodeB.kind === 'CONTENT' ? 0.5 : 0
            nodeB.x += nx * factorB
            nodeB.y += ny * factorB
          }
        }
      }
    }
    allList.forEach((node) => {
      if (node.kind !== 'CONTENT') return
      node.x = Math.max(42, Math.min(baseWidth - 42, node.x))
      node.y = Math.max(42, Math.min(baseHeight - 42, node.y))
    })
  } else {
    // Legacy V1 Mode: Position nodes directly without artificial extra hub nodes
    const tagNodes = rawNodes
      .filter((n) => n.type === 'TAG')
      .sort((a, b) => (degreeMap.get(b.id || '') || 0) - (degreeMap.get(a.id || '') || 0) || (a.id || '').localeCompare(b.id || ''))
    const contentNodes = rawNodes
      .filter((n) => n.type !== 'TAG')
      .sort((a, b) => (a.id || '').localeCompare(b.id || ''))

    const cappedTags = tagNodes.slice(0, 12)
    const cappedContent = contentNodes.slice(0, MAX_DISPLAY_NODES - 12)
    const cappedList = [...cappedTags, ...cappedContent]

    cappedList.forEach((n, idx) => {
      const isTag = n.type === 'TAG'
      const total = isTag ? cappedTags.length : cappedContent.length
      const groupIdx = isTag ? cappedTags.indexOf(n) : cappedContent.indexOf(n)
      const angle = total === 1 ? 0 : (groupIdx / total) * Math.PI * 2 - Math.PI / 2

      const h1 = stringHash(n.id || '', 7)
      const h2 = stringHash(n.id || '', 13)
      const jitterX = pseudoRandom(h1) * 36
      const jitterY = pseudoRandom(h2) * 36

      const ringDist = isTag ? 100 : 200 + (groupIdx % 2) * 45
      const px = cx + Math.cos(angle) * ringDist + jitterX
      const py = cy + Math.sin(angle) * ringDist + jitterY

      const typeKey = (n.type && n.type in GROUP_CONFIGS ? n.type : 'POST') as keyof typeof GROUP_CONFIGS
      const config = GROUP_CONFIGS[typeKey] || GROUP_CONFIGS['POST']

      const vNode: VisualNode = {
        id: n.id || 'unknown',
        label: n.label || n.id || '',
        type: n.type || 'POST',
        kind: isTag ? 'GROUP' : 'CONTENT',
        groupId: null,
        url: n.url || null,
        subtitle: n.subtitle || null,
        imageUrl: n.imageUrl || null,
        updatedAt: n.updatedAt || null,
        degree: degreeMap.get(n.id || '') || 1,
        importance: isTag ? 3 : 2,
        x: px,
        y: py,
        radius: isTag ? 18 : 20,
        color: config.color,
        order: idx,
      }
      nodesMap.set(vNode.id, vNode)
    })
  }

  // Generate Visual Edges
  const visualEdges: VisualEdge[] = []
  const edgeSet = new Set<string>()

  const addEdge = (srcId: string, tgtId: string, kind: GraphEdgeKind = 'RELATION', strength = 1) => {
    if (!nodesMap.has(srcId) || !nodesMap.has(tgtId)) return
    const key = `${srcId}|${tgtId}`
    if (edgeSet.has(key) || edgeSet.has(`${tgtId}|${srcId}`)) return
    edgeSet.add(key)

    const src = nodesMap.get(srcId)!
    const tgt = nodesMap.get(tgtId)!
    const isStructure = kind === 'STRUCTURE' || src.kind !== 'CONTENT' || tgt.kind !== 'CONTENT'

    let pathD = ''
    if (isStructure) {
      const dx = tgt.x - src.x
      const dy = tgt.y - src.y
      const perpX = -dy * 0.15
      const perpY = dx * 0.15
      const ctrlX1 = src.x + dx * 0.4 + perpX
      const ctrlY1 = src.y + dy * 0.4 + perpY
      const ctrlX2 = src.x + dx * 0.7 - perpX
      const ctrlY2 = src.y + dy * 0.7 - perpY
      pathD = `M ${src.x.toFixed(1)},${src.y.toFixed(1)} C ${ctrlX1.toFixed(1)},${ctrlY1.toFixed(1)} ${ctrlX2.toFixed(1)},${ctrlY2.toFixed(1)} ${tgt.x.toFixed(1)},${tgt.y.toFixed(1)}`
    } else {
      pathD = `M ${src.x.toFixed(1)},${src.y.toFixed(1)} L ${tgt.x.toFixed(1)},${tgt.y.toFixed(1)}`
    }

    const edgeColor = tgt.kind !== 'ROOT' ? tgt.color : src.color

    visualEdges.push({
      id: key,
      source: srcId,
      target: tgtId,
      kind,
      strength,
      pathD,
      isStructure,
      color: edgeColor,
      leafX: src.x + (tgt.x - src.x) * 0.62,
      leafY: src.y + (tgt.y - src.y) * 0.62,
      leafRotation: Math.atan2(tgt.y - src.y, tgt.x - src.x) * 180 / Math.PI + 32,
    })
  }

  if (isV2Overview) {
    // Structure edges for V2
    nodesMap.forEach((node) => {
      if (node.kind === 'GROUP') {
        addEdge(node.groupId || 'root-knowledge', node.id, 'STRUCTURE', 2)
      } else if (node.kind === 'CONTENT' && node.groupId) {
        addEdge(node.groupId, node.id, 'STRUCTURE', 1)
      }
    })
  }

  // Raw edges
  rawEdges.forEach((e) => {
    if (e.source && e.target) {
      addEdge(e.source, e.target, e.kind || 'RELATION', e.strength || 1)
    }
  })

  return {
    nodesMap,
    nodesList: Array.from(nodesMap.values()),
    edgesList: visualEdges,
  }
}
