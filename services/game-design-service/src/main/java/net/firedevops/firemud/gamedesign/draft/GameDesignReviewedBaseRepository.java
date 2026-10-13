package net.firedevops.firemud.gamedesign.draft;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal prerequisite for the canonical reviewed-source ingress; no authentication is implied.
 */
public final class GameDesignReviewedBaseRepository {
  private final DSLContext dsl;
  private final GameDesignSourceRepository sources;
  private final DraftCommitCoordinatorRepository coordinator;

  public GameDesignReviewedBaseRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    sources = new GameDesignSourceRepository(dsl);
    coordinator = new DraftCommitCoordinatorRepository(dsl);
  }

  /** Resolve exact owner provenance when preparing the immutable review, under the Version lock. */
  public GameDesignReviewedBaseEvidence resolve(TargetProof target, DraftBaseReference reference) {
    requireWrite();
    lockDraft(target);
    Objects.requireNonNull(reference, "reference");
    if (reference.kind() == DraftBaseReference.Kind.GENESIS) {
      var genesis =
          sources
              .readGenesis(target)
              .orElseThrow(() -> new IllegalStateException("REVIEWED_BASE_GENESIS_UNAVAILABLE"));
      if (!reference.identity().equals(genesis.policy().receiptId())) {
        throw new IllegalArgumentException("REVIEWED_BASE_GENESIS_IDENTITY_CONFLICT");
      }
      return GameDesignReviewedBaseEvidence.genesis(genesis);
    }
    var synchronizedSources =
        sources
            .readSynchronized(target, reference.identity())
            .orElseThrow(
                () -> new IllegalStateException("REVIEWED_BASE_AUTHORED_SOURCE_UNAVAILABLE"));
    var binding = synchronizedSources.command().binding();
    var retained =
        coordinator
            .read(target, binding.requestId())
            .orElseThrow(
                () -> new IllegalStateException("REVIEWED_BASE_AUTHORED_COMMIT_UNAVAILABLE"));
    if (!reference.identity().equals(binding.commitId())) {
      throw new IllegalArgumentException("REVIEWED_BASE_AUTHORED_IDENTITY_CONFLICT");
    }
    return GameDesignReviewedBaseEvidence.authored(synchronizedSources, retained);
  }

  /**
   * Compare the frozen reviewed bytes to actual owner evidence before claiming a new coordinator
   * binding. Exact retries compare retained bytes and never re-resolve or replace their provenance.
   * Account authorization and semantic affected-set verification remain caller obligations.
   */
  public DraftCommitCoordinatorRepository.CommitSnapshot claimReviewed(
      DraftCommitBinding binding, GameDesignReviewedBaseEvidence expectedReviewedEvidence) {
    requireWrite();
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(expectedReviewedEvidence, "expectedReviewedEvidence");
    var reference = DraftBaseReference.parse(binding.baseCommitId());
    if (!binding.target().equals(expectedReviewedEvidence.target())
        || !reference.equals(expectedReviewedEvidence.reference())) {
      throw new IllegalArgumentException("REVIEWED_BASE_BINDING_CONFLICT");
    }
    var locked =
        coordinator.lockVersionTarget(
            binding.target().canonicalTenantId(), binding.target().canonicalVersionId());
    if (!binding.target().equals(locked.target())) {
      throw new IllegalArgumentException("REVIEWED_BASE_TARGET_CONFLICT");
    }
    Optional<ReviewedBaseReadback> existing = read(binding);
    if (existing.isPresent()) {
      requireExact(expectedReviewedEvidence, existing.orElseThrow());
      return coordinator.read(binding.target(), binding.requestId()).orElseThrow();
    }
    // A previously opaque claim cannot be retroactively decorated with invented reviewed evidence.
    if (coordinator.read(binding.target(), binding.requestId()).isPresent()) {
      throw new IllegalStateException("REVIEWED_BASE_EXISTING_CLAIM_LACKS_EVIDENCE");
    }
    var resolved = resolve(binding.target(), reference);
    if (!resolved.equals(expectedReviewedEvidence)) {
      throw new IllegalArgumentException("REVIEWED_BASE_OWNER_EVIDENCE_CONFLICT");
    }
    var result = coordinator.claim(binding);
    dsl.execute(
        "INSERT INTO game_design_reviewed_draft_base "
            + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, input_digest, "
            + "binding_json, base_kind, base_identity, base_reference, evidence_schema, "
            + "evidence_bytes, evidence_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId(),
        binding.digest(),
        binding.canonicalJson(),
        reference.kind().name(),
        reference.identity(),
        reference.canonicalValue(),
        GameDesignReviewedBaseEvidence.SCHEMA,
        resolved.canonicalBytes(),
        resolved.digest());
    requireExact(resolved, read(binding).orElseThrow());
    return result;
  }

  /** Immutable exact-binding readback, including the original bytes and their versioned digest. */
  public Optional<ReviewedBaseReadback> read(DraftCommitBinding binding) {
    Objects.requireNonNull(binding, "binding");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_design_reviewed_draft_base WHERE canonical_tenant_id = ? "
                + "AND canonical_version_id = ? AND (request_id = ? OR commit_id = ?)",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId());
    if (row == null) return Optional.empty();
    var reference = DraftBaseReference.parse(binding.baseCommitId());
    if (!binding.equals(
            DraftCommitBinding.fromStored(
                row.get("binding_json", String.class), row.get("input_digest", String.class)))
        || !reference.kind().name().equals(row.get("base_kind", String.class))
        || !reference.identity().equals(row.get("base_identity", java.util.UUID.class))
        || !reference.canonicalValue().equals(row.get("base_reference", String.class))
        || !GameDesignReviewedBaseEvidence.SCHEMA.equals(
            row.get("evidence_schema", String.class))) {
      throw new IllegalArgumentException("REVIEWED_BASE_STORED_BINDING_CONFLICT");
    }
    var result =
        new ReviewedBaseReadback(
            row.get("evidence_bytes", byte[].class), row.get("evidence_digest", String.class));
    return Optional.of(result);
  }

  /** Require original immutable review provenance for source writes and publication consumption. */
  public ReviewedBaseReadback requireRetained(DraftCommitBinding binding) {
    var retained =
        read(binding)
            .orElseThrow(
                () -> new IllegalStateException("GAME_DESIGN_SOURCE_REVIEWED_BASE_UNAVAILABLE"));
    var commit =
        coordinator
            .read(binding.target(), binding.requestId())
            .orElseThrow(
                () -> new IllegalStateException("REVIEWED_BASE_AUTHORED_COMMIT_UNAVAILABLE"));
    if (!binding.equals(commit.binding())) {
      throw new IllegalArgumentException("REVIEWED_BASE_STORED_BINDING_CONFLICT");
    }
    return retained;
  }

  private void lockDraft(TargetProof target) {
    Objects.requireNonNull(target, "target");
    var version =
        coordinator.lockVersionTarget(target.canonicalTenantId(), target.canonicalVersionId());
    if (!target.equals(version.target())
        || !"DRAFT".equals(version.versionState())
        || version.scriptOnly()
        || version.baseVersionId() != null
        || version.scriptPatchVersion() != null) {
      throw new IllegalArgumentException("REVIEWED_BASE_EXACT_FULL_DRAFT_REQUIRED");
    }
  }

  private static void requireExact(
      GameDesignReviewedBaseEvidence expected, ReviewedBaseReadback stored) {
    if (!expected.digest().equals(stored.digest())
        || !Arrays.equals(expected.canonicalBytes(), stored.canonicalBytes())) {
      throw new IllegalArgumentException("REVIEWED_BASE_STORED_EVIDENCE_CONFLICT");
    }
  }

  private static void requireWrite() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Reviewed base requires caller-owned writable READ_COMMITTED transaction");
    }
  }

  public record ReviewedBaseReadback(byte[] canonicalBytes, String digest) {
    public ReviewedBaseReadback {
      canonicalBytes = Objects.requireNonNull(canonicalBytes, "canonicalBytes").clone();
      if (!DraftAuthorizationFenceBinding.digest(canonicalBytes).equals(digest)) {
        throw new IllegalArgumentException("REVIEWED_BASE_STORED_DIGEST_CONFLICT");
      }
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }
}
