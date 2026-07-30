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
  POST: { label: '文章', xRatio: 0.48, yRatio: 0.3, directionDeg: -90, color: '#4a9af7' },
  NOTE: { label: '学习笔记', xRatio: 0.25, yRatio: 0.42, directionDeg: 180, color: '#67c890' },
  DISH: { label: '美食菜谱', xRatio: 0.38, yRatio: 0.68, directionDeg: 125, color: '#f4aa54' },
  TAG: { label: '标签', xRatio: 0.7, yRatio: 0.37, directionDeg: -8, color: '#9b63e7' },
  SERIES: { label: '合集', xRatio: 0.76, yRatio: 0.68, directionDeg: 22, color: '#ef6c9a' },
}

export const ROOT_COLOR = '#f58cab'
export const MAX_DISPLAY_NODES = 40

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

    // Only render groups present in the API. Empty synthetic hubs made the legend and canvas disagree.
    const allGroupTypes = ['POST', 'NOTE', 'DISH', 'TAG', 'SERIES'] as const
    const groupTypes = allGroupTypes.filter((type) =>
      rawNodes.some((node) =>
        (node.kind === 'GROUP' && node.type === type) ||
        (node.kind === 'CONTENT' && node.type === type)
      )
    )
    groupTypes.forEach((type, idx) => {
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
    const contentNodes = rawNodes.filter((n) => n.kind === 'CONTENT' || (!n.kind && n.id !== 'hub-root' && !n.id?.startsWith('hub-')))
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

      const ringSize = 8
      const ringIndex = Math.floor(indexInGroup / ringSize)
      const ringStart = ringIndex * ringSize
      const nodesInRing = Math.min(ringSize, countInGroup - ringStart)
      const slot = indexInGroup - ringStart
      const arcSpan = nodesInRing <= 1 ? 0 : Math.min(2.1, 0.72 + nodesInRing * 0.17)
      const stepAngle = nodesInRing <= 1 ? 0 : (slot / (nodesInRing - 1) - 0.5) * arcSpan
      const angle = baseAngleRad + stepAngle + pseudoRandom(h1) * 0.09
      const ringRadius = 80 + ringIndex * 48 + pseudoRandom(h2) * 10

      const px = Math.max(48, Math.min(baseWidth - 48, hub.x + Math.cos(angle) * ringRadius))
      const py = Math.max(48, Math.min(baseHeight - 48, hub.y + Math.sin(angle) * ringRadius))

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
        order: 6 + idx,
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

    visualEdges.push({
      id: key,
      source: srcId,
      target: tgtId,
      kind,
      strength,
      pathD,
      isStructure,
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
