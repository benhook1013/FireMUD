package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.mapper.ScriptDefinitionMapper;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.service.ScriptDefinitionService;
import net.firedevops.firemud.automationscripting.service.ScriptEventRegistryService;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ScriptDefinitionServiceImpl implements ScriptDefinitionService {
  private static final String DEFAULT_EVENT_SCHEMA_VERSION = "v1";
  private static final String PRIORITY_HIGH = "high";
  private static final String PRIORITY_NORMAL = "normal";
  private static final String PRIORITY_BACKGROUND = "background";

  private final ScriptDefinitionRepository repository;
  private final ScriptEventBindingRepository bindingRepository;
  private final ScriptDefinitionMapper mapper;
  private final SagaRunner sagaRunner;
  private final ScriptEventRegistryService eventRegistryService;

  @Override
  @Transactional
  @Timed(value = "script.update")
  public ScriptDefinitionDto updateScript(ScriptDefinitionDto dto) throws SagaException {
    validateBindings(dto);
    ScriptDefinition entity = mapper.toEntity(dto);
    ScriptDefinition previousDefinition =
        repository
            .findByTenantIdAndScriptVersionAndName(dto.tenantId(), dto.version(), dto.name())
            .orElse(null);
    List<ScriptEventBinding> previousBindings = snapshotBindings(dto);
    AtomicReference<ScriptDefinition> persisted = new AtomicReference<>(entity);
    var saga =
        new SagaBuilder("updateScript")
            .step(
                "persistScript",
                () -> persisted.set(repository.save(entity)),
                () -> compensateDefinition(previousDefinition, persisted.get()))
            .step(
                "replaceEventBindings",
                () -> replaceEventBindings(dto),
                () -> restoreBindings(dto, previousBindings))
            .build();
    sagaRunner.run(saga);
    return mapper.toDto(persisted.get());
  }

  private List<ScriptEventBinding> snapshotBindings(ScriptDefinitionDto dto) {
    return bindingRepository.findByTenantIdAndScriptPatchVersionAndScriptId(
        dto.tenantId(), dto.version(), dto.name());
  }

  private void compensateDefinition(
      ScriptDefinition previousDefinition, ScriptDefinition persistedDefinition) {
    if (previousDefinition == null) {
      // No row existed when this request began, so only this request's newly
      // persisted natural-key row is eligible for cleanup.
      repository.delete(persistedDefinition);
      return;
    }
    // The upsert may have advanced the row version. Restore through the row
    // that was just persisted so optimistic locking remains valid, while
    // retaining the pre-request definition content and identity.
    ScriptDefinition restored = copyDefinition(previousDefinition);
    restored.setId(persistedDefinition.getId());
    restored.setRowVersion(persistedDefinition.getRowVersion());
    repository.save(restored);
  }

  private void restoreBindings(ScriptDefinitionDto dto, List<ScriptEventBinding> previousBindings) {
    bindingRepository.deleteByTenantIdAndScriptPatchVersionAndScriptId(
        dto.tenantId(), dto.version(), dto.name());
    if (previousBindings.isEmpty()) {
      return;
    }
    // The replace step deleted the old rows. Insert value copies rather than
    // attempting optimistic updates against those now-absent primary keys.
    previousBindings.forEach(
        binding -> bindingRepository.restoreWithId(copyBindingForInsert(binding)));
  }

  private static ScriptDefinition copyDefinition(ScriptDefinition source) {
    ScriptDefinition copy = new ScriptDefinition();
    copy.setTenantId(source.getTenantId());
    copy.setName(source.getName());
    copy.setScriptVersion(source.getScriptVersion());
    copy.setDefinition(source.getDefinition());
    copy.setRowVersion(source.getRowVersion());
    return copy;
  }

  private static ScriptEventBinding copyBindingForInsert(ScriptEventBinding source) {
    ScriptEventBinding copy = new ScriptEventBinding();
    // Retain the original id so fixed-id restoration preserves durable references instead of
    // allocating replacement binding identities.
    copy.setId(source.getId());
    copy.setTenantId(source.getTenantId());
    copy.setScriptPatchVersion(source.getScriptPatchVersion());
    copy.setEventType(source.getEventType());
    copy.setEventSchemaVersion(source.getEventSchemaVersion());
    copy.setScriptId(source.getScriptId());
    copy.setTargetScopeType(source.getTargetScopeType());
    copy.setTargetScopeId(source.getTargetScopeId());
    copy.setPriority(source.getPriority());
    copy.setPriorityTag(source.getPriorityTag());
    copy.setRequiresExclusiveEvent(source.isRequiresExclusiveEvent());
    copy.setEnabled(source.isEnabled());
    copy.setRowVersion(source.getRowVersion());
    return copy;
  }

  private void validateBindings(ScriptDefinitionDto dto) {
    if (dto.eventBindings() == null || dto.eventBindings().isEmpty()) {
      return;
    }
    dto.eventBindings().forEach(this::validateBinding);
  }

  private void validateBinding(ScriptDefinitionDto.EventBindingDto binding) {
    normalizeBinding(binding);
    normalizePriorityTag(binding.priorityTag());
  }

  private void replaceEventBindings(ScriptDefinitionDto dto) {
    bindingRepository.deleteByTenantIdAndScriptPatchVersionAndScriptId(
        dto.tenantId(), dto.version(), dto.name());
    if (dto.eventBindings() == null || dto.eventBindings().isEmpty()) {
      return;
    }
    bindingRepository.saveAll(
        dto.eventBindings().stream().map(binding -> toEntity(dto, binding)).toList());
  }

  private ScriptEventBinding toEntity(
      ScriptDefinitionDto dto, ScriptDefinitionDto.EventBindingDto binding) {
    NormalizedBinding normalized = normalizeBinding(binding);
    ScriptEventBinding entity = new ScriptEventBinding();
    entity.setTenantId(dto.tenantId());
    entity.setScriptPatchVersion(dto.version());
    entity.setScriptId(requiredText(dto.name(), "script name"));
    entity.setEventType(normalized.eventType());
    entity.setEventSchemaVersion(normalized.eventSchemaVersion());
    entity.setTargetScopeType(normalized.targetScopeType());
    entity.setTargetScopeId(normalize(binding.targetScopeId()));
    entity.setPriority(binding.priority());
    entity.setPriorityTag(normalizePriorityTag(binding.priorityTag()));
    entity.setRequiresExclusiveEvent(binding.requiresExclusiveEvent());
    entity.setEnabled(true);
    return entity;
  }

  private NormalizedBinding normalizeBinding(ScriptDefinitionDto.EventBindingDto binding) {
    String eventType = requiredText(binding.eventType(), "event type");
    String eventSchemaVersion =
        binding.eventSchemaVersion() == null || binding.eventSchemaVersion().isBlank()
            ? DEFAULT_EVENT_SCHEMA_VERSION
            : binding.eventSchemaVersion();
    String targetScopeType =
        normalizeScopeType(requiredText(binding.targetScopeType(), "target scope type"));
    validateBindingScope(eventType, eventSchemaVersion, targetScopeType);
    return new NormalizedBinding(eventType, eventSchemaVersion, targetScopeType);
  }

  private void validateBindingScope(
      String eventType, String eventSchemaVersion, String targetScopeType) {
    ScriptEventRegistryService.EventDefinition definition =
        eventRegistryService.getDefinition(eventType, eventSchemaVersion).orElse(null);
    if (definition == null) {
      throw new IllegalArgumentException(
          "unknown built-in event binding: " + eventType + "@" + eventSchemaVersion);
    }
    if (!definition.allowedBindingScopes().contains(targetScopeType)) {
      throw new IllegalArgumentException(
          "unsupported binding scope "
              + targetScopeType
              + " for "
              + eventType
              + "@"
              + eventSchemaVersion);
    }
  }

  private static String requiredText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  private static String normalizeScopeType(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String normalizePriorityTag(String value) {
    if (value == null || value.isBlank()) {
      return PRIORITY_NORMAL;
    }
    String normalized = value.toLowerCase(java.util.Locale.ROOT);
    return switch (normalized) {
      case PRIORITY_HIGH, PRIORITY_NORMAL, PRIORITY_BACKGROUND -> normalized;
      default ->
          throw new IllegalArgumentException("priority tag must be high, normal, or background");
    };
  }

  private record NormalizedBinding(
      String eventType, String eventSchemaVersion, String targetScopeType) {}
}
