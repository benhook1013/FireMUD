package unit.net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ResolveCurrentTenantCreationAuthorizationRequest;
import net.firedevops.firemud.account.v1.ResolveCurrentTenantCreationAuthorizationResponse;
import net.firedevops.firemud.accountservice.service.impl.TenantCreationCurrentAuthorizationReadGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationCurrentAuthorizationOwner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.CurrentTenantCreationAuthorizationGrpcCodec;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Handler guard/closed-message proof; peer contexts here are stipulated, not physical mTLS. */
class TenantCreationCurrentAuthorizationReadGrpcServiceTest {
  private static final String PEER = "spiffe://firemud/ns/test/sa/game-design-service";
  private final AccountTenantCreationCurrentAuthorizationOwner owner =
      mock(AccountTenantCreationCurrentAuthorizationOwner.class);
  private final TenantCreationCurrentAuthorizationReadGrpcService service =
      new TenantCreationCurrentAuthorizationReadGrpcService(owner, "test");
  private final UUID readId = UUID.randomUUID();
  private final Binding binding =
      new Binding(
          "test",
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          "sha256:" + "a".repeat(64),
          "sha256:" + "b".repeat(64));

  @AfterEach
  void clearCaller() {
    SessionContext.clear();
  }

  @Test
  void onlyTheExactGameDesignWorkloadReceivesAnOwnerBoundReply() {
    when(owner.resolve(binding))
        .thenReturn(
            new AccountTenantCreationCurrentAuthorizationOwner.Authorization(binding, 200000L));
    var result = call(request(), PEER);
    assertThat(result.error).isNull();
    assertThat(result.complete).isTrue();
    assertThat(
            CurrentTenantCreationAuthorizationGrpcCodec.verifyResponse(
                    readId, binding, result.response)
                .validBeforeEpochMillis())
        .isEqualTo(200000L);
  }

  @Test
  void absentWrongAndCrossNamespacePeersAndUserContextCannotReadTheOwner() {
    for (String peer :
        new String[] {
          null,
          "spiffe://firemud/ns/test/sa/account-service",
          "spiffe://firemud/ns/other/sa/game-design-service"
        }) {
      assertThat(call(request(), peer).error).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    SessionContext.setContext(UUID.randomUUID().toString(), List.of("player"), Map.of());
    assertThat(call(request(), PEER).error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void unknownFieldsAndWrongTargetNeverReachOwnerState() {
    var unknown =
        request().toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();
    assertThat(call(unknown, PEER).error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(call(request().toBuilder().setTargetNamespace("other").build(), PEER).error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  @Test
  void refusalAndStorageFailureReturnNoPartialAuthorization() {
    when(owner.resolve(binding))
        .thenThrow(new IllegalStateException("not current"))
        .thenThrow(new org.jooq.exception.DataAccessException("offline"));
    var refused = call(request(), PEER);
    assertThat(refused.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(refused.response).isNull();
    var unavailable = call(request(), PEER);
    assertThat(unavailable.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(unavailable.response).isNull();
  }

  private ResolveCurrentTenantCreationAuthorizationRequest request() {
    return ResolveCurrentTenantCreationAuthorizationRequest.newBuilder()
        .setSchemaVersion(1)
        .setReadRequestId(readId.toString())
        .setTargetNamespace(binding.targetNamespace())
        .setCreationRequestId(binding.creationRequestId().toString())
        .setAccountAuthorizationOperationId(binding.accountAuthorizationOperationId().toString())
        .setInitiatingAccountId(binding.initiatingAccountId().toString())
        .setCreationRequestDigest(binding.creationRequestDigest())
        .setAccountAuthorizationDigest(binding.accountAuthorizationDigest())
        .build();
  }

  private Reply call(ResolveCurrentTenantCreationAuthorizationRequest request, String peer) {
    var reply = new Reply();
    Runnable action = () -> service.resolveCurrentTenantCreationAuthorization(request, reply);
    if (peer == null) action.run();
    else
      Context.current()
          .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(peer).orElseThrow())
          .run(action);
    return reply;
  }

  private static final class Reply
      implements StreamObserver<ResolveCurrentTenantCreationAuthorizationResponse> {
    private ResolveCurrentTenantCreationAuthorizationResponse response;
    private Status.Code error;
    private boolean complete;

    @Override
    public void onNext(ResolveCurrentTenantCreationAuthorizationResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      complete = true;
    }
  }
}
