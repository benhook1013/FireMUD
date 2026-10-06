package net.firedevops.firemud.worldmanagement.tenant;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-local immutable terminal receipt and exact FROZEN-to-terminal phase transition. */
public final class WorldPublicationTerminalRepository {
  private static final String TERMINAL_WITH_OWNER_READ =
      "SELECT t.*, o.owner_freeze_phase AS joined_owner_phase, "
          + "o.current_publication_fence AS joined_owner_current_fence, "
          + "a.owner_binding_schema_version AS joined_owner_binding_schema_version, "
          + "a.target_namespace AS joined_attempt_target_namespace, "
          + "a.canonical_tenant_id AS joined_attempt_canonical_tenant_id, "
          + "a.canonical_version_id AS joined_attempt_canonical_version_id, "
          + "a.local_tenant_key AS joined_attempt_local_tenant_key, "
          + "a.version_id AS joined_attempt_local_version_id, "
          + "a.intake_request_id AS joined_attempt_intake_request_id, "
          + "a.publication_request_id AS joined_attempt_publication_request_id, "
          + "a.request_digest AS joined_attempt_request_digest, "
          + "a.version_state_epoch AS joined_attempt_version_state_epoch, "
          + "a.publish_workflow_id AS joined_attempt_publish_workflow_id, "
          + "a.applied_commit_id AS joined_attempt_applied_commit_id, "
          + "a.content_digest AS joined_attempt_content_digest, "
          + "a.digest_schema_version AS joined_attempt_digest_schema_version "
          + "FROM world_design_publication_terminal t "
          + "LEFT JOIN world_design_publication_fence_attempt a "
          + "ON a.publication_fence=t.publication_fence "
          + "LEFT JOIN world_design_publication_fence_owner o "
          + "ON o.target_namespace=a.target_namespace "
          + "AND o.canonical_tenant_id=a.canonical_tenant_id "
          + "AND o.local_tenant_key=a.local_tenant_key AND o.version_id=a.version_id "
          + "WHERE t.publication_fence=?";

  private final DSLContext dsl;
  private final TransactionTemplate ownerTransaction;

  public WorldPublicationTerminalRepository(
      DSLContext dsl, PlatformTransactionManager transactionManager) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Persists one exact terminal result under the original V25 owner lock. The supplied authority
   * was acquired before this call and remains held until the real owner transaction commits.
   */
  public GameDesignPublicationTerminalEvidence complete(
      WorldPublicationTerminal.Request request,
      WorldPublicationTerminalService.HeldTerminalAuthority authority) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(authority, "authority");
    requireNoAmbientTransaction("World publication terminal");
    GameDesignPublicationTerminalEvidence committed =
        Objects.requireNonNull(
            ownerTransaction.execute(
                status -> {
                  requireWritableReadCommittedTransaction();
                  authority.requireHeld();
                  Record owner = lockOriginalOwner(request);
                  Optional<GameDesignPublicationTerminalEvidence> prior =
                      readInOwnerTransaction(request);
                  if (prior.isPresent()) return prior.orElseThrow();
                  if (!"FROZEN".equals(required(owner, "owner_freeze_phase", String.class))) {
                    throw new PublicationTerminalConflictException(
                        "World publication fence is no longer open for its first terminal result");
                  }

                  insertTerminal(request);
                  String phase = request.ownerPhase();
                  int updated =
                      dsl.execute(
                          "UPDATE world_design_publication_fence_owner SET owner_freeze_phase=? "
                              + "WHERE target_namespace=? AND canonical_tenant_id=? "
                              + "AND local_tenant_key=? AND version_id=? "
                              + "AND owner_freeze_phase='FROZEN' AND current_publication_fence=?",
                          phase,
                          request.worldEvidence().request().targetNamespace(),
                          request.worldEvidence().request().canonicalTenantId(),
                          required(owner, "local_tenant_key", Long.class),
                          required(owner, "version_id", Long.class),
                          request.worldEvidence().request().publicationFence());
                  if (updated != 1) {
                    throw new PublicationTerminalConflictException(
                        "World publication terminal lost the exact locked FROZEN owner row");
                  }
                  authority.requireHeld();
                  GameDesignPublicationTerminalEvidence readback =
                      readInOwnerTransaction(request)
                          .orElseThrow(
                              () ->
                                  new PublicationTerminalConflictException(
                                      "World publication terminal has no exact immutable readback"));
                  if (!Arrays.equals(readback.canonicalBytes(), request.terminalBytes())) {
                    throw new PublicationTerminalConflictException(
                        "World publication terminal differs from exact inserted evidence");
                  }
                  return readback;
                }),
            "World publication terminal transaction did not commit");

    // Confirm committed immutable readback after TransactionTemplate returns from commit.
    GameDesignPublicationTerminalEvidence readback =
        readCommitted(request)
            .orElseThrow(
                () ->
                    new PublicationTerminalConflictException(
                        "Committed World publication terminal has no independent readback"));
    if (!Arrays.equals(committed.canonicalBytes(), readback.canonicalBytes())) {
      throw new PublicationTerminalConflictException(
          "Committed World publication terminal differs from its owner transaction readback");
    }
    return readback;
  }

  /** Independent immutable read. Unknown is not interpreted as publication or abort. */
  public Optional<GameDesignPublicationTerminalEvidence> readCommitted(
      WorldPublicationTerminal.Request request) {
    Objects.requireNonNull(request, "request");
    requireNoAmbientTransaction("World publication terminal readback");
    Record row = readTerminalWithOwner(request, false);
    if (row == null) return Optional.empty();
    return Optional.of(exactReadback(request, row));
  }

  private Record lockOriginalOwner(WorldPublicationTerminal.Request request) {
    var world = request.worldEvidence().request();
    Record row =
        dsl.fetchOne(
            "SELECT a.*,o.owner_freeze_phase,o.current_publication_fence "
                + "FROM world_design_publication_fence_attempt a "
                + "JOIN world_design_publication_fence_owner o "
                + "ON o.target_namespace=a.target_namespace "
                + "AND o.canonical_tenant_id=a.canonical_tenant_id "
                + "AND o.local_tenant_key=a.local_tenant_key AND o.version_id=a.version_id "
                + "WHERE a.publication_fence=? AND a.target_namespace=? "
                + "AND a.canonical_tenant_id=? AND a.canonical_version_id=? "
                + "AND o.current_publication_fence=a.publication_fence FOR UPDATE OF o",
            world.publicationFence(),
            world.targetNamespace(),
            world.canonicalTenantId(),
            world.canonicalVersionId());
    if (row == null) {
      throw new PublicationTerminalConflictException(
          "World terminal request has no exact retained V25 attempt and current owner association");
    }
    if (!Short.valueOf((short) 1).equals(required(row, "owner_binding_schema_version", Short.class))
        || !world.intakeRequestId().equals(required(row, "intake_request_id", java.util.UUID.class))
        || !world
            .publicationFence()
            .equals(required(row, "publication_fence", java.util.UUID.class))
        || !world
            .publicationRequestId()
            .equals(required(row, "publication_request_id", String.class))
        || !world.requestDigest().equals(required(row, "request_digest", String.class))
        || world.versionStateEpoch() != required(row, "version_state_epoch", Long.class)
        || !world.publishWorkflowId().equals(required(row, "publish_workflow_id", String.class))
        || !world.appliedCommitId().equals(required(row, "applied_commit_id", String.class))
        || !world.contentDigest().equals(required(row, "content_digest", String.class))
        || world.digestSchemaVersion() != required(row, "digest_schema_version", Integer.class)) {
      throw new PublicationTerminalConflictException(
          "World terminal evidence differs from its exact immutable V25 publication checkpoint");
    }
    return row;
  }

  private void insertTerminal(WorldPublicationTerminal.Request request) {
    var world = request.worldEvidence().request();
    GameDesignPublicationTerminalEvidence evidence = request.evidence();
    dsl.execute(
        "INSERT INTO world_design_publication_terminal (publication_fence,target_namespace,"
            + "canonical_tenant_id,canonical_version_id,publication_request_id,request_digest,"
            + "publish_workflow_id,freeze_version_state_epoch,applied_commit_id,content_digest,"
            + "digest_schema_version,outcome,publication_version_state_epoch,"
            + "published_release_bundle_ref,published_release_bundle_digest,operation_bytes,"
            + "terminal_evidence_bytes,terminal_evidence_digest,world_evidence_bytes,world_request_json,"
            + "selector_receipt_bytes,original_account_binding_bytes,applied_result_bytes,release_content_bytes) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        world.publicationFence(),
        world.targetNamespace(),
        world.canonicalTenantId(),
        world.canonicalVersionId(),
        world.publicationRequestId(),
        world.requestDigest(),
        world.publishWorkflowId(),
        world.versionStateEpoch(),
        world.appliedCommitId(),
        world.contentDigest(),
        world.digestSchemaVersion(),
        evidence.outcome().name(),
        request.publicationVersionStateEpoch(),
        request.releaseBundleRef(),
        request.releaseBundleDigest(),
        request.operationBytes(),
        request.terminalBytes(),
        digest(request.terminalBytes()),
        request.worldEvidenceBytes(),
        request.worldRequestJson(),
        request.selectorReceiptBytes(),
        request.originalAccountBindingBytes(),
        request.appliedResultBytes(),
        request.releaseContentBytes());
  }

  private Optional<GameDesignPublicationTerminalEvidence> readInOwnerTransaction(
      WorldPublicationTerminal.Request request) {
    Record row = readTerminalWithOwner(request, true);
    if (row == null) return Optional.empty();
    return Optional.of(exactReadback(request, row));
  }

  private Record readTerminalWithOwner(WorldPublicationTerminal.Request request, boolean lock) {
    return dsl.fetchOne(
        TERMINAL_WITH_OWNER_READ + (lock ? " FOR UPDATE OF t" : ""),
        request.worldEvidence().request().publicationFence());
  }

  private GameDesignPublicationTerminalEvidence exactReadback(
      WorldPublicationTerminal.Request request, Record row) {
    byte[] stored = required(row, "terminal_evidence_bytes", byte[].class);
    if (!request.exactBytes(stored)) {
      throw new PublicationTerminalConflictException(
          "World publication fence was reused with changed complete terminal evidence");
    }
    var world = request.worldEvidence().request();
    if (!request.ownerPhase().equals(required(row, "joined_owner_phase", String.class))
        || !world
            .publicationFence()
            .equals(required(row, "joined_owner_current_fence", java.util.UUID.class))
        || !Short.valueOf((short) 1)
            .equals(required(row, "joined_owner_binding_schema_version", Short.class))
        || !world
            .targetNamespace()
            .equals(required(row, "joined_attempt_target_namespace", String.class))
        || !world
            .canonicalTenantId()
            .equals(required(row, "joined_attempt_canonical_tenant_id", java.util.UUID.class))
        || !world
            .canonicalVersionId()
            .equals(required(row, "joined_attempt_canonical_version_id", java.util.UUID.class))
        || required(row, "joined_attempt_local_tenant_key", Long.class) <= 0L
        || required(row, "joined_attempt_local_version_id", Long.class) <= 0L
        || !world
            .intakeRequestId()
            .equals(required(row, "joined_attempt_intake_request_id", java.util.UUID.class))
        || !world
            .publicationRequestId()
            .equals(required(row, "joined_attempt_publication_request_id", String.class))
        || !world
            .requestDigest()
            .equals(required(row, "joined_attempt_request_digest", String.class))
        || world.versionStateEpoch()
            != required(row, "joined_attempt_version_state_epoch", Long.class)
        || !world
            .publishWorkflowId()
            .equals(required(row, "joined_attempt_publish_workflow_id", String.class))
        || !world
            .appliedCommitId()
            .equals(required(row, "joined_attempt_applied_commit_id", String.class))
        || !world
            .contentDigest()
            .equals(required(row, "joined_attempt_content_digest", String.class))
        || world.digestSchemaVersion()
            != required(row, "joined_attempt_digest_schema_version", Integer.class)
        || !digest(stored).equals(required(row, "terminal_evidence_digest", String.class))
        || !world
            .publicationFence()
            .equals(required(row, "publication_fence", java.util.UUID.class))
        || !world.targetNamespace().equals(required(row, "target_namespace", String.class))
        || !world
            .canonicalTenantId()
            .equals(required(row, "canonical_tenant_id", java.util.UUID.class))
        || !world
            .canonicalVersionId()
            .equals(required(row, "canonical_version_id", java.util.UUID.class))
        || !world
            .publicationRequestId()
            .equals(required(row, "publication_request_id", String.class))
        || !world.requestDigest().equals(required(row, "request_digest", String.class))
        || !world.publishWorkflowId().equals(required(row, "publish_workflow_id", String.class))
        || world.versionStateEpoch() != required(row, "freeze_version_state_epoch", Long.class)
        || !world.appliedCommitId().equals(required(row, "applied_commit_id", String.class))
        || !world.contentDigest().equals(required(row, "content_digest", String.class))
        || world.digestSchemaVersion() != required(row, "digest_schema_version", Integer.class)
        || !request.evidence().outcome().name().equals(required(row, "outcome", String.class))
        || !Objects.equals(
            request.publicationVersionStateEpoch(),
            row.get("publication_version_state_epoch", Long.class))
        || !Objects.equals(
            request.releaseBundleRef(), row.get("published_release_bundle_ref", String.class))
        || !Objects.equals(
            request.releaseBundleDigest(), row.get("published_release_bundle_digest", String.class))
        || !Arrays.equals(request.operationBytes(), required(row, "operation_bytes", byte[].class))
        || !Arrays.equals(
            request.worldEvidenceBytes(), required(row, "world_evidence_bytes", byte[].class))
        || !request.worldRequestJson().equals(required(row, "world_request_json", String.class))
        || !Arrays.equals(
            request.selectorReceiptBytes(), required(row, "selector_receipt_bytes", byte[].class))
        || !Arrays.equals(
            request.originalAccountBindingBytes(),
            required(row, "original_account_binding_bytes", byte[].class))
        || !Arrays.equals(
            request.appliedResultBytes(), required(row, "applied_result_bytes", byte[].class))
        || !Arrays.equals(
            request.releaseContentBytes(), required(row, "release_content_bytes", byte[].class))) {
      throw new InvalidPublicationTerminalEvidenceException(
          "Retained World publication terminal columns differ from their canonical producer evidence");
    }
    return GameDesignPublicationTerminalEvidence.fromStored(stored);
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static <T> T required(Record record, String field, Class<T> type) {
    T value = record.get(field, type);
    if (value == null) throw new InvalidPublicationTerminalEvidenceException("Missing " + field);
    return value;
  }

  private static void requireNoAmbientTransaction(String operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(operation + " requires no caller transaction");
    }
  }

  private void requireWritableReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "World publication terminal requires a writable owner transaction");
    }
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException("World publication terminal requires READ COMMITTED");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS isolation, "
                + "current_setting('transaction_read_only') AS read_only");
    if (settings == null
        || !"read committed".equalsIgnoreCase(required(settings, "isolation", String.class))
        || !"off".equalsIgnoreCase(required(settings, "read_only", String.class))) {
      throw new IllegalStateException(
          "World publication terminal requires writable READ COMMITTED");
    }
  }

  public static class PublicationTerminalConflictException extends IllegalStateException {
    public PublicationTerminalConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidPublicationTerminalEvidenceException
      extends PublicationTerminalConflictException {
    public InvalidPublicationTerminalEvidenceException(String message) {
      super(message);
    }
  }
}
