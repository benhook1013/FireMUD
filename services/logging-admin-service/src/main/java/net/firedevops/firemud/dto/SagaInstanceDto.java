package net.firedevops.firemud.dto;

import java.time.Instant;

public record SagaInstanceDto(
    Long id, String sagaName, String state, Instant createdAt, Instant updatedAt) {}
