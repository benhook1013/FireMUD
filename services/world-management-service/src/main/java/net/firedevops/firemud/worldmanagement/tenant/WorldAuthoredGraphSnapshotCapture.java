package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService.WorldDraftDesignDigest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnerCommitProofStatus;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshotRepository.OwnerProvenance;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshotRepository.SnapshotConflictException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Internal-only World graph snapshot capture. Captured rows are representation evidence, not
 * publication eligibility or proof that World applied a complete synchronized owner commit.
 */
public class WorldAuthoredGraphSnapshotCapture {
  private static final int WORLD_DIGEST_SCHEMA_VERSION = 3;

  private static final Table<?> REVISION_LEDGER = table("world_design_revision_ledger");

  private static final Field<Long> ID = field("id", Long.class);
  private static final Field<Long> TENANT_ID = field("tenant_id", Long.class);
  private static final Field<Long> VERSION_ID = field("version_id", Long.class);

  private static final Field<String> COMMIT_ID = field("commit_id", String.class);
  private static final Field<String> REVISION_ID = field("revision_id", String.class);
  private static final Field<String> OPERATION_TYPE = field("operation_type", String.class);
  private static final Field<String> AGGREGATE_TYPE = field("aggregate_type", String.class);
  private static final Field<String> REQUESTED_AGGREGATE_ID =
      field("requested_aggregate_id", String.class);
  private static final Field<Long> APPLIED_AGGREGATE_ID = field("applied_aggregate_id", Long.class);
  private static final Field<String> RESULT = field("result", String.class);
  private static final Field<Long> AGGREGATE_EPOCH_AFTER =
      field("aggregate_epoch_after", Long.class);
  private static final Field<Long> SCOPE_EPOCH_AFTER = field("scope_epoch_after", Long.class);

  private final DSLContext dsl;
  private final WorldAuthoredGraphReader graphReader;
  private final WorldDraftDesignDigestService draftDigestService;
  private final WorldAuthoredGraphSnapshotRepository snapshotRepository;
  private final ObjectMapper objectMapper;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Injected World collaborators are internal; construction acquires no resources and this class has no finalizer.")
  public WorldAuthoredGraphSnapshotCapture(
      DSLContext dsl,
      WorldDraftDesignDigestService draftDigestService,
      WorldAuthoredGraphSnapshotRepository snapshotRepository,
      ObjectMapper objectMapper) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
    this.graphReader = new WorldAuthoredGraphReader(dsl);
    this.draftDigestService =
        Objects.requireNonNull(draftDigestService, "draftDigestService must not be null");
    this.snapshotRepository =
        Objects.requireNonNull(snapshotRepository, "snapshotRepository must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  /**
   * Captures the current six-family authored graph while holding the exact V25 owner row.
   *
   * <p>The caller owns the writable READ COMMITTED transaction. This method is deliberately unwired
   * from public publication/activation surfaces and persists every result as {@code
   * CAPTURED_UNVERIFIED}; V19 currently lacks the revision-to-complete-scope proof needed to claim
   * synchronized owner application.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public WorldAuthoredGraphSnapshot capture(CaptureRequest request) {
    Objects.requireNonNull(request, "request");
    requireWritableReadCommittedOwnerTransaction();
    OwnerProvenance provenance = snapshotRepository.lockAndResolve(request);
    String tuplesJson = encodeTuples(request.suppliedOwnedAffectedTuples());
    String captureRequestDigest = captureRequestDigest(request, provenance, tuplesJson);

    WorldAuthoredGraphSnapshot prior = snapshotRepository.findByFence(request.publicationFence());
    if (prior != null) {
      snapshotRepository.requireSameRequest(
          prior, request, provenance, captureRequestDigest, tuplesJson);
      return prior;
    }
    if (request.digestSchemaVersion() != WORLD_DIGEST_SCHEMA_VERSION) {
      throw new SnapshotConflictException("New World graph capture requires digest schema 3");
    }
    if (!"FROZEN".equals(provenance.ownerFreezePhase())) {
      throw new SnapshotConflictException(
          "World graph capture cannot create a new snapshot after its V25 fence leaves FROZEN");
    }

    String revisionEvidenceJson = captureOwnerRevisionEvidence(request, provenance);
    WorldAuthoredGraph graph =
        graphReader.readAndValidateGraph(provenance.localTenantKey(), provenance.localVersionKey());
    byte[] graphBytes = graph.encode(objectMapper);
    WorldDraftDesignDigest currentDigest =
        draftDigestService.getDraftDesignDigest(
            Long.toString(provenance.localTenantKey()),
            Long.toString(provenance.localVersionKey()));
    if (!Long.toString(provenance.localTenantKey()).equals(currentDigest.tenantId())
        || !Long.toString(provenance.localVersionKey()).equals(currentDigest.scopeValue())
        || currentDigest.digestSchemaVersion() != WORLD_DIGEST_SCHEMA_VERSION
        || !request.contentDigest().equals(currentDigest.contentDigest())) {
      throw new SnapshotConflictException(
          "World graph capture differs from the V25 frozen checkpoint under the V27 private Version key");
    }

    WorldAuthoredGraphSnapshot snapshot =
        new WorldAuthoredGraphSnapshot(
            newNonNilUuid(),
            provenance.targetNamespace(),
            provenance.canonicalTenantId(),
            provenance.canonicalVersionId(),
            provenance.versionIdentityOperationId(),
            provenance.worldSlug(),
            provenance.gameDesignVersionId(),
            provenance.localVersionKey(),
            provenance.localTenantKey(),
            (short) 1,
            provenance.intakeOperationId(),
            provenance.intakeRequestId(),
            provenance.intakeRequestDigest(),
            provenance.sourceOperationId(),
            provenance.sourceEvidenceDigest(),
            provenance.intakeReceiptDigest(),
            request.publicationFence(),
            request.publicationRequestId(),
            request.requestDigest(),
            request.versionStateEpoch(),
            request.publishWorkflowId(),
            request.appliedCommitId(),
            request.contentDigest(),
            request.digestSchemaVersion(),
            captureRequestDigest,
            tuplesJson,
            revisionEvidenceJson,
            graphBytes,
            sha256(graphBytes),
            OwnerCommitProofStatus.CAPTURED_UNVERIFIED);
    return snapshotRepository.insertAndReadback(snapshot);
  }

  private String captureOwnerRevisionEvidence(CaptureRequest request, OwnerProvenance provenance) {
    Result<? extends Record> rows =
        dsl.select(
                ID,
                COMMIT_ID,
                REVISION_ID,
                OPERATION_TYPE,
                AGGREGATE_TYPE,
                REQUESTED_AGGREGATE_ID,
                APPLIED_AGGREGATE_ID,
                RESULT,
                AGGREGATE_EPOCH_AFTER,
                SCOPE_EPOCH_AFTER)
            .from(REVISION_LEDGER)
            .where(
                TENANT_ID
                    .eq(provenance.localTenantKey())
                    .and(VERSION_ID.eq(provenance.localVersionKey()))
                    .and(COMMIT_ID.eq(request.appliedCommitId())))
            .orderBy(ID.asc())
            .fetch();
    if (rows.isEmpty()) {
      throw new WorldAuthoredGraphSnapshotRepository.MissingOwnerHistoryException(
          "World graph capture requires retained V19 revision history for the named commit");
    }
    List<Map<String, Object>> evidence = new ArrayList<>();
    for (Record row : rows) {
      if (!"APPLIED".equals(row.get(RESULT, String.class))) {
        throw new SnapshotConflictException(
            "World graph capture encountered non-APPLIED V19 revision history");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", required(row, ID));
      item.put("commitId", required(row, COMMIT_ID));
      item.put("revisionId", required(row, REVISION_ID));
      item.put("operationType", required(row, OPERATION_TYPE));
      item.put("aggregateType", required(row, AGGREGATE_TYPE));
      item.put("requestedAggregateId", required(row, REQUESTED_AGGREGATE_ID));
      item.put("appliedAggregateId", required(row, APPLIED_AGGREGATE_ID));
      item.put("result", required(row, RESULT));
      item.put("aggregateEpochAfter", required(row, AGGREGATE_EPOCH_AFTER));
      item.put("scopeEpochAfter", row.get(SCOPE_EPOCH_AFTER, Long.class));
      evidence.add(item);
    }
    return writeJson(evidence);
  }

  private String encodeTuples(List<OwnedAffectedTuple> tuples) {
    List<LinkedHashMap<String, Object>> values = new ArrayList<>();
    for (OwnedAffectedTuple tuple : tuples) {
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("owner", tuple.owner());
      item.put("aggregateType", tuple.aggregateType());
      item.put("aggregateId", tuple.aggregateId());
      item.put("scopeType", tuple.scopeType());
      item.put("scopeId", tuple.scopeId());
      item.put("expectedEpoch", tuple.expectedEpoch());
      values.add(item);
    }
    return writeJson(values);
  }

  private String captureRequestDigest(
      CaptureRequest request, OwnerProvenance provenance, String tuplesJson) {
    LinkedHashMap<String, Object> binding = new LinkedHashMap<>();
    binding.put("targetNamespace", request.targetNamespace());
    binding.put("canonicalTenantId", request.canonicalTenantId().toString());
    binding.put("canonicalVersionId", request.canonicalVersionId().toString());
    binding.put("versionIdentityOperationId", provenance.versionIdentityOperationId().toString());
    binding.put("worldSlug", provenance.worldSlug());
    binding.put("gameDesignVersionId", provenance.gameDesignVersionId());
    binding.put("localVersionKey", provenance.localVersionKey());
    binding.put("localTenantKey", provenance.localTenantKey());
    binding.put("intakeOperationId", provenance.intakeOperationId().toString());
    binding.put("intakeRequestId", request.intakeRequestId().toString());
    binding.put("intakeRequestDigest", provenance.intakeRequestDigest());
    binding.put("sourceOperationId", provenance.sourceOperationId().toString());
    binding.put("sourceEvidenceDigest", provenance.sourceEvidenceDigest());
    binding.put("intakeReceiptDigest", provenance.intakeReceiptDigest());
    binding.put("publicationFence", request.publicationFence().toString());
    binding.put("publicationRequestId", request.publicationRequestId());
    binding.put("requestDigest", request.requestDigest());
    binding.put("versionStateEpoch", request.versionStateEpoch());
    binding.put("publishWorkflowId", request.publishWorkflowId());
    binding.put("appliedCommitId", request.appliedCommitId());
    binding.put("contentDigest", request.contentDigest());
    binding.put("digestSchemaVersion", request.digestSchemaVersion());
    binding.put("suppliedOwnedAffectedTuplesJson", tuplesJson);
    return sha256(writeJsonBytes(binding));
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new SnapshotConflictException("World authored graph evidence could not be encoded");
    }
  }

  private byte[] writeJsonBytes(Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (Exception exception) {
      throw new SnapshotConflictException("World authored graph bytes could not be encoded");
    }
  }

  private <T> T required(Record row, Field<T> field) {
    T value = row.get(field, field.getType());
    if (value == null) {
      throw new SnapshotConflictException(
          "World graph contains null required field " + field.getName());
    }
    return value;
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("World graph capture requires a caller-owned transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("World graph capture requires a writable owner transaction");
    }
    Integer declaredIsolation =
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (declaredIsolation != null && declaredIsolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException("World graph capture requires READ COMMITTED isolation");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only");
    if (settings == null
        || !"read committed".equalsIgnoreCase(settings.get("transaction_isolation", String.class))
        || !"off".equalsIgnoreCase(settings.get("transaction_read_only", String.class))) {
      throw new IllegalStateException(
          "World graph capture requires a writable READ COMMITTED owner transaction");
    }
  }

  private static Table<?> table(String name) {
    return DSL.table(DSL.name(name));
  }

  private static <T> Field<T> field(String name, Class<T> type) {
    return DSL.field(DSL.name(name), type);
  }

  private static UUID newNonNilUuid() {
    UUID value;
    do {
      value = UUID.randomUUID();
    } while (value.equals(new UUID(0L, 0L)));
    return value;
  }

  static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder builder = new StringBuilder(digest.length * 2);
      for (byte current : digest) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }
}
