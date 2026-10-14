package net.firedevops.firemud.worldmanagement.tenant;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.FrozenAttempt;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal immutable correlation between a newly-created World freeze and its distinct original
 * Account publication authorization. This does not settle or release Account source participation.
 */
final class WorldSelectedDraftPublicationAuthorizationRepository {
  private static final String SELECT_BINDING =
      "SELECT publication_fence, account_operation_id, account_fence_id, "
          + "account_binding_bytes, account_binding_digest "
          + "FROM world_design_publication_account_binding WHERE publication_fence=?";
  private static final String SELECT_ATTEMPT_AND_OWNER =
      "SELECT a.*, o.owner_freeze_phase, o.current_publication_fence "
          + "FROM world_design_publication_fence_attempt a "
          + "JOIN world_design_publication_fence_owner o "
          + "ON o.target_namespace=a.target_namespace "
          + "AND o.canonical_tenant_id=a.canonical_tenant_id "
          + "AND o.local_tenant_key=a.local_tenant_key AND o.version_id=a.version_id "
          + "WHERE a.publication_fence=?";

  private final DSLContext dsl;

  WorldSelectedDraftPublicationAuthorizationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Reads the exact immutable qualification after commit. Absence means the attempt is unqualified,
   * not that current Account state may be sampled to upgrade it.
   */
  Optional<AccountPublicationAuthorizationBinding> readCommitted(FrozenAttempt attempt) {
    Objects.requireNonNull(attempt, "attempt");
    requireNoAmbientTransaction();
    requireExactAttempt(attempt, false, false);
    Record row = dsl.fetchOne(SELECT_BINDING, attempt.publicationFence());
    return row == null ? Optional.empty() : Optional.of(decodeExact(row, attempt));
  }

  /**
   * Retains or replays the original Account binding while the first-freeze owner transaction is
   * still holding its World version row lock.
   *
   * <p>{@code attemptCreatedInThisTransaction} must be set only from the actual checkpoint callback
   * invocation by {@link WorldDesignPublicationFenceRepository#claimFreeze}; absence of that signal
   * permits exact replay only and never late qualification of an old frozen attempt.
   */
  AccountPublicationAuthorizationBinding retainOrRequireExact(
      FrozenAttempt attempt,
      AccountPublicationAuthorizationBinding supplied,
      boolean attemptCreatedInThisTransaction) {
    Objects.requireNonNull(attempt, "attempt");
    Objects.requireNonNull(supplied, "supplied");
    requireWritableReadCommittedOwnerTransaction();
    requireExactAttempt(attempt, true, true);

    Record existing = dsl.fetchOne(SELECT_BINDING, attempt.publicationFence());
    if (existing != null) {
      return requireSameBinding(supplied, decodeExact(existing, attempt));
    }
    if (!attemptCreatedInThisTransaction) {
      throw conflict("Historical or concurrent World freeze has no original Account qualification");
    }

    byte[] canonicalBytes = supplied.canonicalBytes();
    String digest = DraftAuthorizationFenceBinding.digest(canonicalBytes);
    int inserted =
        dsl.execute(
            "INSERT INTO world_design_publication_account_binding "
                + "(publication_fence, account_operation_id, account_fence_id, "
                + "account_binding_bytes, account_binding_digest) VALUES (?, ?, ?, ?, ?)",
            attempt.publicationFence(),
            supplied.operationId(),
            supplied.fenceId(),
            canonicalBytes,
            digest);
    if (inserted != 1) {
      throw conflict("World publication Account binding was not retained");
    }
    Record readback = dsl.fetchOne(SELECT_BINDING, attempt.publicationFence());
    if (readback == null) {
      throw conflict("New World freeze has no Account qualification readback");
    }
    return requireSameBinding(supplied, decodeExact(readback, attempt));
  }

  private void requireExactAttempt(
      FrozenAttempt expected, boolean lockOwner, boolean requireCurrentFreeze) {
    String sql = SELECT_ATTEMPT_AND_OWNER + (lockOwner ? " FOR UPDATE OF o" : "");
    Record row = dsl.fetchOne(sql, expected.publicationFence());
    if (row == null) {
      throw conflict("World Account qualification has no exact retained publication attempt");
    }
    var request = expected.request();
    var checkpoint = expected.checkpoint();
    if (!Short.valueOf((short) 1).equals(required(row, "owner_binding_schema_version", Short.class))
        || !request.targetNamespace().equals(required(row, "target_namespace", String.class))
        || !request.canonicalTenantId().equals(required(row, "canonical_tenant_id", UUID.class))
        || !request.canonicalVersionId().equals(required(row, "canonical_version_id", UUID.class))
        || !request
            .versionIdentityOperationId()
            .equals(required(row, "version_identity_operation_id", UUID.class))
        || request.gameDesignVersionId() != required(row, "game_design_version_id", Long.class)
        || !request.intakeRequestId().equals(required(row, "intake_request_id", UUID.class))
        || !request.intakeOperationId().equals(required(row, "intake_operation_id", UUID.class))
        || !request
            .intakeRequestDigest()
            .equals(required(row, "intake_request_digest", String.class))
        || !request.sourceOperationId().equals(required(row, "source_operation_id", UUID.class))
        || !request
            .sourceEvidenceDigest()
            .equals(required(row, "source_evidence_digest", String.class))
        || !request
            .intakeReceiptDigest()
            .equals(required(row, "intake_receipt_digest", String.class))
        || !request
            .publicationRequestId()
            .equals(required(row, "publication_request_id", String.class))
        || !request.requestDigest().equals(required(row, "request_digest", String.class))
        || request.versionStateEpoch() != required(row, "version_state_epoch", Long.class)
        || !request.publishWorkflowId().equals(required(row, "publish_workflow_id", String.class))
        || !checkpoint.appliedCommitId().equals(required(row, "applied_commit_id", String.class))
        || !checkpoint.contentDigest().equals(required(row, "content_digest", String.class))
        || checkpoint.digestSchemaVersion() != required(row, "digest_schema_version", Integer.class)
        || (requireCurrentFreeze
            && (!"FROZEN".equals(required(row, "owner_freeze_phase", String.class))
                || !expected
                    .publicationFence()
                    .equals(required(row, "current_publication_fence", UUID.class))))) {
      throw conflict("World Account qualification differs from the exact retained frozen attempt");
    }
  }

  private static AccountPublicationAuthorizationBinding decodeExact(
      Record row, FrozenAttempt attempt) {
    UUID fence = required(row, "publication_fence", UUID.class);
    UUID accountOperation = required(row, "account_operation_id", UUID.class);
    UUID accountFence = required(row, "account_fence_id", UUID.class);
    byte[] bytes = required(row, "account_binding_bytes", byte[].class);
    String digest = required(row, "account_binding_digest", String.class);
    if (!attempt.publicationFence().equals(fence)
        || !DraftAuthorizationFenceBinding.digest(bytes).equals(digest)) {
      throw conflict("Stored World Account publication binding is corrupt");
    }
    final AccountPublicationAuthorizationBinding binding;
    try {
      binding = AccountPublicationAuthorizationBinding.fromStored(bytes);
    } catch (RuntimeException invalid) {
      throw new WorldDesignPublicationFenceRepository.ConflictException(
          "Stored World Account publication binding is not canonical");
    }
    if (!accountOperation.equals(binding.operationId())
        || !accountFence.equals(binding.fenceId())
        || !Arrays.equals(bytes, binding.canonicalBytes())) {
      throw conflict("Stored World Account publication binding identity differs from its bytes");
    }
    return binding;
  }

  private static AccountPublicationAuthorizationBinding requireSameBinding(
      AccountPublicationAuthorizationBinding supplied,
      AccountPublicationAuthorizationBinding stored) {
    if (!supplied.operationId().equals(stored.operationId())
        || !supplied.fenceId().equals(stored.fenceId())
        || !Arrays.equals(supplied.canonicalBytes(), stored.canonicalBytes())) {
      throw conflict("World publication retry changed its original Account authorization");
    }
    return stored;
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "World Account qualification requires a writable owner transaction");
    }
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException("World Account qualification requires READ COMMITTED");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only");
    if (settings == null
        || !"read committed".equalsIgnoreCase(settings.get("transaction_isolation", String.class))
        || !"off".equalsIgnoreCase(settings.get("transaction_read_only", String.class))) {
      throw new IllegalStateException(
          "World Account qualification requires a writable READ COMMITTED owner transaction");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "World committed Account qualification read requires no ambient transaction");
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) {
      throw conflict("World Account qualification is missing retained field " + field);
    }
    return value;
  }

  private static WorldDesignPublicationFenceRepository.ConflictException conflict(String message) {
    return new WorldDesignPublicationFenceRepository.ConflictException(message);
  }
}
