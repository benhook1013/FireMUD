package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RealmAccessGrantRequest(
    @NotNull Long accountId,
    @NotNull Long tenantId,
    @NotBlank @Size(max = 120) String worldSlug,
    @NotBlank @Size(max = 120) String realmSlug,
    @NotBlank @Size(max = 200) String grantedBy,
    @NotBlank @Size(max = 500) String grantReason,
    @NotBlank @Size(max = 128) String requestId) {}
