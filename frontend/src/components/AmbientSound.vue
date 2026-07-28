<script setup lang="ts">
import { ref, computed, onBeforeUnmount, onMounted } from 'vue'
import { fetchMusicTracks } from '../api/content'

interface Track {
  id: string | number
  title: string
  artist: string
  duration?: number
  audioUrl: string
  coverUrl?: string
}

// Built-in fallback light music tracks (Instrumental / Piano / Acoustic)
const fallbackTracks: Track[] = [
  {
    id: 'track-1',
    title: '雨的印记 (Kiss the Rain)',
    artist: '钢琴纯音乐',
    audioUrl: 'https://cdn.pixabay.com/download/audio/2022/05/27/audio_1808fbf07a.mp3?filename=soft-piano-10988.mp3',
  },
  {
    id: 'track-2',
    title: '安妮的仙境 (Annie\'s Wonderland)',
    artist: '舒缓吉他与长笛',
    audioUrl: 'https://cdn.pixabay.com/download/audio/2022/03/15/audio_c8c8a73467.mp3?filename=relaxing-light-music-10874.mp3',
  },
  {
    id: 'track-3',
    title: '静谧森林 (Forest Acoustic)',
    artist: '自然轻音乐',
    audioUrl: 'https://cdn.pixabay.com/download/audio/2022/01/18/audio_d0a13f69d2.mp3?filename=ambient-piano-10781.mp3',
  },
]

const tracks = ref<Track[]>(fallbackTracks)
const currentTrackIndex = ref(0)
const isPlaying = ref(false)
const volume = ref(0.4)
const isOpen = ref(false)
const isLoading = ref(false)

let audioEl: HTMLAudioElement | null = null

const currentTrack = computed(() => tracks.value[currentTrackIndex.value] || fallbackTracks[0])

// NF-7：改走统一 api 层（错误处理与 baseURL 一致化），不再组件内裸 fetch
async function fetchRemoteTracks() {
  try {
    const data = await fetchMusicTracks()
    if (Array.isArray(data) && data.length > 0) {
      tracks.value = data
    }
  } catch {
    // Keep fallback tracks on error
  }
}

function initAudio() {
  if (!audioEl) {
    audioEl = new Audio()
    audioEl.volume = volume.value
    // NF-11：loop=true 会困在单曲循环——改为播完自动切下一首
    audioEl.loop = false
    audioEl.onended = () => { nextTrack() }
    audioEl.onwaiting = () => { isLoading.value = true }
    audioEl.oncanplay = () => { isLoading.value = false }
    audioEl.onerror = () => {
      isLoading.value = false
      isPlaying.value = false
    }
  }
}

function playTrack(index: number) {
  currentTrackIndex.value = index
  initAudio()
  if (!audioEl) return

  const target = tracks.value[index]
  if (audioEl.src !== target.audioUrl) {
    audioEl.src = target.audioUrl
  }

  isLoading.value = true
  audioEl.play().then(() => {
    isPlaying.value = true
    isLoading.value = false
  }).catch(() => {
    isPlaying.value = false
    isLoading.value = false
  })
}

function togglePlay() {
  if (isPlaying.value) {
    isPlaying.value = false
    if (audioEl) audioEl.pause()
  } else {
    playTrack(currentTrackIndex.value)
  }
}

function prevTrack() {
  const next = (currentTrackIndex.value - 1 + tracks.value.length) % tracks.value.length
  playTrack(next)
}

function nextTrack() {
  const next = (currentTrackIndex.value + 1) % tracks.value.length
  playTrack(next)
}

function updateVolume(val: number) {
  volume.value = val
  if (audioEl) {
    audioEl.volume = val
  }
}

onMounted(() => {
  void fetchRemoteTracks()
})

onBeforeUnmount(() => {
  if (audioEl) {
    audioEl.pause()
    audioEl.src = ''
    audioEl = null
  }
})
</script>

<template>
  <div class="ambient-music-widget" :class="{ open: isOpen }">
    <!-- Prominent Top-Right Vinyl Player Trigger Button (48px) -->
    <button
      type="button"
      class="ambient-music-trigger"
      :class="{ playing: isPlaying, loading: isLoading }"
      :title="isPlaying ? `正在播放：${currentTrack.title}` : '打开舒缓轻音乐播放器'"
      :aria-label="isPlaying ? `音乐播放器，正在播放：${currentTrack.title}` : '打开音乐播放器'"
      :aria-expanded="isOpen"
      @click="isOpen = !isOpen"
    >
      <div class="vinyl-disc" :class="{ spinning: isPlaying }" aria-hidden="true">
        <span class="vinyl-ring" />
        <span class="vinyl-center">🎵</span>
      </div>
      <div v-if="isPlaying" class="sound-wave-bars" aria-hidden="true">
        <span /><span /><span />
      </div>
    </button>

    <!-- Expanded Floating Light Music Panel -->
    <div v-if="isOpen" class="ambient-music-panel">
      <header class="panel-header">
        <div class="header-title">
          <span class="music-kicker">✦ AMBIENT LIGHT MUSIC</span>
          <h4>舒缓轻音乐原声</h4>
        </div>
        <button type="button" class="close-btn" aria-label="关闭播放面板" @click="isOpen = false">×</button>
      </header>

      <!-- Current Track Info Display -->
      <div class="current-track-card">
        <div class="track-vinyl-thumb" :class="{ spinning: isPlaying }">
          <span>🎼</span>
        </div>
        <div class="track-details">
          <strong class="track-name">{{ currentTrack.title }}</strong>
          <small class="track-artist">{{ currentTrack.artist }}</small>
        </div>
      </div>

      <!-- Playback Controls (Prev, Play/Pause, Next) -->
      <div class="playback-controls">
        <button type="button" class="ctrl-btn" title="上一首" aria-label="上一首" @click="prevTrack">⏮</button>
        <button type="button" class="play-btn" :class="{ active: isPlaying }" title="播放/暂停" :aria-label="isPlaying ? '暂停' : '播放'" @click="togglePlay">
          {{ isLoading ? '⌛' : (isPlaying ? '⏸' : '▶') }}
        </button>
        <button type="button" class="ctrl-btn" title="下一首" aria-label="下一首" @click="nextTrack">⏭</button>
      </div>

      <!-- Track List Selector -->
      <div class="track-list">
        <button
          v-for="(t, idx) in tracks"
          :key="t.id"
          type="button"
          class="track-item"
          :class="{ active: currentTrackIndex === idx }"
          @click="playTrack(idx)"
        >
          <span class="track-idx">0{{ idx + 1 }}</span>
          <div class="track-item-info">
            <strong>{{ t.title }}</strong>
            <small>{{ t.artist }}</small>
          </div>
          <span v-if="currentTrackIndex === idx && isPlaying" class="playing-indicator">▶</span>
        </button>
      </div>

      <!-- Volume Bar -->
      <div class="volume-bar">
        <span aria-hidden="true">🔈</span>
        <input
          type="range"
          min="0"
          max="1"
          step="0.05"
          :value="volume"
          aria-label="音量"
          @input="updateVolume(parseFloat(($event.target as HTMLInputElement).value))"
        >
        <span aria-hidden="true">🔊</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ambient-music-widget {
  position: relative;
  display: inline-flex;
  align-items: center;
  z-index: 500;
}

/* Header Navbar Music Trigger Button (40px) */
.ambient-music-trigger {
  position: relative;
  width: 40px;
  height: 40px;
  padding: 0;
  border-radius: 50%;
  background: var(--surface);
  border: 1px solid var(--line-strong);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.28);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  backdrop-filter: blur(14px);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.ambient-music-trigger:hover {
  transform: translateY(-2px);
  border-color: var(--accent);
  background: var(--surface-solid);
  box-shadow: 0 4px 14px color-mix(in srgb, var(--accent) 25%, transparent);
}
.ambient-music-trigger:active {
  transform: translateY(1px) scale(0.96);
  box-shadow: none;
}
.ambient-music-trigger.playing {
  border-color: var(--accent);
  box-shadow: 0 0 16px color-mix(in srgb, var(--accent) 45%, transparent);
}

.vinyl-disc {
  position: relative;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: radial-gradient(circle, #2a2829 35%, #181617 70%, #0d0c0d 100%);
  display: flex;
  align-items: center;
  justify-content: center;
}
.vinyl-disc.spinning {
  animation: spin-record 8s linear infinite;
}
@keyframes spin-record {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.vinyl-ring {
  position: absolute;
  inset: 4px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.15);
  pointer-events: none;
}
.vinyl-center {
  font-size: 13px;
}

/* Dynamic Soundwave Bars badge */
.sound-wave-bars {
  position: absolute;
  bottom: -3px;
  right: -3px;
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 12px;
  padding: 2px 3px;
  border-radius: 999px;
  background: var(--accent);
}
.sound-wave-bars span {
  width: 2px;
  height: 100%;
  background: #fff;
  border-radius: 2px;
  animation: wave-bar-bounce 1s infinite ease-in-out;
}
.sound-wave-bars span:nth-child(2) { animation-delay: 0.2s; }
.sound-wave-bars span:nth-child(3) { animation-delay: 0.4s; }
@keyframes wave-bar-bounce {
  0%, 100% { height: 25%; }
  50% { height: 100%; }
}

/* Expanded Light Music Control Panel */
.ambient-music-panel {
  position: absolute;
  top: calc(100% + 10px);
  right: 0;
  width: min(310px, 90vw);
  padding: 22px;
  border-radius: 24px;
  background: var(--surface-solid);
  border: 1px solid var(--line-strong);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.22);
  color: var(--ink);
  animation: panel-fade-down 0.28s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes panel-fade-down {
  from { opacity: 0; transform: translateY(-10px) scale(0.95); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.music-kicker {
  color: var(--accent);
  font: 600 10px ui-monospace, Consolas, monospace;
  letter-spacing: 0.15em;
  display: block;
}
.header-title h4 {
  margin: 2px 0 0;
  font-size: 16px;
  font-weight: 600;
}
.close-btn {
  background: none;
  border: none;
  font-size: 20px;
  color: var(--muted);
  cursor: pointer;
  line-height: 1;
}
.close-btn:hover {
  color: var(--accent);
}

.current-track-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 14px;
  border-radius: 16px;
  background: var(--surface);
  border: 1px solid var(--line);
  margin-bottom: 16px;
}
.track-vinyl-thumb {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: radial-gradient(circle, var(--accent) 30%, #2a2829 70%);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}
.track-vinyl-thumb.spinning {
  animation: spin-record 8s linear infinite;
}
.track-details {
  flex: 1;
  min-width: 0;
}
.track-name {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.track-artist {
  display: block;
  font-size: 12px;
  color: var(--muted);
  margin-top: 2px;
}

.playback-controls {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-bottom: 18px;
}
.ctrl-btn {
  background: none;
  border: none;
  font-size: 18px;
  color: var(--muted);
  cursor: pointer;
  transition: color 0.2s, transform 0.2s;
}
.ctrl-btn:hover {
  color: var(--ink);
  transform: scale(1.15);
}
.play-btn {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--surface);
  border: 1px solid var(--line-strong);
  color: var(--ink);
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.25s;
}
.play-btn:hover {
  border-color: var(--accent);
  transform: scale(1.08);
}
.play-btn.active {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
  box-shadow: 0 4px 16px color-mix(in srgb, var(--accent) 40%, transparent);
}

.track-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 160px;
  overflow-y: auto;
  margin-bottom: 16px;
  padding-right: 4px;
}
.track-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 12px;
  background: transparent;
  border: 1px solid transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.2s, border-color 0.2s;
}
.track-item:hover {
  background: var(--surface);
}
.track-item.active {
  background: var(--surface);
  border-color: var(--accent);
}
.track-idx {
  font: 700 12px Georgia, serif;
  color: var(--accent);
  width: 18px;
}
.track-item-info {
  flex: 1;
  min-width: 0;
}
.track-item-info strong {
  display: block;
  font-size: 13px;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.track-item-info small {
  font-size: 11px;
  color: var(--muted);
}
.playing-indicator {
  color: var(--accent);
  font-size: 11px;
}

.volume-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--muted);
}
.volume-bar input[type="range"] {
  flex: 1;
  accent-color: var(--accent);
}

@media (max-width: 640px) {
  .ambient-music-widget {
    top: 70px;
    right: 16px;
  }
}
</style>
