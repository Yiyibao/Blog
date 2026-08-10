import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import AdminAiImagesPage from '../pages/AdminAiImagesPage.vue';
import * as adminApi from '../api/admin';

enableAutoUnmount(afterEach);

const mockFetchAiImageModels = vi.fn();
const mockFetchAiImageSessions = vi.fn();
const mockFetchAiImageSessionImages = vi.fn();
const mockFetchAiImageContent = vi.fn();
const mockGenerateAiImages = vi.fn();
const mockDeleteAiImageSession = vi.fn();
const mockDeleteAiGeneratedImage = vi.fn();

vi.mock('../api/admin', async (importOriginal) => {
  const actual = await importOriginal<typeof adminApi>();
  return {
    ...actual,
    fetchAiImageModels: (...args: unknown[]) => mockFetchAiImageModels(...args),
    fetchAiImageSessions: (...args: unknown[]) => mockFetchAiImageSessions(...args),
    fetchAiImageSessionImages: (...args: unknown[]) => mockFetchAiImageSessionImages(...args),
    fetchAiImageContent: (...args: unknown[]) => mockFetchAiImageContent(...args),
    generateAiImages: (...args: unknown[]) => mockGenerateAiImages(...args),
    deleteAiImageSession: (...args: unknown[]) => mockDeleteAiImageSession(...args),
    deleteAiGeneratedImage: (...args: unknown[]) => mockDeleteAiGeneratedImage(...args),
  };
});

const SESSION_ID = 11;
const GENERATION_ID = 'd7c68fa8-9a6b-4b7a-bf4e-000000000001';
const IMAGE_PUBLIC_ID = 'd7c68fa8-9a6b-4b7a-bf4e-000000000002';

function imageOf(publicId: string, generationId: string, prompt: string) {
  return {
    publicId,
    generationId,
    provider: 'grok' as const,
    model: 'grok-imagine-image-quality',
    prompt,
    mediaType: 'image/png',
    byteSize: 1234,
    width: 1024,
    height: 1024,
    contentPath: '/content',
    createdAt: '2026-08-04T01:00:00Z',
  };
}

function sessionOf(id: number, title: string | null) {
  return { id, title, createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T01:00:00Z' };
}

async function mountPage() {
  const wrapper = mount(AdminAiImagesPage, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  mockFetchAiImageModels.mockReset();
  mockFetchAiImageSessions.mockReset();
  mockFetchAiImageSessionImages.mockReset();
  mockFetchAiImageContent.mockReset();
  mockGenerateAiImages.mockReset();
  mockDeleteAiImageSession.mockReset();
  mockDeleteAiGeneratedImage.mockReset();
  mockFetchAiImageModels.mockResolvedValue([
    { provider: 'grok', model: 'grok-imagine-image-quality', isDefault: true },
  ]);
  mockFetchAiImageSessions.mockResolvedValue([]);
  mockFetchAiImageContent.mockResolvedValue(new Blob(['fake'], { type: 'image/png' }));
});

describe('AdminAiImagesPage 会话侧边栏', () => {
  it('渲染会话列表与新建按钮', async () => {
    mockFetchAiImageSessions.mockResolvedValue([sessionOf(1, '雨后的西湖'), sessionOf(2, null)]);
    const wrapper = await mountPage();

    expect(wrapper.text()).toContain('新建图片');
    expect(wrapper.text()).toContain('雨后的西湖');
    expect(wrapper.text()).toContain('新对话');
  });

  it('侧边栏可向左隐藏、向右拉出', async () => {
    const wrapper = await mountPage();

    expect(wrapper.find('.image-chat-panel').exists()).toBe(true);
    expect(wrapper.find('.image-chat-panel').classes()).not.toContain('hidden');

    await wrapper.find('.sidebar-toggle').trigger('click');
    expect(wrapper.find('.image-chat-panel').classes()).toContain('hidden');

    await wrapper.find('.sidebar-toggle').trigger('click');
    expect(wrapper.find('.image-chat-panel').classes()).not.toContain('hidden');
  });

  it('新建图片清空当前对话', async () => {
    mockFetchAiImageSessions.mockResolvedValue([sessionOf(1, '第一轮')]);
    mockFetchAiImageSessionImages.mockResolvedValue([
      imageOf(IMAGE_PUBLIC_ID, GENERATION_ID, '第一轮提示词'),
    ]);
    const wrapper = await mountPage();
    await wrapper.find('.session-entry').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('第一轮提示词');

    await wrapper.find('.new-chat-btn').trigger('click');
    expect(wrapper.find('.chat-welcome').exists()).toBe(true);
  });

  it('点击会话记录加载当时的对话（按生成轮次分组）', async () => {
    mockFetchAiImageSessions.mockResolvedValue([sessionOf(1, '第一轮')]);
    mockFetchAiImageSessionImages.mockResolvedValue([
      imageOf(IMAGE_PUBLIC_ID, GENERATION_ID, '第一轮提示词'),
      imageOf('d7c68fa8-9a6b-4b7a-bf4e-000000000003', 'd7c68fa8-9a6b-4b7a-bf4e-000000000004', '第二轮提示词'),
    ]);
    const wrapper = await mountPage();

    await wrapper.find('.session-entry').trigger('click');
    await flushPromises();

    expect(mockFetchAiImageSessionImages).toHaveBeenCalledWith(1);
    expect(wrapper.text()).toContain('第一轮提示词');
    expect(wrapper.text()).toContain('第二轮提示词');
  });

  it('首次生成自动新建会话并继续在原会话追加', async () => {
    mockGenerateAiImages
      .mockResolvedValueOnce({
        sessionId: SESSION_ID,
        sessionTitle: '第一句话',
        images: [imageOf(IMAGE_PUBLIC_ID, GENERATION_ID, '第一句话')],
      })
      .mockResolvedValueOnce({
        sessionId: SESSION_ID,
        sessionTitle: '第一句话',
        images: [
          imageOf('d7c68fa8-9a6b-4b7a-bf4e-000000000005', 'd7c68fa8-9a6b-4b7a-bf4e-000000000006', '第二句话'),
        ],
      });
    const wrapper = await mountPage();

    await wrapper.find('textarea').setValue('第一句话');
    await wrapper.find('button.send-btn').trigger('click');
    await flushPromises();
    expect(mockGenerateAiImages).toHaveBeenLastCalledWith(
      expect.objectContaining({ sessionId: null, prompt: '第一句话' }),
    );

    await wrapper.find('textarea').setValue('第二句话');
    await wrapper.find('button.send-btn').trigger('click');
    await flushPromises();
    expect(mockGenerateAiImages).toHaveBeenLastCalledWith(
      expect.objectContaining({ sessionId: SESSION_ID, prompt: '第二句话' }),
    );
    expect(wrapper.text()).toContain('第一句话');
    expect(wrapper.text()).toContain('第二句话');
  });

  it('生成失败时移除空气泡并展示错误', async () => {
    mockGenerateAiImages.mockRejectedValue(new Error('boom'));
    const wrapper = await mountPage();

    await wrapper.find('textarea').setValue('会失败的提示词');
    await wrapper.find('button.send-btn').trigger('click');
    await flushPromises();

    expect(wrapper.find('.chat-error-bar').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('会失败的提示词');
  });

  it('上传参考图后将文件随生成请求发送', async () => {
    mockGenerateAiImages.mockResolvedValue({
      sessionId: SESSION_ID,
      sessionTitle: '参考图创作',
      images: [imageOf(IMAGE_PUBLIC_ID, GENERATION_ID, '把它改成蓝色海报')],
    });
    const wrapper = await mountPage();
    const file = new File(['fake image'], 'reference.png', { type: 'image/png' });
    const input = wrapper.find('input.reference-file-input');
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
    await input.trigger('change');

    expect(wrapper.text()).toContain('参考图已选择');
    await wrapper.find('textarea').setValue('把它改成蓝色海报');
    await wrapper.find('button.send-btn').trigger('click');
    await flushPromises();

    expect(mockGenerateAiImages).toHaveBeenCalledWith(
      expect.objectContaining({ prompt: '把它改成蓝色海报', referenceImage: file }),
    );
  });

  it('确认后删除会话并从列表移除', async () => {
    mockFetchAiImageSessions.mockResolvedValueOnce([sessionOf(1, '要被删的会话')]).mockResolvedValue([]);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    mockDeleteAiImageSession.mockResolvedValue(undefined);
    const wrapper = await mountPage();

    await wrapper.find('.session-delete').trigger('click');
    await flushPromises();

    expect(mockDeleteAiImageSession).toHaveBeenCalledWith(1);
    expect(wrapper.text()).not.toContain('要被删的会话');
  });

  it('取消确认时不删除会话', async () => {
    mockFetchAiImageSessions.mockResolvedValue([sessionOf(1, '保留的会话')]);
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const wrapper = await mountPage();

    await wrapper.find('.session-delete').trigger('click');
    await flushPromises();

    expect(mockDeleteAiImageSession).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('保留的会话');
  });
});

describe('AdminAiImagesPage 图片交互', () => {
  it('点击图片弹窗放大预览', async () => {
    mockFetchAiImageSessions.mockResolvedValue([sessionOf(1, '预览')]);
    mockFetchAiImageSessionImages.mockResolvedValue([imageOf(IMAGE_PUBLIC_ID, GENERATION_ID, '预览提示词')]);
    const wrapper = await mountPage();
    await wrapper.find('.session-entry').trigger('click');
    await flushPromises();
    await flushPromises();

    await wrapper.find('img.thumb').trigger('click');
    await flushPromises();

    expect(wrapper.find('.lightbox').exists()).toBe(true);
    expect(wrapper.find('.lightbox-meta').exists()).toBe(false);
  });

  it('图片可下载', async () => {
    mockFetchAiImageSessions.mockResolvedValue([sessionOf(1, '下载')]);
    mockFetchAiImageSessionImages.mockResolvedValue([imageOf(IMAGE_PUBLIC_ID, GENERATION_ID, '下载提示词')]);
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const wrapper = await mountPage();
    await wrapper.find('.session-entry').trigger('click');
    await flushPromises();
    await flushPromises();

    await wrapper.find('.image-actions button').trigger('click');

    expect(clickSpy).toHaveBeenCalledTimes(1);
    clickSpy.mockRestore();
  });

  it('可删除单张图片', async () => {
    mockFetchAiImageSessions.mockResolvedValue([sessionOf(1, '删除')]);
    mockFetchAiImageSessionImages.mockResolvedValue([imageOf(IMAGE_PUBLIC_ID, GENERATION_ID, '删除提示词')]);
    mockDeleteAiGeneratedImage.mockResolvedValue(undefined);
    const wrapper = await mountPage();
    await wrapper.find('.session-entry').trigger('click');
    await flushPromises();
    await flushPromises();

    await wrapper.find('.image-actions .danger').trigger('click');
    await flushPromises();

    expect(mockDeleteAiGeneratedImage).toHaveBeenCalledWith(IMAGE_PUBLIC_ID);
    expect(wrapper.findAll('.image-item')).toHaveLength(0);
  });
});
