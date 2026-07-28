<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { hasValidAdminSession } from '../api/admin'
import { useLoginForm } from '../composables/useLoginForm'
import HumanVerifyModal from './HumanVerifyModal.vue'
import TotpVerifyModal from './TotpVerifyModal.vue'

const router = useRouter()
// FD-9：表单逻辑提取为 useLoginForm，与 /login 通用登录页共用
// L-15：人机验证改显性弹窗——提交先过 HumanVerifyModal，通过后才发登录请求
const {
  username, password, error, submitting, remember,
  verifyOpen, handleVerified, handleVerifyCancel, submit,
  totpOpen, totpSubmitting, totpError, submitTotp, cancelTotp,
} = useLoginForm(async () => {
  await router.replace('/admin')
})

onMounted(() => {
  if (hasValidAdminSession()) void router.replace('/admin')
})
</script>

<template>
  <section class="admin-login section-wrap">
    <div class="admin-login-copy">
      <p class="eyebrow"><span /> PRIVATE STUDIO</p>
      <h1>回到你的<br><em>内容工作台。</em></h1>
      <p>管理文章、菜品与学习笔记，让每一次发布都保持清晰、可靠和可追溯。</p>
      <RouterLink class="back-link" to="/">← 返回博客</RouterLink>
    </div>
    <form class="admin-login-card" @submit.prevent="submit">
      <span class="admin-lock">AUTH / 01</span>
      <h2>管理员登录</h2>
      <p>使用本机配置的管理账号继续。</p>
      <label>用户名<input v-model="username" autocomplete="username" required placeholder="admin"></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required placeholder="••••••••••••"></label>
      <p v-if="error" class="admin-error" role="alert" aria-live="assertive">{{ error }}</p>
      <label class="admin-remember"><input v-model="remember" type="checkbox"> 在这台设备上保持登录（24 小时）</label>
      <button class="button primary" type="submit" :disabled="submitting">
        {{ submitting ? '正在验证…' : '进入工作台 ↗' }}
      </button>
      <small>{{ remember ? '登录状态将通过 Cookie 保持 24 小时，可随时退出登录。' : '登录令牌仅保存在当前浏览器会话中。' }}</small>
    </form>
    <HumanVerifyModal
      :open="verifyOpen"
      :username="username"
      @verified="handleVerified"
      @cancel="handleVerifyCancel"
    />
    <TotpVerifyModal
      :open="totpOpen"
      :submitting="totpSubmitting"
      :error="totpError"
      @submit="submitTotp"
      @cancel="cancelTotp"
    />
  </section>
</template>
