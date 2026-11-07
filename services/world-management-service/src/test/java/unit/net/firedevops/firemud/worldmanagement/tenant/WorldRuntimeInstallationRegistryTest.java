package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class WorldRuntimeInstallationRegistryTest {
  @Test
  void unavailableDefaultSourceRefusesPreflightWithoutAnyDatabaseAccess() {
    var dsl = mock(DSLContext.class);
    var transactions = mock(PlatformTransactionManager.class);
    var registry = new WorldRuntimeInstallationRegistry(dsl, transactions, "world", "firemud");

    assertThatThrownBy(
            () ->
                registry.preflightLaunchOwners(
                    UUID.fromString("88888888-8888-4888-8888-888888888888")))
        .isInstanceOf(WorldRuntimeInstallationRegistry.RegistryUnavailableException.class)
        .hasMessage("Protected current-installation source is unavailable");

    verifyNoInteractions(dsl, transactions);
  }
}
