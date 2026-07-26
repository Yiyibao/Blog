package com.yubai.blog.quote;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/quotes")
public class QuoteController {
    /** NB-6："今天"以站点受众时区为准，而非服务器 UTC。 */
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Shanghai");

    private final QuoteService service;

    public QuoteController(QuoteService service) {
        this.service = service;
    }

    @GetMapping("/daily")
    public ApiResponse<List<QuoteResponse>> getDaily() {
        // 轮转在缓存代理之外做——findAll 走 P1-5 缓存，轮转本身零成本
        return ApiResponse.ok(QuoteService.rotateForDay(service.findAll(), LocalDate.now(SITE_ZONE)));
    }
}
