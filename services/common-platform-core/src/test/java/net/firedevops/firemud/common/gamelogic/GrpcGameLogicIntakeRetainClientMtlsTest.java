package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamelogic.v1.GameLogicGameplayRuleIntakeServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.RetainGameplayRuleIntakeRequest;
import net.firedevops.firemud.gamelogic.v1.RetainGameplayRuleIntakeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical mTLS and message-size proof for the standalone GL intake retain client. */
class GrpcGameLogicIntakeRetainClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String GL_URI = "spiffe://firemud/ns/test/sa/game-logic-service";
  private static final String WRONG_GL_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GD_URI = "spiffe://firemud/ns/test/sa/game-design-service";

  @TempDir static Path tempDirectory;
  private static IntakeTestPki pki;

  private Server server;
  private GrpcGameLogicIntakeRetainClient client;
  private AtomicInteger calls;
  private AtomicReference<String> clientPeer;

  @BeforeAll
  static void createTrustedWorkloadCertificates() throws Exception {
    pki =
        IntakeTestPki.create(
            tempDirectory,
            "intake-retain",
            "FireMUD intake-retain test CA",
            IntakeTestPki.IdentitySpec.server("game-logic-server", GL_URI),
            IntakeTestPki.IdentitySpec.server("wrong-server", WRONG_GL_URI),
            IntakeTestPki.IdentitySpec.client("game-design-client", GD_URI));
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
  }

  @Test
  void acceptsLargeTerminalFromExactGameLogicServerAndRejectsSubstitutedServer() throws Exception {
    var authorization = GameLogicIntakeRetainProtoCodecTest.largeAuthorization(1800);
    var request = GameLogicIntakeRetainEvidence.Request.create(NAMESPACE, authorization);
    var source = authorization.source();
    var terminal =
        GameLogicGameplayRuleIntakeTerminal.retained(
            new GameLogicGameplayRuleIntakeOperation(NAMESPACE, authorization),
            source.canonicalBytes(),
            source.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8));
    byte[] expectedTerminal = terminal.canonicalBytes();
    assertThat(expectedTerminal.length).isGreaterThan(4 * 1024 * 1024);

    startServer(pki.server(), request, terminal);
    client = newClient(server.getPort());
    client.init();
    var actual = client.retain(request);
    assertThat(actual.terminal().canonicalBytes()).containsExactly(expectedTerminal);
    assertThat(calls).hasValue(1);
    assertThat(clientPeer).hasValue(GD_URI);

    stopTransport();
    startServer(pki.wrongServer(), request, terminal);
    client = newClient(server.getPort());
    client.init();
    assertThatThrownBy(() -> client.retain(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(calls).hasValue(0);
    assertThat(clientPeer.get()).isNull();
  }

  private void startServer(
      IntakeTestPki.Identity identity,
      GameLogicIntakeRetainEvidence.Request expectedRequest,
      GameLogicGameplayRuleIntakeTerminal terminal)
      throws Exception {
    calls = new AtomicInteger();
    clientPeer = new AtomicReference<>();
    var service =
        new GameLogicGameplayRuleIntakeServiceGrpc.GameLogicGameplayRuleIntakeServiceImplBase() {
          @Override
          public void retainGameplayRuleIntake(
              RetainGameplayRuleIntakeRequest wire,
              StreamObserver<RetainGameplayRuleIntakeResponse> observer) {
            calls.incrementAndGet();
            var peer = GrpcPeerIdentity.current();
            clientPeer.set(peer == null ? null : peer.uri());
            var decoded = GameLogicIntakeRetainProtoCodec.fromRequest(wire);
            assertThat(decoded).isEqualTo(expectedRequest);
            observer.onNext(
                GameLogicIntakeRetainProtoCodec.toResponse(
                    new GameLogicIntakeRetainEvidence(decoded, terminal)));
            observer.onCompleted();
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(GameLogicIntakeRetainProtoCodec.MAX_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GrpcGameLogicIntakeRetainClient newClient(int port) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameLogicService("localhost:" + port);
    return new GrpcGameLogicIntakeRetainClient(
        endpoints,
        pki.clientProperties(tempDirectory, pki.client()),
        new GrpcChannelFactory(),
        NAMESPACE);
  }
}
