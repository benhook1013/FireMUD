package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.gamesession.v1.AccountInitialAdmissionSettlementReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetAccountInitialAdmissionSettlementRequest;
import net.firedevops.firemud.gamesession.v1.GetAccountInitialAdmissionSettlementResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual loopback mTLS with a controlled response owner; no durable GS/Account authority claim. */
class GrpcAccountInitialAdmissionSettlementReadClientTest {
  private static final String NAMESPACE = "world-runtime";
  private static final String ACCOUNT = "spiffe://firemud/ns/world-runtime/sa/account-service";
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;
  private Server server;
  private GrpcAccountInitialAdmissionSettlementReadClient client;
  private final AtomicInteger bodies = new AtomicInteger();
  private final AtomicReference<AccountInitialAdmissionSettlementReadInput> observed =
      new AtomicReference<>();
  private final AtomicReference<
          Function<
              AccountInitialAdmissionSettlementReadInput,
              GetAccountInitialAdmissionSettlementResponse>>
      responder = new AtomicReference<>();
  private final AtomicReference<Status> failure = new AtomicReference<>();

  @BeforeAll
  static void createTestCertificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Controlled admission-settlement test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "gs", "spiffe://firemud/ns/world-runtime/sa/game-session-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-role",
                "spiffe://firemud/ns/world-runtime/sa/world-management-service",
                true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-namespace", "spiffe://firemud/ns/foreign/sa/game-session-service", true),
            new LoopbackMtlsTestPki.IdentitySpec("account", ACCOUNT, false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "entity", "spiffe://firemud/ns/world-runtime/sa/entity-management-service", false));
  }

  @AfterEach
  void closeOwnedTransport() throws Exception {
    TransactionSynchronizationManager.clear();
    if (client != null) client.close();
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void actualMtlsPreservesLargeCompleteProtectionAndTerminalAcrossExactReplay() throws Exception {
    var evidence =
        AccountInitialAdmissionSettlementReadGrpcCodecTest.evidence(Outcome.COMMITTED, true);
    assertThat(evidence.protection().canonicalBytes().length).isGreaterThan(4 * 1024 * 1024);
    responder.set(
        input ->
            AccountInitialAdmissionSettlementReadGrpcCodec.toResponse(
                new AccountInitialAdmissionSettlementReadEvidence(
                    input.readRequestId(), input.protection(), evidence.terminal())));
    start("gs");
    client = newClient("account", NAMESPACE);
    client.init();
    client.init();
    for (int attempt = 0; attempt < 2; attempt++) {
      var received = client.read(evidence.input());
      assertThat(received.readRequestId()).isEqualTo(evidence.readRequestId());
      assertThat(received.protection().canonicalBytes())
          .containsExactly(evidence.protection().canonicalBytes());
      assertThat(
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                  received.terminal()))
          .containsExactly(
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                  evidence.terminal()));
      assertThat(observed.get().protection().canonicalBytes())
          .containsExactly(evidence.protection().canonicalBytes());
    }
    assertThat(bodies.get()).isEqualTo(2);
    client.close();
    assertThatThrownBy(() -> client.read(evidence.input()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(client::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
    assertThat(bodies.get()).isEqualTo(2);
  }

  @Test
  void actualMtlsPreservesDefinitiveAbortAndRejectsInvalidOwnerResponses() throws Exception {
    var evidence =
        AccountInitialAdmissionSettlementTestFixtures.evidence(Outcome.ABORTED);
    var response = AccountInitialAdmissionSettlementReadGrpcCodec.toResponse(evidence);
    responder.set(input -> response);
    start("gs");
    client = newClient("account", NAMESPACE);
    client.init();
    assertThat(client.read(evidence.input()).terminal().positiveDurableAbort()).isTrue();
    var invalid =
        List.of(
            response.toBuilder().setReadRequestId(0, UUID.randomUUID().toString()).build(),
            response.toBuilder()
                .setCanonicalGameSessionOwnerProof(0, ByteString.copyFromUtf8("malformed"))
                .build(),
            response.toBuilder()
                .setUnknownFields(
                    UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                        .build())
                .build());
    for (var value : invalid) {
      responder.set(input -> value);
      assertThatThrownBy(() -> client.read(evidence.input()))
          .isInstanceOf(IllegalStateException.class)
          .hasCauseInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void nativeTransportStatusesRemainUnchangedAndNoAutomaticRetryOccurs() throws Exception {
    var evidence =
        AccountInitialAdmissionSettlementTestFixtures.evidence(Outcome.COMMITTED);
    start("gs");
    client = newClient("account", NAMESPACE);
    client.init();
    for (Status status : List.of(Status.UNAVAILABLE, Status.FAILED_PRECONDITION)) {
      failure.set(status);
      int previous = bodies.get();
      assertThatThrownBy(() -> client.read(evidence.input()))
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode()).isEqualTo(status.getCode()));
      assertThat(bodies.get()).isEqualTo(previous + 1);
    }
  }

  @Test
  void rejectsWrongServerWorkloadBeforeSendingProtection() throws Exception {
    assertWrongServer("wrong-role");
  }

  @Test
  void rejectsForeignNamespaceServerBeforeSendingProtection() throws Exception {
    assertWrongServer("wrong-namespace");
  }

  private void assertWrongServer(String identity) throws Exception {
    start(identity);
    client = newClient("account", NAMESPACE);
    client.init();
    var input =
        AccountInitialAdmissionSettlementTestFixtures.evidence(Outcome.COMMITTED)
            .input();
    assertThatThrownBy(() -> client.read(input))
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(bodies.get()).isZero();
  }

  @Test
  void controlledReceiverRefusesNonAccountCertificateBeforeEvidenceDecode() throws Exception {
    start("gs");
    client = newClient("entity", NAMESPACE);
    client.init();
    var input =
        AccountInitialAdmissionSettlementTestFixtures.evidence(Outcome.COMMITTED)
            .input();
    assertThatThrownBy(() -> client.read(input))
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.PERMISSION_DENIED));
    assertThat(bodies.get()).isZero();
  }

  @Test
  void localNamespaceAndSqlFailBeforeAnyRpc() throws Exception {
    var evidence =
        AccountInitialAdmissionSettlementTestFixtures.evidence(Outcome.COMMITTED);
    start("gs");
    client = newClient("account", "other");
    assertThatThrownBy(() -> client.read(evidence.input()))
        .isInstanceOf(IllegalArgumentException.class);
    client.close();
    client = newClient("account", NAMESPACE);
    client.init();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.read(evidence.input()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient");
    TransactionSynchronizationManager.clear();
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> client.read(evidence.input()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient");
    TransactionSynchronizationManager.clear();
    assertThat(bodies.get()).isZero();
  }

  @Test
  void requiresFileBackedTlsAndExplicitInitializationBeforeChannelUse() throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var tls = LoopbackMtlsTestPki.placeholderTls(directory);
    tls.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GrpcAccountInitialAdmissionSettlementReadClient(
                    new ServiceEndpointsProperties(), tls, factory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    tls.setPlaintext(false);
    tls.setCertChain("classpath:client.crt");
    assertThatThrownBy(
            () ->
                new GrpcAccountInitialAdmissionSettlementReadClient(
                    new ServiceEndpointsProperties(), tls, factory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    tls.setCertChain(directory.resolve("absent.crt").toString());
    assertThatThrownBy(
            () ->
                new GrpcAccountInitialAdmissionSettlementReadClient(
                    new ServiceEndpointsProperties(), tls, factory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    client =
        new GrpcAccountInitialAdmissionSettlementReadClient(
            new ServiceEndpointsProperties(),
            LoopbackMtlsTestPki.placeholderTls(directory),
            factory,
            NAMESPACE);
    var input =
        AccountInitialAdmissionSettlementTestFixtures.evidence(Outcome.COMMITTED)
            .input();
    assertThatThrownBy(() -> client.read(input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    verifyNoInteractions(factory);
  }

  private void start(String serverIdentity) throws Exception {
    var identity = pki.identity(serverIdentity);
    var service =
        new AccountInitialAdmissionSettlementReadServiceGrpc
            .AccountInitialAdmissionSettlementReadServiceImplBase() {
          @Override
          public void getAccountInitialAdmissionSettlement(
              GetAccountInitialAdmissionSettlementRequest wire,
              StreamObserver<GetAccountInitialAdmissionSettlementResponse> observer) {
            if (GrpcPeerIdentity.current() == null
                || !ACCOUNT.equals(GrpcPeerIdentity.current().uri())) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            bodies.incrementAndGet();
            if (failure.get() != null) {
              observer.onError(failure.get().asRuntimeException());
              return;
            }
            var input = AccountInitialAdmissionSettlementReadGrpcCodec.fromRequest(wire, NAMESPACE);
            observed.set(input);
            observer.onNext(responder.get().apply(input));
            observer.onCompleted();
          }
        };
    ServerInterceptor noCredentials =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            assertThat(
                    headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)))
                .isNull();
            return next.startCall(call, headers);
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(
                AccountInitialAdmissionSettlementReadGrpcCodec.MAX_REQUEST_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, noCredentials, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GrpcAccountInitialAdmissionSettlementReadClient newClient(
      String identity, String namespace) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("localhost:" + server.getPort());
    return new GrpcAccountInitialAdmissionSettlementReadClient(
        endpoints,
        pki.clientProperties(directory, pki.identity(identity)),
        new GrpcChannelFactory(),
        namespace);
  }
}
