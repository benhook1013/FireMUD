package net.firedevops.firemud.gamesession.repository;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable local initial issuer coverage/layout anchor returned only after finite physical
 * readback and an exact SQL installation CAS. This is not an issuer tenant barrier or admission
 * capability.
 */
public final class CanonicalGameplayIssuerInstalledAnchor {
  private static final String ENCODING = "canonical-installed-issuer-anchor/v1";

  private final UUID bootstrapOperationId;
  private final UUID bootstrapRequestId;
  private final UUID buildOperationId;
  private final String buildPlanDigest;
  private final String issuerId;
  private final BigInteger controlEpoch;
  private final UUID issuerCoverageOperationId;
  private final BigInteger operationFence;
  private final BigInteger coverageFence;
  private final BigInteger issuerInventorySnapshotRevision;
  private final BigInteger layoutVersion;
  private final BigInteger partitionCount;
  private final BigInteger partitionCapacity;
  private final String mappingIdentity;
  private final byte[] layoutDescriptorBytes;
  private final String layoutDescriptorDigest;
  private final BigInteger issuerAuthGeneration;
  private final byte[] sourceCheckpointBytes;
  private final String sourceCheckpointDigest;
  private final byte[] sourceEventBytes;
  private final String sourceEventDigest;
  private final byte[] incarnationBytes;
  private final String incarnationDigest;
  private final BigInteger globalCoverageGeneration;
  private final long partitionProofCount;
  private final long physicalReservationCount;
  private final long physicalBindingCount;
  private final BigInteger aggregateCountedCapacity;
  private final String partitionProofRootDigest;
  private final byte[] exactAnchorBytes;
  private final String exactAnchorDigest;

  CanonicalGameplayIssuerInstalledAnchor(
      UUID bootstrapOperationId,
      UUID bootstrapRequestId,
      UUID buildOperationId,
      String buildPlanDigest,
      String issuerId,
      BigInteger controlEpoch,
      UUID issuerCoverageOperationId,
      BigInteger operationFence,
      BigInteger coverageFence,
      BigInteger issuerInventorySnapshotRevision,
      BigInteger layoutVersion,
      BigInteger partitionCount,
      BigInteger partitionCapacity,
      String mappingIdentity,
      byte[] layoutDescriptorBytes,
      String layoutDescriptorDigest,
      BigInteger issuerAuthGeneration,
      byte[] sourceCheckpointBytes,
      String sourceCheckpointDigest,
      byte[] sourceEventBytes,
      String sourceEventDigest,
      byte[] incarnationBytes,
      String incarnationDigest,
      BigInteger globalCoverageGeneration,
      long partitionProofCount,
      long physicalReservationCount,
      long physicalBindingCount,
      BigInteger aggregateCountedCapacity,
      String partitionProofRootDigest,
      byte[] storedExactAnchorBytes,
      String storedExactAnchorDigest) {
    this.bootstrapOperationId = requireNonNil(bootstrapOperationId, "bootstrapOperationId");
    this.bootstrapRequestId = requireNonNil(bootstrapRequestId, "bootstrapRequestId");
    this.buildOperationId = requireNonNil(buildOperationId, "buildOperationId");
    this.buildPlanDigest = requireDigest(buildPlanDigest, "buildPlanDigest");
    this.issuerId = requireIssuer(issuerId);
    this.controlEpoch = requirePositive(controlEpoch, "controlEpoch");
    this.issuerCoverageOperationId = requireNonNil(issuerCoverageOperationId, "issuerCoverageOperationId");
    this.operationFence = requirePositive(operationFence, "operationFence");
    this.coverageFence = requirePositive(coverageFence, "coverageFence");
    if (!this.issuerCoverageOperationId.equals(this.bootstrapOperationId)
        || !this.controlEpoch.equals(this.operationFence)
        || this.operationFence.equals(this.coverageFence)) {
      throw new IllegalArgumentException("Initial anchor must retain its exact distinct operation tuple");
    }
    this.issuerInventorySnapshotRevision =
        requirePositive(issuerInventorySnapshotRevision, "issuerInventorySnapshotRevision");
    this.layoutVersion = requirePositive(layoutVersion, "layoutVersion");
    this.partitionCount = requirePositive(partitionCount, "partitionCount");
    this.partitionCapacity = requirePositive(partitionCapacity, "partitionCapacity");
    this.mappingIdentity = Objects.requireNonNull(mappingIdentity, "mappingIdentity");
    if (!CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY.equals(mappingIdentity)) {
      throw new IllegalArgumentException("Unsupported issuer layout mapping identity");
    }
    this.layoutDescriptorBytes = nonEmpty(layoutDescriptorBytes, "layoutDescriptorBytes");
    this.layoutDescriptorDigest = requireDigest(layoutDescriptorDigest, "layoutDescriptorDigest");
    requireDigestMatches(this.layoutDescriptorBytes, this.layoutDescriptorDigest, "layout descriptor");
    this.issuerAuthGeneration = requirePositive(issuerAuthGeneration, "issuerAuthGeneration");
    this.sourceCheckpointBytes = nonEmpty(sourceCheckpointBytes, "sourceCheckpointBytes");
    this.sourceCheckpointDigest = requireDigest(sourceCheckpointDigest, "sourceCheckpointDigest");
    requireDigestMatches(this.sourceCheckpointBytes, this.sourceCheckpointDigest, "source checkpoint");
    this.sourceEventBytes = sourceEventBytes == null ? null : nonEmpty(sourceEventBytes, "sourceEventBytes");
    this.sourceEventDigest = sourceEventDigest == null ? null : requireDigest(sourceEventDigest, "sourceEventDigest");
    if ((this.sourceEventBytes == null) != (this.sourceEventDigest == null)) {
      throw new IllegalArgumentException("Account source event bytes and digest must be paired");
    }
    if (this.sourceEventBytes != null) {
      requireDigestMatches(this.sourceEventBytes, this.sourceEventDigest, "source event");
    }
    this.incarnationBytes = nonEmpty(incarnationBytes, "incarnationBytes");
    this.incarnationDigest = requireDigest(incarnationDigest, "incarnationDigest");
    requireDigestMatches(this.incarnationBytes, this.incarnationDigest, "stable incarnation");
    this.globalCoverageGeneration = requirePositive(globalCoverageGeneration, "globalCoverageGeneration");
    if (partitionProofCount < 1
        || !BigInteger.valueOf(partitionProofCount).equals(partitionCount)
        || physicalReservationCount < 0
        || physicalBindingCount < 0
        || physicalBindingCount > physicalReservationCount) {
      throw new IllegalArgumentException("Installed anchor must retain the complete finite proof counts");
    }
    this.partitionProofCount = partitionProofCount;
    this.physicalReservationCount = physicalReservationCount;
    this.physicalBindingCount = physicalBindingCount;
    this.aggregateCountedCapacity = Objects.requireNonNull(aggregateCountedCapacity, "aggregateCountedCapacity");
    if (aggregateCountedCapacity.signum() < 0
        || aggregateCountedCapacity.compareTo(partitionCount.multiply(partitionCapacity)) > 0
        || aggregateCountedCapacity.compareTo(BigInteger.valueOf(physicalReservationCount)) > 0) {
      throw new IllegalArgumentException("Installed anchor aggregate capacity is outside its finite bound");
    }
    this.partitionProofRootDigest = requireDigest(partitionProofRootDigest, "partitionProofRootDigest");
    this.exactAnchorBytes = encodeExactAnchor();
    this.exactAnchorDigest = CanonicalGameplayIssuerMemberV2Codec.sha256(this.exactAnchorBytes);
    if ((storedExactAnchorBytes == null) != (storedExactAnchorDigest == null)
        || (storedExactAnchorBytes != null
            && (!java.util.Arrays.equals(this.exactAnchorBytes, storedExactAnchorBytes)
                || !this.exactAnchorDigest.equals(storedExactAnchorDigest)))) {
      throw new IllegalArgumentException("Stored installed-anchor bytes differ from their exact tuple");
    }
  }

  static byte[] encodeIncarnation(CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation) {
    Objects.requireNonNull(incarnation, "incarnation");
    var deployment = incarnation.targetDeploymentEvidence();
    return encode(
        output -> {
          writeText(output, "canonical-stable-issuer-incarnation/v1");
          writeText(output, incarnation.attestorBinding());
          writeText(output, incarnation.acceptedAttestationDigest());
          writeText(output, deployment.deploymentIdentity());
          writeText(output, deployment.deploymentGeneration());
          output.writeInt(deployment.canonicalNodeSet().size());
          for (String node : deployment.canonicalNodeSet()) writeText(output, node);
          writeText(output, deployment.redisBuildIdentity());
          writeText(output, deployment.luaScriptRegistryVersion());
          writeText(output, deployment.luaScriptRegistryDigest());
          writeText(output, deployment.startupState());
          writeText(output, deployment.bootGeneration());
          writeText(output, deployment.startupGeneration());
          writeText(output, deployment.storageGeneration());
          writeText(output, deployment.aofGeneration());
          writeText(output, incarnation.coordOpsRoAclDigest());
          writeText(output, incarnation.coordRedisConfigObservedDigest());
        });
  }

  private byte[] encodeExactAnchor() {
    return encode(
        output -> {
          writeText(output, ENCODING);
          writeText(output, bootstrapOperationId.toString());
          writeText(output, bootstrapRequestId.toString());
          writeText(output, buildOperationId.toString());
          writeText(output, buildPlanDigest);
          writeText(output, issuerId);
          writeBigInteger(output, controlEpoch);
          writeText(output, issuerCoverageOperationId.toString());
          writeBigInteger(output, operationFence);
          writeBigInteger(output, coverageFence);
          writeBigInteger(output, issuerInventorySnapshotRevision);
          writeBigInteger(output, layoutVersion);
          writeBigInteger(output, partitionCount);
          writeBigInteger(output, partitionCapacity);
          writeText(output, mappingIdentity);
          writeBytes(output, layoutDescriptorBytes);
          writeText(output, layoutDescriptorDigest);
          writeBigInteger(output, issuerAuthGeneration);
          writeBytes(output, sourceCheckpointBytes);
          writeText(output, sourceCheckpointDigest);
          writeNullableBytes(output, sourceEventBytes);
          writeNullableText(output, sourceEventDigest);
          writeBytes(output, incarnationBytes);
          writeText(output, incarnationDigest);
          writeBigInteger(output, globalCoverageGeneration);
          output.writeLong(partitionProofCount);
          output.writeLong(physicalReservationCount);
          output.writeLong(physicalBindingCount);
          writeBigInteger(output, aggregateCountedCapacity);
          writeText(output, partitionProofRootDigest);
        });
  }

  public UUID bootstrapOperationId() { return bootstrapOperationId; }
  public UUID bootstrapRequestId() { return bootstrapRequestId; }
  public UUID buildOperationId() { return buildOperationId; }
  public String buildPlanDigest() { return buildPlanDigest; }
  public String issuerId() { return issuerId; }
  public BigInteger controlEpoch() { return controlEpoch; }
  public UUID issuerCoverageOperationId() { return issuerCoverageOperationId; }
  public BigInteger operationFence() { return operationFence; }
  public BigInteger coverageFence() { return coverageFence; }
  public BigInteger issuerInventorySnapshotRevision() { return issuerInventorySnapshotRevision; }
  public BigInteger layoutVersion() { return layoutVersion; }
  public BigInteger partitionCount() { return partitionCount; }
  public BigInteger partitionCapacity() { return partitionCapacity; }
  public String mappingIdentity() { return mappingIdentity; }
  public byte[] layoutDescriptorBytes() { return layoutDescriptorBytes.clone(); }
  public String layoutDescriptorDigest() { return layoutDescriptorDigest; }
  public BigInteger issuerAuthGeneration() { return issuerAuthGeneration; }
  public byte[] sourceCheckpointBytes() { return sourceCheckpointBytes.clone(); }
  public String sourceCheckpointDigest() { return sourceCheckpointDigest; }
  public byte[] sourceEventBytes() { return sourceEventBytes == null ? null : sourceEventBytes.clone(); }
  public String sourceEventDigest() { return sourceEventDigest; }
  public byte[] incarnationBytes() { return incarnationBytes.clone(); }
  public String incarnationDigest() { return incarnationDigest; }
  public BigInteger globalCoverageGeneration() { return globalCoverageGeneration; }
  public long partitionProofCount() { return partitionProofCount; }
  public long physicalReservationCount() { return physicalReservationCount; }
  public long physicalBindingCount() { return physicalBindingCount; }
  public BigInteger aggregateCountedCapacity() { return aggregateCountedCapacity; }
  public String partitionProofRootDigest() { return partitionProofRootDigest; }
  public byte[] exactAnchorBytes() { return exactAnchorBytes.clone(); }
  public String exactAnchorDigest() { return exactAnchorDigest; }

  private static byte[] encode(IoWriter writer) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        writer.write(output);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("Could not encode exact issuer anchor bytes", impossible);
    }
  }

  private static void writeText(DataOutputStream output, String value) throws IOException {
    writeBytes(output, Objects.requireNonNull(value, "exact text").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static void writeBigInteger(DataOutputStream output, BigInteger value) throws IOException {
    writeText(output, Objects.requireNonNull(value, "exact counter").toString());
  }

  private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static void writeNullableBytes(DataOutputStream output, byte[] bytes) throws IOException {
    output.writeBoolean(bytes != null);
    if (bytes != null) writeBytes(output, bytes);
  }

  private static void writeNullableText(DataOutputStream output, String value) throws IOException {
    output.writeBoolean(value != null);
    if (value != null) writeText(output, value);
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) throw new IllegalArgumentException(name + " must not be nil");
    return value;
  }

  private static String requireIssuer(String value) {
    if (!net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.ISSUER.equals(value)) {
      throw new IllegalArgumentException("Installed anchor issuer scope is not canonical");
    }
    return value;
  }

  private static BigInteger requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
    return value;
  }

  private static byte[] nonEmpty(byte[] value, String name) {
    Objects.requireNonNull(value, name);
    if (value.length == 0) throw new IllegalArgumentException(name + " must not be empty");
    return value.clone();
  }

  private static String requireDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a canonical SHA-256 digest");
    }
    return value;
  }

  private static void requireDigestMatches(byte[] bytes, String digest, String name) {
    if (!CanonicalGameplayIssuerMemberV2Codec.sha256(bytes).equals(digest)) {
      throw new IllegalArgumentException(name + " digest does not match its exact bytes");
    }
  }

  @FunctionalInterface
  private interface IoWriter {
    void write(DataOutputStream output) throws IOException;
  }
}
