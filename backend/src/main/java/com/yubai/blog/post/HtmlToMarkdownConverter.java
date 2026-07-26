package com.yubai.blog.post;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

/**
 * 3A-2：存量文章 HTML→Markdown 一次性转换器（jsoup 解析后递归序列化）。
 * 输入空间以 PostContentSanitizer 白名单为准（标题/段落/列表/引用/代码/表格/图片/链接/强调等）。
 * 高风险结构不硬转——表格按 GFM 尽力输出、嵌套列表/公式类 class、未知标签均记入风险清单，
 * 供人工校对（3A-5 签收前逐篇核对）。
 */
@Component
public class HtmlToMarkdownConverter {

    public record Conversion(String markdown, List<String> risks) {
    }

    public Conversion convert(String html) {
        var body = Jsoup.parseBodyFragment(html == null ? "" : html).body();
        Set<String> risks = new LinkedHashSet<>();
        var out = new StringBuilder();
        for (Node child : body.childNodes()) {
            renderBlock(child, out, risks, 0);
        }
        return new Conversion(out.toString().replaceAll("\\n{3,}", "\n\n").strip() + "\n", List.copyOf(risks));
    }

    private void renderBlock(Node node, StringBuilder out, Set<String> risks, int listDepth) {
        if (node instanceof TextNode text) {
            var value = text.text().strip();
            if (!value.isEmpty()) out.append(value).append("\n\n");
            return;
        }
        if (!(node instanceof Element el)) return;

        switch (el.tagName()) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = el.tagName().charAt(1) - '0';
                out.append("#".repeat(level)).append(' ').append(inline(el, risks)).append("\n\n");
            }
            case "p", "div", "section", "span" -> {
                var text = inline(el, risks);
                if (!text.isBlank()) out.append(text).append("\n\n");
            }
            case "blockquote" -> {
                var innerBuilder = new StringBuilder();
                for (Node child : el.childNodes()) renderBlock(child, innerBuilder, risks, listDepth);
                for (var line : innerBuilder.toString().strip().split("\n")) {
                    out.append("> ").append(line).append('\n');
                }
                out.append('\n');
            }
            case "pre" -> {
                var code = el.selectFirst("code");
                var lang = code == null ? "" : languageOf(code);
                var raw = (code == null ? el : code).wholeText().stripTrailing();
                out.append("```").append(lang).append('\n').append(raw).append("\n```\n\n");
            }
            case "ul", "ol" -> renderList(el, out, risks, listDepth);
            case "table" -> {
                risks.add("表格（GFM 尽力转换，需人工核对对齐与合并单元格）");
                renderTable(el, out, risks);
            }
            case "hr" -> out.append("---\n\n");
            case "img" -> out.append(imageMarkdown(el)).append("\n\n");
            case "br" -> out.append('\n');
            default -> {
                risks.add("未知标签 <" + el.tagName() + ">（按纯文本降级）");
                var text = inline(el, risks);
                if (!text.isBlank()) out.append(text).append("\n\n");
            }
        }
        if (!el.classNames().isEmpty()
            && el.classNames().stream().anyMatch(c -> c.contains("math") || c.contains("katex") || c.contains("formula"))) {
            risks.add("疑似公式类 class（" + String.join(",", el.classNames()) + "），需人工确认 LaTeX 语义");
        }
    }

    private void renderList(Element list, StringBuilder out, Set<String> risks, int depth) {
        if (depth >= 1) {
            risks.add("嵌套列表（深度 " + (depth + 1) + "），需人工核对缩进层级");
        }
        boolean ordered = list.tagName().equals("ol");
        int index = 1;
        for (Element item : list.children()) {
            if (!item.tagName().equals("li")) continue;
            var marker = ordered ? (index++) + ". " : "- ";
            out.append("  ".repeat(depth)).append(marker).append(inlineShallow(item, risks)).append('\n');
            for (Element nested : item.children()) {
                if (nested.tagName().equals("ul") || nested.tagName().equals("ol")) {
                    renderList(nested, out, risks, depth + 1);
                }
            }
        }
        out.append('\n');
    }

    private void renderTable(Element table, StringBuilder out, Set<String> risks) {
        var rows = table.select("tr");
        if (rows.isEmpty()) return;
        boolean headerDone = false;
        for (Element row : rows) {
            var cells = row.children().stream()
                .filter(c -> c.tagName().equals("th") || c.tagName().equals("td"))
                .map(c -> inline(c, risks).replace("|", "\\|"))
                .toList();
            if (cells.isEmpty()) continue;
            out.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (!headerDone) {
                out.append("|").append(" --- |".repeat(cells.size())).append('\n');
                headerDone = true;
            }
        }
        out.append('\n');
    }

    /** 行内序列化：strong/em/code/del/a/img 转对应 Markdown 记号，其余取纯文本。 */
    private String inline(Element el, Set<String> risks) {
        var out = new StringBuilder();
        for (Node node : el.childNodes()) {
            if (node instanceof TextNode text) {
                out.append(text.text());
            } else if (node instanceof Element child) {
                switch (child.tagName()) {
                    case "strong", "b" -> out.append("**").append(inline(child, risks)).append("**");
                    case "em", "i" -> out.append('*').append(inline(child, risks)).append('*');
                    case "del", "s", "strike" -> out.append("~~").append(inline(child, risks)).append("~~");
                    case "code" -> out.append('`').append(child.wholeText()).append('`');
                    case "a" -> out.append('[').append(inline(child, risks)).append("](").append(child.attr("href")).append(')');
                    case "img" -> out.append(imageMarkdown(child));
                    case "br" -> out.append('\n');
                    case "sub", "sup", "u" -> {
                        risks.add("行内标签 <" + child.tagName() + "> 无 Markdown 等价物（按纯文本降级）");
                        out.append(inline(child, risks));
                    }
                    default -> out.append(inline(child, risks));
                }
            }
        }
        return out.toString().strip();
    }

    /** 列表项的直接行内内容（不含嵌套子列表——由 renderList 递归处理）。 */
    private String inlineShallow(Element li, Set<String> risks) {
        var clone = li.clone();
        clone.children().removeIf(c -> c.tagName().equals("ul") || c.tagName().equals("ol"));
        var stripped = new ArrayList<Element>();
        for (Element child : clone.children()) {
            if (child.tagName().equals("ul") || child.tagName().equals("ol")) stripped.add(child);
        }
        stripped.forEach(Element::remove);
        return inline(clone, risks);
    }

    private static String imageMarkdown(Element img) {
        return "![" + img.attr("alt") + "](" + img.attr("src") + ")";
    }

    private static String languageOf(Element code) {
        for (var className : code.classNames()) {
            if (className.startsWith("language-")) return className.substring("language-".length());
        }
        return "";
    }
}
