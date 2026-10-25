package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolVersion;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountIndexMember;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountMemberRepairOperation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayBindingInventoryRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID NAMESPACE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CHARACTER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID INSTANCE_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final long RUNTIME_GAME_INSTANCE_ID = 23L;
  private static final UUID REGION_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String ISSUER_ID = GameSessionAccountDelegationProfile.ISSUER;
  private static final UUID TRANSITION_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");
  private static final UUID ACCOUNT_INDEX_FENCE =
      UUID.fromString("99999999-9999-4999-8999-999999999999");
  private static final UUID RESERVATION_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID RESERVATION_FENCE =
      UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ISSUER_COVERAGE_OPERATION_ID =
      UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID PRIOR_RESERVATION_ID =
      UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final UUID PRIOR_RESERVATION_FENCE =
      UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final UUID PRIOR_TRANSITION_ID =
      UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
  private static final String MEMBER_REPAIR_PRINCIPAL = "gamesession_coord_app";
  private static final String MEMBER_REPAIR_PASSWORD = UUID.randomUUID().toString();
  private static final String MEMBER_REPAIR_ACCOUNT_KEY_PREFIX = "session:game:index:account:";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @Test
  void failsClosedWhenInventoryAbsenceCannotProveLegacyControllerAbsence() {
    String schema = "gs_binding_inventory_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = dslForSchema(dataSource, schema);
      CanonicalGameplayBindingInventoryRepository repository =
          new CanonicalGameplayBindingInventoryRepository(dsl);
      assertThatThrownBy(() -> repository.prepare(request(candidateSession(1))))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("legacy-index migration fence");

      var inventory = repository.readSnapshot();
      assertThat(inventory.inventoryRevision()).isZero();
      assertThat(inventory.bindings()).isEmpty();
      assertThat(inventory.transitions()).isEmpty();
      assertThat(inventory.reservations()).isEmpty();
      var generationCount =
          java.util.Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT count(*) AS row_count FROM game_session_canonical_binding_generation"),
              "binding generation count row");
      assertThat(generationCount.get("row_count", Long.class)).isZero();
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void preparesFirstBindingOnlyBehindVerifiedDispositionAndRetainsNoPriorShape() {
    String schema = "gs_binding_first_inventory_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = dslForSchema(dataSource, schema);
      seedSyntheticVerifiedLegacyDisposition(dsl);
      CanonicalGameplayBindingInventoryRepository repository =
          new CanonicalGameplayBindingInventoryRepository(dsl);

      var prepared = repository.prepare(request(candidateSession(1)));
      var evidence = repository.readProvisionalCasEvidence(TRANSITION_ID);

      assertThat(prepared.bindingGeneration()).isEqualTo(BigInteger.ONE);
      assertThat(prepared.expectedPriorBindingRef()).isNull();
      assertThat(prepared.expectedPriorBindingGeneration()).isNull();
      assertThat(evidence.expectedPrior()).isNull();
      assertThat(evidence.expectedPriorReservation()).isNull();
      assertThat(evidence.initialBindingMigrationDisposition()).isNotNull();

      // This synthetic migration fixture exercises the repository guard, not live migration proof.
      var beforeLeaseFreeAttempt = repository.readSnapshot();
      assertThatThrownBy(() -> repository.markProvisional(evidence))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("requires the exact Account lease decision");
      var afterLeaseFreeAttempt = repository.readSnapshot();
      assertThat(afterLeaseFreeAttempt.inventoryRevision())
          .isEqualTo(beforeLeaseFreeAttempt.inventoryRevision());
      assertThat(afterLeaseFreeAttempt.bindings())
          .singleElement()
          .satisfies(
              candidate ->
                  assertThat(candidate.lifecycle())
                      .isEqualTo(
                          net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventoryEntry.Lifecycle
                              .CANDIDATE_PREPARED));
      var storedDecision =
          dsl.fetchOne(
              "SELECT admission_request_id, admission_lease_id"
                  + " FROM game_session_canonical_binding_transition WHERE transition_id = ?",
              TRANSITION_ID);
      assertThat(storedDecision).isNotNull();
      assertThat(storedDecision.get("admission_request_id", UUID.class)).isNull();
      assertThat(storedDecision.get("admission_lease_id", UUID.class)).isNull();

      assertThat(repository.readSnapshot().accountIndexObligations())
          .singleElement()
          .satisfies(obligation -> assertThat(obligation.expectedPriorGeneration()).isNull());
      assertThat(repository.readSnapshot().bindings())
          .singleElement()
          .satisfies(
              candidate ->
                  assertThat(candidate.lifecycle())
                      .isEqualTo(
                          net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventoryEntry.Lifecycle
                              .CANDIDATE_PREPARED));
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void persistsFirstBindingAccountIndexReadbackWithoutPriorObligation() {
    TestSchema testSchema = newTestSchema("gs_binding_first_projection_");
    try {
      LeaseBoundFirstBinding fixture =
          prepareLeaseBoundFirstBinding(testSchema.dataSource(), testSchema.dsl());
      assertThat(fixture.evidence().expectedPrior()).isNull();
      String member =
          CanonicalGameplayAccountIndexMember.of(fixture.evidence().candidate()).value();

      var receipt =
          fixture.repository().markCandidateAccountIndexPresent(fixture.evidence(), member);

      assertThat(receipt.obligationOrdinal()).isZero();
      assertThat(receipt.member()).isEqualTo(member);
      assertThat(receipt.state())
          .isEqualTo(
              net.firedevops.firemud.gamesession.binding
                  .CanonicalGameplayBindingAccountIndexProjectionReceipt.State
                  .PRESENT_AWAITING_COVERAGE);
      assertThat(fixture.repository().readSnapshot().accountIndexObligations())
          .singleElement()
          .satisfies(
              obligation ->
                  assertThat(obligation.projectionState())
                      .isEqualTo(
                          net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingAccountIndexObligation.ProjectionState
                              .PRESENT_AWAITING_COVERAGE));
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void acknowledgesOnlyTheFrozenNewBindingMemberAfterPhysicalRedisReadback() {
    TestSchema testSchema = newTestSchema("gs_binding_local_member_");
    RedisMemberFixture redisFixture = null;
    try {
      redisFixture = startMemberRepairRedisFixture();
      LeaseBoundFirstBinding fixture =
          prepareLeaseBoundFirstBinding(testSchema.dataSource(), testSchema.dsl());
      var before = fixture.repository().readSnapshot();
      CanonicalGameplayAccountMemberRepairOperation operation =
          fixture.repository().beginAccountMemberRepair(TRANSITION_ID);
      assertThat(operation.transitionId()).isEqualTo(TRANSITION_ID);
      assertThat(operation.accountId()).isEqualTo(ACCOUNT_ID);
      assertThat(operation.tenantId()).isEqualTo(TENANT_ID);
      assertThat(operation.originalObligation().ordinal()).isZero();
      assertThat(operation.originalObligation().expectedPriorGeneration()).isNull();
      assertThat(operation.originalObligation().status())
          .isEqualTo(
              net.firedevops.firemud.gamesession.binding
                  .CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED);
      assertThat(operation.operationFence()).isEqualTo(BigInteger.ONE);
      assertThat(operation.coverageFence()).isNotEqualTo(operation.accountIndexFence());
      assertThat(operation.accountControlEpoch()).isZero();
      assertThat(operation.inventorySnapshotRevision()).isEqualTo(before.inventoryRevision());
      assertThat(fixture.repository().beginAccountMemberRepair(TRANSITION_ID)).isEqualTo(operation);
      assertThat(fixture.repository().readSnapshot()).isEqualTo(before);

      BigInteger candidateCoverageGeneration =
          operation.inventorySnapshotRevision().add(BigInteger.ONE);
      BigInteger candidateCompletedRevision =
          operation.inventorySnapshotRevision().add(BigInteger.ONE);
      String memberRepairOperationTable = "game_session_canonical_account_member_repair_operation";
      assertCheckConstraintViolation(
          testSchema.dsl(),
          "UPDATE "
              + memberRepairOperationTable
              + " SET status = 'ACKNOWLEDGED', coverage_generation = NULL,"
              + " readback_state = 'PRESENT', readback_member = ?,"
              + " completed_inventory_revision = ? WHERE operation_id = ? AND status = 'PENDING'",
          operation.member(),
          candidateCompletedRevision,
          operation.operationId());
      assertCheckConstraintViolation(
          testSchema.dsl(),
          "UPDATE "
              + memberRepairOperationTable
              + " SET status = 'ACKNOWLEDGED', coverage_generation = ?,"
              + " readback_state = NULL, readback_member = ?,"
              + " completed_inventory_revision = ? WHERE operation_id = ? AND status = 'PENDING'",
          candidateCoverageGeneration,
          operation.member(),
          candidateCompletedRevision,
          operation.operationId());
      assertCheckConstraintViolation(
          testSchema.dsl(),
          "UPDATE "
              + memberRepairOperationTable
              + " SET status = 'ACKNOWLEDGED', coverage_generation = ?,"
              + " readback_state = 'PRESENT', readback_member = NULL,"
              + " completed_inventory_revision = ? WHERE operation_id = ? AND status = 'PENDING'",
          candidateCoverageGeneration,
          candidateCompletedRevision,
          operation.operationId());
      assertCheckConstraintViolation(
          testSchema.dsl(),
          "UPDATE "
              + memberRepairOperationTable
              + " SET original_projection_state = 'PRESENT_AWAITING_COVERAGE',"
              + " original_projection_member = ?, original_projection_inventory_revision = NULL"
              + " WHERE operation_id = ? AND status = 'PENDING'",
          operation.member(),
          operation.operationId());
      var pendingAfterMalformedCompletions =
          testSchema
              .dsl()
              .fetchOne(
                  "SELECT status, coverage_generation, readback_state, readback_member,"
                      + " original_projection_state, original_projection_inventory_revision"
                      + " FROM "
                      + memberRepairOperationTable
                      + " WHERE operation_id = ?",
                  operation.operationId());
      assertThat(pendingAfterMalformedCompletions.get("status", String.class)).isEqualTo("PENDING");
      assertThat(pendingAfterMalformedCompletions.get("coverage_generation")).isNull();
      assertThat(pendingAfterMalformedCompletions.get("readback_state", String.class)).isNull();
      assertThat(pendingAfterMalformedCompletions.get("readback_member", String.class)).isNull();
      assertThat(pendingAfterMalformedCompletions.get("original_projection_state", String.class))
          .isEqualTo("REQUIRED");
      assertThat(pendingAfterMalformedCompletions.get("original_projection_inventory_revision"))
          .isNull();

      var controlBefore =
          Objects.requireNonNull(
              testSchema
                  .dsl()
                  .fetchOne(
                      "SELECT * FROM game_session_canonical_account_coverage_control"
                          + " WHERE account_id = ?",
                      ACCOUNT_ID),
              "preparation positively persisted account coverage control");
      assertThat(controlBefore.get("state", String.class)).isEqualTo("NO_ACTIVE_ACCOUNT_WIDE_FLOW");
      assertThat(
              Objects.requireNonNull(
                      controlBefore.get("last_operation_fence"), "account coverage control fence")
                  .toString())
          .isEqualTo("0");
      assertThat(controlBefore.get("current_operation_id", UUID.class)).isNull();
      assertThat(controlBefore.get("account_admission_fence", UUID.class)).isNull();

      var redisOwner =
          new CanonicalGameplayAccountMemberRepairRedisOwner(
              redisFixture.applicationClient(),
              RedisScriptCatalog.loadInstalled(getClass().getClassLoader()));
      var acknowledgement =
          new CanonicalGameplayAccountMemberRepairOwner(
                  testSchema.dsl(),
                  redisFixture.applicationClient(),
                  RedisScriptCatalog.loadInstalled(getClass().getClassLoader()))
              .repair(TRANSITION_ID);
      assertThat(acknowledgement.schemaVersion()).isEqualTo(2);
      assertThat(acknowledgement.operationId()).isEqualTo(operation.operationId());
      assertThat(acknowledgement.action())
          .isEqualTo(
              net.firedevops.firemud.gamesession.binding
                  .CanonicalGameplayBindingAccountIndexObligation.Action.ADD_OR_RETAIN);
      assertThat(acknowledgement.readback().member()).isEqualTo(operation.member());
      assertThat(acknowledgement.coverageGeneration())
          .isGreaterThan(operation.inventorySnapshotRevision());
      assertThat(acknowledgement.accountCoverageState().name())
          .isEqualTo("NO_ACTIVE_ACCOUNT_WIDE_FLOW");
      assertThat(acknowledgement.accountCoverageOperationId().name()).isEqualTo("NOT_APPLICABLE");
      assertThat(acknowledgement.accountCoverageLifecycle().name()).isEqualTo("NOT_APPLICABLE");
      assertThat(acknowledgement.accountCoverageOperationFence().name())
          .isEqualTo("NOT_APPLICABLE");
      assertThat(acknowledgement.accountAdmissionFence().name()).isEqualTo("NOT_APPLICABLE");
      assertThat(acknowledgement.historicalAccountAdmissionFence().name())
          .isEqualTo("NOT_APPLICABLE");
      assertThat(acknowledgement.resolvedScope().name()).isEqualTo("NOT_APPLICABLE");

      var completed = fixture.repository().readSnapshot();
      assertThat(completed.transitions())
          .singleElement()
          .satisfies(
              transition ->
                  assertThat(transition.status())
                      .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL));
      assertThat(completed.bindings())
          .singleElement()
          .satisfies(
              candidate -> {
                assertThat(candidate.lifecycle())
                    .isEqualTo(
                        net.firedevops.firemud.gamesession.binding
                            .CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL);
                assertThat(candidate.accountIndexState())
                    .isEqualTo(
                        net.firedevops.firemud.gamesession.binding
                            .CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED);
              });
      assertThat(completed.accountIndexObligations())
          .singleElement()
          .satisfies(
              obligation -> {
                assertThat(obligation.status())
                    .isEqualTo(
                        net.firedevops.firemud.gamesession.binding
                            .CanonicalGameplayBindingAccountIndexObligation.Status.ACKNOWLEDGED);
                assertThat(obligation.projectionState())
                    .isEqualTo(
                        net.firedevops.firemud.gamesession.binding
                            .CanonicalGameplayBindingAccountIndexObligation.ProjectionState
                            .PRESENT_AWAITING_COVERAGE);
                assertThat(obligation.projectedMember()).isEqualTo(operation.member());
              });

      var controlAfter =
          Objects.requireNonNull(
              testSchema
                  .dsl()
                  .fetchOne(
                      "SELECT * FROM game_session_canonical_account_coverage_control"
                          + " WHERE account_id = ?",
                      ACCOUNT_ID),
              "account coverage control remains durably present");
      assertThat(controlAfter.get("state", String.class)).isEqualTo("NO_ACTIVE_ACCOUNT_WIDE_FLOW");
      assertThat(
              Objects.requireNonNull(
                      controlAfter.get("last_operation_fence"), "account coverage control fence")
                  .toString())
          .isEqualTo(
              Objects.requireNonNull(
                      controlBefore.get("last_operation_fence"), "account coverage control fence")
                  .toString());
      assertThat(controlAfter.get("current_operation_id", UUID.class)).isNull();
      assertThat(controlAfter.get("account_admission_fence", UUID.class)).isNull();
      var persistedOperation =
          Objects.requireNonNull(
              testSchema
                  .dsl()
                  .fetchOne(
                      "SELECT * FROM game_session_canonical_account_member_repair_operation"
                          + " WHERE operation_id = ?",
                      operation.operationId()),
              "binding-local repair operation is persisted");
      assertThat(persistedOperation.get("status", String.class)).isEqualTo("ACKNOWLEDGED");
      assertThat(persistedOperation.get("coverage_generation").toString())
          .isEqualTo(acknowledgement.coverageGeneration().toString());
      assertThat(persistedOperation.get("readback_member", String.class))
          .isEqualTo(operation.member());
      assertThat(persistedOperation.get("account_control_state", String.class))
          .isEqualTo("NO_ACTIVE_ACCOUNT_WIDE_FLOW");
      assertThat(persistedOperation.get("account_control_epoch").toString()).isEqualTo("0");
      assertThat(persistedOperation.get("inventory_snapshot_revision").toString())
          .isEqualTo(operation.inventorySnapshotRevision().toString());
      assertThat(persistedOperation.get("operation_fence").toString())
          .isEqualTo(operation.operationFence().toString());
      assertThat(persistedOperation.get("coverage_fence", UUID.class))
          .isEqualTo(operation.coverageFence());

      var completedEvidence = fixture.repository().readProvisionalCasEvidence(TRANSITION_ID);
      assertThat(completedEvidence.candidate().accountIndexState())
          .isEqualTo(
              net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry
                  .IndexState.ACKNOWLEDGED);
      assertThat(completedEvidence.candidate().issuerIndexState())
          .isEqualTo(
              net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry
                  .IndexState.REPAIR_REQUIRED);

      // Recreate the package-private SQL owner to model a worker restart after a lost response.
      var restartedRepository = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      var retryOperation = restartedRepository.beginAccountMemberRepair(TRANSITION_ID);
      assertThat(retryOperation).isEqualTo(operation);
      var retryReadback = redisOwner.projectAndRead(retryOperation);
      var retryAcknowledgement =
          restartedRepository.acknowledgeAccountMemberRepair(retryOperation, retryReadback);
      assertThat(retryAcknowledgement).isEqualTo(acknowledgement);
      assertThat(fixture.repository().readSnapshot()).isEqualTo(completed);
    } finally {
      if (redisFixture != null) {
        redisFixture.deleteAccountKey(ACCOUNT_ID);
        redisFixture.close();
      }
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void missingAccountCoverageControlRowCannotAuthorizeMemberRepair() {
    TestSchema testSchema = newTestSchema("gs_member_missing_");
    try {
      LeaseBoundFirstBinding fixture =
          prepareLeaseBoundFirstBinding(testSchema.dataSource(), testSchema.dsl());
      assertThat(
              testSchema
                  .dsl()
                  .execute(
                      "DELETE FROM game_session_canonical_account_coverage_control"
                          + " WHERE account_id = ?",
                      ACCOUNT_ID))
          .isEqualTo(1);

      assertThatThrownBy(() -> fixture.repository().beginAccountMemberRepair(TRANSITION_ID))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("account-coverage fence is missing");
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) AS row_count FROM"
                          + " game_session_canonical_account_member_repair_operation")
                  .get("row_count", Long.class))
          .isZero();
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void memberRepairPreservesAnEarlierPresentObservationUntilActualAcknowledgement() {
    TestSchema testSchema = newTestSchema("gs_member_present_");
    RedisMemberFixture redisFixture = null;
    try {
      redisFixture = startMemberRepairRedisFixture();
      LeaseBoundFirstBinding fixture =
          prepareLeaseBoundFirstBinding(testSchema.dataSource(), testSchema.dsl());
      String member =
          CanonicalGameplayAccountIndexMember.of(fixture.evidence().candidate()).value();

      // The old owner records PRESENT_AWAITING_COVERAGE but does not satisfy the obligation.
      var historicalObservation =
          fixture.repository().markCandidateAccountIndexPresent(fixture.evidence(), member);
      assertThat(historicalObservation.state())
          .isEqualTo(
              net.firedevops.firemud.gamesession.binding
                  .CanonicalGameplayBindingAccountIndexProjectionReceipt.State
                  .PRESENT_AWAITING_COVERAGE);
      var beforeRepair = fixture.repository().readSnapshot();
      assertThat(beforeRepair.accountIndexObligations().getFirst().status())
          .isEqualTo(
              net.firedevops.firemud.gamesession.binding
                  .CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED);

      var operation = fixture.repository().beginAccountMemberRepair(TRANSITION_ID);
      assertThat(operation.originalObligation().projectionState())
          .isEqualTo(
              net.firedevops.firemud.gamesession.binding
                  .CanonicalGameplayBindingAccountIndexObligation.ProjectionState
                  .PRESENT_AWAITING_COVERAGE);
      assertThat(operation.originalObligation().projectedMember()).isEqualTo(member);
      assertThat(operation.originalObligation().inventoryRevision())
          .isEqualTo(historicalObservation.projectionInventoryRevision());
      assertThat(fixture.repository().readSnapshot()).isEqualTo(beforeRepair);

      var redisOwner =
          new CanonicalGameplayAccountMemberRepairRedisOwner(
              redisFixture.applicationClient(),
              RedisScriptCatalog.loadInstalled(getClass().getClassLoader()));
      var readback = redisOwner.projectAndRead(operation);
      var acknowledgement =
          fixture.repository().acknowledgeAccountMemberRepair(operation, readback);
      assertThat(acknowledgement.member()).isEqualTo(member);
      assertThat(fixture.repository().readSnapshot().accountIndexObligations())
          .singleElement()
          .satisfies(
              obligation -> {
                assertThat(obligation.status())
                    .isEqualTo(
                        net.firedevops.firemud.gamesession.binding
                            .CanonicalGameplayBindingAccountIndexObligation.Status.ACKNOWLEDGED);
                assertThat(obligation.projectionState())
                    .isEqualTo(
                        net.firedevops.firemud.gamesession.binding
                            .CanonicalGameplayBindingAccountIndexObligation.ProjectionState
                            .PRESENT_AWAITING_COVERAGE);
                assertThat(obligation.projectedMember()).isEqualTo(member);
              });
    } finally {
      if (redisFixture != null) {
        redisFixture.deleteAccountKey(ACCOUNT_ID);
        redisFixture.close();
      }
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void changedNoActiveControlFenceRejectsRedisReadbackAcknowledgement() {
    TestSchema testSchema = newTestSchema("gs_member_fence_");
    RedisMemberFixture redisFixture = null;
    try {
      redisFixture = startMemberRepairRedisFixture();
      LeaseBoundFirstBinding fixture =
          prepareLeaseBoundFirstBinding(testSchema.dataSource(), testSchema.dsl());
      // This is a legal NO_ACTIVE row after an earlier completed account-wide lifecycle. The
      // fixture advances only the durable epoch; it does not manufacture ACTIVE coverage proof.
      assertThat(
              testSchema
                  .dsl()
                  .execute(
                      "UPDATE game_session_canonical_account_coverage_control"
                          + " SET last_operation_fence = 1"
                          + " WHERE account_id = ? AND state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'"
                          + " AND last_operation_fence = 0",
                      ACCOUNT_ID))
          .isEqualTo(1);
      CanonicalGameplayAccountMemberRepairOperation operation =
          fixture.repository().beginAccountMemberRepair(TRANSITION_ID);
      assertThat(operation.accountControlEpoch()).isEqualTo(BigInteger.ONE);
      var redisOwner =
          new CanonicalGameplayAccountMemberRepairRedisOwner(
              redisFixture.applicationClient(),
              RedisScriptCatalog.loadInstalled(getClass().getClassLoader()));
      var readback = redisOwner.projectAndRead(operation);

      // A completed account-wide lifecycle may return to NO_ACTIVE with a later durable epoch.
      // This controlled row advancement tests stale-result rejection only; it does not fabricate
      // an ACTIVE operation or establish positive account-wide recovery proof.
      assertThat(
              testSchema
                  .dsl()
                  .execute(
                      "UPDATE game_session_canonical_account_coverage_control"
                          + " SET last_operation_fence = 2"
                          + " WHERE account_id = ? AND state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'"
                          + " AND last_operation_fence = 1",
                      ACCOUNT_ID))
          .isEqualTo(1);

      assertThatThrownBy(
              () -> fixture.repository().acknowledgeAccountMemberRepair(operation, readback))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("control fence changed");
      var snapshot = fixture.repository().readSnapshot();
      assertThat(snapshot.accountIndexObligations())
          .singleElement()
          .satisfies(
              obligation ->
                  assertThat(obligation.status())
                      .isEqualTo(
                          net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED));
      assertThat(snapshot.bindings())
          .singleElement()
          .satisfies(
              candidate ->
                  assertThat(candidate.accountIndexState())
                      .isEqualTo(
                          net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED));
      var pending =
          Objects.requireNonNull(
              testSchema
                  .dsl()
                  .fetchOne(
                      "SELECT status, coverage_generation, readback_member"
                          + " FROM game_session_canonical_account_member_repair_operation"
                          + " WHERE operation_id = ?",
                      operation.operationId()),
              "pending member-repair row remains retained");
      assertThat(pending.get("status", String.class)).isEqualTo("PENDING");
      assertThat(pending.get("coverage_generation")).isNull();
      assertThat(pending.get("readback_member", String.class)).isNull();
    } finally {
      if (redisFixture != null) {
        redisFixture.deleteAccountKey(ACCOUNT_ID);
        redisFixture.close();
      }
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void leaseBoundRuntimeFenceTriggerRejectsRawAbortAfterAccountMemberProjection() {
    TestSchema testSchema = newTestSchema("gs_member_stale_");
    RedisMemberFixture redisFixture = null;
    try {
      redisFixture = startMemberRepairRedisFixture();
      LeaseBoundFirstBinding fixture =
          prepareLeaseBoundFirstBinding(testSchema.dataSource(), testSchema.dsl());
      CanonicalGameplayAccountMemberRepairOperation operation =
          fixture.repository().beginAccountMemberRepair(TRANSITION_ID);
      var redisOwner =
          new CanonicalGameplayAccountMemberRepairRedisOwner(
              redisFixture.applicationClient(),
              RedisScriptCatalog.loadInstalled(getClass().getClassLoader()));
      redisOwner.projectAndRead(operation);

      assertThatThrownBy(
              () ->
                  testSchema
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_binding_transition"
                              + " SET status = 'ABORTED' WHERE transition_id = ?"
                              + " AND status = 'PROVISIONAL'",
                          TRANSITION_ID))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> {
                assertThat(failure.sqlState()).isEqualTo("23514");
                assertThat(failure)
                    .hasMessageContaining(
                        "A lease-bound runtime fence cannot be released without terminal owner reconciliation");
              });
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_canonical_binding_transition"
                          + " WHERE transition_id = ?",
                      TRANSITION_ID)
                  .get("status", String.class))
          .isEqualTo("PROVISIONAL");
      var pending =
          Objects.requireNonNull(
              testSchema
                  .dsl()
                  .fetchOne(
                      "SELECT status, coverage_generation, readback_member"
                          + " FROM game_session_canonical_account_member_repair_operation"
                          + " WHERE operation_id = ?",
                      operation.operationId()),
              "original member operation remains retained after stale transition rejection");
      assertThat(pending.get("status", String.class)).isEqualTo("PENDING");
      assertThat(pending.get("coverage_generation")).isNull();
      assertThat(pending.get("readback_member", String.class)).isNull();
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_canonical_binding_account_index_obligation"
                          + " WHERE transition_id = ? AND obligation_ordinal = 0",
                      TRANSITION_ID)
                  .get("status", String.class))
          .isEqualTo("REQUIRED");
    } finally {
      if (redisFixture != null) {
        redisFixture.deleteAccountKey(ACCOUNT_ID);
        redisFixture.close();
      }
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void readsLeaseBoundAdmissionDecisionInReadOnlyTransaction() {
    TestSchema testSchema = newTestSchema("gs_binding_readonly_decision_");
    try {
      LeaseBoundFirstBinding fixture =
          prepareLeaseBoundFirstBinding(testSchema.dataSource(), testSchema.dsl());

      var decision =
          fixture.repository().readAdmissionDecision(TRANSITION_ID, fixture.leaseEvidence());

      assertThat(decision.bindingDecisionId()).isEqualTo(TRANSITION_ID);
      assertThat(decision.leaseEvidence().hasSameIdentity(fixture.leaseEvidence())).isTrue();
      assertThat(decision.candidate()).isEqualTo(fixture.evidence().candidate().identity());
      assertThat(decision.expectedOldBindingGeneration()).isNull();
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void persistsLeaseBoundAdmissionTerminalTimestampAsPostgresTimestamptz() {
    TestSchema testSchema = newTestSchema("gs_binding_terminal_timestamp_");
    try {
      LeaseBoundFirstBinding fixture =
          prepareLeaseBoundFirstBinding(testSchema.dataSource(), testSchema.dsl());

      var decision =
          fixture.repository().readAdmissionDecision(TRANSITION_ID, fixture.leaseEvidence());
      var storedTarget =
          testSchema
              .dsl()
              .fetchOne(
                  "SELECT admission_target_terminal_at,"
                      + " pg_typeof(admission_target_terminal_at) AS terminal_at_type"
                      + " FROM game_session_canonical_binding_transition WHERE transition_id = ?",
                  TRANSITION_ID);

      assertThat(storedTarget).isNotNull();
      assertThat(storedTarget.get("terminal_at_type", String.class))
          .isEqualTo("timestamp with time zone");
      assertThat(storedTarget.get("admission_target_terminal_at", OffsetDateTime.class).toInstant())
          .isEqualTo(decision.targetEvidence().ownerProofTerminalAt());
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void leaseBoundDecisionAndRuntimeMutationAcquireLocksInOneOrder() throws Exception {
    String applicationName =
        "gs_binding_runtime_lock_order_" + UUID.randomUUID().toString().replace("-", "");
    TestSchema testSchema = newTestSchema("gs_binding_runtime_lock_order_", applicationName);
    CountDownLatch runtimeRowLocked = new CountDownLatch(1);
    CountDownLatch allowRuntimeMutation = new CountDownLatch(1);
    try {
      PreparedLeaseBoundFirstBinding fixture =
          prepareLeaseBoundFirstBindingDecision(testSchema.dataSource(), testSchema.dsl());
      CanonicalPlayableTarget target = fixture.runtimeTarget().playableTarget();
      long initialRowVersion =
          Objects.requireNonNull(
                  testSchema
                      .dsl()
                      .fetchOne(
                          "SELECT row_version FROM game_instances WHERE tenant_id = ? AND id = ?",
                          target.gameSessionTenantId(),
                          target.gameInstanceId()))
              .get("row_version", Long.class);

      try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
        try {
          Future<Integer> runtimeMutation =
              executor.submit(
                  () ->
                      testSchema
                          .dsl()
                          .transactionResult(
                              configuration -> {
                                DSLContext transaction = DSL.using(configuration);
                                var runtime =
                                    transaction.fetchOne(
                                        "SELECT id FROM game_instances"
                                            + " WHERE tenant_id = ? AND id = ? FOR UPDATE",
                                        target.gameSessionTenantId(),
                                        target.gameInstanceId());
                                if (runtime == null) {
                                  throw new IllegalStateException("Expected current runtime row");
                                }
                                runtimeRowLocked.countDown();
                                awaitLatch(allowRuntimeMutation);
                                return transaction.execute(
                                    "UPDATE game_instances SET row_version = row_version + 1"
                                        + " WHERE tenant_id = ? AND id = ?",
                                    target.gameSessionTenantId(),
                                    target.gameInstanceId());
                              }));

          assertThat(runtimeRowLocked.await(5, TimeUnit.SECONDS)).isTrue();
          Future<CanonicalGameplayBindingTransitionSnapshot> decisionAcquisition =
              executor.submit(
                  () ->
                      fixture.repository().markProvisional(fixture.evidence(), fixture.decision()));

          awaitBindingLockWait(testSchema.dsl(), applicationName);
          allowRuntimeMutation.countDown();

          assertThat(runtimeMutation.get(10, TimeUnit.SECONDS)).isEqualTo(1);
          assertThat(decisionAcquisition.get(10, TimeUnit.SECONDS).status())
              .isEqualTo(
                  net.firedevops.firemud.gamesession.binding
                      .CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL);
          assertThat(
                  Objects.requireNonNull(
                          testSchema
                              .dsl()
                              .fetchOne(
                                  "SELECT row_version FROM game_instances WHERE tenant_id = ? AND id = ?",
                                  target.gameSessionTenantId(),
                                  target.gameInstanceId()))
                      .get("row_version", Long.class))
              .isEqualTo(initialRowVersion + 1);

          CountDownLatch projectionRuntimeRowLocked = new CountDownLatch(1);
          CountDownLatch allowFencedRuntimeMutation = new CountDownLatch(1);
          Future<Integer> fencedRuntimeMutation =
              executor.submit(
                  () ->
                      testSchema
                          .dsl()
                          .transactionResult(
                              configuration -> {
                                DSLContext transaction = DSL.using(configuration);
                                var runtime =
                                    transaction.fetchOne(
                                        "SELECT id FROM game_instances"
                                            + " WHERE tenant_id = ? AND id = ? FOR UPDATE",
                                        target.gameSessionTenantId(),
                                        target.gameInstanceId());
                                if (runtime == null) {
                                  throw new IllegalStateException("Expected current runtime row");
                                }
                                projectionRuntimeRowLocked.countDown();
                                awaitLatch(allowFencedRuntimeMutation);
                                return transaction.execute(
                                    "UPDATE game_instances SET status = 'STOPPED',"
                                        + " row_version = row_version + 1"
                                        + " WHERE tenant_id = ? AND id = ?",
                                    target.gameSessionTenantId(),
                                    target.gameInstanceId());
                              }));
          Future<?> accountIndexReadback;
          try {
            assertThat(projectionRuntimeRowLocked.await(5, TimeUnit.SECONDS)).isTrue();
            CanonicalGameplayBindingProvisionalCasEvidence provisionalEvidence =
                fixture.repository().readProvisionalCasEvidence(TRANSITION_ID);
            String member =
                CanonicalGameplayAccountIndexMember.of(provisionalEvidence.candidate()).value();
            accountIndexReadback =
                executor.submit(
                    () ->
                        fixture
                            .repository()
                            .markCandidateAccountIndexPresent(provisionalEvidence, member));
            awaitBindingLockWait(testSchema.dsl(), applicationName);
          } finally {
            allowFencedRuntimeMutation.countDown();
          }

          assertThatThrownBy(() -> fencedRuntimeMutation.get(10, TimeUnit.SECONDS))
              .hasStackTraceContaining("runtime row is fenced");
          assertThat(accountIndexReadback.get(10, TimeUnit.SECONDS)).isNotNull();
          assertThat(
                  Objects.requireNonNull(
                          testSchema
                              .dsl()
                              .fetchOne(
                                  "SELECT row_version FROM game_instances WHERE tenant_id = ? AND id = ?",
                                  target.gameSessionTenantId(),
                                  target.gameInstanceId()))
                      .get("row_version", Long.class))
              .isEqualTo(initialRowVersion + 1);
        } finally {
          allowRuntimeMutation.countDown();
        }
      }
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void unresolvedPreparedTransitionFencesASecondTransitionForTheSameController() {
    String schema = "gs_binding_pending_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = dslForSchema(dataSource, schema);
      CanonicalGameplayBindingInventoryRepository repository =
          new CanonicalGameplayBindingInventoryRepository(dsl);
      CanonicalGameplayBindingIdentity prior = identity(sourceSession());
      seedActiveSource(dsl, prior);
      repository.prepare(transferRequest(candidateSession(1), prior));

      CanonicalGameplayBindingTransitionRequest otherOperation =
          new CanonicalGameplayBindingTransitionRequest(
              UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
              UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
              identity(candidateSession(2)),
              new CanonicalIssuerReservationFenceEvidence(
                  UUID.fromString("abababab-abab-4bab-8bab-abababababab"),
                  UUID.fromString("acacacac-acac-4cac-8cac-acacacacacac"),
                  ISSUER_COVERAGE_OPERATION_ID,
                  BigInteger.valueOf(13),
                  BigInteger.valueOf(17),
                  BigInteger.valueOf(41)),
              prior.bindingRef(),
              BigInteger.valueOf(5));
      assertThatThrownBy(() -> repository.prepare(otherOperation))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessage(
              "An unresolved candidate or repair obligation already fences this controller");
      assertThat(repository.readSnapshot().inventoryRevision()).isEqualTo(BigInteger.valueOf(9));
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void advancesGenerationAtomicallyAndKeepsPriorControllerActiveUntilExternalCas() {
    String schema = "gs_binding_generation_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = dslForSchema(dataSource, schema);
      CanonicalGameplayBindingIdentity prior = identity(sourceSession());
      seedActiveSource(dsl, prior);

      var prepared =
          new CanonicalGameplayBindingInventoryRepository(dsl)
              .prepare(
                  new CanonicalGameplayBindingTransitionRequest(
                      TRANSITION_ID,
                      ACCOUNT_INDEX_FENCE,
                      identity(candidateSession(2)),
                      reservation(RESERVATION_FENCE),
                      prior.bindingRef(),
                      BigInteger.valueOf(5)));

      CanonicalGameplayBindingTransitionRequest changedRetry =
          new CanonicalGameplayBindingTransitionRequest(
              TRANSITION_ID,
              UUID.fromString("abababab-abab-4bab-8bab-abababababab"),
              identity(candidateSession(2)),
              reservation(RESERVATION_FENCE),
              prior.bindingRef(),
              BigInteger.valueOf(5));
      assertThatThrownBy(
              () -> new CanonicalGameplayBindingInventoryRepository(dsl).prepare(changedRetry))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThat(
              new CanonicalGameplayBindingInventoryRepository(dsl)
                  .readSnapshot()
                  .inventoryRevision())
          .isEqualTo(BigInteger.valueOf(9));

      assertThat(prepared.bindingGeneration()).isEqualTo(BigInteger.valueOf(6));
      assertThat(prepared.inventoryRevision()).isEqualTo(BigInteger.valueOf(9));
      var snapshot = new CanonicalGameplayBindingInventoryRepository(dsl).readSnapshot();
      assertThat(snapshot.bindings()).hasSize(2);
      assertThat(snapshot.bindings())
          .allSatisfy(row -> assertThat(row.identity().issuerId()).isEqualTo(ISSUER_ID));
      assertThat(snapshot.bindings())
          .anySatisfy(
              row ->
                  assertThat(row.lifecycle())
                      .isEqualTo(
                          net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventoryEntry.Lifecycle.ACTIVE));
      assertThat(snapshot.bindings())
          .anySatisfy(
              row ->
                  assertThat(row.lifecycle())
                      .isEqualTo(
                          net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventoryEntry.Lifecycle
                              .CANDIDATE_PREPARED));
      assertThat(snapshot.accountIndexObligations()).hasSize(2);
      assertThat(snapshot.accountIndexObligations().get(0).expectedPriorGeneration())
          .isEqualTo(BigInteger.valueOf(5));
      assertThat(snapshot.accountIndexObligations().get(1).executionPhase())
          .isEqualTo(
              net.firedevops.firemud.gamesession.binding
                  .CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.AFTER_FINAL_CAS);
    } finally {
      dropSchema(schema);
    }
  }

  private static CanonicalGameplayBindingTransitionRequest request(String sessionId) {
    return new CanonicalGameplayBindingTransitionRequest(
        TRANSITION_ID,
        ACCOUNT_INDEX_FENCE,
        identity(sessionId),
        reservation(RESERVATION_FENCE),
        null,
        null);
  }

  private static CanonicalGameplayBindingTransitionRequest request(
      String sessionId, CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget runtimeTarget) {
    return new CanonicalGameplayBindingTransitionRequest(
        TRANSITION_ID,
        ACCOUNT_INDEX_FENCE,
        identity(sessionId, runtimeTarget),
        reservation(RESERVATION_FENCE),
        null,
        null);
  }

  private static LeaseBoundFirstBinding prepareLeaseBoundFirstBinding(
      DriverManagerDataSource dataSource, DSLContext dsl) {
    PreparedLeaseBoundFirstBinding prepared =
        prepareLeaseBoundFirstBindingDecision(dataSource, dsl);
    prepared.repository().markProvisional(prepared.evidence(), prepared.decision());
    return new LeaseBoundFirstBinding(
        prepared.repository(),
        prepared.leaseEvidence(),
        prepared.repository().readProvisionalCasEvidence(TRANSITION_ID));
  }

  private static PreparedLeaseBoundFirstBinding prepareLeaseBoundFirstBindingDecision(
      DriverManagerDataSource dataSource, DSLContext dsl) {
    var runtimeTarget =
        CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource, TENANT_ID);
    // This synthetic disposition opens the repository branch for proof; it is not real migration
    // or bootstrap authorization.
    seedSyntheticVerifiedLegacyDisposition(dsl);
    CanonicalGameplayBindingInventoryRepository repository =
        new CanonicalGameplayBindingInventoryRepository(dsl);
    CanonicalGameplayBindingTransitionRequest request = request(candidateSession(1), runtimeTarget);
    repository.prepare(request);
    CanonicalGameplayBindingProvisionalCasEvidence preparedEvidence =
        repository.readProvisionalCasEvidence(TRANSITION_ID);
    var candidate = preparedEvidence.candidate().identity();
    CanonicalPlayableTarget target = runtimeTarget.playableTarget();
    AccountGameplayAdmissionLeaseEvidence leaseEvidence =
        leaseEvidence(candidate, preparedEvidence.transition().bindingGeneration(), target);
    CanonicalGameplayAdmissionDecision decision =
        new CanonicalGameplayAdmissionDecision(
            TRANSITION_ID,
            leaseEvidence,
            candidate,
            preparedEvidence.transition().bindingGeneration(),
            null,
            target);
    return new PreparedLeaseBoundFirstBinding(
        repository, leaseEvidence, preparedEvidence, decision, runtimeTarget);
  }

  private static AccountGameplayAdmissionLeaseEvidence leaseEvidence(
      CanonicalGameplayBindingIdentity candidate,
      BigInteger bindingGeneration,
      CanonicalPlayableTarget target) {
    long now = System.currentTimeMillis();
    String nowSeconds = Long.toString(now / 1000L);
    Map<String, Object> carrier = new LinkedHashMap<>();
    carrier.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    carrier.put("schemaVersion", "1");
    carrier.put("mode", "PUBLIC_PRODUCTION");
    carrier.put("leaseKind", AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING.name());
    carrier.put("targetNamespace", target.targetNamespace());
    carrier.put(
        "callerWorkload",
        "spiffe://firemud/ns/" + target.targetNamespace() + "/sa/game-session-service");
    carrier.put("requestId", "10101010-1010-4010-8010-101010101010");
    carrier.put("leaseId", "20202020-2020-4020-8020-202020202020");
    carrier.put("leaseFence", "1");

    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accountId", candidate.accountId().toString());
    scope.put("tenantId", candidate.tenantId().toString());
    scope.put("realmId", target.realmId().toString());
    scope.put("worldSlug", target.worldSlug());
    scope.put("realmSlug", target.realmSlug());
    scope.put("playableStateNamespaceId", target.playableStateNamespaceId().toString());
    scope.put("playableStateScope", target.playableStateScope());
    scope.put("gameInstanceId", target.canonicalGameInstanceId().toString());
    scope.put("characterId", candidate.characterId().toString());
    scope.put("sessionId", candidate.sessionId());
    scope.put("bindingGeneration", bindingGeneration.toString());
    scope.put("catalogRevision", Long.toString(target.catalogRevision()));
    scope.put("pointerVersion", Long.toString(target.pointerVersion()));
    scope.put("regionId", candidate.regionId().toString());
    scope.put("regionEpoch", candidate.regionEpoch().toString());
    carrier.put("bindingScope", scope);

    Map<String, Object> authority = new LinkedHashMap<>();
    authority.put("issuerAuthGeneration", candidate.issuerAuthGeneration().toString());
    authority.put("accountAuthorityGeneration", "1");
    authority.put("tenantAuthorityGeneration", Map.of(candidate.tenantId().toString(), "1"));
    authority.put("membershipAuthorityGeneration", Map.of(candidate.tenantId().toString(), "1"));
    authority.put("privateRealmGrantVersions", List.of());
    carrier.put("authorityTuple", authority);
    carrier.put("issuanceFence", "1");

    Map<String, Object> membershipVersions =
        new LinkedHashMap<>(Map.of(candidate.tenantId().toString(), "1"));
    carrier.put(
        "membershipBaseline",
        new LinkedHashMap<>(
            Map.of(
                "membershipLifecycleState",
                "ACTIVE",
                "membershipVersion",
                membershipVersions,
                "membershipAuthorityGeneration",
                "1")));

    String prefix = "account:auth-authority:v1:";
    List<Map<String, Object>> checkpoints = new ArrayList<>();
    for (String stream :
        List.of(
            "account/" + candidate.accountId(),
            "issuer/firemud-account-service",
            "membership/" + candidate.accountId() + "/" + candidate.tenantId(),
            "tenant/" + candidate.tenantId())) {
      checkpoints.add(
          new LinkedHashMap<>(
              Map.of(
                  "outboxStreamKey",
                  prefix + stream,
                  "outboxSequence",
                  stream.startsWith("membership/") || stream.startsWith("tenant/") ? "1" : "0")));
    }
    carrier.put("outboxCheckpoints", checkpoints);

    Map<String, Object> tokenIdentity = new LinkedHashMap<>();
    tokenIdentity.put("accountId", candidate.accountId().toString());
    tokenIdentity.put("operationId", "40404040-4040-4040-8040-404040404040");
    tokenIdentity.put("issuanceRequestId", "50505050-5050-4050-8050-505050505050");
    tokenIdentity.put("tokenJti", "60606060-6060-4060-8060-606060606060");
    tokenIdentity.put("tokenSHA256", "a".repeat(64));
    tokenIdentity.put("tokenProfile", "game-session-account-delegation");
    tokenIdentity.put("tokenGeneration", "1");
    tokenIdentity.put("issuanceFence", "1");
    tokenIdentity.put("tokenIdentityFence", "1");
    tokenIdentity.put("issuedAt", nowSeconds);
    tokenIdentity.put("notBefore", nowSeconds);
    tokenIdentity.put("expiresAt", Long.toString(now / 1000L + 60L));
    carrier.put("tokenIdentityEvidence", tokenIdentity);
    carrier.put("evaluatedAt", Long.toString(now));
    carrier.put(
        "expiresAt",
        Long.toString(now + AccountGameplayAdmissionLeaseEvidence.MAX_DURATION_MILLIS));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  private static TestSchema newTestSchema(String prefix) {
    return newTestSchema(prefix, null);
  }

  private static TestSchema newTestSchema(String prefix, String applicationName) {
    String schema = prefix + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema, applicationName);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    return new TestSchema(schema, dataSource, dslForSchema(dataSource, schema));
  }

  private static RedisMemberFixture startMemberRepairRedisFixture() {
    RedisURI bootstrapUri =
        RedisURI.Builder.redis(redis.getHost(), redis.getMappedPort(6379)).build();
    bootstrapUri.setTimeout(Duration.ofSeconds(5));
    RedisClient bootstrapClient = RedisClient.create(bootstrapUri);
    try {
      try (StatefulRedisConnection<byte[], byte[]> connection =
          bootstrapClient.connect(ByteArrayCodec.INSTANCE)) {
        connection
            .sync()
            .aclSetuser(
                MEMBER_REPAIR_PRINCIPAL,
                new AclSetuserArgs()
                    .reset()
                    .on()
                    .addPassword(MEMBER_REPAIR_PASSWORD)
                    .keyPattern(MEMBER_REPAIR_ACCOUNT_KEY_PREFIX + "*")
                    .addCommand(CommandType.HELLO)
                    .addCommand(CommandType.PING)
                    .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
                    .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
                    .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
                    .addCommand(CommandType.EVALSHA)
                    .addCommand(CommandType.SADD)
                    .addCommand(CommandType.TYPE)
                    .addCommand(CommandType.PTTL)
                    .addCommand(CommandType.SISMEMBER));
      }
    } catch (RuntimeException failure) {
      bootstrapClient.shutdown();
      throw failure;
    }

    RedisURI applicationUri =
        RedisURI.Builder.redis(redis.getHost(), redis.getMappedPort(6379))
            .withAuthentication(MEMBER_REPAIR_PRINCIPAL, MEMBER_REPAIR_PASSWORD)
            .build();
    applicationUri.setTimeout(Duration.ofSeconds(5));
    RedisClient applicationClient = RedisClient.create(applicationUri);
    applicationClient.setOptions(
        ClientOptions.builder()
            .autoReconnect(false)
            .protocolVersion(ProtocolVersion.RESP2)
            .build());
    return new RedisMemberFixture(bootstrapClient, applicationClient);
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for the lock-order test gate");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for the lock-order test gate", interrupted);
    }
  }

  private static void awaitBindingLockWait(DSLContext observer, String applicationName)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      Boolean waiting =
          Objects.requireNonNull(
                  observer.fetchOne(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity"
                          + " WHERE pid <> pg_backend_pid() AND state = 'active'"
                          + " AND application_name = ?"
                          + " AND wait_event_type = 'Lock'"
                          + " AND (query ILIKE '%game_instances%FOR UPDATE%'"
                          + " OR query ILIKE '%UPDATE game_session_canonical_binding_transition%'))",
                      applicationName))
              .get(0, Boolean.class);
      if (Boolean.TRUE.equals(waiting)) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Lease-bound decision did not wait on the held runtime row");
  }

  private record LeaseBoundFirstBinding(
      CanonicalGameplayBindingInventoryRepository repository,
      AccountGameplayAdmissionLeaseEvidence leaseEvidence,
      CanonicalGameplayBindingProvisionalCasEvidence evidence) {}

  private record PreparedLeaseBoundFirstBinding(
      CanonicalGameplayBindingInventoryRepository repository,
      AccountGameplayAdmissionLeaseEvidence leaseEvidence,
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      CanonicalGameplayAdmissionDecision decision,
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget runtimeTarget) {}

  private record TestSchema(String schema, DriverManagerDataSource dataSource, DSLContext dsl) {}

  private record RedisMemberFixture(RedisClient bootstrapClient, RedisClient applicationClient) {
    void deleteAccountKey(UUID accountId) {
      String key = MEMBER_REPAIR_ACCOUNT_KEY_PREFIX + accountId;
      try (StatefulRedisConnection<String, String> connection =
          bootstrapClient.connect(io.lettuce.core.codec.StringCodec.UTF8)) {
        connection.sync().del(key);
      }
    }

    void close() {
      applicationClient.shutdown();
      bootstrapClient.shutdown();
    }
  }

  private static CanonicalGameplayBindingTransitionRequest transferRequest(
      String candidateSessionId, CanonicalGameplayBindingIdentity prior) {
    return new CanonicalGameplayBindingTransitionRequest(
        TRANSITION_ID,
        ACCOUNT_INDEX_FENCE,
        identity(candidateSessionId),
        reservation(RESERVATION_FENCE),
        prior.bindingRef(),
        BigInteger.valueOf(5));
  }

  static CanonicalGameplayBindingIdentity identity(String sessionId) {
    return identity(sessionId, TENANT_ID, NAMESPACE_ID, INSTANCE_ID, RUNTIME_GAME_INSTANCE_ID);
  }

  private static CanonicalGameplayBindingIdentity identity(
      String sessionId, CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget runtimeTarget) {
    return identity(
        sessionId,
        runtimeTarget.canonicalTenantId(),
        runtimeTarget.playableStateNamespaceId(),
        runtimeTarget.gameInstanceUuid(),
        runtimeTarget.gameInstanceId());
  }

  private static CanonicalGameplayBindingIdentity identity(
      String sessionId,
      UUID canonicalTenantId,
      UUID playableStateNamespaceId,
      UUID gameInstanceUuid,
      long runtimeGameInstanceId) {
    return new CanonicalGameplayBindingIdentity(
        ACCOUNT_ID,
        canonicalTenantId,
        playableStateNamespaceId,
        CHARACTER_ID,
        "SHARED",
        gameInstanceUuid,
        runtimeGameInstanceId,
        sessionId,
        REGION_ID,
        BigInteger.valueOf(12),
        ISSUER_ID,
        BigInteger.valueOf(7),
        BigInteger.valueOf(3),
        BigInteger.valueOf(16),
        BigInteger.valueOf(32));
  }

  private static String sourceSession() {
    return "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  }

  private static String candidateSession(int number) {
    return number == 1
        ? "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
        : "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2";
  }

  static void seedActiveSource(DSLContext dsl, CanonicalGameplayBindingIdentity prior) {
    byte[] priorRef = prior.bindingRef().bytes();
    BigInteger priorPartition = prior.bindingRef().partitionId(prior.issuerIndexPartitionCount());
    UUID priorAccountFence = UUID.fromString("12121212-1212-4212-8212-121212121212");
    dsl.execute(
        "UPDATE game_session_canonical_binding_inventory_clock SET inventory_revision = 8"
            + " WHERE singleton_id = 1");
    dsl.execute(
        "INSERT INTO game_session_canonical_binding_generation"
            + " (tenant_id, playable_state_namespace_id, character_id, last_issued_generation)"
            + " VALUES (?, ?, ?, 5)",
        TENANT_ID,
        NAMESPACE_ID,
        CHARACTER_ID);
    dsl.execute(
        "INSERT INTO game_session_canonical_gameplay_binding_inventory"
            + " (binding_ref, account_id, tenant_id, playable_state_namespace_id, character_id,"
            + " playable_state_scope, game_instance_id, runtime_game_instance_id, session_id,"
            + " binding_generation, region_id, region_epoch, issuer_id, issuer_auth_generation,"
            + " issuer_index_layout_version, issuer_index_partition_count,"
            + " issuer_index_partition_capacity, issuer_partition_id, account_index_fence,"
            + " issuer_reservation_id, transition_id, lifecycle, account_index_state,"
            + " issuer_index_state, inventory_revision)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 5, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " 'ACTIVE', 'ACKNOWLEDGED', 'ACKNOWLEDGED', 8)",
        priorRef,
        prior.accountId(),
        prior.tenantId(),
        prior.playableStateNamespaceId(),
        prior.characterId(),
        prior.playableStateScope(),
        prior.gameInstanceId(),
        prior.runtimeGameInstanceId(),
        prior.sessionId(),
        prior.regionId(),
        BigInteger.valueOf(12),
        prior.issuerId(),
        BigInteger.valueOf(7),
        BigInteger.valueOf(3),
        BigInteger.valueOf(16),
        BigInteger.valueOf(32),
        priorPartition,
        priorAccountFence,
        PRIOR_RESERVATION_ID,
        PRIOR_TRANSITION_ID);
    dsl.execute(
        "INSERT INTO game_session_canonical_issuer_partition_reservation"
            + " (reservation_id, binding_ref, account_id, tenant_id, game_instance_id,"
            + " runtime_game_instance_id, session_id, binding_generation, transition_id, issuer_id,"
            + " issuer_auth_generation, playable_state_namespace_id, character_id,"
            + " playable_state_scope, region_id, region_epoch, partition_id,"
            + " issuer_index_layout_version, issuer_index_partition_count,"
            + " issuer_index_partition_capacity, lifecycle, reservation_fence,"
            + " issuer_coverage_operation_id, issuer_coverage_operation_fence, coverage_fence,"
            + " inventory_snapshot_revision, inventory_revision)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, 5, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BOUND', ?, ?, 1, 1, 7, 8)",
        PRIOR_RESERVATION_ID,
        priorRef,
        prior.accountId(),
        prior.tenantId(),
        prior.gameInstanceId(),
        prior.runtimeGameInstanceId(),
        prior.sessionId(),
        PRIOR_TRANSITION_ID,
        prior.issuerId(),
        BigInteger.valueOf(7),
        prior.playableStateNamespaceId(),
        prior.characterId(),
        prior.playableStateScope(),
        prior.regionId(),
        BigInteger.valueOf(12),
        priorPartition,
        BigInteger.valueOf(3),
        BigInteger.valueOf(16),
        BigInteger.valueOf(32),
        PRIOR_RESERVATION_FENCE,
        ISSUER_COVERAGE_OPERATION_ID);
    dsl.execute(
        "INSERT INTO game_session_canonical_binding_transition"
            + " (transition_id, tenant_id, playable_state_namespace_id, character_id,"
            + " expected_prior_binding_ref, expected_prior_binding_generation, candidate_binding_ref,"
            + " candidate_binding_generation, candidate_account_index_fence, issuer_reservation_id,"
            + " status, inventory_revision)"
            + " VALUES (?, ?, ?, ?, NULL, NULL, ?, 5, ?, ?, 'COMMITTED', 8)",
        PRIOR_TRANSITION_ID,
        TENANT_ID,
        NAMESPACE_ID,
        CHARACTER_ID,
        priorRef,
        priorAccountFence,
        PRIOR_RESERVATION_ID);
  }

  /**
   * Synthetic storage fixture for branch mechanics only; it is not evidence that the real legacy
   * index was fenced, inventoried, rebuilt, or read back.
   */
  static void seedSyntheticVerifiedLegacyDisposition(DSLContext dsl) {
    dsl.execute(
        "UPDATE game_session_canonical_binding_inventory_clock"
            + " SET inventory_revision = 1 WHERE singleton_id = 1");
    dsl.execute(
        "UPDATE game_session_canonical_binding_legacy_disposition"
            + " SET disposition_state = 'VERIFIED',"
            + " cohort_id = '10101010-1010-4010-8010-101010101010',"
            + " owner_operation_id = '20202020-2020-4020-8020-202020202020',"
            + " legacy_writer_fence = '30303030-3030-4030-8030-303030303030',"
            + " source_snapshot_revision = 1, canonical_inventory_revision = 1,"
            + " namespace_index_readback_revision = 1,"
            + " evidence_digest = 'sha256:"
            + "0000000000000000000000000000000000000000000000000000000000000000'"
            + " WHERE singleton_id = 1");
  }

  private static void assertCheckConstraintViolation(
      DSLContext dsl, String statement, Object... values) {
    assertThatThrownBy(() -> dsl.execute(statement, values))
        .isInstanceOfSatisfying(
            org.jooq.exception.DataAccessException.class,
            failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
  }

  private static CanonicalIssuerReservationFenceEvidence reservation(UUID reservationFence) {
    return new CanonicalIssuerReservationFenceEvidence(
        RESERVATION_ID,
        reservationFence,
        ISSUER_COVERAGE_OPERATION_ID,
        BigInteger.valueOf(13),
        BigInteger.valueOf(17),
        BigInteger.valueOf(41));
  }

  private static DriverManagerDataSource dataSource(String schema) {
    return dataSource(schema, null);
  }

  private static DriverManagerDataSource dataSource(String schema, String applicationName) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    // Testcontainers already appends loggerLevel=OFF to the JDBC URL, so add currentSchema with
    // '&' to keep both parameters and bind later repository connections to Flyway's schema.
    String jdbcUrl = postgres.getJdbcUrl();
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    String applicationNameParameter =
        applicationName == null ? "" : "&ApplicationName=" + applicationName;
    dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema + applicationNameParameter);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static DSLContext dslForSchema(DriverManagerDataSource dataSource, String schema) {
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    var schemaReadback = dsl.fetchOne("SELECT current_schema() AS actual_schema");
    assertThat(schemaReadback.get("actual_schema", String.class)).isEqualTo(schema);
    var clockReadback =
        dsl.fetchOne(
            "SELECT to_regclass('game_session_canonical_binding_inventory_clock')::text"
                + " AS relation_name");
    assertThat(clockReadback.get("relation_name", String.class))
        .isEqualTo("game_session_canonical_binding_inventory_clock");
    return dsl;
  }

  private static void dropSchema(String schema) {
    try (var connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to dispose canonical binding test schema", failure);
    }
  }
}
