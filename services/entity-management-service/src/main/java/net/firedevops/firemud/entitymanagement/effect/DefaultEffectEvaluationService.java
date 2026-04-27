package net.firedevops.firemud.entitymanagement.effect;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class DefaultEffectEvaluationService implements EffectEvaluationService {
  private static final Comparator<EffectModifier> MODIFIER_ORDER =
      Comparator.comparingInt(EffectModifier::priority)
          .thenComparing(modifier -> modifier.source().sourceType())
          .thenComparing(modifier -> modifier.source().sourceId())
          .thenComparing(modifier -> modifier.operation().name())
          .thenComparing(EffectModifier::targetKey);

  @Override
  public EffectEvaluationResult evaluate(EffectEvaluationInput input) {
    Map<String, MutableResource> resources = new TreeMap<>();
    input
        .baseResources()
        .forEach(resource -> resources.put(resource.statKey(), new MutableResource(resource)));
    List<EffectModifier> modifiers = input.modifiers().stream().sorted(MODIFIER_ORDER).toList();

    applyNumeric(resources, modifiers, EffectOperation.ADD);
    applyNumeric(resources, modifiers, EffectOperation.MULTIPLY);
    applyNumeric(resources, modifiers, EffectOperation.CLAMP_MIN);
    applyNumeric(resources, modifiers, EffectOperation.CLAMP_MAX);

    List<GrantedActorState> grantedStates =
        modifiers.stream()
            .filter(
                modifier ->
                    modifier.operation() == EffectOperation.GRANT_FLAG
                        || modifier.operation() == EffectOperation.GRANT_CONDITION)
            .map(
                modifier ->
                    new GrantedActorState(
                        modifier.targetKey(),
                        modifier.operation(),
                        modifier.scope(),
                        modifier.source()))
            .toList();
    return new EffectEvaluationResult(
        resources.values().stream().map(MutableResource::toEvaluated).toList(), grantedStates);
  }

  private void applyNumeric(
      Map<String, MutableResource> resources,
      List<EffectModifier> modifiers,
      EffectOperation operation) {
    modifiers.stream()
        .filter(modifier -> modifier.operation() == operation)
        .forEach(modifier -> applyNumeric(resources, modifier));
  }

  private void applyNumeric(Map<String, MutableResource> resources, EffectModifier modifier) {
    MutableResource resource =
        resources.computeIfAbsent(modifier.targetKey(), MutableResource::newSynthetic);
    resource.sources.add(modifier.source());
    switch (modifier.operation()) {
      case ADD -> resource.value = resource.value.add(modifier.value());
      case MULTIPLY -> resource.value = resource.value.multiply(modifier.value());
      case CLAMP_MIN -> resource.value = resource.value.max(modifier.value());
      case CLAMP_MAX -> resource.value = resource.value.min(modifier.value());
      case GRANT_FLAG, GRANT_CONDITION ->
          throw new IllegalArgumentException("not a numeric modifier");
    }
  }

  private static final class MutableResource {
    private final String statKey;
    private final Long maxValue;
    private final Long baseValue;
    private final String primitiveKind;
    private final List<EffectSource> sources = new ArrayList<>();
    private BigDecimal value;

    private MutableResource(EffectResourceValue resource) {
      this.statKey = resource.statKey();
      this.maxValue = resource.maxValue();
      this.baseValue = resource.baseValue();
      this.primitiveKind = resource.primitiveKind();
      this.value = BigDecimal.valueOf(resource.currentValue());
      this.sources.add(resource.source());
    }

    private MutableResource(String statKey) {
      this.statKey = statKey;
      this.maxValue = null;
      this.baseValue = null;
      this.primitiveKind = "INTEGER";
      this.value = BigDecimal.ZERO;
    }

    private static MutableResource newSynthetic(String statKey) {
      return new MutableResource(statKey);
    }

    private EvaluatedResourceValue toEvaluated() {
      return new EvaluatedResourceValue(
          statKey,
          value.setScale(0, RoundingMode.HALF_UP).longValueExact(),
          maxValue,
          baseValue,
          primitiveKind,
          sources);
    }
  }
}
