package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Exact SQL-issued physical removal of one terminal RELEASED capacity field. */
final class CanonicalGameplayIssuerBindingLocalRetirementOperation {
  enum Status {
    PENDING_PHYSICAL_PROOF,
    RETIRED
  }

  private final CanonicalGameplayIssuerBindingLocalReleaseOperation release;
  private final UUID operationId;
  private final BigInteger partitionId;
  private final UUID reservationId;
  private final CanonicalGameplayIssuerPartitionMutation physicalMutation;
  private final Status status;
  private final byte[] exactAbsenceReadbackFieldSetBytes;
  private final String exactAbsenceReadbackFieldSetDigest;

  CanonicalGameplayIssuerBindingLocalRetirementOperation(
      CanonicalGameplayIssuerBindingLocalReleaseOperation release,
      UUID operationId,
      CanonicalGameplayIssuerPartitionMutation physicalMutation,
      Status status,
      byte[] exactAbsenceReadbackFieldSetBytes,
      String exactAbsenceReadbackFieldSetDigest) {
    this(
        Objects.requireNonNull(release, "release"),
        operationId,
        release.releasedMemberValue().reservation().partitionId(),
        release.releasedMemberValue().reservation().reservationId(),
        physicalMutation,
        status,
        exactAbsenceReadbackFieldSetBytes,
        exactAbsenceReadbackFieldSetDigest);
  }

  private CanonicalGameplayIssuerBindingLocalRetirementOperation(
      CanonicalGameplayIssuerBindingLocalReleaseOperation release,
      UUID operationId,
      BigInteger partitionId,
      UUID reservationId,
      CanonicalGameplayIssuerPartitionMutation physicalMutation,
      Status status,
      byte[] exactAbsenceReadbackFieldSetBytes,
      String exactAbsenceReadbackFieldSetDigest) {
    this.release = release;
    this.operationId = requireNonNil(operationId, "operationId");
    this.partitionId = Objects.requireNonNull(partitionId, "partitionId");
    if (partitionId.signum() < 0)
      throw new IllegalArgumentException("partitionId must be nonnegative");
    this.reservationId = requireNonNil(reservationId, "reservationId");
    this.physicalMutation = Objects.requireNonNull(physicalMutation, "physicalMutation");
    this.status = Objects.requireNonNull(status, "status");
    this.exactAbsenceReadbackFieldSetBytes =
        exactAbsenceReadbackFieldSetBytes == null
            ? null
            : exactAbsenceReadbackFieldSetBytes.clone();
    this.exactAbsenceReadbackFieldSetDigest = exactAbsenceReadbackFieldSetDigest;
    if (physicalMutation.kind() != CanonicalGameplayIssuerPartitionMutation.Kind.RETIRE
        || !physicalMutation.ownerOperationId().equals(operationId)
        || !physicalMutation.partitionId().equals(partitionId)
        || (release == null && status != Status.RETIRED)
        || (release != null
            && (release.status()
                    != CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED
                || !release.releasedMemberValue().reservation().partitionId().equals(partitionId)
                || !release
                    .releasedMemberValue()
                    .reservation()
                    .reservationId()
                    .equals(reservationId)))
        || (status == Status.PENDING_PHYSICAL_PROOF
            && (physicalMutation.status()
                    != CanonicalGameplayIssuerPartitionMutation.Status.PENDING_PHYSICAL_PROOF
                || exactAbsenceReadbackFieldSetBytes != null
                || exactAbsenceReadbackFieldSetDigest != null))
        || (status == Status.RETIRED
            && (physicalMutation.status()
                    != CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED
                || this.exactAbsenceReadbackFieldSetBytes == null
                || this.exactAbsenceReadbackFieldSetBytes.length == 0
                || this.exactAbsenceReadbackFieldSetBytes.length > 17_825_792
                || exactAbsenceReadbackFieldSetDigest == null
                || !exactAbsenceReadbackFieldSetDigest.equals(
                    CanonicalGameplayIssuerMemberV2Codec.sha256(
                        this.exactAbsenceReadbackFieldSetBytes))
                || !Arrays.equals(
                    physicalMutation.exactReadbackFieldSetBytes(),
                    this.exactAbsenceReadbackFieldSetBytes)))) {
      throw new IllegalArgumentException(
          "Retirement differs from its committed release or revision proof");
    }
    Map<String, byte[]> fields =
        status == Status.RETIRED
            ? CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
                this.exactAbsenceReadbackFieldSetBytes)
            : Map.of();
    String capacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(reservationId);
    byte[] revision = fields.get(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD);
    if (status == Status.RETIRED
        && (fields.containsKey(capacityField)
            || !Arrays.equals(
                revision,
                physicalMutation
                    .successorRevision()
                    .toString()
                    .getBytes(StandardCharsets.US_ASCII)))) {
      throw new IllegalArgumentException(
          "Retirement proof does not establish exact capacity absence");
    }
  }

  static CanonicalGameplayIssuerBindingLocalRetirementOperation completedHistorical(
      UUID operationId,
      BigInteger partitionId,
      UUID reservationId,
      CanonicalGameplayIssuerPartitionMutation physicalMutation,
      byte[] exactAbsenceReadbackFieldSetBytes,
      String exactAbsenceReadbackFieldSetDigest) {
    return new CanonicalGameplayIssuerBindingLocalRetirementOperation(
        null,
        operationId,
        partitionId,
        reservationId,
        physicalMutation,
        Status.RETIRED,
        exactAbsenceReadbackFieldSetBytes,
        exactAbsenceReadbackFieldSetDigest);
  }

  CanonicalGameplayIssuerBindingLocalReleaseOperation release() {
    return release;
  }

  UUID operationId() {
    return operationId;
  }

  BigInteger partitionId() {
    return partitionId;
  }

  UUID reservationId() {
    return reservationId;
  }

  CanonicalGameplayIssuerPartitionMutation physicalMutation() {
    return physicalMutation;
  }

  Status status() {
    return status;
  }

  byte[] exactAbsenceReadbackFieldSetBytes() {
    return exactAbsenceReadbackFieldSetBytes == null
        ? null
        : exactAbsenceReadbackFieldSetBytes.clone();
  }

  String exactAbsenceReadbackFieldSetDigest() {
    return exactAbsenceReadbackFieldSetDigest;
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L)))
      throw new IllegalArgumentException(name + " must not be nil");
    return value;
  }
}
