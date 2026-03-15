package net.firedevops.firemud.accountservice.dto;

import java.time.LocalDateTime;

public record SubscriptionDto(
    Long id,
    Long tenantId,
    Long accountId,
    String planId,
    String status,
    LocalDateTime startedAt,
    LocalDateTime endedAt) {}
