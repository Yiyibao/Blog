package com.yubai.blog.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.series.SeriesDtos.AdminSeriesResponse;
import com.yubai.blog.series.SeriesDtos.SeriesEntriesRequest;
import com.yubai.blog.series.SeriesDtos.SeriesRequest;
import com.yubai.blog.series.SeriesService;

import jakarta.validation.Valid;

/** 4B：合集管理——CRUD + 成员整表排序（乐观锁 version 随行，冲突 409）。 */
@RestController
@RequestMapping("/api/v1/admin/series")
public class AdminSeriesController {

    private final SeriesService service;

    public AdminSeriesController(SeriesService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AdminSeriesResponse>> list() {
        return ApiResponse.ok(service.findAdmin());
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminSeriesResponse> findOne(@PathVariable long id) {
        return ApiResponse.ok(service.findAdminOne(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminSeriesResponse> create(@Valid @RequestBody SeriesRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminSeriesResponse> update(@PathVariable long id,
                                                   @RequestParam long version,
                                                   @Valid @RequestBody SeriesRequest request) {
        return ApiResponse.ok(service.update(id, version, request));
    }

    /** 拖拽排序/增删成员统一入口：提交完整有序成员列表（整表替换）。 */
    @PutMapping("/{id}/entries")
    public ApiResponse<AdminSeriesResponse> setEntries(@PathVariable long id,
                                                       @Valid @RequestBody SeriesEntriesRequest request) {
        return ApiResponse.ok(service.setEntries(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
