package com.yubai.blog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {
    private boolean enabled;
    private String baseUrl = "https://api.deepseek.com";
    private String apiKey;
    private String model = "deepseek-v4-flash";
    private int requestTimeout = 60;
    private int maxInputChars = 8000;
    private int maxHistoryMessages = 20;
    private int maxTotalChars = 40000;
    private int maxOutputTokens = 2048;
    /** 4A-1：供应商注册表密钥加密主密钥（APP_AI_MASTER_KEY）；为空时注册表密钥存取不可用。 */
    private String masterKey;
    /** 4A-1：是否允许 base_url 指向本地/私网端点（如 Ollama）；只能改 env 重启生效。 */
    private boolean allowLocalEndpoints;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
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
}
