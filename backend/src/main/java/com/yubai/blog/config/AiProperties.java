package com.yubai.blog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {
    private boolean enabled;
    private String baseUrl = "https://api.deepseek.com";
    private String apiKey;
    private String model = "deepseek-v4-flash";
    /** Native Anthropic Messages API settings supplied by the server environment. */
    private String anthropicBaseUrl;
    private String anthropicAuthToken;
    private String anthropicModel = "claude-sonnet-5";
    private String anthropicModels = "claude-fable-5,claude-haiku-4-5,claude-haiku-4-5-20251001,claude-opus-4-6,claude-opus-4-7,claude-opus-4-8,claude-opus-5,claude-sonnet-4-6,claude-sonnet-5";
    /** OpenAI Responses API relay configuration. The key is materialized into the provider registry encrypted. */
    private boolean responsesEnabled;
    private String responsesBaseUrl;
    private String responsesApiKey;
    private String responsesModel = "gpt-5.5";
    private String responsesModels = "gpt-5.3-codex-spark,gpt-5.4,gpt-5.5,gpt-5.6-luna,gpt-5.6-sol,gpt-5.6-terra";
    private String responsesHeaderName;
    private String responsesHeaderValue;
    private String responsesReasoningEffort = "xhigh";
    private boolean responsesStore;
    private int requestTimeout = 60;
    private int maxInputChars = 32_000;
    private int maxHistoryMessages = 20;
    private int maxTotalChars = 160_000;
    private int maxOutputTokens = 2048;
    /** 4A-1：供应商注册表密钥加密主密钥（APP_AI_MASTER_KEY）；为空时注册表密钥存取不可用。 */
    private String masterKey;
    /** 4A-1：是否允许 base_url 指向本地/私网端点（如 Ollama）；只能改 env 重启生效。 */
    private boolean allowLocalEndpoints;
    private String opencodeUsername;
    private String opencodePassword;
    private String opencodeAgent = "blog-ai";
    private String opencodeProviderId = "opencode-go";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getAnthropicBaseUrl() { return anthropicBaseUrl; }
    public void setAnthropicBaseUrl(String anthropicBaseUrl) { this.anthropicBaseUrl = anthropicBaseUrl; }
    public String getAnthropicAuthToken() { return anthropicAuthToken; }
    public void setAnthropicAuthToken(String anthropicAuthToken) { this.anthropicAuthToken = anthropicAuthToken; }
    public String getAnthropicModel() { return anthropicModel; }
    public void setAnthropicModel(String anthropicModel) { this.anthropicModel = anthropicModel; }
    public String getAnthropicModels() { return anthropicModels; }
    public void setAnthropicModels(String anthropicModels) { this.anthropicModels = anthropicModels; }
    public boolean isResponsesEnabled() { return responsesEnabled; }
    public void setResponsesEnabled(boolean responsesEnabled) { this.responsesEnabled = responsesEnabled; }
    public String getResponsesBaseUrl() { return responsesBaseUrl; }
    public void setResponsesBaseUrl(String responsesBaseUrl) { this.responsesBaseUrl = responsesBaseUrl; }
    public String getResponsesApiKey() { return responsesApiKey; }
    public void setResponsesApiKey(String responsesApiKey) { this.responsesApiKey = responsesApiKey; }
    public String getResponsesModel() { return responsesModel; }
    public void setResponsesModel(String responsesModel) { this.responsesModel = responsesModel; }
    public String getResponsesModels() { return responsesModels; }
    public void setResponsesModels(String responsesModels) { this.responsesModels = responsesModels; }
    public String getResponsesHeaderName() { return responsesHeaderName; }
    public void setResponsesHeaderName(String responsesHeaderName) { this.responsesHeaderName = responsesHeaderName; }
    public String getResponsesHeaderValue() { return responsesHeaderValue; }
    public void setResponsesHeaderValue(String responsesHeaderValue) { this.responsesHeaderValue = responsesHeaderValue; }
    public String getResponsesReasoningEffort() { return responsesReasoningEffort; }
    public void setResponsesReasoningEffort(String responsesReasoningEffort) { this.responsesReasoningEffort = responsesReasoningEffort; }
    public boolean isResponsesStore() { return responsesStore; }
    public void setResponsesStore(boolean responsesStore) { this.responsesStore = responsesStore; }
    public int getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(int requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getMaxInputChars() { return maxInputChars; }
    public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
    public int getMaxHistoryMessages() { return maxHistoryMessages; }
    public void setMaxHistoryMessages(int maxHistoryMessages) { this.maxHistoryMessages = maxHistoryMessages; }
    public int getMaxTotalChars() { return maxTotalChars; }
    public void setMaxTotalChars(int maxTotalChars) { this.maxTotalChars = maxTotalChars; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public String getMasterKey() { return masterKey; }
    public void setMasterKey(String masterKey) { this.masterKey = masterKey; }
    public boolean isAllowLocalEndpoints() { return allowLocalEndpoints; }
    public void setAllowLocalEndpoints(boolean allowLocalEndpoints) { this.allowLocalEndpoints = allowLocalEndpoints; }
    public String getOpencodeUsername() { return opencodeUsername; }
    public void setOpencodeUsername(String opencodeUsername) { this.opencodeUsername = opencodeUsername; }
    public String getOpencodePassword() { return opencodePassword; }
    public void setOpencodePassword(String opencodePassword) { this.opencodePassword = opencodePassword; }
    public String getOpencodeAgent() { return opencodeAgent; }
    public void setOpencodeAgent(String opencodeAgent) { this.opencodeAgent = opencodeAgent; }
    public String getOpencodeProviderId() { return opencodeProviderId; }
    public void setOpencodeProviderId(String opencodeProviderId) { this.opencodeProviderId = opencodeProviderId; }
}
