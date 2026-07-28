package com.yubai.blog.dish;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DishImportPreviewResponse(
    UUID token,
    Instant expiresAt,
    YrecipePackage recipe,
    List<String> warnings,
    String categoryMatch,
    boolean slugAvailable,
    String coverPreviewUrl
) {}
