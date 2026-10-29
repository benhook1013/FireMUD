package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.protocol.CommandType;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionNeverAdmittedGrpcCodec.AbortEvidence;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Native PostgreSQL trigger proof with actual issuer Redis release/retirement. Account lease,
 * definitive abort, source and incarnation qualifications remain controlled fixture inputs. The
 * malformed mutation is transaction-local fault injection, not a supported application write.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@Execution(ExecutionMode.SAME_THREAD)
class CanonicalGameplayIssuerRetirementMutationLineageIntegrationTest
    extends CanonicalGameplayIssuerLifecycleProofFixtures {
  private static final String RETIREMENT = "game_session_canonical_issuer_binding_local_retirement";
  private static final String MUTATION = "game_session_canonical_issuer_partition_mutation";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @Override
  protected PostgreSQLContainer<?> postgres() {
    return POSTGRES;
  }

  @Override
  protected GenericContainer<?> redis() {
    return REDIS;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsWrongBootstrapAtPreparationAndCommitAndRetainsGenuineRetirement(
      boolean installedBootstrap) throws Exception {
    try (var fixture = sourceFixture(installedBootstrap)) {
      if (installedBootstrap) {
        recordInstalledNegativeTerminal(fixture);
      } else {
        fixture.publishOriginalWithControlledAccountLease();
        assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
      }
      var releaseRepository =
          new CanonicalGameplayIssuerBindingLocalReleaseOperationRepository(
              fixture.dsl(), fixture.currentIssuerSource());
      var release = fixture.prepareIssuerRelease();
      var releaseReadback = fixture.projectIssuerRelease(release);
      release = releaseRepository.completeWithReadback(release, releaseReadback);
      assertThat(release.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);
      assertThat(release.originalSource().bootstrapMemberOperationId() != null)
          .isEqualTo(installedBootstrap);

      var pending = fixture.prepareIssuerRetirement(fixture.transitionId());
      UUID wrongBootstrapId = UUID.randomUUID();
      UUID realBootstrapId =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT bootstrap_operation_id FROM " + MUTATION + " WHERE mutation_id = ?",
                  pending.physicalMutation().mutationId())
              .get(0, UUID.class);
      assertThat(wrongBootstrapId).isNotEqualTo(realBootstrapId);
      String pendingRow =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT to_jsonb(retirement)::text FROM "
                      + RETIREMENT
                      + " retirement WHERE retirement_operation_id = ?",
                  pending.operationId())
              .get(0, String.class);
      Map<String, List<String>> retainedBefore = retainedRows(fixture.dsl());
      byte[] physicalBefore = physicalFieldSet(fixture.issuerPartitionKey());

      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .transaction(
                          configuration -> {
                            DSLContext transaction = DSL.using(configuration);
                            // Only manufacture the malformed preimage as the disposable PG
                            // superuser. Restore normal triggers before the real INSERT. The
                            // expected refusal rolls back this deletion and lineage corruption.
                            transaction.execute("SET LOCAL session_replication_role = replica");
                            assertThat(
                                    transaction.execute(
                                        "DELETE FROM "
                                            + RETIREMENT
                                            + " WHERE retirement_operation_id = ?",
                                        pending.operationId()))
                                .isOne();
                            corruptMutationLineage(transaction, pending, wrongBootstrapId);
                            transaction.execute("SET LOCAL session_replication_role = origin");
                            transaction.execute(
                                "INSERT INTO "
                                    + RETIREMENT
                                    + " SELECT (jsonb_populate_record(NULL::"
                                    + RETIREMENT
                                    + ", ?::jsonb)).*",
                                pendingRow);
                          }))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> assertThat(failure.getSQLState()).isEqualTo("23514"))
          .hasMessageContaining("Issuer retirement lacks its exact pending physical mutation");
      assertThat(retainedRows(fixture.dsl())).isEqualTo(retainedBefore);
      assertThat(physicalFieldSet(fixture.issuerPartitionKey())).isEqualTo(physicalBefore);
      assertNormalTriggerMode(fixture.dsl());

      // Execute the genuine Lua once, modeling loss before SQL completion, and read the actual
      // successor revision/absence. SQL completion below must still require exact lineage.
      assertThat(evaluate(retirementInvocation(fixture, pending))).isEqualTo(1L);
      byte[] retiredPhysical = physicalFieldSet(fixture.issuerPartitionKey());
      assertThat(retiredPhysical).isNotEqualTo(physicalBefore);
      Map<String, List<String>> beforeCommitRefusal = retainedRows(fixture.dsl());
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .transaction(
                          configuration -> {
                            DSLContext transaction = DSL.using(configuration);
                            CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(
                                transaction);
                            CanonicalGameplayIssuerPartitionMutationRepository
                                .completeInTransaction(
                                    transaction, pending.physicalMutation(), retiredPhysical);
                            // All committed proof bytes and revisions are genuine. Corrupt only
                            // bootstrap lineage, then restore all guards for the real UPDATE.
                            transaction.execute("SET LOCAL session_replication_role = replica");
                            corruptMutationLineage(transaction, pending, wrongBootstrapId);
                            transaction.execute("SET LOCAL session_replication_role = origin");
                            transaction.execute(
                                "UPDATE "
                                    + RETIREMENT
                                    + " SET status = 'RETIRED',"
                                    + " exact_absence_readback_field_set_bytes = ?,"
                                    + " exact_absence_readback_field_set_digest = ?,"
                                    + " completed_at = CURRENT_TIMESTAMP"
                                    + " WHERE retirement_operation_id = ?",
                                retiredPhysical,
                                CanonicalGameplayIssuerMemberV2Codec.sha256(retiredPhysical),
                                pending.operationId());
                          }))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> assertThat(failure.getSQLState()).isEqualTo("P0001"))
          .hasMessageContaining(
              "Issuer retirement needs its exact committed physical absence proof");
      assertThat(retainedRows(fixture.dsl())).isEqualTo(beforeCommitRefusal);
      assertThat(physicalFieldSet(fixture.issuerPartitionKey())).isEqualTo(retiredPhysical);
      assertNormalTriggerMode(fixture.dsl());

      // Recovery consumes the same real physical successor and original pending SQL intent.
      assertThat(fixture.retireIssuerCapacity(pending).scriptOutcome()).isEqualTo(2L);
      var completed = fixture.prepareIssuerRetirement(fixture.transitionId());
      assertThat(completed.operationId()).isEqualTo(pending.operationId());
      assertThat(completed.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.RETIRED);
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT bootstrap_operation_id FROM " + MUTATION + " WHERE mutation_id = ?",
                      completed.physicalMutation().mutationId())
                  .get(0, UUID.class))
          .isEqualTo(realBootstrapId);
      assertThat(completed.exactAbsenceReadbackFieldSetBytes()).isEqualTo(retiredPhysical);
      assertThat(physicalFieldSet(fixture.issuerPartitionKey())).isEqualTo(retiredPhysical);
      var retainedAfter = retainedRows(fixture.dsl());
      assertThat(fixture.retireIssuerCapacity(completed).scriptOutcome()).isEqualTo(2L);
      assertThat(retainedRows(fixture.dsl())).isEqualTo(retainedAfter);
      assertThat(physicalFieldSet(fixture.issuerPartitionKey())).isEqualTo(retiredPhysical);
    }
  }

  @Test
  void forwardMigrationPreservesPopulatedBootstrapRowsAndAddsBothLineagePredicates() {
    try (var fixture =
        newFixture(
            controlledCandidate(BigInteger.valueOf(103), BigInteger.ONE),
            1,
            MigrationVersion.fromVersion("1050.4"),
            this::upgradePopulatedBootstrapRetirementLineage)) {
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_canonical_issuer_bootstrap_member_operation")
                  .get(0, Long.class))
          .isOne();
    }
  }

  private void upgradePopulatedBootstrapRetirementLineage(
      DriverManagerDataSource dataSource, CanonicalGameplayIssuerLayoutBootstrapIntent intent) {
    DSLContext parent = DSL.using(dataSource, SQLDialect.POSTGRES);
    var member =
        CanonicalGameplayIssuerMemberV2Codec.decode(
            parent
                .fetchSingle(
                    "SELECT member_value_bytes FROM game_session_canonical_issuer_bootstrap_member_operation"
                        + " WHERE bootstrap_operation_id = ?",
                    intent.operationId())
                .get(0, byte[].class));
    byte[] partitionKey =
        CanonicalGameplayIssuerRedisKeys.forMember(member).getBytes(StandardCharsets.US_ASCII);
    byte[] physicalBefore = physicalFieldSet(partitionKey);
    assertThat(decodeFields(physicalBefore)).isNotEmpty();
    assertThat(parent.fetchSingle("SELECT count(*) FROM " + RETIREMENT).get(0, Long.class))
        .isZero();
    // This upgrade case has genuine installed bootstrap data, rather than old retirement rows.
    // The two separate source cases above exercise actual pending and committed retirement.
    Map<String, List<String>> rowsBefore = retainedRows(parent, true);
    List<String> historyBefore = rowsBefore.remove("flyway_schema_history");
    assertThat(historyBefore).isNotEmpty();
    String predicate =
        "AND mutation.bootstrap_operation_id = NEW.anchor_issuer_coverage_operation_id";
    assertThat(retirementGuardDefinition(parent)).doesNotContain(predicate);

    assertThat(
            CanonicalGameplayAdmissionAbortCompositionFixture
                .upgradeWithDefaultGameSessionMigrations(
                    MigrationVersion.fromVersion("1050.5"), dataSource))
        .isOne();

    Map<String, List<String>> rowsAfter = retainedRows(parent, true);
    List<String> historyAfter = rowsAfter.remove("flyway_schema_history");
    assertThat(rowsAfter).isEqualTo(rowsBefore);
    // Full prior history rows include original checksums and xmin, proving no checksum repair
    // or row rewrite; Flyway appends exactly the one forward migration.
    assertThat(historyAfter).containsAll(historyBefore).hasSize(historyBefore.size() + 1);
    assertThat(physicalFieldSet(partitionKey)).isEqualTo(physicalBefore);
    String definition = retirementGuardDefinition(parent);
    assertThat(definition.substring(0, definition.indexOf("IF TG_OP = 'DELETE'")))
        .containsOnlyOnce(predicate);
    assertThat(definition.substring(definition.indexOf("IF OLD.status")))
        .containsOnlyOnce(predicate);
  }

  private static String retirementGuardDefinition(DSLContext dsl) {
    return dsl.fetchSingle(
            "SELECT pg_get_functiondef('guard_canonical_issuer_binding_local_retirement()'::regprocedure)")
        .get(0, String.class);
  }

  private CanonicalGameplayAdmissionAbortCompositionFixture sourceFixture(
      boolean installedBootstrap) {
    var dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = POSTGRES.getJdbcUrl();
    dataSource.setUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=public");
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    administratorCommands.aclSetuser(
        OWNER_PRINCIPAL,
        new AclSetuserArgs()
            .keyPattern("session:game:{*")
            .keyPattern("session:game:index:character:{*")
            .keyPattern("session:game:index:account-tenant:{*")
            .keyPattern("session:game:index:tenant:{*")
            .addCommand(CommandType.GET)
            .addCommand(CommandType.SET)
            .addCommand(CommandType.SISMEMBER));
    // Each parameter owns a distinct physical layout while sharing the disposable Redis server.
    return new CanonicalGameplayAdmissionAbortCompositionFixture(
        dataSource,
        controlledCandidate(BigInteger.valueOf(installedBootstrap ? 102 : 101), BigInteger.ONE),
        issuerOwner,
        installedBootstrap);
  }

  private static void recordInstalledNegativeTerminal(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture) {
    var lease = fixture.originalPreparedDecisionWithControlledAccountLease().leaseEvidence();
    fixture.claimOriginalNewCandidateWithoutDispatch(lease);
    var account = mock(AccountGameplayAdmissionLeaseClient.class);
    UUID cleanupId = UUID.randomUUID();
    UUID resolutionId = UUID.randomUUID();
    when(account.abortNeverAdmitted(anyString(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              var source = fixture.abandonmentSource();
              // The actual closure and terminal SQL write run. A controlled incarnation change
              // stops automatic issuer cleanup so this test can exercise its SQL boundaries.
              fixture.moveIssuerIncarnation();
              return new AbortEvidence(
                  lease,
                  fixture.transitionId(),
                  source.abandonmentId(),
                  CanonicalGameplayIssuerMemberV2Codec.sha256(source.candidate().canonicalBytes())
                      .substring(7),
                  source.closureDigest().substring(7),
                  cleanupId,
                  resolutionId);
            });
    assertThatThrownBy(
            () -> fixture.neverAdmittedOwner(account).abort(fixture.transitionId(), "controlled"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(fixture.abandonmentSource().orphanCleanupId()).isEqualTo(cleanupId);
    assertThat(fixture.abandonmentSource().negativeResolutionId()).isEqualTo(resolutionId);
    fixture.restoreIssuerState();
  }

  private static void corruptMutationLineage(
      DSLContext transaction,
      CanonicalGameplayIssuerBindingLocalRetirementOperation pending,
      UUID wrongBootstrapId) {
    assertThat(
            transaction.execute(
                "UPDATE " + MUTATION + " SET bootstrap_operation_id = ? WHERE mutation_id = ?",
                wrongBootstrapId,
                pending.physicalMutation().mutationId()))
        .isOne();
  }

  private static void assertNormalTriggerMode(DSLContext dsl) {
    assertThat(dsl.fetchSingle("SHOW session_replication_role").get(0, String.class))
        .isEqualTo("origin");
  }

  private static Map<String, List<String>> retainedRows(DSLContext dsl) {
    return retainedRows(dsl, false);
  }

  private static Map<String, List<String>> retainedRows(DSLContext dsl, boolean includeXmin) {
    String rowImage =
        includeXmin
            ? "retained.xmin::text || ':' || to_jsonb(retained)::text"
            : "to_jsonb(retained)::text";
    Map<String, List<String>> rows = new LinkedHashMap<>();
    for (String table :
        dsl.fetch(
                "SELECT tablename FROM pg_tables WHERE schemaname = current_schema() ORDER BY tablename")
            .getValues(0, String.class)) {
      rows.put(
          table,
          dsl.select(DSL.field(rowImage, String.class))
              .from(DSL.table(DSL.name(table)).as("retained"))
              .orderBy(DSL.field("to_jsonb(retained)::text"))
              .fetch()
              .getValues(0, String.class));
    }
    return rows;
  }

  private static LuaInvocation retirementInvocation(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture,
      CanonicalGameplayIssuerBindingLocalRetirementOperation pending)
      throws Exception {
    return new LuaInvocation(
        readScript(
            CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution.RESOURCE_PATH),
        fixture.issuerPartitionKey(),
        scriptArguments(
            CanonicalGameplayIssuerBindingLocalRetirementRedisOwner.class,
            new Class<?>[] {
              CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork.class,
              CanonicalGameplayIssuerBindingLocalRetirementOperation.class
            },
            fixture.readCurrentOriginalPartitionWork(),
            pending));
  }
}
