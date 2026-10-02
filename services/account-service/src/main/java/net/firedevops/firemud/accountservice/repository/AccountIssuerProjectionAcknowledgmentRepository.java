package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stores immutable Account-owned acknowledgments for installed issuer projections. */
@Repository
public class AccountIssuerProjectionAcknowledgmentRepository {
  private static final String TABLE = "account_issuer_projection_installation_acknowledgments";
  private static final String PROJECTION_KEY_PREFIX = "session:game:auth:issuer-generation:v1:";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final HexFormat HEX = HexFormat.of();
  private static final String SELECT_COLUMNS =
      "acknowledgment_id, capture_operation_id, capture_request_id, issuer_id, "
          + "caller_workload_identity, projection_key, capture_request_digest_version, "
          + "capture_request_digest, request_digest_version, request_digest, "
          + "installed_projection_json, installed_projection_sha256";

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The injected transaction-aware DSLContext is a shared internal collaborator.")
  public AccountIssuerProjectionAcknowledgmentRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Reads the single acknowledgment bound to one immutable capture operation. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<Acknowledgment> findByCaptureOperationId(UUID captureOperationId) {
    requireOwnerTransaction();
    requireUuid(captureOperationId, "capture operation ID");
    Record row =
        dsl.fetchOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE + " WHERE capture_operation_id = ?",
            captureOperationId);
    return Optional.ofNullable(row == null ? null : toAcknowledgment(row));
  }

  /** Inserts the one immutable acknowledgment for an exact captured operation. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Acknowledgment insert(Acknowledgment acknowledgment) {
    requireOwnerTransaction();
    validateAcknowledgment(acknowledgment);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (acknowledgment_id, capture_operation_id, capture_request_id, issuer_id, "
                + "caller_workload_identity, projection_key, capture_request_digest_version, "
                + "capture_request_digest, request_digest_version, request_digest, "
                + "installed_projection_json, installed_projection_sha256) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            acknowledgment.acknowledgmentId(),
            acknowledgment.captureOperationId(),
            acknowledgment.captureRequestId(),
            acknowledgment.issuerId(),
            acknowledgment.callerWorkloadIdentity(),
            acknowledgment.projectionKey(),
            acknowledgment.captureRequestDigestVersion(),
            decodeDigest(acknowledgment.captureRequestDigest(), "capture request digest"),
            acknowledgment.requestDigestVersion(),
            decodeDigest(acknowledgment.requestDigest(), "acknowledgment request digest"),
            acknowledgment.installedProjectionUtf8(),
            acknowledgment.installedProjectionSha256());
    if (inserted != 1) {
      throw new IllegalStateException(
          "Issuer projection installation acknowledgment was not inserted");
    }
    return acknowledgment;
  }

  private Acknowledgment toAcknowledgment(Record row) {
    return new Acknowledgment(
        requiredUuid(row.get("acknowledgment_id", UUID.class), "acknowledgment ID"),
        requiredUuid(row.get("capture_operation_id", UUID.class), "capture operation ID"),
        requiredUuid(row.get("capture_request_id", UUID.class), "capture request ID"),
        requiredText(row.get("issuer_id", String.class), "issuer ID", 512),
        requiredText(
            row.get("caller_workload_identity", String.class), "caller workload identity", 512),
        requiredText(row.get("projection_key", String.class), "projection key", 2048),
        requiredPositive(
            row.get("capture_request_digest_version", Integer.class),
            "capture request digest version"),
        encodeDigest(row.get("capture_request_digest", byte[].class), "capture request digest"),
        requiredPositive(
            row.get("request_digest_version", Integer.class), "acknowledgment digest version"),
        encodeDigest(row.get("request_digest", byte[].class), "acknowledgment request digest"),
        requiredBytes(row.get("installed_projection_json", byte[].class), "installed projection"),
        requiredBytes(
            row.get("installed_projection_sha256", byte[].class), "installed projection SHA-256"));
  }

  private static void validateAcknowledgment(Acknowledgment acknowledgment) {
    Objects.requireNonNull(acknowledgment, "issuer projection acknowledgment is required");
    // The value object's constructor performs the complete binding and byte-hash validation.
    new Acknowledgment(
        acknowledgment.acknowledgmentId(),
        acknowledgment.captureOperationId(),
        acknowledgment.captureRequestId(),
        acknowledgment.issuerId(),
        acknowledgment.callerWorkloadIdentity(),
        acknowledgment.projectionKey(),
        acknowledgment.captureRequestDigestVersion(),
        acknowledgment.captureRequestDigest(),
        acknowledgment.requestDigestVersion(),
        acknowledgment.requestDigest(),
        acknowledgment.installedProjectionUtf8(),
        acknowledgment.installedProjectionSha256());
  }

  private void requireOwnerTransaction() {
    if (dsl == null) {
      throw new IllegalStateException(
          "Issuer projection acknowledgment storage requires a DSLContext");
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Issuer projection acknowledgment storage requires an active Account transaction");
    }
  }

  private static UUID requiredUuid(UUID value, String field) {
    requireUuid(value, field);
    return value;
  }

  private static void requireUuid(UUID value, String field) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
    }
  }

  private static int requiredPositive(Integer value, String field) {
    if (value == null || value <= 0) {
      throw new IllegalStateException("Stored issuer acknowledgment " + field + " is invalid");
    }
    return value;
  }

  private static String requiredText(String value, String field, int maximumLength) {
    requireText(value, field, maximumLength);
    return value;
  }

  private static void requireText(String value, String field, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          field + " must contain 1 to " + maximumLength + " characters");
    }
  }

  private static byte[] requiredBytes(byte[] value, String field) {
    if (value == null || value.length == 0) {
      throw new IllegalStateException("Stored issuer acknowledgment " + field + " is missing");
    }
    return value.clone();
  }

  private static byte[] decodeDigest(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256 hexadecimal");
    }
    return HEX.parseHex(value);
  }

  private static String encodeDigest(byte[] value, String field) {
    if (value == null || value.length != 32) {
      throw new IllegalStateException("Stored issuer acknowledgment " + field + " is invalid");
    }
    return HEX.formatHex(value);
  }

  /** Complete immutable evidence retained for one successful issuer projection installation. */
  public record Acknowledgment(
      UUID acknowledgmentId,
      UUID captureOperationId,
      UUID captureRequestId,
      String issuerId,
      String callerWorkloadIdentity,
      String projectionKey,
      int captureRequestDigestVersion,
      String captureRequestDigest,
      int requestDigestVersion,
      String requestDigest,
      byte[] installedProjectionUtf8,
      byte[] installedProjectionSha256) {
    public static final int REQUEST_DIGEST_VERSION = 1;
    public static final int MAX_INSTALLED_PROJECTION_UTF8_BYTES = 65_536;
    private static final String DIGEST_SCHEMA = "issuer-projection-installation-ack/v1";
    private static final String OPERATION = "ISSUER_PROJECTION_INSTALLATION_ACK";

    public Acknowledgment {
      Objects.requireNonNull(
          installedProjectionUtf8, "installed projection JSON bytes are required");
      Objects.requireNonNull(installedProjectionSha256, "installed projection SHA-256 is required");
      installedProjectionUtf8 = installedProjectionUtf8.clone();
      installedProjectionSha256 = installedProjectionSha256.clone();
      validate(
          acknowledgmentId,
          captureOperationId,
          captureRequestId,
          issuerId,
          callerWorkloadIdentity,
          projectionKey,
          captureRequestDigestVersion,
          captureRequestDigest,
          requestDigestVersion,
          requestDigest,
          installedProjectionUtf8,
          installedProjectionSha256);
    }

    @Override
    public byte[] installedProjectionUtf8() {
      return installedProjectionUtf8.clone();
    }

    @Override
    public byte[] installedProjectionSha256() {
      return installedProjectionSha256.clone();
    }

    /** Computes the exact ten-field byte-framed acknowledgment request digest. */
    public static String requestDigestFor(
        String issuerId,
        String callerWorkloadIdentity,
        String projectionKey,
        UUID captureOperationId,
        UUID captureRequestId,
        int captureRequestDigestVersion,
        String captureRequestDigest,
        String installedProjectionJson) {
      requireRequestBindings(
          issuerId,
          callerWorkloadIdentity,
          projectionKey,
          captureOperationId,
          captureRequestId,
          captureRequestDigestVersion,
          captureRequestDigest);
      byte[] projectionBytes = encodeUtf8(installedProjectionJson);
      if (projectionBytes.length == 0
          || projectionBytes.length > MAX_INSTALLED_PROJECTION_UTF8_BYTES) {
        throw new IllegalArgumentException(
            "Installed projection JSON must contain 1 to "
                + MAX_INSTALLED_PROJECTION_UTF8_BYTES
                + " UTF-8 bytes");
      }
      return digest(
          DIGEST_SCHEMA,
          OPERATION,
          issuerId,
          callerWorkloadIdentity,
          projectionKey,
          captureOperationId.toString(),
          captureRequestId.toString(),
          Integer.toString(captureRequestDigestVersion),
          captureRequestDigest,
          installedProjectionJson);
    }

    private static void validate(
        UUID acknowledgmentId,
        UUID captureOperationId,
        UUID captureRequestId,
        String issuerId,
        String callerWorkloadIdentity,
        String projectionKey,
        int captureRequestDigestVersion,
        String captureRequestDigest,
        int requestDigestVersion,
        String requestDigest,
        byte[] installedProjectionUtf8,
        byte[] installedProjectionSha256) {
      requireUuid(acknowledgmentId, "acknowledgment ID");
      requireRequestBindings(
          issuerId,
          callerWorkloadIdentity,
          projectionKey,
          captureOperationId,
          captureRequestId,
          captureRequestDigestVersion,
          captureRequestDigest);
      if (requestDigestVersion != REQUEST_DIGEST_VERSION
          || requestDigest == null
          || !requestDigest.matches("[0-9a-f]{64}")
          || installedProjectionUtf8.length == 0
          || installedProjectionUtf8.length > MAX_INSTALLED_PROJECTION_UTF8_BYTES
          || installedProjectionSha256.length != 32) {
        throw new IllegalArgumentException("Issuer projection acknowledgment is malformed");
      }
      String projectionJson = decodeUtf8(installedProjectionUtf8);
      if (!MessageDigest.isEqual(sha256(installedProjectionUtf8), installedProjectionSha256)
          || !requestDigest.equals(
              requestDigestFor(
                  issuerId,
                  callerWorkloadIdentity,
                  projectionKey,
                  captureOperationId,
                  captureRequestId,
                  captureRequestDigestVersion,
                  captureRequestDigest,
                  projectionJson))) {
        throw new IllegalArgumentException(
            "Issuer projection acknowledgment digest does not bind its original result");
      }
    }

    private static void requireRequestBindings(
        String issuerId,
        String callerWorkloadIdentity,
        String projectionKey,
        UUID captureOperationId,
        UUID captureRequestId,
        int captureRequestDigestVersion,
        String captureRequestDigest) {
      requireText(issuerId, "issuer ID", 512);
      requireText(callerWorkloadIdentity, "caller workload identity", 512);
      requireText(projectionKey, "projection key", 2048);
      requireUuid(captureOperationId, "capture operation ID");
      requireUuid(captureRequestId, "capture request ID");
      if (!projectionKey.equals(PROJECTION_KEY_PREFIX + issuerId)
          || captureRequestDigestVersion != 1
          || captureRequestDigest == null
          || !captureRequestDigest.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException(
            "Issuer projection acknowledgment capture binding is malformed");
      }
    }

    private static String digest(String... fields) {
      ByteArrayOutputStream framed = new ByteArrayOutputStream();
      for (String field : fields) {
        byte[] bytes = encodeUtf8(field);
        framed.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        framed.write(':');
        framed.writeBytes(bytes);
      }
      return HexFormat.of().formatHex(sha256(framed.toByteArray()));
    }

    private static byte[] encodeUtf8(String value) {
      if (value == null) {
        throw new IllegalArgumentException("Installed projection JSON is required");
      }
      try {
        ByteBuffer encoded =
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
      } catch (CharacterCodingException malformed) {
        throw new IllegalArgumentException(
            "Installed projection JSON is not valid Unicode", malformed);
      }
    }

    private static String decodeUtf8(byte[] value) {
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString();
      } catch (CharacterCodingException malformed) {
        throw new IllegalArgumentException(
            "Installed projection JSON is not valid UTF-8", malformed);
      }
    }

    private static byte[] sha256(byte[] value) {
      try {
        return MessageDigest.getInstance("SHA-256").digest(value);
      } catch (NoSuchAlgorithmException unavailable) {
        throw new IllegalStateException("SHA-256 is unavailable", unavailable);
      }
    }
  }
}
