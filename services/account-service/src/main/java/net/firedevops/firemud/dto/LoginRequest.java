package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @NotNull Long tenantId,
    @NotNull @Size(max = 50) String username,
    @NotNull @Size(min = 6, max = 100) String password) {}
