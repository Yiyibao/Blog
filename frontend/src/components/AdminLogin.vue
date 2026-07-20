<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api/admin'

const router = useRouter()
const username = ref('')
const password = ref('')
const error = ref('')
const submitting = ref(false)

async function submit() {
  error.value = ''
  submitting.value = true
  try {
    const result = await login(username.value.trim(), password.value)
    sessionStorage.setItem('yubai-admin-token', result.token)
    sessionStorage.setItem('yubai-admin-name', result.username)
    sessionStorage.setItem('yubai-admin-expiry', result.expiresAt)
    await router.replace('/admin')
  } catch {
    error.value = '登录失败，请检查用户名和密码。'
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (sessionStorage.getItem('yubai-admin-token')) void router.replace('/admin')
})
</script>

<template>
  <section class="admin-login section-wrap">
    <div class="admin-login-copy">
      <p class="eyebrow"><span /> PRIVATE STUDIO</p>
      <h1>回到你的<br><em>内容工作台。</em></h1>
      <p>管理文章与项目，让每一次发布都保持清晰、可靠和可追溯。</p>
      <RouterLink class="back-link" to="/">← 返回博客</RouterLink>
    </div>
    <form class="admin-login-card" @submit.prevent="submit">
      <span class="admin-lock">AUTH / 01</span>
      <h2>管理员登录</h2>
      <p>使用本机配置的管理账号继续。</p>
      <label>用户名<input v-model="username" autocomplete="username" required placeholder="admin"></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required placeholder="••••••••••••"></label>
      <p v-if="error" class="admin-error" role="alert">{{ error }}</p>
      <button class="button primary" type="submit" :disabled="submitting">{{ submitting ? '正在验证…' : '进入工作台 ↗' }}</button>
      <small>登录令牌仅保存在当前浏览器会话中。</small>
    </form>
  </section>
</template>
