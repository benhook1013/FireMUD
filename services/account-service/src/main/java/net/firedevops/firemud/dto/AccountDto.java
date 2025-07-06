package net.firedevops.firemud.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AccountDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull @Size(max = 50) String username,
    @NotNull @Email @Size(max = 100) String email) {}
