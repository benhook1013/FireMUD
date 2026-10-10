package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Definition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import org.junit.jupiter.api.Test;

class GameplayAbilitySchemaProjectionTest {
  private static final String EMPTY_FAMILIES =
      "\"ABILITIES\":[],\"ACTIONS\":[],\"ADMISSION_TAGS\":[],\"COMMANDS\":[],\"CONDITIONS\":[],"
          + "\"CONTINUOUS_OVERLAYS\":[],\"DEFAULT_BINDINGS\":[],\"DISPOSITIONS\":[],\"EFFECTS\":[],"
          + "\"FEEDBACK\":[],\"OBSERVATION_POLICIES\":[],\"RESOURCES\":[],\"SELECTION_POLICIES\":[],"
          + "\"STATS\":[],\"TARGETING_POLICIES\":[]";
  private static final String EMPTY_VECTOR =
      "{\"families\":{" + EMPTY_FAMILIES + "},\"schema\":\"gameplay-ability-schema/v1\"}";
  private static final String ROOT_VECTOR =
      "{\"families\":{\"ABILITIES\":[{\"abilityId\":\"ability_a\",\"actionSequenceId\":\"action_a\",\"name\":\"A\"}],"
          + "\"ACTIONS\":[{\"actionSequenceId\":\"action_a\",\"admissionTags\":[],\"cooldowns\":[],\"costs\":[],"
          + "\"effects\":[],\"feedbackKeys\":[],\"name\":\"A\",\"targetSets\":[]},"
          + "{\"actionSequenceId\":\"action_b\",\"admissionTags\":[],\"cooldowns\":[],\"costs\":[],"
          + "\"effects\":[],\"feedbackKeys\":[],\"name\":\"B\",\"targetSets\":[]}],"
          + EMPTY_FAMILIES.substring(EMPTY_FAMILIES.indexOf("\"ADMISSION_TAGS\""))
          + "},\"schema\":\"gameplay-ability-schema/v1\"}";

  @Test
  void frozenEmptyVectorIsDomainSeparatedAndDoesNotEstablishSourceAuthentication() {
    var source = GameplayRuleManifest.explicitEmpty();
    assertThat(GameplayAbilitySchemaProjection.canonicalJson(source)).isEqualTo(EMPTY_VECTOR);
    assertThat(GameplayAbilitySchemaProjection.digest(source))
        .isEqualTo("sha256:0207783645e944996b4f7589d2662d5b8309231769681e162ee05de51d34db2a");
    assertThat(GameplayAbilitySchemaProjection.digest(source)).isNotEqualTo(source.digest());
    // Content projection intentionally cannot manufacture a source operation or authenticated
    // receipt.
    assertThat(GameplayRuleManifest.tree(EMPTY_VECTOR).propertyNames())
        .containsExactly("families", "schema");
    assertThatThrownBy(() -> GameplayAbilitySchemaProjection.canonicalBytes(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void frozenRootsVectorIncludesEveryActionEvenWithoutAnAbilityReference() {
    var source = roots();
    assertThat(GameplayAbilitySchemaProjection.canonicalJson(source)).isEqualTo(ROOT_VECTOR);
    assertThat(GameplayAbilitySchemaProjection.canonicalBytes(source))
        .isEqualTo(ROOT_VECTOR.getBytes(StandardCharsets.UTF_8));
    assertThat(GameplayAbilitySchemaProjection.digest(source))
        .isEqualTo("sha256:659a321a8fd1a02a4825a81568d67dee5039a1cb538756fb91c212aeaea1c536");
  }

  @Test
  void allAbilityRootsAndOriginalFamilyOrderAffectTheProjection() {
    var values = copy(roots());
    values
        .get(Family.ABILITIES)
        .add(new GameplayRuleManifest.Ability("ability_b", "B", "action_b"));
    var both = new GameplayRuleManifest(values);
    var reversed = copy(both);
    java.util.Collections.reverse(reversed.get(Family.ABILITIES));
    assertThat(GameplayAbilitySchemaProjection.digest(both))
        .isNotEqualTo(GameplayAbilitySchemaProjection.digest(roots()));
    assertThat(GameplayAbilitySchemaProjection.digest(new GameplayRuleManifest(reversed)))
        .isNotEqualTo(GameplayAbilitySchemaProjection.digest(both));
    var actions = copy(both);
    java.util.Collections.reverse(actions.get(Family.ACTIONS));
    assertThat(GameplayAbilitySchemaProjection.digest(new GameplayRuleManifest(actions)))
        .isNotEqualTo(GameplayAbilitySchemaProjection.digest(both));
  }

  @Test
  void followsTypedTransitivePredicateCostTargetEffectAndFeedbackEdgesIncludingCycles() {
    var source = rich();
    var json =
        GameplayRuleManifest.tree(GameplayAbilitySchemaProjection.canonicalJson(source))
            .path("families");
    for (Family family :
        List.of(
            Family.ABILITIES,
            Family.ACTIONS,
            Family.ADMISSION_TAGS,
            Family.CONDITIONS,
            Family.DISPOSITIONS,
            Family.FEEDBACK,
            Family.OBSERVATION_POLICIES,
            Family.RESOURCES,
            Family.SELECTION_POLICIES,
            Family.STATS,
            Family.TARGETING_POLICIES))
      assertThat(json.path(family.name()).size()).as(family.name()).isEqualTo(1);
    assertThat(json.path("EFFECTS").size()).isEqualTo(2);
    for (Family family :
        List.of(Family.COMMANDS, Family.CONTINUOUS_OVERLAYS, Family.DEFAULT_BINDINGS))
      assertThat(json.path(family.name()).size()).as(family.name()).isZero();
    assertThat(json.path("EFFECTS").get(0).path("effectKey").textValue()).isEqualTo("drain");
    assertThat(json.path("EFFECTS").get(1).path("effectKey").textValue()).isEqualTo("mark_effect");
    assertThat(json.path("CONDITIONS").get(0).path("effectKeys").get(1).textValue())
        .isEqualTo("mark_effect");
  }

  @Test
  void retainsCompleteTransitiveFieldsAndNestedArrayOrdering() {
    var source = rich();
    var changed = copy(source);
    var resource = (GameplayRuleManifest.Resource) changed.get(Family.RESOURCES).getFirst();
    changed
        .get(Family.RESOURCES)
        .set(
            0,
            new GameplayRuleManifest.Resource(
                resource.statKey(),
                resource.defaultCurrent(),
                resource.minimum(),
                resource.defaultMaximum(),
                resource.hardMaximum(),
                resource.visibility(),
                List.of("changed")));
    assertThat(GameplayAbilitySchemaProjection.digest(new GameplayRuleManifest(changed)))
        .isNotEqualTo(GameplayAbilitySchemaProjection.digest(source));
    var reordered = copy(source);
    var condition = (GameplayRuleManifest.Condition) reordered.get(Family.CONDITIONS).getFirst();
    reordered
        .get(Family.CONDITIONS)
        .set(
            0,
            new GameplayRuleManifest.Condition(
                condition.conditionKey(),
                condition.reapplication(),
                condition.durationPolicy(),
                condition.durationTicks(),
                condition.maximumStacks(),
                condition.visibility(),
                condition.tags(),
                List.of("mark_effect", "drain"),
                condition.removalPriority()));
    assertThat(GameplayAbilitySchemaProjection.digest(new GameplayRuleManifest(reordered)))
        .isNotEqualTo(GameplayAbilitySchemaProjection.digest(source));
  }

  @Test
  void excludesUnreferencedCatalogAndCommandEditsButNotFromAggregate() {
    var source = rich();
    var changed = copy(source);
    changed
        .get(Family.STATS)
        .add(
            new GameplayRuleManifest.Stat(
                "unrelated",
                GameplayRuleManifest.PrimitiveKind.NUMERIC,
                BigDecimal.ONE,
                null,
                null,
                null,
                GameplayRuleManifest.Visibility.PUBLIC,
                List.of()));
    var command = (GameplayRuleManifest.Command) changed.get(Family.COMMANDS).getFirst();
    changed
        .get(Family.COMMANDS)
        .set(
            0,
            new GameplayRuleManifest.Command(
                command.commandId(),
                command.actionSequenceId(),
                command.semanticOwner(),
                command.executionDiscipline(),
                command.stageRequirement(),
                command.promptPolicy(),
                command.actionCategory(),
                command.historyRecordable(),
                List.of("different_alias"),
                command.actionTags(),
                command.admissionTags()));
    var other = new GameplayRuleManifest(changed);
    assertThat(GameplayAbilitySchemaProjection.digest(other))
        .isEqualTo(GameplayAbilitySchemaProjection.digest(source));
    assertThat(other.digest()).isNotEqualTo(source.digest());
  }

  @Test
  void rejectsIncompleteUnsupportedAndUnresolvedInputsEvenOutsideClosure() {
    var missing = copy(roots());
    missing.remove(Family.DEFAULT_BINDINGS);
    assertThatThrownBy(
            () -> GameplayAbilitySchemaProjection.digest(new GameplayRuleManifest(missing)))
        .isInstanceOf(IllegalArgumentException.class);
    var unresolved = copy(roots());
    unresolved
        .get(Family.COMMANDS)
        .add(
            new GameplayRuleManifest.Command(
                "unrelated",
                "missing",
                "GAME_LOGIC",
                GameplayRuleManifest.ExecutionDiscipline.DURABLE_GAMEPLAY,
                GameplayRuleManifest.Stage.GAMEPLAY,
                GameplayRuleManifest.PromptPolicy.NEVER,
                GameplayRuleManifest.ActionCategory.GAMEPLAY,
                true,
                List.of(),
                List.of(),
                List.of()));
    assertThatThrownBy(
            () -> GameplayAbilitySchemaProjection.digest(new GameplayRuleManifest(unresolved)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameplayRuleManifest.fromStored(
                    roots()
                        .canonicalJson()
                        .replace("\"families\":", "\"unknown\":0,\"families\":")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameplayRuleManifest.fromStored(
                    roots().canonicalJson().replace("gameplay-rule-manifest/v1", "unsupported/v1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void numericEffectAndComparatorEdgesResolveTheirExactDeclaredFamily() {
    var values = copy(rich());
    var original = (GameplayRuleManifest.Condition) values.get(Family.CONDITIONS).getFirst();
    values
        .get(Family.CONDITIONS)
        .set(
            0,
            new GameplayRuleManifest.Condition(
                original.conditionKey(),
                original.reapplication(),
                original.durationPolicy(),
                original.durationTicks(),
                original.maximumStacks(),
                original.visibility(),
                original.tags(),
                List.of("drain", "mark_effect", "boost", "capacity"),
                original.removalPriority()));
    values
        .get(Family.EFFECTS)
        .add(
            new GameplayRuleManifest.Effect(
                "capacity",
                GameplayRuleManifest.Lifecycle.CONTINUOUS,
                GameplayRuleManifest.EffectOperation.ADD,
                "mana",
                GameplayRuleManifest.StatField.MAXIMUM,
                BigDecimal.ONE,
                null,
                GameplayRuleManifest.CapacityChangePolicy.CLAMP_ONLY));
    var json =
        GameplayRuleManifest.tree(
                GameplayAbilitySchemaProjection.canonicalJson(new GameplayRuleManifest(values)))
            .path("families");
    assertThat(json.path("EFFECTS").size()).isEqualTo(4);
    assertThat(json.path("STATS").get(0).path("statKey").textValue()).isEqualTo("power");
    assertThat(json.path("RESOURCES").get(0).path("statKey").textValue()).isEqualTo("mana");
    values
        .get(Family.SELECTION_POLICIES)
        .set(
            0,
            new GameplayRuleManifest.SelectionPolicy(
                "select",
                GameplayRuleManifest.Cardinality.EXACTLY_ONE,
                null,
                null,
                GameplayRuleManifest.SelectionStrategy.RANKED,
                null,
                List.of(
                    new GameplayRuleManifest.RankComparator(
                        "power",
                        GameplayRuleManifest.StatField.VALUE,
                        GameplayRuleManifest.Direction.ASCENDING))));
    var statComparison = new GameplayRuleManifest(values);
    assertThat(
            GameplayRuleManifest.tree(GameplayAbilitySchemaProjection.canonicalJson(statComparison))
                .path("families")
                .path("SELECTION_POLICIES")
                .get(0)
                .path("comparators")
                .get(0)
                .path("statKey")
                .textValue())
        .isEqualTo("power");
  }

  private static GameplayRuleManifest roots() {
    return manifest(
        new GameplayRuleManifest.Ability("ability_a", "A", "action_a"),
        action("action_a", "A"),
        action("action_b", "B"));
  }

  private static GameplayRuleManifest.Action action(String id, String name) {
    return new GameplayRuleManifest.Action(
        id, name, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  private static GameplayRuleManifest rich() {
    var target =
        new GameplayRuleManifest.TargetSet("victim", "target", "select", false, "fb", null);
    var truth =
        new GameplayRuleManifest.Predicate(
            GameplayRuleManifest.PredicateKind.TRUE, null, null, null, null, List.of());
    return manifest(
        new GameplayRuleManifest.Ability("ability", "Ability", "act"),
        new GameplayRuleManifest.Action(
            "act",
            "Action",
            List.of("combat"),
            List.of(target),
            List.of(
                new GameplayRuleManifest.Cost(
                    "mana", BigDecimal.ONE, GameplayRuleManifest.CommitPolicy.ON_EXECUTION)),
            List.of(
                new GameplayRuleManifest.Cooldown(
                    "cooldown", 3, GameplayRuleManifest.CommitPolicy.ON_EXECUTION)),
            List.of(new GameplayRuleManifest.EffectBinding("mark_effect", "SOURCE")),
            List.of("fb")),
        new GameplayRuleManifest.Stat(
            "power",
            GameplayRuleManifest.PrimitiveKind.NUMERIC,
            BigDecimal.ONE,
            null,
            null,
            null,
            GameplayRuleManifest.Visibility.PUBLIC,
            List.of()),
        new GameplayRuleManifest.Resource(
            "mana",
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.TEN,
            BigDecimal.TEN,
            GameplayRuleManifest.Visibility.PUBLIC,
            List.of()),
        new GameplayRuleManifest.Condition(
            "mark",
            GameplayRuleManifest.Reapplication.REPLACE,
            GameplayRuleManifest.DurationPolicy.RESET,
            2L,
            null,
            GameplayRuleManifest.Visibility.PUBLIC,
            List.of(),
            List.of("drain", "mark_effect"),
            1),
        new GameplayRuleManifest.Effect(
            "drain",
            GameplayRuleManifest.Lifecycle.INSTANT,
            GameplayRuleManifest.EffectOperation.ADJUST_RESOURCE,
            "mana",
            null,
            BigDecimal.ONE.negate(),
            null,
            null),
        new GameplayRuleManifest.Effect(
            "mark_effect",
            GameplayRuleManifest.Lifecycle.INSTANT,
            GameplayRuleManifest.EffectOperation.APPLY_CONDITION,
            null,
            null,
            null,
            "mark",
            null),
        new GameplayRuleManifest.Effect(
            "boost",
            GameplayRuleManifest.Lifecycle.CONTINUOUS,
            GameplayRuleManifest.EffectOperation.ADD,
            "power",
            GameplayRuleManifest.StatField.VALUE,
            BigDecimal.ONE,
            null,
            null),
        new GameplayRuleManifest.AdmissionTag("combat"),
        new GameplayRuleManifest.Disposition("hostile", List.of("combat"), "fb"),
        new GameplayRuleManifest.ObservationPolicy(
            "observe",
            new GameplayRuleManifest.Predicate(
                GameplayRuleManifest.PredicateKind.CONDITION_PRESENT,
                GameplayRuleManifest.ActorRole.TARGET,
                "mark",
                null,
                null,
                List.of())),
        new GameplayRuleManifest.TargetingPolicy(
            "target",
            GameplayRuleManifest.CandidateSelector.DIRECT_ACTOR,
            "observe",
            new GameplayRuleManifest.Predicate(
                GameplayRuleManifest.PredicateKind.ALL,
                null,
                null,
                null,
                null,
                List.of(
                    new GameplayRuleManifest.Predicate(
                        GameplayRuleManifest.PredicateKind.STAT_COMPARE,
                        GameplayRuleManifest.ActorRole.TARGET,
                        "power",
                        GameplayRuleManifest.Comparison.GE,
                        BigDecimal.ONE,
                        List.of()),
                    new GameplayRuleManifest.Predicate(
                        GameplayRuleManifest.PredicateKind.DISPOSITION_EQUALS,
                        GameplayRuleManifest.ActorRole.TARGET,
                        "hostile",
                        null,
                        null,
                        List.of()))),
            "fb"),
        new GameplayRuleManifest.SelectionPolicy(
            "select",
            GameplayRuleManifest.Cardinality.EXACTLY_ONE,
            null,
            null,
            GameplayRuleManifest.SelectionStrategy.RANKED,
            null,
            List.of(
                new GameplayRuleManifest.RankComparator(
                    "mana",
                    GameplayRuleManifest.StatField.MAXIMUM,
                    GameplayRuleManifest.Direction.DESCENDING))),
        new GameplayRuleManifest.Feedback(
            "fb",
            "outcome",
            GameplayRuleManifest.Audience.SOURCE_ACTOR,
            "message",
            "Safe feedback",
            List.of(),
            GameplayRuleManifest.Visibility.PUBLIC,
            GameplayRuleManifest.ReplayPolicy.EFFECT_IDEMPOTENT),
        new GameplayRuleManifest.ContinuousOverlay(
            "unrelated_overlay", truth, List.of("combat"), List.of("boost")),
        new GameplayRuleManifest.DefaultBinding("unrelated_default", List.of(target)),
        new GameplayRuleManifest.Command(
            "unrelated_command",
            "act",
            "GAME_LOGIC",
            GameplayRuleManifest.ExecutionDiscipline.DURABLE_GAMEPLAY,
            GameplayRuleManifest.Stage.GAMEPLAY,
            GameplayRuleManifest.PromptPolicy.NEVER,
            GameplayRuleManifest.ActionCategory.GAMEPLAY,
            true,
            List.of("alias"),
            List.of(),
            List.of("combat")));
  }

  private static GameplayRuleManifest manifest(Definition... definitions) {
    Map<Family, List<Definition>> families = copy(GameplayRuleManifest.explicitEmpty());
    for (Definition definition : definitions) families.get(definition.family()).add(definition);
    return new GameplayRuleManifest(families);
  }

  private static Map<Family, List<Definition>> copy(GameplayRuleManifest source) {
    Map<Family, List<Definition>> families = new EnumMap<>(Family.class);
    source.families().forEach((family, values) -> families.put(family, new ArrayList<>(values)));
    return families;
  }
}
