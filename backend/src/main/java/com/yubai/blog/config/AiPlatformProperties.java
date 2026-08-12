package com.yubai.blog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.platform")
public class AiPlatformProperties {
    private boolean tasksEnabled;
    private boolean multimodalEnabled;
    private boolean memoryEnabled;
    private boolean artifactsEnabled;
    private int maxFileBytes = 15_000_000;
    private int maxArtifactBytes = 15_000_000;
    private long maxOwnerFileBytes = 100_000_000L;
    private int maxOwnerFiles = 100;
    private long maxOwnerArtifactBytes = 100_000_000L;
    private int maxOwnerArtifacts = 100;
    private int maxExtractedChars = 120_000;
    private int maxPdfPages = 100;
    private int maxDocxEntries = 2_000;
    private long maxDocxUncompressedBytes = 40_000_000L;
    private int maxCsvRows = 10_000;
    private int maxCsvColumns = 200;
    private int maxConcurrentTasks = 4;
    private int maxConcurrentTasksPerUser = 2;
    private int maxConcurrentTasksPerProvider = 2;
    private int maxQueuedTasks = 16;
    private int queueWaitSeconds = 20;
    private int fileRetentionDays = 30;
    private int artifactRetentionDays = 30;
    private int maxMemoryContextTokens = 1_500;
    private int maxSessionSummaryTokens = 1_000;
    private int maxRecentMessageTokens = 4_000;
    private int maxRecentMessages = 16;
    private int maxSummarySourceMessages = 120;

    public boolean isTasksEnabled() {
        return tasksEnabled;
    }

    public void setTasksEnabled(boolean tasksEnabled) {
        this.tasksEnabled = tasksEnabled;
    }

    public boolean isMultimodalEnabled() {
        return multimodalEnabled;
    }

    public void setMultimodalEnabled(boolean multimodalEnabled) {
        this.multimodalEnabled = multimodalEnabled;
    }

    public boolean isMemoryEnabled() {
        return memoryEnabled;
    }

    public void setMemoryEnabled(boolean memoryEnabled) {
        this.memoryEnabled = memoryEnabled;
    }

    public boolean isArtifactsEnabled() {
        return artifactsEnabled;
    }

    public void setArtifactsEnabled(boolean artifactsEnabled) {
        this.artifactsEnabled = artifactsEnabled;
    }

    public int getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(int maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public int getMaxArtifactBytes() {
        return maxArtifactBytes;
    }

    public void setMaxArtifactBytes(int maxArtifactBytes) {
        this.maxArtifactBytes = maxArtifactBytes;
    }

    public long getMaxOwnerFileBytes() {
        return maxOwnerFileBytes;
    }

    public void setMaxOwnerFileBytes(long maxOwnerFileBytes) {
        this.maxOwnerFileBytes = maxOwnerFileBytes;
    }

    public int getMaxOwnerFiles() {
        return maxOwnerFiles;
    }

    public void setMaxOwnerFiles(int maxOwnerFiles) {
        this.maxOwnerFiles = maxOwnerFiles;
    }

    public long getMaxOwnerArtifactBytes() {
        return maxOwnerArtifactBytes;
    }

    public void setMaxOwnerArtifactBytes(long maxOwnerArtifactBytes) {
        this.maxOwnerArtifactBytes = maxOwnerArtifactBytes;
    }

    public int getMaxOwnerArtifacts() {
        return maxOwnerArtifacts;
    }

    public void setMaxOwnerArtifacts(int maxOwnerArtifacts) {
        this.maxOwnerArtifacts = maxOwnerArtifacts;
    }

    public int getMaxExtractedChars() {
        return maxExtractedChars;
    }

    public void setMaxExtractedChars(int maxExtractedChars) {
        this.maxExtractedChars = maxExtractedChars;
    }

    public int getMaxPdfPages() {
        return maxPdfPages;
    }

    public void setMaxPdfPages(int maxPdfPages) {
        this.maxPdfPages = maxPdfPages;
    }

    public int getMaxDocxEntries() {
        return maxDocxEntries;
    }

    public void setMaxDocxEntries(int maxDocxEntries) {
        this.maxDocxEntries = maxDocxEntries;
    }

    public long getMaxDocxUncompressedBytes() {
        return maxDocxUncompressedBytes;
    }

    public void setMaxDocxUncompressedBytes(long maxDocxUncompressedBytes) {
        this.maxDocxUncompressedBytes = maxDocxUncompressedBytes;
    }

    public int getMaxCsvRows() {
        return maxCsvRows;
    }

    public void setMaxCsvRows(int maxCsvRows) {
        this.maxCsvRows = maxCsvRows;
    }

    public int getMaxCsvColumns() {
        return maxCsvColumns;
    }

    public void setMaxCsvColumns(int maxCsvColumns) {
        this.maxCsvColumns = maxCsvColumns;
    }

    public int getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    public void setMaxConcurrentTasks(int maxConcurrentTasks) {
        this.maxConcurrentTasks = maxConcurrentTasks;
    }

    public int getMaxConcurrentTasksPerUser() {
        return maxConcurrentTasksPerUser;
    }

    public void setMaxConcurrentTasksPerUser(int maxConcurrentTasksPerUser) {
        this.maxConcurrentTasksPerUser = maxConcurrentTasksPerUser;
    }

    public int getMaxConcurrentTasksPerProvider() {
        return maxConcurrentTasksPerProvider;
    }

    public void setMaxConcurrentTasksPerProvider(int maxConcurrentTasksPerProvider) {
        this.maxConcurrentTasksPerProvider = maxConcurrentTasksPerProvider;
    }

    public int getMaxQueuedTasks() {
        return maxQueuedTasks;
    }

    public void setMaxQueuedTasks(int maxQueuedTasks) {
        this.maxQueuedTasks = maxQueuedTasks;
    }

    public int getQueueWaitSeconds() {
        return queueWaitSeconds;
    }

    public void setQueueWaitSeconds(int queueWaitSeconds) {
        this.queueWaitSeconds = queueWaitSeconds;
    }

    public int getFileRetentionDays() {
        return fileRetentionDays;
    }

    public void setFileRetentionDays(int fileRetentionDays) {
        this.fileRetentionDays = fileRetentionDays;
    }

    public int getArtifactRetentionDays() {
        return artifactRetentionDays;
    }

    public void setArtifactRetentionDays(int artifactRetentionDays) {
        this.artifactRetentionDays = artifactRetentionDays;
    }

    public int getMaxMemoryContextTokens() {
        return maxMemoryContextTokens;
    }

    public void setMaxMemoryContextTokens(int maxMemoryContextTokens) {
        this.maxMemoryContextTokens = maxMemoryContextTokens;
    }

    public int getMaxSessionSummaryTokens() {
        return maxSessionSummaryTokens;
    }

    public void setMaxSessionSummaryTokens(int maxSessionSummaryTokens) {
        this.maxSessionSummaryTokens = maxSessionSummaryTokens;
    }

    public int getMaxRecentMessageTokens() {
        return maxRecentMessageTokens;
    }

    public void setMaxRecentMessageTokens(int maxRecentMessageTokens) {
        this.maxRecentMessageTokens = maxRecentMessageTokens;
    }

    public int getMaxRecentMessages() {
        return maxRecentMessages;
    }

    public void setMaxRecentMessages(int maxRecentMessages) {
        this.maxRecentMessages = maxRecentMessages;
    }

    public int getMaxSummarySourceMessages() {
        return maxSummarySourceMessages;
    }

    public void setMaxSummarySourceMessages(int maxSummarySourceMessages) {
        this.maxSummarySourceMessages = maxSummarySourceMessages;
    }
}
