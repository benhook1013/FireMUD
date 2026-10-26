package net.firedevops.firemud.gamesession.repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;

/** Exact live issuer admission-control value, in its own epoch domain from the V1030 layout. */
final class CanonicalGameplayIssuerAdmissionControlValue {
  static final String SCHEMA = "gs_issuer_admission_control/v1";
  static final int MAX_BYTES = 512 * 1024;
  private static final String ENCODING = "canonical-live-issuer-admission-control/v1";

  enum State {
    OPEN,
    CUTOFF_PENDING,
    INSTALLED
  }

  enum OperationKind {
    INITIAL_ANCHOR_INSTALL,
    CUTOFF
  }

  private final String issuerId;
  private final State state;
  private final BigInteger liveBarrierEpoch;
  private final UUID currentOperationId;
  private final OperationKind currentOperationKind;
  private final UUID bootstrapOperationId;
  private final UUID bootstrapRequestId;
  private final BigInteger bootstrapControlEpoch;
  private final byte[] anchorExactBytes;
  private final String anchorExactDigest;
  private final BigInteger accountIssuerAuthGeneration;
  private final byte[] accountCheckpointBytes;
  private final String accountCheckpointDigest;
  private final byte[] accountSourceEventBytes;
  private final String accountSourceEventDigest;
  private final byte[] stableIncarnationBytes;
  private final String stableIncarnationDigest;
  private final BigInteger inventorySnapshotRevision;
  private final long cohortRowCount;
  private final String cohortDigest;
  private final byte[] exactBytes;
  private final String exactDigest;

  private CanonicalGameplayIssuerAdmissionControlValue(
      String issuerId,
      State state,
      BigInteger liveBarrierEpoch,
      UUID currentOperationId,
      OperationKind currentOperationKind,
      UUID bootstrapOperationId,
      UUID bootstrapRequestId,
      BigInteger bootstrapControlEpoch,
      byte[] anchorExactBytes,
      String anchorExactDigest,
      BigInteger accountIssuerAuthGeneration,
      byte[] accountCheckpointBytes,
      String accountCheckpointDigest,
      byte[] accountSourceEventBytes,
      String accountSourceEventDigest,
      byte[] stableIncarnationBytes,
      String stableIncarnationDigest,
      BigInteger inventorySnapshotRevision,
      long cohortRowCount,
      String cohortDigest) {
    this.issuerId = requireIssuer(issuerId);
    this.state = Objects.requireNonNull(state, "state");
    this.liveBarrierEpoch = requirePositive(liveBarrierEpoch, "liveBarrierEpoch");
    this.currentOperationId = requireNonNil(currentOperationId, "currentOperationId");
    this.currentOperationKind = Objects.requireNonNull(currentOperationKind, "currentOperationKind");
    this.bootstrapOperationId = requireNonNil(bootstrapOperationId, "bootstrapOperationId");
    this.bootstrapRequestId = requireNonNil(bootstrapRequestId, "bootstrapRequestId");
    this.bootstrapControlEpoch = requirePositive(bootstrapControlEpoch, "bootstrapControlEpoch");
    this.anchorExactBytes = nonEmpty(anchorExactBytes, "anchorExactBytes");
    this.anchorExactDigest = requireDigest(anchorExactDigest, "anchorExactDigest");
    requireDigestMatches(this.anchorExactBytes, this.anchorExactDigest, "installed anchor");
    this.accountIssuerAuthGeneration =
        requirePositive(accountIssuerAuthGeneration, "accountIssuerAuthGeneration");
    this.accountCheckpointBytes = nonEmpty(accountCheckpointBytes, "accountCheckpointBytes");
    this.accountCheckpointDigest = requireDigest(accountCheckpointDigest, "accountCheckpointDigest");
    requireDigestMatches(
        this.accountCheckpointBytes, this.accountCheckpointDigest, "Account checkpoint");
    if ((accountSourceEventBytes == null) != (accountSourceEventDigest == null)) {
      throw new IllegalArgumentException("Account source event bytes and digest must be paired");
    }
    this.accountSourceEventBytes =
        accountSourceEventBytes == null ? null : nonEmpty(accountSourceEventBytes, "accountSourceEventBytes");
    this.accountSourceEventDigest =
        accountSourceEventDigest == null ? null : requireDigest(accountSourceEventDigest, "accountSourceEventDigest");
    if (this.accountSourceEventBytes != null) {
      requireDigestMatches(
          this.accountSourceEventBytes, this.accountSourceEventDigest, "Account source event");
    }
    this.stableIncarnationBytes = nonEmpty(stableIncarnationBytes, "stableIncarnationBytes");
    this.stableIncarnationDigest = requireDigest(stableIncarnationDigest, "stableIncarnationDigest");
    requireDigestMatches(
        this.stableIncarnationBytes, this.stableIncarnationDigest, "stable incarnation");
    this.inventorySnapshotRevision =
        requirePositive(inventorySnapshotRevision, "inventorySnapshotRevision");
    if (cohortRowCount < 0) throw new IllegalArgumentException("cohortRowCount must not be negative");
    this.cohortRowCount = cohortRowCount;
    this.cohortDigest = requireDigest(cohortDigest, "cohortDigest");
    if ((state == State.OPEN) != (currentOperationKind == OperationKind.INITIAL_ANCHOR_INSTALL)) {
      throw new IllegalArgumentException("Only the initial-anchor install may establish OPEN");
    }
    this.exactBytes = encode();
    if (exactBytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Issuer admission-control value exceeds its bound");
    }
    this.exactDigest = sha256(exactBytes);
  }

  static CanonicalGameplayIssuerAdmissionControlValue initialOpen(
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current,
      BigInteger liveBarrierEpoch,
      long cohortRowCount,
      String cohortDigest) {
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(current, "current");
    var checkpoint = current.accountCheckpoint();
    byte[] anchorBytes = anchor.exactAnchorBytes();
    if (!anchor.issuerId().equals(checkpoint.issuerId())
        || !anchor.issuerAuthGeneration().equals(checkpoint.issuerAuthGeneration())
        || !Arrays.equals(anchor.sourceCheckpointBytes(), checkpoint.exactCheckpointBytes())
        || !Objects.equals(anchor.sourceEventDigest(), checkpoint.latestIssuerEventDigest())
        || !Arrays.equals(anchor.sourceEventBytes(), checkpoint.latestIssuerEventBytes())
        || !Arrays.equals(
            anchor.incarnationBytes(),
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(current.incarnation()))) {
      throw new IllegalArgumentException("Live issuer control requires the exact current anchor sources");
    }
    return new CanonicalGameplayIssuerAdmissionControlValue(
        anchor.issuerId(),
        State.OPEN,
        liveBarrierEpoch,
        anchor.bootstrapOperationId(),
        OperationKind.INITIAL_ANCHOR_INSTALL,
        anchor.bootstrapOperationId(),
        anchor.bootstrapRequestId(),
        anchor.controlEpoch(),
        anchorBytes,
        sha256(anchorBytes),
        checkpoint.issuerAuthGeneration(),
        checkpoint.exactCheckpointBytes(),
        checkpoint.checkpointDigest(),
        checkpoint.latestIssuerEventBytes(),
        checkpoint.latestIssuerEventDigest(),
        anchor.incarnationBytes(),
        anchor.incarnationDigest(),
        anchor.issuerInventorySnapshotRevision(),
        cohortRowCount,
        cohortDigest);
  }

  CanonicalGameplayIssuerAdmissionControlValue cutoffPending(
      UUID cutoffOperationId,
      BigInteger targetLiveBarrierEpoch,
      BigInteger snapshotRevision,
      long frozenCohortRowCount,
      String frozenCohortDigest) {
    if (state != State.OPEN
        || currentOperationKind != OperationKind.INITIAL_ANCHOR_INSTALL
        || targetLiveBarrierEpoch.compareTo(liveBarrierEpoch) <= 0) {
      throw new IllegalArgumentException("Issuer cutoff must advance the live admission epoch");
    }
    return new CanonicalGameplayIssuerAdmissionControlValue(
        issuerId,
        State.CUTOFF_PENDING,
        targetLiveBarrierEpoch,
        requireNonNil(cutoffOperationId, "cutoffOperationId"),
        OperationKind.CUTOFF,
        bootstrapOperationId,
        bootstrapRequestId,
        bootstrapControlEpoch,
        anchorExactBytes,
        anchorExactDigest,
        accountIssuerAuthGeneration,
        accountCheckpointBytes,
        accountCheckpointDigest,
        accountSourceEventBytes,
        accountSourceEventDigest,
        stableIncarnationBytes,
        stableIncarnationDigest,
        snapshotRevision,
        frozenCohortRowCount,
        frozenCohortDigest);
  }

  CanonicalGameplayIssuerAdmissionControlValue installed() {
    if (state != State.CUTOFF_PENDING || currentOperationKind != OperationKind.CUTOFF) {
      throw new IllegalArgumentException("Only a pending cutoff can become installed");
    }
    return new CanonicalGameplayIssuerAdmissionControlValue(
        issuerId,
        State.INSTALLED,
        liveBarrierEpoch,
        currentOperationId,
        currentOperationKind,
        bootstrapOperationId,
        bootstrapRequestId,
        bootstrapControlEpoch,
        anchorExactBytes,
        anchorExactDigest,
        accountIssuerAuthGeneration,
        accountCheckpointBytes,
        accountCheckpointDigest,
        accountSourceEventBytes,
        accountSourceEventDigest,
        stableIncarnationBytes,
        stableIncarnationDigest,
        inventorySnapshotRevision,
        cohortRowCount,
        cohortDigest);
  }

  static CanonicalGameplayIssuerAdmissionControlValue decode(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw malformed();
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (!ENCODING.equals(readText(in))) throw malformed();
      CanonicalGameplayIssuerAdmissionControlValue result =
          new CanonicalGameplayIssuerAdmissionControlValue(
              readText(in),
              State.valueOf(readText(in)),
              readPositive(in),
              readUuid(in),
              OperationKind.valueOf(readText(in)),
              readUuid(in),
              readUuid(in),
              readPositive(in),
              readBytes(in),
              readText(in),
              readPositive(in),
              readBytes(in),
              readText(in),
              readNullableBytes(in),
              readNullableText(in),
              readBytes(in),
              readText(in),
              readPositive(in),
              in.readLong(),
              readText(in));
      if (in.available() != 0 || !Arrays.equals(bytes, result.exactBytes())) throw malformed();
      return result;
    } catch (IOException | RuntimeException invalid) {
      throw malformed();
    }
  }

  private byte[] encode() {
    return encode(
        out -> {
          writeText(out, ENCODING);
          writeText(out, issuerId);
          writeText(out, state.name());
          writeBigInteger(out, liveBarrierEpoch);
          writeUuid(out, currentOperationId);
          writeText(out, currentOperationKind.name());
          writeUuid(out, bootstrapOperationId);
          writeUuid(out, bootstrapRequestId);
          writeBigInteger(out, bootstrapControlEpoch);
          writeBytes(out, anchorExactBytes);
          writeText(out, anchorExactDigest);
          writeBigInteger(out, accountIssuerAuthGeneration);
          writeBytes(out, accountCheckpointBytes);
          writeText(out, accountCheckpointDigest);
          writeNullableBytes(out, accountSourceEventBytes);
          writeNullableText(out, accountSourceEventDigest);
          writeBytes(out, stableIncarnationBytes);
          writeText(out, stableIncarnationDigest);
          writeBigInteger(out, inventorySnapshotRevision);
          out.writeLong(cohortRowCount);
          writeText(out, cohortDigest);
        });
  }

  boolean matchesCurrentIssuerState(
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current) {
    Objects.requireNonNull(current, "current");
    var checkpoint = current.accountCheckpoint();
    return issuerId.equals(checkpoint.issuerId())
        && accountIssuerAuthGeneration.equals(checkpoint.issuerAuthGeneration())
        && Arrays.equals(accountCheckpointBytes, checkpoint.exactCheckpointBytes())
        && accountCheckpointDigest.equals(checkpoint.checkpointDigest())
        && Arrays.equals(accountSourceEventBytes, checkpoint.latestIssuerEventBytes())
        && Objects.equals(accountSourceEventDigest, checkpoint.latestIssuerEventDigest())
        && Arrays.equals(
            stableIncarnationBytes,
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(current.incarnation()));
  }

  String issuerId() { return issuerId; }
  State state() { return state; }
  BigInteger liveBarrierEpoch() { return liveBarrierEpoch; }
  UUID currentOperationId() { return currentOperationId; }
  OperationKind currentOperationKind() { return currentOperationKind; }
  UUID bootstrapOperationId() { return bootstrapOperationId; }
  UUID bootstrapRequestId() { return bootstrapRequestId; }
  BigInteger bootstrapControlEpoch() { return bootstrapControlEpoch; }
  byte[] anchorExactBytes() { return anchorExactBytes.clone(); }
  String anchorExactDigest() { return anchorExactDigest; }
  BigInteger accountIssuerAuthGeneration() { return accountIssuerAuthGeneration; }
  byte[] accountCheckpointBytes() { return accountCheckpointBytes.clone(); }
  String accountCheckpointDigest() { return accountCheckpointDigest; }
  byte[] accountSourceEventBytes() { return accountSourceEventBytes == null ? null : accountSourceEventBytes.clone(); }
  String accountSourceEventDigest() { return accountSourceEventDigest; }
  byte[] stableIncarnationBytes() { return stableIncarnationBytes.clone(); }
  String stableIncarnationDigest() { return stableIncarnationDigest; }
  BigInteger inventorySnapshotRevision() { return inventorySnapshotRevision; }
  long cohortRowCount() { return cohortRowCount; }
  String cohortDigest() { return cohortDigest; }
  byte[] exactBytes() { return exactBytes.clone(); }
  String exactDigest() { return exactDigest; }

  static String sha256(byte[] bytes) {
    try {
      return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) {
      throw new IllegalStateException("Could not digest exact issuer admission-control bytes", failure);
    }
  }

  private static byte[] encode(Writer writer) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        writer.write(out);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("Could not encode issuer admission-control bytes", impossible);
    }
  }

  private static void writeText(DataOutputStream out, String value) throws IOException {
    byte[] bytes = Objects.requireNonNull(value, "text").getBytes(StandardCharsets.UTF_8);
    writeBytes(out, bytes);
  }

  private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
    if (value == null || value.length == 0 || value.length > MAX_BYTES) {
      throw new IllegalArgumentException("Control bytes are outside their bound");
    }
    out.writeInt(value.length);
    out.write(value);
  }

  private static void writeNullableBytes(DataOutputStream out, byte[] value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) writeBytes(out, value);
  }

  private static void writeNullableText(DataOutputStream out, String value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) writeText(out, value);
  }

  private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
    writeText(out, requireNonNil(value, "uuid").toString());
  }

  private static void writeBigInteger(DataOutputStream out, BigInteger value) throws IOException {
    writeText(out, requirePositive(value, "counter").toString());
  }

  private static String readText(DataInputStream in) throws IOException {
    byte[] bytes = readBytes(in);
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException invalid) {
      throw malformed();
    }
  }

  private static byte[] readBytes(DataInputStream in) throws IOException {
    int length = in.readInt();
    if (length < 1 || length > MAX_BYTES || length > in.available()) throw malformed();
    return in.readNBytes(length);
  }

  private static byte[] readNullableBytes(DataInputStream in) throws IOException {
    return in.readBoolean() ? readBytes(in) : null;
  }

  private static String readNullableText(DataInputStream in) throws IOException {
    return in.readBoolean() ? readText(in) : null;
  }

  private static UUID readUuid(DataInputStream in) throws IOException {
    String value = readText(in);
    UUID uuid;
    try {
      uuid = UUID.fromString(value);
    } catch (RuntimeException malformed) {
      throw malformed();
    }
    if (!uuid.toString().equals(value) || uuid.equals(new UUID(0L, 0L))) throw malformed();
    return uuid;
  }

  private static BigInteger readPositive(DataInputStream in) throws IOException {
    String value = readText(in);
    if (!value.matches("[1-9][0-9]{0,255}")) throw malformed();
    return new BigInteger(value);
  }

  private static String requireIssuer(String issuerId) {
    if (!GameSessionAccountDelegationProfile.ISSUER.equals(issuerId)) {
      throw new IllegalArgumentException("Live issuer control requires the exact Account issuer");
    }
    return issuerId;
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) throw new IllegalArgumentException(name + " must not be nil");
    return value;
  }

  private static BigInteger requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0 || value.toString().length() > 256) {
      throw new IllegalArgumentException(name + " must be a bounded positive integer");
    }
    return value;
  }

  private static byte[] nonEmpty(byte[] value, String name) {
    Objects.requireNonNull(value, name);
    if (value.length == 0 || value.length > MAX_BYTES) {
      throw new IllegalArgumentException(name + " is outside its byte bound");
    }
    return value.clone();
  }

  private static String requireDigest(String digest, String name) {
    Objects.requireNonNull(digest, name);
    if (!digest.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException(name + " is malformed");
    return digest;
  }

  private static void requireDigestMatches(byte[] bytes, String digest, String name) {
    if (!sha256(bytes).equals(digest)) throw new IllegalArgumentException(name + " digest differs");
  }

  private static IllegalArgumentException malformed() {
    return new IllegalArgumentException("Issuer admission-control value is malformed");
  }

  @FunctionalInterface
  private interface Writer {
    void write(DataOutputStream out) throws IOException;
  }
}
