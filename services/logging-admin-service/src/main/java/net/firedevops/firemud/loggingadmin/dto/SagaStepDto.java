package net.firedevops.firemud.loggingadmin.dto;

import java.time.Instant;

public record SagaStepDto(
    Long id,
    Long instanceId,
    String name,
    String status,
    int attempt,
    Instant createdAt,
    Instant updatedAt) {}
