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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Focused PostgreSQL/Redis proof for the first OPEN permit, replay-only retries, and cutoff. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayIssuerAdmissionBarrierRepositoryIntegrationTest {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String OWNER_PRINCIPAL = "gamesession_coord_app";
  private static final BigInteger ONE = BigInteger.ONE;

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  private RedisClient administrator;
  private RedisClient ownerClient;
  private StatefulRedisConnection<byte[], byte[]> administratorConnection;
  private RedisCommands<byte[], byte[]> administratorCommands;
  private CanonicalGameplayAdmissionAbortCompositionFixture fixture;

  @BeforeEach
  void setUp() {
    String password = UUID.randomUUID().toString();
    administrator = createRedisClient(null, null);
    administratorConnection = administrator.connect(ByteArrayCodec.INSTANCE);
    administratorCommands = administratorConnection.sync();
    administratorCommands.flushdb();
    administratorCommands.aclSetuser(
        OWNER_PRINCIPAL,
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(password)
            .keyPattern("session:game:*")
            .addCommand(CommandType.HELLO)
            .addCommand(CommandType.PING)
            .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
            .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
            .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
            .addCommand(CommandType.EVALSHA)
            .addCommand(CommandType.TYPE)
            .addCommand(CommandType.PTTL)
            .addCommand(CommandType.HGET)
            .addCommand(CommandType.HLEN)
            .addCommand(CommandType.HGETALL)
            .addCommand(CommandType.HSET));
    ownerClient = createRedisClient(OWNER_PRINCIPAL, password);
  }

  @AfterEach
  void tearDown() {
    if (fixture != null) fixture.close();
    if (administratorConnection != null) administratorConnection.close();
    if (ownerClient != null) ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (administrator != null) administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
  }

  @Test
  void firstOpenIsPersistedExactRetryIsReplayOnlyAndCutoffPreservesNewerClosedState() {
    FixtureState state = fixtureState();
    UUID operationId = state.fixture().originalOperationId();

    var first = state.owner().initializeAndRead(operationId);
    assertThat(first.scriptOutcome()).isEqualTo(1L);
    assertThat(initializationState(state, operationId)).isEqualTo("OPEN_CONFIRMED");
    var replay = state.owner().initializeAndRead(operationId);
    assertThat(replay.scriptOutcome()).isEqualTo(2L);
    assertThat(replay.exactPhysicalPayload()).containsExactly(first.exactPhysicalPayload());

    var prepared = state.repository().readPreparedInitializer(operationId);
    administratorCommands.del(bytes(prepared.key()));
    assertThatThrownBy(() -> state.owner().initializeAndRead(operationId))
        .isInstanceOf(IllegalStateException.class);

    UUID cutoffOperationId = UUID.randomUUID();
    state.repository().beginCutoff(cutoffOperationId, UUID.randomUUID());
    var cutoffTargets = state.repository().readPreparedCutoffTargets(cutoffOperationId);
    assertThat(cutoffTargets).hasSize(1);
    var target = cutoffTargets.getFirst();
    var closed =
        state
            .owner()
            .closeAndRead(
                cutoffOperationId,
                target.closedValue().tenantId(),
                target.closedValue().playableStateNamespaceId());
    assertThat(closed.scriptOutcome()).isEqualTo(1L);

    var newerClosed =
        CanonicalGameplayIssuerAdmissionBarrierValue.closed(
            ISSUER,
            target.closedValue().tenantId(),
            target.closedValue().playableStateNamespaceId(),
            target.closedValue().tenantGameplayTag(),
            target.closedValue().admissionEpoch().add(ONE),
            UUID.randomUUID(),
            target.closedValue().initializer());
    writePhysicalValue(target.key(), newerClosed);
    byte[] newerBytes = newerClosed.exactBytes();
    assertThatThrownBy(
            () ->
                state
                    .owner()
                    .closeAndRead(
                        cutoffOperationId,
                        target.closedValue().tenantId(),
                        target.closedValue().playableStateNamespaceId()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(administratorCommands.hget(bytes(target.key()), bytes("payload")))
        .containsExactly(newerBytes);
  }

  @Test
  void lostReceiptReplaysTheExistingOpenWithoutMintingAnotherCreatePermit() {
    FixtureState state = fixtureState();
    UUID operationId = state.fixture().originalOperationId();
    AtomicBoolean loseFirstReceipt = new AtomicBoolean(true);
    var delegate = state.repository();
    var lostReplyRepository =
        new CanonicalGameplayIssuerAdmissionBarrierRedisOwner.ProjectionRepository() {
          @Override
          public CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer
              readPreparedInitializer(UUID id) {
            return delegate.readPreparedInitializer(id);
          }

          @Override
          public CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget
              readPreparedCutoffTarget(UUID id, UUID tenantId, UUID namespaceId) {
            return delegate.readPreparedCutoffTarget(id, tenantId, namespaceId);
          }

          @Override
          public void requireStillCurrent(
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer prepared) {
            delegate.requireStillCurrent(prepared);
          }

          @Override
          public void requireStillCurrent(
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget prepared) {
            delegate.requireStillCurrent(prepared);
          }

          @Override
          public void recordOpenReadback(
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer prepared,
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback readback) {
            if (loseFirstReceipt.getAndSet(false)) {
              throw new IllegalStateException("Controlled loss after physical OPEN readback");
            }
            delegate.recordOpenReadback(prepared, readback);
          }

          @Override
          public void recordClosedReadback(
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget prepared,
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback readback) {
            delegate.recordClosedReadback(prepared, readback);
          }
        };
    var lostReplyOwner = state.owner(lostReplyRepository);

    assertThatThrownBy(() -> lostReplyOwner.initializeAndRead(operationId))
        .isInstanceOf(IllegalStateException.class);
    assertThat(initializationState(state, operationId)).isEqualTo("INITIALIZING");
    assertThat(countReceipt(state, operationId)).isZero();

    var replay = state.owner().initializeAndRead(operationId);
    assertThat(replay.scriptOutcome()).isEqualTo(2L);
    assertThat(initializationState(state, operationId)).isEqualTo("OPEN_CONFIRMED");
    assertThat(countReceipt(state, operationId)).isEqualTo(1L);
  }

  @Test
  void missingOpenAfterUncertainFirstAttemptIsNeverRecreated() {
    FixtureState state = fixtureState();
    UUID operationId = state.fixture().originalOperationId();
    AtomicBoolean loseFirstReceipt = new AtomicBoolean(true);
    var delegate = state.repository();
    var lostReplyRepository =
        new CanonicalGameplayIssuerAdmissionBarrierRedisOwner.ProjectionRepository() {
          @Override
          public CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer
              readPreparedInitializer(UUID id) {
            return delegate.readPreparedInitializer(id);
          }

          @Override
          public CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget
              readPreparedCutoffTarget(UUID id, UUID tenantId, UUID namespaceId) {
            return delegate.readPreparedCutoffTarget(id, tenantId, namespaceId);
          }

          @Override
          public void requireStillCurrent(
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer prepared) {
            delegate.requireStillCurrent(prepared);
          }

          @Override
          public void requireStillCurrent(
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget prepared) {
            delegate.requireStillCurrent(prepared);
          }

          @Override
          public void recordOpenReadback(
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer prepared,
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback readback) {
            if (loseFirstReceipt.getAndSet(false)) {
              throw new IllegalStateException("Controlled loss after physical OPEN readback");
            }
            delegate.recordOpenReadback(prepared, readback);
          }

          @Override
          public void recordClosedReadback(
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget prepared,
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback readback) {
            delegate.recordClosedReadback(prepared, readback);
          }
        };
    assertThatThrownBy(() -> state.owner(lostReplyRepository).initializeAndRead(operationId))
        .isInstanceOf(IllegalStateException.class);

    var prepared = state.repository().readPreparedInitializer(operationId);
    administratorCommands.del(bytes(prepared.key()));
    assertThatThrownBy(() -> state.owner().initializeAndRead(operationId))
        .isInstanceOf(IllegalStateException.class);
    assertThat(initializationState(state, operationId)).isEqualTo("INITIALIZING");
    assertThat(countReceipt(state, operationId)).isZero();
    assertThat(administratorCommands.exists(bytes(prepared.key()))).isZero();
  }

  @Test
  void cutoffCapturesAnInitializerWhoseCreatePermitWasClaimedButNotYetAttempted() throws Exception {
    FixtureState state = fixtureState();
    UUID operationId = state.fixture().originalOperationId();
    UUID cutoffOperationId = UUID.randomUUID();
    CountDownLatch preparedForRedis = new CountDownLatch(1);
    CountDownLatch allowRedisOwnerToContinue = new CountDownLatch(1);
    AtomicReference<CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer>
        observedInitializer = new AtomicReference<>();
    var delegate = state.repository();
    var gatedRepository =
        new CanonicalGameplayIssuerAdmissionBarrierRedisOwner.ProjectionRepository() {
          @Override
          public CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer
              readPreparedInitializer(UUID id) {
            var prepared = delegate.readPreparedInitializer(id);
            observedInitializer.set(prepared);
            preparedForRedis.countDown();
            try {
              if (!allowRedisOwnerToContinue.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Controlled initializer gate timed out");
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(
                  "Controlled initializer gate was interrupted", interrupted);
            }
            return prepared;
          }

          @Override
          public CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget
              readPreparedCutoffTarget(UUID id, UUID tenantId, UUID namespaceId) {
            return delegate.readPreparedCutoffTarget(id, tenantId, namespaceId);
          }

          @Override
          public void requireStillCurrent(
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer prepared) {
            delegate.requireStillCurrent(prepared);
          }

          @Override
          public void requireStillCurrent(
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget prepared) {
            delegate.requireStillCurrent(prepared);
          }

          @Override
          public void recordOpenReadback(
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedInitializer prepared,
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback readback) {
            delegate.recordOpenReadback(prepared, readback);
          }

          @Override
          public void recordClosedReadback(
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.PreparedCutoffTarget prepared,
              CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback readback) {
            delegate.recordClosedReadback(prepared, readback);
          }
        };
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      var initializerAttempt =
          executor.submit(() -> state.owner(gatedRepository).initializeAndRead(operationId));
      assertThat(preparedForRedis.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(initializationState(state, operationId)).isEqualTo("INITIALIZING");

      state.repository().beginCutoff(cutoffOperationId, UUID.randomUUID());
      allowRedisOwnerToContinue.countDown();
      assertThatThrownBy(() -> initializerAttempt.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IllegalStateException.class);
      assertThat(administratorCommands.exists(bytes(observedInitializer.get().key()))).isZero();

      var targets = state.repository().readPreparedCutoffTargets(cutoffOperationId);
      assertThat(targets).hasSize(1);
      var closed =
          state
              .owner()
              .closeAndRead(
                  cutoffOperationId,
                  targets.getFirst().closedValue().tenantId(),
                  targets.getFirst().closedValue().playableStateNamespaceId());
      assertThat(closed.scriptOutcome()).isEqualTo(1L);
      assertThat(countReceipt(state, operationId)).isZero();
    } finally {
      allowRedisOwnerToContinue.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void cutoffClosesAnAbortedOriginTargetAndRetainsItsFrozenInitializer() {
    FixtureState state = fixtureState();
    UUID originalOperationId = state.fixture().originalOperationId();
    var opened = state.owner().initializeAndRead(originalOperationId);

    new CanonicalGameplayBindingInventoryRepository(state.fixture().dsl())
        .recordFirstNewAccountAbort(state.fixture().source(), UUID.randomUUID());
    assertThat(
            state
                .fixture()
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_canonical_binding_transition"
                        + " WHERE transition_id = ?",
                    state.fixture().transitionId())
                .get("status", String.class))
        .isEqualTo("ABORTED");

    UUID cutoffOperationId = UUID.randomUUID();
    state.repository().beginCutoff(cutoffOperationId, UUID.randomUUID());
    var targets = state.repository().readPreparedCutoffTargets(cutoffOperationId);
    assertThat(targets).hasSize(1);
    var target = targets.getFirst();
    assertThat(target.expectedOpenValue()).isNotNull();
    assertThat(target.expectedOpenValue().exactBytes())
        .containsExactly(opened.exactPhysicalPayload());
    assertThat(target.closedValue().initializer().exactBytes())
        .containsExactly(opened.value().initializer().exactBytes());

    var closed =
        state
            .owner()
            .closeAndRead(
                cutoffOperationId,
                target.closedValue().tenantId(),
                target.closedValue().playableStateNamespaceId());
    // Outcome 3 is CAPTURED_OPEN_CLOSED: this target replaces the exact OPEN
    // payload captured by the cutoff.
    assertThat(closed.scriptOutcome()).isEqualTo(3L);
    var installed = state.repository().completeCutoff(cutoffOperationId);
    assertThat(installed.state())
        .isEqualTo(CanonicalGameplayIssuerAdmissionControlValue.State.INSTALLED);

    var frozenInitializer =
        state
            .fixture()
            .dsl()
            .fetchOne(
                "SELECT operation_id, open_payload_bytes FROM"
                    + " game_session_canonical_issuer_admission_initializer"
                    + " WHERE operation_id = ?",
                originalOperationId);
    assertThat(frozenInitializer.get("operation_id", UUID.class)).isEqualTo(originalOperationId);
    assertThat(frozenInitializer.get("open_payload_bytes", byte[].class))
        .containsExactly(opened.exactPhysicalPayload());
    assertThatThrownBy(() -> state.owner().initializeAndRead(originalOperationId))
        .isInstanceOf(IllegalStateException.class);
    assertThat(administratorCommands.hget(bytes(target.key()), bytes("payload")))
        .containsExactly(closed.exactPhysicalPayload());
  }

  @Test
  void cutoffCompletesFromFrozenSourcesAfterTheProvisionalOriginAborts() {
    FixtureState state = fixtureState();
    var opened = state.owner().initializeAndRead(state.fixture().originalOperationId());
    UUID cutoffOperationId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    var pending = state.repository().beginCutoff(cutoffOperationId, requestId);
    var target = state.repository().readPreparedCutoffTargets(cutoffOperationId).getFirst();
    List<String> frozenRows =
        state
            .fixture()
            .dsl()
            .fetch(
                "SELECT to_jsonb(c)::text AS image FROM"
                    + " game_session_canonical_issuer_admission_cohort_row c"
                    + " WHERE operation_id = ? ORDER BY row_ordinal",
                cutoffOperationId)
            .getValues("image", String.class);
    var frozenTarget =
        state
            .fixture()
            .dsl()
            .fetchSingle(
                "SELECT target_source_digest, closed_payload_digest FROM"
                    + " game_session_canonical_issuer_admission_cutoff_target"
                    + " WHERE operation_id = ?",
                cutoffOperationId);

    new CanonicalGameplayBindingInventoryRepository(state.fixture().dsl())
        .recordFirstNewAccountAbort(state.fixture().source(), UUID.randomUUID());
    var transition =
        state
            .fixture()
            .dsl()
            .fetchSingle(
                "SELECT status, inventory_revision FROM game_session_canonical_binding_transition"
                    + " WHERE transition_id = ?",
                state.fixture().transitionId());
    assertThat(transition.get("status", String.class)).isEqualTo("ABORTED");
    assertThat(transition.get("inventory_revision", java.math.BigDecimal.class).toBigIntegerExact())
        .isGreaterThan(pending.inventorySnapshotRevision());
    var retry = state.repository().beginCutoff(cutoffOperationId, requestId);
    assertThat(retry.cohortDigest()).isEqualTo(pending.cohortDigest());
    assertThat(retry.cohortRowCount()).isEqualTo(pending.cohortRowCount());
    assertThat(retry.liveBarrierEpoch()).isEqualTo(pending.liveBarrierEpoch());
    assertThatThrownBy(() -> state.repository().completeCutoff(cutoffOperationId))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThat(target.expectedOpenValue().exactBytes())
        .containsExactly(opened.exactPhysicalPayload());
    var closed =
        state
            .owner()
            .closeAndRead(
                cutoffOperationId,
                target.closedValue().tenantId(),
                target.closedValue().playableStateNamespaceId());
    assertThat(closed.scriptOutcome()).isEqualTo(3L);
    assertThat(closed.exactPhysicalPayload()).containsExactly(target.closedValue().exactBytes());
    var installed = state.repository().completeCutoff(cutoffOperationId);
    assertThat(installed.state())
        .isEqualTo(CanonicalGameplayIssuerAdmissionControlValue.State.INSTALLED);
    assertThat(installed.liveBarrierEpoch()).isEqualTo(pending.liveBarrierEpoch());
    assertThat(installed.cohortDigest()).isEqualTo(pending.cohortDigest());
    assertThat(
            state
                .fixture()
                .dsl()
                .fetch(
                    "SELECT to_jsonb(c)::text AS image FROM"
                        + " game_session_canonical_issuer_admission_cohort_row c"
                        + " WHERE operation_id = ? ORDER BY row_ordinal",
                    cutoffOperationId)
                .getValues("image", String.class))
        .containsExactlyElementsOf(frozenRows);
    var completedTarget =
        state
            .fixture()
            .dsl()
            .fetchSingle(
                "SELECT target_source_digest, closed_payload_digest, target_state,"
                    + " exact_closed_readback_bytes FROM game_session_canonical_issuer_admission_cutoff_target"
                    + " WHERE operation_id = ?",
                cutoffOperationId);
    assertThat(completedTarget.get("target_source_digest", String.class))
        .isEqualTo(frozenTarget.get("target_source_digest", String.class));
    assertThat(completedTarget.get("closed_payload_digest", String.class))
        .isEqualTo(frozenTarget.get("closed_payload_digest", String.class));
    assertThat(completedTarget.get("target_state", String.class)).isEqualTo("CLOSED");
    assertThat(completedTarget.get("exact_closed_readback_bytes", byte[].class))
        .containsExactly(target.closedValue().exactBytes());
    assertThatThrownBy(() -> state.owner().initializeAndRead(state.fixture().originalOperationId()))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SOURCE_BYTES", "MISSING_SOURCE", "MISSING_TARGET", "TARGET_DIGEST"})
  void cutoffRefusesChangedOrMissingFrozenEvidence(String corruption) {
    FixtureState state = fixtureState();
    state.owner().initializeAndRead(state.fixture().originalOperationId());
    UUID cutoffOperationId = UUID.randomUUID();
    state.repository().beginCutoff(cutoffOperationId, UUID.randomUUID());
    var target = state.repository().readPreparedCutoffTargets(cutoffOperationId).getFirst();
    // Controlled corruption of retained SQL evidence; no trigger or runtime guard is bypassed.
    String mutation =
        switch (corruption) {
          case "SOURCE_BYTES" ->
              "UPDATE game_session_canonical_issuer_admission_cohort_row"
                  + " SET source_row_json = source_row_json || ' ' WHERE operation_id = ?";
          case "MISSING_SOURCE" ->
              "DELETE FROM game_session_canonical_issuer_admission_cohort_row WHERE operation_id = ?";
          case "MISSING_TARGET" ->
              "DELETE FROM game_session_canonical_issuer_admission_cutoff_target WHERE operation_id = ?";
          case "TARGET_DIGEST" ->
              "UPDATE game_session_canonical_issuer_admission_cutoff_target"
                  + " SET target_source_digest = 'sha256:' || repeat('0', 64) WHERE operation_id = ?";
          default -> throw new IllegalArgumentException(corruption);
        };
    assertThat(state.fixture().dsl().execute(mutation, cutoffOperationId)).isPositive();
    assertThatThrownBy(
            () ->
                state
                    .repository()
                    .readPreparedCutoffTarget(
                        cutoffOperationId,
                        target.closedValue().tenantId(),
                        target.closedValue().playableStateNamespaceId()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThatThrownBy(() -> state.repository().completeCutoff(cutoffOperationId))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
  }

  @Test
  void reusesFrozenTargetInitializerAcrossOperationsRevisionProgressAndOriginAbort() {
    FixtureState state = fixtureState();
    UUID originalOperationId = state.fixture().originalOperationId();
    var opened = state.owner().initializeAndRead(originalOperationId);
    byte[] originalPayload = opened.exactPhysicalPayload();
    UUID secondAccountId = UUID.randomUUID();
    assertThat(secondAccountId).isNotEqualTo(state.candidate().accountId());

    CanonicalGameplayIssuerBindingLocalOperation currentOperation =
        prepareCurrentOperation(state, "same-target-different-account", secondAccountId);
    var originalRetry = state.owner().initializeAndRead(originalOperationId);
    assertThat(originalRetry.scriptOutcome()).isEqualTo(2L);
    assertThat(originalRetry.exactPhysicalPayload()).containsExactly(originalPayload);

    var abortSource = state.fixture().source();
    new CanonicalGameplayBindingInventoryRepository(state.fixture().dsl())
        .recordFirstNewAccountAbort(abortSource, UUID.randomUUID());
    assertThat(
            state
                .fixture()
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_canonical_binding_transition"
                        + " WHERE transition_id = ?",
                    state.fixture().transitionId())
                .get("status", String.class))
        .isEqualTo("ABORTED");

    var abortedOriginRetry = state.owner().initializeAndRead(originalOperationId);
    assertThat(abortedOriginRetry.scriptOutcome()).isEqualTo(2L);
    assertThat(abortedOriginRetry.exactPhysicalPayload()).containsExactly(originalPayload);

    var currentCandidateReadback = state.owner().initializeAndRead(currentOperation.operationId());
    assertThat(currentCandidateReadback.scriptOutcome()).isEqualTo(2L);
    assertThat(currentCandidateReadback.exactPhysicalPayload()).containsExactly(originalPayload);

    var storedInitializer =
        state
            .fixture()
            .dsl()
            .fetchOne(
                "SELECT operation_id, open_payload_bytes FROM"
                    + " game_session_canonical_issuer_admission_initializer"
                    + " WHERE tenant_id = ? AND playable_state_namespace_id = ?",
                state.candidate().tenantId(),
                state.candidate().playableStateNamespaceId());
    assertThat(storedInitializer.get("operation_id", UUID.class)).isEqualTo(originalOperationId);
    assertThat(storedInitializer.get("open_payload_bytes", byte[].class))
        .containsExactly(originalPayload);

    state.currentState().set(currentIssuerState(state.candidate(), "moved-source"));
    assertThatThrownBy(() -> state.owner().initializeAndRead(currentOperation.operationId()))
        .isInstanceOf(IllegalStateException.class);

    state.currentState().set(currentIssuerState(state.candidate()));
    var prepared = state.repository().readPreparedInitializer(currentOperation.operationId());
    administratorCommands.del(bytes(prepared.key()));
    assertThatThrownBy(() -> state.owner().initializeAndRead(currentOperation.operationId()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(administratorCommands.exists(bytes(prepared.key()))).isZero();
  }

  @Test
  void callerOwnedJooqTransactionRefusesBeforeSourceOrRemoteDispatch() {
    FixtureState state = fixtureState();
    var dsl = state.fixture().dsl();
    var current = state.currentState().get();
    var anchor =
        dsl.transactionResult(
            configuration -> {
              var transaction = org.jooq.impl.DSL.using(configuration);
              CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
              return CanonicalGameplayIssuerBootstrapCoverageRepository
                  .readInstalledAnchorForBindingPreparationInTransaction(transaction, current);
            });
    AtomicBoolean sourceCalled = new AtomicBoolean();
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource source =
        () -> {
          sourceCalled.set(true);
          return current;
        };
    dsl.transaction(
        configuration -> {
          var transaction = org.jooq.impl.DSL.using(configuration);
          var repository =
              new CanonicalGameplayIssuerAdmissionBarrierRepository(
                  transaction,
                  new CanonicalGameplayTenantGameplayTagRepository(
                      transaction, state.fixture().target().targetNamespace(), 1),
                  source);
          assertThatThrownBy(
                  () -> repository.readPreparedInitializer(state.fixture().originalOperationId()))
              .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
              .hasMessageContaining("jOOQ transaction");
          assertThatThrownBy(
                  () ->
                      new CanonicalGameplayIssuerAdmissionControlRepository(transaction)
                          .installInitialOpen(anchor, source))
              .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
              .hasMessageContaining("jOOQ transaction");
        });
    assertThat(sourceCalled).isFalse();
    assertThat(
            dsl.fetchCount(
                org.jooq.impl.DSL.table("game_session_canonical_issuer_admission_initializer")))
        .isZero();
  }

  private FixtureState fixtureState() {
    Candidate candidate = candidate();
    DriverManagerDataSource accountDataSource = new DriverManagerDataSource();
    accountDataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = POSTGRES.getJdbcUrl();
    accountDataSource.setUrl(
        jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=public");
    accountDataSource.setUsername(POSTGRES.getUsername());
    accountDataSource.setPassword(POSTGRES.getPassword());
    fixture =
        new CanonicalGameplayAdmissionAbortCompositionFixture(
            accountDataSource, candidate, ownerClient);
    Candidate actualCandidate = fixture.candidate();
    CanonicalGameplayBindingIdentity identity = identity(actualCandidate);
    fixture.publish(controlledLeaseEvidence(identity, fixture.generation(), fixture.target()));
    // Controlled upstream Account current-source and stable-incarnation inputs; native SQL/Redis
    // owners execute below, but this fixture does not establish production source custody.
    var currentState = new AtomicReference<>(currentIssuerState(actualCandidate));
    var sourceReader =
        (CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource)
            currentState::get;
    var repository =
        new CanonicalGameplayIssuerAdmissionBarrierRepository(
            fixture.dsl(),
            new CanonicalGameplayTenantGameplayTagRepository(
                fixture.dsl(), fixture.target().targetNamespace(), 1),
            sourceReader);
    return new FixtureState(
        fixture,
        actualCandidate,
        repository,
        new CanonicalGameplayIssuerAdmissionBarrierRedisOwner(
            ownerClient,
            RedisScriptCatalog.fromContributions(
                List.of(new CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution())),
            repository,
            sourceReader),
        ownerClient,
        currentState);
  }

  private CanonicalGameplayIssuerBindingLocalOperation prepareCurrentOperation(
      FixtureState state, String seed, UUID accountId) {
    Candidate candidate = state.candidate();
    CanonicalGameplayBindingIdentity identity =
        new CanonicalGameplayBindingIdentity(
            accountId,
            candidate.tenantId(),
            candidate.playableStateNamespaceId(),
            UUID.randomUUID(),
            candidate.playableStateScope(),
            candidate.gameInstanceId(),
            candidate.runtimeGameInstanceId(),
            UUID.randomUUID().toString(),
            candidate.regionId(),
            candidate.regionEpoch(),
            candidate.issuerId(),
            candidate.issuerAuthGeneration(),
            candidate.issuerIndexLayoutVersion(),
            candidate.issuerIndexPartitionCount(),
            candidate.issuerIndexPartitionCapacity());
    UUID transitionId = UUID.randomUUID();
    var request =
        new CanonicalGameplayIssuerBindingLocalOperation.Request(
            UUID.randomUUID(), transitionId, identity, null, null);
    var operation =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(
                state.fixture().dsl(), state.sourceReader())
            .beginOrResumeOrdinaryNew(request);
    var localCatalog =
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayIssuerBindingLocalRedisScriptContribution()));
    var localProjection =
        new CanonicalGameplayIssuerBindingLocalProjectionRepository(
            state.fixture().dsl(), state.sourceReader());
    var localOwner =
        new CanonicalGameplayIssuerBindingLocalRedisOwner(
            ownerClient, localCatalog, localProjection, state.sourceReader());
    // Capacity is a distinct physical phase: publish it while SQL is still PREPARED.
    localOwner.projectAndRead(operation);
    var inventory = new CanonicalGameplayBindingInventoryRepository(state.fixture().dsl());
    var provisionalEvidence = inventory.readProvisionalCasEvidence(transitionId);
    var lease =
        controlledLeaseEvidence(
            identity, operation.preparedTransition().bindingGeneration(), state.fixture().target());
    inventory.markProvisional(
        provisionalEvidence,
        new CanonicalGameplayAdmissionDecision(
            transitionId,
            lease,
            identity,
            operation.preparedTransition().bindingGeneration(),
            null,
            state.fixture().source().originalDecision().targetEvidence()));
    localOwner.projectAndRead(operation);
    return operation;
  }

  private static Candidate candidate() {
    return new Candidate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "controlled-scope",
        UUID.randomUUID(),
        1L,
        UUID.randomUUID(),
        UUID.randomUUID(),
        ONE,
        ISSUER,
        ONE,
        BigInteger.valueOf(3),
        ONE,
        BigInteger.valueOf(2));
  }

  private static CanonicalGameplayBindingIdentity identity(Candidate candidate) {
    return new CanonicalGameplayBindingIdentity(
        candidate.accountId(),
        candidate.tenantId(),
        candidate.playableStateNamespaceId(),
        candidate.characterId(),
        candidate.playableStateScope(),
        candidate.gameInstanceId(),
        candidate.runtimeGameInstanceId(),
        candidate.sessionId().toString(),
        candidate.regionId(),
        candidate.regionEpoch(),
        candidate.issuerId(),
        candidate.issuerAuthGeneration(),
        candidate.issuerIndexLayoutVersion(),
        candidate.issuerIndexPartitionCount(),
        candidate.issuerIndexPartitionCapacity());
  }

  private static CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState
      currentIssuerState(Candidate candidate) {
    return currentIssuerState(candidate, "controlled-release-composition");
  }

  private static CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState
      currentIssuerState(Candidate candidate, String seed) {
    byte[] checkpoint =
        ("controlled-initial-issuer-checkpoint/v1|" + candidate.issuerId() + "|1")
            .getBytes(StandardCharsets.UTF_8);
    var sourceCheckpoint =
        new CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint(
            candidate.issuerId(),
            candidate.issuerAuthGeneration(),
            checkpoint,
            CanonicalGameplayIssuerMemberV2Codec.sha256(checkpoint),
            null,
            null);
    String digest =
        CanonicalGameplayIssuerMemberV2Codec.sha256(seed.getBytes(StandardCharsets.UTF_8));
    var deployment =
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "controlled-release-deployment-" + seed,
            "controlled-release-generation-" + seed,
            List.of("controlled-release-node-" + seed),
            "controlled-release-redis-build-" + seed,
            "controlled-release-registry-" + seed,
            digest,
            "READY",
            "controlled-release-boot-" + seed,
            "controlled-release-startup-" + seed,
            "controlled-release-storage-" + seed,
            "controlled-release-aof-" + seed);
    var incarnation =
        new CanonicalGameplayIssuerMemberV2Value.Incarnation(
            "controlled-release-attestor-" + seed, digest, deployment, digest, digest);
    return new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
        sourceCheckpoint, incarnation);
  }

  private static AccountGameplayAdmissionLeaseEvidence controlledLeaseEvidence(
      CanonicalGameplayBindingIdentity candidate, BigInteger bindingGeneration, Target target) {
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
    scope.put("sessionId", candidate.sessionId().toString());
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
                "membershipLifecycleState", "ACTIVE",
                "membershipVersion", Map.of(candidate.tenantId().toString(), "1"),
                "membershipAuthorityGeneration", "1")));
    String prefix = "account:auth-authority:v1:";
    carrier.put(
        "outboxCheckpoints",
        List.of(
            checkpoint(prefix + "account/" + candidate.accountId(), "0"),
            checkpoint(prefix + "issuer/" + ISSUER, "0"),
            checkpoint(
                prefix + "membership/" + candidate.accountId() + "/" + candidate.tenantId(), "1"),
            checkpoint(prefix + "tenant/" + candidate.tenantId(), "1")));
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

  private static Map<String, Object> checkpoint(String key, String sequence) {
    return new LinkedHashMap<>(Map.of("outboxStreamKey", key, "outboxSequence", sequence));
  }

  private static String initializationState(FixtureState state, UUID operationId) {
    return state
        .fixture()
        .dsl()
        .fetchSingle(
            "SELECT initialization_state FROM game_session_canonical_issuer_admission_initializer"
                + " WHERE operation_id = ?",
            operationId)
        .get("initialization_state", String.class);
  }

  private static long countReceipt(FixtureState state, UUID operationId) {
    return state
        .fixture()
        .dsl()
        .fetchSingle(
            "SELECT count(*) AS receipt_count"
                + " FROM game_session_canonical_issuer_admission_initializer_receipt"
                + " WHERE operation_id = ?",
            operationId)
        .get("receipt_count", Long.class);
  }

  private void writePhysicalValue(String key, CanonicalGameplayIssuerAdmissionBarrierValue value) {
    var initializer = value.initializer();
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("schema", CanonicalGameplayIssuerAdmissionBarrierValue.SCHEMA);
    fields.put("issuerId", value.issuerId());
    fields.put("tenantId", value.tenantId().toString());
    fields.put("playableStateNamespaceId", value.playableStateNamespaceId().toString());
    fields.put("tenantGameplayTag", value.tenantGameplayTag());
    fields.put("state", value.state().name());
    fields.put("admissionEpoch", value.admissionEpoch().toString());
    fields.put("ownerOperationId", value.ownerOperationId().toString());
    fields.put(
        "initializerOperationId",
        initializer == null ? "none" : initializer.operationId().toString());
    fields.put(
        "initializerEpoch", initializer == null ? "0" : initializer.admissionEpoch().toString());
    fields.put("initializerDigest", initializer == null ? "none" : initializer.exactDigest());
    fields.put("payloadDigest", value.exactDigest());
    for (Map.Entry<String, String> field : fields.entrySet()) {
      administratorCommands.hset(bytes(key), bytes(field.getKey()), bytes(field.getValue()));
    }
    administratorCommands.hset(bytes(key), bytes("payload"), value.exactBytes());
  }

  private static RedisClient createRedisClient(String username, String password) {
    RedisURI.Builder builder = RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(6379));
    if (username != null) builder.withAuthentication(username, password);
    RedisURI uri = builder.build();
    uri.setTimeout(Duration.ofSeconds(5));
    RedisClient client = RedisClient.create(uri);
    client.setOptions(
        ClientOptions.builder()
            .autoReconnect(false)
            .protocolVersion(io.lettuce.core.protocol.ProtocolVersion.RESP2)
            .build());
    return client;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private record FixtureState(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture,
      Candidate candidate,
      CanonicalGameplayIssuerAdmissionBarrierRepository repository,
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner owner,
      RedisClient ownerClient,
      AtomicReference<CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState>
          currentState) {
    private CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
        sourceReader() {
      return currentState::get;
    }

    private CanonicalGameplayIssuerAdmissionBarrierRedisOwner owner(
        CanonicalGameplayIssuerAdmissionBarrierRedisOwner.ProjectionRepository
            projectionRepository) {
      return new CanonicalGameplayIssuerAdmissionBarrierRedisOwner(
          ownerClient,
          RedisScriptCatalog.fromContributions(
              List.of(new CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution())),
          projectionRepository,
          sourceReader());
    }
  }
}
