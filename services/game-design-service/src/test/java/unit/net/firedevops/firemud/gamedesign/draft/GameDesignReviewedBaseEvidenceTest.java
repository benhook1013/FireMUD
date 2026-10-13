package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.publication.AssetSnapshot;
import net.firedevops.firemud.gamedesign.publication.CommandSource;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyGenesis;
import org.junit.jupiter.api.Test;

class GameDesignReviewedBaseEvidenceTest {
  @Test
  void genesisBindsPolicyCommandAndDistinctAssetBaselineWithoutCommit() {
    var target =
        new TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 7, "tenant", 3, "tenant", "NEW_GAME_ROW");
    var receipt = UUID.randomUUID();
    var assetReceipt = UUID.randomUUID();
    var genesis = genesis(target, receipt, "11", assetReceipt);
    var evidence = GameDesignReviewedBaseEvidence.genesis(genesis);
    assertThat(evidence.target()).isEqualTo(target);
    assertThat(evidence.reference()).isEqualTo(DraftBaseReference.parse("genesis:" + receipt));
    assertThat(evidence.canonicalBytes()).contains(genesis.policy().canonicalBytes());
    assertThat(evidence.canonicalBytes()).contains(genesis.command().canonicalBytes());
    assertThat(evidence.canonicalBytes()).contains(genesis.asset().canonicalBytes());
    assertThat(evidence.digest())
        .isEqualTo(DraftAuthorizationFenceBinding.digest(evidence.canonicalBytes()));
    assertThat(evidence).isEqualTo(GameDesignReviewedBaseEvidence.genesis(genesis));
    assertThat(evidence)
        .isNotEqualTo(
            GameDesignReviewedBaseEvidence.genesis(
                genesis(target, receipt, "12", genesis.asset().receiptId())));
    assertThat(evidence)
        .isNotEqualTo(
            GameDesignReviewedBaseEvidence.genesis(
                genesis(target, UUID.randomUUID(), "11", genesis.asset().receiptId())));
    assertThat(evidence)
        .isNotEqualTo(
            GameDesignReviewedBaseEvidence.genesis(
                genesis(target, receipt, "11", UUID.randomUUID())));
    assertThatThrownBy(
            () ->
                GameDesignReviewedBaseEvidence.genesis(
                    new GameDesignSourceRepository.Genesis(genesis.policy(), genesis.command())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GAME_DESIGN_SOURCE_ASSET_GENESIS_UNAVAILABLE");
    byte[] returned = evidence.canonicalBytes();
    returned[0] ^= 1;
    assertThat(returned).isNotEqualTo(evidence.canonicalBytes());
  }

  @Test
  void readbackRejectsChangedDigestAndDefensivelyCopiesBytes() {
    byte[] bytes = {1, 2, 3};
    var retained =
        new GameDesignReviewedBaseRepository.ReviewedBaseReadback(
            bytes, DraftAuthorizationFenceBinding.digest(bytes));
    bytes[0] = 9;
    assertThat(retained.canonicalBytes()).containsExactly(1, 2, 3);
    assertThatThrownBy(
            () ->
                new GameDesignReviewedBaseRepository.ReviewedBaseReadback(bytes, retained.digest()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static GameDesignSourceRepository.Genesis genesis(
      TargetProof target, UUID receipt, String witness, UUID assetReceipt) {
    return new GameDesignSourceRepository.Genesis(
        new RealmPolicyGenesis(target, receipt, witness),
        new CommandSource.NewDraftGenesisReceipt(target, receipt, witness),
        java.util.Optional.of(new AssetSnapshot.Genesis(target, assetReceipt, witness)));
  }
}
