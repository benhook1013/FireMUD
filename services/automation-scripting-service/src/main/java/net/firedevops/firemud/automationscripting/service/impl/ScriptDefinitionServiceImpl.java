package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.mapper.ScriptDefinitionMapper;
import net.firedevops.firemud.automationscripting.model.ScriptDefinitionIdentityConflictException;
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
  @Transactional(rollbackFor = SagaException.class)
  @Timed(value = "script.update")
  public ScriptDefinitionDto updateScript(ScriptDefinitionDto dto) throws SagaException {
    validateBindings(dto);
    ScriptDefinition entity = mapper.toEntity(dto);
    ScriptDefinition previousDefinition =
        repository
            .findByTenantIdAndScriptVersionAndName(dto.tenantId(), dto.version(), dto.name())
            .orElse(null);
    validateExistingIdentity(entity, previousDefinition);
    List<ScriptEventBinding> previousBindings = snapshotBindings(dto);
    AtomicReference<ScriptDefinitionRepository.SaveResult> persisted =
        new AtomicReference<>(new ScriptDefinitionRepository.SaveResult(entity, false));
    var saga =
        new SagaBuilder("updateScript")
            .step(
                "persistScript",
                () -> persisted.set(repository.saveWithCreationResult(entity)),
                () -> compensateDefinition(previousDefinition, persisted.get()))
            .step(
                "replaceEventBindings",
                () -> replaceEventBindings(dto),
                () -> restoreBindings(dto, previousBindings))
            .build();
    try {
      sagaRunner.run(saga);
    } catch (SagaException ex) {
      // Keep the current gRPC boundary's INVALID_ARGUMENT mapping for a stable-identity conflict;
      // SagaRunner wraps the repository exception so it would otherwise be reported as INTERNAL.
      if (isIdentityConflict(ex.getCause())) {
        throw (IllegalArgumentException) ex.getCause();
      }
      throw ex;
    }
    return mapper.toDto(persisted.get().definition());
  }

  private void validateExistingIdentity(
      ScriptDefinition requested, ScriptDefinition stableIdentityRow) {
    if (requested.getId() == null) {
      return;
    }
    ScriptDefinition idRow = repository.findById(requested.getId()).orElse(null);
    if (idRow != null && !sameIdentity(idRow, requested)) {
      throw identityConflict(idRow, requested);
    }
    if (stableIdentityRow != null
        && !Objects.equals(stableIdentityRow.getId(), requested.getId())) {
      throw identityConflict(stableIdentityRow, requested);
    }
    ScriptDefinition durableRow = idRow != null ? idRow : stableIdentityRow;
    if (durableRow != null) {
      requested.setRowVersion(durableRow.getRowVersion());
    }
  }

  private List<ScriptEventBinding> snapshotBindings(ScriptDefinitionDto dto) {
    List<ScriptEventBinding> bindings =
        bindingRepository
            .findByTenantIdAndScriptPatchVersionAndScriptIdOrderByEventTypeAscEventSchemaVersionAscPriorityAscBindingIdAscIdAsc(
                dto.tenantId(), dto.version(), dto.name());
    if (bindings == null || bindings.isEmpty()) {
      return List.of();
    }
    return bindings;
  }

  private void compensateDefinition(
      ScriptDefinition previousDefinition, ScriptDefinitionRepository.SaveResult saveResult) {
    if (saveResult == null) {
      return;
    }
    ScriptDefinition persistedDefinition = saveResult.definition();
    if (persistedDefinition == null || persistedDefinition.getId() == null) {
      return;
    }
    if (previousDefinition == null) {
      if (saveResult.created()) {
        repository.delete(persistedDefinition);
      }
      return;
    }
    // A same-definition retry returned the durable winner; it must never delete that pre-existing
    // row when a later binding write fails. An explicit existing-ID definition replacement can be
    // restored for non-transactional callers; the service transaction also rolls it back.
    if (sameDefinition(previousDefinition, persistedDefinition)) {
      return;
    }
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
    bindingRepository.saveAll(
        previousBindings.stream().map(ScriptDefinitionServiceImpl::copyBindingForInsert).toList());
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
    copy.setTenantId(source.getTenantId());
    copy.setScriptPatchVersion(source.getScriptPatchVersion());
    copy.setEventType(source.getEventType());
    copy.setEventSchemaVersion(source.getEventSchemaVersion());
    copy.setScriptId(source.getScriptId());
    copy.setBindingId(source.getBindingId());
    copy.setTargetScopeType(source.getTargetScopeType());
    copy.setTargetScopeId(source.getTargetScopeId());
    copy.setPriority(source.getPriority());
    copy.setPriorityTag(source.getPriorityTag());
    copy.setRequiresExclusiveEvent(source.isRequiresExclusiveEvent());
    copy.setEnabled(source.isEnabled());
    return copy;
  }

  private static boolean sameIdentity(ScriptDefinition left, ScriptDefinition right) {
    return Objects.equals(left.getTenantId(), right.getTenantId())
        && Objects.equals(left.getName(), right.getName())
        && Objects.equals(left.getScriptVersion(), right.getScriptVersion());
  }

  private static boolean sameDefinition(ScriptDefinition left, ScriptDefinition right) {
    return Objects.equals(left.getDefinition(), right.getDefinition());
  }

  private static ScriptDefinitionIdentityConflictException identityConflict(
      ScriptDefinition existing, ScriptDefinition requested) {
    return new ScriptDefinitionIdentityConflictException(
        "SCRIPT_DEFINITION_CONFLICT: immutable stable identity cannot be changed for id="
            + requested.getId()
            + "; existing=(tenantId="
            + existing.getTenantId()
            + ", version="
            + existing.getScriptVersion()
            + ", name="
            + existing.getName()
            + "), requested=(tenantId="
            + requested.getTenantId()
            + ", version="
            + requested.getScriptVersion()
            + ", name="
            + requested.getName()
            + ")");
  }

  private static boolean isIdentityConflict(Throwable throwable) {
    return throwable instanceof ScriptDefinitionIdentityConflictException;
  }

  private void validateBindings(ScriptDefinitionDto dto) {
    if (dto.eventBindings() == null || dto.eventBindings().isEmpty()) {
      return;
    }
    Set<String> bindingIds = new HashSet<>();
    dto.eventBindings().forEach(binding -> validateBinding(binding, bindingIds));
  }

  private void validateBinding(
      ScriptDefinitionDto.EventBindingDto binding, Set<String> bindingIds) {
    normalizeBinding(binding);
    String bindingId = normalizeBindingId(binding.bindingId());
    if (!bindingIds.add(bindingId)) {
      throw new IllegalArgumentException("duplicate binding id: " + bindingId);
    }
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
    entity.setBindingId(normalizeBindingId(binding.bindingId()));
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

  private static String normalizeBindingId(String value) {
    return requiredText(value, "binding id").trim();
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
