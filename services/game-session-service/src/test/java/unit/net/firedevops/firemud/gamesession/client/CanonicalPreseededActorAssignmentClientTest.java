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
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorRequest;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentOutcome;
import net.firedevops.firemud.gamesession.client.CanonicalPreseededActorAssignmentClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled channel tests; they do not establish physical mTLS or owner custody. */
class CanonicalPreseededActorAssignmentClientTest {
  private static final String NAMESPACE = "game-session-test";
  private static final String EXPECTED_ENTITY_PEER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/entity-management-service";
  private static final String ASSIGNMENT_UUID = "11111111-1111-4111-8111-111111111111";
  private static final String CHARACTER_UUID = "22222222-2222-4222-8222-222222222222";
  private static final String INTENT_DIGEST = "a".repeat(64);

  @TempDir Path directory;
  private final GrpcChannelFactory channels = mock(GrpcChannelFactory.class);
  private final List<CanonicalPreseededActorAssignmentClient> clients = new ArrayList<>();
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
  void returnsOnlyCheckedAssignedAndConflictResponses() throws Exception {
    var request = request();
    var stub = mockStub();
    var assigned = assignedResponse(request);
    when(stub.assignPreseededActor(request)).thenReturn(assigned);
    var client = clientWithStub(stub);

    assertThat(client.assign(request)).isEqualTo(assigned);
    verify(stub).withDeadlineAfter(20L, TimeUnit.SECONDS);

    var conflict =
        AssignPreseededActorResponse.newBuilder()
            .setSchemaVersion(1)
            .setAssignmentUuid(ASSIGNMENT_UUID)
            .setOutcome(
                PreseededActorAssignmentOutcome
                    .PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_IDEMPOTENCY_CONFLICT)
            .setIntentDigest(INTENT_DIGEST)
            .build();
    when(stub.assignPreseededActor(request)).thenReturn(conflict);
    assertThat(client.assign(request)).isEqualTo(conflict);
  }

  @Test
  void rejectsOpenMalformedOrNonCanonicalRequestsBeforeRpc() throws Exception {
    var stub = mockStub();
    var client = clientWithStub(stub);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    var requests =
        List.of(
            request().toBuilder().setSchemaVersion(2).build(),
            request().toBuilder().setUnknownFields(unknown).build(),
            request().toBuilder()
                .setExpectedTarget(
                    request().getExpectedTarget().toBuilder().setUnknownFields(unknown))
                .build(),
            request().toBuilder().setAssignmentUuid("00000000-0000-0000-0000-000000000000").build(),
            request().toBuilder()
                .setExpectedTarget(request().getExpectedTarget().toBuilder().setGameInstanceId(" "))
                .build(),
            request().toBuilder()
                .setExpectedTarget(
                    request().getExpectedTarget().toBuilder().setPlayableStateScopeValue(99))
                .build(),
            request().toBuilder().setMutationIntentDigest("A".repeat(64)).build(),
            request().toBuilder()
                .setCorePayload(request().getCorePayload().toBuilder().setDisplayName(" bad "))
                .build());

    for (var malformed : requests) {
      assertThatThrownBy(() -> client.assign(malformed))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verify(stub, never()).assignPreseededActor(any());
  }

  @Test
  void rejectsMalformedResponsesAndPreservesNativeGrpcFailures() throws Exception {
    var request = request();
    var stub = mockStub();
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    var responses =
        List.of(
            assignedResponse(request).toBuilder().setSchemaVersion(2).build(),
            assignedResponse(request).toBuilder().setUnknownFields(unknown).build(),
            assignedResponse(request).toBuilder().setAssignmentUuid(CHARACTER_UUID).build(),
            assignedResponse(request).toBuilder().setIntentDigest("b".repeat(64)).build(),
            assignedResponse(request).toBuilder().clearCharacterUuid().build(),
            assignedResponse(request).toBuilder().setCharacterUuid("not-a-uuid").build(),
            AssignPreseededActorResponse.newBuilder()
                .setSchemaVersion(1)
                .setAssignmentUuid(ASSIGNMENT_UUID)
                .setOutcome(
                    PreseededActorAssignmentOutcome
                        .PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_IDEMPOTENCY_CONFLICT)
                .setCharacterUuid(CHARACTER_UUID)
                .setIntentDigest(INTENT_DIGEST)
                .build(),
            assignedResponse(request).toBuilder().setOutcomeValue(99).build());
    when(stub.assignPreseededActor(request)).thenReturn(responses.getFirst());
    var client = clientWithStub(stub);

    for (var response : responses) {
      when(stub.assignPreseededActor(request)).thenReturn(response);
      assertThatThrownBy(() -> client.assign(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Entity preseeded actor assignment response is invalid");
    }

    var unavailable = new StatusRuntimeException(Status.UNAVAILABLE.withDescription("offline"));
    when(stub.assignPreseededActor(request)).thenThrow(unavailable);
    assertThatThrownBy(() -> client.assign(request)).isSameAs(unavailable);
    verify(stub, times(responses.size() + 1)).assignPreseededActor(request);
  }

  @Test
  void refusesAmbientActualOrSynchronizationOnlySqlAndRejectsUseAfterClose() throws Exception {
    var request = request();
    var stub = mockStub();
    when(stub.assignPreseededActor(request)).thenReturn(assignedResponse(request));
    var client = clientWithStub(stub);

    for (boolean synchronizationOnly : List.of(true, false)) {
      if (synchronizationOnly) {
        TransactionSynchronizationManager.initSynchronization();
      } else {
        TransactionSynchronizationManager.setActualTransactionActive(true);
      }
      try {
        assertThatThrownBy(() -> client.assign(request)).isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
    verify(stub, never()).assignPreseededActor(any());

    client.close();
    assertThatThrownBy(() -> client.assign(request)).isInstanceOf(IllegalStateException.class);
    verify(stub, never()).assignPreseededActor(any());
  }

  @Test
  void actualStubPinsEntityPeerAndCarriesCompressionDeadlineAndClientIdentity() throws Exception {
    var request = request();
    var channel = mock(ManagedChannel.class);
    @SuppressWarnings("unchecked")
    ClientCall<AssignPreseededActorRequest, AssignPreseededActorResponse> call =
        mock(ClientCall.class);
    var ssl = mock(javax.net.ssl.SSLSession.class);
    var certificate = mock(X509Certificate.class);
    when(ssl.getPeerCertificates()).thenReturn(new java.security.cert.Certificate[] {certificate});
    when(certificate.getSubjectAlternativeNames())
        .thenReturn(
            List.of(List.of(6, "spiffe://firemud/ns/foreign/sa/entity-management-service")));
    when(call.getAttributes())
        .thenReturn(
            Attributes.newBuilder().set(io.grpc.Grpc.TRANSPORT_ATTR_SSL_SESSION, ssl).build());
    doAnswer(
            invocation -> {
              ClientCall.Listener<AssignPreseededActorResponse> listener =
                  invocation.getArgument(0);
              listener.onMessage(assignedResponse(request));
              listener.onClose(Status.OK, new Metadata());
              return null;
            })
        .when(call)
        .start(any(), any());
    when(channel.<AssignPreseededActorRequest, AssignPreseededActorResponse>newCall(any(), any()))
        .thenReturn(call);
    when(channels.buildChannel(anyString(), anyInt(), any(), anyBoolean())).thenReturn(channel);
    var client = newClient();
    client.init();

    Deadline earliest = Deadline.after(20L, TimeUnit.SECONDS);
    assertThatThrownBy(() -> client.assign(request))
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
    assertThat(peerField.get(options.getCredentials())).isEqualTo(EXPECTED_ENTITY_PEER);
    client.close();
    verify(channel).shutdown();
  }

  @Test
  void constructorRejectsPlaintextMissingTlsAndInvalidNamespace() {
    var plaintext = tls.copy();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new CanonicalPreseededActorAssignmentClient(
                    new ServiceEndpointsProperties(), plaintext, channels, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);

    var classpath = tls.copy();
    classpath.setCertChain("classpath:client.crt");
    assertThatThrownBy(
            () ->
                new CanonicalPreseededActorAssignmentClient(
                    new ServiceEndpointsProperties(), classpath, channels, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);

    var missing = tls.copy();
    missing.setCaCert(directory.resolve("missing-ca.crt").toString());
    assertThatThrownBy(
            () ->
                new CanonicalPreseededActorAssignmentClient(
                    new ServiceEndpointsProperties(), missing, channels, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new CanonicalPreseededActorAssignmentClient(
                    new ServiceEndpointsProperties(), tls, channels, "bad/namespace"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private CanonicalPreseededActorAssignmentClient clientWithStub(
      CanonicalPreseededActorAssignmentServiceGrpc
              .CanonicalPreseededActorAssignmentServiceBlockingStub
          stub)
      throws Exception {
    var client = newClient();
    client.init();
    ReflectionTestUtils.setField(client, "stub", stub);
    return client;
  }

  private CanonicalPreseededActorAssignmentClient newClient() {
    var client =
        new CanonicalPreseededActorAssignmentClient(
            new ServiceEndpointsProperties(), tls, channels, NAMESPACE);
    clients.add(client);
    return client;
  }

  private static CanonicalPreseededActorAssignmentServiceGrpc
          .CanonicalPreseededActorAssignmentServiceBlockingStub
      mockStub() {
    var stub =
        mock(
            CanonicalPreseededActorAssignmentServiceGrpc
                .CanonicalPreseededActorAssignmentServiceBlockingStub.class);
    when(stub.withDeadlineAfter(20L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static AssignPreseededActorRequest request() {
    return AssignPreseededActorRequest.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid(ASSIGNMENT_UUID)
        .setCanonicalAccountUuid("33333333-3333-4333-8333-333333333333")
        .setExpectedTarget(
            PreseededActorAssignmentExpectedTarget.newBuilder()
                .setCanonicalTenantUuid("44444444-4444-4444-8444-444444444444")
                .setRealmUuid("55555555-5555-4555-8555-555555555555")
                .setWorldSlug("outer-reach")
                .setRealmSlug("green-march")
                .setGameInstanceId("opaque-instance:preserved")
                .setCatalogRevision(7L)
                .setCanonicalVersionUuid("66666666-6666-4666-8666-666666666666")
                .setFrozenPolicyDigest("b".repeat(64))
                .setPlayableStateNamespaceUuid("77777777-7777-4777-8777-777777777777")
                .setPublishedReleaseBundleRef("release:bounded/v1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .setCorePayload(
            PreseededActorAssignmentCorePayload.newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName("Pilot One"))
        .setMutationIntentDigest(INTENT_DIGEST)
        .build();
  }

  private static AssignPreseededActorResponse assignedResponse(
      AssignPreseededActorRequest request) {
    return AssignPreseededActorResponse.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid(request.getAssignmentUuid())
        .setOutcome(PreseededActorAssignmentOutcome.PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_ASSIGNED)
        .setCharacterUuid(CHARACTER_UUID)
        .setIntentDigest(request.getMutationIntentDigest())
        .build();
  }
}
