package com.yubai.blog.admin;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.dish.DishImportCommitRequest;
import com.yubai.blog.dish.DishImportPreviewResponse;
import com.yubai.blog.dish.DishImportService;
import com.yubai.blog.dish.DishResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/dish-imports")
@Validated
public class AdminDishImportController {
    private final DishImportService importService;

    public AdminDishImportController(DishImportService importService) {
        this.importService = importService;
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DishImportPreviewResponse> preview(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(importService.preview(file));
    }

    @PostMapping("/{token}/commit")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DishResponse> commit(@PathVariable UUID token,
                                            @Valid @RequestBody DishImportCommitRequest request) {
        return ApiResponse.created(importService.commit(token, request));
    }

    @DeleteMapping("/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID token) {
        importService.cancel(token);
    }

    @GetMapping("/{token}/cover")
    public ResponseEntity<byte[]> stagedCover(@PathVariable UUID token) {
        var data = importService.readStagedCover(token);
        var mediaType = importService.getStagedMediaType(token);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mediaType != null ? mediaType : "image/jpeg"))
            .cacheControl(CacheControl.noStore())
            .header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(data);
    }
}
