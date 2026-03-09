package net.firedevops.firemud.entitymanagement.dto;

import jakarta.validation.constraints.NotNull;

/** Request body for adding a friend to a character. */
public record AddFriendRequest(@NotNull Long friendId) {}
