package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldStateRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldStateResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInitialAdmissionHoldServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Physical mTLS proof for the opt-in Game Session to World hold identity and state client. */
class WorldCanonicalInitialAdmissionHoldClientTest {
  private static final String NAMESPACE = "test";
  private static final String WORLD_URI = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String WRONG_WORLD_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String STORE_PASSWORD = "test-only-store-password";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID GAME_INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final String REQUEST_ID = "gs-initial-admission-17";
  private static final String LAUNCH_CONTROL_PLANE_REQUEST_ID = "launch-control-plane-42";
  private static final String REQUEST_DIGEST = "a".repeat(64);

  @TempDir static Path tempDirectory;

  private static TestPki pki;
  private Server server;
  private WorldCanonicalInitialAdmissionHoldClient client;
  private AtomicInteger applicationCalls;
  private AtomicInteger serverCallStarts;
  private AtomicReference<String> receivedPeerUri;
  private AtomicReference<AcquireCanonicalInitialAdmissionHoldRequest> receivedAcquire;
  private AtomicReference<ReadCanonicalInitialAdmissionHoldIdentityRequest> receivedRead;
  private AtomicReference<ReadCanonicalInitialAdmissionHoldStateRequest> receivedStateRead;

  @BeforeAll
  static void createTrustedWorkloadCertificates() throws Exception {
    pki = TestPki.create(tempDirectory);
  }

  @AfterEach
  void stopTransport() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void acquiresAndReadsOnlyTheExactImmutableIdentityOverPhysicalMtls() throws Exception {
    Request request = request();
    HoldIdentity expected = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    startServer(pki.worldServer(), ResponseMode.EXACT, request);
    client = newClient(server);
    client.init();

    HoldIdentity acquired = client.acquire(request, lifecycleRequest());
    HoldIdentity historical = client.readIdentity(request);

    assertThat(acquired).isEqualTo(expected);
    assertThat(historical).isEqualTo(expected);
    assertThat(lifecycleRequest().controlPlaneRequestId())
        .isNotEqualTo(request.initialAdmissionRequestId());
    assertThat(applicationCalls).hasValue(2);
    assertThat(serverCallStarts).hasValue(2);
    assertThat(receivedPeerUri).hasValue(GAME_SESSION_URI);
    assertThat(
            WorldCanonicalInitialAdmissionHold.Request.fromStored(
                receivedAcquire.get().getCanonicalHoldRequestBytes().toByteArray()))
        .isEqualTo(request);
    assertThat(
            WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(
                receivedAcquire.get().getCanonicalLifecycleReadRequestBytes().toByteArray()))
        .isEqualTo(lifecycleRequest());
    assertThat(Request.fromStored(receivedRead.get().getCanonicalHoldRequestBytes().toByteArray()))
        .isEqualTo(request);
    UUID readRequestId = UUID.fromString(receivedRead.get().getReadRequestId());
    assertThat(readRequestId).isNotEqualTo(new UUID(0L, 0L));
    assertThat(receivedRead.get().getReadRequestId()).isEqualTo(readRequestId.toString());
  }

  @Test
  void rejectsWrongServerWorkloadBeforeRequestAndRejectsEmptyOrUnknownResponses() throws Exception {
    Request request = request();
    startServer(pki.wrongWorldServer(), ResponseMode.EXACT, request);
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.readIdentity(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(serverCallStarts).hasValue(0);
    assertThat(applicationCalls).hasValue(0);
    stopTransport();

    startServer(pki.worldServer(), ResponseMode.EMPTY_IDENTITY, request);
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.acquire(request, lifecycleRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid canonical initial-admission hold identity");
    assertThat(applicationCalls).hasValue(1);
    stopTransport();

    startServer(pki.worldServer(), ResponseMode.WRONG_READ_REQUEST_ID, request);
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.readIdentity(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid historical initial-admission hold identity");
    assertThat(applicationCalls).hasValue(1);
    stopTransport();

    startServer(pki.worldServer(), ResponseMode.OTHER_REQUEST, request);
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.acquire(request, lifecycleRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid canonical initial-admission hold identity");
    assertThat(applicationCalls).hasValue(1);
    stopTransport();

    startServer(pki.worldServer(), ResponseMode.UNKNOWN_RESPONSE_FIELD, request);
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.readIdentity(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid historical initial-admission hold identity");
    assertThat(applicationCalls).hasValue(1);
  }

  @Test
  void requiresExplicitInitializationConfiguredNamespaceMatchingAndNoAmbientTransaction()
      throws Exception {
    Request request = request();
    HoldIdentity identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    client = newClientPropertiesOnly();

    assertThatThrownBy(() -> client.acquire(request, lifecycleRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    assertThatThrownBy(() -> client.readState(identity, lifecycleRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    assertThatThrownBy(() -> client.readIdentity(request("other")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured workload namespace");
    assertThatThrownBy(() -> client.readState(identity, lifecycleRequest("other")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact initial-admission target");
    assertThatThrownBy(() -> client.acquire(request, lifecycleRequest("other")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact initial-admission target");
    assertThatThrownBy(
            () ->
                client.readState(
                    identity,
                    lifecycleRequest(
                        NAMESPACE, true, uuid("99999999-9999-4999-8999-999999999999"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact initial-admission target");
    assertThatThrownBy(() -> lifecycleRequest(NAMESPACE, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires public-production evidence");

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.readIdentity(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient owner transaction");
    assertThatThrownBy(() -> client.readState(identity, lifecycleRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient owner transaction");
  }

  @Test
  void stateReadSendsExactIdentityAndLifecycleReadTupleAndRejectsMalformedOwnerBytes()
      throws Exception {
    Request request = request();
    HoldIdentity identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycle = lifecycleRequest();
    startServer(pki.worldServer(), ResponseMode.MALFORMED_STATE, request);
    client = newClient(server);
    client.init();

    assertThatThrownBy(() -> client.readState(identity, lifecycle))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid canonical initial-admission hold state");

    assertThat(applicationCalls).hasValue(1);
    assertThat(serverCallStarts).hasValue(1);
    assertThat(receivedPeerUri).hasValue(GAME_SESSION_URI);
    assertThat(receivedStateRead.get().getReadRequestId())
        .isEqualTo(lifecycle.readRequestId().toString());
    assertThat(receivedStateRead.get().getHoldIdentityBytes().toByteArray())
        .containsExactly(identity.canonicalBytes());
    assertThat(
            WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(
                receivedStateRead.get().getCanonicalLifecycleReadRequestBytes().toByteArray()))
        .isEqualTo(lifecycle);
  }

  @Test
  void stateReadReturnsEveryTypedOwnerStateOverPhysicalMtlsWithoutInferringAdmission()
      throws Exception {
    WorldCanonicalInstanceLifecycleEvidence lifecycle =
        AuthoringFixtures.lifecycleEvidence("ACTIVE", 7L);
    Request request = stateHoldRequest(lifecycle);
    HoldIdentity identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);

    for (WorldCanonicalInitialAdmissionHoldState.HoldStatus status :
        List.of(
            WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING,
            WorldCanonicalInitialAdmissionHoldState.HoldStatus.RECONCILIATION_REQUIRED,
            WorldCanonicalInitialAdmissionHoldState.HoldStatus.COMMITTED,
            WorldCanonicalInitialAdmissionHoldState.HoldStatus.ABORTED)) {
      WorldCanonicalInitialAdmissionHoldState expected =
          new WorldCanonicalInitialAdmissionHoldState(identity, status, lifecycle);
      startServer(pki.worldServer(), ResponseMode.EXACT, request, expected.canonicalBytes());
      client = newClient(server);
      client.init();

      WorldCanonicalInitialAdmissionHoldState observed =
          client.readState(identity, lifecycle.request());

      assertThat(observed).isEqualTo(expected);
      assertThat(observed.isPendingAtExpectedActiveEpoch())
          .isEqualTo(status == WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING);
      assertThat(applicationCalls).hasValue(1);
      assertThat(serverCallStarts).hasValue(1);
      assertThat(receivedPeerUri).hasValue(GAME_SESSION_URI);
      assertThat(receivedStateRead.get().getReadRequestId())
          .isEqualTo(lifecycle.request().readRequestId().toString());
      assertThat(receivedStateRead.get().getHoldIdentityBytes().toByteArray())
          .containsExactly(identity.canonicalBytes());
      assertThat(receivedStateRead.get().getCanonicalLifecycleReadRequestBytes().toByteArray())
          .containsExactly(lifecycle.request().canonicalBytes());
      stopTransport();
    }
  }

  @Test
  void stateReadRejectsAlteredIdentityLifecycleRequestAndNoncanonicalState() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence lifecycle =
        AuthoringFixtures.lifecycleEvidence("ACTIVE", 7L);
    Request request = stateHoldRequest(lifecycle);
    HoldIdentity identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    var status = WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING;

    var alteredIdentity =
        new HoldIdentity(request, HOLD_ID, uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"));
    var identityMismatch =
        new WorldCanonicalInitialAdmissionHoldState(alteredIdentity, status, lifecycle);
    startServer(pki.worldServer(), ResponseMode.EXACT, request, identityMismatch.canonicalBytes());
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.readState(identity, lifecycle.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid canonical initial-admission hold state");
    stopTransport();

    var alteredLifecycle =
        withReadRequestId(lifecycle, uuid("ffffffff-ffff-4fff-8fff-ffffffffffff"));
    var lifecycleMismatch =
        new WorldCanonicalInitialAdmissionHoldState(identity, status, alteredLifecycle);
    startServer(pki.worldServer(), ResponseMode.EXACT, request, lifecycleMismatch.canonicalBytes());
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.readState(identity, lifecycle.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid canonical initial-admission hold state");
    stopTransport();

    byte[] noncanonical =
        (new String(
                    new WorldCanonicalInitialAdmissionHoldState(identity, status, lifecycle)
                        .canonicalBytes(),
                    StandardCharsets.UTF_8)
                + " ")
            .getBytes(StandardCharsets.UTF_8);
    startServer(pki.worldServer(), ResponseMode.EXACT, request, noncanonical);
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.readState(identity, lifecycle.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid canonical initial-admission hold state");
  }

  @Test
  void stateReadRejectsWrongPeerCorrelationAndUnknownResponseFieldsBeforeAcceptingState()
      throws Exception {
    Request request = request();
    HoldIdentity identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycle = lifecycleRequest();

    startServer(pki.wrongWorldServer(), ResponseMode.EMPTY_STATE, request);
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.readState(identity, lifecycle))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(serverCallStarts).hasValue(0);
    assertThat(applicationCalls).hasValue(0);
    stopTransport();

    startServer(pki.worldServer(), ResponseMode.WRONG_READ_REQUEST_ID, request);
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.readState(identity, lifecycle))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid canonical initial-admission hold state");
    assertThat(applicationCalls).hasValue(1);
    stopTransport();

    startServer(pki.worldServer(), ResponseMode.UNKNOWN_RESPONSE_FIELD, request);
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.readState(identity, lifecycle))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid canonical initial-admission hold state");
    assertThat(applicationCalls).hasValue(1);
  }

  private void startServer(TestIdentity identity, ResponseMode mode, Request expectedRequest)
      throws Exception {
    startServer(identity, mode, expectedRequest, null);
  }

  private void startServer(
      TestIdentity identity, ResponseMode mode, Request expectedRequest, byte[] holdStateBytes)
      throws Exception {
    applicationCalls = new AtomicInteger();
    serverCallStarts = new AtomicInteger();
    receivedPeerUri = new AtomicReference<>();
    receivedAcquire = new AtomicReference<>();
    receivedRead = new AtomicReference<>();
    receivedStateRead = new AtomicReference<>();
    var service =
        new WorldCanonicalInitialAdmissionHoldServiceGrpc
            .WorldCanonicalInitialAdmissionHoldServiceImplBase() {
          @Override
          public void acquireCanonicalInitialAdmissionHold(
              AcquireCanonicalInitialAdmissionHoldRequest request,
              StreamObserver<AcquireCanonicalInitialAdmissionHoldResponse> observer) {
            applicationCalls.incrementAndGet();
            capturePeer();
            receivedAcquire.set(request);
            AcquireCanonicalInitialAdmissionHoldResponse.Builder response =
                AcquireCanonicalInitialAdmissionHoldResponse.newBuilder();
            responseIdentity(expectedRequest, mode)
                .ifPresent(bytes -> response.setHoldIdentityBytes(ByteString.copyFrom(bytes)));
            if (mode == ResponseMode.UNKNOWN_RESPONSE_FIELD) {
              response.setUnknownFields(unknownFields());
            }
            observer.onNext(response.build());
            observer.onCompleted();
          }

          @Override
          public void readCanonicalInitialAdmissionHoldIdentity(
              ReadCanonicalInitialAdmissionHoldIdentityRequest request,
              StreamObserver<ReadCanonicalInitialAdmissionHoldIdentityResponse> observer) {
            applicationCalls.incrementAndGet();
            capturePeer();
            receivedRead.set(request);
            ReadCanonicalInitialAdmissionHoldIdentityResponse.Builder response =
                ReadCanonicalInitialAdmissionHoldIdentityResponse.newBuilder()
                    .setReadRequestId(
                        mode == ResponseMode.WRONG_READ_REQUEST_ID
                            ? uuid("ffffffff-ffff-4fff-8fff-ffffffffffff").toString()
                            : request.getReadRequestId());
            responseIdentity(expectedRequest, mode)
                .ifPresent(bytes -> response.setHoldIdentityBytes(ByteString.copyFrom(bytes)));
            if (mode == ResponseMode.UNKNOWN_RESPONSE_FIELD) {
              response.setUnknownFields(unknownFields());
            }
            observer.onNext(response.build());
            observer.onCompleted();
          }

          @Override
          public void readCanonicalInitialAdmissionHoldState(
              ReadCanonicalInitialAdmissionHoldStateRequest request,
              StreamObserver<ReadCanonicalInitialAdmissionHoldStateResponse> observer) {
            applicationCalls.incrementAndGet();
            capturePeer();
            receivedStateRead.set(request);
            ReadCanonicalInitialAdmissionHoldStateResponse.Builder response =
                ReadCanonicalInitialAdmissionHoldStateResponse.newBuilder()
                    .setReadRequestId(
                        mode == ResponseMode.WRONG_READ_REQUEST_ID
                            ? uuid("ffffffff-ffff-4fff-8fff-ffffffffffff").toString()
                            : request.getReadRequestId());
            if (mode == ResponseMode.MALFORMED_STATE) {
              response.setHoldStateBytes(ByteString.copyFrom(new byte[] {0x01, 0x02, 0x03}));
            } else if (holdStateBytes != null) {
              response.setHoldStateBytes(ByteString.copyFrom(holdStateBytes));
            }
            if (mode == ResponseMode.UNKNOWN_RESPONSE_FIELD) {
              response.setUnknownFields(unknownFields());
            }
            observer.onNext(response.build());
            observer.onCompleted();
          }

          private void capturePeer() {
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            receivedPeerUri.set(peer == null ? null : peer.uri());
          }
        };
    ServerInterceptor countApplicationHeaders =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            serverCallStarts.incrementAndGet();
            return next.startCall(call, headers);
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, countApplicationHeaders, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private WorldCanonicalInitialAdmissionHoldClient newClient(Server target) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("localhost:" + target.getPort());
    return new WorldCanonicalInitialAdmissionHoldClient(
        endpoints, pki.clientProperties(tempDirectory), new GrpcChannelFactory(), NAMESPACE);
  }

  private WorldCanonicalInitialAdmissionHoldClient newClientPropertiesOnly() throws Exception {
    return new WorldCanonicalInitialAdmissionHoldClient(
        new ServiceEndpointsProperties(),
        pki.clientProperties(tempDirectory),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static java.util.Optional<byte[]> responseIdentity(Request request, ResponseMode mode) {
    if (mode == ResponseMode.EMPTY_IDENTITY) {
      return java.util.Optional.empty();
    }
    Request returned =
        mode == ResponseMode.OTHER_REQUEST ? request("prod", "other-initial-admission") : request;
    return java.util.Optional.of(new HoldIdentity(returned, HOLD_ID, HOLD_FENCE).canonicalBytes());
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static Request request() {
    return request(NAMESPACE, REQUEST_ID);
  }

  private static Request request(String namespace) {
    return request(namespace, REQUEST_ID);
  }

  private static Request request(String namespace, String requestId) {
    return new Request(
        namespace,
        TENANT,
        "green-hollow",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        GAME_INSTANCE,
        VERSION,
        7L,
        requestId,
        REQUEST_DIGEST,
        InitialAdmissionOrigin.NO_PRIOR_POINTER,
        12L,
        null);
  }

  private static Request stateHoldRequest(WorldCanonicalInstanceLifecycleEvidence lifecycle) {
    var request = lifecycle.request();
    return new Request(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        REALM,
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.canonicalGameInstanceId(),
        request.canonicalVersionId(),
        lifecycle.lifecycleEpoch(),
        REQUEST_ID,
        REQUEST_DIGEST,
        InitialAdmissionOrigin.NO_PRIOR_POINTER,
        12L,
        null);
  }

  private static WorldCanonicalInstanceLifecycleEvidence withReadRequestId(
      WorldCanonicalInstanceLifecycleEvidence lifecycle, UUID readRequestId) {
    var source = lifecycle.request();
    var alteredRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            source.schemaVersion(),
            readRequestId,
            source.targetNamespace(),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.canonicalGameInstanceId(),
            source.playableStateNamespaceId(),
            source.playableStateScope(),
            source.publicProduction(),
            source.controlPlaneRequestId(),
            source.canonicalVersionId(),
            source.expectedDescriptorRequestDigest(),
            source.expectedDescriptorResultDigest(),
            source.expectedReleaseAttestationDigest());
    return new WorldCanonicalInstanceLifecycleEvidence(
        alteredRequest,
        lifecycle.launchBinding(),
        lifecycle.startLocation(),
        lifecycle.runtimeRoomInstanceId(),
        lifecycle.lifecycleStatus(),
        lifecycle.lifecycleEpoch(),
        lifecycle.rowVersion(),
        lifecycle.captureId(),
        lifecycle.graphSha256(),
        lifecycle.preparationInputDigest(),
        lifecycle.operationalRegionAssignments());
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest() {
    return lifecycleRequest(NAMESPACE, true);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest(
      String namespace) {
    return lifecycleRequest(namespace, true);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest(
      String namespace, boolean publicProduction) {
    return lifecycleRequest(namespace, publicProduction, GAME_INSTANCE);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest(
      String namespace, boolean publicProduction, UUID gameInstanceId) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
        uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
        namespace,
        TENANT,
        "green-hollow",
        gameInstanceId,
        PLAYABLE_NAMESPACE,
        "SHARED",
        publicProduction,
        LAUNCH_CONTROL_PLANE_REQUEST_ID,
        VERSION,
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        "sha256:" + "c".repeat(64));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private enum ResponseMode {
    EXACT,
    EMPTY_STATE,
    MALFORMED_STATE,
    EMPTY_IDENTITY,
    UNKNOWN_RESPONSE_FIELD,
    WRONG_READ_REQUEST_ID,
    OTHER_REQUEST
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity worldServer,
      TestIdentity wrongWorldServer,
      TestIdentity gameSessionClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD initial-admission test CA",
          "-validity",
          "30",
          "-ext",
          "BC=ca:true",
          "-ext",
          "KU=keyCertSign,cRLSign",
          "-storetype",
          "PKCS12",
          "-keystore",
          caStore.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      Path caFile = directory.resolve("test-ca.crt");
      runKeytool(
          "-exportcert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
          "-rfc");
      X509Certificate caCertificate = readCertificate(caFile);
      return new TestPki(
          caCertificate,
          issueIdentity(directory, caStore, caFile, "world-server", WORLD_URI, true),
          issueIdentity(directory, caStore, caFile, "wrong-world-server", WRONG_WORLD_URI, true),
          issueIdentity(
              directory, caStore, caFile, "game-session-client", GAME_SESSION_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory) throws Exception {
      Path clientCertificate =
          writePem(
              directory.resolve("game-session-client.crt"),
              "CERTIFICATE",
              gameSessionClient.certificate().getEncoded());
      Path clientPrivateKey =
          writePem(
              directory.resolve("game-session-client.key"),
              "PRIVATE KEY",
              gameSessionClient.privateKey().getEncoded());
      Path caFile =
          writePem(
              directory.resolve("client-trust-ca.crt"), "CERTIFICATE", caCertificate.getEncoded());
      CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
      properties.setCertChain(clientCertificate.toString());
      properties.setPrivateKey(clientPrivateKey.toString());
      properties.setCaCert(caFile.toString());
      return properties;
    }

    private static TestIdentity issueIdentity(
        Path directory, Path caStore, Path caFile, String alias, String workloadUri, boolean server)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      String san = "URI:" + workloadUri + ",DNS:localhost,IP:127.0.0.1";
      runKeytool(
          "-genkeypair",
          "-alias",
          alias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=" + alias,
          "-validity",
          "30",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + (server ? "serverAuth" : "clientAuth"),
          "-ext",
          "SAN=" + san,
          "-storetype",
          "PKCS12",
          "-keystore",
          store.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      runKeytool(
          "-certreq",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          request.toString(),
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-gencert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-infile",
          request.toString(),
          "-outfile",
          certificate.toString(),
          "-validity",
          "30",
          "-rfc",
          "-ext",
          "BC=ca:false",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + (server ? "serverAuth" : "clientAuth"),
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-importcert",
          "-alias",
          "test-ca",
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
          "-noprompt");
      runKeytool(
          "-importcert",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          certificate.toString(),
          "-noprompt");
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(store)) {
        keyStore.load(input, STORE_PASSWORD.toCharArray());
      }
      PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray());
      X509Certificate x509Certificate = (X509Certificate) keyStore.getCertificate(alias);
      writePem(directory.resolve(alias + "-leaf.crt"), "CERTIFICATE", x509Certificate.getEncoded());
      writePem(directory.resolve(alias + "-leaf.key"), "PRIVATE KEY", privateKey.getEncoded());
      return new TestIdentity(privateKey, x509Certificate);
    }

    private static void runKeytool(String... arguments) throws Exception {
      Path keytool =
          Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").toLowerCase().contains("windows")
                  ? "keytool.exe"
                  : "keytool");
      var command = new java.util.ArrayList<String>();
      command.add(keytool.toString());
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output;
      try (var stream = process.getInputStream()) {
        output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        throw new IllegalStateException("keytool failed: " + output);
      }
    }

    private static X509Certificate readCertificate(Path path) throws Exception {
      try (var input = Files.newInputStream(path)) {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      }
    }

    private static Path writePem(Path path, String label, byte[] bytes) throws IOException {
      String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
      return Files.writeString(
          path,
          "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
          StandardCharsets.US_ASCII);
    }
  }
}
