<script setup lang="ts">
import { computed, ref, watch } from 'vue';

const props = withDefaults(
  defineProps<{
    page: number;
    totalPages: number;
    ariaLabel?: string;
  }>(),
  {
    ariaLabel: '分页',
  },
);

const emit = defineEmits<{
  change: [page: number];
}>();

const jumpPage = ref(String(props.page + 1));

watch(
  () => props.page,
  (page) => {
    jumpPage.value = String(page + 1);
  },
);

const pageItems = computed(() => {
  const total = props.totalPages;
  if (total <= 3) return Array.from({ length: total }, (_, index) => index);

  const last = total - 1;
  if (props.page <= 1) return [0, 1, 'right-ellipsis', last] as const;
  if (props.page >= last - 1) return [0, 'left-ellipsis', last - 1, last] as const;
  return [0, 'left-ellipsis', props.page, 'right-ellipsis', last] as const;
});

function changePage(page: number) {
  if (page < 0 || page >= props.totalPages || page === props.page) return;
  emit('change', page);
}

function jump() {
  const parsed = Number.parseInt(jumpPage.value, 10);
  if (!Number.isFinite(parsed)) {
    jumpPage.value = String(props.page + 1);
    return;
  }
  const target = Math.min(props.totalPages, Math.max(1, parsed)) - 1;
  jumpPage.value = String(target + 1);
  changePage(target);
}
</script>

<template>
  <nav v-if="totalPages > 1" class="pagination" :aria-label="ariaLabel">
    <button
      class="pagination-control"
      type="button"
      :disabled="page <= 0"
      aria-label="上一页"
      @click="changePage(page - 1)"
    >
      上一页
    </button>
    <div class="pagination-pages">
      <template v-for="item in pageItems" :key="item">
        <span v-if="typeof item === 'string'" class="pagination-ellipsis" aria-hidden="true">…</span>
        <button
          v-else
          class="pagination-page"
          :class="{ active: item === page }"
          type="button"
          :aria-label="`第 ${item + 1} 页`"
          :aria-current="item === page ? 'page' : undefined"
          @click="changePage(item)"
        >
          {{ item + 1 }}
        </button>
      </template>
    </div>
    <button
      class="pagination-control"
      type="button"
      :disabled="page >= totalPages - 1"
      aria-label="下一页"
      @click="changePage(page + 1)"
    >
      下一页
    </button>
    <form class="pagination-jump" @submit.prevent="jump">
      <label
        >跳至
        <input
          v-model="jumpPage"
          type="number"
          inputmode="numeric"
          min="1"
          :max="totalPages"
          aria-label="跳转页码"
        />
        页</label
      >
      <button type="submit">跳转</button>
    </form>
  </nav>
</template>

<style scoped>
.pagination {
  margin: 36px 0 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 12px;
}
.pagination button {
  min-width: 0;
  height: 42px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
  transition:
    transform 0.2s,
    border-color 0.2s,
    background 0.2s,
    opacity 0.2s;
}
.pagination button:hover:not(:disabled) {
  transform: translateY(-2px);
  border-color: var(--accent);
}
.pagination button:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.pagination-control {
  min-width: 88px !important;
  padding: 0 20px;
  border-radius: 999px;
}
.pagination-pages {
  display: flex;
  align-items: center;
  gap: 6px;
}
.pagination-page {
  width: 38px;
  padding: 0;
  border-radius: 50%;
  font:
    600 12px ui-monospace,
    Consolas,
    monospace;
}
.pagination-page.active {
  border-color: var(--ink);
  background: var(--ink);
  color: var(--paper);
}
.pagination-ellipsis {
  width: 24px;
  text-align: center;
  color: var(--faint);
}
.pagination-jump {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: 4px;
}
.pagination-jump label {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--faint);
  font-size: 12px;
  white-space: nowrap;
}
.pagination-jump input {
  width: 58px;
  height: 38px;
  padding: 0 6px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink);
  text-align: center;
  font:
    600 12px ui-monospace,
    Consolas,
    monospace;
}
.pagination-jump button {
  height: 38px;
  padding: 0 14px;
  border-radius: 10px;
  font-size: 12px;
}
@media (max-width: 640px) {
  .pagination {
    gap: 9px;
  }
  .pagination-control {
    min-width: 76px !important;
    padding: 0 14px;
  }
  .pagination-jump {
    width: 100%;
    justify-content: center;
    margin: 4px 0 0;
  }
}
</style>
