<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import type { Dish } from '../../data';
import { useFocusTrap } from '../../composables/useFocusTrap';
import { usePrefersReducedMotion } from '../../composables/usePrefersReducedMotion';

const props = defineProps<{
  dishes: Dish[];
  /** 注入随机源以便测试确定性；缺省 Math.random */
  rng?: () => number;
}>();
const emit = defineEmits<{ close: []; open: [dish: Dish] }>();

const SPIN_MS = 1600;

const dialogRoot = ref<HTMLElement | null>(null);
const spinRef = ref<HTMLButtonElement | null>(null);
const phase = ref<'idle' | 'spinning' | 'settled'>('idle');
const result = ref<Dish | null>(null);
const reduceMotion = usePrefersReducedMotion();
let spinTimer: number | undefined;

const canSpin = computed(() => props.dishes.length > 0 && phase.value !== 'spinning');
// 滚轮视觉是纯装饰：结果在点击瞬间已同步定死，动画只负责期待感
const reelNames = computed(() => {
  const names = props.dishes.map((dish) => dish.name);
  return names.length ? [...names, ...names, ...names] : [];
});

function draw() {
  if (!canSpin.value) return;
  const random = (props.rng ?? Math.random)();
  const index = Math.min(props.dishes.length - 1, Math.max(0, Math.floor(random * props.dishes.length)));
  result.value = props.dishes[index];
  if (reduceMotion.value) {
    phase.value = 'settled';
    return;
  }
  phase.value = 'spinning';
  window.clearTimeout(spinTimer);
  spinTimer = window.setTimeout(() => {
    phase.value = 'settled';
  }, SPIN_MS);
}

function onWindowKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close');
}

useFocusTrap(dialogRoot);

onMounted(async () => {
  window.addEventListener('keydown', onWindowKeydown);
  document.body.style.overflow = 'hidden';
  spinRef.value?.focus();
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown);
  window.clearTimeout(spinTimer);
  document.body.style.removeProperty('overflow');
});
</script>

<template>
  <Teleport to="body">
    <div class="roulette-backdrop" @click.self="emit('close')">
      <div
        ref="dialogRoot"
        class="roulette-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="roulette-title"
      >
        <button class="roulette-close tap-44" type="button" aria-label="关闭抽卡" @click="emit('close')">
          ×
        </button>
        <p class="roulette-kicker">TASTE DICE</p>
        <h2 id="roulette-title">今天吃什么？</h2>

        <p v-if="!dishes.length" class="roulette-empty">这里还没有可抽的菜——先去把菜谱库填满一点吧。</p>

        <template v-else>
          <div v-if="phase !== 'settled'" class="roulette-reel" aria-hidden="true">
            <ul :class="{ spinning: phase === 'spinning' }">
              <li v-for="(name, index) in reelNames" :key="index">{{ name }}</li>
            </ul>
            <i class="reel-window" />
          </div>

          <div v-else-if="result" class="roulette-result">
            <img :src="result.imageUrl" :alt="result.imageAlt" loading="lazy" />
            <div class="roulette-result-copy">
              <small>{{ result.category }} · {{ result.prepMinutes }} 分钟 · {{ result.difficulty }}</small>
              <strong>{{ result.name }}</strong>
              <span>{{ result.summary }}</span>
            </div>
          </div>

          <p class="roulette-status" role="status">
            {{
              phase === 'settled' && result
                ? `抽到了：${result.name}`
                : phase === 'spinning'
                  ? '命运的滚轮转起来了…'
                  : ''
            }}
          </p>
        </template>

        <div class="roulette-actions">
          <button
            v-if="phase !== 'settled'"
            ref="spinRef"
            class="roulette-spin tap-44"
            type="button"
            :disabled="!canSpin"
            @click="draw"
          >
            {{ phase === 'spinning' ? '转着呢…' : '开抽！' }}
          </button>
          <template v-else>
            <button class="roulette-open tap-44" type="button" @click="result && emit('open', result)">
              就吃这道 ↗
            </button>
            <button class="roulette-again tap-44" type="button" @click="draw">再抽一次</button>
          </template>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.roulette-backdrop {
  position: fixed;
  z-index: 2300;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 20px;
  background: var(--cinema-backdrop);
  backdrop-filter: blur(8px);
}
.roulette-dialog {
  position: relative;
  width: min(460px, 100%);
  padding: clamp(26px, 5vw, 40px);
  color: var(--ink);
  background: var(--surface-solid);
  border: 1px solid color-mix(in srgb, var(--accent) 22%, var(--line));
  border-radius: 28px 28px 9px 28px;
  box-shadow: var(--shadow-lg);
  animation: roulette-in 0.5s var(--ease-out) both;
}
.roulette-close {
  position: absolute;
  top: 14px;
  right: 14px;
  display: grid;
  place-items: center;
  color: var(--muted);
  font-size: 1.2rem;
  background: transparent;
  border: 1px solid var(--line);
  border-radius: 50%;
  cursor: pointer;
  transition:
    color 0.2s,
    border-color 0.2s;
}
.roulette-close:hover {
  color: var(--ink);
  border-color: var(--line-strong);
}
.roulette-kicker {
  margin: 0;
  color: var(--accent);
  font:
    650 0.64rem/1 ui-monospace,
    'SF Mono',
    Consolas,
    monospace;
  letter-spacing: 0.17em;
}
.roulette-dialog h2 {
  margin: 10px 0 22px;
  font:
    400 clamp(1.9rem, 4vw, 2.6rem)/1.05 Georgia,
    'Songti SC',
    serif;
  letter-spacing: -0.04em;
}
.roulette-empty {
  margin: 18px 0 6px;
  color: var(--muted);
  line-height: 1.7;
}
.roulette-reel {
  position: relative;
  height: 168px;
  margin-bottom: 20px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 16px 16px 5px 16px;
  background: color-mix(in srgb, var(--accent-soft) 40%, transparent);
}
.roulette-reel ul {
  margin: 0;
  padding: 0;
  list-style: none;
}
.roulette-reel li {
  display: grid;
  place-items: center;
  height: 56px;
  font:
    500 1.15rem/1 Georgia,
    'Songti SC',
    serif;
  color: var(--muted);
}
.roulette-reel ul.spinning {
  animation: reel-spin 0.38s linear infinite;
}
.reel-window {
  position: absolute;
  top: 56px;
  right: 10px;
  left: 10px;
  height: 56px;
  border: 1px dashed color-mix(in srgb, var(--accent) 55%, transparent);
  border-radius: 10px;
  pointer-events: none;
}
.roulette-result {
  display: grid;
  grid-template-columns: 108px 1fr;
  gap: 16px;
  align-items: center;
  margin-bottom: 16px;
  padding: 12px;
  border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--line));
  border-radius: 18px 18px 6px 18px;
  background: color-mix(in srgb, var(--accent-soft) 46%, transparent);
  animation: roulette-settle 0.45s cubic-bezier(0.175, 0.885, 0.32, 1.275) both;
}
.roulette-result img {
  width: 108px;
  height: 96px;
  object-fit: cover;
  border-radius: 12px 12px 4px 12px;
}
.roulette-result-copy {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}
.roulette-result-copy small {
  color: var(--faint);
  font-size: 0.68rem;
  letter-spacing: 0.05em;
}
.roulette-result-copy strong {
  font:
    520 1.4rem/1.2 Georgia,
    'Songti SC',
    serif;
  letter-spacing: -0.03em;
}
.roulette-result-copy span {
  display: -webkit-box;
  overflow: hidden;
  color: var(--muted);
  font-size: 0.82rem;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.roulette-status {
  min-height: 1.2em;
  margin: 0 0 14px;
  color: var(--muted);
  font-size: 0.82rem;
}
.roulette-actions {
  display: flex;
  gap: 10px;
}
.roulette-actions button {
  flex: 1;
  padding: 12px 16px;
  font-size: 0.92rem;
  font-weight: 600;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.roulette-spin,
.roulette-open {
  color: #fff;
  background: var(--accent);
  border: 1px solid transparent;
  box-shadow: 0 8px 22px color-mix(in srgb, var(--accent) 35%, transparent);
}
.roulette-spin:hover:not(:disabled),
.roulette-open:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 30px color-mix(in srgb, var(--accent) 45%, transparent);
  filter: brightness(1.05);
}
.roulette-spin:active:not(:disabled),
.roulette-open:active {
  transform: translateY(1px) scale(0.97);
  box-shadow: 0 3px 10px color-mix(in srgb, var(--accent) 25%, transparent);
}
.roulette-spin:disabled {
  opacity: 0.6;
  cursor: default;
  box-shadow: none;
  transform: none;
}
.roulette-again {
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line-strong);
}
.roulette-again:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 8%, var(--surface));
  transform: translateY(-2px);
  box-shadow: 0 6px 18px color-mix(in srgb, var(--accent) 15%, transparent);
}
.roulette-again:active {
  transform: translateY(1px) scale(0.97);
  box-shadow: none;
}
.roulette-dialog :focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 45%, transparent);
}
@keyframes roulette-in {
  from {
    opacity: 0;
    transform: translateY(18px) scale(0.97);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
@keyframes reel-spin {
  from {
    transform: translateY(0);
  }
  to {
    transform: translateY(-168px);
  }
}
@keyframes roulette-settle {
  from {
    opacity: 0;
    transform: scale(0.92);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}
@media (prefers-reduced-motion: reduce) {
  .roulette-dialog,
  .roulette-result {
    animation: none;
  }
  .roulette-reel ul.spinning {
    animation: none;
  }
  .roulette-actions button {
    transition-duration: 0.01ms;
  }
}
</style>
