package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import net.firedevops.firemud.account.v1.ReadHistoricalStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.ReadHistoricalStartSessionWorldParticipationResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture.Values;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
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
            .setOriginalPostAuthorizationTuple(ByteString.copyFrom(originalTupleBytes()))
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
        .setOriginalPostAuthorizationTuple(ByteString.copyFrom(originalTupleBytes()))
        .build();
  }

  private static byte[] originalTupleBytes() {
    UUID tenantId = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
    UUID actorId = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
    UUID targetAccountId = UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "historical-read-wire-original",
            actorId,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(tenantId, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, targetAccountId),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "historical read wire test"));
    byte[] bundle =
        StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
            preTuple,
            new Values(
                UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f"),
                "sha256:" + "a".repeat(64),
                "17",
                "CURRENT",
                Instant.parse("2026-10-09T00:00:00Z"),
                Instant.parse("2026-10-09T00:03:00Z"),
                1L,
                2L,
                3L,
                4L,
                5L,
                23L,
                UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc"),
                "2",
                "3"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
            "arfp/v1/test-key/" + "b".repeat(64),
            UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
            19L,
            bundle,
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                "17",
                "23",
                "18446744073709551615"))
        .canonicalBytes();
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
