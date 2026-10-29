package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewCandidateRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL source plus Coordination Redis proof for the non-admitting original NEW value.
 * The upstream target and Account lease are controlled fixtures; this proof does not qualify their
 * authority or the separate ordinary-NEW issuer-LIVE preparation owner.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayOriginalNewCandidateRedisOwnerIntegrationTest {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String KEY_PATTERN = "session:game:*";
  private static final int PORT = 6379;
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final UUID TRANSITION_ID = UUID.randomUUID();
  private static final UUID ACCOUNT_INDEX_FENCE = UUID.randomUUID();
  private static final String ISSUER_ID = GameSessionAccountDelegationProfile.ISSUER;

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(PORT);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private RedisClient administrator;
  private RedisClient ownerClient;
  private StatefulRedisConnection<byte[], byte[]> adminConnection;
  private RedisCommands<byte[], byte[]> admin;
  private TestSource source;
  private CanonicalGameplayOriginalNewCandidateRedisOwner owner;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    administrator = createClient(null, null);
    adminConnection = administrator.connect(ByteArrayCodec.INSTANCE);
    admin = adminConnection.sync();
    admin.flushdb();
    configurePrincipal(true);
    ownerClient = createClient(PRINCIPAL, PASSWORD);
    var catalog = RedisScriptCatalog.loadInstalled(getClass().getClassLoader());
    source = prepareSource();
    owner =
        CanonicalGameplayOriginalNewCandidateRedisOwnerFactory.create(
            ownerClient, catalog, source.repository(), source.tenantTags());
  }

  private void configurePrincipal(boolean allowScriptLoad) {
    AclSetuserArgs permissions =
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(PASSWORD)
            .keyPattern(KEY_PATTERN)
            .addCommand(CommandType.HELLO)
            .addCommand(CommandType.PING)
            .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
            .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
            .addCommand(CommandType.EVALSHA)
            .addCommand(CommandType.TYPE)
            .addCommand(CommandType.PTTL)
            .addCommand(CommandType.GET)
            .addCommand(CommandType.SET)
            .addCommand(CommandType.SADD)
            .addCommand(CommandType.SISMEMBER);
    if (allowScriptLoad) permissions.addCommand(CommandType.SCRIPT, CommandKeyword.LOAD);
    admin.aclSetuser(PRINCIPAL, permissions);
  }

  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    if (source != null) source.close();
    if (adminConnection != null) adminConnection.close();
    if (ownerClient != null) ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (administrator != null) administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
  }

  @org.junit.jupiter.api.Test
  void selectedAbandonmentRetainsTheExactOriginalAndExcludesEveryPositiveCandidateRetry() {
    var claim = source.repository().claimOriginalNewCandidateInstallation(source.decision());
    var before = source.repository().readProvisionalCasEvidence(TRANSITION_ID);
    var selected = source.repository().selectOriginalNewAbandonment(TRANSITION_ID);
    var retry = source.repository().selectOriginalNewAbandonment(TRANSITION_ID);

    assertThat(selected.candidate()).isEqualTo(claim.candidate());
    assertThat(selected.state())
        .isEqualTo(CanonicalGameplayOriginalNewAbandonmentSource.State.ABANDONMENT_SELECTED);
    assertThat(selected.closureDigest()).isNull();
    assertThat(selected.orphanCleanupId()).isNull();
    assertThat(retry.abandonmentId()).isEqualTo(selected.abandonmentId());
    assertThat(retry.transition().inventoryRevision())
        .isEqualTo(selected.transition().inventoryRevision());
    assertThat(source.repository().readOriginalNewAbandonmentSource(TRANSITION_ID).candidate())
        .isEqualTo(claim.candidate());
    assertThatThrownBy(() -> owner.installOnce(source.decision()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("irreversibly abandoned");
    assertThatThrownBy(() -> source.repository().markProvisional(before, source.decision()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("irreversibly abandoned");
    assertThatThrownBy(() -> source.repository().beginAccountMemberRepair(TRANSITION_ID))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThatThrownBy(
            () ->
                source
                    .dsl()
                    .execute(
                        "UPDATE game_session_canonical_binding_transition SET status = 'PROVISIONAL' WHERE transition_id = ?",
                        TRANSITION_ID))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(admin.dbsize()).isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"HISTORICAL", "ACQUISITION_INTENT", "ACTIVE"})
  void postCreateAccountMovementAllowsOnlyExactRetainedAbandonment(String nextControl) {
    var original = source.repository().readProvisionalCasEvidence(TRANSITION_ID);
    var candidate =
        source
            .repository()
            .readOriginalNewCandidateInstallationSource(source.decision())
            .candidate();
    var repository = org.mockito.Mockito.spy(source.repository());
    int[] claims = {0};
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (++claims[0] == 2) moveAccountControlAfterCandidateCreate(nextControl);
              return invocation.callRealMethod();
            })
        .when(repository)
        .claimOriginalNewCandidateInstallation(source.decision());
    var racingOwner =
        CanonicalGameplayOriginalNewCandidateRedisOwnerFactory.create(
            ownerClient,
            RedisScriptCatalog.loadInstalled(getClass().getClassLoader()),
            repository,
            source.tenantTags());

    // The second claim occurs only after real CREATE and independent physical readback.
    assertThatThrownBy(() -> racingOwner.installOnce(source.decision()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThat(claims[0]).isEqualTo(2);
    var assignment = source.tenantTags().assignmentForTransition(TRANSITION_ID);
    String key =
        CanonicalGameplaySessionRedisKeys.session(
            assignment, candidate.bindingIdentity().bindingRef());
    assertThat(admin.get(ascii(key))).containsExactly(candidate.canonicalBytes());
    assertThat(admin.pttl(ascii(key))).isEqualTo(-1L);
    String retained = sourceSqlImage(source);
    String control = accountControlImage();
    assertThatThrownBy(() -> source.repository().readProvisionalCasEvidence(TRANSITION_ID))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThatThrownBy(
            () -> source.repository().claimOriginalNewCandidateInstallation(source.decision()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThatThrownBy(() -> owner.installOnce(source.decision()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThatThrownBy(() -> source.repository().markProvisional(original, source.decision()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThat(sourceSqlImage(source)).isEqualTo(retained);

    var selected = source.repository().selectOriginalNewAbandonment(TRANSITION_ID);
    var retry = source.repository().selectOriginalNewAbandonment(TRANSITION_ID);
    assertThat(selected.candidate()).isEqualTo(candidate);
    assertThat(selected.transition().status().name()).isEqualTo("PREPARED");
    assertThat(selected.state().name()).isEqualTo("ABANDONMENT_SELECTED");
    assertThat(retry.abandonmentId()).isEqualTo(selected.abandonmentId());
    assertThat(retry.transition().inventoryRevision())
        .isEqualTo(selected.transition().inventoryRevision());
    assertThat(accountControlImage()).isEqualTo(control);
    String refusal =
        switch (nextControl) {
          case "ACQUISITION_INTENT" -> "Account-fence acquisition intent blocks";
          case "ACTIVE" -> "Account-wide admission fence blocks";
          default -> "irreversibly abandoned";
        };
    assertThatThrownBy(() -> owner.installOnce(source.decision()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining(refusal);
    assertThatThrownBy(() -> source.repository().beginAccountMemberRepair(TRANSITION_ID))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThat(admin.get(ascii(key))).containsExactly(candidate.canonicalBytes());
  }

  private String accountControlImage() {
    return source
        .dsl()
        .fetchSingle(
            "SELECT to_jsonb(control)::text FROM game_session_canonical_account_coverage_control control WHERE account_id = ?",
            source.identity().accountId())
        .get(0, String.class);
  }

  private void moveAccountControlAfterCandidateCreate(String nextControl) {
    if ("ACQUISITION_INTENT".equals(nextControl)) {
      new CanonicalGameplayAccountAdmissionBarrierRepository(source.dsl())
          .beginFenceIntent(source.identity().accountId(), UUID.randomUUID());
      return;
    }
    // Coverage operations are controlled upstream SQL inputs, not acquisition-producer proof.
    boolean historical = "HISTORICAL".equals(nextControl);
    java.math.BigDecimal snapshot =
        source
            .dsl()
            .fetchSingle(
                "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock WHERE singleton_id = 1")
            .get(0, java.math.BigDecimal.class);
    java.math.BigDecimal generation =
        Objects.requireNonNull(snapshot).add(java.math.BigDecimal.ONE);
    UUID operation = UUID.randomUUID();
    UUID admissionFence = UUID.randomUUID();
    UUID coverageFence = UUID.randomUUID();
    source
        .dsl()
        .transaction(
            configuration -> {
              DSLContext transaction = DSL.using(configuration);
              CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
              transaction.execute(
                  "INSERT INTO game_session_canonical_account_coverage_operation"
                      + " (operation_id, request_id, account_id, legacy_cohort_id, legacy_owner_operation_id,"
                      + " legacy_writer_fence, legacy_source_snapshot_revision, legacy_canonical_inventory_revision,"
                      + " legacy_namespace_index_readback_revision, legacy_evidence_digest, lifecycle, operation_fence,"
                      + " coverage_fence, account_admission_fence, historical_account_admission_fence, resolved_scope_kind,"
                      + " snapshot_state, inventory_snapshot_revision, coverage_generation, redis_member_count, redis_member_set_digest)"
                      + " SELECT ?, ?, ?, cohort_id, owner_operation_id, legacy_writer_fence, source_snapshot_revision,"
                      + " canonical_inventory_revision, namespace_index_readback_revision, evidence_digest, ?, 1,"
                      + " ?, ?, ?, 'ACCOUNT_WIDE', 'COMPLETE', ?, ?, ?, ?"
                      + " FROM game_session_canonical_binding_legacy_disposition WHERE singleton_id = 1",
                  operation,
                  UUID.randomUUID(),
                  source.identity().accountId(),
                  nextControl,
                  coverageFence,
                  historical ? null : admissionFence,
                  historical ? admissionFence : null,
                  historical ? snapshot : null,
                  historical ? generation : null,
                  historical ? 0L : null,
                  historical ? "sha256:" + "0".repeat(64) : null);
              transaction.execute(
                  "UPDATE game_session_canonical_account_coverage_control SET state = ?,"
                      + " last_operation_fence = 1, current_operation_id = ?, current_operation_fence = 1,"
                      + " account_admission_fence = ?, historical_account_admission_fence = ?, coverage_fence = ?,"
                      + " inventory_snapshot_revision = ?, coverage_generation = ? WHERE account_id = ?",
                  nextControl,
                  operation,
                  historical ? null : admissionFence,
                  historical ? admissionFence : null,
                  coverageFence,
                  historical ? snapshot : null,
                  historical ? generation : null,
                  source.identity().accountId());
            });
  }

  @org.junit.jupiter.api.Test
  void abandonmentRefusesNeverClaimedAndAlreadyReadbackConfirmedOriginals() {
    assertThatThrownBy(() -> source.repository().selectOriginalNewAbandonment(TRANSITION_ID))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    var installed = owner.installOnce(source.decision());
    String sqlBefore = sourceSqlImage(source);
    assertThatThrownBy(() -> source.repository().selectOriginalNewAbandonment(TRANSITION_ID))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThat(sourceSqlImage(source)).isEqualTo(sqlBefore);
    assertThat(admin.get(ascii(installed.sessionKey())))
        .containsExactly(installed.exactCandidateBytes());
  }

  @org.junit.jupiter.api.Test
  void candidateDigestMustMatchTheExactFrozenBytesBeforeDispatch() {
    byte[] candidate =
        source
            .repository()
            .readOriginalNewCandidateInstallationSource(source.decision())
            .candidate()
            .canonicalBytes();
    String sqlBefore = sourceSqlImage(source);

    assertCandidateConstraint(
        () ->
            source
                .dsl()
                .execute(
                    "UPDATE game_session_canonical_binding_transition"
                        + " SET original_new_candidate_bytes = ?, original_new_candidate_digest = ?,"
                        + " original_new_candidate_state = 'DISPATCH_POSSIBLE' WHERE transition_id = ?",
                    candidate,
                    "sha256:" + "0".repeat(64),
                    TRANSITION_ID),
        "chk_gs_original_new_candidate_installation");

    assertThat(sourceSqlImage(source)).isEqualTo(sqlBefore);
    assertThat(admin.dbsize()).isZero();
    assertThat(owner.installOnce(source.decision()).provisionalTransition().status().name())
        .isEqualTo("PROVISIONAL");
  }

  @org.junit.jupiter.api.Test
  void insertedTransitionCannotSkipTheOriginalCandidateClaim() {
    byte[] candidate =
        source
            .repository()
            .readOriginalNewCandidateInstallationSource(source.decision())
            .candidate()
            .canonicalBytes();
    String sqlBefore = sourceSqlImage(source);
    for (String state : List.of("DISPATCH_POSSIBLE", "READBACK_CONFIRMED")) {
      String readback = state.equals("READBACK_CONFIRMED") ? "sha256:" + "1".repeat(64) : null;
      assertCandidateConstraint(
          () ->
              source
                  .dsl()
                  .execute(
                      "INSERT INTO game_session_canonical_binding_transition"
                          + " SELECT (jsonb_populate_record(retained, jsonb_build_object("
                          + " 'original_new_candidate_bytes', ?::bytea,"
                          + " 'original_new_candidate_digest', 'sha256:' || encode(sha256(?::bytea), 'hex'),"
                          + " 'original_new_candidate_state', ?::text,"
                          + " 'original_new_candidate_readback_digest', ?::text))).*"
                          + " FROM game_session_canonical_binding_transition retained WHERE transition_id = ?",
                      candidate,
                      candidate,
                      state,
                      readback,
                      TRANSITION_ID),
          "gs_original_new_candidate_immutable");
      assertThat(sourceSqlImage(source)).isEqualTo(sqlBefore);
      assertThat(admin.dbsize()).isZero();
    }
    assertThat(owner.installOnce(source.decision()).provisionalTransition().status().name())
        .isEqualTo("PROVISIONAL");
  }

  private static void assertCandidateConstraint(Runnable write, String constraint) {
    assertThatThrownBy(write::run)
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            PSQLException.class,
            failure -> {
              assertThat(failure.getSQLState()).isEqualTo("23514");
              assertThat(Objects.requireNonNull(failure.getServerErrorMessage()).getConstraint())
                  .isEqualTo(constraint);
            });
  }

  @org.junit.jupiter.api.Test
  void failedScriptPreflightLeavesCreationUnclaimedAndTheSameOriginalCanRetry() {
    String sqlBefore = sourceSqlImage(source);
    configurePrincipal(false);

    assertThatThrownBy(() -> owner.installOnce(source.decision()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Original NEW candidate install/readback is unavailable");

    assertThat(sourceSqlImage(source)).isEqualTo(sqlBefore);
    assertThat(admin.dbsize()).isZero();
    var untouched =
        source.repository().readOriginalNewCandidateInstallationSource(source.decision());
    assertThat(untouched.state().name()).isEqualTo("NEVER_DISPATCHED");
    assertThat(untouched.consumeCreatePermission()).isFalse();
    configurePrincipal(true);
    var installed = owner.installOnce(source.decision());
    assertThat(installed.candidate()).isEqualTo(untouched.candidate());
    assertThat(installed.provisionalTransition().status().name()).isEqualTo("PROVISIONAL");
    assertThat(admin.get(ascii(installed.sessionKey())))
        .containsExactly(untouched.candidate().canonicalBytes());
  }

  @org.junit.jupiter.api.Test
  void currentAccountMovementAfterPreflightRefusesDispatchWithoutClaimingCreation() {
    var repository = org.mockito.Mockito.spy(source.repository());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              new CanonicalGameplayAccountAdmissionBarrierRepository(source.dsl())
                  .beginFenceIntent(source.identity().accountId(), UUID.randomUUID());
              return invocation.callRealMethod();
            })
        .when(repository)
        .claimOriginalNewCandidateInstallation(source.decision());
    var racingOwner =
        CanonicalGameplayOriginalNewCandidateRedisOwnerFactory.create(
            ownerClient,
            RedisScriptCatalog.loadInstalled(getClass().getClassLoader()),
            repository,
            source.tenantTags());
    String sqlBefore = sourceSqlImage(source);

    assertThatThrownBy(() -> racingOwner.installOnce(source.decision()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessage("Account-fence acquisition intent blocks a new binding transition");

    org.mockito.Mockito.verify(repository).claimOriginalNewCandidateInstallation(source.decision());
    assertThat(sourceSqlImage(source)).isEqualTo(sqlBefore);
    assertThat(admin.dbsize()).isZero();
  }

  @org.junit.jupiter.api.Test
  void retainedDispatchUsesReadbackWithoutScriptLoadAndNeverRecreatesMissingState() {
    var installed = owner.installOnce(source.decision());
    String sqlBefore = sourceSqlImage(source);
    configurePrincipal(false);
    admin.scriptFlush();

    var retry = owner.installOnce(source.decision());
    assertThat(retry.candidate()).isEqualTo(installed.candidate());
    assertThat(retry.physicalReadback().digest()).isEqualTo(installed.physicalReadback().digest());
    assertThat(sourceSqlImage(source)).isEqualTo(sqlBefore);

    assertThat(admin.del(ascii(installed.sessionKey()))).isEqualTo(1L);
    assertThatThrownBy(() -> owner.installOnce(source.decision()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Original NEW candidate install/readback is unavailable");
    assertThat(admin.type(ascii(installed.sessionKey()))).isEqualTo("none");
    assertThat(sourceSqlImage(source)).isEqualTo(sqlBefore);
  }

  @org.junit.jupiter.api.Test
  void installsExactCandidateAndReadOnlyRetryRetainsTheSameProvisionalDecision() {
    var first = owner.installOnce(source.decision());
    advanceInventoryWithUnrelatedPreparedNew();
    var retry = owner.installOnce(source.decision());

    assertThat(first.candidate().matchesOriginalDecision(source.decision())).isTrue();
    assertThat(first.candidate().canonicalBytes()).containsExactly(first.exactCandidateBytes());
    assertThat(first.isNonAdmittingCandidate()).isTrue();
    assertThat(first.provisionalTransition().status().name()).isEqualTo("PROVISIONAL");
    assertThat(retry.candidate()).isEqualTo(first.candidate());
    assertThat(retry.physicalReadback().digest()).isEqualTo(first.physicalReadback().digest());
    assertThat(
            source
                .repository()
                .readAdmissionDecision(TRANSITION_ID, source.decision().leaseEvidence()))
        .satisfies(
            stored -> {
              assertThat(first.candidate().matchesOriginalDecision(stored)).isTrue();
              assertThat(stored.leaseEvidence().canonicalJson())
                  .isEqualTo(source.decision().leaseEvidence().canonicalJson());
            });

    assertThat(admin.type(ascii(first.sessionKey()))).isEqualTo("string");
    assertThat(admin.get(ascii(first.sessionKey()))).containsExactly(first.exactCandidateBytes());
    assertThat(admin.type(ascii(first.characterIndexKey()))).isEqualTo("string");
    assertThat(new String(admin.get(ascii(first.characterIndexKey())), StandardCharsets.US_ASCII))
        .contains(
            source.identity().sessionId(),
            source.identity().gameInstanceId().toString(),
            "bindingGeneration");
    assertThat(
            admin.sismember(
                ascii(first.accountTenantIndexKey()), ascii(source.identity().sessionId())))
        .isTrue();
    assertThat(admin.sismember(ascii(first.tenantIndexKey()), ascii(source.identity().sessionId())))
        .isTrue();
    assertThat(admin.pttl(ascii(first.sessionKey()))).isEqualTo(-1L);
  }

  @org.junit.jupiter.api.Test
  void missingAccountTenantOrTenantSetRefusesRetryWithoutChangingSqlOrOtherPostimages() {
    List<TestSource> additionalSources = new ArrayList<>();
    try {
      for (int missingIndex = 0; missingIndex < 2; missingIndex++) {
        TestSource scenario = missingIndex == 0 ? source : prepareSource();
        if (scenario != source) additionalSources.add(scenario);
        CanonicalGameplayOriginalNewCandidateRedisOwner scenarioOwner =
            scenario == source
                ? owner
                : CanonicalGameplayOriginalNewCandidateRedisOwnerFactory.create(
                    ownerClient,
                    RedisScriptCatalog.loadInstalled(getClass().getClassLoader()),
                    scenario.repository(),
                    scenario.tenantTags());
        var installation = scenarioOwner.installOnce(scenario.decision());
        String missingKey =
            missingIndex == 0
                ? installation.accountTenantIndexKey()
                : installation.tenantIndexKey();
        String preservedKey =
            missingIndex == 0
                ? installation.tenantIndexKey()
                : installation.accountTenantIndexKey();
        byte[] sessionBytes = admin.get(ascii(installation.sessionKey()));
        byte[] characterBytes = admin.get(ascii(installation.characterIndexKey()));
        String sqlImageBeforeRetry = sourceSqlImage(scenario);

        assertThat(admin.del(ascii(missingKey))).isEqualTo(1L);
        assertThatThrownBy(() -> scenarioOwner.installOnce(scenario.decision()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("install/readback is unavailable");

        assertThat(admin.type(ascii(missingKey))).isEqualTo("none");
        assertThat(admin.type(ascii(installation.sessionKey()))).isEqualTo("string");
        assertThat(admin.pttl(ascii(installation.sessionKey()))).isEqualTo(-1L);
        assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(sessionBytes);
        assertThat(admin.type(ascii(installation.characterIndexKey()))).isEqualTo("string");
        assertThat(admin.pttl(ascii(installation.characterIndexKey()))).isEqualTo(-1L);
        assertThat(admin.get(ascii(installation.characterIndexKey())))
            .containsExactly(characterBytes);
        assertThat(admin.type(ascii(preservedKey))).isEqualTo("set");
        assertThat(admin.pttl(ascii(preservedKey))).isEqualTo(-1L);
        assertThat(admin.sismember(ascii(preservedKey), ascii(scenario.identity().sessionId())))
            .isTrue();
        assertThat(sourceSqlImage(scenario)).isEqualTo(sqlImageBeforeRetry);
      }
    } finally {
      additionalSources.forEach(TestSource::close);
    }
  }

  @org.junit.jupiter.api.Test
  void doubleSqlClaimIssuesExactlyOneSingleUseCreatePermission() {
    var first = source.repository().claimOriginalNewCandidateInstallation(source.decision());
    var second = source.repository().claimOriginalNewCandidateInstallation(source.decision());

    assertThat(first.consumeCreatePermission()).isTrue();
    assertThat(first.consumeCreatePermission()).isFalse();
    assertThat(second.consumeCreatePermission()).isFalse();
    assertThatThrownBy(() -> owner.installOnce(source.decision()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("install/readback is unavailable");
    assertThat(admin.dbsize()).isZero();
  }

  @org.junit.jupiter.api.Test
  void claimedCandidateCannotUseLegacyProvisionalPublicationWithoutPhysicalReadback() {
    source.repository().claimOriginalNewCandidateInstallation(source.decision());
    var claimedEvidence = source.repository().readProvisionalCasEvidence(TRANSITION_ID);
    String sqlBefore = sourceSqlImage(source);
    assertThat(admin.dbsize()).isZero();

    assertThatThrownBy(
            () -> source.repository().markProvisional(claimedEvidence, source.decision()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessage("Claimed original NEW candidate requires owner-produced physical readback");

    assertThat(sourceSqlImage(source)).isEqualTo(sqlBefore);
    assertThat(admin.dbsize()).isZero();
  }

  @org.junit.jupiter.api.Test
  void changedOriginalLeaseCannotReuseThePersistedCandidateOrDispatchRedis() {
    var originalClaim =
        source.repository().claimOriginalNewCandidateInstallation(source.decision());
    assertThat(originalClaim.state().name()).isEqualTo("DISPATCH_POSSIBLE");
    Map<String, Object> changedCarrier =
        new LinkedHashMap<>(source.decision().leaseEvidence().carrier());
    changedCarrier.put("leaseId", UUID.randomUUID().toString());
    var changedLease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier);
    var changedDecision =
        new CanonicalGameplayAdmissionDecision(
            TRANSITION_ID,
            changedLease,
            source.identity(),
            source.decision().bindingGeneration(),
            null,
            source.decision().targetEvidence());

    assertThatThrownBy(() -> owner.installOnce(changedDecision))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessage("Original NEW candidate retry differs from its immutable SQL source");
    assertThat(admin.dbsize()).isZero();
    assertThat(
            source
                .dsl()
                .fetchSingle(
                    "SELECT original_new_candidate_state FROM game_session_canonical_binding_transition"
                        + " WHERE transition_id = ?",
                    TRANSITION_ID)
                .get("original_new_candidate_state", String.class))
        .isEqualTo("DISPATCH_POSSIBLE");
  }

  @org.junit.jupiter.api.Test
  void lostReplyRecoveryUsesOnlyExactReadbackAfterTheCreatePermissionWasClaimed() {
    var claim = source.repository().claimOriginalNewCandidateInstallation(source.decision());
    assertThat(claim.consumeCreatePermission()).isTrue();
    var assignment = source.tenantTags().assignmentForTransition(TRANSITION_ID);
    var binding = claim.candidate().bindingIdentity();
    String sessionKey = CanonicalGameplaySessionRedisKeys.session(assignment, binding.bindingRef());
    String characterKey =
        CanonicalGameplaySessionRedisKeys.characterIndex(assignment, binding.bindingRef());
    String accountKey =
        CanonicalGameplaySessionRedisKeys.accountTenantIndex(assignment, binding.bindingRef());
    String tenantKey =
        CanonicalGameplaySessionRedisKeys.tenantIndex(assignment, binding.bindingRef());
    byte[] script;
    try (var input =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                CanonicalGameplayOriginalNewCandidateRedisScriptContribution.RESOURCE_PATH)) {
      if (input == null) throw new IllegalStateException("Candidate install script is missing");
      script = input.readAllBytes();
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Cannot load candidate install script", failure);
    }
    String sha = admin.scriptLoad(script);
    Long reply =
        admin.evalsha(
            sha,
            io.lettuce.core.ScriptOutputType.INTEGER,
            new byte[][] {
              ascii(sessionKey), ascii(characterKey), ascii(accountKey), ascii(tenantKey)
            },
            ascii(CanonicalGameplayOriginalNewCandidate.PURPOSE),
            ascii(assignment.tenantId().toString()),
            ascii(assignment.accountId().toString()),
            ascii(assignment.playableStateNamespaceId().toString()),
            ascii(assignment.characterId().toString()),
            ascii(assignment.gameInstanceId().toString()),
            ascii(assignment.sessionId()),
            ascii(claim.candidate().transition().bindingGeneration().toString()),
            ascii(assignment.tenantGameplayTag()),
            claim.candidate().canonicalBytes());
    assertThat(reply).isEqualTo(1L);
    // The owner is called as if the prior reply was lost. The SQL marker prevents a second eval.
    var recovered = owner.installOnce(source.decision());
    assertThat(recovered.candidate()).isEqualTo(claim.candidate());
    assertThat(admin.get(ascii(recovered.sessionKey())))
        .containsExactly(recovered.exactCandidateBytes());
  }

  @org.junit.jupiter.api.Test
  void resetAndCharacterConflictRemainUnresolvedWithoutRepair() {
    var claim = source.repository().claimOriginalNewCandidateInstallation(source.decision());
    assertThat(claim.consumeCreatePermission()).isTrue();
    admin.flushdb();

    assertThatThrownBy(() -> owner.installOnce(source.decision()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("install/readback is unavailable");
    assertThat(admin.dbsize()).isZero();
    assertThat(
            source
                .dsl()
                .fetchSingle(
                    "SELECT original_new_candidate_state FROM game_session_canonical_binding_transition"
                        + " WHERE transition_id = ?",
                    TRANSITION_ID)
                .get("original_new_candidate_state", String.class))
        .isEqualTo("DISPATCH_POSSIBLE");

    var conflictSource = prepareSource();
    try {
      var assignment = conflictSource.tenantTags().assignmentForTransition(TRANSITION_ID);
      String characterKey =
          CanonicalGameplaySessionRedisKeys.characterIndex(
              assignment, conflictSource.identity().bindingRef());
      admin.set(ascii(characterKey), ascii("foreign-generation"));
      var conflictOwner =
          CanonicalGameplayOriginalNewCandidateRedisOwnerFactory.create(
              ownerClient,
              RedisScriptCatalog.loadInstalled(getClass().getClassLoader()),
              conflictSource.repository(),
              conflictSource.tenantTags());
      assertThatThrownBy(() -> conflictOwner.installOnce(conflictSource.decision()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("install/readback is unavailable");
      assertThat(admin.get(ascii(characterKey))).isEqualTo(ascii("foreign-generation"));
      assertThat(
              admin.type(
                  ascii(
                      CanonicalGameplaySessionRedisKeys.session(
                          assignment, conflictSource.identity().bindingRef()))))
          .isEqualTo("none");
    } finally {
      conflictSource.close();
    }
  }

  @org.junit.jupiter.api.Test
  void acquisitionIntentRaceRefusesBeforeAnyRedisMutationAndAmbientSqlNeverCallsRedis() {
    var claimed = source.repository().claimOriginalNewCandidateInstallation(source.decision());
    assertThat(claimed.consumeCreatePermission()).isTrue();
    new CanonicalGameplayAccountAdmissionBarrierRepository(source.dsl())
        .beginFenceIntent(source.identity().accountId(), UUID.randomUUID());
    assertThatThrownBy(() -> owner.installOnce(source.decision()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessage("Account-fence acquisition intent blocks a new binding transition");
    assertThat(admin.dbsize()).isZero();

    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> owner.installOnce(source.decision()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient SQL transaction or synchronization");
    } finally {
      TransactionSynchronizationManager.clear();
    }
    assertThat(admin.dbsize()).isZero();

    try {
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> owner.installOnce(source.decision()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient SQL transaction or synchronization");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    assertThat(admin.dbsize()).isZero();
  }

  private static String sourceSqlImage(TestSource source) {
    return source
        .dsl()
        .fetchSingle(
            "SELECT jsonb_build_array("
                + "(SELECT to_jsonb(t) FROM game_session_canonical_binding_transition t"
                + " WHERE t.transition_id = ?),"
                + "(SELECT to_jsonb(i) FROM game_session_canonical_gameplay_binding_inventory i"
                + " WHERE i.transition_id = ?),"
                + "(SELECT to_jsonb(r) FROM game_session_canonical_issuer_partition_reservation r"
                + " WHERE r.transition_id = ?),"
                + "(SELECT to_jsonb(a) FROM game_session_canonical_binding_account_index_obligation a"
                + " WHERE a.transition_id = ?),"
                + "(SELECT to_jsonb(i) FROM game_session_canonical_binding_issuer_index_obligation i"
                + " WHERE i.transition_id = ?),"
                + "(SELECT to_jsonb(r) FROM game_session_canonical_binding_region_bridge_obligation r"
                + " WHERE r.transition_id = ?),"
                + "(SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                + " WHERE singleton_id = 1))::text",
            TRANSITION_ID,
            TRANSITION_ID,
            TRANSITION_ID,
            TRANSITION_ID,
            TRANSITION_ID,
            TRANSITION_ID)
        .get(0, String.class);
  }

  private TestSource prepareSource() {
    String schema = "gs_original_new_candidate_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = POSTGRES.getJdbcUrl();
    dataSource.setUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema);
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    var runtime = CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource);
    seedSyntheticVerifiedLegacyDisposition(dsl);
    CanonicalGameplayBindingIdentity identity =
        new CanonicalGameplayBindingIdentity(
            UUID.randomUUID(),
            runtime.canonicalTenantId(),
            runtime.playableStateNamespaceId(),
            UUID.randomUUID(),
            runtime.playableStateScope(),
            runtime.gameInstanceUuid(),
            runtime.gameInstanceId(),
            UUID.randomUUID().toString(),
            UUID.randomUUID(),
            BigInteger.valueOf(3),
            ISSUER_ID,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.valueOf(4),
            BigInteger.valueOf(32));
    UUID transitionId = TRANSITION_ID;
    CanonicalGameplayBindingInventoryRepository repository =
        new CanonicalGameplayBindingInventoryRepository(dsl);
    repository.prepare(
        new CanonicalGameplayBindingTransitionRequest(
            transitionId,
            ACCOUNT_INDEX_FENCE,
            identity,
            new CanonicalIssuerReservationFenceEvidence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigInteger.ONE,
                BigInteger.TWO,
                BigInteger.ONE),
            null,
            null));
    CanonicalGameplayBindingProvisionalCasEvidence prepared =
        repository.readProvisionalCasEvidence(transitionId);
    var decision =
        new CanonicalGameplayAdmissionDecision(
            transitionId,
            leaseEvidence(
                identity, prepared.transition().bindingGeneration(), runtime.playableTarget()),
            identity,
            prepared.transition().bindingGeneration(),
            null,
            runtime.playableTarget());
    var tenantTags =
        new CanonicalGameplayTenantGameplayTagRepository(dsl, runtime.targetNamespace(), 1);
    return new TestSource(schema, dataSource, dsl, repository, tenantTags, identity, decision);
  }

  private void advanceInventoryWithUnrelatedPreparedNew() {
    CanonicalGameplayBindingIdentity original = source.identity();
    var unrelated =
        new CanonicalGameplayBindingIdentity(
            original.accountId(),
            original.tenantId(),
            original.playableStateNamespaceId(),
            UUID.randomUUID(),
            original.playableStateScope(),
            original.gameInstanceId(),
            original.runtimeGameInstanceId(),
            UUID.randomUUID().toString(),
            original.regionId(),
            original.regionEpoch(),
            original.issuerId(),
            original.issuerAuthGeneration(),
            original.issuerIndexLayoutVersion(),
            original.issuerIndexPartitionCount(),
            original.issuerIndexPartitionCapacity());
    source
        .repository()
        .prepare(
            new CanonicalGameplayBindingTransitionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                unrelated,
                new CanonicalIssuerReservationFenceEvidence(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    BigInteger.ONE,
                    BigInteger.TWO,
                    BigInteger.ONE),
                null,
                null));
  }

  private static void seedSyntheticVerifiedLegacyDisposition(DSLContext dsl) {
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
            + " namespace_index_readback_revision = 1, evidence_digest = 'sha256:"
            + "0000000000000000000000000000000000000000000000000000000000000000'"
            + " WHERE singleton_id = 1");
  }

  private static AccountGameplayAdmissionLeaseEvidence leaseEvidence(
      CanonicalGameplayBindingIdentity candidate,
      BigInteger bindingGeneration,
      net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget target) {
    return CanonicalGameplayIssuerTestFixtures.controlledLeaseEvidence(
        candidate, bindingGeneration, target);
  }

  private RedisClient createClient(String username, String password) {
    RedisURI.Builder builder =
        RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(PORT))
            .withTimeout(Duration.ofSeconds(3));
    if (username != null) builder.withAuthentication(username, password);
    RedisClient client = RedisClient.create(builder.build());
    client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return client;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private record TestSource(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      CanonicalGameplayBindingInventoryRepository repository,
      CanonicalGameplayTenantGameplayTagRepository tenantTags,
      CanonicalGameplayBindingIdentity identity,
      CanonicalGameplayAdmissionDecision decision)
      implements AutoCloseable {
    @Override
    public void close() {
      try (var connection =
              java.sql.DriverManager.getConnection(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      } catch (Exception failure) {
        throw new IllegalStateException("Cannot drop candidate proof schema", failure);
      }
    }
  }
}
