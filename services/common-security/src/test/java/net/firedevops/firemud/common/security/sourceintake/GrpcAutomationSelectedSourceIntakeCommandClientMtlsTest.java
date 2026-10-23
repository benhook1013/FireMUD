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
import net.firedevops.firemud.automationscripting.v1.AutomationSelectedSourceIntakeCommandServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.RetainSelectedSourceRequest;
import net.firedevops.firemud.automationscripting.v1.RetainSelectedSourceResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerCredentials;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderCredentials;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeCommandEvidence;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeCommandGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeAuthorizationCredentials;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.security.sourceintake.SelectedSourceIntakeCommandTestPki.Identity;
import net.firedevops.firemud.common.security.sourceintake.SelectedSourceIntakeCommandTestPki.Pki;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Positive command mTLS proof; synthetic fixture is not an Automation producer or physical census
 * proof.
 */
class GrpcAutomationSelectedSourceIntakeCommandClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String AUTOMATION_URI =
      "spiffe://firemud/ns/test/sa/automation-scripting-service";
  private static final String WRONG_AUTOMATION_URI = "spiffe://firemud/ns/test/sa/automation-other";
  private static final String GAME_DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";

  @TempDir static Path tempDirectory;
  private static Fixture fixture;
  private static Pki pki;
  private Server server;
  private GrpcAutomationSelectedSourceIntakeCommandClient client;
  private AtomicInteger headers;
  private AtomicInteger bodies;
  private AtomicReference<Deadline> deadline;
  private static AutomationEmptySelectedSourceIntakeReceipt receipt;

  @BeforeAll
  static void createSyntheticInputsAndTrustedCertificates() throws Exception {
    fixture = EntitySelectedSourceIntakeTerminalReadFixtures.create();
    var authorization = fixture.wrongOwnerAuthorization();
    var freeze = fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence();
    var worldEvidence =
        new SelectedOwnerWorldInventoryReadEvidence(
            SelectedOwnerWorldInventoryReadEvidence.Request.create(
                NAMESPACE, authorization, freeze),
            fixture.inputs().worldInventoryReadEvidence().inventory());
    var inputs = new SelectedOwnerEmptySourceInputs(authorization, worldEvidence);
    receipt =
        AutomationEmptySelectedSourceIntakeReceipt.create(
            inputs,
            9001L,
            9002L,
            AutomationEmptySelectedSourceIntakeReceipt.requestDigest(
                NAMESPACE, authorization, freeze),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            java.time.OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    pki =
        SelectedSourceIntakeCommandTestPki.create(
            tempDirectory,
            "FireMUD Automation command test CA",
            AUTOMATION_URI,
            WRONG_AUTOMATION_URI,
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
  void loopbackMtlsReturnsExactReceiptAndPinsAutomationServerWithBoundedDeadline()
      throws Exception {
    var freeze = fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence();
    var request =
        AutomationSelectedSourceIntakeCommandEvidence.Request.create(
            NAMESPACE, fixture.wrongOwnerAuthorization(), freeze);
    byte[] receiptBytes = receipt.canonicalBytes();

    startServer(pki.server());
    client = newClient(server, pki.gameDesignClient());
    client.init();
    var actual = client.retain(request);
    assertThat(actual.request()).isEqualTo(request);
    assertThat(actual.receipt().canonicalBytes()).isEqualTo(receiptBytes);
    assertThat(actual.receipt().authorizationBindingBytes())
        .isEqualTo(fixture.wrongOwnerAuthorization().canonicalBytes());
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
  void malformedReceiptResponseFailsClosedAfterAuthenticatedTransport() throws Exception {
    var freeze = fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence();
    var request =
        AutomationSelectedSourceIntakeCommandEvidence.Request.create(
            NAMESPACE, fixture.wrongOwnerAuthorization(), freeze);
    startServer(pki.server(), true);
    client = newClient(server, pki.gameDesignClient());
    client.init();

    assertThatThrownBy(() -> client.retain(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid selected-source receipt evidence");
    assertThat(headers).hasValue(1);
    assertThat(bodies).hasValue(1);
  }

  @Test
  void namespaceSqlSynchronizationEndUserAndSelectedOrderAreDeniedBeforeTransport(@TempDir Path dir)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var request =
        AutomationSelectedSourceIntakeCommandEvidence.Request.create(
            NAMESPACE,
            fixture.wrongOwnerAuthorization(),
            fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence());
    var wrongNamespaceClient =
        new GrpcAutomationSelectedSourceIntakeCommandClient(
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
        new GrpcAutomationSelectedSourceIntakeCommandClient(
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
    startServer(identity, false);
  }

  private void startServer(Identity identity, boolean malformedResponse) throws Exception {
    headers = new AtomicInteger();
    bodies = new AtomicInteger();
    deadline = new AtomicReference<>();
    var service =
        new AutomationSelectedSourceIntakeCommandServiceGrpc
            .AutomationSelectedSourceIntakeCommandServiceImplBase() {
          @Override
          public void retainSelectedSource(
              RetainSelectedSourceRequest wire,
              StreamObserver<RetainSelectedSourceResponse> observer) {
            bodies.incrementAndGet();
            assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(GAME_DESIGN_URI);
            deadline.set(Context.current().getDeadline());
            var request = AutomationSelectedSourceIntakeCommandGrpcCodec.fromRequest(wire);
            assertThat(request.originalAuthorizationBinding().canonicalBytes())
                .isEqualTo(fixture.wrongOwnerAuthorization().canonicalBytes());
            assertThat(request.freezeEvidence())
                .isEqualTo(
                    fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence());
            var response =
                AutomationSelectedSourceIntakeCommandGrpcCodec.toResponse(
                    new AutomationSelectedSourceIntakeCommandEvidence(request, receipt));
            if (malformedResponse) {
              response = response.toBuilder().setReceiptDigest("0".repeat(64)).build();
            }
            observer.onNext(response);
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
                AutomationSelectedSourceIntakeCommandGrpcCodec.MAX_REQUEST_WIRE_BYTES)
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

  private GrpcAutomationSelectedSourceIntakeCommandClient newClient(Server target, Identity local)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAutomationScriptingService("localhost:" + target.getPort());
    return new GrpcAutomationSelectedSourceIntakeCommandClient(
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
