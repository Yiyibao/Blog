package com.yubai.blog.post;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 3A-2：转换器快照——常规结构逐类断言，高风险结构必须落进风险清单。 */
class HtmlToMarkdownConverterTest {

    private final HtmlToMarkdownConverter converter = new HtmlToMarkdownConverter();

    @Test
    void convertsHeadingsParagraphsAndInlineMarks() {
        var result = converter.convert(
            "<h2>章节</h2><p>这是 <strong>加粗</strong>、<em>斜体</em> 与 <code>行内代码</code>，"
                + "还有 <a href=\"https://example.com\">链接</a>。</p>");

        assertThat(result.markdown()).contains("## 章节");
        assertThat(result.markdown()).contains("**加粗**");
        assertThat(result.markdown()).contains("*斜体*");
        assertThat(result.markdown()).contains("`行内代码`");
        assertThat(result.markdown()).contains("[链接](https://example.com)");
        assertThat(result.risks()).isEmpty();
    }

    @Test
    void convertsCodeBlockWithLanguageClass() {
        var result = converter.convert(
            "<pre><code class=\"language-java\">int a = 1;\nint b = 2;</code></pre>");

        assertThat(result.markdown()).contains("```java\nint a = 1;\nint b = 2;\n```");
        assertThat(result.risks()).isEmpty();
    }

    @Test
    void convertsListsAndFlagsNestedOnes() {
        var flat = converter.convert("<ul><li>甲</li><li>乙</li></ul><ol><li>一</li><li>二</li></ol>");
        assertThat(flat.markdown()).contains("- 甲");
        assertThat(flat.markdown()).contains("1. 一");
        assertThat(flat.markdown()).contains("2. 二");
        assertThat(flat.risks()).isEmpty();

        var nested = converter.convert("<ul><li>外层<ul><li>内层</li></ul></li></ul>");
        assertThat(nested.markdown()).contains("- 外层");
        assertThat(nested.markdown()).contains("  - 内层");
        assertThat(nested.risks()).anyMatch(risk -> risk.contains("嵌套列表"));
    }

    @Test
    void convertsTableToGfmAndFlagsIt() {
        var result = converter.convert(
            "<table><thead><tr><th>名称</th><th>值</th></tr></thead>"
                + "<tbody><tr><td>甲</td><td>1</td></tr></tbody></table>");

        assertThat(result.markdown()).contains("| 名称 | 值 |");
        assertThat(result.markdown()).contains("| --- | --- |");
        assertThat(result.markdown()).contains("| 甲 | 1 |");
        assertThat(result.risks()).anyMatch(risk -> risk.contains("表格"));
    }

    @Test
    void flagsFormulaClassesAndUnknownInlineTags() {
        var math = converter.convert("<p class=\"katex-block\">E = mc^2</p>");
        assertThat(math.risks()).anyMatch(risk -> risk.contains("公式"));

        var sup = converter.convert("<p>x<sup>2</sup></p>");
        assertThat(sup.markdown()).contains("x2");
        assertThat(sup.risks()).anyMatch(risk -> risk.contains("<sup>"));
    }

    @Test
    void convertsBlockquoteImageAndRule() {
        var result = converter.convert(
            "<blockquote><p>引文第一行</p></blockquote><hr>"
                + "<p><img src=\"/images/x.webp\" alt=\"示意图\"></p>");

        assertThat(result.markdown()).contains("> 引文第一行");
        assertThat(result.markdown()).contains("---");
        assertThat(result.markdown()).contains("![示意图](/images/x.webp)");
    }
}
