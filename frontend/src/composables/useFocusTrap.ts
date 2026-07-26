import { onBeforeUnmount, watch, type Ref } from 'vue'

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * FD-2：把 Tab / Shift+Tab 焦点圈定在 container 内（模态对话框的 a11y 基线）。
 * container 为 null 时不拦截；监听挂在 window 捕获阶段，容器出现即生效、销毁即移除。
 */
export function useFocusTrap(container: Ref<HTMLElement | null>) {
  function onKeydown(event: KeyboardEvent) {
    if (event.key !== 'Tab') return
    const root = container.value
    if (!root) return
    const focusable = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
    if (!focusable.length) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    const active = document.activeElement
    const inside = active instanceof Node && root.contains(active)
    if (event.shiftKey) {
      if (!inside || active === first) {
        event.preventDefault()
        last.focus()
      }
    } else if (!inside || active === last) {
      event.preventDefault()
      first.focus()
    }
  }

  function attach() {
    window.addEventListener('keydown', onKeydown, true)
  }

  function detach() {
    window.removeEventListener('keydown', onKeydown, true)
  }

  watch(container, (el) => {
    detach()
    if (el) attach()
  }, { immediate: true })

  onBeforeUnmount(detach)
}
