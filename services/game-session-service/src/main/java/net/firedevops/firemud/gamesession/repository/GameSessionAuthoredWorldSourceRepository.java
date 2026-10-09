package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Game Session's immutable, owner-local copy of authenticated Game Design authored-world source.
 */
@Repository
public class GameSessionAuthoredWorldSourceRepository {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private static final Table<?> TENANT_BINDING =
      DSL.table(DSL.name("game_session_authored_world_tenant_source_binding"));
  private static final Field<String> BINDING_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> BINDING_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> BINDING_TENANT_SLUG =
      DSL.field(DSL.name("tenant_slug"), String.class);
  private static final Field<Long> BINDING_SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("source_game_row_id"), Long.class);
  private static final Field<String> BINDING_SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<String> BINDING_PROVENANCE_KIND =
      DSL.field(DSL.name("provenance_kind"), String.class);

  private static final Table<?> INTAKE =
      DSL.table(DSL.name("game_session_authored_world_source_intake"));
  private static final Field<UUID> OPERATION_ID = DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<Integer> OPERATION_SCHEMA_VERSION =
      DSL.field(DSL.name("schema_version"), Integer.class);
  private static final Field<String> OPERATION_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> INTAKE_REQUEST_ID =
      DSL.field(DSL.name("intake_request_id"), UUID.class);
  private static final Field<String> REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final Field<Integer> SOURCE_SCHEMA_VERSION =
      DSL.field(DSL.name("source_schema_version"), Integer.class);
  private static final Field<UUID> SOURCE_REGISTRATION_REQUEST_ID =
      DSL.field(DSL.name("source_registration_request_id"), UUID.class);
  private static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("source_operation_id"), UUID.class);
  private static final Field<String> SOURCE_REQUEST_DIGEST =
      DSL.field(DSL.name("source_request_digest"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> TENANT_SLUG = DSL.field(DSL.name("tenant_slug"), String.class);
  private static final Field<String> WORLD_SLUG = DSL.field(DSL.name("world_slug"), String.class);
  private static final Field<String> WORLD_DISPLAY_NAME =
      DSL.field(DSL.name("world_display_name"), String.class);
  private static final Field<Long> SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("source_game_row_id"), Long.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<String> SOURCE_PROVENANCE_KIND =
      DSL.field(DSL.name("source_provenance_kind"), String.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("source_evidence_digest"), String.class);
  private static final Field<String> RECEIPT_DIGEST =
      DSL.field(DSL.name("receipt_digest"), String.class);

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Trusted Spring collaborator is validated before use; no resources or finalizer are acquired."
              + " The repository remains non-final for transaction proxying.")
  public GameSessionAuthoredWorldSourceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
  }

  /**
   * Persists one complete source receipt under the caller's owner transaction.
   *
   * <p>The authenticated source client is responsible for obtaining the evidence from Game Design.
   * This repository validates its closed evidence and digests, then atomically stores that exact
   * copy with the stable tenant and world selector claims.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public IntakeReceipt register(UUID intakeRequestId, AuthoredWorldSourceEvidence source) {
    requireActiveOwnerTransaction();
    requireNonNil(intakeRequestId, "intakeRequestId");
    AuthoredWorldSourceEvidence validatedSource = validateSource(source);
    String requestDigest =
        GameSessionAuthoredWorldIntakeDigest.requestDigest(intakeRequestId, validatedSource);

    Record existingRequest =
        findByIntakeRequest(validatedSource.targetNamespace(), intakeRequestId);
    if (existingRequest != null) {
      return requireExactReplay(existingRequest, intakeRequestId, requestDigest, validatedSource);
    }

    ensureTenantBinding(validatedSource);
    UUID operationId = UUID.randomUUID();
    String receiptDigest =
        GameSessionAuthoredWorldIntakeDigest.receiptDigest(
            operationId, requestDigest, validatedSource.evidenceDigest());

    int inserted =
        dsl.insertInto(INTAKE)
            .set(OPERATION_ID, operationId)
            .set(OPERATION_SCHEMA_VERSION, SCHEMA_VERSION)
            .set(OPERATION_NAMESPACE, validatedSource.targetNamespace())
            .set(INTAKE_REQUEST_ID, intakeRequestId)
            .set(REQUEST_DIGEST, requestDigest)
            .set(SOURCE_SCHEMA_VERSION, validatedSource.schemaVersion())
            .set(SOURCE_REGISTRATION_REQUEST_ID, validatedSource.registrationRequestId())
            .set(SOURCE_OPERATION_ID, validatedSource.operationId())
            .set(SOURCE_REQUEST_DIGEST, validatedSource.requestDigest())
            .set(CANONICAL_TENANT_ID, validatedSource.canonicalTenantId())
            .set(TENANT_SLUG, validatedSource.tenantSlug())
            .set(WORLD_SLUG, validatedSource.worldSlug())
            .set(WORLD_DISPLAY_NAME, validatedSource.worldDisplayName())
            .set(SOURCE_GAME_ROW_ID, validatedSource.sourceGameRowId())
            .set(SOURCE_GAME_TENANT_KEY, validatedSource.sourceGameTenantKey())
            .set(SOURCE_PROVENANCE_KIND, validatedSource.provenanceKind())
            .set(SOURCE_EVIDENCE_DIGEST, validatedSource.evidenceDigest())
            .set(RECEIPT_DIGEST, receiptDigest)
            .onConflictDoNothing()
            .execute();
    if (inserted == 0) {
      Record racedRequest = findByIntakeRequest(validatedSource.targetNamespace(), intakeRequestId);
      if (racedRequest != null) {
        return requireExactReplay(racedRequest, intakeRequestId, requestDigest, validatedSource);
      }
      if (findBySourceOperation(validatedSource.targetNamespace(), validatedSource.operationId())
          != null) {
        throw new RegistrationConflictException(
            "Game Design source operation is already consumed by another intake request");
      }
      if (findBySourceRegistrationRequest(
              validatedSource.targetNamespace(), validatedSource.registrationRequestId())
          != null) {
        throw new RegistrationConflictException(
            "Game Design source registration request is already consumed by another intake");
      }
      if (findByWorldSelector(
              validatedSource.targetNamespace(),
              validatedSource.canonicalTenantId(),
              validatedSource.worldSlug())
          != null) {
        throw new RegistrationConflictException(
            "Authored-world selector is already bound to another consumed source");
      }
      throw new RegistrationConflictException(
          "Authored-world source intake conflicts with an existing owner claim");
    }

    Record persisted = findByIntakeRequest(validatedSource.targetNamespace(), intakeRequestId);
    if (persisted == null) {
      throw new IllegalStateException("Committed authored-world source intake is missing");
    }
    IntakeReceipt receipt = toReceipt(persisted);
    requireExactSource(receipt.source(), validatedSource);
    requireTenantBinding(receipt.source());
    return receipt;
  }

  /** Reads the exact committed local operation without allocating or repairing selector state. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<IntakeReceipt> read(
      UUID localOperationId, UUID canonicalTenantId, String worldSlug, String namespace) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authored-world source intake read requires a committed-outcome owner read");
    }
    AuthoredWorldSourceDigest.validateReadSelector(namespace, canonicalTenantId, worldSlug);
    requireNonNil(localOperationId, "localOperationId");

    Record record =
        dsl.selectFrom(INTAKE)
            .where(
                OPERATION_ID
                    .eq(localOperationId)
                    .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                    .and(WORLD_SLUG.eq(worldSlug))
                    .and(OPERATION_NAMESPACE.eq(namespace)))
            .fetchOne();
    if (record == null) {
      return Optional.empty();
    }
    IntakeReceipt receipt = toReceipt(record);
    requireTenantBinding(receipt.source());
    return Optional.of(receipt);
  }

  /**
   * Resolves the one committed source for an exact canonical world selector.
   *
   * <p>This owner-local read does not infer source operation IDs or digests from retained numeric
   * keys. The returned original receipt still requires exact comparison with the selected catalog
   * and separately proved retained association before any launch use.
   */
  @Transactional(propagation = Propagation.NEVER, readOnly = true)
  public Optional<IntakeReceipt> readByWorldSelector(
      String namespace, UUID canonicalTenantId, String worldSlug) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authored-world source intake read requires a committed-outcome owner read");
    }
    AuthoredWorldSourceDigest.validateReadSelector(namespace, canonicalTenantId, worldSlug);
    Record record = findByWorldSelector(namespace, canonicalTenantId, worldSlug);
    if (record == null) {
      return Optional.empty();
    }
    IntakeReceipt receipt = toReceipt(record);
    requireTenantBinding(receipt.source());
    return Optional.of(receipt);
  }

  /** Reads an exact committed retry by its stable intake request without allocating owner state. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<IntakeReceipt> readByIntakeRequest(String namespace, UUID intakeRequestId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authored-world source intake read requires a committed-outcome owner read");
    }
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    requireNonNil(intakeRequestId, "intakeRequestId");

    Record record =
        dsl.selectFrom(INTAKE)
            .where(OPERATION_NAMESPACE.eq(namespace).and(INTAKE_REQUEST_ID.eq(intakeRequestId)))
            .fetchOne();
    if (record == null) {
      return Optional.empty();
    }
    IntakeReceipt receipt = toReceipt(record);
    if (!receipt.intakeRequestId().equals(intakeRequestId)
        || !receipt.source().targetNamespace().equals(namespace)) {
      throw new InvalidIntakeEvidenceException(
          "Persisted authored-world source intake request binding is invalid");
    }
    requireTenantBinding(receipt.source());
    return Optional.of(receipt);
  }

  private void ensureTenantBinding(AuthoredWorldSourceEvidence source) {
    Record existing = findTenantBinding(source.targetNamespace(), source.canonicalTenantId());
    if (existing != null) {
      requireMatchingBinding(existing, source);
      return;
    }

    int inserted =
        dsl.insertInto(TENANT_BINDING)
            .set(BINDING_NAMESPACE, source.targetNamespace())
            .set(BINDING_CANONICAL_TENANT_ID, source.canonicalTenantId())
            .set(BINDING_TENANT_SLUG, source.tenantSlug())
            .set(BINDING_SOURCE_GAME_ROW_ID, source.sourceGameRowId())
            .set(BINDING_SOURCE_GAME_TENANT_KEY, source.sourceGameTenantKey())
            .set(BINDING_PROVENANCE_KIND, source.provenanceKind())
            .onConflictDoNothing()
            .execute();
    if (inserted == 1) {
      return;
    }

    Record tenantBinding = findTenantBinding(source.targetNamespace(), source.canonicalTenantId());
    if (tenantBinding != null) {
      requireMatchingBinding(tenantBinding, source);
      return;
    }
    if (findTenantSlugBinding(source.targetNamespace(), source.tenantSlug()) != null) {
      throw new RegistrationConflictException(
          "Tenant selector is already owned by another canonical tenant");
    }
    if (findSourceRowBinding(source.targetNamespace(), source.sourceGameRowId()) != null) {
      throw new RegistrationConflictException(
          "Game Design source row is already bound to another canonical tenant");
    }
    throw new RegistrationConflictException("Tenant source binding conflicts with an owner claim");
  }

  private void requireTenantBinding(AuthoredWorldSourceEvidence source) {
    Record binding = findTenantBinding(source.targetNamespace(), source.canonicalTenantId());
    if (binding == null) {
      throw new InvalidIntakeEvidenceException("Tenant source selector binding is missing");
    }
    requireMatchingBinding(binding, source);
  }

  private void requireMatchingBinding(Record binding, AuthoredWorldSourceEvidence source) {
    if (!Objects.equals(binding.get(BINDING_NAMESPACE), source.targetNamespace())
        || !Objects.equals(binding.get(BINDING_CANONICAL_TENANT_ID), source.canonicalTenantId())
        || !Objects.equals(binding.get(BINDING_TENANT_SLUG), source.tenantSlug())
        || !Objects.equals(binding.get(BINDING_SOURCE_GAME_ROW_ID), source.sourceGameRowId())
        || !Objects.equals(
            binding.get(BINDING_SOURCE_GAME_TENANT_KEY), source.sourceGameTenantKey())
        || !Objects.equals(binding.get(BINDING_PROVENANCE_KIND), source.provenanceKind())) {
      throw new RegistrationConflictException(
          "Canonical tenant source or stable tenant selector conflicts with its prior claim");
    }
  }

  private IntakeReceipt requireExactReplay(
      Record record,
      UUID intakeRequestId,
      String requestDigest,
      AuthoredWorldSourceEvidence source) {
    IntakeReceipt receipt = toReceipt(record);
    if (!receipt.intakeRequestId().equals(intakeRequestId)
        || !receipt.requestDigest().equals(requestDigest)
        || !receipt.source().equals(source)) {
      throw new RegistrationConflictException(
          "Authored-world intake identity was reused with changed source evidence");
    }
    requireTenantBinding(receipt.source());
    return receipt;
  }

  private void requireExactSource(
      AuthoredWorldSourceEvidence actual, AuthoredWorldSourceEvidence expected) {
    if (!actual.equals(expected)) {
      throw new InvalidIntakeEvidenceException(
          "Persisted authored-world source differs from its authenticated input");
    }
  }

  private AuthoredWorldSourceEvidence validateSource(AuthoredWorldSourceEvidence source) {
    Objects.requireNonNull(source, "source");
    try {
      return new AuthoredWorldSourceEvidence(
          source.schemaVersion(),
          source.targetNamespace(),
          source.registrationRequestId(),
          source.operationId(),
          source.requestDigest(),
          source.canonicalTenantId(),
          source.tenantSlug(),
          source.worldSlug(),
          source.worldDisplayName(),
          source.sourceGameRowId(),
          source.sourceGameTenantKey(),
          source.provenanceKind(),
          source.evidenceDigest());
    } catch (IllegalArgumentException exception) {
      throw new InvalidIntakeEvidenceException(
          "Game Design authored-world source evidence is invalid", exception);
    }
  }

  private void requireActiveOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authored-world source intake registration requires an active owner transaction");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private Record findByIntakeRequest(String namespace, UUID intakeRequestId) {
    return dsl.selectFrom(INTAKE)
        .where(OPERATION_NAMESPACE.eq(namespace).and(INTAKE_REQUEST_ID.eq(intakeRequestId)))
        .fetchOne();
  }

  private Record findBySourceOperation(String namespace, UUID sourceOperationId) {
    return dsl.selectFrom(INTAKE)
        .where(OPERATION_NAMESPACE.eq(namespace).and(SOURCE_OPERATION_ID.eq(sourceOperationId)))
        .fetchOne();
  }

  private Record findBySourceRegistrationRequest(String namespace, UUID sourceRequestId) {
    return dsl.selectFrom(INTAKE)
        .where(
            OPERATION_NAMESPACE
                .eq(namespace)
                .and(SOURCE_REGISTRATION_REQUEST_ID.eq(sourceRequestId)))
        .fetchOne();
  }

  private Record findByWorldSelector(String namespace, UUID canonicalTenantId, String worldSlug) {
    return dsl.selectFrom(INTAKE)
        .where(
            OPERATION_NAMESPACE
                .eq(namespace)
                .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                .and(WORLD_SLUG.eq(worldSlug)))
        .fetchOne();
  }

  private Record findTenantBinding(String namespace, UUID canonicalTenantId) {
    return dsl.selectFrom(TENANT_BINDING)
        .where(
            BINDING_NAMESPACE.eq(namespace).and(BINDING_CANONICAL_TENANT_ID.eq(canonicalTenantId)))
        .fetchOne();
  }

  private Record findTenantSlugBinding(String namespace, String tenantSlug) {
    return dsl.selectFrom(TENANT_BINDING)
        .where(BINDING_NAMESPACE.eq(namespace).and(BINDING_TENANT_SLUG.eq(tenantSlug)))
        .fetchOne();
  }

  private Record findSourceRowBinding(String namespace, long sourceGameRowId) {
    return dsl.selectFrom(TENANT_BINDING)
        .where(BINDING_NAMESPACE.eq(namespace).and(BINDING_SOURCE_GAME_ROW_ID.eq(sourceGameRowId)))
        .fetchOne();
  }

  private IntakeReceipt toReceipt(Record record) {
    try {
      AuthoredWorldSourceEvidence source =
          new AuthoredWorldSourceEvidence(
              Objects.requireNonNull(record.get(SOURCE_SCHEMA_VERSION)),
              Objects.requireNonNull(record.get(OPERATION_NAMESPACE)),
              Objects.requireNonNull(record.get(SOURCE_REGISTRATION_REQUEST_ID)),
              Objects.requireNonNull(record.get(SOURCE_OPERATION_ID)),
              Objects.requireNonNull(record.get(SOURCE_REQUEST_DIGEST)),
              Objects.requireNonNull(record.get(CANONICAL_TENANT_ID)),
              Objects.requireNonNull(record.get(TENANT_SLUG)),
              Objects.requireNonNull(record.get(WORLD_SLUG)),
              Objects.requireNonNull(record.get(WORLD_DISPLAY_NAME)),
              Objects.requireNonNull(record.get(SOURCE_GAME_ROW_ID)),
              Objects.requireNonNull(record.get(SOURCE_GAME_TENANT_KEY)),
              Objects.requireNonNull(record.get(SOURCE_PROVENANCE_KIND)),
              Objects.requireNonNull(record.get(SOURCE_EVIDENCE_DIGEST)));
      if (!Integer.valueOf(SCHEMA_VERSION).equals(record.get(OPERATION_SCHEMA_VERSION))) {
        throw new IllegalArgumentException("Unsupported local intake schema version");
      }
      return new IntakeReceipt(
          Objects.requireNonNull(record.get(OPERATION_ID)),
          Objects.requireNonNull(record.get(INTAKE_REQUEST_ID)),
          Objects.requireNonNull(record.get(REQUEST_DIGEST)),
          source,
          Objects.requireNonNull(record.get(RECEIPT_DIGEST)));
    } catch (RuntimeException exception) {
      if (exception instanceof InvalidIntakeEvidenceException) {
        throw exception;
      }
      throw new InvalidIntakeEvidenceException(
          "Persisted authored-world source intake evidence is invalid", exception);
    }
  }

  /** Immutable local receipt retaining the exact authenticated Game Design source record. */
  public record IntakeReceipt(
      UUID operationId,
      UUID intakeRequestId,
      String requestDigest,
      AuthoredWorldSourceEvidence source,
      String receiptDigest) {
    public IntakeReceipt {
      requireNonNil(operationId, "operationId");
      requireNonNil(intakeRequestId, "intakeRequestId");
      Objects.requireNonNull(requestDigest, "requestDigest");
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(receiptDigest, "receiptDigest");
      AuthoredWorldSourceEvidence validated =
          new AuthoredWorldSourceEvidence(
              source.schemaVersion(),
              source.targetNamespace(),
              source.registrationRequestId(),
              source.operationId(),
              source.requestDigest(),
              source.canonicalTenantId(),
              source.tenantSlug(),
              source.worldSlug(),
              source.worldDisplayName(),
              source.sourceGameRowId(),
              source.sourceGameTenantKey(),
              source.provenanceKind(),
              source.evidenceDigest());
      if (!validated.equals(source)
          || !requestDigest.equals(
              GameSessionAuthoredWorldIntakeDigest.requestDigest(intakeRequestId, source))
          || !receiptDigest.equals(
              GameSessionAuthoredWorldIntakeDigest.receiptDigest(
                  operationId, requestDigest, source.evidenceDigest()))) {
        throw new IllegalArgumentException(
            "Game Session authored-world receipt digests do not match");
      }
    }
  }

  public static final class RegistrationConflictException extends IllegalStateException {
    public RegistrationConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidIntakeEvidenceException extends IllegalStateException {
    public InvalidIntakeEvidenceException(String message) {
      super(message);
    }

    public InvalidIntakeEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
