package net.firedevops.firemud.entitymanagement.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultEffectEvaluationServiceTest {
  private final DefaultEffectEvaluationService service = new DefaultEffectEvaluationService();

  @Test
  void evaluatesNumericEffectsInDeterministicPhases() {
    EffectSource base = new EffectSource("CHARACTER_BASELINE", "character:7", "7");
    EffectSource aura = new EffectSource("CONDITION", "aura:1", "7");
    EffectSource curse = new EffectSource("CONDITION", "curse:1", "7");
    EffectEvaluationInput input =
        new EffectEvaluationInput(
            List.of(new EffectResourceValue("strength", 10L, null, 10L, "INTEGER", base)),
            List.of(
                EffectModifier.multiply("strength", "2.0", aura),
                EffectModifier.add("strength", 5L, aura),
                EffectModifier.clampMax("strength", 20L, curse)));

    EffectEvaluationResult result = service.evaluate(input);

    assertEquals(1, result.resources().size());
    EvaluatedResourceValue strength = result.resources().get(0);
    assertEquals("strength", strength.statKey());
    assertEquals(20L, strength.currentValue());
    assertEquals(List.of(base, aura, aura, curse), strength.contributingSources());
  }

  @Test
  void grantsFlagsAndConditionsWithoutInventingNumericResources() {
    EffectSource source = new EffectSource("ACTION_STATE", "block:1", "7");
    EffectEvaluationInput input =
        new EffectEvaluationInput(
            List.of(),
            List.of(
                EffectModifier.grantFlag("blocking", source),
                EffectModifier.grantCondition("guarded", source)));

    EffectEvaluationResult result = service.evaluate(input);

    assertEquals(List.of(), result.resources());
    assertEquals(2, result.grantedStates().size());
    assertEquals("guarded", result.grantedStates().get(0).stateKey());
    assertEquals(EffectOperation.GRANT_CONDITION, result.grantedStates().get(0).operation());
    assertEquals("blocking", result.grantedStates().get(1).stateKey());
    assertEquals(EffectOperation.GRANT_FLAG, result.grantedStates().get(1).operation());
  }

  @Test
  void createsSyntheticResourceForModifierOnlyStat() {
    EffectSource source = new EffectSource("EQUIPMENT", "ring:1", "7");
    EffectEvaluationInput input =
        new EffectEvaluationInput(
            List.of(), List.of(EffectModifier.add("fire_resist", 12L, source)));

    EffectEvaluationResult result = service.evaluate(input);

    assertEquals(1, result.resources().size());
    assertEquals("fire_resist", result.resources().get(0).statKey());
    assertEquals(12L, result.resources().get(0).currentValue());
    assertEquals(List.of(source), result.resources().get(0).contributingSources());
  }
}
