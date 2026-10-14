package integration.net.firedevops.firemud.gamedesign.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.ResolvedGrpcTlsMaterial;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.data.RunOwnedFixtureOwnerReadAuthorization;
import net.firedevops.firemud.gamedesign.data.TestDataSeeder;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
import net.firedevops.firemud.gamedesign.dto.VersionStateDto;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.jooq.tables.GameDesignPublishedRealmPolicySet;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.service.impl.GameDesignLaunchReadProjectionMapper;
import net.firedevops.firemud.gamedesign.service.impl.RunOwnedFixtureAuthoredWorldSourceReadGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.RunOwnedFixtureAuthoredWorldVersionStateGrpcService;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldSourceReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldVersionStateServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
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
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
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
class TestDataSeederIntegrationTest {
  private static final String NAMESPACE = "dev";
  private static final String SESSION_URI_SAN = "spiffe://firemud/ns/dev/sa/game-session-service";
  private static final String WORLD_MANAGEMENT_URI_SAN =
      "spiffe://firemud/ns/dev/sa/world-management-service";
  private static final Table<?> SOURCE_OPERATIONS =
      DSL.table(DSL.name("game_design_authored_world_source_operations"));
  private static final Table<?> SOURCE_DELIVERIES =
      DSL.table(DSL.name("game_design_authored_world_source_deliveries"));
  private static final Field<UUID> REGISTRATION_REQUEST_ID =
      DSL.field(DSL.name("registration_request_id"), UUID.class);
  private static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<UUID> DELIVERY_WORLD_OPERATION_ID =
      DSL.field(DSL.name("world_operation_id"), UUID.class);

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static PostgreSQLContainer<?> composedGameSessionPostgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static PostgreSQLContainer<?> composedWorldPostgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private TestDataSeeder seeder;

  @Autowired
  private net.firedevops.firemud.gamedesign.service.impl.GameDesignGrpcService
      gameDesignGrpcService;

  @MockitoSpyBean
  private net.firedevops.firemud.gamedesign.data.RunOwnedFixtureLaunchReadback fixtureReadback;

  @MockitoSpyBean
  private net.firedevops.firemud.gamedesign.data.RunOwnedFixturePolicyProducer fixturePolicy;

  @Autowired private Flyway flyway;
  @MockitoSpyBean private GameAuthoredWorldSourceRepository authoredWorldSourceRepository;
  @Autowired private GameRepository gameRepository;

  @Autowired
  private net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository
      creationRepository;

  @Autowired private GameTemplateRepository templateRepository;
  @Autowired private VersionRepository versionRepository;
  @Autowired private VersionAssetArtifactRepository artifactRepository;
  @Autowired private VersionService versionService;
  @Autowired private VersionAssetArtifactService artifactService;
  @Autowired private LaunchDescriptorRepository launchDescriptorRepository;
  @MockitoSpyBean private LaunchDescriptorService launchDescriptorService;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void resetDisposableServiceSchema() {
    ReflectionTestUtils.setField(seeder, "initialAdmissionFixtureEnabled", false);
    flyway.clean();
    flyway.migrate();
    seeder.run(new DefaultApplicationArguments(new String[] {}));
  }

  @Test
  void fixedWorldReadsQualifyRealPreexistingOwnersWithoutRewritingOrResolving(
      @TempDir Path testDirectory) throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    configureSeeder(fixture);
    seeder.run(new DefaultApplicationArguments(new String[] {}));
    var capability =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .parseCapability(Files.readAllBytes(fixture.capabilityPath()));
    var receipt =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .parseSourceReceipt(Files.readAllBytes(fixture.receiptPath()), capability);
    var authorization =
        new net.firedevops.firemud.gamedesign.data.RunOwnedFixtureOwnerReadAuthorization(
            fixtureReadback,
            fixturePolicy,
            NAMESPACE,
            fixture.runId(),
            fixture.composeProjectName(),
            fixture.capabilityPath().toString(),
            fixture.receiptPath().toString(),
            fixture.gameSessionCertificatePath().toString(),
            fixture.worldManagementCertificatePath().toString(),
            fixture.caCertificatePath().toString());
    var source = receipt.sourceEvidence();
    var sourceRequest =
        net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec.toReadRequest(
            new net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec.ReadRequest(
                NAMESPACE,
                UUID.randomUUID(),
                source.operationId(),
                source.canonicalTenantId(),
                source.worldSlug()));
    var versionRequest =
        net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .setReadRequestId(UUID.randomUUID().toString())
            .setCanonicalTenantId(source.canonicalTenantId().toString())
            .setWorldSlug(source.worldSlug())
            .setSourceOperationId(source.operationId().toString())
            .setExpectedSourceEvidenceDigest(source.evidenceDigest())
            .setVersionId(receipt.launchReadEvidence().versionState().getVersionId())
            .build();
    Map<String, String> before = fixedReadOwnerRows();
    byte[] receiptBefore = Files.readAllBytes(fixture.receiptPath());
    X509Certificate actualMountedWorldLeaf;
    try (var input = Files.newInputStream(fixture.worldManagementCertificatePath())) {
      actualMountedWorldLeaf =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
    // Peer context is controlled here; immutable PKI, producer and PostgreSQL owners are real.
    var peerContext =
        io.grpc.Context.current()
            .withValue(
                net.firedevops.firemud.common.grpc.GrpcPeerIdentity.CONTEXT_KEY,
                net.firedevops.firemud.common.grpc.GrpcPeerIdentity.parseUri(
                        WORLD_MANAGEMENT_URI_SAN)
                    .orElseThrow())
            .withValue(
                net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence.CONTEXT_KEY,
                net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence.fromCertificate(
                        actualMountedWorldLeaf)
                    .orElseThrow());
    org.mockito.Mockito.clearInvocations(launchDescriptorService);
    peerContext.run(
        () -> {
          assertThat(authorization.authorizeSource(sourceRequest)).isEqualTo(receipt);
          assertThat(authorization.authorizeVersion(versionRequest)).isEqualTo(receipt);
          var sourceOutput =
              new FixedReadObserver<
                  net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse>();
          new net.firedevops.firemud.gamedesign.service.impl
                  .RunOwnedFixtureAuthoredWorldSourceReadGrpcService(
                  authorization, authoredWorldSourceRepository, NAMESPACE)
              .resolveAuthoredWorldSource(sourceRequest, sourceOutput);
          assertThat(sourceOutput.failure).isNull();
          assertThat(sourceOutput.completed).isTrue();
          assertThat(
                  net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec
                      .fromReadResponse(
                          net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec
                              .fromReadRequest(NAMESPACE, sourceRequest),
                          sourceOutput.value))
              .isEqualTo(source);
          var versionOutput =
              new FixedReadObserver<
                  net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateResponse>();
          new net.firedevops.firemud.gamedesign.service.impl
                  .RunOwnedFixtureAuthoredWorldVersionStateGrpcService(
                  authorization, transactionManager, authoredWorldSourceRepository, NAMESPACE)
              .getAuthoredWorldVersionState(versionRequest, versionOutput);
          assertThat(versionOutput.failure).isNull();
          assertThat(versionOutput.completed).isTrue();
          var actual =
              net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateGrpcCodec
                  .fromResponse(
                      net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateGrpcCodec
                          .fromRequest(versionRequest),
                      versionOutput.value);
          assertThat(actual.sourceEvidence()).isEqualTo(source);
          assertThat(actual.versionStateEpoch())
              .isEqualTo(receipt.launchReadEvidence().versionState().getVersionStateEpoch());
        });
    org.mockito.Mockito.verify(launchDescriptorService, org.mockito.Mockito.never())
        .resolveLaunchDescriptor(any());
    assertThat(fixedReadOwnerRows()).isEqualTo(before);
    assertThat(Files.readAllBytes(fixture.receiptPath())).containsExactly(receiptBefore);
    org.mockito.Mockito.clearInvocations(authoredWorldSourceRepository, launchDescriptorService);
    peerContext.run(
        () -> {
          assertThatThrownBy(
                  () ->
                      authorization.authorizeSource(
                          sourceRequest.toBuilder()
                              .setOperationId(UUID.randomUUID().toString())
                              .build()))
              .isInstanceOf(SecurityException.class);
          assertThatThrownBy(
                  () ->
                      authorization.authorizeSource(
                          sourceRequest.toBuilder()
                              .setCanonicalTenantId(UUID.randomUUID().toString())
                              .build()))
              .isInstanceOf(SecurityException.class);
          assertThatThrownBy(
                  () ->
                      authorization.authorizeSource(
                          sourceRequest.toBuilder().setWorldSlug("different-world").build()))
              .isInstanceOf(SecurityException.class);
          assertThatThrownBy(
                  () ->
                      authorization.authorizeVersion(
                          versionRequest.toBuilder().setTargetNamespace("other").build()))
              .isInstanceOf(SecurityException.class);
          assertThatThrownBy(
                  () ->
                      authorization.authorizeVersion(
                          versionRequest.toBuilder()
                              .setVersionId(versionRequest.getVersionId() + 1)
                              .build()))
              .isInstanceOf(SecurityException.class);
          assertThatThrownBy(
                  () ->
                      authorization.authorizeVersion(
                          versionRequest.toBuilder()
                              .setExpectedSourceEvidenceDigest("sha256:" + "f".repeat(64))
                              .build()))
              .isInstanceOf(SecurityException.class);
        });
    org.mockito.Mockito.verifyNoInteractions(
        authoredWorldSourceRepository, launchDescriptorService);
    assertThat(fixedReadOwnerRows()).isEqualTo(before);
  }

  @Test
  void fixedWorldClientsReadActualProducerOwnersOverMutualTlsWithoutOwnerWrites(
      @TempDir Path testDirectory) throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    configureSeeder(fixture);
    seeder.run(new DefaultApplicationArguments(new String[] {}));
    var capability =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .parseCapability(Files.readAllBytes(fixture.capabilityPath()));
    var receipt =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .parseSourceReceipt(Files.readAllBytes(fixture.receiptPath()), capability);
    var authorization =
        new RunOwnedFixtureOwnerReadAuthorization(
            fixtureReadback,
            fixturePolicy,
            NAMESPACE,
            fixture.runId(),
            fixture.composeProjectName(),
            fixture.capabilityPath().toString(),
            fixture.receiptPath().toString(),
            fixture.gameSessionCertificatePath().toString(),
            fixture.worldManagementCertificatePath().toString(),
            fixture.caCertificatePath().toString());
    var source = receipt.sourceEvidence();
    var sourceRequest =
        new AuthoredWorldSourceGrpcCodec.ReadRequest(
            NAMESPACE,
            UUID.randomUUID(),
            source.operationId(),
            source.canonicalTenantId(),
            source.worldSlug());
    var versionRequest =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            NAMESPACE,
            UUID.randomUUID(),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            receipt.launchReadEvidence().versionState().getVersionId());
    var serverLeaf =
        issueFixedReadLeaf(
            fixture, "game-design-server", "spiffe://firemud/ns/dev/sa/game-design-service", true);
    var otherWorldLeaf =
        issueFixedReadLeaf(fixture, "different-world-client", WORLD_MANAGEMENT_URI_SAN, false);
    var auth =
        new AuthTokenInterceptor(
            new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L),
            Set.of(
                AuthoredWorldSourceReadServiceGrpc.getResolveAuthoredWorldSourceMethod()
                    .getFullMethodName(),
                AuthoredWorldVersionStateServiceGrpc.getGetAuthoredWorldVersionStateMethod()
                    .getFullMethodName()));
    Map<String, String> originalRows = fixedReadOwnerRows();
    byte[] originalReceipt = Files.readAllBytes(fixture.receiptPath());
    // Actual handlers/interceptors on a loopback socket; Spring registration and Compose are
    // separate proofs.
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverLeaf.certificate().toFile(), serverLeaf.privateKey().toFile()))
                    .trustManager(fixture.caCertificatePath().toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    new RunOwnedFixtureAuthoredWorldSourceReadGrpcService(
                        authorization, authoredWorldSourceRepository, NAMESPACE),
                    new GrpcPeerIdentityInterceptor(),
                    auth))
            .addService(
                ServerInterceptors.intercept(
                    new RunOwnedFixtureAuthoredWorldVersionStateGrpcService(
                        authorization,
                        transactionManager,
                        authoredWorldSourceRepository,
                        NAMESPACE),
                    new GrpcPeerIdentityInterceptor(),
                    auth))
            .build()
            .start();
    try {
      org.mockito.Mockito.clearInvocations(launchDescriptorService, fixturePolicy);
      withFixedReadClients(
          server,
          fixture,
          fixture.worldManagementCertificatePath(),
          fixture.worldManagementCertificatePath().resolveSibling("world-management.key"),
          (sourceClient, versionClient) -> {
            assertThat(sourceClient.read(sourceRequest)).isEqualTo(source);
            var actual = versionClient.read(versionRequest);
            assertThat(actual.request()).isEqualTo(versionRequest);
            assertThat(actual.sourceEvidence()).isEqualTo(source);
            assertThat(actual.canonicalVersionId())
                .isEqualTo(
                    UUID.fromString(
                        receipt
                            .launchReadEvidence()
                            .publishedReleaseBundle()
                            .getCanonicalVersionId()));
            assertThat(actual.versionState())
                .isEqualTo(receipt.launchReadEvidence().versionState().getVersionState());
            assertThat(actual.versionStateEpoch())
                .isEqualTo(receipt.launchReadEvidence().versionState().getVersionStateEpoch());
          });
      org.mockito.Mockito.verify(launchDescriptorService, org.mockito.Mockito.never())
          .resolveLaunchDescriptor(any());
      assertThat(org.mockito.Mockito.mockingDetails(fixturePolicy).getInvocations())
          .extracting(invocation -> invocation.getMethod().getName())
          .contains("readExisting")
          .doesNotContain("sealAndRead");
      assertThat(fixedReadOwnerRows()).isEqualTo(originalRows);
      assertThat(Files.readAllBytes(fixture.receiptPath())).containsExactly(originalReceipt);
      org.mockito.Mockito.clearInvocations(
          authoredWorldSourceRepository, launchDescriptorService, fixtureReadback, fixturePolicy);
      for (var leaf :
          java.util.List.of(
              otherWorldLeaf,
              new FixedReadLeaf(
                  fixture.gameSessionCertificatePath(),
                  fixture.gameSessionCertificatePath().resolveSibling("game-session.key")))) {
        withFixedReadClients(
            server,
            fixture,
            leaf.certificate(),
            leaf.privateKey(),
            (sourceClient, versionClient) -> {
              assertSocketReadDenied(() -> sourceClient.read(sourceRequest));
              assertSocketReadDenied(() -> versionClient.read(versionRequest));
            });
      }
      withFixedReadClients(
          server,
          fixture,
          fixture.worldManagementCertificatePath(),
          fixture.worldManagementCertificatePath().resolveSibling("world-management.key"),
          (sourceClient, versionClient) -> {
            assertSocketReadDenied(
                () ->
                    sourceClient.read(
                        new AuthoredWorldSourceGrpcCodec.ReadRequest(
                            NAMESPACE,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            source.canonicalTenantId(),
                            source.worldSlug())));
            assertSocketReadDenied(
                () ->
                    sourceClient.read(
                        new AuthoredWorldSourceGrpcCodec.ReadRequest(
                            NAMESPACE,
                            UUID.randomUUID(),
                            source.operationId(),
                            UUID.randomUUID(),
                            source.worldSlug())));
            assertSocketReadDenied(
                () ->
                    sourceClient.read(
                        new AuthoredWorldSourceGrpcCodec.ReadRequest(
                            NAMESPACE,
                            UUID.randomUUID(),
                            source.operationId(),
                            source.canonicalTenantId(),
                            "different-world")));
            assertSocketReadDenied(
                () ->
                    versionClient.read(
                        new AuthoredWorldVersionStateEvidence.Request(
                            1,
                            NAMESPACE,
                            UUID.randomUUID(),
                            source.canonicalTenantId(),
                            source.worldSlug(),
                            source.operationId(),
                            source.evidenceDigest(),
                            versionRequest.versionId() + 1)));
            assertSocketReadDenied(
                () ->
                    versionClient.read(
                        new AuthoredWorldVersionStateEvidence.Request(
                            1,
                            NAMESPACE,
                            UUID.randomUUID(),
                            source.canonicalTenantId(),
                            source.worldSlug(),
                            source.operationId(),
                            "sha256:" + "f".repeat(64),
                            versionRequest.versionId())));
          });
      org.mockito.Mockito.verifyNoInteractions(
          authoredWorldSourceRepository, launchDescriptorService, fixtureReadback, fixturePolicy);
      assertThat(fixedReadOwnerRows()).isEqualTo(originalRows);
      assertThat(Files.readAllBytes(fixture.receiptPath())).containsExactly(originalReceipt);
      // No physical missing-header case: immutable producer seal precedes receipt publication.
      // Controlled missing-policy denial is covered separately without forging/deleting owner
      // evidence.
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void assertSocketReadDenied(Runnable read) {
    assertThatThrownBy(read::run)
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  private void withFixedReadClients(
      Server server,
      Fixture fixture,
      Path certificate,
      Path privateKey,
      BiConsumer<AuthoredWorldSourceClient, AuthoredWorldVersionStateClient> reads)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("127.0.0.1:" + server.getPort());
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate.toString());
    tls.setPrivateKey(privateKey.toString());
    tls.setCaCert(fixture.caCertificatePath().toString());
    var channels = new CopyOnWriteArrayList<ManagedChannel>();
    var factory =
        new GrpcChannelFactory() {
          @Override
          public ManagedChannel buildChannel(
              String target,
              int port,
              CommonGrpcClientProperties properties,
              boolean keepAlive,
              ResolvedGrpcTlsMaterial material)
              throws javax.net.ssl.SSLException {
            ManagedChannel channel =
                super.buildChannel(target, port, properties, keepAlive, material);
            channels.add(channel);
            return channel;
          }
        };
    try (var sourceClient = new AuthoredWorldSourceClient(endpoints, tls, factory, NAMESPACE);
        var versionClient =
            new AuthoredWorldVersionStateClient(endpoints, tls, factory, NAMESPACE)) {
      sourceClient.init();
      versionClient.init();
      reads.accept(sourceClient, versionClient);
    } finally {
      for (ManagedChannel channel : channels) {
        channel.shutdownNow();
        assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private FixedReadLeaf issueFixedReadLeaf(Fixture fixture, String name, String uri, boolean server)
      throws Exception {
    Path directory = java.util.Objects.requireNonNull(fixture.caCertificatePath().getParent());
    Path key = directory.resolve(name + ".key");
    Path csr = directory.resolve(name + ".csr");
    Path certificate = directory.resolve(name + ".crt");
    Path extensions = directory.resolve(name + ".ext");
    runOpenSsl(
        "req",
        "-new",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-sha256",
        "-keyout",
        key.toString(),
        "-out",
        csr.toString(),
        "-subj",
        "/CN=" + name);
    Files.writeString(
        extensions,
        "basicConstraints=critical,CA:FALSE\n"
            + "keyUsage=critical,digitalSignature,keyEncipherment\n"
            + "extendedKeyUsage="
            + (server ? "serverAuth" : "clientAuth")
            + "\n"
            + "subjectAltName=URI:"
            + uri
            + (server
                ? ",DNS:game-design-service,DNS:game-design-service.dev,DNS:game-design-service.dev.svc,DNS:game-design-service.dev.svc.cluster.local,DNS:localhost,IP:127.0.0.1"
                : ",DNS:world-management-service")
            + "\n");
    runOpenSsl(
        "x509",
        "-req",
        "-in",
        csr.toString(),
        "-CA",
        fixture.caCertificatePath().toString(),
        "-CAkey",
        directory.resolve("ca.key").toString(),
        "-CAcreateserial",
        "-out",
        certificate.toString(),
        "-days",
        "2",
        "-sha256",
        "-extfile",
        extensions.toString());
    Files.setPosixFilePermissions(
        certificate,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    awaitFixtureCertificatesValid(certificate);
    return new FixedReadLeaf(certificate, key);
  }

  private record FixedReadLeaf(Path certificate, Path privateKey) {}

  @Test
  void actualProducerStartingAndPreparingOwnersComposeOverThreeMutualTlsSockets(
      @TempDir Path testDirectory) throws Exception {
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
        .isEqualTo(75L);
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
        .load()
        .migrate();
    return new ComposedOwnerDatabase(
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES),
        new DataSourceTransactionManager(dataSource));
  }

  private record ComposedOwnerDatabase(DSLContext dsl, PlatformTransactionManager transactions) {}

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

  private Map<String, String> fixedReadOwnerRows() {
    Map<String, String> rows = new LinkedHashMap<>();
    for (String table :
        Set.of(
            "game_tenant_creation_operations",
            "game_design_authored_world_source_operations",
            "game_design_authored_world_source_deliveries",
            "game",
            "game_templates",
            "version",
            "version_asset_artifact",
            "published_release_bundle",
            "launch_descriptor",
            "game_design_published_realm_policy_set",
            "game_design_realm_policy_capture",
            "game_design_publication_operation",
            "game_design_published_realm_policy")) {
      rows.put(
          table,
          dsl.fetch(
                  "SELECT row_to_json(t)::text AS bytes, xmin::text AS row_version FROM "
                      + table
                      + " t ORDER BY row_to_json(t)::text")
              .formatJSON());
    }
    return rows;
  }

  private static final class FixedReadObserver<T> implements io.grpc.stub.StreamObserver<T> {
    private T value;
    private Status.Code failure;
    private boolean completed;

    @Override
    public void onNext(T response) {
      value = response;
    }

    @Override
    public void onError(Throwable error) {
      failure = Status.fromThrowable(error).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  @Test
  void fixtureRefusesExistingUnownedDemoInsteadOfBackfillingCreation(@TempDir Path testDirectory)
      throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    flyway.clean();
    flyway.migrate();
    ReflectionTestUtils.setField(seeder, "initialAdmissionFixtureEnabled", false);
    seeder.run(new DefaultApplicationArguments(new String[] {}));
    var existing = gameRepository.findByTenantId("1");
    configureSeeder(fixture);
    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .hasMessageContaining("already belongs to a game row");
    assertThat(gameRepository.findByTenantId("1").getId()).isEqualTo(existing.getId());
    assertThat(creationRepository.read(fixture.operationId(), NAMESPACE)).isEmpty();
    assertThat(fixture.receiptPath()).doesNotExist();
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS)).isZero();
  }

  @Test
  void publishesExactOwnerReceiptOnlyAfterCommitAndLeavesNoReceiptOnRollback(
      @TempDir Path testDirectory) throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    configureSeeder(fixture);
    Path receiptPath = fixture.receiptPath();

    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              seeder.run(new DefaultApplicationArguments(new String[] {}));
              assertThat(receiptPath).doesNotExist();
              status.setRollbackOnly();
              return null;
            });

    assertThat(receiptPath).doesNotExist();
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(
            launchDescriptorRepository.findByPrivateRequest("1", fixture.operationId().toString()))
        .isEmpty();

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    assertThat(receiptPath).isRegularFile();
    assertThat(Files.isSymbolicLink(receiptPath)).isFalse();
    assertThat(Files.size(receiptPath)).isBetween(1L, 16L * 1024L);
    assertThat(Files.getPosixFilePermissions(receiptPath))
        .containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);
    JsonNode envelope = objectMapper.readTree(Files.readAllBytes(receiptPath));
    assertThat(envelope.size()).isEqualTo(14);
    assertThat(fieldNames(envelope))
        .isEqualTo(
            Set.of(
                "schema",
                "runId",
                "composeProjectName",
                "fixtureOperationId",
                "ownerService",
                "gameSessionUriSan",
                "gameSessionLeafSha256",
                "worldManagementUriSan",
                "worldManagementLeafSha256",
                "caCertificateSha256",
                "sourceEvidence",
                "creationEvidence",
                "fixturePolicySeal",
                "launchReadEvidence"));
    assertThat(envelope.get("schema").textValue())
        .isEqualTo("firemud.run-owned-authored-world-source-receipt.v1");
    assertThat(envelope.get("runId").textValue()).isEqualTo(fixture.runId());
    assertThat(envelope.get("composeProjectName").textValue())
        .isEqualTo(fixture.composeProjectName());
    assertThat(envelope.get("fixtureOperationId").textValue())
        .isEqualTo(fixture.operationId().toString());
    assertThat(envelope.get("ownerService").textValue()).isEqualTo("game-design-service");
    assertThat(envelope.get("gameSessionUriSan").textValue()).isEqualTo(SESSION_URI_SAN);
    assertThat(envelope.get("gameSessionLeafSha256").textValue())
        .isEqualTo(fixture.gameSessionLeafSha256());
    assertThat(envelope.get("worldManagementUriSan").textValue())
        .isEqualTo(WORLD_MANAGEMENT_URI_SAN);
    assertThat(envelope.get("worldManagementLeafSha256").textValue())
        .isEqualTo(fixture.worldManagementLeafSha256());
    assertThat(envelope.get("caCertificateSha256").textValue())
        .isEqualTo(fixture.caCertificateSha256());
    var creation =
        objectMapper.treeToValue(
            envelope.get("creationEvidence"),
            net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence.class);
    assertThat(creationRepository.read(fixture.operationId(), NAMESPACE)).contains(creation);
    var policySeal =
        objectMapper.treeToValue(
            envelope.get("fixturePolicySeal"),
            net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
                .FixturePolicySeal.class);
    var storedPolicy =
        dsl.fetchSingle(
            "SELECT * FROM game_design_published_realm_policy_set " + "WHERE canonical_tenant_id=?",
            creation.canonicalTenantId());
    assertThat(policySeal.operationId()).isEqualTo(fixture.operationId());
    assertThat(storedPolicy.get("fixture_operation_id", UUID.class))
        .isEqualTo(policySeal.operationId());
    assertThat(storedPolicy.get("fixture_request_digest", String.class))
        .isEqualTo(policySeal.requestDigest());
    assertThat(storedPolicy.get("fixture_evidence_digest", String.class))
        .isEqualTo(policySeal.evidenceDigest());
    for (String humanField :
        Set.of(
            "source_commit_id",
            "source_epoch",
            "target_proof_json",
            "operation_bytes",
            "capture_bytes",
            "terminal_evidence_bytes",
            "release_content_bytes",
            "published_release_bundle_ref",
            "published_release_bundle_digest",
            "publish_workflow_id",
            "manifest_hash",
            "publication_version_state_epoch",
            "policy_count",
            "policy_set_digest")) {
      assertThat(storedPolicy.get(humanField)).as(humanField).isNull();
    }
    for (String humanTable :
        Set.of(
            "game_design_realm_policy_capture",
            "game_design_publication_operation",
            "game_design_published_realm_policy")) {
      assertThat(dsl.fetchCount(DSL.table(DSL.name(humanTable)))).as(humanTable).isZero();
    }
    var versionForPolicy = versionRepository.findByTenantIdAndVersionNumber("1", 1).orElseThrow();
    var fixtureTarget =
        new net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof(
            creation.canonicalTenantId(),
            versionForPolicy.getCanonicalVersionId(),
            versionForPolicy.getId(),
            "1",
            creation.sourceGameRowId(),
            creation.sourceGameTenantKey(),
            creation.provenanceKind());
    assertThatThrownBy(
            () ->
                new net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationRepository(
                        dsl)
                    .readPublishedSet(fixtureTarget))
        .hasMessageContaining("FIXTURE_POLICY_IS_NOT_HUMAN_PUBLICATION_EVIDENCE");
    assertThat(
            net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
                .parseFixturePolicyRequestBytes(
                    storedPolicy.get("fixture_request_bytes", byte[].class))
                .creationEvidence())
        .isEqualTo(creation);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM game_design_published_realm_policy "
                        + "WHERE canonical_tenant_id=?",
                    creation.canonicalTenantId())
                .get(0, Long.class))
        .isZero();
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_design_published_realm_policy_set "
                        + "SET fixture_request_digest=? WHERE canonical_tenant_id=?",
                    "sha256:" + "f".repeat(64),
                    creation.canonicalTenantId()))
        .hasMessageContaining("fixture policy seal is immutable");
    JsonNode sourceJson = envelope.get("sourceEvidence");
    assertThat(sourceJson.size()).isEqualTo(13);
    assertThat(fieldNames(sourceJson))
        .isEqualTo(
            Set.of(
                "schemaVersion",
                "targetNamespace",
                "registrationRequestId",
                "operationId",
                "requestDigest",
                "canonicalTenantId",
                "tenantSlug",
                "worldSlug",
                "worldDisplayName",
                "sourceGameRowId",
                "sourceGameTenantKey",
                "provenanceKind",
                "evidenceDigest"));
    AuthoredWorldSourceEvidence actual =
        objectMapper.treeToValue(sourceJson, AuthoredWorldSourceEvidence.class);
    UUID sourceOperationId =
        dsl.select(SOURCE_OPERATION_ID)
            .from(SOURCE_OPERATIONS)
            .where(REGISTRATION_REQUEST_ID.eq(fixture.operationId()))
            .fetchOne(SOURCE_OPERATION_ID);
    assertThat(sourceOperationId).isNotNull();
    AuthoredWorldSourceEvidence committed =
        authoredWorldSourceRepository
            .read(
                sourceOperationId,
                gameRepository.findByTenantId("1").getCanonicalTenantId(),
                "demo",
                NAMESPACE)
            .orElseThrow();
    assertThat(actual).isEqualTo(committed);
    assertThat(actual.registrationRequestId()).isEqualTo(fixture.operationId());
    assertThat(actual.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(actual.tenantSlug()).isEqualTo("demo");
    assertThat(actual.worldSlug()).isEqualTo("demo");
    assertThat(actual.worldDisplayName()).isEqualTo("Demo World");
    assertThat(actual.sourceGameTenantKey()).isEqualTo("1");
    assertThat(actual.provenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isOne();
    assertThat(
            dsl.select(DELIVERY_WORLD_OPERATION_ID)
                .from(SOURCE_DELIVERIES)
                .where(REGISTRATION_REQUEST_ID.eq(fixture.operationId()))
                .fetchOne(DELIVERY_WORLD_OPERATION_ID))
        .isNull();

    JsonNode launchJson = envelope.get("launchReadEvidence");
    assertThat(launchJson.size()).isEqualTo(6);
    assertThat(fieldNames(launchJson))
        .isEqualTo(
            Set.of(
                "schema",
                "gameTemplateId",
                "publishedReleaseBundleBase64",
                "versionAssetArtifactStateBase64",
                "versionStateBase64",
                "launchDescriptorBase64"));
    assertThat(launchJson.get("schema").textValue())
        .isEqualTo("firemud.run-owned-v1-launch-read-evidence.v1");
    var template = templateRepository.findByTenantIdAndName("1", "Default Template").orElseThrow();
    var version = versionRepository.findByTenantIdAndVersionNumber("1", 1).orElseThrow();
    var artifact =
        artifactRepository.findByTenantIdAndVersionId("1", version.getId()).orElseThrow();
    assertThat(launchJson.get("gameTemplateId").longValue())
        .isEqualTo(template.getId())
        .isPositive();
    PublishedReleaseBundleDto bundleDto =
        versionService.getPublishedReleaseBundle("1", version.getId());
    VersionAssetArtifactStateDto artifactDto = artifactService.getState("1", version.getId());
    VersionStateDto versionDto = versionService.getVersionState("1", version.getId());
    String bundleBase64 = launchJson.get("publishedReleaseBundleBase64").textValue();
    byte[] bundleBytes = Base64.getDecoder().decode(bundleBase64);
    assertThat(Base64.getEncoder().encodeToString(bundleBytes)).isEqualTo(bundleBase64);
    PublishedReleaseBundle bundleProof = PublishedReleaseBundle.parseFrom(bundleBytes);
    assertThat(bundleProof).isEqualTo(GameDesignLaunchReadProjectionMapper.toProto(bundleDto));
    assertThat(bundleProof.getId()).isPositive();
    assertThat(bundleProof.getVersionId()).isEqualTo(version.getId());
    assertThat(bundleProof.getPublishedReleaseBundleRef()).isNotBlank();
    assertThat(bundleProof.getCanonicalTenantId()).isEqualTo(actual.canonicalTenantId().toString());
    assertThat(bundleProof.getCanonicalVersionId())
        .isEqualTo(version.getCanonicalVersionId().toString());
    assertThat(bundleProof.getWorkflowRunId()).isEmpty();
    assertThat(bundleProof.getWorkflowStatus()).isEmpty();
    assertThat(bundleProof.getWorkflowFamily()).isEmpty();
    assertThat(bundleProof.getUnknownFields().asMap()).isEmpty();
    bundleProof
        .getParticipantDigestsList()
        .forEach(digest -> assertThat(digest.getUnknownFields().asMap()).isEmpty());
    bundleProof
        .getArtifactDigestsList()
        .forEach(digest -> assertThat(digest.getUnknownFields().asMap()).isEmpty());
    String artifactBase64 = launchJson.get("versionAssetArtifactStateBase64").textValue();
    byte[] artifactBytes = Base64.getDecoder().decode(artifactBase64);
    assertThat(Base64.getEncoder().encodeToString(artifactBytes)).isEqualTo(artifactBase64);
    VersionAssetArtifactState artifactProof = VersionAssetArtifactState.parseFrom(artifactBytes);
    assertThat(artifactProof).isEqualTo(GameDesignLaunchReadProjectionMapper.toProto(artifactDto));
    assertThat(artifactProof.getVersionId()).isEqualTo(version.getId());
    assertThat(artifactProof.getStateEpoch()).isEqualTo(artifact.getStateEpoch());
    assertThat(artifactProof.getManifestHash()).isEqualTo(bundleDto.manifestHash());
    assertThat(artifactProof.getUnknownFields().asMap()).isEmpty();
    String versionBase64 = launchJson.get("versionStateBase64").textValue();
    byte[] versionBytes = Base64.getDecoder().decode(versionBase64);
    assertThat(Base64.getEncoder().encodeToString(versionBytes)).isEqualTo(versionBase64);
    VersionStateSnapshot versionProof = VersionStateSnapshot.parseFrom(versionBytes);
    assertThat(versionProof).isEqualTo(GameDesignLaunchReadProjectionMapper.toProto(versionDto));
    assertThat(versionProof.getVersionId()).isEqualTo(version.getId());
    assertThat(versionProof.getVersionStateEpoch()).isEqualTo(version.getVersionStateEpoch());
    assertThat(versionProof.getUnknownFields().asMap()).isEmpty();

    var persistedDescriptor =
        launchDescriptorRepository
            .findBoundByRequest(
                NAMESPACE, actual.canonicalTenantId(), fixture.operationId().toString())
            .orElseThrow();
    assertThat(persistedDescriptor.getOutcomeStatus()).isEqualTo("SUCCESS");
    assertThat(persistedDescriptor.getAuthoredWorldSourceEvidenceDigest())
        .isEqualTo(actual.evidenceDigest());
    ResolvedLaunchDescriptorDto descriptorDto =
        launchDescriptorService.getLaunchDescriptor(
            UUID.randomUUID(),
            actual.canonicalTenantId(),
            actual.worldSlug(),
            fixture.operationId().toString(),
            persistedDescriptor.getRequestDigest(),
            persistedDescriptor.getResultDigest());
    assertThat(descriptorDto.authoredWorldBinding().request().controlPlaneRequestId())
        .isEqualTo(fixture.operationId().toString());
    assertThat(descriptorDto.authoredWorldBinding().authoredWorldSourceEvidenceDigest())
        .isEqualTo(actual.evidenceDigest());
    String descriptorBase64 = launchJson.get("launchDescriptorBase64").textValue();
    byte[] descriptorBytes = Base64.getDecoder().decode(descriptorBase64);
    assertThat(Base64.getEncoder().encodeToString(descriptorBytes)).isEqualTo(descriptorBase64);
    LaunchDescriptor descriptorProof = LaunchDescriptor.parseFrom(descriptorBytes);
    assertThat(descriptorProof)
        .isEqualTo(
            AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(
                descriptorDto.authoredWorldBinding()));
    assertThat(descriptorProof.getAuthoredWorldBinding().getRequestDigest())
        .isEqualTo(persistedDescriptor.getRequestDigest());
    assertThat(descriptorProof.getAuthoredWorldBinding().getResultDigest())
        .isEqualTo(persistedDescriptor.getResultDigest());
    assertThat(descriptorProof.getVersionId()).isEqualTo(version.getId());
    assertThat(descriptorProof.getVersionStateEpoch()).isEqualTo(version.getVersionStateEpoch());
    assertThat(descriptorProof.getReleaseBundleId()).isEqualTo(bundleProof.getId());
    assertThat(descriptorProof.getPublishedReleaseBundleRef())
        .isEqualTo(bundleProof.getPublishedReleaseBundleRef());
    assertThat(descriptorProof.getUnknownFields().asMap()).isEmpty();
    assertThat(descriptorProof.getAuthoredWorldBinding().getUnknownFields().asMap()).isEmpty();

    byte[] originalBundleBytes = readPublishedReleaseBundleBytes();
    JsonNode bundleJson = objectMapper.readTree(originalBundleBytes);
    assertThat(bundleJson.get("id").asLong()).isPositive();
    assertThat(bundleJson.get("published_release_bundle_ref").textValue()).isNotBlank();
    byte[] originalReceipt = Files.readAllBytes(receiptPath);
    var originalVersion = version;
    var originalTemplate = template;
    var originalArtifact = artifact;
    seeder.run(new DefaultApplicationArguments(new String[] {}));
    assertThat(Files.readAllBytes(receiptPath)).isEqualTo(originalReceipt);
    assertThat(readPublishedReleaseBundleBytes()).containsExactly(originalBundleBytes);
    assertThat(versionRepository.findByTenantIdAndId("1", originalVersion.getId()).orElseThrow())
        .isEqualTo(originalVersion);
    assertThat(templateRepository.findByTenantIdAndId("1", originalTemplate.getId()).orElseThrow())
        .isEqualTo(originalTemplate);
    assertThat(
            artifactRepository
                .findByTenantIdAndVersionId("1", originalVersion.getId())
                .orElseThrow())
        .isEqualTo(originalArtifact);
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isOne();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isOne();
    assertThat(
            launchDescriptorRepository.findByPrivateRequest("1", fixture.operationId().toString()))
        .isPresent();
  }

  @Test
  void refusesReceiptWhenVersionEpochChangesAfterCommitBeforeOwnerReadback(
      @TempDir Path testDirectory) throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    configureSeeder(fixture);
    var existingVersion = versionRepository.findByTenantIdAndVersionNumber("1", 1).orElseThrow();
    String tenantId = existingVersion.getTenantId();
    Long versionId = existingVersion.getId();
    long originalEpoch = existingVersion.getVersionStateEpoch();
    long changedEpoch = originalEpoch + 1L;
    TransactionTemplate versionMutation = new TransactionTemplate(transactionManager);
    versionMutation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    TransactionTemplate registration = new TransactionTemplate(transactionManager);

    try {
      assertThatThrownBy(
              () ->
                  registration.execute(
                      status -> {
                        TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                              @Override
                              public int getOrder() {
                                return Ordered.HIGHEST_PRECEDENCE;
                              }

                              @Override
                              public void afterCommit() {
                                writeVersionStateEpoch(
                                    versionMutation, tenantId, versionId, changedEpoch);
                              }
                            });
                        seeder.run(new DefaultApplicationArguments(new String[] {}));
                        return null;
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Run-owned published version does not match its game source");

      assertThat(fixture.receiptPath()).doesNotExist();
      assertThat(
              launchDescriptorRepository.findByPrivateRequest(
                  "1", fixture.operationId().toString()))
          .isEmpty();
      var changedVersion = versionRepository.findByTenantIdAndId(tenantId, versionId).orElseThrow();
      assertThat(changedVersion.getVersionStateEpoch()).isEqualTo(changedEpoch);
    } finally {
      writeVersionStateEpoch(versionMutation, tenantId, versionId, originalEpoch);
    }

    var restoredVersion = versionRepository.findByTenantIdAndId(tenantId, versionId).orElseThrow();
    assertThat(restoredVersion.getVersionStateEpoch()).isEqualTo(originalEpoch);
  }

  @Test
  void refusesReceiptWhenExactDescriptorReadbackChanges(@TempDir Path testDirectory)
      throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    configureSeeder(fixture);
    doAnswer(
            invocation -> {
              ResolvedLaunchDescriptorDto actual =
                  (ResolvedLaunchDescriptorDto) invocation.callRealMethod();
              return new ResolvedLaunchDescriptorDto(
                  actual.launchDescriptorId(),
                  actual.canonicalTenantId(),
                  actual.gameTemplateId(),
                  actual.controlPlaneRequestId(),
                  actual.versionId(),
                  actual.scriptPatchVersion(),
                  actual.runtimeFlagsJson(),
                  actual.generationConfigRevision(),
                  actual.versionStateEpoch(),
                  actual.releaseBundleId(),
                  actual.publishedReleaseBundleRef() + "-changed",
                  actual.remapSetId(),
                  actual.authoredWorldBinding());
            })
        .when(launchDescriptorService)
        .getLaunchDescriptor(
            any(UUID.class), any(UUID.class), anyString(), anyString(), anyString(), anyString());

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("readback differs from its resolved result");

    assertThat(fixture.receiptPath()).doesNotExist();
    var source = gameRepository.findByTenantId("1");
    assertThat(
            launchDescriptorRepository.findBoundByRequest(
                NAMESPACE, source.getCanonicalTenantId(), fixture.operationId().toString()))
        .isPresent();
  }

  @Test
  void refusesReceiptWhenSourceChangesAfterDescriptorPublicationBeforeFinalReadback(
      @TempDir Path testDirectory) throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    configureSeeder(fixture);
    AtomicInteger committedSourceReads = new AtomicInteger();
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Optional<AuthoredWorldSourceEvidence> actual =
                  (Optional<AuthoredWorldSourceEvidence>) invocation.callRealMethod();
              if (committedSourceReads.incrementAndGet() == 4) {
                return actual.map(TestDataSeederIntegrationTest.this::changedSourceEvidence);
              }
              return actual;
            })
        .when(authoredWorldSourceRepository)
        .read(any(UUID.class), any(UUID.class), anyString(), anyString());

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Committed authored-world source readback differs from the registration result");

    assertThat(committedSourceReads).hasValue(4);
    assertThat(fixture.receiptPath()).doesNotExist();
    var source = gameRepository.findByTenantId("1");
    assertThat(
            launchDescriptorRepository.findBoundByRequest(
                NAMESPACE, source.getCanonicalTenantId(), fixture.operationId().toString()))
        .isPresent();
  }

  @Test
  void rejectsWrongGameSessionCertificatePinBeforeOwnerRegistration(@TempDir Path testDirectory)
      throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    String capability = Files.readString(fixture.capabilityPath());
    String wrongLeafDigest = "0".repeat(64);
    assertThat(wrongLeafDigest).isNotEqualTo(fixture.gameSessionLeafSha256());
    Files.setPosixFilePermissions(
        fixture.capabilityPath(),
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    var changedCapability =
        (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(capability);
    changedCapability.put("gameSessionLeafSha256", wrongLeafDigest);
    UUID changedOperationId =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .fixtureOperationId(
                changedCapability.get("claimDigest").textValue(),
                changedCapability.get("runId").textValue(),
                changedCapability.get("composeProjectName").textValue(),
                wrongLeafDigest,
                changedCapability.get("caCertificateSha256").textValue());
    changedCapability.put("operationId", changedOperationId.toString());
    byte[] changedCapabilityBytes = objectMapper.writeValueAsBytes(changedCapability);
    // The fixed operation is derived from the GS leaf pin. Keep this wire input valid so
    // the negative reaches the actual configured certificate comparison, before any owner write.
    assertThat(
            net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
                .parseCapability(changedCapabilityBytes)
                .operationId())
        .isEqualTo(changedOperationId.toString());
    Files.write(fixture.capabilityPath(), changedCapabilityBytes);
    Files.setPosixFilePermissions(
        fixture.capabilityPath(),
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    configureSeeder(fixture);

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TLS certificates do not match capability pins");
    assertThat(fixture.receiptPath()).doesNotExist();
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(changedOperationId)))
        .isZero();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(changedOperationId)))
        .isZero();
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
  }

  @Test
  void rejectsWrongWorldManagementCertificatePinBeforeOwnerRegistration(@TempDir Path testDirectory)
      throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    String capability = Files.readString(fixture.capabilityPath());
    String wrongLeafDigest = "0".repeat(64);
    assertThat(wrongLeafDigest).isNotEqualTo(fixture.worldManagementLeafSha256());
    Files.setPosixFilePermissions(
        fixture.capabilityPath(),
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    Files.writeString(
        fixture.capabilityPath(),
        capability.replace(fixture.worldManagementLeafSha256(), wrongLeafDigest));
    Files.setPosixFilePermissions(
        fixture.capabilityPath(),
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    configureSeeder(fixture);

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TLS certificates do not match capability pins");
    assertThat(fixture.receiptPath()).doesNotExist();
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(
            launchDescriptorRepository.findByPrivateRequest("1", fixture.operationId().toString()))
        .isEmpty();
  }

  @Test
  void rejectsUnknownCapabilityFieldBeforeOwnerRegistration(@TempDir Path testDirectory)
      throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    String capability = Files.readString(fixture.capabilityPath());
    Files.setPosixFilePermissions(
        fixture.capabilityPath(),
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    Files.writeString(
        fixture.capabilityPath(),
        capability.substring(0, capability.lastIndexOf('}')) + ",\"unexpected\":true}");
    Files.setPosixFilePermissions(
        fixture.capabilityPath(),
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    configureSeeder(fixture);

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Could not read run-owned fixture capability")
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThat(fixture.receiptPath()).doesNotExist();
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isZero();
    assertThat(
            launchDescriptorRepository.findByPrivateRequest("1", fixture.operationId().toString()))
        .isEmpty();
  }

  @Test
  void preservesConflictingReceiptInsteadOfReplacingIt(@TempDir Path testDirectory)
      throws Exception {
    Fixture fixture = prepareFixture(testDirectory);
    byte[] conflict = "existing receipt from another run".getBytes(StandardCharsets.UTF_8);
    Files.write(fixture.receiptPath(), conflict);
    Files.setPosixFilePermissions(
        fixture.receiptPath(),
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    configureSeeder(fixture);

    assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments(new String[] {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with committed owner evidence");
    assertThat(Files.readAllBytes(fixture.receiptPath())).isEqualTo(conflict);
    assertThat(dsl.fetchCount(SOURCE_OPERATIONS, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isOne();
    assertThat(dsl.fetchCount(SOURCE_DELIVERIES, REGISTRATION_REQUEST_ID.eq(fixture.operationId())))
        .isOne();
  }

  private Set<String> fieldNames(JsonNode object) {
    Set<String> fields = new HashSet<>();
    object.propertyNames().forEach(fields::add);
    return fields;
  }

  private byte[] readPublishedReleaseBundleBytes() {
    Record row =
        dsl.fetchOne(
            "select convert_to(row_to_json(release_bundle)::text, 'UTF8') as bundle_bytes "
                + "from published_release_bundle as release_bundle "
                + "where tenant_id = ? and version_id = ?",
            "1",
            1L);
    assertThat(row).isNotNull();
    return row.get("bundle_bytes", byte[].class);
  }

  private void writeVersionStateEpoch(
      TransactionTemplate transaction, String tenantId, Long versionId, long epoch) {
    transaction.executeWithoutResult(
        status -> {
          int updated =
              dsl.execute(
                  "update version set version_state_epoch = ? where tenant_id = ? and id = ?",
                  epoch,
                  tenantId,
                  versionId);
          assertThat(updated).isOne();
        });
  }

  private Fixture prepareFixture(Path testDirectory) throws Exception {
    Path certificateDirectory = Files.createDirectory(testDirectory.resolve("certificates"));
    Path caKey = certificateDirectory.resolve("ca.key");
    Path caCertificate = certificateDirectory.resolve("ca.crt");
    Path sessionKey = certificateDirectory.resolve("game-session.key");
    Path sessionRequest = certificateDirectory.resolve("game-session.csr");
    Path sessionCertificate = certificateDirectory.resolve("game-session.crt");
    Path sessionExtensions = certificateDirectory.resolve("game-session.ext");
    Path worldManagementKey = certificateDirectory.resolve("world-management.key");
    Path worldManagementRequest = certificateDirectory.resolve("world-management.csr");
    Path worldManagementCertificate = certificateDirectory.resolve("world-management.crt");
    Path worldManagementExtensions = certificateDirectory.resolve("world-management.ext");
    runOpenSsl(
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-sha256",
        "-days",
        "2",
        "-keyout",
        caKey.toString(),
        "-out",
        caCertificate.toString(),
        "-subj",
        "/CN=FireMUD test fixture CA",
        "-addext",
        "basicConstraints=critical,CA:TRUE",
        "-addext",
        "keyUsage=critical,keyCertSign,cRLSign");
    runOpenSsl(
        "req",
        "-new",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-sha256",
        "-keyout",
        sessionKey.toString(),
        "-out",
        sessionRequest.toString(),
        "-subj",
        "/CN=game-session-service");
    Files.writeString(
        sessionExtensions,
        "basicConstraints=critical,CA:FALSE\n"
            + "keyUsage=critical,digitalSignature,keyEncipherment\n"
            + "extendedKeyUsage=clientAuth,serverAuth\n"
            + "subjectAltName=URI:"
            + SESSION_URI_SAN
            + ",DNS:game-session-service"
            + ",DNS:game-session-service.dev"
            + ",DNS:game-session-service.dev.svc"
            + ",DNS:game-session-service.dev.svc.cluster.local,DNS:localhost,IP:127.0.0.1"
            + "\n");
    runOpenSsl(
        "x509",
        "-req",
        "-in",
        sessionRequest.toString(),
        "-CA",
        caCertificate.toString(),
        "-CAkey",
        caKey.toString(),
        "-CAcreateserial",
        "-out",
        sessionCertificate.toString(),
        "-days",
        "2",
        "-sha256",
        "-extfile",
        sessionExtensions.toString());
    runOpenSsl(
        "req",
        "-new",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-sha256",
        "-keyout",
        worldManagementKey.toString(),
        "-out",
        worldManagementRequest.toString(),
        "-subj",
        "/CN=world-management-service");
    Files.writeString(
        worldManagementExtensions,
        "basicConstraints=critical,CA:FALSE\n"
            + "keyUsage=critical,digitalSignature,keyEncipherment\n"
            + "extendedKeyUsage=clientAuth,serverAuth\n"
            + "subjectAltName=URI:"
            + WORLD_MANAGEMENT_URI_SAN
            + ",DNS:world-management-service"
            + ",DNS:world-management-service.dev"
            + ",DNS:world-management-service.dev.svc"
            + ",DNS:world-management-service.dev.svc.cluster.local,DNS:localhost,IP:127.0.0.1"
            + "\n");
    runOpenSsl(
        "x509",
        "-req",
        "-in",
        worldManagementRequest.toString(),
        "-CA",
        caCertificate.toString(),
        "-CAkey",
        caKey.toString(),
        "-CAcreateserial",
        "-out",
        worldManagementCertificate.toString(),
        "-days",
        "2",
        "-sha256",
        "-extfile",
        worldManagementExtensions.toString());
    Files.setPosixFilePermissions(
        caCertificate,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    Files.setPosixFilePermissions(
        sessionCertificate,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    Files.setPosixFilePermissions(
        worldManagementCertificate,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));

    String leafDigest = certificateDigest(sessionCertificate);
    String worldManagementLeafDigest = certificateDigest(worldManagementCertificate);
    String caDigest = certificateDigest(caCertificate);
    String runId = "fixture-commit-proof";
    String composeProjectName = "firemud-smoke-fixture-commit-proof";
    String claimDigest = "e".repeat(64);
    UUID operationId =
        net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
            .fixtureOperationId(claimDigest, runId, composeProjectName, leafDigest, caDigest);
    Path capabilityPath = testDirectory.resolve("run-owned-initial-admission-capability.json");
    Map<String, Object> capability = new LinkedHashMap<>();
    capability.put("schema", "firemud.run-owned-initial-admission-fixture.v1");
    capability.put("runId", runId);
    capability.put("composeProjectName", composeProjectName);
    capability.put("claimDigest", claimDigest);
    capability.put("operationId", operationId.toString());
    capability.put("tenantId", 1);
    capability.put(
        "gameTemplateId",
        templateRepository.findByTenantIdAndName("1", "Default Template").orElseThrow().getId());
    capability.put("ownerAccountId", 1);
    capability.put("worldSlug", "demo");
    capability.put("worldDisplayName", "Demo World");
    capability.put("realmSlug", "production");
    capability.put("realmDisplayName", "Live Realm");
    capability.put("visible", true);
    capability.put("publicProductionRealm", true);
    capability.put("requiresCharacterSelection", false);
    capability.put("stateScope", "SHARED");
    capability.put("characterCreationPolicy", "ALLOW_NEW");
    capability.put("gameSessionLeafSha256", leafDigest);
    capability.put("gameSessionUriSan", SESSION_URI_SAN);
    capability.put("worldManagementLeafSha256", worldManagementLeafDigest);
    capability.put("worldManagementUriSan", WORLD_MANAGEMENT_URI_SAN);
    capability.put("caCertificateSha256", caDigest);
    Files.write(capabilityPath, objectMapper.writeValueAsBytes(capability));
    Files.setPosixFilePermissions(
        capabilityPath,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));

    awaitFixtureCertificatesValid(sessionCertificate, worldManagementCertificate, caCertificate);

    Path receiptDirectory =
        Files.createDirectory(testDirectory.resolve("run-owned-authored-world-source"));
    Files.setPosixFilePermissions(
        receiptDirectory,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE));
    Fixture fixture =
        new Fixture(
            operationId,
            runId,
            composeProjectName,
            capabilityPath,
            receiptDirectory.resolve("source-receipt.json"),
            sessionCertificate,
            worldManagementCertificate,
            caCertificate,
            leafDigest,
            worldManagementLeafDigest,
            caDigest);
    configureSeeder(fixture);
    ReflectionTestUtils.invokeMethod(seeder, "loadFixtureCapability");
    // Disposable test schema only: create the real fixture owner from inception, never backfill.
    flyway.clean();
    flyway.migrate();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                creationRepository.createCandidate(
                    NAMESPACE, operationId, "1", "Demo Game", "Seed game"));
    ReflectionTestUtils.setField(seeder, "initialAdmissionFixtureEnabled", false);
    seeder.run(new DefaultApplicationArguments(new String[] {}));
    return fixture;
  }

  private void configureSeeder(Fixture fixture) {
    ReflectionTestUtils.setField(seeder, "initialAdmissionFixtureEnabled", true);
    ReflectionTestUtils.setField(seeder, "fixtureRunId", fixture.runId());
    ReflectionTestUtils.setField(seeder, "fixtureComposeProjectName", fixture.composeProjectName());
    ReflectionTestUtils.setField(
        seeder, "fixtureCapabilityPath", fixture.capabilityPath().toString());
    ReflectionTestUtils.setField(seeder, "fixtureReceiptPath", fixture.receiptPath().toString());
    ReflectionTestUtils.setField(
        seeder, "gameSessionLeafCertPath", fixture.gameSessionCertificatePath().toString());
    ReflectionTestUtils.setField(
        seeder, "worldManagementLeafCertPath", fixture.worldManagementCertificatePath().toString());
    ReflectionTestUtils.setField(seeder, "workloadNamespace", NAMESPACE);
    ReflectionTestUtils.setField(seeder, "caCertPath", fixture.caCertificatePath().toString());
  }

  private String certificateDigest(Path path) throws Exception {
    try (var input = Files.newInputStream(path)) {
      X509Certificate certificate =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }
  }

  private void awaitFixtureCertificatesValid(Path... certificatePaths) throws Exception {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      try {
        for (Path path : certificatePaths) {
          try (var input = Files.newInputStream(path)) {
            X509Certificate certificate =
                (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(input);
            certificate.checkValidity();
          }
        }
        return;
      } catch (CertificateNotYetValidException exception) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
          throw new IllegalStateException(
              "Run-owned fixture certificates did not become valid within 5 seconds", exception);
        }
        TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(10), remainingNanos));
      }
    }
  }

  private AuthoredWorldSourceEvidence changedSourceEvidence(AuthoredWorldSourceEvidence source) {
    String changedWorldSlug = source.worldSlug() + "-changed";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            source.targetNamespace(),
            source.registrationRequestId(),
            source.canonicalTenantId(),
            source.tenantSlug(),
            changedWorldSlug,
            source.worldDisplayName());
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            source.targetNamespace(),
            source.registrationRequestId(),
            source.operationId(),
            requestDigest,
            source.canonicalTenantId(),
            source.tenantSlug(),
            changedWorldSlug,
            source.worldDisplayName(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind());
    return new AuthoredWorldSourceEvidence(
        source.schemaVersion(),
        source.targetNamespace(),
        source.registrationRequestId(),
        source.operationId(),
        requestDigest,
        source.canonicalTenantId(),
        source.tenantSlug(),
        changedWorldSlug,
        source.worldDisplayName(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        evidenceDigest);
  }

  private void runOpenSsl(String... arguments) throws Exception {
    Process process =
        new ProcessBuilder(concat(new String[] {"openssl"}, arguments))
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    if (process.waitFor() != 0) {
      throw new IOException("OpenSSL fixture certificate generation failed: " + output);
    }
  }

  private String[] concat(String[] prefix, String[] suffix) {
    String[] combined = new String[prefix.length + suffix.length];
    System.arraycopy(prefix, 0, combined, 0, prefix.length);
    System.arraycopy(suffix, 0, combined, prefix.length, suffix.length);
    return combined;
  }

  private record Fixture(
      UUID operationId,
      String runId,
      String composeProjectName,
      Path capabilityPath,
      Path receiptPath,
      Path gameSessionCertificatePath,
      Path worldManagementCertificatePath,
      Path caCertificatePath,
      String gameSessionLeafSha256,
      String worldManagementLeafSha256,
      String caCertificateSha256) {}

  @Test
  void freshBaselineSeedAndRestartReuseActualOwnerIdentityAndOpaqueBundle() {
    FoundationFixture fixture = foundationFixture(null);
    fixture.migrate(null);
    fixture.seed();
    var game = fixture.games().findByTenantId("1");
    var version = fixture.versions().findByTenantIdAndVersionNumber("1", 1).orElseThrow();
    var bundle = fixture.bundles().findByTenantIdAndVersionId("1", version.getId()).orElseThrow();
    var artifact =
        fixture.artifacts().findByTenantIdAndVersionId("1", version.getId()).orElseThrow();
    assertThat(version.getCanonicalTenantId()).isEqualTo(game.getCanonicalTenantId());
    assertThat(version.getCanonicalVersionId()).isNotNull().isNotEqualTo(new UUID(0L, 0L));
    assertThat(version.getIdentitySourceGameRowId()).isEqualTo(game.getId());
    assertThat(version.getIdentitySourceGameTenantKey()).isEqualTo(game.getTenantId());
    assertThat(version.getIdentitySourceProvenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(bundle.getCanonicalTenantId()).isEqualTo(game.getCanonicalTenantId());
    assertThat(bundle.getCanonicalVersionId()).isEqualTo(version.getCanonicalVersionId());
    assertThat(bundle.getAttestationSchemaVersion()).isEqualTo("v1");
    assertThat(bundle.getPublishedReleaseBundleRef()).isNotBlank();
    String xmin = fixture.bundleXmin();
    String artifactXmin = fixture.artifactXmin();

    fixture.seed();

    assertThat(fixture.bundleXmin()).isEqualTo(xmin);
    assertThat(fixture.artifactXmin()).isEqualTo(artifactXmin);
    var retainedArtifact =
        fixture.artifacts().findByTenantIdAndVersionId("1", version.getId()).orElseThrow();
    assertThat(retainedArtifact.getId()).isEqualTo(artifact.getId());
    assertThat(retainedArtifact.getStateEpoch()).isEqualTo(artifact.getStateEpoch());
    assertThat(retainedArtifact.getStateEpoch()).isEqualTo(1L);
    assertThat(fixture.bundles().findByTenantIdAndVersionId("1", version.getId()).orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(bundle);
    assertThat(
            fixture
                .versions()
                .findByTenantIdAndVersionNumber("1", 1)
                .orElseThrow()
                .getCanonicalVersionId())
        .isEqualTo(version.getCanonicalVersionId());
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_authored_world_source_operations")))
        .isZero();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_authored_world_source_deliveries")))
        .isZero();
    assertRetainedDescriptorInsertAndRead(fixture, version.getId(), bundle.getId());
    assertThatThrownBy(() -> fixture.bundles().save(bundle)).hasMessageContaining("immutable");
  }

  @Test
  void baselineUpgradeRetainsUnboundVersionBundleAndDescriptorWithoutIdentityBackfill() {
    FoundationFixture fixture = foundationFixture("30");
    fixture
        .dsl()
        .execute(
            "INSERT INTO game (tenant_id, name, description) VALUES ('1', 'Demo Game', 'Seed game')");
    Long versionId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, is_script_only, notes) VALUES ('1', 1, 'PUBLISHED', 1, false, 'Seed published version') RETURNING id")
            .get("id", Long.class);
    Long bundleId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO published_release_bundle (tenant_id, version_id, version_number, attestation_schema_version, publish_workflow_id, manifest_hash, generation_config_revision, required_manifest_asset_keys_json, participant_digests_json, command_definitions_json, script_only) VALUES ('1', ?, 1, 'v1', 'demo-seed-publish', 'demo-manifest-hash', 'genrev:demo', '[]', '[]', '[]', false) RETURNING id",
                versionId)
            .get("id", Long.class);
    fixture.migrate(null);
    String xmin = fixture.bundleXmin();
    fixture.seed();
    var artifact = fixture.artifacts().findByTenantIdAndVersionId("1", versionId).orElseThrow();
    Long artifactId = artifact.getId();
    Long artifactEpoch = artifact.getStateEpoch();
    String artifactXmin = fixture.artifactXmin();

    fixture.seed();

    var version = fixture.versions().findByTenantIdAndId("1", versionId).orElseThrow();
    var bundle = fixture.bundles().findByTenantIdAndVersionId("1", versionId).orElseThrow();
    assertThat(version.getCanonicalVersionId()).isNull();
    assertThat(version.getCanonicalTenantId()).isNull();
    assertThat(bundle.getCanonicalTenantId()).isNull();
    assertThat(bundle.getCanonicalVersionId()).isNull();
    assertThat(bundle.getPublishedReleaseBundleRef()).isNull();
    assertThat(bundle.getId()).isEqualTo(bundleId);
    assertThat(fixture.bundleXmin()).isEqualTo(xmin);
    assertThat(fixture.artifactXmin()).isEqualTo(artifactXmin);
    assertThat(fixture.artifacts().findByTenantIdAndVersionId("1", versionId).orElseThrow())
        .extracting(VersionAssetArtifact::getId, VersionAssetArtifact::getStateEpoch)
        .containsExactly(artifactId, artifactEpoch);
    assertRetainedDescriptorInsertAndRead(fixture, versionId, bundleId);
  }

  @Test
  void retainedBaselineRejectsMismatchedArtifactWithoutChangingItsRow() {
    FoundationFixture fixture = foundationFixture("30");
    fixture
        .dsl()
        .execute(
            "INSERT INTO game (tenant_id, name, description) VALUES ('1', 'Demo Game', 'Seed game')");
    Long versionId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, is_script_only, notes) VALUES ('1', 1, 'PUBLISHED', 1, false, 'Seed published version') RETURNING id")
            .get("id", Long.class);
    fixture
        .dsl()
        .execute(
            "INSERT INTO published_release_bundle (tenant_id, version_id, version_number, attestation_schema_version, publish_workflow_id, manifest_hash, generation_config_revision, required_manifest_asset_keys_json, participant_digests_json, command_definitions_json, script_only) VALUES ('1', ?, 1, 'v1', 'demo-seed-publish', 'demo-manifest-hash', 'genrev:demo', '[]', '[]', '[]', false)",
            versionId);
    fixture.migrate(null);
    fixture.seed();
    fixture
        .dsl()
        .execute(
            "UPDATE version_asset_artifact SET state_epoch = 2 WHERE tenant_id = '1' AND version_id = ?",
            versionId);
    String artifactXmin = fixture.artifactXmin();

    assertThatThrownBy(fixture::seed)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Existing version artifact does not exactly match the demo seed");

    var retainedArtifact =
        fixture.artifacts().findByTenantIdAndVersionId("1", versionId).orElseThrow();
    assertThat(retainedArtifact.getStateEpoch()).isEqualTo(2L);
    assertThat(fixture.artifactXmin()).isEqualTo(artifactXmin);
  }

  @Test
  void retainedVersionWithoutBundleRefusesAndRollsBackAllSeedWrites() {
    FoundationFixture fixture = foundationFixture("30");
    fixture
        .dsl()
        .execute(
            "INSERT INTO game (tenant_id, name, description) VALUES ('1', 'Retained Game', 'Retained description')");
    Long versionId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, is_script_only, notes) VALUES ('1', 1, 'FAILED', 2, false, 'retained-unbundled') RETURNING id")
            .get("id", Long.class);
    fixture.migrate(null);
    var originalGame = fixture.games().findByTenantId("1");
    var originalVersion = fixture.versions().findByTenantIdAndId("1", versionId).orElseThrow();

    assertThatThrownBy(fixture::seed)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No exact canonical Game Design source association");

    assertThat(fixture.games().findByTenantId("1"))
        .usingRecursiveComparison()
        .isEqualTo(originalGame);
    assertThat(fixture.versions().findByTenantIdAndId("1", versionId).orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(originalVersion);
    assertThat(originalVersion.getCanonicalVersionId()).isNull();
    assertThat(originalVersion.getCanonicalTenantId()).isNull();
    for (String table :
        java.util.List.of(
            "published_release_bundle",
            "game_templates",
            "revision",
            "version_asset_artifact",
            "game_design_authored_world_source_operations",
            "game_design_authored_world_source_deliveries")) {
      assertThat(fixture.dsl().fetchCount(DSL.table(table))).as(table).isZero();
    }
  }

  private void assertRetainedDescriptorInsertAndRead(
      FoundationFixture fixture, Long versionId, Long bundleId) {
    Long templateId =
        fixture
            .dsl()
            .fetchSingle("SELECT id FROM game_templates WHERE tenant_id = '1'")
            .get("id", Long.class);
    var repository = new LaunchDescriptorRepository(fixture.dsl());
    var descriptor = new net.firedevops.firemud.gamedesign.entity.LaunchDescriptor();
    descriptor.setLaunchDescriptorId("retained-v1-descriptor");
    descriptor.setTenantId("1");
    descriptor.setGameTemplateId(templateId);
    descriptor.setControlPlaneRequestId("baseline-request");
    descriptor.setRequestHash("baseline-request-hash");
    descriptor.setVersionId(versionId);
    descriptor.setRuntimeFlagsJson("{}");
    descriptor.setGenerationConfigRevision("genrev:demo");
    descriptor.setVersionStateEpoch(1L);
    descriptor.setReleaseBundleId(bundleId);
    descriptor.setPublishedReleaseBundleRef("prb:1:" + versionId + ":" + bundleId);
    var saved = repository.save(descriptor);
    assertThat(saved.getDescriptorSchemaVersion()).isNull();
    assertThat(saved.getCanonicalTenantId()).isNull();
    assertThat(saved.getAuthoredWorldSourceOperationId()).isNull();
    assertThat(
            repository
                .findByTenantIdAndGameTemplateIdAndControlPlaneRequestId(
                    "1", templateId, "baseline-request")
                .orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(saved);
    assertThatThrownBy(() -> repository.save(saved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Launch descriptors are immutable");
  }

  @Test
  void policyOwnerRetainsValidatedExactHumanCaptureForeignKey() {
    var generatedCaptureReferences =
        GameDesignPublishedRealmPolicySet.GAME_DESIGN_PUBLISHED_REALM_POLICY_SET
            .getReferences()
            .stream()
            .filter(
                reference ->
                    reference
                        .getKey()
                        .getTable()
                        .getName()
                        .equals("game_design_realm_policy_capture"))
            .toList();
    assertThat(generatedCaptureReferences).hasSize(1);
    var generatedCaptureReference = generatedCaptureReferences.getFirst();
    assertThat(generatedCaptureReference.getName())
        .isEqualTo("fk_realm_policy_owner_exact_capture");
    assertThat(
            generatedCaptureReference.getFields().stream().map(field -> field.getName()).toList())
        .containsExactly("canonical_tenant_id", "canonical_version_id", "source_commit_id");
    assertThat(
            generatedCaptureReference.getKey().getFields().stream()
                .map(field -> field.getName())
                .toList())
        .containsExactly("canonical_tenant_id", "canonical_version_id", "commit_id");
    var captureForeignKey =
        dsl.fetchSingle(
            "SELECT constraint_row.convalidated, constraint_row.confmatchtype::text AS match_type, "
                + "constraint_row.confupdtype::text AS update_action, "
                + "constraint_row.confdeltype::text AS delete_action, "
                + "constraint_row.confrelid = 'game_design_realm_policy_capture'::regclass AS exact_target, "
                + "ARRAY(SELECT attribute.attname::text "
                + "FROM unnest(constraint_row.conkey) WITH ORDINALITY AS key(attnum, ordinal) "
                + "JOIN pg_attribute attribute ON attribute.attrelid = constraint_row.conrelid "
                + "AND attribute.attnum = key.attnum ORDER BY key.ordinal) AS owner_columns, "
                + "ARRAY(SELECT attribute.attname::text "
                + "FROM unnest(constraint_row.confkey) WITH ORDINALITY AS key(attnum, ordinal) "
                + "JOIN pg_attribute attribute ON attribute.attrelid = constraint_row.confrelid "
                + "AND attribute.attnum = key.attnum ORDER BY key.ordinal) AS capture_columns "
                + "FROM pg_constraint constraint_row "
                + "WHERE constraint_row.conrelid = 'game_design_published_realm_policy_set'::regclass "
                + "AND constraint_row.contype = 'f' "
                + "AND constraint_row.conname = 'fk_realm_policy_owner_exact_capture'");
    assertThat(captureForeignKey.get("convalidated", Boolean.class)).isTrue();
    assertThat(captureForeignKey.get("exact_target", Boolean.class)).isTrue();
    assertThat(captureForeignKey.get("match_type", String.class)).isEqualTo("s");
    assertThat(captureForeignKey.get("update_action", String.class)).isEqualTo("a");
    assertThat(captureForeignKey.get("delete_action", String.class)).isEqualTo("a");
    assertThat(captureForeignKey.get("owner_columns", String[].class))
        .containsExactly("canonical_tenant_id", "canonical_version_id", "source_commit_id");
    assertThat(captureForeignKey.get("capture_columns", String[].class))
        .containsExactly("canonical_tenant_id", "canonical_version_id", "commit_id");
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM pg_constraint "
                        + "WHERE conrelid = 'game_design_published_realm_policy_set'::regclass "
                        + "AND conname = 'game_design_published_realm__canonical_tenant_id_canonica_fkey1'")
                .get(0, Long.class))
        .isZero();
  }

  private FoundationFixture foundationFixture(String target) {
    String schema = "foundation_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    FoundationFixture fixture =
        new FoundationFixture(
            schema,
            dataSource,
            dsl,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    if (target != null) fixture.migrate(target);
    return fixture;
  }

  private record FoundationFixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transactions) {
    void migrate(String target) {
      var configuration =
          Flyway.configure()
              .dataSource(dataSource)
              .schemas(schema)
              .defaultSchema(schema)
              .placeholders(Map.of("serviceSchema", schema))
              .locations(
                  "filesystem:"
                      + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize());
      if (target != null) configuration.target(target);
      configuration.load().migrate();
    }

    GameRepository games() {
      return new GameRepository(dsl);
    }

    VersionRepository versions() {
      return new VersionRepository(dsl);
    }

    PublishedReleaseBundleRepository bundles() {
      return new PublishedReleaseBundleRepository(dsl);
    }

    VersionAssetArtifactRepository artifacts() {
      return new VersionAssetArtifactRepository(dsl);
    }

    void seed() {
      TestDataSeeder seeder =
          new TestDataSeeder(
              games(),
              org.mockito.Mockito.mock(GameAuthoredWorldSourceRepository.class),
              new GameTemplateRepository(dsl),
              new RevisionRepository(dsl),
              versions(),
              bundles(),
              new VersionAssetArtifactRepository(dsl),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.gamedesign.data.RunOwnedFixtureLaunchReadback.class),
              new ObjectMapper(),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository.class),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.gamedesign.data.RunOwnedFixturePolicyProducer.class));
      transactions.executeWithoutResult(
          status -> seeder.run(new DefaultApplicationArguments(new String[] {})));
    }

    String bundleXmin() {
      return dsl.fetchSingle(
              "SELECT xmin::text AS xmin FROM published_release_bundle WHERE tenant_id = '1'")
          .get("xmin", String.class);
    }

    String artifactXmin() {
      return dsl.fetchSingle(
              "SELECT xmin::text AS xmin FROM version_asset_artifact WHERE tenant_id = '1'")
          .get("xmin", String.class);
    }
  }
}
