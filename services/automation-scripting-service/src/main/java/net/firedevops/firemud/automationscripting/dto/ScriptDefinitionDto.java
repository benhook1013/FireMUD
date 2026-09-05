package net.firedevops.firemud.automationscripting.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ScriptDefinitionDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull @Size(max = 100) String name,
    @NotNull @Size(max = 20) String version,
    @NotNull String definition,
    List<EventBindingDto> eventBindings) {
  public ScriptDefinitionDto {
    eventBindings = eventBindings == null ? List.of() : List.copyOf(eventBindings);
  }

  public record EventBindingDto(
      @NotNull String eventType,
      String eventSchemaVersion,
      @NotNull String targetScopeType,
      String targetScopeId,
      int priority,
      String priorityTag,
      boolean requiresExclusiveEvent,
      String bindingId) {}
}
