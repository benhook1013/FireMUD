package net.firedevops.firemud.gamedesign.publication;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Internal immutable policy association; all writes join the actual Game Design seal transaction.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal owner storage collaborator.")
public final class RealmPolicyPublicationRepository {
  private static final int MAX_POLICY_ROWS = RealmPolicySource.MAX_POLICIES;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final DSLContext dsl;

  public RealmPolicyPublicationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /**
   * Retains an association only from the operation's actual sealed PUBLISHED owner readback. A
   * release with no policy source/capture remains a valid unrelated release and creates no row.
   */
  public Optional<RealmPolicyPublishedEvidence.PublishedSet> retainSealedPublished(
      String publishWorkflowId) {
    requireWrite();
    if (publishWorkflowId == null || publishWorkflowId.isBlank()) {
      throw new IllegalArgumentException("Exact publication workflow required");
    }
    var operationReadback =
        new GameDesignPublicationOperationRepository(dsl)
            .read(publishWorkflowId)
            .orElseThrow(() -> new IllegalStateException("PUBLICATION_OPERATION_UNAVAILABLE"));
    if (!"PUBLISHED".equals(operationReadback.outcome())
        || operationReadback.terminalEvidenceBytes() == null) {
      throw new IllegalStateException("POLICY_PUBLICATION_REQUIRES_SEALED_PUBLISHED_OPERATION");
    }
    var operation = operationReadback.operation();
    var terminal =
        GameDesignPublicationTerminalEvidence.fromStored(operationReadback.terminalEvidenceBytes());
    if (!operation.workflowId().equals(publishWorkflowId)
        || terminal.outcome() != GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
        || !Arrays.equals(terminal.operationBytes(), operation.canonicalBytes())) {
      throw new IllegalStateException("POLICY_PUBLICATION_TERMINAL_BINDING_CONFLICT");
    }
    TargetProof target = operation.account().input().selection().target();
    var sourceRepository = new RealmPolicySourceRepository(dsl);
    Optional<RealmPolicySnapshot.Capture> captureResult = sourceRepository.readCapture(operation);
    if (captureResult.isEmpty()) {
      if (sourceExists(target) || selectedCommitDeclaresRealmPolicy(operation)) {
        throw new IllegalStateException("POLICY_PUBLICATION_CAPTURE_UNAVAILABLE");
      }
      return Optional.empty();
    }
    var capture = captureResult.orElseThrow();
    if (!capture.operation().equals(operation)
        || !capture.snapshot().binding().target().equals(target)) {
      throw new IllegalStateException("POLICY_PUBLICATION_CAPTURE_BINDING_CONFLICT");
    }

    var existing =
        dsl.fetchOne(
            "SELECT * FROM game_design_published_realm_policy_set "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (existing != null) {
      var retained = readPublishedSet(target, existing).orElseThrow();
      requireExactRetry(retained, operation, capture, terminal);
      return Optional.of(retained);
    }

    requireOwner(target, terminal, true);
    requireCurrentVisibleSource(target, capture.snapshot());
    requireActualRelease(operation, terminal);

    var release = terminal.releaseContent();
    var rows = new ArrayList<RealmPolicyPublishedEvidence.Policy>();
    for (RealmPolicySource.Policy source : capture.snapshot().policies()) {
      UUID id = UUID.randomUUID();
      rows.add(
          new RealmPolicyPublishedEvidence.Policy(
              id,
              source,
              RealmPolicyPublishedEvidence.policyDigest(
                  id,
                  target,
                  release.versionNumber(),
                  release.publishedReleaseBundleRef(),
                  terminal.publishedReleaseBundleDigest(),
                  release.publishWorkflowId(),
                  release.manifestHash(),
                  source)));
    }
    List<RealmPolicySource.Policy> orderedSources =
        RealmPolicySource.ordered(
            rows.stream().map(RealmPolicyPublishedEvidence.Policy::source).toList());
    if (!orderedSources.equals(capture.snapshot().policies())
        || rows.size() != capture.snapshot().policies().size()) {
      throw new IllegalStateException("POLICY_PUBLICATION_SOURCE_SET_CHANGED");
    }
    String setDigest =
        RealmPolicyPublishedEvidence.computePolicySetDigest(
            target,
            release.versionNumber(),
            capture.snapshot().binding().commitId(),
            capture.snapshot().sourceEpoch(),
            release.publishedReleaseBundleRef(),
            terminal.publishedReleaseBundleDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operation.canonicalBytes(),
            capture.canonicalBytes(),
            terminal.canonicalBytes(),
            rows);

    dsl.execute(
        "INSERT INTO game_design_published_realm_policy_set "
            + "(canonical_tenant_id, canonical_version_id, game_design_version_row_id, version_number, "
            + "source_commit_id, source_epoch, target_proof_json, operation_bytes, capture_bytes, "
            + "terminal_evidence_bytes, release_content_bytes, published_release_bundle_ref, "
            + "published_release_bundle_digest, publish_workflow_id, manifest_hash, "
            + "publication_version_state_epoch, policy_count, policy_set_digest, sealed) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        target.gameDesignVersionRowId(),
        release.versionNumber(),
        capture.snapshot().binding().commitId(),
        capture.snapshot().sourceEpoch(),
        RealmPolicyPublishedEvidence.targetProofJson(target),
        operation.canonicalBytes(),
        capture.canonicalBytes(),
        terminal.canonicalBytes(),
        release.canonicalBytes(),
        release.publishedReleaseBundleRef(),
        terminal.publishedReleaseBundleDigest(),
        release.publishWorkflowId(),
        release.manifestHash(),
        terminal.publicationVersionStateEpoch(),
        rows.size(),
        setDigest);

    for (int ordinal = 0; ordinal < rows.size(); ordinal++) {
      var row = rows.get(ordinal);
      var source = row.source();
      var policy = source.policy();
      dsl.execute(
          "INSERT INTO game_design_published_realm_policy "
              + "(canonical_tenant_id, canonical_version_id, ordinal, realm_policy_id, "
              + "source_commit_id, source_revision_id, logical_revision_id, world_slug, realm_slug, "
              + "policy_json, policy_digest, visible, public_production) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          target.canonicalTenantId(),
          target.canonicalVersionId(),
          ordinal,
          row.policyId(),
          source.commitId(),
          source.revisionId(),
          source.logicalRevisionId(),
          policy.worldSlug(),
          policy.realmSlug(),
          policy.canonicalJson(),
          row.policyDigest(),
          policy.visible(),
          policy.publicProduction());
    }
    int sealed =
        dsl.execute(
            "UPDATE game_design_published_realm_policy_set SET sealed = TRUE "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND sealed = FALSE",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (sealed != 1) throw new IllegalStateException("POLICY_PUBLICATION_SET_SEAL_CONFLICT");

    var stored =
        dsl.fetchOne(
            "SELECT * FROM game_design_published_realm_policy_set "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (stored == null) {
      throw new IllegalStateException("POLICY_PUBLICATION_SET_READBACK_UNAVAILABLE");
    }
    var readback = readPublishedSet(target, stored).orElseThrow();
    requireExactRetry(readback, operation, capture, terminal);
    return Optional.of(readback);
  }

  /**
   * Caller supplies a REPEATABLE_READ snapshot; no caller state substitutes for stored evidence.
   */
  public Optional<RealmPolicyPublishedEvidence.PublishedSet> readPublishedSet(
      UUID canonicalTenantId, UUID canonicalVersionId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Canonical policy target resolution requires one read-only REPEATABLE_READ snapshot");
    }
    var version =
        // Exact read uses the transaction-bound search path, not the migration-only lock API.
        new VersionRepository(dsl, null)
            .findByCanonicalTenantIdAndCanonicalVersionId(canonicalTenantId, canonicalVersionId);
    if (version.isEmpty()) return Optional.empty();
    var owner = version.orElseThrow();
    return readPublishedSet(
        new TargetProof(
            owner.getCanonicalTenantId(),
            owner.getCanonicalVersionId(),
            owner.getId(),
            owner.getTenantId(),
            owner.getIdentitySourceGameRowId(),
            owner.getIdentitySourceGameTenantKey(),
            owner.getIdentitySourceProvenanceKind()));
  }

  /** Exact internal target read; the service owns its independent snapshot boundary. */
  public Optional<RealmPolicyPublishedEvidence.PublishedSet> readPublishedSet(TargetProof target) {
    Objects.requireNonNull(target, "target");
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_published_realm_policy_set "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    return row == null ? Optional.empty() : readPublishedSet(target, row);
  }

  private Optional<RealmPolicyPublishedEvidence.PublishedSet> readPublishedSet(
      TargetProof target, Record header) {
    if (!Boolean.TRUE.equals(header.get("sealed", Boolean.class))) {
      throw new IllegalStateException("POLICY_PUBLICATION_SET_INCOMPLETE");
    }
    if (!Objects.equals(
            header.get("target_proof_json", String.class),
            RealmPolicyPublishedEvidence.targetProofJson(target))
        || !Objects.equals(
            header.get("canonical_tenant_id", UUID.class), target.canonicalTenantId())
        || !Objects.equals(
            header.get("canonical_version_id", UUID.class), target.canonicalVersionId())
        || !Objects.equals(
            header.get("game_design_version_row_id", Long.class),
            target.gameDesignVersionRowId())) {
      throw new IllegalStateException("POLICY_PUBLICATION_TARGET_PROOF_CONFLICT");
    }

    byte[] operationBytes = header.get("operation_bytes", byte[].class);
    var operation = GameDesignPublicationOperation.fromStored(operationBytes);
    if (!operation.account().input().selection().target().equals(target)) {
      throw new IllegalStateException("POLICY_PUBLICATION_OPERATION_TARGET_CONFLICT");
    }
    var operationReadback =
        new GameDesignPublicationOperationRepository(dsl)
            .read(operation.workflowId())
            .orElseThrow(() -> new IllegalStateException("PUBLICATION_OPERATION_UNAVAILABLE"));
    if (!"PUBLISHED".equals(operationReadback.outcome())
        || !Arrays.equals(operationReadback.operation().canonicalBytes(), operationBytes)) {
      throw new IllegalStateException("POLICY_PUBLICATION_OPERATION_NOT_SEALED");
    }
    byte[] terminalBytes = operationReadback.terminalEvidenceBytes();
    byte[] retainedTerminalBytes = header.get("terminal_evidence_bytes", byte[].class);
    if (terminalBytes == null || !Arrays.equals(terminalBytes, retainedTerminalBytes)) {
      throw new IllegalStateException("POLICY_PUBLICATION_TERMINAL_CHANGED");
    }
    var terminal = GameDesignPublicationTerminalEvidence.fromStored(terminalBytes);
    var release = terminal.releaseContent();
    if (!Arrays.equals(release.canonicalBytes(), header.get("release_content_bytes", byte[].class))
        || !Objects.equals(
            release.publishedReleaseBundleRef(),
            header.get("published_release_bundle_ref", String.class))
        || !Objects.equals(
            terminal.publishedReleaseBundleDigest(),
            header.get("published_release_bundle_digest", String.class))
        || !Objects.equals(
            release.publishWorkflowId(), header.get("publish_workflow_id", String.class))
        || !Objects.equals(release.manifestHash(), header.get("manifest_hash", String.class))
        || !Objects.equals(
            terminal.publicationVersionStateEpoch(),
            header.get("publication_version_state_epoch", Long.class))) {
      throw new IllegalStateException("POLICY_PUBLICATION_RELEASE_CONTENT_CHANGED");
    }

    Record version = requireOwner(target, terminal, false);
    int versionNumber = header.get("version_number", Integer.class);
    if (release.versionNumber() != versionNumber
        || version.get("version_number", Integer.class) != versionNumber) {
      throw new IllegalStateException("POLICY_PUBLICATION_VERSION_NUMBER_CHANGED");
    }
    var sourceRepository = new RealmPolicySourceRepository(dsl);
    var capture =
        sourceRepository
            .readCapture(operation)
            .orElseThrow(() -> new IllegalStateException("POLICY_PUBLICATION_CAPTURE_UNAVAILABLE"));
    byte[] captureBytes = header.get("capture_bytes", byte[].class);
    if (!Arrays.equals(capture.canonicalBytes(), captureBytes)
        || !capture.snapshot().binding().target().equals(target)
        || !Objects.equals(
            capture.snapshot().binding().commitId(), header.get("source_commit_id", UUID.class))
        || !Objects.equals(
            capture.snapshot().sourceEpoch(), header.get("source_epoch", String.class))) {
      throw new IllegalStateException("POLICY_PUBLICATION_CAPTURE_CHANGED");
    }
    requireCurrentVisibleSource(target, capture.snapshot());

    int expectedCount = header.get("policy_count", Integer.class);
    if (expectedCount < 1 || expectedCount > MAX_POLICY_ROWS) {
      throw new IllegalStateException("POLICY_PUBLICATION_SET_OVERSIZED_OR_EMPTY");
    }
    List<Record> storedRows =
        dsl.fetch(
            "SELECT * FROM game_design_published_realm_policy "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "ORDER BY ordinal LIMIT ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            MAX_POLICY_ROWS + 1);
    if (storedRows.size() != expectedCount || storedRows.size() > MAX_POLICY_ROWS) {
      throw new IllegalStateException("POLICY_PUBLICATION_SET_CARDINALITY_CONFLICT");
    }
    List<RealmPolicyPublishedEvidence.Policy> policies = new ArrayList<>();
    for (int ordinal = 0; ordinal < storedRows.size(); ordinal++) {
      Record row = storedRows.get(ordinal);
      RealmPolicySource.Policy source = sourcePolicy(row);
      if (!Objects.equals(row.get("ordinal", Integer.class), ordinal)) {
        throw new IllegalStateException("POLICY_PUBLICATION_SET_ORDER_CONFLICT");
      }
      policies.add(
          new RealmPolicyPublishedEvidence.Policy(
              row.get("realm_policy_id", UUID.class),
              source,
              row.get("policy_digest", String.class)));
    }
    List<RealmPolicySource.Policy> expectedPolicies = capture.snapshot().policies();
    if (!expectedPolicies.equals(
        policies.stream().map(RealmPolicyPublishedEvidence.Policy::source).toList())) {
      throw new IllegalStateException("POLICY_PUBLICATION_SOURCE_ROWS_CHANGED");
    }
    for (var policy : policies) {
      String digest =
          RealmPolicyPublishedEvidence.policyDigest(
              policy.policyId(),
              target,
              versionNumber,
              release.publishedReleaseBundleRef(),
              terminal.publishedReleaseBundleDigest(),
              release.publishWorkflowId(),
              release.manifestHash(),
              policy.source());
      if (!digest.equals(policy.policyDigest())) {
        throw new IllegalStateException("POLICY_PUBLICATION_POLICY_DIGEST_CONFLICT");
      }
    }
    String setDigest =
        RealmPolicyPublishedEvidence.computePolicySetDigest(
            target,
            versionNumber,
            capture.snapshot().binding().commitId(),
            capture.snapshot().sourceEpoch(),
            release.publishedReleaseBundleRef(),
            terminal.publishedReleaseBundleDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operationBytes,
            captureBytes,
            terminalBytes,
            policies);
    if (!setDigest.equals(header.get("policy_set_digest", String.class))) {
      throw new IllegalStateException("POLICY_PUBLICATION_SET_DIGEST_CONFLICT");
    }
    return Optional.of(
        new RealmPolicyPublishedEvidence.PublishedSet(
            target,
            versionNumber,
            capture.snapshot().binding().commitId(),
            capture.snapshot().sourceEpoch(),
            release.publishedReleaseBundleRef(),
            terminal.publishedReleaseBundleDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operationBytes,
            captureBytes,
            terminalBytes,
            expectedCount,
            setDigest,
            policies));
  }

  private RealmPolicySource.Policy sourcePolicy(Record row) {
    RealmEntryPolicy policy =
        RealmEntryPolicy.parseCanonical(row.get("policy_json", String.class), JSON);
    if (!Objects.equals(row.get("world_slug", String.class), policy.worldSlug())
        || !Objects.equals(row.get("realm_slug", String.class), policy.realmSlug())
        || !Objects.equals(row.get("visible", Boolean.class), policy.visible())
        || !Objects.equals(
            row.get("public_production", Boolean.class), policy.publicProduction())) {
      throw new IllegalStateException("POLICY_PUBLICATION_TYPED_POLICY_CONFLICT");
    }
    return new RealmPolicySource.Policy(
        row.get("source_commit_id", UUID.class),
        row.get("source_revision_id", UUID.class),
        row.get("logical_revision_id", String.class),
        policy);
  }

  /** This reads without acquiring earlier publication locks; the seal already holds Version. */
  private Record requireOwner(
      TargetProof target, GameDesignPublicationTerminalEvidence terminal, boolean atSeal) {
    Record owner =
        dsl.fetchOne(
            "SELECT v.version_state, v.version_state_epoch, v.version_number, v.is_script_only, "
                + "v.script_patch_version, v.base_version_id "
                + "FROM version v JOIN game g ON g.id = v.identity_source_game_row_id "
                + "AND g.tenant_id = v.identity_source_game_tenant_key "
                + "AND g.canonical_tenant_id = v.canonical_tenant_id "
                + "AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind "
                + "AND g.tenant_identity_source_game_id = g.id "
                + "AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id "
                + "WHERE v.id = ? AND v.tenant_id = ? AND v.canonical_tenant_id = ? "
                + "AND v.canonical_version_id = ? AND v.identity_source_game_row_id = ? "
                + "AND v.identity_source_game_tenant_key = ? AND v.identity_source_provenance_kind = ? "
                + "AND g.id = ? AND g.tenant_id = ? AND g.canonical_tenant_id = ? "
                + "AND g.tenant_identity_provenance_kind = ? AND g.tenant_identity_source_game_id = g.id "
                + "AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id",
            target.gameDesignVersionRowId(),
            target.gameDesignVersionTenantKey(),
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            target.sourceGameRowId(),
            target.sourceGameTenantKey(),
            target.sourceProvenanceKind(),
            target.sourceGameRowId(),
            target.sourceGameTenantKey(),
            target.canonicalTenantId(),
            target.sourceProvenanceKind());
    if (owner == null
        || !Boolean.FALSE.equals(owner.get("is_script_only", Boolean.class))
        || owner.get("script_patch_version", String.class) != null
        || owner.get("base_version_id", Long.class) != null) {
      throw new IllegalStateException("POLICY_PUBLICATION_CANONICAL_OWNER_UNAVAILABLE");
    }
    String state = owner.get("version_state", String.class);
    long epoch = owner.get("version_state_epoch", Long.class);
    if (atSeal) {
      if (!"PUBLISHED".equals(state) || epoch != terminal.publicationVersionStateEpoch()) {
        throw new IllegalStateException("POLICY_PUBLICATION_VERSION_NOT_AT_SEAL");
      }
    } else if (("PUBLISHED".equals(state) && epoch != terminal.publicationVersionStateEpoch())
        || ("ACTIVE".equals(state) && epoch <= terminal.publicationVersionStateEpoch())
        || (!"PUBLISHED".equals(state) && !"ACTIVE".equals(state))) {
      throw new IllegalStateException("POLICY_PUBLICATION_VERSION_NOT_CURRENT");
    }
    return owner;
  }

  private void requireCurrentVisibleSource(TargetProof target, RealmPolicySnapshot snapshot) {
    Record source =
        dsl.fetchOne(
            "SELECT source_epoch, visible_commit_id FROM game_design_realm_policy_source "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (source == null
        || !Objects.equals(source.get("source_epoch", String.class), snapshot.sourceEpoch())
        || !Objects.equals(
            source.get("visible_commit_id", UUID.class), snapshot.binding().commitId())) {
      throw new IllegalStateException("POLICY_PUBLICATION_SOURCE_VISIBILITY_CHANGED");
    }
    var fence =
        new DraftCommitCoordinatorRepository(dsl)
            .readVisibilityFence(target)
            .orElseThrow(
                () -> new IllegalStateException("POLICY_PUBLICATION_VISIBILITY_FENCE_UNAVAILABLE"));
    if (!fence.commitId().equals(snapshot.binding().commitId())
        || !fence.requestId().equals(snapshot.binding().requestId())
        || !fence.inputDigest().equals(snapshot.binding().digest())) {
      throw new IllegalStateException("POLICY_PUBLICATION_VISIBILITY_FENCE_CHANGED");
    }
  }

  private void requireActualRelease(
      GameDesignPublicationOperation operation, GameDesignPublicationTerminalEvidence terminal) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM published_release_bundle WHERE tenant_id = ? AND version_id = ?",
            operation.tenantKey(),
            operation.versionId());
    if (row == null) throw new IllegalStateException("POLICY_PUBLICATION_RELEASE_UNAVAILABLE");
    var release = PublicationReleaseContent.fromRow(row);
    if (!Arrays.equals(release.canonicalBytes(), terminal.releaseContent().canonicalBytes())
        || !Objects.equals(row.get("publish_workflow_id", String.class), operation.workflowId())
        || !Objects.equals(
            row.get("published_release_bundle_ref", String.class),
            terminal.publishedReleaseBundleRef())
        || !Objects.equals(terminal.publishedReleaseBundleDigest(), release.contentDigest())) {
      throw new IllegalStateException("POLICY_PUBLICATION_RELEASE_CONTENT_CONFLICT");
    }
  }

  private boolean sourceExists(TargetProof target) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from("game_design_realm_policy_source")
            .where(
                "canonical_tenant_id = ? AND canonical_version_id = ?",
                target.canonicalTenantId(),
                target.canonicalVersionId()));
  }

  private boolean selectedCommitDeclaresRealmPolicy(GameDesignPublicationOperation operation) {
    return operation.account().input().selection().selectedCommit().revisions().stream()
        .filter(revision -> revision.owner() == DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE)
        .anyMatch(
            revision -> {
              JsonNode root = RealmPolicySource.tree(revision.payload());
              return root.path("revisionKind").isTextual()
                  && RealmEntryPolicy.REVISION_KIND.equals(root.path("revisionKind").asText());
            });
  }

  private void requireExactRetry(
      RealmPolicyPublishedEvidence.PublishedSet retained,
      GameDesignPublicationOperation operation,
      RealmPolicySnapshot.Capture capture,
      GameDesignPublicationTerminalEvidence terminal) {
    if (!Arrays.equals(retained.operationBytes(), operation.canonicalBytes())
        || !Arrays.equals(retained.captureBytes(), capture.canonicalBytes())
        || !Arrays.equals(retained.terminalEvidenceBytes(), terminal.canonicalBytes())) {
      throw new IllegalStateException("POLICY_PUBLICATION_IDENTITY_CONFLICT");
    }
  }

  private static void requireWrite() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Published policy association requires the owner READ_COMMITTED seal transaction");
    }
  }
}
