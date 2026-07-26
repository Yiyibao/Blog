import { onBeforeUnmount, ref } from 'vue'

/** FD-5：响应式的 prefers-reduced-motion 查询；无 matchMedia 环境（jsdom）默认不减弱以便测试走完整路径。 */
export function usePrefersReducedMotion() {
  const reduced = ref(false)
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    const query = window.matchMedia('(prefers-reduced-motion: reduce)')
    reduced.value = query.matches
    const onChange = (event: MediaQueryListEvent) => { reduced.value = event.matches }
    query.addEventListener?.('change', onChange)
    onBeforeUnmount(() => query.removeEventListener?.('change', onChange))
  }
  return reduced
}
