package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;

/**
 * Exact, non-admitting Game Session readback of one Account lease-bound binding decision.
 *
 * <p>The decision identifier is the existing binding transition identifier. This value does not
 * represent the controller switch, Account finalization, or gameplay admission.
 */
public final class CanonicalGameplayAdmissionDecision {
  private final UUID bindingDecisionId;
  private final AccountGameplayAdmissionLeaseEvidence leaseEvidence;
  private final CanonicalGameplayBindingIdentity candidate;
  private final BigInteger bindingGeneration;
  private final BigInteger expectedOldBindingGeneration;
  private final UUID resumeEpisodeId;
  private final CanonicalPlayableTarget targetEvidence;

  public CanonicalGameplayAdmissionDecision(
      UUID bindingDecisionId,
      AccountGameplayAdmissionLeaseEvidence leaseEvidence,
      CanonicalGameplayBindingIdentity candidate,
      BigInteger bindingGeneration,
      BigInteger expectedOldBindingGeneration,
      CanonicalPlayableTarget targetEvidence) {
    this.bindingDecisionId = requireNonNil(bindingDecisionId, "bindingDecisionId");
    this.leaseEvidence = Objects.requireNonNull(leaseEvidence, "leaseEvidence");
    this.candidate = Objects.requireNonNull(candidate, "candidate");
    this.bindingGeneration = requirePositive(bindingGeneration, "bindingGeneration");
    this.targetEvidence = Objects.requireNonNull(targetEvidence, "targetEvidence");
    if (expectedOldBindingGeneration != null && expectedOldBindingGeneration.signum() <= 0) {
      throw new IllegalArgumentException("expectedOldBindingGeneration must be positive");
    }

    Map<String, Object> carrier = leaseEvidence.carrier();
    Map<String, Object> scope = object(carrier.get("bindingScope"), "bindingScope");
    requireEqual(text(carrier, "targetNamespace"), targetEvidence.targetNamespace(), "namespace");
    requireEqual(text(scope, "accountId"), candidate.accountId().toString(), "accountId");
    requireEqual(text(scope, "tenantId"), candidate.tenantId().toString(), "tenantId");
    requireEqual(
        text(scope, "tenantId"), targetEvidence.canonicalTenantId().toString(), "target tenantId");
    requireEqual(text(scope, "realmId"), targetEvidence.realmId().toString(), "realmId");
    requireEqual(text(scope, "worldSlug"), targetEvidence.worldSlug(), "worldSlug");
    requireEqual(text(scope, "realmSlug"), targetEvidence.realmSlug(), "realmSlug");
    requireEqual(
        text(scope, "playableStateNamespaceId"),
        candidate.playableStateNamespaceId().toString(),
        "playableStateNamespaceId");
    requireEqual(
        text(scope, "playableStateNamespaceId"),
        targetEvidence.playableStateNamespaceId().toString(),
        "target playableStateNamespaceId");
    requireEqual(
        text(scope, "playableStateScope"), candidate.playableStateScope(), "playableStateScope");
    requireEqual(
        text(scope, "playableStateScope"),
        targetEvidence.playableStateScope(),
        "target playableStateScope");
    requireEqual(
        text(scope, "gameInstanceId"), candidate.gameInstanceId().toString(), "gameInstanceId");
    requireEqual(
        text(scope, "gameInstanceId"),
        targetEvidence.canonicalGameInstanceId().toString(),
        "target canonicalGameInstanceId");
    requireEqual(text(scope, "characterId"), candidate.characterId().toString(), "characterId");
    requireEqual(text(scope, "sessionId"), candidate.sessionId(), "sessionId");
    requireEqual(
        positiveString(scope, "bindingGeneration"), bindingGeneration, "bindingGeneration");
    requireEqual(
        positiveString(scope, "catalogRevision"),
        BigInteger.valueOf(targetEvidence.catalogRevision()),
        "catalogRevision");
    requireEqual(
        positiveString(scope, "pointerVersion"),
        BigInteger.valueOf(targetEvidence.pointerVersion()),
        "pointerVersion");
    requireEqual(text(scope, "regionId"), candidate.regionId().toString(), "regionId");
    requireEqual(positiveString(scope, "regionEpoch"), candidate.regionEpoch(), "regionEpoch");
    requireEqual(
        BigInteger.valueOf(targetEvidence.gameInstanceId()),
        BigInteger.valueOf(candidate.runtimeGameInstanceId()),
        "runtimeGameInstanceId");

    Object expectedOld = carrier.get("expectedOldBindingGeneration");
    this.expectedOldBindingGeneration =
        expectedOld == null ? null : positiveString(carrier, "expectedOldBindingGeneration");
    if (!Objects.equals(this.expectedOldBindingGeneration, expectedOldBindingGeneration)) {
      throw new IllegalArgumentException(
          "lease expectedOldBindingGeneration differs from the durable prior generation");
    }
    if (leaseEvidence.leaseKind() == AccountGameplayAdmissionLeaseEvidence.LeaseKind.RESUME) {
      if (expectedOldBindingGeneration == null) {
        throw new IllegalArgumentException(
            "RESUME lease requires an exact durable prior generation");
      }
      this.resumeEpisodeId = UUID.fromString(text(carrier, "resumeEpisodeId"));
    } else {
      this.resumeEpisodeId = null;
    }
  }

  /** The existing transition ID is the binding decision ID; no second decision ledger is used. */
  public UUID bindingDecisionId() {
    return bindingDecisionId;
  }

  public UUID transitionId() {
    return bindingDecisionId;
  }

  public UUID requestId() {
    return UUID.fromString(text(leaseEvidence.carrier(), "requestId"));
  }

  public UUID leaseId() {
    return UUID.fromString(text(leaseEvidence.carrier(), "leaseId"));
  }

  public BigInteger leaseFence() {
    return leaseEvidence.leaseFence();
  }

  public String leaseSha256() {
    return leaseEvidence.sha256();
  }

  /** Returns the complete canonical evidence unchanged, including its authority and checkpoints. */
  public String leaseEvidenceJson() {
    return leaseEvidence.canonicalJson();
  }

  public BigInteger leaseExpiresAtEpochMillis() {
    return positiveString(leaseEvidence.carrier(), "expiresAt");
  }

  public AccountGameplayAdmissionLeaseEvidence leaseEvidence() {
    return leaseEvidence;
  }

  public AccountGameplayAdmissionLeaseEvidence.LeaseKind leaseKind() {
    return leaseEvidence.leaseKind();
  }

  public UUID resumeEpisodeId() {
    return resumeEpisodeId;
  }

  public BigInteger expectedOldBindingGeneration() {
    return expectedOldBindingGeneration;
  }

  public CanonicalGameplayBindingIdentity candidate() {
    return candidate;
  }

  public BigInteger bindingGeneration() {
    return bindingGeneration;
  }

  /**
   * The exact point-in-time catalog, OPEN pointer, and launch evidence re-read for this decision.
   */
  public CanonicalPlayableTarget targetEvidence() {
    return targetEvidence;
  }

  public Status status() {
    return Status.PROVISIONAL;
  }

  public enum Status {
    PROVISIONAL
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
    return value;
  }

  private static BigInteger requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static Map<String, Object> object(Object value, String name) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("Lease evidence has no object " + name);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) map;
    return result;
  }

  private static String text(Map<String, Object> value, String name) {
    Object field = value.get(name);
    if (!(field instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("Lease evidence has no canonical text " + name);
    }
    return text;
  }

  private static BigInteger positiveString(Map<String, Object> value, String name) {
    String text = text(value, name);
    if (!text.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(
          "Lease evidence has no canonical positive integer " + name);
    }
    return new BigInteger(text);
  }

  private static void requireEqual(Object actual, Object expected, String name) {
    if (!Objects.equals(actual, expected)) {
      throw new IllegalArgumentException("Lease bindingScope differs from exact " + name);
    }
  }
}
