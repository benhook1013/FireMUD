package net.firedevops.firemud.gamedesign.draft;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;

/** Exact versioned owner evidence frozen when a proposal is reviewed; grants no authorization. */
public final class GameDesignReviewedBaseEvidence {
  public static final String SCHEMA = "game-design-reviewed-draft-base/v1";
  private final TargetProof target;
  private final DraftBaseReference reference;
  private final byte[] bytes;
  private final String digest;

  private GameDesignReviewedBaseEvidence(
      TargetProof target, DraftBaseReference reference, byte[] ownerEvidence) {
    this.target = Objects.requireNonNull(target, "target");
    this.reference = Objects.requireNonNull(reference, "reference");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    frame(out, SCHEMA);
    frame(out, DraftBaseReference.SCHEMA);
    frame(out, reference.kind().name());
    frame(out, reference.canonicalValue());
    frame(out, target.canonicalTenantId().toString());
    frame(out, target.canonicalVersionId().toString());
    frame(out, Long.toString(target.gameDesignVersionRowId()));
    frame(out, target.gameDesignVersionTenantKey());
    frame(out, Long.toString(target.sourceGameRowId()));
    frame(out, target.sourceGameTenantKey());
    frame(out, target.sourceProvenanceKind());
    DraftAuthorizationFenceBinding.frame(out, ownerEvidence);
    bytes = out.toByteArray();
    digest = DraftAuthorizationFenceBinding.digest(bytes);
  }

  public static GameDesignReviewedBaseEvidence genesis(GameDesignSourceRepository.Genesis genesis) {
    Objects.requireNonNull(genesis, "genesis");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    frame(out, "shared-policy-command-genesis/v1");
    frame(out, genesis.policy().creationTransactionId());
    DraftAuthorizationFenceBinding.frame(out, genesis.policy().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, genesis.command().canonicalBytes());
    return new GameDesignReviewedBaseEvidence(
        genesis.policy().target(),
        new DraftBaseReference(DraftBaseReference.Kind.GENESIS, genesis.policy().receiptId()),
        out.toByteArray());
  }

  public static GameDesignReviewedBaseEvidence authored(
      GameDesignSourceRepository.SynchronizedSources sources,
      DraftCommitCoordinatorRepository.CommitSnapshot retained) {
    Objects.requireNonNull(sources, "sources");
    Objects.requireNonNull(retained, "retained");
    DraftCommitBinding binding = sources.command().binding();
    if (!binding.equals(retained.binding())
        || retained.workflowState() != DraftCommitCoordinatorRepository.WorkflowState.SYNCHRONIZED
        || !retained.ownerStates().keySet().equals(new HashSet<>(binding.requiredOwners()))) {
      throw new IllegalArgumentException(
          "Exact retained synchronized base and owner vector required");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    frame(out, "retained-authored-policy-command-base/v1");
    DraftAuthorizationFenceBinding.frame(out, binding.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, sources.policy().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, sources.command().canonicalBytes());
    frame(out, Integer.toString(binding.requiredOwners().size()));
    for (var owner : binding.requiredOwners()) {
      var state = retained.ownerStates().get(owner);
      var outcome = state.outcome().orElseThrow();
      if (state.status() != DraftCommitCoordinatorRepository.OwnerStatus.APPLIED
          || !binding.commitId().equals(outcome.commitId())
          || !binding.digest().equals(outcome.bindingDigest())
          || outcome.appliedEpochs().size() != binding.affectedUnits(owner).size()
          || binding.affectedUnits(owner).stream()
              .anyMatch(
                  unit ->
                      outcome.appliedEpochs().stream()
                          .noneMatch(
                              epoch ->
                                  epoch.aggregateType().equals(unit.aggregateType())
                                      && epoch.aggregateId().equals(unit.aggregateId())
                                      && epoch.scopeType().equals(unit.scopeType())
                                      && epoch.scopeId().equals(unit.scopeId())
                                      && epoch.expectedEpoch().equals(unit.expectedEpoch())))) {
        throw new IllegalArgumentException("Complete exact APPLIED base owner evidence required");
      }
      frame(out, owner.name());
      frame(out, outcome.status().name());
      frame(out, outcome.commitId().toString());
      frame(out, outcome.bindingDigest());
      frame(out, outcome.resultIdentity());
      DraftAuthorizationFenceBinding.frame(out, outcome.resultBytes());
      frame(out, Integer.toString(outcome.appliedEpochs().size()));
      for (var epoch : outcome.appliedEpochs()) {
        frame(out, epoch.aggregateType());
        frame(out, epoch.aggregateId());
        frame(out, epoch.scopeType());
        frame(out, epoch.scopeId());
        frame(out, epoch.expectedEpoch());
        frame(out, epoch.resultingEpoch());
      }
    }
    return new GameDesignReviewedBaseEvidence(
        binding.target(),
        new DraftBaseReference(DraftBaseReference.Kind.AUTHORED_COMMIT, binding.commitId()),
        out.toByteArray());
  }

  public TargetProof target() {
    return target;
  }

  public DraftBaseReference reference() {
    return reference;
  }

  public byte[] canonicalBytes() {
    return bytes.clone();
  }

  public String digest() {
    return digest;
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof GameDesignReviewedBaseEvidence evidence
        && Arrays.equals(bytes, evidence.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }
}
