package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InstanceCutoverCompatibilityServiceImplTest {
  @Test
  void validateInstanceCutoverCompatibilityRejectsZeroSourceVersionIdBeforeDownstreamChecks() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameDesignClient gameDesignClient = Mockito.mock(GameDesignClient.class);
    WorldManagementClient worldManagementClient = Mockito.mock(WorldManagementClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    Mockito.when(repository.findById(7L)).thenReturn(Optional.of(sourceInstance(1L, null, 0L, 3L)));
    InstanceCutoverCompatibilityServiceImpl service =
        new InstanceCutoverCompatibilityServiceImpl(
            repository, gameDesignClient, worldManagementClient, entityManagementClient);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.validateInstanceCutoverCompatibility(1L, 7L, 9L));

    assertEquals("versionId must be positive", error.getMessage());
    Mockito.verifyNoInteractions(gameDesignClient, worldManagementClient, entityManagementClient);
  }

  @Test
  void validateInstanceCutoverCompatibilityRejectsMalformedRuntimeVersionBeforeDownstreamChecks() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameDesignClient gameDesignClient = Mockito.mock(GameDesignClient.class);
    WorldManagementClient worldManagementClient = Mockito.mock(WorldManagementClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    Mockito.when(repository.findById(7L))
        .thenReturn(Optional.of(sourceInstance(1L, "bad", null, 3L)));
    InstanceCutoverCompatibilityServiceImpl service =
        new InstanceCutoverCompatibilityServiceImpl(
            repository, gameDesignClient, worldManagementClient, entityManagementClient);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.validateInstanceCutoverCompatibility(1L, 7L, 9L));

    assertEquals("runtimeVersion must be numeric", error.getMessage());
    Mockito.verifyNoInteractions(gameDesignClient, worldManagementClient, entityManagementClient);
  }

  @Test
  void validateInstanceCutoverCompatibilityRejectsZeroGameTemplateIdBeforeDownstreamChecks() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameDesignClient gameDesignClient = Mockito.mock(GameDesignClient.class);
    WorldManagementClient worldManagementClient = Mockito.mock(WorldManagementClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    Mockito.when(repository.findById(7L))
        .thenReturn(Optional.of(sourceInstance(1L, null, 11L, 0L)));
    InstanceCutoverCompatibilityServiceImpl service =
        new InstanceCutoverCompatibilityServiceImpl(
            repository, gameDesignClient, worldManagementClient, entityManagementClient);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.validateInstanceCutoverCompatibility(1L, 7L, 9L));

    assertEquals("gameTemplateId must be positive", error.getMessage());
    Mockito.verifyNoInteractions(gameDesignClient, worldManagementClient, entityManagementClient);
  }

  private static GameInstance sourceInstance(
      long tenantId, String runtimeVersion, Long versionId, Long gameTemplateId) {
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(tenantId);
    instance.setRuntimeVersion(runtimeVersion);
    instance.setVersionId(versionId);
    instance.setGameTemplateId(gameTemplateId);
    return instance;
  }
}
