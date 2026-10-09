package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalInitialAdmissionIntentRepositoryTest {
  private static final String NAMESPACE = "intent-unit";
  private static final UUID TENANT = uuid(1);
  private static final UUID REALM = uuid(2);
  private static final UUID PLAYABLE_NAMESPACE = uuid(3);
  private static final UUID INSTANCE = uuid(4);
  private static final UUID VERSION = uuid(5);
  private static final String SHA256 = "sha256:" + "a".repeat(64);

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void reserveRejectsAnIdentityBeyondTheV28IntentWidthBeforeOwnerReads() {
    DSLContext dsl = mock(DSLContext.class);
    var catalog = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var launch = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    var repository = new CanonicalInitialAdmissionIntentRepository(dsl, catalog, launch);
    String requestId = "r".repeat(121);

    assertThatThrownBy(() -> repository.reserve(holdRequest(requestId), lifecycleRequest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("120-character intent column");

    verifyNoInteractions(dsl, catalog, launch);
  }

  @Test
  void mutationRequiresWritableOwnerTransactionBeforeOwnerReads() {
    DSLContext dsl = mock(DSLContext.class);
    var catalog = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var launch = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    var repository = new CanonicalInitialAdmissionIntentRepository(dsl, catalog, launch);

    assertThatThrownBy(() -> repository.reserve(holdRequest("request-1"), lifecycleRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable owner transaction");

    verifyNoInteractions(dsl, catalog, launch);
  }

  @Test
  void readRejectsAnAmbientTransactionBeforeQueryingTheIntentLedger() {
    DSLContext dsl = mock(DSLContext.class);
    var catalog = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var launch = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    var repository = new CanonicalInitialAdmissionIntentRepository(dsl, catalog, launch);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.read(NAMESPACE, "request-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed owner read");

    verifyNoInteractions(dsl, catalog, launch);
  }

  private static WorldCanonicalInitialAdmissionHold.Request holdRequest(String requestId) {
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            NAMESPACE,
            TENANT,
            "world",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            VERSION,
            2L,
            1L,
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            requestId);
    return new WorldCanonicalInitialAdmissionHold.Request(
        NAMESPACE,
        TENANT,
        "world",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        INSTANCE,
        VERSION,
        2L,
        requestId,
        requestDigest,
        InitialAdmissionOrigin.NO_PRIOR_POINTER,
        1L,
        null);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest() {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        uuid(6),
        NAMESPACE,
        TENANT,
        "world",
        INSTANCE,
        PLAYABLE_NAMESPACE,
        "SHARED",
        true,
        "control-request-1",
        VERSION,
        SHA256,
        SHA256,
        SHA256);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }
}
