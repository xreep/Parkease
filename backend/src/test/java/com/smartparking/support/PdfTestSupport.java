package com.smartparking.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

public final class PdfTestSupport {

    private PdfTestSupport() {
    }

    /** All text of the PDF, page by page. */
    public static String textOf(byte[] pdf) {
        try {
            PdfReader reader = new PdfReader(pdf);
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            reader.close();
            return text.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
