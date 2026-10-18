package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadClient;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic exact source/freeze fixtures; Account and retained storage are explicit boundaries. */
class WorldSelectedOwnerInventoryReadServiceTest {
  private static final String NAMESPACE = "firemud";

  @AfterEach
  void clearRequestContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesOwnerWorkloadBeforeParsingAndRequiresIndependentRead() {
    var inventory = mock(WorldSelectedPublicationArtifactInventoryReadService.class);
    var account = mock(SelectedOwnerIntakeWorldClosureAuthorizationReadClient.class);
    var orchestrator = new WorldSelectedOwnerInventoryReadService(NAMESPACE, inventory, account);
    var receiver = new WorldSelectedOwnerInventoryReadGrpcService(NAMESPACE, orchestrator);
    var malformed = ReadSelectedOwnerWorldInventoryRequest.getDefaultInstance();

    var anonymous = new RecordingObserver();
    withoutPeer(() -> receiver.readSelectedOwnerWorldInventory(malformed, anonymous));
    assertDenied(anonymous, Status.Code.UNAUTHENTICATED);

    var wrongWorkload = new RecordingObserver();
    withPeer(
        peer(NAMESPACE, "game-design-service"),
        () -> receiver.readSelectedOwnerWorldInventory(malformed, wrongWorkload));
    assertDenied(wrongWorkload, Status.Code.PERMISSION_DENIED);

    var wrongNamespace = new RecordingObserver();
    withPeer(
        peer("other", "entity-management-service"),
        () -> receiver.readSelectedOwnerWorldInventory(malformed, wrongNamespace));
    assertDenied(wrongNamespace, Status.Code.PERMISSION_DENIED);

    SessionContext.setContext(
        "11111111-1111-4111-8111-111111111111", java.util.List.of(), java.util.Map.of());
    var endUser = new RecordingObserver();
    withPeer(
        peer(NAMESPACE, "automation-scripting-service"),
        () -> receiver.readSelectedOwnerWorldInventory(malformed, endUser));
    assertDenied(endUser, Status.Code.PERMISSION_DENIED);
    SessionContext.clear();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      var ambient = new RecordingObserver();
      withPeer(
          peer(NAMESPACE, "entity-management-service"),
          () -> receiver.readSelectedOwnerWorldInventory(malformed, ambient));
      assertDenied(ambient, Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    var authenticatedMalformed = new RecordingObserver();
    withPeer(
        peer(NAMESPACE, "entity-management-service"),
        () -> receiver.readSelectedOwnerWorldInventory(malformed, authenticatedMalformed));
    assertDenied(authenticatedMalformed, Status.Code.INVALID_ARGUMENT);

    var authenticatedAutomationMalformed = new RecordingObserver();
    withPeer(
        peer(NAMESPACE, "automation-scripting-service"),
        () ->
            receiver.readSelectedOwnerWorldInventory(malformed, authenticatedAutomationMalformed));
    assertDenied(authenticatedAutomationMalformed, Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(inventory, account);
  }

  @Test
  void accountConfirmationPrecedesTheExactRetainedReadForBothOwners() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var fixture = WorldSelectedOwnerInventoryTestFixtures.fixture(owner);
      var inventoryRead = mock(WorldSelectedPublicationArtifactInventoryReadService.class);
      var account = mock(SelectedOwnerIntakeWorldClosureAuthorizationReadClient.class);
      var retainedInventory = mock(WorldSelectedPublicationArtifactInventoryEvidence.class);
      when(retainedInventory.freezeEvidence()).thenReturn(fixture.freezeEvidence());
      when(retainedInventory.canonicalBytes()).thenReturn(new byte[] {1, 2, 3});
      when(retainedInventory.digest()).thenReturn("sha256:" + "a".repeat(64));

      var callOrder = new ArrayList<String>();
      when(account.readHeld(any()))
          .thenAnswer(
              invocation -> {
                var request =
                    (SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request)
                        invocation.getArgument(0);
                callOrder.add("account");
                assertThat(request.binding().canonicalBytes())
                    .containsExactly(fixture.binding().canonicalBytes());
                assertThat(request.intendedReader())
                    .isEqualTo("spiffe://firemud/ns/firemud/sa/world-management-service");
                assertThat(request.targetNamespace()).isEqualTo(NAMESPACE);
                assertThat(request.readRequestId()).isNotEqualTo(fixture.request().readRequestId());
                assertThat(request.closureReadPurpose())
                    .isEqualTo(
                        owner == Owner.ENTITY_MANAGEMENT
                            ? "ENTITY_INTAKE_WORLD_CLOSURE_READ"
                            : "AUTOMATION_INTAKE_WORLD_CLOSURE_READ");
                return new SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence(request);
              });
      when(inventoryRead.readRetained(fixture.freezeEvidence()))
          .thenAnswer(
              invocation -> {
                callOrder.add("retained-world");
                return retainedInventory;
              });

      var service = new WorldSelectedOwnerInventoryReadService(NAMESPACE, inventoryRead, account);
      var result =
          withPeer(peer(NAMESPACE, serviceName(owner)), () -> service.read(fixture.request()));

      assertThat(callOrder).containsExactly("account", "retained-world");
      assertThat(result.request()).isEqualTo(fixture.request());
      assertThat(result.request().authorizationBinding().canonicalBytes())
          .containsExactly(fixture.binding().canonicalBytes());
      assertThat(result.inventory()).isSameAs(retainedInventory);
      verify(account).readHeld(any());
      verify(inventoryRead).readRetained(fixture.freezeEvidence());
    }
  }

  @Test
  void absentOrChangedAccountEchoNeverReadsWorldStorage() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var fixture = WorldSelectedOwnerInventoryTestFixtures.fixture(owner);
      for (boolean absent : List.of(true, false)) {
        var inventoryRead = mock(WorldSelectedPublicationArtifactInventoryReadService.class);
        var account = mock(SelectedOwnerIntakeWorldClosureAuthorizationReadClient.class);
        when(account.readHeld(any()))
            .thenAnswer(
                invocation -> {
                  var expected =
                      (SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request)
                          invocation.getArgument(0);
                  if (absent) return null;
                  var changed =
                      SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create(
                          NAMESPACE, fixture.binding());
                  while (changed.readRequestId().equals(expected.readRequestId())) {
                    changed =
                        SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create(
                            NAMESPACE, fixture.binding());
                  }
                  return new SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence(changed);
                });
        var service = new WorldSelectedOwnerInventoryReadService(NAMESPACE, inventoryRead, account);

        assertThatThrownBy(
                () ->
                    withPeer(
                        peer(NAMESPACE, serviceName(owner)), () -> service.read(fixture.request())))
            .isInstanceOfSatisfying(
                WorldSelectedOwnerInventoryReadService.ReadFailure.class,
                failure -> {
                  assertThat(failure.statusCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                  assertThat(failure.description())
                      .isEqualTo(
                          "Account did not confirm the exact selected-owner World closure read");
                  assertThat(failure.getCause()).isNull();
                });
        verify(inventoryRead, never()).readRetained(any());
      }
    }
  }

  @Test
  void domainServiceRejectsWrongRecipientEndUserAndAmbientSqlBeforeAccountCall() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var fixture = WorldSelectedOwnerInventoryTestFixtures.fixture(owner);
      var inventoryRead = mock(WorldSelectedPublicationArtifactInventoryReadService.class);
      var account = mock(SelectedOwnerIntakeWorldClosureAuthorizationReadClient.class);
      var service = new WorldSelectedOwnerInventoryReadService(NAMESPACE, inventoryRead, account);
      String caller = serviceName(owner);
      String wrongCaller =
          serviceName(
              owner == Owner.ENTITY_MANAGEMENT
                  ? Owner.AUTOMATION_SCRIPTING
                  : Owner.ENTITY_MANAGEMENT);

      assertThatThrownBy(
              () -> withPeer(peer(NAMESPACE, wrongCaller), () -> service.read(fixture.request())))
          .isInstanceOf(SecurityException.class);

      SessionContext.setContext("abababab-abab-4bab-8bab-abababababab", List.of(), Map.of());
      try {
        assertThatThrownBy(
                () -> withPeer(peer(NAMESPACE, caller), () -> service.read(fixture.request())))
            .isInstanceOf(SecurityException.class);
      } finally {
        SessionContext.clear();
      }

      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(
                () -> withPeer(peer(NAMESPACE, caller), () -> service.read(fixture.request())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no ambient SQL");
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      verifyNoInteractions(account, inventoryRead);
    }
  }

  private static String serviceName(Owner owner) {
    return owner == Owner.ENTITY_MANAGEMENT
        ? "entity-management-service"
        : "automation-scripting-service";
  }

  private static void assertDenied(RecordingObserver observer, Status.Code code) {
    assertThat(Status.fromThrowable(observer.error).getCode()).isEqualTo(code);
    assertThat(observer.response).isNull();
    assertThat(observer.completed).isFalse();
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private static <T> T withPeer(GrpcPeerIdentity peer, java.util.function.Supplier<T> action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    withPeer(
        peer,
        () -> {
          action.run();
          return null;
        });
  }

  private static void withoutPeer(Runnable action) {
    Context previous = Context.ROOT.attach();
    try {
      action.run();
    } finally {
      Context.ROOT.detach(previous);
    }
  }

  private static final class RecordingObserver
      implements StreamObserver<ReadSelectedOwnerWorldInventoryResponse> {
    ReadSelectedOwnerWorldInventoryResponse response;
    Throwable error;
    boolean completed;

    @Override
    public void onNext(ReadSelectedOwnerWorldInventoryResponse value) {
      response = value;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Test observer intentionally retains the reported failure.")
    public void onError(Throwable failure) {
      error = failure;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
