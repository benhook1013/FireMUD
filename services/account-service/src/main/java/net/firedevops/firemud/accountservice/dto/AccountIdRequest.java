package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotNull;

public record AccountIdRequest(@NotNull Long accountId) {}
