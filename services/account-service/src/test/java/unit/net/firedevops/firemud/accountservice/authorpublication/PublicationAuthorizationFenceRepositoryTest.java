package unit.net.firedevops.firemud.accountservice.authorpublication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PublicationAuthorizationFenceRepositoryTest {
  @Test
  void allEntryPointsDenyOutsideWritableOwnerTransactionBeforePersistence() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new PublicationAuthorizationFenceRepository(dsl);
    assertThatThrownBy(() -> repository.reserve(null)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.claimPublicationOrder(null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.read(null)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.readOriginalBinding(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.revokeAffected(List.of()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.allAffectedSettled(List.of()))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  void readOnlyOwnerTransactionCannotReserveOrReleaseSourceParticipation() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new PublicationAuthorizationFenceRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(() -> repository.reserve(null)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> repository.allAffectedSettled(List.of()))
          .isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(dsl);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }
}
