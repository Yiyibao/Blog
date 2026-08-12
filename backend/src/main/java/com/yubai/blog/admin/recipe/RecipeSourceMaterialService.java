package com.yubai.blog.admin.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

/** Loads and normalizes recipe source material without holding a database transaction. */
@Component
public class RecipeSourceMaterialService {
    private final ObjectMapper mapper;
    private final RecipeSourceHttpClient sourceHttpClient;
    private final VideoRecipeSourceExtractor videoExtractor;

    public RecipeSourceMaterialService(
            ObjectMapper mapper,
            RecipeSourceHttpClient sourceHttpClient,
            VideoRecipeSourceExtractor videoExtractor) {
        this.mapper = mapper;
        this.sourceHttpClient = sourceHttpClient;
        this.videoExtractor = videoExtractor;
    }

    SourceMaterial load(RecipeExtractionJobEntity entity) {
        return switch (entity.getSourceType()) {
            case "WEB_URL" ->
                    new SourceMaterial(
                            fetchWebContent(entity.getSourceContent()),
                            entity.getSourceContent(),
                            null,
                            null,
                            null,
                            null);
            case "VIDEO_URL" -> {
                var video = videoExtractor.extract(entity.getSourceContent());
                yield new SourceMaterial(
                        video.text(),
                        video.sourceUrl(),
                        video.title(),
                        video.creator(),
                        video.coverBytes(),
                        video.coverMediaType());
            }
            default -> new SourceMaterial(entity.getSourceContent(), null, null, null, null, null);
        };
    }

    String fetchWebContent(String url) {
        String html = sourceHttpClient.fetch(url);
        Document doc = Jsoup.parse(html);

        var ldJson = doc.select("script[type=\"application/ld+json\"]");
        for (var script : ldJson) {
            try {
                var root = mapper.readTree(script.data());
                var recipeNode = findRecipeNode(root);
                if (recipeNode != null) {
                    var extracted = extractFromSchemaOrg(recipeNode);
                    if (extracted != null) return extracted;
                }
            } catch (Exception ignored) {
                // A malformed JSON-LD block must not hide usable visible page text.
            }
        }

        return extractVisibleText(doc);
    }

    private JsonNode findRecipeNode(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) {
            for (var item : node) {
                var found = findRecipeNode(item);
                if (found != null) return found;
            }
            return null;
        }
        var type = node.get("@type");
        if (type != null) {
            String typeStr = type.isArray() ? type.get(0).asText("") : type.asText("");
            if ("Recipe".equals(typeStr)) return node;
        }
        var graph = node.get("@graph");
        if (graph != null && graph.isArray()) {
            for (var item : graph) {
                var found = findRecipeNode(item);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String extractFromSchemaOrg(JsonNode recipe) {
        var sb = new StringBuilder();
        appendText(sb, recipe, "name", "菜谱名称");
        appendText(sb, recipe, "description", "简介");

        var author = recipe.get("author");
        if (author != null) {
            String authorName =
                    author.isObject() ? author.path("name").asText("") : author.asText("");
            if (!authorName.isBlank()) sb.append("作者: ").append(authorName).append('\n');
        }
        appendDuration(sb, recipe, "prepTime", "准备时间");
        appendDuration(sb, recipe, "cookTime", "烹饪时间");
        appendDuration(sb, recipe, "totalTime", "总时间");
        appendFirstText(sb, recipe, "recipeYield", "份量");
        appendText(sb, recipe, "recipeCategory", "分类");
        appendText(sb, recipe, "recipeCuisine", "菜系");
        appendFirstText(sb, recipe, "image", "图片");
        appendText(sb, recipe, "keywords", "关键词");

        var ingredients = recipe.get("recipeIngredient");
        if (ingredients != null && ingredients.isArray()) {
            sb.append("\n食材:\n");
            for (var ingredient : ingredients) {
                sb.append("- ").append(ingredient.asText()).append('\n');
            }
        }

        var instructions = recipe.get("recipeInstructions");
        if (instructions != null) {
            sb.append("\n步骤:\n");
            if (instructions.isArray()) {
                int stepNumber = 1;
                for (var instruction : instructions) {
                    String text =
                            instruction.isObject()
                                    ? instruction.path("text").asText("")
                                    : instruction.asText();
                    if (!text.isBlank()) {
                        sb.append(stepNumber++).append(". ").append(text).append('\n');
                    }
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void appendText(
            StringBuilder builder, JsonNode node, String field, String label) {
        var value = node.get(field);
        if (value != null) builder.append(label).append(": ").append(value.asText()).append('\n');
    }

    private static void appendFirstText(
            StringBuilder builder, JsonNode node, String field, String label) {
        var value = node.get(field);
        if (value == null) return;
        String text = value.isArray() ? value.path(0).asText("") : value.asText("");
        if (!text.isBlank()) builder.append(label).append(": ").append(text).append('\n');
    }

    private static void appendDuration(
            StringBuilder builder, JsonNode node, String field, String label) {
        var value = node.get(field);
        if (value == null) return;
        var minutes = parseIsoDuration(value.asText());
        if (minutes > 0) builder.append(label).append(": ").append(minutes).append(" 分钟\n");
    }

    private static long parseIsoDuration(String duration) {
        try {
            if (duration != null) {
                duration = duration.trim();
                if (duration.startsWith("PT") && duration.endsWith("M")) {
                    return Long.parseLong(duration.substring(2, duration.length() - 1));
                }
                if (duration.startsWith("PT") && duration.contains("H")) {
                    return Duration.parse(duration).toMinutes();
                }
            }
        } catch (Exception ignored) {
            // Invalid optional metadata is ignored; the model can still use the remaining text.
        }
        return 0;
    }

    private String extractVisibleText(Document doc) {
        doc.select(
                        "script, style, nav, footer, header, aside, .sidebar, .comments, .comment, .ad, .ads, .advertisement, noscript")
                .remove();
        var sb = new StringBuilder();
        var title = doc.title();
        if (!title.isBlank()) sb.append("标题: ").append(title).append("\n\n");

        for (var heading : doc.select("h1, h2, h3")) {
            var text = heading.text().trim();
            if (!text.isBlank())
                sb.append(heading.tagName()).append(": ").append(text).append('\n');
        }

        var recipeSections =
                doc.select(
                        ".recipe, .recipe-content, .entry-content, .post-content, .article-content, "
                                + "[class*=recipe], [class*=ingredient], [class*=instruction], [class*=direction], "
                                + "[class*=method], [class*=step]");
        if (!recipeSections.isEmpty()) {
            for (var section : recipeSections) {
                var text = section.text().trim();
                if (!text.isBlank())
                    sb.append(section.tagName()).append(": ").append(text).append("\n\n");
            }
        } else if (doc.body() != null) {
            var text = doc.body().text().trim();
            if (!text.isBlank()) sb.append(text);
        }

        var result = sb.toString();
        return result.length() > 30_000 ? result.substring(0, 30_000) : result;
    }

    record SourceMaterial(
            String text,
            String sourceUrl,
            String title,
            String creator,
            byte[] coverBytes,
            String coverMediaType) {}
}
