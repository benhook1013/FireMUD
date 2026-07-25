package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Tenant-free credentials for first-party gameplay bootstrap. */
public record PlayerBootstrapRequest(
    @NotBlank @Size(max = 254) String accountIdentifier,
    @NotBlank @Size(min = 6, max = 100) String secret) {}
