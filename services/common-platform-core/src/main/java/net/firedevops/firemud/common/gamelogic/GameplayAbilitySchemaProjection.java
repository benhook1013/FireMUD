package net.firedevops.firemud.common.gamelogic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Ability;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Action;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.AdmissionTag;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Command;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Condition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.ContinuousOverlay;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.DefaultBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Definition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Disposition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Effect;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.EffectOperation;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Feedback;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.ObservationPolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Predicate;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Resource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.SelectionPolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Stat;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.StatField;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.TargetSet;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.TargetingPolicy;

/**
 * Content-only projection; the caller must independently prove exact retained source provenance.
 */
public final class GameplayAbilitySchemaProjection {
  public static final String SCHEMA = "gameplay-ability-schema/v1";

  private GameplayAbilitySchemaProjection() {}

  public static String canonicalJson(GameplayRuleManifest source) {
    // Validate every family, including definitions that will not occur in the projection.
    var complete = new GameplayRuleManifest(Objects.requireNonNull(source).families());
    Map<Key, Definition> definitions = new HashMap<>();
    complete
        .families()
        .forEach(
            (family, values) ->
                values.forEach(value -> definitions.put(new Key(family, value.key()), value)));
    var pending = new ArrayDeque<Key>();
    for (Family root : List.of(Family.ABILITIES, Family.ACTIONS))
      complete.families().get(root).forEach(value -> pending.add(new Key(root, value.key())));
    Set<Key> visited = new HashSet<>();
    while (!pending.isEmpty()) {
      Key key = pending.remove();
      if (!visited.add(key)) continue;
      Definition value = definitions.get(key);
      if (value == null) throw new IllegalArgumentException("Unresolved ability-schema reference");
      dependencies(value, definitions, pending);
    }
    Map<Family, List<Definition>> retained = new EnumMap<>(Family.class);
    complete
        .families()
        .forEach(
            (family, values) ->
                retained.put(
                    family,
                    values.stream()
                        .filter(value -> visited.contains(new Key(family, value.key())))
                        .toList()));
    // Closure must itself remain a complete, valid inventory; do not repair missing edges with
    // defaults.
    var projection = new GameplayRuleManifest(retained);
    Map<String, Object> families = new LinkedHashMap<>();
    for (Family family : Family.values())
      families.put(family.name(), projection.families().get(family));
    return GameplayRuleManifest.canonical(Map.of("schema", SCHEMA, "families", families));
  }

  public static byte[] canonicalBytes(GameplayRuleManifest source) {
    return canonicalJson(source).getBytes(StandardCharsets.UTF_8);
  }

  public static String digest(GameplayRuleManifest source) {
    return GameplayRuleManifest.sha256(canonicalBytes(source));
  }

  private record Key(Family family, String key) {}

  private static void add(ArrayDeque<Key> pending, Family family, String key) {
    pending.add(new Key(family, key));
  }

  private static void addAll(ArrayDeque<Key> pending, Family family, List<String> keys) {
    keys.forEach(key -> add(pending, family, key));
  }

  private static void numeric(
      ArrayDeque<Key> pending, Map<Key, Definition> definitions, String key) {
    // The closed grammar rejects cross-family stat/resource collisions before traversal.
    add(
        pending,
        definitions.containsKey(new Key(Family.RESOURCES, key)) ? Family.RESOURCES : Family.STATS,
        key);
  }

  private static void targets(ArrayDeque<Key> pending, List<TargetSet> targets) {
    for (TargetSet target : targets) {
      add(pending, Family.TARGETING_POLICIES, target.targetingPolicyKey());
      add(pending, Family.SELECTION_POLICIES, target.targetSelectionPolicyKey());
      if (target.unresolvedFeedbackKey() != null)
        add(pending, Family.FEEDBACK, target.unresolvedFeedbackKey());
    }
  }

  private static void predicate(
      ArrayDeque<Key> pending, Map<Key, Definition> definitions, Predicate value) {
    switch (value.kind()) {
      case STAT_COMPARE -> numeric(pending, definitions, value.factKey());
      case CONDITION_PRESENT -> add(pending, Family.CONDITIONS, value.factKey());
      case DISPOSITION_EQUALS -> add(pending, Family.DISPOSITIONS, value.factKey());
      case TRUE, FALSE, ALL, ANY, NOT -> {}
    }
    for (Predicate operand : value.operands()) predicate(pending, definitions, operand);
  }

  private static void dependencies(
      Definition definition, Map<Key, Definition> definitions, ArrayDeque<Key> pending) {
    switch (definition) {
      case Stat ignored -> {}
      case Resource ignored -> {}
      case AdmissionTag ignored -> {}
      case Feedback ignored -> {}
      case Ability value -> add(pending, Family.ACTIONS, value.actionSequenceId());
      case Condition value -> addAll(pending, Family.EFFECTS, value.effectKeys());
      case Effect value -> {
        if (value.statKey() != null) {
          if (value.operation() == EffectOperation.ADJUST_RESOURCE
              || value.statField() == StatField.MAXIMUM)
            add(pending, Family.RESOURCES, value.statKey());
          else numeric(pending, definitions, value.statKey());
        }
        if (value.conditionKey() != null) add(pending, Family.CONDITIONS, value.conditionKey());
      }
      case Action value -> {
        addAll(pending, Family.ADMISSION_TAGS, value.admissionTags());
        targets(pending, value.targetSets());
        value.costs().forEach(cost -> add(pending, Family.RESOURCES, cost.statKey()));
        value.effects().forEach(effect -> add(pending, Family.EFFECTS, effect.effectKey()));
        addAll(pending, Family.FEEDBACK, value.feedbackKeys());
      }
      case Command value -> {
        add(pending, Family.ACTIONS, value.actionSequenceId());
        addAll(pending, Family.ADMISSION_TAGS, value.admissionTags());
      }
      case Disposition value -> {
        addAll(pending, Family.ADMISSION_TAGS, value.deniedAdmissionTags());
        add(pending, Family.FEEDBACK, value.safeFeedbackKey());
      }
      case ContinuousOverlay value -> {
        predicate(pending, definitions, value.eligibleWhen());
        addAll(pending, Family.ADMISSION_TAGS, value.deniedAdmissionTags());
        addAll(pending, Family.EFFECTS, value.effectKeys());
      }
      case ObservationPolicy value -> predicate(pending, definitions, value.observableWhen());
      case TargetingPolicy value -> {
        add(pending, Family.OBSERVATION_POLICIES, value.observationPolicyKey());
        predicate(pending, definitions, value.eligibleWhen());
        add(pending, Family.FEEDBACK, value.safeFailureFeedbackKey());
      }
      case SelectionPolicy value ->
          value
              .comparators()
              .forEach(
                  comparator -> {
                    if (comparator.statField() == StatField.MAXIMUM)
                      add(pending, Family.RESOURCES, comparator.statKey());
                    else numeric(pending, definitions, comparator.statKey());
                  });
      case DefaultBinding value -> targets(pending, value.targetSets());
    }
  }
}
