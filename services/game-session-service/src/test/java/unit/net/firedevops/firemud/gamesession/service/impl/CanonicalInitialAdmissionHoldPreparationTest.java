package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.client.WorldCanonicalInitialAdmissionHoldClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.service.impl.CanonicalInitialAdmissionHoldPreparation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalInitialAdmissionHoldPreparationTest {
  private static final String NAMESPACE = "hold-unit";
  private static final UUID TENANT = uuid(1);
  private static final UUID REALM = uuid(2);
  private static final UUID PLAYABLE_NAMESPACE = uuid(3);
  private static final UUID INSTANCE = uuid(4);
  private static final UUID VERSION = uuid(5);
  private static final String SHA256 = "sha256:" + "a".repeat(64);
  private static final Request HOLD_REQUEST = holdRequest("request-1");
  private static final WorldCanonicalInstanceLifecycleEvidence.Request LIFECYCLE_REQUEST =
      lifecycleRequest();

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @ParameterizedTest
  @MethodSource("humanRequestAndLifecycleVariants")
  void humanRequestVariantsDenyBeforeOwnerAccessOrTransaction(
      Request request, WorldCanonicalInstanceLifecycleEvidence.Request lifecycle) {
    assertHumanUnavailable(new Fixtures(), request, lifecycle);
  }

  private static Stream<Arguments> humanRequestAndLifecycleVariants() {
    return Stream.of(
        Arguments.of(HOLD_REQUEST, LIFECYCLE_REQUEST),
        Arguments.of(holdRequest("different-request"), LIFECYCLE_REQUEST),
        Arguments.of(HOLD_REQUEST, lifecycleRequestWithChangedIdentity()),
        Arguments.of(
            holdRequest("closed-origin-request", InitialAdmissionOrigin.EXPECT_CLOSED, 1L),
            LIFECYCLE_REQUEST));
  }

  @org.junit.jupiter.api.Test
  void ambientTransactionOrSynchronizationDeniesBeforeRepositoryOrWorldAccess() {
    Fixtures transaction = new Fixtures();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> transaction.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");
    verifyNoInteractions(transaction.repository, transaction.worldClient);

    TransactionSynchronizationManager.clear();
    Fixtures synchronization = new Fixtures();
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> synchronization.preparation.prepare(HOLD_REQUEST, LIFECYCLE_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");
    verifyNoInteractions(synchronization.repository, synchronization.worldClient);
  }

  private static void assertHumanUnavailable(
      Fixtures fixtures,
      Request request,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycle) {
    assertThatThrownBy(() -> fixtures.preparation.prepare(request, lifecycle))
        .isInstanceOf(
            net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository
                .CanonicalHumanAdmissionProtectionUnavailableException.class)
        .hasMessageContaining("Account terminal protection");
    verifyNoInteractions(fixtures.repository, fixtures.worldClient);
    assertThat(fixtures.transactionManager.transactionDefinitions).isEmpty();
  }

  private static Request holdRequest(String requestId) {
    return holdRequest(requestId, InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
  }

  private static Request holdRequest(
      String requestId, InitialAdmissionOrigin origin, Long priorPointerVersion) {
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
            CanonicalInitialAdmissionRequest.OriginKind.valueOf(origin.name()),
            priorPointerVersion,
            requestId);
    return new Request(
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
        origin,
        1L,
        priorPointerVersion);
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

  private static WorldCanonicalInstanceLifecycleEvidence.Request
      lifecycleRequestWithChangedIdentity() {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        uuid(7),
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

  private static final class Fixtures {
    private final CanonicalInitialAdmissionIntentRepository repository =
        mock(CanonicalInitialAdmissionIntentRepository.class);
    private final WorldCanonicalInitialAdmissionHoldClient worldClient =
        mock(WorldCanonicalInitialAdmissionHoldClient.class);
    private final RecordingTransactionManager transactionManager;
    private final CanonicalInitialAdmissionHoldPreparation preparation;

    private Fixtures() {
      this.transactionManager = new RecordingTransactionManager();
      this.preparation =
          new CanonicalInitialAdmissionHoldPreparation(repository, worldClient, transactionManager);
    }
  }

  private static final class RecordingTransactionManager
      extends AbstractPlatformTransactionManager {
    private final List<TransactionDefinition> transactionDefinitions = new ArrayList<>();

    private RecordingTransactionManager() {}

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      transactionDefinitions.add(definition);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
