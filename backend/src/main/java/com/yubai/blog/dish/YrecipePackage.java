package com.yubai.blog.dish;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record YrecipePackage(
        @JsonProperty(required = true) String schemaVersion,
        @JsonProperty(required = true) String kind,
        @JsonProperty(required = true) String packageId,
        @JsonProperty(required = true) YrecipeContent recipe,
        @JsonProperty(required = true) YrecipeCover cover,
        YrecipeSource source,
        YrecipeGeneration generation) {
    public record YrecipeContent(
            @JsonProperty(required = true) String name,
            String slug,
            @JsonProperty(required = true) String summary,
            String categoryHint,
            int prepMinutes,
            String difficulty,
            int baseServings,
            @JsonProperty(required = true) List<String> ingredients,
            @JsonProperty(required = true) List<String> steps) {}

    public record YrecipeCover(@JsonProperty(required = true) String path, String alt) {}

    public record YrecipeSource(
            String type, String url, String title, String creator, Long capturedAtSeconds) {}

    public record YrecipeGeneration(
            String generator,
            String provider,
            String model,
            String createdAt,
            Double confidence,
            List<String> warnings) {}
}
