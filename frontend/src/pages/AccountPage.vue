<script setup lang="ts">
import { useAuthStore } from '../stores/auth'
import ChangePasswordForm from '../components/ChangePasswordForm.vue'

const auth = useAuthStore()
</script>

<template>
  <section class="account-page section-wrap">
    <header class="account-head">
      <p class="account-kicker">ACCOUNT · SAFETY</p>
      <h1>账号安全</h1>
      <p class="account-meta">
        当前登录：<strong>{{ auth.displayName || auth.username }}</strong>
        <span class="account-role">{{ auth.isAdmin ? '站长' : '伙伴' }}</span>
      </p>
    </header>
    <div class="account-card">
      <h2>修改密码</h2>
      <ChangePasswordForm />
    </div>
  </section>
</template>

<style scoped>
.account-page { padding-top: clamp(70px, 9vw, 110px); padding-bottom: 90px; }
.account-kicker { margin: 0; color: var(--accent); font: 650 .64rem/1 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: .17em; }
.account-head h1 { margin: 12px 0 10px; font: 400 clamp(2.2rem, 4.5vw, 3.4rem)/1.05 Georgia, "Songti SC", serif; letter-spacing: -.04em; }
.account-meta { margin: 0; color: var(--muted); font-size: .9rem; }
.account-role { margin-left: 10px; padding: 4px 10px; color: var(--accent); background: color-mix(in srgb, var(--accent-soft) 60%, transparent); border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--line)); border-radius: 999px; font-size: .7rem; }
.account-card { max-width: 520px; margin-top: 34px; padding: clamp(24px, 4vw, 38px); border: 1px solid var(--line); border-radius: 24px 24px 8px 24px; background: var(--surface-solid); box-shadow: var(--shadow-sm); }
.account-card h2 { margin: 0 0 20px; font: 520 1.35rem/1.2 Georgia, "Songti SC", serif; }
</style>
