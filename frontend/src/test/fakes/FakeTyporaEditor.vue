<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{
  modelValue: string
  uploadImage: (file: File) => Promise<string>
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'upload-error': [message: string]
}>()

const content = ref(props.modelValue)
const uploading = ref(false)
const uploadPromise = ref<Promise<string> | null>(null)

watch(() => props.modelValue, (val) => {
  content.value = val
})

function onInput(event: Event) {
  const text = (event.target as HTMLElement).textContent ?? ''
  content.value = text
  emit('update:modelValue', text)
}

function setContent(value: string) {
  content.value = value
}

async function simulateUpload(file?: File) {
  if (!file) file = new File(['dummy'], 'test.png', { type: 'image/png' })
  uploading.value = true
  try {
    const result = await props.uploadImage(file)
    return result
  } catch (e) {
    emit('upload-error', '图片上传失败')
    throw e
  } finally {
    uploading.value = false
  }
}

defineExpose({ content, uploading, setContent, uploadPromise, simulateUpload })
</script>

<template>
  <div class="fake-typora-editor">
    <div
      class="fake-prose"
      contenteditable="true"
      :data-content="content"
      @input="onInput"
    >{{ content }}</div>
    <div v-if="uploading" class="fake-uploading">上传中…</div>
  </div>
</template>
