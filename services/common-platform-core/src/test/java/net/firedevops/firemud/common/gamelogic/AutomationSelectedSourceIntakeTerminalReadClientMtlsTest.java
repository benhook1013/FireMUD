package net.firedevops.firemud.common.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.automationscripting.v1.AutomationSelectedSourceIntakeTerminalReadServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.ReadSelectedSourceIntakeTerminalRequest;
import net.firedevops.firemud.automationscripting.v1.ReadSelectedSourceIntakeTerminalResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadClient;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic missing-receipt transport proof for client mTLS and server workload pinning only. */
class AutomationSelectedSourceIntakeTerminalReadClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String AUTOMATION_URI =
      "spiffe://firemud/ns/test/sa/automation-scripting-service";

  @TempDir static Path tempDirectory;

  private static GameLogicIntakeSourceReadTransportTest.TestPki pki;
  private static GameLogicIntakeSourceReadTransportTest.TestIdentity automationServer;
  private static GameLogicIntakeSourceReadTransportTest.TestIdentity accountClient;

  private Server server;
  private AutomationSelectedSourceIntakeTerminalReadClient client;
  private final AtomicInteger calls = new AtomicInteger();

  @BeforeAll
  static void certificates() throws Exception {
    pki = GameLogicIntakeSourceReadTransportTest.TestPki.create(tempDirectory);
    Path caStore = tempDirectory.resolve("terminal-test-ca.p12");
    Path caFile = tempDirectory.resolve("terminal-test-ca.crt");
    automationServer =
        GameLogicIntakeSourceReadTransportTest.TestPki.issueIdentity(
            tempDirectory, caStore, caFile, "automation-terminal-server", AUTOMATION_URI, true);
    accountClient =
        GameLogicIntakeSourceReadTransportTest.TestPki.issueIdentity(
            tempDirectory, caStore, caFile, "account-terminal-client", ACCOUNT_URI, false);
  }

  @AfterEach
  void closeTransport() throws Exception {
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
  void authenticatesAccountClientAndPinsSameNamespaceAutomationServer() throws Exception {
    startServer(automationServer);
    final int serverPort = server.getPort();
    client = newClient(accountClient);
    client.init();

    assertThatThrownBy(() -> client.read(request()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.NOT_FOUND));
    assertThat(calls).hasValue(1);

    server.shutdownNow();
    assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    server = null;
    startServer(pki.wrongServer(), serverPort);
    assertThatThrownBy(() -> client.read(request()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(calls).hasValue(1);
  }

  private void startServer(GameLogicIntakeSourceReadTransportTest.TestIdentity identity)
      throws Exception {
    startServer(identity, 0);
  }

  private void startServer(GameLogicIntakeSourceReadTransportTest.TestIdentity identity, int port)
      throws Exception {
    var service =
        new AutomationSelectedSourceIntakeTerminalReadServiceGrpc
            .AutomationSelectedSourceIntakeTerminalReadServiceImplBase() {
          @Override
          public void readSelectedSourceIntakeTerminal(
              ReadSelectedSourceIntakeTerminalRequest request,
              StreamObserver<ReadSelectedSourceIntakeTerminalResponse> observer) {
            calls.incrementAndGet();
            assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(ACCOUNT_URI);
            observer.onError(Status.NOT_FOUND.asRuntimeException());
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
            .maxInboundMessageSize(
                net.firedevops.firemud.common.automation.sourceintake
                    .AutomationSelectedSourceIntakeTerminalReadGrpcCodec.MAX_WIRE_BYTES)
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

  private AutomationSelectedSourceIntakeTerminalReadClient newClient(
      GameLogicIntakeSourceReadTransportTest.TestIdentity identity) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAutomationScriptingService("localhost:" + server.getPort());
    return new AutomationSelectedSourceIntakeTerminalReadClient(
        endpoints,
        pki.clientProperties(tempDirectory, identity),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static AutomationSelectedSourceIntakeTerminalReadEvidence.Request request() {
    var binding = mock(SelectedOwnerIntakeAuthorizationBinding.class);
    when(binding.owner())
        .thenReturn(
            net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner.AUTOMATION_SCRIPTING);
    when(binding.targetNamespace()).thenReturn(NAMESPACE);
    when(binding.schema()).thenReturn("account-automation-intake-authorization/v1");
    when(binding.purpose()).thenReturn("AUTOMATION_INTAKE_RETENTION");
    when(binding.canonicalBytes()).thenReturn(new byte[] {1, 2, 3});
    when(binding.digest()).thenReturn("sha256:" + "a".repeat(64));
    when(binding.operationId()).thenReturn(UUID.randomUUID());
    when(binding.fenceId()).thenReturn(UUID.randomUUID());
    when(binding.intakeRequestId()).thenReturn(UUID.randomUUID());
    return AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create(NAMESPACE, binding);
  }
}
