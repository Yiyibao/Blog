package com.yubai.blog.admin.recipe;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.admin.ai.AiChatService;
import com.yubai.blog.admin.ai.ChatMessage;
import com.yubai.blog.admin.ai.ChatRequest;
import com.yubai.blog.config.AiProperties;
import com.yubai.blog.dish.InvalidRecipeException;
import com.yubai.blog.dish.YrecipePackage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Calls the configured AI service and turns its response into a validated recipe package. */
@Component
public class RecipeExtractionPayloadService {
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private final AiChatService chatService;
    private final AiProperties aiProperties;

    public RecipeExtractionPayloadService(AiChatService chatService, AiProperties aiProperties) {
        this.chatService = chatService;
        this.aiProperties = aiProperties;
    }

    YrecipePackage extract(
            RecipeExtractionJobEntity entity, RecipeSourceMaterialService.SourceMaterial source) {
        var sourceText = source.text() == null ? "" : source.text();
        if (sourceText.isBlank()) {
            throw new InvalidRecipeException("未获取到有效的菜谱内容");
        }

        var response =
                chatService.chat(
                        new ChatRequest(
                                List.of(new ChatMessage("user", buildPrompt(sourceText))),
                                entity.getProviderId(),
                                entity.getModel()));
        var content = response.content();
        if (content == null || content.isBlank()) {
            throw new InvalidRecipeException("AI 未返回有效内容");
        }
        return parseAndValidate(content, entity, source);
    }

    String buildPrompt(String sourceText) {
        var sourceLimit = Math.max(1000, aiProperties.getMaxInputChars() - 1500);
        var truncated =
                sourceText.length() > sourceLimit
                        ? sourceText.substring(0, sourceLimit)
                        : sourceText;
        return """
你是一个专业的菜谱提取助手。请从以下文本中提取菜谱信息，**只返回符合 JSON 格式的菜谱数据**，不要包含任何其他文字、代码块标记或说明。

要求：
- name: 菜谱名称（必需，最多 120 字）
- slug: 英文 URL 别名（可选，小写字母+连字符）
- summary: 菜谱简介（必需，最多 1000 字）
- categoryHint: 分类提示（可选，如"家常菜"、"烘焙"等）
- prepMinutes: 准备时间（分钟，1-1440）
- difficulty: 难度（必须为"简单"、"家常"或"进阶"）
- baseServings: 份数（必须 >= 1）
- ingredients: 食材列表（必需，每项最多 240 字，1-30 项）
- steps: 步骤列表（必需，每项最多 2000 字，1-30 项）

返回 JSON 格式：
{
  "name": "...",
  "slug": "...",
  "summary": "...",
  "categoryHint": "...",
  "prepMinutes": 0,
  "difficulty": "家常",
  "baseServings": 2,
  "ingredients": ["..."],
  "steps": ["..."]
}

文本内容：
---
%s
---"""
                .formatted(truncated);
    }

    private YrecipePackage parseAndValidate(
            String aiResponse,
            RecipeExtractionJobEntity entity,
            RecipeSourceMaterialService.SourceMaterial source) {
        String json = aiResponse.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) json = json.substring(start, end).trim();
        }

        JsonNode recipeNode;
        try {
            recipeNode = MAPPER.readTree(json);
        } catch (Exception exception) {
            var braceStart = json.indexOf('{');
            var braceEnd = json.lastIndexOf('}');
            if (braceStart < 0 || braceEnd <= braceStart) {
                throw new InvalidRecipeException("AI 返回的 JSON 格式不合法");
            }
            try {
                recipeNode = MAPPER.readTree(json.substring(braceStart, braceEnd + 1));
            } catch (Exception ignored) {
                throw new InvalidRecipeException("AI 返回的 JSON 格式不合法");
            }
        }

        var name = recipeNode.get("name");
        var summary = recipeNode.get("summary");
        var ingredients = recipeNode.get("ingredients");
        var steps = recipeNode.get("steps");
        if (name == null || name.asText("").isBlank()) throw new InvalidRecipeException("菜谱名称不能为空");
        if (summary == null || summary.asText("").isBlank())
            throw new InvalidRecipeException("菜谱简介不能为空");
        if (ingredients == null || !ingredients.isArray() || ingredients.isEmpty())
            throw new InvalidRecipeException("食材列表不能为空");
        if (steps == null || !steps.isArray() || steps.isEmpty())
            throw new InvalidRecipeException("步骤列表不能为空");

        var ingredientList = new ArrayList<String>();
        for (var ingredient : ingredients) {
            var text = ingredient.asText("").trim();
            if (!text.isBlank()) ingredientList.add(text);
        }
        var stepList = new ArrayList<String>();
        for (var step : steps) {
            var text = step.asText("").trim();
            if (!text.isBlank()) stepList.add(text);
        }
        if (ingredientList.isEmpty()) throw new InvalidRecipeException("食材列表不能为空");
        if (stepList.isEmpty()) throw new InvalidRecipeException("步骤列表不能为空");
        if (ingredientList.size() > 30) throw new InvalidRecipeException("食材数量不能超过 30 项");
        if (stepList.size() > 30) throw new InvalidRecipeException("步骤数量不能超过 30 项");

        var recipeContent =
                new YrecipePackage.YrecipeContent(
                        name.asText().trim(),
                        recipeNode.has("slug") ? recipeNode.get("slug").asText("").trim() : null,
                        summary.asText().trim(),
                        recipeNode.has("categoryHint")
                                ? recipeNode.get("categoryHint").asText("").trim()
                                : null,
                        recipeNode.has("prepMinutes")
                                ? recipeNode.get("prepMinutes").asInt(30)
                                : 30,
                        recipeNode.has("difficulty")
                                ? recipeNode.get("difficulty").asText("家常")
                                : "家常",
                        recipeNode.has("baseServings")
                                ? recipeNode.get("baseServings").asInt(2)
                                : 2,
                        ingredientList,
                        stepList);
        var coverExtension = coverExtension(source.coverMediaType());
        return new YrecipePackage(
                "1.0",
                "yubai.recipe",
                UUID.randomUUID().toString(),
                recipeContent,
                new YrecipePackage.YrecipeCover("assets/cover." + coverExtension, name.asText()),
                new YrecipePackage.YrecipeSource(
                        source.sourceUrl() == null ? "ai" : "website",
                        source.sourceUrl(),
                        source.title(),
                        source.creator(),
                        Instant.now().getEpochSecond()),
                new YrecipePackage.YrecipeGeneration(
                        "yubai-recipe-extractor",
                        entity.getProviderId() == null
                                ? null
                                : String.valueOf(entity.getProviderId()),
                        entity.getModel(),
                        Instant.now().toString(),
                        0.8,
                        null));
    }

    private static String coverExtension(String mediaType) {
        if ("image/png".equals(mediaType)) return "png";
        if ("image/webp".equals(mediaType)) return "webp";
        return "jpg";
    }
}
