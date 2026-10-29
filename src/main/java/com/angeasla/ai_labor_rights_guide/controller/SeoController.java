package com.angeasla.ai_labor_rights_guide.controller;

import com.angeasla.ai_labor_rights_guide.service.WikiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic SEO endpoints backed by {@link WikiService}:
 * <ul>
 *   <li>{@code GET /sitemap.xml} — XML sitemap with all static routes and all wiki articles.</li>
 *   <li>{@code GET /api/wiki/seo/{*path}} — Lightweight HTML with Open Graph, Twitter Card, and
 *       canonical tags for social media link previews (Viber, Facebook, Telegram, X, LinkedIn, etc.).</li>
 * </ul>
 */
@RestController
public class SeoController {

    private static final String SITE_NAME = "Εργασιακός Οδηγός Βάσης";
    private static final String DEFAULT_DESCRIPTION =
            "Εργασιακός Οδηγός Βάσης: δωρεάν και ανώνυμος οδηγός εργασιακών δικαιωμάτων. "
            + "Άρθρα, εργαλεία υπολογισμού (αποζημίωση, δώρα, άδειες, υπερωρίες) και AI βοηθός.";

    private static final CacheControl SEO_CACHE =
            CacheControl.maxAge(Duration.ofHours(24)).mustRevalidate().cachePublic();

    private static final Pattern FM_BLOCK = Pattern.compile("^---\\r?\\n([\\s\\S]*?)\\r?\\n---[\\r\\n]*");
    private static final Pattern FM_LINE = Pattern.compile("^([\\w-]+)\\s*:\\s*(.+)$");
    private static final Pattern H1_LINE = Pattern.compile("(?m)^#\\s+(.+)$");

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
        String fullTitle = meta.title().isEmpty()
                ? SITE_NAME
                : meta.title() + " — " + SITE_NAME;
        String description = meta.description().isEmpty() ? DEFAULT_DESCRIPTION : meta.description();
        String routePath = rel.contains("/") ? "/wiki/" + rel : "/wiki/./" + rel;
        String canonicalUrl = siteUrl + routePath;
        String imageUrl = siteUrl + "/logo-icon.png";

        String html = """
                <!doctype html>
                <html lang="el">
                <head>
                  <meta charset="utf-8" />
                  <title>%s</title>
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
                </head>
                <body>
                  <h1>%s</h1>
                  <p>%s</p>
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
                escapeHtml(meta.title().isEmpty() ? SITE_NAME : meta.title()),
                escapeHtml(description)
        );

        return ResponseEntity.ok()
                .cacheControl(SEO_CACHE)
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(html);
    }

    private static void appendUrl(StringBuilder xml, String loc, String changefreq, String priority) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(escapeXml(loc)).append("</loc>\n");
        xml.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
        xml.append("    <priority>").append(priority).append("</priority>\n");
        xml.append("  </url>\n");
    }

    record ArticleMeta(String title, String description) {}

    static ArticleMeta parseMarkdownMeta(String rawMarkdown) {
        String title = "";
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
                    if ("title".equals(key)) {
                        title = val;
                    } else if ("description".equals(key)) {
                        description = val;
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

        return new ArticleMeta(title, description);
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
}
