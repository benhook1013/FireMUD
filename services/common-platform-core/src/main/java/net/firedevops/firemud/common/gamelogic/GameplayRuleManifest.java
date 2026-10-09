package net.firedevops.firemud.common.gamelogic;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed authored input grammar. This value is neither source provenance nor mutation authority.
 */
public record GameplayRuleManifest(Map<Family, List<Definition>> families) {
  public static final String SCHEMA = "gameplay-rule-manifest/v1";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .build();

  public enum Family {
    STATS,
    RESOURCES,
    CONDITIONS,
    EFFECTS,
    ABILITIES,
    ACTIONS,
    COMMANDS,
    ADMISSION_TAGS,
    DISPOSITIONS,
    CONTINUOUS_OVERLAYS,
    OBSERVATION_POLICIES,
    TARGETING_POLICIES,
    SELECTION_POLICIES,
    DEFAULT_BINDINGS,
    FEEDBACK
  }

  public enum PrimitiveKind {
    NUMERIC,
    BOOLEAN_FLAG
  }

  public enum Visibility {
    PUBLIC,
    PRIVATE,
    NON_DISCLOSING
  }

  public enum Lifecycle {
    CONTINUOUS,
    INSTANT
  }

  public enum EffectOperation {
    ADD,
    MULTIPLY,
    CLAMP_MIN,
    CLAMP_MAX,
    ADJUST_RESOURCE,
    APPLY_CONDITION,
    REMOVE_CONDITION
  }

  public enum StatField {
    VALUE,
    MAXIMUM
  }

  public enum CapacityChangePolicy {
    CLAMP_ONLY,
    PRESERVE_RATIO,
    PRESERVE_DEFICIT
  }

  public enum Reapplication {
    REPLACE,
    REFRESH,
    STACK,
    PARALLEL
  }

  public enum DurationPolicy {
    RESET,
    EXTEND,
    PRESERVE_LONGER
  }

  public enum CommitPolicy {
    ON_EXECUTION,
    ON_EFFECT_SUCCESS
  }

  public enum CandidateSelector {
    SELF,
    DIRECT_ACTOR
  }

  public enum Cardinality {
    EXACTLY_ONE,
    UP_TO_N,
    ALL_ELIGIBLE
  }

  public enum SelectionStrategy {
    PLAYER_SELECTED,
    CANONICAL_ORDER,
    RANKED,
    RANDOM_SEEDED
  }

  public enum Direction {
    ASCENDING,
    DESCENDING
  }

  public enum ActorRole {
    SOURCE,
    TARGET
  }

  public enum PredicateKind {
    TRUE,
    FALSE,
    ALL,
    ANY,
    NOT,
    STAT_COMPARE,
    CONDITION_PRESENT,
    DISPOSITION_EQUALS
  }

  public enum Comparison {
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE
  }

  public enum Audience {
    SOURCE_ACTOR,
    DIRECT_TARGET,
    AUTHORIZED_OBSERVER
  }

  public enum ArgumentKind {
    TEXT,
    NUMBER,
    BOOLEAN,
    ACTOR_ID
  }

  public enum ReplayPolicy {
    EFFECT_IDEMPOTENT
  }

  public enum Stage {
    NONE,
    LOGIN,
    GAMEPLAY
  }

  public enum PromptPolicy {
    NEVER,
    WHEN_LOGGED_IN,
    WHEN_GAMEPLAY
  }

  public enum ActionCategory {
    GAMEPLAY,
    SOCIAL,
    META,
    ADMIN,
    SYSTEM
  }

  public enum ExecutionDiscipline {
    DURABLE_GAMEPLAY
  }

  public enum ActionTag {
    MOVEMENT,
    COMMUNICATION,
    COMBAT,
    INVENTORY,
    WORLD_BROWSE,
    SOCIAL_PRESENCE,
    AUTHORING,
    SESSION,
    UI
  }

  public sealed interface Definition
      permits Stat,
          Resource,
          Condition,
          Effect,
          Ability,
          Action,
          Command,
          AdmissionTag,
          Disposition,
          ContinuousOverlay,
          ObservationPolicy,
          TargetingPolicy,
          SelectionPolicy,
          DefaultBinding,
          Feedback {
    String key();

    Family family();
  }

  public record Stat(
      String statKey,
      PrimitiveKind primitiveKind,
      BigDecimal baseValue,
      Boolean flagValue,
      BigDecimal minimum,
      BigDecimal maximum,
      Visibility visibility,
      List<String> tags)
      implements Definition {
    public Stat {
      identifier(statKey);
      Objects.requireNonNull(primitiveKind);
      Objects.requireNonNull(visibility);
      numbers(baseValue, minimum, maximum);
      tags = keys(tags);
      if (primitiveKind == PrimitiveKind.NUMERIC) {
        Objects.requireNonNull(baseValue);
        bounds(minimum, baseValue, maximum);
        absent(flagValue);
      } else {
        Objects.requireNonNull(flagValue);
        absent(baseValue, minimum, maximum);
      }
    }

    @Override
    public String key() {
      return statKey;
    }

    @Override
    public Family family() {
      return Family.STATS;
    }

    public List<String> tags() {
      return List.copyOf(tags);
    }
  }

  /** A BOUNDED_RESOURCE is one typed resource, never an invented max_<stat> key. */
  public record Resource(
      String statKey,
      BigDecimal defaultCurrent,
      BigDecimal minimum,
      BigDecimal defaultMaximum,
      BigDecimal hardMaximum,
      Visibility visibility,
      List<String> tags)
      implements Definition {
    public Resource {
      identifier(statKey);
      Objects.requireNonNull(defaultCurrent);
      Objects.requireNonNull(minimum);
      Objects.requireNonNull(defaultMaximum);
      Objects.requireNonNull(hardMaximum);
      Objects.requireNonNull(visibility);
      tags = keys(tags);
      numbers(defaultCurrent, minimum, defaultMaximum, hardMaximum);
      bounds(minimum, defaultCurrent, defaultMaximum);
      bounds(minimum, defaultMaximum, hardMaximum);
    }

    @Override
    public String key() {
      return statKey;
    }

    @Override
    public Family family() {
      return Family.RESOURCES;
    }

    public List<String> tags() {
      return List.copyOf(tags);
    }
  }

  public record Condition(
      String conditionKey,
      Reapplication reapplication,
      DurationPolicy durationPolicy,
      Long durationTicks,
      Integer maximumStacks,
      Visibility visibility,
      List<String> tags,
      List<String> effectKeys,
      int removalPriority)
      implements Definition {
    public Condition {
      identifier(conditionKey);
      Objects.requireNonNull(reapplication);
      Objects.requireNonNull(durationPolicy);
      Objects.requireNonNull(visibility);
      tags = keys(tags);
      effectKeys = keys(effectKeys);
      ticks(Objects.requireNonNull(durationTicks));
      if (reapplication == Reapplication.STACK) {
        if (maximumStacks == null || maximumStacks < 1) fail("STACK requires maximumStacks");
      } else absent(maximumStacks);
    }

    @Override
    public String key() {
      return conditionKey;
    }

    @Override
    public Family family() {
      return Family.CONDITIONS;
    }

    public List<String> tags() {
      return List.copyOf(tags);
    }

    public List<String> effectKeys() {
      return List.copyOf(effectKeys);
    }
  }

  public record Effect(
      String effectKey,
      Lifecycle lifecycle,
      EffectOperation operation,
      String statKey,
      StatField statField,
      BigDecimal amount,
      String conditionKey,
      CapacityChangePolicy capacityChangePolicy)
      implements Definition {
    public Effect {
      identifier(effectKey);
      Objects.requireNonNull(lifecycle);
      Objects.requireNonNull(operation);
      numbers(amount);
      switch (operation) {
        case ADD, MULTIPLY, CLAMP_MIN, CLAMP_MAX -> {
          if (lifecycle != Lifecycle.CONTINUOUS) fail("Derived-state modifiers are CONTINUOUS");
          identifier(statKey);
          Objects.requireNonNull(statField);
          Objects.requireNonNull(amount);
          absent(conditionKey);
          if (statField != StatField.MAXIMUM) absent(capacityChangePolicy);
        }
        case ADJUST_RESOURCE -> {
          if (lifecycle != Lifecycle.INSTANT) fail("Resource adjustment is INSTANT");
          identifier(statKey);
          Objects.requireNonNull(amount);
          absent(statField, conditionKey, capacityChangePolicy);
        }
        case APPLY_CONDITION, REMOVE_CONDITION -> {
          if (lifecycle != Lifecycle.INSTANT) fail("Condition mutation is INSTANT");
          identifier(conditionKey);
          absent(statKey, statField, amount, capacityChangePolicy);
        }
      }
    }

    @Override
    public String key() {
      return effectKey;
    }

    @Override
    public Family family() {
      return Family.EFFECTS;
    }
  }

  public record Ability(String abilityId, String name, String actionSequenceId)
      implements Definition {
    public Ability {
      identifier(abilityId);
      text(name);
      identifier(actionSequenceId);
    }

    @Override
    public String key() {
      return abilityId;
    }

    @Override
    public Family family() {
      return Family.ABILITIES;
    }
  }

  public record Cost(String statKey, BigDecimal amount, CommitPolicy commitPolicy) {
    public Cost {
      identifier(statKey);
      Objects.requireNonNull(amount);
      Objects.requireNonNull(commitPolicy);
      numbers(amount);
      if (amount.signum() < 0) fail("Cost cannot be negative");
    }
  }

  public record Cooldown(String cooldownKey, long durationTicks, CommitPolicy commitPolicy) {
    public Cooldown {
      identifier(cooldownKey);
      Objects.requireNonNull(commitPolicy);
      ticks(durationTicks);
    }
  }

  public record TargetSet(
      String targetSetKey,
      String targetingPolicyKey,
      String targetSelectionPolicyKey,
      boolean required,
      String unresolvedFeedbackKey,
      String playerSelectorInputSlot) {
    public TargetSet {
      identifier(targetSetKey);
      identifier(targetingPolicyKey);
      identifier(targetSelectionPolicyKey);
      if ("SOURCE".equals(targetSetKey)) fail("SOURCE is the implicit reserved target set");
      if (required) absent(unresolvedFeedbackKey);
      else identifier(unresolvedFeedbackKey);
      if (playerSelectorInputSlot != null) identifier(playerSelectorInputSlot);
    }
  }

  public record EffectBinding(String effectKey, String targetSetKey) {
    public EffectBinding {
      identifier(effectKey);
      identifier(targetSetKey);
    }
  }

  public record Action(
      String actionSequenceId,
      String name,
      List<String> admissionTags,
      List<TargetSet> targetSets,
      List<Cost> costs,
      List<Cooldown> cooldowns,
      List<EffectBinding> effects,
      List<String> feedbackKeys)
      implements Definition {
    public Action {
      identifier(actionSequenceId);
      text(name);
      admissionTags = keys(admissionTags);
      targetSets = copy(targetSets);
      costs = copy(costs);
      cooldowns = copy(cooldowns);
      effects = copy(effects);
      feedbackKeys = keys(feedbackKeys);
      unique(targetSets.stream().map(TargetSet::targetSetKey).toList());
      unique(cooldowns.stream().map(Cooldown::cooldownKey).toList());
      Set<String> targets =
          new HashSet<>(targetSets.stream().map(TargetSet::targetSetKey).toList());
      targets.add("SOURCE");
      for (EffectBinding effect : effects)
        if (!targets.contains(effect.targetSetKey())) fail("Effect uses undeclared target set");
    }

    @Override
    public String key() {
      return actionSequenceId;
    }

    @Override
    public Family family() {
      return Family.ACTIONS;
    }

    public List<String> admissionTags() {
      return List.copyOf(admissionTags);
    }

    public List<TargetSet> targetSets() {
      return List.copyOf(targetSets);
    }

    public List<Cost> costs() {
      return List.copyOf(costs);
    }

    public List<Cooldown> cooldowns() {
      return List.copyOf(cooldowns);
    }

    public List<EffectBinding> effects() {
      return List.copyOf(effects);
    }

    public List<String> feedbackKeys() {
      return List.copyOf(feedbackKeys);
    }
  }

  public record Command(
      String commandId,
      String actionSequenceId,
      String semanticOwner,
      ExecutionDiscipline executionDiscipline,
      Stage stageRequirement,
      PromptPolicy promptPolicy,
      ActionCategory actionCategory,
      boolean historyRecordable,
      List<String> aliases,
      List<ActionTag> actionTags,
      List<String> admissionTags)
      implements Definition {
    public Command {
      identifier(commandId);
      identifier(actionSequenceId);
      text(semanticOwner);
      Objects.requireNonNull(executionDiscipline);
      Objects.requireNonNull(stageRequirement);
      Objects.requireNonNull(promptPolicy);
      Objects.requireNonNull(actionCategory);
      aliases = keys(aliases);
      actionTags = copy(actionTags);
      admissionTags = keys(admissionTags);
      if (new HashSet<>(actionTags).size() != actionTags.size()) fail("Duplicate action tag");
    }

    @Override
    public String key() {
      return commandId;
    }

    @Override
    public Family family() {
      return Family.COMMANDS;
    }

    public List<String> aliases() {
      return List.copyOf(aliases);
    }

    public List<ActionTag> actionTags() {
      return List.copyOf(actionTags);
    }

    public List<String> admissionTags() {
      return List.copyOf(admissionTags);
    }
  }

  public record AdmissionTag(String tagKey) implements Definition {
    public AdmissionTag {
      identifier(tagKey);
    }

    @Override
    public String key() {
      return tagKey;
    }

    @Override
    public Family family() {
      return Family.ADMISSION_TAGS;
    }
  }

  public record Disposition(
      String dispositionKey, List<String> deniedAdmissionTags, String safeFeedbackKey)
      implements Definition {
    public Disposition {
      identifier(dispositionKey);
      deniedAdmissionTags = keys(deniedAdmissionTags);
      identifier(safeFeedbackKey);
    }

    @Override
    public String key() {
      return dispositionKey;
    }

    @Override
    public Family family() {
      return Family.DISPOSITIONS;
    }

    public List<String> deniedAdmissionTags() {
      return List.copyOf(deniedAdmissionTags);
    }
  }

  public record ContinuousOverlay(
      String overlayKey,
      Predicate eligibleWhen,
      List<String> deniedAdmissionTags,
      List<String> effectKeys)
      implements Definition {
    public ContinuousOverlay {
      identifier(overlayKey);
      Objects.requireNonNull(eligibleWhen);
      deniedAdmissionTags = keys(deniedAdmissionTags);
      effectKeys = keys(effectKeys);
    }

    @Override
    public String key() {
      return overlayKey;
    }

    @Override
    public Family family() {
      return Family.CONTINUOUS_OVERLAYS;
    }

    public List<String> deniedAdmissionTags() {
      return List.copyOf(deniedAdmissionTags);
    }

    public List<String> effectKeys() {
      return List.copyOf(effectKeys);
    }
  }

  /** Only already declared Entity-owned actor facts; spatial/relationship extensions deny. */
  public record Predicate(
      PredicateKind kind,
      ActorRole actor,
      String factKey,
      Comparison comparison,
      BigDecimal number,
      List<Predicate> operands) {
    public Predicate {
      Objects.requireNonNull(kind);
      operands = copy(operands);
      numbers(number);
      if (operands.size() > 64) fail("Predicate operand ceiling exceeded");
      switch (kind) {
        case TRUE, FALSE -> {
          absent(actor, factKey, comparison, number);
          empty(operands);
        }
        case ALL, ANY, NOT -> {
          absent(actor, factKey, comparison, number);
          if (operands.isEmpty() || kind == PredicateKind.NOT && operands.size() != 1)
            fail("Invalid predicate operands");
        }
        case STAT_COMPARE -> {
          Objects.requireNonNull(actor);
          identifier(factKey);
          Objects.requireNonNull(comparison);
          Objects.requireNonNull(number);
          empty(operands);
        }
        case CONDITION_PRESENT, DISPOSITION_EQUALS -> {
          Objects.requireNonNull(actor);
          identifier(factKey);
          absent(comparison, number);
          empty(operands);
        }
      }
    }

    public List<Predicate> operands() {
      return List.copyOf(operands);
    }
  }

  public record ObservationPolicy(String observationPolicyKey, Predicate observableWhen)
      implements Definition {
    public ObservationPolicy {
      identifier(observationPolicyKey);
      Objects.requireNonNull(observableWhen);
    }

    @Override
    public String key() {
      return observationPolicyKey;
    }

    @Override
    public Family family() {
      return Family.OBSERVATION_POLICIES;
    }
  }

  public record TargetingPolicy(
      String targetingPolicyKey,
      CandidateSelector candidateSelector,
      String observationPolicyKey,
      Predicate eligibleWhen,
      String safeFailureFeedbackKey)
      implements Definition {
    public TargetingPolicy {
      identifier(targetingPolicyKey);
      Objects.requireNonNull(candidateSelector);
      identifier(observationPolicyKey);
      Objects.requireNonNull(eligibleWhen);
      identifier(safeFailureFeedbackKey);
    }

    @Override
    public String key() {
      return targetingPolicyKey;
    }

    @Override
    public Family family() {
      return Family.TARGETING_POLICIES;
    }
  }

  public record RankComparator(String statKey, StatField statField, Direction direction) {
    public RankComparator {
      identifier(statKey);
      Objects.requireNonNull(statField);
      Objects.requireNonNull(direction);
    }
  }

  public record SelectionPolicy(
      String targetSelectionPolicyKey,
      Cardinality cardinality,
      Integer minTargets,
      Integer maxTargets,
      SelectionStrategy strategy,
      SelectionStrategy truncationStrategy,
      List<RankComparator> comparators)
      implements Definition {
    public SelectionPolicy {
      identifier(targetSelectionPolicyKey);
      Objects.requireNonNull(cardinality);
      Objects.requireNonNull(strategy);
      comparators = copy(comparators);
      if (cardinality == Cardinality.UP_TO_N) {
        if (minTargets == null
            || maxTargets == null
            || minTargets < 0
            || maxTargets < 1
            || minTargets > maxTargets) fail("UP_TO_N requires explicit valid bounds");
      } else absent(minTargets, maxTargets);
      if (cardinality == Cardinality.ALL_ELIGIBLE) Objects.requireNonNull(truncationStrategy);
      else absent(truncationStrategy);
      if (strategy == SelectionStrategy.RANKED || truncationStrategy == SelectionStrategy.RANKED) {
        if (comparators.isEmpty()) fail("RANKED requires comparators");
      } else empty(comparators);
    }

    @Override
    public String key() {
      return targetSelectionPolicyKey;
    }

    @Override
    public Family family() {
      return Family.SELECTION_POLICIES;
    }

    public List<RankComparator> comparators() {
      return List.copyOf(comparators);
    }
  }

  public record DefaultBinding(String bindingKey, List<TargetSet> targetSets)
      implements Definition {
    public DefaultBinding {
      identifier(bindingKey);
      targetSets = copy(targetSets);
      unique(targetSets.stream().map(TargetSet::targetSetKey).toList());
    }

    @Override
    public String key() {
      return bindingKey;
    }

    @Override
    public Family family() {
      return Family.DEFAULT_BINDINGS;
    }

    public List<TargetSet> targetSets() {
      return List.copyOf(targetSets);
    }
  }

  public record Argument(String key, ArgumentKind kind) {
    public Argument {
      identifier(key);
      Objects.requireNonNull(kind);
    }
  }

  public record Feedback(
      String feedbackKey,
      String outcomeEvent,
      Audience audience,
      String messageKey,
      String messageTemplate,
      List<Argument> arguments,
      Visibility visibility,
      ReplayPolicy replayPolicy)
      implements Definition {
    public Feedback {
      identifier(feedbackKey);
      identifier(outcomeEvent);
      Objects.requireNonNull(audience);
      identifier(messageKey);
      text(messageTemplate);
      arguments = copy(arguments);
      unique(arguments.stream().map(Argument::key).toList());
      Objects.requireNonNull(visibility);
      Objects.requireNonNull(replayPolicy);
    }

    @Override
    public String key() {
      return feedbackKey;
    }

    @Override
    public Family family() {
      return Family.FEEDBACK;
    }

    public List<Argument> arguments() {
      return List.copyOf(arguments);
    }
  }

  public GameplayRuleManifest {
    Objects.requireNonNull(families, "Explicit complete family inventory required");
    if (!families.keySet().equals(Set.of(Family.values()))) fail("Missing/unknown rule family");
    EnumMap<Family, List<Definition>> retained = new EnumMap<>(Family.class);
    families.forEach(
        (family, definitions) -> {
          List<Definition> values = copy(definitions);
          unique(values.stream().map(Definition::key).toList());
          for (Definition definition : values)
            if (definition.family() != family) fail("Definition belongs to another family");
          retained.put(family, values);
        });
    families = java.util.Collections.unmodifiableMap(retained);
    validateReferences(families);
  }

  /** Only the actual fresh-source insertion transaction may use this as empty-source evidence. */
  public static GameplayRuleManifest explicitEmpty() {
    EnumMap<Family, List<Definition>> families = new EnumMap<>(Family.class);
    for (Family family : Family.values()) families.put(family, List.of());
    return new GameplayRuleManifest(families);
  }

  public String canonicalJson() {
    Map<String, Object> inventory = new LinkedHashMap<>();
    for (Family family : Family.values()) inventory.put(family.name(), families.get(family));
    return canonical(Map.of("schema", SCHEMA, "families", inventory));
  }

  public byte[] canonicalBytes() {
    return canonicalJson().getBytes(StandardCharsets.UTF_8);
  }

  public String digest() {
    return sha256(canonicalBytes());
  }

  public static GameplayRuleManifest fromStored(String json) {
    JsonNode root = tree(json);
    fields(root, Set.of("schema", "families"));
    if (!SCHEMA.equals(root.path("schema").asText())) fail("Unsupported manifest schema");
    Set<String> names = new HashSet<>();
    for (Family family : Family.values()) names.add(family.name());
    fields(root.path("families"), names);
    EnumMap<Family, List<Definition>> families = new EnumMap<>(Family.class);
    for (Family family : Family.values()) {
      JsonNode array = root.path("families").path(family.name());
      if (!array.isArray()) fail("Explicit family array required");
      List<Definition> values = new ArrayList<>();
      for (JsonNode value : array) values.add(definition(family, canonical(value)));
      families.put(family, values);
    }
    GameplayRuleManifest result = new GameplayRuleManifest(families);
    if (!result.canonicalJson().equals(json)) fail("Noncanonical manifest");
    return result;
  }

  public static Definition definition(Family family, String json) {
    Class<? extends Definition> type =
        switch (family) {
          case STATS -> Stat.class;
          case RESOURCES -> Resource.class;
          case CONDITIONS -> Condition.class;
          case EFFECTS -> Effect.class;
          case ABILITIES -> Ability.class;
          case ACTIONS -> Action.class;
          case COMMANDS -> Command.class;
          case ADMISSION_TAGS -> AdmissionTag.class;
          case DISPOSITIONS -> Disposition.class;
          case CONTINUOUS_OVERLAYS -> ContinuousOverlay.class;
          case OBSERVATION_POLICIES -> ObservationPolicy.class;
          case TARGETING_POLICIES -> TargetingPolicy.class;
          case SELECTION_POLICIES -> SelectionPolicy.class;
          case DEFAULT_BINDINGS -> DefaultBinding.class;
          case FEEDBACK -> Feedback.class;
        };
    try {
      Definition result = JSON.treeToValue(tree(json), type);
      if (!canonical(result).equals(canonical(tree(json))))
        fail("Definition has coerced or omitted typed fields");
      return result;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid typed rule definition", invalid);
    }
  }

  private static void validateReferences(Map<Family, List<Definition>> families) {
    Map<Family, Set<String>> keys = new EnumMap<>(Family.class);
    families.forEach(
        (family, values) ->
            keys.put(family, Set.copyOf(values.stream().map(Definition::key).toList())));
    Set<String> stats = new HashSet<>(keys.get(Family.STATS));
    for (String resource : keys.get(Family.RESOURCES))
      if (!stats.add(resource)) fail("Resource/stat key collision");
    Set<String> numericStats = new HashSet<>(keys.get(Family.RESOURCES));
    for (Definition definition : families.get(Family.STATS)) {
      Stat stat = (Stat) definition;
      if (stat.primitiveKind() == PrimitiveKind.NUMERIC) numericStats.add(stat.key());
    }
    for (List<Definition> values : families.values())
      for (Definition definition : values) {
        if (definition instanceof Condition value) refs(keys, Family.EFFECTS, value.effectKeys());
        else if (definition instanceof Effect value) {
          if (value.statKey() != null) {
            if (value.operation() == EffectOperation.ADJUST_RESOURCE
                || value.statField() == StatField.MAXIMUM)
              ref(keys, Family.RESOURCES, value.statKey());
            else if (!numericStats.contains(value.statKey()))
              fail("Effect requires a declared numeric stat");
          }
          if (value.conditionKey() != null) ref(keys, Family.CONDITIONS, value.conditionKey());
        } else if (definition instanceof Ability value)
          ref(keys, Family.ACTIONS, value.actionSequenceId());
        else if (definition instanceof Action value) {
          refs(keys, Family.ADMISSION_TAGS, value.admissionTags());
          targets(keys, families, value.targetSets());
          for (Cost cost : value.costs()) ref(keys, Family.RESOURCES, cost.statKey());
          for (EffectBinding effect : value.effects()) {
            ref(keys, Family.EFFECTS, effect.effectKey());
            if (effect(families, effect.effectKey()).lifecycle() != Lifecycle.INSTANT)
              fail("Action effect must be INSTANT");
          }
          refs(keys, Family.FEEDBACK, value.feedbackKeys());
        } else if (definition instanceof Command value) {
          ref(keys, Family.ACTIONS, value.actionSequenceId());
          refs(keys, Family.ADMISSION_TAGS, value.admissionTags());
        } else if (definition instanceof Disposition value) {
          refs(keys, Family.ADMISSION_TAGS, value.deniedAdmissionTags());
          ref(keys, Family.FEEDBACK, value.safeFeedbackKey());
        } else if (definition instanceof ContinuousOverlay value) {
          predicate(keys, numericStats, value.eligibleWhen(), 0);
          refs(keys, Family.ADMISSION_TAGS, value.deniedAdmissionTags());
          refs(keys, Family.EFFECTS, value.effectKeys());
          for (String key : value.effectKeys())
            if (effect(families, key).lifecycle() != Lifecycle.CONTINUOUS)
              fail("Overlay effect must be CONTINUOUS");
        } else if (definition instanceof ObservationPolicy value)
          predicate(keys, numericStats, value.observableWhen(), 0);
        else if (definition instanceof TargetingPolicy value) {
          ref(keys, Family.OBSERVATION_POLICIES, value.observationPolicyKey());
          predicate(keys, numericStats, value.eligibleWhen(), 0);
          ref(keys, Family.FEEDBACK, value.safeFailureFeedbackKey());
        } else if (definition instanceof SelectionPolicy value) {
          for (RankComparator comparator : value.comparators()) {
            if (!numericStats.contains(comparator.statKey()))
              fail("Comparator requires a declared numeric stat");
            if (comparator.statField() == StatField.MAXIMUM)
              ref(keys, Family.RESOURCES, comparator.statKey());
          }
        } else if (definition instanceof DefaultBinding value)
          targets(keys, families, value.targetSets());
      }
    Set<String> commandTokens = new HashSet<>();
    for (Definition definition : families.get(Family.COMMANDS)) {
      Command command = (Command) definition;
      for (String token :
          java.util.stream.Stream.concat(
                  java.util.stream.Stream.of(command.key()), command.aliases().stream())
              .toList())
        if (!commandTokens.add(token.toLowerCase(java.util.Locale.ROOT)))
          fail("Command token collision");
    }
  }

  private static Effect effect(Map<Family, List<Definition>> families, String key) {
    return (Effect)
        families.get(Family.EFFECTS).stream()
            .filter(value -> value.key().equals(key))
            .findFirst()
            .orElseThrow();
  }

  private static void targets(
      Map<Family, Set<String>> keys,
      Map<Family, List<Definition>> families,
      List<TargetSet> targets) {
    for (TargetSet target : targets) {
      ref(keys, Family.TARGETING_POLICIES, target.targetingPolicyKey());
      ref(keys, Family.SELECTION_POLICIES, target.targetSelectionPolicyKey());
      if (target.unresolvedFeedbackKey() != null)
        ref(keys, Family.FEEDBACK, target.unresolvedFeedbackKey());
      SelectionPolicy policy =
          (SelectionPolicy)
              families.get(Family.SELECTION_POLICIES).stream()
                  .filter(value -> value.key().equals(target.targetSelectionPolicyKey()))
                  .findFirst()
                  .orElseThrow();
      if ((target.playerSelectorInputSlot() != null)
          != (policy.strategy() == SelectionStrategy.PLAYER_SELECTED
              || policy.truncationStrategy() == SelectionStrategy.PLAYER_SELECTED))
        fail("Target selector input does not match selection policy");
    }
  }

  private static void predicate(
      Map<Family, Set<String>> keys, Set<String> stats, Predicate value, int depth) {
    if (depth > 32 || value.operands().size() > 64) fail("Predicate exceeds bounded grammar");
    switch (value.kind()) {
      case STAT_COMPARE -> {
        if (!stats.contains(value.factKey())) fail("Unknown predicate stat");
      }
      case CONDITION_PRESENT -> ref(keys, Family.CONDITIONS, value.factKey());
      case DISPOSITION_EQUALS -> ref(keys, Family.DISPOSITIONS, value.factKey());
      default -> {}
    }
    for (Predicate operand : value.operands()) predicate(keys, stats, operand, depth + 1);
  }

  private static void refs(Map<Family, Set<String>> keys, Family family, List<String> values) {
    for (String value : values) ref(keys, family, value);
  }

  private static void ref(Map<Family, Set<String>> keys, Family family, String key) {
    if (!keys.get(family).contains(key)) fail("Unresolved " + family + " reference: " + key);
  }

  public static String canonical(Object value) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)),
          StandardCharsets.UTF_8);
    } catch (java.io.IOException | RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid rule JSON", invalid);
    }
  }

  public static JsonNode tree(String json) {
    try {
      return JSON.readTree(json);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid rule JSON", invalid);
    }
  }

  public static void fields(JsonNode node, Set<String> expected) {
    Set<String> actual = new HashSet<>();
    node.propertyNames().forEach(actual::add);
    if (!node.isObject() || !actual.equals(expected)) fail("Unknown or missing rule fields");
  }

  public static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static void identifier(String value) {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}"))
      fail("Invalid authored identifier");
  }

  private static void text(String value) {
    if (value == null || value.isBlank()) fail("Required text missing");
  }

  private static <T> List<T> copy(List<T> values) {
    return List.copyOf(Objects.requireNonNull(values));
  }

  private static List<String> keys(List<String> values) {
    List<String> result = copy(values);
    result.forEach(GameplayRuleManifest::identifier);
    unique(result);
    return result;
  }

  private static void unique(List<String> values) {
    if (new HashSet<>(values).size() != values.size()) fail("Duplicate authored key");
  }

  private static void absent(Object... values) {
    for (Object value : values)
      if (value != null) fail("Field is not valid for this typed declaration");
  }

  private static void empty(List<?> values) {
    if (!values.isEmpty()) fail("Unexpected declaration values");
  }

  private static void bounds(BigDecimal minimum, BigDecimal value, BigDecimal maximum) {
    if (minimum != null && value.compareTo(minimum) < 0
        || maximum != null && value.compareTo(maximum) > 0
        || minimum != null && maximum != null && minimum.compareTo(maximum) > 0)
      fail("Invalid numeric bounds");
  }

  private static void numbers(BigDecimal... values) {
    for (BigDecimal value : values)
      if (value != null) {
        double number = value.doubleValue();
        if (!Double.isFinite(number) || BigDecimal.valueOf(number).compareTo(value) != 0)
          fail("Number cannot be retained exactly by the canonical JSON schema");
      }
  }

  private static void ticks(long value) {
    if (value < 1 || value > 9007199254740991L)
      fail("Ticks must be a canonical positive safe integer");
  }

  private static void fail(String message) {
    throw new IllegalArgumentException(message);
  }
}
