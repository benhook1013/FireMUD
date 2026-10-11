package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Definition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Direction;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Effect;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.EffectOperation;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Lifecycle;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.PrimitiveKind;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.RankComparator;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Resource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.SelectionPolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.SelectionStrategy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Stat;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.StatField;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Visibility;
import org.junit.jupiter.api.Test;

class GameplayRuleManifestTest {
  private static final Resource MANA =
      new Resource(
          "mana",
          decimal("5"),
          decimal("0"),
          decimal("10"),
          decimal("20"),
          Visibility.PUBLIC,
          List.of());

  private static final Stat STRENGTH =
      new Stat(
          "strength",
          PrimitiveKind.NUMERIC,
          decimal("10"),
          null,
          decimal("0"),
          decimal("20"),
          Visibility.PUBLIC,
          List.of());

  @Test
  void rejectsContinuousModifierOnResourceValue() {
    assertThatThrownBy(
            () ->
                manifest(
                    MANA,
                    effect(
                        "continuous-mana-value",
                        Lifecycle.CONTINUOUS,
                        EffectOperation.ADD,
                        "mana",
                        StatField.VALUE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("numeric STATS key");
  }

  @Test
  void acceptsContinuousNumericStatValueAndResourceMaximum() {
    manifest(
        MANA,
        STRENGTH,
        effect(
            "continuous-strength-value",
            Lifecycle.CONTINUOUS,
            EffectOperation.ADD,
            "strength",
            StatField.VALUE),
        effect(
            "continuous-mana-maximum",
            Lifecycle.CONTINUOUS,
            EffectOperation.ADD,
            "mana",
            StatField.MAXIMUM));
  }

  @Test
  void acceptsResourceValueComparatorAndInstantResourceAdjustment() {
    manifest(
        MANA,
        new SelectionPolicy(
            "rank-by-mana-value",
            GameplayRuleManifest.Cardinality.EXACTLY_ONE,
            null,
            null,
            SelectionStrategy.RANKED,
            null,
            List.of(new RankComparator("mana", StatField.VALUE, Direction.ASCENDING))),
        new Effect(
            "instant-mana-adjustment",
            Lifecycle.INSTANT,
            EffectOperation.ADJUST_RESOURCE,
            "mana",
            null,
            decimal("1"),
            null,
            null));
  }

  private static Effect effect(
      String key, Lifecycle lifecycle, EffectOperation operation, String statKey, StatField field) {
    return new Effect(key, lifecycle, operation, statKey, field, decimal("1"), null, null);
  }

  private static GameplayRuleManifest manifest(Definition... definitions) {
    Map<Family, List<Definition>> families = new EnumMap<>(Family.class);
    for (Family family : Family.values()) families.put(family, new ArrayList<>());
    for (Definition definition : definitions) families.get(definition.family()).add(definition);
    return new GameplayRuleManifest(families);
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
