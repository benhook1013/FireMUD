package net.firedevops.firemud.accountservice.repository;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequest;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequestDigest;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperation.CompletedResponse;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperation.Lifecycle;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperation.OriginalCapture;
import net.firedevops.firemud.accountservice.security.AccountControlUiResponseEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered Account-local first-writer correlation and exact encrypted-result storage.
 *
 * <p>This component does not authenticate credentials, establish authority/currentness, write or
 * inspect the active token registry, or prove custody. Its opaque capture bytes identify the
 * original supplied evidence only; callers must establish all applicable gates separately.
 */
public final class AccountControlUiIssuanceOperationRepository {
  private static final String OPERATIONS = "account_control_ui_issuance_operations";
  private static final String ENVELOPES = "account_control_ui_issuance_response_envelopes";

  private final DSLContext dsl;

  public AccountControlUiIssuanceOperationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Claims the stable request identity with its caller-preallocated internal operation ID, or
   * recovers the original capture. An existing exact retry returns the stored operation ID even
   * when the caller's proposed ID differs; a supplied capture must byte-match the immutable first
   * capture.
   */
  public Claim claim(
      UUID operationId,
      AccountControlUiIssuanceRequest request,
      Optional<OriginalCapture> proposedCapture) {
    requireWritableOwnerTransaction();
    Objects.requireNonNull(operationId, "operationId");
    if (operationId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("operationId must be non-nil");
    }
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(proposedCapture, "proposedCapture");
    byte[] requestDigest = AccountControlUiIssuanceRequestDigest.digest(request);
    AccountAssociation association = lockAccount(request.accountUuid());
    Optional<AccountControlUiIssuanceOperation> existing =
        readOperation(UUID.fromString(request.requestId()), true);
    if (existing.isPresent()) {
      AccountControlUiIssuanceOperation stored = existing.orElseThrow();
      requireRequestAndAssociation(stored, request, requestDigest, association);
      proposedCapture.ifPresent(
          capture -> {
            if (!stored.originalCapture().equals(capture)) {
              throw new OperationConflictException(
                  "Original control-UI authority/fence capture cannot be replaced");
            }
          });
      return new Claim(false, stored);
    }

    OriginalCapture capture =
        proposedCapture.orElseThrow(
            () -> new IllegalArgumentException("First control-UI claim requires original capture"));
    requireCaptureAssociation(capture, association);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + OPERATIONS
                + " (operation_id, request_id, account_uuid, account_id, account_provenance, "
                + "profile, audience, request_digest_version, request_digest, "
                + "authority_capture, authority_capture_digest, issuance_fence_capture, "
                + "issuance_fence_digest, status) "
                + "VALUES (?, ?, ?, ?, ?, 'control-ui', 'control-ui', ?, ?, ?, ?, ?, ?, 'PENDING') "
                + "ON CONFLICT (request_id) DO NOTHING",
            operationId,
            UUID.fromString(request.requestId()),
            UUID.fromString(request.accountUuid()),
            association.accountId(),
            association.provenance().name(),
            AccountControlUiIssuanceRequestDigest.VERSION,
            requestDigest,
            capture.authorityCapture(),
            capture.authorityCaptureDigest(),
            capture.issuanceFenceCapture(),
            capture.issuanceFenceDigest());
    if (inserted < 0 || inserted > 1) {
      throw new IllegalStateException("Control-UI issuance claim result was ambiguous");
    }

    AccountControlUiIssuanceOperation stored =
        readOperation(UUID.fromString(request.requestId()), true)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Control-UI issuance claim has no durable operation readback"));
    requireRequestAndAssociation(stored, request, requestDigest, association);
    if (inserted == 1
        && (!stored.operationId().equals(operationId)
            || !stored.originalCapture().equals(capture))) {
      throw new IllegalStateException("First control-UI claim readback differs from its proposal");
    }
    return new Claim(inserted == 1, stored);
  }

  /** Reads the exact original operation and encrypted response, if committed. */
  public Optional<AccountControlUiIssuanceOperation> findByRequest(
      AccountControlUiIssuanceRequest request) {
    requireWritableOwnerTransaction();
    Objects.requireNonNull(request, "request");
    byte[] requestDigest = AccountControlUiIssuanceRequestDigest.digest(request);
    AccountAssociation association = lockAccount(request.accountUuid());
    Optional<AccountControlUiIssuanceOperation> stored =
        readOperation(UUID.fromString(request.requestId()), false);
    stored.ifPresent(
        operation -> requireRequestAndAssociation(operation, request, requestDigest, association));
    return stored;
  }

  /**
   * Commits one exact original encrypted result. This does not mint, extend or validate a token;
   * authenticated/currentness/registry checks must already have succeeded outside this store.
   */
  public AccountControlUiIssuanceOperation complete(
      Claim claim,
      AccountControlUiResponseEnvelopeBinding binding,
      AccountEncryptedEnvelope envelope) {
    requireWritableOwnerTransaction();
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(envelope, "envelope");
    AccountControlUiIssuanceOperation claimed = claim.operation();
    AccountControlUiIssuanceRequest request = claimed.request();
    AccountAssociation association = lockAccount(request.accountUuid());
    AccountControlUiIssuanceOperation stored =
        readOperation(UUID.fromString(request.requestId()), true)
            .orElseThrow(
                () -> new OperationConflictException("Original control-UI claim is missing"));
    requireRequestAndAssociation(
        stored, request, AccountControlUiIssuanceRequestDigest.digest(request), association);
    if (!stored.operationId().equals(claimed.operationId())
        || !stored.originalCapture().equals(claimed.originalCapture())) {
      throw new OperationConflictException("Control-UI claim no longer matches its original row");
    }
    requireBinding(stored, binding, envelope);
    CompletedResponse proposed = new CompletedResponse(binding, envelope);
    if (stored.lifecycle() == Lifecycle.COMMITTED) {
      if (!stored.completedResponse().equals(proposed)) {
        throw new OperationConflictException(
            "Committed control-UI result cannot be replaced or reminted");
      }
      return stored;
    }

    int updated =
        dsl.execute(
            "UPDATE "
                + OPERATIONS
                + " SET status = 'COMMITTED', token_hash = ?, response_digest = ?, "
                + "issued_at = CAST(? AS timestamptz), expires_at = CAST(? AS timestamptz) "
                + "WHERE operation_id = ? AND request_id = ? "
                + "AND status = 'PENDING'",
            binding.tokenHash(),
            binding.responseDigest(),
            toOffsetDateTime(binding.issuedAt()),
            toOffsetDateTime(binding.expiresAt()),
            stored.operationId(),
            UUID.fromString(request.requestId()));
    if (updated != 1) {
      throw new OperationConflictException("Control-UI operation did not transition exactly once");
    }
    int envelopeInserted =
        dsl.execute(
            "INSERT INTO "
                + ENVELOPES
                + " (operation_id, request_id, account_uuid, account_id, account_provenance, "
                + "profile, audience, request_digest_version, request_digest, token_hash, "
                + "response_digest, authority_capture_digest, issuance_fence_digest, issued_at, "
                + "expires_at, format_version, key_id, purpose, nonce, ciphertext) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS timestamptz), "
                + "CAST(? AS timestamptz), ?, ?, ?, ?, ?)",
            stored.operationId(),
            UUID.fromString(request.requestId()),
            UUID.fromString(request.accountUuid()),
            stored.accountId(),
            stored.accountProvenance().name(),
            binding.profile(),
            binding.audience(),
            AccountControlUiIssuanceRequestDigest.VERSION,
            binding.requestDigest(),
            binding.tokenHash(),
            binding.responseDigest(),
            binding.authorityCaptureDigest(),
            binding.issuanceFenceDigest(),
            toOffsetDateTime(binding.issuedAt()),
            toOffsetDateTime(binding.expiresAt()),
            envelope.formatVersion(),
            envelope.keyId(),
            envelope.purpose().name(),
            envelope.nonce(),
            envelope.ciphertext());
    if (envelopeInserted != 1) {
      throw new IllegalStateException("Control-UI encrypted response insert was ambiguous");
    }

    AccountControlUiIssuanceOperation readback =
        readOperation(UUID.fromString(request.requestId()), true)
            .orElseThrow(
                () -> new IllegalStateException("Committed control-UI operation disappeared"));
    if (!sameCommittedReadback(readback, stored, proposed)) {
      throw new IllegalStateException("Committed control-UI operation/envelope readback differs");
    }
    return readback;
  }

  private static boolean sameCommittedReadback(
      AccountControlUiIssuanceOperation readback,
      AccountControlUiIssuanceOperation original,
      CompletedResponse result) {
    return readback.operationId().equals(original.operationId())
        && readback.request().equals(original.request())
        && readback.accountId() == original.accountId()
        && readback.accountProvenance() == original.accountProvenance()
        && MessageDigest.isEqual(readback.requestDigest(), original.requestDigest())
        && readback.lifecycle() == Lifecycle.COMMITTED
        && readback.originalCapture().equals(original.originalCapture())
        && readback.completedResponse().equals(result)
        && readback.createdAt().equals(original.createdAt())
        && !readback.updatedAt().isBefore(original.updatedAt());
  }

  private Optional<AccountControlUiIssuanceOperation> readOperation(
      UUID requestId, boolean forUpdate) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + OPERATIONS
                + " WHERE request_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            requestId);
    if (row == null) return Optional.empty();

    UUID operationId = row.get("operation_id", UUID.class);
    UUID accountUuid = row.get("account_uuid", UUID.class);
    String requestIdText = row.get("request_id", UUID.class).toString();
    AccountControlUiIssuanceRequest request =
        new AccountControlUiIssuanceRequest(requestIdText, accountUuid.toString());
    if (!"control-ui".equals(row.get("profile", String.class))
        || !"control-ui".equals(row.get("audience", String.class))
        || row.get("request_digest_version", Short.class)
            != AccountControlUiIssuanceRequestDigest.VERSION) {
      throw new IllegalStateException("Stored control-UI request profile/version is invalid");
    }
    byte[] requestDigest = row.get("request_digest", byte[].class);
    if (!MessageDigest.isEqual(
        requestDigest, AccountControlUiIssuanceRequestDigest.digest(request))) {
      throw new IllegalStateException("Stored control-UI semantic request digest differs");
    }
    long accountId = row.get("account_id", Long.class);
    AccountIdentityProvenance provenance =
        AccountIdentityProvenance.fromStorageValue(row.get("account_provenance", String.class));
    OriginalCapture capture =
        new OriginalCapture(
            accountId,
            provenance,
            row.get("authority_capture", byte[].class),
            row.get("issuance_fence_capture", byte[].class));
    if (!MessageDigest.isEqual(
            capture.authorityCaptureDigest(), row.get("authority_capture_digest", byte[].class))
        || !MessageDigest.isEqual(
            capture.issuanceFenceDigest(), row.get("issuance_fence_digest", byte[].class))) {
      throw new IllegalStateException("Stored original control-UI capture digest differs");
    }

    Lifecycle lifecycle = parseLifecycle(row.get("status", String.class));
    Optional<AccountControlUiIssuanceOperation.CompletedResponse> completed =
        readEnvelope(operationId, row, capture, request, requestDigest);
    if ((lifecycle == Lifecycle.PENDING) != completed.isEmpty()) {
      throw new IllegalStateException("Stored control-UI operation/envelope lifecycle differs");
    }
    return Optional.of(
        new AccountControlUiIssuanceOperation(
            operationId,
            request,
            accountId,
            provenance,
            requestDigest,
            lifecycle,
            capture,
            completed.orElse(null),
            toInstant(row.get("created_at", OffsetDateTime.class)),
            toInstant(row.get("updated_at", OffsetDateTime.class))));
  }

  private Optional<CompletedResponse> readEnvelope(
      UUID operationId,
      Record operation,
      OriginalCapture capture,
      AccountControlUiIssuanceRequest request,
      byte[] requestDigest) {
    Record row =
        dsl.fetchOne("SELECT * FROM " + ENVELOPES + " WHERE operation_id = ?", operationId);
    if (row == null) return Optional.empty();
    try {
      if (!operationId.equals(row.get("operation_id", UUID.class))
          || !operation.get("request_id", UUID.class).equals(row.get("request_id", UUID.class))
          || !operation.get("account_uuid", UUID.class).equals(row.get("account_uuid", UUID.class))
          || !operation.get("account_id", Long.class).equals(row.get("account_id", Long.class))
          || !operation
              .get("account_provenance", String.class)
              .equals(row.get("account_provenance", String.class))
          || !"control-ui".equals(row.get("profile", String.class))
          || !"control-ui".equals(row.get("audience", String.class))
          || row.get("request_digest_version", Short.class)
              != AccountControlUiIssuanceRequestDigest.VERSION
          || !MessageDigest.isEqual(requestDigest, row.get("request_digest", byte[].class))
          || !MessageDigest.isEqual(
              capture.authorityCaptureDigest(), row.get("authority_capture_digest", byte[].class))
          || !MessageDigest.isEqual(
              capture.issuanceFenceDigest(), row.get("issuance_fence_digest", byte[].class))) {
        throw new IllegalStateException("Stored control-UI envelope index binding differs");
      }
      AccountControlUiResponseEnvelopeBinding binding =
          new AccountControlUiResponseEnvelopeBinding(
              row.get("account_uuid", UUID.class).toString(),
              operationId.toString(),
              row.get("request_id", UUID.class).toString(),
              row.get("request_digest", byte[].class),
              row.get("token_hash", String.class),
              row.get("response_digest", byte[].class),
              row.get("authority_capture_digest", byte[].class),
              row.get("issuance_fence_digest", byte[].class),
              toInstant(row.get("issued_at", OffsetDateTime.class)),
              toInstant(row.get("expires_at", OffsetDateTime.class)));
      AccountControlUiResponseEnvelopeBinding operationBinding =
          new AccountControlUiResponseEnvelopeBinding(
              operation.get("account_uuid", UUID.class).toString(),
              operationId.toString(),
              operation.get("request_id", UUID.class).toString(),
              operation.get("request_digest", byte[].class),
              operation.get("token_hash", String.class),
              operation.get("response_digest", byte[].class),
              capture.authorityCaptureDigest(),
              capture.issuanceFenceDigest(),
              toInstant(operation.get("issued_at", OffsetDateTime.class)),
              toInstant(operation.get("expires_at", OffsetDateTime.class)));
      if (!binding.equals(operationBinding)
          || !binding.requestId().equals(request.requestId())
          || !binding.accountId().equals(request.accountUuid())) {
        throw new IllegalStateException("Stored control-UI original response binding differs");
      }
      AccountEncryptedEnvelope envelope =
          new AccountEncryptedEnvelope(
              row.get("format_version", Short.class),
              row.get("key_id", String.class),
              AccountEnvelopePurpose.valueOf(row.get("purpose", String.class)),
              row.get("nonce", byte[].class),
              row.get("ciphertext", byte[].class));
      return Optional.of(new CompletedResponse(binding, envelope));
    } catch (RuntimeException failure) {
      if (failure instanceof IllegalStateException) throw failure;
      throw new IllegalStateException("Stored control-UI encrypted response is malformed");
    }
  }

  private static void requireBinding(
      AccountControlUiIssuanceOperation operation,
      AccountControlUiResponseEnvelopeBinding binding,
      AccountEncryptedEnvelope envelope) {
    if (!binding.accountId().equals(operation.request().accountUuid())
        || !binding.operationId().equals(operation.operationId().toString())
        || !binding.requestId().equals(operation.request().requestId())
        || !MessageDigest.isEqual(binding.requestDigest(), operation.requestDigest())
        || !MessageDigest.isEqual(
            binding.authorityCaptureDigest(), operation.originalCapture().authorityCaptureDigest())
        || !MessageDigest.isEqual(
            binding.issuanceFenceDigest(), operation.originalCapture().issuanceFenceDigest())
        || !AccountControlUiIssuanceRequest.PROFILE.equals(binding.profile())
        || !AccountControlUiIssuanceRequest.AUDIENCE.equals(binding.audience())
        || envelope.purpose() != AccountEnvelopePurpose.CONTROL_UI_RESPONSE) {
      throw new OperationConflictException(
          "Encrypted control-UI response does not match the exact original operation binding");
    }
  }

  private AccountAssociation lockAccount(String accountUuid) {
    Record row =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_source_numeric_id, account_uuid_provenance "
                + "FROM accounts WHERE account_uuid = ? FOR UPDATE",
            UUID.fromString(accountUuid));
    if (row == null) {
      throw new OperationConflictException("Canonical Account association does not exist");
    }
    Long id = row.get("id", Long.class);
    Long sourceNumericId = row.get("account_uuid_source_numeric_id", Long.class);
    UUID uuid = row.get("account_uuid", UUID.class);
    AccountIdentityProvenance provenance =
        AccountIdentityProvenance.fromStorageValue(
            row.get("account_uuid_provenance", String.class));
    if (id == null
        || id <= 0L
        || !id.equals(sourceNumericId)
        || !uuid.toString().equals(accountUuid)) {
      throw new OperationConflictException(
          "Canonical Account numeric association is contradictory");
    }
    return new AccountAssociation(id, uuid, provenance);
  }

  private static void requireRequestAndAssociation(
      AccountControlUiIssuanceOperation operation,
      AccountControlUiIssuanceRequest request,
      byte[] digest,
      AccountAssociation association) {
    if (!operation.request().equals(request)
        || !MessageDigest.isEqual(operation.requestDigest(), digest)
        || operation.accountId() != association.accountId()
        || operation.accountProvenance() != association.provenance()
        || !operation.request().accountUuid().equals(association.accountUuid().toString())) {
      throw new OperationConflictException(
          "Control-UI request identity or Account association was reused with changed semantics");
    }
  }

  private static void requireCaptureAssociation(
      OriginalCapture capture, AccountAssociation association) {
    if (capture.accountId() != association.accountId()
        || capture.accountProvenance() != association.provenance()) {
      throw new OperationConflictException(
          "Original control-UI capture has a changed Account association");
    }
  }

  private static Lifecycle parseLifecycle(String value) {
    try {
      return Lifecycle.valueOf(value);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Stored control-UI operation lifecycle is invalid");
    }
  }

  private static Instant toInstant(OffsetDateTime value) {
    if (value == null) throw new IllegalStateException("Stored control-UI result time is missing");
    return value.toInstant();
  }

  private static OffsetDateTime toOffsetDateTime(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Control-UI issuance storage requires a writable caller-owned Account transaction");
    }
  }

  private record AccountAssociation(
      long accountId, UUID accountUuid, AccountIdentityProvenance provenance) {}

  /** First writer retains one operation UUID; exact retries return its original capture/result. */
  public record Claim(boolean created, AccountControlUiIssuanceOperation operation) {
    public Claim {
      Objects.requireNonNull(operation, "operation");
    }
  }

  public static final class OperationConflictException extends IllegalStateException {
    public OperationConflictException(String message) {
      super(message);
    }
  }
}
