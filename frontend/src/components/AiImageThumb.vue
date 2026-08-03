<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchAiImageContent } from '../api/admin'

/**
 * AI 生图历史缩略图：图片内容接口需要管理员 JWT，普通 <img src> 无法直连，
 * 因此在挂载后拉取 blob 生成 objectURL，卸载时回收；预览时父组件复用该 objectURL。
 */
const props = defineProps<{
  publicId: string
  alt: string
}>()

const url = ref('')

onMounted(async () => {
  try {
    const blob = await fetchAiImageContent(props.publicId)
    url.value = URL.createObjectURL(blob)
  } catch {
    url.value = ''
  }
})

onBeforeUnmount(() => {
  if (url.value) URL.revokeObjectURL(url.value)
})

defineExpose({ url })
</script>

<template>
  <img v-if="url" class="thumb" :src="url" :alt="alt" loading="lazy" />
  <span v-else class="thumb thumb-placeholder">加载失败</span>
</template>

<style scoped>
.thumb {
  display: block;
  width: min(300px, calc(100vw - 118px));
  height: min(300px, calc(100vw - 118px));
  object-fit: contain;
  border-radius: 12px;
  border: 1px solid #e6ded4;
  background: #f1ece6;
  cursor: zoom-in;
}
.thumb-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 150px;
  min-height: 150px;
  font-size: 12px;
  color: #a79d92;
}

@media (max-width: 560px) {
  .thumb {
    width: min(260px, calc(100vw - 118px));
    height: min(260px, calc(100vw - 118px));
  }
}
</style>
