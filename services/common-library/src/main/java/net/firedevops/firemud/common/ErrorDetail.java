package net.firedevops.firemud.common;

import jakarta.validation.constraints.NotBlank;

public record ErrorDetail(@NotBlank String code, @NotBlank String message) {}
