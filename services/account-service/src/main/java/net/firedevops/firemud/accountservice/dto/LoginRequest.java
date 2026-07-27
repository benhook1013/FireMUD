package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @NotNull @Size(max = 50) String username, @NotNull @Size(min = 1, max = 100) String password) {}
