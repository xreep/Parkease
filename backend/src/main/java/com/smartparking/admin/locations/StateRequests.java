package com.smartparking.admin.locations;

import com.smartparking.location.StateType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class StateRequests {

    private StateRequests() {
    }

    public record Create(
            @NotBlank @Size(min = 2, max = 100) String name,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2,5}$", message = "must be 2 to 5 letters") String code,
            @NotNull StateType type,
            @NotBlank @Size(min = 2, max = 150) String capitalName) {
    }

    /** Every field is optional; only the ones present change. */
    public record Update(
            @Size(min = 2, max = 100) String name,
            StateType type,
            @Size(min = 2, max = 150) String capitalName) {
    }
}
