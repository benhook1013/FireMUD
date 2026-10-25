package unit.net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.gamesession.client.CanonicalInitialPlayerLocationClient;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationOutcome;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled-channel proof; owner digests are validated by grammar and remain World evidence. */
class CanonicalInitialPlayerLocationClientTest {
  private static final String NAMESPACE = "game-session-test";
  private static final String EXPECTED_WORLD_PEER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service";
  private static final String OPERATION_UUID = "11111111-1111-4111-8111-111111111111";
  private static final String ACCOUNT_UUID = "22222222-2222-4222-8222-222222222222";
  private static final String CHARACTER_UUID = "33333333-3333-4333-8333-333333333333";
  private static final String ASSIGNMENT_UUID = "44444444-4444-4444-8444-444444444444";
  private static final String INTENT_DIGEST = "a".repeat(64);
  private static final String REQUEST_DIGEST = "b".repeat(64);
  private static final String TERMINAL_PROOF_DIGEST = "sha256:" + "c".repeat(64);
  private static final String WORLD_REQUEST_DIGEST = "sha256:" + "d".repeat(64);
  private static final String WORLD_RESULT_DIGEST = "sha256:" + "e".repeat(64);

  @TempDir Path directory;
  private final GrpcChannelFactory channels = mock(GrpcChannelFactory.class);
  private final List<CanonicalInitialPlayerLocationClient> clients = new ArrayList<>();
  private CommonGrpcClientProperties tls;

  @BeforeEach
  void configureFileBackedTlsAndControlledChannel() throws Exception {
    tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "test-cert").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "test-key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "test-ca").toString());
    when(channels.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(mock(ManagedChannel.class));
  }

  @AfterEach
  void closeClientsAndClearTransactionContext() throws Exception {
    TransactionSynchronizationManager.clear();
    for (var client : clients) {
      client.close();
    }
  }

  @Test
  void returnsOnlyCheckedAppliedAndConflictResponsesForBothMethods() throws Exception {
    var request = request();
    var stub = mockStub();
    var applied = appliedResponse(request);
    when(stub.placeInitialPlayer(request)).thenReturn(applied);
    when(stub.readInitialPlayerLocation(request)).thenReturn(applied);
    var client = clientWithStub(stub);

    assertThat(client.placeInitialPlayer(request)).isEqualTo(applied);
    assertThat(client.readInitialPlayerLocation(request)).isEqualTo(applied);
    verify(stub, times(2)).withDeadlineAfter(20L, TimeUnit.SECONDS);

    var conflict = conflictResponse(request);
    when(stub.placeInitialPlayer(request)).thenReturn(conflict);
    assertThat(client.placeInitialPlayer(request)).isEqualTo(conflict);
  }

  @Test
  void rejectsOpenMalformedOrUnsupportedRequestsBeforeEitherRpc() throws Exception {
    var request = request();
    var stub = mockStub();
    var client = clientWithStub(stub);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    var malformed =
        List.of(
            request.toBuilder().setSchemaVersion(2).build(),
            request.toBuilder().setUnknownFields(unknown).build(),
            request.toBuilder().clearExpectedTarget().build(),
            request.toBuilder()
                .setExpectedTarget(
                    request.getExpectedTarget().toBuilder().setUnknownFields(unknown))
                .build(),
            request.toBuilder().setPlacementOperationUuid("not-a-uuid").build(),
            request.toBuilder()
                .setCanonicalAccountUuid("00000000-0000-0000-0000-000000000000")
                .build(),
            request.toBuilder()
                .setExpectedTarget(
                    request.getExpectedTarget().toBuilder()
                        .setGameInstanceId("opaque-instance:preserved"))
                .build(),
            request.toBuilder()
                .setExpectedTarget(
                    request.getExpectedTarget().toBuilder()
                        .setGameInstanceId("00000000-0000-0000-0000-000000000000"))
                .build(),
            request.toBuilder()
                .setExpectedTarget(
                    request.getExpectedTarget().toBuilder()
                        .setGameInstanceId("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"))
                .build(),
            request.toBuilder()
                .setExpectedTarget(
                    request.getExpectedTarget().toBuilder()
                        .setPublishedReleaseBundleRef("r".repeat(1025)))
                .build(),
            request.toBuilder()
                .setExpectedTarget(
                    request.getExpectedTarget().toBuilder()
                        .setPublishedReleaseBundleRef("r".repeat(1024)))
                .build(),
            request.toBuilder()
                .setExpectedTarget(
                    request.getExpectedTarget().toBuilder()
                        .setPublishedReleaseBundleRef("r".repeat(129)))
                .build(),
            request.toBuilder()
                .setExpectedTarget(
                    request.getExpectedTarget().toBuilder()
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
                .build(),
            request.toBuilder()
                .setExpectedTarget(
                    request.getExpectedTarget().toBuilder().setPlayableStateScopeValue(99))
                .build(),
            request.toBuilder()
                .setExpectedTarget(
                    request.getExpectedTarget().toBuilder().setCanonicalVersionUuid("bad"))
                .build(),
            request.toBuilder()
                .setExpectedTarget(request.getExpectedTarget().toBuilder().setCatalogRevision(0L))
                .build(),
            request.toBuilder()
                .setExpectedTarget(
                    request.getExpectedTarget().toBuilder().setFrozenPolicyDigest("A".repeat(64)))
                .build(),
            request.toBuilder().setAssignmentIntentDigest("A".repeat(64)).build(),
            request.toBuilder().setInitialAdmissionRequestId(" ").build(),
            request.toBuilder()
                .setInitialAdmissionRequestDigest("sha256:" + REQUEST_DIGEST)
                .build(),
            request.toBuilder().setTerminalProofDigest(REQUEST_DIGEST).build(),
            request.toBuilder().setActiveLifecycleEpoch(0L).build(),
            request.toBuilder().setCommittedPointerVersion(0L).build(),
            request.toBuilder().setCommittedPointerVersion(2L).build(),
            request.toBuilder().setAuditEventId(0L).build());

    for (var candidate : malformed) {
      assertThatThrownBy(() -> client.placeInitialPlayer(candidate))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> client.readInitialPlayerLocation(candidate))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verify(stub, never()).placeInitialPlayer(any());
    verify(stub, never()).readInitialPlayerLocation(any());
  }

  @Test
  void acceptsCanonicalInstanceUuidAndReleaseReferencesAt128Characters() throws Exception {
    var stub = mockStub();
    var client = clientWithStub(stub);
    var candidate =
        request().toBuilder()
            .setExpectedTarget(
                request().getExpectedTarget().toBuilder()
                    .setPublishedReleaseBundleRef("r".repeat(128)))
            .build();
    when(stub.placeInitialPlayer(candidate)).thenReturn(appliedResponse(candidate));

    assertThat(client.placeInitialPlayer(candidate)).isEqualTo(appliedResponse(candidate));
    verify(stub).placeInitialPlayer(candidate);
  }

  @Test
  void rejectsMismatchedIdentityScopeAssignmentAndMalformedResponses() throws Exception {
    var request = request();
    var stub = mockStub();
    var client = clientWithStub(stub);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    var applied = appliedResponse(request);
    var malformed =
        List.of(
            applied.toBuilder().setSchemaVersion(2).build(),
            applied.toBuilder().setUnknownFields(unknown).build(),
            applied.toBuilder().setPlacementOperationUuid(ASSIGNMENT_UUID).build(),
            applied.toBuilder().setCanonicalAccountUuid(ASSIGNMENT_UUID).build(),
            applied.toBuilder().setCharacterUuid(ASSIGNMENT_UUID).build(),
            applied.toBuilder().setAssignmentUuid(CHARACTER_UUID).build(),
            applied.toBuilder().setAssignmentIntentDigest("f".repeat(64)).build(),
            applied.toBuilder().setRequestDigest("d".repeat(64)).build(),
            applied.toBuilder().setResultDigest("SHA256:" + "e".repeat(64)).build(),
            applied.toBuilder().clearStartLocation().build(),
            applied.toBuilder().setConflictCode("CONFLICT").build(),
            applied.toBuilder()
                .setStartLocation(applied.getStartLocation().toBuilder().setUnknownFields(unknown))
                .build(),
            applied.toBuilder()
                .setStartLocation(
                    applied.getStartLocation().toBuilder()
                        .setCanonicalTenantUuid("88888888-8888-4888-8888-888888888888"))
                .build(),
            applied.toBuilder()
                .setStartLocation(
                    applied.getStartLocation().toBuilder().setCanonicalVersionUuid("bad"))
                .build(),
            applied.toBuilder()
                .setStartLocation(applied.getStartLocation().toBuilder().setRoomTemplateUuid("bad"))
                .build(),
            applied.toBuilder().setRuntimeRoomInstanceId("0").build(),
            applied.toBuilder().setRuntimeRoomInstanceId("01").build(),
            applied.toBuilder().setRuntimeRoomInstanceId("9223372036854775808").build(),
            conflictResponse(request).toBuilder().clearConflictCode().build(),
            conflictResponse(request).toBuilder()
                .setStartLocation(applied.getStartLocation())
                .build(),
            conflictResponse(request).toBuilder().setRuntimeRoomInstanceId("7").build(),
            applied.toBuilder().setOutcomeValue(99).build());

    for (var response : malformed) {
      when(stub.placeInitialPlayer(request)).thenReturn(response);
      assertThatThrownBy(() -> client.placeInitialPlayer(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("World initial player location response is invalid");
    }

    var notFound = new StatusRuntimeException(Status.NOT_FOUND.withDescription("not retained"));
    when(stub.readInitialPlayerLocation(request)).thenThrow(notFound);
    assertThatThrownBy(() -> client.readInitialPlayerLocation(request)).isSameAs(notFound);
    var unavailable = new StatusRuntimeException(Status.UNAVAILABLE.withDescription("offline"));
    when(stub.placeInitialPlayer(request)).thenThrow(unavailable);
    assertThatThrownBy(() -> client.placeInitialPlayer(request)).isSameAs(unavailable);
  }

  @Test
  void refusesAmbientSqlDuringInitializationAndBothRpcsAndRejectsUseAfterClose() throws Exception {
    var request = request();
    var initClient = newClient();
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(initClient::init).isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.clear();
    }
    verify(channels, never()).buildChannel(anyString(), anyInt(), any(), anyBoolean());

    var stub = mockStub();
    when(stub.placeInitialPlayer(request)).thenReturn(appliedResponse(request));
    when(stub.readInitialPlayerLocation(request)).thenReturn(appliedResponse(request));
    var client = clientWithStub(stub);
    for (boolean synchronizationOnly : List.of(true, false)) {
      if (synchronizationOnly) {
        TransactionSynchronizationManager.initSynchronization();
      } else {
        TransactionSynchronizationManager.setActualTransactionActive(true);
      }
      try {
        assertThatThrownBy(() -> client.placeInitialPlayer(request))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.readInitialPlayerLocation(request))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
    verify(stub, never()).placeInitialPlayer(any());
    verify(stub, never()).readInitialPlayerLocation(any());

    client.close();
    assertThatThrownBy(() -> client.placeInitialPlayer(request))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void actualStubPinsWorldPeerAndCarriesCompressionDeadlineAndClientIdentity() throws Exception {
    var request = request();
    var channel = mock(ManagedChannel.class);
    @SuppressWarnings("unchecked")
    ClientCall<CanonicalInitialPlayerLocationRequest, CanonicalInitialPlayerLocationResponse> call =
        mock(ClientCall.class);
    var ssl = mock(javax.net.ssl.SSLSession.class);
    var certificate = mock(X509Certificate.class);
    when(ssl.getPeerCertificates()).thenReturn(new java.security.cert.Certificate[] {certificate});
    when(certificate.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(6, "spiffe://firemud/ns/foreign/sa/world-management-service")));
    when(call.getAttributes())
        .thenReturn(
            Attributes.newBuilder().set(io.grpc.Grpc.TRANSPORT_ATTR_SSL_SESSION, ssl).build());
    doAnswer(
            invocation -> {
              ClientCall.Listener<CanonicalInitialPlayerLocationResponse> listener =
                  invocation.getArgument(0);
              listener.onMessage(appliedResponse(request));
              listener.onClose(Status.OK, new Metadata());
              return null;
            })
        .when(call)
        .start(any(), any());
    when(channel
            .<CanonicalInitialPlayerLocationRequest, CanonicalInitialPlayerLocationResponse>newCall(
                any(), any()))
        .thenReturn(call);
    when(channels.buildChannel(anyString(), anyInt(), any(), anyBoolean())).thenReturn(channel);
    var client = newClient();
    client.init();

    Deadline earliest = Deadline.after(20L, TimeUnit.SECONDS);
    assertThatThrownBy(() -> client.placeInitialPlayer(request))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    Deadline latest = Deadline.after(20L, TimeUnit.SECONDS);

    var optionsCaptor = org.mockito.ArgumentCaptor.forClass(CallOptions.class);
    verify(channel).newCall(any(), optionsCaptor.capture());
    var options = optionsCaptor.getValue();
    assertThat(options.getCompressor()).isEqualTo("gzip");
    assertThat(options.getDeadline()).isNotNull();
    assertThat(options.getDeadline().compareTo(earliest)).isGreaterThanOrEqualTo(0);
    assertThat(options.getDeadline().compareTo(latest)).isLessThanOrEqualTo(0);
    assertThat(options.getCredentials()).isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
    Field peerField = options.getCredentials().getClass().getDeclaredField("expectedPeerUri");
    peerField.setAccessible(true);
    assertThat(peerField.get(options.getCredentials())).isEqualTo(EXPECTED_WORLD_PEER);
    client.close();
    verify(channel).shutdown();
  }

  @Test
  void constructorRejectsPlaintextMissingTlsAndInvalidNamespace() {
    var plaintext = tls.copy();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new CanonicalInitialPlayerLocationClient(
                    new ServiceEndpointsProperties(), plaintext, channels, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);

    var classpath = tls.copy();
    classpath.setCertChain("classpath:client.crt");
    assertThatThrownBy(
            () ->
                new CanonicalInitialPlayerLocationClient(
                    new ServiceEndpointsProperties(), classpath, channels, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);

    var missing = tls.copy();
    missing.setCaCert(directory.resolve("missing-ca.crt").toString());
    assertThatThrownBy(
            () ->
                new CanonicalInitialPlayerLocationClient(
                    new ServiceEndpointsProperties(), missing, channels, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new CanonicalInitialPlayerLocationClient(
                    new ServiceEndpointsProperties(), tls, channels, "bad/namespace"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private CanonicalInitialPlayerLocationClient clientWithStub(
      CanonicalInitialPlayerLocationServiceGrpc.CanonicalInitialPlayerLocationServiceBlockingStub
          stub)
      throws Exception {
    var client = newClient();
    client.init();
    ReflectionTestUtils.setField(client, "stub", stub);
    return client;
  }

  private CanonicalInitialPlayerLocationClient newClient() {
    var client =
        new CanonicalInitialPlayerLocationClient(
            new ServiceEndpointsProperties(), tls, channels, NAMESPACE);
    clients.add(client);
    return client;
  }

  private static CanonicalInitialPlayerLocationServiceGrpc
          .CanonicalInitialPlayerLocationServiceBlockingStub
      mockStub() {
    var stub =
        mock(
            CanonicalInitialPlayerLocationServiceGrpc
                .CanonicalInitialPlayerLocationServiceBlockingStub.class);
    when(stub.withDeadlineAfter(20L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static CanonicalInitialPlayerLocationRequest request() {
    return CanonicalInitialPlayerLocationRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(OPERATION_UUID)
        .setExpectedTarget(
            PreseededActorAssignmentExpectedTarget.newBuilder()
                .setCanonicalTenantUuid("55555555-5555-4555-8555-555555555555")
                .setRealmUuid("66666666-6666-4666-8666-666666666666")
                .setWorldSlug("outer-reach")
                .setRealmSlug("green-march")
                .setGameInstanceId("99999999-9999-4999-8999-999999999999")
                .setCatalogRevision(7L)
                .setCanonicalVersionUuid("77777777-7777-4777-8777-777777777777")
                .setFrozenPolicyDigest("8".repeat(64))
                .setPlayableStateNamespaceUuid("88888888-8888-4888-8888-888888888888")
                .setPublishedReleaseBundleRef("release:bounded/v1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .setCanonicalAccountUuid(ACCOUNT_UUID)
        .setCharacterUuid(CHARACTER_UUID)
        .setAssignmentUuid(ASSIGNMENT_UUID)
        .setAssignmentIntentDigest(INTENT_DIGEST)
        .setInitialAdmissionHoldUuid("99999999-9999-4999-8999-999999999999")
        .setInitialAdmissionHoldFence("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .setInitialAdmissionRequestId("initial-admission-attempt-7")
        .setInitialAdmissionRequestDigest(REQUEST_DIGEST)
        .setActiveLifecycleEpoch(12L)
        .setTerminalProofDigest(TERMINAL_PROOF_DIGEST)
        .setCommittedPointerVersion(1L)
        .setAuditEventId(101L)
        .build();
  }

  private static CanonicalInitialPlayerLocationResponse appliedResponse(
      CanonicalInitialPlayerLocationRequest request) {
    return CanonicalInitialPlayerLocationResponse.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(request.getPlacementOperationUuid())
        .setRequestDigest(WORLD_REQUEST_DIGEST)
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setCharacterUuid(request.getCharacterUuid())
        .setAssignmentUuid(request.getAssignmentUuid())
        .setAssignmentIntentDigest(request.getAssignmentIntentDigest())
        .setResultDigest(WORLD_RESULT_DIGEST)
        .setOutcome(
            CanonicalInitialPlayerLocationOutcome.CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_APPLIED)
        .setStartLocation(
            net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRoom
                .newBuilder()
                .setCanonicalTenantUuid(request.getExpectedTarget().getCanonicalTenantUuid())
                .setCanonicalVersionUuid(request.getExpectedTarget().getCanonicalVersionUuid())
                .setRoomTemplateUuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
        .setRuntimeRoomInstanceId("9182")
        .build();
  }

  private static CanonicalInitialPlayerLocationResponse conflictResponse(
      CanonicalInitialPlayerLocationRequest request) {
    return CanonicalInitialPlayerLocationResponse.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(request.getPlacementOperationUuid())
        .setRequestDigest(WORLD_REQUEST_DIGEST)
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setCharacterUuid(request.getCharacterUuid())
        .setAssignmentUuid(request.getAssignmentUuid())
        .setAssignmentIntentDigest(request.getAssignmentIntentDigest())
        .setResultDigest(WORLD_RESULT_DIGEST)
        .setOutcome(
            CanonicalInitialPlayerLocationOutcome
                .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_CONFLICT)
        .setConflictCode("CHARACTER_ALREADY_PLACED")
        .build();
  }
}
