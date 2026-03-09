package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;

/** DTO representing a per-game friend link between characters. */
public record CharacterFriendDto(
    @NotNull Long characterId, @NotNull Long friendId, Long createdAtEpochMs) {}
