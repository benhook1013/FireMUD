package unit.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSnapshot;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSourceRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class GameplayRuleSourceRepositoryTest {
  @Test
  void pureValidatorRequiresTheGameplayContainingScopeBeforeStorage() {
    var sql = mock(DSLContext.class);
    var owner = new GameDesignSourceRepository(sql);
    var valid = binding("GAMEPLAY_RULE_SET");
    owner.validateInput(valid);
    assertThatThrownBy(() -> owner.validateInput(binding("OTHER_SCOPE")))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(sql);
  }

  @Test
  void gameplayWritesRequireTheCallerOwnerTransaction() {
    var sql = mock(DSLContext.class);
    var owner = new GameplayRuleSourceRepository(sql);
    var binding = binding("GAMEPLAY_RULE_SET");
    assertThatThrownBy(() -> owner.enrollFreshDraft(binding.target()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> owner.apply(binding)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> owner.captureSynchronized(binding))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(sql);
  }

  @Test
  void canonicalSnapshotRetainsItsExactBindingAndExplicitInventory() {
    var binding = binding("GAMEPLAY_RULE_SET");
    var snapshot = new GameplayRuleSnapshot(binding, "0", null, UUID.randomUUID(), List.of());
    var decoded = GameplayRuleSnapshot.fromStored(snapshot.canonicalJson());
    assertThat(decoded).isEqualTo(snapshot);
    assertThat(decoded.manifest().families()).hasSize(Family.values().length);
    assertThatThrownBy(
            () ->
                GameplayRuleSnapshot.fromStored(
                    snapshot
                        .canonicalJson()
                        .replace("game-design-gameplay-rule-source-snapshot/v1", "unknown/v1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static DraftCommitBinding binding(String scope) {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "owner", 2, "owner", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.randomUUID(),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.deletePayload(Family.STATS, "strength"))),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                scope,
                target.canonicalVersionId().toString(),
                scope,
                "effective",
                "0")));
  }
}
