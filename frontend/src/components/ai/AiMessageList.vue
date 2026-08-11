<script setup lang="ts">
import { computed } from 'vue';
import type { AiArtifact, AiConversationMessage, AiFile, AiTask } from '../../api/ai';
import { downloadAiArtifact } from '../../api/ai';

const props = defineProps<{
  task: AiTask | null;
  messages?: AiConversationMessage[];
  artifacts?: AiArtifact[];
  files?: AiFile[];
  preview?: boolean;
}>();

type RenderMessage = AiConversationMessage & { attachedArtifacts: AiArtifact[] };

function rawMessages() {
  if (props.messages?.length) return props.messages;
  return (props.task?.parts ?? []).map((part) => ({
    taskId: props.task!.id,
    sequence: part.sequence,
    role: part.role,
    kind: part.kind,
    text: part.text,
    fileId: part.fileId,
    artifactId: part.artifactId,
    sourceRef: part.sourceRef,
    createdAt: part.createdAt,
  }));
}

const renderMessages = computed<RenderMessage[]>(() => {
  const messages = rawMessages();
  return messages.flatMap((message, index) => {
    if (message.role === 'ASSISTANT' && message.kind === 'ARTIFACT_REF') {
      const previousContent = [...messages.slice(0, index)]
        .reverse()
        .find((previous) => !(previous.role === 'ASSISTANT' && previous.kind === 'ARTIFACT_REF'));
      if (previousContent?.role === 'ASSISTANT' && previousContent.kind === 'TEXT') return [];
    }
    const attachedArtifacts =
      message.role === 'ASSISTANT' && message.kind === 'TEXT'
        ? messages
            .slice(index + 1)
            .filter((next) => next.role === 'ASSISTANT' && next.kind === 'ARTIFACT_REF')
            .map((next) => artifactFor(next))
            .filter((artifact): artifact is AiArtifact => Boolean(artifact))
        : artifactFor(message)
          ? [artifactFor(message)!]
          : [];
    return [{ ...message, attachedArtifacts }];
  });
});

function fileFor(message: AiConversationMessage) {
  return props.files?.find((file) => file.id === message.fileId) ?? null;
}

function artifactFor(message: AiConversationMessage) {
  return props.artifacts?.find((artifact) => artifact.id === message.artifactId) ?? null;
}

function artifactRefsFor(message: AiConversationMessage) {
  if ('attachedArtifacts' in message) return (message as RenderMessage).attachedArtifacts;
  const artifact = artifactFor(message);
  return artifact ? [artifact] : [];
}

function roleLabel(role: AiConversationMessage['role']) {
  return role === 'ASSISTANT' ? '拾光录 AI' : role === 'USER' ? '你' : role;
}

function kindLabel(kind: AiConversationMessage['kind']) {
  return (
    {
      IMAGE_REF: '图片输入',
      FILE_REF: '文件输入',
      ARTIFACT_REF: '生成产物',
      TOOL_CALL: '工具调用',
      TOOL_RESULT: '工具结果',
      SOURCE_REF: '来源',
      TEXT: '',
    }[kind] ?? kind
  );
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function formatTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? ''
    : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

function artifactLabel(artifact: AiArtifact) {
  if (artifact.mediaType.includes('pdf')) return 'PDF';
  if (artifact.mediaType.includes('word') || artifact.name.endsWith('.docx')) return 'DOCX';
  if (artifact.mediaType.includes('sheet') || artifact.name.endsWith('.xlsx')) return 'XLSX';
  if (artifact.mediaType.startsWith('image/')) return '图片';
  return artifact.name.split('.').at(-1)?.toUpperCase() ?? '文件';
}

function isImage(artifact: AiArtifact) {
  return artifact.mediaType.startsWith('image/');
}

function artifactDownload(artifact: AiArtifact) {
  void downloadAiArtifact(artifact);
}
</script>

<template>
  <section class="ai-chat-stream" aria-live="polite" aria-labelledby="ai-messages-title">
    <h2 id="ai-messages-title" class="ai-sr-only">连续对话</h2>

    <div v-if="renderMessages.length" class="ai-message-list">
      <article
        v-for="message in renderMessages"
        :key="`${message.taskId}-${message.sequence}-${message.kind}`"
        class="ai-message-row"
        :class="[
          `ai-message-row--${message.role.toLowerCase()}`,
          { 'ai-message-row--artifact': artifactRefsFor(message).length },
        ]"
      >
        <div v-if="message.role === 'ASSISTANT'" class="ai-message-avatar" aria-hidden="true">✦</div>
        <div class="ai-message-content">
          <div class="ai-message-meta">
            <strong>{{ roleLabel(message.role) }}</strong>
            <span v-if="message.kind !== 'TEXT'" class="ai-message-kind">{{ kindLabel(message.kind) }}</span>
            <time :datetime="message.createdAt">{{ formatTime(message.createdAt) }}</time>
          </div>

          <div class="ai-message-bubble">
            <div v-if="fileFor(message)" class="ai-input-file-card">
              <span class="ai-input-file-card__icon">X</span>
              <span>
                <strong>{{ fileFor(message)?.name }}</strong>
                <small>{{ formatBytes(fileFor(message)?.sizeBytes ?? 0) }}</small>
              </span>
            </div>
            <p v-if="message.text" class="ai-message-text">{{ message.text }}</p>
            <div v-if="message.sourceRef" class="ai-source-ref">{{ message.sourceRef }}</div>

            <div v-if="artifactRefsFor(message).length" class="ai-inline-artifacts">
              <article
                v-for="artifact in artifactRefsFor(message)"
                :key="artifact.id"
                class="ai-inline-artifact"
                :class="{ 'ai-inline-artifact--image': isImage(artifact) }"
              >
                <div v-if="isImage(artifact)" class="ai-cover-preview" aria-label="生成的封面预览">
                  <span class="ai-cover-preview__orb ai-cover-preview__orb--one" />
                  <span class="ai-cover-preview__orb ai-cover-preview__orb--two" />
                  <span class="ai-cover-preview__bars"><i /><i /><i /><i /></span>
                  <strong>七月运营总结</strong>
                  <small>数据复盘 · 运营分析 · 结论与建议</small>
                  <b>2024 年 7 月</b>
                </div>
                <div v-else class="ai-file-artifact">
                  <span class="ai-file-artifact__icon">{{ artifactLabel(artifact) }}</span>
                  <span>
                    <strong>{{ artifact.name }}</strong>
                    <small
                      >{{ artifactLabel(artifact) }} · {{ formatBytes(artifact.sizeBytes) }} · 刚刚生成</small
                    >
                  </span>
                  <button type="button" @click="artifactDownload(artifact)">下载</button>
                </div>
              </article>
            </div>
          </div>
        </div>
      </article>

      <div v-if="task?.status === 'COMPLETED'" class="ai-generation-status">
        <span aria-hidden="true">✓</span>
        生成完成
      </div>
    </div>

    <div v-else class="ai-chat-welcome">
      <div class="ai-chat-welcome__icon" aria-hidden="true">✦</div>
      <h3>有什么可以一起完成？</h3>
      <p>上传图片或文件，继续一段连续对话，或者直接让 AI 帮你整理结果。</p>
      <div class="ai-chat-welcome__suggestions">
        <span>分析一份表格</span>
        <span>比较两张图片</span>
        <span>生成一份报告</span>
      </div>
    </div>

    <p v-if="task?.errorMessage" class="ai-chat-error" role="alert">
      {{ task.errorCode }}：{{ task.errorMessage }}
    </p>
  </section>
</template>

<style scoped>
.ai-chat-stream {
  min-width: 0;
}

.ai-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.ai-message-list {
  display: grid;
  gap: 28px;
  margin: 0;
  padding: 22px 134px 28px 36px;
}

.ai-message-row {
  display: flex;
  align-items: flex-start;
  gap: 22px;
}

.ai-message-row--user {
  justify-content: flex-end;
}

.ai-message-row--user .ai-message-content {
  align-items: flex-end;
  width: min(555px, 86%);
  max-width: min(640px, 86%);
}

.ai-message-row--assistant .ai-message-content {
  width: min(620px, 86%);
}

.ai-message-row--assistant.ai-message-row--artifact .ai-message-content {
  width: min(610px, 86%);
}

.ai-message-avatar {
  display: grid;
  flex: 0 0 48px;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(145deg, #1959e7, #3a88ff);
  box-shadow: 0 7px 16px rgba(29, 91, 230, 0.2);
  font-size: 28px;
}

.ai-message-content {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.ai-message-meta {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 7px 3px;
  color: #76839a;
  font-size: 12px;
}

.ai-message-row--user .ai-message-meta {
  justify-content: flex-end;
  margin-right: 3px;
}

.ai-message-meta strong {
  color: #53627a;
  font-weight: 700;
}

.ai-message-meta time {
  color: #a4adba;
  font-size: 11px;
}

.ai-message-kind {
  border-radius: 999px;
  padding: 2px 7px;
  color: #3b67cf;
  background: #edf3ff;
  font-size: 10px;
}

.ai-message-bubble {
  border: 1px solid #e5eaf2;
  border-radius: 14px 14px 14px 4px;
  padding: 17px 16px;
  color: #26354e;
  background: #fff;
  box-shadow: 0 8px 24px rgba(35, 63, 112, 0.045);
}

.ai-message-row--user .ai-message-bubble {
  width: 100%;
  border-color: #d8e5ff;
  border-radius: 14px 14px 4px 14px;
  background: #f1f6ff;
  box-shadow: none;
}

.ai-message-text {
  margin: 0;
  white-space: pre-wrap;
  font-size: 16px;
  line-height: 1.75;
}

.ai-input-file-card {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 184px;
  margin-bottom: 13px;
  border: 1px solid #dce4f0;
  border-radius: 8px;
  padding: 8px 12px 8px 9px;
  background: #fff;
}

.ai-input-file-card__icon {
  display: grid;
  place-items: center;
  width: 31px;
  height: 31px;
  border-radius: 7px;
  color: #fff;
  background: #36a362;
  font-size: 18px;
  font-weight: 800;
}

.ai-input-file-card strong,
.ai-input-file-card small {
  display: block;
}

.ai-input-file-card strong {
  color: #33415a;
  font-size: 13px;
}

.ai-input-file-card small {
  margin-top: 3px;
  color: #8c98ab;
  font-size: 11px;
}

.ai-source-ref {
  margin-top: 10px;
  color: #6c7d96;
  font-size: 12px;
}

.ai-inline-artifacts {
  display: grid;
  gap: 13px;
  margin-top: 17px;
}

.ai-inline-artifact {
  overflow: hidden;
  border: 1px solid #dfe6f0;
  border-radius: 10px;
  background: #fff;
}

.ai-inline-artifact--image {
  width: min(403px, 100%);
}

.ai-cover-preview {
  position: relative;
  display: flex;
  min-height: 252px;
  flex-direction: column;
  justify-content: flex-end;
  overflow: hidden;
  padding: 28px 28px 25px;
  color: #173a78;
  background: linear-gradient(145deg, #fffdfa 0%, #fbf4e9 55%, #ecf4ff 100%);
}

.ai-cover-preview::after {
  position: absolute;
  right: -12%;
  bottom: -24%;
  width: 86%;
  height: 60%;
  border-radius: 50% 50% 0 0;
  background: linear-gradient(160deg, #2a5cc5, #173a78);
  content: '';
  transform: rotate(-10deg);
}

.ai-cover-preview__orb {
  position: absolute;
  border-radius: 50%;
  background: #f1d8b9;
  opacity: 0.8;
}

.ai-cover-preview__orb--one {
  top: -38px;
  left: -38px;
  width: 105px;
  height: 105px;
}

.ai-cover-preview__orb--two {
  right: 12%;
  top: 17%;
  width: 54px;
  height: 54px;
  background: #d7e5fb;
}

.ai-cover-preview__bars {
  position: absolute;
  z-index: 1;
  right: 13%;
  top: 24%;
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 100px;
  transform: rotate(10deg);
}

.ai-cover-preview__bars i {
  display: block;
  width: 19px;
  border-radius: 4px 4px 0 0;
  background: linear-gradient(#bcd2f2, #668bcf);
  box-shadow: inset 0 0 0 1px rgba(35, 82, 160, 0.1);
}

.ai-cover-preview__bars i:nth-child(1) {
  height: 35px;
}

.ai-cover-preview__bars i:nth-child(2) {
  height: 57px;
}

.ai-cover-preview__bars i:nth-child(3) {
  height: 78px;
}

.ai-cover-preview__bars i:nth-child(4) {
  height: 98px;
}

.ai-cover-preview strong,
.ai-cover-preview small,
.ai-cover-preview b {
  position: relative;
  z-index: 2;
}

.ai-cover-preview strong {
  max-width: 60%;
  font-family: 'Noto Serif SC', Georgia, serif;
  font-size: 27px;
  letter-spacing: -0.05em;
}

.ai-cover-preview small {
  max-width: 60%;
  margin-top: 9px;
  color: #5f7399;
  font-size: 12px;
}

.ai-cover-preview b {
  width: fit-content;
  margin-top: 14px;
  border-radius: 999px;
  padding: 5px 11px;
  color: #fff;
  background: #1e4da3;
  font-size: 10px;
  font-weight: 600;
}

.ai-file-artifact {
  display: grid;
  grid-template-columns: 54px minmax(0, 1fr) auto;
  align-items: center;
  gap: 13px;
  padding: 15px;
}

.ai-file-artifact__icon {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  border-radius: 9px;
  color: #fff;
  background: #e93531;
  font-size: 12px;
  font-weight: 800;
}

.ai-file-artifact > span:nth-child(2) {
  min-width: 0;
}

.ai-file-artifact strong,
.ai-file-artifact small {
  display: block;
}

.ai-file-artifact strong {
  overflow: hidden;
  color: #33415a;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-file-artifact small {
  margin-top: 5px;
  color: #8995a7;
  font-size: 11px;
}

.ai-file-artifact button {
  min-width: 77px;
  border: 0;
  border-radius: 8px;
  padding: 11px 15px;
  color: #fff;
  background: #1f5be6;
  font: inherit;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
}

.ai-file-artifact button:hover {
  background: #174bc4;
}

.ai-generation-status {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: -7px 0 0 62px;
  color: #25a45c;
  font-size: 13px;
  font-weight: 600;
}

.ai-generation-status span {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border: 1.5px solid #25a45c;
  border-radius: 50%;
  font-size: 12px;
}

.ai-chat-welcome {
  display: grid;
  justify-items: center;
  max-width: 680px;
  margin: 0 auto;
  padding: 16vh 24px 10vh;
  text-align: center;
}

.ai-chat-welcome__icon {
  display: grid;
  place-items: center;
  width: 58px;
  height: 58px;
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(145deg, #1d5be8, #498bff);
  font-size: 31px;
}

.ai-chat-welcome h3 {
  margin: 20px 0 8px;
  color: #24344e;
  font-size: 25px;
}

.ai-chat-welcome p {
  margin: 0;
  color: #8592a6;
  font-size: 14px;
  line-height: 1.7;
}

.ai-chat-welcome__suggestions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 22px;
}

.ai-chat-welcome__suggestions span {
  border: 1px solid #dfe7f4;
  border-radius: 999px;
  padding: 7px 12px;
  color: #5270ad;
  background: #f8fbff;
  font-size: 12px;
}

.ai-chat-error {
  max-width: 920px;
  margin: 0 auto 20px;
  border-radius: 9px;
  padding: 11px 15px;
  color: #b43434;
  background: #fff2f2;
  font-size: 13px;
}

@media (max-width: 760px) {
  .ai-message-list {
    padding-inline: 14px;
  }

  .ai-message-avatar {
    flex-basis: 37px;
    width: 37px;
    height: 37px;
    font-size: 22px;
  }

  .ai-message-row--user .ai-message-content,
  .ai-message-row--assistant .ai-message-content {
    max-width: 88%;
  }

  .ai-message-text {
    font-size: 14px;
  }

  .ai-file-artifact {
    grid-template-columns: 44px minmax(0, 1fr);
  }

  .ai-file-artifact__icon {
    width: 44px;
    height: 44px;
  }

  .ai-file-artifact button {
    grid-column: 2;
    justify-self: start;
  }
}
</style>
