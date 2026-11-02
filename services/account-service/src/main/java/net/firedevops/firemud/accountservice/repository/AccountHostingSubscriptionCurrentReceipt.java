package net.firedevops.firemud.accountservice.repository;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.Status;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;

/**
 * Consistent backend-only evaluation of one committed hosting receipt and fresh SDK evidence. It is
 * NOT the caller-bound runtime/auth-evidence bundle, a capacity commitment, or a public API DTO.
 * Quotas come only from current.planQuote(); neither numeric archives nor today's catalog is read.
 */
public record AccountHostingSubscriptionCurrentReceipt(
    AccountSubscriptionCurrentRecord current,
    UUID receiptRequestId,
    Status subscriptionStatus,
    boolean gameplayAvailable,
    boolean allowPublicJoin,
    boolean allowNewGameplayBindings,
    boolean allowNewInstanceStarts,
    long entitlementVersion,
    long tenantBillingSequence,
    UUID billingEventId,
    String billingEventDigest,
    TenantAuthorityEventV1Codec.Event tenantAuthorityEvent,
    String rawProviderStatus,
    Instant currentPeriodStart,
    Instant currentPeriodEnd,
    boolean cancelAtPeriodEnd,
    Instant cancelAt,
    Instant cutoffAt,
    Instant evaluatedAt,
    Instant validUntil) {}
