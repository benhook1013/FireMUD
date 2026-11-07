package net.firedevops.firemud.gamesession.repository;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Projects the retained Human publication's original World selection for one exact private source.
 * The caller owns the same short writable transaction used for shared-locked source assembly.
 * Neither this projection nor the original selector is current World or actor authority.
 */
public final class GameSessionPrivateWorldPreparationBuildReader {
  private final DSLContext dsl;
  private final GameSessionPrivateWorldPreparationSourceReader source;
  private final GameSessionCanonicalRealmCatalogRepository catalogs;

  public GameSessionPrivateWorldPreparationBuildReader(
      DSLContext dsl, GameSessionPrivateWorldPreparationSourceReader source) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.source = Objects.requireNonNull(source, "source");
    this.catalogs = new GameSessionCanonicalRealmCatalogRepository(dsl);
  }

  /**
   * Full selected source plus its exact retained World publication selector, without a new grant.
   */
  public record Build(Snapshot source, WorldPublishedStartLocationEvidence selectedWorld) {
    public Build {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(selectedWorld, "selectedWorld");
    }
  }

  public Build read(PrivateWorldPreparationReadGrpcCodec.Request request) {
    Objects.requireNonNull(request, "request");
    // PostgreSQL FOR SHARE requires a caller-owned transaction; fail rather than observing two
    // unrelated owner snapshots. The source receiver uses a writable transaction for shared locks.
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Private build projection requires a writable owner transaction");
    }
    Snapshot selected = source.read(request);
    var s = selected.source();
    var scope = selected.scope();
    var row =
        dsl.fetchOne(
            "SELECT complete_policy_set_evidence, fixture_policy_request_bytes "
                + "FROM game_session_canonical_realm_catalog "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ? "
                + "AND creation_request_id = ? AND catalog_revision = ? FOR SHARE",
            scope.targetNamespace(),
            scope.tenantId(),
            s.catalogRealmId(),
            s.catalogRequestId(),
            s.catalogRevision());
    if (row == null
        || row.get("complete_policy_set_evidence", byte[].class) == null
        || row.get("fixture_policy_request_bytes", byte[].class) != null) {
      throw new IllegalStateException("Exact retained Human source build is unavailable");
    }
    byte[] carrier = row.get("complete_policy_set_evidence", byte[].class);
    if (!PrivateWorldPreparationReadEvidence.sha256(carrier).equals(s.policyCarrierDigest())) {
      throw new IllegalStateException("Retained source policy carrier differs from preparation");
    }
    CanonicalRealmCatalogSnapshot catalog =
        catalogs.lockExactInitialPublicProductionForShare(
            scope.targetNamespace(),
            scope.tenantId(),
            s.catalogRealmId(),
            s.catalogRequestId(),
            s.catalogRevision());
    RealmPolicyPublishedEvidence.PublishedSet policy =
        RealmPolicyPublishedEvidenceCodec.decode(carrier);
    var intake = catalog.sourceIntakeReceipt();
    var authored = intake.source();
    if (!catalog.worldSlug().equals(scope.worldSlug())
        || catalog.realmSlug().equals(scope.realmSlug())
        || !catalog.receiptDigest().equals(s.catalogReceiptDigest())
        || !intake.operationId().equals(s.intakeOperationId())
        || !intake.receiptDigest().equals(s.intakeReceiptDigest())
        || !authored.operationId().equals(s.sourceOperationId())
        || !authored.evidenceDigest().equals(s.sourceEvidenceDigest())
        || !policy.target().canonicalTenantId().equals(scope.tenantId())
        || !policy.target().canonicalVersionId().equals(s.canonicalVersionId())
        || policy.target().sourceGameRowId() != authored.sourceGameRowId()
        || !policy.target().sourceGameTenantKey().equals(authored.sourceGameTenantKey())
        || !policy.target().sourceProvenanceKind().equals(authored.provenanceKind())
        || !policy.publishedReleaseBundleRef().equals(s.publishedReleaseBundleRef())
        || !policy.publishedReleaseBundleDigest().equals(s.publishedReleaseBundleDigest())
        || !policy.policySetDigest().equals(s.policySetDigest())) {
      throw new IllegalStateException("Retained Human build differs from exact private source");
    }
    List<RealmPolicyPublishedEvidence.Policy> targetPolicies =
        policy.policies().stream()
            .filter(
                candidate ->
                    candidate.source().policy().worldSlug().equals(scope.worldSlug())
                        && candidate.source().policy().realmSlug().equals(scope.realmSlug()))
            .toList();
    if (targetPolicies.size() != 1 || !targetPolicies.getFirst().equals(s.targetPolicy())) {
      throw new IllegalStateException("Retained Human build differs from selected private policy");
    }
    WorldPublishedStartLocationEvidence world =
        GameDesignPublicationOperationBinding.fromStored(policy.operationBytes()).world();
    if (!world.request().targetNamespace().equals(scope.targetNamespace())
        || !world.request().canonicalTenantId().equals(scope.tenantId())
        || !world.request().canonicalVersionId().equals(s.canonicalVersionId())
        || !world.request().publishWorkflowId().equals(policy.publishWorkflowId())) {
      throw new IllegalStateException("Published World selection differs from exact private build");
    }
    return new Build(selected, world);
  }
}
