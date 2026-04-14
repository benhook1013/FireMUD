package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;

class ScriptDesignDigestServiceImplTest {
  @Mock private ScriptDefinitionRepository repository;

  private ScriptDesignDigestServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new ScriptDesignDigestServiceImpl(repository, new ObjectMapper());
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

    var digest = service.getDraftDesignDigest("1", "patch-1");

    assertEquals("patch-1", digest.scriptPatchVersion());
    assertEquals("script-patch:patch-1", digest.appliedCommitId());
  }

  @Test
  void getDraftDesignDigestFailsClosedWhenPatchMissing() {
    when(repository.findByTenantIdAndScriptVersionOrderByNameAsc(1L, "patch-2"))
        .thenReturn(List.of());

    assertThrows(
        IllegalArgumentException.class, () -> service.getDraftDesignDigest("1", "patch-2"));
  }
}
