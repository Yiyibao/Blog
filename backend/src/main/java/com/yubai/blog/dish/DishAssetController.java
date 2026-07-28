package com.yubai.blog.dish;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.NotFoundException;

@RestController
@RequestMapping("/api/v1/dish-assets")
public class DishAssetController {
    private final DishAssetService assetService;

    public DishAssetController(DishAssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<byte[]> get(@PathVariable UUID publicId,
                                       @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        var asset = assetService.findByPublicId(publicId);
        if (asset.getDishId() == null) {
            throw new NotFoundException("图片不存在");
        }
        if (ifNoneMatch != null && ("\"" + asset.getSha256() + "\"").equals(ifNoneMatch)) {
            return ResponseEntity.status(304).build();
        }
        byte[] data;
        try {
            data = assetService.readContent(publicId);
        } catch (Exception e) {
            throw new NotFoundException("图片内容不存在");
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(asset.getMediaType()))
            .contentLength(asset.getByteSize())
            .eTag("\"" + asset.getSha256() + "\"")
            .header("Cache-Control", "public, max-age=31536000, immutable")
            .header("X-Content-Type-Options", "nosniff")
            .body(data);
    }
}
