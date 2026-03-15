package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request object for username recovery emails. */
public record UsernameRecoveryRequest(
    @NotNull Long tenantId, @NotNull @Email @Size(max = 100) String email) {}
