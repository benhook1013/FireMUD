package net.firedevops.firemud.gamesession.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.MetadataUtils;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicSourceServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceResponse;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceResponse;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Socket/certificate adapter proof only; the source composition is a controlled double. */
class GameSessionCharacterCreatePublicSourceMtlsTest {
  @TempDir Path temporary;

  @Test
  void onlyExactAccountCertificateCanReadAndBearerCannotReplacePeerIdentity() throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            temporary,
            "Creation public source proof CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "server", "spiffe://firemud/ns/firemud/sa/game-session-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "account", "spiffe://firemud/ns/firemud/sa/account-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "entity", "spiffe://firemud/ns/firemud/sa/entity-management-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign", "spiffe://firemud/ns/other/sa/account-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign-entity", "spiffe://firemud/ns/other/sa/entity-management-service", false));
    var source = mock(GameSessionCharacterCreatePublicSourceService.class);
    var request = GameSessionCharacterCreatePublicSourceServiceTest.request();
    var result =
        ReadCharacterCreatePublicSourceResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequest(request)
            .build();
    when(source.read(request)).thenReturn(result);
    var targetRequest = GameSessionCharacterCreatePublicSourceServiceTest.resolveRequest();
    var targetResult =
        ResolveCharacterCreatePublicTargetResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequest(targetRequest)
            .build();
    when(source.resolve(targetRequest)).thenReturn(targetResult);
    var rosterRequest = GameSessionCharacterCreatePublicSourceServiceTest.rosterRequest();
    var rosterResult =
        ReadPlayerCreatedRosterSourceResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequest(rosterRequest)
            .setPublishedPolicySetEvidence(
                com.google.protobuf.ByteString.copyFromUtf8("controlled-original-sealed-set"))
            .build();
    when(source.readPlayerCreatedRosterSource(rosterRequest)).thenReturn(rosterResult);
    var serverIdentity = pki.identity("server");
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder.forServer(
                            serverIdentity.privateKey(), serverIdentity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .maxInboundMessageSize(GameSessionCharacterCreatePublicSourceService.MAX_REQUEST_BYTES)
            .addService(
                ServerInterceptors.intercept(
                    new GameSessionCharacterCreatePublicSourceGrpcService(source, "firemud"),
                    new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    try {
      for (String caller : new String[] {"account", "entity", "foreign", "foreign-entity"}) {
        ManagedChannel channel = channel(pki, caller, server.getPort());
        try {
          String expected = "spiffe://firemud/ns/firemud/sa/game-session-service";
          var stub =
              CharacterCreatePublicSourceServiceGrpc.newBlockingStub(channel)
                  .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expected))
                  .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expected))
                  .withDeadlineAfter(5, TimeUnit.SECONDS);
          if (caller.equals("account")) {
            assertThat(stub.readCharacterCreatePublicSource(request)).isEqualTo(result);
            assertThat(stub.resolveCharacterCreatePublicTarget(targetRequest))
                .isEqualTo(targetResult);
          } else {
            var headers = new Metadata();
            headers.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer controlled-not-authority");
            var bearerStub =
                stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
            assertThatThrownBy(() -> bearerStub.readCharacterCreatePublicSource(request))
                .isInstanceOfSatisfying(
                    StatusRuntimeException.class,
                    failure ->
                        assertThat(failure.getStatus().getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED));
            assertThatThrownBy(() -> bearerStub.resolveCharacterCreatePublicTarget(targetRequest))
                .isInstanceOfSatisfying(
                    StatusRuntimeException.class,
                    failure ->
                        assertThat(failure.getStatus().getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED));
          }
          if (caller.equals("entity")) {
            assertThat(stub.readPlayerCreatedRosterSource(rosterRequest)).isEqualTo(rosterResult);
          } else {
            var headers = new Metadata();
            headers.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer controlled-entity-assertion-not-authority");
            var bearerStub =
                stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
            assertThatThrownBy(() -> bearerStub.readPlayerCreatedRosterSource(rosterRequest))
                .isInstanceOfSatisfying(
                    StatusRuntimeException.class,
                    failure ->
                        assertThat(failure.getStatus().getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED));
          }
        } finally {
          channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
      }
      verify(source, times(1)).read(request);
      verify(source, times(1)).resolve(targetRequest);
      verify(source, times(1)).readPlayerCreatedRosterSource(rosterRequest);
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static ManagedChannel channel(LoopbackMtlsTestPki.Pki pki, String name, int port)
      throws Exception {
    var identity = pki.identity(name);
    return NettyChannelBuilder.forAddress("127.0.0.1", port)
        .sslContext(
            GrpcSslContexts.configure(
                    io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder.forClient())
                .trustManager(pki.caCertificate())
                .keyManager(identity.privateKey(), identity.certificate())
                .build())
        .maxInboundMessageSize(GameSessionCharacterCreatePublicSourceService.MAX_RESPONSE_BYTES)
        .build();
  }
}
