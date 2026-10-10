package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Ability;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Action;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.ActionCategory;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.ActionTag;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.ActorRole;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.AdmissionTag;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Audience;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.CandidateSelector;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.CapacityChangePolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Cardinality;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Command;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.CommitPolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Condition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.ContinuousOverlay;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Cooldown;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Cost;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.DefaultBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Definition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Disposition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.DurationPolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Effect;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.EffectBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.EffectOperation;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.ExecutionDiscipline;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Feedback;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Lifecycle;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.ObservationPolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Predicate;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.PredicateKind;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.PrimitiveKind;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.PromptPolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Reapplication;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.ReplayPolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Resource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.SelectionPolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.SelectionStrategy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Stage;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Stat;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.StatField;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.TargetSet;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.TargetingPolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Visibility;
import org.junit.jupiter.api.Test;

class GameplayRuleSourceTest {
  private static final TargetProof TARGET =
      new TargetProof(
          UUID.randomUUID(),
          UUID.randomUUID(),
          1L,
          "private-owner-key",
          1L,
          "private-owner-key",
          "NEW_GAME_ROW");

  @Test
  void realNonemptyCatalogRetainsOrderedDeclarationsAndEveryOriginalRevision() {
    var manifest = exampleManifest();
    var binding =
        binding(
            manifest.families().values().stream()
                .flatMap(List::stream)
                .map(GameplayRuleSource::upsertPayload)
                .toList());
    var entries = GameplayRuleSource.replay(List.of(), binding);
    var snapshot = new GameplayRuleSnapshot(binding, "1", null, UUID.randomUUID(), entries);
    var read = GameplayRuleSnapshot.fromStored(snapshot.canonicalJson());

    assertThat(read.canonicalBytes()).containsExactly(snapshot.canonicalBytes());
    assertThat(read.manifest().canonicalBytes()).containsExactly(manifest.canonicalBytes());
    assertThat(read.entries()).hasSize(entries.size());
    assertThat(read.entries())
        .allSatisfy(entry -> assertThat(entry.sourceBinding()).isEqualTo(binding));
    var action = (Action) read.manifest().families().get(Family.ACTIONS).getFirst();
    assertThat(action.admissionTags()).containsExactly("gameplay", "canFocus");
    assertThat(action.effects())
        .extracting(EffectBinding::effectKey)
        .containsExactly("restore", "cleanse");
    var command = (Command) read.manifest().families().get(Family.COMMANDS).getFirst();
    assertThat(command.actionTags()).containsExactly(ActionTag.COMBAT);
    assertThat(command.admissionTags()).containsExactly("gameplay", "canFocus");
  }

  @Test
  void inheritedCatalogPreservesUnchangedProvenanceAndExplicitDelete() {
    var initial =
        binding(
            List.of(
                GameplayRuleSource.upsertPayload(new AdmissionTag("first")),
                GameplayRuleSource.upsertPayload(new AdmissionTag("second"))));
    var original = GameplayRuleSource.replay(List.of(), initial);
    var changed =
        binding(
            List.of(
                GameplayRuleSource.deletePayload(Family.ADMISSION_TAGS, "first"),
                GameplayRuleSource.upsertPayload(new AdmissionTag("third"))));
    var replayed = GameplayRuleSource.replay(original, changed);
    assertThat(replayed)
        .extracting(entry -> entry.definition().key())
        .containsExactly("second", "third");
    assertThat(replayed.getFirst().sourceBinding()).isEqualTo(initial);
    assertThat(replayed.getLast().sourceBinding()).isEqualTo(changed);
    assertThatThrownBy(
            () ->
                GameplayRuleSource.replay(
                    replayed,
                    binding(
                        List.of(
                            GameplayRuleSource.deletePayload(Family.ADMISSION_TAGS, "missing")))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void incompleteInventoryAndUnresolvedReferencesDenyInsteadOfBecomingEmpty() {
    var families =
        new EnumMap<Family, List<Definition>>(GameplayRuleManifest.explicitEmpty().families());
    families.remove(Family.FEEDBACK);
    assertThatThrownBy(() -> new GameplayRuleManifest(families))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameplayRuleSource.replay(
                    List.of(),
                    binding(
                        List.of(
                            GameplayRuleSource.upsertPayload(
                                new Ability("ability", "Ability", "missingAction"))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unresolved ACTIONS");
    assertThatThrownBy(
            () ->
                GameplayRuleManifest.fromStored(
                    "{\"schema\":\"gameplay-rule-manifest/v1\",\"families\":{}}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownDefinitionFieldsDuplicateFieldsAndMissingAdmissionTagsDeny() {
    String command =
        GameplayRuleManifest.canonical(
            exampleManifest().families().get(Family.COMMANDS).getFirst());
    assertThatThrownBy(
            () ->
                GameplayRuleManifest.definition(
                    Family.COMMANDS,
                    command.replace("\"admissionTags\":[\"gameplay\",\"canFocus\"],", "")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameplayRuleManifest.definition(
                    Family.ADMISSION_TAGS, "{\"tagKey\":\"tag\",\"unknownPolicy\":true}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameplayRuleManifest.definition(
                    Family.ADMISSION_TAGS, "{\"tagKey\":\"first\",\"tagKey\":\"second\"}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameplayRuleManifest.definition(Family.ADMISSION_TAGS, "{\"tagKey\":\"first\"} {}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameplayRuleSource.mutations(
                    binding(List.of("{\"revisionKind\":\"UNKNOWN_RULE_FAMILY\"}"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void continuousEffectsCannotBecomeInstantActionEffectsOrGrantDeniedAdmission() {
    var source = exampleManifest();
    var families = new EnumMap<Family, List<Definition>>(source.families());
    Action action = (Action) families.get(Family.ACTIONS).getFirst();
    families.put(
        Family.ACTIONS,
        List.of(
            new Action(
                action.key(),
                action.name(),
                action.admissionTags(),
                action.targetSets(),
                action.costs(),
                action.cooldowns(),
                List.of(new EffectBinding("capacity", "SOURCE")),
                action.feedbackKeys())));
    assertThatThrownBy(() -> new GameplayRuleManifest(families))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameplayRuleManifest.definition(
                    Family.CONTINUOUS_OVERLAYS,
                    GameplayRuleManifest.canonical(
                        Map.of(
                            "overlayKey",
                            "overlay",
                            "eligibleWhen",
                            truth(),
                            "deniedAdmissionTags",
                            List.of(),
                            "effectKeys",
                            List.of(),
                            "grantedAdmissionTags",
                            List.of("gameplay")))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static GameplayRuleManifest exampleManifest() {
    List<Definition> definitions =
        List.of(
            new Stat(
                "focus",
                PrimitiveKind.NUMERIC,
                BigDecimal.ONE,
                null,
                BigDecimal.ZERO,
                BigDecimal.TEN,
                Visibility.PUBLIC,
                List.of()),
            new Resource(
                "energy",
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.TEN,
                BigDecimal.valueOf(100),
                Visibility.PUBLIC,
                List.of()),
            new Condition(
                "weary",
                Reapplication.REFRESH,
                DurationPolicy.RESET,
                5L,
                null,
                Visibility.PUBLIC,
                List.of(),
                List.of(),
                0),
            new Effect(
                "restore",
                Lifecycle.INSTANT,
                EffectOperation.ADJUST_RESOURCE,
                "energy",
                null,
                BigDecimal.ONE,
                null,
                null),
            new Effect(
                "cleanse",
                Lifecycle.INSTANT,
                EffectOperation.REMOVE_CONDITION,
                null,
                null,
                null,
                "weary",
                null),
            new Effect(
                "capacity",
                Lifecycle.CONTINUOUS,
                EffectOperation.ADD,
                "energy",
                StatField.MAXIMUM,
                BigDecimal.ONE,
                null,
                CapacityChangePolicy.CLAMP_ONLY),
            new AdmissionTag("gameplay"),
            new AdmissionTag("canFocus"),
            new Feedback(
                "unavailable",
                "ACTION_REJECTED",
                Audience.SOURCE_ACTOR,
                "action.unavailable",
                "Unavailable.",
                List.of(),
                Visibility.PRIVATE,
                ReplayPolicy.EFFECT_IDEMPOTENT),
            new Disposition("ready", List.of(), "unavailable"),
            new ContinuousOverlay(
                "wearyOverlay",
                new Predicate(
                    PredicateKind.CONDITION_PRESENT,
                    ActorRole.SOURCE,
                    "weary",
                    null,
                    null,
                    List.of()),
                List.of("canFocus"),
                List.of("capacity")),
            new ObservationPolicy("observable", truth()),
            new TargetingPolicy(
                "self", CandidateSelector.SELF, "observable", truth(), "unavailable"),
            new SelectionPolicy(
                "one",
                Cardinality.EXACTLY_ONE,
                null,
                null,
                SelectionStrategy.CANONICAL_ORDER,
                null,
                List.of()),
            new DefaultBinding(
                "focusDefault", List.of(new TargetSet("subject", "self", "one", true, null, null))),
            new Action(
                "focusAction",
                "Focus",
                List.of("gameplay", "canFocus"),
                List.of(new TargetSet("subject", "self", "one", true, null, null)),
                List.of(new Cost("energy", BigDecimal.ONE, CommitPolicy.ON_EXECUTION)),
                List.of(new Cooldown("focusCooldown", 3L, CommitPolicy.ON_EFFECT_SUCCESS)),
                List.of(
                    new EffectBinding("restore", "subject"),
                    new EffectBinding("cleanse", "SOURCE")),
                List.of("unavailable")),
            new Ability("focusAbility", "Focus ability", "focusAction"),
            new Command(
                "focusCommand",
                "focusAction",
                "GAME_LOGIC",
                ExecutionDiscipline.DURABLE_GAMEPLAY,
                Stage.GAMEPLAY,
                PromptPolicy.WHEN_GAMEPLAY,
                ActionCategory.GAMEPLAY,
                true,
                List.of("focus"),
                List.of(ActionTag.COMBAT),
                List.of("gameplay", "canFocus")));
    Map<Family, List<Definition>> families = new EnumMap<>(Family.class);
    for (Family family : Family.values()) families.put(family, new ArrayList<>());
    for (Definition definition : definitions) families.get(definition.family()).add(definition);
    return new GameplayRuleManifest(families);
  }

  private static Predicate truth() {
    return new Predicate(PredicateKind.TRUE, null, null, null, null, List.of());
  }

  private static DraftCommitBinding binding(List<String> payloads) {
    List<RevisionPayload> revisions = new ArrayList<>();
    for (int i = 0; i < payloads.size(); i++)
      revisions.add(
          new RevisionPayload(
              Integer.toString(i),
              UUID.randomUUID(),
              Owner.GAME_DESIGN_CONTROL_PLANE,
              payloads.get(i)));
    return DraftCommitBinding.create(
        TARGET,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-commit-0",
        revisions,
        List.of(
            new AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.SCOPE,
                TARGET.canonicalVersionId().toString(),
                GameplayRuleSource.SCOPE,
                GameplayRuleSource.SCOPE_ID,
                "0")));
  }
}
