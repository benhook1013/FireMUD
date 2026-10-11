package integration.net.firedevops.firemud.gamedesign.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.data.TestDataSeeder;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
import net.firedevops.firemud.gamedesign.dto.VersionStateDto;
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
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
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

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private TestDataSeeder seeder;
  @Autowired private Flyway flyway;
  @MockitoSpyBean private GameAuthoredWorldSourceRepository authoredWorldSourceRepository;
  @Autowired private GameRepository gameRepository;
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
    assertThat(envelope.size()).isEqualTo(12);
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
    Files.writeString(
        fixture.capabilityPath(),
        capability.replace(fixture.gameSessionLeafSha256(), wrongLeafDigest));
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
            + "extendedKeyUsage=clientAuth\n"
            + "subjectAltName=URI:"
            + SESSION_URI_SAN
            + ",DNS:game-session-service"
            + ",DNS:game-session-service.dev"
            + ",DNS:game-session-service.dev.svc"
            + ",DNS:game-session-service.dev.svc.cluster.local"
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
            + "extendedKeyUsage=clientAuth\n"
            + "subjectAltName=URI:"
            + WORLD_MANAGEMENT_URI_SAN
            + ",DNS:world-management-service"
            + ",DNS:world-management-service.dev"
            + ",DNS:world-management-service.dev.svc"
            + ",DNS:world-management-service.dev.svc.cluster.local"
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
    UUID operationId = UUID.randomUUID();
    String runId = "fixture-commit-proof";
    String composeProjectName = "firemud-smoke-fixture-commit-proof";
    Path capabilityPath = testDirectory.resolve("run-owned-initial-admission-capability.json");
    Map<String, Object> capability = new LinkedHashMap<>();
    capability.put("schema", "firemud.run-owned-initial-admission-fixture.v1");
    capability.put("runId", runId);
    capability.put("composeProjectName", composeProjectName);
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
    return new Fixture(
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

    fixture.seed();

    assertThat(fixture.bundleXmin()).isEqualTo(xmin);
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
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, script_only, notes) VALUES ('1', 1, 'PUBLISHED', 1, false, 'Seed published version') RETURNING id")
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
    assertRetainedDescriptorInsertAndRead(fixture, versionId, bundleId);
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
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, script_only, notes) VALUES ('1', 1, 'FAILED', 2, false, 'retained-unbundled') RETURNING id")
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
    assertThatThrownBy(() -> repository.save(saved)).hasMessageContaining("immutable");
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
              .locations("classpath:db/migration");
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
              new ObjectMapper());
      transactions.executeWithoutResult(
          status -> seeder.run(new DefaultApplicationArguments(new String[] {})));
    }

    String bundleXmin() {
      return dsl.fetchSingle(
              "SELECT xmin::text AS xmin FROM published_release_bundle WHERE tenant_id = '1'")
          .get("xmin", String.class);
    }
  }
}
