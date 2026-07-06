package com.emergentterrain.research.literature;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 33 L1b — extractor invariants (the throwaway-extractor bug class, pinned). */
class ArxivHtmlCoreBuilderTest {

    @Test
    void mathWithEscapedAngleBrackets_survivesTagStripping() {
        // THE bug: '&lt;' inside a formula, unescaped before tag-stripping, becomes '<' and the
        // tag-stripper eats the rest of the equation. Sentinel-protection must prevent it.
        String html = "<head><style>.x{}</style></head><body><p>Sums of the form "
                + "<math alttext=\"J_{-k}(T)=\\sum_{0&lt;\\gamma\\leq T}\\frac{1}{|\\zeta(\\rho)|^{2k}}\">"
                + "<mrow>mml junk</mrow></math> where <math alttext=\"k\\in\\mathbb{R}\">k</math>"
                + " matter.</p><script>bad()</script></body>";
        String text = ArxivHtmlCoreBuilder.extract(html);
        assertThat(text)
                .contains("$J_{-k}(T)=\\sum_{0<\\gamma\\leq T}\\frac{1}{|\\zeta(\\rho)|^{2k}}$")
                .contains("$k\\in\\mathbb{R}$")
                .doesNotContain("mml junk")
                .doesNotContain("bad()")
                // '<' itself is LEGITIMATE inside restored math (0<\gamma) — assert no TAGS remain.
                .doesNotContain("<math").doesNotContain("<p>").doesNotContain("</");
    }

    @Test
    void proseNumbers_areNotConfusedWithMathSentinels() {
        // The Java port's own near-bug: a plain-number sentinel would let prose numbers ("in
        // 1973, see page 0") splice math entries into text. NUL delimiters make that impossible.
        String html = "<p>In 1973 and 0 other years <math alttext=\"a+b\">x</math> held.</p>";
        String text = ArxivHtmlCoreBuilder.extract(html);
        assertThat(text).contains("In 1973 and 0 other years $a+b$ held.");
    }

    @Test
    void entityUnescape_coversNamedAndNumeric_ampLast() {
        assertThat(ArxivHtmlCoreBuilder.unescapeHtml("a &lt; b &gt; c &quot;d&quot; &#39;e&#39;"))
                .isEqualTo("a < b > c \"d\" 'e'");
        assertThat(ArxivHtmlCoreBuilder.unescapeHtml("&#x3B6;(s) &#950;")).isEqualTo("ζ(s) ζ");
        // &amp; decodes LAST: '&amp;lt;' is the literal text '&lt;', not '<'.
        assertThat(ArxivHtmlCoreBuilder.unescapeHtml("&amp;lt;")).isEqualTo("&lt;");
        assertThat(ArxivHtmlCoreBuilder.unescapeHtml("plain")).isEqualTo("plain");
    }

    @Test
    void arxivIdFromLocations_scansAllLocations() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        // best OA is publisher-hosted; the arXiv preprint hides in locations[2]'s landing page.
        var work = om.readTree("""
                {"locations":[
                  {"pdf_url":"https://www.cambridge.org/core/x.pdf","landing_page_url":"https://doi.org/10.1/x"},
                  {"pdf_url":null,"landing_page_url":"https://link.springer.com/article/y"},
                  {"pdf_url":null,"landing_page_url":"https://arxiv.org/abs/math/0310381v2"}]}""");
        org.assertj.core.api.Assertions.assertThat(
                ArxivHtmlCoreBuilder.arxivIdFromLocations(work)).isEqualTo("math/0310381");
        var none = om.readTree("{\"locations\":[{\"pdf_url\":\"https://msp.org/a.pdf\"}]}");
        org.assertj.core.api.Assertions.assertThat(
                ArxivHtmlCoreBuilder.arxivIdFromLocations(none)).isNull();
    }

    @Test
    void arxivIdPattern_handlesOldAndNewStyles() {
        java.util.regex.Matcher m;
        m = ArxivHtmlCoreBuilder.ARXIV_ID.matcher("https://arxiv.org/pdf/math/0310381");
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isEqualTo("math/0310381");
        m = ArxivHtmlCoreBuilder.ARXIV_ID.matcher("http://arxiv.org/abs/1010.1399v2");
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isEqualTo("1010.1399");
        assertThat(ArxivHtmlCoreBuilder.ARXIV_ID.matcher("https://doi.org/10.1/x").find()).isFalse();
    }
}
