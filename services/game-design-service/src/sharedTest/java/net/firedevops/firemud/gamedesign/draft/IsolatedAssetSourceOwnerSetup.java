package net.firedevops.firemud.gamedesign.draft;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.AssetSource.Requiredness;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import org.jooq.DSLContext;

/** Isolated test producer for an actual reviewed ordinary asset source commit and owner fence. */
public final class IsolatedAssetSourceOwnerSetup {
  private IsolatedAssetSourceOwnerSetup() {}

  /**
   * Call inside the fixture's writable READ COMMITTED transaction after fresh Version insertion.
   */
  public static DraftCommitBinding upsert(
      DSLContext dsl, TargetProof target, long assetId, String usageKey) {
    var sources = new GameDesignSourceRepository(dsl);
    var genesis = sources.readGenesis(target).orElseThrow();
    String base = "genesis:" + genesis.policy().receiptId();
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            base,
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    AssetSource.upsertPayload(
                        Long.toString(assetId), usageKey, Requiredness.REQUIRED))),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    AssetSource.SCOPE,
                    target.canonicalVersionId().toString(),
                    AssetSource.SCOPE,
                    AssetSource.SCOPE_ID,
                    "0")));
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    sources.claimReviewed(
        binding,
        new GameDesignReviewedBaseRepository(dsl)
            .resolve(target, DraftBaseReference.parse(binding.baseCommitId())));
    coordinator.claimApplicationSlot(binding);
    coordinator.markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
    var result = sources.apply(binding);
    coordinator.advanceSourceVisibilityFence(
        binding,
        new DraftCommitCoordinatorRepository.CoordinatorProof(
            binding, List.of(result.ownerOutcome())));
    sources.captureSynchronized(binding);
    coordinator.releaseApplicationSlot(binding);
    return binding;
  }
}
