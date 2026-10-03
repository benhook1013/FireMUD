package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InstanceCutoverCompatibilityServiceImplTest {
  @Test
  void validateInstanceCutoverCompatibilityDeniesLegacyNumericLaunchBeforeAnyLookup() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameDesignClient gameDesignClient = Mockito.mock(GameDesignClient.class);
    WorldManagementClient worldManagementClient = Mockito.mock(WorldManagementClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    InstanceCutoverCompatibilityServiceImpl service =
        new InstanceCutoverCompatibilityServiceImpl(
            repository, gameDesignClient, worldManagementClient, entityManagementClient);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.validateInstanceCutoverCompatibility(1L, 7L, 9L));

    assertEquals(
        "AUTHORED_WORLD_LAUNCH_BINDING_REQUIRED: canonical authored-world launch binding is required",
        error.getMessage());
    Mockito.verifyNoInteractions(
        repository, gameDesignClient, worldManagementClient, entityManagementClient);
  }
}
