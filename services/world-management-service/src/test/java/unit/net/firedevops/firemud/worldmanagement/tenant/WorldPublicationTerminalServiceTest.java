package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalService;
import org.junit.jupiter.api.Test;

class WorldPublicationTerminalServiceTest {
  @Test
  void productionDefaultDeniesOpaqueEvidenceBeforeDecodeOrOwnerStorage() {
    var repository = mock(WorldPublicationTerminalRepository.class);
    var service = new WorldPublicationTerminalService(repository);

    assertThatThrownBy(() -> service.complete(new byte[0], new byte[0]))
        .isInstanceOf(WorldPublicationTerminalService.TerminalDeniedException.class)
        .hasMessageContaining("no authenticated exact Game Design producer verifier");
    verifyNoInteractions(repository);
  }
}
