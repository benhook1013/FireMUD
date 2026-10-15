package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot.IntentState;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayBindingRuntimeTestFixtures;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for the V34.3 initial-admission intent request identity width. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayInitialAdmissionIntentRequestIdMigrationIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void intentLedgerAcceptsAttemptWidthAndRejectsAnIdentityBeyond128Characters() {
    String schema = "gs_intent_width_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      migrate(schema, dataSource);
      DataSourceTransactionManager transactionManager =
          new DataSourceTransactionManager(dataSource);
      TransactionTemplate transactions = new TransactionTemplate(transactionManager);
      DSLContext dsl =
          DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
      var schemaReadback = dsl.fetchOne("SELECT current_schema() AS actual_schema");
      assertThat(schemaReadback.get("actual_schema", String.class)).isEqualTo(schema);

      RuntimeTarget length121 =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              dataSource, UUID.randomUUID());
      RuntimeTarget length128 =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              dataSource, UUID.randomUUID());

      // Controlled upstream owner inputs support only this pre-hold consumer proof.
      ReservedIntent intent121 = reserveIntent(dsl, transactions, length121, "r".repeat(121));
      ReservedIntent intent128 = reserveIntent(dsl, transactions, length128, "r".repeat(128));
      assertPendingIntent(dsl, intent121);
      assertPendingIntent(dsl, intent128);

      Record originalIntent = readIntent(dsl, intent128);
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_session_canonical_initial_admission_intent "
                          + "SET initial_admission_request_id = ? "
                          + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                      "r".repeat(129),
                      length128.targetNamespace(),
                      intent128.request().initialAdmissionRequestId()))
          .isInstanceOfSatisfying(
              DataAccessException.class,
              exception -> assertThat(exception.sqlState()).isEqualTo("22001"));

      Record intentAfterRejectedUpdate = readIntent(dsl, intent128);
      assertThat(intentAfterRejectedUpdate.get("canonical_hold_request_bytes", byte[].class))
          .containsExactly(originalIntent.get("canonical_hold_request_bytes", byte[].class));
      assertThat(intentAfterRejectedUpdate.get("canonical_lifecycle_request_bytes", byte[].class))
          .containsExactly(originalIntent.get("canonical_lifecycle_request_bytes", byte[].class));
      assertThat(intentAfterRejectedUpdate.get("original_start_session_tuple", byte[].class))
          .containsExactly(originalIntent.get("original_start_session_tuple", byte[].class));
      assertThat(intentAfterRejectedUpdate.get("state", String.class)).isEqualTo("PENDING_HOLD");
      assertThat(intentAfterRejectedUpdate.get("xmin", String.class))
          .isEqualTo(originalIntent.get("xmin", String.class));
    } finally {
      dropSchema(schema);
    }
  }

  private static ReservedIntent reserveIntent(
      DSLContext dsl, TransactionTemplate transactions, RuntimeTarget target, String requestId) {
    var catalogRepository = new GameSessionCanonicalRealmCatalogRepository(dsl);
    var launchRepository =
        new CanonicalGameInstanceLaunchAssociationRepository(dsl, target.targetNamespace());
    CanonicalInitialAdmissionLaunchTarget launchTarget =
        launchRepository
            .readForInitialAdmission(
                target.targetNamespace(),
                target.canonicalTenantId(),
                target.realmId(),
                target.gameInstanceUuid())
            .orElseThrow();
    var intentRepository =
        new CanonicalInitialAdmissionIntentRepository(dsl, catalogRepository, launchRepository);
    WorldCanonicalInitialAdmissionHold.Request request = holdRequest(target, requestId);
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest =
        lifecycleRequest(target, launchTarget, UUID.randomUUID());
    CanonicalInitialAdmissionIntentSnapshot snapshot =
        Objects.requireNonNull(
            transactions.execute(status -> intentRepository.reserve(request, lifecycleRequest)),
            "reserved canonical initial-admission intent");
    return new ReservedIntent(target, request, lifecycleRequest, snapshot);
  }

  private static WorldCanonicalInitialAdmissionHold.Request holdRequest(
      RuntimeTarget target, String requestId) {
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            target.targetNamespace(),
            target.canonicalTenantId(),
            target.worldSlug(),
            target.realmId(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            target.gameInstanceUuid(),
            target.canonicalVersionId(),
            target.activeWorldEpoch(),
            target.catalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            requestId);
    return new WorldCanonicalInitialAdmissionHold.Request(
        target.targetNamespace(),
        target.canonicalTenantId(),
        target.worldSlug(),
        target.realmId(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.gameInstanceUuid(),
        target.canonicalVersionId(),
        target.activeWorldEpoch(),
        requestId,
        requestDigest,
        WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
        target.catalogRevision(),
        null);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest(
      RuntimeTarget target,
      CanonicalInitialAdmissionLaunchTarget launchTarget,
      UUID readRequestId) {
    var association = launchTarget.association();
    var binding = association.launchBindingEvidence();
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
        readRequestId,
        target.targetNamespace(),
        target.canonicalTenantId(),
        target.worldSlug(),
        association.gameInstanceUuid(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        true,
        association.controlPlaneRequestId(),
        launchTarget.canonicalVersionId(),
        binding.descriptor().requestDigest(),
        binding.descriptor().resultDigest(),
        binding.releaseAttestation().evidenceDigest());
  }

  private static void assertPendingIntent(DSLContext dsl, ReservedIntent reserved) {
    assertThat(reserved.snapshot().state()).isEqualTo(IntentState.PENDING_HOLD);
    assertThat(reserved.snapshot().holdIdentity()).isNull();
    assertThat(reserved.snapshot().holdRequest().canonicalRequestBytes())
        .containsExactly(reserved.request().canonicalRequestBytes());
    assertThat(reserved.snapshot().lifecycleRequest().canonicalBytes())
        .containsExactly(reserved.lifecycleRequest().canonicalBytes());
    Record intent = readIntent(dsl, reserved);
    Record launch =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT original_start_session_tuple FROM game_session_canonical_instance_launch "
                    + "WHERE target_namespace = ? AND control_plane_request_id = ?",
                reserved.target().targetNamespace(),
                reserved.snapshot().sourceBinding().controlPlaneRequestId()),
            "actual canonical launch owner row");
    assertThat(intent.get("original_start_session_tuple", byte[].class))
        .containsExactly(launch.get("original_start_session_tuple", byte[].class));
  }

  private static Record readIntent(DSLContext dsl, ReservedIntent reserved) {
    return Objects.requireNonNull(
        dsl.fetchOne(
            "SELECT canonical_hold_request_bytes, canonical_lifecycle_request_bytes, "
                + "original_start_session_tuple, state, xmin::text AS xmin "
                + "FROM game_session_canonical_initial_admission_intent "
                + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
            reserved.target().targetNamespace(),
            reserved.request().initialAdmissionRequestId()),
        "retained canonical initial-admission intent row");
  }

  private record ReservedIntent(
      RuntimeTarget target,
      WorldCanonicalInitialAdmissionHold.Request request,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest,
      CanonicalInitialAdmissionIntentSnapshot snapshot) {}

  private static void migrate(String schema, DriverManagerDataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = postgres.getJdbcUrl();
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void dropSchema(String schema) {
    try (var connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to dispose intent-width test schema", failure);
    }
  }
}
