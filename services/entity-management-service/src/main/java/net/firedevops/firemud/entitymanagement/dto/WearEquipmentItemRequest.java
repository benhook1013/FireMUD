package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record WearEquipmentItemRequest(@NotNull @Positive Long itemId) {}
