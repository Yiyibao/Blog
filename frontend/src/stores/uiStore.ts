import { ref } from 'vue';
import { defineStore } from 'pinia';

export const useUiStore = defineStore('ui', () => {
  const searchOpen = ref(false);
  const toast = ref('');
  const isDark = ref(false);

  let toastTimer: number | undefined;

  function initTheme() {
    const saved = localStorage.getItem('yubai-theme');
    isDark.value = saved ? saved === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
    document.documentElement.classList.toggle('dark', isDark.value);
  }

  function toggleTheme() {
    isDark.value = !isDark.value;
    localStorage.setItem('yubai-theme', isDark.value ? 'dark' : 'light');
    document.documentElement.classList.toggle('dark', isDark.value);
  }

  function showToast(message: string) {
    toast.value = message;
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => {
      toast.value = '';
    }, 2600);
  }

  function openSearch() {
    searchOpen.value = true;
  }

  function closeSearch() {
    searchOpen.value = false;
  }

  return {
    searchOpen,
    toast,
    isDark,
    initTheme,
    toggleTheme,
    showToast,
    openSearch,
    closeSearch,
  };
});
