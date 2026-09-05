package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;

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
    assertEquals(3, digest.digestSchemaVersion());
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
    assertEquals(3, digest.digestSchemaVersion());
  }

  @Test
  void getDraftDesignDigestForScriptPatchRejectsZeroTenantIdBeforeLookups() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.getDraftDesignDigestForScriptPatch("0", "patch-1"));

    verifyNoInteractions(repository, bindingRepository);
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
