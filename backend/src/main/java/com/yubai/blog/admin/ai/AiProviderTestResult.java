package com.yubai.blog.admin.ai;

import java.util.List;

public record AiProviderTestResult(boolean ok, String message, List<String> models) {}
