package integration.net.firedevops.firemud.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.firedevops.firemud.recovery.RecoveryControllerServiceApplication;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseConflictException;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal.RetrySource;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseTupleCodec;
import net.firedevops.firemud.recovery.journal.JooqInitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseCommands.ContinueRecovery;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseCommands.Resume;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.CommitOwnerAuthorizedHandoffEffect;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectKind;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectOutboxState;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadback;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadbackAuthority;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadbackState;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.InitialGameplayReleaseEvidence;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.IssuerLayoutProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.LayoutDescriptor;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.MigrationProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Operation;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OutboxEntry;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OwnerInitializationProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OwnerService;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PartitionAcknowledgement;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PeerStorageBinding;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Phase;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.ProofDisposition;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PublicTransitionResult;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PublicTransitionTuple;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.RegionLeaseProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Scope;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.TransitionVerb;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.TypedEffect;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.WriterDisposition;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.WriterFenceAcknowledgement;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.WriterFenceProof;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.WriterInventory;
import net.firedevops.firemud.recovery.model.MaintenanceLockCredential;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseController;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEffectPort;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEvidenceProvider;
import net.firedevops.firemud.recovery.service.OwnerBoundaryUnavailableException;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.tools.LoggerListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ReflectionUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
@SuppressWarnings("resource")
class InitialGameplayReleaseJournalPostgresIntegrationTest {
  private static final String SCHEMA = "recovery_control";
  private static final List<String> EXPECTED_MIGRATIONS =
      List.of(
          "V1__initial_gameplay_release_journal.sql",
          "V2__retain_initial_gameplay_release_attempt_closure.sql",
          "V3__require_dispatch_attempt_for_non_pending_effects.sql",
          "V4__retain_typed_retry_provenance.sql",
          "V5__retain_local_dispatch_uncertainty.sql",
          "V6__retain_latest_preparation_observation.sql",
          "V7__retain_preparation_receipt_identities.sql",
          "V8__require_pending_effect_without_dispatch_timestamp.sql",
          "V9__require_pristine_pending_attempt.sql",
          "V10__refuse_pending_effects_with_retained_attempts.sql");
  private static final String DIGEST = "b".repeat(64);
  private static final java.util.logging.Logger POSTGRES_PROTOCOL_LOG =
      java.util.logging.Logger.getLogger("org.postgresql.core.v3.QueryExecutorImpl");
  private static final UUID V1_OPERATION_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID V1_PLAYABLE_NAMESPACE_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000003");
  private static final UUID V1_LOCK_IDENTITY =
      UUID.fromString("10000000-0000-0000-0000-000000000004");
  private static final UUID V1_OUTBOX_ID = UUID.fromString("10000000-0000-0000-0000-000000000010");
  private static final UUID V1_EFFECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000011");
  private static final UUID V1_PRIOR_ATTEMPT_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000012");
  // Fixed digests from the V1 writer at a066429d194f3ec6d54038f9581183c4943c82ed.
  // Its canonical digest envelope predates the SQL V2 migration.
  // Keep the inputs independent of the codec under test so the upgrade catches writer drift.
  private static final String V1_SCOPE_DIGEST =
      "ef9bbb81842b83253fcc6e6be7032ee744e563d109e4d0aab35146af85fe2c8f";
  private static final String V1_EVIDENCE_DIGEST =
      "3c811a041f71b88c21c1d023b3fa3f489054c14603e333f8ae9edc32f34ef79e";
  private static final String V1_RESUME_TRANSITION_DIGEST =
      "814d6a36a4c1046394985096a1ea82b498d445dd69eb19a1284a264e252e4e55";
  private static final String V1_OPERATION_TUPLE_DIGEST =
      "926bf0c737455039923849ac17a926440637204c2a4fa7a408f22dcac7c263cf";
  private static final String V1_PREPARE_EFFECT_PAYLOAD_DIGEST =
      "2df3d23d85b96b45a6740be38a5edd357bddf15fc5c346cdc921911ecc3b7b63";
  private static final String V1_SCOPE_PAYLOAD =
      """
      {
        "operationId": "10000000-0000-0000-0000-000000000001",
        "canonicalTenantId": "10000000-0000-0000-0000-000000000002",
        "targetNamespace": "release-target",
        "playableStateNamespaceId": "10000000-0000-0000-0000-000000000003",
        "peerStorageBinding": {
          "targetNamespace": "release-target",
          "clusterIdentity": "cluster-a",
          "peerWorkloadIdentity": "recovery-peer",
          "storageIdentity": "storage-uid-1",
          "storageRevision": "revision-7",
          "bindingDigest": "@DIGEST@"
        },
        "evidenceRef": "immutable-initial-layout-evidence"
      }
      """
          .strip()
          .replace("@DIGEST@", DIGEST);
  private static final String V1_PEER_STORAGE_BINDING_PAYLOAD =
      """
      {
        "targetNamespace": "release-target",
        "clusterIdentity": "cluster-a",
        "peerWorkloadIdentity": "recovery-peer",
        "storageIdentity": "storage-uid-1",
        "storageRevision": "revision-7",
        "bindingDigest": "@DIGEST@"
      }
      """
          .strip()
          .replace("@DIGEST@", DIGEST);
  private static final String V1_EVIDENCE_PAYLOAD =
      """
      {
        "evidenceRef": "immutable-initial-layout-evidence",
        "evidenceIdentity": "evidence-owner-record-31",
        "migration": {
          "migrationOperationId": "10000000-0000-0000-0000-000000000005",
          "disposition": "VERIFIED",
          "storageIdentity": "storage-uid-1",
          "inventoryRevision": "writer-inventory-r9",
          "inventoryDigest": "@DIGEST@"
        },
        "issuerLayout": {
          "layoutOperationId": "10000000-0000-0000-0000-000000000006",
          "descriptor": {
            "descriptorIdentity": "issuer-layout-v1",
            "revision": "layout-r4",
            "digest": "@DIGEST@",
            "partitionIds": [
              0,
              1
            ]
          },
          "accountIssuerSourceIdentity": "account-issuer-source-identity-1",
          "operationFence": 101,
          "coverageFence": "10000000-0000-0000-0000-000000000007",
          "inventoryRevision": "writer-inventory-r9",
          "inventoryDigest": "@DIGEST@",
          "acknowledgements": [
            {
              "partitionId": 0,
              "acknowledgementIdentity": "partition-0-ack",
              "acknowledgedLayoutDigest": "@DIGEST@",
              "readbackDigest": "@DIGEST@",
              "capacity": 32
            },
            {
              "partitionId": 1,
              "acknowledgementIdentity": "partition-1-ack",
              "acknowledgedLayoutDigest": "@DIGEST@",
              "readbackDigest": "@DIGEST@",
              "capacity": 64
            }
          ],
          "capacityVector": {
            "0": 32,
            "1": 64
          }
        },
        "writerFencing": {
          "inventory": {
            "revision": "writer-inventory-r9",
            "digest": "@DIGEST@",
            "legacyWriterIdentity": "legacy-layout-writer",
            "applicableWriterIdentities": [
              "writer-a",
              "writer-b"
            ]
          },
          "legacyWriterDisposition": "STOPPED",
          "writerAcknowledgements": [
            {
              "writerIdentity": "writer-a",
              "disposition": "FENCED",
              "fenceIdentity": "fence-a",
              "inventoryDigest": "@DIGEST@"
            },
            {
              "writerIdentity": "writer-b",
              "disposition": "FENCED",
              "fenceIdentity": "fence-b",
              "inventoryDigest": "@DIGEST@"
            }
          ]
        },
        "worldInitialization": {
          "ownerService": "WORLD_MANAGEMENT",
          "initializationOperationId": "10000000-0000-0000-0000-000000000008",
          "playableStateNamespaceId": "10000000-0000-0000-0000-000000000003",
          "disposition": "VERIFIED",
          "sourceIdentity": "world-init-source-1",
          "resultDigest": "@DIGEST@"
        },
        "entityInitialization": {
          "ownerService": "ENTITY_MANAGEMENT",
          "initializationOperationId": "10000000-0000-0000-0000-000000000009",
          "playableStateNamespaceId": "10000000-0000-0000-0000-000000000003",
          "disposition": "VERIFIED",
          "sourceIdentity": "entity-init-source-1",
          "resultDigest": "@DIGEST@"
        },
        "regionLease": {
          "regionIdentity": "region-a",
          "leaseIdentity": "lease-identity-51",
          "executorGeneration": 10
        }
      }
      """
          .strip()
          .replace("@DIGEST@", DIGEST);
  private static final String V1_PREPARE_EFFECT_PAYLOAD_TEMPLATE =
      """
      {
        "operationId": "10000000-0000-0000-0000-000000000001",
        "effectId": "10000000-0000-0000-0000-000000000011",
        "maintenanceLockIdentity": "10000000-0000-0000-0000-000000000004",
        "scope": %s,
        "operationTupleDigest": "%s",
        "evidenceIdentity": "evidence-owner-record-31"
      }
      """
          .strip();
  // Historical V1 writer payload for the retained resume tuple seeded by the migration fixture.
  private static final String V1_RESUME_TRANSITION_PAYLOAD_TEMPLATE =
      """
      {
        "operationId": "10000000-0000-0000-0000-000000000001",
        "scopeDigest": "%s",
        "verb": "RESUME",
        "expectedPhase": "AWAITING_RESUME",
        "maintenanceLockIdentity": "10000000-0000-0000-0000-000000000004",
        "evidenceRef": "immutable-initial-layout-evidence",
        "evidenceIdentity": "evidence-owner-record-31",
        "evidenceDigest": "%s"
      }
      """
          .strip();

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres())
          .withCommand(
              "postgres",
              "-c",
              "fsync=on",
              "-c",
              "log_error_verbosity=terse",
              "-c",
              "log_parameter_max_length_on_error=0",
              "-c",
              "log_parameter_max_length=0");

  private static Connection connection;
  private static DSLContext dsl;
  private static InitialGameplayReleaseTupleCodec tupleCodec;
  private static InitialGameplayReleaseJournal journal;

  private static Flyway journalFlyway(String jdbcUrl, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .table("flyway_schema_history_recovery_control")
            .placeholders(Map.of("serviceSchema", SCHEMA))
            .locations("classpath:db/recovery-control-migration");
    configuration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    if (target != null) {
      configuration.target(target);
    }
    return configuration.load();
  }

  private static void assertCompletePackagedMigrationReceipts(String jdbcUrl) {
    var flyway = journalFlyway(jdbcUrl, null);
    flyway.validate();
    assertThat(flyway.info().applied())
        .filteredOn(info -> info.getVersion() != null)
        .extracting(info -> info.getScript())
        .containsExactlyElementsOf(EXPECTED_MIGRATIONS);
  }

  @BeforeAll
  static void setUp() throws Exception {
    Flyway flyway = journalFlyway(POSTGRES.getJdbcUrl(), null);
    assertThat(flyway.info().all())
        .filteredOn(info -> info.getVersion() != null)
        .extracting(info -> info.getScript())
        .containsExactlyElementsOf(EXPECTED_MIGRATIONS);
    flyway.migrate();
    connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dsl = DSL.using(connection, SQLDialect.POSTGRES);
    tupleCodec = new InitialGameplayReleaseTupleCodec();
    journal = new JooqInitialGameplayReleaseJournal(dsl, tupleCodec);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (connection != null) {
      connection.close();
    }
  }

  @BeforeEach
  void clearJournal() {
    dsl.execute(
        "TRUNCATE TABLE recovery_control.initial_gameplay_release_attempt_closure, "
            + "recovery_control.initial_gameplay_release_preparation_receipt, "
            + "recovery_control.initial_gameplay_release_attempt_rejection, "
            + "recovery_control.initial_gameplay_release_attempt_local_no_send, "
            + "recovery_control.initial_gameplay_release_outbox, "
            + "recovery_control.initial_gameplay_release_transition, "
            + "recovery_control.initial_gameplay_release_operation");
  }

  @Test
  @org.junit.jupiter.api.extension.ExtendWith(
      org.springframework.boot.test.system.OutputCaptureExtension.class)
  @ResourceLock("packaged-recovery-control-fixture-database")
  void packagedJournalRefusesDatabaseErrorsWithoutExposingTheLockIdentity(
      org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try (var application = startPackagedApplication(true);
        var connection =
            DriverManager.getConnection(
                packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      DSLContext fixture = DSL.using(connection, SQLDialect.POSTGRES);
      String operationTable = "ALTER TABLE recovery_control.initial_gameplay_release_operation";
      fixture.execute(
          operationTable
              + " ADD CONSTRAINT fixture_requires_bound_phase CHECK (phase <> 'collecting')");
      int logStart = POSTGRES.getLogs().length();
      var injected = application.getBean(InitialGameplayReleaseJournal.class);
      Scope scope = scope();
      UUID lockIdentity = UUID.randomUUID();
      assertThatThrownBy(() -> injected.create(scope, lockIdentity))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Unable to create the recovery journal operation")
          .hasNoCause();
      assertThat(injected.find(scope.operationId())).isEmpty();
      fixture.execute(operationTable + " DROP CONSTRAINT fixture_requires_bound_phase");
      Operation collected = injected.create(scope, lockIdentity);
      fixture.execute(
          operationTable
              + " ADD CONSTRAINT fixture_requires_collecting CHECK (phase = 'collecting')");
      var evidence = evidence(scope);
      assertThatThrownBy(() ->
              injected.bindVerifiedEvidence(
                  scope,
                  tupleCodec.digest(scope),
                  evidence,
                  tupleCodec.digest(evidence),
                  Instant.now()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Unable to bind recovery journal evidence")
          .hasNoCause();
      assertThat(injected.find(scope.operationId())).contains(collected);
      fixture.execute(operationTable + " DROP CONSTRAINT fixture_requires_collecting");
      Instant now = Instant.now();
      Operation ready =
          injected.bindVerifiedEvidence(
              scope, tupleCodec.digest(scope), evidence, tupleCodec.digest(evidence), now);
      PublicTransitionTuple continuation = transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY);
      fixture.execute(
          operationTable
              + " ADD CONSTRAINT fixture_holds_transition CHECK (phase <> 'awaiting_resume')");
      assertThatThrownBy(() -> injected.transition(continuation, now.plusSeconds(1)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Unable to record recovery journal transition")
          .hasNoCause();
      assertThat(injected.findTransition(continuation)).isEmpty();
      assertThat(injected.find(scope.operationId())).contains(ready);
      fixture.execute(operationTable + " DROP CONSTRAINT fixture_holds_transition");
      assertThat(injected.transition(continuation, now.plusSeconds(1)).phase())
          .isEqualTo(Phase.AWAITING_RESUME);
      assertThat(injected.transition(continuation, now.plusSeconds(2)).phase())
          .isEqualTo(Phase.AWAITING_RESUME);

      Operation awaiting = injected.find(scope.operationId()).orElseThrow();
      injected.transition(transitionTuple(awaiting, TransitionVerb.RESUME), now.plusSeconds(3));
      OutboxEntry pending = injected.findNextEffect(scope.operationId()).orElseThrow();
      OutboxEntry dispatched =
          injected.claimEffect(
              scope.operationId(),
              pending.effect().effectId(),
              UUID.randomUUID(),
              () -> now.plusSeconds(4));
      var prepared =
          readbackFor(
              dispatched,
              dispatched.lastAttemptId(),
              EffectReadbackState.PREPARED_FENCED,
              "redaction-test-preparation",
              now.plusSeconds(5));
      fixture.execute(
          operationTable + " ADD CONSTRAINT fixture_holds_readback CHECK (phase <> 'releasing')");
      assertThatThrownBy(
              () -> injected.recordEffectReadback(dispatched, prepared, now.plusSeconds(6)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Unable to record recovery journal effect readback")
          .hasNoCause();
      assertThat(injected.findNextEffect(scope.operationId())).contains(dispatched);
      assertThat(injected.find(scope.operationId()))
          .get()
          .extracting(Operation::phase)
          .isEqualTo(Phase.RESUME_AUTHORIZED);
      assertThat(
              injected.findEffect(scope.operationId(), EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
          .isEmpty();
      fixture.execute(operationTable + " DROP CONSTRAINT fixture_holds_readback");
      assertThat(injected.recordEffectReadback(dispatched, prepared, now.plusSeconds(6)).phase())
          .isEqualTo(Phase.RELEASING);
      assertThat(injected.recordEffectReadback(dispatched, prepared, now.plusSeconds(7)).phase())
          .isEqualTo(Phase.RELEASING);
      assertThat(output.getAll()).doesNotContain(lockIdentity.toString());
      String completionMarker = "recovery-journal-redaction-" + UUID.randomUUID();
      dsl.execute("DO $$ BEGIN RAISE LOG '" + completionMarker + "'; END $$");
      String serverLog = awaitPostgresLogSince(logStart, completionMarker, Duration.ofSeconds(5));
      assertThat(serverLog)
          .contains("violates check constraint \"fixture_requires_bound_phase\"")
          .contains("violates check constraint \"fixture_requires_collecting\"")
          .contains("violates check constraint \"fixture_holds_transition\"")
          .contains("violates check constraint \"fixture_holds_readback\"")
          .contains(completionMarker)
          .doesNotContain(lockIdentity.toString());
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @ResourceLock("packaged-recovery-control-fixture-database")
  void journalReadinessRequiresUtf8AndLoggedTables(boolean unloggedTable) throws Exception {
    String database = "firemud_recovery_control";
    dsl.execute(
        "CREATE DATABASE "
            + database
            + (unloggedTable
                ? ""
                : " TEMPLATE template0 ENCODING 'LATIN1' LC_COLLATE 'C' LC_CTYPE 'C'"));
    String url = POSTGRES.getJdbcUrl();
    String packagedUrl = url.substring(0, url.lastIndexOf('/') + 1) + database;
    try (var application = startPackagedApplication(true);
        var connection =
            DriverManager.getConnection(
                packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      if (unloggedTable) {
        assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
        DSL.using(connection, SQLDialect.POSTGRES)
            .execute(
                "ALTER TABLE recovery_control.initial_gameplay_release_preparation_receipt SET UNLOGGED");
      }
      assertCompletePackagedMigrationReceipts(packagedUrl);
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.DOWN);
      assertThat(application.getBean(HealthEndpoint.class).healthForPath("liveness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.UP);
      var packaged = application.getBean(InitialGameplayReleaseJournal.class);
      var scope = scope();
      assertThatThrownBy(() -> packaged.create(scope, UUID.randomUUID()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Recovery journal requires durable PostgreSQL commits");
      assertThat(packaged.find(scope.operationId())).isEmpty();
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @Test
  @ResourceLock("packaged-recovery-control-fixture-database")
  void asynchronousJournalSessionsCannotQualifyOrClaimARelease() throws Exception {
    String database = "firemud_recovery_control";
    dsl.execute("CREATE DATABASE " + database);
    try {
      try (var application =
          startPackagedApplication(true, "--spring.datasource.hikari.maximum-pool-size=1")) {
        assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
        try (var session = application.getBean(DataSource.class).getConnection();
            var statement = session.createStatement()) {
          statement.execute("SET synchronous_commit TO off");
        }
        assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(application.getBean(HealthEndpoint.class).healthForPath("liveness"))
            .isNotNull()
            .extracting(result -> result.getStatus())
            .isEqualTo(Status.UP);
      }
      Instant base = Instant.parse("2026-09-01T00:00:00Z");
      OutboxEntry preparation = authorizedDispatch(base);
      var prepared =
          readbackFor(
              preparation,
              preparation.lastAttemptId(),
              EffectReadbackState.PREPARED_FENCED,
              "durable-preparation",
              base.plusSeconds(5));
      journal.recordEffectReadback(preparation, prepared, base.plusSeconds(5));
      UUID operationId = preparation.effect().operationId();
      OutboxEntry pending = journal.findNextEffect(operationId).orElseThrow();
      assertThat(pending.state()).isEqualTo(EffectOutboxState.PENDING);
      try (var connection =
          DriverManager.getConnection(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var asynchronous = DSL.using(connection, SQLDialect.POSTGRES);
        asynchronous.execute("SET synchronous_commit TO off");
        var unqualified = new JooqInitialGameplayReleaseJournal(asynchronous, tupleCodec);
        assertThatThrownBy(() ->
                unqualified.claimEffect(
                    operationId,
                    pending.effect().effectId(),
                    UUID.randomUUID(),
                    () -> base.plusSeconds(6)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Recovery journal requires durable PostgreSQL commits");
      }
      assertThat(journal.findNextEffect(operationId)).contains(pending);
      assertThat(
              journal
                  .claimEffect(
                      operationId,
                      pending.effect().effectId(),
                      UUID.randomUUID(),
                      () -> base.plusSeconds(6))
                  .state())
          .isEqualTo(EffectOutboxState.DISPATCHED);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @Test
  void credentialWritesRequireRedactedLoggingButExactTransitionReplayStillWorks() throws Exception {
    try (Connection unqualified =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      DSLContext unqualifiedDsl = DSL.using(unqualified, SQLDialect.POSTGRES);
      unqualifiedDsl.execute("SET log_error_verbosity = 'default'");
      var unqualifiedJournal = new JooqInitialGameplayReleaseJournal(unqualifiedDsl, tupleCodec);
      Scope scope = scope();

      assertThatThrownBy(() -> unqualifiedJournal.create(scope, UUID.randomUUID()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Recovery journal requires redacted PostgreSQL logs");
      assertThat(journal.find(scope.operationId())).isEmpty();

      Instant now = Instant.now();
      journal.create(scope, UUID.randomUUID());
      InitialGameplayReleaseEvidence proof = evidence(scope);
      Operation ready =
          journal.bindVerifiedEvidence(
              scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), now);
      PublicTransitionTuple continuation = transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY);
      PublicTransitionResult continued = journal.transition(continuation, now);
      assertThat(unqualifiedJournal.transition(continuation, now)).isEqualTo(continued);

      PublicTransitionTuple resume = transitionTuple(ready, TransitionVerb.RESUME);
      assertThatThrownBy(() -> unqualifiedJournal.transition(resume, now))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Recovery journal requires redacted PostgreSQL logs");
      assertThat(journal.findTransition(resume)).isEmpty();
      assertThat(journal.findNextEffect(scope.operationId())).isEmpty();

      journal.transition(resume, now);
      OutboxEntry pending = journal.findNextEffect(scope.operationId()).orElseThrow();
      OutboxEntry claimed =
          journal.claimEffect(
              scope.operationId(), pending.effect().effectId(), UUID.randomUUID(), () -> now);
      EffectReadback prepared =
          readbackFor(
              claimed,
              claimed.lastAttemptId(),
              EffectReadbackState.PREPARED_FENCED,
              "prepared",
              now);
      assertThatThrownBy(() -> unqualifiedJournal.recordEffectReadback(claimed, prepared, now))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Recovery journal requires redacted PostgreSQL logs");
      assertThat(journal.findNextEffect(scope.operationId())).contains(claimed);
      assertThat(
              journal.findEffect(scope.operationId(), EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
          .isEmpty();
      assertThat(journal.recordEffectReadback(claimed, prepared, now).phase())
          .isEqualTo(Phase.RELEASING);
    }
  }

  @Test
  @ResourceLock("packaged-recovery-control-fixture-database")
  void packagedApplicationMigratesAndPersistsThroughTheInjectedJournal() throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try (var application =
            startPackagedApplication(
                true,
                "--spring.datasource.hikari.maximum-pool-size=1",
                "--spring.datasource.hikari.connection-timeout=1000",
                "--spring.sql.init.mode=always",
                "--spring.sql.init.schema-locations=classpath:/fixture-missing-recovery-init.sql");
        Connection packagedConnection =
            DriverManager.getConnection(
                packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      DSLContext packagedDsl = DSL.using(packagedConnection, SQLDialect.POSTGRES);
      HikariDataSource pool = application.getBean(HikariDataSource.class);
      assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
      assertThat(application.getBean(Flyway.class).getConfiguration().getDataSource())
          .isSameAs(pool);
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
      assertThat(application.getBean(HealthEndpoint.class).healthForPath("readiness"))
          .isNotNull()
          .extracting(health -> health.getStatus())
          .isEqualTo(Status.UP);
      assertCompletePackagedMigrationReceipts(packagedUrl);
      assertValidatedDispatchAttemptConstraint(packagedDsl);

      InitialGameplayReleaseJournal injected =
          application.getBean(InitialGameplayReleaseJournal.class);
      Scope requested = scope();
      UUID lockIdentity = UUID.randomUUID();
      Operation created = injected.create(requested, lockIdentity);

      assertThat(created.phase()).isEqualTo(Phase.COLLECTING);
      assertThat(injected.find(requested.operationId())).contains(created);
      Scope duplicateCredentialScope = scope();
      assertThatThrownBy(() -> injected.create(duplicateCredentialScope, lockIdentity))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessage("IDEMPOTENCY_CONFLICT: maintenance-lock identity is already bound")
          .hasNoCause();
      assertThat(injected.find(duplicateCredentialScope.operationId())).isEmpty();
      assertThat(injected.create(requested, UUID.randomUUID())).isEqualTo(created);
      assertThat(
              packagedDsl
                  .fetchSingle(
                      "SELECT maintenance_lock_identity FROM recovery_control.initial_gameplay_release_operation WHERE operation_id=?",
                      requested.operationId())
                  .get(0, UUID.class))
          .isEqualTo(lockIdentity);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @Test
  @ResourceLock("packaged-recovery-control-fixture-database")
  void packagedV7RollsBackSchemaWhenHistoryCannotBeRecordedAndRetriesCleanly() throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try {
      journalFlyway(packagedUrl, "6").migrate();
      try (var packagedConnection =
          DriverManager.getConnection(
              packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var packagedDsl = DSL.using(packagedConnection, SQLDialect.POSTGRES);
        seedImmutableV1Journal(packagedDsl, false, Instant.parse("2026-09-01T00:00:00Z"));
        String retained = retainedOutboxRow(packagedDsl);
        packagedDsl.execute(
            """
            ALTER TABLE recovery_control.flyway_schema_history_recovery_control
            ADD CONSTRAINT fixture_reject_v7_history CHECK (version IS DISTINCT FROM '7')
            """);

        assertThatThrownBy(
                () -> {
                  try (var ignored = startPackagedApplication(true)) {
                    throw new AssertionError("V7 must fail when its history insert is refused");
                  }
                })
            .hasStackTraceContaining("fixture_reject_v7_history");
        assertThat(
                packagedDsl
                    .fetchSingle(
                        """
                        SELECT to_regclass(
                            'recovery_control.initial_gameplay_release_preparation_receipt') IS NULL
                          AND NOT EXISTS (
                            SELECT 1 FROM recovery_control.flyway_schema_history_recovery_control
                            WHERE version = '7')
                        """)
                    .get(0, Boolean.class))
            .isTrue();
        assertThat(retainedOutboxRow(packagedDsl)).isEqualTo(retained);

        packagedDsl.execute(
            """
            ALTER TABLE recovery_control.flyway_schema_history_recovery_control
            DROP CONSTRAINT fixture_reject_v7_history
            """);
        try (var application =
            startPackagedApplication(true, "--spring.flyway.postgresql.transactional-lock=true")) {
          var configuration = application.getBean(Flyway.class).getConfiguration();
          assertThat(
                  configuration
                      .getPluginRegister()
                      .getExact(PostgreSQLConfigurationExtension.class)
                      .isTransactionalLock())
              .isFalse();
          assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
          assertCompletePackagedMigrationReceipts(packagedUrl);
          assertThat(retainedOutboxRow(packagedDsl)).isEqualTo(retained);
        }
      }
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @Test
  @ResourceLock("packaged-recovery-control-fixture-database")
  void readinessRefusesAnIncompatibleOutboxPayloadColumn() {
    String database = "firemud_recovery_control";
    dsl.execute("CREATE DATABASE " + database);
    try (var application = startPackagedApplication(true)) {
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
      application
          .getBean(DSLContext.class)
          .execute(
              """
              ALTER TABLE recovery_control.initial_gameplay_release_outbox
              ALTER COLUMN payload TYPE integer USING NULL::integer
              """);
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.DOWN);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @Test
  @ResourceLock("packaged-recovery-control-fixture-database")
  void packagedFlywayBoundsOutboxLockWaitAndRetriesWithoutLeakingSessionTimeout() throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try {
      // Bound the fixture independently if the migration loses its narrower lock timeout.
      dsl.execute("ALTER DATABASE " + database + " SET statement_timeout TO '20s'");
      journalFlyway(packagedUrl, "5").migrate();
      try (var fixture =
              startPackagedApplication(false, "--spring.datasource.hikari.maximum-pool-size=1");
          var inspection =
              DriverManager.getConnection(
                  packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var pool = fixture.getBean(HikariDataSource.class);
        var configuration =
            Flyway.configure()
                .configuration(journalFlyway(packagedUrl, null).getConfiguration())
                .dataSource(pool)
                .callbacks(
                    fixture.getBean("recoveryTransactionalMigrationCallback", Callback.class));
        var flyway = configuration.load();
        int backend;
        try (var pooled = pool.getConnection()) {
          var session = DSL.using(pooled, SQLDialect.POSTGRES);
          backend = session.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class);
          session.execute("SET lock_timeout TO '12s'");
        }
        inspection.setAutoCommit(false);
        var inspector = DSL.using(inspection, SQLDialect.POSTGRES);
        inspector.execute(
            "LOCK TABLE recovery_control.initial_gameplay_release_outbox IN ACCESS SHARE MODE");
        try {
          assertThatThrownBy(flyway::migrate)
              .hasStackTraceContaining("canceling statement due to lock timeout");
          try (var pooled = pool.getConnection()) {
            var session = DSL.using(pooled, SQLDialect.POSTGRES);
            assertThat(session.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class))
                .isEqualTo(backend);
            assertThat(session.fetchSingle("SHOW lock_timeout").get(0, String.class))
                .isEqualTo("12s");
          }
          assertThat(
                  inspector
                      .fetchSingle(
                          "SELECT max(version::integer) FROM recovery_control.flyway_schema_history_recovery_control WHERE success")
                      .get(0, Integer.class))
              .isEqualTo(5);
        } finally {
          inspection.rollback();
        }
      }
      try (var application =
          startPackagedApplication(true, "--spring.datasource.hikari.maximum-pool-size=1")) {
        assertCompletePackagedMigrationReceipts(packagedUrl);
        assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
        try (var pooledConnection = application.getBean(HikariDataSource.class).getConnection()) {
          assertThat(
                  DSL.using(pooledConnection, SQLDialect.POSTGRES)
                      .fetchSingle("SHOW lock_timeout")
                      .get(0, String.class))
              .isEqualTo("0");
        }
      }
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "locations, locations must contain only classpath:db/recovery-control-migration",
    "group, must commit each migration separately",
    "datasource, must use the journal datasource",
    "validate, must validate migrations before applying them",
    "skip, must execute migrations before recording them"
  })
  @ResourceLock("packaged-recovery-control-fixture-database")
  void packagedFlywayRejectsDriftFromCustomizerBeforeMigration(String option, String diagnostic)
      throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try {
      assertThatThrownBy(
              () -> {
                try (var ignored =
                    startPackagedApplication(
                        true,
                        DriftedFlywayConfiguration.class,
                        "--fixture.flyway.drift=" + option)) {
                  throw new AssertionError("Effective Flyway drift must refuse startup");
                }
              })
          .hasStackTraceContaining("Recovery Flyway " + diagnostic);
      try (var connection =
          DriverManager.getConnection(
              packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
        assertThat(
                DSL.using(connection, SQLDialect.POSTGRES)
                    .fetchSingle("SELECT to_regnamespace('recovery_control') IS NULL")
                    .get(0, Boolean.class))
            .isTrue();
      }
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class DriftedFlywayConfiguration {
    @Bean
    FlywayConfigurationCustomizer driftedFlyway(Environment environment) {
      return configuration -> {
        switch (environment.getRequiredProperty("fixture.flyway.drift")) {
          case "locations" -> configuration.locations("classpath:unqualified-recovery-migrations");
          case "group" -> configuration.group(true);
          case "datasource" ->
              configuration.dataSource(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
          case "validate" -> configuration.validateOnMigrate(false);
          case "skip" -> configuration.skipExecutingMigrations(true);
          default -> throw new AssertionError("Unexpected fixture customization");
        }
      };
    }
  }

  @Test
  @ResourceLock("packaged-recovery-control-fixture-database")
  void packagedFlywayRefusesMixedModeAfterAllMigrationsAreApplied() throws Exception {
    String database = "firemud_recovery_control";
    dsl.execute("CREATE DATABASE " + database);
    try {
      try (var application = startPackagedApplication(true)) {
        assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
      }
      assertThatThrownBy(
              () -> {
                try (var ignored = startPackagedApplication(true, "--spring.flyway.mixed=true")) {
                  throw new AssertionError("Flyway mixed mode must be refused at startup");
                }
              })
          .hasStackTraceContaining(
              "spring.flyway.mixed must be disabled for the recovery migrations");
      try (var application = startPackagedApplication(false, "--spring.flyway.mixed=true")) {
        assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(application.getBean(ApplicationAvailability.class).getLivenessState())
            .isEqualTo(LivenessState.CORRECT);
      }
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @Test
  @ResourceLock("packaged-recovery-control-fixture-database")
  void packagedFlywayGuardRequiresTransactionalMigrations(@TempDir Path migrations)
      throws Exception {
    String database = "firemud_recovery_control";
    dsl.execute("CREATE DATABASE " + database);
    try (var application = startPackagedApplication(true)) {
      var packaged = application.getBean(Flyway.class);
      var callback = application.getBean("recoveryTransactionalMigrationCallback", Callback.class);
      assertThat(packaged.getConfiguration().getCallbacks()).contains(callback);
      Files.writeString(
          migrations.resolve("V1__transactional_fixture.sql"),
          """
          CREATE TABLE sample (id integer);
          LOCK TABLE sample IN ACCESS EXCLUSIVE MODE;
          CREATE INDEX sample_ordinary_idx ON sample (id);
          """);
      var configuration =
          Flyway.configure()
              .dataSource(packaged.getConfiguration().getDataSource())
              .schemas("transactional_fixture")
              .locations("filesystem:" + migrations)
              .callbacks(callback)
              .executeInTransaction(true)
              .mixed(false);
      assertThat(configuration.load().migrate().migrationsExecuted).isEqualTo(1);
      Files.writeString(
          migrations.resolve("V2__requires_nontransactional_mode.sql"),
          "CREATE INDEX CONCURRENTLY sample_concurrent_idx ON sample (id);");
      assertThatThrownBy(() -> configuration.load().migrate())
          .isInstanceOf(FlywayException.class)
          .hasStackTraceContaining("Recovery migration does not support transactional execution");
      try (var connection = configuration.getDataSource().getConnection()) {
        var fixture = DSL.using(connection, SQLDialect.POSTGRES);
        assertThat(
                fixture
                    .fetchSingle(
                        "SELECT to_regclass('transactional_fixture.sample_ordinary_idx') IS NOT NULL"
                            + " AND to_regclass('transactional_fixture.sample_concurrent_idx') IS NULL")
                    .get(0, Boolean.class))
            .isEqualTo(true);
      }
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @ParameterizedTest(name = "interrupt after owner apply: {0}")
  @ValueSource(booleans = {false, true})
  @ResourceLock("packaged-recovery-control-fixture-database")
  void packagedControllerAppliesOnlyAfterTheExactClaimIsCommitted(boolean interruptAfterApply) {
    String database = "firemud_recovery_control";
    dsl.execute("CREATE DATABASE " + database);
    try {
      qualifyPackagedClaim(interruptAfterApply);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  private void qualifyPackagedClaim(boolean interruptAfterApply) {
    Scope scope = scope();
    UUID operationId = scope.operationId();
    ClaimVisibilityPort port;
    OutboxEntry dispatched;
    try (var application = startPackagedApplication(true, ClaimVisibilityConfiguration.class)) {
      var packagedJournal = application.getBean(InitialGameplayReleaseJournal.class);
      var controller = application.getBean(InitialGameplayReleaseController.class);
      port = application.getBean(ClaimVisibilityPort.class);
      port.interruptAfterApply = interruptAfterApply;
      Instant base = Instant.now().minusSeconds(5);
      Operation collecting = packagedJournal.create(scope, UUID.randomUUID());
      InitialGameplayReleaseEvidence proof = evidence(scope);
      var transaction =
          new TransactionTemplate(application.getBean(PlatformTransactionManager.class));
      Scope nestedCreation = scope();
      assertThat(nestedCreation.operationId()).isNotEqualTo(operationId);
      transaction.executeWithoutResult(status -> {
        assertThatThrownBy(() -> packagedJournal.create(nestedCreation, UUID.randomUUID()))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("operation creation requires its own transaction");
        assertThatThrownBy(() ->
                packagedJournal.bindVerifiedEvidence(
                    scope,
                    tupleCodec.digest(scope),
                    proof,
                    tupleCodec.digest(proof),
                    base.plusSeconds(1)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("readiness evidence binding requires its own transaction");
        status.setRollbackOnly();
      });
      assertThat(packagedJournal.find(operationId)).contains(collecting);
      assertThat(packagedJournal.find(nestedCreation.operationId())).isEmpty();
      UUID nestedLock = UUID.randomUUID();
      Operation created = packagedJournal.create(nestedCreation, nestedLock);
      assertThat(created.maintenanceLockIdentity()).isEqualTo(nestedLock);
      assertThat(packagedJournal.create(nestedCreation, UUID.randomUUID())).isEqualTo(created);

      Operation ready =
          packagedJournal.bindVerifiedEvidence(
              scope,
              tupleCodec.digest(scope),
              proof,
              tupleCodec.digest(proof),
              base.plusSeconds(1));
      assertThat(packagedJournal.find(operationId)).contains(ready);
      packagedJournal.transition(
          transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY), base.plusSeconds(2));
      Operation awaiting = packagedJournal.find(operationId).orElseThrow();
      PublicTransitionTuple resume = transitionTuple(awaiting, TransitionVerb.RESUME);
      transaction.executeWithoutResult(status -> {
        assertThatThrownBy(() -> packagedJournal.transition(resume, base.plusSeconds(3)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("public transition requires its own transaction");
        status.setRollbackOnly();
      });
      assertThat(packagedJournal.findTransition(resume)).isEmpty();
      assertThat(packagedJournal.find(operationId)).contains(awaiting);
      assertThat(packagedJournal.findNextEffect(operationId)).isEmpty();

      assertThat(packagedJournal.transition(resume, base.plusSeconds(3)).phase())
          .isEqualTo(Phase.RESUME_AUTHORIZED);
      OutboxEntry pending = packagedJournal.findNextEffect(operationId).orElseThrow();

      transaction.executeWithoutResult(status -> {
        assertThatThrownBy(() -> controller.reconcileNextEffect(operationId))
            .isInstanceOf(OwnerBoundaryUnavailableException.class)
            .hasMessage("INITIAL_GAMEPLAY_RELEASE_RECONCILIATION_REQUIRES_NO_CALLER_TRANSACTION");
        assertThat(port.applies).isZero();
      });
      assertThat(packagedJournal.findNextEffect(operationId)).contains(pending);

      if (!interruptAfterApply) {
        controller.reconcileNextEffect(operationId);
        assertThat(port.applies).isEqualTo(1);
        assertThat(packagedJournal.findNextEffect(operationId))
            .get()
            .extracting(OutboxEntry::state)
            .isEqualTo(EffectOutboxState.RETRYABLE);
        return;
      }
      assertThatThrownBy(() -> controller.reconcileNextEffect(operationId))
          .isInstanceOf(SimulatedControllerInterruption.class);
      assertThat(port.applies).isEqualTo(1);
      assertThat(port.successfulReadbacks).isZero();
      dispatched = packagedJournal.findNextEffect(operationId).orElseThrow();
      assertThat(dispatched.state()).isEqualTo(EffectOutboxState.DISPATCHED);
      assertThat(dispatched.lastAttemptId()).isEqualTo(port.applied.attemptId());
      assertThat(dispatched.attemptCount()).isEqualTo(port.applied.attemptOrdinal());
      transaction.executeWithoutResult(status -> {
        assertThatThrownBy(() ->
                packagedJournal.recordRetryableEffectForDispatch(
                    dispatched, RetrySource.OWNER_REJECTED, "OWNER_REJECTED", Instant.now()))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("effect outcome requires its own transaction");
        status.setRollbackOnly();
      });
      Operation authorized = packagedJournal.find(operationId).orElseThrow();
      assertThat(authorized.phase()).isEqualTo(Phase.RESUME_AUTHORIZED);
      transaction.executeWithoutResult(status -> {
        assertThatThrownBy(
                () -> packagedJournal.recordEffectReadback(dispatched, port.applied, Instant.now()))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("effect readback requires its own transaction");
      });
      assertThat(packagedJournal.find(operationId)).contains(authorized);
      assertThat(packagedJournal.findNextEffect(operationId)).contains(dispatched);
      assertThat(
              packagedJournal.findEffect(operationId, EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
          .isEmpty();
    }

    // Only the disposable owner survives the first controller context.
    try (var reopened = startPackagedApplication(true)) {
      var reopenedJournal = reopened.getBean(InitialGameplayReleaseJournal.class);
      var reopenedController =
          new InitialGameplayReleaseController(
              reopenedJournal,
              reopened.getBean(InitialGameplayReleaseTupleCodec.class),
              reopened.getBean(InitialGameplayReleaseEvidenceProvider.class),
              port);
      assertThat(reopenedJournal.findNextEffect(operationId)).contains(dispatched);
      assertThat(reopenedController.reconcileNextEffect(operationId).phase())
          .isEqualTo(Phase.RELEASING);
      assertThat(port.applies).isEqualTo(1);
      assertThat(port.successfulReadbacks).isEqualTo(1);
      var acknowledged =
          reopenedJournal.findEffect(operationId, dispatched.effect().kind()).orElseThrow();
      assertThat(acknowledged.state()).isEqualTo(EffectOutboxState.ACKNOWLEDGED);
      assertThat(acknowledged.lastAttemptId()).isEqualTo(dispatched.lastAttemptId());
      assertThat(acknowledged.attemptCount()).isEqualTo(dispatched.attemptCount());
      assertThat(acknowledged.lastOutcomeCode()).isEqualTo(port.applied.state().name());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClaimVisibilityConfiguration {
    @Bean
    ClaimVisibilityPort claimVisibilityPort() {
      return new ClaimVisibilityPort();
    }
  }

  private static final class ClaimVisibilityPort implements InitialGameplayReleaseEffectPort {
    private boolean interruptAfterApply;
    private int applies;
    private int successfulReadbacks;
    private EffectReadback applied;

    @Override
    public EffectApplyResult apply(TypedEffect effect, UUID attemptId, int attemptOrdinal) {
      applies++;
      String fixtureUrl = POSTGRES.getJdbcUrl();
      String packagedUrl =
          fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + "firemud_recovery_control";
      try (var observer =
          DriverManager.getConnection(
              packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var visible =
            DSL.using(observer, SQLDialect.POSTGRES)
                .fetchSingle(
                    """
                    SELECT state, last_attempt_id, attempt_count, payload::text, payload_digest
                    FROM recovery_control.initial_gameplay_release_outbox
                    WHERE operation_id = ? AND effect_id = ?
                    """,
                    effect.operationId(),
                    effect.effectId());
        assertThat(visible.get(0, String.class)).isEqualTo("dispatched");
        assertThat(visible.get(1, UUID.class)).isEqualTo(attemptId);
        assertThat(attemptOrdinal).isEqualTo(1);
        assertThat(visible.get(2, Integer.class)).isEqualTo(attemptOrdinal);
        assertThat(
                tupleCodec.readEffect(effect.kind().databaseValue(), visible.get(3, String.class)))
            .isEqualTo(effect);
        if (interruptAfterApply) {
          applied =
              new EffectReadback(
                  effect.operationId(),
                  effect.effectId(),
                  attemptId,
                  attemptOrdinal,
                  effect.kind(),
                  effect.operationTupleDigest(),
                  visible.get(4, String.class),
                  EffectReadbackState.PREPARED_FENCED,
                  EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
                  "packaged-fixture-preparation-applied",
                  Instant.now());
          return EffectApplyResult.applied();
        }
      } catch (java.sql.SQLException exception) {
        throw new AssertionError("Could not read the packaged claim independently", exception);
      }
      return EffectApplyResult.notDispatched("DISPOSABLE_PORT_DID_NOT_SEND");
    }

    @Override
    public EffectReadbackResolution readback(
        TypedEffect effect, UUID attemptId, int attemptOrdinal) {
      assertThat(applied).isNotNull();
      assertThat(effect.operationId()).isEqualTo(applied.operationId());
      assertThat(effect.effectId()).isEqualTo(applied.effectId());
      assertThat(effect.kind()).isEqualTo(applied.effectKind());
      assertThat(effect.operationTupleDigest()).isEqualTo(applied.operationTupleDigest());
      assertThat(attemptId).isEqualTo(applied.attemptId());
      assertThat(attemptOrdinal).isEqualTo(applied.attemptOrdinal());
      if (interruptAfterApply) {
        interruptAfterApply = false;
        throw new SimulatedControllerInterruption();
      }
      successfulReadbacks++;
      return EffectReadbackResolution.available(applied);
    }
  }

  private static final class SimulatedControllerInterruption extends AssertionError {}

  @Test
  @ResourceLock("packaged-recovery-control-fixture-database")
  void reachableEmptyPackagedDatabaseIsNotJournalReadyButProcessIsLive() throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try (var application = startPackagedApplication(false);
        var connection =
            DriverManager.getConnection(
                packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      assertThat(connection.isValid(2)).isTrue();
      assertThat(application.getBean(ApplicationAvailability.class).getLivenessState())
          .isEqualTo(LivenessState.CORRECT);
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.DOWN);
      var health = application.getBean(HealthEndpoint.class);
      assertThat(health.healthForPath("readiness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.DOWN);
      assertThat(health.healthForPath("liveness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.UP);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "rejection_key",
        "rejection_outbox_unique",
        "local_no_send_key",
        "maintenance_lock",
        "finalized_at_default",
        "outbox_dispatch",
        "weakened_outbox_dispatch",
        "pending_dispatch_timestamp",
        "weakened_pending_dispatch_timestamp",
        "closure_primary_key",
        "closure_outbox_unique"
      })
  @ResourceLock("packaged-recovery-control-fixture-database")
  void migratedPackagedDatabaseWithMissingOrWeakenedRequiredConstraintIsNotReady(String drift)
      throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try (var application = startPackagedApplication(true);
        var connection =
            DriverManager.getConnection(
                packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
      var packaged = DSL.using(connection, SQLDialect.POSTGRES);
      if ("rejection_key".equals(drift)) {
        packaged.execute(
            "ALTER TABLE recovery_control.initial_gameplay_release_attempt_rejection"
                + " DROP CONSTRAINT initial_gameplay_release_attempt_rejection_pkey");
      } else if ("rejection_outbox_unique".equals(drift)) {
        packaged.execute(
            "ALTER TABLE recovery_control.initial_gameplay_release_attempt_rejection"
                + " ADD UNIQUE (outbox_id)");
      } else if ("local_no_send_key".equals(drift)) {
        packaged.execute(
            "ALTER TABLE recovery_control.initial_gameplay_release_attempt_local_no_send"
                + " DROP CONSTRAINT initial_gameplay_release_attempt_local_no_send_pkey");
      } else if ("finalized_at_default".equals(drift)) {
        packaged.execute(
            "ALTER TABLE recovery_control.initial_gameplay_release_operation"
                + " ALTER COLUMN finalized_at SET DEFAULT CURRENT_TIMESTAMP");
      } else if ("outbox_dispatch".equals(drift)) {
        packaged.execute(
            "ALTER TABLE recovery_control.initial_gameplay_release_outbox"
                + " DROP CONSTRAINT ck_release_outbox_non_pending_attempt");
      } else if ("weakened_outbox_dispatch".equals(drift)) {
        packaged.execute(
            """
            ALTER TABLE recovery_control.initial_gameplay_release_outbox
              DROP CONSTRAINT ck_release_outbox_non_pending_attempt,
              ADD CONSTRAINT ck_release_outbox_non_pending_attempt CHECK (true)
            """);
        assertValidatedDispatchAttemptConstraint(packaged);
      } else if ("pending_dispatch_timestamp".equals(drift)) {
        packaged.execute(
            "ALTER TABLE recovery_control.initial_gameplay_release_outbox"
                + " DROP CONSTRAINT ck_release_outbox_pending_dispatch_timestamp");
      } else if ("weakened_pending_dispatch_timestamp".equals(drift)) {
        packaged.execute(
            """
            ALTER TABLE recovery_control.initial_gameplay_release_outbox
              DROP CONSTRAINT ck_release_outbox_pending_dispatch_timestamp,
              ADD CONSTRAINT ck_release_outbox_pending_dispatch_timestamp
                CHECK (state <> 'pending' OR last_dispatched_at IS NULL)
            """);
      } else if ("closure_primary_key".equals(drift)) {
        packaged.execute(
            "ALTER TABLE recovery_control.initial_gameplay_release_attempt_closure"
                + " DROP CONSTRAINT initial_gameplay_release_attempt_closure_pkey");
      } else if ("closure_outbox_unique".equals(drift)) {
        packaged.execute(
            "ALTER TABLE recovery_control.initial_gameplay_release_attempt_closure"
                + " ADD UNIQUE (outbox_id)");
      } else {
        String lockKey =
            packaged
                .fetchSingle(
                    """
                    SELECT conname FROM pg_catalog.pg_constraint
                    WHERE conrelid = 'recovery_control.initial_gameplay_release_operation'::regclass
                      AND contype = 'u'
                      AND pg_get_constraintdef(oid) = 'UNIQUE (maintenance_lock_identity)'
                    """)
                .get(0, String.class);
        packaged
            .alterTable(DSL.name(SCHEMA, "initial_gameplay_release_operation"))
            .dropConstraint(DSL.name(lockKey))
            .execute();
      }
      assertCompletePackagedMigrationReceipts(packagedUrl);
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.DOWN);
      assertThat(application.getBean(HealthEndpoint.class).healthForPath("readiness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.DOWN);
      assertThat(application.getBean(HealthEndpoint.class).healthForPath("liveness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.UP);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "last_dispatched_at",
        "last_outcome_code",
        "retry_provenance",
        "send_outcome_uncertain"
      })
  @ResourceLock("packaged-recovery-control-fixture-database")
  void migratedPackagedDatabaseWithMissingOutboxColumnIsNotReady(String column) throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try (var application = startPackagedApplication(true);
        var packagedConnection =
            DriverManager.getConnection(
                packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
      var packagedDsl = DSL.using(packagedConnection, SQLDialect.POSTGRES);
      packagedDsl.execute(
          "ALTER TABLE recovery_control.initial_gameplay_release_outbox RENAME COLUMN "
              + switch (column) {
                case "last_dispatched_at" -> "last_dispatched_at";
                case "last_outcome_code" -> "last_outcome_code";
                case "retry_provenance" -> "retry_provenance";
                case "send_outcome_uncertain" -> "send_outcome_uncertain";
                default -> throw new AssertionError("Unexpected test fixture column");
              }
              + " TO fixture_missing_column");
      assertCompletePackagedMigrationReceipts(packagedUrl);
      var health = application.getBean(HealthEndpoint.class);
      assertThat(health.healthForPath("readiness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.DOWN);
      assertThat(health.healthForPath("liveness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.UP);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "payload_digest, TYPE char(63)",
    "payload_digest, TYPE varchar(64)",
    "payload_digest, DROP NOT NULL",
    "send_outcome_uncertain, SET NOT NULL",
    "send_outcome_uncertain, SET DEFAULT true",
    "send_outcome_uncertain, TYPE recovery_control.fixture_uncertainty_flag",
    "retry_provenance, SET NOT NULL",
    "retry_provenance, TYPE varchar(1)",
    "retry_provenance, SET DEFAULT 'local_no_send'",
    "last_outcome_code, SET NOT NULL",
    "last_outcome_code, TYPE varchar(63)",
    "last_outcome_code, SET DEFAULT 'UNSENT'",
    "attempt_count, DROP NOT NULL",
    "last_attempt_id, SET NOT NULL",
    "last_attempt_id, SET DEFAULT gen_random_uuid()",
    "acknowledged_at, SET NOT NULL",
    "acknowledged_at, TYPE timestamptz(3)",
    "last_dispatched_at, TYPE timestamptz(3)",
    "last_dispatched_at, SET DEFAULT CURRENT_TIMESTAMP",
    "acknowledged_at, SET DEFAULT CURRENT_TIMESTAMP",
    "updated_at, TYPE timestamptz(3)",
    "updated_at, TYPE timestamptz(6)",
    "preparation_observation_payload, SET DEFAULT jsonb_build_object()",
    "preparation_observation_digest, SET DEFAULT 'a'",
    "state, TYPE varchar(10)",
    "effect_id, DROP NOT NULL",
    "effect_id, TYPE text USING effect_id::text",
    "readback_identity, TYPE varchar(4)",
    "readback_identity, SET DEFAULT 'fixture-default'",
    "readback_digest, TYPE char(63)",
    "readback_digest, SET NOT NULL",
    "readback_digest, SET DEFAULT 'fixture-default'",
    "readback_payload, SET DEFAULT jsonb_build_object()"
  })
  @ResourceLock("packaged-recovery-control-fixture-database")
  void outboxColumnsMustHaveRequiredShapeForJournalReadiness(String column, String alteration)
      throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try (var application = startPackagedApplication(true);
        var connection =
            DriverManager.getConnection(
                packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
      var packaged = DSL.using(connection, SQLDialect.POSTGRES);
      // A boolean domain keeps the active pending-outcome check valid during this type change.
      packaged.execute("CREATE DOMAIN recovery_control.fixture_uncertainty_flag AS boolean");
      packaged.execute(
          "ALTER TABLE recovery_control.initial_gameplay_release_outbox"
              + " ALTER COLUMN "
              + column
              + " "
              + alteration);
      assertCompletePackagedMigrationReceipts(packagedUrl);
      var health = application.getBean(HealthEndpoint.class);
      assertThat(health.healthForPath("readiness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(
              "updated_at".equals(column) && "TYPE timestamptz(6)".equals(alteration)
                  ? Status.UP
                  : Status.DOWN);
      assertThat(health.healthForPath("liveness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.UP);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "attempt_closure, attempt_id, TYPE text USING attempt_id::text",
    "attempt_closure, readback_digest, TYPE char(63)",
    "attempt_closure, readback_digest, TYPE varchar(64)",
    "attempt_closure, readback_digest, DROP NOT NULL",
    "attempt_closure, dispatched_at, TYPE timestamptz(0)",
    "attempt_local_no_send, recorded_at, TYPE timestamptz(0)",
    "transition, expected_phase, TYPE varchar(1)",
    "transition, result_phase, DROP NOT NULL"
  })
  @ResourceLock("packaged-recovery-control-fixture-database")
  void receiptColumnsMustHaveRequiredShapeForJournalReadiness(
      String tableSuffix, String column, String alteration) throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try (var application = startPackagedApplication(true);
        var connection =
            DriverManager.getConnection(
                packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
      DSL.using(connection, SQLDialect.POSTGRES)
          .execute(
              "ALTER TABLE recovery_control.initial_gameplay_release_"
                  + tableSuffix
                  + " ALTER COLUMN "
                  + column
                  + " "
                  + alteration);
      assertCompletePackagedMigrationReceipts(packagedUrl);
      var health = application.getBean(HealthEndpoint.class);
      assertThat(health.healthForPath("readiness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.DOWN);
      assertThat(health.healthForPath("liveness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.UP);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DROP DEFAULT",
        "SET DEFAULT TIMESTAMPTZ '2000-01-01 00:00:00+00'",
        "DROP NOT NULL",
        "TYPE timestamp without time zone"
      })
  @ResourceLock("packaged-recovery-control-fixture-database")
  void transitionCreatedAtMustHaveRequiredShapeForJournalReadiness(String alteration)
      throws Exception {
    assertTransitionColumnDriftKeepsJournalUnready("created_at", alteration);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DROP NOT NULL", "TYPE varchar(16) USING tuple_payload::varchar(16)"})
  @ResourceLock("packaged-recovery-control-fixture-database")
  void transitionPayloadMustBeNotNullJsonbForJournalReadiness(String alteration) throws Exception {
    assertTransitionColumnDriftKeepsJournalUnready("tuple_payload", alteration);
  }

  private static void assertTransitionColumnDriftKeepsJournalUnready(
      String column, String alteration) throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try (var application = startPackagedApplication(true);
        var connection =
            DriverManager.getConnection(
                packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
      DSL.using(connection, SQLDialect.POSTGRES)
          .execute(
              "ALTER TABLE recovery_control.initial_gameplay_release_transition"
                  + " ALTER COLUMN "
                  + column
                  + " "
                  + alteration);
      var health = application.getBean(HealthEndpoint.class);
      assertThat(health.healthForPath("readiness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.DOWN);
      assertThat(health.healthForPath("liveness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.UP);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @ResourceLock("packaged-recovery-control-fixture-database")
  void unhealthyPoolCannotQualifyWritablePackagedDatabase(boolean readOnly) throws Exception {
    String database = "firemud_recovery_control";
    dsl.execute("CREATE DATABASE " + database);
    try {
      try (var migration = startPackagedApplication(true)) {
        assertThat(journalHealth(migration).health().getStatus()).isEqualTo(Status.UP);
      }
      try (var application =
          startPackagedApplication(
              true,
              "--spring.datasource.hikari.maximum-pool-size=1",
              readOnly
                  ? "--spring.datasource.hikari.read-only=true"
                  : "--spring.datasource.hikari.auto-commit=false")) {
        try (var connection = application.getBean(DataSource.class).getConnection()) {
          assertThat(connection.getAutoCommit()).isEqualTo(readOnly);
          assertThat(connection.isReadOnly()).isEqualTo(readOnly);
        }
        assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(application.getBean(HealthEndpoint.class).healthForPath("liveness"))
            .isNotNull()
            .extracting(result -> result.getStatus())
            .isEqualTo(Status.UP);
      }
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @Test
  @ResourceLock("packaged-recovery-control-fixture-database")
  void migratedPackagedDatabaseInReadOnlyModeIsNotJournalReadyButProcessIsLive() throws Exception {
    String database = "firemud_recovery_control";
    dsl.execute("CREATE DATABASE " + database);
    try (var application =
        startPackagedApplication(true, "--spring.datasource.hikari.maximum-pool-size=1")) {
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
      // The dedicated one-connection pool gives the probe the same read-only session.
      try (var connection = application.getBean(DataSource.class).getConnection();
          var statement = connection.createStatement()) {
        statement.execute("SET default_transaction_read_only = on");
        try (var result = statement.executeQuery("SHOW transaction_read_only")) {
          assertThat(result.next()).isTrue();
          assertThat(result.getString(1)).isEqualTo("on");
        }
      }
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.DOWN);
      var health = application.getBean(HealthEndpoint.class);
      assertThat(health.healthForPath("readiness"))
          .isNotNull()
          .extracting(probe -> probe.getStatus())
          .isEqualTo(Status.DOWN);
      assertThat(health.healthForPath("liveness"))
          .isNotNull()
          .extracting(probe -> probe.getStatus())
          .isEqualTo(Status.UP);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @Test
  @ResourceLock("packaged-recovery-control-fixture-database")
  void journalWriterWithActiveRowSecurityIsNotReady() throws Exception {
    String database = "firemud_recovery_control";
    String role = "recovery_fixture_writer";
    dsl.execute("CREATE DATABASE " + database);
    boolean roleCreated = false;
    try {
      dsl.execute("CREATE ROLE " + role + " NOLOGIN");
      roleCreated = true;
      try (var migration = startPackagedApplication(true)) {
        assertThat(journalHealth(migration).health().getStatus()).isEqualTo(Status.UP);
      }
      String fixtureUrl = POSTGRES.getJdbcUrl();
      String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
      try (var connection =
          DriverManager.getConnection(
              packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var owner = DSL.using(connection, SQLDialect.POSTGRES);
        owner.execute("GRANT USAGE ON SCHEMA recovery_control TO " + role);
        owner.execute(
            "GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA recovery_control TO " + role);
        try (var application =
            startPackagedApplication(true, "--spring.datasource.hikari.maximum-pool-size=1")) {
          try (var session = application.getBean(DataSource.class).getConnection();
              var statement = session.createStatement()) {
            statement.execute("SET ROLE " + role);
            try (var result = statement.executeQuery("SELECT current_user")) {
              assertThat(result.next()).isTrue();
              assertThat(result.getString(1)).isEqualTo(role);
            }
          }
          assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
          owner.execute(
              "ALTER TABLE recovery_control.initial_gameplay_release_outbox ENABLE ROW LEVEL SECURITY");
          assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.DOWN);
          assertThat(application.getBean(HealthEndpoint.class).healthForPath("liveness"))
              .isNotNull()
              .extracting(health -> health.getStatus())
              .isEqualTo(Status.UP);
        }
      }
    } finally {
      try {
        dsl.execute("DROP DATABASE " + database);
      } finally {
        if (roleCreated) {
          dsl.execute("DROP ROLE " + role);
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "operation_key",
        "operation_revision_type",
        "operation_revision_constraint",
        "operation_evidence_digest_width",
        "transition_tuple_digest_width",
        "transition_tuple_digest_nullability",
        "transition_missing_key",
        "transition_operation_only_key",
        "transition_verb_only_key",
        "transition_equivalent_key",
        "outbox_operation_kind_key",
        "outbox_operation_only_key",
        "outbox_effect_kind_only_key",
        "outbox_operation_tuple_digest_width",
        "preparation_key",
        "preparation_digest_width",
        "preparation_single_receipt",
        "preparation_globally_unique_identity",
        "local_no_send_dispatch_precision",
        "local_no_send_attempt_only_key",
        "closure_recorded_precision",
        "outbox_readback_payload_nullability",
        "namespace_width"
      })
  @ResourceLock("packaged-recovery-control-fixture-database")
  void journalReadinessRequiresCompleteTypedKeys(String drift) throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try (var application = startPackagedApplication(true);
        var connection =
            DriverManager.getConnection(
                packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
      var schema = DSL.using(connection, SQLDialect.POSTGRES);
      schema.execute(
          switch (drift) {
            case "operation_key" ->
                """
                ALTER TABLE recovery_control.initial_gameplay_release_operation
                  DROP CONSTRAINT initial_gameplay_release_operation_pkey CASCADE
                """;
            case "operation_revision_type" ->
                "ALTER TABLE recovery_control.initial_gameplay_release_operation"
                    + " ALTER COLUMN revision TYPE integer";
            case "operation_revision_constraint" ->
                "ALTER TABLE recovery_control.initial_gameplay_release_operation"
                    + " DROP CONSTRAINT initial_gameplay_release_operation_revision_check";
            case "operation_evidence_digest_width" ->
                "ALTER TABLE recovery_control.initial_gameplay_release_operation"
                    + " ALTER COLUMN evidence_digest TYPE char(32)";
            case "transition_tuple_digest_width" ->
                "ALTER TABLE recovery_control.initial_gameplay_release_transition"
                    + " ALTER COLUMN tuple_digest TYPE CHAR(32)";
            case "transition_tuple_digest_nullability" ->
                "ALTER TABLE recovery_control.initial_gameplay_release_transition"
                    + " ALTER COLUMN tuple_digest DROP NOT NULL";
            case "transition_missing_key" ->
                """
                ALTER TABLE recovery_control.initial_gameplay_release_transition
                  DROP CONSTRAINT initial_gameplay_release_transition_pkey
                """;
            case "transition_operation_only_key" ->
                """
                CREATE UNIQUE INDEX "fixture one transition per operation"
                  ON recovery_control.initial_gameplay_release_transition (operation_id)
                  INCLUDE (verb)
                """;
            case "transition_verb_only_key" ->
                "CREATE UNIQUE INDEX fixture_transition_verb ON recovery_control.initial_gameplay_release_transition (verb)";
            case "transition_equivalent_key" ->
                """
                ALTER TABLE recovery_control.initial_gameplay_release_transition
                  DROP CONSTRAINT initial_gameplay_release_transition_pkey,
                  ALTER COLUMN operation_id SET NOT NULL,
                  ALTER COLUMN verb SET NOT NULL,
                  ADD CONSTRAINT "fixture equivalent transition key" UNIQUE (verb, operation_id)
                """;
            case "outbox_operation_kind_key" ->
                """
                ALTER TABLE recovery_control.initial_gameplay_release_outbox
                  DROP CONSTRAINT initial_gameplay_release_outbox_operation_id_effect_kind_key
                """;
            case "outbox_operation_only_key" ->
                """
                CREATE UNIQUE INDEX "fixture one effect per operation"
                  ON recovery_control.initial_gameplay_release_outbox (operation_id)
                  INCLUDE (effect_kind)
                """;
            case "outbox_effect_kind_only_key" ->
                """
                CREATE UNIQUE INDEX "fixture one operation per effect kind"
                  ON recovery_control.initial_gameplay_release_outbox (effect_kind)
                """;
            case "preparation_key" ->
                """
                ALTER TABLE recovery_control.initial_gameplay_release_preparation_receipt
                  DROP CONSTRAINT initial_gameplay_release_preparation_receipt_pkey,
                  ADD PRIMARY KEY (outbox_id)
                """;
            case "preparation_digest_width" ->
                """
                ALTER TABLE recovery_control.initial_gameplay_release_preparation_receipt
                  ALTER COLUMN identity_digest TYPE CHAR(63)
                """;
            case "preparation_single_receipt" ->
                """
                ALTER TABLE recovery_control.initial_gameplay_release_preparation_receipt
                  ADD UNIQUE (outbox_id)
                """;
            case "outbox_operation_tuple_digest_width" ->
                "ALTER TABLE recovery_control.initial_gameplay_release_outbox"
                    + " ALTER COLUMN operation_tuple_digest TYPE CHAR(63)";
            case "namespace_width" ->
                "ALTER TABLE recovery_control.initial_gameplay_release_operation"
                    + " ALTER COLUMN target_namespace TYPE varchar(1)";
            case "local_no_send_dispatch_precision" ->
                "ALTER TABLE recovery_control.initial_gameplay_release_attempt_local_no_send"
                    + " ALTER COLUMN dispatched_at TYPE timestamptz(3)";
            case "local_no_send_attempt_only_key" ->
                "ALTER TABLE recovery_control.initial_gameplay_release_attempt_local_no_send"
                    + " ADD UNIQUE (attempt_id, outbox_id)";
            case "closure_recorded_precision" ->
                "ALTER TABLE recovery_control.initial_gameplay_release_attempt_closure"
                    + " ALTER COLUMN recorded_at TYPE timestamptz(3)";
            case "outbox_readback_payload_nullability" ->
                "ALTER TABLE recovery_control.initial_gameplay_release_outbox"
                    + " ALTER COLUMN readback_payload SET NOT NULL";
            case "preparation_globally_unique_identity" ->
                """
                ALTER TABLE recovery_control.initial_gameplay_release_preparation_receipt
                  ADD UNIQUE (identity_digest)
                """;
            default -> throw new AssertionError("Unexpected test fixture key");
          });
      assertCompletePackagedMigrationReceipts(packagedUrl);
      Status expected = "transition_equivalent_key".equals(drift) ? Status.UP : Status.DOWN;
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(expected);
      var health = application.getBean(HealthEndpoint.class);
      assertThat(health.healthForPath("readiness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(expected);
      assertThat(health.healthForPath("liveness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.UP);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @Test
  @ResourceLock("packaged-recovery-control-fixture-database")
  void readinessRequiresExactVersionedMigrationInventory() throws Exception {
    String database = "firemud_recovery_control";
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String packagedUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try (var application = startPackagedApplication(true);
        var connection =
            DriverManager.getConnection(
                packagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      assertCompletePackagedMigrationReceipts(packagedUrl);
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
      DSL.using(connection, SQLDialect.POSTGRES)
          .execute(
              """
              INSERT INTO recovery_control.flyway_schema_history_recovery_control
                (installed_rank, version, description, type, script, installed_by, execution_time, success)
              SELECT max(installed_rank) + 1, '10', 'fixture newer migration', 'SQL',
                'V10__fixture_newer_migration.sql', current_user, 0, true
              FROM recovery_control.flyway_schema_history_recovery_control
              """);
      var health = application.getBean(HealthEndpoint.class);
      assertThat(health.healthForPath("readiness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.DOWN);
      assertThat(health.healthForPath("liveness"))
          .isNotNull()
          .extracting(result -> result.getStatus())
          .isEqualTo(Status.UP);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  private InitialGameplayReleaseController controllerAt(
      InitialGameplayReleaseJournal fixtureJournal,
      InitialGameplayReleaseEffectPort port,
      Instant now)
      throws Exception {
    return ReflectionUtils.accessibleConstructor(
            InitialGameplayReleaseController.class,
            InitialGameplayReleaseJournal.class,
            InitialGameplayReleaseTupleCodec.class,
            InitialGameplayReleaseEvidenceProvider.class,
            InitialGameplayReleaseEffectPort.class,
            Clock.class)
        .newInstance(
            fixtureJournal,
            tupleCodec,
            mock(InitialGameplayReleaseEvidenceProvider.class),
            port,
            Clock.fixed(now, ZoneOffset.UTC));
  }

  private static HealthIndicator journalHealth(ConfigurableApplicationContext context) {
    return context.getBean("recoveryJournalHealthIndicator", HealthIndicator.class);
  }

  private static ConfigurableApplicationContext startPackagedApplication(
      boolean migrate, String... overrides) {
    return startPackagedApplication(migrate, RecoveryControllerServiceApplication.class, overrides);
  }

  private static ConfigurableApplicationContext startPackagedApplication(
      boolean migrate, Class<?> fixtureConfiguration, String... overrides) {
    var environment = new StandardEnvironment();
    // Keep ambient datasource overrides out of the context before Flyway migrates.
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

    var builder =
        new SpringApplicationBuilder(RecoveryControllerServiceApplication.class)
            .sources(fixtureConfiguration)
            .environment(environment)
            .web(WebApplicationType.NONE);
    var args =
        new ArrayList<>(
            List.of(
                "--spring.config.location=classpath:/application.yml",
                "--FIREMUD_RECOVERY_CONTROL_DB_HOST=" + POSTGRES.getHost(),
                "--FIREMUD_RECOVERY_CONTROL_DB_PORT=" + POSTGRES.getMappedPort(5432),
                "--FIREMUD_RECOVERY_CONTROL_DB_USER=" + POSTGRES.getUsername(),
                "--FIREMUD_RECOVERY_CONTROL_DB_PASSWORD=" + POSTGRES.getPassword(),
                "--spring.grpc.server.enabled=false",
                "--firemud.auth.jwt-secret=" + UUID.randomUUID() + UUID.randomUUID(),
                "--spring.ssl.bundle.pem.firemud-grpc.keystore.certificate=classpath:certs/dev-cert.pem",
                "--spring.ssl.bundle.pem.firemud-grpc.keystore.private-key=classpath:certs/dev-key.pem",
                "--spring.ssl.bundle.pem.firemud-grpc.truststore.certificate=classpath:certs/dev-ca.pem"));
    if (!migrate) {
      args.add("--spring.flyway.enabled=false");
    }
    args.addAll(List.of(overrides));
    return builder.run(args.toArray(String[]::new));
  }

  @Test
  void journalDoesNotLogControllerLockIdentityAtDebug() {
    Logger logger = (Logger) LoggerFactory.getLogger(LoggerListener.class);
    Level priorLevel = logger.getLevel();
    ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
    logCapture.start();
    logger.addAppender(logCapture);
    logger.setLevel(Level.DEBUG);
    try {
      DSLContext debugDsl =
          DSL.using(dsl.configuration().derive(new Settings().withExecuteLogging(true)));
      InitialGameplayReleaseJournal debugJournal =
          new JooqInitialGameplayReleaseJournal(debugDsl, tupleCodec);
      Scope requested = scope();
      UUID controllerLockIdentity = UUID.randomUUID();

      Operation created = debugJournal.create(requested, controllerLockIdentity);
      assertThat(debugJournal.find(requested.operationId()))
          .get()
          .extracting(Operation::maintenanceLockIdentity)
          .isEqualTo(controllerLockIdentity);
      assertThat(created.maintenanceLockIdentity()).isEqualTo(controllerLockIdentity);
      debugDsl.fetch("SELECT 'journal-log-redaction-positive-control'::text");
      assertThat(logCapture.list)
          .extracting(ILoggingEvent::getFormattedMessage)
          .anyMatch(message -> message.contains("journal-log-redaction-positive-control"));
      assertThat(logCapture.list)
          .extracting(ILoggingEvent::getFormattedMessage)
          .noneMatch(message -> message.contains(controllerLockIdentity.toString()));
    } finally {
      logger.detachAppender(logCapture);
      logger.setLevel(priorLevel);
      logCapture.stop();
    }
  }

  @Test
  @ResourceLock("packaged-recovery-control-fixture-database")
  void postgresDriverTracingRefusesCredentialWriteUntilTracingIsDisabled() throws Exception {
    String database = "firemud_recovery_control";
    dsl.execute("CREATE DATABASE " + database);
    try (var application = startPackagedApplication(true)) {
      var packagedJournal = application.getBean(InitialGameplayReleaseJournal.class);
      var health = application.getBean(HealthEndpoint.class);
      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);

      java.util.logging.Level priorLevel = POSTGRES_PROTOCOL_LOG.getLevel();
      Scope scope = scope();
      UUID dummyLockIdentity = UUID.randomUUID();
      try {
        POSTGRES_PROTOCOL_LOG.setLevel(java.util.logging.Level.FINEST);
        assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.healthForPath("readiness"))
            .isNotNull()
            .extracting(probe -> probe.getStatus())
            .isEqualTo(Status.DOWN);
        assertThat(health.healthForPath("liveness"))
            .isNotNull()
            .extracting(probe -> probe.getStatus())
            .isEqualTo(Status.UP);
        assertThatThrownBy(() -> packagedJournal.create(scope, dummyLockIdentity))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Recovery journal requires PostgreSQL driver tracing off");
        assertThat(packagedJournal.find(scope.operationId())).isEmpty();
      } finally {
        POSTGRES_PROTOCOL_LOG.setLevel(priorLevel);
      }

      assertThat(journalHealth(application).health().getStatus()).isEqualTo(Status.UP);
      assertThat(packagedJournal.create(scope, dummyLockIdentity).maintenanceLockIdentity())
          .isEqualTo(dummyLockIdentity);
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @Test
  void journalKeepsTheLockBoundEvenWhenTheSuppliedContextUsesStaticStatements() throws Exception {
    try (var guardedConnection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      DSLContext logging =
          DSL.using(
              guardedConnection,
              SQLDialect.POSTGRES,
              new Settings()
                  .withStatementType(org.jooq.conf.StatementType.STATIC_STATEMENT)
                  .withParamType(org.jooq.conf.ParamType.INLINED));
      logging.execute("SET log_statement = 'mod'");
      int logStart = POSTGRES.getLogs().length();
      UUID lock = UUID.randomUUID();
      var preparedJournal = new JooqInitialGameplayReleaseJournal(logging, tupleCodec);
      assertThat(preparedJournal.create(scope(), lock).maintenanceLockIdentity()).isEqualTo(lock);
      String completionMarker = "recovery-journal-insert-" + UUID.randomUUID();
      logging.execute("DO $$ BEGIN RAISE LOG '" + completionMarker + "'; END $$");
      assertThat(awaitPostgresLogSince(logStart, completionMarker, Duration.ofSeconds(5)))
          .contains("INSERT INTO recovery_control.initial_gameplay_release_operation")
          .contains(completionMarker)
          .doesNotContain(lock.toString());
    }
  }

  @Test
  void duplicateMaintenanceCredentialRefusesWithoutExposingDatabaseDetailOrCause() {
    Scope original = scope();
    UUID lockIdentity = UUID.randomUUID();
    Operation created = journal.create(original, lockIdentity);
    Scope conflicting = scope();

    assertThatThrownBy(() -> journal.create(conflicting, lockIdentity))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessage("IDEMPOTENCY_CONFLICT: maintenance-lock identity is already bound")
        .hasNoCause()
        .hasNoSuppressedExceptions()
        .satisfies(
            failure -> assertThat(failure.toString()).doesNotContain(lockIdentity.toString()));
    assertThat(journal.find(conflicting.operationId())).isEmpty();
    assertThat(journal.find(original.operationId())).contains(created);
    assertThat(journal.create(original, UUID.randomUUID())).isEqualTo(created);
  }

  @Test
  void journalPersistsExactScopeEvidenceTransitionsAndOwnerReadbackGatedHandoff() {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    Scope scope = scope();
    UUID lockIdentity = UUID.randomUUID();

    Operation created = journal.create(scope, lockIdentity);
    Operation duplicateCreate = journal.create(scope, UUID.randomUUID());
    assertThat(created.phase()).isEqualTo(Phase.COLLECTING);
    assertThat(duplicateCreate.maintenanceLockIdentity()).isEqualTo(lockIdentity);
    assertThat(journal.find(created.scope().operationId())).contains(created);
    assertThat(journal.find(UUID.randomUUID())).isEmpty();

    InitialGameplayReleaseEvidence proof = evidence(scope);
    Operation ready =
        journal.bindVerifiedEvidence(
            scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), base.plusSeconds(1));
    assertThat(ready.phase()).isEqualTo(Phase.READY_TO_REOPEN);
    assertThat(ready.evidence()).isEqualTo(proof);

    PublicTransitionTuple continueTuple = transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY);
    assertThat(journal.findTransition(continueTuple)).isEmpty();
    var continued = new PublicTransitionResult(scope.operationId(), Phase.AWAITING_RESUME);
    assertThat(journal.transition(continueTuple, base.plusSeconds(2))).isEqualTo(continued);
    assertThat(journal.findTransition(continueTuple)).contains(continued);
    assertThat(journal.transition(continueTuple, base.plusSeconds(3))).isEqualTo(continued);

    PublicTransitionTuple resumeTuple = transitionTuple(ready, TransitionVerb.RESUME);
    var resumed = new PublicTransitionResult(scope.operationId(), Phase.RESUME_AUTHORIZED);
    assertThat(journal.transition(resumeTuple, base.plusSeconds(4))).isEqualTo(resumed);
    Operation authorized = journal.find(scope.operationId()).orElseThrow();
    OutboxEntry pendingPrepare =
        journal
            .findEffect(scope.operationId(), EffectKind.PREPARE_PLAYABLE_STATE_FENCED)
            .orElseThrow();
    assertThat(pendingPrepare.state()).isEqualTo(EffectOutboxState.PENDING);
    assertThat(pendingPrepare.attemptCount()).isZero();
    assertThat(pendingPrepare.lastAttemptId()).isNull();
    assertThat(journal.findNextEffect(scope.operationId())).contains(pendingPrepare);
    String retainedPending = storedOutbox(scope.operationId(), pendingPrepare.effect().effectId());
    assertThat(journal.findTransition(continueTuple)).contains(continued);
    assertThat(journal.transition(continueTuple, base.plusSeconds(5))).isEqualTo(continued);
    assertThat(journal.findTransition(resumeTuple)).contains(resumed);
    assertThat(journal.transition(resumeTuple, base.plusSeconds(5))).isEqualTo(resumed);
    assertThat(authorized.phase()).isEqualTo(Phase.RESUME_AUTHORIZED);
    assertThat(journal.find(scope.operationId())).contains(authorized);
    assertThat(storedOutbox(scope.operationId(), pendingPrepare.effect().effectId()))
        .isEqualTo(retainedPending);
    assertThat(journal.findEffect(scope.operationId(), EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .isEmpty();

    UUID prepareAttempt = UUID.randomUUID();
    OutboxEntry prepare =
        journal.claimEffect(
            scope.operationId(),
            pendingPrepare.effect().effectId(),
            prepareAttempt,
            () -> base.plusSeconds(5));
    EffectReadback prepareReadback =
        new EffectReadback(
            scope.operationId(),
            prepare.effect().effectId(),
            prepareAttempt,
            prepare.attemptCount(),
            prepare.effect().kind(),
            prepare.effect().operationTupleDigest(),
            prepare.payloadDigest(),
            EffectReadbackState.PREPARED_FENCED,
            EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
            "gamesession-preparation-readback-1",
            base.plusSeconds(6));
    UUID prepareEffectId = prepare.effect().effectId();
    assertCrossKindReadbackIsRefused(
        prepare, EffectReadbackState.OWNER_HANDOFF_APPLIED, base.plusSeconds(6));
    assertThatThrownBy(
            () -> journal.recordEffectReadback(prepare, prepareReadback, base.plusSeconds(5)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("observation is in the future");
    assertThat(journal.find(scope.operationId())).contains(authorized);
    assertThat(journal.findEffect(scope.operationId(), EffectKind.PREPARE_PLAYABLE_STATE_FENCED))
        .contains(prepare);
    assertThat(journal.findEffect(scope.operationId(), EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .isEmpty();
    EffectReadback otherAttempt =
        new EffectReadback(
            scope.operationId(),
            prepareEffectId,
            UUID.randomUUID(),
            prepare.attemptCount(),
            prepare.effect().kind(),
            prepare.effect().operationTupleDigest(),
            prepare.payloadDigest(),
            EffectReadbackState.PREPARED_FENCED,
            EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
            "wrong-attempt",
            base.plusSeconds(6));
    assertThatThrownBy(
            () -> journal.recordEffectReadback(prepare, otherAttempt, base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(
            journal.recordAmbiguousEffectForDispatch(
                prepare, "TRANSPORT_OUTCOME_UNKNOWN", base.plusSeconds(5)))
        .isTrue();
    OutboxEntry ambiguous = journal.findNextEffect(scope.operationId()).orElseThrow();
    assertThat(ambiguous.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);
    Operation releasing =
        journal.recordEffectReadback(ambiguous, prepareReadback, prepareReadback.observedAt());
    assertThat(releasing.phase()).isEqualTo(Phase.RELEASING);
    String retainedPrepare = storedOutbox(scope.operationId(), prepareEffectId);
    assertThatThrownBy(
            () -> journal.recordEffectReadback(prepare, prepareReadback, base.plusSeconds(5)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("effect readback observation is in the future");
    assertThat(storedOutbox(scope.operationId(), prepareEffectId)).isEqualTo(retainedPrepare);
    assertThat(journal.recordEffectReadback(prepare, prepareReadback, prepareReadback.observedAt()))
        .isEqualTo(releasing);
    assertThat(storedOutbox(scope.operationId(), prepareEffectId)).isEqualTo(retainedPrepare);
    assertThat(
            journal.recordAmbiguousEffectForDispatch(
                prepare, "LATE_TRANSPORT_OUTCOME_UNKNOWN", base.plusSeconds(8)))
        .isTrue();
    assertThat(storedOutbox(scope.operationId(), prepareEffectId)).isEqualTo(retainedPrepare);
    UUID preparedEffectId = prepare.effect().effectId();
    String retainedAcknowledgement = storedOutbox(scope.operationId(), preparedEffectId);
    EffectReadback earlierObservation =
        readbackFor(
            prepare,
            prepareAttempt,
            EffectReadbackState.PREPARED_FENCED,
            prepareReadback.readbackIdentity(),
            base.plusSeconds(5));
    assertThatThrownBy(
            () -> journal.recordEffectReadback(prepare, earlierObservation, base.plusSeconds(5)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("newer than the retained same-attempt observation");
    assertThat(storedOutbox(scope.operationId(), preparedEffectId))
        .isEqualTo(retainedAcknowledgement);
    EffectReadback retimedObservation =
        readbackFor(
            prepare,
            prepareAttempt,
            EffectReadbackState.PREPARED_FENCED,
            prepareReadback.readbackIdentity(),
            base.plusSeconds(8));
    assertThatThrownBy(
            () -> journal.recordEffectReadback(prepare, retimedObservation, base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("effect readback observation is in the future");
    assertThat(storedOutbox(scope.operationId(), preparedEffectId))
        .isEqualTo(retainedAcknowledgement);
    assertThatThrownBy(
            () -> journal.recordEffectReadback(prepare, retimedObservation, base.plusSeconds(9)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("preparation readback identity already has an observation");
    assertThat(storedOutbox(scope.operationId(), preparedEffectId))
        .isEqualTo(retainedAcknowledgement);
    EffectReadback laterObservation =
        readbackFor(
            prepare,
            prepareAttempt,
            EffectReadbackState.PREPARED_FENCED,
            "gamesession-preparation-readback-2",
            retimedObservation.observedAt());
    assertThat(journal.recordEffectReadback(prepare, laterObservation, base.plusSeconds(9)))
        .isEqualTo(releasing);
    String updatedObservation = storedOutbox(scope.operationId(), preparedEffectId);
    assertThat(updatedObservation)
        .contains(tupleCodec.digest(prepareReadback), tupleCodec.digest(laterObservation));
    for (Instant observedAt :
        List.of(laterObservation.observedAt().minusNanos(1), laterObservation.observedAt())) {
      EffectReadback delayedPartial =
          readbackFor(
              prepare,
              prepareAttempt,
              EffectReadbackState.UNKNOWN_OR_PARTIAL,
              "delayed-partial",
              observedAt);
      assertThatThrownBy(
              () -> journal.recordEffectReadback(prepare, delayedPartial, base.plusSeconds(9)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("newer than the retained same-attempt observation");
    }
    EffectReadback changedReceipt =
        readbackFor(
            prepare,
            prepareAttempt,
            EffectReadbackState.PREPARED_FENCED,
            "different-durable-receipt",
            base.plusSeconds(8));
    assertThatThrownBy(
            () -> journal.recordEffectReadback(prepare, changedReceipt, base.plusSeconds(9)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(storedOutbox(scope.operationId(), preparedEffectId)).isEqualTo(updatedObservation);
    assertThatThrownBy(() ->
            journal.recordAmbiguousEffectForDispatch(
                withAttempt(prepare, UUID.randomUUID()), "WRONG_ATTEMPT", base.plusSeconds(8)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(journal.findEffect(scope.operationId(), EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .get()
        .extracting(OutboxEntry::state)
        .isEqualTo(EffectOutboxState.PENDING);

    OutboxEntry handoff =
        journal
            .findEffect(scope.operationId(), EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF)
            .orElseThrow();
    OutboxEntry completedPrepare =
        journal
            .findEffect(scope.operationId(), EffectKind.PREPARE_PLAYABLE_STATE_FENCED)
            .orElseThrow();
    assertThat(completedPrepare.state()).isEqualTo(EffectOutboxState.ACKNOWLEDGED);
    assertThat(completedPrepare.attemptCount()).isEqualTo(prepare.attemptCount());
    assertThat(completedPrepare.lastAttemptId()).isEqualTo(prepareAttempt);
    assertThat(completedPrepare.acknowledgedAt()).isEqualTo(prepareReadback.observedAt());
    assertThat(journal.findNextEffect(scope.operationId())).contains(handoff);
    UUID handoffAttempt = UUID.randomUUID();
    handoff =
        journal.claimEffect(
            scope.operationId(),
            handoff.effect().effectId(),
            handoffAttempt,
            () -> base.plusSeconds(8));
    EffectReadback handoffReadback =
        new EffectReadback(
            scope.operationId(),
            handoff.effect().effectId(),
            handoffAttempt,
            handoff.attemptCount(),
            handoff.effect().kind(),
            handoff.effect().operationTupleDigest(),
            handoff.payloadDigest(),
            EffectReadbackState.OWNER_HANDOFF_APPLIED,
            EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
            "gamesession-owner-handoff-readback-1",
            base.plusSeconds(9));
    assertCrossKindReadbackIsRefused(
        handoff, EffectReadbackState.PREPARED_FENCED, base.plusSeconds(9));
    Operation finalized =
        journal.recordEffectReadback(handoff, handoffReadback, base.plusSeconds(10));

    assertThat(finalized.phase()).isEqualTo(Phase.FINALIZED);
    assertThat(finalized.finalizedAt()).isEqualTo(base.plusSeconds(10));
    assertThat(journal.findNextEffect(scope.operationId())).isEmpty();
  }

  private static void assertCrossKindReadbackIsRefused(
      OutboxEntry dispatch, EffectReadbackState wrongState, Instant now) {
    var effect = dispatch.effect();
    var originalOperation = journal.find(effect.operationId()).orElseThrow();
    String originalRow = storedOutbox(effect.operationId(), effect.effectId());
    var mismatched =
        readbackFor(
            dispatch, dispatch.lastAttemptId(), wrongState, "different-effect-outcome", now);

    assertThatThrownBy(() -> journal.recordEffectReadback(dispatch, mismatched, now))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("effect readback did not prove its exact durable outcome");
    assertThat(journal.find(effect.operationId())).contains(originalOperation);
    assertThat(storedOutbox(effect.operationId(), effect.effectId())).isEqualTo(originalRow);
  }

  @Test
  void handoffCanBeClaimedAtTheSameSubMicrosecondTimeAsPreparationAcknowledgement() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry prepare = authorizedDispatch(base);
    UUID operationId = prepare.effect().operationId();
    Instant now = base.plusSeconds(5).plusNanos(600);
    EffectReadback prepared =
        readbackFor(
            prepare,
            prepare.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "prepare-readback-for-immediate-handoff",
            now);

    assertThat(journal.recordEffectReadback(prepare, prepared, now).phase())
        .isEqualTo(Phase.RELEASING);
    var completedPrepare =
        journal.findEffect(operationId, EffectKind.PREPARE_PLAYABLE_STATE_FENCED).orElseThrow();
    assertThat(completedPrepare.acknowledgedAt()).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));
    assertThat(storedOutbox(operationId, prepare.effect().effectId()))
        .contains(tupleCodec.digest(prepared));

    var handoff = journal.findNextEffect(operationId).orElseThrow();
    assertThat(handoff.effect().kind()).isEqualTo(EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF);
    assertThat(
            journal
                .claimEffect(operationId, handoff.effect().effectId(), UUID.randomUUID(), () -> now)
                .state())
        .isEqualTo(EffectOutboxState.DISPATCHED);
  }

  @Test
  void retainedDefinitivePreparationRequiresItsExactHistoricalIdentity() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry preparation = authorizedDispatch(base);
    UUID operationId = preparation.effect().operationId();
    UUID effectId = preparation.effect().effectId();
    UUID attemptId = preparation.lastAttemptId();
    EffectReadback initial =
        readbackFor(
            preparation,
            attemptId,
            EffectReadbackState.PREPARED_FENCED,
            "initial",
            base.plusSeconds(5));
    EffectReadback firstRefresh =
        readbackFor(
            preparation,
            attemptId,
            EffectReadbackState.PREPARED_FENCED,
            "first-refresh",
            base.plusSeconds(6));
    EffectReadback latest =
        readbackFor(
            preparation,
            attemptId,
            EffectReadbackState.PREPARED_FENCED,
            "latest",
            base.plusSeconds(7));
    journal.recordEffectReadback(preparation, initial, base.plusSeconds(5));
    journal.recordEffectReadback(preparation, firstRefresh, base.plusSeconds(6));
    Operation releasing = journal.recordEffectReadback(preparation, latest, base.plusSeconds(7));
    String retained = storedOutbox(operationId, effectId);
    OutboxEntry pending = journal.findNextEffect(operationId).orElseThrow();
    String historyTable = "recovery_control.initial_gameplay_release_preparation_receipt";
    String latestIdentity = tupleCodec.digest(latest.readbackIdentity());
    String removeIdentity =
        "DELETE FROM " + historyTable + " WHERE outbox_id = ? AND identity_digest = ?";

    assertThat(dsl.execute(removeIdentity, preparation.outboxId(), latestIdentity)).isEqualTo(1);
    assertThat(journal.recordEffectReadback(preparation, initial, base.plusSeconds(8)))
        .isEqualTo(releasing);
    var partial =
        readbackFor(
            preparation,
            attemptId,
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "partial",
            base.plusSeconds(8));
    for (EffectReadback observation : List.of(latest, partial)) {
      assertThatThrownBy(
              () -> journal.recordEffectReadback(preparation, observation, base.plusSeconds(8)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("retained preparation readback has no exact historical identity");
    }
    UUID handoffId = pending.effect().effectId();
    UUID handoffAttempt = UUID.randomUUID();
    assertThatThrownBy(() ->
            journal.claimEffect(operationId, handoffId, handoffAttempt, () -> base.plusSeconds(8)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("retained preparation readback has no exact historical identity");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    assertThat(journal.findNextEffect(operationId)).contains(pending);

    assertThat(
            dsl.execute(
                "INSERT INTO " + historyTable + " (outbox_id, identity_digest) VALUES (?, ?)",
                preparation.outboxId(),
                latestIdentity))
        .isEqualTo(1);
    var handoff =
        journal.claimEffect(operationId, handoffId, handoffAttempt, () -> base.plusSeconds(8));
    var applied =
        readbackFor(
            handoff,
            handoffAttempt,
            EffectReadbackState.OWNER_HANDOFF_APPLIED,
            "applied",
            base.plusSeconds(9));
    var finalized = journal.recordEffectReadback(handoff, applied, base.plusSeconds(9));
    assertThat(finalized.phase()).isEqualTo(Phase.FINALIZED);
    assertThat(journal.recordEffectReadback(preparation, latest, base.plusSeconds(10)))
        .isEqualTo(finalized);

    assertThat(dsl.execute(removeIdentity, preparation.outboxId(), latestIdentity)).isEqualTo(1);
    assertThatThrownBy(
            () -> journal.recordEffectReadback(preparation, latest, base.plusSeconds(10)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("retained preparation readback has no exact historical identity");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
  }

  @Test
  void dispatchedHandoffFinalizesWhileNewerPartialPreparationRemainsCurrent() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry prepare = authorizedDispatch(base);
    UUID operationId = prepare.effect().operationId();
    EffectReadback prepared =
        readbackFor(
            prepare,
            prepare.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "prepared-before-handoff-dispatch",
            base.plusSeconds(5));
    assertThat(journal.recordEffectReadback(prepare, prepared, base.plusSeconds(5)).phase())
        .isEqualTo(Phase.RELEASING);

    OutboxEntry pendingHandoff = journal.findNextEffect(operationId).orElseThrow();
    assertThat(pendingHandoff.effect().kind())
        .isEqualTo(EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF);
    UUID handoffId = pendingHandoff.effect().effectId();
    OutboxEntry dispatchedHandoff =
        journal.claimEffect(operationId, handoffId, UUID.randomUUID(), () -> base.plusSeconds(6));
    assertThat(dispatchedHandoff.state()).isEqualTo(EffectOutboxState.DISPATCHED);

    EffectReadback partial =
        readbackFor(
            prepare,
            prepare.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "partial-after-handoff-dispatch",
            base.plusSeconds(7));
    assertThat(journal.recordEffectReadback(prepare, partial, base.plusSeconds(7)).phase())
        .isEqualTo(Phase.RELEASING);
    String retainedPreparation = storedOutbox(operationId, prepare.effect().effectId());
    assertThat(retainedPreparation).contains(tupleCodec.digest(partial));
    assertThat(journal.findNextEffect(operationId)).contains(dispatchedHandoff);

    EffectReadback applied =
        readbackFor(
            dispatchedHandoff,
            dispatchedHandoff.lastAttemptId(),
            EffectReadbackState.OWNER_HANDOFF_APPLIED,
            "applied-with-partial-preparation",
            base.plusSeconds(8));
    Operation finalized =
        journal.recordEffectReadback(dispatchedHandoff, applied, base.plusSeconds(9));

    assertThat(finalized.phase()).isEqualTo(Phase.FINALIZED);
    assertThat(finalized.finalizedAt()).isEqualTo(base.plusSeconds(9));
    assertThat(journal.find(operationId)).contains(finalized);
    assertThat(storedOutbox(operationId, handoffId)).contains(tupleCodec.digest(applied));
    assertThat(storedOutbox(operationId, prepare.effect().effectId()))
        .isEqualTo(retainedPreparation);
    assertThat(journal.findNextEffect(operationId)).isEmpty();
  }

  @ParameterizedTest(name = "handoff finalizes during refresh: {0}, fresh receipt state: {1}")
  @CsvSource({
    "false, PREPARED_FENCED",
    "true,",
    "true, UNKNOWN_OR_PARTIAL",
    "true, PREPARED_FENCED"
  })
  void unresolvedHandoffRefreshesHeldPreparationWithoutRedispatch(
      boolean finalizeDuringRefresh, EffectReadbackState freshState) throws Exception {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry preparation = authorizedDispatch(base);
    UUID operationId = preparation.effect().operationId();
    EffectReadback prepared =
        readbackFor(
            preparation,
            preparation.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "prepared-before-handoff",
            base.plusSeconds(5));
    journal.recordEffectReadback(preparation, prepared, prepared.observedAt());
    OutboxEntry pendingHandoff = journal.findNextEffect(operationId).orElseThrow();
    OutboxEntry handoff =
        journal.claimEffect(
            operationId,
            pendingHandoff.effect().effectId(),
            UUID.randomUUID(),
            () -> base.plusSeconds(6));
    EffectReadback partial =
        readbackFor(
            preparation,
            preparation.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "preparation-became-partial",
            base.plusSeconds(8));
    journal.recordEffectReadback(preparation, partial, partial.observedAt());
    EffectReadback unknown =
        readbackFor(
            handoff,
            handoff.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "handoff-needs-preparation-refresh",
            base.plusSeconds(9));
    EffectReadback refreshed =
        freshState == null
            ? partial
            : readbackFor(
                preparation,
                preparation.lastAttemptId(),
                freshState,
                "fresh-preparation-receipt",
                base.plusSeconds(10));
    EffectReadback applied =
        readbackFor(
            handoff,
            handoff.lastAttemptId(),
            EffectReadbackState.OWNER_HANDOFF_APPLIED,
            "handoff-completed-during-refresh",
            base.plusSeconds(11));
    Instant now = base.plusSeconds(12);
    UUID handoffId = handoff.effect().effectId();
    List<String> finalizedHandoffRows = new ArrayList<>(1);
    var port = mock(InitialGameplayReleaseEffectPort.class);
    when(port.readback(handoff.effect(), handoff.lastAttemptId(), handoff.attemptCount()))
        .thenReturn(InitialGameplayReleaseEffectPort.EffectReadbackResolution.available(unknown));
    when(port.readback(
            preparation.effect(), preparation.lastAttemptId(), preparation.attemptCount()))
        .thenAnswer(invocation -> {
          if (finalizeDuringRefresh) {
            journal.recordEffectReadback(handoff, applied, now);
            finalizedHandoffRows.add(storedOutbox(operationId, handoffId));
          }
          return InitialGameplayReleaseEffectPort.EffectReadbackResolution.available(refreshed);
        });
    var controller = controllerAt(journal, port, now);
    String priorPreparation = storedOutbox(operationId, preparation.effect().effectId());

    Operation result = controller.reconcileNextEffect(operationId);

    verify(port).readback(handoff.effect(), handoff.lastAttemptId(), handoff.attemptCount());
    verify(port)
        .readback(preparation.effect(), preparation.lastAttemptId(), preparation.attemptCount());
    verifyNoMoreInteractions(port);
    if (finalizeDuringRefresh) {
      assertThat(result.phase()).isEqualTo(Phase.FINALIZED);
      assertThat(journal.find(operationId)).contains(result);
      assertThat(finalizedHandoffRows).hasSize(1);
      assertThat(storedOutbox(operationId, handoffId))
          .isEqualTo(finalizedHandoffRows.getFirst())
          .contains(tupleCodec.digest(applied));
      var wrongGeneration =
          new EffectReadback(
              refreshed.operationId(),
              refreshed.effectId(),
              refreshed.attemptId(),
              refreshed.attemptOrdinal() + 1,
              refreshed.effectKind(),
              refreshed.operationTupleDigest(),
              refreshed.payloadDigest(),
              refreshed.state(),
              refreshed.authority(),
              refreshed.readbackIdentity(),
              refreshed.observedAt());
      assertThatThrownBy(() -> journal.recordEffectReadback(preparation, wrongGeneration, now))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("generation changed");
      EffectReadback futurePartial =
          readbackFor(
              preparation,
              preparation.lastAttemptId(),
              EffectReadbackState.UNKNOWN_OR_PARTIAL,
              "preparation-future-observation",
              now.plusSeconds(1));
      assertThatThrownBy(() -> journal.recordEffectReadback(preparation, futurePartial, now))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("observation is in the future");
      EffectReadback closure =
          readbackFor(
              preparation,
              preparation.lastAttemptId(),
              EffectReadbackState.PROVEN_NO_EFFECT,
              "contradictory-preparation-closure",
              now);
      assertThatThrownBy(() -> journal.recordEffectReadback(preparation, closure, now))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("did not prove its exact durable outcome");
      assertThat(storedOutbox(operationId, preparation.effect().effectId()))
          .isEqualTo(priorPreparation);
      assertThat(
              dsl.fetchOne(
                  """
                  SELECT 1 FROM recovery_control.initial_gameplay_release_preparation_receipt
                  WHERE outbox_id = ? AND identity_digest = ?
                  """,
                  preparation.outboxId(),
                  tupleCodec.digest(refreshed.readbackIdentity())))
          .isNull();
      assertThat(journal.findNextEffect(operationId)).isEmpty();
      return;
    }
    assertThat(result.phase()).isEqualTo(Phase.RELEASING);
    assertThat(storedOutbox(operationId, preparation.effect().effectId()))
        .contains(tupleCodec.digest(refreshed));
    assertThat(journal.findHeldPreparationForRefresh(operationId)).isEmpty();
    OutboxEntry retainedHandoff = journal.findNextEffect(operationId).orElseThrow();
    assertThat(retainedHandoff.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);
    assertThat(retainedHandoff.lastAttemptId()).isEqualTo(handoff.lastAttemptId());
    assertThat(retainedHandoff.attemptCount()).isEqualTo(handoff.attemptCount());
  }

  @Test
  void tupleConflictAndAmbiguousEffectNeverAdvanceOrRedispatch() {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    Scope scope = scope();
    Operation operation = journal.create(scope, UUID.randomUUID());
    InitialGameplayReleaseEvidence proof = evidence(scope);
    Operation ready =
        journal.bindVerifiedEvidence(
            scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), base.plusSeconds(1));

    PublicTransitionTuple wrongLock =
        new PublicTransitionTuple(
            ready.scope().operationId(),
            tupleCodec.digest(scope),
            TransitionVerb.CONTINUE_RECOVERY,
            Phase.READY_TO_REOPEN,
            UUID.randomUUID(),
            scope.evidenceRef(),
            proof.evidenceIdentity(),
            tupleCodec.digest(proof));
    assertThatThrownBy(() -> journal.transition(wrongLock, base.plusSeconds(2)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    PublicTransitionTuple wrongEvidence =
        new PublicTransitionTuple(
            ready.scope().operationId(),
            tupleCodec.digest(scope),
            TransitionVerb.CONTINUE_RECOVERY,
            Phase.READY_TO_REOPEN,
            ready.maintenanceLockIdentity(),
            "different-evidence-reference",
            proof.evidenceIdentity(),
            tupleCodec.digest(proof));
    assertThatThrownBy(() -> journal.transition(wrongEvidence, base.plusSeconds(2)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(journal.find(operation.scope().operationId()).orElseThrow().phase())
        .isEqualTo(Phase.READY_TO_REOPEN);

    journal.transition(
        transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY), base.plusSeconds(3));
    journal.transition(transitionTuple(ready, TransitionVerb.RESUME), base.plusSeconds(4));
    Operation authorized = journal.find(scope.operationId()).orElseThrow();
    OutboxEntry prepare = journal.findNextEffect(scope.operationId()).orElseThrow();
    UUID attemptId = UUID.randomUUID();
    OutboxEntry dispatched =
        journal.claimEffect(
            scope.operationId(), prepare.effect().effectId(), attemptId, () -> base.plusSeconds(5));
    assertThat(dispatched.state()).isEqualTo(EffectOutboxState.DISPATCHED);
    assertThat(
            journal.recordAmbiguousEffectForDispatch(
                dispatched, "TRANSPORT_OUTCOME_UNKNOWN", base.plusSeconds(6)))
        .isTrue();
    assertThatThrownBy(() ->
            journal.claimEffect(
                scope.operationId(),
                prepare.effect().effectId(),
                attemptId,
                () -> base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("effect attempt is already claimed");
    OutboxEntry retryRead =
        journal.claimEffect(
            scope.operationId(),
            prepare.effect().effectId(),
            UUID.randomUUID(),
            () -> base.plusSeconds(7));

    assertThat(retryRead.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);
    assertThat(retryRead.lastAttemptId()).isEqualTo(attemptId);
    assertThat(retryRead.attemptCount()).isEqualTo(1);
    assertThat(journal.find(scope.operationId())).contains(authorized);
    assertThat(journal.findEffect(scope.operationId(), EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .isEmpty();
    assertThat(
            journal.recordRetryableEffectForDispatch(
                dispatched,
                RetrySource.OWNER_REJECTED,
                "OWNER_DECLINED_WITHOUT_EFFECT",
                base.plusSeconds(8)))
        .isTrue();
    assertThat(journal.findNextEffect(scope.operationId()))
        .get()
        .extracting(OutboxEntry::state)
        .isEqualTo(EffectOutboxState.RETRYABLE);
    UUID nextAttempt = UUID.randomUUID();
    OutboxEntry next =
        journal.claimEffect(
            scope.operationId(),
            prepare.effect().effectId(),
            nextAttempt,
            () -> base.plusSeconds(9));
    assertThat(next.lastAttemptId()).isEqualTo(nextAttempt);
    assertThat(next.attemptCount()).isEqualTo(2);
  }

  @Test
  void overlappingReadinessRefreshCannotReplaceEvidenceUsedByContinuation() throws Exception {
    Scope scope = scope();
    var lock = MaintenanceLockCredential.issue(UUID.randomUUID());
    journal.create(scope, lock.identity());
    var evidence = evidence(scope);
    var otherEvidence =
        new InitialGameplayReleaseEvidence(
            evidence.evidenceRef(),
            "different-owner-evidence",
            evidence.migration(),
            evidence.issuerLayout(),
            evidence.writerFencing(),
            evidence.worldInitialization(),
            evidence.entityInitialization(),
            evidence.regionLease());
    var effectPort = mock(InitialGameplayReleaseEffectPort.class);
    var controller =
        new InitialGameplayReleaseController(
            journal,
            tupleCodec,
            ignored ->
                InitialGameplayReleaseEvidenceProvider.EvidenceResolution.available(evidence),
            effectPort);
    var command =
        new ContinueRecovery(scope.operationId(), Phase.READY_TO_REOPEN, lock, scope.evidenceRef());

    var refreshStarted = new CompletableFuture<Void>();
    var finishOwnerRead = new CompletableFuture<Void>();
    try (var otherConnection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var executor = Executors.newSingleThreadExecutor()) {
      var otherJournal =
          new JooqInitialGameplayReleaseJournal(
              DSL.using(otherConnection, SQLDialect.POSTGRES), tupleCodec);
      var otherController =
          new InitialGameplayReleaseController(
              otherJournal,
              tupleCodec,
              ignored -> {
                refreshStarted.complete(null);
                finishOwnerRead.orTimeout(30, TimeUnit.SECONDS).join();
                return InitialGameplayReleaseEvidenceProvider.EvidenceResolution.available(
                    otherEvidence);
              },
              effectPort);
      var delayedRefresh =
          executor.submit(() -> otherController.refreshReadiness(scope.operationId(), lock));
      try {
        // The other refresh already read the collecting operation before this one binds evidence.
        refreshStarted.get(30, TimeUnit.SECONDS);
        var ready = controller.refreshReadiness(scope.operationId(), lock);
        var result = controller.continueRecovery(command);
        var transitioned = journal.find(scope.operationId()).orElseThrow();
        assertThat(result)
            .isEqualTo(new PublicTransitionResult(scope.operationId(), Phase.AWAITING_RESUME));
        assertThat(transitioned.evidence()).isEqualTo(evidence);

        finishOwnerRead.complete(null);
        assertThatThrownBy(() -> delayedRefresh.get(30, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasRootCauseMessage("IDEMPOTENCY_CONFLICT: owner evidence changed after it was bound");
        assertThat(otherController.continueRecovery(command)).isEqualTo(result);
        assertThat(
                otherJournal.findTransition(
                    transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY)))
            .contains(result);
        assertThat(otherJournal.find(scope.operationId())).contains(transitioned);
      } finally {
        finishOwnerRead.complete(null);
      }
    }
    verifyNoInteractions(effectPort);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void oversizedOwnerEvidenceDoesNotReachHashOrJournalWrite(boolean resume) {
    Scope scope = scope();
    var lock = MaintenanceLockCredential.issue(UUID.randomUUID());
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    journal.create(scope, lock.identity());
    InitialGameplayReleaseEvidence retained = evidence(scope);
    if (resume) {
      Operation ready =
          journal.bindVerifiedEvidence(
              scope, tupleCodec.digest(scope), retained, tupleCodec.digest(retained), now);
      journal.transition(
          transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY), now.plusSeconds(1));
    }
    Operation before = journal.find(scope.operationId()).orElseThrow();
    var oversized =
        new InitialGameplayReleaseEvidence(
            retained.evidenceRef(),
            "x".repeat(4 * 1024 * 1024),
            retained.migration(),
            retained.issuerLayout(),
            retained.writerFencing(),
            retained.worldInitialization(),
            retained.entityInitialization(),
            retained.regionLease());
    var observingCodec = spy(new InitialGameplayReleaseTupleCodec());
    var observingJournal = spy(journal);
    var effectPort = mock(InitialGameplayReleaseEffectPort.class);
    var controller =
        new InitialGameplayReleaseController(
            observingJournal,
            observingCodec,
            ignored ->
                InitialGameplayReleaseEvidenceProvider.EvidenceResolution.available(oversized),
            effectPort);

    assertThatThrownBy(
            () -> {
              if (resume) {
                controller.resume(
                    new Resume(
                        scope.operationId(), Phase.AWAITING_RESUME, lock, scope.evidenceRef()));
              } else {
                controller.refreshReadiness(scope.operationId(), lock);
              }
            })
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Initial gameplay release evidence exceeds 4 MiB of JSON");
    verify(observingCodec, never()).digest(oversized);
    verify(observingJournal, never()).bindVerifiedEvidence(any(), any(), any(), any(), any());
    verify(observingJournal, never()).transition(any(), any(Instant.class));
    verifyNoInteractions(effectPort);
    assertThat(journal.find(scope.operationId())).contains(before);
  }

  @ParameterizedTest
  @EnumSource(EffectKind.class)
  void delayedSameAttemptReadbackCannotOverrideNewerAmbiguity(EffectKind effectKind) {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    OutboxEntry dispatched = authorizedDispatch(effectKind, base);
    boolean preparation = effectKind == EffectKind.PREPARE_PLAYABLE_STATE_FENCED;
    EffectReadbackState definitive =
        preparation
            ? EffectReadbackState.PREPARED_FENCED
            : EffectReadbackState.OWNER_HANDOFF_APPLIED;
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    UUID attemptId = dispatched.lastAttemptId();
    Operation before = journal.find(operationId).orElseThrow();
    EffectReadback unknown =
        readbackFor(
            dispatched,
            attemptId,
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "latest-unknown",
            base.plusSeconds(8));
    journal.recordEffectReadback(dispatched, unknown, base.plusSeconds(8));
    String retained = storedOutbox(operationId, effectId);

    for (EffectReadback delayed :
        List.of(
            readbackFor(
                dispatched,
                attemptId,
                EffectReadbackState.UNKNOWN_OR_PARTIAL,
                "delayed-unknown",
                base.plusSeconds(7)),
            readbackFor(
                dispatched, attemptId, definitive, "delayed-older-definitive", base.plusSeconds(7)),
            readbackFor(
                dispatched,
                attemptId,
                definitive,
                "delayed-equal-time-definitive",
                base.plusSeconds(8)))) {
      assertThatThrownBy(
              () -> journal.recordEffectReadback(dispatched, delayed, base.plusSeconds(9)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining(
              delayed.observedAt().equals(unknown.observedAt())
                      && delayed.state() != EffectReadbackState.UNKNOWN_OR_PARTIAL
                  ? "definitive effect readback must be newer than retained partial evidence"
                  : "predates the retained same-attempt observation");
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
      assertThat(journal.find(operationId)).contains(before);
      if (preparation) {
        assertThat(journal.findEffect(operationId, EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
            .isEmpty();
      }
    }

    EffectReadback fresh =
        readbackFor(dispatched, attemptId, definitive, "fresh-owner-proof", base.plusSeconds(10));
    Operation advanced = journal.recordEffectReadback(dispatched, fresh, base.plusSeconds(10));
    assertThat(advanced.phase()).isEqualTo(preparation ? Phase.RELEASING : Phase.FINALIZED);
    assertThat(journal.findEffect(operationId, EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .isPresent();
  }

  @Test
  void definitiveAttemptClosureIsReplayableAndCannotBeOverwritten() {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    Scope scope = scope();
    journal.create(scope, UUID.randomUUID());
    InitialGameplayReleaseEvidence proof = evidence(scope);
    Operation ready =
        journal.bindVerifiedEvidence(
            scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), base.plusSeconds(1));
    journal.transition(
        transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY), base.plusSeconds(2));
    journal.transition(transitionTuple(ready, TransitionVerb.RESUME), base.plusSeconds(3));
    Operation authorized = journal.find(scope.operationId()).orElseThrow();
    UUID effectId =
        journal
            .findEffect(scope.operationId(), EffectKind.PREPARE_PLAYABLE_STATE_FENCED)
            .orElseThrow()
            .effect()
            .effectId();
    UUID firstAttempt = UUID.randomUUID();
    OutboxEntry dispatched =
        journal.claimEffect(scope.operationId(), effectId, firstAttempt, () -> base.plusSeconds(4));
    EffectReadback terminal =
        readbackFor(
            dispatched,
            firstAttempt,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "durable-closure",
            base.plusSeconds(5));
    assertThat(journal.recordEffectReadback(dispatched, terminal, base.plusSeconds(6)))
        .isEqualTo(authorized);
    String retained = storedOutbox(scope.operationId(), effectId);
    String retainedClosure = storedClosure(dispatched);

    var exactCurrentDispatch =
        dsl.fetchOne(
            """
            SELECT c.dispatched_at = o.last_dispatched_at AS same_dispatch
            FROM recovery_control.initial_gameplay_release_attempt_closure c
            JOIN recovery_control.initial_gameplay_release_outbox o ON o.outbox_id=c.outbox_id
            WHERE c.outbox_id=? AND c.attempt_id=?
            """,
            dispatched.outboxId(),
            firstAttempt);
    assertThat(exactCurrentDispatch).isNotNull();
    assertThat(exactCurrentDispatch.get("same_dispatch", Boolean.class)).isTrue();
    assertThat(journal.recordEffectReadback(dispatched, terminal, base.plusSeconds(7)))
        .isEqualTo(authorized);
    assertThat(
            journal.recordAmbiguousEffectForDispatch(
                dispatched, "LATE_TIMEOUT", base.plusSeconds(7)))
        .isTrue();
    assertThat(storedOutbox(scope.operationId(), effectId)).isEqualTo(retained);
    assertThat(storedClosure(dispatched)).isEqualTo(retainedClosure);
    assertThatThrownBy(() ->
            journal.recordAmbiguousEffectForDispatch(
                withAttempt(dispatched, UUID.randomUUID()), "WRONG_ATTEMPT", base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    EffectReadback observedAgain =
        readbackFor(
            dispatched,
            firstAttempt,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "durable-closure",
            base.plusSeconds(6));
    assertThat(journal.recordEffectReadback(dispatched, observedAgain, base.plusSeconds(7)))
        .isEqualTo(authorized);
    assertThat(storedOutbox(scope.operationId(), effectId)).isEqualTo(retained);
    assertThat(storedClosure(dispatched)).isEqualTo(retainedClosure);
    for (Instant invalid : List.of(base.plusSeconds(3), base.plusSeconds(8))) {
      EffectReadback invalidObservation =
          readbackFor(
              dispatched,
              firstAttempt,
              EffectReadbackState.PROVEN_NO_EFFECT,
              "durable-closure",
              invalid);
      assertThatThrownBy(() ->
              journal.recordEffectReadback(dispatched, invalidObservation, base.plusSeconds(7)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class);
    }
    EffectReadback differentClosure =
        readbackFor(
            dispatched,
            firstAttempt,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "different-closure",
            base.plusSeconds(6));
    assertThatThrownBy(
            () -> journal.recordEffectReadback(dispatched, differentClosure, base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(storedOutbox(scope.operationId(), effectId)).isEqualTo(retained);

    assertThatThrownBy(() ->
            journal.claimEffect(
                scope.operationId(), effectId, firstAttempt, () -> base.plusSeconds(8)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("a retry must use a new effect attempt identity");
    assertThat(storedOutbox(scope.operationId(), effectId)).isEqualTo(retained);

    UUID replacementAttempt = UUID.randomUUID();
    assertThat(
            journal
                .claimEffect(
                    scope.operationId(), effectId, replacementAttempt, () -> base.plusSeconds(8))
                .lastAttemptId())
        .isEqualTo(replacementAttempt);
    String replacement = storedOutbox(scope.operationId(), effectId);
    assertThat(replacement)
        .contains(
            "\"readback_identity\":null", "\"readback_payload\":null", "\"readback_digest\":null");
    assertThat(journal.recordEffectReadback(dispatched, terminal, base.plusSeconds(9)))
        .isEqualTo(authorized);
    assertThat(journal.recordEffectReadback(dispatched, observedAgain, base.plusSeconds(9)))
        .isEqualTo(authorized);
    assertThat(storedClosure(dispatched)).isEqualTo(retainedClosure);
    assertThat(storedOutbox(scope.operationId(), effectId)).isEqualTo(replacement);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "missing",
        "ordinal",
        "digest",
        "attempt",
        "receipt",
        "observation",
        "dispatch",
        "outbox-observation"
      })
  void noEffectReplayRequiresExactOriginalClosureHistory(String variant) {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    EffectReadback original =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PROVEN_NO_EFFECT,
            "original-closure",
            base.plusSeconds(5));
    Operation operation = journal.recordEffectReadback(dispatch, original, base.plusSeconds(7));
    if (variant.equals("missing")) {
      dsl.execute(
          "DELETE FROM recovery_control.initial_gameplay_release_attempt_closure WHERE outbox_id=?",
          dispatch.outboxId());
    } else if (variant.equals("ordinal")) {
      dsl.execute(
          "UPDATE recovery_control.initial_gameplay_release_attempt_closure SET attempt_ordinal=2 WHERE outbox_id=?",
          dispatch.outboxId());
    } else if (variant.equals("digest")) {
      dsl.execute(
          "UPDATE recovery_control.initial_gameplay_release_attempt_closure SET readback_digest=? WHERE outbox_id=?",
          "c".repeat(64),
          dispatch.outboxId());
    } else if (variant.equals("dispatch")) {
      dsl.execute(
          "UPDATE recovery_control.initial_gameplay_release_attempt_closure SET dispatched_at=CAST(? AS timestamptz) WHERE outbox_id=?",
          base.plusSeconds(3).atOffset(ZoneOffset.UTC),
          dispatch.outboxId());
    } else if (variant.equals("outbox-observation")) {
      EffectReadback altered =
          readbackFor(
              dispatch,
              dispatch.lastAttemptId(),
              EffectReadbackState.PROVEN_NO_EFFECT,
              original.readbackIdentity(),
              base.plusSeconds(6));
      dsl.execute(
          "UPDATE recovery_control.initial_gameplay_release_outbox SET readback_payload=CAST(? AS jsonb), readback_digest=? WHERE outbox_id=?",
          tupleCodec.toJson(altered),
          tupleCodec.digest(altered),
          dispatch.outboxId());
    } else {
      EffectReadback altered =
          readbackFor(
              dispatch,
              variant.equals("attempt") ? UUID.randomUUID() : dispatch.lastAttemptId(),
              EffectReadbackState.PROVEN_NO_EFFECT,
              variant.equals("receipt") ? "conflicting-closure" : "original-closure",
              variant.equals("observation") ? base.plusSeconds(6) : original.observedAt());
      dsl.execute(
          "UPDATE recovery_control.initial_gameplay_release_attempt_closure SET readback_payload=CAST(? AS jsonb), readback_digest=? WHERE outbox_id=?",
          tupleCodec.toJson(altered),
          tupleCodec.digest(altered),
          dispatch.outboxId());
    }
    String outboxBefore = storedOutbox(operationId, effectId);
    String closureBefore = variant.equals("missing") ? null : storedClosure(dispatch);
    if (variant.equals("outbox-observation")) {
      UUID replacementAttempt = UUID.randomUUID();
      assertThatThrownBy(() ->
              journal.claimEffect(
                  operationId, effectId, replacementAttempt, () -> base.plusSeconds(8)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("retained effect readback does not match exact replay");
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(outboxBefore);
      assertThat(storedClosure(dispatch)).isEqualTo(closureBefore);
      assertThat(journal.find(operationId)).contains(operation);
    }
    for (Instant observation : List.of(original.observedAt(), base.plusSeconds(6))) {
      EffectReadback replay =
          readbackFor(
              dispatch,
              dispatch.lastAttemptId(),
              EffectReadbackState.PROVEN_NO_EFFECT,
              original.readbackIdentity(),
              observation);
      assertThatThrownBy(() -> journal.recordEffectReadback(dispatch, replay, base.plusSeconds(8)))
          .as(variant + " closure history must refuse both exact and later-observation replay")
          .isInstanceOf(InitialGameplayReleaseConflictException.class);
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(outboxBefore);
      assertThat(journal.find(operationId)).contains(operation);
      assertThat(journal.findEffect(operationId, EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
          .isEmpty();
      if (closureBefore != null) {
        assertThat(storedClosure(dispatch)).isEqualTo(closureBefore);
      } else {
        assertThat(
                dsl.fetchCount(
                    DSL.table("recovery_control.initial_gameplay_release_attempt_closure")))
            .isZero();
      }
    }
  }

  @Test
  void firstDurableClosureAfterDefinitiveRejectionIsRetainedAndFenced() {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    OutboxEntry dispatched = authorizedDispatch(base);
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    UUID attemptId = dispatched.lastAttemptId();
    journal.recordRetryableEffectForDispatch(
        dispatched, RetrySource.OWNER_REJECTED, "OWNER_REJECTED", base.plusSeconds(5));
    String before = storedOutbox(operationId, effectId);
    EffectReadback stale =
        readbackFor(
            dispatched,
            attemptId,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "closure",
            base.plusSeconds(3));
    assertThatThrownBy(() -> journal.recordEffectReadback(dispatched, stale, base.plusSeconds(6)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    EffectReadback future =
        readbackFor(
            dispatched,
            attemptId,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "closure",
            base.plusSeconds(7));
    assertThatThrownBy(() -> journal.recordEffectReadback(dispatched, future, base.plusSeconds(6)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
    EffectReadback closure =
        readbackFor(
            dispatched,
            attemptId,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "closure",
            base.plusSeconds(6));
    journal.recordEffectReadback(dispatched, closure, base.plusSeconds(6));
    String retainedClosure = storedClosure(dispatched);
    String closed = storedOutbox(operationId, effectId);
    assertThat(
            journal.recordRetryableEffectForDispatch(
                dispatched,
                RetrySource.OWNER_REJECTED,
                "LATE_OWNER_REJECTION",
                base.plusSeconds(7)))
        .isFalse();
    assertThat(
            journal.recordRetryableEffectForDispatch(
                dispatched, RetrySource.NOT_DISPATCHED, "LATE_NO_SEND", base.plusSeconds(7)))
        .isFalse();
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(closed);
    String retained = storedOutbox(operationId, effectId);
    assertThat(retained).contains(tupleCodec.digest(closure));
    journal.recordEffectReadback(dispatched, closure, base.plusSeconds(7));
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    Operation beforeReplay = journal.find(operationId).orElseThrow();
    assertThat(journal.recordEffectReadback(dispatched, future, base.plusSeconds(8)))
        .isEqualTo(beforeReplay);
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    UUID nextAttempt = UUID.randomUUID();
    journal.claimEffect(operationId, effectId, nextAttempt, () -> base.plusSeconds(8));
    String replaced = storedOutbox(operationId, effectId);
    assertThat(journal.recordEffectReadback(dispatched, closure, base.plusSeconds(9)))
        .isEqualTo(beforeReplay);
    assertThat(journal.recordEffectReadback(dispatched, future, base.plusSeconds(9)))
        .isEqualTo(beforeReplay);
    assertThat(storedClosure(dispatched)).isEqualTo(retainedClosure);
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(replaced);
  }

  @Test
  void unknownReadbackBeforeDefinitiveRejectionAllowsOnlyValidatedSameAttemptClosure() {
    for (String variant : List.of("valid", "corrupt-digest", "other-attempt", "missing-digest")) {
      clearJournal();
      Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
      OutboxEntry dispatched = authorizedDispatch(base);
      UUID operationId = dispatched.effect().operationId();
      UUID effectId = dispatched.effect().effectId();
      UUID attemptId = dispatched.lastAttemptId();
      EffectReadback unknown =
          readbackFor(
              dispatched,
              attemptId,
              EffectReadbackState.UNKNOWN_OR_PARTIAL,
              "unknown",
              base.plusSeconds(5));
      journal.recordEffectReadback(dispatched, unknown, base.plusSeconds(5));
      journal.recordRetryableEffectForDispatch(
          dispatched, RetrySource.OWNER_REJECTED, "OWNER_REJECTED", base.plusSeconds(6));
      if (variant.equals("missing-digest")) {
        String retained = storedOutbox(operationId, effectId);
        assertThatThrownBy(() ->
                dsl.execute(
                    "UPDATE recovery_control.initial_gameplay_release_outbox SET readback_digest=NULL WHERE effect_id=?",
                    effectId))
            .isInstanceOf(org.jooq.exception.IntegrityConstraintViolationException.class);
        assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
        continue;
      }
      if (!variant.equals("valid")) {
        EffectReadback stored =
            variant.equals("other-attempt")
                ? readbackFor(
                    dispatched,
                    UUID.randomUUID(),
                    EffectReadbackState.UNKNOWN_OR_PARTIAL,
                    "unknown",
                    base.plusSeconds(5))
                : unknown;
        dsl.execute(
            "UPDATE recovery_control.initial_gameplay_release_outbox SET readback_payload=CAST(? AS jsonb), readback_digest=? WHERE effect_id=?",
            tupleCodec.toJson(stored),
            variant.equals("missing-digest")
                ? null
                : variant.equals("corrupt-digest") ? "a".repeat(64) : tupleCodec.digest(stored),
            effectId);
      }
      String before = storedOutbox(operationId, effectId);
      EffectReadback stale =
          readbackFor(
              dispatched,
              attemptId,
              EffectReadbackState.PROVEN_NO_EFFECT,
              "closure",
              base.plusSeconds(3));
      assertThatThrownBy(() -> journal.recordEffectReadback(dispatched, stale, base.plusSeconds(7)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class);
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
      EffectReadback closure =
          readbackFor(
              dispatched,
              attemptId,
              EffectReadbackState.PROVEN_NO_EFFECT,
              "closure",
              base.plusSeconds(7));
      if (variant.equals("valid")) {
        journal.recordEffectReadback(dispatched, closure, base.plusSeconds(7));
        String retained = storedOutbox(operationId, effectId);
        assertThat(retained).contains(tupleCodec.digest(closure));
        assertThatThrownBy(
                () -> journal.recordEffectReadback(dispatched, unknown, base.plusSeconds(8)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class);
        assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
      } else {
        assertThatThrownBy(
                () -> journal.recordEffectReadback(dispatched, closure, base.plusSeconds(7)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class);
        assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
      }
    }
  }

  @Test
  void dispatchFloorUsesStoredMicrosecondsWithoutRoundingFutureOrLosingReceiptPrecision() {
    for (int nanos : new int[] {499, 999, 999_999_999}) {
      clearJournal();
      Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60).plusNanos(nanos);
      OutboxEntry dispatched = authorizedDispatch(base);
      UUID operationId = dispatched.effect().operationId();
      UUID effectId = dispatched.effect().effectId();
      Instant dispatchInstant = base.plusSeconds(4);
      Instant floor = dispatchInstant.truncatedTo(ChronoUnit.MICROS);
      assertThat(
              java.util.Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT last_dispatched_at FROM recovery_control.initial_gameplay_release_outbox WHERE effect_id = ?",
                          effectId))
                  .get("last_dispatched_at", java.time.OffsetDateTime.class)
                  .toInstant())
          .isEqualTo(floor);
      String before = storedOutbox(operationId, effectId);
      EffectReadback stale =
          readbackFor(
              dispatched,
              dispatched.lastAttemptId(),
              EffectReadbackState.PROVEN_NO_EFFECT,
              "before-dispatch-floor",
              floor.minusNanos(1));
      assertThatThrownBy(() -> journal.recordEffectReadback(dispatched, stale, base.plusSeconds(5)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class);
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
      EffectReadback closure =
          readbackFor(
              dispatched,
              dispatched.lastAttemptId(),
              EffectReadbackState.PROVEN_NO_EFFECT,
              "same-instant",
              dispatchInstant);
      Operation recorded = journal.recordEffectReadback(dispatched, closure, dispatchInstant);
      String retained = storedOutbox(operationId, effectId);
      assertThat(retained).contains(tupleCodec.digest(closure));
      assertThat(journal.recordEffectReadback(dispatched, closure, base.plusSeconds(6)))
          .isEqualTo(recorded);
      EffectReadback laterObservation =
          readbackFor(
              dispatched,
              dispatched.lastAttemptId(),
              EffectReadbackState.PROVEN_NO_EFFECT,
              closure.readbackIdentity(),
              base.plusSeconds(5));
      assertThat(journal.recordEffectReadback(dispatched, laterObservation, base.plusSeconds(6)))
          .isEqualTo(recorded);
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
      UUID replacementAttempt = UUID.randomUUID();
      assertThat(
              journal
                  .claimEffect(operationId, effectId, replacementAttempt, () -> base.plusSeconds(7))
                  .lastAttemptId())
          .isEqualTo(replacementAttempt);
      String replacement = storedOutbox(operationId, effectId);
      assertThat(journal.recordEffectReadback(dispatched, closure, base.plusSeconds(8)))
          .isEqualTo(recorded);
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(replacement);
    }
  }

  @Test
  void exactOwnerReceiptCanCloseDispatchWithinTheSameMillisecond() {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    OutboxEntry dispatch = authorizedDispatch(base.plusNanos(500_000));
    Instant observedAt = base.plusSeconds(4).plusNanos(800_000);
    EffectReadback closure =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PROVEN_NO_EFFECT,
            "same-millisecond-owner-closure",
            observedAt);

    journal.recordEffectReadback(dispatch, closure, observedAt);

    assertThat(storedOutbox(dispatch.effect().operationId(), dispatch.effect().effectId()))
        .contains(tupleCodec.digest(closure));
  }

  @Test
  void operationCreationKeepsTheWireEvidenceReferenceLimitAndExactBytes() {
    Scope original = scope();
    String exactReference = "\u00e9".repeat(256);
    Scope tooLarge =
        new Scope(
            original.operationId(),
            original.canonicalTenantId(),
            original.targetNamespace(),
            original.playableStateNamespaceId(),
            original.peerStorageBinding(),
            exactReference + "a");
    UUID lock = UUID.randomUUID();
    assertThatThrownBy(() -> journal.create(tooLarge, lock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Operation evidence reference exceeds its UTF-8 limit");
    assertThat(journal.find(original.operationId())).isEmpty();

    Scope bounded =
        new Scope(
            original.operationId(),
            original.canonicalTenantId(),
            original.targetNamespace(),
            original.playableStateNamespaceId(),
            original.peerStorageBinding(),
            exactReference);
    assertThat(journal.create(bounded, lock).scope().evidenceRef()).isEqualTo(exactReference);
    assertThat(journal.find(original.operationId()).orElseThrow().scope()).isEqualTo(bounded);
  }

  @Test
  void operationCreationKeepsTheWireStorageIdentityLimitAndExactBytes() {
    Scope original = scope();
    String exactIdentity = "\u00e9".repeat(128);
    UUID lock = UUID.randomUUID();
    var observingCodec = spy(new InitialGameplayReleaseTupleCodec());
    var observingJournal = new JooqInitialGameplayReleaseJournal(dsl, observingCodec);

    assertThatThrownBy(
            () -> observingJournal.create(withStorageIdentity(original, exactIdentity + "a"), lock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Operation storage identity exceeds its UTF-8 limit");
    verifyNoInteractions(observingCodec);
    assertThat(journal.find(original.operationId())).isEmpty();

    Scope bounded = withStorageIdentity(original, exactIdentity);
    assertThat(journal.create(bounded, lock).scope().peerStorageBinding().storageIdentity())
        .isEqualTo(exactIdentity);
    assertThat(journal.find(original.operationId()).orElseThrow().scope()).isEqualTo(bounded);
  }

  private static Scope withStorageIdentity(Scope scope, String storageIdentity) {
    PeerStorageBinding binding = scope.peerStorageBinding();
    return new Scope(
        scope.operationId(),
        scope.canonicalTenantId(),
        scope.targetNamespace(),
        scope.playableStateNamespaceId(),
        new PeerStorageBinding(
            binding.targetNamespace(),
            binding.clusterIdentity(),
            binding.peerWorkloadIdentity(),
            storageIdentity,
            binding.storageRevision(),
            binding.bindingDigest()),
        scope.evidenceRef());
  }

  @Test
  void operationCreationRejectsNonNfcEvidenceReferenceBeforeHashing() {
    Scope original = scope();
    Scope decomposed =
        new Scope(
            original.operationId(),
            original.canonicalTenantId(),
            original.targetNamespace(),
            original.playableStateNamespaceId(),
            original.peerStorageBinding(),
            "proof/cafe\u0301");
    var observingCodec = spy(new InitialGameplayReleaseTupleCodec());
    var observingJournal = new JooqInitialGameplayReleaseJournal(dsl, observingCodec);

    assertThatThrownBy(() -> observingJournal.create(decomposed, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Operation evidence reference must be NFC");
    verifyNoInteractions(observingCodec);
    assertThat(journal.find(original.operationId())).isEmpty();
  }

  @Test
  void scopeReaderRejectsResealedScopeForAnotherRowKey() {
    Scope original = scope();
    journal.create(original, UUID.randomUUID());
    Scope foreign =
        new Scope(
            UUID.randomUUID(),
            original.canonicalTenantId(),
            original.targetNamespace(),
            original.playableStateNamespaceId(),
            original.peerStorageBinding(),
            original.evidenceRef());

    assertThat(
            dsl.execute(
                "UPDATE recovery_control.initial_gameplay_release_operation SET scope_payload=CAST(? AS jsonb), scope_digest=? WHERE operation_id=?",
                tupleCodec.toJson(foreign),
                tupleCodec.digest(foreign),
                original.operationId()))
        .isEqualTo(1);
    assertThatThrownBy(() -> journal.find(original.operationId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Persisted initial gameplay release scope is inconsistent");
  }

  @Test
  void evidenceReaderRejectsJsonbNullButAcceptsSqlNullForCollectingOperation() {
    Scope scope = scope();
    Operation collecting = journal.create(scope, UUID.randomUUID());
    assertThat(collecting.phase()).isEqualTo(Phase.COLLECTING);
    assertThat(collecting.evidence()).isNull();
    assertThat(journal.find(scope.operationId())).contains(collecting);

    InitialGameplayReleaseEvidence proof = evidence(scope);
    journal.bindVerifiedEvidence(
        scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), Instant.now());
    assertThat(
            dsl.execute(
                "UPDATE recovery_control.initial_gameplay_release_operation "
                    + "SET evidence_snapshot=CAST('null' AS jsonb) WHERE operation_id=?",
                scope.operationId()))
        .isEqualTo(1);

    assertThatThrownBy(() -> journal.find(scope.operationId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Persisted initial gameplay release evidence is inconsistent");
  }

  @Test
  void outboxReadersRejectResealedPayloadForAnotherOperationRowKey() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry original = authorizedDispatch(base);
    OutboxEntry foreign = authorizedDispatch(base);
    UUID originalOperationId = original.effect().operationId();
    UUID foreignEffectId = foreign.effect().effectId();
    assertThat(
            dsl.execute(
                "UPDATE recovery_control.initial_gameplay_release_outbox SET effect_id=?, payload=CAST(? AS jsonb), payload_digest=?, operation_tuple_digest=? WHERE operation_id=? AND effect_id=?",
                foreignEffectId,
                tupleCodec.toJson(foreign.effect()),
                foreign.payloadDigest(),
                foreign.effect().operationTupleDigest(),
                originalOperationId,
                original.effect().effectId()))
        .isEqualTo(1);
    assertOutboxReadersRejectMismatchedKey(originalOperationId, foreignEffectId, base);
  }

  @Test
  void outboxReadersRejectResealedPayloadForAnotherEffectRowKey() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry original = authorizedDispatch(base);
    UUID operationId = original.effect().operationId();
    UUID wrongEffectId = UUID.randomUUID();
    assertThat(
            dsl.execute(
                "UPDATE recovery_control.initial_gameplay_release_outbox SET effect_id=? WHERE operation_id=? AND effect_id=?",
                wrongEffectId,
                operationId,
                original.effect().effectId()))
        .isEqualTo(1);
    assertOutboxReadersRejectMismatchedKey(operationId, wrongEffectId, base);
  }

  private static void assertOutboxReadersRejectMismatchedKey(
      UUID operationId, UUID effectId, Instant base) {
    String error = "Persisted initial gameplay release effect is inconsistent";
    assertThatThrownBy(() -> journal.findNextEffect(operationId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(error);
    assertThatThrownBy(
            () -> journal.findEffect(operationId, EffectKind.PREPARE_PLAYABLE_STATE_FENCED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(error);
    assertThatThrownBy(() ->
            journal.claimEffect(
                operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(5)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(error);
  }

  @Test
  void exactReadbackReplayVerifiesRetainedPayloadInBothClosedStates() {
    for (EffectReadbackState state :
        List.of(EffectReadbackState.PREPARED_FENCED, EffectReadbackState.PROVEN_NO_EFFECT)) {
      clearJournal();
      Instant base = Instant.parse("2026-09-01T00:00:00Z");
      OutboxEntry dispatched = authorizedDispatch(base);
      UUID operationId = dispatched.effect().operationId();
      UUID effectId = dispatched.effect().effectId();
      UUID attemptId = dispatched.lastAttemptId();
      EffectReadback valid =
          readbackFor(dispatched, attemptId, state, "terminal", base.plusSeconds(5));
      Operation settled = journal.recordEffectReadback(dispatched, valid, base.plusSeconds(6));
      String before = storedOutbox(operationId, effectId);
      assertThat(journal.recordEffectReadback(dispatched, valid, base.plusSeconds(7)))
          .isEqualTo(settled);
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
      EffectReadback observedAgain =
          readbackFor(dispatched, attemptId, state, "terminal", base.plusSeconds(6));
      if (state == EffectReadbackState.PREPARED_FENCED) {
        assertThatThrownBy(
                () -> journal.recordEffectReadback(dispatched, observedAgain, base.plusSeconds(7)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("preparation readback identity already has an observation");
        assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
        EffectReadback fresh =
            readbackFor(dispatched, attemptId, state, "fresh", observedAgain.observedAt());
        assertThat(journal.recordEffectReadback(dispatched, fresh, base.plusSeconds(7)))
            .isEqualTo(settled);
        var receipts =
            dsl.fetchSingle(
                "SELECT readback_digest, preparation_observation_digest "
                    + "FROM recovery_control.initial_gameplay_release_outbox WHERE effect_id = ?",
                effectId);
        assertThat(receipts.get("readback_digest", String.class))
            .isEqualTo(tupleCodec.digest(valid));
        assertThat(receipts.get("preparation_observation_digest", String.class))
            .isEqualTo(tupleCodec.digest(fresh));
      } else {
        assertThat(journal.recordEffectReadback(dispatched, observedAgain, base.plusSeconds(7)))
            .isEqualTo(settled);
        assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
      }
      String afterObservation = storedOutbox(operationId, effectId);
      EffectReadback stale =
          readbackFor(dispatched, attemptId, state, "terminal", base.plusSeconds(3));
      EffectReadback future =
          readbackFor(dispatched, attemptId, state, "terminal", base.plusSeconds(8));
      assertThatThrownBy(() -> journal.recordEffectReadback(dispatched, stale, base.plusSeconds(7)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("effect readback is stale");
      assertThatThrownBy(
              () -> journal.recordEffectReadback(dispatched, future, base.plusSeconds(7)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("effect readback observation is in the future");
      assertThatThrownBy(() -> journal.recordEffectReadback(dispatched, valid, base.plusSeconds(4)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("effect readback observation is in the future");
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(afterObservation);
      assertThat(journal.find(operationId)).contains(settled);

      EffectReadback mismatched =
          readbackFor(dispatched, UUID.randomUUID(), state, "terminal", base.plusSeconds(5));
      assertThat(
              dsl.execute(
                  "UPDATE recovery_control.initial_gameplay_release_outbox SET readback_payload=CAST(? AS jsonb) WHERE effect_id=?",
                  tupleCodec.toJson(mismatched),
                  effectId))
          .isEqualTo(1);
      String corrupt = storedOutbox(operationId, effectId);
      assertThatThrownBy(() -> journal.recordEffectReadback(dispatched, valid, base.plusSeconds(8)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("retained effect readback does not match exact replay");
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(corrupt);
      assertThat(journal.find(operationId)).contains(settled);
    }
  }

  @Test
  void pendingEffectMustNotRetainADispatchTimestamp() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry pending = authorizedPending(journal, base);
    UUID operationId = pending.effect().operationId();
    UUID effectId = pending.effect().effectId();
    String setDispatchTime =
        "UPDATE recovery_control.initial_gameplay_release_outbox"
            + " SET last_dispatched_at = CAST(? AS timestamptz) WHERE outbox_id = ?";
    assertThatThrownBy(
            () -> dsl.execute(setDispatchTime, base.atOffset(ZoneOffset.UTC), pending.outboxId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_release_outbox_pending_dispatch_timestamp");

    // Simulate disposable schema drift to exercise the journal checks as well.
    String table = "recovery_control.initial_gameplay_release_outbox";
    String constraint = "ck_release_outbox_pending_dispatch_timestamp";
    String definition = installedConstraintDefinition(table, constraint);
    dsl.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + constraint);
    try {
      dsl.execute(setDispatchTime, base.atOffset(ZoneOffset.UTC), pending.outboxId());
      String malformed = storedOutbox(operationId, effectId);
      assertThatThrownBy(() -> journal.findNextEffect(operationId))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("pending effect has a dispatch timestamp");
      assertThatThrownBy(() ->
              journal.claimEffect(
                  operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(4)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("pending effect has a dispatch timestamp");
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(malformed);
    } finally {
      dsl.execute(setDispatchTime, null, pending.outboxId());
      dsl.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " " + definition);
    }
    assertThat(journal.findNextEffect(operationId)).contains(pending);
    assertThat(
            journal
                .claimEffect(operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(4))
                .attemptCount())
        .isEqualTo(1);
  }

  @Test
  void missingAcknowledgedDispatchTimestampDoesNotAllowNextEffectClaim() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry prepare = authorizedDispatch(base);
    UUID operationId = prepare.effect().operationId();
    var evidence =
        readbackFor(
            prepare,
            prepare.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "prepared",
            base.plusSeconds(5));
    journal.recordEffectReadback(prepare, evidence, base.plusSeconds(6));
    UUID handoffId = journal.findNextEffect(operationId).orElseThrow().effect().effectId();
    String pendingHandoff = storedOutbox(operationId, handoffId);

    withMissingDispatchTimestamp(
        prepare,
        () -> {
          assertThatThrownBy(() -> journal.findNextEffect(operationId))
              .isInstanceOf(InitialGameplayReleaseConflictException.class)
              .hasMessageContaining("no dispatch timestamp");
          assertThatThrownBy(() ->
                  journal.claimEffect(
                      operationId, handoffId, UUID.randomUUID(), () -> base.plusSeconds(8)))
              .isInstanceOf(InitialGameplayReleaseConflictException.class)
              .hasMessageContaining("no dispatch timestamp");
          assertThat(storedOutbox(operationId, handoffId)).isEqualTo(pendingHandoff);
        });
  }

  @Test
  void missingDispatchedTimestampRefusesRetainedEffectBeforeOwnerCall() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    withMissingDispatchTimestamp(
        dispatch,
        () -> {
          String malformed = storedOutbox(operationId, effectId);
          assertThatThrownBy(() -> journal.findNextEffect(operationId))
              .isInstanceOf(InitialGameplayReleaseConflictException.class)
              .hasMessageContaining("no dispatch timestamp");
          assertThatThrownBy(() ->
                  journal.claimEffect(
                      operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(8)))
              .isInstanceOf(InitialGameplayReleaseConflictException.class)
              .hasMessageContaining("no dispatch timestamp");
          assertThat(storedOutbox(operationId, effectId)).isEqualTo(malformed);
        });
  }

  private static void withMissingDispatchTimestamp(OutboxEntry dispatch, Runnable assertions) {
    // Simulate disposable schema drift so these tests still exercise the Java guards.
    String table = "recovery_control.initial_gameplay_release_outbox";
    String constraint = "ck_release_outbox_non_pending_attempt";
    String definition = installedConstraintDefinition(table, constraint);
    OffsetDateTime timestamp =
        dsl.fetchSingle(
                "SELECT last_dispatched_at FROM " + table + " WHERE outbox_id = ?",
                dispatch.outboxId())
            .get(0, OffsetDateTime.class);
    dsl.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + constraint);
    try {
      dsl.execute(
          "UPDATE " + table + " SET last_dispatched_at = NULL WHERE outbox_id = ?",
          dispatch.outboxId());
      assertions.run();
    } finally {
      dsl.execute(
          "UPDATE "
              + table
              + " SET last_dispatched_at = CAST(? AS timestamptz)"
              + " WHERE outbox_id = ?",
          timestamp,
          dispatch.outboxId());
      dsl.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " " + definition);
    }
  }

  private static String installedConstraintDefinition(String table, String constraint) {
    return dsl.fetchSingle(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conrelid = CAST(? AS regclass) AND conname = ?",
            table,
            constraint)
        .get(0, String.class);
  }

  @Test
  void handoffClaimRequiresTheExactRetainedPreparationReadback() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry prepare = authorizedDispatch(base);
    UUID operationId = prepare.effect().operationId();
    var readback =
        readbackFor(
            prepare,
            prepare.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "prepared",
            base.plusSeconds(5));
    Operation releasing = journal.recordEffectReadback(prepare, readback, base.plusSeconds(6));
    OutboxEntry handoff = journal.findNextEffect(operationId).orElseThrow();
    UUID handoffId = handoff.effect().effectId();
    String pendingHandoff = storedOutbox(operationId, handoffId);

    dsl.execute(
        "UPDATE recovery_control.initial_gameplay_release_outbox"
            + " SET readback_payload = NULL, readback_digest = NULL, readback_identity = NULL"
            + " WHERE outbox_id = ?",
        prepare.outboxId());
    assertThatThrownBy(() ->
            journal.claimEffect(
                operationId, handoffId, UUID.randomUUID(), () -> base.plusSeconds(8)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("preparation readback is incomplete");
    assertThat(storedOutbox(operationId, handoffId)).isEqualTo(pendingHandoff);
    assertThat(journal.find(operationId)).contains(releasing);

    dsl.execute(
        "UPDATE recovery_control.initial_gameplay_release_outbox"
            + " SET readback_payload = CAST(? AS jsonb), readback_digest = ?, readback_identity = ?"
            + " WHERE outbox_id = ?",
        tupleCodec.toJson(readback),
        tupleCodec.digest(readback),
        readback.readbackIdentity(),
        prepare.outboxId());
    assertThat(
            journal
                .claimEffect(operationId, handoffId, UUID.randomUUID(), () -> base.plusSeconds(8))
                .state())
        .isEqualTo(EffectOutboxState.DISPATCHED);
  }

  @Test
  void exactAcknowledgementReplayRequiresItsOwnRecordingTime() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry prepare = authorizedDispatch(base);
    var evidence =
        readbackFor(
            prepare,
            prepare.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "prepared",
            base.plusSeconds(7));
    journal.recordEffectReadback(prepare, evidence, base.plusSeconds(7));
    dsl.execute(
        "UPDATE recovery_control.initial_gameplay_release_outbox"
            + " SET acknowledged_at = CAST(? AS timestamptz) WHERE outbox_id = ?",
        base.plusSeconds(6).atOffset(ZoneOffset.UTC),
        prepare.outboxId());

    assertThatThrownBy(() -> journal.recordEffectReadback(prepare, evidence, base.plusSeconds(8)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("after its recording time");
  }

  @Test
  void finalizedOperationRequiresTheRetainedHandoffReceiptBeforeReturningNoWork() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry original = authorizedDispatch(base.minusSeconds(10));
    UUID operationId = original.effect().operationId();
    EffectReadback closure =
        readbackFor(
            original,
            original.lastAttemptId(),
            EffectReadbackState.PROVEN_NO_EFFECT,
            "original-prepare-closed",
            base.minusSeconds(4));
    journal.recordEffectReadback(original, closure, base.minusSeconds(3));
    OutboxEntry prepare =
        journal.claimEffect(
            operationId,
            original.effect().effectId(),
            UUID.randomUUID(),
            () -> base.plusSeconds(4));
    EffectReadback prepared =
        readbackFor(
            prepare,
            prepare.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "prepare-readback-for-handoff",
            base.plusSeconds(5));
    assertThat(journal.recordEffectReadback(prepare, prepared, base.plusSeconds(5)).phase())
        .isEqualTo(Phase.RELEASING);
    OutboxEntry pendingHandoff = journal.findNextEffect(operationId).orElseThrow();
    OutboxEntry handoff =
        journal.claimEffect(
            operationId,
            pendingHandoff.effect().effectId(),
            UUID.randomUUID(),
            () -> base.plusSeconds(6));
    UUID effectId = handoff.effect().effectId();
    var receipt =
        readbackFor(
            handoff,
            handoff.lastAttemptId(),
            EffectReadbackState.OWNER_HANDOFF_APPLIED,
            "terminal-handoff",
            base.plusSeconds(7));
    Operation finalized = journal.recordEffectReadback(handoff, receipt, base.plusSeconds(8));
    var continuation = transitionTuple(finalized, TransitionVerb.CONTINUE_RECOVERY);
    var resume = transitionTuple(finalized, TransitionVerb.RESUME);
    var port = mock(InitialGameplayReleaseEffectPort.class);
    var controller =
        new InitialGameplayReleaseController(
            journal, tupleCodec, mock(InitialGameplayReleaseEvidenceProvider.class), port);

    assertThat(journal.findNextEffect(operationId)).isEmpty();
    assertThat(controller.reconcileNextEffect(operationId)).isEqualTo(finalized);
    String retainedPrepare = storedOutbox(operationId, prepare.effect().effectId());
    String retainedHandoff = storedOutbox(operationId, effectId);
    var continued = new PublicTransitionResult(operationId, Phase.AWAITING_RESUME);
    var resumed = new PublicTransitionResult(operationId, Phase.RESUME_AUTHORIZED);
    assertThat(journal.findTransition(continuation)).contains(continued);
    assertThat(journal.transition(continuation, base.plusSeconds(9))).isEqualTo(continued);
    assertThat(journal.findTransition(resume)).contains(resumed);
    assertThat(journal.transition(resume, base.plusSeconds(9))).isEqualTo(resumed);
    assertThat(finalized.phase()).isEqualTo(Phase.FINALIZED);
    assertThat(journal.find(operationId)).contains(finalized);
    assertThat(storedOutbox(operationId, prepare.effect().effectId())).isEqualTo(retainedPrepare);
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retainedHandoff);
    assertThat(journal.recordEffectReadback(original, closure, base.plusSeconds(9)))
        .isEqualTo(finalized);
    Scope scope = finalized.scope();
    InitialGameplayReleaseEvidence ownerEvidence = finalized.evidence();
    assertThat(journal.create(scope, finalized.maintenanceLockIdentity())).isEqualTo(finalized);
    assertThat(
            journal.bindVerifiedEvidence(
                scope,
                tupleCodec.digest(scope),
                ownerEvidence,
                tupleCodec.digest(ownerEvidence),
                base.plusSeconds(9)))
        .isEqualTo(finalized);
    EffectReadback retimedPreparation =
        readbackFor(
            prepare,
            prepare.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            prepared.readbackIdentity(),
            base.plusSeconds(9));
    assertThat(journal.recordEffectReadback(prepare, prepared, base.plusSeconds(10)))
        .isEqualTo(finalized);
    assertThatThrownBy(
            () -> journal.recordEffectReadback(prepare, retimedPreparation, base.plusSeconds(10)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("preparation readback identity already has an observation");
    assertThat(storedOutbox(operationId, prepare.effect().effectId())).isEqualTo(retainedPrepare);
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retainedHandoff);
    dsl.execute(
        "UPDATE recovery_control.initial_gameplay_release_outbox"
            + " SET readback_identity = NULL, readback_payload = NULL, readback_digest = NULL"
            + " WHERE outbox_id = ?",
        handoff.outboxId());
    String incomplete = storedOutbox(operationId, effectId);

    assertThatThrownBy(() -> journal.findNextEffect(operationId))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("retained handoff readback is incomplete");
    assertThatThrownBy(() -> controller.reconcileNextEffect(operationId))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("retained handoff readback is incomplete");
    for (PublicTransitionTuple replay : List.of(continuation, resume)) {
      assertThatThrownBy(() -> journal.findTransition(replay))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("retained handoff readback is incomplete");
      assertThatThrownBy(() -> journal.transition(replay, base.plusSeconds(10)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("retained handoff readback is incomplete");
    }
    assertThatThrownBy(() -> journal.recordEffectReadback(original, closure, base.plusSeconds(10)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("retained handoff readback is incomplete");
    assertThatThrownBy(() -> journal.create(scope, finalized.maintenanceLockIdentity()))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("retained handoff readback is incomplete");
    assertThatThrownBy(() ->
            journal.bindVerifiedEvidence(
                scope,
                tupleCodec.digest(scope),
                ownerEvidence,
                tupleCodec.digest(ownerEvidence),
                base.plusSeconds(10)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("retained handoff readback is incomplete");
    for (EffectReadback replay : List.of(prepared, retimedPreparation)) {
      assertThatThrownBy(() -> journal.recordEffectReadback(prepare, replay, base.plusSeconds(10)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("retained handoff readback is incomplete");
    }
    assertThat(storedOutbox(operationId, prepare.effect().effectId())).isEqualTo(retainedPrepare);
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(incomplete);
    verifyNoInteractions(port);
  }

  @Test
  void dispatchClaimReportsAWriteSuppressedByDatabaseDrift() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry pending = authorizedPending(journal, base);
    UUID operationId = pending.effect().operationId();
    UUID effectId = pending.effect().effectId();
    String retained = storedOutbox(operationId, effectId);

    withSuppressedOutboxUpdate(() ->
        assertThatThrownBy(() ->
                journal.claimEffect(
                    operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(4)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("effect dispatch claim was not recorded"));

    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    assertThat(
            journal
                .claimEffect(operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(5))
                .state())
        .isEqualTo(EffectOutboxState.DISPATCHED);
  }

  @Test
  void dispatchClaimPreservesContradictoryPendingReadback() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry pending = authorizedPending(journal, base);
    UUID operationId = pending.effect().operationId();
    UUID effectId = pending.effect().effectId();
    UUID attemptId = UUID.randomUUID();
    assertThat(
            dsl.execute(
                """
                UPDATE recovery_control.initial_gameplay_release_outbox
                SET readback_identity = 'retained-pending-readback',
                    readback_payload = '{}'::jsonb, readback_digest = ?
                WHERE outbox_id = ?
                """,
                tupleCodec.digest(pending.effect()),
                pending.outboxId()))
        .isEqualTo(1);
    String retained = storedOutbox(operationId, effectId);
    assertThatThrownBy(
            () -> journal.claimEffect(operationId, effectId, attemptId, () -> base.plusSeconds(4)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("retained pending effect has readback evidence");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
  }

  @Test
  void acknowledgedReadbackReplayRequiresTheEffectKindsSuccessfulStateInBothReplayBranches() {
    for (EffectKind effectKind : List.of(EffectKind.PREPARE_PLAYABLE_STATE_FENCED)) {
      for (boolean exactReplay : List.of(true, false)) {
        clearJournal();
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        OutboxEntry dispatched = authorizedDispatch(effectKind, base);
        UUID operationId = dispatched.effect().operationId();
        UUID effectId = dispatched.effect().effectId();
        Instant observedAt =
            base.plusSeconds(effectKind == EffectKind.PREPARE_PLAYABLE_STATE_FENCED ? 5 : 7);
        EffectReadbackState wrongState =
            effectKind == EffectKind.PREPARE_PLAYABLE_STATE_FENCED
                ? EffectReadbackState.OWNER_HANDOFF_APPLIED
                : EffectReadbackState.PREPARED_FENCED;
        EffectReadback wrongStateReadback =
            readbackFor(
                dispatched,
                dispatched.lastAttemptId(),
                wrongState,
                "wrong-effect-kind-state",
                observedAt);
        Operation acknowledged =
            journal.recordEffectReadback(
                dispatched,
                readbackFor(
                    dispatched,
                    dispatched.lastAttemptId(),
                    effectKind == EffectKind.PREPARE_PLAYABLE_STATE_FENCED
                        ? EffectReadbackState.PREPARED_FENCED
                        : EffectReadbackState.OWNER_HANDOFF_APPLIED,
                    "successful-readback",
                    observedAt),
                observedAt.plusSeconds(1));
        assertThat(
                dsl.execute(
                    "UPDATE recovery_control.initial_gameplay_release_outbox "
                        + "SET readback_identity=?, readback_payload=CAST(? AS jsonb), "
                        + "readback_digest=? WHERE operation_id=? AND effect_id=?",
                    wrongStateReadback.readbackIdentity(),
                    tupleCodec.toJson(wrongStateReadback),
                    tupleCodec.digest(wrongStateReadback),
                    operationId,
                    effectId))
            .isEqualTo(1);
        String corrupted = storedOutbox(operationId, effectId);
        EffectReadback replay =
            exactReplay
                ? wrongStateReadback
                : readbackFor(
                    dispatched,
                    dispatched.lastAttemptId(),
                    wrongState,
                    "wrong-effect-kind-state",
                    observedAt.plusSeconds(2));

        assertThatThrownBy(
                () -> journal.recordEffectReadback(dispatched, replay, observedAt.plusSeconds(3)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("did not prove its exact durable outcome");
        assertThat(storedOutbox(operationId, effectId)).isEqualTo(corrupted);
        assertThat(journal.find(operationId)).contains(acknowledged);
      }
    }
  }

  @Test
  void finalizedHandoffRejectsReplayWhenItsRetainedAcknowledgementIsCorrupted() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatched = authorizedDispatch(EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF, base);
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    Instant observedAt = base.plusSeconds(7);
    EffectReadback successful =
        readbackFor(
            dispatched,
            dispatched.lastAttemptId(),
            EffectReadbackState.OWNER_HANDOFF_APPLIED,
            "successful-handoff-readback",
            observedAt);
    assertThat(journal.recordEffectReadback(dispatched, successful, observedAt).phase())
        .isEqualTo(Phase.FINALIZED);
    EffectReadback corruptedReceipt =
        readbackFor(
            dispatched,
            dispatched.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "corrupted-handoff-readback",
            observedAt);
    assertThat(
            dsl.execute(
                "UPDATE recovery_control.initial_gameplay_release_outbox "
                    + "SET readback_identity=?, readback_payload=CAST(? AS jsonb), "
                    + "readback_digest=? WHERE operation_id=? AND effect_id=?",
                corruptedReceipt.readbackIdentity(),
                tupleCodec.toJson(corruptedReceipt),
                tupleCodec.digest(corruptedReceipt),
                operationId,
                effectId))
        .isEqualTo(1);
    String corrupted = storedOutbox(operationId, effectId);

    assertThatThrownBy(
            () -> journal.recordEffectReadback(dispatched, successful, base.plusSeconds(9)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("did not prove its exact durable outcome");
    assertThatThrownBy(() -> journal.find(operationId))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("did not prove its exact durable outcome");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(corrupted);
  }

  @Test
  void handoffCannotFinalizeWhenTheReceiptWriteIsSkipped() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatched = authorizedDispatch(EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF, base);
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    String retained = storedOutbox(operationId, effectId);
    Instant observedAt = base.plusSeconds(7);
    EffectReadback receipt =
        readbackFor(
            dispatched,
            dispatched.lastAttemptId(),
            EffectReadbackState.OWNER_HANDOFF_APPLIED,
            "successful-handoff-readback",
            observedAt);

    dsl.execute(
        """
        CREATE FUNCTION recovery_control.fixture_skip_outbox_update() RETURNS trigger
        LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$
        """);
    try {
      dsl.execute(
          """
          CREATE TRIGGER fixture_skip_outbox_update BEFORE UPDATE
          ON recovery_control.initial_gameplay_release_outbox FOR EACH ROW
          WHEN (NEW.state = 'acknowledged')
          EXECUTE FUNCTION recovery_control.fixture_skip_outbox_update()
          """);
      assertThatThrownBy(() -> journal.recordEffectReadback(dispatched, receipt, observedAt))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("effect readback compare-and-set lost");
      assertThat(journal.find(operationId))
          .get()
          .extracting(Operation::phase)
          .isEqualTo(Phase.RELEASING);
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    } finally {
      dsl.execute(
          "DROP TRIGGER IF EXISTS fixture_skip_outbox_update"
              + " ON recovery_control.initial_gameplay_release_outbox");
      dsl.execute("DROP FUNCTION recovery_control.fixture_skip_outbox_update()");
    }
    assertThat(journal.recordEffectReadback(dispatched, receipt, observedAt).phase())
        .isEqualTo(Phase.FINALIZED);
  }

  @Test
  void finalizedHandoffAcknowledgementSupportsExactAndLaterSameOutcomeReplay() {
    for (boolean exactReplay : List.of(true, false)) {
      clearJournal();
      Instant base = Instant.parse("2026-09-01T00:00:00Z");
      OutboxEntry dispatched = authorizedDispatch(EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF, base);
      Instant observedAt = base.plusSeconds(7);
      EffectReadback acknowledged =
          readbackFor(
              dispatched,
              dispatched.lastAttemptId(),
              EffectReadbackState.OWNER_HANDOFF_APPLIED,
              "successful-handoff-readback",
              observedAt);
      Operation finalized = journal.recordEffectReadback(dispatched, acknowledged, observedAt);
      assertThat(finalized.phase()).isEqualTo(Phase.FINALIZED);
      String retained =
          storedOutbox(dispatched.effect().operationId(), dispatched.effect().effectId());
      EffectReadback replay =
          exactReplay
              ? acknowledged
              : readbackFor(
                  dispatched,
                  dispatched.lastAttemptId(),
                  EffectReadbackState.OWNER_HANDOFF_APPLIED,
                  "successful-handoff-readback",
                  observedAt.plusSeconds(2));

      assertThat(journal.recordEffectReadback(dispatched, replay, base.plusSeconds(10)))
          .isEqualTo(finalized);
      assertThat(storedOutbox(dispatched.effect().operationId(), dispatched.effect().effectId()))
          .isEqualTo(retained);
    }
  }

  @Test
  void intactPreparationAcknowledgementRejectsWrongStateAndAcceptsFreshProof() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    journal.recordEffectReadback(
        dispatch,
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "original-preparation",
            base.plusSeconds(5)),
        base.plusSeconds(5));
    String acknowledgement = storedOutbox(operationId, effectId);
    EffectReadback wrong =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.OWNER_HANDOFF_APPLIED,
            "wrong-preparation-state",
            base.plusSeconds(6));
    assertThatThrownBy(() -> journal.recordEffectReadback(dispatch, wrong, base.plusSeconds(6)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("did not prove its exact durable outcome");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(acknowledgement);
    EffectReadback refreshed =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "distinct-preparation",
            base.plusSeconds(7));
    assertThat(journal.recordEffectReadback(dispatch, refreshed, base.plusSeconds(7)).phase())
        .isEqualTo(Phase.RELEASING);
    assertThat(storedOutbox(operationId, effectId)).contains(tupleCodec.digest(refreshed));
  }

  @Test
  void definitiveOwnerRejectionRequiresFreshAttemptEvenAfterReplacement() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatched = authorizedDispatch(base);
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    UUID rejectedAttempt = dispatched.lastAttemptId();
    assertThat(
            journal.recordRetryableEffectForDispatch(
                dispatched, RetrySource.OWNER_REJECTED, "OWNER_REJECTED", base.plusSeconds(5)))
        .isTrue();
    assertThat(journal.findNextEffect(operationId))
        .get()
        .extracting(OutboxEntry::state)
        .isEqualTo(EffectOutboxState.RETRYABLE);
    assertThat(storedOutbox(operationId, effectId)).contains("\"readback_payload\":null");
    assertThatThrownBy(() ->
            journal.claimEffect(operationId, effectId, rejectedAttempt, () -> base.plusSeconds(6)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("a retry must use a new effect attempt identity");
    UUID freshAttempt = UUID.randomUUID();
    OutboxEntry retried =
        journal.claimEffect(operationId, effectId, freshAttempt, () -> base.plusSeconds(6));
    assertThat(retried.state()).isEqualTo(EffectOutboxState.DISPATCHED);
    assertThat(retried.lastAttemptId()).isEqualTo(freshAttempt);
    assertThat(retried.attemptCount()).isEqualTo(dispatched.attemptCount() + 1);
    assertThat(
            journal.recordAmbiguousEffectForDispatch(
                dispatched, "LATE_TRANSPORT_ERROR", base.plusSeconds(7)))
        .isFalse();
    assertThat(journal.findNextEffect(operationId)).contains(retried);
    assertThat(
            journal.recordRetryableEffectForDispatch(
                retried, RetrySource.NOT_DISPATCHED, "TRANSPORT_REFUSED", base.plusSeconds(8)))
        .isTrue();
    var reopened = new JooqInitialGameplayReleaseJournal(dsl, tupleCodec);
    String before = storedOutbox(operationId, effectId);
    assertThatThrownBy(() ->
            reopened.claimEffect(operationId, effectId, rejectedAttempt, () -> base.plusSeconds(9)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("a retry must use a new effect attempt identity");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
  }

  @Test
  void transitionReplaysRejectCorruptRetainedPayloadAndPhasesWithoutWrites() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    Scope scope = scope();
    journal.create(scope, UUID.randomUUID());
    InitialGameplayReleaseEvidence proof = evidence(scope);
    Operation ready =
        journal.bindVerifiedEvidence(
            scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), base);
    PublicTransitionTuple continuing = transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY);
    journal.transition(continuing, base.plusSeconds(1));
    PublicTransitionTuple resuming = transitionTuple(ready, TransitionVerb.RESUME);
    journal.transition(resuming, base.plusSeconds(2));
    Operation authorized = journal.find(scope.operationId()).orElseThrow();
    OutboxEntry pending = journal.findNextEffect(scope.operationId()).orElseThrow();
    String outbox = storedOutbox(scope.operationId(), pending.effect().effectId());

    for (PublicTransitionTuple tuple : List.of(continuing, resuming)) {
      var canonical = tupleCodec.toJson(tuple);
      var withExtra = canonical.substring(0, canonical.length() - 1) + ",\"unknownField\":true}";

      var expected = new PublicTransitionResult(scope.operationId(), tuple.verb().resultPhase());
      assertThat(journal.findTransition(tuple)).contains(expected);
      assertThat(journal.transition(tuple, base.plusSeconds(3))).isEqualTo(expected);
      for (String corruption :
          List.of(
              "tuple_payload=jsonb_set(tuple_payload, '{maintenanceLockIdentity}', to_jsonb(CAST(? AS text)))",
              "tuple_payload=CAST(? AS jsonb)",
              "tuple_payload=tuple_payload || CAST(? AS jsonb)",
              "expected_phase=?",
              "result_phase=?")) {
        Object broken =
            corruption.contains("jsonb_set")
                ? UUID.randomUUID().toString()
                : corruption.contains("||")
                    ? withExtra
                    : corruption.contains("jsonb") ? "{}" : "collecting";
        dsl.execute(
            "UPDATE recovery_control.initial_gameplay_release_transition SET "
                + corruption
                + " WHERE operation_id=? AND verb=?",
            broken,
            scope.operationId(),
            tuple.verb().wireValue());
        assertThatThrownBy(() -> journal.findTransition(tuple))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("retained public transition tuple changed");
        assertThatThrownBy(() -> journal.transition(tuple, base.plusSeconds(4)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("retained public transition tuple changed");
        assertThat(journal.find(scope.operationId())).contains(authorized);
        assertThat(storedOutbox(scope.operationId(), pending.effect().effectId()))
            .isEqualTo(outbox);
        dsl.execute(
            "UPDATE recovery_control.initial_gameplay_release_transition "
                + "SET tuple_payload=CAST(? AS jsonb), expected_phase=?, result_phase=? "
                + "WHERE operation_id=? AND verb=?",
            tupleCodec.toJson(tuple),
            tuple.expectedPhase().databaseValue(),
            tuple.verb().resultPhase().databaseValue(),
            scope.operationId(),
            tuple.verb().wireValue());
      }
    }
  }

  @Test
  void claimAndReadbackRejectEffectFromAnotherMaintenanceLockWithoutWrites() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatched = authorizedDispatch(base);
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    UUID attemptId = dispatched.lastAttemptId();
    EffectReadback readback =
        readbackFor(
            dispatched,
            attemptId,
            EffectReadbackState.PREPARED_FENCED,
            "prepared",
            base.plusSeconds(5));
    assertThat(
            dsl.execute(
                "UPDATE recovery_control.initial_gameplay_release_operation SET maintenance_lock_identity=? WHERE operation_id=?",
                UUID.randomUUID(),
                operationId))
        .isEqualTo(1);
    Operation corrupted = journal.find(operationId).orElseThrow();
    String outbox = storedOutbox(operationId, effectId);

    assertThatThrownBy(
            () -> journal.claimEffect(operationId, effectId, attemptId, () -> base.plusSeconds(5)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("retained effect does not match the operation-owned tuple");
    assertThatThrownBy(
            () -> journal.recordEffectReadback(dispatched, readback, base.plusSeconds(6)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("retained effect does not match the operation-owned tuple");
    assertThat(journal.find(operationId)).contains(corrupted);
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(outbox);
    assertThat(journal.findEffect(operationId, EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .isEmpty();
  }

  @Test
  void durableClosureRejectsReclaimAndLetsOnlyExactDelayedDispatchBecomeSuperseded() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry a = authorizedDispatch(base);
    UUID operationId = a.effect().operationId();
    UUID effectId = a.effect().effectId();
    EffectReadback closedAReadback =
        readbackFor(
            a,
            a.lastAttemptId(),
            EffectReadbackState.PROVEN_NO_EFFECT,
            "closed-a",
            base.plusSeconds(5));
    journal.recordEffectReadback(a, closedAReadback, base.plusSeconds(5));
    String closedA = storedClosure(a);
    OutboxEntry b =
        journal.claimEffect(operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(6));
    assertThat(storedClosure(a)).isEqualTo(closedA);
    String before = storedOutbox(operationId, effectId);
    Operation beforeReplay = journal.find(operationId).orElseThrow();
    assertThat(journal.recordEffectReadback(a, closedAReadback, base.plusSeconds(7)))
        .isEqualTo(beforeReplay);
    var newerReading =
        readbackFor(
            a,
            a.lastAttemptId(),
            EffectReadbackState.PROVEN_NO_EFFECT,
            "closed-a",
            base.plusSeconds(7));
    assertThat(journal.recordEffectReadback(a, newerReading, base.plusSeconds(7)))
        .isEqualTo(beforeReplay);
    var staleReading =
        readbackFor(
            a,
            a.lastAttemptId(),
            EffectReadbackState.PROVEN_NO_EFFECT,
            "closed-a",
            base.plusSeconds(3));
    assertThatThrownBy(() -> journal.recordEffectReadback(a, staleReading, base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("readback is stale");
    assertThatThrownBy(() -> journal.recordEffectReadback(a, newerReading, base.plusSeconds(6)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("effect readback changed after definitive no effect");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
    assertThat(journal.findNextEffect(operationId)).contains(b);
    EffectReadback alteredAReadback =
        readbackFor(
            a,
            a.lastAttemptId(),
            EffectReadbackState.PROVEN_NO_EFFECT,
            "altered-closed-a",
            base.plusSeconds(5));
    assertThatThrownBy(() -> journal.recordEffectReadback(a, alteredAReadback, base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("effect readback changed after definitive no effect");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
    assertThat(storedClosure(a)).isEqualTo(closedA);
    assertThat(journal.findNextEffect(operationId)).contains(b);
    assertThat(
            journal.recordAmbiguousEffectForDispatch(a, "DELAYED_AMBIGUOUS", base.plusSeconds(7)))
        .isFalse();
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
    assertThat(storedClosure(a)).isEqualTo(closedA);
    assertThat(
            journal.recordRetryableEffectForDispatch(
                a, RetrySource.OWNER_REJECTED, "DELAYED_NO_EFFECT", base.plusSeconds(7)))
        .isFalse();
    assertThatThrownBy(() ->
            journal.claimEffect(
                operationId, effectId, a.lastAttemptId(), () -> base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
    journal.recordEffectReadback(
        b,
        readbackFor(
            b,
            b.lastAttemptId(),
            EffectReadbackState.PROVEN_NO_EFFECT,
            "closed-b",
            base.plusSeconds(8)),
        base.plusSeconds(8));
    before = storedOutbox(operationId, effectId);
    assertThatThrownBy(() ->
            journal.claimEffect(
                operationId, effectId, a.lastAttemptId(), () -> base.plusSeconds(9)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
    OutboxEntry c =
        journal.claimEffect(operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(10));
    assertThat(c.state()).isEqualTo(EffectOutboxState.DISPATCHED);
    assertThat(c.lastAttemptId()).isNotIn(a.lastAttemptId(), b.lastAttemptId());
  }

  @Test
  void localNoSendPersistsTwoGenerationsOfTheSameAttemptWithoutRewritingReceipts() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry first = authorizedDispatch(base);
    UUID operationId = first.effect().operationId();
    UUID effectId = first.effect().effectId();
    UUID attemptId = first.lastAttemptId();

    assertThat(
            journal.recordRetryableEffectForDispatch(
                first, RetrySource.NOT_DISPATCHED, "TRANSPORT_REFUSED", base.plusSeconds(5)))
        .isTrue();
    var firstReceipt = localNoSendReceipts(first);
    assertThat(firstReceipt).hasSize(1);
    assertThat(storedOutbox(operationId, effectId))
        .contains("\"retry_provenance\":\"local_no_send\"");

    var reopened = new JooqInitialGameplayReleaseJournal(dsl, tupleCodec);
    String firstStored = storedOutbox(operationId, effectId);
    assertThat(
            reopened.recordRetryableEffectForDispatch(
                first, RetrySource.NOT_DISPATCHED, "TRANSPORT_REFUSED", base.plusSeconds(6)))
        .isTrue();
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(firstStored);
    assertThat(localNoSendReceipts(first)).isEqualTo(firstReceipt);

    OutboxEntry second =
        reopened.claimEffect(operationId, effectId, attemptId, () -> base.plusSeconds(7));
    assertThat(second.state()).isEqualTo(EffectOutboxState.DISPATCHED);
    assertThat(second.lastAttemptId()).isEqualTo(attemptId);
    assertThat(second.attemptCount()).isEqualTo(first.attemptCount() + 1);
    assertThat(storedOutbox(operationId, effectId)).contains("\"retry_provenance\":null");
    assertThat(
            reopened.recordRetryableEffectForDispatch(
                second, RetrySource.NOT_DISPATCHED, "TRANSPORT_REFUSED", base.plusSeconds(8)))
        .isTrue();
    assertThat(localNoSendReceipts(second)).hasSize(2).startsWith(firstReceipt.getFirst());
    assertThat(
            dsl.fetchSingle(
                    """
                    SELECT count(*)
                    FROM recovery_control.initial_gameplay_release_attempt_local_no_send p
                    JOIN recovery_control.initial_gameplay_release_outbox o USING (outbox_id)
                    WHERE p.outbox_id = ? AND p.attempt_id = o.last_attempt_id
                      AND p.attempt_ordinal = o.attempt_count
                      AND p.dispatched_at = o.last_dispatched_at
                    """,
                    second.outboxId())
                .get(0, Integer.class))
        .isEqualTo(1);
  }

  @Test
  void existingDispatchCannotAuthorizeAnotherCallerForReusedAttemptIdentity() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry first = authorizedDispatch(base);
    UUID operationId = first.effect().operationId();
    UUID effectId = first.effect().effectId();
    UUID attemptId = first.lastAttemptId();
    assertThat(
            journal.recordRetryableEffectForDispatch(
                first, RetrySource.NOT_DISPATCHED, "TRANSPORT_REFUSED", base.plusSeconds(5)))
        .isTrue();

    OutboxEntry second =
        journal.claimEffect(operationId, effectId, attemptId, () -> base.plusSeconds(6));
    assertThat(second.lastAttemptId()).isEqualTo(attemptId);
    assertThat(second.attemptCount()).isEqualTo(first.attemptCount() + 1);
    String retained = storedOutbox(operationId, effectId);
    assertThatThrownBy(
            () -> journal.claimEffect(operationId, effectId, attemptId, () -> base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("already claimed");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
  }

  @Test
  void dispatchClaimRefusesCallerOwnedJooqTransaction() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatched = authorizedDispatch(base);
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    assertThat(
            journal.recordRetryableEffectForDispatch(
                dispatched, RetrySource.NOT_DISPATCHED, "TRANSPORT_REFUSED", base.plusSeconds(5)))
        .isTrue();
    String retained = storedOutbox(operationId, effectId);
    UUID newAttempt = UUID.randomUUID();

    dsl.transaction(configuration -> {
      var transactionBoundJournal =
          new JooqInitialGameplayReleaseJournal(DSL.using(configuration), tupleCodec);
      assertThatThrownBy(() ->
              transactionBoundJournal.claimEffect(
                  operationId, effectId, newAttempt, () -> base.plusSeconds(6)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("requires its own transaction");
    });

    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    assertThat(journal.claimEffect(operationId, effectId, newAttempt, () -> base.plusSeconds(6)))
        .extracting(OutboxEntry::lastAttemptId)
        .isEqualTo(newAttempt);
  }

  @Test
  void exactLocalNoSendCanBeRetainedAfterTheClockMovesBackward() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatched = authorizedDispatch(base);
    assertThat(
            journal.recordRetryableEffectForDispatch(
                dispatched, RetrySource.NOT_DISPATCHED, "TRANSPORT_REFUSED", base.plusSeconds(3)))
        .isTrue();
    assertThat(
            dsl.fetchSingle(
                    """
                    SELECT recorded_at = dispatched_at AS at_dispatch
                    FROM recovery_control.initial_gameplay_release_attempt_local_no_send
                    WHERE outbox_id = ?
                    """,
                    dispatched.outboxId())
                .get("at_dispatch", Boolean.class))
        .isTrue();
    UUID replacementAttempt = UUID.randomUUID();
    assertThat(
            journal.claimEffect(
                dispatched.effect().operationId(),
                dispatched.effect().effectId(),
                replacementAttempt,
                () -> base.plusSeconds(5)))
        .extracting(OutboxEntry::lastAttemptId)
        .isEqualTo(replacementAttempt);
  }

  @Test
  void laterDispatchOfSameAttemptCanAcceptPositiveReadbackAfterLocalNoSend() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry first = authorizedDispatch(base);
    UUID operationId = first.effect().operationId();
    UUID effectId = first.effect().effectId();
    UUID attemptId = first.lastAttemptId();
    assertThat(
            journal.recordRetryableEffectForDispatch(
                first, RetrySource.NOT_DISPATCHED, "TRANSPORT_REFUSED", base.plusSeconds(5)))
        .isTrue();

    OutboxEntry next =
        journal.claimEffect(operationId, effectId, attemptId, () -> base.plusSeconds(6));
    assertThat(next.attemptCount()).isEqualTo(first.attemptCount() + 1);
    assertThat(localNoSendReceipts(first)).hasSize(1);
    var prepared =
        readbackFor(
            next,
            attemptId,
            EffectReadbackState.PREPARED_FENCED,
            "current-generation-preparation",
            base.plusSeconds(7));
    assertThat(journal.recordEffectReadback(next, prepared, base.plusSeconds(7)).phase())
        .isEqualTo(Phase.RELEASING);
  }

  @Test
  void aLocallyUnsentRetryRequiresACertainRetainedTransportOutcome() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatched = authorizedDispatch(base);
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    assertThat(
            journal.recordRetryableEffectForDispatch(
                dispatched, RetrySource.NOT_DISPATCHED, "TRANSPORT_REFUSED", base.plusSeconds(5)))
        .isTrue();
    assertThat(
            dsl.execute(
                "UPDATE recovery_control.initial_gameplay_release_outbox"
                    + " SET send_outcome_uncertain = true WHERE outbox_id = ?",
                dispatched.outboxId()))
        .isEqualTo(1);

    String retained = storedOutbox(operationId, effectId);
    assertThatThrownBy(() ->
            journal.claimEffect(
                operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(6)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("retained local no-send proof does not match");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
  }

  @Test
  void aRetryRequiresTheExactLocalNoSendReceipt() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatched = authorizedDispatch(base);
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    assertThat(
            journal.recordRetryableEffectForDispatch(
                dispatched, RetrySource.NOT_DISPATCHED, "TRANSPORT_REFUSED", base.plusSeconds(5)))
        .isTrue();
    assertThat(
            dsl.execute(
                "DELETE FROM recovery_control.initial_gameplay_release_attempt_local_no_send"
                    + " WHERE outbox_id = ? AND attempt_id = ? AND attempt_ordinal = ?",
                dispatched.outboxId(),
                dispatched.lastAttemptId(),
                dispatched.attemptCount()))
        .isEqualTo(1);
    String before = storedOutbox(operationId, effectId);
    for (UUID candidate : List.of(dispatched.lastAttemptId(), UUID.randomUUID())) {
      assertThatThrownBy(() ->
              journal.claimEffect(operationId, effectId, candidate, () -> base.plusSeconds(6)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("retained local no-send proof does not match");
    }
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retainedPartialMustBeAtOrAfterItsStoredDispatch(boolean ownerRejected) {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    UUID attemptId = dispatch.lastAttemptId();
    if (ownerRejected) {
      assertThat(
              journal.recordRetryableEffectForDispatch(
                  dispatch, RetrySource.OWNER_REJECTED, "OWNER_REJECTED", base.plusSeconds(4)))
          .isTrue();
    }
    var partial =
        readbackFor(
            dispatch,
            attemptId,
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "partial",
            base.plusSeconds(5));
    var newerPartial =
        readbackFor(
            dispatch,
            attemptId,
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "newer-partial",
            base.plusSeconds(7));
    Operation operation = journal.recordEffectReadback(dispatch, partial, base.plusSeconds(6));
    String updateDispatch =
        "UPDATE recovery_control.initial_gameplay_release_outbox"
            + " SET last_dispatched_at = CAST(? AS timestamptz) WHERE outbox_id = ?";
    assertThat(
            dsl.execute(
                updateDispatch, base.plusSeconds(6).atOffset(ZoneOffset.UTC), dispatch.outboxId()))
        .isEqualTo(1);
    String retained = storedOutbox(operationId, effectId);
    assertThatThrownBy(
            () -> journal.recordEffectReadback(dispatch, newerPartial, base.plusSeconds(8)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("effect readback is stale");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    assertThat(journal.find(operationId)).contains(operation);

    // An observation from the exact stored dispatch instant is a valid floor.
    assertThat(
            dsl.execute(
                updateDispatch, partial.observedAt().atOffset(ZoneOffset.UTC), dispatch.outboxId()))
        .isEqualTo(1);
    assertThat(journal.recordEffectReadback(dispatch, newerPartial, base.plusSeconds(8)))
        .isEqualTo(operation);
    assertThat(storedOutbox(operationId, effectId)).contains(tupleCodec.digest(newerPartial));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void exactLocalNoSendAndPartialReadbackCanArriveInEitherOrder(boolean partialFirst) {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    var observation =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "partial",
            base.plusSeconds(5));
    if (partialFirst) {
      journal.recordEffectReadback(dispatch, observation, base.plusSeconds(6));
    }
    assertThat(
            journal.recordRetryableEffectForDispatch(
                dispatch, RetrySource.NOT_DISPATCHED, "TRANSPORT_REFUSED", base.plusSeconds(7)))
        .isTrue();
    if (!partialFirst) {
      journal.recordEffectReadback(dispatch, observation, base.plusSeconds(8));
    }
    String retained = storedOutbox(operationId, effectId);
    assertThat(retained)
        .contains(tupleCodec.digest(observation), "\"retry_provenance\":\"local_no_send\"");
    var noSendReceipts = localNoSendReceipts(dispatch);
    assertThat(noSendReceipts).hasSize(1);
    for (var observedAt : List.of(base.plusSeconds(4), observation.observedAt())) {
      var closure =
          readbackFor(
              dispatch,
              dispatch.lastAttemptId(),
              EffectReadbackState.PROVEN_NO_EFFECT,
              "not-newer-owner-closure",
              observedAt);
      assertThatThrownBy(() -> journal.recordEffectReadback(dispatch, closure, base.plusSeconds(9)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("retained");
    }
    assertThat(journal.recordEffectReadback(dispatch, observation, base.plusSeconds(9)))
        .isEqualTo(journal.find(operationId).orElseThrow());
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    assertThat(localNoSendReceipts(dispatch)).isEqualTo(noSendReceipts);
    var replacement =
        journal.claimEffect(
            operationId, effectId, dispatch.lastAttemptId(), () -> base.plusSeconds(10));
    assertThat(replacement.attemptCount()).isEqualTo(dispatch.attemptCount() + 1);
    assertThat(replacement.state()).isEqualTo(EffectOutboxState.DISPATCHED);
  }

  @Test
  void ambiguousDispatchRetainsAContradictoryDefinitiveReceiptForOwnerReview() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    journal.recordAmbiguousEffectForDispatch(dispatch, "TRANSPORT_UNCERTAIN", base.plusSeconds(5));
    EffectReadback retained =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "retained-preparation",
            base.plusSeconds(6));
    // Model a restored row that contradicts its own recorded definitive receipt.
    assertThat(
            dsl.execute(
                """
                UPDATE recovery_control.initial_gameplay_release_outbox
                SET readback_identity = ?, readback_payload = CAST(? AS jsonb), readback_digest = ?
                WHERE outbox_id = ?
                """,
                retained.readbackIdentity(),
                tupleCodec.toJson(retained),
                tupleCodec.digest(retained),
                dispatch.outboxId()))
        .isEqualTo(1);
    String before = storedOutbox(operationId, effectId);
    EffectReadback partial =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "new-partial-observation",
            base.plusSeconds(7));

    assertThatThrownBy(() -> journal.recordEffectReadback(dispatch, partial, base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("nonterminal effect unexpectedly retains a definitive readback");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
    assertThat(journal.find(operationId).orElseThrow().phase()).isEqualTo(Phase.RESUME_AUTHORIZED);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void partialReadbackDoesNotEraseAnUncertainTransportOutcome(boolean uncertaintyFirst) {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    var observation =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "partial",
            base.plusSeconds(5));
    if (uncertaintyFirst) {
      assertThat(
              journal.recordAmbiguousEffectForDispatch(
                  dispatch, "TRANSPORT_UNCERTAIN", base.plusSeconds(5)))
          .isTrue();
    }
    journal.recordEffectReadback(dispatch, observation, base.plusSeconds(6));
    if (!uncertaintyFirst) {
      assertThat(
              journal.recordAmbiguousEffectForDispatch(
                  dispatch, "TRANSPORT_UNCERTAIN", base.plusSeconds(7)))
          .isTrue();
    }
    assertThatThrownBy(() ->
            journal.recordRetryableEffectForDispatch(
                dispatch, RetrySource.NOT_DISPATCHED, "CONFLICTING_NO_SEND", base.plusSeconds(8)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(localNoSendReceipts(dispatch)).isEmpty();
    assertThat(storedOutbox(dispatch.effect().operationId(), dispatch.effect().effectId()))
        .contains(tupleCodec.digest(observation), "\"send_outcome_uncertain\":true");
    assertThat(journal.findNextEffect(dispatch.effect().operationId()))
        .get()
        .extracting(OutboxEntry::state)
        .isEqualTo(EffectOutboxState.AMBIGUOUS);
  }

  @Test
  void transportUncertaintyRefusesAWriteSuppressedByDatabaseDrift() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    var observation =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "partial",
            base.plusSeconds(5));
    journal.recordEffectReadback(dispatch, observation, base.plusSeconds(6));
    String retained = storedOutbox(operationId, effectId);
    assertThat(retained).contains("\"send_outcome_uncertain\":false");

    withSuppressedOutboxUpdate(() ->
        assertThatThrownBy(() ->
                journal.recordAmbiguousEffectForDispatch(
                    dispatch, "TRANSPORT_UNCERTAIN", base.plusSeconds(7)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("effect uncertainty compare-and-set lost"));
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    assertThat(
            journal.recordAmbiguousEffectForDispatch(
                dispatch, "TRANSPORT_UNCERTAIN", base.plusSeconds(7)))
        .isTrue();
    assertThat(storedOutbox(operationId, effectId)).contains("\"send_outcome_uncertain\":true");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ambiguousTransportHoldsTheExactDispatchUntilOwnerClosure(boolean localNoSendFirst) {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    UUID attemptId = dispatch.lastAttemptId();
    if (localNoSendFirst) {
      assertThat(
              journal.recordRetryableEffectForDispatch(
                  dispatch, RetrySource.NOT_DISPATCHED, "LOCAL_NO_SEND", base.plusSeconds(5)))
          .isTrue();
    }
    var localReceipts = localNoSendReceipts(dispatch);
    assertThat(
            journal.recordAmbiguousEffectForDispatch(
                dispatch, "TRANSPORT_UNCERTAIN", base.plusSeconds(6)))
        .isTrue();
    assertThatThrownBy(() ->
            journal.recordRetryableEffectForDispatch(
                dispatch, RetrySource.NOT_DISPATCHED, "LOCAL_NO_SEND", base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(localNoSendReceipts(dispatch)).isEqualTo(localReceipts);
    OutboxEntry held = journal.findNextEffect(operationId).orElseThrow();
    assertThat(held.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);
    assertThat(held.lastAttemptId()).isEqualTo(attemptId);
    UUID replacementAttempt = UUID.randomUUID();
    if (localNoSendFirst) {
      assertThatThrownBy(() ->
              journal.claimEffect(
                  operationId, effectId, replacementAttempt, () -> base.plusSeconds(8)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class);
    } else {
      assertThat(
              journal.claimEffect(
                  operationId, effectId, replacementAttempt, () -> base.plusSeconds(8)))
          .isEqualTo(held);
    }

    EffectReadback closure =
        readbackFor(
            dispatch,
            attemptId,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "owner-closure",
            base.plusSeconds(9));
    journal.recordEffectReadback(held, closure, base.plusSeconds(10));
    assertThat(journal.findNextEffect(operationId))
        .get()
        .extracting(OutboxEntry::state)
        .isEqualTo(EffectOutboxState.RETRYABLE);
    assertThat(
            journal
                .claimEffect(operationId, effectId, replacementAttempt, () -> base.plusSeconds(11))
                .attemptCount())
        .isEqualTo(dispatch.attemptCount() + 1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ownerRejectionRetainsNewerPartialEvidenceWithoutAcceptingStaleClosure(boolean heldUntyped) {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    UUID attemptId = dispatch.lastAttemptId();
    assertThat(
            journal.recordAmbiguousEffectForDispatch(
                dispatch, "TRANSPORT_UNCERTAIN", base.plusSeconds(4)))
        .isTrue();
    assertThat(
            journal.recordRetryableEffectForDispatch(
                dispatch, RetrySource.OWNER_REJECTED, "OWNER_REJECTED", base.plusSeconds(5)))
        .isTrue();
    if (heldUntyped) {
      dsl.execute(
          "UPDATE recovery_control.initial_gameplay_release_outbox SET retry_provenance = NULL"
              + " WHERE outbox_id = ?",
          dispatch.outboxId());
    }
    OutboxEntry next = journal.findNextEffect(operationId).orElseThrow();
    assertThat(next.state())
        .isEqualTo(heldUntyped ? EffectOutboxState.AMBIGUOUS : EffectOutboxState.RETRYABLE);
    OutboxEntry reader = heldUntyped ? next : dispatch;

    EffectReadback partial =
        readbackFor(
            reader,
            attemptId,
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "partial",
            base.plusSeconds(7));
    journal.recordEffectReadback(reader, partial, base.plusSeconds(8));
    String retained = storedOutbox(operationId, effectId);
    assertThat(retained)
        .contains(
            tupleCodec.digest(partial),
            heldUntyped ? "\"retry_provenance\":null" : "\"retry_provenance\":\"owner_rejected\"",
            "\"send_outcome_uncertain\":true");
    assertThat(journal.findNextEffect(operationId)).contains(next);
    if (heldUntyped) {
      assertThatThrownBy(() ->
              journal.claimEffect(
                  operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(9)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("no exact durable provenance");
    }
    for (var observedAt : List.of(base.plusSeconds(6), partial.observedAt())) {
      var oldClosure =
          readbackFor(
              reader, attemptId, EffectReadbackState.PROVEN_NO_EFFECT, "old-closure", observedAt);
      assertThatThrownBy(
              () -> journal.recordEffectReadback(reader, oldClosure, base.plusSeconds(9)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("retained");
    }
    var contradictingSuccess =
        readbackFor(
            reader,
            attemptId,
            EffectReadbackState.PREPARED_FENCED,
            "contradicting-success",
            base.plusSeconds(8));
    assertThatThrownBy(
            () -> journal.recordEffectReadback(reader, contradictingSuccess, base.plusSeconds(9)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("after definitive no effect");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);

    var freshClosure =
        readbackFor(
            reader,
            attemptId,
            EffectReadbackState.PROVEN_NO_EFFECT,
            "fresh-closure",
            base.plusSeconds(9));
    journal.recordEffectReadback(reader, freshClosure, base.plusSeconds(10));
    assertThatThrownBy(
            () -> journal.claimEffect(operationId, effectId, attemptId, () -> base.plusSeconds(11)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("new effect attempt identity");
    assertThat(
            journal
                .claimEffect(operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(11))
                .attemptCount())
        .isEqualTo(2);
  }

  @Test
  void missingTransitionReceiptLeavesReadyOperationReconciliable() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    Scope scope = scope();
    journal.create(scope, UUID.randomUUID());
    var proof = evidence(scope);
    var ready =
        journal.bindVerifiedEvidence(
            scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), base);
    var continued = transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY);
    withSkippedInsert(
        "initial_gameplay_release_transition",
        () -> {
          assertThatThrownBy(() -> journal.transition(continued, base.plusSeconds(1)))
              .isInstanceOf(InitialGameplayReleaseConflictException.class)
              .hasMessageContaining("public transition receipt was not inserted");
          assertThat(journal.find(scope.operationId())).contains(ready);
          assertThat(journal.findTransition(continued)).isEmpty();
        });
    assertThat(journal.transition(continued, base.plusSeconds(1)).phase())
        .isEqualTo(Phase.AWAITING_RESUME);
  }

  @Test
  void missingAttemptClosureLeavesTheOriginalDispatchReconciliable() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    var before = storedOutbox(operationId, dispatch.effect().effectId());
    var closure =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PROVEN_NO_EFFECT,
            "definitive-no-effect",
            base.plusSeconds(6));
    withSkippedInsert(
        "initial_gameplay_release_attempt_closure",
        () -> {
          assertThatThrownBy(
                  () -> journal.recordEffectReadback(dispatch, closure, closure.observedAt()))
              .isInstanceOf(InitialGameplayReleaseConflictException.class)
              .hasMessageContaining("effect attempt closure was not inserted");
          assertThat(storedOutbox(operationId, dispatch.effect().effectId())).isEqualTo(before);
        });
    journal.recordEffectReadback(dispatch, closure, closure.observedAt());
    assertThat(journal.findNextEffect(operationId))
        .get()
        .extracting(OutboxEntry::state)
        .isEqualTo(EffectOutboxState.RETRYABLE);
    assertThat(storedClosure(dispatch)).contains(tupleCodec.digest(closure));
  }

  @Test
  void missingNextEffectRollsBackResumeAndPreparationReadback() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    Scope scope = scope();
    UUID operationId = scope.operationId();
    journal.create(scope, UUID.randomUUID());
    var proof = evidence(scope);
    var ready =
        journal.bindVerifiedEvidence(
            scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), base);
    journal.transition(
        transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY), base.plusSeconds(1));
    var awaiting = journal.find(operationId).orElseThrow();
    var resume = transitionTuple(awaiting, TransitionVerb.RESUME);
    withSkippedInsert(
        "initial_gameplay_release_outbox",
        () -> {
          assertThatThrownBy(() -> journal.transition(resume, base.plusSeconds(2)))
              .isInstanceOf(InitialGameplayReleaseConflictException.class)
              .hasMessageContaining("next effect was not enqueued");
          assertThat(journal.find(operationId)).contains(awaiting);
          assertThat(journal.findTransition(resume)).isEmpty();
          assertThat(journal.findNextEffect(operationId)).isEmpty();
        });
    journal.transition(resume, base.plusSeconds(2));
    var pending = journal.findNextEffect(operationId).orElseThrow();
    var dispatch =
        journal.claimEffect(
            operationId, pending.effect().effectId(), UUID.randomUUID(), () -> base.plusSeconds(3));
    var authorized = journal.find(operationId).orElseThrow();
    var before = storedOutbox(operationId, pending.effect().effectId());
    var prepared =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "prepared-for-handoff",
            base.plusSeconds(4));
    withSkippedInsert(
        "initial_gameplay_release_outbox",
        () -> {
          assertThatThrownBy(
                  () -> journal.recordEffectReadback(dispatch, prepared, prepared.observedAt()))
              .isInstanceOf(InitialGameplayReleaseConflictException.class)
              .hasMessageContaining("next effect was not enqueued");
          assertThat(journal.find(operationId)).contains(authorized);
          assertThat(journal.findEffect(operationId, EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
              .isEmpty();
          assertThat(storedOutbox(operationId, dispatch.effect().effectId())).isEqualTo(before);
        });
    assertThat(journal.recordEffectReadback(dispatch, prepared, prepared.observedAt()).phase())
        .isEqualTo(Phase.RELEASING);
    assertThat(journal.findEffect(operationId, EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .isPresent();
  }

  @Test
  void reconciliationRefusesMissingPreparationAfterCommittedResume() {
    var pending = authorizedPending(journal, Instant.parse("2026-09-01T00:00:00Z"));
    UUID operationId = pending.effect().operationId();
    assertThat(journal.find(operationId).orElseThrow().phase()).isEqualTo(Phase.RESUME_AUTHORIZED);
    assertThat(journal.findNextEffect(operationId)).contains(pending);
    assertThat(
            dsl.execute(
                "DELETE FROM recovery_control.initial_gameplay_release_outbox WHERE outbox_id=?",
                pending.outboxId()))
        .isEqualTo(1);

    assertThatThrownBy(() -> journal.findNextEffect(operationId))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("authorized resume has no retained preparation effect");
    assertThat(journal.find(operationId).orElseThrow().phase()).isEqualTo(Phase.RESUME_AUTHORIZED);
    assertThat(journal.findEffect(operationId, EffectKind.PREPARE_PLAYABLE_STATE_FENCED)).isEmpty();
  }

  @Test
  void reconciliationRefusesMissingHandoffAfterPreparationReceipt() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    var dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    var prepared =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "prepared-for-handoff",
            base.plusSeconds(5));
    assertThat(journal.recordEffectReadback(dispatch, prepared, prepared.observedAt()).phase())
        .isEqualTo(Phase.RELEASING);
    var handoff = journal.findNextEffect(operationId).orElseThrow();
    assertThat(
            dsl.execute(
                "DELETE FROM recovery_control.initial_gameplay_release_outbox WHERE outbox_id=?",
                handoff.outboxId()))
        .isEqualTo(1);

    assertThatThrownBy(() -> journal.findNextEffect(operationId))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("releasing operation has no retained handoff effect");
    assertThatThrownBy(() -> journal.findHeldPreparationForRefresh(operationId))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("releasing operation has no retained handoff effect");
    assertThat(journal.findEffect(operationId, EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .isEmpty();
  }

  @Test
  void reconciliationKeepsReleasingPreparationHeldWhenAcknowledgementIsMissing() {
    var base = Instant.parse("2026-09-01T00:00:00Z");
    var dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    var acknowledgement =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "initial-acknowledgement",
            base.plusSeconds(5));
    assertThat(
            journal
                .recordEffectReadback(dispatch, acknowledgement, acknowledgement.observedAt())
                .phase())
        .isEqualTo(Phase.RELEASING);
    var incomplete =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "partial-without-acknowledgement",
            base.plusSeconds(6));
    assertThat(
            dsl.execute(
                """
                UPDATE recovery_control.initial_gameplay_release_outbox
                SET state='ambiguous', last_outcome_code='UNKNOWN_OR_PARTIAL', acknowledged_at=NULL,
                    readback_identity=?, readback_payload=CAST(? AS jsonb), readback_digest=?
                WHERE outbox_id=?
                """,
                incomplete.readbackIdentity(),
                tupleCodec.toJson(incomplete),
                tupleCodec.digest(incomplete),
                dispatch.outboxId()))
        .isEqualTo(1);
    var corrupt = storedOutbox(operationId, dispatch.effect().effectId());
    var delayed =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            acknowledgement.readbackIdentity(),
            base.plusSeconds(7));

    assertThatThrownBy(() -> journal.recordEffectReadback(dispatch, delayed, delayed.observedAt()))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("releasing preparation has no retained acknowledgement");
    assertThat(storedOutbox(operationId, dispatch.effect().effectId())).isEqualTo(corrupt);
    assertThat(journal.find(operationId).orElseThrow().phase()).isEqualTo(Phase.RELEASING);
  }

  @Test
  void claimStorageFailureRollsBackWithoutDatabaseCause() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    var pending = authorizedPending(journal, base);
    UUID operationId = pending.effect().operationId();
    UUID effectId = pending.effect().effectId();
    UUID attemptId = UUID.randomUUID();
    String retained = storedOutbox(operationId, effectId);
    String outbox = "recovery_control.initial_gameplay_release_outbox";
    dsl.execute(
        "ALTER TABLE "
            + outbox
            + " ADD CONSTRAINT fixture_hold_dispatch CHECK (state <> 'dispatched') NOT VALID");
    try {
      assertThatThrownBy(() ->
              journal.claimEffect(operationId, effectId, attemptId, () -> base.plusSeconds(4)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Unable to record recovery journal effect dispatch claim")
          .hasNoCause();
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    } finally {
      dsl.execute("ALTER TABLE " + outbox + " DROP CONSTRAINT fixture_hold_dispatch");
    }
    assertThat(
            journal
                .claimEffect(operationId, effectId, attemptId, () -> base.plusSeconds(4))
                .lastAttemptId())
        .isEqualTo(attemptId);
  }

  @Test
  void rejectionRequiresDurableReceiptBeforeRecordingRetry() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    var dispatch = authorizedDispatch(base);
    String before = storedOutbox(dispatch.effect().operationId(), dispatch.effect().effectId());
    withSkippedInsert(
        "initial_gameplay_release_attempt_rejection",
        () -> {
          assertThatThrownBy(() ->
                  journal.recordRetryableEffectForDispatch(
                      dispatch,
                      RetrySource.OWNER_REJECTED,
                      "OWNER_DECLINED_WITHOUT_EFFECT",
                      base.plusSeconds(5)))
              .isInstanceOf(InitialGameplayReleaseConflictException.class)
              .hasMessageContaining("owner rejection receipt does not match the exact dispatch");
          assertThat(storedOutbox(dispatch.effect().operationId(), dispatch.effect().effectId()))
              .isEqualTo(before);
        });
    assertThat(
            journal.recordRetryableEffectForDispatch(
                dispatch,
                RetrySource.OWNER_REJECTED,
                "OWNER_DECLINED_WITHOUT_EFFECT",
                base.plusSeconds(5)))
        .isTrue();
    assertThat(journal.findNextEffect(dispatch.effect().operationId()))
        .get()
        .extracting(OutboxEntry::state)
        .isEqualTo(EffectOutboxState.RETRYABLE);
  }

  @Test
  void retryablePartialObservationMustBeStoredBeforeItCanBeAcknowledged() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    var dispatch = authorizedDispatch(base);
    journal.recordRetryableEffectForDispatch(
        dispatch, RetrySource.OWNER_REJECTED, "OWNER_DECLINED_WITHOUT_EFFECT", base.plusSeconds(5));
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    var observation =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "partial-owner-status",
            base.plusSeconds(7));
    String before = storedOutbox(operationId, effectId);
    withSkippedMutation(
        "initial_gameplay_release_outbox",
        "UPDATE",
        () -> {
          assertThatThrownBy(() ->
                  journal.recordEffectReadback(dispatch, observation, observation.observedAt()))
              .isInstanceOf(InitialGameplayReleaseConflictException.class)
              .hasMessageContaining("partial effect readback was not retained");
          assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
        });
    var recorded = journal.recordEffectReadback(dispatch, observation, observation.observedAt());
    assertThat(storedOutbox(operationId, effectId)).contains(tupleCodec.digest(observation));
    withSkippedMutation(
        "initial_gameplay_release_outbox",
        "UPDATE",
        () ->
            assertThat(
                    journal.recordEffectReadback(dispatch, observation, observation.observedAt()))
                .isEqualTo(recorded));
  }

  private static void withSkippedInsert(String table, Runnable proof) {
    withSkippedMutation(table, "INSERT", proof);
  }

  private static void withSkippedMutation(String table, String event, Runnable proof) {
    String relation = "recovery_control." + table;
    dsl.execute(
        """
        CREATE FUNCTION recovery_control.fixture_skip_mutation() RETURNS trigger
        LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$;
        """);
    try {
      dsl.execute(
          "CREATE TRIGGER fixture_skip_mutation BEFORE "
              + event
              + " ON "
              + relation
              + " FOR EACH ROW EXECUTE FUNCTION recovery_control.fixture_skip_mutation()");
      proof.run();
    } finally {
      dsl.execute("DROP TRIGGER IF EXISTS fixture_skip_mutation ON " + relation);
      dsl.execute("DROP FUNCTION recovery_control.fixture_skip_mutation()");
    }
  }

  @Test
  void preparationIdentityRollsBackWhenObservationWriteIsSkipped() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry preparation = authorizedDispatch(base);
    UUID attemptId = preparation.lastAttemptId();
    UUID operationId = preparation.effect().operationId();
    UUID effectId = preparation.effect().effectId();
    var prepared =
        readbackFor(
            preparation,
            attemptId,
            EffectReadbackState.PREPARED_FENCED,
            "initial-preparation",
            base.plusSeconds(5));
    journal.recordEffectReadback(preparation, prepared, prepared.observedAt());
    var partial =
        readbackFor(
            preparation,
            attemptId,
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "partial-preparation",
            base.plusSeconds(6));
    journal.recordEffectReadback(preparation, partial, partial.observedAt());
    var fresh =
        readbackFor(
            preparation,
            attemptId,
            EffectReadbackState.PREPARED_FENCED,
            "fresh-preparation",
            base.plusSeconds(7));
    String retained = storedOutbox(operationId, effectId);
    dsl.execute(
        """
        CREATE FUNCTION recovery_control.fixture_skip_preparation_observation() RETURNS trigger
        LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$
        """);
    try {
      dsl.execute(
          """
          CREATE TRIGGER fixture_skip_preparation_observation BEFORE UPDATE
          ON recovery_control.initial_gameplay_release_outbox FOR EACH ROW
          WHEN (NEW.preparation_observation_payload IS DISTINCT FROM OLD.preparation_observation_payload)
          EXECUTE FUNCTION recovery_control.fixture_skip_preparation_observation()
          """);
      assertThatThrownBy(() -> journal.recordEffectReadback(preparation, fresh, fresh.observedAt()))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("preparation observation compare-and-set lost");
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
      assertThat(
              dsl.fetchCount(
                  DSL.table("recovery_control.initial_gameplay_release_preparation_receipt"),
                  DSL.field("outbox_id", UUID.class).eq(preparation.outboxId()),
                  DSL.field("identity_digest", String.class)
                      .eq(tupleCodec.digest(fresh.readbackIdentity()))))
          .isZero();
    } finally {
      dsl.execute(
          "DROP TRIGGER IF EXISTS fixture_skip_preparation_observation"
              + " ON recovery_control.initial_gameplay_release_outbox");
      dsl.execute("DROP FUNCTION recovery_control.fixture_skip_preparation_observation()");
    }
    journal.recordEffectReadback(preparation, fresh, fresh.observedAt());
    assertThat(storedOutbox(operationId, effectId)).contains(tupleCodec.digest(fresh));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void newerPartialPreparationHoldsUnclaimedHandoffUntilFreshOwnerProof(
      boolean preparationArrivesFirst) throws Exception {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    EffectKind handoffKind = EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF;
    EffectReadback prepared =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "prepared",
            base.plusSeconds(5));
    EffectReadback partial =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "partial",
            base.plusSeconds(6));
    Optional<OutboxEntry> originalHandoff = Optional.empty();
    if (preparationArrivesFirst) {
      assertThat(journal.recordEffectReadback(dispatch, prepared, base.plusSeconds(8)).phase())
          .isEqualTo(Phase.RELEASING);
      originalHandoff = journal.findEffect(operationId, handoffKind);
      assertThat(originalHandoff).isPresent();
      var retimedPreparation =
          readbackFor(
              dispatch,
              dispatch.lastAttemptId(),
              EffectReadbackState.PREPARED_FENCED,
              prepared.readbackIdentity(),
              partial.observedAt().plusNanos(1));
      String retained = storedOutbox(operationId, effectId);
      assertThatThrownBy(
              () -> journal.recordEffectReadback(dispatch, retimedPreparation, base.plusSeconds(8)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("preparation readback identity already has an observation");
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    }
    journal.recordEffectReadback(dispatch, partial, base.plusSeconds(8));

    OutboxEntry held = journal.findNextEffect(operationId).orElseThrow();
    assertThat(held.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);
    assertThat(held.effect()).isEqualTo(dispatch.effect());
    assertThat(held.outboxId()).isEqualTo(dispatch.outboxId());
    assertThat(held.lastAttemptId()).isEqualTo(dispatch.lastAttemptId());
    assertThat(held.attemptCount()).isEqualTo(dispatch.attemptCount());
    assertThat(storedOutbox(operationId, effectId)).contains(tupleCodec.digest(partial));
    assertThat(journal.findEffect(operationId, handoffKind)).isEqualTo(originalHandoff);
    if (originalHandoff.isPresent()) {
      UUID handoffId = originalHandoff.orElseThrow().effect().effectId();
      assertThatThrownBy(() ->
              journal.claimEffect(
                  operationId, handoffId, UUID.randomUUID(), () -> base.plusSeconds(9)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("preparation acknowledgement is unavailable");
      EffectReadback contradictoryClosure =
          readbackFor(
              held,
              held.lastAttemptId(),
              EffectReadbackState.PROVEN_NO_EFFECT,
              "contradictory-closure",
              base.plusSeconds(8));
      assertThatThrownBy(
              () -> journal.recordEffectReadback(held, contradictoryClosure, base.plusSeconds(9)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class);
      String retained = storedOutbox(operationId, effectId);
      assertThat(
              journal.recordRetryableEffectForDispatch(
                  dispatch, RetrySource.OWNER_REJECTED, "LATE_REJECTION", base.plusSeconds(9)))
          .isTrue();
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    }

    String retainedPreparation = storedOutbox(operationId, effectId);
    if (preparationArrivesFirst) {
      assertThat(journal.recordEffectReadback(held, prepared, base.plusSeconds(9)).phase())
          .isEqualTo(Phase.RELEASING);
      EffectReadback retimedReceipt =
          readbackFor(
              held,
              held.lastAttemptId(),
              EffectReadbackState.PREPARED_FENCED,
              prepared.readbackIdentity(),
              partial.observedAt().plusNanos(1));
      assertThatThrownBy(
              () -> journal.recordEffectReadback(held, retimedReceipt, base.plusSeconds(9)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("preparation readback identity already has an observation");
    }
    for (Instant observedAt : List.of(prepared.observedAt(), partial.observedAt())) {
      EffectReadback delayed =
          readbackFor(
              held,
              held.lastAttemptId(),
              EffectReadbackState.PREPARED_FENCED,
              "delayed-preparation",
              observedAt);
      assertThatThrownBy(() -> journal.recordEffectReadback(held, delayed, base.plusSeconds(9)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class);
    }
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retainedPreparation);
    assertThat(journal.findNextEffect(operationId)).contains(held);
    assertThat(journal.findEffect(operationId, handoffKind)).isEqualTo(originalHandoff);

    EffectReadback fresh =
        readbackFor(
            held,
            held.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "fresh-preparation",
            base.plusSeconds(9));
    assertThat(journal.recordEffectReadback(held, fresh, base.plusSeconds(10)).phase())
        .isEqualTo(Phase.RELEASING);
    EffectReadback laterPreparation =
        readbackFor(
            held,
            held.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "later-preparation",
            fresh.observedAt().plusNanos(1));
    journal.recordEffectReadback(held, laterPreparation, base.plusSeconds(10));
    String retainedLatestPreparation = storedOutbox(operationId, effectId);
    assertThat(journal.recordEffectReadback(held, laterPreparation, base.plusSeconds(10)).phase())
        .isEqualTo(Phase.RELEASING);
    EffectReadback secondPartial =
        readbackFor(
            held,
            held.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "second-partial",
            base.plusSeconds(10));
    EffectReadback retimedLatest =
        readbackFor(
            held,
            held.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            laterPreparation.readbackIdentity(),
            secondPartial.observedAt().plusNanos(1));
    assertThat(fresh.observedAt()).isBefore(laterPreparation.observedAt());
    EffectReadback retimedOlderReceipt =
        readbackFor(
            held,
            held.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            fresh.readbackIdentity(),
            secondPartial.observedAt().plusNanos(2));
    var retimedReceipts = List.of(retimedLatest, retimedOlderReceipt);
    for (var retimed : retimedReceipts) {
      assertThatThrownBy(() -> journal.recordEffectReadback(held, retimed, base.plusSeconds(11)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("preparation readback identity already has an observation");
      assertThat(storedOutbox(operationId, effectId)).isEqualTo(retainedLatestPreparation);
    }

    UUID unclaimedHandoffId =
        journal.findEffect(operationId, handoffKind).orElseThrow().effect().effectId();
    journal.recordEffectReadback(held, secondPartial, base.plusSeconds(11));
    OutboxEntry secondHeld = journal.findNextEffect(operationId).orElseThrow();
    assertThat(secondHeld.outboxId()).isEqualTo(held.outboxId());
    assertThat(secondHeld.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);
    String retainedSecondHold = storedOutbox(operationId, effectId);
    try (var reopenedConnection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      var reopened =
          new JooqInitialGameplayReleaseJournal(
              DSL.using(reopenedConnection, SQLDialect.POSTGRES), tupleCodec);
      assertThat(reopened.findNextEffect(operationId)).contains(secondHeld);
      for (var retimed : retimedReceipts) {
        assertThatThrownBy(
                () -> reopened.recordEffectReadback(secondHeld, retimed, base.plusSeconds(11)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("preparation readback identity already has an observation");
        assertThat(storedOutbox(operationId, effectId)).isEqualTo(retainedSecondHold);
        assertThat(reopened.findNextEffect(operationId)).contains(secondHeld);
      }
      assertThatThrownBy(() ->
              reopened.claimEffect(
                  operationId, unclaimedHandoffId, UUID.randomUUID(), () -> base.plusSeconds(11)))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("preparation acknowledgement is unavailable");
      EffectReadback afterSecondHold =
          readbackFor(
              held,
              held.lastAttemptId(),
              EffectReadbackState.PREPARED_FENCED,
              "fresh-after-second-hold",
              base.plusSeconds(11));
      reopened.recordEffectReadback(secondHeld, afterSecondHold, base.plusSeconds(11));
    }
    OutboxEntry handoff = journal.findNextEffect(operationId).orElseThrow();
    assertThat(handoff.effect().kind()).isEqualTo(handoffKind);
    assertThat(handoff.state()).isEqualTo(EffectOutboxState.PENDING);
    if (preparationArrivesFirst) {
      assertThat(originalHandoff).contains(handoff);
    }
    UUID attemptId = UUID.randomUUID();
    OutboxEntry claimed =
        journal.claimEffect(
            operationId, handoff.effect().effectId(), attemptId, () -> base.plusSeconds(11));
    assertThat(claimed.state()).isEqualTo(EffectOutboxState.DISPATCHED);
    assertThat(claimed.lastAttemptId()).isEqualTo(attemptId);
    assertThat(claimed.attemptCount()).isEqualTo(1);

    EffectReadback afterClaim =
        readbackFor(
            held,
            held.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "after-claim",
            base.plusSeconds(12));
    UUID handoffId = handoff.effect().effectId();
    String claimedRow = storedOutbox(operationId, handoffId);
    assertThat(journal.recordEffectReadback(held, afterClaim, base.plusSeconds(13)).phase())
        .isEqualTo(Phase.RELEASING);
    assertThat(storedOutbox(operationId, effectId)).contains(tupleCodec.digest(afterClaim));
    assertThat(storedOutbox(operationId, handoffId)).isEqualTo(claimedRow);
    assertThat(journal.findNextEffect(operationId)).contains(claimed);

    assertThat(
            journal.recordAmbiguousEffectForDispatch(
                claimed, "HANDOFF_TRANSPORT_UNCERTAIN", base.plusSeconds(13)))
        .isTrue();
    OutboxEntry activeHandoff = journal.findNextEffect(operationId).orElseThrow();
    assertThat(activeHandoff.outboxId()).isEqualTo(claimed.outboxId());
    assertThat(activeHandoff.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);

    EffectReadback applied =
        readbackFor(
            claimed,
            attemptId,
            EffectReadbackState.OWNER_HANDOFF_APPLIED,
            "handoff-applied",
            base.plusSeconds(14));
    EffectReadback latestPreparation =
        readbackFor(
            held,
            held.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "fresh-after-handoff-claim",
            base.plusSeconds(13));
    journal.recordEffectReadback(held, latestPreparation, base.plusSeconds(13));
    var finalized = journal.recordEffectReadback(activeHandoff, applied, base.plusSeconds(14));
    assertThat(finalized.phase()).isEqualTo(Phase.FINALIZED);
    String finalizedPreparationRow = storedOutbox(operationId, effectId);
    assertThat(journal.recordEffectReadback(held, latestPreparation, base.plusSeconds(15)))
        .isEqualTo(finalized);
    if (preparationArrivesFirst) {
      assertThat(journal.recordEffectReadback(held, prepared, base.plusSeconds(15)))
          .isEqualTo(finalized);
    }
    EffectReadback retimedLatestPreparation =
        readbackFor(
            held,
            held.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            latestPreparation.readbackIdentity(),
            base.plusSeconds(15));
    assertThatThrownBy(() ->
            journal.recordEffectReadback(held, retimedLatestPreparation, base.plusSeconds(15)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("preparation readback identity already has an observation");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(finalizedPreparationRow);
    assertThat(journal.findNextEffect(operationId)).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"false,true", "true,true", "true,false"})
  void stalePreparationHoldsExactHandoffRetryUntilFreshProof(
      boolean ownerRejected, boolean observePartial) {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry preparation = authorizedDispatch(base);
    UUID operationId = preparation.effect().operationId();
    EffectKind handoffKind = EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF;
    journal.recordEffectReadback(
        preparation,
        readbackFor(
            preparation,
            preparation.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "prepared",
            base.plusSeconds(5)),
        base.plusSeconds(5));
    UUID handoffId = journal.findEffect(operationId, handoffKind).orElseThrow().effect().effectId();
    OutboxEntry claimed =
        journal.claimEffect(operationId, handoffId, UUID.randomUUID(), () -> base.plusSeconds(6));
    if (observePartial) {
      EffectReadback partial =
          readbackFor(
              preparation,
              preparation.lastAttemptId(),
              EffectReadbackState.UNKNOWN_OR_PARTIAL,
              "newer-partial",
              base.plusSeconds(8));
      journal.recordEffectReadback(preparation, partial, base.plusSeconds(8));
    }
    assertThat(journal.findNextEffect(operationId)).contains(claimed);
    assertThat(
            journal.recordRetryableEffectForDispatch(
                claimed,
                ownerRejected ? RetrySource.OWNER_REJECTED : RetrySource.NOT_DISPATCHED,
                "DEFINITIVE_NO_EFFECT",
                base.plusSeconds(9)))
        .isTrue();
    String retry = storedOutbox(operationId, handoffId);
    OutboxEntry held = journal.findNextEffect(operationId).orElseThrow();
    assertThat(held.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);
    assertThat(held.outboxId()).isEqualTo(preparation.outboxId());
    UUID retryAttemptId = UUID.randomUUID();
    assertThatThrownBy(() ->
            journal.claimEffect(operationId, handoffId, retryAttemptId, () -> base.plusSeconds(9)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("preparation acknowledgement is unavailable");
    assertThat(storedOutbox(operationId, handoffId)).isEqualTo(retry);

    journal.recordEffectReadback(
        held,
        readbackFor(
            held,
            held.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "fresh-preparation",
            base.plusSeconds(10)),
        base.plusSeconds(10));
    assertThat(storedOutbox(operationId, handoffId)).isEqualTo(retry);
    OutboxEntry retried =
        journal.claimEffect(operationId, handoffId, retryAttemptId, () -> base.plusSeconds(11));
    assertThat(retried.state()).isEqualTo(EffectOutboxState.DISPATCHED);
    assertThat(retried.lastAttemptId()).isEqualTo(retryAttemptId);
    assertThat(retried.attemptCount()).isEqualTo(claimed.attemptCount() + 1);
  }

  @Test
  void pendingClaimAndStartupMigrationPreserveContradictoryAttemptHistory() throws Exception {
    String database = "recovery_pending_attempt_history";
    String sourceUrl = POSTGRES.getJdbcUrl();
    String journalUrl = sourceUrl.substring(0, sourceUrl.lastIndexOf('/') + 1) + database;
    dsl.execute("CREATE DATABASE " + database);
    try {
      journalFlyway(journalUrl, "1").migrate();
      try (var fixtureSession =
          DriverManager.getConnection(journalUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var fixture = DSL.using(fixtureSession, SQLDialect.POSTGRES);
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        seedImmutableV1Journal(fixture, false, base);
        journalFlyway(journalUrl, "9").migrate();
        var receipt = UUID.randomUUID();
        fixture.execute(
            """
            INSERT INTO recovery_control.initial_gameplay_release_attempt_local_no_send
                (outbox_id, attempt_id, attempt_ordinal, dispatched_at, recorded_at)
            VALUES (?, ?, 1, CAST(? AS timestamptz), CAST(? AS timestamptz))
            """,
            V1_OUTBOX_ID,
            receipt,
            base.plusSeconds(3).atOffset(ZoneOffset.UTC),
            base.plusSeconds(4).atOffset(ZoneOffset.UTC));
        String retained = retainedOutboxRow(fixture);
        var restoredJournal = new JooqInitialGameplayReleaseJournal(fixture, tupleCodec);
        assertThatThrownBy(() ->
                restoredJournal.claimEffect(
                    V1_OPERATION_ID, V1_EFFECT_ID, UUID.randomUUID(), () -> base.plusSeconds(5)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("pending effect has retained attempt evidence");
        var upgrade = journalFlyway(journalUrl, null);
        assertThatThrownBy(upgrade::migrate)
            .hasStackTraceContaining("Pending Recovery effect has retained attempt evidence");
        assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("9");
        assertThat(retainedOutboxRow(fixture)).isEqualTo(retained);
        assertThat(
                fixture
                    .fetchSingle(
                        "SELECT attempt_id FROM recovery_control.initial_gameplay_release_attempt_local_no_send WHERE outbox_id = ?",
                        V1_OUTBOX_ID)
                    .get(0, UUID.class))
            .isEqualTo(receipt);
      }
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @Test
  void heldUntypedRetryRequiresDefinitiveEvidenceNewerThanTheRetainedPartialReadback() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatch = authorizedDispatch(base);
    UUID operationId = dispatch.effect().operationId();
    UUID effectId = dispatch.effect().effectId();
    assertThat(
            dsl.execute(
                "UPDATE recovery_control.initial_gameplay_release_outbox SET state = 'retryable'"
                    + " WHERE outbox_id = ?",
                dispatch.outboxId()))
        .isEqualTo(1);
    OutboxEntry held = journal.findNextEffect(operationId).orElseThrow();
    assertThat(held.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);
    assertThatThrownBy(() ->
            journal.claimEffect(
                operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(5)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    EffectReadback delayed =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "delayed-qualification",
            base.plusSeconds(5));
    EffectReadback partial =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "newer-partial",
            base.plusSeconds(6));
    assertThat(journal.recordEffectReadback(held, partial, base.plusSeconds(6)).phase())
        .isEqualTo(Phase.RESUME_AUTHORIZED);
    assertThat(storedOutbox(operationId, effectId)).contains(tupleCodec.digest(partial));
    assertThatThrownBy(() -> journal.recordEffectReadback(held, delayed, base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(journal.findNextEffect(operationId))
        .get()
        .extracting(OutboxEntry::state)
        .isEqualTo(EffectOutboxState.AMBIGUOUS);
    assertThat(journal.findEffect(operationId, EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF))
        .isEmpty();
    EffectReadback fresh =
        readbackFor(
            dispatch,
            dispatch.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "fresh-qualification",
            base.plusSeconds(7));
    assertThat(journal.recordEffectReadback(held, fresh, base.plusSeconds(8)).phase())
        .isEqualTo(Phase.RELEASING);
    assertThat(journal.findEffect(operationId, dispatch.effect().kind()))
        .get()
        .extracting(OutboxEntry::state)
        .isEqualTo(EffectOutboxState.ACKNOWLEDGED);
  }

  @Test
  void heldUntypedRetryCanRecoverAnExactRetainedClosure() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatched = authorizedDispatch(base);
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    var closure =
        readbackFor(
            dispatched,
            dispatched.lastAttemptId(),
            EffectReadbackState.PROVEN_NO_EFFECT,
            "closure",
            base.plusSeconds(5));
    journal.recordEffectReadback(dispatched, closure, base.plusSeconds(6));
    assertThat(
            dsl.execute(
                "UPDATE recovery_control.initial_gameplay_release_outbox SET retry_provenance = NULL"
                    + " WHERE outbox_id = ?",
                dispatched.outboxId()))
        .isEqualTo(1);
    var reopened = new JooqInitialGameplayReleaseJournal(dsl, tupleCodec);
    assertThatThrownBy(() ->
            reopened.claimEffect(
                operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("no exact durable provenance");
    OutboxEntry held = reopened.findNextEffect(operationId).orElseThrow();
    assertThat(held.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);
    assertThat(held.lastAttemptId()).isEqualTo(dispatched.lastAttemptId());
    String retained = storedOutbox(operationId, effectId);
    withSuppressedOutboxUpdate(() ->
        assertThatThrownBy(() -> reopened.recordEffectReadback(held, closure, base.plusSeconds(8)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("owner closure provenance compare-and-set lost"));
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(retained);
    assertThat(reopened.findNextEffect(operationId)).contains(held);
    reopened.recordEffectReadback(held, closure, base.plusSeconds(8));
    assertThat(storedOutbox(operationId, effectId))
        .contains("\"retry_provenance\":\"owner_closure\"");
    OutboxEntry replacement =
        reopened.claimEffect(operationId, effectId, UUID.randomUUID(), () -> base.plusSeconds(9));
    assertThat(replacement.state()).isEqualTo(EffectOutboxState.DISPATCHED);
    assertThat(replacement.attemptCount()).isEqualTo(dispatched.attemptCount() + 1);
  }

  private void withSuppressedOutboxUpdate(Runnable assertion) {
    dsl.execute(
        """
        CREATE FUNCTION recovery_control.fixture_skip_effect_outcome_update() RETURNS trigger
        LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$
        """);
    try {
      dsl.execute(
          """
          CREATE TRIGGER fixture_skip_effect_outcome_update BEFORE UPDATE
          ON recovery_control.initial_gameplay_release_outbox FOR EACH ROW
          EXECUTE FUNCTION recovery_control.fixture_skip_effect_outcome_update()
          """);
      assertion.run();
    } finally {
      try {
        dsl.execute(
            "DROP TRIGGER IF EXISTS fixture_skip_effect_outcome_update"
                + " ON recovery_control.initial_gameplay_release_outbox");
      } finally {
        dsl.execute("DROP FUNCTION recovery_control.fixture_skip_effect_outcome_update()");
      }
    }
  }

  @Test
  void exactOwnerRejectionPersistsDifferentAuthorityAndRequiresAFreshAttemptId() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry dispatched = authorizedDispatch(base);
    UUID operationId = dispatched.effect().operationId();
    UUID effectId = dispatched.effect().effectId();
    UUID attemptId = dispatched.lastAttemptId();
    assertThat(
            journal.recordRetryableEffectForDispatch(
                dispatched, RetrySource.OWNER_REJECTED, "OWNER_REJECTED", base.plusSeconds(5)))
        .isTrue();
    assertThat(storedOutbox(operationId, effectId))
        .contains("\"retry_provenance\":\"owner_rejected\"");
    assertThat(localNoSendReceipts(dispatched)).isEmpty();
    assertThat(
            dsl.fetchSingle(
                    """
                    SELECT attempt_ordinal
                    FROM recovery_control.initial_gameplay_release_attempt_rejection
                    WHERE outbox_id = ? AND attempt_id = ?
                    """,
                    dispatched.outboxId(),
                    attemptId)
                .get(0, Integer.class))
        .isEqualTo(dispatched.attemptCount());

    var reopened = new JooqInitialGameplayReleaseJournal(dsl, tupleCodec);
    String rejected = storedOutbox(operationId, effectId);
    assertThat(
            reopened.recordRetryableEffectForDispatch(
                dispatched, RetrySource.OWNER_REJECTED, "OWNER_REJECTED", base.plusSeconds(6)))
        .isTrue();
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(rejected);
    assertThatThrownBy(
            () -> reopened.claimEffect(operationId, effectId, attemptId, () -> base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("a retry must use a new effect attempt identity");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(rejected);
    UUID freshAttempt = UUID.randomUUID();
    OutboxEntry replacement =
        reopened.claimEffect(operationId, effectId, freshAttempt, () -> base.plusSeconds(8));
    assertThat(replacement.state()).isEqualTo(EffectOutboxState.DISPATCHED);
    assertThat(replacement.lastAttemptId()).isEqualTo(freshAttempt);
    assertThat(replacement.attemptCount()).isEqualTo(dispatched.attemptCount() + 1);
    assertThat(localNoSendReceipts(dispatched)).isEmpty();
  }

  @Test
  void delayedTransportOnlyGenerationCannotMutateSameIdRetry() {
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    OutboxEntry original = authorizedDispatch(base);
    UUID operationId = original.effect().operationId();
    UUID effectId = original.effect().effectId();
    UUID attemptId = original.lastAttemptId();
    assertThat(
            journal.recordRetryableEffectForDispatch(
                original, RetrySource.NOT_DISPATCHED, "TRANSPORT_REFUSED", base.plusSeconds(5)))
        .isTrue();
    OutboxEntry retry =
        journal.claimEffect(operationId, effectId, attemptId, () -> base.plusSeconds(6));
    assertThat(retry.attemptCount()).isEqualTo(original.attemptCount() + 1);
    String before = storedOutbox(operationId, effectId);
    assertThatThrownBy(() ->
            journal.recordRetryableEffectForDispatch(
                original,
                RetrySource.NOT_DISPATCHED,
                "STALE_TRANSPORT_REFUSED",
                base.plusSeconds(7)))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("effect dispatch generation changed");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
    assertThat(
            journal.recordRetryableEffectForDispatch(
                retry,
                RetrySource.NOT_DISPATCHED,
                "CURRENT_TRANSPORT_REFUSED",
                base.plusSeconds(8)))
        .isTrue();
  }

  @Test
  void delayedReadbackCannotMutateSameIdRetryWithinTheSameStoredMicrosecond() {
    Instant base = Instant.parse("2026-09-01T00:00:00.000000100Z");
    OutboxEntry original = authorizedDispatch(base);
    UUID operationId = original.effect().operationId();
    UUID effectId = original.effect().effectId();
    UUID attemptId = original.lastAttemptId();
    Instant firstDispatch = base.plusSeconds(4);
    String dispatchTimeQuery =
        "SELECT last_dispatched_at FROM recovery_control.initial_gameplay_release_outbox"
            + " WHERE outbox_id = ?";
    var firstStoredDispatch = dsl.fetchSingle(dispatchTimeQuery, original.outboxId()).get(0);
    var oldReadback =
        readbackFor(
            original,
            attemptId,
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "original-observation",
            firstDispatch.plusNanos(100));
    assertThat(
            journal.recordRetryableEffectForDispatch(
                original,
                RetrySource.NOT_DISPATCHED,
                "TRANSPORT_REFUSED",
                firstDispatch.plusNanos(200)))
        .isTrue();
    OutboxEntry replacement =
        journal.claimEffect(operationId, effectId, attemptId, () -> firstDispatch.plusNanos(300));
    assertThat(replacement.lastAttemptId()).isEqualTo(attemptId);
    assertThat(replacement.attemptCount()).isEqualTo(original.attemptCount() + 1);
    assertThat(dsl.fetchSingle(dispatchTimeQuery, replacement.outboxId()).get(0))
        .isEqualTo(firstStoredDispatch);

    Instant receivedAt = firstDispatch.plusNanos(600);
    String before = storedOutbox(operationId, effectId);
    Operation operation = journal.find(operationId).orElseThrow();
    assertThatThrownBy(() -> journal.recordEffectReadback(original, oldReadback, receivedAt))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("effect readback dispatch generation changed");
    assertThatThrownBy(() -> journal.recordEffectReadback(replacement, oldReadback, receivedAt))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("effect readback dispatch generation changed");
    assertThat(storedOutbox(operationId, effectId)).isEqualTo(before);
    assertThat(journal.find(operationId)).contains(operation);
    assertThat(journal.findNextEffect(operationId)).contains(replacement);

    var currentReadback =
        readbackFor(
            replacement,
            attemptId,
            EffectReadbackState.UNKNOWN_OR_PARTIAL,
            "replacement-observation",
            firstDispatch.plusNanos(500));
    assertThat(journal.recordEffectReadback(replacement, currentReadback, receivedAt))
        .isEqualTo(operation);
    assertThat(storedOutbox(operationId, effectId)).contains(tupleCodec.digest(currentReadback));
    assertThat(journal.findNextEffect(operationId))
        .get()
        .extracting(OutboxEntry::state)
        .isEqualTo(EffectOutboxState.AMBIGUOUS);
  }

  @ParameterizedTest(name = "prior claim: {0}, V2 outbox: {1}")
  @CsvSource({
    "false, pending",
    "false, valid dispatch",
    "false, retained retry",
    "false, zero attempt ID",
    "false, missing dispatch timestamp",
    "true, pending"
  })
  void migrationPreservesPendingLegacyOutboxAndRefusesClaimedRows(boolean claimed, String v2Outbox)
      throws Exception {
    String database = "legacy_closure_" + UUID.randomUUID().toString().replace("-", "");
    dsl.execute("CREATE DATABASE " + database);
    String canonicalUrl = POSTGRES.getJdbcUrl();
    String legacyUrl = canonicalUrl.substring(0, canonicalUrl.lastIndexOf('/') + 1) + database;
    try {
      journalFlyway(legacyUrl, "1").migrate();
      try (Connection legacyConnection =
          DriverManager.getConnection(legacyUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
        DSLContext legacyDsl = DSL.using(legacyConnection, SQLDialect.POSTGRES);
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        seedImmutableV1Journal(legacyDsl, claimed, base);
        String scopeBefore =
            legacyDsl
                .fetchSingle(
                    "SELECT scope_payload::text FROM recovery_control.initial_gameplay_release_operation WHERE operation_id=?",
                    V1_OPERATION_ID)
                .get(0, String.class);
        String bindingBefore =
            legacyDsl
                .fetchSingle(
                    "SELECT peer_storage_binding::text FROM recovery_control.initial_gameplay_release_operation WHERE operation_id=?",
                    V1_OPERATION_ID)
                .get(0, String.class);
        String evidenceBefore =
            legacyDsl
                .fetchSingle(
                    "SELECT evidence_snapshot::text FROM recovery_control.initial_gameplay_release_operation WHERE operation_id=?",
                    V1_OPERATION_ID)
                .get(0, String.class);
        String payloadBefore =
            legacyDsl
                .fetchSingle(
                    "SELECT payload::text FROM recovery_control.initial_gameplay_release_outbox WHERE outbox_id=?",
                    V1_OUTBOX_ID)
                .get(0, String.class);
        String transitionPayloadBefore =
            legacyDsl
                .fetchSingle(
                    "SELECT tuple_payload::text FROM recovery_control.initial_gameplay_release_transition WHERE operation_id=? AND verb='resume'",
                    V1_OPERATION_ID)
                .get(0, String.class);
        String transitionDigestBefore =
            legacyDsl
                .fetchSingle(
                    "SELECT tuple_digest FROM recovery_control.initial_gameplay_release_transition WHERE operation_id=? AND verb='resume'",
                    V1_OPERATION_ID)
                .get(0, String.class);
        Flyway upgrade = journalFlyway(legacyUrl, "2");
        if (claimed) {
          assertThatThrownBy(() -> upgrade.migrate())
              .hasStackTraceContaining("requires no prior claims");
          assertImmutableV1Payloads(
              legacyDsl,
              scopeBefore,
              bindingBefore,
              evidenceBefore,
              payloadBefore,
              transitionPayloadBefore,
              transitionDigestBefore);
          assertThat(
                  legacyDsl
                      .fetchSingle(
                          "SELECT to_regclass('recovery_control.initial_gameplay_release_attempt_closure')")
                      .get(0))
              .isNull();
          assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("1");
        } else {
          upgrade.migrate();
          assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("2");
          assertImmutableV1Payloads(
              legacyDsl,
              scopeBefore,
              bindingBefore,
              evidenceBefore,
              payloadBefore,
              transitionPayloadBefore,
              transitionDigestBefore);
          InitialGameplayReleaseJournal upgradedJournal =
              new JooqInitialGameplayReleaseJournal(legacyDsl, tupleCodec);
          Operation upgraded = upgradedJournal.find(V1_OPERATION_ID).orElseThrow();
          PublicTransitionTuple v1Resume = v1ResumeTransition();
          var resumed = new PublicTransitionResult(V1_OPERATION_ID, Phase.RESUME_AUTHORIZED);
          assertThat(upgradedJournal.findTransition(v1Resume)).contains(resumed);
          assertThat(upgradedJournal.transition(v1Resume, base.plusSeconds(1))).isEqualTo(resumed);
          assertImmutableV1Payloads(
              legacyDsl,
              scopeBefore,
              bindingBefore,
              evidenceBefore,
              payloadBefore,
              transitionPayloadBefore,
              transitionDigestBefore);
          assertThat(upgraded.maintenanceLockIdentity()).isEqualTo(V1_LOCK_IDENTITY);
          assertThat(upgraded.phase()).isEqualTo(Phase.RESUME_AUTHORIZED);
          assertThat(upgraded.evidence()).isNotNull();
          assertThat(upgraded.evidence().evidenceIdentity()).isEqualTo("evidence-owner-record-31");
          OutboxEntry pending =
              upgradedJournal
                  .findEffect(V1_OPERATION_ID, EffectKind.PREPARE_PLAYABLE_STATE_FENCED)
                  .orElseThrow();
          assertThat(pending.outboxId()).isEqualTo(V1_OUTBOX_ID);
          assertThat(pending.effect().effectId()).isEqualTo(V1_EFFECT_ID);
          assertThat(pending.effect().maintenanceLockIdentity()).isEqualTo(V1_LOCK_IDENTITY);
          assertThat(pending.payloadDigest()).isEqualTo(tupleCodec.digest(pending.effect()));
          assertThat(
                  legacyDsl
                      .fetchSingle(
                          "SELECT payload::text FROM recovery_control.initial_gameplay_release_outbox WHERE outbox_id=?",
                          V1_OUTBOX_ID)
                      .get(0, String.class))
              .isEqualTo(payloadBefore);
          Flyway dispatchAttemptUpgrade = journalFlyway(legacyUrl, "3");
          if (v2Outbox.equals("pending")) {
            String beforeUpgrade = retainedOutboxRow(legacyDsl);
            dispatchAttemptUpgrade.migrate();
            dispatchAttemptUpgrade.validate();
            assertThat(dispatchAttemptUpgrade.info().current().getVersion().getVersion())
                .isEqualTo("3");
            assertValidatedDispatchAttemptConstraint(legacyDsl);
            assertThat(retainedOutboxRow(legacyDsl)).isEqualTo(beforeUpgrade);
            return;
          }
          boolean zeroAttempt = v2Outbox.equals("zero attempt ID");
          boolean missingTimestamp = v2Outbox.equals("missing dispatch timestamp");
          boolean retainedRetry = v2Outbox.equals("retained retry");
          UUID freshAttempt = zeroAttempt ? new UUID(0L, 0L) : UUID.randomUUID();
          // Model one valid V2 row in a disposable fixture without running the V4 writer
          // against a pre-V4 schema. This is historical row shape, not a dispatch.
          assertThat(
                  legacyDsl.execute(
                      """
                      UPDATE recovery_control.initial_gameplay_release_outbox
                      SET state=?, last_attempt_id=?, attempt_count=1,
                          last_dispatched_at=CAST(? AS timestamptz)
                      WHERE outbox_id=? AND state='pending' AND attempt_count=0
                      """,
                      retainedRetry ? "retryable" : "dispatched",
                      freshAttempt,
                      missingTimestamp ? null : base.atOffset(ZoneOffset.UTC),
                      V1_OUTBOX_ID))
              .isEqualTo(1);
          String outboxBeforeV3 = retainedOutboxRow(legacyDsl);
          var installedBeforeV3 =
              legacyDsl.fetch(
                  """
                  SELECT version, script, checksum
                  FROM recovery_control.flyway_schema_history_recovery_control
                  WHERE success AND version IN ('1', '2')
                  ORDER BY installed_rank
                  """);
          assertThat(installedBeforeV3).hasSize(2);
          if (zeroAttempt || missingTimestamp) {
            assertThatThrownBy(dispatchAttemptUpgrade::migrate)
                .hasStackTraceContaining("ck_release_outbox_non_pending_attempt");
            assertThat(dispatchAttemptUpgrade.info().current().getVersion().getVersion())
                .isEqualTo("2");
            assertThat(retainedOutboxRow(legacyDsl)).isEqualTo(outboxBeforeV3);
            return;
          }
          OutboxEntry dispatched =
              upgradedJournal
                  .findEffect(V1_OPERATION_ID, EffectKind.PREPARE_PLAYABLE_STATE_FENCED)
                  .orElseThrow();
          assertThat(dispatched.outboxId()).isEqualTo(V1_OUTBOX_ID);
          assertThat(dispatched.effect()).isEqualTo(pending.effect());
          assertThat(dispatched.state())
              .isEqualTo(
                  retainedRetry ? EffectOutboxState.RETRYABLE : EffectOutboxState.DISPATCHED);
          assertThat(dispatched.lastAttemptId()).isEqualTo(freshAttempt);
          assertThat(dispatched.attemptCount()).isEqualTo(1);
          dispatchAttemptUpgrade.migrate();
          dispatchAttemptUpgrade.validate();
          assertThat(dispatchAttemptUpgrade.info().current().getVersion().getVersion())
              .isEqualTo("3");
          assertValidatedDispatchAttemptConstraint(legacyDsl);
          assertThat(retainedOutboxRow(legacyDsl)).isEqualTo(outboxBeforeV3);
          assertThat(
                  upgradedJournal.findEffect(
                      V1_OPERATION_ID, EffectKind.PREPARE_PLAYABLE_STATE_FENCED))
              .contains(dispatched);
          assertThat(
                  legacyDsl.fetch(
                      """
                      SELECT version, script, checksum
                      FROM recovery_control.flyway_schema_history_recovery_control
                      WHERE success AND version IN ('1', '2')
                      ORDER BY installed_rank
                      """))
              .containsExactlyElementsOf(installedBeforeV3);

          var installedBeforeV4 =
              legacyDsl.fetch(
                  """
                  SELECT version, script, checksum
                  FROM recovery_control.flyway_schema_history_recovery_control
                  WHERE success AND version IN ('1', '2', '3')
                  ORDER BY installed_rank
                  """);
          Flyway provenanceUpgrade = journalFlyway(legacyUrl, "4");
          provenanceUpgrade.migrate();
          provenanceUpgrade.validate();
          assertThat(provenanceUpgrade.info().current().getVersion().getVersion()).isEqualTo("4");
          assertThat(
                  legacyDsl.fetch(
                      """
                      SELECT version, script, checksum
                      FROM recovery_control.flyway_schema_history_recovery_control
                      WHERE success AND version IN ('1', '2', '3')
                      ORDER BY installed_rank
                      """))
              .containsExactlyElementsOf(installedBeforeV4);
          assertThat(
                  legacyDsl.fetchSingle(
                      """
                      SELECT (to_jsonb(o) - 'retry_provenance')::text AS old_shape,
                             retry_provenance
                      FROM recovery_control.initial_gameplay_release_outbox o WHERE outbox_id=?
                      """,
                      V1_OUTBOX_ID))
              .satisfies(row -> {
                assertThat(row.get("old_shape", String.class)).isEqualTo(outboxBeforeV3);
                assertThat(row.get("retry_provenance", String.class)).isNull();
              });
          var transportUpgrade = journalFlyway(legacyUrl, "5");
          transportUpgrade.migrate();
          transportUpgrade.validate();
          assertThat(transportUpgrade.info().current().getVersion().getVersion()).isEqualTo("5");
          assertThat(
                  legacyDsl
                      .fetchSingle(
                          "SELECT send_outcome_uncertain FROM recovery_control.initial_gameplay_release_outbox WHERE outbox_id=?",
                          V1_OUTBOX_ID)
                      .get(0))
              .isNull();
          journalFlyway(legacyUrl, "6").migrate();
          OutboxEntry reconcilable = upgradedJournal.findNextEffect(V1_OPERATION_ID).orElseThrow();
          journalFlyway(legacyUrl, "7").migrate();
          if (retainedRetry) {
            assertThat(reconcilable.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);
            assertThatThrownBy(() ->
                    upgradedJournal.claimEffect(
                        V1_OPERATION_ID,
                        V1_EFFECT_ID,
                        UUID.randomUUID(),
                        () -> base.plusSeconds(1)))
                .isInstanceOf(InitialGameplayReleaseConflictException.class)
                .hasMessageContaining("no exact durable provenance");
          } else {
            assertThat(reconcilable).isEqualTo(dispatched);
            assertThatThrownBy(() ->
                    upgradedJournal.recordRetryableEffectForDispatch(
                        reconcilable,
                        RetrySource.NOT_DISPATCHED,
                        "UNPROVED_LEGACY_SEND",
                        base.plusSeconds(1)))
                .isInstanceOf(InitialGameplayReleaseConflictException.class)
                .hasMessageContaining(
                    "local no-send proof requires the exact currently dispatched attempt");
          }
          assertThat(upgradedJournal.findNextEffect(V1_OPERATION_ID)).contains(reconcilable);
          EffectReadback closure =
              readbackFor(
                  reconcilable,
                  reconcilable.lastAttemptId(),
                  EffectReadbackState.PROVEN_NO_EFFECT,
                  "legacy-dispatch-owner-closure",
                  base.plusSeconds(2));
          upgradedJournal.recordEffectReadback(reconcilable, closure, base.plusSeconds(3));
          var replacement =
              upgradedJournal.claimEffect(
                  V1_OPERATION_ID, V1_EFFECT_ID, UUID.randomUUID(), () -> base.plusSeconds(4));
          assertThat(replacement.attemptCount()).isEqualTo(2);
        }
      }
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"pristine pending", "V1 outcome code", "V5 uncertain send"})
  void v6PreservesPristinePendingAndRefusesContradictoryDispatchOutcome(String variant)
      throws Exception {
    String database = "legacy_pending_" + UUID.randomUUID().toString().replace("-", "");
    dsl.execute("CREATE DATABASE " + database);
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String legacyUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    try {
      journalFlyway(legacyUrl, "1").migrate();
      try (var legacyConnection =
          DriverManager.getConnection(legacyUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var legacyDsl = DSL.using(legacyConnection, SQLDialect.POSTGRES);
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        seedImmutableV1Journal(legacyDsl, false, base);
        if ("V1 outcome code".equals(variant)) {
          assertThat(
                  legacyDsl.execute(
                      "UPDATE recovery_control.initial_gameplay_release_outbox"
                          + " SET last_outcome_code='TRANSPORT_OUTCOME_UNKNOWN' WHERE outbox_id=?",
                      V1_OUTBOX_ID))
              .isEqualTo(1);
        }
        journalFlyway(legacyUrl, "5").migrate();
        if ("V5 uncertain send".equals(variant)) {
          assertThat(
                  legacyDsl.execute(
                      "UPDATE recovery_control.initial_gameplay_release_outbox"
                          + " SET send_outcome_uncertain=true WHERE outbox_id=?",
                      V1_OUTBOX_ID))
              .isEqualTo(1);
        }
        String before = retainedOutboxRow(legacyDsl);
        Flyway upgrade = journalFlyway(legacyUrl, "6");
        if ("pristine pending".equals(variant)) {
          upgrade.migrate();
          upgrade.validate();
          assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("6");
          assertThat(
                  legacyDsl
                      .fetchSingle(
                          """
                          SELECT (to_jsonb(o) - 'preparation_observation_payload'
                                  - 'preparation_observation_digest')::text
                          FROM recovery_control.initial_gameplay_release_outbox o WHERE outbox_id=?
                          """,
                          V1_OUTBOX_ID)
                      .get(0, String.class))
              .isEqualTo(before);
          return;
        }
        assertThatThrownBy(upgrade::migrate)
            .hasStackTraceContaining("ck_release_outbox_pending_outcome");
        assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("5");
        var legacyJournal = new JooqInitialGameplayReleaseJournal(legacyDsl, tupleCodec);
        assertThatThrownBy(() ->
                legacyJournal.claimEffect(
                    V1_OPERATION_ID, V1_EFFECT_ID, UUID.randomUUID(), () -> base.plusSeconds(1)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("retained pending effect has a dispatch outcome");
        assertThat(retainedOutboxRow(legacyDsl)).isEqualTo(before);
      }
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @ParameterizedTest(name = "V6 preparation observation retained: {0}")
  @ValueSource(strings = {"absent", "UNKNOWN_OR_PARTIAL", "PREPARED_FENCED"})
  void v7RequiresCompletePreparationReceiptHistory(String retainedState) throws Exception {
    boolean retainObservation = !"absent".equals(retainedState);
    String database = "legacy_receipts_" + UUID.randomUUID().toString().replace("-", "");
    dsl.execute("CREATE DATABASE " + database);
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String legacyUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    try {
      journalFlyway(legacyUrl, "6").migrate();
      try (var legacyConnection =
          DriverManager.getConnection(legacyUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var legacyDsl = DSL.using(legacyConnection, SQLDialect.POSTGRES);
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        seedImmutableV1Journal(legacyDsl, true, base);
        var legacyJournal = new JooqInitialGameplayReleaseJournal(legacyDsl, tupleCodec);
        OutboxEntry dispatch =
            legacyJournal
                .findEffect(V1_OPERATION_ID, EffectKind.PREPARE_PLAYABLE_STATE_FENCED)
                .orElseThrow();
        EffectReadback acknowledgement =
            readbackFor(
                dispatch,
                dispatch.lastAttemptId(),
                EffectReadbackState.PREPARED_FENCED,
                "v6-acknowledgement",
                base.plusSeconds(1));
        EffectReadback observation =
            readbackFor(
                dispatch,
                dispatch.lastAttemptId(),
                retainObservation
                    ? EffectReadbackState.valueOf(retainedState)
                    : EffectReadbackState.UNKNOWN_OR_PARTIAL,
                "v6-observation",
                base.plusSeconds(2));
        Operation resumeAuthorized = legacyJournal.find(V1_OPERATION_ID).orElseThrow();
        TypedEffect handoff =
            new CommitOwnerAuthorizedHandoffEffect(
                resumeAuthorized.scope().operationId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                resumeAuthorized.maintenanceLockIdentity(),
                resumeAuthorized.scope(),
                tupleCodec.operationTupleDigest(resumeAuthorized),
                resumeAuthorized.evidence().evidenceIdentity());
        String handoffPayload = tupleCodec.toJson(handoff);
        String handoffDigest = tupleCodec.digest(handoff);
        assertThat(
                legacyDsl.execute(
                    """
                    UPDATE recovery_control.initial_gameplay_release_operation
                    SET phase='releasing', revision=revision + 1, updated_at=CAST(? AS timestamptz)
                    WHERE operation_id=? AND phase='resume_authorized'
                    """,
                    base.plusSeconds(1).atOffset(ZoneOffset.UTC),
                    V1_OPERATION_ID))
            .isEqualTo(1);
        assertThat(
                legacyDsl.execute(
                    """
                    UPDATE recovery_control.initial_gameplay_release_outbox
                    SET state='acknowledged', last_outcome_code=?, readback_identity=?,
                        readback_payload=CAST(? AS jsonb), readback_digest=?,
                        acknowledged_at=CAST(? AS timestamptz), updated_at=CAST(? AS timestamptz),
                        preparation_observation_payload=CAST(? AS jsonb),
                        preparation_observation_digest=?
                    WHERE outbox_id=?
                    """,
                    acknowledgement.state().name(),
                    acknowledgement.readbackIdentity(),
                    tupleCodec.toJson(acknowledgement),
                    tupleCodec.digest(acknowledgement),
                    acknowledgement.observedAt().atOffset(ZoneOffset.UTC),
                    (retainObservation ? base.plusSeconds(3) : acknowledgement.observedAt())
                        .atOffset(ZoneOffset.UTC),
                    retainObservation ? tupleCodec.toJson(observation) : null,
                    retainObservation ? tupleCodec.digest(observation) : null,
                    V1_OUTBOX_ID))
            .isEqualTo(1);
        assertThat(
                legacyDsl.execute(
                    """
                    INSERT INTO recovery_control.initial_gameplay_release_outbox (
                        outbox_id, operation_id, effect_id, effect_kind, operation_tuple_digest,
                        payload_digest, payload, state, attempt_count, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'pending', 0,
                              CAST(? AS timestamptz), CAST(? AS timestamptz))
                    """,
                    UUID.randomUUID(),
                    V1_OPERATION_ID,
                    handoff.effectId(),
                    handoff.kind().databaseValue(),
                    handoff.operationTupleDigest(),
                    handoffDigest,
                    handoffPayload,
                    base.plusSeconds(1).atOffset(ZoneOffset.UTC),
                    base.plusSeconds(1).atOffset(ZoneOffset.UTC)))
            .isEqualTo(1);
        String retained = retainedOutboxRow(legacyDsl);
        Flyway upgrade = journalFlyway(legacyUrl, "7");
        if (retainObservation) {
          assertThat(retained).contains(tupleCodec.digest(observation));
          assertThatThrownBy(upgrade::migrate)
              .hasStackTraceContaining("Preparation receipt history is incomplete");
          assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("6");
        } else {
          upgrade.migrate();
          upgrade.validate();
          assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("7");
          OutboxEntry pendingHandoff = legacyJournal.findNextEffect(V1_OPERATION_ID).orElseThrow();
          assertThat(pendingHandoff.effect().kind())
              .isEqualTo(EffectKind.COMMIT_OWNER_AUTHORIZED_HANDOFF);
          OutboxEntry claimedHandoff =
              legacyJournal.claimEffect(
                  V1_OPERATION_ID,
                  pendingHandoff.effect().effectId(),
                  UUID.randomUUID(),
                  () -> base.plusSeconds(4));
          assertThat(claimedHandoff.state()).isEqualTo(EffectOutboxState.DISPATCHED);
          assertThat(claimedHandoff.lastAttemptId()).isNotNull();
        }
        assertThat(retainedOutboxRow(legacyDsl)).isEqualTo(retained);
      }
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "pristine pending",
        "valid dispatched",
        "pending with dispatch timestamp",
        "pending with attempt count only",
        "pending with attempt ID only"
      })
  void pendingAttemptMigrationsValidateRetainedRowsWithoutChangingThem(String variant)
      throws Exception {
    String database = "legacy_pending_timestamp_" + UUID.randomUUID().toString().replace("-", "");
    dsl.execute("CREATE DATABASE " + database);
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String legacyUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    try {
      journalFlyway(legacyUrl, "7").migrate();
      try (var legacyConnection =
          DriverManager.getConnection(legacyUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var legacyDsl = DSL.using(legacyConnection, SQLDialect.POSTGRES);
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        seedImmutableV1Journal(legacyDsl, "valid dispatched".equals(variant), base);
        boolean invalid = "pending with dispatch timestamp".equals(variant);
        if (invalid) {
          assertThat(
                  legacyDsl.execute(
                      "UPDATE recovery_control.initial_gameplay_release_outbox"
                          + " SET last_dispatched_at=CAST(? AS timestamptz) WHERE outbox_id=?",
                      base.atOffset(ZoneOffset.UTC),
                      V1_OUTBOX_ID))
              .isEqualTo(1);
        }
        String retained = retainedOutboxRow(legacyDsl);
        Flyway timestampUpgrade = journalFlyway(legacyUrl, "8");
        if (invalid) {
          assertThatThrownBy(timestampUpgrade::migrate)
              .hasStackTraceContaining("ck_release_outbox_pending_dispatch_timestamp");
          assertThat(timestampUpgrade.info().current().getVersion().getVersion()).isEqualTo("7");
          assertThat(pendingDispatchConstraintDefinition(legacyDsl)).isEmpty();
          assertThat(retainedOutboxRow(legacyDsl)).isEqualTo(retained);
          return;
        }

        timestampUpgrade.migrate();
        assertThat(retainedOutboxRow(legacyDsl)).isEqualTo(retained);
        Flyway pendingAttemptUpgrade = journalFlyway(legacyUrl, "9");
        boolean hasAttemptCount = "pending with attempt count only".equals(variant);
        boolean hasAttemptId = "pending with attempt ID only".equals(variant);
        if (hasAttemptCount || hasAttemptId) {
          String updatePendingAttempt =
              "UPDATE recovery_control.initial_gameplay_release_outbox"
                  + (hasAttemptCount ? " SET attempt_count=?" : " SET last_attempt_id=?")
                  + " WHERE outbox_id=?";
          assertThat(
                  legacyDsl.execute(
                      updatePendingAttempt, hasAttemptCount ? 1 : UUID.randomUUID(), V1_OUTBOX_ID))
              .isEqualTo(1);
          String malformed = retainedOutboxRow(legacyDsl);
          var priorConstraint = pendingDispatchConstraintDefinition(legacyDsl);
          assertThat(priorConstraint).hasSize(1);
          assertThatThrownBy(pendingAttemptUpgrade::migrate)
              .hasStackTraceContaining("ck_release_outbox_pending_dispatch_timestamp");
          assertThat(pendingAttemptUpgrade.info().current().getVersion().getVersion())
              .isEqualTo("8");
          assertThat(pendingDispatchConstraintDefinition(legacyDsl)).isEqualTo(priorConstraint);
          assertThat(retainedOutboxRow(legacyDsl)).isEqualTo(malformed);
        } else {
          pendingAttemptUpgrade.migrate();
          assertThat(pendingAttemptUpgrade.info().current().getVersion().getVersion())
              .isEqualTo("9");
          journalFlyway(legacyUrl, null).migrate();
          assertCompletePackagedMigrationReceipts(legacyUrl);
          assertThat(retainedOutboxRow(legacyDsl)).isEqualTo(retained);
        }
      }
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  private static List<String> pendingDispatchConstraintDefinition(DSLContext journalDsl) {
    return journalDsl
        .fetch(
            """
            SELECT pg_catalog.pg_get_constraintdef(oid) AS definition
            FROM pg_catalog.pg_constraint
            WHERE conrelid = 'recovery_control.initial_gameplay_release_outbox'::regclass
              AND conname = 'ck_release_outbox_pending_dispatch_timestamp'
            """)
        .getValues("definition", String.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void v4LocalNoSendMigratesToOwnerReadbackWithoutRedispatch(boolean missingProvenance)
      throws Exception {
    String database = "legacy_local_no_send_" + UUID.randomUUID().toString().replace("-", "");
    dsl.execute("CREATE DATABASE " + database);
    String fixtureUrl = POSTGRES.getJdbcUrl();
    String legacyUrl = fixtureUrl.substring(0, fixtureUrl.lastIndexOf('/') + 1) + database;
    try {
      journalFlyway(legacyUrl, "4").migrate();
      try (var legacyConnection =
          DriverManager.getConnection(legacyUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var legacyDsl = DSL.using(legacyConnection, SQLDialect.POSTGRES);
        var legacyJournal = new JooqInitialGameplayReleaseJournal(legacyDsl, tupleCodec);
        Instant dispatchTime = Instant.parse("2026-09-01T00:00:00Z");
        seedImmutableV1Journal(legacyDsl, false, dispatchTime);
        UUID attempt = UUID.randomUUID();
        OffsetDateTime dispatchedAt = dispatchTime.atOffset(ZoneOffset.UTC);
        assertThat(
                legacyDsl.execute(
                    """
                    UPDATE recovery_control.initial_gameplay_release_outbox
                    SET state='retryable', last_attempt_id=?, attempt_count=1,
                        last_dispatched_at=CAST(? AS timestamptz), retry_provenance='local_no_send',
                        last_outcome_code='LOCAL_REJECTED'
                    WHERE outbox_id=?
                    """,
                    attempt,
                    dispatchedAt,
                    V1_OUTBOX_ID))
            .isEqualTo(1);
        assertThat(
                legacyDsl.execute(
                    """
                    INSERT INTO recovery_control.initial_gameplay_release_attempt_local_no_send
                        (outbox_id, attempt_id, attempt_ordinal, dispatched_at, recorded_at)
                    VALUES (?, ?, 1, CAST(? AS timestamptz), CAST(? AS timestamptz))
                    """,
                    V1_OUTBOX_ID,
                    attempt,
                    dispatchedAt,
                    dispatchedAt.plusSeconds(1)))
            .isEqualTo(1);
        var prior =
            legacyJournal
                .findEffect(V1_OPERATION_ID, EffectKind.PREPARE_PLAYABLE_STATE_FENCED)
                .orElseThrow();
        var retainedReceipt = localNoSendReceipts(legacyDsl, prior);
        assertThat(retainedReceipt).hasSize(1);

        var transportUpgrade = journalFlyway(legacyUrl, "5");
        transportUpgrade.migrate();
        transportUpgrade.validate();
        assertThat(
                legacyDsl
                    .fetchSingle(
                        "SELECT send_outcome_uncertain FROM recovery_control.initial_gameplay_release_outbox"
                            + " WHERE outbox_id = ?",
                        V1_OUTBOX_ID)
                    .get(0))
            .isNull();
        journalFlyway(legacyUrl, "6").migrate();
        if (missingProvenance) {
          assertThat(
                  legacyDsl.execute(
                      "UPDATE recovery_control.initial_gameplay_release_outbox"
                          + " SET retry_provenance = NULL WHERE outbox_id = ?",
                      V1_OUTBOX_ID))
              .isEqualTo(1);
        }
        var held = legacyJournal.findNextEffect(V1_OPERATION_ID).orElseThrow();
        assertThat(held.state()).isEqualTo(EffectOutboxState.AMBIGUOUS);
        assertThat(held.lastAttemptId()).isEqualTo(attempt);
        journalFlyway(legacyUrl, "7").migrate();
        assertThatThrownBy(() ->
                legacyJournal.claimEffect(
                    V1_OPERATION_ID,
                    V1_EFFECT_ID,
                    UUID.randomUUID(),
                    () -> dispatchTime.plusSeconds(2)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining(
                missingProvenance
                    ? "retained retry has no exact durable provenance"
                    : "retained local no-send proof does not match");

        var port = mock(InitialGameplayReleaseEffectPort.class);
        var partial =
            readbackFor(
                held,
                attempt,
                EffectReadbackState.UNKNOWN_OR_PARTIAL,
                "legacy-partial",
                dispatchTime.plusSeconds(2));
        var closure =
            readbackFor(
                held,
                attempt,
                EffectReadbackState.PROVEN_NO_EFFECT,
                "legacy-owner-closure",
                dispatchTime.plusSeconds(3));
        when(port.readback(prior.effect(), attempt, 1))
            .thenReturn(
                InitialGameplayReleaseEffectPort.EffectReadbackResolution.available(partial),
                InitialGameplayReleaseEffectPort.EffectReadbackResolution.available(closure));
        var controller = controllerAt(legacyJournal, port, dispatchTime.plusSeconds(3));
        controller.reconcileNextEffect(V1_OPERATION_ID);
        assertThat(legacyJournal.findNextEffect(V1_OPERATION_ID)).contains(held);
        assertThat(retainedOutboxRow(legacyDsl)).contains(tupleCodec.digest(partial));
        var contradiction =
            readbackFor(
                held,
                attempt,
                EffectReadbackState.PREPARED_FENCED,
                "contradictory-positive",
                dispatchTime.plusSeconds(3));
        String retained = retainedOutboxRow(legacyDsl);
        assertThatThrownBy(() ->
                legacyJournal.recordEffectReadback(
                    held, contradiction, dispatchTime.plusSeconds(4)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("after definitive no effect");
        assertThat(retainedOutboxRow(legacyDsl)).isEqualTo(retained);
        assertThat(localNoSendReceipts(legacyDsl, prior)).isEqualTo(retainedReceipt);
        controller.reconcileNextEffect(V1_OPERATION_ID);
        verify(port, org.mockito.Mockito.times(2)).readback(prior.effect(), attempt, 1);
        verifyNoMoreInteractions(port);
        assertThat(localNoSendReceipts(legacyDsl, prior)).isEqualTo(retainedReceipt);
        assertThatThrownBy(() ->
                legacyJournal.claimEffect(
                    V1_OPERATION_ID, V1_EFFECT_ID, attempt, () -> dispatchTime.plusSeconds(4)))
            .isInstanceOf(InitialGameplayReleaseConflictException.class)
            .hasMessageContaining("new effect attempt identity");
        var replacement =
            legacyJournal.claimEffect(
                V1_OPERATION_ID,
                V1_EFFECT_ID,
                UUID.randomUUID(),
                () -> dispatchTime.plusSeconds(4));
        assertThat(replacement.attemptCount()).isEqualTo(2);
      }
    } finally {
      dsl.execute("DROP DATABASE " + database);
    }
  }

  private static String retainedOutboxRow(DSLContext fixtureDsl) {
    return fixtureDsl
        .fetchSingle(
            "SELECT to_jsonb(o)::text FROM recovery_control.initial_gameplay_release_outbox o"
                + " WHERE outbox_id=?",
            V1_OUTBOX_ID)
        .get(0, String.class);
  }

  private static void assertValidatedDispatchAttemptConstraint(DSLContext fixtureDsl) {
    assertThat(
            fixtureDsl
                .fetchSingle(
                    """
                    SELECT convalidated FROM pg_constraint
                    WHERE conrelid='recovery_control.initial_gameplay_release_outbox'::regclass
                      AND conname='ck_release_outbox_non_pending_attempt'
                    """)
                .get(0, Boolean.class))
        .isTrue();
  }

  private static void assertImmutableV1Payloads(
      DSLContext legacyDsl,
      String scopePayload,
      String bindingPayload,
      String evidencePayload,
      String effectPayload,
      String transitionPayload,
      String transitionDigest) {
    assertThat(
            legacyDsl
                .fetchSingle(
                    "SELECT scope_payload::text FROM recovery_control.initial_gameplay_release_operation WHERE operation_id=?",
                    V1_OPERATION_ID)
                .get(0, String.class))
        .isEqualTo(scopePayload);
    assertThat(
            legacyDsl
                .fetchSingle(
                    "SELECT peer_storage_binding::text FROM recovery_control.initial_gameplay_release_operation WHERE operation_id=?",
                    V1_OPERATION_ID)
                .get(0, String.class))
        .isEqualTo(bindingPayload);
    assertThat(
            legacyDsl
                .fetchSingle(
                    "SELECT evidence_snapshot::text FROM recovery_control.initial_gameplay_release_operation WHERE operation_id=?",
                    V1_OPERATION_ID)
                .get(0, String.class))
        .isEqualTo(evidencePayload);
    assertThat(
            legacyDsl
                .fetchSingle(
                    "SELECT payload::text FROM recovery_control.initial_gameplay_release_outbox WHERE outbox_id=?",
                    V1_OUTBOX_ID)
                .get(0, String.class))
        .isEqualTo(effectPayload);
    assertThat(
            legacyDsl
                .fetchSingle(
                    "SELECT tuple_payload::text FROM recovery_control.initial_gameplay_release_transition WHERE operation_id=? AND verb='resume'",
                    V1_OPERATION_ID)
                .get(0, String.class))
        .isEqualTo(transitionPayload);
    assertThat(
            legacyDsl
                .fetchSingle(
                    "SELECT tuple_digest FROM recovery_control.initial_gameplay_release_transition WHERE operation_id=? AND verb='resume'",
                    V1_OPERATION_ID)
                .get(0, String.class))
        .isEqualTo(transitionDigest);
  }

  private static void seedImmutableV1Journal(
      DSLContext seedDsl, boolean claimed, Instant timestamp) {
    String effectPayload =
        V1_PREPARE_EFFECT_PAYLOAD_TEMPLATE.formatted(V1_SCOPE_PAYLOAD, V1_OPERATION_TUPLE_DIGEST);
    String resumePayload =
        V1_RESUME_TRANSITION_PAYLOAD_TEMPLATE.formatted(V1_SCOPE_DIGEST, V1_EVIDENCE_DIGEST);

    seedDsl.execute(
        """
        INSERT INTO recovery_control.initial_gameplay_release_operation (
            operation_id, target_namespace, playable_state_namespace_id,
            peer_storage_binding, scope_payload, evidence_ref, scope_digest,
            maintenance_lock_identity, phase, evidence_snapshot, evidence_identity,
            evidence_digest, revision, created_at, updated_at
        ) VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?,
                  'resume_authorized', CAST(? AS jsonb), ?, ?, 4,
                  CAST(? AS timestamptz), CAST(? AS timestamptz))
        """,
        V1_OPERATION_ID,
        "release-target",
        V1_PLAYABLE_NAMESPACE_ID,
        V1_PEER_STORAGE_BINDING_PAYLOAD,
        V1_SCOPE_PAYLOAD,
        "immutable-initial-layout-evidence",
        V1_SCOPE_DIGEST,
        V1_LOCK_IDENTITY,
        V1_EVIDENCE_PAYLOAD,
        "evidence-owner-record-31",
        V1_EVIDENCE_DIGEST,
        timestamp.atOffset(ZoneOffset.UTC),
        timestamp.atOffset(ZoneOffset.UTC));

    seedDsl.execute(
        """
        INSERT INTO recovery_control.initial_gameplay_release_transition (
            operation_id, verb, tuple_digest, tuple_payload, expected_phase, result_phase
        ) VALUES (?, 'resume', ?, CAST(? AS jsonb), 'awaiting_resume', 'resume_authorized')
        """,
        V1_OPERATION_ID,
        V1_RESUME_TRANSITION_DIGEST,
        resumePayload);

    seedDsl.execute(
        """
        INSERT INTO recovery_control.initial_gameplay_release_outbox (
            outbox_id, operation_id, effect_id, effect_kind, operation_tuple_digest,
            payload_digest, payload, state, attempt_count, last_attempt_id,
            last_dispatched_at, created_at, updated_at
        ) VALUES (?, ?, ?, 'prepare_playable_state_fenced', ?, ?, CAST(? AS jsonb),
                  ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz))
        """,
        V1_OUTBOX_ID,
        V1_OPERATION_ID,
        V1_EFFECT_ID,
        V1_OPERATION_TUPLE_DIGEST,
        V1_PREPARE_EFFECT_PAYLOAD_DIGEST,
        effectPayload,
        claimed ? "dispatched" : "pending",
        claimed ? 1 : 0,
        claimed ? V1_PRIOR_ATTEMPT_ID : null,
        claimed ? timestamp.atOffset(ZoneOffset.UTC) : null,
        timestamp.atOffset(ZoneOffset.UTC),
        timestamp.atOffset(ZoneOffset.UTC));
  }

  private static PublicTransitionTuple v1ResumeTransition() {
    return new PublicTransitionTuple(
        V1_OPERATION_ID,
        V1_SCOPE_DIGEST,
        TransitionVerb.RESUME,
        Phase.AWAITING_RESUME,
        V1_LOCK_IDENTITY,
        "immutable-initial-layout-evidence",
        "evidence-owner-record-31",
        V1_EVIDENCE_DIGEST);
  }

  private static OutboxEntry authorizedDispatch(Instant base) {
    OutboxEntry pending = authorizedPending(journal, base);
    return journal.claimEffect(
        pending.effect().operationId(),
        pending.effect().effectId(),
        UUID.randomUUID(),
        () -> base.plusSeconds(4));
  }

  private static OutboxEntry authorizedPending(
      InitialGameplayReleaseJournal journal, Instant base) {
    Scope scope = scope();
    journal.create(scope, UUID.randomUUID());
    InitialGameplayReleaseEvidence proof = evidence(scope);
    Operation ready =
        journal.bindVerifiedEvidence(
            scope, tupleCodec.digest(scope), proof, tupleCodec.digest(proof), base.plusSeconds(1));
    journal.transition(
        transitionTuple(ready, TransitionVerb.CONTINUE_RECOVERY), base.plusSeconds(2));
    journal.transition(transitionTuple(ready, TransitionVerb.RESUME), base.plusSeconds(3));
    return journal.findNextEffect(scope.operationId()).orElseThrow();
  }

  private static OutboxEntry authorizedDispatch(EffectKind effectKind, Instant base) {
    OutboxEntry prepare = authorizedDispatch(base);
    if (effectKind == EffectKind.PREPARE_PLAYABLE_STATE_FENCED) {
      return prepare;
    }
    EffectReadback prepared =
        readbackFor(
            prepare,
            prepare.lastAttemptId(),
            EffectReadbackState.PREPARED_FENCED,
            "prepare-readback-for-handoff",
            base.plusSeconds(5));
    journal.recordEffectReadback(prepare, prepared, base.plusSeconds(5));
    OutboxEntry handoff = journal.findNextEffect(prepare.effect().operationId()).orElseThrow();
    return journal.claimEffect(
        handoff.effect().operationId(),
        handoff.effect().effectId(),
        UUID.randomUUID(),
        () -> base.plusSeconds(6));
  }

  private static List<String> localNoSendReceipts(OutboxEntry dispatch) {
    return localNoSendReceipts(dsl, dispatch);
  }

  private static List<String> localNoSendReceipts(DSLContext context, OutboxEntry dispatch) {
    return context
        .fetch(
            """
            SELECT row_to_json(p)::text || ':' || p.xmin::text AS row_json
            FROM recovery_control.initial_gameplay_release_attempt_local_no_send p
            WHERE outbox_id = ? AND attempt_id = ?
            ORDER BY attempt_ordinal
            """,
            dispatch.outboxId(),
            dispatch.lastAttemptId())
        .getValues("row_json", String.class);
  }

  private static String storedClosure(OutboxEntry dispatch) {
    return java.util.Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT row_to_json(c)::text || ':' || c.xmin::text AS row_json"
                    + " FROM recovery_control.initial_gameplay_release_attempt_closure c"
                    + " WHERE outbox_id = ? AND attempt_id = ?",
                dispatch.outboxId(),
                dispatch.lastAttemptId()),
            "expected persisted attempt closure")
        .get("row_json", String.class);
  }

  private static String storedOutbox(UUID operationId, UUID effectId) {
    return java.util.Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT row_to_json(o)::text || ':' || o.xmin::text AS row_json FROM recovery_control.initial_gameplay_release_outbox o"
                    + " WHERE operation_id = ? AND effect_id = ?",
                operationId,
                effectId),
            "expected persisted outbox row")
        .get("row_json", String.class);
  }

  private static String awaitPostgresLogSince(int startOffset, String expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    String current = "";
    while (System.nanoTime() < deadline) {
      String logs = POSTGRES.getLogs();
      if (logs.length() >= startOffset) {
        current = logs.substring(startOffset);
        if (current.contains(expected)) {
          return current;
        }
      }
      Thread.sleep(25);
    }
    throw new AssertionError("PostgreSQL log did not contain invocation diagnostic: " + expected);
  }

  private static EffectReadback readbackFor(
      OutboxEntry entry,
      UUID attempt,
      EffectReadbackState state,
      String identity,
      Instant observedAt) {
    return new EffectReadback(
        entry.effect().operationId(),
        entry.effect().effectId(),
        attempt,
        entry.attemptCount(),
        entry.effect().kind(),
        entry.effect().operationTupleDigest(),
        entry.payloadDigest(),
        state,
        state == EffectReadbackState.PROVEN_NO_EFFECT
            ? EffectReadbackAuthority.GAME_SESSION_DURABLE_CLOSED_ATTEMPT
            : EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
        identity,
        observedAt);
  }

  @Test
  void createRetryWithChangedCanonicalTenantConflicts() {
    Scope original = scope();
    Operation created = journal.create(original, UUID.randomUUID());
    Scope changedTenant =
        new Scope(
            original.operationId(),
            UUID.randomUUID(),
            original.targetNamespace(),
            original.playableStateNamespaceId(),
            original.peerStorageBinding(),
            original.evidenceRef());

    assertThat(tupleCodec.digest(changedTenant)).isNotEqualTo(tupleCodec.digest(original));
    assertThatThrownBy(() -> journal.create(changedTenant, UUID.randomUUID()))
        .isInstanceOf(InitialGameplayReleaseConflictException.class);
    assertThat(journal.find(original.operationId())).contains(created);
  }

  private static PublicTransitionTuple transitionTuple(Operation operation, TransitionVerb verb) {
    return new PublicTransitionTuple(
        operation.scope().operationId(),
        tupleCodec.digest(operation.scope()),
        verb,
        verb.expectedPhase(),
        operation.maintenanceLockIdentity(),
        operation.scope().evidenceRef(),
        operation.evidence().evidenceIdentity(),
        tupleCodec.digest(operation.evidence()));
  }

  private static Scope scope() {
    return new Scope(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "release-target",
        UUID.randomUUID(),
        new PeerStorageBinding(
            "release-target", "cluster-a", "recovery-peer", "storage-uid-1", "revision-7", DIGEST),
        "immutable-initial-layout-evidence");
  }

  private static InitialGameplayReleaseEvidence evidence(Scope scope) {
    LayoutDescriptor descriptor =
        new LayoutDescriptor("issuer-layout-v1", "layout-r4", DIGEST, List.of(0, 1));
    IssuerLayoutProof issuerLayout =
        new IssuerLayoutProof(
            UUID.randomUUID(),
            descriptor,
            "account-issuer-source-identity-1",
            101,
            UUID.randomUUID(),
            "writer-inventory-r9",
            DIGEST,
            List.of(
                new PartitionAcknowledgement(0, "partition-0-ack", DIGEST, DIGEST, 32),
                new PartitionAcknowledgement(1, "partition-1-ack", DIGEST, DIGEST, 64)),
            Map.of(0, 32, 1, 64));
    WriterInventory inventory =
        new WriterInventory(
            "writer-inventory-r9", DIGEST, "legacy-layout-writer", List.of("writer-a", "writer-b"));
    WriterFenceProof writers =
        new WriterFenceProof(
            inventory,
            WriterDisposition.STOPPED,
            List.of(
                new WriterFenceAcknowledgement(
                    "writer-a", WriterDisposition.FENCED, "fence-a", DIGEST),
                new WriterFenceAcknowledgement(
                    "writer-b", WriterDisposition.FENCED, "fence-b", DIGEST)));
    return new InitialGameplayReleaseEvidence(
        scope.evidenceRef(),
        "evidence-owner-record-31",
        new MigrationProof(
            UUID.randomUUID(),
            ProofDisposition.VERIFIED,
            scope.peerStorageBinding().storageIdentity(),
            "writer-inventory-r9",
            DIGEST),
        issuerLayout,
        writers,
        new OwnerInitializationProof(
            OwnerService.WORLD_MANAGEMENT,
            UUID.randomUUID(),
            scope.playableStateNamespaceId(),
            ProofDisposition.VERIFIED,
            "world-init-source-1",
            DIGEST),
        new OwnerInitializationProof(
            OwnerService.ENTITY_MANAGEMENT,
            UUID.randomUUID(),
            scope.playableStateNamespaceId(),
            ProofDisposition.VERIFIED,
            "entity-init-source-1",
            DIGEST),
        new RegionLeaseProof("region-a", "lease-identity-51", 10));
  }

  private static OutboxEntry withAttempt(OutboxEntry dispatch, UUID attemptId) {
    return new OutboxEntry(
        dispatch.outboxId(),
        dispatch.effect(),
        dispatch.payloadDigest(),
        dispatch.state(),
        dispatch.attemptCount(),
        attemptId,
        dispatch.lastOutcomeCode(),
        dispatch.acknowledgedAt());
  }
}
