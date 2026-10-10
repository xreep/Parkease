package com.smartparking.admin.payouts;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record MarkPaidRequest(@NotNull Long ownerId, @NotEmpty List<@NotNull Long> earningIds,
                              @NotBlank @Size(min = 3, max = 100) String reference) {
}
