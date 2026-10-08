package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInstanceLifecycleReadRepositoryTest {
  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void rejectsAmbientTransactionBeforeOpeningIndependentOwnerSnapshot() {
    var dsl = mock(DSLContext.class);
    var manager = mock(PlatformTransactionManager.class);
    var association = mock(WorldCanonicalInstanceAssociationRepository.class);
    var repository = new WorldCanonicalInstanceLifecycleReadRepository(dsl, manager, association);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.read(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not join an ambient transaction");
    verifyNoInteractions(dsl, manager, association);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request request() {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        "test",
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        "starter-world",
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        "SHARED",
        true,
        "control-request",
        UUID.fromString("55555555-5555-4555-8555-555555555555"),
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        "sha256:" + "c".repeat(64));
  }
}
