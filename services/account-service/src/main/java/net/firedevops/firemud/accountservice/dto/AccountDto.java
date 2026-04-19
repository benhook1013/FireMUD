package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AccountDto(
    Long id,
    @NotNull @Size(max = 50) String username,
    @NotNull @Email @Size(max = 100) String email,
    @NotNull @Size(max = 20) String role,
    boolean emailVerified) {}
