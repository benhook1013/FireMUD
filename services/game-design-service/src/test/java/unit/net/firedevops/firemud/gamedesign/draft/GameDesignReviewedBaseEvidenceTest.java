package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.publication.CommandSource;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyGenesis;
import org.junit.jupiter.api.Test;

class GameDesignReviewedBaseEvidenceTest {
  @Test
  void genesisBindsOriginalPairedReceiptTargetAndCreationWitnessWithoutCommit() {
    var target =
        new TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 7, "tenant", 3, "tenant", "NEW_GAME_ROW");
    var receipt = UUID.randomUUID();
    var genesis = genesis(target, receipt, "11");
    var evidence = GameDesignReviewedBaseEvidence.genesis(genesis);
    assertThat(evidence.target()).isEqualTo(target);
    assertThat(evidence.reference()).isEqualTo(DraftBaseReference.parse("genesis:" + receipt));
    assertThat(evidence.canonicalBytes()).contains(genesis.policy().canonicalBytes());
    assertThat(evidence.canonicalBytes()).contains(genesis.command().canonicalBytes());
    assertThat(evidence.digest())
        .isEqualTo(DraftAuthorizationFenceBinding.digest(evidence.canonicalBytes()));
    assertThat(evidence).isEqualTo(GameDesignReviewedBaseEvidence.genesis(genesis));
    assertThat(evidence)
        .isNotEqualTo(GameDesignReviewedBaseEvidence.genesis(genesis(target, receipt, "12")));
    assertThat(evidence)
        .isNotEqualTo(
            GameDesignReviewedBaseEvidence.genesis(genesis(target, UUID.randomUUID(), "11")));
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
      TargetProof target, UUID receipt, String witness) {
    return new GameDesignSourceRepository.Genesis(
        new RealmPolicyGenesis(target, receipt, witness),
        new CommandSource.NewDraftGenesisReceipt(target, receipt, witness));
  }
}
