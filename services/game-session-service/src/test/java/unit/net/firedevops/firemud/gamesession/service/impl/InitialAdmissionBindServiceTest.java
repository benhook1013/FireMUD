package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindAttemptRepository;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindCatalogRepository;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindCatalogDescriptor;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindHoldBinding;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindRequest;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

class InitialAdmissionBindServiceTest {
  @Test
  void persistsStableCatalogAndIntentThenCommitsMatchingPointerAuditAndLedger() throws Exception {
    Harness harness = newHarness("initial-bind-commit");
    when(harness.gameInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(17L, 93L))
        .thenReturn(Optional.of(gameInstance(17L, 93L, 700L, 44L)));

    InitialAdmissionBindCatalogDescriptor descriptor = descriptor();
    var firstCatalog =
        tx(harness, () -> harness.service.registerPublicSharedFixtureCatalog(descriptor));
    var exactCatalogRetry =
        tx(harness, () -> harness.service.registerPublicSharedFixtureCatalog(descriptor));
    assertThat(exactCatalogRetry.realmId()).isEqualTo(firstCatalog.realmId());
    assertThat(exactCatalogRetry.playableStateNamespaceId())
        .isEqualTo(firstCatalog.playableStateNamespaceId());
    assertThat(exactCatalogRetry.catalogRevision()).isEqualTo(1L);
    assertThat(harness.dsl.fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();

    InitialAdmissionBindRequest request =
        new InitialAdmissionBindRequest(
            17L, "demo", "production", "bind-17-93", digest(), 93L, 44L, 8L);
    InitialAdmissionBindAttempt intent = tx(harness, () -> harness.service.beginIntent(request));
    assertThat(intent.status()).isEqualTo(InitialAdmissionBindAttempt.Status.PENDING);
    assertThat(intent.holdId()).isNull();
    assertThat(tx(harness, () -> harness.service.beginIntent(request)))
        .usingRecursiveComparison()
        .isEqualTo(intent);
    assertThatThrownBy(
            () ->
                tx(
                    harness,
                    () ->
                        harness.service.beginIntent(
                            new InitialAdmissionBindRequest(
                                17L,
                                "demo",
                                "production",
                                "bind-17-93",
                                "b".repeat(64),
                                93L,
                                44L,
                                8L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");

    InitialAdmissionBindHoldBinding binding =
        binding(firstCatalog.realmId(), firstCatalog.playableStateNamespaceId(), "bind-17-93");
    InitialAdmissionBindOwnerProof beforeHold = tx(harness, () -> harness.service.read(binding));
    assertThat(beforeHold.outcome()).isEqualTo(InitialAdmissionBindOwnerProof.Outcome.PENDING);
    assertThat(beforeHold.ownerProofId()).isEqualTo(intent.attemptId().toString());

    tx(harness, () -> harness.service.attachHold(binding));
    InitialAdmissionBindOwnerProof committed = tx(harness, () -> harness.service.commit(binding));
    assertThat(committed.outcome()).isEqualTo(InitialAdmissionBindOwnerProof.Outcome.COMMITTED);
    assertThat(committed.ownerProofId()).isEqualTo(intent.attemptId().toString());
    assertThat(committed.pointerVersion()).isEqualTo(1L);
    assertThat(committed.pointerAuditId()).isNotBlank();
    assertThat(committed.pointerAuditRequestDigest()).isEqualTo(digest());
    assertThat(tx(harness, () -> harness.service.commit(binding))).isEqualTo(committed);
    assertThat(harness.dsl.fetchCount(DSL.table("gameplay_admission_pointer"))).isEqualTo(1);
    assertThat(harness.dsl.fetchCount(DSL.table("gameplay_admission_pointer_event"))).isEqualTo(1);

    harness.dsl.execute(
        "UPDATE gameplay_admission_pointer_event SET catalog_revision = NULL WHERE id = ?",
        Long.parseLong(committed.pointerAuditId()));
    assertThat(tx(harness, () -> harness.service.read(binding)).outcome())
        .isEqualTo(InitialAdmissionBindOwnerProof.Outcome.ERROR);
  }

  @Test
  void abortedAttemptIsDurableAndADelayedCommitCannotTurnItIntoPointer() throws Exception {
    Harness harness = newHarness("initial-bind-abort");
    when(harness.gameInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(17L, 93L))
        .thenReturn(Optional.of(gameInstance(17L, 93L, 700L, 44L)));
    var catalog =
        tx(harness, () -> harness.service.registerPublicSharedFixtureCatalog(descriptor()));
    InitialAdmissionBindRequest request =
        new InitialAdmissionBindRequest(
            17L, "demo", "production", "bind-abort-93", digest(), 93L, 44L, 8L);
    InitialAdmissionBindAttempt intent = tx(harness, () -> harness.service.beginIntent(request));
    InitialAdmissionBindHoldBinding binding =
        binding(catalog.realmId(), catalog.playableStateNamespaceId(), "bind-abort-93");

    InitialAdmissionBindOwnerProof aborted = tx(harness, () -> harness.service.abort(binding));
    assertThat(aborted.outcome()).isEqualTo(InitialAdmissionBindOwnerProof.Outcome.ABORTED);
    assertThat(aborted.ownerProofId()).isEqualTo(intent.attemptId().toString());
    assertThat(aborted.futureCommitPrevented()).isTrue();
    assertThat(aborted.pointerAuditId()).isNull();
    assertThat(aborted.pointerVersion()).isZero();

    InitialAdmissionBindOwnerProof delayedCommit =
        tx(harness, () -> harness.service.commit(binding));
    assertThat(delayedCommit.outcome()).isEqualTo(InitialAdmissionBindOwnerProof.Outcome.ABORTED);
    assertThat(harness.dsl.fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(harness.dsl.fetchCount(DSL.table("gameplay_admission_pointer_event"))).isZero();
    assertThatThrownBy(
            () ->
                tx(
                    harness,
                    () ->
                        harness.service.attachHold(
                            new InitialAdmissionBindHoldBinding(
                                UUID.randomUUID().toString(),
                                UUID.randomUUID().toString(),
                                binding.tenantId(),
                                binding.realmUuid(),
                                binding.playableStateNamespaceUuid(),
                                binding.playableStateScope(),
                                binding.gameInstanceId(),
                                binding.versionId(),
                                binding.activeLifecycleEpoch(),
                                binding.initialAdmissionRequestId(),
                                binding.requestDigest(),
                                true,
                                binding.expectedCatalogRevision()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void exactCatalogRetryReusesIdsButChangedFixtureMetadataConflicts() throws Exception {
    Harness harness = newHarness("initial-bind-catalog");
    var catalog =
        tx(harness, () -> harness.service.registerPublicSharedFixtureCatalog(descriptor()));

    assertThatThrownBy(
            () ->
                tx(
                    harness,
                    () ->
                        harness.service.registerPublicSharedFixtureCatalog(
                            new InitialAdmissionBindCatalogDescriptor(
                                17L,
                                700L,
                                "demo",
                                "Changed Demo Name",
                                "production",
                                "Production Realm",
                                true))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    assertThat(harness.catalogRepository.findByTenantId(17L).orElseThrow().realmId())
        .isEqualTo(catalog.realmId());
    assertThat(harness.dsl.fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
  }

  private static Harness newHarness(String databaseName) throws Exception {
    DriverManagerDataSource setupDataSource = new DriverManagerDataSource();
    setupDataSource.setDriverClassName("org.h2.Driver");
    setupDataSource.setUrl(
        "jdbc:h2:mem:"
            + databaseName
            + "-"
            + System.nanoTime()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    try (Connection connection = setupDataSource.getConnection()) {
      createSchema(DSL.using(connection, SQLDialect.H2));
    }
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(setupDataSource), SQLDialect.H2);
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    InitialAdmissionBindCatalogRepository catalogRepository =
        new InitialAdmissionBindCatalogRepository(dsl);
    InitialAdmissionBindAttemptRepository attemptRepository =
        new InitialAdmissionBindAttemptRepository(dsl);
    DatabaseInitialAdmissionBindOwnerService service =
        new DatabaseInitialAdmissionBindOwnerService(
            catalogRepository,
            attemptRepository,
            new GameplayAdmissionPointerEventRepository(dsl),
            gameInstanceRepository);
    return new Harness(
        dsl,
        new TransactionTemplate(new DataSourceTransactionManager(setupDataSource)),
        catalogRepository,
        gameInstanceRepository,
        service);
  }

  private static void createSchema(DSLContext dsl) {
    dsl.execute(
        "CREATE TABLE gameplay_tenant_shared_playable_state_namespace ("
            + "tenant_id BIGINT PRIMARY KEY, playable_state_namespace_id UUID NOT NULL UNIQUE, "
            + "allocated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    dsl.execute(
        "CREATE TABLE gameplay_initial_admission_bind_catalog ("
            + "realm_id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL, game_template_id BIGINT NOT NULL, "
            + "world_slug VARCHAR(120) NOT NULL, world_display_name VARCHAR(200) NOT NULL, "
            + "realm_slug VARCHAR(120) NOT NULL, realm_display_name VARCHAR(200) NOT NULL, "
            + "catalog_revision BIGINT NOT NULL DEFAULT 1, playable_state_namespace_id UUID NOT NULL, "
            + "visible BOOLEAN NOT NULL DEFAULT TRUE, public_production_realm BOOLEAN NOT NULL DEFAULT TRUE, "
            + "requires_character_selection BOOLEAN NOT NULL, state_scope VARCHAR(32) NOT NULL DEFAULT 'SHARED', "
            + "character_creation_policy VARCHAR(32) NOT NULL DEFAULT 'ALLOW_NEW', "
            + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE (tenant_id))");
    dsl.execute(
        "CREATE TABLE gameplay_initial_admission_bind_attempt ("
            + "attempt_id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL, "
            + "initial_admission_request_id VARCHAR(128) NOT NULL, request_digest VARCHAR(64) NOT NULL, "
            + "realm_id UUID NOT NULL, playable_state_namespace_id UUID NOT NULL, "
            + "playable_state_scope VARCHAR(32) NOT NULL, expected_no_prior_pointer BOOLEAN NOT NULL, "
            + "catalog_revision BIGINT NOT NULL, game_instance_id BIGINT NOT NULL, version_id BIGINT NOT NULL, "
            + "active_lifecycle_epoch BIGINT NOT NULL, hold_id UUID, hold_fence UUID, status VARCHAR(16) NOT NULL, "
            + "pointer_id BIGINT, audit_event_id BIGINT, created_at TIMESTAMP NOT NULL, "
            + "updated_at TIMESTAMP NOT NULL, terminal_at TIMESTAMP, UNIQUE (tenant_id, initial_admission_request_id))");
    dsl.execute(
        "CREATE TABLE gameplay_admission_pointer ("
            + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, world_slug VARCHAR(120) NOT NULL, "
            + "representation_version INT NOT NULL DEFAULT 1, "
            + "world_display_name VARCHAR(200) NOT NULL, realm_slug VARCHAR(120) NOT NULL, "
            + "realm_display_name VARCHAR(200) NOT NULL, tenant_id BIGINT NOT NULL, game_instance_id BIGINT NOT NULL, "
            + "pointer_version BIGINT NOT NULL, catalog_revision BIGINT NOT NULL, realm_id UUID, "
            + "playable_state_namespace_id UUID, visible BOOLEAN NOT NULL, public_production_realm BOOLEAN NOT NULL, "
            + "requires_character_selection BOOLEAN NOT NULL, state_scope VARCHAR(32) NOT NULL, "
            + "character_creation_policy VARCHAR(32) NOT NULL, last_updated_by VARCHAR(200) NOT NULL, "
            + "last_update_reason VARCHAR(500) NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, "
            + "UNIQUE (tenant_id, world_slug, realm_slug), UNIQUE (tenant_id, game_instance_id))");
    dsl.execute(
        "CREATE TABLE gameplay_admission_pointer_event ("
            + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, world_slug VARCHAR(120) NOT NULL, "
            + "representation_version INT NOT NULL DEFAULT 1, "
            + "realm_slug VARCHAR(120) NOT NULL, world_display_name VARCHAR(200) NOT NULL, "
            + "realm_display_name VARCHAR(200) NOT NULL, tenant_id BIGINT NOT NULL, game_instance_id BIGINT NOT NULL, "
            + "pointer_version BIGINT NOT NULL, catalog_revision BIGINT, realm_id UUID, "
            + "playable_state_namespace_id UUID, visible BOOLEAN NOT NULL, public_production_realm BOOLEAN NOT NULL, "
            + "requires_character_selection BOOLEAN NOT NULL, state_scope VARCHAR(32) NOT NULL, "
            + "character_creation_policy VARCHAR(32) NOT NULL, actor_principal VARCHAR(200) NOT NULL, "
            + "reason VARCHAR(500) NOT NULL, control_plane_request_id VARCHAR(128) NOT NULL, "
            + "prepared_version_upgrade_id VARCHAR(64), occurred_at TIMESTAMP NOT NULL)");
  }

  private static <T> T tx(Harness harness, Supplier<T> action) {
    return harness.transactionTemplate.execute(status -> action.get());
  }

  private static InitialAdmissionBindCatalogDescriptor descriptor() {
    return new InitialAdmissionBindCatalogDescriptor(
        17L, 700L, "demo", "Demo World", "production", "Production Realm", true);
  }

  private static GameInstance gameInstance(
      long tenantId, long gameInstanceId, long templateId, long versionId) {
    GameInstance instance = new GameInstance();
    instance.setTenantId(tenantId);
    instance.setId(gameInstanceId);
    instance.setGameTemplateId(templateId);
    instance.setVersionId(versionId);
    return instance;
  }

  private static InitialAdmissionBindHoldBinding binding(
      UUID realmId, UUID namespaceId, String initialAdmissionRequestId) {
    return new InitialAdmissionBindHoldBinding(
        "c791314b-b453-4451-8c34-1a5c6cdfd2dd",
        "8bc5a38d-888d-4773-9815-0e9304a48145",
        17L,
        realmId.toString(),
        namespaceId.toString(),
        "SHARED",
        93L,
        44L,
        8L,
        initialAdmissionRequestId,
        digest(),
        true,
        1L);
  }

  private static String digest() {
    return "a".repeat(64);
  }

  private record Harness(
      DSLContext dsl,
      TransactionTemplate transactionTemplate,
      InitialAdmissionBindCatalogRepository catalogRepository,
      GameInstanceRepository gameInstanceRepository,
      DatabaseInitialAdmissionBindOwnerService service) {}
}
