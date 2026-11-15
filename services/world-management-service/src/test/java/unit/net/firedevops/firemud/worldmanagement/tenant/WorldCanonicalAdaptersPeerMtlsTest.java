package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.ManagedChannel;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.worldmanagement.v1.ActivateCanonicalWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareCanonicalWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInstanceActivationServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInstancePreparationServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Socket peer boundary only; mocked owners do not qualify storage or canonical activation. */
@Timeout(90)
class WorldCanonicalAdaptersPeerMtlsTest {
  @TempDir Path directory;

  @Test
  void wrongWorkloadIsDeniedBeforeParsingWhileGameSessionReachesRequestValidation()
      throws Exception {
    var binding = mock(WorldCompleteLaunchBindingService.class);
    var assembly = mock(WorldCanonicalInstancePreparationAssemblyService.class);
    var preparation = mock(WorldCanonicalInstancePreparationService.class);
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var lifecycle = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var activation = mock(WorldCanonicalInstanceActivationService.class);
    var prepareAdapter =
        new WorldCanonicalInstancePreparationGrpcService(
            binding, assembly, preparation, repository, lifecycle, "firemud");
    var activateAdapter = new WorldCanonicalInstanceActivationGrpcService(activation, "firemud");
    var pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Canonical adapter peer proof",
            new LoopbackMtlsTestPki.IdentitySpec(
                "world", "spiffe://firemud/ns/firemud/sa/world-management-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "gs", "spiffe://firemud/ns/firemud/sa/game-session-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong", "spiffe://firemud/ns/firemud/sa/game-design-service", false));
    var serverIdentity = pki.identity("world");
    var server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverIdentity.privateKey(), serverIdentity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(prepareAdapter, new GrpcPeerIdentityInterceptor()))
            .addService(
                ServerInterceptors.intercept(activateAdapter, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    try {
      for (String peer : List.of("wrong", "gs")) {
        var identity = pki.identity(peer);
        ManagedChannel channel =
            NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
                .sslContext(
                    GrpcSslContexts.forClient()
                        .trustManager(pki.caCertificate())
                        .keyManager(identity.privateKey(), identity.certificate())
                        .build())
                .build();
        try {
          Status.Code expected =
              peer.equals("wrong") ? Status.Code.PERMISSION_DENIED : Status.Code.INVALID_ARGUMENT;
          assertThatThrownBy(
                  () ->
                      WorldCanonicalInstancePreparationServiceGrpc.newBlockingStub(channel)
                          .withDeadlineAfter(5, TimeUnit.SECONDS)
                          .prepareCanonicalWorldInstance(
                              PrepareCanonicalWorldInstanceRequest.getDefaultInstance()))
              .isInstanceOfSatisfying(
                  StatusRuntimeException.class,
                  failure -> assertThat(failure.getStatus().getCode()).isEqualTo(expected));
          assertThatThrownBy(
                  () ->
                      WorldCanonicalInstanceActivationServiceGrpc.newBlockingStub(channel)
                          .withDeadlineAfter(5, TimeUnit.SECONDS)
                          .activateCanonicalWorldInstance(
                              ActivateCanonicalWorldInstanceRequest.getDefaultInstance()))
              .isInstanceOfSatisfying(
                  StatusRuntimeException.class,
                  failure -> assertThat(failure.getStatus().getCode()).isEqualTo(expected));
          verifyNoInteractions(binding, assembly, preparation, repository, lifecycle, activation);
        } finally {
          channel.shutdownNow();
          assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
      }
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
