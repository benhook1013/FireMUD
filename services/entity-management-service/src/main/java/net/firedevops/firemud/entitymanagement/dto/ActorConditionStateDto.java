package net.firedevops.firemud.entitymanagement.dto;

import java.time.Instant;

public record ActorConditionStateDto(
    String conditionKey,
    int stackCount,
    String sourceType,
    String sourceId,
    Instant startedAt,
    Instant expiresAt,
    String effectPayloadJson) {}
