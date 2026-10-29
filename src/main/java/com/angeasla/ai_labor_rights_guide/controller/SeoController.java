package com.angeasla.ai_labor_rights_guide.controller;

import com.angeasla.ai_labor_rights_guide.service.WikiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic SEO endpoints backed by {@link WikiService}:
 * <ul>
 *   <li>{@code GET /sitemap.xml} — XML sitemap with all static routes and all wiki articles.</li>
 *   <li>{@code GET /api/wiki/seo/{*path}} — Semantic HTML with Open Graph, Twitter Card, JSON-LD,
 *       and full article body for non-JS crawlers (social media previews, AI search bots, DuckDuckBot).</li>
 * </ul>
 */
@RestController
public class SeoController {

    private static final String SITE_NAME = "Εργασιακός Οδηγός Βάσης";
    private static final String DEFAULT_DESCRIPTION =
            "Εργασιακός Οδηγός Βάσης: δωρεάν και ανώνυμος οδηγός εργασιακών δικαιωμάτων. "
            + "Άρθρα, εργαλεία υπολογισμού (αποζημίωση, δώρα, άδειες, υπερωρίες) και AI βοηθός.";

    private static final Map<String, String> CATEGORY_LABELS = Map.ofEntries(
            Map.entry("adeies", "Άδειες"),
            Map.entry("anergia", "Ανεργία"),
            Map.entry("apolysi", "Απόλυση & Παραίτηση"),
            Map.entry("asfalisi", "Ασφάλιση & ΕΦΚΑ"),
            Map.entry("misthos", "Μισθός & Δώρα"),
            Map.entry("orario", "Ωράριο & Βάρδιες"),
            Map.entry("symvasi", "Σύμβαση Εργασίας"),
            Map.entry("syntaxi", "Συντάξεις"),
            Map.entry("ygeia", "Υγεία & Ασφάλεια"),
            Map.entry(".", "Γενικά")
    );

    private static final CacheControl SEO_CACHE =
            CacheControl.maxAge(Duration.ofHours(24)).mustRevalidate().cachePublic();

    private static final Pattern FM_BLOCK = Pattern.compile("^---\\r?\\n([\\s\\S]*?)\\r?\\n---[\\r\\n]*");
    private static final Pattern FM_LINE = Pattern.compile("^([\\w-]+)\\s*:\\s*(.+)$");
    private static final Pattern H1_LINE = Pattern.compile("(?m)^#\\s+(.+)$");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[(.*?)]]");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^]]+)]\\((https?://[^)\\s]+|/[^)\\s]*)\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");

    private final WikiService wiki;
    private final String siteUrl;

    public SeoController(
            WikiService wiki,
            @Value("${app.site-url:https://ergasiakos-odigos.gr}") String siteUrl) {
        this.wiki = wiki;
        this.siteUrl = siteUrl.replaceAll("/+$", "");
    }

    @GetMapping(value = {"/sitemap.xml", "/api/wiki/sitemap.xml"}, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        StringBuilder xml = new StringBuilder(8192);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        // Static SPA routes
        appendUrl(xml, siteUrl + "/", "weekly", "1.0");
        appendUrl(xml, siteUrl + "/tools", "monthly", "0.9");
        appendUrl(xml, siteUrl + "/faq", "monthly", "0.8");
        appendUrl(xml, siteUrl + "/about", "monthly", "0.7");

        // Dynamic Wiki routes from WikiService
        Set<String> seenPaths = new LinkedHashSet<>();
        WikiService.WikiIndex index = wiki.index();
        for (WikiService.WikiCategory category : index.categories()) {
            for (WikiService.ArticleEntry article : category.articles()) {
                String path = article.path();
                if (path == null || path.isBlank() || !seenPaths.add(path)) {
                    continue;
                }
                String routePath = path.contains("/") ? "/wiki/" + path : "/wiki/./" + path;
                String priority = path.endsWith("index.md") ? "0.85" : "0.8";
                appendUrl(xml, siteUrl + routePath, "weekly", priority);
            }
        }

        xml.append("</urlset>\n");
        return ResponseEntity.ok()
                .cacheControl(SEO_CACHE)
                .contentType(MediaType.valueOf("application/xml;charset=UTF-8"))
                .body(xml.toString());
    }

    @GetMapping(value = "/api/wiki/seo/{*path}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> articleSeoPreview(@PathVariable String path) {
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.startsWith("./")) {
            rel = rel.substring(2);
        }
        String markdown = wiki.rawMarkdownByPath(rel);
        if (markdown == null) {
            return ResponseEntity.notFound().build();
        }

        ArticleMeta meta = parseMarkdownMeta(markdown);
        String articleTitle = meta.title().isEmpty() ? SITE_NAME : meta.title();
        String fullTitle = meta.title().isEmpty()
                ? SITE_NAME
                : meta.title() + " — " + SITE_NAME;
        String description = meta.description().isEmpty() ? DEFAULT_DESCRIPTION : meta.description();
        String folder = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : ".";
        String categoryKey = meta.category().isEmpty() ? folder : meta.category();
        String categoryLabel = CATEGORY_LABELS.getOrDefault(categoryKey, categoryKey);
        String routePath = rel.contains("/") ? "/wiki/" + rel : "/wiki/./" + rel;
        String canonicalUrl = siteUrl + routePath;
        String imageUrl = siteUrl + "/logo-icon.png";

        Map<String, String> linkMap = wiki.index().linkMap();
        String renderedBodyHtml = renderMarkdownToHtml(meta.body(), linkMap);
        String jsonLd = buildArticleJsonLd(articleTitle, description, canonicalUrl, imageUrl, categoryLabel);

        String html = """
                <!doctype html>
                <html lang="el">
                <head>
                  <meta charset="utf-8" />
                  <title>%s</title>
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <meta name="description" content="%s" />
                  <link rel="canonical" href="%s" />
                  <meta property="og:type" content="article" />
                  <meta property="og:locale" content="el_GR" />
                  <meta property="og:site_name" content="%s" />
                  <meta property="og:title" content="%s" />
                  <meta property="og:description" content="%s" />
                  <meta property="og:url" content="%s" />
                  <meta property="og:image" content="%s" />
                  <meta name="twitter:card" content="summary" />
                  <meta name="twitter:title" content="%s" />
                  <meta name="twitter:description" content="%s" />
                  <meta name="twitter:image" content="%s" />
                  <script type="application/ld+json">%s</script>
                </head>
                <body>
                  <main>
                    <article>
                %s    </article>
                  </main>
                </body>
                </html>
                """.formatted(
                escapeHtml(fullTitle),
                escapeHtml(description),
                escapeHtml(canonicalUrl),
                escapeHtml(SITE_NAME),
                escapeHtml(fullTitle),
                escapeHtml(description),
                escapeHtml(canonicalUrl),
                escapeHtml(imageUrl),
                escapeHtml(fullTitle),
                escapeHtml(description),
                escapeHtml(imageUrl),
                jsonLd,
                renderedBodyHtml
        );

        return ResponseEntity.ok()
                .cacheControl(SEO_CACHE)
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(html);
    }

    private String buildArticleJsonLd(
            String headline,
            String description,
            String canonicalUrl,
            String imageUrl,
            String categoryLabel) {
        return """
                {"@context":"https://schema.org","@type":"Article","headline":"%s","description":"%s","inLanguage":"el-GR","url":"%s","articleSection":"%s","author":{"@type":"Organization","name":"%s","url":"%s/about"},"publisher":{"@type":"Organization","name":"%s","logo":{"@type":"ImageObject","url":"%s"}}}""".formatted(
                escapeJson(headline),
                escapeJson(description),
                escapeJson(canonicalUrl),
                escapeJson(categoryLabel),
                escapeJson(SITE_NAME),
                escapeJson(siteUrl),
                escapeJson(SITE_NAME),
                escapeJson(imageUrl)
        );
    }

    private static void appendUrl(StringBuilder xml, String loc, String changefreq, String priority) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(escapeXml(loc)).append("</loc>\n");
        xml.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
        xml.append("    <priority>").append(priority).append("</priority>\n");
        xml.append("  </url>\n");
    }

    record ArticleMeta(String title, String category, String description, String body) {}

    static ArticleMeta parseMarkdownMeta(String rawMarkdown) {
        String title = "";
        String category = "";
        String description = "";
        String body = rawMarkdown != null ? rawMarkdown : "";

        Matcher fmMatcher = FM_BLOCK.matcher(body);
        if (fmMatcher.find()) {
            String fmContent = fmMatcher.group(1);
            for (String line : fmContent.split("\\r?\\n")) {
                Matcher lineMatcher = FM_LINE.matcher(line.trim());
                if (lineMatcher.matches()) {
                    String key = lineMatcher.group(1);
                    String val = lineMatcher.group(2).trim().replaceAll("^[\"']|[\"']$", "");
                    switch (key) {
                        case "title" -> title = val;
                        case "category" -> category = val;
                        case "description" -> description = val;
                    }
                }
            }
            body = body.substring(fmMatcher.end());
        }

        if (title.isEmpty()) {
            Matcher h1 = H1_LINE.matcher(body);
            if (h1.find()) {
                title = stripInlineMarkdown(h1.group(1));
            }
        }

        if (description.isEmpty()) {
            description = extractExcerpt(body, 155);
        }

        return new ArticleMeta(title, category, description, body);
    }

    /**
     * Converts guide markdown into semantic HTML ({@code h1..h4}, {@code p}, {@code ul/ol},
     * {@code table}, {@code blockquote}, and resolved {@code <a href="/wiki/...">} links).
     */
    static String renderMarkdownToHtml(String markdownBody, Map<String, String> linkMap) {
        if (markdownBody == null || markdownBody.isBlank()) {
            return "";
        }
        StringBuilder html = new StringBuilder(markdownBody.length() + 512);
        String[] lines = markdownBody.split("\\r?\\n");

        List<String> paragraphBuffer = new ArrayList<>();
        boolean inUl = false;
        boolean inOl = false;
        boolean inCodeBlock = false;
        List<String> tableBuffer = new ArrayList<>();

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();

            if (trimmed.startsWith("```")) {
                flushParagraph(html, paragraphBuffer, linkMap);
                inUl = closeList(html, inUl, "ul");
                inOl = closeList(html, inOl, "ol");
                flushTable(html, tableBuffer, linkMap);
                if (!inCodeBlock) {
                    html.append("      <pre><code>");
                    inCodeBlock = true;
                } else {
                    html.append("</code></pre>\n");
                    inCodeBlock = false;
                }
                continue;
            }

            if (inCodeBlock) {
                html.append(escapeHtml(rawLine)).append('\n');
                continue;
            }

            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                flushParagraph(html, paragraphBuffer, linkMap);
                inUl = closeList(html, inUl, "ul");
                inOl = closeList(html, inOl, "ol");
                tableBuffer.add(trimmed);
                continue;
            } else if (!tableBuffer.isEmpty()) {
                flushTable(html, tableBuffer, linkMap);
            }

            if (trimmed.isEmpty()) {
                flushParagraph(html, paragraphBuffer, linkMap);
                inUl = closeList(html, inUl, "ul");
                inOl = closeList(html, inOl, "ol");
                continue;
            }

            if (trimmed.equals("---")) {
                flushParagraph(html, paragraphBuffer, linkMap);
                inUl = closeList(html, inUl, "ul");
                inOl = closeList(html, inOl, "ol");
                html.append("      <hr />\n");
                continue;
            }

            if (trimmed.startsWith("#")) {
                flushParagraph(html, paragraphBuffer, linkMap);
                inUl = closeList(html, inUl, "ul");
                inOl = closeList(html, inOl, "ol");
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                int tagLevel = Math.min(Math.max(level, 1), 4);
                String headingText = trimmed.substring(level).trim();
                html.append("      <h").append(tagLevel).append('>')
                        .append(renderInline(headingText, linkMap))
                        .append("</h").append(tagLevel).append(">\n");
                continue;
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushParagraph(html, paragraphBuffer, linkMap);
                inOl = closeList(html, inOl, "ol");
                if (!inUl) {
                    html.append("      <ul>\n");
                    inUl = true;
                }
                html.append("        <li>")
                        .append(renderInline(trimmed.substring(2).trim(), linkMap))
                        .append("</li>\n");
                continue;
            }

            if (trimmed.matches("^\\d+\\.\\s+.*")) {
                flushParagraph(html, paragraphBuffer, linkMap);
                inUl = closeList(html, inUl, "ul");
                if (!inOl) {
                    html.append("      <ol>\n");
                    inOl = true;
                }
                String itemText = trimmed.replaceFirst("^\\d+\\.\\s+", "");
                html.append("        <li>")
                        .append(renderInline(itemText, linkMap))
                        .append("</li>\n");
                continue;
            }

            if (trimmed.startsWith(">")) {
                flushParagraph(html, paragraphBuffer, linkMap);
                inUl = closeList(html, inUl, "ul");
                inOl = closeList(html, inOl, "ol");
                String quoteText = trimmed.substring(1).trim();
                html.append("      <blockquote><p>")
                        .append(renderInline(quoteText, linkMap))
                        .append("</p></blockquote>\n");
                continue;
            }

            paragraphBuffer.add(trimmed);
        }

        if (inCodeBlock) {
            html.append("</code></pre>\n");
        }
        flushParagraph(html, paragraphBuffer, linkMap);
        closeList(html, inUl, "ul");
        closeList(html, inOl, "ol");
        flushTable(html, tableBuffer, linkMap);

        return html.toString();
    }

    private static void flushParagraph(
            StringBuilder html,
            List<String> paragraphBuffer,
            Map<String, String> linkMap) {
        if (paragraphBuffer.isEmpty()) {
            return;
        }
        String joined = String.join(" ", paragraphBuffer);
        html.append("      <p>").append(renderInline(joined, linkMap)).append("</p>\n");
        paragraphBuffer.clear();
    }

    private static boolean closeList(StringBuilder html, boolean isOpen, String tag) {
        if (isOpen) {
            html.append("      </").append(tag).append(">\n");
        }
        return false;
    }

    private static void flushTable(
            StringBuilder html,
            List<String> tableBuffer,
            Map<String, String> linkMap) {
        if (tableBuffer.isEmpty()) {
            return;
        }
        html.append("      <table>\n");
        boolean headerDone = false;
        boolean bodyOpened = false;

        for (int i = 0; i < tableBuffer.size(); i++) {
            String row = tableBuffer.get(i);
            String inner = row.substring(1, row.length() - 1);
            if (inner.replaceAll("[\\s|:-]", "").isEmpty()) {
                continue; // separator row |---|---|
            }
            String[] cells = inner.split("\\|");
            if (!headerDone && i == 0) {
                html.append("        <thead><tr>");
                for (String cell : cells) {
                    html.append("<th>").append(renderInline(cell.trim(), linkMap)).append("</th>");
                }
                html.append("</tr></thead>\n");
                headerDone = true;
            } else {
                if (!bodyOpened) {
                    html.append("        <tbody>\n");
                    bodyOpened = true;
                }
                html.append("          <tr>");
                for (String cell : cells) {
                    html.append("<td>").append(renderInline(cell.trim(), linkMap)).append("</td>");
                }
                html.append("</tr>\n");
            }
        }
        if (bodyOpened) {
            html.append("        </tbody>\n");
        }
        html.append("      </table>\n");
        tableBuffer.clear();
    }

    private static String renderInline(String raw, Map<String, String> linkMap) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String escaped = escapeHtml(raw);

        // 1. Resolve [[Wiki Links]] and [[Title|Display]]
        Matcher wikiMatcher = WIKI_LINK.matcher(escaped);
        StringBuilder sb = new StringBuilder();
        while (wikiMatcher.find()) {
            String inner = wikiMatcher.group(1);
            String target = inner;
            String display = inner;
            int pipeIdx = inner.indexOf('|');
            if (pipeIdx >= 0) {
                target = inner.substring(0, pipeIdx);
                display = inner.substring(pipeIdx + 1);
            }
            String resolved = resolveWikiLink(target.trim(), linkMap);
            String replacement = resolved != null
                    ? "<a href=\"/wiki/" + escapeHtml(resolved) + "\">" + display.trim() + "</a>"
                    : "<strong>" + display.trim() + "</strong>";
            wikiMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        wikiMatcher.appendTail(sb);

        // 2. Standard markdown links [label](url)
        String out = MD_LINK.matcher(sb.toString()).replaceAll("<a href=\"$2\">$1</a>");

        // 3. Bold, Italic, Inline code
        out = BOLD.matcher(out).replaceAll("<strong>$1</strong>");
        out = ITALIC.matcher(out).replaceAll("<em>$1</em>");
        out = INLINE_CODE.matcher(out).replaceAll("<code>$1</code>");
        return out;
    }

    private static String resolveWikiLink(String query, Map<String, String> linkMap) {
        if (query == null || query.isEmpty() || linkMap == null || linkMap.isEmpty()) {
            return null;
        }
        String normalized = normalizeForLink(query);
        String exact = linkMap.get(normalized);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : linkMap.entrySet()) {
            if (entry.getKey().contains(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalizeForLink(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return DIACRITICS.matcher(n).replaceAll("").toLowerCase(Locale.ROOT).trim();
    }

    private static String extractExcerpt(String body, int maxLen) {
        StringBuilder sb = new StringBuilder();
        for (String rawLine : body.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                if (!sb.isEmpty()) {
                    break;
                }
                continue;
            }
            if (line.startsWith("#") || line.startsWith("|") || line.startsWith("```")
                    || line.startsWith("---") || line.startsWith(">")) {
                continue;
            }
            line = line.replaceAll("^[-*+]\\s+", "").replaceAll("^\\d+\\.\\s+", "");
            if (!line.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(line);
                if (sb.length() >= maxLen) {
                    break;
                }
            }
        }

        String plain = stripInlineMarkdown(sb.toString());
        if (plain.length() <= maxLen) {
            return plain;
        }
        String sliced = plain.substring(0, maxLen);
        int lastSpace = sliced.lastIndexOf(' ');
        return (lastSpace > maxLen * 0.6 ? sliced.substring(0, lastSpace) : sliced).trim() + "…";
    }

    private static String stripInlineMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text
                .replaceAll("\\[\\[([^\\]|]+)\\|([^\\]]+)\\]\\]", "$2")
                .replaceAll("\\[\\[([^\\]]+)\\]\\]", "$1")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
                .replaceAll("(\\*\\*|__)(.*?)\\1", "$2")
                .replaceAll("(\\*|_)(.*?)\\1", "$2")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "");
    }
}
