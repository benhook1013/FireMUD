package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalPlayerAdmissionHoldEvidence;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL owner fixture definitions. Account peer context and original lease are
 * synthetic; the reused graph/release/preparation/activation path stipulates upstream authority.
 * There is no accepting release or canonical termination producer, no real Account finalization,
 * Game Session binding installation, protected transport composition, or live admission proof.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldCanonicalPlayerAdmissionHoldPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager manager;
  @Autowired private AutowireCapableBeanFactory beanFactory;
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void exactRetryAndReadbackRetainOriginalOpaqueIdentityEvidenceAndAllLogicalState()
      throws Exception {
    var f = fixture();
    var lease = lease(f, 15000L);
    String before = logicalRow(f);
    String previousTuple = tupleVersion(f);
    var service = service(f);
    var hold = account().call(() -> acquire(service, f, lease));
    var retry = account().call(() -> acquire(service, f, lease));
    assertThat(retry.holdId()).isEqualTo(hold.holdId());
    assertThat(retry.holdFence()).isEqualTo(hold.holdFence());
    assertThat(retry.worldEvidence().canonicalBytes())
        .isEqualTo(hold.worldEvidence().canonicalBytes());
    assertThat(retry.request().lease().canonicalJson()).isEqualTo(lease.canonicalJson());
    assertThat(
            account()
                .call(
                    () ->
                        service.read(
                            lease.canonicalJson(),
                            lease.sha256(),
                            f.activeEvidence().lifecycleEpoch(),
                            f.activeEvidence().rowVersion())))
        .contains(hold);
    assertThat(logicalRow(f)).isEqualTo(before);
    assertThat(tupleVersion(f)).isNotEqualTo(previousTuple);
    assertThat(count(f)).isEqualTo(1L);
  }

  @Test
  void diagnosticExpiryRetainsHoldAndExactRetryWithoutDeadlineRenewal() throws Exception {
    var f = fixture();
    var lease = lease(f, 5000L);
    var service = service(f);
    var hold = account().call(() -> acquire(service, f, lease));
    long deadline = hold.diagnosticExpiresAtMillis();
    long remaining = deadline - System.currentTimeMillis();
    if (remaining >= 0) TimeUnit.MILLISECONDS.sleep(remaining + 1L);
    var retry = account().call(() -> acquire(service, f, lease));
    assertThat(retry).isEqualTo(hold);
    assertThat(retry.diagnosticExpiresAtMillis()).isEqualTo(deadline);
    assertV51Denied(
        () ->
            dsl.execute(
                "UPDATE world_instance SET status='TERMINATING',"
                    + "lifecycle_epoch=lifecycle_epoch+1,row_version=row_version+1 WHERE id=?",
                f.association().worldInstanceId()));
    assertThat(count(f)).isEqualTo(1L);
  }

  @Test
  void expiredLeaseAndSubstitutedScopeOrExpectedTupleDenyWithoutStateOrHoldChanges()
      throws Exception {
    var f = fixture();
    var service = service(f);
    String before = logicalRow(f);
    String tuple = tupleVersion(f);
    var valid = lease(f, 15000L);
    account()
        .run(
            () -> {
              assertThatThrownBy(
                      () ->
                          service.acquire(
                              valid.canonicalJson(),
                              valid.sha256(),
                              f.activeEvidence().lifecycleEpoch() + 1L,
                              f.activeEvidence().rowVersion()))
                  .isInstanceOf(IllegalArgumentException.class);
              assertThatThrownBy(
                      () ->
                          service.acquire(
                              valid.canonicalJson(),
                              valid.sha256(),
                              f.activeEvidence().lifecycleEpoch(),
                              f.activeEvidence().rowVersion() + 1L))
                  .isInstanceOf(IllegalArgumentException.class);
            });
    var changedScope = new LinkedHashMap<>(scope(valid.carrier()));
    changedScope.put("worldSlug", "substituted-world");
    var changed = changed(valid, "bindingScope", changedScope);
    account()
        .run(
            () ->
                assertThatThrownBy(() -> acquire(service, f, changed))
                    .isInstanceOf(IllegalArgumentException.class));
    changedScope.put("gameInstanceId", UUID.randomUUID().toString());
    var unmapped = changed(valid, "bindingScope", changedScope);
    account()
        .run(
            () ->
                assertThatThrownBy(() -> acquire(service, f, unmapped))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("association is missing"));
    var expiredCarrier = new LinkedHashMap<>(valid.carrier());
    // Keep token valid and evaluated time unchanged, but expire this original lease.
    long now = System.currentTimeMillis();
    expiredCarrier.put("evaluatedAt", Long.toString(now - 1000L));
    expiredCarrier.put("expiresAt", Long.toString(now - 1L));
    var expired = AccountGameplayAdmissionLeaseEvidence.fromCarrier(expiredCarrier);
    account()
        .run(
            () ->
                assertThatThrownBy(() -> acquire(service, f, expired))
                    .hasMessageContaining("original lease or exact owner evidence"));
    assertThat(count(f)).isZero();
    assertThat(logicalRow(f)).isEqualTo(before);
    assertThat(tupleVersion(f)).isEqualTo(tuple);
  }

  @Test
  void retainedAttemptRejectsChangedLeaseDigestIdentityAndEpoch() throws Exception {
    var f = fixture();
    var original = lease(f, 15000L);
    var service = service(f);
    var hold = account().call(() -> acquire(service, f, original));
    String before = logicalRow(f);
    for (var changed :
        List.of(
            changed(original, "leaseFence", "2"),
            changed(original, "leaseId", UUID.randomUUID().toString()),
            changed(original, "requestId", UUID.randomUUID().toString()))) {
      account()
          .run(
              () ->
                  assertThatThrownBy(() -> acquire(service, f, changed))
                      .hasMessageContaining("original lease/attempt binding"));
    }
    account()
        .run(
            () ->
                assertThatThrownBy(
                        () ->
                            service.acquire(
                                original.canonicalJson(),
                                original.sha256(),
                                f.activeEvidence().lifecycleEpoch() + 1L,
                                f.activeEvidence().rowVersion()))
                    .hasMessageContaining("original lease/attempt binding"));
    assertThat(account().call(() -> acquire(service, f, original))).isEqualTo(hold);
    assertThat(count(f)).isEqualTo(1L);
    assertThat(logicalRow(f)).isEqualTo(before);
  }

  @Test
  void v51ItselfFencesRawLifecycleCounterMutationAndDeleteWhileReleaseStaysUnavailable()
      throws Exception {
    var f = fixture();
    var service = service(f);
    account().call(() -> acquire(service, f, lease(f, 15000L)));
    String before = logicalRow(f);
    for (String sql :
        List.of(
            "UPDATE world_instance SET status='TERMINATING',lifecycle_epoch=lifecycle_epoch+1,row_version=row_version+1 WHERE id=?",
            "UPDATE world_instance SET lifecycle_epoch=lifecycle_epoch+1 WHERE id=?",
            "UPDATE world_instance SET row_version=row_version+1 WHERE id=?",
            "DELETE FROM world_instance WHERE id=?")) {
      assertV51Denied(() -> dsl.execute(sql, f.association().worldInstanceId()));
    }
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE world_canonical_player_admission_hold SET hold_state='RELEASED' WHERE world_instance_id=?",
                    f.association().worldInstanceId()))
        .hasMessageContaining("dual-owner release is unavailable");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM world_canonical_player_admission_hold WHERE world_instance_id=?",
                    f.association().worldInstanceId()))
        .hasMessageContaining("dual-owner release is unavailable");
    assertThatThrownBy(() -> dsl.execute("TRUNCATE world_canonical_player_admission_hold"))
        .hasMessageContaining("original evidence are retained");
    account()
        .run(
            () ->
                assertThatThrownBy(service::release)
                    .hasMessageContaining("release is unavailable"));
    assertThat(count(f)).isEqualTo(1L);
    assertThat(logicalRow(f)).isEqualTo(before);
  }

  @Test
  void twoConcurrentExactAcquisitionsRetainOneIdentity() throws Exception {
    var f = fixture();
    var original = lease(f, 15000L);
    var service = service(f);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<WorldCanonicalPlayerAdmissionHoldEvidence> operation =
          () -> {
            ready.countDown();
            await(start);
            return account().call(() -> acquire(service, f, original));
          };
      var first = executor.submit(operation);
      var second = executor.submit(operation);
      await(ready);
      start.countDown();
      assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
      assertThat(count(f)).isEqualTo(1L);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void independentPlayerAttemptsPreserveEveryExistingHoldAndDoNotOpenGenericNoOpBypass()
      throws Exception {
    var f = fixture();
    var service = service(f);
    var firstLease = lease(f, 15000L);
    var first = account().call(() -> acquire(service, f, firstLease));
    String before = logicalRow(f);
    var second = account().call(() -> acquire(service, f, lease(f, 15000L)));
    assertThat(second.holdId()).isNotEqualTo(first.holdId());
    assertThat(second.holdFence()).isNotEqualTo(first.holdFence());
    assertThat(account().call(() -> acquire(service, f, firstLease))).isEqualTo(first);
    assertThat(count(f)).isEqualTo(2L);
    assertThat(logicalRow(f)).isEqualTo(before);
    // V51's exact no-op exception is restricted to the actual insertion transaction. An ordinary
    // later caller still reaches the pre-existing V35 canonical-write denial.
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE world_instance SET row_version=row_version WHERE id=?",
                    f.association().worldInstanceId()))
        .hasMessageContaining("no exact transaction execution manifest");
  }

  @Test
  void staleRepeatableReadSnapshotCannotMissCommittedHoldTupleVersion() throws Exception {
    assertStaleSnapshotFails(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  @Test
  void staleSerializableSnapshotCannotMissCommittedHoldTupleVersion() throws Exception {
    assertStaleSnapshotFails(TransactionDefinition.ISOLATION_SERIALIZABLE);
  }

  private void assertStaleSnapshotFails(int isolation) throws Exception {
    var f = fixture();
    String before = logicalRow(f);
    var snapshotRead = new CountDownLatch(1);
    var holdCommitted = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var stale =
          executor.submit(
              () -> {
                try {
                  transaction(isolation)
                      .execute(
                          status -> {
                            assertThat(logicalRow(f)).isEqualTo(before);
                            assertThat(count(f)).isZero();
                            snapshotRead.countDown();
                            await(holdCommitted);
                            dsl.execute(
                                "UPDATE world_instance SET status='TERMINATING',lifecycle_epoch=lifecycle_epoch+1,"
                                    + "row_version=row_version+1 WHERE id=?",
                                f.association().worldInstanceId());
                            return null;
                          });
                  throw new AssertionError(
                      "Stale snapshot lifecycle change unexpectedly committed");
                } catch (RuntimeException failure) {
                  return sqlState(failure);
                } finally {
                  snapshotRead.countDown();
                }
              });
      await(snapshotRead);
      account().call(() -> acquire(service(f), f, lease(f, 15000L)));
      holdCommitted.countDown();
      assertThat(stale.get(15, TimeUnit.SECONDS)).isEqualTo("40001");
      assertThat(logicalRow(f)).isEqualTo(before);
      assertThat(count(f)).isEqualTo(1L);
    } finally {
      holdCommitted.countDown();
      executor.shutdownNow();
    }
  }

  private WorldDraftGraphApplicationPostgresIntegrationTest.ActivePlayerAdmissionFixture fixture() {
    var owner = new WorldDraftGraphApplicationPostgresIntegrationTest();
    beanFactory.autowireBean(owner);
    return owner.activePlayerAdmissionFixture();
  }

  private WorldCanonicalPlayerAdmissionHoldService service(
      WorldDraftGraphApplicationPostgresIntegrationTest.ActivePlayerAdmissionFixture f) {
    var associations =
        new WorldCanonicalInstanceAssociationRepository(
            dsl,
            new WorldCompleteLaunchBindingRepository(dsl),
            new WorldAuthoredSourceIntakeRepository(dsl),
            new WorldAuthoredVersionIdentityRepository(dsl));
    return new WorldCanonicalPlayerAdmissionHoldService(
        new WorldCanonicalPlayerAdmissionHoldRepository(
            dsl, manager, "firemud", associations, f.lifecycleRepository()),
        "firemud");
  }

  private static WorldCanonicalPlayerAdmissionHoldEvidence acquire(
      WorldCanonicalPlayerAdmissionHoldService service,
      WorldDraftGraphApplicationPostgresIntegrationTest.ActivePlayerAdmissionFixture f,
      AccountGameplayAdmissionLeaseEvidence lease) {
    return service.acquire(
        lease.canonicalJson(),
        lease.sha256(),
        f.activeEvidence().lifecycleEpoch(),
        f.activeEvidence().rowVersion());
  }

  /** Synthetic complete original Account carrier, with no current source or terminal authority. */
  private static AccountGameplayAdmissionLeaseEvidence lease(
      WorldDraftGraphApplicationPostgresIntegrationTest.ActivePlayerAdmissionFixture f,
      long duration) {
    var identity = f.activeEvidence().request();
    String account = UUID.randomUUID().toString();
    String tenant = identity.canonicalTenantId().toString();
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accountId", account);
    scope.put("tenantId", tenant);
    scope.put("realmId", UUID.randomUUID().toString());
    scope.put("worldSlug", identity.worldSlug());
    scope.put("realmSlug", "synthetic-realm");
    scope.put("playableStateNamespaceId", identity.playableStateNamespaceId().toString());
    scope.put("playableStateScope", identity.playableStateScope());
    scope.put("gameInstanceId", identity.canonicalGameInstanceId().toString());
    for (String key : List.of("characterId", "sessionId", "regionId"))
      scope.put(key, UUID.randomUUID().toString());
    for (String key :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch"))
      scope.put(key, "9007199254740993");
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    value.put("schemaVersion", "1");
    value.put("mode", "PUBLIC_PRODUCTION");
    value.put("targetNamespace", "firemud");
    value.put("callerWorkload", "spiffe://firemud/ns/firemud/sa/game-session-service");
    value.put("requestId", UUID.randomUUID().toString());
    value.put("leaseId", UUID.randomUUID().toString());
    value.put("leaseFence", "1");
    value.put("leaseKind", "NEW_BINDING");
    value.put("bindingScope", scope);
    value.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration",
            "1",
            "accountAuthorityGeneration",
            "1",
            "tenantAuthorityGeneration",
            Map.of(tenant, "1"),
            "membershipAuthorityGeneration",
            Map.of(tenant, "1"),
            "privateRealmGrantVersions",
            List.of()));
    value.put("issuanceFence", "1");
    value.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(tenant, "1"),
            "membershipAuthorityGeneration",
            "1"));
    value.put(
        "outboxCheckpoints",
        List.of(
            checkpoint("account/" + account, "0"),
            checkpoint("issuer/firemud-account-service", "0"),
            checkpoint("membership/" + account + "/" + tenant, "1"),
            checkpoint("tenant/" + tenant, "1")));
    long now = System.currentTimeMillis();
    value.put(
        "tokenIdentityEvidence",
        Map.ofEntries(
            Map.entry("accountId", account),
            Map.entry("operationId", UUID.randomUUID().toString()),
            Map.entry("issuanceRequestId", UUID.randomUUID().toString()),
            Map.entry("tokenJti", UUID.randomUUID().toString()),
            Map.entry("tokenSHA256", "a".repeat(64)),
            Map.entry("tokenGeneration", "1"),
            Map.entry("issuanceFence", "1"),
            Map.entry("tokenIdentityFence", "1"),
            Map.entry("tokenProfile", "game-session-account-delegation"),
            Map.entry("issuedAt", Long.toString(now / 1000 - 2)),
            Map.entry("notBefore", Long.toString(now / 1000 - 2)),
            Map.entry("expiresAt", Long.toString(now / 1000 + 300))));
    value.put("evaluatedAt", Long.toString(now));
    value.put("expiresAt", Long.toString(now + duration));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(value);
  }

  private static AccountGameplayAdmissionLeaseEvidence changed(
      AccountGameplayAdmissionLeaseEvidence original, String field, Object value) {
    var carrier = new LinkedHashMap<>(original.carrier());
    carrier.put(field, value);
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> scope(Map<String, Object> carrier) {
    return (Map<String, Object>) carrier.get("bindingScope");
  }

  private static Map<String, Object> checkpoint(String suffix, String sequence) {
    return Map.of(
        "outboxStreamKey", "account:auth-authority:v1:" + suffix, "outboxSequence", sequence);
  }

  private long count(
      WorldDraftGraphApplicationPostgresIntegrationTest.ActivePlayerAdmissionFixture f) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT count(*) FROM world_canonical_player_admission_hold WHERE world_instance_id=?",
                f.association().worldInstanceId()))
        .get(0, Long.class);
  }

  private String logicalRow(
      WorldDraftGraphApplicationPostgresIntegrationTest.ActivePlayerAdmissionFixture f) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT to_jsonb(w)::text FROM world_instance w WHERE id=?",
                f.association().worldInstanceId()))
        .get(0, String.class);
  }

  private String tupleVersion(
      WorldDraftGraphApplicationPostgresIntegrationTest.ActivePlayerAdmissionFixture f) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT xmin::text FROM world_instance WHERE id=?",
                f.association().worldInstanceId()))
        .get(0, String.class);
  }

  private TransactionTemplate transaction(int isolation) {
    var transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(isolation);
    return transaction;
  }

  private static Context account() {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/firemud/sa/account-service")
                .orElseThrow());
  }

  private static void assertV51Denied(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
    assertThatThrownBy(operation)
        .hasMessageContaining("fenced by a retained player-admission hold")
        .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"));
  }

  private static String sqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql) return sql.getSQLState();
    }
    throw new AssertionError("Expected real PostgreSQL SQLSTATE", failure);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new AssertionError("Owner fixture coordination timed out");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Owner fixture interrupted", failure);
    }
  }
}
