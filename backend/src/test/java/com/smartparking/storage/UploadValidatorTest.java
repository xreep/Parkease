package com.smartparking.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class UploadValidatorTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '4', 0, 0, 0, 0};

    private static MockMultipartFile file(byte[] bytes) {
        // The client-declared type is deliberately wrong: only magic bytes count.
        return new MockMultipartFile("file", "x.jpg", "image/jpeg", bytes);
    }

    private static void assertError(Runnable r, HttpStatus status, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    @Test
    void acceptsRealImageRegardlessOfDeclaredType() {
        ValidatedUpload u = UploadValidator.validate(file(PNG), UploadKind.IMAGE);
        assertThat(u.contentType()).isEqualTo("image/png");
        assertThat(u.extension()).isEqualTo("png");
    }

    @Test
    void pdfOnlyAllowedForDocuments() {
        assertThat(UploadValidator.validate(file(PDF), UploadKind.DOCUMENT).contentType()).isEqualTo("application/pdf");
        assertError(() -> UploadValidator.validate(file(PDF), UploadKind.IMAGE),
                HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE");
    }

    @Test
    void rejectsMissingEmptyUnknownAndOversizedFiles() {
        assertError(() -> UploadValidator.validate(null, UploadKind.IMAGE), HttpStatus.BAD_REQUEST, "FILE_REQUIRED");
        assertError(() -> UploadValidator.validate(file(new byte[0]), UploadKind.IMAGE),
                HttpStatus.BAD_REQUEST, "FILE_REQUIRED");
        assertError(() -> UploadValidator.validate(file("GIF89a-not-allowed".getBytes()), UploadKind.IMAGE),
                HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE");
        byte[] big = new byte[(int) UploadValidator.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertError(() -> UploadValidator.validate(file(big), UploadKind.IMAGE),
                HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
    }
}
