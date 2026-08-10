import { computed, onMounted, onUnmounted, ref, type Ref } from 'vue';

export function useGraphViewport(baseWidth = 1000, baseHeight = 680, containerRef?: Ref<HTMLElement | null>) {
  const zoom = ref(1);
  const panX = ref(0);
  const panY = ref(0);
  const isFullscreen = ref(false);

  const MIN_ZOOM = 0.55;
  const MAX_ZOOM = 2.2;

  const viewBox = computed(() => {
    const w = baseWidth / zoom.value;
    const h = baseHeight / zoom.value;
    const x = (baseWidth - w) / 2 + panX.value;
    const y = (baseHeight - h) / 2 + panY.value;
    return `${x} ${y} ${w} ${h}`;
  });

  function zoomBy(factor: number, pivotX?: number, pivotY?: number) {
    const oldZoom = zoom.value;
    const newZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, oldZoom * factor));
    if (newZoom === oldZoom) return;

    if (pivotX !== undefined && pivotY !== undefined) {
      // Zoom centered around pivot
      const oldW = baseWidth / oldZoom;
      const oldH = baseHeight / oldZoom;
      const oldX = (baseWidth - oldW) / 2 + panX.value;
      const oldY = (baseHeight - oldH) / 2 + panY.value;

      const pivotRatioX = (pivotX - oldX) / oldW;
      const pivotRatioY = (pivotY - oldY) / oldH;

      const newW = baseWidth / newZoom;
      const newH = baseHeight / newZoom;

      zoom.value = newZoom;

      const targetX = pivotX - pivotRatioX * newW;
      const targetY = pivotY - pivotRatioY * newH;
      panX.value = targetX - (baseWidth - newW) / 2;
      panY.value = targetY - (baseHeight - newH) / 2;
    } else {
      zoom.value = newZoom;
    }
  }

  function resetView() {
    zoom.value = 1;
    panX.value = 0;
    panY.value = 0;
  }

  function centerOn(x: number, y: number, targetZoom?: number) {
    if (targetZoom) {
      zoom.value = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, targetZoom));
    }
    panX.value = x - baseWidth / 2;
    panY.value = y - baseHeight / 2;
  }

  let dragState: { startX: number; startY: number; panX: number; panY: number } | null = null;
  let rafId: number | null = null;

  function onPointerDown(event: PointerEvent) {
    const target = event.target as Element | null;
    if (target && target.closest('.graph-interactive-element')) return;

    dragState = {
      startX: event.clientX,
      startY: event.clientY,
      panX: panX.value,
      panY: panY.value,
    };
    if (event.currentTarget && 'setPointerCapture' in event.currentTarget) {
      try {
        (event.currentTarget as Element).setPointerCapture(event.pointerId);
      } catch {
        // ignore capture errors
      }
    }
  }

  function onPointerMove(event: PointerEvent) {
    if (!dragState) return;
    const state = dragState;
    const dx = event.clientX - state.startX;
    const dy = event.clientY - state.startY;
    const scale = 1 / zoom.value;
    const nextPanX = state.panX - dx * scale;
    const nextPanY = state.panY - dy * scale;

    if (rafId !== null) cancelAnimationFrame(rafId);
    rafId = requestAnimationFrame(() => {
      panX.value = nextPanX;
      panY.value = nextPanY;
      rafId = null;
    });
  }

  function onPointerUp(event?: PointerEvent) {
    if (event && event.currentTarget && 'releasePointerCapture' in event.currentTarget) {
      try {
        (event.currentTarget as Element).releasePointerCapture(event.pointerId);
      } catch {
        // ignore release errors
      }
    }
    dragState = null;
  }

  function onWheel(event: WheelEvent, svgEl?: SVGSVGElement | null) {
    event.preventDefault();
    const factor = event.deltaY < 0 ? 1.1 : 1 / 1.1;
    if (svgEl) {
      const rect = svgEl.getBoundingClientRect();
      const mouseX = event.clientX - rect.left;
      const mouseY = event.clientY - rect.top;

      // Convert mouse client pos to viewBox logical pos
      const currentW = baseWidth / zoom.value;
      const currentH = baseHeight / zoom.value;
      const currentX = (baseWidth - currentW) / 2 + panX.value;
      const currentY = (baseHeight - currentH) / 2 + panY.value;

      const pivotX = currentX + (mouseX / rect.width) * currentW;
      const pivotY = currentY + (mouseY / rect.height) * currentH;
      zoomBy(factor, pivotX, pivotY);
    } else {
      zoomBy(factor);
    }
  }

  function handleFullscreenChange() {
    isFullscreen.value = Boolean(document.fullscreenElement);
  }

  async function toggleFullscreen() {
    const el = containerRef?.value || document.documentElement;
    if (!document.fullscreenElement) {
      try {
        if (el.requestFullscreen) {
          await el.requestFullscreen();
        }
        isFullscreen.value = Boolean(document.fullscreenElement);
      } catch {
        isFullscreen.value = false;
      }
    } else {
      try {
        if (document.exitFullscreen) {
          await document.exitFullscreen();
        }
        isFullscreen.value = false;
      } catch {
        isFullscreen.value = false;
      }
    }
  }

  onMounted(() => {
    document.addEventListener('fullscreenchange', handleFullscreenChange);
  });

  onUnmounted(() => {
    document.removeEventListener('fullscreenchange', handleFullscreenChange);
    if (rafId !== null) cancelAnimationFrame(rafId);
  });

  return {
    zoom,
    panX,
    panY,
    MIN_ZOOM,
    MAX_ZOOM,
    isFullscreen,
    viewBox,
    zoomBy,
    resetView,
    centerOn,
    onPointerDown,
    onPointerMove,
    onPointerUp,
    onWheel,
    toggleFullscreen,
  };
}
