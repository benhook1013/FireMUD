package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldCanonicalInstancePreparationTest {
  @Test
  void defaultServiceDeniesBeforeRepositoryAccess() {
    WorldCanonicalInstancePreparationRepository repository =
        mock(WorldCanonicalInstancePreparationRepository.class);
    WorldCanonicalInstancePreparation.Input input =
        mock(WorldCanonicalInstancePreparation.Input.class);
    WorldCanonicalInstanceTopologyPlan plan = mock(WorldCanonicalInstanceTopologyPlan.class);
    when(input.topologyPlan()).thenReturn(plan);
    when(plan.generationRules()).thenReturn(List.of());
    when(plan.spawnBindings()).thenReturn(List.of());

    assertThatThrownBy(
            () -> new WorldCanonicalInstancePreparationService(repository).prepare(input))
        .isInstanceOf(WorldCanonicalInstancePreparationService.PreparationDeniedException.class)
        .hasMessageContaining("no authenticated source/release and Account commit verifier");

    verifyNoInteractions(repository);
  }

  @Test
  void requiredGenerationIntentFailsClosedBeforeAnyPreparation() {
    WorldCanonicalInstanceTopologyPlan plan = mock(WorldCanonicalInstanceTopologyPlan.class);
    when(plan.generationRules())
        .thenReturn(List.of(mock(WorldCanonicalInstanceTopologyPlan.GenerationRuleIntent.class)));
    when(plan.spawnBindings()).thenReturn(List.of());

    assertThatThrownBy(() -> WorldCanonicalInstancePreparation.requireGenerationFree(plan))
        .isInstanceOf(WorldCanonicalInstancePreparation.GenerationIntentNotSupportedException.class)
        .hasMessageContaining("configured generation or spawn intent");
  }

  @Test
  void requiredSpawnIntentFailsClosedBeforeAnyPreparation() {
    WorldCanonicalInstanceTopologyPlan plan = mock(WorldCanonicalInstanceTopologyPlan.class);
    when(plan.generationRules()).thenReturn(List.of());
    when(plan.spawnBindings())
        .thenReturn(List.of(mock(WorldCanonicalInstanceTopologyPlan.SpawnBindingIntent.class)));

    assertThatThrownBy(() -> WorldCanonicalInstancePreparation.requireGenerationFree(plan))
        .isInstanceOf(WorldCanonicalInstancePreparation.GenerationIntentNotSupportedException.class)
        .hasMessageContaining("configured generation or spawn intent");
  }
}
