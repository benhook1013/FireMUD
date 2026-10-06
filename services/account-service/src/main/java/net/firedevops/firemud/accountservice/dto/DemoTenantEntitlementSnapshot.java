package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;

/** Exact committed demo entitlement and its current Account tenant-generation/outbox evidence. */
public record DemoTenantEntitlementSnapshot(
    UUID canonicalTenantId,
    FreshTenantCreationEvidence sourceEvidence,
    String entitlementKind,
    String status,
    String subscriptionStatus,
    boolean paid,
    boolean gameplayAvailable,
    boolean allowPublicJoin,
    boolean allowNewGameplayBindings,
    boolean allowNewInstanceStarts,
    DemoTenantEntitlementRequest.Quotas quotas,
    long entitlementVersion,
    long tenantAuthorityGeneration,
    long tenantAuthoritySourceVersion,
    String outboxStreamKey,
    long tenantBillingSequence,
    UUID eventId,
    String eventDigest,
    String snapshotIdentity,
    String tenantAuthorityOutboxStreamKey,
    long tenantAuthorityOutboxSequence,
    UUID tenantAuthorityEventId,
    String tenantAuthorityEventDigest) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern EVENT_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  public DemoTenantEntitlementSnapshot {
    if (canonicalTenantId == null || NIL_UUID.equals(canonicalTenantId)) {
      throw new IllegalArgumentException("Canonical tenant UUID is required");
    }
    Objects.requireNonNull(
        sourceEvidence, "Authenticated fresh tenant source evidence is required");
    if (!canonicalTenantId.equals(sourceEvidence.canonicalTenantId())) {
      throw new IllegalArgumentException("Demo entitlement source evidence targets another tenant");
    }
    if (!"NON_PAID_DEMO".equals(entitlementKind)
        || !"ACTIVE".equals(status)
        || subscriptionStatus != null
        || paid) {
      throw new IllegalArgumentException("Demo entitlement must remain explicitly non-paid");
    }
    Objects.requireNonNull(quotas, "Demo entitlement quotas are required");
    if (entitlementVersion <= 0
        || tenantAuthorityGeneration <= 0
        || tenantAuthoritySourceVersion <= 0
        || tenantBillingSequence <= 0) {
      throw new IllegalArgumentException("Demo entitlement currentness values must be positive");
    }
    String expectedStream = AccountTenantEntitlementOutboxRepository.streamKey(canonicalTenantId);
    if (!expectedStream.equals(outboxStreamKey)) {
      throw new IllegalArgumentException(
          "Demo entitlement outbox scope differs from its tenant UUID");
    }
    if (eventId == null || NIL_UUID.equals(eventId)) {
      throw new IllegalArgumentException("Demo entitlement event ID is required");
    }
    if (eventDigest == null || !EVENT_DIGEST.matcher(eventDigest).matches()) {
      throw new IllegalArgumentException("Demo entitlement event digest is invalid");
    }
    if (!eventDigest.equals(snapshotIdentity)) {
      throw new IllegalArgumentException(
          "Demo entitlement snapshot identity differs from its event");
    }
    if (!TenantAuthorityEventV1Codec.streamKey(canonicalTenantId)
        .equals(tenantAuthorityOutboxStreamKey)) {
      throw new IllegalArgumentException(
          "Demo entitlement authority stream differs from its tenant UUID");
    }
    if (tenantAuthorityOutboxSequence <= 0L
        || tenantAuthorityEventId == null
        || NIL_UUID.equals(tenantAuthorityEventId)
        || tenantAuthorityEventDigest == null
        || !EVENT_DIGEST.matcher(tenantAuthorityEventDigest).matches()) {
      throw new IllegalArgumentException("Demo entitlement authority event evidence is invalid");
    }
  }
}
