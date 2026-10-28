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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot.Family;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual PostgreSQL/Redis proof for local ordinary NEW registration after the installed anchor. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayIssuerBindingLocalOperationRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String OWNER_PRINCIPAL = "gamesession_coord_app";
  private static final String ISSUER_KEY_PREFIX = "session:game:index:issuer:";
  private static final BigInteger LAYOUT_VERSION = BigInteger.valueOf(3);
  private static final BigInteger PARTITION_COUNT = BigInteger.ONE;
  private static final BigInteger PARTITION_CAPACITY = BigInteger.valueOf(2);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  private TestSchema schema;
  private RedisClient administrator;
  private RedisClient ownerClient;
  private StatefulRedisConnection<byte[], byte[]> administratorConnection;
  private RedisCommands<byte[], byte[]> administratorCommands;

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
            .keyPattern(ISSUER_KEY_PREFIX + "*")
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
    ownerClient = createRedisClient(OWNER_PRINCIPAL, password);
    schema = newTestSchemaAtVersion("gs_issuer_local_new_");
  }

  @AfterEach
  void tearDown() {
    if (administratorConnection != null) administratorConnection.close();
    if (ownerClient != null) ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (administrator != null) administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (schema != null) dropSchema(schema.schema());
  }

  @Test
  void registersOrdinaryNewAgainstInstalledAnchorAndReusesExactPendingOperation() throws Exception {
    DSLContext dsl = schema.dsl();
    establishActualLegacyDisposition(dsl);
    var layout = descriptor();
    UUID bootstrapRequestId = UUID.randomUUID();
    var source = sourceCheckpoint(BigInteger.valueOf(7));
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(dsl)
            .beginInitialBootstrapIntent(bootstrapRequestId, layout, source);
    migrateRemaining(schema);

    var incarnation = stableIncarnation("local-new-anchor");
    var initialState =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            intent.sourceCheckpoint(), incarnation);
    AtomicReference<CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState>
        currentState = new AtomicReference<>(initialState);
    var operationRepository =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, currentState::get);
    var firstCandidate = identity("first-new");
    var firstRequest = request("first-new-request", "first-new-transition", firstCandidate);

    assertThatThrownBy(() -> operationRepository.beginOrResumeOrdinaryNew(firstRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("anchor");
    assertThat(count(dsl, "game_session_canonical_issuer_binding_local_operation")).isZero();
    assertThat(count(dsl, "game_session_canonical_gameplay_binding_inventory")).isZero();

    CanonicalGameplayIssuerInstalledAnchor anchor =
        initialCoverageOwner(dsl, currentState::get)
            .completeInitialBootstrap(intent.operationId(), bootstrapRequestId);
    assertThatThrownBy(() -> operationRepository.beginOrResumeOrdinaryNew(firstRequest))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("positive live admission-control row");
    assertThat(count(dsl, "game_session_canonical_issuer_binding_local_operation")).isZero();
    new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
        .installInitialOpen(anchor, currentState::get);
    String installedAnchorImage = anchorImage(dsl);
    String installedProofImage = proofImage(dsl);
    String initialReservationImage = reservationImage(dsl);
    BigInteger initialGlobalCoverageGeneration = anchor.globalCoverageGeneration();
    String beforeFirstAllocation = localRegistrationImage(dsl);
    assertThat(beforeFirstAllocation).isNotNull().endsWith(":missing");

    AtomicInteger sourceReads = new AtomicInteger();
    CyclicBarrier concurrentPreReadBarrier = new CyclicBarrier(2);
    var concurrentSource =
        (CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource)
            () -> {
              if (sourceReads.incrementAndGet() <= 2) {
                try {
                  concurrentPreReadBarrier.await(30, TimeUnit.SECONDS);
                } catch (Exception failure) {
                  throw new IllegalStateException(
                      "Concurrent source pre-reads did not rendezvous", failure);
                }
              }
              return currentState.get();
            };
    var concurrentRepository =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, concurrentSource);
    ExecutorService concurrentCallers = Executors.newFixedThreadPool(2);
    CanonicalGameplayIssuerBindingLocalOperation first;
    CanonicalGameplayIssuerBindingLocalOperation concurrentReplay;
    try {
      var firstCall =
          concurrentCallers.submit(
              () -> concurrentRepository.beginOrResumeOrdinaryNew(firstRequest));
      var secondCall =
          concurrentCallers.submit(
              () -> concurrentRepository.beginOrResumeOrdinaryNew(firstRequest));
      first = firstCall.get(30, TimeUnit.SECONDS);
      concurrentReplay = secondCall.get(30, TimeUnit.SECONDS);
    } finally {
      concurrentCallers.shutdownNow();
    }
    assertThat(sourceReads.get()).isEqualTo(4);
    assertThat(localRegistrationImage(dsl)).isNotEqualTo(beforeFirstAllocation);
    assertThat(localRegistrationImage(dsl)).doesNotEndWith(":missing");
    assertThat(concurrentReplay.operationId()).isEqualTo(first.operationId());
    assertThat(concurrentReplay.reservation().reservationId())
        .isEqualTo(first.reservation().reservationId());
    assertThat(concurrentReplay.reservation().reservationFence())
        .isEqualTo(first.reservation().reservationFence());
    assertThat(concurrentReplay.candidateAccountIndexFence())
        .isEqualTo(first.candidateAccountIndexFence());
    assertThat(concurrentReplay.operationFence()).isEqualTo(first.operationFence());
    assertThat(concurrentReplay.localMemberGeneration()).isEqualTo(first.localMemberGeneration());
    assertThat(concurrentReplay.localInventorySnapshotRevision())
        .isEqualTo(first.localInventorySnapshotRevision());
    assertThat(concurrentReplay.exactMemberValueBytes())
        .containsExactly(first.exactMemberValueBytes());
    assertThat(count(dsl, "game_session_canonical_issuer_binding_local_operation")).isEqualTo(1L);
    assertThat(count(dsl, "game_session_canonical_issuer_partition_reservation")).isEqualTo(1L);
    assertThat(first.status())
        .isEqualTo(CanonicalGameplayIssuerBindingLocalOperation.Status.PENDING_PHYSICAL_PROOF);
    assertThat(first.request()).isEqualTo(firstRequest);
    assertThat(first.preparedTransition().status())
        .isEqualTo(
            net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot
                .Status.PREPARED);
    assertThat(first.reservation().lifecycle())
        .isEqualTo(
            net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation.Lifecycle
                .RESERVED);
    assertThat(first.reservation().issuerCoverageOperationId())
        .isEqualTo(first.anchor().issuerCoverageOperationId());
    assertThat(first.reservation().issuerCoverageOperationFence())
        .isEqualTo(first.anchor().operationFence());
    assertThat(first.reservation().coverageFence()).isEqualTo(first.anchor().coverageFence());
    assertThat(first.reservation().inventorySnapshotRevision())
        .isEqualTo(first.anchor().issuerInventorySnapshotRevision());
    assertThat(first.localInventorySnapshotRevision())
        .isEqualTo(first.reservation().inventoryRevision());
    assertThat(first.memberValue().anchor().issuerCoverageOperationId())
        .isEqualTo(anchor.issuerCoverageOperationId());
    assertThat(first.anchor().exactAnchorBytes()).containsExactly(anchor.exactAnchorBytes());
    assertThat(first.memberValue().issuerControlEpoch()).isEqualTo(anchor.controlEpoch());
    assertThat(first.memberValue().layoutDescriptor().exactDescriptorBytes())
        .containsExactly(anchor.layoutDescriptorBytes());
    assertThat(first.memberValue().layoutDescriptor().descriptorDigest())
        .isEqualTo(anchor.layoutDescriptorDigest());
    assertThat(first.memberValue().sourceCheckpoint().exactCheckpointBytes())
        .containsExactly(anchor.sourceCheckpointBytes());
    assertThat(first.memberValue().sourceCheckpoint().latestIssuerEventBytes())
        .containsExactly(anchor.sourceEventBytes());
    Record sourceEventPair =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT anchor_source_event_bytes, anchor_source_event_digest FROM"
                    + " game_session_canonical_issuer_binding_local_operation WHERE operation_id = ?",
                first.operationId()),
            "Expected local operation source-event pair");
    assertThat(sourceEventPair.get("anchor_source_event_bytes", byte[].class))
        .containsExactly(anchor.sourceEventBytes());
    assertThat(sourceEventPair.get("anchor_source_event_digest", String.class))
        .isEqualTo(anchor.sourceEventDigest());
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO game_session_canonical_issuer_binding_local_operation"
                        + " SELECT (jsonb_populate_record(NULL::"
                        + "game_session_canonical_issuer_binding_local_operation,"
                        + " to_jsonb(operation) || jsonb_build_object('operation_id', ?::text,"
                        + " 'anchor_source_event_digest', NULL))).*"
                        + " FROM game_session_canonical_issuer_binding_local_operation operation"
                        + " WHERE operation_id = ?",
                    UUID.randomUUID(),
                    first.operationId()))
        .hasMessageContaining("chk_gs_issuer_binding_local_operation_source_event_pair");
    assertThat(first.memberValue().incarnation()).isEqualTo(incarnation);
    assertThat(first.memberValue().memberOperation().coverageGeneration())
        .isEqualTo(first.localMemberGeneration());
    assertThat(first.expectedOldMemberState()).isEqualTo("ABSENT");
    assertThat(first.expectedOldCapacityState()).isEqualTo("ABSENT");
    assertThat(first.exactMemberValueBytes())
        .containsExactly(CanonicalGameplayIssuerMemberV2Codec.encode(first.memberValue()));
    assertThat(first.memberValueDigest())
        .isEqualTo(CanonicalGameplayIssuerMemberV2Codec.sha256(first.exactMemberValueBytes()));
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT member_status, member_schema_version FROM"
                            + " game_session_canonical_issuer_binding_local_operation WHERE transition_id = ?",
                        firstRequest.transitionId()),
                    "Expected local operation row")
                .get("member_status", String.class))
        .isEqualTo("PENDING_PHYSICAL_PROOF");
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT member_schema_version FROM"
                            + " game_session_canonical_issuer_binding_local_operation WHERE transition_id = ?",
                        firstRequest.transitionId()),
                    "Expected local operation row")
                .get("member_schema_version", Short.class))
        .isEqualTo((short) 2);
    assertThat(count(dsl, "game_session_canonical_binding_issuer_index_obligation")).isEqualTo(1L);
    assertThat(count(dsl, "game_session_canonical_issuer_binding_local_operation")).isEqualTo(1L);
    assertThat(count(dsl, "game_session_canonical_gameplay_binding_inventory")).isEqualTo(1L);
    assertThat(count(dsl, "game_session_canonical_binding_generation")).isEqualTo(1L);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT last_issued_coverage_generation FROM"
                            + " game_session_canonical_issuer_bootstrap_coverage_counter WHERE issuer_id = ?",
                        ISSUER),
                    "Expected issuer coverage counter row")
                .get("last_issued_coverage_generation", BigDecimal.class))
        .isEqualTo(new BigDecimal(initialGlobalCoverageGeneration));
    assertThat(anchorImage(dsl)).isEqualTo(installedAnchorImage);
    assertThat(proofImage(dsl)).isEqualTo(installedProofImage);
    assertThat(
            administratorCommands.hlen(
                CanonicalGameplayIssuerRedisKeys.forPartition(
                        intent.layoutDescriptor(), BigInteger.ZERO)
                    .getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo(2L);

    String afterFirstRegistration = localRegistrationImage(dsl);
    CanonicalGameplayIssuerBindingLocalOperation replay =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, currentState::get)
            .beginOrResumeOrdinaryNew(firstRequest);
    assertThat(replay.operationId()).isEqualTo(first.operationId());
    assertThat(replay.operationFence()).isEqualTo(first.operationFence());
    assertThat(replay.localMemberGeneration()).isEqualTo(first.localMemberGeneration());
    assertThat(replay.exactMemberValueBytes()).containsExactly(first.exactMemberValueBytes());
    assertThat(localRegistrationImage(dsl)).isEqualTo(afterFirstRegistration);
    assertThat(anchorImage(dsl)).isEqualTo(installedAnchorImage);
    assertThat(proofImage(dsl)).isEqualTo(installedProofImage);

    String beforeRefusals = localRegistrationImage(dsl);
    assertThatThrownBy(
            () ->
                operationRepository.beginOrResumeOrdinaryNew(
                    request("changed-first-request", "first-new-transition", firstCandidate)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(localRegistrationImage(dsl)).isEqualTo(beforeRefusals);
    assertThatThrownBy(
            () ->
                operationRepository.beginOrResumeOrdinaryNew(
                    request("first-new-request", "other-transition", identity("changed-first"))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(localRegistrationImage(dsl)).isEqualTo(beforeRefusals);

    currentState.set(
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            checkpointShape(BigInteger.valueOf(8)), incarnation));
    assertThatThrownBy(
            () ->
                operationRepository.beginOrResumeOrdinaryNew(
                    request(
                        "moved-source-request",
                        "moved-source-transition",
                        identity("moved-source"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source");
    assertThat(localRegistrationImage(dsl)).isEqualTo(beforeRefusals);
    currentState.set(initialState);

    currentState.set(
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            intent.sourceCheckpoint(), stableIncarnation("moved-incarnation")));
    assertThatThrownBy(
            () ->
                operationRepository.beginOrResumeOrdinaryNew(
                    request(
                        "moved-incarnation-request",
                        "moved-incarnation-transition",
                        identity("moved-incarnation"))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(localRegistrationImage(dsl)).isEqualTo(beforeRefusals);
    currentState.set(initialState);

    assertThatThrownBy(
            () ->
                operationRepository.beginOrResumeOrdinaryNew(
                    request(
                        "wrong-layout-request",
                        "wrong-layout-transition",
                        identityWithLayout("wrong-layout", BigInteger.TWO))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("layout");
    assertThat(localRegistrationImage(dsl)).isEqualTo(beforeRefusals);

    var sameController = sameControllerDifferentBinding(firstCandidate, "same-controller");
    assertThatThrownBy(
            () ->
                operationRepository.beginOrResumeOrdinaryNew(
                    request("controller-request", "controller-transition", sameController)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("controller");
    assertThat(localRegistrationImage(dsl)).isEqualTo(beforeRefusals);

    var fencedCandidate = identityForAccount("account-fenced", firstCandidate.accountId());
    new CanonicalGameplayAccountAdmissionBarrierRepository(dsl)
        .beginFenceIntent(firstCandidate.accountId(), UUID.randomUUID());
    String accountFencedImage = localRegistrationImage(dsl);
    assertThatThrownBy(
            () ->
                operationRepository.beginOrResumeOrdinaryNew(
                    request(
                        "account-fenced-request", "account-fenced-transition", fencedCandidate)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("Account");
    assertThat(localRegistrationImage(dsl)).isEqualTo(accountFencedImage);

    var secondCandidate = identity("second-new");
    var secondRequest = request("second-new-request", "second-new-transition", secondCandidate);
    var movedAfterRegistrationState =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            checkpointShape(BigInteger.valueOf(8)), incarnation);
    AtomicInteger movementReadCount = new AtomicInteger();
    var movingSource =
        (CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource)
            () ->
                movementReadCount.incrementAndGet() == 1
                    ? initialState
                    : movedAfterRegistrationState;
    var movingRepository =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, movingSource);
    assertThatThrownBy(() -> movingRepository.beginOrResumeOrdinaryNew(secondRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pending local operation remains durable");
    UUID persistedSecondOperationId =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT operation_id FROM game_session_canonical_issuer_binding_local_operation"
                        + " WHERE transition_id = ?",
                    secondRequest.transitionId()),
                "Expected local operation row")
            .get("operation_id", UUID.class);
    var second = operationRepository.beginOrResumeOrdinaryNew(secondRequest);
    assertThat(second.operationId()).isEqualTo(persistedSecondOperationId);
    assertThat(second.localMemberGeneration()).isGreaterThan(first.localMemberGeneration());
    assertThat(count(dsl, "game_session_canonical_issuer_binding_local_operation")).isEqualTo(2L);
    assertThat(count(dsl, "game_session_canonical_issuer_partition_reservation")).isEqualTo(2L);
    assertThat(anchorImage(dsl)).isEqualTo(installedAnchorImage);
    assertThat(proofImage(dsl)).isEqualTo(installedProofImage);

    String beforeCapacityRefusal = localRegistrationImage(dsl);
    assertThatThrownBy(
            () ->
                operationRepository.beginOrResumeOrdinaryNew(
                    request("capacity-request", "capacity-transition", identity("capacity-new"))))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("capacity");
    assertThat(localRegistrationImage(dsl)).isEqualTo(beforeCapacityRefusal);
    assertThat(anchorImage(dsl)).isEqualTo(installedAnchorImage);
    assertThat(proofImage(dsl)).isEqualTo(installedProofImage);
    assertThat(reservationImage(dsl)).isNotEqualTo(initialReservationImage);
    assertThat(count(dsl, "game_session_canonical_issuer_binding_local_operation")).isEqualTo(2L);
    assertThat(
            administratorCommands.hlen(
                CanonicalGameplayIssuerRedisKeys.forPartition(
                        intent.layoutDescriptor(), BigInteger.ZERO)
                    .getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo(2L);
  }

  @Test
  void localIssuerOwnerRetainsPairedNullSourceEventEvidence() {
    DSLContext dsl = schema.dsl();
    establishActualLegacyDisposition(dsl);
    byte[] exactCheckpoint =
        "issuer-checkpoint-without-latest-event".getBytes(StandardCharsets.UTF_8);
    var source =
        new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint(
            ISSUER, BigInteger.valueOf(7), exactCheckpoint, null);
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(dsl)
            .beginInitialBootstrapIntent(UUID.randomUUID(), descriptor(), source);
    migrateRemaining(schema);
    var currentState =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            intent.sourceCheckpoint(), stableIncarnation("null-source-event"));
    var anchor =
        initialCoverageOwner(dsl, () -> currentState)
            .completeInitialBootstrap(intent.operationId(), intent.requestId());
    new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
        .installInitialOpen(anchor, () -> currentState);
    var operation =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, () -> currentState)
            .beginOrResumeOrdinaryNew(
                request(
                    "null-source-event-request",
                    "null-source-event-transition",
                    identity("null-source-event")));

    Record sourceEventPair =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT anchor_source_event_bytes, anchor_source_event_digest FROM"
                    + " game_session_canonical_issuer_binding_local_operation WHERE operation_id = ?",
                operation.operationId()),
            "Expected local operation source-event pair");
    assertThat(anchor.sourceEventBytes()).isNull();
    assertThat(anchor.sourceEventDigest()).isNull();
    assertThat(sourceEventPair.get("anchor_source_event_bytes", byte[].class)).isNull();
    assertThat(sourceEventPair.get("anchor_source_event_digest", String.class)).isNull();
  }

  @Test
  void ordinaryNewPhysicalOperationBoundRefuses257thBeforePreparationAndRetainsExact256thRetry() {
    DSLContext dsl = schema.dsl();
    establishActualLegacyDisposition(dsl);
    BigInteger capacity = BigInteger.valueOf(512);
    UUID bootstrapRequestId = UUID.randomUUID();
    var source = sourceCheckpoint(BigInteger.valueOf(7));
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(dsl)
            .beginInitialBootstrapIntent(bootstrapRequestId, descriptor(capacity), source);
    migrateRemaining(schema);
    var currentState =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            intent.sourceCheckpoint(), stableIncarnation("physical-operation-bound"));
    var anchor =
        initialCoverageOwner(dsl, () -> currentState)
            .completeInitialBootstrap(intent.operationId(), bootstrapRequestId);
    new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
        .installInitialOpen(anchor, () -> currentState);
    var repository =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, () -> currentState);
    // Every retained registration is created by the actual SQL owner. No local projection or
    // release/retirement is fabricated; these are pending operations under controlled sources.
    for (int index = 0; index < 255; index++) {
      repository.beginOrResumeOrdinaryNew(
          request(
              "physical-bound-request-" + index,
              "physical-bound-transition-" + index,
              identityWithLayout("physical-bound-" + index, LAYOUT_VERSION, capacity)));
    }
    assertThat(count(dsl, "game_session_canonical_issuer_binding_local_operation")).isEqualTo(255L);
    var lastRequest =
        request(
            "physical-bound-request-255",
            "physical-bound-transition-255",
            identityWithLayout("physical-bound-255", LAYOUT_VERSION, capacity));
    var last = repository.beginOrResumeOrdinaryNew(lastRequest);
    assertThat(count(dsl, "game_session_canonical_issuer_binding_local_operation")).isEqualTo(256L);
    String beforeRefusal = localRegistrationImage(dsl);
    String anchorBefore = anchorImage(dsl);
    String proofBefore = proofImage(dsl);
    assertThatThrownBy(
            () ->
                repository.beginOrResumeOrdinaryNew(
                    request(
                        "physical-bound-request-256",
                        "physical-bound-transition-256",
                        identityWithLayout("physical-bound-256", LAYOUT_VERSION, capacity))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("physical owner bound");
    assertThat(localRegistrationImage(dsl)).isEqualTo(beforeRefusal);
    var retry = repository.beginOrResumeOrdinaryNew(lastRequest);
    assertThat(retry.operationId()).isEqualTo(last.operationId());
    assertThat(retry.exactMemberValueBytes()).containsExactly(last.exactMemberValueBytes());
    assertThat(localRegistrationImage(dsl)).isEqualTo(beforeRefusal);
    assertThat(anchorImage(dsl)).isEqualTo(anchorBefore);
    assertThat(proofImage(dsl)).isEqualTo(proofBefore);
  }

  @Test
  void forwardMigrationRetainsCommittedAndPendingPhysicalMutationLineage() {
    DSLContext dsl = schema.dsl();
    establishActualLegacyDisposition(dsl);
    var source = sourceCheckpoint(BigInteger.valueOf(7));
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(dsl)
            .beginInitialBootstrapIntent(UUID.randomUUID(), descriptor(), source);
    Flyway.configure()
        .dataSource(schema.dataSource())
        .schemas(schema.schema())
        .defaultSchema(schema.schema())
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("1038.3"))
        .load()
        .migrate();
    var state =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            intent.sourceCheckpoint(), stableIncarnation("lineage-migration"));
    var anchor =
        initialCoverageOwner(dsl, () -> state)
            .completeInitialBootstrap(intent.operationId(), intent.requestId());
    var local =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, () -> state)
            .beginOrResumeOrdinaryNew(
                request("migration-request", "migration-transition", identity("migration")));
    UUID committedId = UUID.randomUUID();
    UUID pendingId = UUID.randomUUID();
    UUID successor = UUID.randomUUID();
    UUID laterSuccessor = UUID.randomUUID();
    UUID initialRevision =
        dsl.fetchSingle(
                "SELECT current_revision FROM game_session_canonical_issuer_partition_mutation_head WHERE issuer_id = ?",
                ISSUER)
            .get(0, UUID.class);
    byte[] intended = "controlled-pre-upgrade-mutation".getBytes(StandardCharsets.US_ASCII);
    byte[] readback =
        CanonicalGameplayIssuerBootstrapCoverageRepository.encodeFieldSet(
            java.util.Map.of(
                CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD,
                successor.toString().getBytes(StandardCharsets.US_ASCII)));
    // Controlled pre-upgrade SQL receipts exercise retention, immutability and the pending FK.
    // They do not claim a Redis mutation/readback or authorize admission; the bootstrap above is
    // the actual local SQL/Redis owner, under controlled Account/incarnation collaborators.
    dsl.transaction(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          insertPreLineageMutation(
              transaction,
              committedId,
              local.operationId(),
              "ADD_CAPACITY",
              initialRevision,
              successor,
              intended);
          transaction.execute(
              "UPDATE game_session_canonical_issuer_partition_mutation_head"
                  + " SET pending_mutation_id = ? WHERE issuer_id = ?",
              committedId,
              ISSUER);
          transaction.execute(
              "UPDATE game_session_canonical_issuer_partition_mutation"
                  + " SET status = 'COMMITTED', exact_readback_field_set_bytes = ?,"
                  + " exact_readback_field_set_digest = ?, completed_at = CURRENT_TIMESTAMP WHERE mutation_id = ?",
              readback,
              digest(readback),
              committedId);
          transaction.execute(
              "UPDATE game_session_canonical_issuer_partition_mutation_head"
                  + " SET current_revision = ?, pending_mutation_id = NULL WHERE issuer_id = ?",
              successor,
              ISSUER);
          insertPreLineageMutation(
              transaction,
              pendingId,
              local.operationId(),
              "ADD_BINDING",
              successor,
              laterSuccessor,
              intended);
          transaction.execute(
              "UPDATE game_session_canonical_issuer_partition_mutation_head"
                  + " SET pending_mutation_id = ? WHERE issuer_id = ?",
              pendingId,
              ISSUER);
        });
    List<String> beforeHistory =
        dsl.fetch(
                "SELECT to_jsonb(mutation)::text FROM game_session_canonical_issuer_partition_mutation mutation ORDER BY mutation_id")
            .getValues(0, String.class);
    List<String> beforeHeads =
        dsl.fetch(
                "SELECT to_jsonb(head)::text FROM game_session_canonical_issuer_partition_mutation_head head ORDER BY partition_id")
            .getValues(0, String.class);
    String beforeAnchor = anchorImage(dsl);
    String beforeProof = proofImage(dsl);
    migrateRemaining(schema);
    assertThat(
            dsl.fetch(
                    "SELECT (to_jsonb(mutation) - 'bootstrap_operation_id')::text"
                        + " FROM game_session_canonical_issuer_partition_mutation mutation ORDER BY mutation_id")
                .getValues(0, String.class))
        .containsExactlyElementsOf(beforeHistory);
    assertThat(
            dsl.fetch(
                    "SELECT to_jsonb(head)::text FROM game_session_canonical_issuer_partition_mutation_head head ORDER BY partition_id")
                .getValues(0, String.class))
        .containsExactlyElementsOf(beforeHeads);
    assertThat(anchorImage(dsl)).isEqualTo(beforeAnchor);
    assertThat(proofImage(dsl)).isEqualTo(beforeProof);
    assertThat(
            dsl.fetch(
                    "SELECT DISTINCT bootstrap_operation_id FROM game_session_canonical_issuer_partition_mutation")
                .getValues(0, UUID.class))
        .containsExactly(anchor.bootstrapOperationId());
    dsl.transaction(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          var head =
              CanonicalGameplayIssuerPartitionMutationRepository.readHeadInTransaction(
                  transaction, anchor.bootstrapOperationId(), BigInteger.ZERO);
          assertThat(head.pendingMutationId()).isEqualTo(pendingId);
          assertThat(head.currentRevision()).isEqualTo(successor);
          var history =
              CanonicalGameplayIssuerPartitionMutationRepository.readPartitionInTransaction(
                  transaction, anchor.bootstrapOperationId(), BigInteger.ZERO);
          assertThat(history).hasSize(2);
          assertThat(history.stream().map(CanonicalGameplayIssuerPartitionMutation::status))
              .containsExactlyInAnyOrder(
                  CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED,
                  CanonicalGameplayIssuerPartitionMutation.Status.PENDING_PHYSICAL_PROOF);
        });
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_issuer_partition_mutation SET bootstrap_operation_id = ? WHERE mutation_id = ?",
                    UUID.randomUUID(),
                    pendingId))
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_issuer_partition_mutation_head SET bootstrap_operation_id = ? WHERE issuer_id = ?",
                    UUID.randomUUID(),
                    ISSUER))
        .hasMessageContaining("immutable");
    assertThat(
            dsl.fetch(
                    "SELECT conname, pg_get_constraintdef(oid) AS definition FROM pg_constraint"
                        + " WHERE conrelid IN ('game_session_canonical_issuer_partition_mutation'::regclass,"
                        + " 'game_session_canonical_issuer_partition_mutation_head'::regclass)"
                        + " AND conname IN ('fk_gs_issuer_partition_mutation_head', 'fk_gs_issuer_partition_mutation_head_pending')")
                .getValues("definition", String.class))
        .hasSize(2)
        .allSatisfy(definition -> assertThat(definition).contains("bootstrap_operation_id"));
  }

  private static void insertPreLineageMutation(
      DSLContext transaction,
      UUID mutationId,
      UUID ownerId,
      String kind,
      UUID expected,
      UUID successor,
      byte[] intended) {
    transaction.execute(
        "INSERT INTO game_session_canonical_issuer_partition_mutation"
            + " (mutation_id, issuer_id, partition_id, mutation_kind, owner_operation_id, expected_revision,"
            + " successor_revision, exact_intended_change_bytes, exact_intended_change_digest, status)"
            + " VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, 'PENDING_PHYSICAL_PROOF')",
        mutationId,
        ISSUER,
        kind,
        ownerId,
        expected,
        successor,
        intended,
        digest(intended));
  }

  private CanonicalGameplayIssuerBootstrapCoverageOwner initialCoverageOwner(
      DSLContext dsl,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource source) {
    var partitionOwner =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner(
            ownerClient,
            RedisScriptCatalog.fromContributions(
                List.of(new CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution())),
            source);
    return new CanonicalGameplayIssuerBootstrapCoverageOwner(dsl, partitionOwner, source);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor
      descriptor() {
    return descriptor(PARTITION_CAPACITY);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor
      descriptor(BigInteger capacity) {
    String exact =
        "issuer-layout-descriptor/v1|"
            + ISSUER
            + "|"
            + LAYOUT_VERSION
            + "|"
            + PARTITION_COUNT
            + "|"
            + capacity
            + "|"
            + CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY;
    return new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor(
        ISSUER,
        LAYOUT_VERSION,
        PARTITION_COUNT,
        capacity,
        CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY,
        exact.getBytes(StandardCharsets.UTF_8));
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
      sourceCheckpoint(BigInteger generation) {
    long sequence = generation.longValueExact() - 1;
    var event =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
            new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                AccountAuthoritySourceEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + ISSUER,
                Long.toString(sequence),
                ISSUER,
                generation.toString(),
                generation.toString(),
                "ISSUER_SECURITY_CHANGE"));
    byte[] bytes = event.canonicalJsonUtf8();
    return new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint(
        ISSUER, generation, bytes, bytes);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint
      checkpointShape(BigInteger generation) {
    var trusted = sourceCheckpoint(generation);
    byte[] checkpoint = trusted.checkpointBytes();
    byte[] event = trusted.latestEventBytes();
    return new CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint(
        trusted.issuerId(),
        trusted.issuerAuthGeneration(),
        checkpoint,
        CanonicalGameplayIssuerMemberV2Codec.sha256(checkpoint),
        event,
        CanonicalGameplayIssuerMemberV2Codec.sha256(event));
  }

  private static CanonicalGameplayIssuerMemberV2Value.Incarnation stableIncarnation(String seed) {
    String digest = digest(seed.getBytes(StandardCharsets.UTF_8));
    var deployment =
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "controlled-deployment-" + seed,
            "deployment-generation-" + seed,
            List.of("controlled-node-" + seed),
            "controlled-redis-build-" + seed,
            "controlled-lua-registry-" + seed,
            digest,
            "READY",
            "boot-" + seed,
            "startup-" + seed,
            "storage-" + seed,
            "aof-" + seed);
    return new CanonicalGameplayIssuerMemberV2Value.Incarnation(
        "controlled-attestor-" + seed, digest, deployment, digest, digest);
  }

  private static CanonicalGameplayIssuerBindingLocalOperation.Request request(
      String requestSeed, String transitionSeed, CanonicalGameplayBindingIdentity candidate) {
    return new CanonicalGameplayIssuerBindingLocalOperation.Request(
        UUID.nameUUIDFromBytes(requestSeed.getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes(transitionSeed.getBytes(StandardCharsets.UTF_8)),
        candidate,
        null,
        null);
  }

  private static CanonicalGameplayBindingIdentity identity(String seed) {
    return identityWithLayout(seed, LAYOUT_VERSION);
  }

  private static CanonicalGameplayBindingIdentity identityForAccount(String seed, UUID accountId) {
    CanonicalGameplayBindingIdentity identity = identity(seed);
    return new CanonicalGameplayBindingIdentity(
        accountId,
        identity.tenantId(),
        identity.playableStateNamespaceId(),
        identity.characterId(),
        identity.playableStateScope(),
        identity.gameInstanceId(),
        identity.runtimeGameInstanceId(),
        identity.sessionId(),
        identity.regionId(),
        identity.regionEpoch(),
        identity.issuerId(),
        identity.issuerAuthGeneration(),
        identity.issuerIndexLayoutVersion(),
        identity.issuerIndexPartitionCount(),
        identity.issuerIndexPartitionCapacity());
  }

  private static CanonicalGameplayBindingIdentity identityWithLayout(
      String seed, BigInteger layoutVersion) {
    return identityWithLayout(seed, layoutVersion, PARTITION_CAPACITY);
  }

  private static CanonicalGameplayBindingIdentity identityWithLayout(
      String seed, BigInteger layoutVersion, BigInteger capacity) {
    return new CanonicalGameplayBindingIdentity(
        uuid(seed + "-account"),
        uuid(seed + "-tenant"),
        uuid(seed + "-namespace"),
        uuid(seed + "-character"),
        "SHARED",
        uuid(seed + "-game"),
        90_000L + Math.abs((long) seed.hashCode()),
        uuid(seed + "-session").toString(),
        uuid(seed + "-region"),
        BigInteger.valueOf(12),
        ISSUER,
        BigInteger.valueOf(7),
        layoutVersion,
        PARTITION_COUNT,
        capacity);
  }

  private static CanonicalGameplayBindingIdentity sameControllerDifferentBinding(
      CanonicalGameplayBindingIdentity original, String seed) {
    return new CanonicalGameplayBindingIdentity(
        original.accountId(),
        original.tenantId(),
        original.playableStateNamespaceId(),
        original.characterId(),
        original.playableStateScope(),
        uuid(seed + "-game"),
        original.runtimeGameInstanceId() + 1,
        uuid(seed + "-session").toString(),
        original.regionId(),
        original.regionEpoch(),
        original.issuerId(),
        original.issuerAuthGeneration(),
        original.issuerIndexLayoutVersion(),
        original.issuerIndexPartitionCount(),
        original.issuerIndexPartitionCapacity());
  }

  private static UUID uuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private void establishActualLegacyDisposition(DSLContext dsl) {
    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    var migration = new CanonicalGameplayLegacyMigrationRepository(dsl, inventory);
    UUID cohortId = UUID.randomUUID();
    UUID writerFence = UUID.randomUUID();
    var storageIdentity =
        new CanonicalGameplayLegacyMigrationStorageIdentity(
            "controlled-cluster-uid",
            "controlled-namespace-uid",
            "controlled-producer-pod-uid",
            "controlled-container-id",
            "controlled-node-uid",
            "123456789",
            42L,
            "controlled-postgres-volume-uid",
            "controlled-redis-run-id",
            "controlled-redis-volume-uid");
    var fencedCohort =
        new CanonicalGameplayLegacyMigrationOwner.FencedCohort() {
          @Override
          public UUID cohortId() {
            return cohortId;
          }

          @Override
          public UUID legacyWriterFence() {
            return writerFence;
          }

          @Override
          public CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity() {
            return storageIdentity;
          }

          @Override
          public void requireStillFenced(CanonicalGameplayLegacyMigrationStorageIdentity expected) {
            if (!storageIdentity.equals(expected)) {
              throw new IllegalStateException("Controlled test storage identity changed");
            }
          }
        };
    new CanonicalGameplayLegacyMigrationOwner(
            migration,
            inventory,
            () -> fencedCohort,
            ignored ->
                new CanonicalGameplayLegacyMigrationSourceSnapshot(
                    EnumSet.of(
                        Family.TENANT_SESSION_CONTEXT,
                        Family.SESSION_ALIAS_CONTEXT,
                        Family.GAMEPLAY_IDENTITY_CONTEXT,
                        Family.GAMEPLAY_NAME_CONTEXT,
                        Family.MOVEMENT_EFFECT,
                        Family.DURABLE_EFFECT),
                    List.of()),
            new CanonicalGameplayLegacyMigrationOwner.NamespaceIndexOwner() {
              @Override
              public void rebuildExact(
                  CanonicalGameplayLegacyMigrationOwner.FencedCohort ignored,
                  CanonicalGameplayLegacyMigrationSourceSnapshot source,
                  net.firedevops.firemud.gamesession.binding
                          .CanonicalGameplayBindingInventorySnapshot
                      canonical) {
                assertThat(canonical.bindings()).isEmpty();
                assertThat(canonical.reservations()).isEmpty();
              }

              @Override
              public CanonicalGameplayLegacyMigrationReadback readBackExact(
                  CanonicalGameplayLegacyMigrationOwner.FencedCohort ignored,
                  net.firedevops.firemud.gamesession.binding
                          .CanonicalGameplayBindingInventorySnapshot
                      canonical) {
                assertThat(canonical.bindings()).isEmpty();
                return new CanonicalGameplayLegacyMigrationReadback(
                    List.of(), List.of(), List.of(), List.of());
              }
            })
        .migrate();
  }

  private TestSchema newTestSchemaAtVersion(String prefix) {
    String schema = prefix + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("1030"))
        .load()
        .migrate();
    return new TestSchema(schema, dataSource, DSL.using(dataSource, SQLDialect.POSTGRES));
  }

  private static void migrateRemaining(TestSchema schema) {
    Flyway.configure()
        .dataSource(schema.dataSource())
        .schemas(schema.schema())
        .defaultSchema(schema.schema())
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
  }

  private DriverManagerDataSource dataSource(String schemaName) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = POSTGRES.getJdbcUrl();
    dataSource.setUrl(
        jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schemaName);
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  private void dropSchema(String schemaName) {
    try (Connection connection = dataSource("public").getConnection()) {
      DSL.using(connection, SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Could not drop the owned issuer local-operation test schema", failure);
    }
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

  private static long count(DSLContext dsl, String table) {
    return Objects.requireNonNull(
            dsl.fetchOne("SELECT count(*) AS row_count FROM " + table), "Expected count row")
        .get("row_count", Long.class);
  }

  private static String anchorImage(DSLContext dsl) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT encode(exact_anchor_bytes, 'base64') || ':' || exact_anchor_digest || ':'"
                    + " || global_coverage_generation::text FROM"
                    + " game_session_canonical_issuer_installed_anchor WHERE issuer_id = ?",
                ISSUER),
            "Expected installed anchor row")
        .get(0, String.class);
  }

  private static String proofImage(DSLContext dsl) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT coalesce(string_agg(partition_id::text || ':'"
                    + " || encode(exact_physical_field_set_bytes, 'base64') || ':'"
                    + " || exact_physical_field_set_digest, '|' ORDER BY partition_id), '')"
                    + " FROM game_session_canonical_issuer_bootstrap_partition_proof"),
            "Expected proof aggregate row")
        .get(0, String.class);
  }

  private static String reservationImage(DSLContext dsl) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY reservation_id), '[]'::jsonb)::text"
                    + " FROM game_session_canonical_issuer_partition_reservation r"),
            "Expected reservation aggregate row")
        .get(0, String.class);
  }

  private static String localRegistrationImage(DSLContext dsl) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT"
                    + " (SELECT coalesce(jsonb_agg(to_jsonb(i) ORDER BY binding_ref), '[]'::jsonb)::text"
                    + " FROM game_session_canonical_gameplay_binding_inventory i) || ':'"
                    + " || (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY transition_id), '[]'::jsonb)::text"
                    + " FROM game_session_canonical_binding_transition t) || ':'"
                    + " || (SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY reservation_id), '[]'::jsonb)::text"
                    + " FROM game_session_canonical_issuer_partition_reservation r) || ':'"
                    + " || (SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY operation_id), '[]'::jsonb)::text"
                    + " FROM game_session_canonical_issuer_binding_local_operation o) || ':'"
                    + " || (SELECT coalesce(jsonb_agg(to_jsonb(g) ORDER BY tenant_id, playable_state_namespace_id, character_id), '[]'::jsonb)::text"
                    + " FROM game_session_canonical_binding_generation g) || ':'"
                    + " || (SELECT inventory_revision::text FROM game_session_canonical_binding_inventory_clock WHERE singleton_id = 1) || ':'"
                    + " || coalesce((SELECT to_jsonb(c)::text FROM game_session_canonical_issuer_bootstrap_member_op_counter c WHERE issuer_id = '"
                    + ISSUER
                    + "'), 'missing')"),
            "Expected local registration aggregate row")
        .get(0, String.class);
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record TestSchema(String schema, DriverManagerDataSource dataSource, DSLContext dsl) {}
}
