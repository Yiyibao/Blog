<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import heroBackgroundUrl from '../assets/hero-sakura-lake.jpg'

/**
 * L-16/D-18：入口大屏——仅根路径且无既往选择时出现；选择记 localStorage，
 * 深链（非根路径）直达默认游客态，分享链接不被拦截。已登录用户永不拦截。
 */
const STORAGE_KEY = 'yubai-entry-choice'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

function readChoice(): string | null {
  try {
    return window.localStorage?.getItem(STORAGE_KEY) ?? null
  } catch {
    return null
  }
}

function writeChoice(value: string) {
  try {
    window.localStorage?.setItem(STORAGE_KEY, value)
  } catch {
    // 隐私模式存不了就退化为本次会话内不再出现（dismissed 兜底）
  }
}

/** 支持带有 ?entry=1 或 ?gate=1 或 ?resetEntry=1 时主动呼出入口弹窗 */
const forceShow = computed(() =>
  route.query.entry === '1' || route.query.gate === '1' || route.query.resetEntry === '1'
)

const dismissed = ref(readChoice() !== null)

const visible = computed(() =>
  (forceShow.value || !dismissed.value) && route.path === '/' && !auth.isAuthenticated)

function chooseGuest() {
  writeChoice('guest')
  dismissed.value = true
}

function chooseAdmin() {
  writeChoice('admin')
  dismissed.value = true
  void router.push({ path: '/login', query: { next: '/' } })
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="entry-gate" role="dialog" aria-modal="true" aria-label="选择进入方式">
      <img class="entry-backdrop" :src="heroBackgroundUrl" alt="" fetchpriority="high" decoding="async">
      <div class="entry-content">
        <p class="entry-kicker">HXNF'S MEMOIR</p>
        <h1 class="entry-title">日常拾光录</h1>
        <p class="entry-sub">拾起日常里一闪而过的光。<br>选择一种方式，翻开这本生活手记。</p>
        <div class="entry-choices">
          <button type="button" class="entry-choice guest" @click="chooseGuest">
            <span class="choice-icon" aria-hidden="true">✿</span>
            <strong>以游客身份进入</strong>
            <small>浏览文章、归档与美食</small>
          </button>
          <button type="button" class="entry-choice admin" @click="chooseAdmin">
            <span class="choice-icon" aria-hidden="true">✦</span>
            <strong>以管理员身份进入</strong>
            <small>登录后解锁学习笔记与工作台</small>
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.entry-gate {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: grid;
  place-items: center;
  overflow: hidden;
}
.entry-gate::after {
  content: "";
  position: absolute;
  inset: 0;
  z-index: 0;
  background: linear-gradient(180deg, rgba(18, 15, 13, 0.55), rgba(18, 15, 13, 0.72));
  pointer-events: none;
}
.entry-backdrop {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  animation: entry-zoom 12s ease-out forwards;
}
@keyframes entry-zoom {
  from { transform: scale(1.08); }
  to { transform: scale(1); }
}
.entry-content {
  position: relative;
  z-index: 1;
  text-align: center;
  color: #f4efe8;
  padding: 24px;
  animation: entry-rise 0.9s cubic-bezier(0.22, 1, 0.36, 1);
}
@keyframes entry-rise {
  from { opacity: 0; transform: translateY(26px); }
  to { opacity: 1; transform: none; }
}
.entry-kicker {
  margin: 0 0 10px;
  font: 600 11px ui-monospace, Consolas, monospace;
  letter-spacing: 0.34em;
  color: rgba(244, 239, 232, 0.72);
}
.entry-title {
  margin: 0;
  font: 400 clamp(44px, 8vw, 84px)/1.1 Georgia, serif;
  letter-spacing: 0.06em;
}
.entry-sub {
  margin: 18px 0 40px;
  font-size: 15px;
  line-height: 1.9;
  color: rgba(244, 239, 232, 0.85);
}
.entry-choices {
  display: flex;
  gap: 20px;
  justify-content: center;
  flex-wrap: wrap;
}
.entry-choice {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  min-width: 220px;
  padding: 22px 28px;
  border-radius: 18px;
  border: 1px solid rgba(244, 239, 232, 0.35);
  background: rgba(24, 20, 17, 0.35);
  color: #f4efe8;
  cursor: pointer;
  backdrop-filter: blur(10px);
  transition: transform 0.35s cubic-bezier(0.22, 1, 0.36, 1), border-color 0.25s, background 0.25s;
}
.entry-choice:hover,
.entry-choice:focus-visible {
  transform: translateY(-4px);
  border-color: rgba(244, 239, 232, 0.85);
  background: rgba(24, 20, 17, 0.55);
}
.choice-icon { font-size: 22px; }
.entry-choice strong { font-size: 16px; letter-spacing: 0.04em; }
.entry-choice small { font-size: 12px; color: rgba(244, 239, 232, 0.68); }

@media (prefers-reduced-motion: reduce) {
  .entry-backdrop, .entry-content { animation: none; }
  .entry-choice { transition: none; }
}
</style>
