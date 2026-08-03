/**
 * Keep the browser-side limits in sync with the server-side AI safety caps.
 * The server remains authoritative; these values only prevent the UI from
 * rejecting input that the API is prepared to accept.
 */
export const AI_CHAT_MAX_INPUT_CHARS = 32_000
export const AI_CHAT_MAX_TOTAL_CHARS = 160_000
export const AI_IMAGE_MAX_PROMPT_CHARS = 32_000

/** Reserve room for the action instruction when an editor invokes an AI chip. */
export const AI_ACTION_CONTEXT_CHARS = AI_CHAT_MAX_INPUT_CHARS - 1_000
