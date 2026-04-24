package net.firedevops.firemud.entitymanagement.dto;

public record ActorResourceStateDto(
    String statKey,
    long currentValue,
    Long maxValue,
    Long baseValue,
    String primitiveKind,
    String sourceType,
    String sourceId) {}
