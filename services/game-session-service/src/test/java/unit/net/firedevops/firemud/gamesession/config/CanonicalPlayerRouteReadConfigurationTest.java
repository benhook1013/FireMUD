package unit.net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.config.CanonicalPlayerRouteReadConfiguration;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterSelectionService;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CanonicalPlayerRouteReadConfigurationTest {
  @Test
  void defaultConfigurationLeavesCanonicalRosterRouteSourceUnregistered() {
    new ApplicationContextRunner()
        .withUserConfiguration(CanonicalPlayerRouteReadConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(GameDesignPublishedRealmPolicyClient.class);
              assertThat(context).doesNotHaveBean(CanonicalPlayerRouteReadService.class);
              assertThat(context).doesNotHaveBean(CanonicalPublishedPlayerRouteReadService.class);
              assertThat(context).doesNotHaveBean(CanonicalGameplayRosterSelectionService.class);
            });
  }

  @Test
  void explicitlyDisabledConfigurationLeavesCanonicalRosterRouteSourceUnregistered() {
    new ApplicationContextRunner()
        .withUserConfiguration(CanonicalPlayerRouteReadConfiguration.class)
        .withPropertyValues("firemud.canonical-gameplay-roster-owner-read.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(GameDesignPublishedRealmPolicyClient.class);
              assertThat(context).doesNotHaveBean(CanonicalPlayerRouteReadService.class);
              assertThat(context).doesNotHaveBean(CanonicalPublishedPlayerRouteReadService.class);
              assertThat(context).doesNotHaveBean(CanonicalGameplayRosterSelectionService.class);
            });
  }

  // Composition proof only: repositories and channels are controlled doubles with file-backed
  // TLS inputs. Actual clients initialize, but the unregistered reference owner prevents calls.
  // The client-level tests retain separate RPC identity and deadline proof.
  @Test
  void enabledConfigurationComposesRealRouteSourcesWithFileBackedTlsAndExactTarget(
      @TempDir Path directory) throws IOException {
    Path clientCertificate = Files.createFile(directory.resolve("game-session-client.crt"));
    Path clientKey = Files.createFile(directory.resolve("game-session-client.key"));
    Path serverCa = Files.createFile(directory.resolve("game-design-ca.crt"));
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("game-design.test:7654");
    endpoints.setEntityManagementService("entity.test:7655");
    var tlsProperties = new CommonGrpcClientProperties();
    tlsProperties.setCertChain(clientCertificate.toString());
    tlsProperties.setPrivateKey(clientKey.toString());
    tlsProperties.setCaCert(serverCa.toString());
    var channelFactory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            anyString(), anyInt(), any(CommonGrpcClientProperties.class), anyBoolean()))
        .thenReturn(channel);

    new ApplicationContextRunner()
        .withUserConfiguration(
            CanonicalPlayerRouteReadConfiguration.class, CanonicalGameplayRosterClient.class)
        .withBean(ServiceEndpointsProperties.class, () -> endpoints)
        .withBean(CommonGrpcClientProperties.class, () -> tlsProperties)
        .withBean(GrpcChannelFactory.class, () -> channelFactory)
        .withBean(
            GameSessionCanonicalRealmCatalogRepository.class,
            () -> mock(GameSessionCanonicalRealmCatalogRepository.class))
        .withBean(
            CanonicalInitialAdmissionRepository.class,
            () -> mock(CanonicalInitialAdmissionRepository.class))
        .withBean(
            CanonicalGameInstanceLaunchAssociationRepository.class,
            () -> mock(CanonicalGameInstanceLaunchAssociationRepository.class))
        .withPropertyValues(
            "firemud.canonical-gameplay-roster-owner-read.enabled=true",
            "firemud.grpc.workload-namespace=games")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(GameDesignPublishedRealmPolicyClient.class);
              assertThat(context).hasSingleBean(CanonicalPlayerRouteReadService.class);
              assertThat(context).hasSingleBean(CanonicalPublishedPlayerRouteReadService.class);
              assertThat(context).hasSingleBean(CanonicalGameplayRosterSelectionService.class);
              assertThat(context).hasSingleBean(CanonicalGameplayRosterClient.class);
              assertThat(context)
                  .doesNotHaveBean(
                      net.firedevops.firemud.gamesession.service
                          .CanonicalGameplayRosterReferenceOwner.class);
              var rosterClient = context.getBean(CanonicalGameplayRosterClient.class);
              assertThat(Mockito.mockingDetails(rosterClient).isMock()).isFalse();
              var published =
                  support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture
                      .canonicalPublishedRoute();
              var route = published.route();
              var player =
                  net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                      .setAccountId("66666666-6666-4666-8666-666666666666")
                      .setSessionId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
                      .setRequestId("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
                      .setTenantId(route.canonicalTenantId().toString())
                      .setRealmId(route.realmId().toString())
                      .setPlayableStateNamespaceId(route.playableStateNamespaceId().toString())
                      .setPlayableStateScope(route.playableStateScope())
                      .setGameInstanceId(route.canonicalGameInstanceId().toString())
                      .build();
              assertThatThrownBy(() -> rosterClient.listPreseededRoster(published, player))
                  .isInstanceOfSatisfying(
                      io.grpc.StatusRuntimeException.class,
                      failure ->
                          assertThat(failure.getStatus().getCode())
                              .isEqualTo(io.grpc.Status.Code.UNAVAILABLE));
              verify(channel, never()).newCall(any(), any());
              assertThat(
                      Mockito.mockingDetails(
                              context.getBean(GameDesignPublishedRealmPolicyClient.class))
                          .isMock())
                  .isFalse();
              assertThat(
                      Mockito.mockingDetails(context.getBean(CanonicalPlayerRouteReadService.class))
                          .isMock())
                  .isFalse();
              assertThat(
                      Mockito.mockingDetails(
                              context.getBean(CanonicalPublishedPlayerRouteReadService.class))
                          .isMock())
                  .isFalse();
              verify(channelFactory)
                  .buildChannel(eq("game-design.test:7654"), eq(6565), eq(tlsProperties), eq(true));
              verify(channelFactory)
                  .buildChannel(eq("entity.test:7655"), eq(6565), eq(tlsProperties), eq(true));
              verifyNoInteractions(
                  context.getBean(GameSessionCanonicalRealmCatalogRepository.class),
                  context.getBean(CanonicalInitialAdmissionRepository.class),
                  context.getBean(CanonicalGameInstanceLaunchAssociationRepository.class));
            });
  }

  @Test
  void enabledConfigurationRejectsInvalidNamespaceBeforeOpeningChannel(@TempDir Path directory)
      throws IOException {
    Path clientCertificate = Files.createFile(directory.resolve("game-session-client.crt"));
    Path clientKey = Files.createFile(directory.resolve("game-session-client.key"));
    Path serverCa = Files.createFile(directory.resolve("game-design-ca.crt"));
    var tlsProperties = new CommonGrpcClientProperties();
    tlsProperties.setCertChain(clientCertificate.toString());
    tlsProperties.setPrivateKey(clientKey.toString());
    tlsProperties.setCaCert(serverCa.toString());
    var channelFactory = mock(GrpcChannelFactory.class);

    new ApplicationContextRunner()
        .withUserConfiguration(CanonicalPlayerRouteReadConfiguration.class)
        .withBean(ServiceEndpointsProperties.class, ServiceEndpointsProperties::new)
        .withBean(CommonGrpcClientProperties.class, () -> tlsProperties)
        .withBean(GrpcChannelFactory.class, () -> channelFactory)
        .withBean(
            CanonicalGameplayRosterClient.class, () -> mock(CanonicalGameplayRosterClient.class))
        .withBean(
            GameSessionCanonicalRealmCatalogRepository.class,
            () -> mock(GameSessionCanonicalRealmCatalogRepository.class))
        .withBean(
            CanonicalInitialAdmissionRepository.class,
            () -> mock(CanonicalInitialAdmissionRepository.class))
        .withBean(
            CanonicalGameInstanceLaunchAssociationRepository.class,
            () -> mock(CanonicalGameInstanceLaunchAssociationRepository.class))
        .withPropertyValues(
            "firemud.canonical-gameplay-roster-owner-read.enabled=true",
            "firemud.grpc.workload-namespace=games.invalid")
        .run(
            context -> {
              assertThat(context).hasFailed();
              verifyNoInteractions(channelFactory);
            });
  }
}
