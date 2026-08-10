<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';

const props = defineProps<{
  open: boolean;
  submitting: boolean;
  error?: string;
}>();

const emit = defineEmits<{
  (e: 'submit', code: string): void;
  (e: 'cancel'): void;
}>();

const code = ref('');
const inputEl = ref<HTMLInputElement | null>(null);

watch(
  () => props.open,
  (open) => {
    if (open) {
      code.value = '';
      void nextTick(() => inputEl.value?.focus());
    }
  },
);

function onInput() {
  code.value = code.value.replace(/\D/g, '').slice(0, 6);
  if (code.value.length === 6) {
    emit('submit', code.value);
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    emit('cancel');
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="totp-overlay" @click.self="emit('cancel')">
      <div class="totp-modal" role="dialog" aria-modal="true" aria-label="两步验证" @keydown="onKeydown">
        <header class="totp-head">
          <span class="totp-kicker">TWO-FACTOR AUTH</span>
          <h3>输入身份验证器中的验证码</h3>
          <button type="button" class="totp-close" aria-label="取消" @click="emit('cancel')">×</button>
        </header>
        <div class="totp-body">
          <p class="totp-hint">请在身份验证应用中输入当前显示的 6 位验证码。</p>
          <p v-if="error" class="totp-error" role="alert">{{ error }}</p>
          <div class="totp-input-row">
            <input
              ref="inputEl"
              v-model="code"
              type="text"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
              placeholder="000000"
              :disabled="submitting"
              @input="onInput"
            />
          </div>
          <div class="totp-actions">
            <button type="button" class="totp-secondary" :disabled="submitting" @click="emit('cancel')">
              取消
            </button>
            <button
              type="button"
              class="totp-primary"
              :disabled="code.length !== 6 || submitting"
              @click="emit('submit', code)"
            >
              {{ submitting ? '验证中…' : '验证' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.totp-overlay {
  position: fixed;
  inset: 0;
  z-index: 1500;
  display: grid;
  place-items: center;
  background: rgba(20, 17, 14, 0.45);
  backdrop-filter: blur(6px);
}
.totp-modal {
  width: min(380px, calc(100vw - 40px));
  padding: 26px;
  border-radius: 22px;
  background: var(--surface-solid);
  border: 1px solid var(--line-strong);
  box-shadow: var(--shadow-lg);
  animation: totp-pop 0.3s cubic-bezier(0.22, 1.2, 0.36, 1);
}
@keyframes totp-pop {
  from {
    opacity: 0;
    transform: translateY(14px) scale(0.96);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.totp-head {
  position: relative;
  margin-bottom: 18px;
}
.totp-kicker {
  font:
    600 10px ui-monospace,
    Consolas,
    monospace;
  letter-spacing: 0.16em;
  color: var(--accent);
}
.totp-head h3 {
  margin: 4px 0 0;
  font-size: 18px;
  color: var(--ink);
}
.totp-close {
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
.totp-close:hover {
  color: var(--accent);
}
.totp-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.totp-hint {
  margin: 0;
  font-size: 13px;
  color: var(--muted);
}
.totp-error {
  margin: 0;
  color: #b84f48;
  font-size: 13px;
}
.totp-input-row input {
  width: 100%;
  padding: 14px;
  border-radius: 12px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  color: var(--ink);
  font-size: 28px;
  font-family: ui-monospace, Consolas, monospace;
  letter-spacing: 8px;
  text-align: center;
  outline: none;
  transition: border-color 0.2s;
}
.totp-input-row input:focus {
  border-color: var(--accent);
}
.totp-input-row input:disabled {
  opacity: 0.5;
}
.totp-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}
.totp-primary,
.totp-secondary {
  padding: 10px 18px;
  border-radius: 10px;
  font-size: 14px;
  cursor: pointer;
}
.totp-primary {
  border: none;
  background: var(--accent);
  color: #fff;
}
.totp-primary:disabled {
  opacity: 0.4;
  cursor: default;
}
.totp-secondary {
  border: 1px solid var(--line-strong);
  background: transparent;
  color: var(--ink);
}
@media (prefers-reduced-motion: reduce) {
  .totp-modal {
    animation: none;
  }
}
</style>
