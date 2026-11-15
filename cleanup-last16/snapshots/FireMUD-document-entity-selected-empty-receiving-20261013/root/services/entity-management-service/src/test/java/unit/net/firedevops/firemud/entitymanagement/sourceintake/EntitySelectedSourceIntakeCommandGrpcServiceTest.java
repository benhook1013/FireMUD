package unit.net.firedevops.firemud.entitymanagement.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandEvidence;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderCredentials;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.sourceintake.EntityEmptySelectedSourceIntakeService;
import net.firedevops.firemud.entitymanagement.sourceintake.EntitySelectedSourceIntakeCommandGrpcService;
import net.firedevops.firemud.entitymanagement.v1.RetainSelectedEntitySourceRequest;
import net.firedevops.firemud.entitymanagement.v1.RetainSelectedEntitySourceResponse;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked owner tests verify gates and delegation, not native producer or physical census proof. */
class EntitySelectedSourceIntakeCommandGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final String GAME_DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static Fixture fixture;

  @BeforeAll
  static void createSyntheticEntityInputs() {
    fixture = EntitySelectedSourceIntakeTerminalReadFixtures.create();
  }

  @BeforeEach
  @AfterEach
  void clearContexts() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatedGameDesignGetsExactRetainedReceiptFromOwner() {
    var owner = mock(EntityEmptySelectedSourceIntakeService.class);
    var freeze = fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence();
    var request =
        EntitySelectedSourceIntakeCommandEvidence.Request.create(
            NAMESPACE, fixture.authorization(), freeze);
    when(owner.retain(
            eq(NAMESPACE),
            any(SelectedOwnerIntakeAuthorizationBinding.class),
            any(WorldSelectedDraftPublicationFreezeEvidence.class)))
        .thenReturn(fixture.receipt());
    var service = new EntitySelectedSourceIntakeCommandGrpcService(owner, NAMESPACE);
    var observedResponse = new AtomicReference<RetainSelectedEntitySourceResponse>();
    var observer = new CapturingObserver(observedResponse);
    var accountContext = gameDesignPeer(NAMESPACE);
    var previous = accountContext.attach();
    try {
      service.retainSelectedEntitySource(
          EntitySelectedSourceIntakeCommandGrpcCodec.toRequest(request), observer);
    } finally {
      accountContext.detach(previous);
    }

    assertThat(observer.failure()).isNull();
    assertThat(observedResponse.get()).isNotNull();
    assertThat(observedResponse.get().getRequest())
        .isEqualTo(EntitySelectedSourceIntakeCommandGrpcCodec.toRequest(request));
    var evidence =
        EntitySelectedSourceIntakeCommandGrpcCodec.fromResponse(request, observedResponse.get());
    assertThat(evidence.receipt().canonicalBytes()).isEqualTo(fixture.receipt().canonicalBytes());
    var authorizationArgument =
        ArgumentCaptor.forClass(SelectedOwnerIntakeAuthorizationBinding.class);
    var freezeArgument = ArgumentCaptor.forClass(WorldSelectedDraftPublicationFreezeEvidence.class);
    verify(owner).retain(eq(NAMESPACE), authorizationArgument.capture(), freezeArgument.capture());
    assertThat(authorizationArgument.getValue().canonicalBytes())
        .isEqualTo(fixture.authorization().canonicalBytes());
    assertThat(
            WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(
                freezeArgument.getValue().request()))
        .isEqualTo(WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(freeze.request()));
    assertThat(
            WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(
                freezeArgument.getValue().acknowledgement()))
        .isEqualTo(
            WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(freeze.acknowledgement()));
  }

  @Test
  void rejectsMissingWrongPeerAndWrongNamespaceBeforeDecodeOrOwnerInvocation() {
    var owner = mock(EntityEmptySelectedSourceIntakeService.class);
    var service = new EntitySelectedSourceIntakeCommandGrpcService(owner, NAMESPACE);

    var missingPeer = new CapturingObserver(new AtomicReference<>());
    service.retainSelectedEntitySource(
        RetainSelectedEntitySourceRequest.getDefaultInstance(), missingPeer);
    assertCode(missingPeer.failure(), Status.Code.UNAUTHENTICATED);

    var wrongPeer = gameDesignPeer("other");
    var previous = wrongPeer.attach();
    try {
      var denied = new CapturingObserver(new AtomicReference<>());
      service.retainSelectedEntitySource(
          RetainSelectedEntitySourceRequest.getDefaultInstance(), denied);
      assertCode(denied.failure(), Status.Code.PERMISSION_DENIED);
    } finally {
      wrongPeer.detach(previous);
    }

    var goodPeer = gameDesignPeer(NAMESPACE);
    previous = goodPeer.attach();
    try {
      var mismatchedTarget = new CapturingObserver(new AtomicReference<>());
      service.retainSelectedEntitySource(
          RetainSelectedEntitySourceRequest.newBuilder().setTargetNamespace("other").build(),
          mismatchedTarget);
      assertCode(mismatchedTarget.failure(), Status.Code.PERMISSION_DENIED);
    } finally {
      goodPeer.detach(previous);
    }
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsEndUserSelectedOrderAndAmbientSqlBeforeMalformedEvidenceDecode() {
    var owner = mock(EntityEmptySelectedSourceIntakeService.class);
    var service = new EntitySelectedSourceIntakeCommandGrpcService(owner, NAMESPACE);
    var peer = gameDesignPeer(NAMESPACE);
    var malformed = RetainSelectedEntitySourceRequest.getDefaultInstance();
    var previous = peer.attach();
    try {
      SessionContext.setContext("101", List.of(), Map.of());
      var endUser = new CapturingObserver(new AtomicReference<>());
      service.retainSelectedEntitySource(malformed, endUser);
      assertCode(endUser.failure(), Status.Code.PERMISSION_DENIED);
      SessionContext.clear();

      var selectedOrder =
          Context.current()
              .withValue(
                  AccountSelectedPublicationOrderCredentials.CONTEXT_KEY,
                  AccountSelectedPublicationOrderCredentials.Credential.of(
                      "synthetic-selected-order-credential"));
      var selectedOrderPrevious = selectedOrder.attach();
      try {
        var denied = new CapturingObserver(new AtomicReference<>());
        service.retainSelectedEntitySource(malformed, denied);
        assertCode(denied.failure(), Status.Code.PERMISSION_DENIED);
      } finally {
        selectedOrder.detach(selectedOrderPrevious);
      }

      var malformedEvidence =
          RetainSelectedEntitySourceRequest.newBuilder().setTargetNamespace(NAMESPACE).build();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      var activeSql = new CapturingObserver(new AtomicReference<>());
      service.retainSelectedEntitySource(malformedEvidence, activeSql);
      assertCode(activeSql.failure(), Status.Code.FAILED_PRECONDITION);
      TransactionSynchronizationManager.clear();

      TransactionSynchronizationManager.initSynchronization();
      var synchronizedSql = new CapturingObserver(new AtomicReference<>());
      service.retainSelectedEntitySource(malformedEvidence, synchronizedSql);
      assertCode(synchronizedSql.failure(), Status.Code.FAILED_PRECONDITION);
      TransactionSynchronizationManager.clear();

      var invalidEvidence = new CapturingObserver(new AtomicReference<>());
      service.retainSelectedEntitySource(malformedEvidence, invalidEvidence);
      assertCode(invalidEvidence.failure(), Status.Code.INVALID_ARGUMENT);
    } finally {
      peer.detach(previous);
    }
    verifyNoInteractions(owner);
  }

  private static void assertCode(Throwable error, Status.Code expected) {
    assertThat(error).isNotNull();
    assertThat(Status.fromThrowable(error).getCode()).isEqualTo(expected);
  }

  private static Context gameDesignPeer(String namespace) {
    String uri = "spiffe://firemud/ns/" + namespace + "/sa/game-design-service";
    return Context.ROOT.withValue(
        GrpcPeerIdentity.CONTEXT_KEY, new GrpcPeerIdentity(uri, namespace, "game-design-service"));
  }

  private static final class CapturingObserver
      implements StreamObserver<RetainSelectedEntitySourceResponse> {
    private final AtomicReference<RetainSelectedEntitySourceResponse> response;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private CapturingObserver(AtomicReference<RetainSelectedEntitySourceResponse> response) {
      this.response = response;
    }

    @Override
    public void onNext(RetainSelectedEntitySourceResponse value) {
      response.set(value);
    }

    @Override
    public void onError(Throwable error) {
      failure.set(error);
    }

    @Override
    public void onCompleted() {}

    Throwable failure() {
      return failure.get();
    }
  }
}
