package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.gamesession.repository.CanonicalHumanPublicationOwnerTestFixtures;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.saga.persistence.SagaInstance;
import net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository.OriginalStartSessionAssociation;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository.Candidate;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository.StoredSource;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalStartingInstanceRepository.StartingInstance;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL proof of append-only exact authored-core retention, including a valid empty result.
 * Synthetic result bytes prove storage binding only, not authenticated Game Design transport.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionAuthoredActorCoreRetentionPostgresIntegrationTest {
  private static final String NAMESPACE = "test";
  private static final String[] MIGRATION_LOCATIONS = {
    "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize(),
    "filesystem:"
        + Path.of("../common-saga/src/main/resources/db/migration/saga")
            .toAbsolutePath()
            .normalize()
  };
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void v1013UpgradePreservesCombinedHistoryAndExistingOwnerRows() throws Exception {
    try (Fixture fixture = newFixture("1012")) {
      LaunchInputs launch = fixture.createLaunch();
      String operationId = launch.tuple().controlPlaneRequestId();

      SagaInstance saga = new SagaInstance();
      saga.setSagaName("gs-v1013-retention-proof");
      saga.setState("RUNNING");
      saga.setCreatedAt(Instant.parse("2026-10-15T00:00:00Z"));
      saga.setUpdatedAt(Instant.parse("2026-10-15T00:00:01Z"));
      SagaInstance retainedSaga =
          new SagaInstanceRepository(fixture.dsl(), fixture.schema()).save(saga);

      List<Map<String, Object>> historyBefore = appliedMigrationHistory(fixture);
      assertThat(historyBefore)
          .allSatisfy(row -> assertThat(row.get("success")).isEqualTo(Boolean.TRUE));
      List<String> versionsBefore =
          historyBefore.stream().map(row -> Objects.toString(row.get("version"))).toList();
      assertThat(versionsBefore).contains("1000", "1001", "1002", "1012");
      assertThat(versionsBefore).doesNotContain("1013");
      assertThat(versionsBefore.get(versionsBefore.size() - 1)).isEqualTo("1012");

      RowSnapshot launchBefore =
          retainedRowSnapshot(
              fixture,
              "game_session_canonical_instance_launch",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId);
      RowSnapshot attemptBefore =
          retainedRowSnapshot(
              fixture,
              "game_session_start_session_operator_attempt",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId);
      RowSnapshot sagaBefore =
          retainedRowSnapshot(fixture, "saga_instance", "id = ?", retainedSaga.getId());

      migrationFlyway(fixture.dataSource(), fixture.schema(), null).migrate();

      List<Map<String, Object>> historyAfter = appliedMigrationHistory(fixture);
      assertThat(historyAfter).hasSize(historyBefore.size() + 1);
      assertThat(historyAfter.subList(0, historyBefore.size()))
          .containsExactlyElementsOf(historyBefore);
      Map<String, Object> v1013 = historyAfter.get(historyAfter.size() - 1);
      assertThat(v1013.get("version")).isEqualTo("1013");
      assertThat(v1013.get("script"))
          .isEqualTo("V1013__retained_original_start_session_authored_core.sql");
      assertThat(v1013.get("success")).isEqualTo(Boolean.TRUE);
      assertThat(v1013.get("installed_rank"))
          .isEqualTo(
              ((Number) historyBefore.get(historyBefore.size() - 1).get("installed_rank"))
                      .intValue()
                  + 1);

      assertRowUnchanged(
          launchBefore,
          retainedRowSnapshot(
              fixture,
              "game_session_canonical_instance_launch",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId));
      assertRowUnchanged(
          attemptBefore,
          retainedRowSnapshot(
              fixture,
              "game_session_start_session_operator_attempt",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId));
      assertRowUnchanged(
          sagaBefore,
          retainedRowSnapshot(fixture, "saga_instance", "id = ?", retainedSaga.getId()));
      var remainingRetentionRows =
          Objects.requireNonNull(
              fixture
                  .dsl()
                  .fetchOne(
                      "SELECT count(*) FROM game_session_original_start_session_authored_actor_core_retention"),
              "new V1013 table count query returned no row");
      Long remainingRetentionCount = remainingRetentionRows.get(0, Long.class);
      assertThat(remainingRetentionCount).isZero();
    }
  }

  @Test
  void retainsExactEmptyOwnerResultAndRejectsConflictOrMutation() throws Exception {
    try (Fixture fixture = newFixture()) {
      LaunchInputs launch = fixture.createLaunch();
      var attempt = launch.attempt();
      var selector = exactReplayRequest(launch, launch.claim());
      byte[] requestBytes =
          StartSessionTemplateAssociationReadGrpcCodec.toRequest(selector).toByteArray();
      byte[] sourceBytes = canonicalEmptyResult(requestBytes);
      Candidate candidate = candidate(launch, attempt, requestBytes, sourceBytes);

      StoredSource first =
          Objects.requireNonNull(
              fixture
                  .transactions()
                  .execute(
                      status -> fixture.retentionRepository().retainInOwnerTransaction(candidate)));
      StoredSource independent =
          fixture
              .retentionRepository()
              .readByOriginalOperation(NAMESPACE, launch.tuple().controlPlaneRequestId())
              .orElseThrow();

      assertThat(first.candidate().canonicalSourceResultBytes()).containsExactly(sourceBytes);
      assertThat(independent.candidate().canonicalSourceResultBytes()).containsExactly(sourceBytes);
      assertThat(independent.candidate().sourceResultDigest())
          .isEqualTo(candidate.sourceResultDigest());
      assertThat(independent.retainedAt()).isEqualTo(first.retainedAt());
      assertThat(new String(sourceBytes, java.nio.charset.StandardCharsets.UTF_8))
          .contains("\"projections\":[]");

      byte[] conflictingBytes = canonicalEmptyResult(requestBytes, "different exact result");
      Candidate conflict = candidate(launch, attempt, requestBytes, conflictingBytes);
      assertThatThrownBy(
              () ->
                  fixture
                      .transactions()
                      .execute(
                          status ->
                              fixture.retentionRepository().retainInOwnerTransaction(conflict)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("conflicts with its first exact result");
      assertThat(
              fixture
                  .retentionRepository()
                  .readByOriginalOperation(NAMESPACE, launch.tuple().controlPlaneRequestId())
                  .orElseThrow()
                  .candidate()
                  .canonicalSourceResultBytes())
          .containsExactly(sourceBytes);

      String where =
          " WHERE target_namespace = '"
              + NAMESPACE
              + "' AND control_plane_request_id = '"
              + launch.tuple().controlPlaneRequestId()
              + "'";
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_original_start_session_authored_actor_core_retention"
                              + " SET source_result_digest = 'sha256:' || repeat('0', 64)"
                              + where))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "DELETE FROM game_session_original_start_session_authored_actor_core_retention"
                              + where))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "TRUNCATE game_session_original_start_session_authored_actor_core_retention"))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void originalAssociationReadRequiresExactLiveOwnerAndActualPinsAreAppendOnly() throws Exception {
    try (Fixture fixture = newFixture()) {
      LaunchInputs launch = fixture.createLaunch();
      AttemptSnapshot attempt = launch.attempt();
      var tuple = launch.tuple();
      String operationId = tuple.controlPlaneRequestId();
      var owner = attempt;
      LaunchPinBytes pinBytes = retainedLaunchPinBytes(fixture, launch);
      assertThat(pinBytes.selectionRequest()).isNotEmpty();
      assertThat(pinBytes.selectionResponse()).isNotEmpty();
      assertThat(pinBytes.descriptorRequest()).isNotEmpty();
      assertThat(pinBytes.descriptorResponse()).isNotEmpty();

      RowSnapshot selectionBefore =
          retainedRowSnapshot(
              fixture,
              "game_session_start_session_template_association_pin",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId);
      RowSnapshot descriptorBefore =
          retainedRowSnapshot(
              fixture,
              "game_session_start_session_launch_descriptor_pin",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId);
      RowSnapshot attemptBefore =
          retainedRowSnapshot(
              fixture,
              "game_session_start_session_operator_attempt",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId);
      RowSnapshot launchBefore =
          retainedRowSnapshot(
              fixture,
              "game_session_canonical_instance_launch",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId);

      var associationReadRepository =
          new CanonicalGameInstanceLaunchAssociationRepository(fixture.dsl(), NAMESPACE);
      var exactSelector = originalSelector(launch.association(), tuple, launch.claim());
      OriginalStartSessionAssociation exactRead =
          associationReadRepository
              .readOriginalStartSessionAssociation(exactSelector)
              .orElseThrow();
      assertThat(exactRead.association()).isEqualTo(launch.association());
      assertThat(exactRead.postAuthorizationExecutionTuple().canonicalBytes())
          .containsExactly(tuple.canonicalBytes());
      assertThat(exactRead.ownerAttemptId()).isEqualTo(owner.ownerAttemptId());
      assertThat(exactRead.ownerFence()).isEqualTo(owner.ownerFence());

      var attemptRepository =
          new GameSessionStartSessionOperatorAttemptRepository(
              fixture.dsl(), Duration.ofMinutes(5));
      assertThatThrownBy(
              () ->
                  attemptRepository.readHistoricalOwnerAttempt(
                      tuple, UUID.randomUUID(), owner.ownerFence()))
          .isInstanceOf(
              GameSessionStartSessionOperatorAttemptRepository
                  .StaleStartSessionOperatorAttemptClaimException.class)
          .hasMessage("Historical owner read does not match the original owner attempt and fence");
      assertThatThrownBy(
              () ->
                  attemptRepository.readHistoricalOwnerAttempt(
                      tuple, owner.ownerAttemptId(), owner.ownerFence() + 1))
          .isInstanceOf(
              GameSessionStartSessionOperatorAttemptRepository
                  .StaleStartSessionOperatorAttemptClaimException.class)
          .hasMessage("Historical owner read does not match the original owner attempt and fence");

      UUID wrongTenantId = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
                      UUID.randomUUID(),
                      exactSelector.targetNamespace(),
                      wrongTenantId,
                      exactSelector.worldSlug(),
                      exactSelector.gameInstanceUuid(),
                      exactSelector.controlPlaneRequestId(),
                      exactSelector.launchDescriptorId(),
                      exactSelector.expectedDescriptorRequestDigest(),
                      exactSelector.expectedDescriptorResultDigest(),
                      exactSelector.expectedReleaseAttestationEvidenceDigest(),
                      exactSelector.originalOperation()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Original operation differs from the exact independent launch selector");

      var wrongTenantSelector =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
              UUID.randomUUID(),
              exactSelector.targetNamespace(),
              wrongTenantId,
              exactSelector.worldSlug(),
              exactSelector.gameInstanceUuid(),
              exactSelector.controlPlaneRequestId(),
              exactSelector.launchDescriptorId(),
              exactSelector.expectedDescriptorRequestDigest(),
              exactSelector.expectedDescriptorResultDigest(),
              exactSelector.expectedReleaseAttestationEvidenceDigest());
      assertThat(associationReadRepository.readOriginalStartSessionAssociation(wrongTenantSelector))
          .isEmpty();

      var wrongOwnerSelector =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
              UUID.randomUUID(),
              exactSelector.targetNamespace(),
              exactSelector.canonicalTenantId(),
              exactSelector.worldSlug(),
              exactSelector.gameInstanceUuid(),
              exactSelector.controlPlaneRequestId(),
              exactSelector.launchDescriptorId(),
              exactSelector.expectedDescriptorRequestDigest(),
              exactSelector.expectedDescriptorResultDigest(),
              exactSelector.expectedReleaseAttestationEvidenceDigest(),
              new CanonicalGameInstanceLaunchAssociationReadEvidence.OriginalOperation(
                  tuple.canonicalBytes(),
                  owner.ownerMutationId(),
                  UUID.randomUUID(),
                  owner.ownerFence()));
      assertThat(associationReadRepository.readOriginalStartSessionAssociation(wrongOwnerSelector))
          .isEmpty();

      var wrongFenceSelector =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
              UUID.randomUUID(),
              exactSelector.targetNamespace(),
              exactSelector.canonicalTenantId(),
              exactSelector.worldSlug(),
              exactSelector.gameInstanceUuid(),
              exactSelector.controlPlaneRequestId(),
              exactSelector.launchDescriptorId(),
              exactSelector.expectedDescriptorRequestDigest(),
              exactSelector.expectedDescriptorResultDigest(),
              exactSelector.expectedReleaseAttestationEvidenceDigest(),
              new CanonicalGameInstanceLaunchAssociationReadEvidence.OriginalOperation(
                  tuple.canonicalBytes(),
                  owner.ownerMutationId(),
                  owner.ownerAttemptId(),
                  owner.ownerFence() + 1));
      assertThat(associationReadRepository.readOriginalStartSessionAssociation(wrongFenceSelector))
          .isEmpty();

      var wrongMutationSelector =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
              UUID.randomUUID(),
              exactSelector.targetNamespace(),
              exactSelector.canonicalTenantId(),
              exactSelector.worldSlug(),
              exactSelector.gameInstanceUuid(),
              exactSelector.controlPlaneRequestId(),
              exactSelector.launchDescriptorId(),
              exactSelector.expectedDescriptorRequestDigest(),
              exactSelector.expectedDescriptorResultDigest(),
              exactSelector.expectedReleaseAttestationEvidenceDigest(),
              new CanonicalGameInstanceLaunchAssociationReadEvidence.OriginalOperation(
                  tuple.canonicalBytes(),
                  UUID.randomUUID(),
                  owner.ownerAttemptId(),
                  owner.ownerFence()));
      assertThat(
              associationReadRepository.readOriginalStartSessionAssociation(wrongMutationSelector))
          .isEmpty();

      var wrongWorldSelector =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
              UUID.randomUUID(),
              exactSelector.targetNamespace(),
              exactSelector.canonicalTenantId(),
              "other-world",
              exactSelector.gameInstanceUuid(),
              exactSelector.controlPlaneRequestId(),
              exactSelector.launchDescriptorId(),
              exactSelector.expectedDescriptorRequestDigest(),
              exactSelector.expectedDescriptorResultDigest(),
              exactSelector.expectedReleaseAttestationEvidenceDigest(),
              exactSelector.originalOperation());
      assertThatThrownBy(
              () ->
                  associationReadRepository.readOriginalStartSessionAssociation(wrongWorldSelector))
          .isInstanceOf(
              CanonicalGameInstanceLaunchAssociationRepository
                  .LaunchAssociationOwnerReadMismatchException.class)
          .hasMessage(
              "Canonical launch association differs from its exact original StartSession selector");

      String where =
          " WHERE target_namespace = '"
              + NAMESPACE
              + "' AND control_plane_request_id = '"
              + operationId
              + "'";
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_start_session_template_association_pin"
                              + " SET association_response_digest = 'sha256:' || repeat('0', 64)"
                              + where))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasStackTraceContaining("StartSession template association pins are immutable");
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_start_session_launch_descriptor_pin"
                              + " SET descriptor_response_digest = 'sha256:' || repeat('0', 64)"
                              + where))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasStackTraceContaining("StartSession launch descriptor pins are immutable");
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "DELETE FROM game_session_start_session_launch_descriptor_pin" + where))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasStackTraceContaining("StartSession launch descriptor pins are immutable");
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "DELETE FROM game_session_start_session_template_association_pin"
                              + where))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasStackTraceContaining("StartSession template association pins are immutable");
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute("TRUNCATE game_session_start_session_launch_descriptor_pin"))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasStackTraceContaining("StartSession launch descriptor pins are immutable");
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute("TRUNCATE game_session_start_session_template_association_pin"))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasStackTraceContaining("cannot truncate a table referenced in a foreign key constraint")
          .hasStackTraceContaining("game_session_start_session_launch_descriptor_pin")
          .hasStackTraceContaining("game_session_start_session_template_association_pin");
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "TRUNCATE game_session_start_session_template_association_pin CASCADE"))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasStackTraceContaining("pins are immutable");

      LaunchPinBytes pinBytesAfter = retainedLaunchPinBytes(fixture, launch);
      assertThat(pinBytesAfter.selectionRequest()).containsExactly(pinBytes.selectionRequest());
      assertThat(pinBytesAfter.selectionResponse()).containsExactly(pinBytes.selectionResponse());
      assertThat(pinBytesAfter.descriptorRequest()).containsExactly(pinBytes.descriptorRequest());
      assertThat(pinBytesAfter.descriptorResponse()).containsExactly(pinBytes.descriptorResponse());
      assertRowUnchanged(
          selectionBefore,
          retainedRowSnapshot(
              fixture,
              "game_session_start_session_template_association_pin",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId));
      assertRowUnchanged(
          descriptorBefore,
          retainedRowSnapshot(
              fixture,
              "game_session_start_session_launch_descriptor_pin",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId));
      assertRowUnchanged(
          attemptBefore,
          retainedRowSnapshot(
              fixture,
              "game_session_start_session_operator_attempt",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId));
      assertRowUnchanged(
          launchBefore,
          retainedRowSnapshot(
              fixture,
              "game_session_canonical_instance_launch",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId));
    }

    try (Fixture fixture = newFixture()) {
      LaunchInputs launch = fixture.createLaunch(Duration.ofSeconds(15));
      String operationId = launch.tuple().controlPlaneRequestId();
      LaunchPinBytes pinBytes = retainedLaunchPinBytes(fixture, launch);
      RowSnapshot selectionBefore =
          retainedRowSnapshot(
              fixture,
              "game_session_start_session_template_association_pin",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId);
      RowSnapshot descriptorBefore =
          retainedRowSnapshot(
              fixture,
              "game_session_start_session_launch_descriptor_pin",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId);
      var leaseExpiry =
          fixture
              .dsl()
              .fetchOne(
                  "SELECT lease_expires_at <= clock_timestamp() AS expired "
                      + "FROM game_session_start_session_operator_attempt "
                      + "WHERE target_namespace = ? AND control_plane_request_id = ? "
                      + "AND owner_attempt_id = ? AND owner_mutation_id = ? AND owner_fence = ?",
                  NAMESPACE,
                  operationId,
                  launch.attempt().ownerAttemptId(),
                  launch.attempt().ownerMutationId(),
                  launch.attempt().ownerFence());
      assertThat(leaseExpiry)
          .as("exact original owner attempt remains present before the expiry wait")
          .isNotNull();
      Boolean expired =
          Objects.requireNonNull(
              leaseExpiry.get("expired", Boolean.class),
              "exact original owner attempt expiry state is required");
      assertThat(expired)
          .as("original owner claim must remain live before waiting for its immutable deadline")
          .isFalse();

      long expiryWaitDeadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (!expired && System.nanoTime() < expiryWaitDeadline) {
        Thread.sleep(Duration.ofMillis(100).toMillis());
        leaseExpiry =
            fixture
                .dsl()
                .fetchOne(
                    "SELECT lease_expires_at <= clock_timestamp() AS expired "
                        + "FROM game_session_start_session_operator_attempt "
                        + "WHERE target_namespace = ? AND control_plane_request_id = ? "
                        + "AND owner_attempt_id = ? AND owner_mutation_id = ? AND owner_fence = ?",
                    NAMESPACE,
                    operationId,
                    launch.attempt().ownerAttemptId(),
                    launch.attempt().ownerMutationId(),
                    launch.attempt().ownerFence());
        assertThat(leaseExpiry)
            .as("exact original owner attempt remains present while polling its expiry")
            .isNotNull();
        expired =
            Objects.requireNonNull(
                leaseExpiry.get("expired", Boolean.class),
                "exact original owner attempt expiry state is required");
      }
      assertThat(expired)
          .as("original owner claim reaches its immutable database-clock deadline")
          .isTrue();

      var associationReadRepository =
          new CanonicalGameInstanceLaunchAssociationRepository(fixture.dsl(), NAMESPACE);
      assertThat(
              associationReadRepository.readOriginalStartSessionAssociation(
                  originalSelector(launch.association(), launch.tuple(), launch.claim())))
          .isEmpty();
      LaunchPinBytes pinBytesAfter = retainedLaunchPinBytes(fixture, launch);
      assertThat(pinBytesAfter.selectionRequest()).containsExactly(pinBytes.selectionRequest());
      assertThat(pinBytesAfter.selectionResponse()).containsExactly(pinBytes.selectionResponse());
      assertThat(pinBytesAfter.descriptorRequest()).containsExactly(pinBytes.descriptorRequest());
      assertThat(pinBytesAfter.descriptorResponse()).containsExactly(pinBytes.descriptorResponse());
      assertRowUnchanged(
          selectionBefore,
          retainedRowSnapshot(
              fixture,
              "game_session_start_session_template_association_pin",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId));
      assertRowUnchanged(
          descriptorBefore,
          retainedRowSnapshot(
              fixture,
              "game_session_start_session_launch_descriptor_pin",
              "target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              operationId));
    }
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request historicalSelector(
      LaunchInputs launch) {
    var tuple = launch.tuple();
    var descriptor = launch.binding().descriptor();
    var release = launch.binding().releaseAttestation();
    var association = launch.association();
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
        UUID.randomUUID(),
        association.targetNamespace(),
        association.canonicalTenantId(),
        association.worldSlug(),
        association.gameInstanceUuid(),
        tuple.controlPlaneRequestId(),
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        release.evidenceDigest());
  }

  private static LaunchPinBytes retainedLaunchPinBytes(Fixture fixture, LaunchInputs launch) {
    String namespace = launch.association().targetNamespace();
    String requestId = launch.tuple().controlPlaneRequestId();
    var selection =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT association_request_wire, association_response_wire FROM "
                        + "game_session_start_session_template_association_pin "
                        + "WHERE target_namespace = ? AND control_plane_request_id = ?",
                    namespace,
                    requestId),
            "retained canonical initial selection pin is required");
    var descriptor =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT descriptor_request_wire, descriptor_response_wire FROM "
                        + "game_session_start_session_launch_descriptor_pin "
                        + "WHERE target_namespace = ? AND control_plane_request_id = ?",
                    namespace,
                    requestId),
            "retained canonical launch descriptor pin is required");
    return new LaunchPinBytes(
        Objects.requireNonNull(
            selection.get("association_request_wire", byte[].class),
            "canonical selection request bytes are required"),
        Objects.requireNonNull(
            selection.get("association_response_wire", byte[].class),
            "canonical selection response bytes are required"),
        Objects.requireNonNull(
            descriptor.get("descriptor_request_wire", byte[].class),
            "canonical descriptor request bytes are required"),
        Objects.requireNonNull(
            descriptor.get("descriptor_response_wire", byte[].class),
            "canonical descriptor response bytes are required"));
  }

  private static Candidate candidate(
      LaunchInputs launch, AttemptSnapshot attempt, byte[] requestBytes, byte[] sourceBytes) {
    var tuple = launch.tuple();
    var binding = launch.binding();
    var release = binding.releaseAttestation();
    byte[] projection = attempt.accountRedemptionProjection();
    return new Candidate(
        NAMESPACE,
        tuple.controlPlaneRequestId(),
        launch.association().canonicalTenantId(),
        launch.association().gameInstanceUuid(),
        attempt.ownerAttemptId(),
        attempt.ownerMutationId(),
        attempt.ownerFence(),
        attempt.leaseExpiresAt(),
        tuple.canonicalBytes(),
        projection,
        OriginalStartSessionCurrentAttemptEvidence.projectionDigest(projection),
        release.canonicalVersionId(),
        UUID.fromString(release.commitId()),
        release.publishWorkflowId(),
        associationDigest(),
        release.authoredWorldSourceOperationId(),
        release.authoredWorldSourceEvidenceDigest(),
        release.worldSlug(),
        binding.descriptor().launchDescriptorId(),
        binding.descriptor().requestDigest(),
        binding.descriptor().resultDigest(),
        release.evidenceDigest(),
        requestBytes,
        sourceBytes,
        sha256(sourceBytes));
  }

  private static StartSessionTemplateAssociationReadEvidence.Request exactReplayRequest(
      LaunchInputs launch, AttemptClaim claim) {
    var tuple = launch.tuple();
    var release = launch.binding().releaseAttestation();
    return new StartSessionTemplateAssociationReadEvidence.Request(
        StartSessionTemplateAssociationReadEvidence.SCHEMA_VERSION,
        NAMESPACE,
        UUID.randomUUID(),
        tuple.canonicalBytes(),
        claim.ownerAttemptId(),
        claim.ownerFence(),
        new StartSessionTemplateAssociationReadEvidence.ExactReplay(
            release.canonicalVersionId(),
            UUID.fromString(release.commitId()),
            release.publishWorkflowId(),
            associationDigest()));
  }

  private static byte[] canonicalEmptyResult(byte[] requestBytes) {
    return canonicalEmptyResult(requestBytes, "");
  }

  private static byte[] canonicalEmptyResult(byte[] requestBytes, String associationRead) {
    Map<String, Object> result =
        Map.of(
            "schema", "game-design-authored-initial-actor-core-read/v1",
            "request", Base64.getEncoder().encodeToString(requestBytes),
            "associationRead", Base64.getEncoder().encodeToString(associationRead.getBytes()),
            "launchDescriptor", Base64.getEncoder().encodeToString("descriptor".getBytes()),
            "projections", List.of());
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(result));
    } catch (IOException invalid) {
      throw new IllegalStateException("Cannot serialize empty source fixture", invalid);
    }
  }

  private static String associationDigest() {
    return "sha256:" + "b".repeat(64);
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static List<Map<String, Object>> appliedMigrationHistory(Fixture fixture) {
    return fixture
        .dsl()
        .fetch(
            "SELECT installed_rank, version, description, type, script, checksum, installed_by,"
                + " installed_on, execution_time, success FROM "
                + fixture.schema()
                + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")
        .stream()
        .map(record -> record.intoMap())
        .toList();
  }

  private static RowSnapshot retainedRowSnapshot(
      Fixture fixture, String table, String predicate, Object... bindings) {
    var row =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT convert_to(row_to_json(retained_row)::TEXT, 'UTF8') AS row_bytes,"
                        + " retained_row.xmin::TEXT AS row_xmin FROM "
                        + fixture.schema()
                        + "."
                        + table
                        + " AS retained_row WHERE "
                        + predicate,
                    bindings),
            "retained row snapshot returned no row for " + table);
    return new RowSnapshot(row.get("row_bytes", byte[].class), row.get("row_xmin", String.class));
  }

  private static void assertRowUnchanged(RowSnapshot before, RowSnapshot after) {
    assertThat(after.bytes()).containsExactly(before.bytes());
    assertThat(after.xmin()).isEqualTo(before.xmin());
  }

  private static Flyway migrationFlyway(
      DriverManagerDataSource dataSource, String schema, String targetVersion) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .locations(MIGRATION_LOCATIONS)
            .placeholders(Map.of("serviceSchema", schema));
    if (targetVersion != null) {
      configuration.target(MigrationVersion.fromVersion(targetVersion));
    }
    return configuration.load();
  }

  private static Fixture newFixture() {
    return newFixture(null);
  }

  private static Fixture newFixture(String targetVersion) {
    String schema = "gs_authored_core_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    migrationFlyway(dataSource, schema, targetVersion).migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Fixture(schema, dataSource, dsl, new DataSourceTransactionManager(dataSource));
  }

  private record RowSnapshot(byte[] bytes, String xmin) {}

  private record LaunchPinBytes(
      byte[] selectionRequest,
      byte[] selectionResponse,
      byte[] descriptorRequest,
      byte[] descriptorResponse) {}

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    UUID tenantId = UUID.randomUUID();
    UUID registrationRequestId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    String tenantSlug = "authored-core-" + UUID.randomUUID().toString().substring(0, 8);
    String worldSlug = "retained-core-" + UUID.randomUUID().toString().substring(0, 8);
    String displayName = "Retained authored core owner";
    long sourceGameRowId = 91L;
    String sourceGameTenantKey =
        "retained-core-key-" + UUID.randomUUID().toString().substring(0, 8);
    String provenanceKind = "NEW_GAME_ROW";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequestId, tenantId, tenantSlug, worldSlug, displayName);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        sourceOperationId,
        requestDigest,
        tenantId,
        tenantSlug,
        worldSlug,
        displayName,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind,
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            displayName,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind));
  }

  private static AuthoredWorldLaunchDescriptorEvidence unpinnedDescriptor(
      StartSessionPostAuthorizationExecutionTuple tuple, AuthoredWorldSourceEvidence source) {
    var action = tuple.preAuthorizationTuple().action();
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            action.scope().targetNamespace(),
            tuple.controlPlaneRequestId(),
            action.scope().tenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            action.target().gameTemplateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            true,
            "{}");
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        "unowned-start-session-descriptor",
        19L,
        false,
        null,
        "{}",
        "generation-1",
        5L,
        91L,
        "immutable-release-ref",
        false,
        null);
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request originalSelector(
      CanonicalGameInstanceLaunchAssociation association,
      StartSessionPostAuthorizationExecutionTuple tuple,
      AttemptClaim claim) {
    var descriptor = association.launchBindingEvidence().descriptor();
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
        UUID.randomUUID(),
        association.targetNamespace(),
        association.canonicalTenantId(),
        association.worldSlug(),
        association.gameInstanceUuid(),
        association.controlPlaneRequestId(),
        association.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        association.launchBindingEvidence().releaseAttestation().evidenceDigest(),
        new CanonicalGameInstanceLaunchAssociationReadEvidence.OriginalOperation(
            tuple.canonicalBytes(),
            claim.ownerMutationId(),
            claim.ownerAttemptId(),
            claim.ownerFence()));
  }

  private record LaunchInputs(
      StartSessionPostAuthorizationExecutionTuple tuple,
      CompleteLaunchBindingEvidence binding,
      AttemptClaim claim,
      AttemptSnapshot attempt,
      OriginalStartSessionAssociation originalAssociation) {
    CanonicalGameInstanceLaunchAssociation association() {
      return originalAssociation.association();
    }
  }

  private record FreshAssociationInputs(
      GameSessionAuthoredWorldSourceRepository.IntakeReceipt sourceReceipt,
      FreshGameSessionTenantAssociation tenantAssociation) {}

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      DataSourceTransactionManager transactionManager)
      implements AutoCloseable {
    TransactionTemplate transactions() {
      return new TransactionTemplate(transactionManager);
    }

    GameSessionAuthoredActorCoreRetentionRepository retentionRepository() {
      return new GameSessionAuthoredActorCoreRetentionRepository(
          dsl, transactionManager, NAMESPACE);
    }

    LaunchInputs createLaunch() {
      return createLaunch(Duration.ofMinutes(5));
    }

    LaunchInputs createLaunch(Duration leaseDuration) {
      AuthoredWorldSourceEvidence source = sourceEvidence();
      FreshAssociationInputs fresh = createFreshAssociation(source);
      UUID canonicalVersionId = UUID.randomUUID();
      var createCatalog =
          new CreateCanonicalRealmCatalogRequest(
              UUID.randomUUID(),
              NAMESPACE,
              source.canonicalTenantId(),
              fresh.sourceReceipt().operationId(),
              source.worldSlug(),
              source.worldDisplayName(),
              true,
              true,
              "SHARED",
              "PRESEEDED_ONLY",
              null,
              null);
      RealmPolicyPublishedEvidence.PublishedSet publishedSet =
          CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
              createCatalog,
              source,
              canonicalVersionId,
              19L,
              "retained-core-publication-" + UUID.randomUUID(),
              "immutable-release-ref",
              "generation-1",
              5L);
      var catalogRepository = new GameSessionCanonicalRealmCatalogRepository(dsl);
      var preparedCatalog =
          catalogRepository.prepareInitialPublicProduction(createCatalog, publishedSet);
      transactions()
          .executeWithoutResult(
              status -> catalogRepository.createInitialPublicProduction(preparedCatalog));

      var tuple =
          CanonicalHumanPublicationOwnerTestFixtures.tuple(
              NAMESPACE,
              source.canonicalTenantId(),
              UUID.randomUUID(),
              91L,
              "retained-authored-core-" + UUID.randomUUID());
      CompleteLaunchBindingEvidence binding =
          CanonicalHumanPublicationOwnerTestFixtures.completeBinding(
              unpinnedDescriptor(tuple, source), publishedSet);
      AttemptClaim claim =
          CanonicalHumanPublicationOwnerTestFixtures.pinLaunch(
              dsl, transactions(), tuple, source, publishedSet, binding, leaseDuration);
      var startingRepository = new GameSessionCanonicalStartingInstanceRepository(dsl, NAMESPACE);
      StartingInstance starting =
          Objects.requireNonNull(
              transactions()
                  .execute(
                      status ->
                          startingRepository.createStarting(
                              fresh.tenantAssociation(),
                              binding,
                              tuple.preAuthorizationTuple().action().target().ownerAccountId(),
                              tuple.controlPlaneRequestId(),
                              tuple.mutationDigest(),
                              tuple)),
              "created original Human StartSession instance");
      var attempts = new GameSessionStartSessionOperatorAttemptRepository(dsl, leaseDuration);
      AttemptSnapshot attempt =
          Objects.requireNonNull(
              transactions().execute(status -> attempts.validateCurrentClaim(claim)),
              "validated original pending StartSession attempt");
      var associationReadRepository =
          new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
      OriginalStartSessionAssociation original =
          associationReadRepository
              .readOriginalStartSessionAssociation(
                  originalSelector(starting.launchAssociation(), tuple, claim))
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Canonical Human launch association must read back with its original owner"));
      assertThat(original.association()).isEqualTo(starting.launchAssociation());
      assertThat(original.postAuthorizationExecutionTuple().canonicalBytes())
          .containsExactly(tuple.canonicalBytes());
      assertThat(original.ownerAttemptId()).isEqualTo(claim.ownerAttemptId());
      assertThat(original.ownerFence()).isEqualTo(claim.ownerFence());
      assertThat(original.association().currentGameInstanceStatus())
          .isEqualTo(CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STARTING);
      assertThat(attempt.phaseState())
          .isEqualTo(OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE);
      assertThat(attempt.accountRedemptionProjection())
          .containsExactly(
              net.firedevops.firemud.common.account.startsession
                  .StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple));
      assertThat(original.canonicalRealmId()).isNotNull();
      assertThat(original.catalogRevision()).isPositive();
      return new LaunchInputs(tuple, binding, claim, attempt, original);
    }

    private FreshAssociationInputs createFreshAssociation(AuthoredWorldSourceEvidence source) {
      var sourceRepository = new GameSessionAuthoredWorldSourceRepository(dsl);
      var sourceReceipt =
          Objects.requireNonNull(
              transactions()
                  .execute(status -> sourceRepository.register(UUID.randomUUID(), source)),
              "registered controlled upstream source receipt");
      var tenantRepository = new GameSessionFreshTenantAssociationRepository(dsl);
      FreshGameSessionTenantAssociation tenantAssociation =
          Objects.requireNonNull(
              transactions()
                  .execute(
                      status ->
                          tenantRepository.associateFresh(
                              UUID.randomUUID(),
                              NAMESPACE,
                              sourceReceipt,
                              CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(source))),
              "created canonical fresh tenant association");
      return new FreshAssociationInputs(sourceReceipt, tenantAssociation);
    }

    @Override
    public void close() throws Exception {
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
      }
    }
  }
}
