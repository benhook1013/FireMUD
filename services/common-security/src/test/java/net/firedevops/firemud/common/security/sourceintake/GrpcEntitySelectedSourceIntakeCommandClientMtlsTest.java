package net.firedevops.firemud.common.security.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Deadline;
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
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerCredentials;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderCredentials;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandEvidence;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandGrpcCodec;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeAuthorizationCredentials;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.security.sourceintake.SelectedSourceIntakeCommandTestPki.Identity;
import net.firedevops.firemud.common.security.sourceintake.SelectedSourceIntakeCommandTestPki.Pki;
import net.firedevops.firemud.entitymanagement.v1.EntitySelectedSourceIntakeCommandServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.RetainSelectedEntitySourceRequest;
import net.firedevops.firemud.entitymanagement.v1.RetainSelectedEntitySourceResponse;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Positive command mTLS proof; synthetic fixture is not Entity producer or physical census proof.
 */
class GrpcEntitySelectedSourceIntakeCommandClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ENTITY_URI = "spiffe://firemud/ns/test/sa/entity-management-service";
  private static final String WRONG_ENTITY_URI = "spiffe://firemud/ns/test/sa/entity-other";
  private static final String GAME_DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";

  @TempDir static Path tempDirectory;
  private static Fixture fixture;
  private static Pki pki;
  private Server server;
  private GrpcEntitySelectedSourceIntakeCommandClient client;
  private AtomicInteger headers;
  private AtomicInteger bodies;
  private AtomicReference<Deadline> deadline;

  @BeforeAll
  static void createSyntheticInputsAndTrustedCertificates() throws Exception {
    fixture = EntitySelectedSourceIntakeTerminalReadFixtures.create();
    pki =
        SelectedSourceIntakeCommandTestPki.create(
            tempDirectory,
            "FireMUD Entity command test CA",
            ENTITY_URI,
            WRONG_ENTITY_URI,
            GAME_DESIGN_URI);
  }

  @AfterEach
  void stopTransportAndClearAmbientContext() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
    TransactionSynchronizationManager.clear();
    SessionContext.clear();
  }

  @Test
  void loopbackMtlsReturnsExactReceiptAndPinsEntityServerWithBoundedDeadline() throws Exception {
    var freeze = fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence();
    var request =
        EntitySelectedSourceIntakeCommandEvidence.Request.create(
            NAMESPACE, fixture.authorization(), freeze);
    byte[] receiptBytes = fixture.receipt().canonicalBytes();

    startServer(pki.server());
    client = newClient(server, pki.gameDesignClient());
    client.init();
    var actual = client.retain(request);
    assertThat(actual.request()).isEqualTo(request);
    assertThat(actual.receipt().canonicalBytes()).isEqualTo(receiptBytes);
    assertThat(actual.receipt().authorizationBindingBytes())
        .isEqualTo(fixture.authorization().canonicalBytes());
    assertThat(headers).hasValue(1);
    assertThat(bodies).hasValue(1);
    assertThat(deadline.get()).isNotNull();
    assertThat(deadline.get().timeRemaining(TimeUnit.SECONDS)).isBetween(1L, 5L);

    stopCurrentTransport();
    startServer(pki.wrongServer());
    client = newClient(server, pki.gameDesignClient());
    client.init();
    assertThatThrownBy(() -> client.retain(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(headers).hasValue(0);
    assertThat(bodies).hasValue(0);
  }

  @Test
  void namespaceSqlSynchronizationEndUserAndSelectedOrderAreDeniedBeforeTransport(@TempDir Path dir)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var request =
        EntitySelectedSourceIntakeCommandEvidence.Request.create(
            NAMESPACE,
            fixture.authorization(),
            fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence());
    var wrongNamespaceClient =
        new GrpcEntitySelectedSourceIntakeCommandClient(
            new ServiceEndpointsProperties(),
            SelectedSourceIntakeCommandTestPki.placeholderTls(dir),
            factory,
            "other");
    try {
      assertThatThrownBy(() -> wrongNamespaceClient.retain(request))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      verifyNoInteractions(factory);
    } finally {
      wrongNamespaceClient.close();
    }

    var client =
        new GrpcEntitySelectedSourceIntakeCommandClient(
            new ServiceEndpointsProperties(),
            SelectedSourceIntakeCommandTestPki.placeholderTls(dir),
            factory,
            NAMESPACE);
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.retain(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient SQL");
      TransactionSynchronizationManager.setActualTransactionActive(false);

      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.retain(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient SQL");
      TransactionSynchronizationManager.clearSynchronization();

      SessionContext.setContext("101", List.of(), Map.of());
      assertThatThrownBy(() -> client.retain(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("workload-only context");
      SessionContext.clear();

      for (var producerContext :
          List.of(
              Context.current()
                  .withValue(
                      AccountOriginalDraftOrderCredentials.CONTEXT_KEY,
                      AccountOriginalDraftOrderCredentials.Credential.of(
                          "controlled-original-author")),
              Context.current()
                  .withValue(
                      AccountGameLogicIntakeAuthorizationCredentials.CONTEXT_KEY,
                      AccountGameLogicIntakeAuthorizationCredentials.Credential.of(
                          "controlled-game-logic-author")),
              Context.current()
                  .withValue(
                      SelectedOwnerIntakeAuthorizationProducerCredentials.CONTEXT_KEY,
                      SelectedOwnerIntakeAuthorizationProducerCredentials.Credential.of(
                          "controlled-selected-intake-author")))) {
        var previous = producerContext.attach();
        try {
          assertThatThrownBy(() -> client.retain(null))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("workload-only context");
        } finally {
          producerContext.detach(previous);
        }
      }
      verifyNoInteractions(factory);
    } finally {
      client.close();
      TransactionSynchronizationManager.clear();
      SessionContext.clear();
    }
  }

  private void startServer(Identity identity) throws Exception {
    headers = new AtomicInteger();
    bodies = new AtomicInteger();
    deadline = new AtomicReference<>();
    var service =
        new EntitySelectedSourceIntakeCommandServiceGrpc
            .EntitySelectedSourceIntakeCommandServiceImplBase() {
          @Override
          public void retainSelectedEntitySource(
              RetainSelectedEntitySourceRequest wire,
              StreamObserver<RetainSelectedEntitySourceResponse> observer) {
            bodies.incrementAndGet();
            assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(GAME_DESIGN_URI);
            deadline.set(Context.current().getDeadline());
            var request = EntitySelectedSourceIntakeCommandGrpcCodec.fromRequest(wire);
            assertThat(request.originalAuthorizationBinding().canonicalBytes())
                .isEqualTo(fixture.authorization().canonicalBytes());
            assertThat(request.freezeEvidence())
                .isEqualTo(
                    fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence());
            observer.onNext(
                EntitySelectedSourceIntakeCommandGrpcCodec.toResponse(
                    new EntitySelectedSourceIntakeCommandEvidence(request, fixture.receipt())));
            observer.onCompleted();
          }
        };
    ServerInterceptor countHeaders =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            headers.incrementAndGet();
            return next.startCall(call, metadata);
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(
                EntitySelectedSourceIntakeCommandGrpcCodec.MAX_REQUEST_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, countHeaders, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GrpcEntitySelectedSourceIntakeCommandClient newClient(Server target, Identity local)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService("localhost:" + target.getPort());
    return new GrpcEntitySelectedSourceIntakeCommandClient(
        endpoints, pki.clientProperties(tempDirectory, local), new GrpcChannelFactory(), NAMESPACE);
  }

  private void stopCurrentTransport() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }
}
