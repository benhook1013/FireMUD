package net.firedevops.firemud.gamedesign.publication;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner storage only. Deliberately has no runtime bean or authenticated ingress. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal owner storage collaborator.")
public final class GameDesignPublicationOperationRepository {
  private final DSLContext dsl;

  public GameDesignPublicationOperationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  public void reserve(GameDesignPublicationOperation operation) {
    requireTransaction();
    lock(operation.tenantKey(), operation.workflowId(), operation.versionId());
    Optional<Readback> existing = read(operation.workflowId());
    if (existing.isPresent()) {
      exact(existing.get().operation(), operation);
      return;
    }
    dsl.execute(
        "INSERT INTO game_design_publication_operation "
            + "(publish_workflow_id, tenant_id, version_id, selection_digest, request_bytes) VALUES (?, ?, ?, ?, ?)",
        operation.workflowId(),
        operation.tenantKey(),
        operation.versionId(),
        operation.selectionDigest(),
        operation.canonicalBytes());
  }

  public GameDesignPublicationOperation requirePending(
      String tenant, String workflow, long version, String digest) {
    requireTransaction();
    lock(tenant, workflow, version);
    Readback result =
        read(workflow)
            .orElseThrow(() -> new IllegalStateException("PUBLICATION_OPERATION_UNAVAILABLE"));
    var operation = result.operation();
    if (!operation.tenantKey().equals(tenant)
        || operation.versionId() != version
        || !operation.selectionDigest().equals(digest)
        || !result.outcome().equals("PENDING")) {
      throw new IllegalStateException("PUBLICATION_OPERATION_SEALED_OR_CHANGED");
    }
    return operation;
  }

  public void seal(String tenant, String workflow, long version, String digest, boolean published) {
    GameDesignPublicationOperation operation = requirePending(tenant, workflow, version, digest);
    Record release =
        dsl.fetchOne(
            "SELECT to_jsonb(r)::TEXT AS evidence FROM published_release_bundle r WHERE tenant_id = ? AND version_id = ?",
            tenant,
            version);
    String evidence = release == null ? null : release.get("evidence", String.class);
    if (published != (evidence != null))
      throw new IllegalStateException("PUBLICATION_READBACK_UNRESOLVED");
    String outcome = published ? "PUBLISHED" : "NO_PUBLICATION";
    byte[] result = receipt(operation, outcome, evidence);
    int changed =
        dsl.execute(
            "UPDATE game_design_publication_operation SET outcome = ?, result_bytes = ?, release_row_json = ?, revision = revision + 1 WHERE publish_workflow_id = ? AND outcome = 'PENDING' AND revision = 1",
            outcome,
            result,
            evidence,
            workflow);
    if (changed != 1) throw new IllegalStateException("PUBLICATION_OPERATION_CAS_CONFLICT");
  }

  public Optional<Readback> read(String workflow) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_design_publication_operation WHERE publish_workflow_id = ?",
            workflow);
    if (row == null) return Optional.empty();
    var operation =
        GameDesignPublicationOperation.fromStored(row.get("request_bytes", byte[].class));
    String outcome = row.get("outcome", String.class);
    byte[] bytes = row.get("result_bytes", byte[].class);
    String evidence = row.get("release_row_json", String.class);
    if (!operation.workflowId().equals(workflow)
        || !operation.tenantKey().equals(row.get("tenant_id", String.class))
        || operation.versionId() != row.get("version_id", Long.class)
        || !operation.selectionDigest().equals(row.get("selection_digest", String.class))) {
      throw new IllegalStateException("PUBLICATION_OPERATION_STORAGE_CONFLICT");
    }
    if (!"PENDING".equals(outcome)) {
      if (!Arrays.equals(bytes, receipt(operation, outcome, evidence)))
        throw new IllegalStateException("PUBLICATION_RECEIPT_CORRUPT");
      Record release =
          dsl.fetchOne(
              "SELECT to_jsonb(r)::TEXT AS evidence FROM published_release_bundle r WHERE tenant_id = ? AND version_id = ?",
              operation.tenantKey(),
              operation.versionId());
      String actual = release == null ? null : release.get("evidence", String.class);
      if (!Objects.equals(actual, evidence) || ("PUBLISHED".equals(outcome) != (actual != null))) {
        throw new IllegalStateException("PUBLICATION_RECEIPT_BACKING_CONFLICT");
      }
      Record attempt =
          dsl.fetchOne(
              "SELECT status FROM publish_attempt WHERE publish_workflow_id = ?", workflow);
      if (attempt == null) {
        throw new IllegalStateException("PUBLICATION_RECEIPT_ATTEMPT_CONFLICT");
      }
      String attemptStatus = attempt.get("status", String.class);
      if (!("PUBLISHED".equals(outcome) ? "SUCCEEDED" : "FAILED").equals(attemptStatus)) {
        throw new IllegalStateException("PUBLICATION_RECEIPT_ATTEMPT_CONFLICT");
      }
    }
    return Optional.of(new Readback(operation, outcome, bytes));
  }

  private void lock(String tenant, String workflow, long version) {
    if (dsl.fetchOne("SELECT id FROM game WHERE tenant_id = ? FOR UPDATE", tenant) == null
        || dsl.fetchOne(
                "SELECT id FROM publish_attempt WHERE tenant_id = ? AND publish_workflow_id = ? AND version_id = ? FOR UPDATE",
                tenant,
                workflow,
                version)
            == null
        || dsl.fetchOne(
                "SELECT id FROM version WHERE tenant_id = ? AND id = ? FOR UPDATE", tenant, version)
            == null) {
      throw new IllegalStateException("PUBLICATION_OWNER_IDENTITY_UNAVAILABLE");
    }
  }

  public static void exact(
      GameDesignPublicationOperation retained, GameDesignPublicationOperation requested) {
    if (!Arrays.equals(retained.canonicalBytes(), requested.canonicalBytes()))
      throw new IllegalArgumentException("PUBLICATION_OPERATION_IDENTITY_CONFLICT");
  }

  private static byte[] receipt(
      GameDesignPublicationOperation operation, String outcome, String evidence) {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, "game-design-publication-owner-readback/v1");
    DraftAuthorizationFenceBinding.frame(out, operation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, outcome);
    DraftAuthorizationFenceBinding.frame(out, evidence == null ? "" : evidence);
    return out.toByteArray();
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Publication owner transaction required");
  }

  public record Readback(
      GameDesignPublicationOperation operation, String outcome, byte[] receiptBytes) {
    public Readback {
      receiptBytes = receiptBytes == null ? null : receiptBytes.clone();
    }

    @Override
    public byte[] receiptBytes() {
      return receiptBytes == null ? null : receiptBytes.clone();
    }
  }
}
