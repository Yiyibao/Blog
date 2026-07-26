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
}
