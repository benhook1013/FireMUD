package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.service.CompleteLaunchBindingService;
import net.firedevops.firemud.gamedesign.service.GameAuthoredHelpTopicService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.Pki;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Loopback mTLS proof for the real Game Design adapter and scoped Common client. The fixture mounts
 * the adapter on a test server and supplies digest-valid evidence from a mocked descriptor owner.
 * Persistence and application registration are outside the fixture's scope.
 */
class AuthoredWorldLaunchDescriptorMtlsTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT_ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("32345678-1234-4234-8234-123456789abc");

  @Test
  void resolvesForGameSessionAndReadsForGameSessionAndWorldOverMtls(@TempDir Path directory)
      throws Exception {
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "cp-launch",
            TENANT_ID,
            "silver-march",
            UUID.fromString("22345678-1234-4234-8234-123456789abc"),
            "sha256:" + "a".repeat(64),
            9L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var evidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "ld-1",
            7L,
            false,
            null,
            "{}",
            "genrev-1",
            11L,
            11L,
            "release-bundle:" + TENANT_ID + ":7:11",
            false,
            null);
    var read =
        new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
            READ_REQUEST_ID, request, evidence.resultDigest());
    var descriptor =
        new ResolvedLaunchDescriptorDto(
            evidence.launchDescriptorId(),
            TENANT_ID.toString(),
            evidence.gameTemplateId(),
            evidence.controlPlaneRequestId(),
            evidence.versionId(),
            evidence.scriptPatchVersion(),
            evidence.runtimeFlagsJson(),
            evidence.generationConfigRevision(),
            evidence.versionStateEpoch(),
            evidence.releaseBundleId(),
            evidence.publishedReleaseBundleRef(),
            evidence.remapSetId(),
            evidence);
    var owner = mock(LaunchDescriptorService.class);
    when(owner.resolveLaunchDescriptor(request)).thenReturn(descriptor);
    when(owner.getLaunchDescriptor(
            READ_REQUEST_ID,
            TENANT_ID,
            request.worldSlug(),
            request.controlPlaneRequestId(),
            evidence.requestDigest(),
            evidence.resultDigest()))
        .thenReturn(descriptor);

    var pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Launch descriptor test CA",
            new IdentitySpec("server", "spiffe://firemud/ns/test/sa/game-design-service", true),
            new IdentitySpec("session", "spiffe://firemud/ns/test/sa/game-session-service", false),
            new IdentitySpec(
                "world", "spiffe://firemud/ns/test/sa/world-management-service", false),
            new IdentitySpec("other", "spiffe://firemud/ns/other/sa/game-session-service", false));
    var meters = new SimpleMeterRegistry();
    try {
      Server server = startReceiver(pki, owner, meters);
      try {
        var factory = new GrpcChannelFactory();
        var wrongNamespace =
            factory.buildChannel(
                "localhost",
                server.getPort(),
                pki.clientProperties(directory, pki.identity("other")),
                false);
        try {
          var stub =
              GameDesignServiceGrpc.newBlockingStub(wrongNamespace)
                  .withDeadlineAfter(5, TimeUnit.SECONDS);
          var deniedResolve =
              stub.resolveLaunchDescriptor(
                  AuthoredWorldLaunchDescriptorGrpcCodec.toResolveRequest(request));
          var deniedRead =
              stub.getLaunchDescriptor(AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(read));
          assertThat(deniedResolve.getError().getCode()).isEqualTo("PERMISSION_DENIED");
          assertThat(deniedRead.getError().getCode()).isEqualTo("PERMISSION_DENIED");
          verifyNoInteractions(owner);
        } finally {
          wrongNamespace.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        var endpoints = new ServiceEndpointsProperties();
        endpoints.setGameDesignService("localhost:" + server.getPort());
        try (var sessionClient =
                new AuthoredWorldLaunchDescriptorClient(
                    endpoints,
                    pki.clientProperties(directory, pki.identity("session")),
                    factory,
                    NAMESPACE);
            var worldClient =
                new AuthoredWorldLaunchDescriptorClient(
                    endpoints,
                    pki.clientProperties(directory, pki.identity("world")),
                    factory,
                    NAMESPACE)) {
          sessionClient.init();
          worldClient.init();

          assertThatThrownBy(() -> worldClient.resolve(request))
              .isInstanceOf(IllegalStateException.class)
              .hasRootCauseMessage("Game Design rejected launch descriptor resolution");
          verifyNoInteractions(owner);

          assertThat(sessionClient.resolve(request)).isEqualTo(evidence);
          assertThat(sessionClient.get(read)).isEqualTo(evidence);
          assertThat(worldClient.get(read)).isEqualTo(evidence);
        }
      } finally {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    } finally {
      meters.close();
    }

    verify(owner).resolveLaunchDescriptor(request);
    verify(owner, times(2))
        .getLaunchDescriptor(
            READ_REQUEST_ID,
            TENANT_ID,
            request.worldSlug(),
            request.controlPlaneRequestId(),
            evidence.requestDigest(),
            evidence.resultDigest());
    verifyNoMoreInteractions(owner);
  }

  private static Server startReceiver(
      Pki pki, LaunchDescriptorService owner, SimpleMeterRegistry meters) throws Exception {
    var service =
        new GameDesignGrpcService(
            mock(PingService.class),
            mock(RevisionService.class),
            mock(VersionService.class),
            owner,
            mock(CompleteLaunchBindingService.class),
            mock(TemplateRemapSetService.class),
            mock(VersionAssetArtifactService.class),
            mock(SettingsAuthorityService.class),
            mock(GameAuthoredHelpTopicService.class),
            new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
            meters);
    ReflectionTestUtils.setField(service, "workloadNamespace", NAMESPACE);

    var auth =
        new AuthTokenInterceptor(
            new JwtUtil("test-secret-key-test-secret-key-32-bytes", 60_000L), publicMethods());
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(pki.keyManagerFactory(pki.identity("server"))))
                .trustManager(pki.trustManagerFactory())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(service, auth, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static Set<String> publicMethods() throws IOException {
    var yaml =
        new YamlPropertySourceLoader()
            .load("application.yml", new FileSystemResource("src/main/resources/application.yml"));
    return Set.copyOf(
        new Binder(ConfigurationPropertySources.from(yaml))
            .bind("firemud.auth.grpc.public-methods", Bindable.listOf(String.class))
            .orElseThrow(
                () -> new IllegalStateException("Missing Game Design gRPC method policy")));
  }
}
