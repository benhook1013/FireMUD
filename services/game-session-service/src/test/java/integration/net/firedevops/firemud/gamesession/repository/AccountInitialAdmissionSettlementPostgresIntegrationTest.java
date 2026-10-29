package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.entitymanagement.testing.ControlledPlayerCreatedRosterSourceFixture;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.service.AccountInitialAdmissionSettlementReadService;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy;
import net.firedevops.firemud.gamesession.testing.ControlledPlayerCreatedOriginalOwnerFixture;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Physical query/legacy refusal proof, not a protected Human writer or Account settlement flow. The
 * reused source fixture explicitly constructs controlled pre1006 history under its installed
 * guards. No Account association is added to that history and no current pointer is seeded here.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountInitialAdmissionSettlementPostgresIntegrationTest {
  private static final String MIGRATIONS =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @TempDir Path directory;

  @Test
  void actualDefaultMigrationTailAndEmptyQueriesCannotProduceTerminalEvidence() {
    var data = dataSource();
    try {
      var migrations = migrateDefault(data);
      assertThat(migrations.info().applied())
          .noneMatch(m -> m.getState() == MigrationState.OUT_OF_ORDER);
      var dsl = dsl(data);
      var bindings = new AccountInitialAdmissionProtectionBindingRepository(dsl);
      var admissions = admissions(dsl);
      // PostgreSQL resolves every real join/column even when no operation matches.
      assertThat(bindings.read("test", "absent-original")).isEmpty();
      assertThat(admissions.read("test", "absent-original")).isEmpty();
      assertThat(dsl.fetchCount(DSL.table("game_session_canonical_initial_admission_attempt")))
          .isZero();
      assertThat(dsl.fetchCount(DSL.table("game_session_start_session_operator_attempt"))).isZero();
      assertThat(
              dsl.fetch(
                      "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                  .getValues("version", String.class))
          .containsSubsequence("1017", "1020", "1021", "1022");
    } finally {
      drop(data);
    }
  }

  @Test
  void retainedLegacyCommitRemainsReadableButCannotAcquireOrSettleUnrecordedAccountProtection()
      throws Exception {
    var data = dataSource();
    try {
      var initial =
          new ControlledPlayerCreatedRosterSourceFixture(
                  Files.createDirectories(directory.resolve("controlled-upstream")))
              .wireFixture();
      // Unused recovery transport only: no recovery RPC or roster retirement is exercised.
      var fixture =
          new ControlledPlayerCreatedOriginalOwnerFixture(
              data,
              initial,
              mock(CanonicalGameplayRosterRecoveryClient.class),
              Clock.systemUTC(),
              new CanonicalGameplayRosterReferencePolicy(
                  Duration.ofSeconds(20),
                  Duration.ofSeconds(20),
                  Duration.ofSeconds(20),
                  Duration.ofSeconds(1),
                  20));
      var dsl = dsl(data);
      var route = fixture.route();
      String namespace = fixture.wireFixture().request().getTargetNamespace();
      var reader = admissions(dsl);
      var legacy =
          reader.readCurrentOpenForRealm(namespace, route.canonicalTenantId(), route.realmId());
      assertThat(legacy.outcome()).isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
      assertThat(legacy.committedPointerVersion()).isPositive();
      assertThat(legacy.auditEventId()).isPositive();
      String before = terminalBytesAndXmin(dsl, namespace, legacy.initialAdmissionRequestId());
      var history =
          dsl.fetch(
              "SELECT installed_rank, version, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank");
      var migrations = migrateDefault(data);
      assertThat(migrations.info().applied())
          .noneMatch(m -> m.getState() == MigrationState.OUT_OF_ORDER);
      assertThat(
              dsl.fetch(
                      "SELECT installed_rank, version, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank")
                  .subList(0, history.size()))
          .containsExactlyElementsOf(history);
      assertThat(terminalBytesAndXmin(dsl, namespace, legacy.initialAdmissionRequestId()))
          .isEqualTo(before);
      assertThat(reader.read(namespace, legacy.initialAdmissionRequestId())).contains(legacy);

      var bindings = new AccountInitialAdmissionProtectionBindingRepository(dsl);
      assertThat(bindings.read(namespace, legacy.initialAdmissionRequestId())).isEmpty();
      assertThat(bindings.read(namespace, "another-admission")).isEmpty();
      var tuple =
          StartSessionPostAuthorizationExecutionTuple.decode(
              fixture.wireFixture().authored().request().canonicalPostAuthorizationTuple());
      var retained =
          dsl.fetchSingle(
              "SELECT owner_attempt_id, owner_fence FROM game_session_start_session_operator_attempt "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              namespace,
              tuple.controlPlaneRequestId());
      var originals =
          new GameSessionStartSessionOperatorAttemptRepository(dsl, Duration.ofMinutes(5));
      var original =
          originals
              .readHistoricalOwnerAttempt(
                  tuple,
                  retained.get("owner_attempt_id", UUID.class),
                  retained.get("owner_fence", Long.class))
              .orElseThrow();
      var presented = unrecordedProtection(original, tuple, legacy);
      var owner =
          new AccountInitialAdmissionSettlementReadService(bindings, originals, reader, namespace);
      var input = new AccountInitialAdmissionSettlementReadInput(UUID.randomUUID(), presented);
      assertThatThrownBy(() -> owner.read(input))
          .isInstanceOf(
              AccountInitialAdmissionSettlementReadService.SettlementUnavailableException.class);
      var changed =
          AccountStartSessionAdmissionProtectionEvidence.create(
              presented.request(),
              UUID.randomUUID(),
              presented.accountProtectionFence() + 1,
              presented.originalSourceCaptureReferenceBytes(),
              presented.originalSourceCaptureReferenceSha256(),
              presented.sourceEvidenceVector());
      assertThatThrownBy(
              () ->
                  owner.read(
                      new AccountInitialAdmissionSettlementReadInput(
                          input.readRequestId(), changed)))
          .isInstanceOf(
              AccountInitialAdmissionSettlementReadService.SettlementUnavailableException.class);

      // Installed terminal immutability must reject historical adoption, even with complete bytes.
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_session_canonical_initial_admission_attempt SET account_protection_evidence_version = 1, "
                          + "account_protection_id = ?, account_protection_fence = ?, original_owner_attempt_id = ?, "
                          + "original_owner_mutation_id = ?, original_owner_fence = ?, account_protection_evidence_bytes = ? "
                          + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
                      presented.accountProtectionId(),
                      presented.accountProtectionFence(),
                      original.ownerAttemptId(),
                      original.ownerMutationId(),
                      original.ownerFence(),
                      presented.canonicalBytes(),
                      namespace,
                      legacy.initialAdmissionRequestId()))
          .isInstanceOfSatisfying(
              DataAccessException.class,
              failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
      var request = request(legacy);
      var world =
          new CanonicalInitialAdmissionWorldProof(
              request.initialAdmissionRequestId(),
              request.requestDigest(),
              request.targetNamespace(),
              request.canonicalTenantId(),
              request.worldSlug(),
              request.realmId(),
              request.playableStateNamespaceId(),
              request.playableStateScope(),
              request.canonicalGameInstanceId(),
              request.canonicalVersionId(),
              "ACTIVE",
              request.activeLifecycleEpoch(),
              request.originKind(),
              request.expectedCatalogRevision(),
              request.expectedPriorPointerVersion(),
              request.holdId(),
              request.holdFence(),
              request.holdBindingDigest());
      assertThatThrownBy(() -> reader.reserve(request))
          .isInstanceOf(
              CanonicalInitialAdmissionRepository
                  .CanonicalHumanAdmissionProtectionUnavailableException.class);
      assertThatThrownBy(() -> reader.commit(request, world))
          .isInstanceOf(
              CanonicalInitialAdmissionRepository
                  .CanonicalHumanAdmissionProtectionUnavailableException.class);
      assertThatThrownBy(() -> reader.abort(request, "not-authorized-to-write"))
          .isInstanceOf(
              CanonicalInitialAdmissionRepository
                  .CanonicalHumanAdmissionProtectionUnavailableException.class);
      assertThat(terminalBytesAndXmin(dsl, namespace, legacy.initialAdmissionRequestId()))
          .isEqualTo(before);
      assertThat(bindings.read(namespace, legacy.initialAdmissionRequestId())).isEmpty();
      assertThat(reader.read(namespace, legacy.initialAdmissionRequestId())).contains(legacy);
    } finally {
      drop(data);
    }
  }

  /**
   * Integrity-only, explicitly unrecorded claim for the legacy selector, never acquisition or an
   * existing protected association. Its canonical hold digest is computed from these fields; the
   * retained pre1006 fixture instead has a synthetic sha256:eeee... hold digest. Neither that
   * retained value nor this reconstructed claim establishes a current authoritative World hold.
   */
  private static AccountStartSessionAdmissionProtectionEvidence unrecordedProtection(
      GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot original,
      StartSessionPostAuthorizationExecutionTuple tuple,
      CanonicalInitialAdmissionOwnerProof legacy)
      throws Exception {
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            new WorldCanonicalInitialAdmissionHold.Request(
                legacy.targetNamespace(),
                legacy.canonicalTenantId(),
                legacy.worldSlug(),
                legacy.realmId(),
                legacy.playableStateNamespaceId(),
                legacy.playableStateScope(),
                legacy.canonicalGameInstanceId(),
                legacy.canonicalVersionId(),
                legacy.activeLifecycleEpoch(),
                legacy.initialAdmissionRequestId(),
                legacy.requestDigest(),
                WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.valueOf(
                    legacy.originKind().name()),
                legacy.expectedCatalogRevision(),
                legacy.expectedPriorPointerVersion()),
            legacy.holdId(),
            legacy.holdFence());
    var request =
        AccountStartSessionAdmissionProtectionRequest.create(
            original.postAuthorizationExecutionTuple(),
            original.accountRedemptionProjection(),
            original.ownerMutationId(),
            original.ownerAttemptId(),
            original.ownerFence(),
            original.leaseExpiresAt(),
            UUID.randomUUID(),
            1,
            hold);
    var reference = tuple.bundleReference();
    byte[] capture =
        Rfc8785CanonicalJson.canonicalizeUtf8(
            JsonMapper.builder()
                .build()
                .writeValueAsString(
                    Map.of(
                        "schema",
                            AccountStartSessionAdmissionProtectionEvidence
                                .SOURCE_CAPTURE_REFERENCE_SCHEMA,
                        "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                        "capturedAt",
                            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
                                .withZone(ZoneOffset.UTC)
                                .format(original.createdAt()),
                        "bundleReference",
                            Map.of(
                                "bundleVersion",
                                reference.bundleVersion(),
                                "sourceVersion",
                                reference.sourceVersion(),
                                "sourceFence",
                                reference.sourceFence(),
                                "linearization",
                                reference.linearization()),
                        "snapshotSha256", sha256(tuple.canonicalBytes()))));
    return AccountStartSessionAdmissionProtectionEvidence.create(
        request,
        UUID.randomUUID(),
        1,
        capture,
        sha256(capture),
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                legacy.canonicalTenantId().toString(),
                "2",
                "17",
                null,
                null,
                "unrecorded-controlled-claim".getBytes(StandardCharsets.UTF_8))));
  }

  private static CanonicalInitialAdmissionRequest request(CanonicalInitialAdmissionOwnerProof p) {
    return new CanonicalInitialAdmissionRequest(
        p.targetNamespace(),
        p.canonicalTenantId(),
        p.worldSlug(),
        p.realmId(),
        p.playableStateNamespaceId(),
        p.playableStateScope(),
        p.canonicalGameInstanceId(),
        p.canonicalVersionId(),
        p.activeLifecycleEpoch(),
        p.expectedCatalogRevision(),
        p.originKind(),
        p.expectedPriorPointerVersion(),
        p.initialAdmissionRequestId(),
        p.requestDigest(),
        p.holdId(),
        p.holdFence(),
        p.holdBindingDigest());
  }

  private static CanonicalInitialAdmissionRepository admissions(DSLContext dsl) {
    var catalogs = new GameSessionCanonicalRealmCatalogRepository(dsl);
    return new CanonicalInitialAdmissionRepository(
        dsl,
        catalogs,
        new GameSessionCanonicalAdmissionPointerRepository(
            dsl, catalogs, new GameplayAdmissionPointerEventRepository(dsl)));
  }

  private static String terminalBytesAndXmin(DSLContext dsl, String namespace, String id) {
    return dsl.fetchSingle(
            "SELECT a.xmin::text || ':' || to_jsonb(a)::text FROM game_session_canonical_initial_admission_attempt a "
                + "WHERE target_namespace = ? AND initial_admission_request_id = ?",
            namespace,
            id)
        .get(0, String.class);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static Flyway migrateDefault(DriverManagerDataSource data) {
    var migrations =
        Flyway.configure()
            .dataSource(data)
            .schemas(data.getSchema())
            .defaultSchema(data.getSchema())
            .locations(MIGRATIONS)
            .load();
    migrations.migrate();
    return migrations;
  }

  private static DSLContext dsl(DriverManagerDataSource data) {
    return DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
  }

  private static DriverManagerDataSource dataSource() {
    var data =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    data.setSchema("account_admission_read_" + UUID.randomUUID().toString().replace("-", ""));
    return data;
  }

  private static void drop(DriverManagerDataSource data) {
    var admin =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl(admin).execute("DROP SCHEMA IF EXISTS \"" + data.getSchema() + "\" CASCADE");
  }
}
