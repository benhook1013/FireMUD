package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.firedevops.firemud.account.v1.ReadHistoricalStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.ReadHistoricalStartSessionWorldParticipationResponse;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadGrpcCodec;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;

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
            .setOriginalPostAuthorizationTuple(
                ByteString.copyFrom(originalTuple().canonicalBytes()))
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

  @Test
  void returnsResolvedCoordinatesForTheExactOriginalTupleLookup() throws Exception {
    var wire = validWire();
    var decoded = AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromRequest(wire);
    var evidence =
        new AccountStartSessionWorldParticipationHistoricalReadEvidence(
            decoded,
            UUID.fromString("a8c1e8c8-f237-41b7-918d-ec2281bcac10"),
            31L,
            UUID.fromString("b9d2f9d9-0438-42c8-829e-fd3392cd9d21"),
            37L,
            UUID.fromString("c0e30aea-1549-43d9-93af-0e44a3deae32"),
            "{\"retained\":true}",
            digest("{\"retained\":true}"));
    when(owner.read(decoded)).thenReturn(evidence);
    var response = new AtomicReference<ReadHistoricalStartSessionWorldParticipationResponse>();
    var completed = new AtomicBoolean();
    StreamObserver<ReadHistoricalStartSessionWorldParticipationResponse> observer =
        new StreamObserver<>() {
          @Override
          public void onNext(ReadHistoricalStartSessionWorldParticipationResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable error) {
            throw new AssertionError("historical tuple read unexpectedly failed", error);
          }

          @Override
          public void onCompleted() {
            completed.set(true);
          }
        };

    peer(
        NAMESPACE,
        "world-management-service",
        () -> {
          invoke(wire, observer);
          return null;
        });

    assertThat(completed).isTrue();
    assertThat(response.get().getRequest()).isEqualTo(wire);
    assertThat(response.get().getAccountWorldParticipationId())
        .isEqualTo("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
    assertThat(response.get().getAccountWorldParticipationFence()).isEqualTo(31L);
    assertThat(response.get().getGameSessionOwnerAttemptId())
        .isEqualTo("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
    verify(owner).read(decoded);
  }

  @Test
  void mapsConnectionFailuresToRedactedUnavailable() {
    assertOwnerFailure(
        new CannotCreateTransactionException("sensitive transaction detail"),
        Status.Code.UNAVAILABLE);
    assertOwnerFailure(
        new org.jooq.exception.DataAccessException(
            "sensitive jOOQ detail", new SQLException("sensitive SQL detail", "08006")),
        Status.Code.UNAVAILABLE);
    assertOwnerFailure(
        new DataAccessResourceFailureException(
            "sensitive Spring detail", new SQLException("sensitive SQL detail", "08001")),
        Status.Code.UNAVAILABLE);
  }

  @Test
  void mapsNonConnectionPersistenceAndUnexpectedFailuresToRedactedInternal() {
    assertOwnerFailure(
        new org.jooq.exception.DataAccessException(
            "sensitive jOOQ detail", new SQLException("sensitive SQL detail", "23505")),
        Status.Code.INTERNAL);
    assertOwnerFailure(
        new DataIntegrityViolationException(
            "sensitive Spring detail", new SQLException("sensitive SQL detail", "23505")),
        Status.Code.INTERNAL);
    assertOwnerFailure(new RuntimeException("sensitive runtime detail"), Status.Code.INTERNAL);
  }

  @Test
  void mapsOwnerEvidenceMismatchesToFailedPrecondition() {
    assertOwnerFailure(
        new IllegalArgumentException("sensitive mismatch detail"), Status.Code.FAILED_PRECONDITION);
    assertOwnerFailure(
        new IllegalStateException("sensitive mismatch detail"), Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void preservesExplicitGrpcStatusFailures() {
    var expected = Status.PERMISSION_DENIED.withDescription("upstream denial");
    assertOwnerFailure(expected.asRuntimeException(), expected.getCode(), "upstream denial");
  }

  private static ReadHistoricalStartSessionWorldParticipationRequest validWire() {
    return ReadHistoricalStartSessionWorldParticipationRequest.newBuilder()
        .setSchemaVersion(1)
        .setReadRequestId("d1f41bfb-265a-4f3b-8b2a-124cba20ce43")
        .setTargetNamespace(NAMESPACE)
        .setOriginalPostAuthorizationTuple(ByteString.copyFrom(originalTuple().canonicalBytes()))
        .build();
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple() {
    String namespace = NAMESPACE;
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "historical-grpc-original-request",
            UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622"),
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(
                    UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531"), namespace),
                new StartSessionOperatorAction.Target(
                    91L, UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "synthetic historical gRPC boundary test"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
        19L,
        StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
            preTuple,
            new Values(
                UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f"),
                "sha256:" + "a".repeat(64),
                "17",
                "CURRENT",
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-01-02T00:00:00Z"),
                1L,
                2L,
                3L,
                4L,
                5L,
                23L,
                UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc"),
                "2",
                "3")),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static String digest(String json) throws Exception {
    return "sha256:"
        + HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
  }

  private void invoke(
      ReadHistoricalStartSessionWorldParticipationRequest request,
      StreamObserver<ReadHistoricalStartSessionWorldParticipationResponse> observer) {
    receiver.readHistoricalStartSessionWorldParticipation(request, observer);
  }

  private void assertOwnerFailure(RuntimeException failure, Status.Code expected) {
    assertOwnerFailure(failure, expected, null);
  }

  private void assertOwnerFailure(
      RuntimeException failure, Status.Code expected, String expectedDescription) {
    org.mockito.Mockito.reset(owner);
    var wire = validWire();
    var decoded = AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromRequest(wire);
    when(owner.read(decoded)).thenThrow(failure);
    @SuppressWarnings("unchecked")
    var observer =
        (StreamObserver<ReadHistoricalStartSessionWorldParticipationResponse>)
            mock(StreamObserver.class);

    peer(
        NAMESPACE,
        "world-management-service",
        () -> {
          invoke(wire, observer);
          return null;
        });

    var error = org.mockito.ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(error.capture());
    Status status = Status.fromThrowable(error.getValue());
    assertThat(status.getCode()).isEqualTo(expected);
    if (expectedDescription == null) {
      assertThat(String.valueOf(status.getDescription())).doesNotContain("sensitive");
    } else {
      assertThat(status.getDescription()).isEqualTo(expectedDescription);
    }
    verify(owner).read(decoded);
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
