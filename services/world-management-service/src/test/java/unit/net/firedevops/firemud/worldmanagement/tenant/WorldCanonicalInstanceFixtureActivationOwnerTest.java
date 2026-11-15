package net.firedevops.firemud.worldmanagement.tenant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInstanceFixtureActivationOwnerTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final WorldCanonicalInstanceAssociationRepository associations =
      mock(WorldCanonicalInstanceAssociationRepository.class);
  private final WorldCanonicalInstanceFixtureActivationOwner owner =
      new WorldCanonicalInstanceFixtureActivationOwner(dsl, manager, associations);

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void refusesAmbientTransactionBeforeAnyOwnerReadOrWrite() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThrows(IllegalStateException.class, () -> owner.read(null));
    assertThrows(IllegalStateException.class, () -> owner.activate(null, null, null));
    verifyNoInteractions(dsl, manager, associations);
  }

  @Test
  void refusesAmbientSynchronizationEvenWithoutTransaction() {
    TransactionSynchronizationManager.initSynchronization();
    assertThrows(IllegalStateException.class, () -> owner.read(null));
    assertThrows(IllegalStateException.class, () -> owner.activate(null, null, null));
    verifyNoInteractions(dsl, manager, associations);
  }

  @Test
  void refusesMissingOwnerInputWithoutStartingTransaction() {
    assertThrows(NullPointerException.class, () -> owner.read(null));
    assertThrows(NullPointerException.class, () -> owner.activate(null, null, null));
    verifyNoInteractions(dsl, manager, associations);
  }
}
