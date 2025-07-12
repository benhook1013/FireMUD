package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;

public record AccountRefRequest(@NotNull Long tenantId, @NotNull Long accountId) {}
