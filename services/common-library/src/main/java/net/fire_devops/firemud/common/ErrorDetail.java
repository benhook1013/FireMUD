package net.fire_devops.firemud.common;

import jakarta.validation.constraints.NotBlank;

public record ErrorDetail(@NotBlank String code, @NotBlank String message) { }
