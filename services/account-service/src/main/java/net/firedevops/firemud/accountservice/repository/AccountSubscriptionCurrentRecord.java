package net.firedevops.firemud.accountservice.repository;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;

/**
 * Current storage snapshot only. No runtime entitlement DTO or provider/lifecycle authority claim.
 */
public record AccountSubscriptionCurrentRecord(
    UUID subscriptionId,
    UUID tenantId,
    OwnerFence owner,
    String planCode,
    AccountHostingPlanQuote planQuote,
    UUID paymentInstrumentId,
    long reservedInstrumentVersion,
    UUID customerProvisioningRequestId,
    String providerCustomerId,
    UUID creationRequestId,
    long rowVersion,
    String status,
    String providerSubscriptionId,
    UUID terminalResolutionId,
    Instant supersededAt,
    UUID supersededByRequestId) {}
