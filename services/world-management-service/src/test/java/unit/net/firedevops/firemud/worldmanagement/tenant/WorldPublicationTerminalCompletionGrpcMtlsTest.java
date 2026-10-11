package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalCompletionClient;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalCompletionGrpcCodec;
import net.firedevops.firemud.test.TlsTestSupport;
import net.firedevops.firemud.worldmanagement.tenant.WorldGameDesignPublicationTerminalVerifierTest;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalCompletionGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalService;
import net.firedevops.firemud.worldmanagement.v1.WorldPublicationTerminalCompletionServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real loopback mTLS transport proof; World storage is mocked except for default-deny checks. */
class WorldPublicationTerminalCompletionGrpcMtlsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void sameNamespaceGameDesignCertificateReturnsExactStoredTerminalOnRetry() throws Exception {
    var operation = WorldGameDesignPublicationTerminalVerifierTest.operation();
    var terminal =
        WorldGameDesignPublicationTerminalVerifierTest.terminal(operation, Outcome.PUBLISHED);
    var request =
        WorldPublicationTerminalCompletionGrpcCodec.Request.create(
            "test", operation.canonicalBytes(), terminal.canonicalBytes());
    var owner = mock(WorldPublicationTerminalService.class);
    when(owner.complete(any(), any())).thenReturn(terminal);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var handlerCalls = new AtomicInteger();
    var capturedResponse = new AtomicReference<Object>();

    try (var server =
            WorldOriginalStartSessionActivationTransportTestSupport.startServer(
                pki, service, handlerCalls, false, capturedResponse::set);
        var client = worldClient(pki, server.port())) {
      client.init();
      var first = client.complete(request);
      var retry = client.complete(request);

      assertThat(first.canonicalBytes()).containsExactly(terminal.canonicalBytes());
      assertThat(retry.canonicalBytes()).containsExactly(terminal.canonicalBytes());
      assertThat(handlerCalls).hasValue(2);
      assertThat(capturedResponse.get())
          .isEqualTo(WorldPublicationTerminalCompletionGrpcCodec.toResponse(request, terminal));

      var operationCaptor = org.mockito.ArgumentCaptor.forClass(byte[].class);
      var terminalCaptor = org.mockito.ArgumentCaptor.forClass(byte[].class);
      verify(owner, times(2)).complete(operationCaptor.capture(), terminalCaptor.capture());
      assertThat(operationCaptor.getAllValues())
          .allSatisfy(bytes -> assertThat(bytes).containsExactly(operation.canonicalBytes()));
      assertThat(terminalCaptor.getAllValues())
          .allSatisfy(bytes -> assertThat(bytes).containsExactly(terminal.canonicalBytes()));
    }
  }

  @Test
  void wrongWorkloadAndNamespaceCertificatesAreDeniedBeforeOwner() throws Exception {
    var input = request();
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server =
        WorldOriginalStartSessionActivationTransportTestSupport.startServer(
            pki, service, handlerCalls, false, null)) {
      for (var identity :
          java.util.List.of(pki.accountClient(), pki.otherNamespaceGameDesignClient())) {
        try (var channel = managedChannel(pki, server.port(), identity)) {
          assertThatThrownBy(
                  () ->
                      WorldPublicationTerminalCompletionServiceGrpc.newBlockingStub(
                              channel.channel())
                          .withDeadlineAfter(5, TimeUnit.SECONDS)
                          .completeVersionPublication(input.wire()))
              .isInstanceOf(StatusRuntimeException.class)
              .satisfies(
                  failure ->
                      assertThat(Status.fromThrowable(failure).getCode())
                          .isEqualTo(Status.Code.PERMISSION_DENIED));
        }
      }
    }

    assertThat(handlerCalls).hasValue(2);
    verifyNoInteractions(owner);
  }

  @Test
  void requestNamespaceMustMatchTheAuthenticatedWorldOwner() throws Exception {
    var input = request();
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "other");
    var handlerCalls = new AtomicInteger();

    try (var server =
            WorldOriginalStartSessionActivationTransportTestSupport.startServer(
                pki, pki.otherNamespaceWorldServer(), service, handlerCalls, false, null);
        var channel = managedChannel(pki, server.port(), pki.otherNamespaceGameDesignClient())) {
      assertThatThrownBy(
              () ->
                  WorldPublicationTerminalCompletionServiceGrpc.newBlockingStub(channel.channel())
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .completeVersionPublication(input.wire()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    assertThat(handlerCalls).hasValue(1);
    verifyNoInteractions(owner);
  }

  @Test
  void clientPinsExactWorldPeerBeforeSendingRequest() throws Exception {
    var input = request();
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server =
            WorldOriginalStartSessionActivationTransportTestSupport.startServer(
                pki, pki.otherNamespaceWorldServer(), service, handlerCalls, false, null);
        var client = worldClient(pki, server.port())) {
      client.init();
      assertThatThrownBy(() -> client.complete(input.request()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    assertThat(handlerCalls).hasValue(0);
    verifyNoInteractions(owner);
  }

  @Test
  void authenticatedEndUserContextIsDeniedBeforeOwner() throws Exception {
    var input = request();
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server =
            WorldOriginalStartSessionActivationTransportTestSupport.startServer(
                pki, service, handlerCalls, true, null);
        var channel = managedChannel(pki, server.port(), pki.gameDesignClient())) {
      assertThatThrownBy(
              () ->
                  WorldPublicationTerminalCompletionServiceGrpc.newBlockingStub(channel.channel())
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .completeVersionPublication(input.wire()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    assertThat(handlerCalls).hasValue(1);
    verifyNoInteractions(owner);
  }

  @Test
  void defaultMissingVerifierFailsClosedWithoutOwnerStorage() throws Exception {
    var input = request();
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var repository = mock(WorldPublicationTerminalRepository.class);
    var owner = new WorldPublicationTerminalService(repository);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server =
            WorldOriginalStartSessionActivationTransportTestSupport.startServer(
                pki, service, handlerCalls, false, null);
        var channel = managedChannel(pki, server.port(), pki.gameDesignClient())) {
      assertThatThrownBy(
              () ->
                  WorldPublicationTerminalCompletionServiceGrpc.newBlockingStub(channel.channel())
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .completeVersionPublication(input.wire()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
    }

    assertThat(handlerCalls).hasValue(1);
    verifyNoInteractions(repository);
  }

  @Test
  void missingClientCertificateIsRejectedDuringTlsBeforeHandlerOrOwner() throws Exception {
    var input = request();
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server =
            WorldOriginalStartSessionActivationTransportTestSupport.startServer(
                pki, service, handlerCalls, false, null);
        var unauthenticatedClient =
            WorldOriginalStartSessionActivationTransportTestSupport.clientWithoutIdentity(
                pki, server.port())) {
      assertThatThrownBy(
              () ->
                  WorldPublicationTerminalCompletionServiceGrpc.newBlockingStub(
                          unauthenticatedClient.channel())
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .completeVersionPublication(input.wire()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure -> assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isTrue());
    }

    assertThat(handlerCalls).hasValue(0);
    verifyNoInteractions(owner);
  }

  private static RequestFixture request() throws Exception {
    GameDesignPublicationOperationBinding operation =
        WorldGameDesignPublicationTerminalVerifierTest.operation();
    GameDesignPublicationTerminalEvidence terminal =
        WorldGameDesignPublicationTerminalVerifierTest.terminal(operation, Outcome.PUBLISHED);
    var request =
        WorldPublicationTerminalCompletionGrpcCodec.Request.create(
            "test", operation.canonicalBytes(), terminal.canonicalBytes());
    return new RequestFixture(
        request, WorldPublicationTerminalCompletionGrpcCodec.toRequest(request));
  }

  private static ManagedChannel channel(
      WorldOriginalStartSessionActivationTransportTestSupport.TestPki pki,
      int port,
      WorldOriginalStartSessionActivationTransportTestSupport.TestIdentity identity)
      throws Exception {
    return NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
        .sslContext(
            GrpcSslContexts.forClient()
                .trustManager(pki.caCertificate())
                .keyManager(identity.privateKey(), identity.certificate())
                .build())
        .build();
  }

  private static ManagedChannelResource managedChannel(
      WorldOriginalStartSessionActivationTransportTestSupport.TestPki pki,
      int port,
      WorldOriginalStartSessionActivationTransportTestSupport.TestIdentity identity)
      throws Exception {
    return new ManagedChannelResource(channel(pki, port, identity));
  }

  private WorldPublicationTerminalCompletionClient worldClient(
      WorldOriginalStartSessionActivationTransportTestSupport.TestPki pki, int port)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("127.0.0.1:" + port);
    return new WorldPublicationTerminalCompletionClient(
        endpoints,
        pki.clientProperties(temporaryDirectory, pki.gameDesignClient()),
        new GrpcChannelFactory(),
        "test");
  }

  private record RequestFixture(
      WorldPublicationTerminalCompletionGrpcCodec.Request request,
      net.firedevops.firemud.worldmanagement.v1.CompleteVersionPublicationRequest wire) {}

  private record ManagedChannelResource(ManagedChannel channel) implements AutoCloseable {
    @Override
    public void close() throws InterruptedException {
      channel.shutdownNow();
      if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback gRPC client did not terminate");
      }
    }
  }
}
