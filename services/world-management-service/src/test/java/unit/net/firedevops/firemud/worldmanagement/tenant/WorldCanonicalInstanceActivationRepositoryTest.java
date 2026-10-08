package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInstanceActivationRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycle =
      mock(WorldCanonicalInstanceLifecycleReadRepository.class);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void immutableResultReadRejectsAmbientTransactionBeforeRepositoryOrDatabaseUse() {
    var repository = new WorldCanonicalInstanceActivationRepository(dsl, manager, lifecycle);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.readResult(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not join an ambient transaction");

    verifyNoInteractions(dsl, manager, lifecycle);
  }

  @Test
  void activationRejectsAmbientTransactionBeforeOpeningItsOwnerCas() {
    var repository = new WorldCanonicalInstanceActivationRepository(dsl, manager, lifecycle);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () ->
                repository.activate(
                    request(),
                    new WorldCanonicalInstanceActivationService.HeldActivationAuthority() {
                      public void requireHeld() {}

                      public void close() {}
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not join an ambient transaction");

    verifyNoInteractions(dsl, manager, lifecycle);
  }

  private static WorldCanonicalInstanceActivation.Request request() {
    var lifecycleRequest = mock(WorldCanonicalInstanceLifecycleEvidence.Request.class);
    when(lifecycleRequest.canonicalGameInstanceId())
        .thenReturn(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    var preparing = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(preparing.request()).thenReturn(lifecycleRequest);
    when(preparing.lifecycleStatus()).thenReturn("PREPARING");
    when(preparing.lifecycleEpoch()).thenReturn(1L);
    when(preparing.rowVersion()).thenReturn(0L);
    when(preparing.canonicalBytes())
        .thenReturn(
            ("{\"request\":{\"readRequestId\":\"11111111-1111-4111-8111-111111111111\"}}")
                .getBytes(StandardCharsets.UTF_8));
    return new WorldCanonicalInstanceActivation.Request(
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), preparing);
  }
}
