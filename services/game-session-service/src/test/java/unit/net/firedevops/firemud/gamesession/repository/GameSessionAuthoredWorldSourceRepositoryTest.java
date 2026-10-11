package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.InvalidIntakeEvidenceException;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionAuthoredWorldSourceRepositoryTest {
  private static final String NAMESPACE = "authored-world-intake-unit";
  private static final UUID TENANT = uuid(1);
  private static final UUID SOURCE_OPERATION = uuid(2);
  private static final UUID SOURCE_REGISTRATION_REQUEST = uuid(3);

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsPaddedOpenWorldDisplayNameBeforeStorageAccess() {
    DSLContext dsl = mock(DSLContext.class);
    GameSessionAuthoredWorldSourceRepository repository =
        new GameSessionAuthoredWorldSourceRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.register(uuid(4), source(" Violet Wilds ")))
        .isInstanceOf(InvalidIntakeEvidenceException.class)
        .hasMessageContaining("not canonical for an OPEN pointer");

    verifyNoInteractions(dsl);
  }

  private static AuthoredWorldSourceEvidence source(String worldDisplayName) {
    String tenantSlug = "tenant-authored-world-unit";
    String worldSlug = "violet-wilds";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            SOURCE_REGISTRATION_REQUEST,
            TENANT,
            tenantSlug,
            worldSlug,
            worldDisplayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            SOURCE_REGISTRATION_REQUEST,
            SOURCE_OPERATION,
            requestDigest,
            TENANT,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            821,
            "gds-tenant-821",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        SOURCE_REGISTRATION_REQUEST,
        SOURCE_OPERATION,
        requestDigest,
        TENANT,
        tenantSlug,
        worldSlug,
        worldDisplayName,
        821,
        "gds-tenant-821",
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }
}
