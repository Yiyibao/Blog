package com.yubai.blog.admin.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RecipeSourceMaterialServiceTest {
    private final RecipeSourceHttpClient httpClient = mock(RecipeSourceHttpClient.class);
    private final VideoRecipeSourceExtractor videoExtractor =
            mock(VideoRecipeSourceExtractor.class);
    private final RecipeSourceMaterialService service =
            new RecipeSourceMaterialService(new ObjectMapper(), httpClient, videoExtractor);

    @Test
    void extractsSchemaOrgRecipeAndOmitsNavigation() {
        when(httpClient.fetch("https://example.com/recipe"))
                .thenReturn(
                        """
                        <html><head><title>Recipe</title><script type="application/ld+json">
                        {"@type":"Recipe","name":"番茄炒蛋","recipeIngredient":["番茄"],"recipeInstructions":[{"text":"翻炒"}]}
                        </script></head><body><nav>不要发送</nav><main>正文</main></body></html>
                        """);

        var text = service.fetchWebContent("https://example.com/recipe");

        assertThat(text).contains("菜谱名称: 番茄炒蛋", "- 番茄", "1. 翻炒").doesNotContain("不要发送");
    }

    @Test
    void fallsBackToVisibleTextWhenSchemaOrgIsMalformed() {
        when(httpClient.fetch("https://example.com/plain"))
                .thenReturn(
                        "<html><head><script type=\"application/ld+json\">not-json</script></head>"
                                + "<body><h1>可见菜谱</h1><p>正文内容</p></body></html>");

        assertThat(service.fetchWebContent("https://example.com/plain"))
                .contains("h1: 可见菜谱", "正文内容");
    }
}
