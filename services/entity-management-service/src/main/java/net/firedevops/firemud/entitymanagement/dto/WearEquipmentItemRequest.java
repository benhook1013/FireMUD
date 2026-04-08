package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;

public record WearEquipmentItemRequest(@NotNull Long itemId) {}
