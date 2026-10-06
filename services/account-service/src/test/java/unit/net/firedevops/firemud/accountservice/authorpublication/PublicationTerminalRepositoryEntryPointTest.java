package unit.net.firedevops.firemud.accountservice.authorpublication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class PublicationTerminalRepositoryEntryPointTest {
  @Test
  void terminalStorageRequiresWritableOwnerTransactionBeforeReadingEvidenceOrPersistence() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new PublicationAuthorizationFenceRepository(dsl);

    assertThatThrownBy(() -> repository.readOriginalOperation(null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.recordOwnerResult(null, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.readOwnerResult(null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.readSettlement(null))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl);
  }
}
