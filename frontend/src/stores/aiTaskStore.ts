import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import {
  archiveAiProject,
  archiveAiSession,
  cancelAiTask,
  clearAiSessionSummary,
  confirmAiMemory,
  createAiArtifact,
  createAiMemory,
  createAiProject,
  createAiSession,
  createAiTask,
  deleteAiArtifact,
  deleteAiFile,
  deleteAiMemory,
  fetchAiArtifacts,
  fetchAiFiles,
  fetchAiMemories,
  fetchAiProjects,
  fetchAiSessionConversation,
  fetchAiSessions,
  fetchAiTaskEvents,
  fetchAiTasks,
  moveAiSession,
  replayAiTaskStream,
  rejectAiMemory,
  restoreAiProject,
  runAiTask,
  setAiMemoryEnabled,
  updateAiMemory,
  updateAiProject,
  uploadAiFile,
  type AiArtifact,
  type AiArtifactFormat,
  type AiConversationMessage,
  type AiFile,
  type AiMemory,
  type AiProject,
  type AiReasoningEffort,
  type AiSession,
  type AiTask,
  type AiTaskCreateInput,
  type AiTaskEvent,
} from '../api/ai';

export const useAiTaskStore = defineStore('ai-tasks', () => {
  const projects = ref<AiProject[]>([]);
  const sessions = ref<AiSession[]>([]);
  const tasks = ref<AiTask[]>([]);
  const files = ref<AiFile[]>([]);
  const memories = ref<AiMemory[]>([]);
  const artifacts = ref<AiArtifact[]>([]);
  const events = ref<AiTaskEvent[]>([]);
  const conversationMessages = ref<AiConversationMessage[]>([]);
  const currentSessionId = ref<number | null>(null);
  const currentTask = ref<AiTask | null>(null);
  const selectedFileIds = ref<string[]>([]);
  const loading = ref(false);
  const running = ref(false);
  const error = ref('');

  const selectedFiles = computed(() => files.value.filter((file) => selectedFileIds.value.includes(file.id)));
  const currentSession = computed(
    () => sessions.value.find((session) => session.id === currentSessionId.value) ?? null,
  );
  const currentArtifacts = computed(() => {
    const taskIds = new Set(
      tasks.value.filter((task) => task.sessionId === currentSessionId.value).map((task) => task.id),
    );
    if (currentTask.value) taskIds.add(currentTask.value.id);
    return artifacts.value.filter((artifact) => taskIds.has(artifact.taskId));
  });

  async function initialize() {
    loading.value = true;
    error.value = '';
    try {
      const loaded = await Promise.all([
        fetchAiSessions(),
        fetchAiTasks(),
        fetchAiFiles(),
        fetchAiMemories(),
        fetchAiArtifacts(),
      ]);
      [sessions.value, tasks.value, files.value, memories.value, artifacts.value] = loaded;
      if (typeof fetchAiProjects === 'function') {
        try {
          projects.value = await fetchAiProjects();
        } catch {
          projects.value = [];
        }
      }
      currentTask.value = tasks.value[0] ?? null;
      currentSessionId.value = currentTask.value?.sessionId ?? sessions.value[0]?.id ?? null;
      if (currentTask.value) await replayEvents(currentTask.value.id);
      if (currentSessionId.value != null) await loadConversation(currentSessionId.value);
    } catch (cause) {
      error.value = errorMessage(cause);
    } finally {
      loading.value = false;
    }
  }

  async function upload(fileList: FileList | File[]) {
    error.value = '';
    for (const file of Array.from(fileList)) {
      try {
        const uploaded = await uploadAiFile(file);
        files.value.push(uploaded);
        selectedFileIds.value.push(uploaded.id);
      } catch (cause) {
        error.value = `${file.name}: ${errorMessage(cause)}`;
        break;
      }
    }
  }

  function toggleFile(fileId: string) {
    selectedFileIds.value = selectedFileIds.value.includes(fileId)
      ? selectedFileIds.value.filter((id) => id !== fileId)
      : [...selectedFileIds.value, fileId];
  }

  async function removeFile(fileId: string) {
    await deleteAiFile(fileId);
    files.value = files.value.filter((file) => file.id !== fileId);
    selectedFileIds.value = selectedFileIds.value.filter((id) => id !== fileId);
  }

  async function submit(
    prompt: string,
    providerId: number | null,
    model: string | null,
    taskType: AiTaskCreateInput['taskType'] = 'CHAT',
    reasoningEffort: AiReasoningEffort | null = null,
    projectId: number | null = currentSession.value?.projectId ?? null,
  ) {
    if (!prompt.trim() && selectedFiles.value.length === 0) return;
    error.value = '';
    running.value = true;
    try {
      if (currentSessionId.value == null) {
        const session = await createAiSession(prompt.trim().slice(0, 40) || 'New multimodal task', projectId);
        sessions.value.unshift(session);
        currentSessionId.value = session.id;
      }
      const task = await createAiTask({
        sessionId: currentSessionId.value,
        projectId,
        taskType,
        providerId,
        model,
        reasoningEffort,
        idempotencyKey: crypto.randomUUID(),
        parts: [
          ...(prompt.trim() ? [{ kind: 'TEXT' as const, text: prompt.trim() }] : []),
          ...selectedFiles.value.map((file) => ({
            kind: (file.mediaType.startsWith('image/') ? 'IMAGE_REF' : 'FILE_REF') as
              'IMAGE_REF' | 'FILE_REF',
            fileId: file.id,
          })),
        ],
      });
      currentTask.value = task;
      tasks.value.unshift(task);
      selectedFileIds.value = [];
      const completed = await runAiTask(task.id);
      currentTask.value = completed;
      tasks.value = tasks.value.map((item) => (item.id === completed.id ? completed : item));
      sessions.value = await fetchAiSessions();
      await replayEvents(completed.id);
      await loadConversation(completed.sessionId);
    } catch (cause) {
      error.value = errorMessage(cause);
    } finally {
      running.value = false;
    }
  }

  async function cancelCurrent() {
    if (!currentTask.value || currentTask.value.status === 'COMPLETED') return;
    currentTask.value = await cancelAiTask(currentTask.value.id);
    tasks.value = tasks.value.map((item) => (item.id === currentTask.value!.id ? currentTask.value! : item));
    running.value = false;
    await replayEvents(currentTask.value.id);
  }

  async function selectTask(task: AiTask) {
    currentTask.value = task;
    currentSessionId.value = task.sessionId;
    await replayEvents(task.id);
    await loadConversation(task.sessionId);
  }

  async function selectSession(sessionId: number) {
    currentSessionId.value = sessionId;
    currentTask.value = tasks.value.find((task) => task.sessionId === sessionId) ?? null;
    if (currentTask.value) await replayEvents(currentTask.value.id);
    await loadConversation(sessionId);
  }

  async function loadConversation(sessionId: number) {
    if (typeof fetchAiSessionConversation !== 'function') {
      conversationMessages.value = taskMessages(sessionId);
      return;
    }
    try {
      const conversation = await fetchAiSessionConversation(sessionId);
      conversationMessages.value = conversation.messages;
      sessions.value = sessions.value.map((session) =>
        session.id === conversation.session.id ? conversation.session : session,
      );
    } catch {
      conversationMessages.value = taskMessages(sessionId);
    }
  }

  function taskMessages(sessionId: number): AiConversationMessage[] {
    return tasks.value
      .filter((task) => task.sessionId === sessionId)
      .flatMap((task) =>
        task.parts.map((part) => ({
          taskId: task.id,
          sequence: part.sequence,
          role: part.role,
          kind: part.kind,
          text: part.text,
          fileId: part.fileId,
          artifactId: part.artifactId,
          sourceRef: part.sourceRef,
          createdAt: part.createdAt,
        })),
      );
  }

  async function replayEvents(taskId: string) {
    const persisted = await fetchAiTaskEvents(taskId, 0);
    events.value = persisted;
    const cursor = persisted.at(-1)?.sequence ?? 0;
    await replayAiTaskStream(taskId, cursor, (event) => {
      if (!events.value.some((item) => item.sequence === event.sequence)) events.value.push(event);
    }).catch(() => {});
  }

  async function addProject(title: string) {
    const project = await createAiProject(title);
    projects.value = [project, ...projects.value];
    return project;
  }

  async function renameProject(project: AiProject, title: string) {
    const updated = await updateAiProject(project.id, title, project.version);
    projects.value = projects.value.map((item) => (item.id === updated.id ? updated : item));
    return updated;
  }

  async function toggleProject(project: AiProject) {
    const updated =
      project.status === 'ACTIVE' ? await archiveAiProject(project.id) : await restoreAiProject(project.id);
    projects.value = projects.value.map((item) => (item.id === updated.id ? updated : item));
    return updated;
  }

  async function moveSession(session: AiSession, projectId: number | null) {
    const updated = await moveAiSession(session.id, projectId);
    sessions.value = sessions.value.map((item) => (item.id === updated.id ? updated : item));
    if (currentSessionId.value === updated.id) await loadConversation(updated.id);
    return updated;
  }

  async function archiveSession(session: AiSession) {
    const updated = await archiveAiSession(session.id);
    sessions.value = sessions.value.map((item) => (item.id === updated.id ? updated : item));
    if (currentSessionId.value === updated.id) currentSessionId.value = null;
    return updated;
  }

  async function addMemory(content: string, scope = 'USER', sourceTaskId: string | null = null) {
    const memory = await createAiMemory(content, scope, sourceTaskId);
    memories.value.unshift(memory);
  }

  async function confirmMemory(memory: AiMemory) {
    replaceMemory(await confirmAiMemory(memory.id));
  }

  async function toggleMemory(memory: AiMemory) {
    replaceMemory(await setAiMemoryEnabled(memory.id, memory.status !== 'ACTIVE'));
  }

  async function editMemory(memory: AiMemory, content: string) {
    replaceMemory(await updateAiMemory(memory, content));
  }

  async function rejectMemory(memory: AiMemory) {
    replaceMemory(await rejectAiMemory(memory.id));
  }

  async function forgetMemory(memory: AiMemory) {
    await deleteAiMemory(memory.id);
    memories.value = memories.value.filter((item) => item.id !== memory.id);
  }

  async function clearSessionSummary() {
    if (currentSessionId.value == null) return;
    const updated = await clearAiSessionSummary(currentSessionId.value);
    sessions.value = sessions.value.map((session) => (session.id === updated.id ? updated : session));
  }

  function replaceMemory(memory: AiMemory) {
    memories.value = memories.value.map((item) => (item.id === memory.id ? memory : item));
  }

  async function materialize(
    format: AiArtifactFormat,
    name: string,
    content?: string,
    sourceImageId?: string,
  ) {
    if (!currentTask.value) return;
    const artifact = await createAiArtifact(currentTask.value.id, {
      format,
      name,
      content,
      sourceImageId,
    });
    artifacts.value = [artifact, ...artifacts.value.filter((item) => item.id !== artifact.id)];
  }

  async function removeArtifact(artifact: AiArtifact) {
    await deleteAiArtifact(artifact.id);
    artifacts.value = artifacts.value.filter((item) => item.id !== artifact.id);
  }

  return {
    projects,
    sessions,
    tasks,
    files,
    memories,
    artifacts,
    events,
    conversationMessages,
    currentSessionId,
    currentTask,
    selectedFileIds,
    selectedFiles,
    currentSession,
    currentArtifacts,
    loading,
    running,
    error,
    initialize,
    upload,
    toggleFile,
    removeFile,
    submit,
    cancelCurrent,
    selectTask,
    selectSession,
    addProject,
    renameProject,
    toggleProject,
    moveSession,
    archiveSession,
    addMemory,
    confirmMemory,
    toggleMemory,
    editMemory,
    rejectMemory,
    forgetMemory,
    clearSessionSummary,
    materialize,
    removeArtifact,
  };
});

function errorMessage(cause: unknown) {
  if (cause && typeof cause === 'object' && 'response' in cause) {
    const response = (cause as { response?: { data?: { message?: string } } }).response;
    if (response?.data?.message) return response.data.message;
  }
  return cause instanceof Error ? cause.message : 'AI request failed; please try again later';
}
