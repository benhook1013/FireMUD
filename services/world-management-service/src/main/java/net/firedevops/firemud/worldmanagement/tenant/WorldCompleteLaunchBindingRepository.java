package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Owner-local immutable persistence for complete authored-world launch evidence. */
public class WorldCompleteLaunchBindingRepository {
  private static final Table<?> BINDING = DSL.table(DSL.name("world_complete_launch_binding"));
  private static final JsonMapper CLOSED_JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private static final Field<UUID> BINDING_OPERATION_ID =
      DSL.field(DSL.name("binding_operation_id"), UUID.class);
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<UUID> CANONICAL_VERSION_ID =
      DSL.field(DSL.name("canonical_version_id"), UUID.class);
  private static final Field<String> WORLD_SLUG = DSL.field(DSL.name("world_slug"), String.class);
  private static final Field<String> CONTROL_PLANE_REQUEST_ID =
      DSL.field(DSL.name("control_plane_request_id"), String.class);
  private static final Field<UUID> INTAKE_OPERATION_ID =
      DSL.field(DSL.name("intake_operation_id"), UUID.class);
  private static final Field<UUID> INTAKE_REQUEST_ID =
      DSL.field(DSL.name("intake_request_id"), UUID.class);
  private static final Field<Long> LOCAL_TENANT_KEY =
      DSL.field(DSL.name("local_tenant_key"), Long.class);
  private static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("source_operation_id"), UUID.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("source_evidence_digest"), String.class);
  private static final Field<String> INTAKE_RECEIPT_DIGEST =
      DSL.field(DSL.name("intake_receipt_digest"), String.class);
  private static final Field<String> DESCRIPTOR_REQUEST_DIGEST =
      DSL.field(DSL.name("descriptor_request_digest"), String.class);
  private static final Field<String> DESCRIPTOR_RESULT_DIGEST =
      DSL.field(DSL.name("descriptor_result_digest"), String.class);
  private static final Field<String> RELEASE_ATTESTATION_DIGEST =
      DSL.field(DSL.name("release_attestation_digest"), String.class);
  private static final Field<String> DESCRIPTOR_JSON =
      DSL.field(DSL.name("descriptor_json"), String.class);
  private static final Field<String> RELEASE_ATTESTATION_JSON =
      DSL.field(DSL.name("release_attestation_json"), String.class);

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The injected DSLContext is an internal collaborator; construction acquires no resources and this repository has no finalizer.")
  public WorldCompleteLaunchBindingRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
  }

  /** Reads the immutable owner row by its stable namespace/tenant/control-plane request key. */
  public Optional<StoredBinding> read(
      String namespace, UUID canonicalTenantId, String controlPlaneRequestId) {
    requireNoActiveTransaction("World complete launch binding read");
    validateKey(namespace, canonicalTenantId, controlPlaneRequestId);
    Record row = find(namespace, canonicalTenantId, controlPlaneRequestId);
    return row == null ? Optional.empty() : Optional.of(toStoredBinding(row));
  }

  /**
   * Retains one complete pair against the exact committed local source receipt.
   *
   * <p>The caller must authenticate Game Session, perform all remote and committed reads outside
   * the owner transaction, then invoke this method in a fresh writable READ COMMITTED transaction.
   */
  public WorldCompleteLaunchBindingReceipt acceptFresh(
      String namespace,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      CompleteLaunchBindingEvidence evidence) {
    requireWritableReadCommittedOwnerTransaction();
    Objects.requireNonNull(sourceReceipt, "sourceReceipt");
    Objects.requireNonNull(evidence, "evidence");
    requireSourceBinding(namespace, sourceReceipt, evidence);

    AuthoredWorldLaunchDescriptorEvidence descriptor = evidence.descriptor();
    String descriptorJson = writeJson(descriptor);
    String attestationJson = writeJson(evidence.releaseAttestation());
    UUID operationId = newNonNilUuid();
    int inserted =
        dsl.insertInto(BINDING)
            .set(BINDING_OPERATION_ID, operationId)
            .set(TARGET_NAMESPACE, namespace)
            .set(CANONICAL_TENANT_ID, descriptor.canonicalTenantId())
            .set(CANONICAL_VERSION_ID, evidence.releaseAttestation().canonicalVersionId())
            .set(WORLD_SLUG, descriptor.worldSlug())
            .set(CONTROL_PLANE_REQUEST_ID, descriptor.controlPlaneRequestId())
            .set(INTAKE_OPERATION_ID, sourceReceipt.operationId())
            .set(INTAKE_REQUEST_ID, sourceReceipt.intakeRequestId())
            .set(LOCAL_TENANT_KEY, sourceReceipt.localTenantKey())
            .set(SOURCE_OPERATION_ID, sourceReceipt.sourceOperationId())
            .set(SOURCE_EVIDENCE_DIGEST, sourceReceipt.sourceEvidenceDigest())
            .set(INTAKE_RECEIPT_DIGEST, sourceReceipt.receiptDigest())
            .set(DESCRIPTOR_REQUEST_DIGEST, descriptor.requestDigest())
            .set(DESCRIPTOR_RESULT_DIGEST, descriptor.resultDigest())
            .set(RELEASE_ATTESTATION_DIGEST, evidence.releaseAttestation().evidenceDigest())
            .set(DESCRIPTOR_JSON, descriptorJson)
            .set(RELEASE_ATTESTATION_JSON, attestationJson)
            .onConflictDoNothing()
            .execute();

    Record row =
        find(namespace, descriptor.canonicalTenantId(), descriptor.controlPlaneRequestId());
    if (row == null) {
      throw new InvalidBindingEvidenceException(
          "World complete launch binding is missing after its owner claim");
    }
    StoredBinding stored = toStoredBinding(row);
    WorldCompleteLaunchBindingReceipt receipt = toReceipt(stored, sourceReceipt);
    requireExactEvidence(receipt, evidence);
    if (inserted == 0) {
      throwIfInsertDidNotSelectOriginal(receipt, sourceReceipt, evidence);
    }
    return receipt;
  }

  /**
   * Rehydrates the internal receipt only after the exact local intake has been read independently.
   */
  WorldCompleteLaunchBindingReceipt toReceipt(
      StoredBinding stored, WorldAuthoredSourceIntakeReceipt sourceReceipt) {
    Objects.requireNonNull(stored, "stored");
    Objects.requireNonNull(sourceReceipt, "sourceReceipt");
    if (!stored.targetNamespace().equals(sourceReceipt.targetNamespace())
        || !stored.canonicalTenantId().equals(sourceReceipt.canonicalTenantId())
        || !stored.worldSlug().equals(sourceReceipt.worldSlug())
        || !stored.intakeOperationId().equals(sourceReceipt.operationId())
        || !stored.intakeRequestId().equals(sourceReceipt.intakeRequestId())
        || stored.localTenantKey() != sourceReceipt.localTenantKey()
        || !stored.sourceOperationId().equals(sourceReceipt.sourceOperationId())
        || !stored.sourceEvidenceDigest().equals(sourceReceipt.sourceEvidenceDigest())
        || !stored.intakeReceiptDigest().equals(sourceReceipt.receiptDigest())) {
      throw new InvalidBindingEvidenceException(
          "World complete launch binding differs from its exact local source receipt");
    }
    try {
      return new WorldCompleteLaunchBindingReceipt(
          1,
          stored.operationId(),
          stored.targetNamespace(),
          stored.canonicalTenantId(),
          stored.worldSlug(),
          stored.controlPlaneRequestId(),
          sourceReceipt,
          stored.evidence());
    } catch (RuntimeException exception) {
      if (exception instanceof InvalidBindingEvidenceException) {
        throw exception;
      }
      throw new InvalidBindingEvidenceException(
          "Persisted World complete launch binding conflicts with its local source", exception);
    }
  }

  void requireExactEvidence(
      WorldCompleteLaunchBindingReceipt receipt, CompleteLaunchBindingEvidence expected) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = expected.descriptor();
    if (!receipt.evidence().equals(expected)
        || !receipt.targetNamespace().equals(descriptor.targetNamespace())
        || !receipt.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !receipt.worldSlug().equals(descriptor.worldSlug())
        || !receipt.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())) {
      throw new RegistrationConflictException(
          "World complete launch binding request identity was reused with changed evidence");
    }
  }

  private void throwIfInsertDidNotSelectOriginal(
      WorldCompleteLaunchBindingReceipt receipt,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      CompleteLaunchBindingEvidence expected) {
    if (!receipt.sourceIntakeReceipt().equals(sourceReceipt)) {
      throw new RegistrationConflictException(
          "World complete launch binding request identity changed its source intake");
    }
    requireExactEvidence(receipt, expected);
  }

  private Record find(String namespace, UUID canonicalTenantId, String controlPlaneRequestId) {
    return dsl.selectFrom(BINDING)
        .where(
            TARGET_NAMESPACE
                .eq(namespace)
                .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                .and(CONTROL_PLANE_REQUEST_ID.eq(controlPlaneRequestId)))
        .fetchOne();
  }

  private StoredBinding toStoredBinding(Record row) {
    try {
      UUID operationId = required(row, BINDING_OPERATION_ID);
      String namespace = required(row, TARGET_NAMESPACE);
      UUID canonicalTenantId = required(row, CANONICAL_TENANT_ID);
      UUID canonicalVersionId = required(row, CANONICAL_VERSION_ID);
      String worldSlug = required(row, WORLD_SLUG);
      String controlPlaneRequestId = required(row, CONTROL_PLANE_REQUEST_ID);
      UUID intakeOperationId = required(row, INTAKE_OPERATION_ID);
      UUID intakeRequestId = required(row, INTAKE_REQUEST_ID);
      Long localTenantKey = required(row, LOCAL_TENANT_KEY);
      UUID sourceOperationId = required(row, SOURCE_OPERATION_ID);
      String sourceEvidenceDigest = required(row, SOURCE_EVIDENCE_DIGEST);
      String intakeReceiptDigest = required(row, INTAKE_RECEIPT_DIGEST);
      String descriptorRequestDigest = required(row, DESCRIPTOR_REQUEST_DIGEST);
      String descriptorResultDigest = required(row, DESCRIPTOR_RESULT_DIGEST);
      String attestationDigest = required(row, RELEASE_ATTESTATION_DIGEST);
      AuthoredWorldLaunchDescriptorEvidence descriptor =
          readJson(required(row, DESCRIPTOR_JSON), AuthoredWorldLaunchDescriptorEvidence.class);
      AuthoredWorldReleaseAttestationEvidence attestation =
          readJson(
              required(row, RELEASE_ATTESTATION_JSON),
              AuthoredWorldReleaseAttestationEvidence.class);
      descriptor.requireValid();
      CompleteLaunchBindingEvidence evidence =
          new CompleteLaunchBindingEvidence(descriptor, attestation);
      requireStoredColumns(
          namespace,
          canonicalTenantId,
          canonicalVersionId,
          worldSlug,
          controlPlaneRequestId,
          sourceOperationId,
          sourceEvidenceDigest,
          descriptorRequestDigest,
          descriptorResultDigest,
          attestationDigest,
          descriptor,
          attestation);
      if (localTenantKey <= 0) {
        throw new IllegalArgumentException("Stored local tenant key must be positive");
      }
      return new StoredBinding(
          operationId,
          namespace,
          canonicalTenantId,
          worldSlug,
          controlPlaneRequestId,
          intakeOperationId,
          intakeRequestId,
          localTenantKey,
          sourceOperationId,
          sourceEvidenceDigest,
          intakeReceiptDigest,
          descriptorRequestDigest,
          descriptorResultDigest,
          attestationDigest,
          evidence);
    } catch (RuntimeException exception) {
      if (exception instanceof InvalidBindingEvidenceException) {
        throw exception;
      }
      throw new InvalidBindingEvidenceException(
          "Persisted World complete launch binding evidence is invalid", exception);
    }
  }

  private static void requireStoredColumns(
      String namespace,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String worldSlug,
      String controlPlaneRequestId,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      String descriptorRequestDigest,
      String descriptorResultDigest,
      String attestationDigest,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldReleaseAttestationEvidence attestation) {
    if (!namespace.equals(descriptor.targetNamespace())
        || !canonicalTenantId.equals(descriptor.canonicalTenantId())
        || !worldSlug.equals(descriptor.worldSlug())
        || !controlPlaneRequestId.equals(descriptor.controlPlaneRequestId())
        || !sourceOperationId.equals(descriptor.authoredWorldSourceOperationId())
        || !sourceEvidenceDigest.equals(descriptor.authoredWorldSourceEvidenceDigest())
        || !descriptorRequestDigest.equals(descriptor.requestDigest())
        || !descriptorResultDigest.equals(descriptor.resultDigest())
        || !canonicalVersionId.equals(attestation.canonicalVersionId())
        || !attestationDigest.equals(attestation.evidenceDigest())) {
      throw new InvalidBindingEvidenceException(
          "Persisted World complete launch binding columns differ from their closed evidence");
    }
  }

  private static void requireSourceBinding(
      String namespace,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      CompleteLaunchBindingEvidence evidence) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = evidence.descriptor();
    if (!namespace.equals(descriptor.targetNamespace())
        || !namespace.equals(sourceReceipt.targetNamespace())
        || !descriptor.canonicalTenantId().equals(sourceReceipt.canonicalTenantId())
        || !descriptor.worldSlug().equals(sourceReceipt.worldSlug())
        || !descriptor.authoredWorldSourceOperationId().equals(sourceReceipt.sourceOperationId())
        || !descriptor
            .authoredWorldSourceEvidenceDigest()
            .equals(sourceReceipt.sourceEvidenceDigest())) {
      throw new RegistrationConflictException(
          "Complete launch binding does not match the exact World source intake");
    }
  }

  private static <T> T required(Record row, Field<T> field) {
    return Objects.requireNonNull(row.get(field), "Persisted " + field.getName() + " is null");
  }

  private static String writeJson(Object value) {
    try {
      return CLOSED_JSON.writeValueAsString(value);
    } catch (Exception exception) {
      throw new InvalidBindingEvidenceException(
          "World complete launch binding evidence could not be encoded", exception);
    }
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      T value = CLOSED_JSON.readValue(json, type);
      if (!writeJson(value).equals(json)) {
        throw new IllegalArgumentException("Persisted evidence JSON is not in its closed form");
      }
      return value;
    } catch (Exception exception) {
      throw new InvalidBindingEvidenceException(
          "Persisted World complete launch binding JSON is not closed evidence", exception);
    }
  }

  private static void validateKey(
      String namespace, UUID canonicalTenantId, String controlPlaneRequestId) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("namespace must be one canonical DNS label");
    }
    if (canonicalTenantId == null || canonicalTenantId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("canonicalTenantId must be a non-nil UUID");
    }
    if (controlPlaneRequestId == null || controlPlaneRequestId.isBlank()) {
      throw new IllegalArgumentException("controlPlaneRequestId must be nonblank");
    }
  }

  private static UUID newNonNilUuid() {
    UUID value;
    do {
      value = UUID.randomUUID();
    } while (value.equals(new UUID(0L, 0L)));
    return value;
  }

  private static void requireNoActiveTransaction(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(label + " requires an independent committed owner read");
    }
  }

  private static void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World complete launch binding requires an active World owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "World complete launch binding requires a writable owner transaction");
    }
    Integer declaredIsolation =
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (declaredIsolation != null
        && declaredIsolation != java.sql.Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException(
          "World complete launch binding requires READ COMMITTED isolation");
    }
  }

  static record StoredBinding(
      UUID operationId,
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      String controlPlaneRequestId,
      UUID intakeOperationId,
      UUID intakeRequestId,
      long localTenantKey,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      String intakeReceiptDigest,
      String descriptorRequestDigest,
      String descriptorResultDigest,
      String releaseAttestationDigest,
      CompleteLaunchBindingEvidence evidence) {}

  public static final class RegistrationConflictException extends IllegalStateException {
    public RegistrationConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidBindingEvidenceException extends IllegalStateException {
    public InvalidBindingEvidenceException(String message) {
      super(message);
    }

    public InvalidBindingEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
