package com.smartparking.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileTypesTest {

    static byte[] bytes(int... values) {
        byte[] b = new byte[Math.max(values.length, 16)];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }

    @Test
    void detectsJpegPngWebpPdf() {
        assertThat(FileTypes.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("image/jpeg");
        assertThat(FileTypes.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))).isEqualTo("image/png");
        assertThat(FileTypes.detect(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'))).isEqualTo("image/webp");
        assertThat(FileTypes.detect(bytes('%', 'P', 'D', 'F', '-'))).isEqualTo("application/pdf");
    }

    @Test
    void rejectsUnknownAndTinyInput() {
        assertThat(FileTypes.detect(bytes('G', 'I', 'F', '8', '9', 'a'))).isNull();
        assertThat(FileTypes.detect(new byte[] {(byte) 0xFF})).isNull();
        assertThat(FileTypes.detect(null)).isNull();
    }

    @Test
    void extensionsMatchTypes() {
        assertThat(FileTypes.extension("image/jpeg")).isEqualTo("jpg");
        assertThat(FileTypes.extension("image/png")).isEqualTo("png");
        assertThat(FileTypes.extension("image/webp")).isEqualTo("webp");
        assertThat(FileTypes.extension("application/pdf")).isEqualTo("pdf");
    }
}
