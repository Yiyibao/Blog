package com.yubai.blog.series;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.series.SeriesDtos.PublicSeriesDetail;
import com.yubai.blog.series.SeriesDtos.PublicSeriesSummary;

/** 4B：合集公开读——列表与按序详情（仅已发布合集与已发布成员）。 */
@RestController
@RequestMapping("/api/v1/series")
public class SeriesController {

    private final SeriesService service;

    public SeriesController(SeriesService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<PublicSeriesSummary>> list() {
        return ApiResponse.ok(service.findPublished());
    }

    @GetMapping("/{slug}")
    public ApiResponse<PublicSeriesDetail> detail(@PathVariable String slug) {
        return ApiResponse.ok(service.findPublishedBySlug(slug));
    }
}
