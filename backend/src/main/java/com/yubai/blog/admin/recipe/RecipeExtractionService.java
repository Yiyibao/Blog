package com.yubai.blog.admin.recipe;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.admin.ai.AiChatService;
import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.admin.ai.ChatMessage;
import com.yubai.blog.admin.ai.ChatRequest;
import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.AiProperties;
import com.yubai.blog.dish.DishImportPreviewResponse;
import com.yubai.blog.dish.DishImportService;
import com.yubai.blog.dish.InvalidRecipeException;
import com.yubai.blog.dish.YrecipePackage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RecipeExtractionService {
    private static final Logger log = LoggerFactory.getLogger(RecipeExtractionService.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private static final int HTTP_TIMEOUT_SECONDS = 15;
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_URL_LENGTH = 2048;
    private static final byte[] ONE_PX_JPEG =
            Base64.getDecoder()
                    .decode(
                            "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/xAAUAQEAAAAAAAAAAAAAAAAAAAAA/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8AKwA=");

    private final RecipeExtractionJobRepository jobRepository;
    private final AiChatService chatService;
    private final DishImportService dishImportService;
    private final RecipeUrlValidator urlValidator;
    private final VideoRecipeSourceExtractor videoExtractor;
    private final AiProperties aiProperties;
    private final ExecutorService executor;
    private final ScheduledExecutorService timeoutScheduler;
    private final ConcurrentHashMap<Long, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public RecipeExtractionService(
            RecipeExtractionJobRepository jobRepository,
            AiChatService chatService,
            DishImportService dishImportService,
            RecipeUrlValidator urlValidator,
            VideoRecipeSourceExtractor videoExtractor,
            AiProperties aiProperties,
            @Qualifier("recipeExtractionExecutor") ExecutorService executor,
            @Qualifier("recipeExtractionTimeoutScheduler")
                    ScheduledExecutorService timeoutScheduler) {
        this.jobRepository = jobRepository;
        this.chatService = chatService;
        this.dishImportService = dishImportService;
        this.urlValidator = urlValidator;
        this.videoExtractor = videoExtractor;
        this.aiProperties = aiProperties;
        this.executor = executor;
        this.timeoutScheduler = timeoutScheduler;
    }

    @Transactional
    public RecipeExtractionResponse create(RecipeExtractionRequest request) {
        var entity =
                new RecipeExtractionJobEntity(
                        RecipeExtractionJobEntity.SourceType.valueOf(request.sourceType()),
                        request.sourceContent(),
                        request.providerId(),
                        request.model());
        entity = jobRepository.save(entity);
        var jobId = entity.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            submit(jobId);
                        }
                    });
        } else {
            submit(jobId);
        }
        return RecipeExtractionResponse.from(entity, null);
    }

    void execute(long jobId) {
        var entity = jobRepository.findById(jobId).orElse(null);
        if (entity == null
                || !entity.getStatus().equals(RecipeExtractionJobEntity.Status.QUEUED.name()))
            return;
        try {
            entity.start();
            entity.updateStage("正在获取内容…", 10);
            entity = jobRepository.save(entity);

            var source =
                    switch (entity.getSourceType()) {
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
                        default ->
                                new SourceMaterial(
                                        entity.getSourceContent(), null, null, null, null, null);
                    };
            ensureActive(jobId);

            entity.updateStage("正在调用 AI 提取菜谱…", 30);
            entity = jobRepository.save(entity);

            var aiResult = callAiForRecipe(entity, source.text());
            ensureActive(jobId);

            entity.updateStage("正在验证结果…", 70);
            entity = jobRepository.save(entity);

            var yrecipe = parseAndValidate(aiResult, entity, source);

            entity.updateStage("正在生成导入包…", 85);
            entity = jobRepository.save(entity);

            var importPreview =
                    storeAsYrecipe(yrecipe, source.coverBytes(), source.coverMediaType());
            ensureActive(jobId);

            entity.succeed(importPreview.token());
            jobRepository.save(entity);
        } catch (Exception e) {
            log.error("Recipe extraction failed for job {}: {}", jobId, e.toString());
            String safeMessage =
                    e instanceof InvalidRecipeException
                            ? e.getMessage()
                            : (e instanceof AiServiceException ? e.getMessage() : "提取菜谱失败，请稍后重试");
            var current = jobRepository.findById(jobId).orElse(null);
            if (current != null
                    && !current.getStatus()
                            .equals(RecipeExtractionJobEntity.Status.CANCELLED.name())) {
                current.fail(safeMessage);
                jobRepository.save(current);
            }
        } finally {
            runningTasks.remove(jobId);
        }
    }

    @Transactional(readOnly = true)
    public RecipeExtractionResponse getJob(Long id) {
        var entity = jobRepository.findById(id).orElseThrow(() -> new NotFoundException("提取任务不存在"));
        DishImportPreviewResponse importPreview =
                entity.getResultImportToken() == null
                        ? null
                        : dishImportService.getStagedPreview(entity.getResultImportToken());
        return RecipeExtractionResponse.from(entity, toPreview(importPreview));
    }

    @Transactional
    public void cancelJob(Long id) {
        var entity = jobRepository.findById(id).orElseThrow(() -> new NotFoundException("提取任务不存在"));
        if (entity.getStatus().equals(RecipeExtractionJobEntity.Status.QUEUED.name())
                || entity.getStatus().equals(RecipeExtractionJobEntity.Status.RUNNING.name())) {
            entity.cancel();
            jobRepository.save(entity);
            var task = runningTasks.remove(id);
            if (task != null) task.cancel(true);
        }
    }

    @Transactional
    public RecipeExtractionResponse retryJob(Long id) {
        var entity = jobRepository.findById(id).orElseThrow(() -> new NotFoundException("提取任务不存在"));
        if (!entity.getStatus().equals(RecipeExtractionJobEntity.Status.FAILED.name())) {
            throw new InvalidRecipeException("只有失败的任务可以重试");
        }
        entity.retry();
        entity = jobRepository.save(entity);
        var jobId = entity.getId();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        submit(jobId);
                    }
                });
        return RecipeExtractionResponse.from(entity, null);
    }

    private void submit(long jobId) {
        var task =
                new FutureTask<Void>(
                        () -> {
                            execute(jobId);
                            return null;
                        });
        runningTasks.put(jobId, task);
        try {
            executor.execute(task);
            timeoutScheduler.schedule(
                    () -> {
                        var runningTask = runningTasks.get(jobId);
                        if (runningTask != null && !runningTask.isDone()) {
                            runningTask.cancel(true);
                            var current = jobRepository.findById(jobId).orElse(null);
                            if (current != null
                                    && current.getStatus()
                                            .equals(
                                                    RecipeExtractionJobEntity.Status.RUNNING
                                                            .name())) {
                                current.fail("提取任务超时，请重试");
                                jobRepository.save(current);
                            }
                        }
                    },
                    3,
                    TimeUnit.MINUTES);
        } catch (RejectedExecutionException exception) {
            runningTasks.remove(jobId, task);
            var entity = jobRepository.findById(jobId).orElse(null);
            if (entity != null) {
                entity.fail("当前提取任务过多，请稍后重试");
                jobRepository.save(entity);
            }
        }
    }

    private void ensureActive(long jobId) {
        if (Thread.currentThread().isInterrupted()) throw new InvalidRecipeException("提取任务已取消");
        var status =
                jobRepository.findById(jobId).map(RecipeExtractionJobEntity::getStatus).orElse("");
        if (status.equals(RecipeExtractionJobEntity.Status.CANCELLED.name())) {
            throw new InvalidRecipeException("提取任务已取消");
        }
    }

    private static RecipeExtractionResponse.ImportPreview toPreview(
            DishImportPreviewResponse preview) {
        return preview == null
                ? null
                : new RecipeExtractionResponse.ImportPreview(
                        preview.token(),
                        preview.expiresAt(),
                        preview.recipe(),
                        preview.warnings(),
                        preview.categoryMatch(),
                        preview.slugAvailable(),
                        preview.coverPreviewUrl());
    }

    String fetchWebContent(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
            throw new InvalidRecipeException("URL 不合法");
        }
        URI uri = urlValidator.validatePublicHttps(url);

        String html;
        try {
            var request =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                            .header("User-Agent", "Mozilla/5.0 (compatible; BlogBot/1.0)")
                            .header("Accept", "text/html,application/xhtml+xml,application/ld+json")
                            .GET()
                            .build();
            var response = httpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status >= 300) {
                throw new InvalidRecipeException("页面返回 " + status + "，无法获取内容");
            }
            var bodyBytes = response.body();
            if (bodyBytes.length > MAX_RESPONSE_BYTES) {
                throw new InvalidRecipeException("页面内容过大");
            }
            html = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (InvalidRecipeException e) {
            throw e;
        } catch (IOException e) {
            throw new InvalidRecipeException("无法获取页面内容: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidRecipeException("请求被中断");
        }

        Document doc = Jsoup.parse(html);

        var ldJson = doc.select("script[type=\"application/ld+json\"]");
        for (var script : ldJson) {
            try {
                var root = MAPPER.readTree(script.data());
                var recipeNode = findRecipeNode(root);
                if (recipeNode != null) {
                    var extracted = extractFromSchemaOrg(recipeNode);
                    if (extracted != null) return extracted;
                }
            } catch (Exception ignored) {
            }
        }

        return extractVisibleText(doc);
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .build();
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
        var name = recipe.get("name");
        if (name != null) sb.append("菜谱名称: ").append(name.asText()).append("\n");
        var description = recipe.get("description");
        if (description != null) sb.append("简介: ").append(description.asText()).append("\n");
        var author = recipe.get("author");
        if (author != null) {
            String authorName =
                    author.isObject() ? author.get("name").asText("") : author.asText("");
            if (!authorName.isBlank()) sb.append("作者: ").append(authorName).append("\n");
        }
        var prepTime = recipe.get("prepTime");
        if (prepTime != null) {
            var minutes = parseIsoDuration(prepTime.asText());
            if (minutes > 0) sb.append("准备时间: ").append(minutes).append(" 分钟\n");
        }
        var cookTime = recipe.get("cookTime");
        if (cookTime != null) {
            var minutes = parseIsoDuration(cookTime.asText());
            if (minutes > 0) sb.append("烹饪时间: ").append(minutes).append(" 分钟\n");
        }
        var totalTime = recipe.get("totalTime");
        if (totalTime != null) {
            var minutes = parseIsoDuration(totalTime.asText());
            if (minutes > 0) sb.append("总时间: ").append(minutes).append(" 分钟\n");
        }
        var recipeYield = recipe.get("recipeYield");
        if (recipeYield != null) {
            String yield =
                    recipeYield.isArray() ? recipeYield.get(0).asText("") : recipeYield.asText("");
            if (!yield.isBlank()) sb.append("份量: ").append(yield).append("\n");
        }
        var recipeCategory = recipe.get("recipeCategory");
        if (recipeCategory != null) sb.append("分类: ").append(recipeCategory.asText()).append("\n");
        var recipeCuisine = recipe.get("recipeCuisine");
        if (recipeCuisine != null) sb.append("菜系: ").append(recipeCuisine.asText()).append("\n");
        var image = recipe.get("image");
        if (image != null) {
            String imgUrl = image.isArray() ? image.get(0).asText("") : image.asText("");
            if (!imgUrl.isBlank()) sb.append("图片: ").append(imgUrl).append("\n");
        }
        var keywords = recipe.get("keywords");
        if (keywords != null) sb.append("关键词: ").append(keywords.asText()).append("\n");

        var ingredients = recipe.get("recipeIngredient");
        if (ingredients != null && ingredients.isArray()) {
            sb.append("\n食材:\n");
            for (var ing : ingredients) {
                sb.append("- ").append(ing.asText()).append("\n");
            }
        }
        var instructions = recipe.get("recipeInstructions");
        if (instructions != null) {
            sb.append("\n步骤:\n");
            if (instructions.isArray()) {
                int stepNum = 1;
                for (var step : instructions) {
                    String text;
                    if (step.isObject()) {
                        text = step.get("text") != null ? step.get("text").asText() : "";
                    } else {
                        text = step.asText();
                    }
                    if (!text.isBlank()) {
                        sb.append(stepNum).append(". ").append(text).append("\n");
                        stepNum++;
                    }
                }
            }
        }
        return sb.toString().isBlank() ? null : sb.toString();
    }

    private static long parseIsoDuration(String duration) {
        try {
            if (duration != null) {
                duration = duration.trim();
                if (duration.startsWith("PT") && duration.endsWith("M")) {
                    var num = duration.substring(2, duration.length() - 1);
                    return Long.parseLong(num);
                }
                if (duration.startsWith("PT") && duration.contains("H")) {
                    var parsed = java.time.Duration.parse(duration);
                    return parsed.toMinutes();
                }
            }
        } catch (Exception ignored) {
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

        var recipeHeadings = doc.select("h1, h2, h3");
        for (var h : recipeHeadings) {
            var text = h.text().trim();
            if (!text.isBlank()) sb.append(h.tagName()).append(": ").append(text).append("\n");
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
        } else {
            var body = doc.body();
            if (body != null) {
                var text = body.text().trim();
                if (!text.isBlank()) sb.append(text);
            }
        }
        var result = sb.toString();
        if (result.length() > 30000) {
            result = result.substring(0, 30000);
        }
        return result;
    }

    private String callAiForRecipe(RecipeExtractionJobEntity entity, String sourceText) {
        if (sourceText.isBlank()) {
            throw new InvalidRecipeException("未获取到有效的菜谱内容");
        }

        var prompt = buildPrompt(sourceText);
        var messages = List.of(new ChatMessage("user", prompt));
        var request = new ChatRequest(messages, entity.getProviderId(), entity.getModel());

        var response = chatService.chat(request);
        var content = response.content();
        if (content == null || content.isBlank()) {
            throw new InvalidRecipeException("AI 未返回有效内容");
        }
        return content;
    }

    private String buildPrompt(String sourceText) {
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
            String aiResponse, RecipeExtractionJobEntity entity, SourceMaterial source) {
        String json = aiResponse.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start, end).trim();
            }
        }

        JsonNode recipeNode;
        try {
            recipeNode = MAPPER.readTree(json);
        } catch (Exception e) {
            var braceStart = json.indexOf('{');
            var braceEnd = json.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = json.substring(braceStart, braceEnd + 1);
                try {
                    recipeNode = MAPPER.readTree(json);
                } catch (Exception e2) {
                    throw new InvalidRecipeException("AI 返回的 JSON 格式不合法");
                }
            } else {
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
        for (var ing : ingredients) {
            var text = ing.asText("").trim();
            if (!text.isBlank()) ingredientList.add(text);
        }
        var stepList = new ArrayList<String>();
        for (var step : steps) {
            var text = step.asText("").trim();
            if (!text.isBlank()) stepList.add(text);
        }

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

        var packageId = UUID.randomUUID().toString();
        var coverPath = "assets/cover." + coverExtension(source.coverMediaType());
        var sourceType = source.sourceUrl() == null ? "ai" : "website";
        return new YrecipePackage(
                "1.0",
                "yubai.recipe",
                packageId,
                recipeContent,
                new YrecipePackage.YrecipeCover(coverPath, name.asText()),
                new YrecipePackage.YrecipeSource(
                        sourceType,
                        source.sourceUrl(),
                        source.title(),
                        source.creator(),
                        Instant.now().getEpochSecond()),
                new YrecipePackage.YrecipeGeneration(
                        "yubai-recipe-extractor",
                        entity.getProviderId() != null
                                ? String.valueOf(entity.getProviderId())
                                : null,
                        entity.getModel(),
                        Instant.now().toString(),
                        0.8,
                        null));
    }

    private DishImportPreviewResponse storeAsYrecipe(
            YrecipePackage yrecipe, byte[] coverBytes, String coverMediaType) {
        try {
            var baos = new ByteArrayOutputStream();
            try (var zos = new ZipOutputStream(baos, java.nio.charset.StandardCharsets.UTF_8)) {
                var jsonBytes = MAPPER.writeValueAsBytes(yrecipe);
                var jsonEntry = new ZipEntry("recipe.json");
                jsonEntry.setSize(jsonBytes.length);
                zos.putNextEntry(jsonEntry);
                zos.write(jsonBytes);
                zos.closeEntry();

                var actualCover =
                        coverBytes == null || coverBytes.length == 0 ? ONE_PX_JPEG : coverBytes;
                var coverEntry = new ZipEntry(yrecipe.cover().path());
                coverEntry.setSize(actualCover.length);
                zos.putNextEntry(coverEntry);
                zos.write(actualCover);
                zos.closeEntry();
            }
            var zipData = baos.toByteArray();
            return dishImportService.previewFromBytes(zipData);
        } catch (IOException e) {
            throw new InvalidRecipeException("生成菜谱包失败: " + e.getMessage());
        }
    }

    private static String coverExtension(String mediaType) {
        if ("image/png".equals(mediaType)) return "png";
        if ("image/webp".equals(mediaType)) return "webp";
        return "jpg";
    }

    private record SourceMaterial(
            String text,
            String sourceUrl,
            String title,
            String creator,
            byte[] coverBytes,
            String coverMediaType) {}
}
