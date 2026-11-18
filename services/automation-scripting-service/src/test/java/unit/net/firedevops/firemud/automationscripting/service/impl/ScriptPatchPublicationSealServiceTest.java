package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.model.ScriptPatchPublicationSeal;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchPublicationSealRepository;
import net.firedevops.firemud.automationscripting.service.ScriptDesignDigestService;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class ScriptPatchPublicationSealServiceTest {
  private ScriptDefinitionRepository definitions;
  private ScriptEventBindingRepository bindings;
  private ScriptPatchPublicationSealRepository seals;
  private ScriptDesignDigestService digestService;
  private ObjectMapper objectMapper;
  private ScriptPatchPublicationSealService service;

  @BeforeEach
  void setUp() {
    definitions = Mockito.mock(ScriptDefinitionRepository.class);
    bindings = Mockito.mock(ScriptEventBindingRepository.class);
    seals = Mockito.mock(ScriptPatchPublicationSealRepository.class);
    digestService = Mockito.mock(ScriptDesignDigestService.class);
    objectMapper = new ObjectMapper();
    service =
        new ScriptPatchPublicationSealService(
            definitions, bindings, seals, digestService, objectMapper);
    when(definitions.findScriptPatchBaseVersionId("23", "patch-7")).thenReturn(Optional.of(6L));
    when(seals.lockForUpdate(23L, "patch-7"))
        .thenReturn(
            new ScriptPatchPublicationSealRepository.PatchLock(
                23L, "patch-7", false, null, null, null, null, null, null));
  }

  @Test
  void sealsBoundContentWithoutClaimingAReadinessManifest() throws Exception {
    ScriptDefinition definition = new ScriptDefinition();
    definition.setTenantId(23L);
    definition.setName("alpha");
    definition.setScriptVersion("patch-7");
    definition.setDefinition("{\"say\":\"hello\"}");
    ScriptEventBinding binding = new ScriptEventBinding();
    binding.setTenantId(23L);
    binding.setScriptPatchVersion("patch-7");
    binding.setEventType("onCommand");
    binding.setEventSchemaVersion("v1");
    binding.setScriptId("alpha");
    binding.setBindingId("greet");
    binding.setTargetScopeType("GLOBAL");
    binding.setTargetScopeId("");
    binding.setPriority(2);
    binding.setPriorityTag("normal");
    binding.setEnabled(true);
    when(definitions.findByTenantIdAndScriptVersionOrderByNameAsc(23L, "patch-7"))
        .thenReturn(List.of(definition));
    when(bindings
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                23L, "patch-7"))
        .thenReturn(List.of(binding));
    when(digestService.getDraftDesignDigestForScriptPatchSnapshot(
            "23", 6L, "patch-7", List.of(definition), List.of(binding)))
        .thenReturn(
            new ScriptDesignDigestService.ScriptDraftDesignDigest(
                "23", "patch-7", 6L, "script-patch:patch-7", "ab".repeat(32), 5));
    when(seals.seal(eq(23L), eq(6L), eq("patch-7"), eq(1), eq(5), eq("ab".repeat(32)), anyString()))
        .thenAnswer(
            invocation ->
                new ScriptPatchPublicationSeal(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    invocation.getArgument(5),
                    invocation.getArgument(6),
                    OffsetDateTime.now()));

    ScriptPatchPublicationSeal seal =
        service.sealSnapshot(
            binding(), binding().derivedWorkflowIdentity(), binding().requestDigest());

    assertThat(seal.baseVersionId()).isEqualTo(6L);
    ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
    verify(seals)
        .seal(
            eq(23L), eq(6L), eq("patch-7"), eq(1), eq(5), eq("ab".repeat(32)), snapshot.capture());
    var json = objectMapper.readTree(snapshot.getValue());
    assertThat(json.get("tenantId").asText()).isEqualTo("23");
    assertThat(json.get("baseVersionId").asLong()).isEqualTo(6L);
    assertThat(json.get("scriptPatchVersion").asText()).isEqualTo("patch-7");
    assertThat(json.get("contentDigestSchemaVersion").asInt()).isEqualTo(5);
    assertThat(json.get("contentDigest").asText()).isEqualTo("ab".repeat(32));
    assertThat(json.get("definitions").get(0).get("definition").asText())
        .isEqualTo("{\"say\":\"hello\"}");
    assertThat(json.get("eventBindings").get(0).get("bindingId").asText()).isEqualTo("greet");
    assertThat(json.has("onLoadHandlerIds")).isFalse();
    assertThat(json.get("requestDigest").asText()).isEqualTo(binding().requestDigest());
  }

  @Test
  void emptyPatchContentDoesNotInventZeroHandlerReadiness() throws Exception {
    when(definitions.findByTenantIdAndScriptVersionOrderByNameAsc(23L, "patch-7"))
        .thenReturn(List.of());
    when(bindings
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                23L, "patch-7"))
        .thenReturn(List.of());
    when(digestService.getDraftDesignDigestForScriptPatchSnapshot(
            "23", 6L, "patch-7", List.of(), List.of()))
        .thenReturn(
            new ScriptDesignDigestService.ScriptDraftDesignDigest(
                "23", "patch-7", 6L, "script-patch:patch-7", "cd".repeat(32), 5));
    when(seals.seal(eq(23L), eq(6L), eq("patch-7"), eq(1), eq(5), eq("cd".repeat(32)), anyString()))
        .thenReturn(
            new ScriptPatchPublicationSeal(
                23L, 6L, "patch-7", 1, 5, "cd".repeat(32), "{}", OffsetDateTime.now()));

    service.sealSnapshot(binding(), binding().derivedWorkflowIdentity(), binding().requestDigest());

    ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
    verify(seals)
        .seal(
            eq(23L), eq(6L), eq("patch-7"), eq(1), eq(5), eq("cd".repeat(32)), snapshot.capture());
    assertThat(objectMapper.readTree(snapshot.getValue()).has("onLoadHandlerIds")).isFalse();
  }

  private static PublicationDigestRequestBinding binding() {
    return PublicationDigestRequestBinding.patch("23", "6", "patch-7", "request-7");
  }

  @Test
  void refusesChangedRequestBeforeReadingOrSealing() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.sealSnapshot(binding(), "wrong", binding().requestDigest()))
        .isInstanceOf(IllegalArgumentException.class);
    Mockito.verifyNoInteractions(seals);
  }

  @Test
  void refusesUnretainedBaseEvenForEmptyPatch() {
    when(definitions.findScriptPatchBaseVersionId("23", "patch-7")).thenReturn(Optional.empty());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.sealSnapshot(
                    binding(), binding().derivedWorkflowIdentity(), binding().requestDigest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("retained_base");
    Mockito.verifyNoInteractions(bindings, digestService);
  }
}
