package net.firedevops.firemud.entitymanagement.dto;

public record RuntimeInstanceCleanupResultDto(
    long deletedRoomGroundEntries,
    long deletedItemStacks,
    long deletedItemInstances,
    long deletedContainerInstances) {}
