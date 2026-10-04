package com.smartparking.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestController
@RequestMapping("/api/v1/test-errors")
class ErrorTestController {

    record Body(@NotBlank String name) {
    }

    @GetMapping("/api")
    void api() {
        throw ApiException.conflict("SLOT_UNAVAILABLE", "Slot taken");
    }

    @PostMapping("/validation")
    void validation(@Valid @RequestBody Body body) {
    }

    @GetMapping("/number")
    int number(@RequestParam int value) {
        return value;
    }

    @GetMapping("/extra")
    void extra() {
        throw ApiException.badRequest("LISTING_INCOMPLETE", "Missing").with("missing", List.of("PHOTOS", "SLOTS"));
    }

    @GetMapping("/too-large")
    void tooLarge() {
        throw new MaxUploadSizeExceededException(5L * 1024 * 1024);
    }

    @GetMapping("/boom")
    void boom() {
        throw new IllegalStateException("secret internals");
    }
}
