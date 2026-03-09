package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
    @NotNull @Size(max = 50) String username,
    @NotNull @Email @Size(max = 100) String email,
    @NotNull @Size(min = 6, max = 100) String password) {}
