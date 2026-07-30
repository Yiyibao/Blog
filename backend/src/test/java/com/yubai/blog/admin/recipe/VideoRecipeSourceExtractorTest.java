package com.yubai.blog.admin.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.config.RecipeExtractionProperties;
import com.yubai.blog.dish.InvalidRecipeException;

class VideoRecipeSourceExtractorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final VideoRecipeSourceExtractor extractor = new VideoRecipeSourceExtractor(
        new RecipeExtractionProperties(true, "yt-dlp", Duration.ofSeconds(10), 2000,
            List.of("bilibili.com")),
        new RecipeUrlValidator(),
        mapper
    );

    @Test
    void cleansVttTimestampsTagsAndDuplicateLines() {
        var raw = """
            WEBVTT

            00:00:00.000 --> 00:00:02.000
            <c>先把鸡肉切块</c>

            00:00:02.000 --> 00:00:04.000
            先把鸡肉切块
            加入生抽腌制
            """;

        assertThat(VideoRecipeSourceExtractor.cleanVtt(raw, 1000))
            .isEqualTo("先把鸡肉切块\n加入生抽腌制");
    }

    @Test
    void buildsAiSourceFromMetadataAndTranscript() throws Exception {
        var metadata = mapper.readTree("""
            {
              "title": "宫保鸡丁教程",
              "uploader": "测试厨师",
              "description": "完整演示宫保鸡丁的准备与烹饪方法，包含食材用量和火候说明。",
              "duration": 180,
              "tags": ["川菜", "鸡肉"]
            }
            """);

        var result = extractor.fromMetadata(metadata,
            "鸡腿肉切丁，加入生抽、料酒和淀粉抓匀腌制。热锅炒香花椒和干辣椒，"
                + "再加入鸡丁炒至变色，放入葱段和花生，最后倒入糖醋调味汁快速翻炒收汁。",
            new VideoRecipeSourceExtractor.CoverData(new byte[] {1, 2, 3}, "image/jpeg"),
            "https://www.bilibili.com/video/BV1test");

        assertThat(result.text()).contains("宫保鸡丁教程", "测试厨师", "字幕/口述内容", "鸡腿肉切丁");
        assertThat(result.creator()).isEqualTo("测试厨师");
        assertThat(result.coverMediaType()).isEqualTo("image/jpeg");
    }

    @Test
    void rejectsMetadataWithoutUsefulDescriptionOrTranscript() throws Exception {
        var metadata = mapper.readTree("{\"title\":\"短视频\",\"description\":\"好吃\"}");

        assertThatThrownBy(() -> extractor.fromMetadata(metadata, "", null,
            "https://www.bilibili.com/video/BV1test"))
            .isInstanceOf(InvalidRecipeException.class)
            .hasMessageContaining("没有足够");
    }
}
