import axios from 'axios';
import { ref } from 'vue';
import { fetchGraphSubgraph } from '../api/content';
import { useRequestToken } from './useRequestToken';
import type { GraphNode, GraphEdge } from '../components/KnowledgeGraph.vue';

export function useGraphSubgraph() {
  const { next, isCurrent } = useRequestToken();

  const subgraphLoading = ref(false);
  const subgraphError = ref('');
  const subgraphActive = ref(false);
  const localMode = ref(false);
  const localModeCenter = ref('');

  const fullSnapshot = ref<{ nodes: GraphNode[]; edges: GraphEdge[] } | null>(null);

  function saveSnapshot(nodes: GraphNode[], edges: GraphEdge[]) {
    if (!fullSnapshot.value) {
      fullSnapshot.value = { nodes: [...nodes], edges: [...edges] };
    }
  }

  function restoreOverview(): { nodes: GraphNode[]; edges: GraphEdge[] } | null {
    if (fullSnapshot.value) {
      subgraphActive.value = false;
      localMode.value = false;
      localModeCenter.value = '';
      subgraphError.value = '';
      return { nodes: [...fullSnapshot.value.nodes], edges: [...fullSnapshot.value.edges] };
    }
    return null;
  }

  async function expandSubgraph(
    centerId: string,
    depth: number,
    currentNodes: GraphNode[],
    currentEdges: GraphEdge[],
  ): Promise<{ nodes: GraphNode[]; edges: GraphEdge[] } | null> {
    const seq = next();
    subgraphLoading.value = true;
    subgraphError.value = '';
    subgraphActive.value = true;

    try {
      const data = await fetchGraphSubgraph(centerId, depth);
      if (!isCurrent(seq)) return null;

      const existingIds = new Set(currentNodes.map((n) => n.id));
      const mergedNodes: GraphNode[] = [...currentNodes];
      for (const n of data.nodes) {
        if (!existingIds.has(n.id)) {
          mergedNodes.push(n as unknown as GraphNode);
          existingIds.add(n.id);
        }
      }

      const edgeKey = (e: GraphEdge) => `${e.source}|${e.target}`;
      const existingEdgeKeys = new Set(currentEdges.map(edgeKey));
      const mergedEdges: GraphEdge[] = [...currentEdges];
      for (const e of data.edges) {
        const key = edgeKey(e as GraphEdge);
        if (!existingEdgeKeys.has(key)) {
          mergedEdges.push(e as GraphEdge);
          existingEdgeKeys.add(key);
        }
      }

      return { nodes: mergedNodes, edges: mergedEdges };
    } catch (cause) {
      if (!isCurrent(seq)) return null;
      subgraphError.value =
        axios.isAxiosError(cause) && cause.response ? '关联展开失败' : '网络异常，关联展开失败';
      subgraphActive.value = false;
      return null;
    } finally {
      if (isCurrent(seq)) subgraphLoading.value = false;
    }
  }

  function findStableCenter(nodes: GraphNode[], edges: GraphEdge[]): string | null {
    const degree = new Map<string, number>();
    edges.forEach((e) => {
      degree.set(e.source, (degree.get(e.source) || 0) + 1);
      degree.set(e.target, (degree.get(e.target) || 0) + 1);
    });
    const tagNodes = nodes.filter((n) => n.type === 'TAG');
    if (tagNodes.length === 0) return null;
    let best = tagNodes[0];
    let bestD = degree.get(best.id) || 0;
    for (let i = 1; i < tagNodes.length; i++) {
      const d = degree.get(tagNodes[i].id) || 0;
      if (d > bestD || (d === bestD && tagNodes[i].id.localeCompare(best.id) < 0)) {
        best = tagNodes[i];
        bestD = d;
      }
    }
    return best.id;
  }

  async function autoLocalSubgraph(
    nodes: GraphNode[],
    edges: GraphEdge[],
  ): Promise<{ nodes: GraphNode[]; edges: GraphEdge[] } | null> {
    saveSnapshot(nodes, edges);
    localMode.value = true;
    subgraphActive.value = true;

    const center = findStableCenter(nodes, edges);
    if (!center) return null;
    localModeCenter.value = center;

    try {
      const data = await fetchGraphSubgraph(center, 2);
      const resultNodes = data.nodes as unknown as GraphNode[];
      const resultEdges = data.edges as GraphEdge[];
      if (resultNodes.length === 0) return null;
      return { nodes: resultNodes, edges: resultEdges };
    } catch {
      localMode.value = false;
      subgraphActive.value = false;
      return null;
    }
  }

  return {
    subgraphLoading,
    subgraphError,
    subgraphActive,
    localMode,
    localModeCenter,
    fullSnapshot,
    saveSnapshot,
    restoreOverview,
    expandSubgraph,
    findStableCenter,
    autoLocalSubgraph,
  };
}
