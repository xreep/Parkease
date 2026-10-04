package com.smartparking.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/boom")
    void boom() {
        throw new IllegalStateException("secret internals");
    }
}
