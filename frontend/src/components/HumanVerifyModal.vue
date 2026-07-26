<script setup lang="ts">
import axios from 'axios'
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { fetchLoginChallenge, type LoginChallenge, type LoginVerification } from '../api/admin'
import { solvePow } from '../utils/pow'

/**
 * L-15：人机验证点击式弹窗——把 L-7 的三层验证（PoW / 图形码升级 / 冷却）显性化为独立交互。
 * 协议零改动：完全复用 GET /auth/challenge 与登录携带 challengeId/nonce/captchaAnswer 的既有契约。
 * 关闭弹窗 = 中止本次登录（由宿主处理 cancel）。
 */
const props = defineProps<{
  open: boolean
  username?: string
}>()

const emit = defineEmits<{
  (e: 'verified', verification: LoginVerification): void
  (e: 'cancel'): void
}>()

type Stage = 'idle' | 'solving' | 'captcha' | 'done' | 'cooldown' | 'error'

const stage = ref<Stage>('idle')
const progress = ref(0)
const captchaImage = ref('')
const captchaAnswer = ref('')
const noticeText = ref('')
const cooldownSeconds = ref(0)

const modalEl = ref<HTMLElement | null>(null)
const startBtn = ref<HTMLButtonElement | null>(null)

let challenge: LoginChallenge | null = null
let solvedNonce = ''
let progressTimer: number | undefined
let cooldownTimer: number | undefined
let doneTimer: number | undefined

function clearTimers() {
  window.clearInterval(progressTimer)
  window.clearInterval(cooldownTimer)
  window.clearTimeout(doneTimer)
}

function resetToIdle() {
  clearTimers()
  stage.value = 'idle'
  progress.value = 0
  captchaImage.value = ''
  captchaAnswer.value = ''
  noticeText.value = ''
  cooldownSeconds.value = 0
  challenge = null
  solvedNonce = ''
}

watch(() => props.open, (open) => {
  if (open) {
    resetToIdle()
    void nextTick(() => startBtn.value?.focus())
  } else {
    clearTimers()
  }
})

/** PoW 无法上报真实进度——以趋近 90% 的缓动进度呈现计算在途，求解结束补满。 */
function startProgress() {
  progress.value = 6
  progressTimer = window.setInterval(() => {
    progress.value = Math.min(90, progress.value + Math.max(1, (90 - progress.value) * 0.08))
  }, 120)
}

async function start() {
  noticeText.value = ''
  stage.value = 'solving'
  startProgress()
  try {
    challenge = await fetchLoginChallenge(props.username?.trim() || undefined)
    solvedNonce = await solvePow(challenge.salt, challenge.difficulty)
    window.clearInterval(progressTimer)
    progress.value = 100
    if (challenge.type === 'IMAGE') {
      captchaImage.value = challenge.captchaImage ?? ''
      stage.value = 'captcha'
      void nextTick(() => modalEl.value?.querySelector<HTMLInputElement>('#verify-captcha-answer')?.focus())
    } else {
      finish()
    }
  } catch (cause) {
    handleFailure(cause)
  }
}

function finish(answer?: string) {
  stage.value = 'done'
  const payload: LoginVerification = {
    challengeId: challenge!.challengeId,
    nonce: solvedNonce,
    ...(answer ? { captchaAnswer: answer } : {}),
  }
  // 打勾动画停留后回传，宿主随即发起登录
  doneTimer = window.setTimeout(() => emit('verified', payload), 500)
}

function submitCaptcha() {
  if (!captchaAnswer.value.trim()) {
    noticeText.value = '请输入图中字符后再继续。'
    return
  }
  finish(captchaAnswer.value.trim())
}

/** 图形码换一张：challenge 一次性使用，需重新取号并重解 PoW。 */
function refreshCaptcha() {
  captchaAnswer.value = ''
  void start()
}

function handleFailure(cause: unknown) {
  window.clearInterval(progressTimer)
  progress.value = 0
  if (axios.isAxiosError(cause) && cause.response?.status === 429) {
    const retryAfterRaw = cause.response.headers?.['retry-after']
    const retryAfter = Number(Array.isArray(retryAfterRaw) ? retryAfterRaw[0] : retryAfterRaw)
    const message = (cause.response.data as { message?: string } | undefined)?.message
    noticeText.value = message || '尝试过于频繁，已进入冷却。'
    cooldownSeconds.value = Number.isFinite(retryAfter) && retryAfter > 0 ? Math.round(retryAfter) : 0
    stage.value = 'cooldown'
    if (cooldownSeconds.value > 0) {
      cooldownTimer = window.setInterval(() => {
        cooldownSeconds.value -= 1
        if (cooldownSeconds.value <= 0) {
          window.clearInterval(cooldownTimer)
          stage.value = 'idle'
          noticeText.value = ''
        }
      }, 1000)
    }
  } else {
    noticeText.value = '安全校验暂时不可用，请检查网络后重试。'
    stage.value = 'error'
  }
}

function cancel() {
  clearTimers()
  emit('cancel')
}

/** 焦点陷阱：Tab 循环限制在弹窗内；Esc 关闭（= 中止登录）。 */
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.stopPropagation()
    cancel()
    return
  }
  if (event.key !== 'Tab' || !modalEl.value) return
  const focusables = Array.from(modalEl.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), input:not([disabled])'
  ))
  if (focusables.length === 0) return
  const first = focusables[0]
  const last = focusables[focusables.length - 1]
  const active = document.activeElement
  if (event.shiftKey && active === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

onBeforeUnmount(clearTimers)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="verify-overlay" @click.self="cancel">
      <div
        ref="modalEl"
        class="verify-modal"
        role="dialog"
        aria-modal="true"
        aria-label="人机验证"
        @keydown="onKeydown"
      >
        <header class="verify-head">
          <span class="verify-kicker">SECURITY CHECK</span>
          <h3>确认你不是机器人</h3>
          <button type="button" class="verify-close" aria-label="关闭并中止登录" @click="cancel">×</button>
        </header>

        <!-- aria-live：状态机各阶段的可听化播报 -->
        <p class="sr-only" aria-live="polite">
          {{ stage === 'solving' ? '安全校验计算中' : stage === 'captcha' ? '需要输入图形验证码' : stage === 'done' ? '验证通过' : stage === 'cooldown' ? '已进入冷却' : '' }}
        </p>

        <div v-if="stage === 'idle' || stage === 'error'" class="verify-body">
          <button ref="startBtn" type="button" class="verify-start" @click="start">
            <span class="verify-box" aria-hidden="true" />
            我不是机器人
          </button>
          <p v-if="noticeText" class="verify-notice" role="alert">{{ noticeText }}</p>
        </div>

        <div v-else-if="stage === 'solving'" class="verify-body">
          <div class="verify-progress" role="progressbar" :aria-valuenow="Math.round(progress)" aria-valuemin="0" aria-valuemax="100">
            <i :style="{ width: `${progress}%` }" />
          </div>
          <p class="verify-hint">正在完成安全校验，请稍候…</p>
        </div>

        <div v-else-if="stage === 'captcha'" class="verify-body">
          <p class="verify-hint">再确认一步：输入下图字符（不区分大小写）。</p>
          <button type="button" class="verify-captcha-image" title="看不清？点击换一张" @click="refreshCaptcha">
            <img v-if="captchaImage" :src="captchaImage" alt="图形验证码，点击可更换">
          </button>
          <div class="verify-captcha-row">
            <input
              id="verify-captcha-answer"
              v-model="captchaAnswer"
              autocomplete="off"
              inputmode="text"
              placeholder="输入图中字符"
              @keydown.enter.prevent="submitCaptcha"
            >
            <button type="button" class="verify-secondary" @click="refreshCaptcha">换一张</button>
            <button type="button" class="verify-primary" @click="submitCaptcha">确认</button>
          </div>
          <p v-if="noticeText" class="verify-notice" role="alert">{{ noticeText }}</p>
        </div>

        <div v-else-if="stage === 'done'" class="verify-body verify-done">
          <span class="verify-check" aria-hidden="true">✓</span>
          <p class="verify-hint">验证通过，正在继续登录…</p>
        </div>

        <div v-else-if="stage === 'cooldown'" class="verify-body">
          <p class="verify-notice" role="alert">{{ noticeText }}</p>
          <p v-if="cooldownSeconds > 0" class="verify-cooldown">
            冷却剩余 <strong>{{ cooldownSeconds }}</strong> 秒
          </p>
          <button v-else type="button" class="verify-secondary" @click="stage = 'idle'">重新验证</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.verify-overlay {
  position: fixed;
  inset: 0;
  z-index: 1400;
  display: grid;
  place-items: center;
  background: rgba(20, 17, 14, 0.45);
  backdrop-filter: blur(6px);
}
.verify-modal {
  width: min(400px, calc(100vw - 40px));
  padding: 26px;
  border-radius: 22px;
  background: var(--surface-solid);
  border: 1px solid var(--line-strong);
  box-shadow: var(--shadow-lg);
  animation: verify-pop 0.3s cubic-bezier(0.22, 1.2, 0.36, 1);
}
@keyframes verify-pop {
  from { opacity: 0; transform: translateY(14px) scale(0.96); }
  to { opacity: 1; transform: none; }
}
.verify-head {
  position: relative;
  margin-bottom: 18px;
}
.verify-kicker {
  font: 600 10px ui-monospace, Consolas, monospace;
  letter-spacing: 0.16em;
  color: var(--accent);
}
.verify-head h3 {
  margin: 4px 0 0;
  font-size: 18px;
  color: var(--ink);
}
.verify-close {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--muted);
  font-size: 20px;
  cursor: pointer;
}
.verify-close:hover { color: var(--accent); }

.verify-body { display: flex; flex-direction: column; gap: 14px; }

.verify-start {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
  border-radius: 14px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  color: var(--ink);
  font-size: 15px;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.verify-start:hover,
.verify-start:focus-visible {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 20%, transparent);
}
.verify-box {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  border: 2px solid var(--line-strong);
  background: var(--surface-solid);
}

.verify-progress {
  height: 10px;
  border-radius: 999px;
  background: var(--surface);
  border: 1px solid var(--line);
  overflow: hidden;
}
.verify-progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--accent);
  transition: width 0.15s linear;
}
.verify-hint { margin: 0; font-size: 13px; color: var(--muted); }
.verify-notice { margin: 0; font-size: 13px; color: #b4452c; }
.verify-cooldown { margin: 0; font-size: 14px; color: var(--ink); }

.verify-captcha-image {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 0;
  background: var(--surface);
  cursor: pointer;
  overflow: hidden;
}
.verify-captcha-image img { display: block; width: 100%; }
.verify-captcha-row { display: flex; gap: 8px; }
.verify-captcha-row input {
  flex: 1;
  min-width: 0;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  color: var(--ink);
}
.verify-primary,
.verify-secondary {
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 13px;
  cursor: pointer;
}
.verify-primary {
  border: none;
  background: var(--accent);
  color: #fff;
}
.verify-secondary {
  border: 1px solid var(--line-strong);
  background: transparent;
  color: var(--ink);
}

.verify-done { align-items: center; }
.verify-check {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  border-radius: 50%;
  background: color-mix(in srgb, #2e9e5b 16%, var(--surface));
  color: #2e9e5b;
  font-size: 28px;
  animation: check-pop 0.4s cubic-bezier(0.22, 1.4, 0.36, 1);
}
@keyframes check-pop {
  from { transform: scale(0.4); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

@media (prefers-reduced-motion: reduce) {
  .verify-modal, .verify-check { animation: none; }
  .verify-progress i { transition: none; }
}
</style>
