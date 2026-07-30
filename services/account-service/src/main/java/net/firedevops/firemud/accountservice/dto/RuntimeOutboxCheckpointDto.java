package net.firedevops.firemud.accountservice.dto;

public record RuntimeOutboxCheckpointDto(String outboxStreamKey, long outboxSequence) {}
