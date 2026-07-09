package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AccountIdRequest(@NotNull @Positive Long accountId) {}
