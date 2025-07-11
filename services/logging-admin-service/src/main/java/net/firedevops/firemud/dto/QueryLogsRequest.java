package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record QueryLogsRequest(@NotNull Long tenantId, @Size(max = 255) String filter) {}
