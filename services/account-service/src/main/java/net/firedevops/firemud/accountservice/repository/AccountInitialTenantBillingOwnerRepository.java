package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stores only the initial fresh-tenant billing-owner consent and reads the current owner fence. */
@Repository
public class AccountInitialTenantBillingOwnerRepository {
  private static final String OPERATION_TABLE = "account_initial_tenant_billing_owner_operations";
  private static final String OWNER_TABLE = "account_tenant_billing_owners";
  private static final long INITIAL_OWNER_VERSION = 1L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Spring transaction proxy requires a non-final repository; constructor validates only its internal collaborator.")
  public AccountInitialTenantBillingOwnerRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Inserts one initial binding or returns its exact retained original result. Evidence is internal
   * input, not authenticated authority: caller authentication, current-authority revalidation, and
   * exact committed-receipt verification remain outside this repository. The caller must compose
   * that verification with this method in the authoritative Account creation transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public InitialBindingResult establishInitial(InitialRequest request) {
    requireWriteTransaction();
    Objects.requireNonNull(request, "initial billing-owner request is required");
    AccountInitialTenantBillingOwnerDigest.EncodedRequest encoded =
        AccountInitialTenantBillingOwnerDigest.request(request);
    FreshTenantCreatorEvidence creator = request.creatorEvidence();
    var creation = creator.creationEvidence();
    UUID ownerGeneration = UUID.randomUUID();
    byte[] resultPayload =
        AccountInitialTenantBillingOwnerDigest.resultPayload(request, encoded, ownerGeneration);
    String resultDigest = AccountInitialTenantBillingOwnerDigest.sha256(resultPayload);

    int inserted =
        dsl.execute(
            "INSERT INTO "
                + OPERATION_TABLE
                + " (request_id, tenant_uuid, initiating_account_uuid, initial_owner_account_uuid, "
                + "initial_owner_version, initial_owner_generation, target_namespace, "
                + "creation_request_id, creation_operation_id, creation_request_digest, "
                + "source_game_row_id, source_game_tenant_key, provenance_kind, "
                + "creation_evidence_digest, account_authorization_operation_id, "
                + "account_authorization_digest, creator_evidence_digest, creator_evidence_payload, "
                + "creator_evidence_payload_digest, consent_operation_id, consent_statement_version, "
                + "consent_affirmative, consent_payload, consent_digest, request_payload, "
                + "request_digest, result_payload, result_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING",
            request.requestId(),
            creation.canonicalTenantId(),
            creator.initiatingAccountId(),
            creator.initiatingAccountId(),
            INITIAL_OWNER_VERSION,
            ownerGeneration,
            creation.targetNamespace(),
            creation.creationRequestId(),
            creation.operationId(),
            creation.requestDigest(),
            creation.sourceGameRowId(),
            creation.sourceGameTenantKey(),
            creation.provenanceKind(),
            creation.evidenceDigest(),
            creator.accountAuthorizationOperationId(),
            creator.accountAuthorizationDigest(),
            creator.evidenceDigest(),
            encoded.creatorEvidencePayload(),
            encoded.creatorEvidencePayloadDigest(),
            request.consentOperationId(),
            request.consentStatementVersion(),
            encoded.consentPayload(),
            encoded.consentDigest(),
            encoded.requestPayload(),
            encoded.requestDigest(),
            resultPayload,
            resultDigest);
    if (inserted == 1) {
      insertInitialCurrentOwner(request, ownerGeneration);
      InitialBindingResult stored =
          findOperationForUpdate(request.requestId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Initial billing-owner result is absent after insert"));
      requireSameRequest(stored, request, encoded);
      if (!stored.ownerGeneration().equals(ownerGeneration)
          || !Arrays.equals(stored.resultPayload(), resultPayload)
          || !stored.resultDigest().equals(resultDigest)) {
        throw new IllegalStateException(
            "Initial billing-owner result readback differs from insert");
      }
      CurrentOwnerBinding current =
          findCurrentOwnerForUpdate(creation.canonicalTenantId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Initial current billing owner is absent after insert"));
      requireInitialCurrentOwner(current, request, ownerGeneration);
      return stored;
    }
    if (inserted != 0) {
      throw new IllegalStateException(
          "Initial billing-owner insert affected an ambiguous row count");
    }

    List<Record> conflicts =
        dsl.fetch(
            "SELECT * FROM "
                + OPERATION_TABLE
                + " WHERE request_id = ? OR tenant_uuid = ? OR consent_operation_id = ? "
                + "OR (target_namespace = ? AND creation_request_id = ?) "
                + "OR creation_operation_id = ? FOR UPDATE",
            request.requestId(),
            creation.canonicalTenantId(),
            request.consentOperationId(),
            creation.targetNamespace(),
            creation.creationRequestId(),
            creation.operationId());
    if (conflicts.size() != 1) {
      throw new OperationConflictException(
          "Initial billing-owner request conflicts with retained tenant or source evidence");
    }
    InitialBindingResult retained = toResult(conflicts.getFirst());
    if (!retained.requestId().equals(request.requestId())) {
      throw new OperationConflictException(
          "Fresh tenant already has a different immutable initial billing-owner operation");
    }
    requireSameRequest(retained, request, encoded);
    return retained;
  }

  /** Reads and row-locks the current owner tuple; this repository has no transfer/update method. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CurrentOwnerBinding> findCurrentOwnerForUpdate(UUID tenantUuid) {
    requireWriteTransaction();
    requireNonNil(tenantUuid, "tenant UUID");
    Record row =
        dsl.fetchOne(
            "SELECT tenant_uuid, owner_account_uuid, owner_version, owner_generation, "
                + "initial_request_id FROM "
                + OWNER_TABLE
                + " WHERE tenant_uuid = ? FOR UPDATE",
            tenantUuid);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        new CurrentOwnerBinding(
            required(row.get("tenant_uuid", UUID.class), "current tenant UUID"),
            required(row.get("owner_account_uuid", UUID.class), "current owner Account UUID"),
            required(row.get("owner_version", Long.class), "current owner version"),
            required(row.get("owner_generation", UUID.class), "current owner generation"),
            required(row.get("initial_request_id", UUID.class), "initial operation request ID")));
  }

  private void insertInitialCurrentOwner(InitialRequest request, UUID ownerGeneration) {
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + OWNER_TABLE
                + " (tenant_uuid, owner_account_uuid, owner_version, owner_generation, initial_request_id) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            request.creatorEvidence().creationEvidence().canonicalTenantId(),
            request.creatorEvidence().initiatingAccountId(),
            INITIAL_OWNER_VERSION,
            ownerGeneration,
            request.requestId());
    if (inserted != 1) {
      throw new OperationConflictException(
          "Fresh tenant already has a current billing-owner binding");
    }
  }

  private Optional<InitialBindingResult> findOperationForUpdate(UUID requestId) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + OPERATION_TABLE + " WHERE request_id = ? FOR UPDATE", requestId);
    return row == null ? Optional.empty() : Optional.of(toResult(row));
  }

  private static InitialBindingResult toResult(Record row) {
    InitialBindingResult result =
        new InitialBindingResult(
            required(row.get("request_id", UUID.class), "request ID"),
            required(row.get("tenant_uuid", UUID.class), "tenant UUID"),
            required(
                row.get("initial_owner_account_uuid", UUID.class), "initial owner Account UUID"),
            required(row.get("initial_owner_version", Long.class), "initial owner version"),
            required(row.get("initial_owner_generation", UUID.class), "initial owner generation"),
            required(row.get("target_namespace", String.class), "target namespace"),
            required(row.get("creation_request_id", UUID.class), "creation request ID"),
            required(row.get("creation_operation_id", UUID.class), "creation operation ID"),
            required(
                row.get("account_authorization_operation_id", UUID.class),
                "Account authorization operation ID"),
            required(
                row.get("account_authorization_digest", String.class),
                "Account authorization digest"),
            required(row.get("creation_evidence_digest", String.class), "creation evidence digest"),
            required(row.get("creator_evidence_digest", String.class), "creator evidence digest"),
            required(row.get("creator_evidence_payload", byte[].class), "creator evidence payload"),
            required(
                row.get("creator_evidence_payload_digest", String.class),
                "creator evidence payload digest"),
            required(row.get("consent_operation_id", UUID.class), "consent operation ID"),
            required(
                row.get("consent_statement_version", Integer.class), "consent statement version"),
            required(row.get("consent_affirmative", Boolean.class), "affirmative consent flag"),
            required(row.get("consent_payload", byte[].class), "consent payload"),
            required(row.get("consent_digest", String.class), "consent digest"),
            required(row.get("request_payload", byte[].class), "request payload"),
            required(row.get("request_digest", String.class), "request digest"),
            required(row.get("result_payload", byte[].class), "result payload"),
            required(row.get("result_digest", String.class), "result digest"),
            required(row.get("created_at", OffsetDateTime.class), "creation timestamp")
                .toInstant());
    if (result.ownerVersion() != INITIAL_OWNER_VERSION
        || !result.consentAffirmative()
        || !AccountInitialTenantBillingOwnerDigest.sha256(result.creatorEvidencePayload())
            .equals(result.creatorEvidencePayloadDigest())
        || !AccountInitialTenantBillingOwnerDigest.sha256(result.consentPayload())
            .equals(result.consentDigest())
        || !AccountInitialTenantBillingOwnerDigest.sha256(result.requestPayload())
            .equals(result.requestDigest())
        || !AccountInitialTenantBillingOwnerDigest.sha256(result.resultPayload())
            .equals(result.resultDigest())) {
      throw new IllegalStateException("Stored initial billing-owner evidence is malformed");
    }
    return result;
  }

  private static void requireSameRequest(
      InitialBindingResult retained,
      InitialRequest request,
      AccountInitialTenantBillingOwnerDigest.EncodedRequest encoded) {
    var creator = request.creatorEvidence();
    var creation = creator.creationEvidence();
    byte[] expectedResult =
        AccountInitialTenantBillingOwnerDigest.resultPayload(
            request, encoded, retained.ownerGeneration());
    if (!retained.requestId().equals(request.requestId())
        || !retained.tenantUuid().equals(creation.canonicalTenantId())
        || !retained.ownerAccountUuid().equals(creator.initiatingAccountId())
        || !retained.targetNamespace().equals(creation.targetNamespace())
        || !retained.creationRequestId().equals(creation.creationRequestId())
        || !retained.creationOperationId().equals(creation.operationId())
        || !retained
            .accountAuthorizationOperationId()
            .equals(creator.accountAuthorizationOperationId())
        || !retained.accountAuthorizationDigest().equals(creator.accountAuthorizationDigest())
        || !retained.creationEvidenceDigest().equals(creation.evidenceDigest())
        || !retained.creatorEvidenceDigest().equals(creator.evidenceDigest())
        || !Arrays.equals(retained.creatorEvidencePayload(), encoded.creatorEvidencePayload())
        || !retained.creatorEvidencePayloadDigest().equals(encoded.creatorEvidencePayloadDigest())
        || !retained.consentOperationId().equals(request.consentOperationId())
        || retained.consentStatementVersion() != request.consentStatementVersion()
        || !retained.consentAffirmative()
        || !Arrays.equals(retained.consentPayload(), encoded.consentPayload())
        || !retained.consentDigest().equals(encoded.consentDigest())
        || !Arrays.equals(retained.requestPayload(), encoded.requestPayload())
        || !retained.requestDigest().equals(encoded.requestDigest())
        || !Arrays.equals(retained.resultPayload(), expectedResult)) {
      throw new OperationConflictException(
          "Initial billing-owner request ID was reused with changed source or consent evidence");
    }
  }

  private static void requireInitialCurrentOwner(
      CurrentOwnerBinding current, InitialRequest request, UUID ownerGeneration) {
    if (!current
            .tenantUuid()
            .equals(request.creatorEvidence().creationEvidence().canonicalTenantId())
        || !current.ownerAccountUuid().equals(request.creatorEvidence().initiatingAccountId())
        || current.ownerVersion() != INITIAL_OWNER_VERSION
        || !current.ownerGeneration().equals(ownerGeneration)
        || !current.initialRequestId().equals(request.requestId())) {
      throw new IllegalStateException("Initial current billing-owner readback differs from insert");
    }
  }

  private static void requireWriteTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Initial billing-owner operation requires an active writable Account transaction");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label + " is required");
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be non-nil");
    }
  }

  private static <T> T required(T value, String label) {
    if (value == null) {
      throw new IllegalStateException("Stored initial billing-owner " + label + " is absent");
    }
    return value;
  }

  /**
   * Exact internal affirmative-consent and fresh-tenant source input; not authenticated authority.
   */
  public record InitialRequest(
      UUID requestId,
      FreshTenantCreatorEvidence creatorEvidence,
      UUID consentOperationId,
      int consentStatementVersion,
      boolean affirmativeConsent) {
    public InitialRequest {
      requireNonNil(requestId, "initial billing-owner request ID");
      Objects.requireNonNull(creatorEvidence, "fresh tenant creator evidence is required");
      requireNonNil(consentOperationId, "billing-owner consent operation ID");
      if (consentStatementVersion <= 0) {
        throw new IllegalArgumentException(
            "Billing-owner consent statement version must be positive");
      }
      if (!affirmativeConsent) {
        throw new IllegalArgumentException("Initial billing-owner consent must be affirmative");
      }
    }
  }

  /** Current owner identity plus its independently versioned authority fence. */
  public record CurrentOwnerBinding(
      UUID tenantUuid,
      UUID ownerAccountUuid,
      long ownerVersion,
      UUID ownerGeneration,
      UUID initialRequestId) {
    public CurrentOwnerBinding {
      requireNonNil(tenantUuid, "current tenant UUID");
      requireNonNil(ownerAccountUuid, "current owner Account UUID");
      if (ownerVersion <= 0L) {
        throw new IllegalArgumentException("Current owner version must be positive");
      }
      requireNonNil(ownerGeneration, "current owner generation");
      requireNonNil(initialRequestId, "initial request ID");
    }
  }

  /** Immutable original binding and consent receipt returned for both insertion and exact retry. */
  public record InitialBindingResult(
      UUID requestId,
      UUID tenantUuid,
      UUID ownerAccountUuid,
      long ownerVersion,
      UUID ownerGeneration,
      String targetNamespace,
      UUID creationRequestId,
      UUID creationOperationId,
      UUID accountAuthorizationOperationId,
      String accountAuthorizationDigest,
      String creationEvidenceDigest,
      String creatorEvidenceDigest,
      byte[] creatorEvidencePayload,
      String creatorEvidencePayloadDigest,
      UUID consentOperationId,
      int consentStatementVersion,
      boolean consentAffirmative,
      byte[] consentPayload,
      String consentDigest,
      byte[] requestPayload,
      String requestDigest,
      byte[] resultPayload,
      String resultDigest,
      Instant createdAt) {
    public InitialBindingResult {
      requireNonNil(requestId, "initial request ID");
      requireNonNil(tenantUuid, "initial tenant UUID");
      requireNonNil(ownerAccountUuid, "initial owner Account UUID");
      if (ownerVersion <= 0L || consentStatementVersion <= 0) {
        throw new IllegalArgumentException("Initial owner and consent versions must be positive");
      }
      requireNonNil(ownerGeneration, "initial owner generation");
      Objects.requireNonNull(targetNamespace, "target namespace");
      requireNonNil(creationRequestId, "creation request ID");
      requireNonNil(creationOperationId, "creation operation ID");
      requireNonNil(accountAuthorizationOperationId, "Account authorization operation ID");
      Objects.requireNonNull(accountAuthorizationDigest, "Account authorization digest");
      Objects.requireNonNull(creationEvidenceDigest, "creation evidence digest");
      Objects.requireNonNull(creatorEvidenceDigest, "creator evidence digest");
      creatorEvidencePayload = cloneBytes(creatorEvidencePayload);
      Objects.requireNonNull(creatorEvidencePayloadDigest, "creator evidence payload digest");
      requireNonNil(consentOperationId, "consent operation ID");
      consentPayload = cloneBytes(consentPayload);
      Objects.requireNonNull(consentDigest, "consent digest");
      requestPayload = cloneBytes(requestPayload);
      Objects.requireNonNull(requestDigest, "request digest");
      resultPayload = cloneBytes(resultPayload);
      Objects.requireNonNull(resultDigest, "result digest");
      Objects.requireNonNull(createdAt, "creation timestamp");
      if (!consentAffirmative
          || creatorEvidencePayload.length == 0
          || consentPayload.length == 0
          || requestPayload.length == 0
          || resultPayload.length == 0) {
        throw new IllegalArgumentException("Initial billing-owner result evidence is incomplete");
      }
    }

    @Override
    public byte[] creatorEvidencePayload() {
      return cloneBytes(creatorEvidencePayload);
    }

    @Override
    public byte[] consentPayload() {
      return cloneBytes(consentPayload);
    }

    @Override
    public byte[] requestPayload() {
      return cloneBytes(requestPayload);
    }

    @Override
    public byte[] resultPayload() {
      return cloneBytes(resultPayload);
    }

    private static byte[] cloneBytes(byte[] bytes) {
      return Objects.requireNonNull(bytes, "evidence payload is required").clone();
    }
  }

  /** One tenant has one initial owner operation; this path never transfers an established owner. */
  public static final class OperationConflictException extends IllegalStateException {
    public OperationConflictException(String message) {
      super(message);
    }
  }
}
