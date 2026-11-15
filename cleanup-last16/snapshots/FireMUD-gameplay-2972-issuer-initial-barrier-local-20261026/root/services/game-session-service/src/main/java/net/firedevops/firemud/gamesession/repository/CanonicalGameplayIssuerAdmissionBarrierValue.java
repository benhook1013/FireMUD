package net.firedevops.firemud.gamesession.repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;

/** Exact tenant-local issuer admission barrier payload; it is not an issuer-wide proof. */
final class CanonicalGameplayIssuerAdmissionBarrierValue {
  static final String SCHEMA = "gs_issuer_admission_barrier/v1";
  static final int MAX_BYTES = 512 * 1024;
  private static final String TARGET_ENCODING = "canonical-issuer-admission-target/v1";
  private static final String VALUE_ENCODING = "canonical-issuer-admission-barrier/v1";

  enum State {
    OPEN,
    CLOSED
  }

  /** Full immutable initializer source retained by OPEN and any later CLOSED value. */
  static final class Initializer {
    private final String issuerId;
    private final UUID tenantId;
    private final UUID playableStateNamespaceId;
    private final String tenantGameplayTag;
    private final UUID operationId;
    private final BigInteger admissionEpoch;
    private final UUID transitionId;
    private final UUID accountId;
    private final byte[] bindingRefBytes;
    private final byte[] reservationBytes;
    private final String reservationDigest;
    private final byte[] anchorBytes;
    private final String anchorDigest;
    private final BigInteger accountIssuerAuthGeneration;
    private final byte[] accountCheckpointBytes;
    private final String accountCheckpointDigest;
    private final byte[] accountSourceEventBytes;
    private final String accountSourceEventDigest;
    private final byte[] incarnationBytes;
    private final String incarnationDigest;
    private final byte[] registeredTargetBytes;
    private final String registeredTargetDigest;
    private final byte[] exactBytes;
    private final String exactDigest;

    Initializer(
        String issuerId,
        UUID tenantId,
        UUID playableStateNamespaceId,
        String tenantGameplayTag,
        UUID operationId,
        BigInteger admissionEpoch,
        UUID transitionId,
        UUID accountId,
        byte[] bindingRefBytes,
        byte[] reservationBytes,
        String reservationDigest,
        byte[] anchorBytes,
        String anchorDigest,
        BigInteger accountIssuerAuthGeneration,
        byte[] accountCheckpointBytes,
        String accountCheckpointDigest,
        byte[] accountSourceEventBytes,
        String accountSourceEventDigest,
        byte[] incarnationBytes,
        String incarnationDigest,
        byte[] registeredTargetBytes,
        String registeredTargetDigest) {
      this.issuerId = requireIssuer(issuerId);
      this.tenantId = requireNonNil(tenantId, "tenantId");
      this.playableStateNamespaceId =
          requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      this.tenantGameplayTag = requireTag(tenantGameplayTag);
      this.operationId = requireNonNil(operationId, "operationId");
      this.admissionEpoch = requirePositive(admissionEpoch, "admissionEpoch");
      this.transitionId = requireNonNil(transitionId, "transitionId");
      this.accountId = requireNonNil(accountId, "accountId");
      this.bindingRefBytes = nonEmpty(bindingRefBytes, "bindingRefBytes");
      this.reservationBytes = nonEmpty(reservationBytes, "reservationBytes");
      this.reservationDigest = requireDigest(reservationDigest, "reservationDigest");
      requireDigestMatches(this.reservationBytes, this.reservationDigest, "reservation");
      this.anchorBytes = nonEmpty(anchorBytes, "anchorBytes");
      this.anchorDigest = requireDigest(anchorDigest, "anchorDigest");
      requireDigestMatches(this.anchorBytes, this.anchorDigest, "installed anchor");
      this.accountIssuerAuthGeneration =
          requirePositive(accountIssuerAuthGeneration, "accountIssuerAuthGeneration");
      this.accountCheckpointBytes = nonEmpty(accountCheckpointBytes, "accountCheckpointBytes");
      this.accountCheckpointDigest =
          requireDigest(accountCheckpointDigest, "accountCheckpointDigest");
      requireDigestMatches(
          this.accountCheckpointBytes, this.accountCheckpointDigest, "Account checkpoint");
      if ((accountSourceEventBytes == null) != (accountSourceEventDigest == null)) {
        throw new IllegalArgumentException("Account source event bytes and digest must be paired");
      }
      this.accountSourceEventBytes =
          accountSourceEventBytes == null
              ? null
              : nonEmpty(accountSourceEventBytes, "accountSourceEventBytes");
      this.accountSourceEventDigest =
          accountSourceEventDigest == null
              ? null
              : requireDigest(accountSourceEventDigest, "accountSourceEventDigest");
      if (this.accountSourceEventBytes != null) {
        requireDigestMatches(
            this.accountSourceEventBytes, this.accountSourceEventDigest, "Account source event");
      }
      this.incarnationBytes = nonEmpty(incarnationBytes, "incarnationBytes");
      this.incarnationDigest = requireDigest(incarnationDigest, "incarnationDigest");
      requireDigestMatches(this.incarnationBytes, this.incarnationDigest, "incarnation");
      this.registeredTargetBytes = nonEmpty(registeredTargetBytes, "registeredTargetBytes");
      this.registeredTargetDigest =
          requireDigest(registeredTargetDigest, "registeredTargetDigest");
      requireDigestMatches(
          this.registeredTargetBytes, this.registeredTargetDigest, "registered target");
      long boundedBytes =
          (long) this.bindingRefBytes.length
              + this.reservationBytes.length
              + this.anchorBytes.length
              + this.accountCheckpointBytes.length
              + (this.accountSourceEventBytes == null ? 0 : this.accountSourceEventBytes.length)
              + this.incarnationBytes.length
              + this.registeredTargetBytes.length
              + 16_384L;
      if (boundedBytes > MAX_BYTES) {
        throw new IllegalArgumentException("Issuer initializer tuple exceeds its encoded bound");
      }
      this.exactBytes = encodeTarget();
      this.exactDigest = sha256(this.exactBytes);
      if (this.exactBytes.length > MAX_BYTES) {
        throw new IllegalArgumentException("Issuer initializer tuple exceeds its encoded bound");
      }
    }

    private byte[] encodeTarget() {
      return encode(
          out -> {
            writeText(out, TARGET_ENCODING);
            writeText(out, issuerId);
            writeUuid(out, tenantId);
            writeUuid(out, playableStateNamespaceId);
            writeText(out, tenantGameplayTag);
            writeUuid(out, operationId);
            writeBigInteger(out, admissionEpoch);
            writeUuid(out, transitionId);
            writeUuid(out, accountId);
            writeBytes(out, bindingRefBytes);
            writeBytes(out, reservationBytes);
            writeText(out, reservationDigest);
            writeBytes(out, anchorBytes);
            writeText(out, anchorDigest);
            writeBigInteger(out, accountIssuerAuthGeneration);
            writeBytes(out, accountCheckpointBytes);
            writeText(out, accountCheckpointDigest);
            writeNullableBytes(out, accountSourceEventBytes);
            writeNullableText(out, accountSourceEventDigest);
            writeBytes(out, incarnationBytes);
            writeText(out, incarnationDigest);
            writeBytes(out, registeredTargetBytes);
            writeText(out, registeredTargetDigest);
          });
    }

    private static Initializer decode(byte[] bytes) {
      if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw malformed();
      try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
        if (!TARGET_ENCODING.equals(readText(in))) throw malformed();
        Initializer result =
            new Initializer(
                readText(in),
                readUuid(in),
                readUuid(in),
                readText(in),
                readUuid(in),
                readPositive(in),
                readUuid(in),
                readUuid(in),
                readBytes(in),
                readBytes(in),
                readText(in),
                readBytes(in),
                readText(in),
                readPositive(in),
                readBytes(in),
                readText(in),
                readNullableBytes(in),
                readNullableText(in),
                readBytes(in),
                readText(in),
                readBytes(in),
                readText(in));
        if (in.available() != 0 || !Arrays.equals(bytes, result.exactBytes())) throw malformed();
        return result;
      } catch (IOException | RuntimeException invalid) {
        throw malformed();
      }
    }

    String issuerId() { return issuerId; }
    UUID tenantId() { return tenantId; }
    UUID playableStateNamespaceId() { return playableStateNamespaceId; }
    String tenantGameplayTag() { return tenantGameplayTag; }
    UUID operationId() { return operationId; }
    BigInteger admissionEpoch() { return admissionEpoch; }
    UUID transitionId() { return transitionId; }
    UUID accountId() { return accountId; }
    byte[] bindingRefBytes() { return bindingRefBytes.clone(); }
    byte[] reservationBytes() { return reservationBytes.clone(); }
    String reservationDigest() { return reservationDigest; }
    byte[] anchorBytes() { return anchorBytes.clone(); }
    String anchorDigest() { return anchorDigest; }
    BigInteger accountIssuerAuthGeneration() { return accountIssuerAuthGeneration; }
    byte[] accountCheckpointBytes() { return accountCheckpointBytes.clone(); }
    String accountCheckpointDigest() { return accountCheckpointDigest; }
    byte[] accountSourceEventBytes() {
      return accountSourceEventBytes == null ? null : accountSourceEventBytes.clone();
    }
    String accountSourceEventDigest() { return accountSourceEventDigest; }
    byte[] incarnationBytes() { return incarnationBytes.clone(); }
    String incarnationDigest() { return incarnationDigest; }
    byte[] registeredTargetBytes() { return registeredTargetBytes.clone(); }
    String registeredTargetDigest() { return registeredTargetDigest; }
    byte[] exactBytes() { return exactBytes.clone(); }
    String exactDigest() { return exactDigest; }

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
              incarnationBytes,
              CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(current.incarnation()));
    }
  }

  private final String issuerId;
  private final UUID tenantId;
  private final UUID playableStateNamespaceId;
  private final String tenantGameplayTag;
  private final State state;
  private final BigInteger admissionEpoch;
  private final UUID ownerOperationId;
  private final Initializer initializer;
  private final byte[] exactBytes;
  private final String exactDigest;

  private CanonicalGameplayIssuerAdmissionBarrierValue(
      String issuerId,
      UUID tenantId,
      UUID playableStateNamespaceId,
      String tenantGameplayTag,
      State state,
      BigInteger admissionEpoch,
      UUID ownerOperationId,
      Initializer initializer) {
    this.issuerId = requireIssuer(issuerId);
    this.tenantId = requireNonNil(tenantId, "tenantId");
    this.playableStateNamespaceId =
        requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    this.tenantGameplayTag = requireTag(tenantGameplayTag);
    this.state = Objects.requireNonNull(state, "state");
    this.admissionEpoch = requirePositive(admissionEpoch, "admissionEpoch");
    this.ownerOperationId = requireNonNil(ownerOperationId, "ownerOperationId");
    this.initializer = initializer;
    if (initializer != null
        && (!this.issuerId.equals(initializer.issuerId())
            || !this.tenantId.equals(initializer.tenantId())
            || !this.playableStateNamespaceId.equals(initializer.playableStateNamespaceId())
            || !this.tenantGameplayTag.equals(initializer.tenantGameplayTag()))) {
      throw new IllegalArgumentException("Barrier initializer belongs to a different target");
    }
    if (state == State.OPEN
        && (initializer == null
            || !ownerOperationId.equals(initializer.operationId())
            || !admissionEpoch.equals(initializer.admissionEpoch()))) {
      throw new IllegalArgumentException("OPEN requires its exact original initializer tuple");
    }
    if (state == State.CLOSED
        && initializer != null
        && (admissionEpoch.compareTo(initializer.admissionEpoch()) <= 0
            || ownerOperationId.equals(initializer.operationId()))) {
      throw new IllegalArgumentException("CLOSED must advance past a captured initializer epoch");
    }
    this.exactBytes = encodeValue();
    this.exactDigest = sha256(this.exactBytes);
    if (this.exactBytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Issuer admission barrier exceeds its encoded bound");
    }
  }

  static CanonicalGameplayIssuerAdmissionBarrierValue open(Initializer initializer) {
    Objects.requireNonNull(initializer, "initializer");
    return new CanonicalGameplayIssuerAdmissionBarrierValue(
        initializer.issuerId(),
        initializer.tenantId(),
        initializer.playableStateNamespaceId(),
        initializer.tenantGameplayTag(),
        State.OPEN,
        initializer.admissionEpoch(),
        initializer.operationId(),
        initializer);
  }

  static CanonicalGameplayIssuerAdmissionBarrierValue closed(
      String issuerId,
      UUID tenantId,
      UUID playableStateNamespaceId,
      String tenantGameplayTag,
      BigInteger cutoffEpoch,
      UUID cutoffOperationId,
      Initializer capturedInitializer) {
    return new CanonicalGameplayIssuerAdmissionBarrierValue(
        issuerId,
        tenantId,
        playableStateNamespaceId,
        tenantGameplayTag,
        State.CLOSED,
        cutoffEpoch,
        cutoffOperationId,
        capturedInitializer);
  }

  static CanonicalGameplayIssuerAdmissionBarrierValue decode(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw malformed();
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (!VALUE_ENCODING.equals(readText(in))) throw malformed();
      String issuerId = readText(in);
      UUID tenantId = readUuid(in);
      UUID namespaceId = readUuid(in);
      String tag = readText(in);
      State state = State.valueOf(readText(in));
      BigInteger epoch = readPositive(in);
      UUID ownerOperationId = readUuid(in);
      byte[] initializerBytes = readNullableBytes(in);
      String initializerDigest = readNullableText(in);
      Initializer initializer = null;
      if (initializerBytes != null) {
        initializer = Initializer.decode(initializerBytes);
        if (!initializer.exactDigest().equals(initializerDigest)) throw malformed();
      } else if (initializerDigest != null) {
        throw malformed();
      }
      CanonicalGameplayIssuerAdmissionBarrierValue result =
          new CanonicalGameplayIssuerAdmissionBarrierValue(
              issuerId,
              tenantId,
              namespaceId,
              tag,
              state,
              epoch,
              ownerOperationId,
              initializer);
      if (in.available() != 0 || !Arrays.equals(bytes, result.exactBytes())) throw malformed();
      return result;
    } catch (IOException | RuntimeException invalid) {
      throw malformed();
    }
  }

  private byte[] encodeValue() {
    return encode(
        out -> {
          writeText(out, VALUE_ENCODING);
          writeText(out, issuerId);
          writeUuid(out, tenantId);
          writeUuid(out, playableStateNamespaceId);
          writeText(out, tenantGameplayTag);
          writeText(out, state.name());
          writeBigInteger(out, admissionEpoch);
          writeUuid(out, ownerOperationId);
          writeNullableBytes(out, initializer == null ? null : initializer.exactBytes());
          writeNullableText(out, initializer == null ? null : initializer.exactDigest());
        });
  }

  String issuerId() { return issuerId; }
  UUID tenantId() { return tenantId; }
  UUID playableStateNamespaceId() { return playableStateNamespaceId; }
  String tenantGameplayTag() { return tenantGameplayTag; }
  State state() { return state; }
  BigInteger admissionEpoch() { return admissionEpoch; }
  UUID ownerOperationId() { return ownerOperationId; }
  Initializer initializer() { return initializer; }
  byte[] exactBytes() { return exactBytes.clone(); }
  String exactDigest() { return exactDigest; }

  static String sha256(byte[] bytes) {
    try {
      return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) {
      throw new IllegalStateException("Could not digest exact issuer admission bytes", failure);
    }
  }

  private static byte[] encode(Writer writer) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        writer.write(output);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("Could not encode exact issuer admission bytes", impossible);
    }
  }

  private static void writeText(DataOutputStream out, String value) throws IOException {
    byte[] bytes = Objects.requireNonNull(value, "text").getBytes(StandardCharsets.UTF_8);
    writeBytes(out, bytes);
  }

  private static void writeBigInteger(DataOutputStream out, BigInteger value) throws IOException {
    writeText(out, requirePositive(value, "counter").toString());
  }

  private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
    writeText(out, requireNonNil(value, "uuid").toString());
  }

  private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
    if (value.length > MAX_BYTES) throw new IllegalArgumentException("Byte field exceeds bound");
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

  private static String readText(DataInputStream in) throws IOException {
    return decodeUtf8(readBytes(in));
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
    String text = readText(in);
    UUID value;
    try {
      value = UUID.fromString(text);
    } catch (IllegalArgumentException invalid) {
      throw malformed();
    }
    if (!value.toString().equals(text) || value.equals(new UUID(0L, 0L))) throw malformed();
    return value;
  }

  private static BigInteger readPositive(DataInputStream in) throws IOException {
    String text = readText(in);
    if (!text.matches("[1-9][0-9]{0,255}")) throw malformed();
    return new BigInteger(text);
  }

  private static String decodeUtf8(byte[] bytes) throws IOException {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException invalid) {
      throw malformed();
    }
  }

  private static IllegalArgumentException malformed() {
    return new IllegalArgumentException("Issuer admission barrier payload is malformed");
  }

  private static byte[] nonEmpty(byte[] value, String name) {
    Objects.requireNonNull(value, name);
    if (value.length == 0 || value.length > MAX_BYTES) {
      throw new IllegalArgumentException(name + " is outside the exact payload bound");
    }
    return value.clone();
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

  private static String requireIssuer(String value) {
    if (!GameSessionAccountDelegationProfile.ISSUER.equals(value)) {
      throw new IllegalArgumentException("Issuer admission barrier requires the Account issuer");
    }
    return value;
  }

  private static String requireTag(String value) {
    Objects.requireNonNull(value, "tenantGameplayTag");
    if (!value.matches("g[1-9][0-9]{0,9}-[0-9a-f]{32}")) {
      throw new IllegalArgumentException("Issuer admission barrier requires an exact gameplay tag");
    }
    return value;
  }

  private static String requireDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be canonical SHA-256");
    }
    return value;
  }

  private static void requireDigestMatches(byte[] bytes, String digest, String name) {
    if (!sha256(bytes).equals(digest)) throw new IllegalArgumentException(name + " digest differs");
  }

  @FunctionalInterface
  private interface Writer {
    void write(DataOutputStream output) throws IOException;
  }
}
