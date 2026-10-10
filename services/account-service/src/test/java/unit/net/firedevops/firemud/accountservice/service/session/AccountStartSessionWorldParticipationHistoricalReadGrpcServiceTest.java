package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.function.Consumer;
import net.firedevops.firemud.account.v1.ReadHistoricalStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.ReadHistoricalStartSessionWorldParticipationResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Verifies authentication-before-codec ordering; this is not mTLS or producer proof. */
class AccountStartSessionWorldParticipationHistoricalReadGrpcServiceTest {
  private static final String NAMESPACE = "world-runtime";

  private final AccountStartSessionWorldParticipationHistoricalReadService owner =
      mock(AccountStartSessionWorldParticipationHistoricalReadService.class);
  private final AccountStartSessionWorldParticipationHistoricalReadGrpcService receiver =
      new AccountStartSessionWorldParticipationHistoricalReadGrpcService(owner, NAMESPACE);

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
  }

  @Test
  void authenticatesExactWorldAndAbsentEndUserBeforeRequestDecodeOrOwnerAccess() {
    var malformed = ReadHistoricalStartSessionWorldParticipationRequest.newBuilder().build();
    assertRpcStatus(Status.Code.UNAUTHENTICATED, observer -> invoke(malformed, observer));
    assertRpcStatus(
        Status.Code.PERMISSION_DENIED,
        observer ->
            peer(
                NAMESPACE,
                "game-session-service",
                () -> {
                  invoke(malformed, observer);
                  return null;
                }));
    assertRpcStatus(
        Status.Code.PERMISSION_DENIED,
        observer ->
            peer(
                "other-runtime",
                "world-management-service",
                () -> {
                  invoke(malformed, observer);
                  return null;
                }));
    SessionContext.setContext("123", java.util.List.of(), java.util.Map.of());
    assertRpcStatus(
        Status.Code.PERMISSION_DENIED,
        observer ->
            peer(
                NAMESPACE,
                "world-management-service",
                () -> {
                  invoke(malformed, observer);
                  return null;
                }));
    verifyNoInteractions(owner);
  }

  @Test
  void bindsConfiguredNamespaceBeforeClosedRequestCodecAndRejectsUnknownOrWrongVersion() {
    var foreignNamespace =
        ReadHistoricalStartSessionWorldParticipationRequest.newBuilder()
            .setSchemaVersion(1)
            .setReadRequestId("d1f41bfb-265a-4f3b-8b2a-124cba20ce43")
            .setTargetNamespace("other-runtime")
            .setAccountWorldParticipationId("a8c1e8c8-f237-41b7-918d-ec2281bcac10")
            .setAccountWorldParticipationFence(31L)
            .build();
    var wrongVersion = validWire().toBuilder().setSchemaVersion(2).build();
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    var unknownField = validWire().toBuilder().setUnknownFields(unknown).build();

    assertRpcStatus(
        Status.Code.PERMISSION_DENIED,
        observer ->
            peer(
                NAMESPACE,
                "world-management-service",
                () -> {
                  invoke(foreignNamespace, observer);
                  return null;
                }));
    assertRpcStatus(
        Status.Code.INVALID_ARGUMENT,
        observer ->
            peer(
                NAMESPACE,
                "world-management-service",
                () -> {
                  invoke(wrongVersion, observer);
                  return null;
                }));
    assertRpcStatus(
        Status.Code.INVALID_ARGUMENT,
        observer ->
            peer(
                NAMESPACE,
                "world-management-service",
                () -> {
                  invoke(unknownField, observer);
                  return null;
                }));
    verifyNoInteractions(owner);
  }

  private static ReadHistoricalStartSessionWorldParticipationRequest validWire() {
    return ReadHistoricalStartSessionWorldParticipationRequest.newBuilder()
        .setSchemaVersion(1)
        .setReadRequestId("d1f41bfb-265a-4f3b-8b2a-124cba20ce43")
        .setTargetNamespace(NAMESPACE)
        .setAccountWorldParticipationId("a8c1e8c8-f237-41b7-918d-ec2281bcac10")
        .setAccountWorldParticipationFence(31L)
        .build();
  }

  private void invoke(
      ReadHistoricalStartSessionWorldParticipationRequest request,
      StreamObserver<ReadHistoricalStartSessionWorldParticipationResponse> observer) {
    receiver.readHistoricalStartSessionWorldParticipation(request, observer);
  }

  private static void assertRpcStatus(
      Status.Code expected,
      Consumer<StreamObserver<ReadHistoricalStartSessionWorldParticipationResponse>> action) {
    @SuppressWarnings("unchecked")
    var observer =
        (StreamObserver<ReadHistoricalStartSessionWorldParticipationResponse>)
            mock(StreamObserver.class);
    action.accept(observer);
    var error = org.mockito.ArgumentCaptor.forClass(Throwable.class);
    org.mockito.Mockito.verify(observer).onError(error.capture());
    assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(expected);
  }

  private static <T> T peer(
      String namespace, String workload, java.util.function.Supplier<T> action) {
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    var prior = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(prior);
    }
  }
}
