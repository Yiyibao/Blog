import type { NoteAttachment, NotePayload } from '../api/admin';

export function buildPayload(
  form: {
    title: string;
    markdownContent: string;
    folder: string;
    status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
    tags: string[];
    version: number;
  },
  attachments: NoteAttachment[],
  attachmentPreviewUrls: Record<number, string>,
  tagText: string,
): NotePayload {
  let markdownContent = form.markdownContent;
  for (const attachment of attachments) {
    const previewUrl = attachmentPreviewUrls[attachment.id];
    if (previewUrl) markdownContent = markdownContent.replaceAll(previewUrl, attachment.url);
  }
  return {
    ...form,
    markdownContent,
    title: form.title.trim() || '未命名笔记',
    tags: tagText
      .split(/[,，]/)
      .map((tag) => tag.trim())
      .filter(Boolean),
  };
}

export function clearPreviewUrls(previewUrls: Record<number, string>): Record<number, string> {
  Object.values(previewUrls).forEach((url) => URL.revokeObjectURL(url));
  return {};
}

export function replacePreviewUrls(
  markdown: string,
  attachments: NoteAttachment[],
  previewUrls: Record<number, string>,
): string {
  let rendered = markdown;
  for (const attachment of attachments) {
    const previewUrl = previewUrls[attachment.id];
    if (previewUrl) rendered = rendered.replaceAll(attachment.url, previewUrl);
  }
  return rendered;
}

export function replaceCanonicalUrls(
  markdown: string,
  attachments: NoteAttachment[],
  previewUrls: Record<number, string>,
): string {
  let result = markdown;
  for (const attachment of attachments) {
    const previewUrl = previewUrls[attachment.id];
    if (previewUrl) result = result.replaceAll(previewUrl, attachment.url);
  }
  return result;
}
