package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotNull;

public record AccountRefRequest(@NotNull Long tenantId, @NotNull Long accountId) {}
