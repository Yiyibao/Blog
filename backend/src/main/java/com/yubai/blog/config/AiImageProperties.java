package com.yubai.blog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side configuration for the two image-generation routes.
 *
 * <p>The values are deliberately split into Grok and GPT profiles so that a
 * relay can use separate credentials and model aliases.  Neither profile is
 * exposed through the browser; the admin API only returns the model names.</p>
 */
@ConfigurationProperties(prefix = "app.ai.image")
public class AiImageProperties {
    private boolean enabled;
    private int maxPromptChars = 32_000;
    private int maxImages = 1;
    private long maxImageBytes = 15_000_000L;
    private int requestTimeout = 120;
    private int rateLimit = 3;
    private int rateWindowSeconds = 60;
    private final Provider grok = new Provider();
    private final Provider gpt = new Provider();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxPromptChars() { return maxPromptChars; }
    public void setMaxPromptChars(int maxPromptChars) { this.maxPromptChars = maxPromptChars; }
    public int getMaxImages() { return maxImages; }
    public void setMaxImages(int maxImages) { this.maxImages = maxImages; }
    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long maxImageBytes) { this.maxImageBytes = maxImageBytes; }
    public int getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(int requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getRateLimit() { return rateLimit; }
    public void setRateLimit(int rateLimit) { this.rateLimit = rateLimit; }
    public int getRateWindowSeconds() { return rateWindowSeconds; }
    public void setRateWindowSeconds(int rateWindowSeconds) { this.rateWindowSeconds = rateWindowSeconds; }
    public Provider getGrok() { return grok; }
    public Provider getGpt() { return gpt; }

    public static class Provider {
        private boolean enabled;
        private String baseUrl;
        private String apiKey;
        private String models;
        private String defaultModel;
        private String wireApi = "images";
        private String headerName;
        private String headerValue;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModels() { return models; }
        public void setModels(String models) { this.models = models; }
        public String getDefaultModel() { return defaultModel; }
        public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
        public String getWireApi() { return wireApi; }
        public void setWireApi(String wireApi) { this.wireApi = wireApi; }
        public String getHeaderName() { return headerName; }
        public void setHeaderName(String headerName) { this.headerName = headerName; }
        public String getHeaderValue() { return headerValue; }
        public void setHeaderValue(String headerValue) { this.headerValue = headerValue; }
    }
}
