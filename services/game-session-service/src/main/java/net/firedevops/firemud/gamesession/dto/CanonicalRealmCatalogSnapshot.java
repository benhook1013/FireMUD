package net.firedevops.firemud.gamesession.dto;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;

/**
 * Immutable catalog evidence for one public-production realm; it is not admission authority. A
 * run-owned fixture row carries its retained sealed fixture policy; Human rows carry none.
 */
public record CanonicalRealmCatalogSnapshot(
    String targetNamespace,
    UUID tenantId,
    String tenantSlug,
    String worldSlug,
    UUID realmId,
    String realmSlug,
    String realmDisplayName,
    boolean visible,
    boolean publicProduction,
    String stateScope,
    UUID playableStateNamespaceId,
    String characterCreationPolicy,
    long catalogRevision,
    UUID creationRequestId,
    String requestDigest,
    String receiptDigest,
    IntakeReceipt sourceIntakeReceipt,
    // Read-side only: the persisted catalog evidence form, its equality and stored launch/pointer
    // snapshots stay the shared catalog row identity; the fixture policy is re-read from the row.
    @com.fasterxml.jackson.annotation.JsonIgnore FixturePolicy fixturePolicy) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  /**
   * Catalog-retained run-owned fixture policy request bytes and its owner seal. Structural copy
   * only; the catalog repository independently verified it on readback.
   */
  public record FixturePolicy(
      byte[] requestBytes,
      UUID sealOperationId,
      String sealRequestDigest,
      String sealEvidenceDigest) {
    public FixturePolicy {
      Objects.requireNonNull(requestBytes, "requestBytes");
      if (requestBytes.length == 0) {
        throw new IllegalArgumentException("Fixture policy request bytes are required");
      }
      requestBytes = requestBytes.clone();
      requireNonNil(sealOperationId, "sealOperationId");
      Objects.requireNonNull(sealRequestDigest, "sealRequestDigest");
      Objects.requireNonNull(sealEvidenceDigest, "sealEvidenceDigest");
    }

    @Override
    public byte[] requestBytes() {
      return requestBytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof FixturePolicy that
          && Arrays.equals(requestBytes, that.requestBytes)
          && sealOperationId.equals(that.sealOperationId)
          && sealRequestDigest.equals(that.sealRequestDigest)
          && sealEvidenceDigest.equals(that.sealEvidenceDigest);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          Arrays.hashCode(requestBytes), sealOperationId, sealRequestDigest, sealEvidenceDigest);
    }

    @Override
    public String toString() {
      return "FixturePolicy[sealOperationId="
          + sealOperationId
          + ", sealEvidenceDigest="
          + sealEvidenceDigest
          + "]";
    }
  }

  /** Human catalog row without a run-owned fixture policy. */
  public CanonicalRealmCatalogSnapshot(
      String targetNamespace,
      UUID tenantId,
      String tenantSlug,
      String worldSlug,
      UUID realmId,
      String realmSlug,
      String realmDisplayName,
      boolean visible,
      boolean publicProduction,
      String stateScope,
      UUID playableStateNamespaceId,
      String characterCreationPolicy,
      long catalogRevision,
      UUID creationRequestId,
      String requestDigest,
      String receiptDigest,
      IntakeReceipt sourceIntakeReceipt) {
    this(
        targetNamespace,
        tenantId,
        tenantSlug,
        worldSlug,
        realmId,
        realmSlug,
        realmDisplayName,
        visible,
        publicProduction,
        stateScope,
        playableStateNamespaceId,
        characterCreationPolicy,
        catalogRevision,
        creationRequestId,
        requestDigest,
        receiptDigest,
        sourceIntakeReceipt,
        null);
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  public boolean isRunOwnedFixture() {
    return fixturePolicy != null;
  }

  public CanonicalRealmCatalogSnapshot {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    requireNonNil(tenantId, "tenantId");
    Objects.requireNonNull(tenantSlug, "tenantSlug");
    Objects.requireNonNull(worldSlug, "worldSlug");
    requireNonNil(realmId, "realmId");
    Objects.requireNonNull(realmSlug, "realmSlug");
    Objects.requireNonNull(realmDisplayName, "realmDisplayName");
    Objects.requireNonNull(stateScope, "stateScope");
    requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    Objects.requireNonNull(characterCreationPolicy, "characterCreationPolicy");
    requireNonNil(creationRequestId, "creationRequestId");
    Objects.requireNonNull(requestDigest, "requestDigest");
    Objects.requireNonNull(receiptDigest, "receiptDigest");
    Objects.requireNonNull(sourceIntakeReceipt, "sourceIntakeReceipt");
    if (!visible || !publicProduction || catalogRevision != 1L) {
      throw new IllegalArgumentException("Unsupported canonical initial realm catalog state");
    }
    var source = sourceIntakeReceipt.source();
    if (!targetNamespace.equals(source.targetNamespace())
        || !tenantId.equals(source.canonicalTenantId())
        || !tenantSlug.equals(source.tenantSlug())
        || !worldSlug.equals(source.worldSlug())) {
      throw new IllegalArgumentException(
          "Catalog selectors do not match the persisted authored-world source receipt");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  /** Catalog identity excludes the read-side fixture policy, which persisted snapshots omit. */
  @Override
  public boolean equals(Object other) {
    return other instanceof CanonicalRealmCatalogSnapshot that
        && java.util.Objects.equals(targetNamespace, that.targetNamespace)
        && java.util.Objects.equals(tenantId, that.tenantId)
        && java.util.Objects.equals(tenantSlug, that.tenantSlug)
        && java.util.Objects.equals(worldSlug, that.worldSlug)
        && java.util.Objects.equals(realmId, that.realmId)
        && java.util.Objects.equals(realmSlug, that.realmSlug)
        && java.util.Objects.equals(realmDisplayName, that.realmDisplayName)
        && visible == that.visible
        && publicProduction == that.publicProduction
        && java.util.Objects.equals(stateScope, that.stateScope)
        && java.util.Objects.equals(playableStateNamespaceId, that.playableStateNamespaceId)
        && java.util.Objects.equals(characterCreationPolicy, that.characterCreationPolicy)
        && catalogRevision == that.catalogRevision
        && java.util.Objects.equals(creationRequestId, that.creationRequestId)
        && java.util.Objects.equals(requestDigest, that.requestDigest)
        && java.util.Objects.equals(receiptDigest, that.receiptDigest)
        && java.util.Objects.equals(sourceIntakeReceipt, that.sourceIntakeReceipt);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(
        targetNamespace,
        tenantId,
        tenantSlug,
        worldSlug,
        realmId,
        realmSlug,
        realmDisplayName,
        visible,
        publicProduction,
        stateScope,
        playableStateNamespaceId,
        characterCreationPolicy,
        catalogRevision,
        creationRequestId,
        requestDigest,
        receiptDigest,
        sourceIntakeReceipt);
  }
}
