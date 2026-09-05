package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

class ScriptDesignDigestServiceImplTest {
  @Mock private ScriptDefinitionRepository repository;
  @Mock private ScriptEventBindingRepository bindingRepository;

  private ScriptDesignDigestServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new ScriptDesignDigestServiceImpl(repository, bindingRepository, new ObjectMapper());
  }

  @Test
  void getDraftDesignDigestReturnsStablePatchDigest() {
    ScriptDefinition one = new ScriptDefinition();
    one.setTenantId(1L);
    one.setName("alpha");
    one.setScriptVersion("patch-1");
    one.setDefinition("return 1");
    when(repository.findByTenantIdAndScriptVersionOrderByNameAsc(1L, "patch-1"))
        .thenReturn(List.of(one));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of());

    var digest = service.getDraftDesignDigestForScriptPatch("1", "patch-1");

    assertEquals("patch-1", digest.scopeValue());
    assertEquals("script-patch:patch-1", digest.appliedCommitId());
    assertEquals(4, digest.digestSchemaVersion());
  }

  @Test
  void getDraftDesignDigestForVersionRejectsZeroTenantIdBeforeLookups() {
    assertThrows(
        IllegalArgumentException.class, () -> service.getDraftDesignDigestForVersion("0", "7"));

    verifyNoInteractions(repository, bindingRepository);
  }

  @Test
  void getDraftDesignDigestReturnsStableFullVersionDigest() {
    ScriptDefinition one = new ScriptDefinition();
    one.setTenantId(1L);
    one.setName("alpha");
    one.setScriptVersion("patch-1");
    one.setDefinition("return 1");
    when(repository.findByTenantIdOrderByNameAscScriptVersionAsc(1L)).thenReturn(List.of(one));
    when(bindingRepository
            .findByTenantIdOrderByScriptPatchVersionAscEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L))
        .thenReturn(List.of());

    var digest = service.getDraftDesignDigestForVersion("1", "7");

    assertEquals("7", digest.scopeValue());
    assertEquals("version:7", digest.appliedCommitId());
    assertEquals(4, digest.digestSchemaVersion());
  }

  @Test
  void getDraftDesignDigestForScriptPatchRejectsZeroTenantIdBeforeLookups() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.getDraftDesignDigestForScriptPatch("0", "patch-1"));

    verifyNoInteractions(repository, bindingRepository);
  }

  @Test
  void getDraftDesignDigestIsIndependentOfJsonMapOrdering() {
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("alpha");
    script.setScriptVersion("patch-1");
    script.setDefinition("return 1");
    ScriptEventBinding binding = new ScriptEventBinding();
    binding.setTenantId(1L);
    binding.setScriptPatchVersion("patch-1");
    binding.setEventType("onCommand");
    binding.setEventSchemaVersion("v1");
    binding.setScriptId("script-1");
    binding.setBindingId("binding-1");
    binding.setTargetScopeType("ENTITY");
    binding.setTargetScopeId("entity-1");
    binding.setPriority(1);

    when(repository.findByTenantIdAndScriptVersionOrderByNameAsc(1L, "patch-1"))
        .thenReturn(List.of(script));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(binding));

    var defaultMapperDigest = service.getDraftDesignDigestForScriptPatch("1", "patch-1");
    var sortedMapperService =
        new ScriptDesignDigestServiceImpl(
            repository,
            bindingRepository,
            new ObjectMapper()
                .rebuild()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build());
    var sortedMapperDigest = sortedMapperService.getDraftDesignDigestForScriptPatch("1", "patch-1");

    assertEquals(defaultMapperDigest.contentDigest(), sortedMapperDigest.contentDigest());
    assertEquals(4, defaultMapperDigest.digestSchemaVersion());
    assertEquals(4, sortedMapperDigest.digestSchemaVersion());
  }

  @Test
  void getDraftDesignDigestChangesWhenOnlyBindingIdChanges() {
    ScriptDefinition script = new ScriptDefinition();
    script.setTenantId(1L);
    script.setName("alpha");
    script.setScriptVersion("patch-1");
    script.setDefinition("return 1");
    ScriptEventBinding first = binding("binding-1");
    ScriptEventBinding second = binding("binding-2");

    when(repository.findByTenantIdAndScriptVersionOrderByNameAsc(1L, "patch-1"))
        .thenReturn(List.of(script));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(first), List.of(second));

    var firstDigest = service.getDraftDesignDigestForScriptPatch("1", "patch-1");
    var secondDigest = service.getDraftDesignDigestForScriptPatch("1", "patch-1");

    assertEquals(firstDigest.digestSchemaVersion(), secondDigest.digestSchemaVersion());
    org.junit.jupiter.api.Assertions.assertNotEquals(
        firstDigest.contentDigest(), secondDigest.contentDigest());
  }

  private static ScriptEventBinding binding(String bindingId) {
    ScriptEventBinding binding = new ScriptEventBinding();
    binding.setTenantId(1L);
    binding.setScriptPatchVersion("patch-1");
    binding.setEventType("onCommand");
    binding.setEventSchemaVersion("v1");
    binding.setScriptId("script-1");
    binding.setBindingId(bindingId);
    binding.setTargetScopeType("ENTITY");
    binding.setTargetScopeId("entity-1");
    binding.setPriority(1);
    return binding;
  }

  @Test
  void getDraftDesignDigestFailsClosedWhenPatchMissing() {
    when(repository.findByTenantIdAndScriptVersionOrderByNameAsc(1L, "patch-2"))
        .thenReturn(List.of());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.getDraftDesignDigestForScriptPatch("1", "patch-2"));
  }
}
