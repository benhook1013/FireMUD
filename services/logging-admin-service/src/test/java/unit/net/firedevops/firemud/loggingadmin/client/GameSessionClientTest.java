package net.firedevops.firemud.loggingadmin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.CallCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamesession.v1.AuthorizeStartSessionRequest;
import net.firedevops.firemud.gamesession.v1.AuthorizeStartSessionResponse;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.StartSessionOwnerAuthorizationProgress;
import net.firedevops.firemud.loggingadmin.client.StartSessionOperatorAuthorizationClient.AuthorizationReference;
import net.firedevops.firemud.loggingadmin.operator.StartSessionAuthorizationCoordinator.TransientOwnerExecutionHandoff;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.OwnerExecutionHandoff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class GameSessionClientTest {
  @TempDir private Path tlsDirectory;

  private static final long DEADLINE_SECONDS = 5L;
  private static final String OPAQUE_REFERENCE = "A".repeat(43);
  private static final String LOGGING_IDENTITY =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final UUID TENANT_ID = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR_ID = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID OWNER_ID = UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER =
      UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID OWNER_ATTEMPT = UUID.fromString("69116466-a576-4fa6-9e11-4c0c6b2a92f0");
  private static final UUID OWNER_MUTATION =
      UUID.fromString("89da7d84-12f5-4cf8-b69b-30ba8eb115a4");

  @Test
  void buildStubAppliesInjectedStubCustomizer() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub =
        mock(GameSessionServiceGrpc.GameSessionServiceBlockingStub.class);
    AtomicReference<GameSessionServiceGrpc.GameSessionServiceBlockingStub> customized =
        new AtomicReference<>();
    BlockingGrpcStubCustomizer stubCustomizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T candidate) {
            customized.set((GameSessionServiceGrpc.GameSessionServiceBlockingStub) candidate);
            return candidate;
          }
        };
    TestGameSessionClient client =
        new TestGameSessionClient(
            endpoints, grpc, mock(GrpcChannelFactory.class), stubCustomizer, stub);

    client.buildStub(mock(ManagedChannel.class));

    assertThat(customized.get()).isSameAs(stub);
  }

  @Test
  void sendsOneExactTypedHandoffAndValidatesSecretFreeOwnerEcho() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    var handoff = handoff();
    StartSessionPostAuthorizationExecutionTuple tuple = handoff.postAuthorizationTuple();
    AuthorizeStartSessionResponse response =
        ownerResponse(
            tuple,
            StartSessionOwnerAuthorizationProgress
                .START_SESSION_OWNER_AUTHORIZATION_PROGRESS_ACCOUNT_OUTCOME_AMBIGUOUS);
    when(stub.authorizeStartSession(any())).thenReturn(response);
    GameSessionClient client = client(stub);
    ArgumentCaptor<AuthorizeStartSessionRequest> requestCaptor =
        ArgumentCaptor.forClass(AuthorizeStartSessionRequest.class);
    ArgumentCaptor<ClientInterceptor[]> interceptorCaptor =
        ArgumentCaptor.forClass(ClientInterceptor[].class);

    GameSessionClient.StartSessionOwnerHandoffResult result = client.authorizeStartSession(handoff);

    verify(stub, times(1))
        .withDeadlineAfter(DEADLINE_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    verify(stub, times(1)).authorizeStartSession(requestCaptor.capture());
    verify(stub, never()).stopSession(any());
    assertThat(
            requestCaptor
                .getValue()
                .getCanonicalPostAuthorizationExecutionTupleBytes()
                .toByteArray())
        .containsExactly(tuple.canonicalBytes());
    assertThat(requestCaptor.getValue().getOperatorAuthorizationReference())
        .isEqualTo(OPAQUE_REFERENCE);
    verify(stub, times(1)).withCallCredentials(any(GrpcServerPeerIdentityCallCredentials.class));
    verify(stub, times(1)).withInterceptors(interceptorCaptor.capture());
    assertThat(interceptorCaptor.getValue()).hasSize(1);
    assertThat(interceptorCaptor.getValue()[0])
        .isInstanceOf(GrpcServerPeerIdentityClientInterceptor.class);
    assertThat(result.controlPlaneRequestId()).isEqualTo(tuple.controlPlaneRequestId());
    assertThat(result.mutationDigest()).isEqualTo(tuple.mutationDigest());
    assertThat(result.ownerAttemptId()).isEqualTo(OWNER_ATTEMPT);
    assertThat(result.ownerMutationId()).isEqualTo(OWNER_MUTATION);
    assertThat(result.ownerFence()).isEqualTo(37L);
    assertThat(result.progress())
        .isEqualTo(
            StartSessionOwnerAuthorizationProgress
                .START_SESSION_OWNER_AUTHORIZATION_PROGRESS_ACCOUNT_OUTCOME_AMBIGUOUS);
    assertThat(result.toString()).doesNotContain(OPAQUE_REFERENCE);
  }

  @Test
  void refusesOwnerResponseThatDoesNotEchoTheOriginalTuple() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    var handoff = handoff();
    AuthorizeStartSessionResponse substitutedResponse =
        ownerResponse(
                handoff.postAuthorizationTuple(),
                StartSessionOwnerAuthorizationProgress
                    .START_SESSION_OWNER_AUTHORIZATION_PROGRESS_EXACT_REPLAY)
            .toBuilder()
            .setMutationDigest("different-digest")
            .build();
    when(stub.authorizeStartSession(any())).thenReturn(substitutedResponse);

    assertThatThrownBy(() -> client(stub).authorizeStartSession(handoff))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match")
        .hasMessageNotContaining(OPAQUE_REFERENCE);
    verify(stub, times(1)).authorizeStartSession(any());
  }

  @Test
  void refusesSensitiveHandoffWithoutReadableFileBackedMtlsBeforeRpc() throws Exception {
    TransientOwnerExecutionHandoff handoff = handoff();
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();

    CommonGrpcClientProperties plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(() -> client(stub, plaintext).authorizeStartSession(handoff))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires workload mTLS");

    CommonGrpcClientProperties missingFiles = new CommonGrpcClientProperties();
    assertThatThrownBy(() -> client(stub, missingFiles).authorizeStartSession(handoff))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("file-backed workload mTLS");

    CommonGrpcClientProperties directoryInsteadOfCertificate = fileBackedTlsProperties();
    directoryInsteadOfCertificate.setCertChain(tlsDirectory.toString());
    assertThatThrownBy(
            () -> client(stub, directoryInsteadOfCertificate).authorizeStartSession(handoff))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("file-backed workload mTLS");

    verify(stub, never()).authorizeStartSession(any());
  }

  @Test
  void rejectsSubstitutedAccountReferenceBeforeCallingGameSession() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    TransientOwnerExecutionHandoff handoff = handoff();
    when(handoff.accountResponse().authenticatedLoggingWorkloadIdentity())
        .thenReturn("spiffe://firemud/ns/other/sa/logging-admin-service");

    assertThatThrownBy(() -> client(stub).authorizeStartSession(handoff))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not internally bound")
        .hasMessageNotContaining(OPAQUE_REFERENCE);
    verify(stub, never()).authorizeStartSession(any());
  }

  @Test
  void surfacesTransportAmbiguityWithoutAutomaticRetryOrReferenceDisclosure() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    TransientOwnerExecutionHandoff handoff = handoff();
    when(stub.authorizeStartSession(any()))
        .thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());

    assertThatThrownBy(() -> client(stub).authorizeStartSession(handoff))
        .isInstanceOf(GameSessionClient.AmbiguousOwnerHandoffException.class)
        .hasMessageNotContaining(OPAQUE_REFERENCE);
    verify(stub, times(1)).authorizeStartSession(any());
  }

  private GameSessionClient client(GameSessionServiceGrpc.GameSessionServiceBlockingStub stub) {
    try {
      return client(stub, fileBackedTlsProperties());
    } catch (IOException failure) {
      throw new AssertionError("test TLS fixture creation failed", failure);
    }
  }

  private GameSessionClient client(
      GameSessionServiceGrpc.GameSessionServiceBlockingStub stub, CommonGrpcClientProperties grpc) {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    TestGameSessionClient client =
        new TestGameSessionClient(
            endpoints,
            grpc,
            mock(GrpcChannelFactory.class),
            new BlockingGrpcStubCustomizer() {
              @Override
              public <T extends io.grpc.stub.AbstractStub<T>> T customize(T candidate) {
                return candidate;
              }
            },
            stub);
    try {
      client.installStubForTest();
    } catch (javax.net.ssl.SSLException failure) {
      throw new AssertionError("test stub installation failed", failure);
    }
    return client;
  }

  private CommonGrpcClientProperties fileBackedTlsProperties() throws IOException {
    Path certChain = tlsDirectory.resolve("client.crt");
    Path privateKey = tlsDirectory.resolve("client.key");
    Path caCert = tlsDirectory.resolve("ca.crt");
    Files.writeString(certChain, "test-only certificate placeholder");
    Files.writeString(privateKey, "test-only private-key placeholder");
    Files.writeString(caCert, "test-only CA placeholder");
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setCertChain(certChain.toString());
    grpc.setPrivateKey(privateKey.toString());
    grpc.setCaCert(caCert.toString());
    return grpc;
  }

  private static GameSessionServiceGrpc.GameSessionServiceBlockingStub mockStub() {
    var stub = mock(GameSessionServiceGrpc.GameSessionServiceBlockingStub.class);
    when(stub.withCallCredentials(any(CallCredentials.class))).thenReturn(stub);
    when(stub.withInterceptors(any(ClientInterceptor[].class))).thenReturn(stub);
    when(stub.withDeadlineAfter(DEADLINE_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
        .thenReturn(stub);
    return stub;
  }

  private static AuthorizeStartSessionResponse ownerResponse(
      StartSessionPostAuthorizationExecutionTuple tuple,
      StartSessionOwnerAuthorizationProgress progress) {
    return AuthorizeStartSessionResponse.newBuilder()
        .setTargetNamespace("world-runtime")
        .setControlPlaneRequestId(tuple.controlPlaneRequestId())
        .setMutationDigest(tuple.mutationDigest())
        .setOwnerAttemptId(OWNER_ATTEMPT.toString())
        .setOwnerMutationId(OWNER_MUTATION.toString())
        .setOwnerFence(37L)
        .setOwnerPhaseState("OWNER_EXECUTION_PENDING")
        .setProgress(progress)
        .build();
  }

  private static TransientOwnerExecutionHandoff handoff() throws Exception {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
            new StartSessionOperatorAction.Target(91L, OWNER_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "Logging Game Session client transport test");
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "logging-game-session-client-test", ACTOR_ID, action);
    StartSessionPostAuthorizationExecutionTuple tuple = mockTuple(preTuple);
    AuthorizationReference accountResponse = mock(AuthorizationReference.class);
    String tupleFingerprint = tuple.authorizationReferenceFingerprint();
    byte[] tupleAuthorityEvidenceBundle = tuple.authorityEvidenceBundleBytes();
    StartSessionAuthorityEvidenceBundle.BundleReference tupleBundleReference =
        tuple.bundleReference();
    when(accountResponse.operatorAuthorizationReference()).thenReturn(OPAQUE_REFERENCE);
    when(accountResponse.authenticatedLoggingWorkloadIdentity()).thenReturn(LOGGING_IDENTITY);
    when(accountResponse.authorizationReferenceFingerprint()).thenReturn(tupleFingerprint);
    when(accountResponse.authorityEvidenceBundle()).thenReturn(tupleAuthorityEvidenceBundle);
    when(accountResponse.bundleReference()).thenReturn(tupleBundleReference);
    return new TransientOwnerExecutionHandoff(
        new OwnerExecutionHandoff(UUID.fromString("5be93a95-79f3-4c08-9857-dc0848b2185d")),
        accountResponse,
        tuple);
  }

  private static StartSessionPostAuthorizationExecutionTuple mockTuple(
      StartSessionPreAuthorizationReservationTuple preTuple) {
    StartSessionAuthorityEvidenceBundle.BundleReference bundleReference =
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "23", "18446744073709551615");
    var tuple = mock(StartSessionPostAuthorizationExecutionTuple.class);
    when(tuple.canonicalBytes()).thenReturn(new byte[] {1, 2, 3});
    when(tuple.preAuthorizationTuple()).thenReturn(preTuple);
    when(tuple.controlPlaneRequestId()).thenReturn(preTuple.controlPlaneRequestId());
    when(tuple.mutationDigest()).thenReturn(preTuple.mutationDigest());
    when(tuple.authenticatedWorkloadIdentity()).thenReturn(LOGGING_IDENTITY);
    when(tuple.authorizationReferenceFingerprint())
        .thenReturn("arfp/v1/test-key/" + "b".repeat(64));
    when(tuple.authorityEvidenceBundleBytes()).thenReturn(new byte[] {4, 5, 6});
    when(tuple.bundleReference()).thenReturn(bundleReference);
    return tuple;
  }

  private static final class TestGameSessionClient extends GameSessionClient {
    private final GameSessionServiceGrpc.GameSessionServiceBlockingStub stub;

    private TestGameSessionClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tlsProps,
        GrpcChannelFactory channelFactory,
        BlockingGrpcStubCustomizer stubCustomizer,
        GameSessionServiceGrpc.GameSessionServiceBlockingStub stub) {
      super(endpoints, tlsProps, channelFactory, stubCustomizer);
      this.stub = stub;
    }

    private void installStubForTest() throws javax.net.ssl.SSLException {
      reloadChannel();
    }

    @Override
    protected GameSessionServiceGrpc.GameSessionServiceBlockingStub buildStub(
        ManagedChannel channel) {
      return applyStubCustomizer(stub);
    }
  }
}
