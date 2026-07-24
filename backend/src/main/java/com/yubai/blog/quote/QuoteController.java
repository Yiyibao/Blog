package com.yubai.blog.quote;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/quotes")
public class QuoteController {

    private final QuoteService service;

    public QuoteController(QuoteService service) {
        this.service = service;
    }

    @GetMapping("/daily")
    public ApiResponse<List<QuoteResponse>> getDaily() {
        return ApiResponse.ok(service.findAll());
    }
}
