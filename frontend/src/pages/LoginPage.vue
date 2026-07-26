<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useLoginForm } from '../composables/useLoginForm'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// FD-9/FD-14：登录后回到来时的页面（含 intent 等 query）；
// 只接受站内相对路径，防开放重定向
function destination(): string {
  const raw = route.query.next
  const next = typeof raw === 'string' ? raw : ''
  if (next.startsWith('/') && !next.startsWith('//')) return next
  return auth.isAdmin ? '/admin' : '/recipes'
}

const {
  username, password, error, submitting, verifying, remember,
  captchaRequired, captchaImage, captchaAnswer,
  refreshCaptcha, submit,
} = useLoginForm(async () => {
  await router.replace(destination())
})

onMounted(() => {
  if (auth.isAuthenticated) void router.replace(destination())
})
</script>

<template>
  <section class="admin-login section-wrap food-login">
    <div class="admin-login-copy">
      <p class="eyebrow"><span /> TASTE CLUB · WELCOME BACK</p>
      <h1>回到我们的<br><em>小餐桌。</em></h1>
      <p>登录后可以一起排今天的菜单、给菜点亮爱心、把每一顿好好吃的饭记下来。</p>
      <RouterLink class="back-link" to="/recipes">← 先去逛逛菜谱</RouterLink>
    </div>
    <form class="admin-login-card" @submit.prevent="submit">
      <span class="admin-lock">TABLE / 02</span>
      <h2>登录</h2>
      <p>用你的专属账号继续。</p>
      <label>用户名<input v-model="username" autocomplete="username" required placeholder="你的用户名"></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required placeholder="••••••••••••"></label>
      <div v-if="captchaRequired" class="admin-captcha">
        <label for="login-captcha-answer">图形验证码</label>
        <button
          type="button"
          class="admin-captcha-image"
          title="看不清？点击换一张"
          :disabled="submitting"
          @click="refreshCaptcha"
        >
          <img v-if="captchaImage" :src="captchaImage" alt="图形验证码，点击可更换">
        </button>
        <div class="admin-captcha-row">
          <input
            id="login-captcha-answer"
            v-model="captchaAnswer"
            autocomplete="off"
            inputmode="text"
            placeholder="输入图中字符（不区分大小写）"
          >
          <button type="button" class="admin-captcha-refresh" :disabled="submitting" @click="refreshCaptcha">换一张</button>
        </div>
      </div>
      <p v-if="error" class="admin-error" role="alert" aria-live="assertive">{{ error }}</p>
      <label class="admin-remember"><input v-model="remember" type="checkbox"> 在这台设备上保持登录（24 小时）</label>
      <button class="button primary" type="submit" :disabled="submitting">
        {{ verifying ? '安全校验中…' : submitting ? '正在验证…' : '回到餐桌 ↗' }}
      </button>
      <small>{{ remember ? '令牌将在本设备保留 24 小时，可随时退出登录。' : '登录令牌仅保存在当前浏览器会话中。' }}</small>
    </form>
  </section>
</template>

<style scoped>
/* FD-9：美食皮肤——复用 admin-login 骨架，只调气质：樱粉衬线标题与暖色卡片描边 */
.food-login .admin-login-copy h1 em { color: var(--accent); font-style: normal; }
.food-login .admin-login-copy .eyebrow { color: var(--accent); }
.food-login .admin-login-card { border-color: color-mix(in srgb, var(--accent) 24%, var(--line)); border-radius: 26px 26px 8px 26px; }
.food-login .admin-lock { color: var(--accent); }
</style>
