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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Physical Coordination Redis and PostgreSQL composition proof using only a controlled local
 * signer. It proves the local CLOSED-write/readback algorithm, not genuine attestor custody,
 * operational OPEN authorization, an SQL installation receipt, or account-wide coverage.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayAccountClosedBarrierRedisOwnerIntegrationTest {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String PREFIX = "session:game:account-admission:";
  private static final int PORT = 6379;
  private static final String KEY_ID = "controlled-barrier-attestor";
  private static final String ATTESTOR_BINDING = "controlled-local-barrier-attestor";
  private static final String ACCEPTED_ATTESTATION_DIGEST = "sha256:" + "a".repeat(64);
  private static final String ACL_DIGEST = "sha256:" + "b".repeat(64);
  private static final String CONFIG_DIGEST = "sha256:" + "c".repeat(64);
  private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(PORT);

  @Test
  void installsExactClosedBarrierRetriesAndFailsClosedOnLossConflictAndChangedIncarnation()
      throws Exception {
    try (Fixture fixture = Fixture.open();
        RedisFixture redis = RedisFixture.open()) {
      var initialReceiver = fixture.receiver(new ControlledSigner(fixture.keyPair(), false));
      var assignment =
          fixture.tagRepository().assignmentForBarrierTarget(fixture.intent().operationId(), 0L);
      String key = CanonicalGameplaySessionRedisKeys.accountAdmissionBarrier(assignment);
      byte[] redisKey = ascii(key);
      var initialProof = initialReceiver.observeForFenceTarget(fixture.intent().operationId(), 0L);

      try {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(
                () ->
                    fixture
                        .owner(initialReceiver, redis)
                        .installAndRead(fixture.intent().operationId(), 0L))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clear();
      }
      assertThat(redis.admin().type(redisKey)).isEqualTo("none");
      try {
        TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(
                () ->
                    fixture
                        .owner(initialReceiver, redis)
                        .installAndRead(fixture.intent().operationId(), 0L))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      assertThat(redis.admin().type(redisKey)).isEqualTo("none");

      // Simulate a valid lower-epoch OPEN hash. The actual operation fence is seeded at 10 by the
      // test fixture's existing NO_ACTIVE control high-water; the script must replace it CLOSED.
      Map<String, String> previousOpen =
          withPriorOperation(barrierFields(fixture, assignment, initialProof, "9", "OPEN"));
      redis.admin().hset(redisKey, encoded(previousOpen));
      var firstOwner = fixture.owner(initialReceiver, redis);
      var installed = firstOwner.installAndRead(fixture.intent().operationId(), 0L);
      assertThat(fixture.intent().operationFence()).isEqualTo(java.math.BigInteger.TEN);
      assertThat(installed.mutationOutcome())
          .isEqualTo(
              CanonicalGameplayAccountClosedBarrierRedisOwner.MutationOutcome.ADVANCED_TO_CLOSED);
      assertThat(installed.key()).isEqualTo(key);
      assertThat(installed.barrierFields())
          .containsEntry("ownerOperationId", fixture.intent().operationId().toString())
          .containsEntry("ownerFenceEpoch", "10")
          .containsEntry("incarnationDigest", initialProof.incarnationDigest())
          .containsEntry("state", "CLOSED");
      assertExactHash(redis.admin(), redisKey, installed.barrierFields());
      assertThat(
              redis
                  .admin()
                  .type(ascii("session:game:index:account:" + fixture.intent().accountId())))
          .isEqualTo("none");

      // A lower CLOSED predecessor also advances; a restarted owner retries the same SQL tuple.
      Map<String, String> previousClosed =
          withPriorOperation(barrierFields(fixture, assignment, initialProof, "9", "CLOSED"));
      redis.admin().hset(redisKey, encoded(previousClosed));
      var advancedClosed =
          fixture
              .owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
              .installAndRead(fixture.intent().operationId(), 0L);
      assertThat(advancedClosed.mutationOutcome())
          .isEqualTo(
              CanonicalGameplayAccountClosedBarrierRedisOwner.MutationOutcome.ADVANCED_TO_CLOSED);
      assertThat(advancedClosed.barrierFields()).isEqualTo(installed.barrierFields());

      var exactRetry =
          fixture
              .owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
              .installAndRead(fixture.intent().operationId(), 0L);
      assertThat(exactRetry.mutationOutcome())
          .isEqualTo(
              CanonicalGameplayAccountClosedBarrierRedisOwner.MutationOutcome.EXACT_CLOSED_REPLAY);
      assertThat(exactRetry.barrierFields()).isEqualTo(installed.barrierFields());

      Map<String, String> equalEpochOpen = new LinkedHashMap<>(installed.barrierFields());
      equalEpochOpen.put("state", "OPEN");
      redis.admin().del(List.of(redisKey).toArray(byte[][]::new));
      redis.admin().hset(redisKey, encoded(equalEpochOpen));
      assertThatThrownBy(
              () ->
                  fixture
                      .owner(
                          fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
                      .installAndRead(fixture.intent().operationId(), 0L))
          .isInstanceOf(IllegalStateException.class);
      assertExactHash(redis.admin(), redisKey, equalEpochOpen);

      Map<String, String> equalEpochConflictingOwner =
          new LinkedHashMap<>(installed.barrierFields());
      equalEpochConflictingOwner.put("ownerOperationId", UUID.randomUUID().toString());
      redis.admin().del(List.of(redisKey).toArray(byte[][]::new));
      redis.admin().hset(redisKey, encoded(equalEpochConflictingOwner));
      assertThatThrownBy(
              () ->
                  fixture
                      .owner(
                          fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
                      .installAndRead(fixture.intent().operationId(), 0L))
          .isInstanceOf(IllegalStateException.class);
      assertExactHash(redis.admin(), redisKey, equalEpochConflictingOwner);

      Map<String, String> unknownField = new LinkedHashMap<>(installed.barrierFields());
      unknownField.put("operatorExtension", "preserve");
      redis.admin().del(List.of(redisKey).toArray(byte[][]::new));
      redis.admin().hset(redisKey, encoded(unknownField));
      assertThatThrownBy(
              () ->
                  fixture
                      .owner(
                          fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
                      .installAndRead(fixture.intent().operationId(), 0L))
          .isInstanceOf(IllegalStateException.class);
      assertThat(stringMap(redis.admin().hgetall(redisKey))).isEqualTo(unknownField);
      assertThat(redis.admin().type(redisKey)).isEqualTo("hash");
      assertThat(redis.admin().pttl(redisKey)).isEqualTo(-1L);
      assertThat(redis.admin().hlen(redisKey)).isEqualTo(10L);

      // A lost Redis key may be recreated CLOSED under the same original operation and fence.
      redis.admin().del(List.of(redisKey).toArray(byte[][]::new));
      var recreated =
          fixture
              .owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
              .installAndRead(fixture.intent().operationId(), 0L);
      assertThat(recreated.mutationOutcome())
          .isEqualTo(
              CanonicalGameplayAccountClosedBarrierRedisOwner.MutationOutcome.CREATED_CLOSED);
      assertThat(recreated.barrierFields()).isEqualTo(installed.barrierFields());

      // The write may have happened, but changed protected evidence on the post-read observation
      // prevents an owner readback capability from being returned. The stored barrier stays closed.
      var changingReceiver = fixture.receiver(new ControlledSigner(fixture.keyPair(), true));
      assertThatThrownBy(
              () ->
                  fixture
                      .owner(changingReceiver, redis)
                      .installAndRead(fixture.intent().operationId(), 0L))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("CLOSED account barrier is unavailable");
      assertExactHash(redis.admin(), redisKey, installed.barrierFields());

      // A newer OPEN owner/fence is never overwritten by this stale installer.
      Map<String, String> newerOpen =
          new LinkedHashMap<>(barrierFields(fixture, assignment, initialProof, "11", "OPEN"));
      newerOpen.put("ownerOperationId", UUID.randomUUID().toString());
      redis.admin().hset(redisKey, encoded(newerOpen));
      assertThatThrownBy(
              () ->
                  fixture
                      .owner(
                          fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
                      .installAndRead(fixture.intent().operationId(), 0L))
          .isInstanceOf(IllegalStateException.class);
      assertExactHash(redis.admin(), redisKey, newerOpen);

      // Wrong Redis type, foreign scope, and TTL-bearing hashes are non-mutating refusals.
      redis.admin().del(List.of(redisKey).toArray(byte[][]::new));
      redis.admin().set(redisKey, ascii("operator-owned"));
      assertThatThrownBy(
              () ->
                  fixture
                      .owner(
                          fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
                      .installAndRead(fixture.intent().operationId(), 0L))
          .isInstanceOf(IllegalStateException.class);
      assertThat(redis.admin().get(redisKey)).isEqualTo(ascii("operator-owned"));

      redis.admin().del(List.of(redisKey).toArray(byte[][]::new));
      Map<String, String> foreignScope =
          new LinkedHashMap<>(barrierFields(fixture, assignment, initialProof, "9", "CLOSED"));
      foreignScope.put("tenantId", UUID.randomUUID().toString());
      redis.admin().hset(redisKey, encoded(foreignScope));
      assertThatThrownBy(
              () ->
                  fixture
                      .owner(
                          fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
                      .installAndRead(fixture.intent().operationId(), 0L))
          .isInstanceOf(IllegalStateException.class);
      assertExactHash(redis.admin(), redisKey, foreignScope);

      redis.admin().del(List.of(redisKey).toArray(byte[][]::new));
      redis.admin().hset(redisKey, encoded(previousClosed));
      redis.admin().pexpire(redisKey, 60_000L);
      Map<byte[], byte[]> expiringBefore = redis.admin().hgetall(redisKey);
      assertThatThrownBy(
              () ->
                  fixture
                      .owner(
                          fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
                      .installAndRead(fixture.intent().operationId(), 0L))
          .isInstanceOf(IllegalStateException.class);
      assertThat(stringMap(redis.admin().hgetall(redisKey))).isEqualTo(stringMap(expiringBefore));
      assertThat(redis.admin().pttl(redisKey)).isPositive();

      assertThatThrownBy(
              () ->
                  fixture
                      .owner(
                          fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
                      .installAndRead(UUID.randomUUID(), 0L))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () ->
                  fixture
                      .owner(
                          fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
                      .installAndRead(fixture.intent().operationId(), 1L))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void comparesVeryLargeFenceEpochsAsCanonicalDecimalStrings() throws Exception {
    java.math.BigInteger highWater =
        new java.math.BigInteger("900719925474099312345678901234567890");
    try (Fixture fixture = Fixture.open(highWater);
        RedisFixture redis = RedisFixture.open()) {
      var receiver = fixture.receiver(new ControlledSigner(fixture.keyPair(), false));
      var assignment =
          fixture.tagRepository().assignmentForBarrierTarget(fixture.intent().operationId(), 0L);
      byte[] key = ascii(CanonicalGameplaySessionRedisKeys.accountAdmissionBarrier(assignment));
      var proof = receiver.observeForFenceTarget(fixture.intent().operationId(), 0L);
      java.math.BigInteger fence = fixture.intent().operationFence();
      assertThat(fence).isGreaterThan(new java.math.BigInteger("9007199254740992"));

      String priorEpoch = fence.subtract(java.math.BigInteger.ONE).toString();
      Map<String, String> lowerClosed =
          withPriorOperation(barrierFields(fixture, assignment, proof, priorEpoch, "CLOSED"));
      redis.admin().hset(key, encoded(lowerClosed));

      var advanced =
          fixture.owner(receiver, redis).installAndRead(fixture.intent().operationId(), 0L);
      String exactFence = fixture.intent().operationFence().toString();
      assertThat(exactFence).matches("[1-9][0-9]*");
      assertThat(advanced.mutationOutcome())
          .isEqualTo(
              CanonicalGameplayAccountClosedBarrierRedisOwner.MutationOutcome.ADVANCED_TO_CLOSED);
      assertThat(advanced.barrierFields().get("ownerFenceEpoch")).isEqualTo(exactFence);

      var replay =
          fixture
              .owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
              .installAndRead(fixture.intent().operationId(), 0L);
      assertThat(replay.mutationOutcome())
          .isEqualTo(
              CanonicalGameplayAccountClosedBarrierRedisOwner.MutationOutcome.EXACT_CLOSED_REPLAY);
      assertThat(replay.barrierFields().get("ownerFenceEpoch")).isEqualTo(exactFence);

      String greaterEpoch =
          fixture.intent().operationFence().add(java.math.BigInteger.ONE).toString();
      Map<String, String> newerOwner =
          new LinkedHashMap<>(barrierFields(fixture, assignment, proof, greaterEpoch, "OPEN"));
      newerOwner.put("ownerOperationId", UUID.randomUUID().toString());
      redis.admin().hset(key, encoded(newerOwner));
      assertThatThrownBy(
              () ->
                  fixture
                      .owner(
                          fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
                      .installAndRead(fixture.intent().operationId(), 0L))
          .isInstanceOf(IllegalStateException.class);
      assertThat(stringMap(redis.admin().hgetall(key))).isEqualTo(newerOwner);
      assertThat(newerOwner.get("ownerFenceEpoch")).isEqualTo(greaterEpoch);
      assertThat(greaterEpoch).matches("[1-9][0-9]*");
    }
  }

  @Test
  void closesPriorOpenAtZeroFromPositiveControlAndRejectsPriorClosedAtZero() throws Exception {
    try (Fixture fixture = Fixture.open(java.math.BigInteger.ZERO);
        RedisFixture redis = RedisFixture.open()) {
      assertThat(fixture.intent().operationFence()).isEqualTo(java.math.BigInteger.ONE);
      var receiver = fixture.receiver(new ControlledSigner(fixture.keyPair(), false));
      var assignment =
          fixture.tagRepository().assignmentForBarrierTarget(fixture.intent().operationId(), 0L);
      byte[] key = ascii(CanonicalGameplaySessionRedisKeys.accountAdmissionBarrier(assignment));
      var proof = receiver.observeForFenceTarget(fixture.intent().operationId(), 0L);

      // This controlled prior hash demonstrates the script's epoch-zero OPEN handling. It is not
      // evidence that an actual OPEN producer installed the fixture value.
      Map<String, String> priorOpen =
          withPriorOperation(barrierFields(fixture, assignment, proof, "0", "OPEN"));
      redis.admin().hset(key, encoded(priorOpen));
      var installed =
          fixture.owner(receiver, redis).installAndRead(fixture.intent().operationId(), 0L);
      assertThat(installed.mutationOutcome())
          .isEqualTo(
              CanonicalGameplayAccountClosedBarrierRedisOwner.MutationOutcome.ADVANCED_TO_CLOSED);
      assertThat(installed.barrierFields())
          .containsEntry("ownerFenceEpoch", "1")
          .containsEntry("state", "CLOSED");
      assertExactHash(redis.admin(), key, installed.barrierFields());

      var retry =
          fixture
              .owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
              .installAndRead(fixture.intent().operationId(), 0L);
      assertThat(retry.mutationOutcome())
          .isEqualTo(
              CanonicalGameplayAccountClosedBarrierRedisOwner.MutationOutcome.EXACT_CLOSED_REPLAY);
      assertThat(retry.barrierFields()).isEqualTo(installed.barrierFields());

      Map<String, String> priorClosed =
          withPriorOperation(barrierFields(fixture, assignment, proof, "0", "CLOSED"));
      redis.admin().del(List.of(key).toArray(byte[][]::new));
      redis.admin().hset(key, encoded(priorClosed));
      assertThatThrownBy(
              () ->
                  fixture
                      .owner(
                          fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
                      .installAndRead(fixture.intent().operationId(), 0L))
          .isInstanceOf(IllegalStateException.class);
      assertExactHash(redis.admin(), key, priorClosed);
    }
  }

  @Test
  void acceptsOneClosedReceiptAndExactRetryReturnsTheOriginalSqlPostimage() throws Exception {
    try (Fixture fixture = Fixture.open();
        RedisFixture redis = RedisFixture.open()) {
      UUID operationId = fixture.intent().operationId();
      var sqlOwner =
          new CanonicalGameplayAccountAdmissionBarrierRepository(
              fixture.dsl, Clock.fixed(NOW, ZoneOffset.UTC));

      var firstReadback =
          fixture
              .owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
              .installAndRead(operationId, 0L);
      // Deliberately discard the first acceptance result; this models loss of the caller's local
      // result only, not a simulated network or response-loss event.
      sqlOwner.acceptClosedBarrierReadback(operationId, 0L, firstReadback);
      String originalPostimage = closedReceiptPostimage(fixture, operationId, 0L);

      var freshRetryReadback =
          fixture
              .owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
              .installAndRead(operationId, 0L);
      var restartedSqlOwner =
          new CanonicalGameplayAccountAdmissionBarrierRepository(
              fixture.dsl, Clock.fixed(NOW, ZoneOffset.UTC));
      StoredClosedBarrierReceipt retry =
          restartedSqlOwner.acceptClosedBarrierReadback(operationId, 0L, freshRetryReadback);

      assertThat(retry.operationId()).isEqualTo(operationId);
      assertThat(retry.barrierState()).isEqualTo("CLOSED");
      assertThat(closedReceiptCount(fixture, operationId)).isEqualTo(1L);
      assertThat(closedReceiptPostimage(fixture, operationId, 0L)).isEqualTo(originalPostimage);

      assertThatThrownBy(
              () ->
                  restartedSqlOwner.acceptClosedBarrierReadback(
                      UUID.randomUUID(), 0L, freshRetryReadback))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThatThrownBy(
              () ->
                  restartedSqlOwner.acceptClosedBarrierReadback(
                      operationId, 1L, freshRetryReadback))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThat(closedReceiptCount(fixture, operationId)).isEqualTo(1L);
      assertThat(closedReceiptPostimage(fixture, operationId, 0L)).isEqualTo(originalPostimage);

      // The receipt is as-of evidence only. SQL remains ACQUISITION_INTENT and the target remains
      // REQUIRED; this test does not publish INSTALLED or imply current physical presence later.
      assertThat(
              fixture
                  .dsl
                  .fetchSingle(
                      "SELECT state FROM game_session_canonical_account_coverage_control"
                          + " WHERE account_id = ?",
                      fixture.intent().accountId())
                  .get("state", String.class))
          .isEqualTo("ACQUISITION_INTENT");
      assertThat(
              fixture
                  .dsl
                  .fetchSingle(
                      "SELECT target_state FROM"
                          + " game_session_canonical_account_admission_fence_intent_barrier_target"
                          + " WHERE operation_id = ? AND target_ordinal = 0",
                      operationId)
                  .get("target_state", String.class))
          .isEqualTo("REQUIRED");
    }
  }

  @Test
  void acceptsClosedReadbackAfterConcurrentPrepareCommitsWhileWaitingOnInventoryClock()
      throws Exception {
    try (Fixture fixture = Fixture.open();
        RedisFixture redis = RedisFixture.open()) {
      UUID operationId = fixture.intent().operationId();
      var readback =
          fixture
              .owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
              .installAndRead(operationId, 0L);

      String prepareApplicationName =
          "gs_closed_receipt_prepare_" + UUID.randomUUID().toString().replace("-", "");
      String acceptApplicationName =
          "gs_closed_receipt_accept_" + UUID.randomUUID().toString().replace("-", "");
      DSLContext prepareDsl = fixture.dslForApplication(prepareApplicationName);
      DSLContext acceptDsl = fixture.dslForApplication(acceptApplicationName);

      // A distinct account can still prepare while this account's acquisition intent is pending.
      // That real owner operation advances the shared inventory revision.
      var runtime =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              fixture.dataSource, UUID.randomUUID());
      UUID otherAccountId = UUID.randomUUID();
      CanonicalGameplayBindingIdentity otherCandidate =
          new CanonicalGameplayBindingIdentity(
              otherAccountId,
              runtime.canonicalTenantId(),
              runtime.playableStateNamespaceId(),
              UUID.randomUUID(),
              "SHARED",
              runtime.gameInstanceUuid(),
              runtime.gameInstanceId(),
              UUID.randomUUID().toString(),
              UUID.randomUUID(),
              java.math.BigInteger.ONE,
              GameSessionAccountDelegationProfile.ISSUER,
              java.math.BigInteger.ONE,
              java.math.BigInteger.ONE,
              java.math.BigInteger.valueOf(16),
              java.math.BigInteger.valueOf(32));
      CanonicalGameplayBindingTransitionRequest otherRequest =
          new CanonicalGameplayBindingTransitionRequest(
              UUID.randomUUID(),
              UUID.randomUUID(),
              otherCandidate,
              new CanonicalIssuerReservationFenceEvidence(
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  java.math.BigInteger.ONE,
                  java.math.BigInteger.ONE,
                  java.math.BigInteger.ONE),
              null,
              null);
      java.math.BigInteger startingRevision =
          Objects.requireNonNull(
                  fixture
                      .dsl
                      .fetchSingle(
                          "SELECT inventory_revision FROM"
                              + " game_session_canonical_binding_inventory_clock"
                              + " WHERE singleton_id = 1")
                      .get("inventory_revision", java.math.BigDecimal.class),
                  "starting inventory revision")
              .toBigIntegerExact();

      CountDownLatch inventoryClockHeld = new CountDownLatch(1);
      CountDownLatch releaseInventoryClock = new CountDownLatch(1);
      try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
        Future<?> clockHolder =
            executor.submit(
                () ->
                    fixture.dsl.transactionResult(
                        configuration -> {
                          DSLContext transaction = DSL.using(configuration);
                          if (transaction.fetchOne(
                                  "SELECT inventory_revision FROM"
                                      + " game_session_canonical_binding_inventory_clock"
                                      + " WHERE singleton_id = 1 FOR UPDATE")
                              == null) {
                            throw new IllegalStateException("Inventory clock row is missing");
                          }
                          inventoryClockHeld.countDown();
                          awaitLatch(releaseInventoryClock);
                          return Boolean.TRUE;
                        }));
        try {
          assertThat(inventoryClockHeld.await(5, TimeUnit.SECONDS)).isTrue();
          Future<?> prepare =
              executor.submit(
                  () ->
                      new CanonicalGameplayBindingInventoryRepository(prepareDsl)
                          .prepare(otherRequest));
          awaitInventoryClockLockWait(fixture.dsl, prepareApplicationName);

          Future<StoredClosedBarrierReceipt> accepted =
              executor.submit(
                  () ->
                      new CanonicalGameplayAccountAdmissionBarrierRepository(
                              acceptDsl, Clock.fixed(NOW, ZoneOffset.UTC))
                          .acceptClosedBarrierReadback(operationId, 0L, readback));
          awaitInventoryClockLockWait(fixture.dsl, acceptApplicationName);

          // PostgreSQL's row-lock queue releases the actual prepare first, then lets the receipt
          // owner take a fresh READ COMMITTED snapshot after that commit.
          releaseInventoryClock.countDown();
          prepare.get(10, TimeUnit.SECONDS);
          assertThat(accepted.get(10, TimeUnit.SECONDS).operationId()).isEqualTo(operationId);
        } finally {
          releaseInventoryClock.countDown();
          clockHolder.get(10, TimeUnit.SECONDS);
        }
      }

      java.math.BigInteger endingRevision =
          Objects.requireNonNull(
                  fixture
                      .dsl
                      .fetchSingle(
                          "SELECT inventory_revision FROM"
                              + " game_session_canonical_binding_inventory_clock"
                              + " WHERE singleton_id = 1")
                      .get("inventory_revision", java.math.BigDecimal.class),
                  "ending inventory revision")
              .toBigIntegerExact();
      assertThat(endingRevision).isEqualTo(startingRevision.add(java.math.BigInteger.ONE));
      assertThat(closedReceiptCount(fixture, operationId)).isEqualTo(1L);
    }
  }

  @Test
  void expiredTrustedAcceptanceClockCannotReplaceAnExistingClosedReceipt() throws Exception {
    try (Fixture fixture = Fixture.open();
        RedisFixture redis = RedisFixture.open()) {
      UUID operationId = fixture.intent().operationId();
      var actualOwner =
          fixture.owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis);
      var validReadback = actualOwner.installAndRead(operationId, 0L);
      var currentSqlOwner =
          new CanonicalGameplayAccountAdmissionBarrierRepository(
              fixture.dsl, Clock.fixed(NOW, ZoneOffset.UTC));
      currentSqlOwner.acceptClosedBarrierReadback(operationId, 0L, validReadback);
      String originalPostimage = closedReceiptPostimage(fixture, operationId, 0L);

      // The Redis owner still performs an actual install/readback. Acceptance occurs at the exact
      // expiry boundary under a controlled trusted clock, so the stale capability cannot overwrite.
      var nowExpiredReadback =
          fixture
              .owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
              .installAndRead(operationId, 0L);
      var expiredSqlOwner =
          new CanonicalGameplayAccountAdmissionBarrierRepository(
              fixture.dsl, Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
      assertThatThrownBy(
              () ->
                  expiredSqlOwner.acceptClosedBarrierReadback(operationId, 0L, nowExpiredReadback))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("expired");
      assertThat(closedReceiptCount(fixture, operationId)).isEqualTo(1L);
      assertThat(closedReceiptPostimage(fixture, operationId, 0L)).isEqualTo(originalPostimage);
    }
  }

  @Test
  void changedCurrentControlOwnerCannotAddOrReplaceTheClosedReceipt() throws Exception {
    try (Fixture fixture = Fixture.open();
        RedisFixture redis = RedisFixture.open()) {
      UUID operationId = fixture.intent().operationId();
      var actualOwner =
          fixture.owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis);
      var initial = actualOwner.installAndRead(operationId, 0L);
      var sqlOwner =
          new CanonicalGameplayAccountAdmissionBarrierRepository(
              fixture.dsl, Clock.fixed(NOW, ZoneOffset.UTC));
      sqlOwner.acceptClosedBarrierReadback(operationId, 0L, initial);
      String originalPostimage = closedReceiptPostimage(fixture, operationId, 0L);

      var staleCurrentProof = actualOwner.installAndRead(operationId, 0L);
      // Controlled SQL displacement is a refusal fixture, not a claimed release lifecycle.
      fixture.dsl.execute(
          "UPDATE game_session_canonical_account_coverage_control"
              + " SET state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW', current_operation_id = NULL,"
              + " current_fence_intent_id = NULL, current_operation_fence = NULL,"
              + " account_admission_fence = NULL, historical_account_admission_fence = NULL,"
              + " coverage_fence = NULL, inventory_snapshot_revision = NULL,"
              + " coverage_generation = NULL WHERE account_id = ?",
          fixture.intent().accountId());

      assertThatThrownBy(
              () -> sqlOwner.acceptClosedBarrierReadback(operationId, 0L, staleCurrentProof))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThat(closedReceiptCount(fixture, operationId)).isEqualTo(1L);
      assertThat(closedReceiptPostimage(fixture, operationId, 0L)).isEqualTo(originalPostimage);
    }
  }

  @Test
  void changedPersistedCohortCannotAddOrReplaceTheClosedReceipt() throws Exception {
    try (Fixture fixture = Fixture.open();
        RedisFixture redis = RedisFixture.open()) {
      UUID operationId = fixture.intent().operationId();
      var actualOwner =
          fixture.owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis);
      var initial = actualOwner.installAndRead(operationId, 0L);
      var sqlOwner =
          new CanonicalGameplayAccountAdmissionBarrierRepository(
              fixture.dsl, Clock.fixed(NOW, ZoneOffset.UTC));
      sqlOwner.acceptClosedBarrierReadback(operationId, 0L, initial);
      String originalPostimage = closedReceiptPostimage(fixture, operationId, 0L);

      var staleCurrentProof = actualOwner.installAndRead(operationId, 0L);
      fixture.dsl.execute(
          "UPDATE game_session_canonical_account_admission_fence_intent"
              + " SET cohort_digest = CASE WHEN cohort_digest = ? THEN ? ELSE ? END"
              + " WHERE operation_id = ?",
          "sha256:" + "f".repeat(64),
          "sha256:" + "e".repeat(64),
          "sha256:" + "f".repeat(64),
          operationId);

      assertThatThrownBy(
              () -> sqlOwner.acceptClosedBarrierReadback(operationId, 0L, staleCurrentProof))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("cohort");
      assertThat(closedReceiptCount(fixture, operationId)).isEqualTo(1L);
      assertThat(closedReceiptPostimage(fixture, operationId, 0L)).isEqualTo(originalPostimage);
    }
  }

  private static long closedReceiptCount(Fixture fixture, UUID operationId) {
    Long count =
        fixture
            .dsl
            .fetchSingle(
                "SELECT count(*) AS row_count FROM"
                    + " game_session_canonical_account_closed_barrier_receipt"
                    + " WHERE operation_id = ?",
                operationId)
            .get("row_count", Long.class);
    return java.util.Objects.requireNonNull(count, "receipt count");
  }

  private static void awaitInventoryClockLockWait(DSLContext observer, String applicationName)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Boolean waiting =
          Objects.requireNonNull(
                  observer.fetchOne(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity"
                          + " WHERE pid <> pg_backend_pid() AND application_name = ?"
                          + " AND state = 'active' AND wait_event_type = 'Lock'"
                          + " AND query ILIKE '%game_session_canonical_binding_inventory_clock%')",
                      applicationName),
                  "inventory clock lock wait query")
              .get(0, Boolean.class);
      if (Boolean.TRUE.equals(waiting)) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Owner did not wait on the held inventory clock row");
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to release inventory clock row");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while holding inventory clock row", interrupted);
    }
  }

  private static String closedReceiptPostimage(
      Fixture fixture, UUID operationId, long targetOrdinal) {
    return fixture
        .dsl
        .fetchSingle(
            "SELECT to_jsonb(receipt)::text AS row_image FROM"
                + " game_session_canonical_account_closed_barrier_receipt receipt"
                + " WHERE operation_id = ? AND target_ordinal = ?",
            operationId,
            targetOrdinal)
        .get("row_image", String.class);
  }

  @Test
  void upgradesPopulatedV1028IntentAndAcceptsReceiptWithoutChangingRetainedRows() throws Exception {
    try (Fixture fixture = Fixture.open(java.math.BigInteger.valueOf(9), "1028");
        RedisFixture redis = RedisFixture.open()) {
      UUID operationId = fixture.intent().operationId();
      var assignment = fixture.tagRepository().assignmentForBarrierTarget(operationId, 0L);
      RetainedV1028Postimage originalRows = retainedV1028Postimage(fixture, assignment);
      assertThat(originalRows.intent()).hasSize(1);
      assertThat(originalRows.cohort()).isNotEmpty();
      assertThat(originalRows.targets()).isNotEmpty();
      assertThat(originalRows.control()).hasSize(1);
      assertThat(originalRows.mapping()).hasSize(1);

      fixture.migrateTo("1029");
      assertThat(retainedV1028Postimage(fixture, assignment)).isEqualTo(originalRows);

      var readback =
          fixture
              .owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
              .installAndRead(operationId, 0L);
      StoredClosedBarrierReceipt receipt =
          new CanonicalGameplayAccountAdmissionBarrierRepository(
                  fixture.dsl, Clock.fixed(NOW, ZoneOffset.UTC))
              .acceptClosedBarrierReadback(operationId, 0L, readback);
      assertThat(receipt.operationId()).isEqualTo(operationId);
      assertThat(receipt.barrierState()).isEqualTo("CLOSED");
      assertThat(retainedV1028Postimage(fixture, assignment)).isEqualTo(originalRows);

      String receiptPostimage = closedReceiptPostimage(fixture, operationId, 0L);
      assertThat(closedReceiptCount(fixture, operationId)).isEqualTo(1L);
      fixture.validateAndMigrateTo("1029");
      assertThat(retainedV1028Postimage(fixture, assignment)).isEqualTo(originalRows);
      assertThat(closedReceiptPostimage(fixture, operationId, 0L)).isEqualTo(receiptPostimage);
    }
  }

  @Test
  void databaseRejectsReceiptUpdateDeleteAndTruncateWithoutChangingItsPostimage() throws Exception {
    try (Fixture fixture = Fixture.open();
        RedisFixture redis = RedisFixture.open()) {
      UUID operationId = fixture.intent().operationId();
      var readback =
          fixture
              .owner(fixture.receiver(new ControlledSigner(fixture.keyPair(), false)), redis)
              .installAndRead(operationId, 0L);
      new CanonicalGameplayAccountAdmissionBarrierRepository(
              fixture.dsl, Clock.fixed(NOW, ZoneOffset.UTC))
          .acceptClosedBarrierReadback(operationId, 0L, readback);
      String originalPostimage = closedReceiptPostimage(fixture, operationId, 0L);

      // created_at is outside the row CHECK constraints, so 23514 proves the immutable trigger.
      assertThatThrownBy(
              () ->
                  fixture.dsl.execute(
                      "UPDATE game_session_canonical_account_closed_barrier_receipt"
                          + " SET created_at = created_at + INTERVAL '1 second'"
                          + " WHERE operation_id = ? AND target_ordinal = 0",
                      operationId))
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"));
      assertThat(closedReceiptPostimage(fixture, operationId, 0L)).isEqualTo(originalPostimage);

      assertThatThrownBy(
              () ->
                  fixture.dsl.execute(
                      "DELETE FROM game_session_canonical_account_closed_barrier_receipt"
                          + " WHERE operation_id = ? AND target_ordinal = 0",
                      operationId))
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"));
      assertThat(closedReceiptPostimage(fixture, operationId, 0L)).isEqualTo(originalPostimage);

      assertThatThrownBy(
              () ->
                  fixture.dsl.execute(
                      "TRUNCATE TABLE game_session_canonical_account_closed_barrier_receipt"))
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"));
      assertThat(closedReceiptCount(fixture, operationId)).isEqualTo(1L);
      assertThat(closedReceiptPostimage(fixture, operationId, 0L)).isEqualTo(originalPostimage);
    }
  }

  private record RetainedV1028Postimage(
      List<String> intent,
      List<String> cohort,
      List<String> targets,
      List<String> control,
      List<String> mapping) {
    private RetainedV1028Postimage {
      intent = List.copyOf(intent);
      cohort = List.copyOf(cohort);
      targets = List.copyOf(targets);
      control = List.copyOf(control);
      mapping = List.copyOf(mapping);
    }
  }

  private static RetainedV1028Postimage retainedV1028Postimage(
      Fixture fixture, CanonicalGameplayTenantGameplayTagRepository.Assignment assignment) {
    UUID operationId = fixture.intent().operationId();
    return new RetainedV1028Postimage(
        rowImages(
            fixture,
            "SELECT to_jsonb(source_row)::text AS row_image"
                + " FROM game_session_canonical_account_admission_fence_intent source_row"
                + " WHERE operation_id = ?",
            operationId),
        rowImages(
            fixture,
            "SELECT to_jsonb(source_row)::text AS row_image"
                + " FROM game_session_canonical_account_admission_fence_intent_cohort_row source_row"
                + " WHERE operation_id = ? ORDER BY row_ordinal",
            operationId),
        rowImages(
            fixture,
            "SELECT to_jsonb(source_row)::text AS row_image"
                + " FROM game_session_canonical_account_admission_fence_intent_barrier_target"
                + " source_row WHERE operation_id = ? ORDER BY target_ordinal",
            operationId),
        rowImages(
            fixture,
            "SELECT to_jsonb(source_row)::text AS row_image"
                + " FROM game_session_canonical_account_coverage_control source_row"
                + " WHERE account_id = ?",
            fixture.intent().accountId()),
        rowImages(
            fixture,
            "SELECT to_jsonb(source_row)::text AS row_image"
                + " FROM game_session_canonical_tenant_gameplay_tag source_row"
                + " WHERE tenant_id = ?",
            assignment.tenantId()));
  }

  private static List<String> rowImages(Fixture fixture, String query, Object... bindings) {
    return fixture.dsl.fetch(query, bindings).map(row -> row.get("row_image", String.class));
  }

  private static String sqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        return sqlException.getSQLState();
      }
    }
    return null;
  }

  private static Map<String, String> barrierFields(
      Fixture fixture,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation incarnation,
      String epoch,
      String state) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("schema", "gs_account_admission_barrier/v1");
    fields.put("accountId", fixture.intent().accountId().toString());
    fields.put("tenantId", assignment.tenantId().toString());
    fields.put("playableStateNamespaceId", assignment.playableStateNamespaceId().toString());
    fields.put("tenantGameplayTag", assignment.tenantGameplayTag());
    fields.put("ownerOperationId", fixture.intent().operationId().toString());
    fields.put("ownerFenceEpoch", epoch);
    fields.put("incarnationDigest", incarnation.incarnationDigest());
    fields.put("state", state);
    return Map.copyOf(fields);
  }

  private static Map<byte[], byte[]> encoded(Map<String, String> fields) {
    Map<byte[], byte[]> encoded = new LinkedHashMap<>();
    fields.forEach((field, value) -> encoded.put(ascii(field), ascii(value)));
    return encoded;
  }

  private static Map<String, String> withPriorOperation(Map<String, String> fields) {
    Map<String, String> prior = new LinkedHashMap<>(fields);
    prior.put("ownerOperationId", UUID.randomUUID().toString());
    return Map.copyOf(prior);
  }

  private static Map<String, String> stringMap(Map<byte[], byte[]> fields) {
    Map<String, String> decoded = new LinkedHashMap<>();
    fields.forEach(
        (field, value) ->
            decoded.put(
                new String(field, StandardCharsets.US_ASCII),
                new String(value, StandardCharsets.US_ASCII)));
    return decoded;
  }

  private static void assertExactHash(
      RedisCommands<byte[], byte[]> admin, byte[] key, Map<String, String> expected) {
    Map<String, String> actual = stringMap(admin.hgetall(key));
    assertThat(actual).isEqualTo(expected);
    assertThat(admin.type(key)).isEqualTo("hash");
    assertThat(admin.pttl(key)).isEqualTo(-1L);
    assertThat(admin.hlen(key)).isEqualTo(9L);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static final class ControlledSigner
      implements CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel {
    private final KeyPair keyPair;
    private final boolean changeSecondObservation;
    private int calls;

    private ControlledSigner(KeyPair keyPair, boolean changeSecondObservation) {
      this.keyPair = keyPair;
      this.changeSecondObservation = changeSecondObservation;
    }

    @Override
    public CanonicalGameplayProtectedIncarnationReceiver.SignedObservation observe(
        CanonicalGameplayProtectedIncarnationReceiver.ObservationRequest request) {
      try {
        Map<String, Object> root = payload(request);
        calls++;
        if (changeSecondObservation && calls > 1) {
          asMap(root.get("targetDeploymentEvidence")).put("storageGeneration", "storage-changed");
        }
        byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonical);
        return new CanonicalGameplayProtectedIncarnationReceiver.SignedObservation(
            KEY_ID, canonical, signer.sign());
      } catch (Exception failure) {
        throw new IllegalStateException("Controlled local test signer failed", failure);
      }
    }
  }

  private static Map<String, Object> payload(
      CanonicalGameplayProtectedIncarnationReceiver.ObservationRequest request) {
    var context = request.context();
    var evidence = request.expectedEvidence();
    Map<String, Object> owner = new LinkedHashMap<>();
    owner.put("operationId", context.operationId().toString());
    owner.put("requestId", context.requestId().toString());
    owner.put("accountId", context.accountId().toString());
    owner.put("operationFence", context.operationFence());
    owner.put("plannedAccountAdmissionFence", context.plannedAccountAdmissionFence().toString());
    owner.put("inventorySnapshotRevision", context.inventorySnapshotRevision());
    owner.put("cohortDigest", context.cohortDigest());
    owner.put("barrierTargetDigest", context.barrierTargetDigest());

    Map<String, Object> target = new LinkedHashMap<>();
    target.put("targetOrdinal", context.targetOrdinal());
    target.put("tenantId", context.tenantId().toString());
    target.put("playableStateNamespaceId", context.playableStateNamespaceId().toString());
    target.put("tenantGameplayTag", context.tenantGameplayTag());
    target.put("workloadNamespace", context.workloadNamespace());
    target.put("allocationVersion", context.allocationVersion());

    Map<String, Object> targetEvidence = new LinkedHashMap<>();
    targetEvidence.put("deploymentIdentity", evidence.deploymentIdentity());
    targetEvidence.put("deploymentGeneration", evidence.deploymentGeneration());
    targetEvidence.put("canonicalNodeSet", evidence.canonicalNodeSet());
    targetEvidence.put("redisBuildIdentity", evidence.redisBuildIdentity());
    targetEvidence.put("luaScriptRegistryVersion", evidence.luaScriptRegistryVersion());
    targetEvidence.put("luaScriptRegistryDigest", evidence.luaScriptRegistryDigest());
    targetEvidence.put("startupState", evidence.startupState());
    targetEvidence.put("bootGeneration", evidence.bootGeneration());
    targetEvidence.put("startupGeneration", evidence.startupGeneration());
    targetEvidence.put("storageGeneration", evidence.storageGeneration());
    targetEvidence.put("aofGeneration", evidence.aofGeneration());

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schema", "firemud.gs.protected-incarnation-read/v1");
    root.put("attestorBinding", request.attestorBinding());
    root.put("attestorKeyId", KEY_ID);
    root.put("acceptedAttestationDigest", request.acceptedAttestationDigest());
    root.put("observationNonce", request.nonce());
    root.put("observationIssuedAtEpochMillis", request.issuedAtEpochMillis());
    root.put("observationObservedAtEpochMillis", request.issuedAtEpochMillis());
    root.put("observationExpiresAtEpochMillis", request.expiresAtEpochMillis());
    root.put("owner", owner);
    root.put("target", target);
    root.put("targetDeploymentEvidence", targetEvidence);
    root.put("coordOpsRoAclDigest", request.coordOpsRoAclDigest());
    root.put("coordRedisConfigObservedDigest", request.coordRedisConfigObservedDigest());
    return root;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  private static final class Fixture implements AutoCloseable {
    private static final String MIGRATION_LOCATION =
        "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
    private final String schema;
    private final DriverManagerDataSource dataSource;
    private final DSLContext dsl;
    private final CanonicalGameplayAccountAdmissionFenceIntent intent;
    private final String workloadNamespace;
    private final KeyPair keyPair;
    private final CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
        expectedEvidence;
    private final CanonicalGameplayAccountAdmissionBarrierRepository intentRepository;
    private final CanonicalGameplayTenantGameplayTagRepository tagRepository;

    private Fixture(
        String schema,
        DriverManagerDataSource dataSource,
        DSLContext dsl,
        CanonicalGameplayAccountAdmissionFenceIntent intent,
        String workloadNamespace,
        KeyPair keyPair,
        CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
            expectedEvidence) {
      this.schema = schema;
      this.dataSource = dataSource;
      this.dsl = dsl;
      this.intent = intent;
      this.workloadNamespace = workloadNamespace;
      this.keyPair = keyPair;
      this.expectedEvidence = expectedEvidence;
      this.intentRepository = new CanonicalGameplayAccountAdmissionBarrierRepository(dsl);
      this.tagRepository =
          new CanonicalGameplayTenantGameplayTagRepository(dsl, workloadNamespace, 1);
    }

    static Fixture open() throws Exception {
      return open(java.math.BigInteger.valueOf(9), null);
    }

    static Fixture open(java.math.BigInteger lastOperationFence) throws Exception {
      return open(lastOperationFence, null);
    }

    static Fixture open(java.math.BigInteger lastOperationFence, String migrationTarget)
        throws Exception {
      String schema = "gs_closed_barrier_" + UUID.randomUUID().toString().replace("-", "");
      DriverManagerDataSource dataSource = dataSource(schema);
      flyway(dataSource, schema, migrationTarget).migrate();
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      CanonicalGameplayBindingIdentity retainedIdentity =
          "1028".equals(migrationTarget)
              ? CanonicalGameplayBindingInventoryRepositoryIntegrationTest.identity(
                  UUID.randomUUID().toString())
              : null;
      UUID tenantId = retainedIdentity == null ? UUID.randomUUID() : retainedIdentity.tenantId();
      var runtime =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource, tenantId);
      CanonicalGameplayBindingInventoryRepositoryIntegrationTest
          .seedSyntheticVerifiedLegacyDisposition(dsl);

      UUID accountId;
      if (retainedIdentity != null) {
        // This V1028-only fixture reuses the existing canonical historical-source SQL helper.
        // It is retained storage for this migration test, not a live prepare-owner or admission
        // proof. V1028 predates the V1030 issuer-intent guard read by current prepare().
        accountId = retainedIdentity.accountId();
        CanonicalGameplayBindingInventoryRepositoryIntegrationTest.seedActiveSource(
            dsl, retainedIdentity);
        seedNoActiveAccountCoverageControl(dsl, accountId);
      } else {
        accountId = UUID.randomUUID();
        UUID transitionId = UUID.randomUUID();
        CanonicalGameplayBindingIdentity identity =
            new CanonicalGameplayBindingIdentity(
                accountId,
                runtime.canonicalTenantId(),
                runtime.playableStateNamespaceId(),
                UUID.randomUUID(),
                "SHARED",
                runtime.gameInstanceUuid(),
                runtime.gameInstanceId(),
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                java.math.BigInteger.ONE,
                GameSessionAccountDelegationProfile.ISSUER,
                java.math.BigInteger.ONE,
                java.math.BigInteger.ONE,
                java.math.BigInteger.valueOf(16),
                java.math.BigInteger.valueOf(32));
        CanonicalIssuerReservationFenceEvidence reservation =
            new CanonicalIssuerReservationFenceEvidence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                java.math.BigInteger.ONE,
                java.math.BigInteger.ONE,
                java.math.BigInteger.ONE);
        new CanonicalGameplayBindingInventoryRepository(dsl)
            .prepare(
                new CanonicalGameplayBindingTransitionRequest(
                    transitionId, UUID.randomUUID(), identity, reservation, null, null));
      }
      dsl.execute(
          "UPDATE game_session_canonical_account_coverage_control SET last_operation_fence = ?"
              + " WHERE account_id = ? AND state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'",
          lastOperationFence,
          accountId);
      CanonicalGameplayAccountAdmissionFenceIntent intent =
          new CanonicalGameplayAccountAdmissionBarrierRepository(dsl)
              .beginFenceIntent(accountId, UUID.randomUUID());
      if (!intent.operationFence().equals(lastOperationFence.add(java.math.BigInteger.ONE))) {
        throw new IllegalStateException("Test fixture did not establish the intended fence epoch");
      }

      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair keyPair = generator.generateKeyPair();
      var evidence =
          new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
              "controlled-target-deployment",
              "generation-7",
              List.of("redis-node-a"),
              "redis-test-build",
              "registry-test-v1",
              "sha256:" + "d".repeat(64),
              "READY",
              "boot-7",
              "startup-7",
              "storage-7",
              "aof-7");
      return new Fixture(
          schema, dataSource, dsl, intent, runtime.targetNamespace(), keyPair, evidence);
    }

    private static void seedNoActiveAccountCoverageControl(DSLContext dsl, UUID accountId) {
      // Controlled retained-row input only; this is not owner-produced no-active authority.
      dsl.execute(
          "INSERT INTO game_session_canonical_account_coverage_control "
              + "(account_id, state, last_operation_fence) "
              + "VALUES (?, 'NO_ACTIVE_ACCOUNT_WIDE_FLOW', 0)",
          accountId);
    }

    private void migrateTo(String migrationTarget) {
      flyway(dataSource, schema, migrationTarget).migrate();
    }

    private void validateAndMigrateTo(String migrationTarget) {
      Flyway flyway = flyway(dataSource, schema, migrationTarget);
      flyway.validate();
      flyway.migrate();
    }

    private DSLContext dslForApplication(String applicationName) {
      return DSL.using(dataSource(schema, applicationName), SQLDialect.POSTGRES);
    }

    private static Flyway flyway(
        DriverManagerDataSource dataSource, String schema, String migrationTarget) {
      var configuration =
          Flyway.configure()
              .dataSource(dataSource)
              .schemas(schema)
              .defaultSchema(schema)
              .table("flyway_schema_history")
              .locations(MIGRATION_LOCATION);
      if (migrationTarget != null) {
        configuration.target(MigrationVersion.fromVersion(migrationTarget));
      }
      return configuration.load();
    }

    private CanonicalGameplayProtectedIncarnationReceiver receiver(
        CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel channel) {
      var trusted =
          new CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration(
              ATTESTOR_BINDING,
              ACCEPTED_ATTESTATION_DIGEST,
              Map.of(KEY_ID, (java.security.interfaces.RSAPublicKey) keyPair.getPublic()),
              expectedEvidence,
              ACL_DIGEST,
              CONFIG_DIGEST,
              16 * 1024,
              1,
              Duration.ofSeconds(30));
      return new CanonicalGameplayProtectedIncarnationReceiver(
          intentRepository, tagRepository, channel, trusted, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CanonicalGameplayAccountClosedBarrierRedisOwner owner(
        CanonicalGameplayProtectedIncarnationReceiver receiver, RedisFixture redis) {
      return new CanonicalGameplayAccountClosedBarrierRedisOwner(
          intentRepository,
          tagRepository,
          receiver,
          redis.ownerClient(),
          redis.catalog(),
          Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CanonicalGameplayAccountAdmissionFenceIntent intent() {
      return intent;
    }

    private KeyPair keyPair() {
      return keyPair;
    }

    private CanonicalGameplayTenantGameplayTagRepository tagRepository() {
      return tagRepository;
    }

    @Override
    public void close() {
      try (var connection =
              java.sql.DriverManager.getConnection(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      } catch (Exception failure) {
        throw new IllegalStateException("Could not dispose test database schema", failure);
      }
    }

    private static DriverManagerDataSource dataSource(String schema) {
      return dataSource(schema, null);
    }

    private static DriverManagerDataSource dataSource(String schema, String applicationName) {
      DriverManagerDataSource source = new DriverManagerDataSource();
      source.setDriverClassName("org.postgresql.Driver");
      String jdbcUrl = POSTGRES.getJdbcUrl();
      String applicationNameParameter =
          applicationName == null ? "" : "&ApplicationName=" + applicationName;
      source.setUrl(
          jdbcUrl
              + (jdbcUrl.contains("?") ? "&" : "?")
              + "currentSchema="
              + schema
              + applicationNameParameter);
      source.setUsername(POSTGRES.getUsername());
      source.setPassword(POSTGRES.getPassword());
      return source;
    }
  }

  private static final class RedisFixture implements AutoCloseable {
    private final RedisClient administrator;
    private final RedisClient ownerClient;
    private final StatefulRedisConnection<byte[], byte[]> adminConnection;
    private final RedisCommands<byte[], byte[]> admin;
    private final RedisScriptCatalog catalog;

    private RedisFixture(
        RedisClient administrator,
        RedisClient ownerClient,
        StatefulRedisConnection<byte[], byte[]> adminConnection,
        RedisCommands<byte[], byte[]> admin,
        RedisScriptCatalog catalog) {
      this.administrator = administrator;
      this.ownerClient = ownerClient;
      this.adminConnection = adminConnection;
      this.admin = admin;
      this.catalog = catalog;
    }

    static RedisFixture open() {
      RedisClient administrator = createClient(null, null);
      StatefulRedisConnection<byte[], byte[]> adminConnection =
          administrator.connect(ByteArrayCodec.INSTANCE);
      RedisCommands<byte[], byte[]> admin = adminConnection.sync();
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
              .addCommand(CommandType.HLEN)
              .addCommand(CommandType.HGETALL)
              .addCommand(CommandType.HSET));
      RedisClient ownerClient = createClient(PRINCIPAL, PASSWORD);
      RedisScriptCatalog catalog =
          RedisScriptCatalog.loadInstalled(
              CanonicalGameplayAccountClosedBarrierRedisOwnerIntegrationTest.class
                  .getClassLoader());
      return new RedisFixture(administrator, ownerClient, adminConnection, admin, catalog);
    }

    private static RedisClient createClient(String username, String password) {
      RedisURI.Builder builder =
          RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(PORT))
              .withTimeout(Duration.ofSeconds(3));
      if (username != null) builder.withAuthentication(username, password);
      RedisClient client = RedisClient.create(builder.build());
      client.setOptions(ClientOptions.builder().autoReconnect(false).build());
      return client;
    }

    private RedisClient ownerClient() {
      return ownerClient;
    }

    private RedisCommands<byte[], byte[]> admin() {
      return admin;
    }

    private RedisScriptCatalog catalog() {
      return catalog;
    }

    @Override
    public void close() {
      adminConnection.close();
      ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }
}
