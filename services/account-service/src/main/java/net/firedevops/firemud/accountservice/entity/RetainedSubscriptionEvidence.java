package net.firedevops.firemud.accountservice.entity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable historical numeric subscription evidence, never current billing or entitlement
 * authority. All identifiers and counters here are retained values, not canonical identity or
 * billing sequence.
 */
public record RetainedSubscriptionEvidence(
    Long id,
    Long accountId,
    String planId,
    String status,
    LocalDateTime startedAt,
    LocalDateTime endedAt,
    Long tenantId,
    long entitlementVersion,
    UUID tenantAuthorityGeneration) {}
