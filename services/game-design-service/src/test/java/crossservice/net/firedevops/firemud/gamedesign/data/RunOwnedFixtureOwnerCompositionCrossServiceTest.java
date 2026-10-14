package crossservice.net.firedevops.firemud.gamedesign.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.ResolvedGrpcTlsMaterial;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.data.RunOwnedFixtureOwnerReadAuthorization;
import net.firedevops.firemud.gamedesign.data.TestDataSeederIntegrationSupport;
import net.firedevops.firemud.gamedesign.service.impl.RunOwnedFixtureAuthoredWorldSourceReadGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.RunOwnedFixtureAuthoredWorldVersionStateGrpcService;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldSourceReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldVersionStateServiceGrpc;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionFixtureCoordinator;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.impl.GameSessionControlPlaneGrpcService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldFixedPresetResources;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.service.impl.WorldInstanceActivationServiceImpl;
import net.firedevops.firemud.worldmanagement.service.impl.WorldLifecycleCommandServiceImpl;
import net.firedevops.firemud.worldmanagement.service.impl.WorldManagementGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "spring.config.import=file:src/main/resources/application.yml",
      "firemud.smoke.seed-demo-runtime.enabled=true",
      "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED=false",
      "spring.flyway.clean-disabled=false",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "firemud.grpc.workload-namespace=dev",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class RunOwnedFixtureOwnerCompositionCrossServiceTest extends TestDataSeederIntegrationSupport {
  @Container
  static PostgreSQLContainer<?> composedGameSessionPostgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static PostgreSQLContainer<?> composedWorldPostgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void actualProducerStartingAndPreparingOwnersComposeOverThreeMutualTlsSockets(
      @TempDir Path testDirectory) throws Exception {
    assertThat(environment.getRequiredProperty("spring.application.name"))
        .isEqualTo("game-design-service");
    var applicationNameOrigin =
        environment.getPropertySources().stream()
            .filter(source -> source.containsProperty("spring.application.name"))
            .map(
                source ->
                    org.springframework.boot.origin.OriginLookup.getOrigin(
                        source, "spring.application.name"))
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("spring.application.name has no config data origin"));
    while (applicationNameOrigin
        instanceof org.springframework.boot.origin.PropertySourceOrigin wrapper) {
      applicationNameOrigin =
          java.util.Objects.requireNonNull(
              wrapper.getOrigin(), "spring.application.name origin wrapper has no concrete parent");
    }
    assertThat(applicationNameOrigin)
        .as("origin for spring.application.name: %s", applicationNameOrigin)
        .isInstanceOf(org.springframework.boot.origin.TextResourceOrigin.class);
    Path actualApplicationConfig =
        java.util.Objects.requireNonNull(
                ((org.springframework.boot.origin.TextResourceOrigin) applicationNameOrigin)
                    .getResource())
            .getFile()
            .toPath()
            .toRealPath();
    Path expectedApplicationConfig = Path.of("src/main/resources/application.yml").toRealPath();
    assertThat(actualApplicationConfig)
        .withFailMessage(
            "spring.application.name came from %s, expected GDS config %s",
            actualApplicationConfig, expectedApplicationConfig)
        .isEqualTo(expectedApplicationConfig);

    Map<String, java.util.function.Supplier<Object>> postgresProperties = new LinkedHashMap<>();
    PostgresBackedServiceTestSupport.registerPostgresService(
        postgresProperties::put, postgres, "game_design_service");
    String expectedFlywayLocations =
        postgresProperties.get("spring.flyway.locations").get().toString();
    assertThat(
            java.util.Arrays.stream(flyway.getConfiguration().getLocations())
                .map(org.flywaydb.core.api.Location::getDescriptor)
                .toList())
        .containsExactlyInAnyOrder(expectedFlywayLocations.split(","));

    Fixture fixture = prepareFixture(testDirectory);
    configureSeeder(fixture);
    seeder.run(new DefaultApplicationArguments(new String[] {}));
    byte[] originalReceipt = Files.readAllBytes(fixture.receiptPath());
    var capability =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .parseCapability(Files.readAllBytes(fixture.capabilityPath()));
    var receipt =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .parseSourceReceipt(originalReceipt, capability);
    var gsDatabase =
        composedOwnerDatabase(composedGameSessionPostgres, "game-session-service", "composed_gs");
    var worldDatabase =
        composedOwnerDatabase(composedWorldPostgres, "world-management-service", "composed_world");
    assertThat(
            worldDatabase
                .dsl()
                .fetchSingle(
                    "SELECT count(*) AS count FROM flyway_schema_history "
                        + "WHERE success AND type='SQL' AND version IS NOT NULL")
                .get("count", Long.class))
        .isEqualTo(worldDatabase.versionedSqlMigrationCount());
    assertThat(
            worldDatabase
                .dsl()
                .fetch(
                    "SELECT version FROM flyway_schema_history WHERE success "
                        + "AND version IN ('1009', '1009.1', '1010') ORDER BY installed_rank")
                .getValues("version", String.class))
        .containsExactly("1009", "1009.1", "1010");
    // Consume unused real allocator values to exercise unequal private Version/instance numbers.
    // Numeric values can naturally coincide across services; authority comes from typed owner
    // bindings.
    worldDatabase
        .dsl()
        .fetchSingle(
            "SELECT nextval(pg_get_serial_sequence('world_authored_version_identity', 'local_version_key'))");
    worldDatabase
        .dsl()
        .fetchSingle("SELECT nextval('world_canonical_private_game_instance_key_seq')");
    worldDatabase.dsl().fetchSingle("SELECT nextval('world_instance_id_seq')");
    var catalog =
        new GameSessionCanonicalRealmCatalogRepository(
            gsDatabase.dsl(), gsDatabase.transactions(), NAMESPACE);
    var launchOwner =
        new GameSessionCanonicalInstanceLaunchRepository(
            gsDatabase.dsl(), gsDatabase.transactions(), NAMESPACE, catalog);
    var beanFactory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    beanFactory.addBean("actualLaunchOwner", launchOwner);
    var gsTls =
        fixedReadTls(
            fixture,
            fixture.gameSessionCertificatePath(),
            fixture.gameSessionCertificatePath().resolveSibling("game-session.key"));
    var worldTls =
        fixedReadTls(
            fixture,
            fixture.worldManagementCertificatePath(),
            fixture.worldManagementCertificatePath().resolveSibling("world-management.key"));
    var coordinator =
        new RunOwnedInitialAdmissionFixtureCoordinator(
            true,
            fixture.capabilityPath().toString(),
            fixture.receiptPath().toString(),
            fixture.runId(),
            fixture.composeProjectName(),
            gsTls,
            beanFactory.getBeanProvider(GameSessionCanonicalInstanceLaunchRepository.class),
            NAMESPACE);
    // The production loader verifies real producer files and original mounted pins; no test-only GS
    // carrier is used.
    var gsEvidence = coordinator.loadFixedVerifiedEvidence();
    var starting = launchOwner.createVerifiedFixtureStartingLaunch(gsEvidence);
    assertThat(launchOwner.readVerifiedFixtureLaunch(gsEvidence)).isEqualTo(starting);
    assertThat(starting.catalog().association().sourceEvidence())
        .isEqualTo(receipt.creationEvidence());
    assertThat(starting.catalog().snapshot().sourceIntakeReceipt().source())
        .isEqualTo(receipt.sourceEvidence());
    assertThat(starting.status())
        .isEqualTo(
            net.firedevops.firemud.common.gamesession
                .CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
                .STARTING);
    assertThat(starting.rowVersion()).isZero();
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    var gsHandler = org.mockito.Mockito.spy(actualGsFixtureReadHandler(registry));
    gsHandler.configureRunOwnedFixtureLaunchAssociationReadBoundary(coordinator, true, NAMESPACE);
    var gdsLeaf =
        issueFixedReadLeaf(
            fixture,
            "composed-game-design-server",
            "spiffe://firemud/ns/dev/sa/game-design-service",
            true);
    var channels = new CopyOnWriteArrayList<ManagedChannel>();
    var factory = composedChannelFactory(channels);
    var servers = new java.util.ArrayList<Server>();
    net.firedevops.firemud.worldmanagement.client.GameDesignClient remoteDesign = null;
    RunOwnedWorldLaunchEvidenceProvider provider = null;
    WorldLifecycleCommandServiceImpl commands = null;
    var fixedOwnerContext = actualFixedReadContext(fixture);
    try {
      Server gdsServer =
          fixedProductionServer(
              fixture,
              gdsLeaf,
              Set.of(
                  "gamedesign.v1.GameDesignService/GetLaunchDescriptor",
                  AuthoredWorldSourceReadServiceGrpc.getResolveAuthoredWorldSourceMethod()
                      .getFullMethodName(),
                  AuthoredWorldVersionStateServiceGrpc.getGetAuthoredWorldVersionStateMethod()
                      .getFullMethodName()),
              gameDesignGrpcService,
              fixedOwnerContext.getBean(RunOwnedFixtureAuthoredWorldSourceReadGrpcService.class),
              fixedOwnerContext.getBean(RunOwnedFixtureAuthoredWorldVersionStateGrpcService.class));
      servers.add(gdsServer);
      Server gsServer =
          fixedProductionServer(
              fixture,
              new FixedReadLeaf(
                  fixture.gameSessionCertificatePath(),
                  fixture.gameSessionCertificatePath().resolveSibling("game-session.key")),
              Set.of(
                  net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc
                      .getGetRunOwnedFixtureLaunchAssociationMethod()
                      .getFullMethodName()),
              gsHandler);
      servers.add(gsServer);
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameDesignService("127.0.0.1:" + gdsServer.getPort());
      endpoints.setGameSessionService("127.0.0.1:" + gsServer.getPort());
      remoteDesign =
          new net.firedevops.firemud.worldmanagement.client.GameDesignClient(
              endpoints,
              worldTls,
              factory,
              net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer.noop());
      ReflectionTestUtils.invokeMethod(remoteDesign, "init");
      provider =
          new RunOwnedWorldLaunchEvidenceProvider(
              true,
              fixture.capabilityPath().toString(),
              fixture.receiptPath().toString(),
              fixture.runId(),
              fixture.composeProjectName(),
              worldTls,
              remoteDesign,
              endpoints,
              factory,
              NAMESPACE);
      var entityClient =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.worldmanagement.client.EntityManagementClient.class);
      commands =
          actualWorldFixtureCommands(
              worldDatabase,
              provider,
              remoteDesign,
              entityClient,
              endpoints,
              worldTls,
              factory,
              registry);
      var worldRepository =
          new net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository(
              worldDatabase.dsl());
      var worldHandler = actualWorldFixtureHandler(commands, provider, worldRepository, registry);
      Server worldServer =
          fixedProductionServer(
              fixture,
              new FixedReadLeaf(
                  fixture.worldManagementCertificatePath(),
                  fixture.worldManagementCertificatePath().resolveSibling("world-management.key")),
              Set.of(
                  net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc
                      .getPrepareWorldInstanceMethod()
                      .getFullMethodName()),
              worldHandler);
      servers.add(worldServer);
      var descriptor = receipt.launchReadEvidence().launchDescriptor();
      var bundle = receipt.launchReadEvidence().publishedReleaseBundle();
      var request =
          net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceRequest.newBuilder()
              .setTenantId(Long.toString(starting.catalog().association().gameSessionTenantId()))
              .setGameInstanceId(Long.toString(starting.gameInstanceId()))
              .setGameTemplateId(Long.toString(capability.gameTemplateId()))
              .setControlPlaneRequestId(capability.operationId())
              .setLaunchDescriptorId(descriptor.getLaunchDescriptorId())
              .setVersionId(Long.toString(bundle.getVersionId()))
              .setRuntimeFlagsJson(descriptor.getRuntimeFlagsJson())
              .setGenerationConfigRevision(bundle.getGenerationConfigRevision())
              .setReleaseBundleId(Long.toString(bundle.getId()))
              .setPublishedReleaseBundleRef(bundle.getPublishedReleaseBundleRef())
              .setVersionStateEpoch(
                  receipt.launchReadEvidence().versionState().getVersionStateEpoch())
              .build();
      var originalGdsRows = fixedReadOwnerRows();
      var originalGsRows = allComposedOwnerRows(gsDatabase.dsl());
      ManagedChannel worldChannel =
          factory.buildChannel(
              "127.0.0.1:" + worldServer.getPort(), worldServer.getPort(), gsTls, false);
      var stub =
          net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc.newBlockingStub(
                  worldChannel)
              .withCallCredentials(
                  new net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials(
                      WORLD_MANAGEMENT_URI_SAN))
              .withInterceptors(
                  new net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor(
                      WORLD_MANAGEMENT_URI_SAN))
              .withDeadlineAfter(30, TimeUnit.SECONDS);
      var prepared = stub.prepareWorldInstance(request);
      assertThat(prepared.hasError()).as("actual World prepare response").isFalse();
      assertThat(prepared.getWorldInstance().getStatus())
          .isEqualTo(
              net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus
                  .WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING);
      assertThat(prepared.getWorldInstance().getLifecycleEpoch()).isEqualTo(1L);
      assertThat(prepared.getWorldInstance().getTenantId()).isEqualTo(request.getTenantId());
      assertThat(prepared.getWorldInstance().getGameInstanceId())
          .isEqualTo(request.getGameInstanceId());
      assertThat(prepared.getWorldInstance().getVersionId()).isEqualTo(request.getVersionId());
      var association =
          independentlyReadWorldFixture(worldDatabase.dsl(), starting.gameInstanceUuid());
      var privateKeys = association.worldPrivateKeys();
      // Tenant numbers may coincide; typed World owner receipts establish independent authority.
      assertThat(privateKeys.tenantId())
          .isEqualTo(association.runOwnedFixture().sourceIntakeReceipt().localTenantKey());
      assertThat(association.runOwnedFixture().sourceIntakeReceipt().source())
          .isEqualTo(receipt.sourceEvidence());
      assertThat(association.runOwnedFixture().sourceIntakeReceipt().sourceOperationId())
          .isEqualTo(receipt.sourceEvidence().operationId());
      assertThat(association.versionIdentity().sourceIntakeReceipt())
          .isEqualTo(association.runOwnedFixture().sourceIntakeReceipt());
      assertThat(association.versionIdentity().versionStateEvidence().sourceEvidence())
          .isEqualTo(receipt.sourceEvidence());
      assertThat(association.versionIdentity().versionStateEvidence().canonicalVersionId())
          .isEqualTo(UUID.fromString(bundle.getCanonicalVersionId()));
      assertThat(privateKeys.versionId())
          .isEqualTo(association.versionIdentity().localVersionKey());
      assertThat(association.identity().canonicalGameInstanceId())
          .isEqualTo(starting.gameInstanceUuid());
      assertThat(association.runOwnedFixture().gameSessionTenantId())
          .isEqualTo(starting.catalog().association().gameSessionTenantId());
      assertThat(association.runOwnedFixture().gameSessionGameInstanceId())
          .isEqualTo(starting.gameInstanceId());
      assertThat(privateKeys.gameInstanceId()).isNotEqualTo(starting.gameInstanceId());
      assertThat(privateKeys.versionId()).isNotEqualTo(bundle.getVersionId());
      var stored =
          new net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository(
                  worldDatabase.dsl())
              .findById(association.worldInstanceId())
              .orElseThrow();
      assertThat(stored.getStatus()).isEqualTo("PREPARING");
      assertThat(stored.getTenantId()).isEqualTo(privateKeys.tenantId());
      assertThat(stored.getGameInstanceId()).isEqualTo(privateKeys.gameInstanceId());
      assertThat(stored.getVersionId()).isEqualTo(privateKeys.versionId());
      assertThat(stored.getLifecycleEpoch()).isEqualTo(1L);
      assertThat(stored.getRowVersion()).isZero();
      var materialized =
          RunOwnedWorldFixedPresetResources.readCommitted(
              worldDatabase.dsl(),
              association,
              association.runOwnedFixture().sourceIntakeReceipt(),
              association.versionIdentity());
      assertThat(materialized.regionCount()).isEqualTo(1);
      assertThat(materialized.zoneCount()).isEqualTo(1);
      assertThat(materialized.roomCount()).isEqualTo(2);
      assertThat(materialized.exitCount()).isEqualTo(2);
      assertThat(materialized.presetContentDigest())
          .isEqualTo(
              RunOwnedWorldFixedPresetResources.plan(
                      association,
                      association.runOwnedFixture().sourceIntakeReceipt(),
                      association.versionIdentity())
                  .expectedContentDigest());
      assertThat(worldDatabase.dsl().fetchCount(DSL.table("world_authored_topology_identity")))
          .isEqualTo(6);
      var definition = RunOwnedWorldFixedPresetResources.definition();
      var expectedTemplateIds = new java.util.ArrayList<UUID>();
      expectedTemplateIds.add(definition.region().templateId());
      expectedTemplateIds.add(definition.zone().templateId());
      definition.rooms().forEach(room -> expectedTemplateIds.add(room.templateId()));
      definition.exits().forEach(exit -> expectedTemplateIds.add(exit.templateId()));
      assertThat(
              worldDatabase
                  .dsl()
                  .fetch("SELECT template_id FROM world_authored_topology_identity")
                  .getValues("template_id", UUID.class))
          .containsExactlyInAnyOrderElementsOf(expectedTemplateIds);
      assertThat(
              worldDatabase.dsl().fetchCount(DSL.table("region_instance"))
                  + worldDatabase.dsl().fetchCount(DSL.table("zone_instance"))
                  + worldDatabase.dsl().fetchCount(DSL.table("room_instance"))
                  + worldDatabase.dsl().fetchCount(DSL.table("room_instance_exit")))
          .isEqualTo(6);
      assertThat(worldDatabase.dsl().fetchCount(DSL.table("world_instance"))).isOne();
      assertThat(worldDatabase.dsl().fetchCount(DSL.table("initial_admission_bind_hold"))).isZero();
      assertThat(worldDatabase.dsl().fetchCount(DSL.table("world_canonical_player_admission_hold")))
          .isZero();
      assertThat(
              worldDatabase
                  .dsl()
                  .fetchCount(DSL.table("world_canonical_instance_activation_operation")))
          .isZero();
      assertThat(worldDatabase.dsl().fetchCount(DSL.table("world_entity_spawn_binding"))).isZero();
      assertThat(
              worldDatabase
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) AS count FROM room_instance_exit outward JOIN room_instance_exit inward "
                          + "ON outward.from_room_instance_record_id=inward.to_room_instance_record_id "
                          + "AND outward.to_room_instance_record_id=inward.from_room_instance_record_id "
                          + "WHERE outward.direction='NORTH' AND inward.direction='SOUTH'")
                  .get("count", Long.class))
          .isOne();
      var worldRows = allComposedOwnerRows(worldDatabase.dsl());
      assertThat(stub.prepareWorldInstance(request)).isEqualTo(prepared);
      var reread = independentlyReadWorldFixture(worldDatabase.dsl(), starting.gameInstanceUuid());
      assertThat(
              RunOwnedWorldFixedPresetResources.readCommitted(
                  worldDatabase.dsl(),
                  reread,
                  reread.runOwnedFixture().sourceIntakeReceipt(),
                  reread.versionIdentity()))
          .isEqualTo(materialized);
      assertThat(allComposedOwnerRows(worldDatabase.dsl())).isEqualTo(worldRows);
      assertThat(allComposedOwnerRows(gsDatabase.dsl())).isEqualTo(originalGsRows);
      assertThat(fixedReadOwnerRows()).isEqualTo(originalGdsRows);
      assertThat(Files.readAllBytes(fixture.receiptPath())).containsExactly(originalReceipt);
      // Selector denial is an actual production response; no remote positive response is
      // controlled.
      assertThat(
              stub.prepareWorldInstance(
                      request.toBuilder()
                          .setGameInstanceId(Long.toString(starting.gameInstanceId() + 1))
                          .build())
                  .hasError())
          .isTrue();
      assertThat(allComposedOwnerRows(worldDatabase.dsl())).isEqualTo(worldRows);
      org.mockito.Mockito.verifyNoInteractions(entityClient);
      // Reconstruct actual World owners and all remote readers, retaining the original allocation.
      ReflectionTestUtils.invokeMethod(commands, "closeRunOwnedWorldFixtureOwners");
      provider.closeGameSessionFixtureClient();
      remoteDesign.close();
      remoteDesign =
          new net.firedevops.firemud.worldmanagement.client.GameDesignClient(
              endpoints,
              worldTls,
              factory,
              net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer.noop());
      ReflectionTestUtils.invokeMethod(remoteDesign, "init");
      provider =
          new RunOwnedWorldLaunchEvidenceProvider(
              true,
              fixture.capabilityPath().toString(),
              fixture.receiptPath().toString(),
              fixture.runId(),
              fixture.composeProjectName(),
              worldTls,
              remoteDesign,
              endpoints,
              factory,
              NAMESPACE);
      commands =
          actualWorldFixtureCommands(
              worldDatabase,
              provider,
              remoteDesign,
              entityClient,
              endpoints,
              worldTls,
              factory,
              registry);
      var restartedHandler =
          actualWorldFixtureHandler(
              commands,
              provider,
              new net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository(
                  worldDatabase.dsl()),
              registry);
      Server restartedServer =
          fixedProductionServer(
              fixture,
              new FixedReadLeaf(
                  fixture.worldManagementCertificatePath(),
                  fixture.worldManagementCertificatePath().resolveSibling("world-management.key")),
              Set.of(
                  net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc
                      .getPrepareWorldInstanceMethod()
                      .getFullMethodName()),
              restartedHandler);
      servers.add(restartedServer);
      ManagedChannel restartedChannel =
          factory.buildChannel(
              "127.0.0.1:" + restartedServer.getPort(), restartedServer.getPort(), gsTls, false);
      assertThat(actualWorldPrepareStub(restartedChannel).prepareWorldInstance(request))
          .isEqualTo(prepared);
      assertThat(allComposedOwnerRows(worldDatabase.dsl())).isEqualTo(worldRows);
      var reads =
          org.mockito.ArgumentCaptor.forClass(
              net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationRequest
                  .class);
      org.mockito.Mockito.verify(gsHandler, org.mockito.Mockito.atLeast(3))
          .getRunOwnedFixtureLaunchAssociation(reads.capture(), any());
      assertThat(reads.getAllValues())
          .extracting(value -> value.getReadRequestId())
          .doesNotHaveDuplicates();
      org.mockito.Mockito.clearInvocations(
          gsHandler, launchDescriptorService, fixtureReadback, fixturePolicy);
      var otherGsLeaf =
          issueFixedReadLeaf(fixture, "different-game-session-client", SESSION_URI_SAN, false);
      for (var wrongLeaf :
          java.util.List.of(
              otherGsLeaf,
              new FixedReadLeaf(
                  fixture.worldManagementCertificatePath(),
                  fixture
                      .worldManagementCertificatePath()
                      .resolveSibling("world-management.key")))) {
        var wrongTls = fixedReadTls(fixture, wrongLeaf.certificate(), wrongLeaf.privateKey());
        var wrongChannel =
            factory.buildChannel(
                "127.0.0.1:" + restartedServer.getPort(),
                restartedServer.getPort(),
                wrongTls,
                false);
        assertThat(actualWorldPrepareStub(wrongChannel).prepareWorldInstance(request).hasError())
            .isTrue();
      }
      org.mockito.Mockito.verifyNoInteractions(
          gsHandler, launchDescriptorService, fixtureReadback, fixturePolicy, entityClient);
      assertThat(allComposedOwnerRows(worldDatabase.dsl())).isEqualTo(worldRows);
      assertThat(allComposedOwnerRows(gsDatabase.dsl())).isEqualTo(originalGsRows);
      assertThat(fixedReadOwnerRows()).isEqualTo(originalGdsRows);
      assertThat(Files.readAllBytes(fixture.receiptPath())).containsExactly(originalReceipt);
    } finally {
      try {
        try {
          if (commands != null)
            ReflectionTestUtils.invokeMethod(commands, "closeRunOwnedWorldFixtureOwners");
        } finally {
          try {
            if (provider != null) provider.closeGameSessionFixtureClient();
          } finally {
            if (remoteDesign != null) remoteDesign.close();
          }
        }
      } finally {
        for (Server server : servers) server.shutdownNow();
        for (ManagedChannel channel : channels) channel.shutdownNow();
        try {
          for (ManagedChannel channel : channels)
            assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
          for (Server server : servers)
            assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
          fixedOwnerContext.close();
          registry.close();
        }
      }
    }
  }

  private GameSessionControlPlaneGrpcService actualGsFixtureReadHandler(
      io.micrometer.core.instrument.MeterRegistry registry) throws Exception {
    // Its public production constructor has package-private legacy parameter types. No fixture
    // positive response or owner is mocked; only these unrelated, unused dependencies are doubles.
    var constructor = GameSessionControlPlaneGrpcService.class.getConstructors()[0];
    var parameters = constructor.getParameterTypes();
    Object[] arguments = new Object[parameters.length];
    for (int index = 0; index < parameters.length; index++) {
      arguments[index] =
          parameters[index].isInstance(registry)
              ? registry
              : org.mockito.Mockito.mock(parameters[index]);
    }
    return (GameSessionControlPlaneGrpcService) constructor.newInstance(arguments);
  }

  private org.springframework.context.annotation.AnnotationConfigApplicationContext
      actualFixedReadContext(Fixture fixture) {
    var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "actualImmutableFixture",
                Map.ofEntries(
                    Map.entry("FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED", "true"),
                    Map.entry("firemud.grpc.workload-namespace", NAMESPACE),
                    Map.entry("FIREMUD_SMOKE_RUN_ID", fixture.runId()),
                    Map.entry("FIREMUD_SMOKE_COMPOSE_PROJECT_NAME", fixture.composeProjectName()),
                    Map.entry(
                        "FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH",
                        fixture.capabilityPath().toString()),
                    Map.entry(
                        "FIREMUD_SMOKE_AUTHORED_WORLD_SOURCE_RECEIPT_PATH",
                        fixture.receiptPath().toString()),
                    Map.entry(
                        "FIREMUD_SMOKE_GAME_SESSION_LEAF_CERT_PATH",
                        fixture.gameSessionCertificatePath().toString()),
                    Map.entry(
                        "FIREMUD_SMOKE_WORLD_MANAGEMENT_LEAF_CERT_PATH",
                        fixture.worldManagementCertificatePath().toString()),
                    Map.entry("firemud.grpc.ca-cert", fixture.caCertificatePath().toString()))));
    context.getBeanFactory().registerSingleton("committedLaunchReadback", fixtureReadback);
    context.getBeanFactory().registerSingleton("preexistingPolicyOwner", fixturePolicy);
    context.getBeanFactory().registerSingleton("actualSourceOwner", authoredWorldSourceRepository);
    context.getBeanFactory().registerSingleton("actualTransactions", transactionManager);
    context.register(
        RunOwnedFixtureOwnerReadAuthorization.class,
        RunOwnedFixtureAuthoredWorldSourceReadGrpcService.class,
        RunOwnedFixtureAuthoredWorldVersionStateGrpcService.class);
    try {
      context.refresh();
      assertThat(context.getBean(RunOwnedFixtureOwnerReadAuthorization.class)).isNotNull();
      return context;
    } catch (RuntimeException | Error failure) {
      context.close();
      throw failure;
    }
  }

  private WorldManagementGrpcService actualWorldFixtureHandler(
      WorldLifecycleCommandServiceImpl commands,
      RunOwnedWorldLaunchEvidenceProvider provider,
      net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository repository,
      io.micrometer.core.instrument.MeterRegistry registry) {
    var activation =
        new WorldInstanceActivationServiceImpl(commands, Optional.empty(), provider, repository);
    return new WorldManagementGrpcService(
        org.mockito.Mockito.mock(net.firedevops.firemud.worldmanagement.service.PingService.class),
        org.mockito.Mockito.mock(net.firedevops.firemud.worldmanagement.service.RoomService.class),
        activation,
        org.mockito.Mockito.mock(
            net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService.class),
        org.mockito.Mockito.mock(
            net.firedevops.firemud.worldmanagement.service.WorldDesignMutationService.class),
        org.mockito.Mockito.mock(
            net.firedevops.firemud.worldmanagement.service.WorldUpgradeValidationService.class),
        org.mockito.Mockito.mock(
            net.firedevops.firemud.common.security.GameplaySessionAttestationService.class),
        registry,
        objectMapper,
        NAMESPACE);
  }

  private net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc
          .WorldManagementServiceBlockingStub
      actualWorldPrepareStub(ManagedChannel channel) {
    return net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc.newBlockingStub(
            channel)
        .withCallCredentials(
            new net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials(
                WORLD_MANAGEMENT_URI_SAN))
        .withInterceptors(
            new net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor(
                WORLD_MANAGEMENT_URI_SAN))
        .withDeadlineAfter(30, TimeUnit.SECONDS);
  }

  private CommonGrpcClientProperties fixedReadTls(Fixture fixture, Path certificate, Path key) {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate.toString());
    tls.setPrivateKey(key.toString());
    tls.setCaCert(fixture.caCertificatePath().toString());
    return tls;
  }

  private ComposedOwnerDatabase composedOwnerDatabase(
      PostgreSQLContainer<?> container, String service, String schema) {
    var dataSource =
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setSchema(schema);
    Flyway ownerFlyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations(
                "filesystem:"
                    + Path.of("..", service, "src/main/resources/db/migration")
                        .toAbsolutePath()
                        .normalize(),
                "filesystem:"
                    + Path.of("../common-saga/src/main/resources/db/migration/saga")
                        .toAbsolutePath()
                        .normalize())
            .load();
    ownerFlyway.migrate();
    long versionedSqlMigrationCount =
        java.util.Arrays.stream(ownerFlyway.info().all())
            .filter(migration -> migration.getVersion() != null)
            .filter(migration -> "SQL".equals(migration.getType().name()))
            .count();
    return new ComposedOwnerDatabase(
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES),
        new DataSourceTransactionManager(dataSource),
        versionedSqlMigrationCount);
  }

  private record ComposedOwnerDatabase(
      DSLContext dsl, PlatformTransactionManager transactions, long versionedSqlMigrationCount) {}

  private static GrpcChannelFactory composedChannelFactory(
      java.util.List<ManagedChannel> channels) {
    return new GrpcChannelFactory() {
      @Override
      public ManagedChannel buildChannel(
          String target,
          int port,
          CommonGrpcClientProperties properties,
          boolean keepAlive,
          ResolvedGrpcTlsMaterial material)
          throws javax.net.ssl.SSLException {
        ManagedChannel channel = super.buildChannel(target, port, properties, keepAlive, material);
        channels.add(channel);
        return channel;
      }
    };
  }

  private Server fixedProductionServer(
      Fixture fixture, FixedReadLeaf leaf, Set<String> methods, io.grpc.BindableService... handlers)
      throws Exception {
    var builder =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            leaf.certificate().toFile(), leaf.privateKey().toFile()))
                    .trustManager(fixture.caCertificatePath().toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build());
    var auth =
        new AuthTokenInterceptor(
            new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L), methods);
    for (var handler : handlers)
      builder.addService(
          ServerInterceptors.intercept(handler, new GrpcPeerIdentityInterceptor(), auth));
    return builder.build().start();
  }

  private WorldLifecycleCommandServiceImpl actualWorldFixtureCommands(
      ComposedOwnerDatabase database,
      RunOwnedWorldLaunchEvidenceProvider provider,
      net.firedevops.firemud.worldmanagement.client.GameDesignClient design,
      net.firedevops.firemud.worldmanagement.client.EntityManagementClient entities,
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory factory,
      io.micrometer.core.instrument.MeterRegistry registry) {
    var commands =
        new WorldLifecycleCommandServiceImpl(
            new net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository(
                database.dsl()),
            new net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository(
                database.dsl()),
            new net.firedevops.firemud.worldmanagement.repository.ZoneRepository(database.dsl()),
            new net.firedevops.firemud.worldmanagement.repository.ZoneInstanceRepository(
                database.dsl()),
            new net.firedevops.firemud.worldmanagement.repository.RoomRepository(database.dsl()),
            new net.firedevops.firemud.worldmanagement.repository.RoomExitRepository(
                database.dsl()),
            new net.firedevops.firemud.worldmanagement.repository.RoomInstanceRepository(
                database.dsl()),
            new net.firedevops.firemud.worldmanagement.repository.RoomInstanceExitRepository(
                database.dsl()),
            new net.firedevops.firemud.worldmanagement.repository.WorldEventRepository(
                database.dsl()),
            new net.firedevops.firemud.worldmanagement.config.WorldProperties(),
            design,
            entities,
            registry,
            database.transactions(),
            provider,
            database.dsl(),
            endpoints,
            tls,
            factory,
            NAMESPACE);
    ReflectionTestUtils.invokeMethod(commands, "initMetrics");
    return commands;
  }

  private net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation
      independentlyReadWorldFixture(DSLContext world, UUID canonicalInstance) {
    return new WorldCanonicalInstanceAssociationRepository(
            world,
            new net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingRepository(
                world),
            new net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository(
                world),
            new net.firedevops.firemud.worldmanagement.tenant
                .WorldAuthoredVersionIdentityRepository(world))
        .readOwnerAssociation(canonicalInstance)
        .orElseThrow();
  }

  private Map<String, String> allComposedOwnerRows(DSLContext owner) {
    Map<String, String> rows = new LinkedHashMap<>();
    for (String table :
        owner
            .fetch(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=current_schema() "
                    + "AND table_type='BASE TABLE' ORDER BY table_name")
            .getValues("table_name", String.class)) {
      String qualified = DSL.name(table).toString();
      rows.put(
          table,
          owner
              .fetch(
                  "SELECT row_to_json(t)::text AS bytes, xmin::text AS row_version FROM "
                      + qualified
                      + " t ORDER BY row_to_json(t)::text")
              .formatJSON());
    }
    return rows;
  }
}
