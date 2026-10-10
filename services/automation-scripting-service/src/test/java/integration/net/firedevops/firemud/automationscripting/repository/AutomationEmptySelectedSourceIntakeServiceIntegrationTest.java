package net.firedevops.firemud.automationscripting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationEmptySelectedSourceIntakeRepository;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationEmptySelectedSourceIntakeService;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadClient;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Mocked service ordering proof using exact typed, synthetic Common owner-input fixtures.
 * Collaborator echoes here are not authenticated producer or physical owner proof.
 */
class AutomationEmptySelectedSourceIntakeServiceIntegrationTest {
  private static final String NAMESPACE =
      AutomationEmptySelectedSourceIntakePostgresFixture.NAMESPACE;

  @BeforeEach
  void clearPriorContexts() {
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @AfterEach
  void clearContexts() {
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void freshTypedInputsReachRepositoryOnlyAfterTwoAccountEchoesAndWorldEcho() {
    var fixture = AutomationEmptySelectedSourceIntakePostgresFixture.create("1");
    var inputs = fixture.inputs();
    var repository = mock(AutomationEmptySelectedSourceIntakeRepository.class);
    var accountClient = mock(SelectedOwnerIntakeAuthorizationReadClient.class);
    var worldClient = mock(SelectedOwnerWorldInventoryReadClient.class);
    var receipt = mock(AutomationEmptySelectedSourceIntakeReceipt.class);
    when(repository.read(NAMESPACE, inputs.authorizationBinding().intakeRequestId()))
        .thenReturn(Optional.empty());
    when(repository.retainFresh(any(SelectedOwnerEmptySourceInputs.class), anyString()))
        .thenReturn(receipt);
    when(accountClient.read(any()))
        .thenAnswer(
            invocation ->
                new SelectedOwnerIntakeAuthorizationReadEvidence(invocation.getArgument(0)));
    when(worldClient.read(any()))
        .thenAnswer(
            invocation ->
                new SelectedOwnerWorldInventoryReadEvidence(
                    invocation.getArgument(0), fixture.worldEvidence().inventory()));

    var service =
        new AutomationEmptySelectedSourceIntakeService(repository, accountClient, worldClient);
    String requestDigest =
        AutomationEmptySelectedSourceIntakeReceipt.requestDigest(
            NAMESPACE, fixture.authorization(), fixture.freezeEvidence());

    AutomationEmptySelectedSourceIntakeReceipt returned =
        withGameDesignPeer(
            () -> service.retain(NAMESPACE, fixture.authorization(), fixture.freezeEvidence()));

    assertThat(returned).isSameAs(receipt);
    ArgumentCaptor<SelectedOwnerIntakeAuthorizationReadEvidence.Request> accountRequests =
        ArgumentCaptor.forClass(SelectedOwnerIntakeAuthorizationReadEvidence.Request.class);
    ArgumentCaptor<SelectedOwnerWorldInventoryReadEvidence.Request> worldRequests =
        ArgumentCaptor.forClass(SelectedOwnerWorldInventoryReadEvidence.Request.class);
    ArgumentCaptor<SelectedOwnerEmptySourceInputs> retainedInputs =
        ArgumentCaptor.forClass(SelectedOwnerEmptySourceInputs.class);
    InOrder order = inOrder(repository, accountClient, worldClient, receipt);
    order.verify(repository).read(NAMESPACE, inputs.authorizationBinding().intakeRequestId());
    order.verify(accountClient).read(accountRequests.capture());
    order.verify(worldClient).read(worldRequests.capture());
    order.verify(accountClient).read(accountRequests.capture());
    order.verify(repository).retainFresh(retainedInputs.capture(), eq(requestDigest));
    order
        .verify(receipt)
        .requireSameRequest(NAMESPACE, fixture.authorization(), fixture.freezeEvidence());

    assertThat(accountRequests.getAllValues()).hasSize(2);
    assertThat(accountRequests.getAllValues().get(0).binding()).isEqualTo(fixture.authorization());
    assertThat(accountRequests.getAllValues().get(1).binding()).isEqualTo(fixture.authorization());
    assertThat(accountRequests.getAllValues().get(0).readRequestId())
        .isNotEqualTo(accountRequests.getAllValues().get(1).readRequestId());
    assertThat(worldRequests.getValue().authorizationBinding()).isEqualTo(fixture.authorization());
    assertThat(worldRequests.getValue().freezeEvidence()).isEqualTo(fixture.freezeEvidence());

    SelectedOwnerEmptySourceInputs actualInputs = retainedInputs.getValue();
    assertThat(actualInputs.authorizationBinding().canonicalBytes())
        .containsExactly(fixture.authorization().canonicalBytes());
    assertThat(actualInputs.authorizationBinding().digest())
        .isEqualTo(fixture.authorization().digest());
    assertThat(actualInputs.sourceContent().canonicalBytes())
        .containsExactly(fixture.authorization().content().canonicalBytes());
    assertThat(actualInputs.sourceContent().digest())
        .isEqualTo(fixture.authorization().content().digest());
    for (String family :
        List.of(
            "COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG")) {
      assertThat(actualInputs.sourceContent().snapshotBytes(family))
          .containsExactly(fixture.authorization().content().snapshotBytes(family));
    }
    var actualDeclaration = actualInputs.ownerSourceInventoryDeclaration();
    var expectedDeclaration = inputs.ownerSourceInventoryDeclaration();
    assertThat(actualDeclaration.owner()).isEqualTo(expectedDeclaration.owner());
    assertThat(actualDeclaration.inventory().canonicalJson())
        .isEqualTo(expectedDeclaration.inventory().canonicalJson());
    assertThat(actualDeclaration.sourceBinding().canonicalBytes())
        .containsExactly(expectedDeclaration.sourceBinding().canonicalBytes());
    assertThat(actualDeclaration.revisionOrder()).isEqualTo(expectedDeclaration.revisionOrder());
    assertThat(actualDeclaration.revisionId()).isEqualTo(expectedDeclaration.revisionId());
    assertThat(actualInputs.worldInventoryReadEvidence().request())
        .isEqualTo(worldRequests.getValue());
    assertThat(actualInputs.worldInventoryReadEvidence().inventory().canonicalBytes())
        .containsExactly(fixture.worldEvidence().inventory().canonicalBytes());
    assertThat(actualInputs.worldInventoryReadEvidence().inventory().digest())
        .isEqualTo(fixture.worldEvidence().inventory().digest());
  }

  @Test
  void unavailableOrMismatchedAccountAndWorldEchoesNeverReachFreshRetention() {
    var fixture = AutomationEmptySelectedSourceIntakePostgresFixture.create("1");
    for (ReadFailure failure : ReadFailure.values()) {
      var inputs = fixture.inputs();
      var repository = mock(AutomationEmptySelectedSourceIntakeRepository.class);
      var accountClient = mock(SelectedOwnerIntakeAuthorizationReadClient.class);
      var worldClient = mock(SelectedOwnerWorldInventoryReadClient.class);
      when(repository.read(NAMESPACE, inputs.authorizationBinding().intakeRequestId()))
          .thenReturn(Optional.empty());

      AtomicInteger accountReads = new AtomicInteger();
      when(accountClient.read(any()))
          .thenAnswer(
              invocation -> {
                var request =
                    invocation.getArgument(
                        0, SelectedOwnerIntakeAuthorizationReadEvidence.Request.class);
                int call = accountReads.incrementAndGet();
                if (failure == ReadFailure.FIRST_ACCOUNT_UNAVAILABLE && call == 1
                    || failure == ReadFailure.FINAL_ACCOUNT_UNAVAILABLE && call == 2) {
                  return null;
                }
                if (failure == ReadFailure.FIRST_ACCOUNT_MISMATCH && call == 1
                    || failure == ReadFailure.FINAL_ACCOUNT_MISMATCH && call == 2) {
                  request = mismatched(request);
                }
                return new SelectedOwnerIntakeAuthorizationReadEvidence(request);
              });

      AtomicInteger worldReads = new AtomicInteger();
      when(worldClient.read(any()))
          .thenAnswer(
              invocation -> {
                var request =
                    invocation.getArgument(
                        0, SelectedOwnerWorldInventoryReadEvidence.Request.class);
                worldReads.incrementAndGet();
                if (failure == ReadFailure.WORLD_UNAVAILABLE) return null;
                if (failure == ReadFailure.WORLD_MISMATCH) request = mismatched(request);
                return new SelectedOwnerWorldInventoryReadEvidence(
                    request, fixture.worldEvidence().inventory());
              });

      var service =
          new AutomationEmptySelectedSourceIntakeService(repository, accountClient, worldClient);
      assertThatThrownBy(
              () ->
                  withGameDesignPeer(
                      () ->
                          service.retain(
                              NAMESPACE, fixture.authorization(), fixture.freezeEvidence())))
          .as(failure.name())
          .isInstanceOf(IllegalStateException.class);

      verify(repository).read(NAMESPACE, inputs.authorizationBinding().intakeRequestId());
      verify(repository, never())
          .retainFresh(any(SelectedOwnerEmptySourceInputs.class), anyString());
      int expectedAccountReads =
          failure == ReadFailure.FINAL_ACCOUNT_UNAVAILABLE
                  || failure == ReadFailure.FINAL_ACCOUNT_MISMATCH
              ? 2
              : 1;
      int expectedWorldReads =
          failure == ReadFailure.FIRST_ACCOUNT_UNAVAILABLE
                  || failure == ReadFailure.FIRST_ACCOUNT_MISMATCH
              ? 0
              : 1;
      assertThat(accountReads.get()).isEqualTo(expectedAccountReads);
      assertThat(worldReads.get()).isEqualTo(expectedWorldReads);
    }
  }

  @Test
  void exactStoredRetryReturnsBeforeRemoteOwnerReads() {
    var fixture = AutomationEmptySelectedSourceIntakePostgresFixture.create("1");
    var repository = mock(AutomationEmptySelectedSourceIntakeRepository.class);
    var accountClient = mock(SelectedOwnerIntakeAuthorizationReadClient.class);
    var worldClient = mock(SelectedOwnerWorldInventoryReadClient.class);
    var retained = mock(AutomationEmptySelectedSourceIntakeReceipt.class);
    when(repository.read(NAMESPACE, fixture.authorization().intakeRequestId()))
        .thenReturn(Optional.of(retained));
    var service =
        new AutomationEmptySelectedSourceIntakeService(repository, accountClient, worldClient);

    var returned =
        withGameDesignPeer(
            () -> service.retain(NAMESPACE, fixture.authorization(), fixture.freezeEvidence()));

    assertThat(returned).isSameAs(retained);
    verify(repository).read(NAMESPACE, fixture.authorization().intakeRequestId());
    verify(retained)
        .requireSameRequest(NAMESPACE, fixture.authorization(), fixture.freezeEvidence());
    verify(repository, never()).retainFresh(any(SelectedOwnerEmptySourceInputs.class), anyString());
    verifyNoInteractions(accountClient, worldClient);
  }

  private static SelectedOwnerIntakeAuthorizationReadEvidence.Request mismatched(
      SelectedOwnerIntakeAuthorizationReadEvidence.Request request) {
    UUID differentId =
        differentReadId(
            request.readRequestId(),
            request.binding().operationId(),
            request.binding().fenceId(),
            request.binding().intakeRequestId());
    return new SelectedOwnerIntakeAuthorizationReadEvidence.Request(
        request.schemaVersion(), request.targetNamespace(), differentId, request.binding());
  }

  private static SelectedOwnerWorldInventoryReadEvidence.Request mismatched(
      SelectedOwnerWorldInventoryReadEvidence.Request request) {
    var binding = request.authorizationBinding();
    var accountBinding = request.freezeEvidence().request().accountBinding();
    var acknowledgement = request.freezeEvidence().acknowledgement();
    UUID differentId =
        differentReadId(
            request.readRequestId(),
            binding.operationId(),
            binding.fenceId(),
            binding.intakeRequestId(),
            accountBinding.operationId(),
            accountBinding.fenceId(),
            acknowledgement.intakeRequestId(),
            acknowledgement.publicationFence());
    return new SelectedOwnerWorldInventoryReadEvidence.Request(
        request.schemaVersion(),
        request.targetNamespace(),
        differentId,
        binding,
        request.freezeEvidence());
  }

  private static UUID differentReadId(UUID original, UUID... reserved) {
    UUID candidate;
    do {
      candidate = UUID.randomUUID();
    } while (candidate.equals(original) || contains(reserved, candidate));
    return candidate;
  }

  private static boolean contains(UUID[] values, UUID candidate) {
    for (UUID value : values) {
      if (candidate.equals(value)) return true;
    }
    return false;
  }

  private static <T> T withGameDesignPeer(Supplier<T> action) {
    GrpcPeerIdentity peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-design-service",
            NAMESPACE,
            "game-design-service");
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private enum ReadFailure {
    FIRST_ACCOUNT_UNAVAILABLE,
    FIRST_ACCOUNT_MISMATCH,
    WORLD_UNAVAILABLE,
    WORLD_MISMATCH,
    FINAL_ACCOUNT_UNAVAILABLE,
    FINAL_ACCOUNT_MISMATCH
  }
}
