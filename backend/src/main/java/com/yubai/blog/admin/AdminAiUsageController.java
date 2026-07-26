package com.yubai.blog.admin;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.admin.ai.AiProviderEntity;
import com.yubai.blog.admin.ai.AiProviderRepository;
import com.yubai.blog.admin.ai.AiUsageService;
import com.yubai.blog.common.ApiResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 4A-6：AI 用量汇总（仪表盘用量卡片数据源，4D 消费）。 */
@RestController
@RequestMapping("/api/v1/admin/ai/usage")
@Validated
public class AdminAiUsageController {

    public record UsageSummaryItem(
        long providerId, String providerName, long requests,
        long promptTokens, long completionTokens, long errors
    ) {}

    private final AiUsageService usageService;
    private final AiProviderRepository providerRepository;

    public AdminAiUsageController(AiUsageService usageService, AiProviderRepository providerRepository) {
        this.usageService = usageService;
        this.providerRepository = providerRepository;
    }

    @GetMapping
    public ApiResponse<List<UsageSummaryItem>> summary(
        @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days
    ) {
        Map<Long, String> names = providerRepository.findAll().stream()
            .collect(Collectors.toMap(AiProviderEntity::getId, AiProviderEntity::getName));
        var items = usageService.summarize(days).stream()
            .map(row -> new UsageSummaryItem(
                row.getProviderId(),
                names.getOrDefault(row.getProviderId(), "已删除供应商 #" + row.getProviderId()),
                row.getRequests(), row.getPromptTokens(), row.getCompletionTokens(), row.getErrors()))
            .toList();
        return ApiResponse.ok(items);
    }
}
