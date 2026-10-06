package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalLaunchPreparationRequest;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.FreshGameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalLaunchPreparationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.CanonicalStartingGameInstanceOwner;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociationService;
import net.firedevops.firemud.gamesession.service.GameSessionCanonicalLaunchPreparationService;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameInstanceLaunchAssociationIntegrationTest {
  private static final String NAMESPACE = "canonical-launch-association-it";
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void capturesAnExistingRunOwnedStartingRowThroughTheExactFreshOwnerMapping() {
    String schema = "gs_launch_assoc_capture_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl() + "?currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DataSourceTransactionManager transactionManager =
          new DataSourceTransactionManager(dataSource);
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      DSLContext dsl =
          DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
      GameSessionAuthoredWorldSourceRepository sourceRepository =
          new GameSessionAuthoredWorldSourceRepository(dsl);
      GameSessionCanonicalRealmCatalogRepository catalogRepository =
          new GameSessionCanonicalRealmCatalogRepository(dsl);
      var intake =
          transaction.execute(status -> sourceRepository.register(uuid(4), sourceEvidence()));
      assertThat(intake).isNotNull();
      CreateCanonicalRealmCatalogRequest catalogRequest =
          new CreateCanonicalRealmCatalogRequest(
              uuid(5),
              NAMESPACE,
              uuid(2),
              intake.operationId(),
              "owner-realm",
              "Owner Realm",
              true,
              true,
              "SHARED",
              "explicit-policy-v1",
              null,
              null);
      var preparedCatalog = catalogRepository.prepareInitialPublicProduction(catalogRequest);
      CanonicalRealmCatalogSnapshot createdCatalog =
          transaction.execute(
              status -> catalogRepository.createInitialPublicProduction(preparedCatalog));
      assertThat(createdCatalog).isNotNull();

      var tenantRepository =
          new FreshGameSessionTenantAssociationRepository(dsl, transactionManager, NAMESPACE);
      FreshGameSessionTenantAssociation tenant =
          tenantRepository.registerAndReadback(
              new RuntimeTenantIdentityEvidence(
                  1,
                  NAMESPACE,
                  uuid(6),
                  uuid(2),
                  intake.source().sourceGameRowId(),
                  intake.source().sourceGameTenantKey(),
                  "NEW_GAME_ROW"));
      CompleteLaunchBindingEvidence binding = launchBinding(intake);
      GameInstance instance = startingInstance(tenant, binding);
      GameInstanceRepository instances = new GameInstanceRepository(dsl);
      CanonicalGameInstanceLaunchAssociationRepository associations =
          new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);

      CanonicalGameInstanceLaunchAssociation captured =
          transaction.execute(
              status -> {
                GameInstance saved = instances.save(instance);
                return associations.capture(tenant, saved.getId(), binding);
              });

      assertThat(captured).isNotNull();
      assertThat(captured.tenantAssociationOperationId())
          .isEqualTo(tenant.associationOperationId());
      assertThat(captured.capturedStartingRowVersion()).isZero();
      assertThat(captured.currentGameInstanceStatus())
          .isEqualTo(CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STARTING);
      assertThat(associations.read(binding.descriptor().controlPlaneRequestId()))
          .contains(captured);
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void canonicalStartingOwnerCreatesStartingInstanceAndCapturesAssociationAtomically() {
    StartingOwnerFixture fixture = startingOwnerFixture();
    try {
      assertThat(
              fixture
                  .sourceRepository()
                  .read(
                      fixture.intake().operationId(),
                      fixture.request().canonicalTenantId(),
                      fixture.intake().source().worldSlug(),
                      NAMESPACE))
          .contains(fixture.intake());
      assertThat(
              fixture
                  .catalogRepository()
                  .readByRequest(NAMESPACE, fixture.catalog().creationRequestId()))
          .contains(fixture.catalog());

      CanonicalGameInstanceLaunchAssociation created =
          fixture.owner().createStartingInstance(fixture.request(), fixture.associationRequestId());
      Record instance =
          fixture
              .dsl()
              .fetchOne(
                  "SELECT * FROM game_instances WHERE tenant_id = ? "
                      + "AND run_owned_start_request_id = ?",
                  created.gameSessionTenantId(),
                  fixture.request().controlPlaneRequestId());
      assertThat(instance).isNotNull();
      assertThat(instance.get("status", String.class)).isEqualTo("STARTING");
      assertThat(instance.get("game_instance_uuid", UUID.class))
          .isEqualTo(created.gameInstanceUuid());
      assertThat(instance.get("row_version", Long.class)).isEqualTo(0L);
      assertThat(instance.get("owner_account_uuid", UUID.class))
          .isEqualTo(fixture.request().actingAccountUuid());
      assertThat(instance.get("run_owned_start_request_id", String.class))
          .isEqualTo(fixture.request().controlPlaneRequestId());
      assertThat(
              fixture
                  .dsl()
                  .fetchCount(DSL.table(DSL.name("game_session_canonical_instance_launch"))))
          .isEqualTo(1);
      assertThat(
              fixture
                  .dsl()
                  .fetchCount(DSL.table(DSL.name("game_session_canonical_launch_preparation"))))
          .isEqualTo(1);
      assertThat(fixture.launchAssociations().read(fixture.request().controlPlaneRequestId()))
          .contains(created);
      assertThat(created.currentGameInstanceStatus())
          .isEqualTo(CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STARTING);
      assertThat(created.capturedStartingRowVersion()).isZero();
      assertThat(created.launchBindingEvidence()).isEqualTo(fixture.binding());
    } finally {
      dropSchema(fixture.schema());
    }
  }

  @Test
  void exactStartingOwnerRetryPreservesTheOriginalInstanceAndAssociation() {
    StartingOwnerFixture fixture = startingOwnerFixture();
    try {
      CanonicalGameInstanceLaunchAssociation first =
          fixture.owner().createStartingInstance(fixture.request(), fixture.associationRequestId());
      Record originalInstance = instanceRow(fixture);
      Record originalAssociation = associationRow(fixture);

      CanonicalGameInstanceLaunchAssociation retry =
          fixture.owner().createStartingInstance(fixture.request(), fixture.associationRequestId());

      assertThat(retry).isEqualTo(first);
      assertThat(instanceRow(fixture)).isEqualTo(originalInstance);
      assertThat(associationRow(fixture)).isEqualTo(originalAssociation);
      assertThat(fixture.dsl().fetchCount(DSL.table(DSL.name("game_instances")))).isEqualTo(1);
      assertThat(
              fixture
                  .dsl()
                  .fetchCount(DSL.table(DSL.name("game_session_canonical_instance_launch"))))
          .isEqualTo(1);
      verify(fixture.descriptorClient(), times(1)).resolve(any());
      verify(fixture.descriptorClient(), times(1)).get(any(GetRequest.class));
      verify(fixture.descriptorClient(), times(2))
          .getComplete(any(GetLaunchDescriptorRequest.class));
    } finally {
      dropSchema(fixture.schema());
    }
  }

  @Test
  void changedCompleteDescriptorAttestationPairConflictsWithoutPartialMutation() {
    StartingOwnerFixture fixture = startingOwnerFixture();
    try {
      fixture.owner().createStartingInstance(fixture.request(), fixture.associationRequestId());
      Record originalInstance = instanceRow(fixture);
      Record originalAssociation = associationRow(fixture);
      CompleteLaunchBindingEvidence changedPair = launchBinding(fixture.intake(), uuid(11));
      assertThat(changedPair.descriptor()).isEqualTo(fixture.binding().descriptor());
      assertThat(changedPair.releaseAttestation())
          .isNotEqualTo(fixture.binding().releaseAttestation());
      when(fixture.descriptorClient().getComplete(any(GetLaunchDescriptorRequest.class)))
          .thenReturn(changedPair);

      assertThatThrownBy(
              () ->
                  fixture
                      .owner()
                      .createStartingInstance(fixture.request(), fixture.associationRequestId()))
          .isInstanceOf(
              CanonicalGameInstanceLaunchAssociationRepository
                  .CanonicalGameInstanceLaunchAssociationConflictException.class)
          .hasMessageContaining("changed tenant, instance, or source binding");

      assertThat(instanceRow(fixture)).isEqualTo(originalInstance);
      assertThat(associationRow(fixture)).isEqualTo(originalAssociation);
      assertThat(fixture.dsl().fetchCount(DSL.table(DSL.name("game_instances")))).isEqualTo(1);
      assertThat(
              fixture
                  .dsl()
                  .fetchCount(DSL.table(DSL.name("game_session_canonical_instance_launch"))))
          .isEqualTo(1);
      assertThat(
              fixture
                  .dsl()
                  .fetchCount(DSL.table(DSL.name("game_session_canonical_launch_preparation"))))
          .isEqualTo(1);
    } finally {
      dropSchema(fixture.schema());
    }
  }

  @Test
  void freshStartingInstanceCannotCommitWithoutItsExactLaunchAssociation() {
    String schema = "gs_launch_assoc_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl() + "?currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DataSourceTransactionManager transactionManager =
          new DataSourceTransactionManager(dataSource);
      DSLContext dsl =
          DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
      FreshGameSessionTenantAssociationRepository tenantRepository =
          new FreshGameSessionTenantAssociationRepository(dsl, transactionManager, NAMESPACE);
      FreshGameSessionTenantAssociation tenant =
          tenantRepository.registerAndReadback(
              new RuntimeTenantIdentityEvidence(
                  1,
                  NAMESPACE,
                  uuid(1),
                  uuid(2),
                  9_001L,
                  "source-game-tenant-9002",
                  "NEW_GAME_ROW"));
      GameInstance instance = startingInstance(tenant);
      GameInstanceRepository instances = new GameInstanceRepository(dsl);
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);

      assertThatThrownBy(() -> transaction.execute(status -> instances.save(instance)))
          .hasRootCauseMessage(
              "Fresh Game Session instance requires one exact complete launch association");
      Record gameInstanceCountRow =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT count(*) FROM game_instances WHERE tenant_id = ?",
                  tenant.legacyGameSessionTenantId()),
              "game instance count query must return one row");
      assertThat(
              Objects.requireNonNull(
                  gameInstanceCountRow.get(0, Long.class), "game instance count must be present"))
          .isZero();

      GameInstance legacy = new GameInstance();
      legacy.setTenantId(99_001L);
      legacy.setRuntimeVersion("legacy-row");
      legacy.setOwnerAccountId(uuid(3).toString());
      legacy.setStatus("STOPPED");
      GameInstance savedLegacy = transaction.execute(status -> instances.save(legacy));
      assertThat(savedLegacy).isNotNull();
    } finally {
      dropSchema(schema);
    }
  }

  private static void dropSchema(String schema) {
    try (var connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to dispose canonical launch test schema", failure);
    }
  }

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    String worldSlug = "owner-world";
    String tenantSlug = "owner-tenant";
    String displayName = "Owner World";
    UUID registrationRequestId = uuid(7);
    UUID sourceOperationId = uuid(8);
    long sourceGameRowId = 9_001L;
    String sourceGameTenantKey = "source-game-tenant-9002";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequestId, uuid(2), tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            uuid(2),
            tenantSlug,
            worldSlug,
            displayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        sourceOperationId,
        requestDigest,
        uuid(2),
        tenantSlug,
        worldSlug,
        displayName,
        sourceGameRowId,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static CompleteLaunchBindingEvidence launchBinding(
      GameSessionAuthoredWorldSourceRepository.IntakeReceipt intake) {
    return launchBinding(intake, uuid(10));
  }

  private static CompleteLaunchBindingEvidence launchBinding(
      GameSessionAuthoredWorldSourceRepository.IntakeReceipt intake, UUID attestationRequestId) {
    AuthoredWorldSourceEvidence source = intake.source();
    String requestId = "launch-request-1";
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            requestId,
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            801L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "launch-descriptor-1",
            902L,
            false,
            null,
            "{}",
            "generation-4",
            4L,
            903L,
            "prb:tenant:902:903",
            false,
            null);
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        "902",
                        false,
                        null,
                        "publish-commit-902",
                        "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "d".repeat(64) : null))
            .toList();
    var attestation =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            source.canonicalTenantId(),
            attestationRequestId,
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish-workflow-1",
            "publish-commit-902",
            participants,
            "sha256:" + "b".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingEvidence(descriptor, attestation);
  }

  private static GameInstance startingInstance(
      FreshGameSessionTenantAssociation tenant, CompleteLaunchBindingEvidence binding) {
    GameInstance instance = new GameInstance();
    instance.setTenantId(tenant.legacyGameSessionTenantId());
    instance.setRuntimeVersion("owner-created-starting-row");
    instance.setOwnerAccountId(uuid(3).toString());
    instance.setStatus("STARTING");
    instance.setGameTemplateId(binding.descriptor().gameTemplateId());
    instance.setLaunchDescriptorId(binding.descriptor().launchDescriptorId());
    instance.setVersionId(binding.descriptor().versionId());
    instance.setReleaseBundleId(binding.descriptor().releaseBundleId());
    instance.setVersionStateEpoch(binding.descriptor().versionStateEpoch());
    instance.setGenerationConfigRevision(binding.descriptor().generationConfigRevision());
    instance.setRunOwnedStartRequestId(binding.descriptor().controlPlaneRequestId());
    instance.setRunOwnedStartRequestDigest("a".repeat(64));
    instance.setRunOwnedStartPublishedReleaseBundleRef(
        binding.descriptor().publishedReleaseBundleRef());
    return instance;
  }

  private static GameInstance startingInstance(FreshGameSessionTenantAssociation tenant) {
    GameInstance instance = new GameInstance();
    instance.setTenantId(tenant.legacyGameSessionTenantId());
    instance.setRuntimeVersion("owner-created-starting-row");
    instance.setOwnerAccountId(uuid(3).toString());
    instance.setStatus("STARTING");
    instance.setGameTemplateId(801L);
    instance.setLaunchDescriptorId("launch-descriptor-1");
    instance.setVersionId(902L);
    instance.setReleaseBundleId(903L);
    instance.setVersionStateEpoch(4L);
    instance.setGenerationConfigRevision("generation-4");
    instance.setRunOwnedStartRequestId("launch-request-1");
    instance.setRunOwnedStartRequestDigest("a".repeat(64));
    instance.setRunOwnedStartPublishedReleaseBundleRef("prb:tenant:902:903");
    return instance;
  }

  private static UUID uuid(long value) {
    return new UUID(0x123e4567e89b12d3L, value);
  }

  private static StartingOwnerFixture startingOwnerFixture() {
    String schema = "gs_starting_owner_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl() + "?currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();

    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameSessionAuthoredWorldSourceRepository sourceRepository =
        new GameSessionAuthoredWorldSourceRepository(dsl);
    GameSessionCanonicalRealmCatalogRepository catalogRepository =
        new GameSessionCanonicalRealmCatalogRepository(dsl);
    var intake =
        transaction.execute(status -> sourceRepository.register(uuid(4), sourceEvidence()));
    assertThat(intake).isNotNull();
    UUID catalogRequestId = uuid(5);
    var preparedCatalog =
        catalogRepository.prepareInitialPublicProduction(
            new CreateCanonicalRealmCatalogRequest(
                catalogRequestId,
                NAMESPACE,
                uuid(2),
                intake.operationId(),
                "owner-realm",
                "Owner Realm",
                true,
                true,
                "SHARED",
                "explicit-policy-v1",
                null,
                null));
    CanonicalRealmCatalogSnapshot catalog =
        transaction.execute(
            status -> catalogRepository.createInitialPublicProduction(preparedCatalog));
    assertThat(catalog).isNotNull();

    UUID associationRequestId = uuid(6);
    RuntimeTenantIdentityEvidence tenantEvidence =
        new RuntimeTenantIdentityEvidence(
            1,
            NAMESPACE,
            associationRequestId,
            uuid(2),
            intake.source().sourceGameRowId(),
            intake.source().sourceGameTenantKey(),
            "NEW_GAME_ROW");
    GameDesignRuntimeTenantIdentityClient tenantIdentityClient =
        mock(GameDesignRuntimeTenantIdentityClient.class);
    when(tenantIdentityClient.resolveRuntimeTenantIdentity(anyString(), anyString()))
        .thenReturn(tenantEvidence);
    var tenantAssociationService =
        new FreshGameSessionTenantAssociationService(
            tenantIdentityClient,
            new FreshGameSessionTenantAssociationRepository(dsl, transactionManager, NAMESPACE),
            NAMESPACE);

    CompleteLaunchBindingEvidence binding = launchBinding(intake);
    AuthoredWorldLaunchDescriptorClient descriptorClient =
        mock(AuthoredWorldLaunchDescriptorClient.class);
    when(descriptorClient.resolve(any(AuthoredWorldLaunchDescriptorEvidence.Request.class)))
        .thenReturn(binding.descriptor());
    when(descriptorClient.get(any(GetRequest.class))).thenReturn(binding.descriptor());
    when(descriptorClient.getComplete(any(GetLaunchDescriptorRequest.class))).thenReturn(binding);
    GameSessionCanonicalLaunchPreparationRepository preparationRepository =
        new GameSessionCanonicalLaunchPreparationRepository(dsl, catalogRepository);
    GameSessionCanonicalLaunchPreparationService preparationService =
        new GameSessionCanonicalLaunchPreparationService(
            descriptorClient,
            catalogRepository,
            sourceRepository,
            preparationRepository,
            transactionManager,
            NAMESPACE);
    CreateCanonicalLaunchPreparationRequest request =
        new CreateCanonicalLaunchPreparationRequest(
            binding.descriptor().controlPlaneRequestId(),
            uuid(3),
            NAMESPACE,
            uuid(2),
            catalog.realmId(),
            catalogRequestId,
            catalog.catalogRevision(),
            intake.operationId(),
            binding.descriptor().gameTemplateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    CanonicalGameInstanceLaunchAssociationRepository launchAssociations =
        new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
    GameInstanceRepository instances = new GameInstanceRepository(dsl);
    CanonicalStartingGameInstanceOwner owner =
        new CanonicalStartingGameInstanceOwner(
            tenantAssociationService,
            preparationService,
            launchAssociations,
            instances,
            transactionManager,
            NAMESPACE);

    // These mocks stop at Game Design's authenticated tenant and descriptor reads. They provide
    // deterministic owner evidence only; this fixture is not mTLS, Account authorization, World,
    // lifecycle, or gameplay-admission proof.
    return new StartingOwnerFixture(
        schema,
        dsl,
        intake,
        catalog,
        sourceRepository,
        catalogRepository,
        associationRequestId,
        request,
        binding,
        descriptorClient,
        launchAssociations,
        owner);
  }

  private static Record instanceRow(StartingOwnerFixture fixture) {
    CanonicalGameInstanceLaunchAssociation association =
        fixture.launchAssociations().read(fixture.request().controlPlaneRequestId()).orElseThrow();
    return fixture
        .dsl()
        .fetchOne(
            "SELECT * FROM game_instances WHERE tenant_id = ? "
                + "AND run_owned_start_request_id = ?",
            association.gameSessionTenantId(),
            fixture.request().controlPlaneRequestId());
  }

  private static Record associationRow(StartingOwnerFixture fixture) {
    return fixture
        .dsl()
        .fetchOne(
            "SELECT * FROM game_session_canonical_instance_launch "
                + "WHERE target_namespace = ? AND control_plane_request_id = ?",
            NAMESPACE,
            fixture.request().controlPlaneRequestId());
  }

  private record StartingOwnerFixture(
      String schema,
      DSLContext dsl,
      GameSessionAuthoredWorldSourceRepository.IntakeReceipt intake,
      CanonicalRealmCatalogSnapshot catalog,
      GameSessionAuthoredWorldSourceRepository sourceRepository,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      UUID associationRequestId,
      CreateCanonicalLaunchPreparationRequest request,
      CompleteLaunchBindingEvidence binding,
      AuthoredWorldLaunchDescriptorClient descriptorClient,
      CanonicalGameInstanceLaunchAssociationRepository launchAssociations,
      CanonicalStartingGameInstanceOwner owner) {}
}
