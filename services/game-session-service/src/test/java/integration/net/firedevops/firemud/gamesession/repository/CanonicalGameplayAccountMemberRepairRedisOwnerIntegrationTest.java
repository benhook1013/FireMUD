package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountIndexMember;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountMemberAcknowledgement;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountMemberRepairOperation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingAccountIndexObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountMemberRepairRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical one-key Coordination Redis proof for the binding-local member projection. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayAccountMemberRepairRedisOwnerIntegrationTest {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String PREFIX = "session:game:index:account:";
  private static final int PORT = 6379;
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final UUID ACCOUNT_ID = UUID.randomUUID();
  private static final UUID CHARACTER_ID = UUID.randomUUID();
  private static final UUID REGION_ID = UUID.randomUUID();
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
  private CanonicalGameplayAccountMemberRepairRedisOwner owner;
  private RedisScriptCatalog catalog;
  private StatefulRedisConnection<byte[], byte[]> adminConnection;
  private RedisCommands<byte[], byte[]> admin;

  @BeforeEach
  void setUp() {
    administrator = createClient(null, null);
    adminConnection = administrator.connect(ByteArrayCodec.INSTANCE);
    admin = adminConnection.sync();
    admin.flushdb();
    admin.aclSetuser(
        PRINCIPAL,
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(PASSWORD)
            .keyPattern(PREFIX + "*")
            .addCommand(CommandType.HELLO)
            .addCommand(CommandType.PING)
            .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
            .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
            .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
            .addCommand(CommandType.EVALSHA)
            .addCommand(CommandType.TYPE)
            .addCommand(CommandType.PTTL)
            .addCommand(CommandType.SADD)
            .addCommand(CommandType.SISMEMBER));
    ownerClient = createClient(PRINCIPAL, PASSWORD);
    catalog = RedisScriptCatalog.loadInstalled(getClass().getClassLoader());
    owner = new CanonicalGameplayAccountMemberRepairRedisOwner(ownerClient, catalog);
  }

  @AfterEach
  void tearDown() {
    if (adminConnection != null) adminConnection.close();
    if (ownerClient != null) ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (administrator != null) administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
  }

  @Test
  void addsExactMemberOnOneGlobalKeyAndExactRetryPreservesOtherTenantsAndNewerGeneration() {
    CanonicalGameplayAccountMemberRepairOperation operation = operation();
    byte[] key = key(operation.accountId());
    var unrelated =
        new CanonicalGameplayAccountIndexMember(
                CanonicalGameplayBindingRef.of(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString()),
                BigInteger.valueOf(3),
                UUID.randomUUID())
            .value();
    var newer =
        new CanonicalGameplayAccountIndexMember(
                operation.bindingRef(),
                operation.bindingGeneration().add(BigInteger.ONE),
                UUID.randomUUID())
            .value();
    admin.sadd(key, ascii(unrelated), ascii(newer));

    var first = owner.projectAndRead(operation);
    var retry = owner.projectAndRead(operation);

    assertThat(first.operation()).isSameAs(operation);
    assertThat(first.member()).isEqualTo(operation.member());
    assertThat(retry.operation()).isSameAs(operation);
    assertThat(retry.member()).isEqualTo(operation.member());
    assertThat(admin.type(key)).isEqualTo("set");
    assertThat(admin.sismember(key, ascii(operation.member()))).isTrue();
    assertThat(admin.sismember(key, ascii(unrelated))).isTrue();
    assertThat(admin.sismember(key, ascii(newer))).isTrue();
    assertThat(admin.scard(key)).isEqualTo(3L);
    assertThat(admin.pttl(key)).isEqualTo(-1L);
  }

  @Test
  void refusesWrongPhysicalTypeAndPreservesItsValue() {
    CanonicalGameplayAccountMemberRepairOperation operation = operation();
    byte[] key = key(operation.accountId());
    admin.set(key, ascii("operator-owned-string"));

    assertThatThrownBy(() -> owner.projectAndRead(operation))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("projection is unavailable");

    assertThat(admin.type(key)).isEqualTo("string");
    assertThat(admin.get(key)).isEqualTo(ascii("operator-owned-string"));
  }

  @Test
  void refusesWrongRedisPrincipalWithoutCreatingTheAccountKey() {
    CanonicalGameplayAccountMemberRepairOperation operation = operation();
    byte[] key = key(operation.accountId());
    var wrongOwner = new CanonicalGameplayAccountMemberRepairRedisOwner(administrator, catalog);

    assertThatThrownBy(() -> wrongOwner.projectAndRead(operation))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("projection is unavailable");

    assertThat(admin.type(key)).isEqualTo("none");
  }

  @Test
  void refusesMalformedMemberAtTheLuaBoundaryWithoutCreatingTheKey() throws IOException {
    CanonicalGameplayAccountMemberRepairOperation operation = operation();
    byte[] key = key(operation.accountId());
    byte[] source;
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                CanonicalGameplayAccountMemberRepairRedisScriptContribution.RESOURCE_PATH)) {
      if (input == null) throw new IllegalStateException("Redis script resource is missing");
      source = input.readAllBytes();
    }
    String sha = admin.scriptLoad(source);
    byte[][] arguments = arguments(operation);
    arguments[5] = ascii(operation.member() + "-conflict");

    Long reply = admin.evalsha(sha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments);

    assertThat(reply).isEqualTo(-2L);
    assertThat(admin.type(key)).isEqualTo("none");

    arguments = arguments(operation);
    arguments[0] = ascii(UUID.randomUUID().toString());
    reply = admin.evalsha(sha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments);
    assertThat(reply).isEqualTo(-2L);
    assertThat(admin.type(key)).isEqualTo("none");

    arguments = arguments(operation);
    arguments[1] = ascii(UUID.randomUUID().toString());
    reply = admin.evalsha(sha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments);
    assertThat(reply).isEqualTo(-2L);
    assertThat(admin.type(key)).isEqualTo("none");
  }

  @Test
  void refusesRedisProjectionWhileSqlTransactionIsAmbient() {
    CanonicalGameplayAccountMemberRepairOperation operation = operation();
    byte[] key = key(operation.accountId());
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> owner.projectAndRead(operation))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("projection is unavailable");
    } finally {
      TransactionSynchronizationManager.clear();
    }

    assertThat(admin.type(key)).isEqualTo("none");
  }

  @Test
  void acknowledgementLocksLeaseBoundRuntimeBeforeTransitionAgainstRuntimeMutation()
      throws Exception {
    String applicationName =
        "gs_account_member_runtime_lock_" + UUID.randomUUID().toString().replace("-", "");
    TestSchema testSchema = newTestSchema(applicationName);
    CountDownLatch runtimeRowLocked = new CountDownLatch(1);
    CountDownLatch allowRuntimeMutation = new CountDownLatch(1);
    try {
      // Controlled Account lease and legacy fixtures exercise owner locks, not production
      // authority.
      LeaseBoundMemberRepairFixture fixture =
          prepareLeaseBoundMemberRepair(testSchema.dataSource(), testSchema.dsl());
      CanonicalGameplayAccountMemberRepairOperation operation =
          fixture.repository().beginAccountMemberRepair(TRANSITION_ID);
      var actualReadback = owner.projectAndRead(operation);
      CanonicalPlayableTarget target = fixture.runtimeTarget().playableTarget();
      long initialRowVersion =
          Objects.requireNonNull(
                  testSchema
                      .dsl()
                      .fetchOne(
                          "SELECT row_version FROM game_instances WHERE tenant_id = ? AND id = ?",
                          target.gameSessionTenantId(),
                          target.gameInstanceId()),
                  "current runtime row")
              .get("row_version", Long.class);

      try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
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
                                  "UPDATE game_instances SET status = 'STOPPED',"
                                      + " row_version = row_version + 1"
                                      + " WHERE tenant_id = ? AND id = ?",
                                  target.gameSessionTenantId(),
                                  target.gameInstanceId());
                            }));

        try {
          assertThat(runtimeRowLocked.await(5, TimeUnit.SECONDS)).isTrue();
          Future<CanonicalGameplayAccountMemberAcknowledgement> acknowledgement =
              executor.submit(
                  () ->
                      fixture
                          .repository()
                          .acknowledgeAccountMemberRepair(operation, actualReadback));
          awaitRuntimeRowLockWait(testSchema.dsl(), applicationName);
          allowRuntimeMutation.countDown();

          assertThatThrownBy(() -> runtimeMutation.get(10, TimeUnit.SECONDS))
              .hasStackTraceContaining("runtime row is fenced");
          var accepted = acknowledgement.get(10, TimeUnit.SECONDS);
          assertThat(accepted.operation()).isEqualTo(operation);
          assertThat(accepted.member()).isEqualTo(operation.member());
          assertThat(fixture.repository().acknowledgeAccountMemberRepair(operation, actualReadback))
              .isEqualTo(accepted);
          var exactPrepareRetry = fixture.repository().prepare(fixture.request());
          assertThat(exactPrepareRetry.transitionId()).isEqualTo(TRANSITION_ID);
          assertThat(exactPrepareRetry.status().name()).isEqualTo("PROVISIONAL");
          assertThat(
                  testSchema
                      .dsl()
                      .fetchSingle(
                          "SELECT account_index_state FROM game_session_canonical_gameplay_binding_inventory"
                              + " WHERE transition_id = ?",
                          TRANSITION_ID)
                      .get("account_index_state", String.class))
              .isEqualTo("ACKNOWLEDGED");
          assertThat(
                  testSchema
                      .dsl()
                      .fetchSingle(
                          "SELECT status FROM game_session_canonical_account_member_repair_operation"
                              + " WHERE operation_id = ?",
                          accepted.operation().operationId())
                      .get("status", String.class))
              .isEqualTo("ACKNOWLEDGED");
          assertThat(
                  Objects.requireNonNull(
                          testSchema
                              .dsl()
                              .fetchOne(
                                  "SELECT status, row_version FROM game_instances"
                                      + " WHERE tenant_id = ? AND id = ?",
                                  target.gameSessionTenantId(),
                                  target.gameInstanceId()),
                          "current runtime row")
                      .get("status", String.class))
              .isEqualTo("RUNNING");
          assertThat(
                  Objects.requireNonNull(
                          testSchema
                              .dsl()
                              .fetchOne(
                                  "SELECT row_version FROM game_instances"
                                      + " WHERE tenant_id = ? AND id = ?",
                                  target.gameSessionTenantId(),
                                  target.gameInstanceId()),
                          "current runtime row")
                      .get("row_version", Long.class))
              .isEqualTo(initialRowVersion);
        } finally {
          allowRuntimeMutation.countDown();
        }
      }
    } finally {
      allowRuntimeMutation.countDown();
      dropSchema(testSchema.schema());
    }
  }

  private static LeaseBoundMemberRepairFixture prepareLeaseBoundMemberRepair(
      DriverManagerDataSource dataSource, DSLContext dsl) {
    var runtimeTarget = CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource);
    seedSyntheticVerifiedLegacyDisposition(dsl);
    CanonicalGameplayBindingInventoryRepository repository =
        new CanonicalGameplayBindingInventoryRepository(dsl);
    CanonicalGameplayBindingIdentity candidate =
        identity(UUID.randomUUID().toString(), runtimeTarget);
    CanonicalGameplayBindingTransitionRequest request =
        new CanonicalGameplayBindingTransitionRequest(
            TRANSITION_ID,
            ACCOUNT_INDEX_FENCE,
            candidate,
            new CanonicalIssuerReservationFenceEvidence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigInteger.valueOf(13),
                BigInteger.valueOf(17),
                BigInteger.valueOf(41)),
            null,
            null);
    repository.prepare(request);
    CanonicalGameplayBindingProvisionalCasEvidence prepared =
        repository.readProvisionalCasEvidence(TRANSITION_ID);
    AccountGameplayAdmissionLeaseEvidence leaseEvidence =
        leaseEvidence(
            prepared.candidate().identity(),
            prepared.transition().bindingGeneration(),
            runtimeTarget.playableTarget());
    CanonicalGameplayAdmissionDecision decision =
        new CanonicalGameplayAdmissionDecision(
            TRANSITION_ID,
            leaseEvidence,
            prepared.candidate().identity(),
            prepared.transition().bindingGeneration(),
            null,
            runtimeTarget.playableTarget());
    repository.markProvisional(prepared, decision);
    return new LeaseBoundMemberRepairFixture(repository, runtimeTarget, request);
  }

  private static CanonicalGameplayBindingIdentity identity(
      String sessionId, CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target) {
    return new CanonicalGameplayBindingIdentity(
        ACCOUNT_ID,
        target.canonicalTenantId(),
        target.playableStateNamespaceId(),
        CHARACTER_ID,
        target.playableStateScope(),
        target.gameInstanceUuid(),
        target.gameInstanceId(),
        sessionId,
        REGION_ID,
        BigInteger.valueOf(12),
        ISSUER_ID,
        BigInteger.valueOf(7),
        BigInteger.valueOf(3),
        BigInteger.valueOf(16),
        BigInteger.valueOf(32));
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
    carrier.put("requestId", UUID.randomUUID().toString());
    carrier.put("leaseId", UUID.randomUUID().toString());
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

    carrier.put(
        "membershipBaseline",
        new LinkedHashMap<>(
            Map.of(
                "membershipLifecycleState",
                "ACTIVE",
                "membershipVersion",
                Map.of(candidate.tenantId().toString(), "1"),
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
    tokenIdentity.put("operationId", UUID.randomUUID().toString());
    tokenIdentity.put("issuanceRequestId", UUID.randomUUID().toString());
    tokenIdentity.put("tokenJti", UUID.randomUUID().toString());
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
            + " namespace_index_readback_revision = 1,"
            + " evidence_digest = 'sha256:"
            + "0000000000000000000000000000000000000000000000000000000000000000'"
            + " WHERE singleton_id = 1");
  }

  private static TestSchema newTestSchema(String applicationName) {
    String schema = "gs_account_member_lock_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema, applicationName);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    return new TestSchema(schema, dataSource, dsl);
  }

  private static DriverManagerDataSource dataSource(String schema, String applicationName) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String url = POSTGRES.getJdbcUrl();
    String separator = url.contains("?") ? "&" : "?";
    dataSource.setUrl(
        url + separator + "currentSchema=" + schema + "&ApplicationName=" + applicationName);
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  private static void dropSchema(String schema) {
    try (var connection =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Failed to dispose account-member lock-order schema", failure);
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for runtime lock-order test gate");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for runtime lock-order gate", interrupted);
    }
  }

  private static void awaitRuntimeRowLockWait(DSLContext observer, String applicationName)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      Boolean waiting =
          Objects.requireNonNull(
                  observer.fetchOne(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity"
                          + " WHERE pid <> pg_backend_pid() AND state = 'active'"
                          + " AND application_name = ? AND wait_event_type = 'Lock'"
                          + " AND query ILIKE '%game_instances%'"
                          + " AND query ILIKE '%FOR UPDATE%')",
                      applicationName),
                  "runtime lock wait query")
              .get(0, Boolean.class);
      if (Boolean.TRUE.equals(waiting)) return;
      Thread.sleep(10);
    }
    throw new AssertionError("Member acknowledgement did not wait on the held runtime row");
  }

  private record LeaseBoundMemberRepairFixture(
      CanonicalGameplayBindingInventoryRepository repository,
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget runtimeTarget,
      CanonicalGameplayBindingTransitionRequest request) {}

  private record TestSchema(String schema, DriverManagerDataSource dataSource, DSLContext dsl) {}

  private static CanonicalGameplayAccountMemberRepairOperation operation() {
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    CanonicalGameplayBindingRef bindingRef =
        CanonicalGameplayBindingRef.of(tenantId, UUID.randomUUID(), UUID.randomUUID().toString());
    UUID accountIndexFence = UUID.randomUUID();
    UUID transitionId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID coverageFence = UUID.randomUUID();
    BigInteger generation = BigInteger.valueOf(17);
    BigInteger snapshotRevision = BigInteger.valueOf(23);
    String member =
        new CanonicalGameplayAccountIndexMember(bindingRef, generation, accountIndexFence).value();
    var obligation =
        new CanonicalGameplayBindingAccountIndexObligation(
            transitionId,
            0,
            bindingRef,
            accountId,
            tenantId,
            CanonicalGameplayBindingAccountIndexObligation.Action.ADD_OR_RETAIN,
            generation,
            null,
            accountIndexFence,
            CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.BEFORE_FINAL_CAS,
            CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED,
            BigInteger.valueOf(22),
            CanonicalGameplayBindingAccountIndexObligation.ProjectionState.REQUIRED,
            null,
            null);
    return new CanonicalGameplayAccountMemberRepairOperation(
        transitionId,
        operationId,
        BigInteger.valueOf(4),
        coverageFence,
        snapshotRevision,
        bindingRef,
        accountId,
        tenantId,
        generation,
        accountIndexFence,
        obligation,
        member,
        BigInteger.valueOf(5));
  }

  private static byte[][] arguments(CanonicalGameplayAccountMemberRepairOperation operation) {
    return new byte[][] {
      ascii(operation.accountId().toString()),
      ascii(operation.tenantId().toString()),
      operation.bindingRef().bytes(),
      ascii(operation.bindingGeneration().toString()),
      ascii(operation.accountIndexFence().toString()),
      ascii(operation.member()),
      ascii(operation.transitionId().toString()),
      ascii(operation.operationId().toString()),
      ascii(operation.operationFence().toString()),
      ascii(operation.coverageFence().toString()),
      ascii(operation.inventorySnapshotRevision().toString()),
      ascii(operation.accountControlEpoch().toString())
    };
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

  private static byte[] key(UUID accountId) {
    return ascii(PREFIX + accountId);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
