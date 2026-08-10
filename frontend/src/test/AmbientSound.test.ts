import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { flushPromises, mount, enableAutoUnmount } from '@vue/test-utils';
import AmbientSound from '../components/AmbientSound.vue';
import * as contentApi from '../api/content';

const mockFetchMusicTracks = vi.fn();
vi.mock('../api/content', async (importOriginal) => {
  const actual = await importOriginal<typeof contentApi>();
  return {
    ...actual,
    fetchMusicTracks: (...args: unknown[]) => mockFetchMusicTracks(...args),
  };
});

/** jsdom 无真实音频：注入 FakeAudio 捕获 src/play/pause 与事件回调。 */
class FakeAudio {
  volume = 0.4;
  loop = false;
  src = '';
  onended: (() => void) | null = null;
  onwaiting: (() => void) | null = null;
  oncanplay: (() => void) | null = null;
  onerror: (() => void) | null = null;
  play = vi.fn(() => Promise.resolve());
  pause = vi.fn();
}

let audioInstances: FakeAudio[] = [];

function track(id: string, audioUrl: string) {
  return { id, title: `曲目 ${id}`, artist: '测试', duration: 60, audioUrl, coverUrl: '' };
}

enableAutoUnmount(afterEach);

async function mountPlayer() {
  const wrapper = mount(AmbientSound);
  await flushPromises();
  // 打开播放面板（play-btn 等控件在 v-if="isOpen" 内）
  await wrapper.find('.ambient-music-trigger').trigger('click');
  return wrapper;
}

beforeEach(() => {
  audioInstances = [];
  vi.stubGlobal(
    'Audio',
    class extends FakeAudio {
      constructor() {
        super();
        audioInstances.push(this);
      }
    },
  );
  mockFetchMusicTracks.mockReset();
  mockFetchMusicTracks.mockResolvedValue([]);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('AmbientSound 音乐播放', () => {
  it('后端无曲目时使用内置自托管曲目（/audio/ 本地 WAV，确定可播）', async () => {
    const wrapper = await mountPlayer();
    await wrapper.find('.play-btn').trigger('click');
    await flushPromises();

    expect(audioInstances).toHaveLength(1);
    expect(audioInstances[0].src).toBe('/audio/calm-piano-1.wav');
    expect(audioInstances[0].play).toHaveBeenCalled();
    expect(wrapper.find('.play-btn').text()).toContain('⏸');
  });

  it('后端返回占位外链（cdn.example.com）时过滤掉，保留内置可播曲目', async () => {
    mockFetchMusicTracks.mockResolvedValue([
      track('t1', 'https://cdn.example.com/music/x.mp3'),
      track('t2', 'https://cdn.example.com/music/y.mp3'),
    ]);
    const wrapper = await mountPlayer();
    // 占位被过滤 → 列表仍是内置 3 首
    expect(wrapper.findAll('.track-item')).toHaveLength(3);

    await wrapper.find('.play-btn').trigger('click');
    await flushPromises();
    expect(audioInstances[0].src).toBe('/audio/calm-piano-1.wav');
  });

  it('后端返回可播放的真实曲目时替换内置列表', async () => {
    mockFetchMusicTracks.mockResolvedValue([track('real', 'https://cdn.test/music/real.mp3')]);
    const wrapper = await mountPlayer();
    expect(wrapper.findAll('.track-item')).toHaveLength(1);
    expect(wrapper.text()).toContain('曲目 real');

    await wrapper.find('.play-btn').trigger('click');
    await flushPromises();
    expect(audioInstances[0].src).toBe('https://cdn.test/music/real.mp3');
  });

  it('曲目加载失败（onerror）时自动跳下一首，且不超过曲目总数（有界）', async () => {
    const wrapper = await mountPlayer();
    await wrapper.find('.play-btn').trigger('click');
    await flushPromises();

    // 全部 3 首均失败：逐首跳过（2→3→1 回绕），count 达上限后停止，绝不无限循环
    const seen: string[] = [];
    for (let index = 0; index < 6; index += 1) {
      audioInstances[audioInstances.length - 1].onerror?.();
      seen.push(audioInstances[audioInstances.length - 1].src);
    }
    expect(seen).toEqual([
      '/audio/calm-piano-2.wav',
      '/audio/calm-piano-3.wav',
      '/audio/calm-piano-1.wav',
      '/audio/calm-piano-1.wav',
      '/audio/calm-piano-1.wav',
      '/audio/calm-piano-1.wav',
    ]);
    expect(audioInstances.length).toBe(1);
  });

  it('上一首/下一首切换重置失败计数并更新 src', async () => {
    const wrapper = await mountPlayer();
    await wrapper.find('.play-btn').trigger('click');
    await flushPromises();
    expect(audioInstances[0].src).toBe('/audio/calm-piano-1.wav');

    await wrapper.find('button[aria-label="下一首"]').trigger('click');
    await flushPromises();
    expect(audioInstances[audioInstances.length - 1].src).toBe('/audio/calm-piano-2.wav');

    await wrapper.find('button[aria-label="上一首"]').trigger('click');
    await flushPromises();
    expect(audioInstances[audioInstances.length - 1].src).toBe('/audio/calm-piano-1.wav');
  });

  it('暂停后再次播放恢复当前曲目', async () => {
    const wrapper = await mountPlayer();
    await wrapper.find('.play-btn').trigger('click');
    await flushPromises();
    await wrapper.find('.play-btn').trigger('click');
    await flushPromises();
    expect(audioInstances[0].pause).toHaveBeenCalled();
    expect(wrapper.find('.play-btn').text()).toContain('▶');

    await wrapper.find('.play-btn').trigger('click');
    await flushPromises();
    expect(audioInstances[audioInstances.length - 1].play).toHaveBeenCalled();
    expect(wrapper.find('.play-btn').text()).toContain('⏸');
  });

  it('音量滑块更新 audio 元素音量', async () => {
    const wrapper = await mountPlayer();
    await wrapper.find('.play-btn').trigger('click');
    await flushPromises();

    await wrapper.find('input[aria-label="音量"]').setValue('0.8');
    expect(audioInstances[0].volume).toBe(0.8);
  });
});
