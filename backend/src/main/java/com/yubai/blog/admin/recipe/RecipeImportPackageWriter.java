package com.yubai.blog.admin.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.dish.DishImportPreviewResponse;
import com.yubai.blog.dish.DishImportService;
import com.yubai.blog.dish.InvalidRecipeException;
import com.yubai.blog.dish.YrecipePackage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

/** Serializes a validated recipe package and hands it to the normal import boundary. */
@Component
public class RecipeImportPackageWriter {
    private static final byte[] ONE_PX_JPEG =
            Base64.getDecoder()
                    .decode(
                            "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/xAAUAQEAAAAAAAAAAAAAAAAAAAAA/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8AKwA=");

    private final DishImportService dishImportService;
    private final ObjectMapper mapper;

    public RecipeImportPackageWriter(DishImportService dishImportService, ObjectMapper mapper) {
        this.dishImportService = dishImportService;
        this.mapper = mapper;
    }

    DishImportPreviewResponse write(
            YrecipePackage yrecipe, byte[] coverBytes, String coverMediaType) {
        try {
            var archive = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(archive, java.nio.charset.StandardCharsets.UTF_8)) {
                var jsonBytes = mapper.writeValueAsBytes(yrecipe);
                var jsonEntry = new ZipEntry("recipe.json");
                jsonEntry.setSize(jsonBytes.length);
                zip.putNextEntry(jsonEntry);
                zip.write(jsonBytes);
                zip.closeEntry();

                var actualCover =
                        coverBytes == null || coverBytes.length == 0 ? ONE_PX_JPEG : coverBytes;
                var coverEntry = new ZipEntry(yrecipe.cover().path());
                coverEntry.setSize(actualCover.length);
                zip.putNextEntry(coverEntry);
                zip.write(actualCover);
                zip.closeEntry();
            }
            return dishImportService.previewFromBytes(archive.toByteArray());
        } catch (IOException exception) {
            throw new InvalidRecipeException("生成菜谱包失败: " + exception.getMessage());
        }
    }
}
