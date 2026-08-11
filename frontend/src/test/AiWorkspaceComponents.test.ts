import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import AiAttachmentTray from '../components/ai/AiAttachmentTray.vue';
import AiMemoryPanel from '../components/ai/AiMemoryPanel.vue';
import AiTaskComposer from '../components/ai/AiTaskComposer.vue';

describe('AI Workspace components', () => {
  it('attachment tray only emits bytes explicitly selected by the user', async () => {
    const wrapper = mount(AiAttachmentTray, { props: { files: [], selectedIds: [] } });
    const input = wrapper.get('input[type="file"]');
    const files = [new File(['hello'], 'note.txt', { type: 'text/plain' })];
    Object.defineProperty(input.element, 'files', { value: files, configurable: true });

    await input.trigger('change');

    expect(wrapper.emitted('upload')?.[0]?.[0]).toEqual(files);
    expect(wrapper.text()).toContain('尚未上传附件');
  });

  it('composer exposes an accessible cancel action while a task runs', async () => {
    const wrapper = mount(AiTaskComposer, { props: { running: true, selectedCount: 2 } });

    expect(wrapper.get('textarea').attributes('disabled')).toBeDefined();
    await wrapper.get('button').trigger('click');
    expect(wrapper.emitted('cancel')).toHaveLength(1);
  });

  it('memory panel distinguishes proposed confirmation from active disable and forget', async () => {
    const base = {
      id: 'memory-1',
      scope: 'USER',
      kind: 'PREFERENCE',
      content: '默认中文回答',
      sourceTaskId: 'task-1',
      sourceRef: null,
      status: 'PROPOSED' as const,
      confidence: null,
      expiresAt: null,
      version: 0,
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    };
    const wrapper = mount(AiMemoryPanel, { props: { memories: [base] } });

    expect(wrapper.text()).toContain('PROPOSED');
    await wrapper.get('button.ai-link-button').trigger('click');
    expect(wrapper.emitted('confirm')?.[0]?.[0]).toEqual(base);
    expect(wrapper.findAll('button').some((button) => button.text() === '忘记')).toBe(true);
  });

  it('creates session-scoped proposals and lets the user clear the server summary', async () => {
    const wrapper = mount(AiMemoryPanel, {
      props: {
        memories: [],
        currentSessionId: 7,
        currentTaskId: 'task-7',
        sessionSummary: '较早对话摘要',
      },
    });
    await wrapper.get('#ai-memory-content').setValue('默认先给结论');
    await wrapper.get('select').setValue('SESSION');
    const proposal = wrapper.findAll('button').find((button) => button.text() === '保存为待确认提案');

    await proposal!.trigger('click');
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '清除摘要')!
      .trigger('click');

    expect(wrapper.emitted('create')?.[0]).toEqual(['默认先给结论', 'SESSION:7', 'task-7']);
    expect(wrapper.emitted('clearSummary')).toHaveLength(1);
  });

  it('defaults new memory to the selected project scope', async () => {
    const wrapper = mount(AiMemoryPanel, {
      props: {
        memories: [],
        currentProjectId: 42,
        currentProjectTitle: '内容升级',
      },
    });

    await wrapper.get('#ai-memory-content').setValue('项目专属输出格式');
    expect(wrapper.get('select').element.value).toBe('PROJECT');
    await wrapper.get('form.ai-memory-form').trigger('submit');

    expect(wrapper.emitted('create')?.[0]).toEqual(['项目专属输出格式', 'PROJECT:42', null]);
    expect(wrapper.text()).toContain('内容升级');
  });
});
