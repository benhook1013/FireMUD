package net.firedevops.firemud.loggingadmin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record QueryLogsRequest(@NotNull @Positive Long tenantId, @Size(max = 255) String filter) {}
