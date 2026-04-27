package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.mapper.ScriptDefinitionMapper;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.service.ScriptDefinitionService;
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

  @Override
  @Transactional
  @Timed(value = "script.update")
  public ScriptDefinitionDto updateScript(ScriptDefinitionDto dto) throws SagaException {
    ScriptDefinition entity = mapper.toEntity(dto);
    var saga =
        new SagaBuilder("updateScript")
            .step("persistScript", () -> repository.save(entity), () -> repository.delete(entity))
            .step(
                "replaceEventBindings",
                () -> replaceEventBindings(dto),
                () ->
                    bindingRepository.deleteByTenantIdAndScriptPatchVersionAndScriptId(
                        dto.tenantId(), dto.version(), dto.name()))
            .build();
    sagaRunner.run(saga);
    return mapper.toDto(entity);
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
    ScriptEventBinding entity = new ScriptEventBinding();
    entity.setTenantId(dto.tenantId());
    entity.setScriptPatchVersion(dto.version());
    entity.setScriptId(requiredText(dto.name(), "script name"));
    entity.setEventType(requiredText(binding.eventType(), "event type"));
    entity.setEventSchemaVersion(
        binding.eventSchemaVersion() == null || binding.eventSchemaVersion().isBlank()
            ? DEFAULT_EVENT_SCHEMA_VERSION
            : binding.eventSchemaVersion());
    entity.setTargetScopeType(requiredText(binding.targetScopeType(), "target scope type"));
    entity.setTargetScopeId(normalize(binding.targetScopeId()));
    entity.setPriority(binding.priority());
    entity.setPriorityTag(normalizePriorityTag(binding.priorityTag()));
    entity.setRequiresExclusiveEvent(binding.requiresExclusiveEvent());
    entity.setEnabled(true);
    return entity;
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
}
