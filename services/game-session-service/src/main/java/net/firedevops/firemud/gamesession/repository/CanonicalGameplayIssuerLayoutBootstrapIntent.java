package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationDisposition;

/**
 * Immutable SQL read model for the pending initial issuer-layout bootstrap intent.
 *
 * <p>This model is deliberately limited to {@link State#INITIAL_BOOTSTRAP_INTENT}. It contains no
 * active pointer, installed anchor, issuer coverage acknowledgement, or admission authority.
 */
final class CanonicalGameplayIssuerLayoutBootstrapIntent {
  static final String MAPPING_IDENTITY = "bindingRef/v1;sha256-u256-mod/v1";

  enum State {
    INITIAL_BOOTSTRAP_INTENT
  }

  enum CohortKind {
    BINDING,
    UNRESOLVED_LEGACY_BINDING,
    TRANSITION,
    RESERVATION,
    UNRESOLVED_LEGACY_RESERVATION,
    ACCOUNT_OBLIGATION,
    ISSUER_OBLIGATION,
    REGION_OBLIGATION
  }

  private final UUID operationId;
  private final UUID requestId;
  private final String issuerId;
  private final BigInteger controlEpoch;
  private final BigInteger operationFence;
  private final BigInteger coverageFence;
  private final BigInteger inventorySnapshotRevision;
  private final LayoutDescriptor layoutDescriptor;
  private final AccountIssuerSourceCheckpoint sourceCheckpoint;
  private final CanonicalGameplayLegacyMigrationDisposition legacyDisposition;
  private final State state;
  private final List<CohortRow> cohortRows;
  private final String cohortDigest;

  CanonicalGameplayIssuerLayoutBootstrapIntent(
      UUID operationId,
      UUID requestId,
      String issuerId,
      BigInteger controlEpoch,
      BigInteger operationFence,
      BigInteger coverageFence,
      BigInteger inventorySnapshotRevision,
      LayoutDescriptor layoutDescriptor,
      AccountIssuerSourceCheckpoint sourceCheckpoint,
      CanonicalGameplayLegacyMigrationDisposition legacyDisposition,
      State state,
      List<CohortRow> cohortRows,
      String cohortDigest) {
    this.operationId = requireNonNil(operationId, "operationId");
    this.requestId = requireNonNil(requestId, "requestId");
    if (operationId.equals(requestId)) {
      throw new IllegalArgumentException("operationId and requestId must be distinct");
    }
    this.issuerId = requireIssuerId(issuerId);
    this.controlEpoch = requirePositive(controlEpoch, "controlEpoch");
    this.operationFence = requirePositive(operationFence, "operationFence");
    this.coverageFence = requirePositive(coverageFence, "coverageFence");
    this.inventorySnapshotRevision =
        requirePositive(inventorySnapshotRevision, "inventorySnapshotRevision");
    if (!operationFence.equals(controlEpoch) || operationFence.equals(coverageFence)) {
      throw new IllegalArgumentException(
          "controlEpoch must equal operationFence and coverageFence must be distinct");
    }
    this.layoutDescriptor = Objects.requireNonNull(layoutDescriptor, "layoutDescriptor");
    this.sourceCheckpoint = Objects.requireNonNull(sourceCheckpoint, "sourceCheckpoint");
    if (!issuerId.equals(layoutDescriptor.issuerId())
        || !issuerId.equals(sourceCheckpoint.issuerId())) {
      throw new IllegalArgumentException("Issuer layout and source checkpoint scopes must match");
    }
    this.legacyDisposition = Objects.requireNonNull(legacyDisposition, "legacyDisposition");
    if (legacyDisposition.canonicalInventoryRevision().compareTo(inventorySnapshotRevision) > 0) {
      throw new IllegalArgumentException(
          "Legacy disposition cannot be newer than the captured issuer inventory");
    }
    this.state = Objects.requireNonNull(state, "state");
    if (state != State.INITIAL_BOOTSTRAP_INTENT) {
      throw new IllegalArgumentException("Only a pending initial-bootstrap intent is supported");
    }
    this.cohortRows = List.copyOf(Objects.requireNonNull(cohortRows, "cohortRows"));
    if (this.cohortRows.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Issuer cohort rows must not contain null");
    }
    for (int index = 0; index < this.cohortRows.size(); index++) {
      if (this.cohortRows.get(index).rowOrdinal() != index) {
        throw new IllegalArgumentException(
            "Issuer cohort row ordinals must be contiguous from zero");
      }
    }
    this.cohortDigest = requireDigest(cohortDigest, "cohortDigest");
  }

  UUID operationId() {
    return operationId;
  }

  UUID requestId() {
    return requestId;
  }

  String issuerId() {
    return issuerId;
  }

  BigInteger controlEpoch() {
    return controlEpoch;
  }

  BigInteger operationFence() {
    return operationFence;
  }

  BigInteger coverageFence() {
    return coverageFence;
  }

  BigInteger inventorySnapshotRevision() {
    return inventorySnapshotRevision;
  }

  LayoutDescriptor layoutDescriptor() {
    return layoutDescriptor;
  }

  AccountIssuerSourceCheckpoint sourceCheckpoint() {
    return sourceCheckpoint;
  }

  CanonicalGameplayLegacyMigrationDisposition legacyDisposition() {
    return legacyDisposition;
  }

  State state() {
    return state;
  }

  List<CohortRow> cohortRows() {
    return cohortRows;
  }

  String cohortDigest() {
    return cohortDigest;
  }

  static final class LayoutDescriptor {
    private final String issuerId;
    private final BigInteger layoutVersion;
    private final BigInteger partitionCount;
    private final BigInteger partitionCapacity;
    private final String mappingIdentity;
    private final byte[] exactDescriptorBytes;
    private final String descriptorDigest;

    LayoutDescriptor(
        String issuerId,
        BigInteger layoutVersion,
        BigInteger partitionCount,
        BigInteger partitionCapacity,
        String mappingIdentity,
        byte[] exactDescriptorBytes,
        String descriptorDigest) {
      this.issuerId = requireIssuerId(issuerId);
      this.layoutVersion = requirePositive(layoutVersion, "layoutVersion");
      this.partitionCount = requirePositive(partitionCount, "partitionCount");
      this.partitionCapacity = requirePositive(partitionCapacity, "partitionCapacity");
      this.mappingIdentity = Objects.requireNonNull(mappingIdentity, "mappingIdentity");
      if (!MAPPING_IDENTITY.equals(mappingIdentity)) {
        throw new IllegalArgumentException("Unsupported canonical issuer mapping identity");
      }
      this.exactDescriptorBytes = nonEmptyBytes(exactDescriptorBytes, "exactDescriptorBytes");
      this.descriptorDigest = requireDigest(descriptorDigest, "descriptorDigest");
    }

    String issuerId() {
      return issuerId;
    }

    BigInteger layoutVersion() {
      return layoutVersion;
    }

    BigInteger partitionCount() {
      return partitionCount;
    }

    BigInteger partitionCapacity() {
      return partitionCapacity;
    }

    String mappingIdentity() {
      return mappingIdentity;
    }

    byte[] exactDescriptorBytes() {
      return exactDescriptorBytes.clone();
    }

    String descriptorDigest() {
      return descriptorDigest;
    }
  }

  static final class AccountIssuerSourceCheckpoint {
    private final String issuerId;
    private final BigInteger issuerAuthGeneration;
    private final byte[] exactCheckpointBytes;
    private final String checkpointDigest;
    private final byte[] latestIssuerEventBytes;
    private final String latestIssuerEventDigest;

    AccountIssuerSourceCheckpoint(
        String issuerId,
        BigInteger issuerAuthGeneration,
        byte[] exactCheckpointBytes,
        String checkpointDigest,
        byte[] latestIssuerEventBytes,
        String latestIssuerEventDigest) {
      this.issuerId = requireIssuerId(issuerId);
      this.issuerAuthGeneration = requirePositive(issuerAuthGeneration, "issuerAuthGeneration");
      this.exactCheckpointBytes = nonEmptyBytes(exactCheckpointBytes, "exactCheckpointBytes");
      this.checkpointDigest = requireDigest(checkpointDigest, "checkpointDigest");
      if ((latestIssuerEventBytes == null) != (latestIssuerEventDigest == null)) {
        throw new IllegalArgumentException("Latest issuer event bytes and digest must be paired");
      }
      this.latestIssuerEventBytes =
          latestIssuerEventBytes == null
              ? null
              : nonEmptyBytes(latestIssuerEventBytes, "latestIssuerEventBytes");
      this.latestIssuerEventDigest =
          latestIssuerEventDigest == null
              ? null
              : requireDigest(latestIssuerEventDigest, "latestIssuerEventDigest");
    }

    String issuerId() {
      return issuerId;
    }

    BigInteger issuerAuthGeneration() {
      return issuerAuthGeneration;
    }

    byte[] exactCheckpointBytes() {
      return exactCheckpointBytes.clone();
    }

    String checkpointDigest() {
      return checkpointDigest;
    }

    byte[] latestIssuerEventBytes() {
      return latestIssuerEventBytes == null ? null : latestIssuerEventBytes.clone();
    }

    String latestIssuerEventDigest() {
      return latestIssuerEventDigest;
    }
  }

  static final class CohortRow {
    private final long rowOrdinal;
    private final CohortKind kind;
    private final String sourceKey;
    private final BigInteger sourceInventoryRevision;
    private final String sourceRowJson;
    private final String sourceRowDigest;

    CohortRow(
        long rowOrdinal,
        CohortKind kind,
        String sourceKey,
        BigInteger sourceInventoryRevision,
        String sourceRowJson,
        String sourceRowDigest) {
      if (rowOrdinal < 0) throw new IllegalArgumentException("rowOrdinal must not be negative");
      this.rowOrdinal = rowOrdinal;
      this.kind = Objects.requireNonNull(kind, "kind");
      this.sourceKey = requireNonBlank(sourceKey, "sourceKey");
      this.sourceInventoryRevision =
          requirePositive(sourceInventoryRevision, "sourceInventoryRevision");
      this.sourceRowJson = requireNonBlank(sourceRowJson, "sourceRowJson");
      this.sourceRowDigest = requireDigest(sourceRowDigest, "sourceRowDigest");
    }

    long rowOrdinal() {
      return rowOrdinal;
    }

    CohortKind kind() {
      return kind;
    }

    String sourceKey() {
      return sourceKey;
    }

    BigInteger sourceInventoryRevision() {
      return sourceInventoryRevision;
    }

    String sourceRowJson() {
      return sourceRowJson;
    }

    String sourceRowDigest() {
      return sourceRowDigest;
    }
  }

  private static String requireIssuerId(String value) {
    Objects.requireNonNull(value, "issuerId");
    if (!GameSessionAccountDelegationProfile.ISSUER.equals(value)) {
      throw new IllegalArgumentException("Issuer must be the exact Account environment issuer");
    }
    return value;
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
    return value;
  }

  private static BigInteger requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
    return value;
  }

  private static byte[] nonEmptyBytes(byte[] value, String name) {
    Objects.requireNonNull(value, name);
    if (value.length == 0) throw new IllegalArgumentException(name + " must not be empty");
    return value.clone();
  }

  private static String requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    return value;
  }

  private static String requireDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a canonical SHA-256 digest");
    }
    return value;
  }
}
