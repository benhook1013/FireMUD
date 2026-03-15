package net.firedevops.firemud.loggingadmin.dto;

import java.time.Instant;

public record SagaInstanceDto(
    Long id, String sagaName, String state, Instant createdAt, Instant updatedAt) {}
