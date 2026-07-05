package com.emergentterrain.research.literature;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 33 L1 — pure helpers + a PDFBox round trip (no fixtures, no network). */
class FullTextCoreBuilderTest {

    @Test
    void pdfRoundTrip_extractsGeneratedText() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("The divisor summatory error term satisfies theta <= 131/416.");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }
        // Magic bytes as the builder checks them.
        assertThat(pdf[0]).isEqualTo((byte) '%');
        try (PDDocument reloaded = Loader.loadPDF(pdf)) {
            String text = FullTextCoreBuilder.normalize(new PDFTextStripper().getText(reloaded));
            assertThat(text).contains("theta <= 131/416");
        }
    }

    @Test
    void normalize_collapsesArtifactsAndCaps() {
        assertThat(FullTextCoreBuilder.normalize("a\t\t b\r\n\r\n\r\n\r\nc   d"))
                .isEqualTo("a b\n\nc d");
        String huge = "x".repeat(FullTextCoreBuilder.MAX_TEXT_CHARS + 100);
        assertThat(FullTextCoreBuilder.normalize(huge))
                .hasSize(FullTextCoreBuilder.MAX_TEXT_CHARS);
        assertThat(FullTextCoreBuilder.normalize(null)).isEmpty();
    }

    @Test
    void shortId_extractsAndSanitizes() {
        assertThat(FullTextCoreBuilder.shortId("https://openalex.org/W4230159862"))
                .isEqualTo("W4230159862");
        assertThat(FullTextCoreBuilder.shortId("W123")).isEqualTo("W123");
        // Safety property: only the alphanumeric LAST segment survives — no path traversal
        // can reach the filename ("../../etc/passwd" reduces to the harmless token "passwd").
        assertThat(FullTextCoreBuilder.shortId("https://x/../../etc/passwd")).isEqualTo("passwd");
        assertThat(FullTextCoreBuilder.shortId("https://x/W123?cursor=1")).isEmpty();
        assertThat(FullTextCoreBuilder.shortId(null)).isEmpty();
    }
}
