package net.firedevops.firemud.accountservice.repository;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;

/**
 * Immutable original creation result. A pending result is not a provider resource or entitlement.
 */
public record AccountSubscriptionCreationRecord(
    UUID requestId,
    String requestDigest,
    UUID subscriptionId,
    UUID tenantId,
    OwnerFence owner,
    String planCode,
    AccountHostingPlanQuote planQuote,
    UUID paymentInstrumentId,
    long reservedInstrumentVersion,
    UUID customerProvisioningRequestId,
    String providerCustomerId,
    UUID subscriptionProvisioningRequestId,
    UUID cleanupProviderIdentity,
    String initialStatus,
    long initialRowVersion,
    Instant createdAt) {}
