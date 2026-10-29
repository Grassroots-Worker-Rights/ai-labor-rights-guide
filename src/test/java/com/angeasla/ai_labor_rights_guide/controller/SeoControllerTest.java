package com.angeasla.ai_labor_rights_guide.controller;

import com.angeasla.ai_labor_rights_guide.service.WikiService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

class SeoControllerTest {

    @Test
    void sitemapContainsStaticRoutesAndAllWikiArticles() {
        WikiService wikiService = new WikiService();
        SeoController controller = new SeoController(wikiService, "https://ergasiakos-odigos.gr/");

        ResponseEntity<String> response = controller.sitemap();
        assertEquals(HttpStatus.OK, response.getStatusCode());

        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(body.contains("<loc>https://ergasiakos-odigos.gr/</loc>"));
        assertTrue(body.contains("<loc>https://ergasiakos-odigos.gr/tools</loc>"));
        assertTrue(body.contains("<loc>https://ergasiakos-odigos.gr/faq</loc>"));
        assertTrue(body.contains("<loc>https://ergasiakos-odigos.gr/about</loc>"));
        assertTrue(body.contains("<loc>https://ergasiakos-odigos.gr/wiki/./index.md</loc>"));
        assertTrue(body.contains("<loc>https://ergasiakos-odigos.gr/wiki/adeies/adia-gamos.md</loc>"));
    }

    @Test
    void articleSeoPreviewReturnsFullSemanticHtmlAndResolvedLinks() {
        WikiService wikiService = new WikiService();
        SeoController controller = new SeoController(wikiService, "https://ergasiakos-odigos.gr");

        ResponseEntity<String> response = controller.articleSeoPreview("adeies/adia-gamos.md");
        assertEquals(HttpStatus.OK, response.getStatusCode());

        String html = response.getBody();
        assertNotNull(html);
        assertTrue(html.contains("<title>Άδεια Γάμου &amp; Τεκνοποίησης — Εργασιακός Οδηγός Βάσης</title>"));
        assertTrue(html.contains("rel=\"canonical\" href=\"https://ergasiakos-odigos.gr/wiki/adeies/adia-gamos.md\""));
        assertTrue(html.contains("property=\"og:title\" content=\"Άδεια Γάμου &amp; Τεκνοποίησης — Εργασιακός Οδηγός Βάσης\""));
        assertTrue(html.contains("property=\"og:description\" content=\"Αν παντρεύεσαι ή συνάπτεις σύμφωνο συμβίωσης"));
        assertTrue(html.contains("<script type=\"application/ld+json\">"));
        assertTrue(html.contains("\"@type\":\"Article\""));
        assertTrue(html.contains("<h1>Άδεια Γάμου &amp; Τεκνοποίησης</h1>"));
        assertTrue(html.contains("<h2>Άδεια γάμου / συμφώνου συμβίωσης</h2>"));
        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("<a href=\"/wiki/adeies/etisia-adia.md\">Ετήσια Άδεια</a>"));
    }

    @Test
    void articleSeoPreviewReturns404ForMissingArticle() {
        WikiService wikiService = new WikiService();
        SeoController controller = new SeoController(wikiService, "https://ergasiakos-odigos.gr");

        ResponseEntity<String> response = controller.articleSeoPreview("adeies/nonexistent.md");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
