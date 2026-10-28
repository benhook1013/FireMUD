package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountIndexMember;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot.Family;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Actual PostgreSQL/Redis proof for local ordinary NEW physical projection and refusals. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayIssuerBindingLocalRedisOwnerIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String OWNER_PRINCIPAL = "gamesession_coord_app";
  private static final String ISSUER_KEY_PREFIX = "session:game:index:issuer:";
  private static final String METADATA_FIELD = "!issuer-partition-bootstrap/v1";
  private static final String ACKNOWLEDGEMENT_TABLE =
      "game_session_canonical_issuer_binding_local_acknowledgement";
  private static final BigInteger LAYOUT_VERSION = BigInteger.valueOf(3);
  private static final BigInteger PARTITION_COUNT = BigInteger.ONE;
  private static final BigInteger PARTITION_CAPACITY = BigInteger.valueOf(2);
  private static final JsonMapper JSON = JsonMapper.builder().build();

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
            .keyPattern("session:game:index:account:*")
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
            .addCommand(CommandType.HSET)
            .addCommand(CommandType.SADD)
            .addCommand(CommandType.SISMEMBER));
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
  void projectsOrdinaryNewAgainstInstalledAnchorAndReusesExactPendingOperation() throws Exception {
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
    var runtimeTarget =
        CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
            schema.dataSource(), uuid("local-projection-tenant"));
    var firstCandidate = identity("first-new", runtimeTarget);
    var firstRequest = request("first-new-request", "first-new-transition", firstCandidate);

    assertThatThrownBy(() -> operationRepository.beginOrResumeOrdinaryNew(firstRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("anchor");
    assertThat(count(dsl, "game_session_canonical_issuer_binding_local_operation")).isZero();
    assertThat(count(dsl, "game_session_canonical_gameplay_binding_inventory")).isZero();

    assertThat(localRegistrationImage(dsl)).endsWith(":missing");

    CanonicalGameplayIssuerInstalledAnchor anchor =
        initialCoverageOwner(dsl, currentState::get)
            .completeInitialBootstrap(intent.operationId(), bootstrapRequestId);
    new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
        .installInitialOpen(anchor, currentState::get);
    String installedAnchorImage = anchorImage(dsl);
    String installedProofImage = proofImage(dsl);
    String initialReservationImage = reservationImage(dsl);
    BigInteger initialGlobalCoverageGeneration = anchor.globalCoverageGeneration();

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

    var projectionOwner = projectionOwner(dsl, currentState::get);
    var firstCapacityOnly = projectionOwner.projectAndRead(first);
    assertThat(firstCapacityOnly.operation()).isSameAs(first);
    assertThat(firstCapacityOnly.scriptOutcome()).isEqualTo(1L);
    var acknowledgementRepository =
        new CanonicalGameplayIssuerBindingLocalAcknowledgementRepository(dsl, currentState::get);
    assertThatThrownBy(() -> acknowledgementRepository.acknowledge(firstCapacityOnly))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nonterminal binding");
    assertThat(count(dsl, ACKNOWLEDGEMENT_TABLE)).isZero();
    assertThat(count(dsl, "game_session_canonical_issuer_binding_local_ack_gen_counter")).isZero();
    var firstCapacityFields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
            firstCapacityOnly.exactReadbackBytes());
    String firstCapacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
            first.reservation().reservationId());
    assertThat(firstCapacityFields)
        .containsEntry(firstCapacityField, first.exactMemberValueBytes());
    assertThat(firstCapacityFields)
        .doesNotContainKey(new String(firstCandidate.bindingRef().bytes(), StandardCharsets.UTF_8));

    AtomicInteger sourceReadCount = new AtomicInteger();
    var currentStateDisappearsAfterSqlRead =
        (CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource)
            () -> sourceReadCount.incrementAndGet() <= 2 ? initialState : null;
    var unavailableBeforeRedisWrite = projectionOwner(dsl, currentStateDisappearsAfterSqlRead);
    Map<String, String> beforeUnavailable =
        hashImage(
            CanonicalGameplayIssuerRedisKeys.forPartition(
                    intent.layoutDescriptor(), BigInteger.ZERO)
                .getBytes(StandardCharsets.US_ASCII));
    Throwable unavailable = catchThrowable(() -> unavailableBeforeRedisWrite.projectAndRead(first));
    assertThat(unavailable)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Issuer binding-local projection is unavailable")
        .hasNoCause();
    assertThat(sourceReadCount).hasValue(3);
    assertThat(
            hashImage(
                CanonicalGameplayIssuerRedisKeys.forPartition(
                        intent.layoutDescriptor(), BigInteger.ZERO)
                    .getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo(beforeUnavailable);

    byte[] partitionKey =
        CanonicalGameplayIssuerRedisKeys.forPartition(intent.layoutDescriptor(), BigInteger.ZERO)
            .getBytes(StandardCharsets.US_ASCII);
    Map<String, String> beforeMalformedRelease = hashImage(partitionKey);
    byte[][] malformedReleaseBaseline =
        malformedReleaseBaselineArguments(partitionKey, firstCapacityField);
    assertThat(
            evalOwnerScript(
                CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution.RESOURCE_PATH,
                partitionKey,
                malformedReleaseBaseline))
        .isEqualTo(-2L);
    assertThat(hashImage(partitionKey)).isEqualTo(beforeMalformedRelease);

    assertThat(projectionOwner.projectAndRead(first).scriptOutcome()).isEqualTo(3L);
    assertThat(anchorImage(dsl)).isEqualTo(installedAnchorImage);
    assertThat(proofImage(dsl)).isEqualTo(installedProofImage);

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

    var concurrentProjectionResults = concurrentProjection(projectionOwner, first, second);
    assertThat(concurrentProjectionResults.first().scriptOutcome()).isEqualTo(3L);
    assertThat(concurrentProjectionResults.second().scriptOutcome()).isEqualTo(1L);
    var provisionalEvidence =
        new CanonicalGameplayBindingInventoryRepository(dsl)
            .readProvisionalCasEvidence(firstRequest.transitionId());
    var firstLease =
        controlledLeaseEvidence(
            firstCandidate,
            provisionalEvidence.transition().bindingGeneration(),
            runtimeTarget.playableTarget());
    var firstDecision =
        new CanonicalGameplayAdmissionDecision(
            firstRequest.transitionId(),
            firstLease,
            firstCandidate,
            provisionalEvidence.transition().bindingGeneration(),
            null,
            runtimeTarget.playableTarget());
    new CanonicalGameplayBindingInventoryRepository(dsl)
        .markProvisional(provisionalEvidence, firstDecision);
    var resumedProvisional = reloadProgressedOperation(dsl, currentState::get, first);
    var firstWithBinding =
        projectionOwner(dsl, currentState::get).projectAndRead(resumedProvisional);
    assertThat(firstWithBinding.scriptOutcome()).isEqualTo(2L);
    var firstProjectedFields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
            firstWithBinding.exactReadbackBytes());
    assertThat(firstProjectedFields)
        .containsEntry(
            new String(firstCandidate.bindingRef().bytes(), StandardCharsets.UTF_8),
            first.exactMemberValueBytes());
    assertThat(firstProjectedFields)
        .containsEntry(firstCapacityField, first.exactMemberValueBytes());
    assertThat(projectionOwner.projectAndRead(first).scriptOutcome()).isEqualTo(3L);

    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    var currentProvisional = inventory.readProvisionalCasEvidence(firstRequest.transitionId());
    String accountMember =
        CanonicalGameplayAccountIndexMember.of(currentProvisional.candidate()).value();
    inventory.markCandidateAccountIndexPresent(currentProvisional, accountMember);
    var resumedPresent = reloadProgressedOperation(dsl, currentState::get, first);
    assertThat(resumedPresent.preparedTransition().inventoryRevision())
        .isGreaterThan(resumedProvisional.preparedTransition().inventoryRevision());
    assertThat(
            projectionOwner(dsl, currentState::get).projectAndRead(resumedPresent).scriptOutcome())
        .isEqualTo(3L);

    var memberRepair = inventory.beginAccountMemberRepair(firstRequest.transitionId());
    var accountRedisOwner =
        new CanonicalGameplayAccountMemberRepairRedisOwner(
            ownerClient, RedisScriptCatalog.loadInstalled(getClass().getClassLoader()));
    var memberReadback = accountRedisOwner.projectAndRead(memberRepair);
    inventory.acknowledgeAccountMemberRepair(memberRepair, memberReadback);
    var resumedAcknowledged = reloadProgressedOperation(dsl, currentState::get, first);
    assertThat(resumedAcknowledged.preparedTransition().inventoryRevision())
        .isGreaterThan(resumedPresent.preparedTransition().inventoryRevision());
    var acknowledgementReadback =
        projectionOwner(dsl, currentState::get).projectAndRead(resumedAcknowledged);
    assertThat(acknowledgementReadback.scriptOutcome()).isEqualTo(3L);

    String acknowledgementImageBeforeAmbientRefusals =
        dsl.fetch("SELECT * FROM " + ACKNOWLEDGEMENT_TABLE).formatJSON();
    String acknowledgementCounterImageBeforeAmbientRefusals =
        dsl.fetch("SELECT * FROM game_session_canonical_issuer_binding_local_ack_gen_counter")
            .formatJSON();
    AtomicInteger acknowledgementSourceReads = new AtomicInteger();
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
        acknowledgementSource =
            () -> {
              acknowledgementSourceReads.incrementAndGet();
              return currentState.get();
            };
    var guardedAcknowledgementRepository =
        new CanonicalGameplayIssuerBindingLocalAcknowledgementRepository(dsl, acknowledgementSource);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> guardedAcknowledgementRepository.acknowledge(acknowledgementReadback))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Issuer binding-local acknowledgement is unavailable: Issuer acknowledgement owner cannot join an ambient SQL transaction");
      assertThat(acknowledgementSourceReads).hasValue(0);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    assertThat(dsl.fetch("SELECT * FROM " + ACKNOWLEDGEMENT_TABLE).formatJSON())
        .isEqualTo(acknowledgementImageBeforeAmbientRefusals);
    assertThat(
            dsl.fetch("SELECT * FROM game_session_canonical_issuer_binding_local_ack_gen_counter")
                .formatJSON())
        .isEqualTo(acknowledgementCounterImageBeforeAmbientRefusals);

    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThatThrownBy(() -> guardedAcknowledgementRepository.acknowledge(acknowledgementReadback))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Issuer binding-local acknowledgement is unavailable: Issuer acknowledgement owner cannot join an ambient SQL transaction");
      assertThat(acknowledgementSourceReads).hasValue(0);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    assertThat(dsl.fetch("SELECT * FROM " + ACKNOWLEDGEMENT_TABLE).formatJSON())
        .isEqualTo(acknowledgementImageBeforeAmbientRefusals);
    assertThat(
            dsl.fetch("SELECT * FROM game_session_canonical_issuer_binding_local_ack_gen_counter")
                .formatJSON())
        .isEqualTo(acknowledgementCounterImageBeforeAmbientRefusals);

    dsl.transaction(
        configuration -> {
          DSLContext callerTransaction = DSL.using(configuration);
          var callerOwnedAcknowledgementRepository =
              new CanonicalGameplayIssuerBindingLocalAcknowledgementRepository(
                  callerTransaction, acknowledgementSource);
          assertThatThrownBy(() -> callerOwnedAcknowledgementRepository.acknowledge(acknowledgementReadback))
              .isInstanceOf(IllegalStateException.class)
              .hasMessage(
                  "Issuer binding-local acknowledgement is unavailable: Issuer acknowledgement owner cannot join a jOOQ transaction");
          assertThat(acknowledgementSourceReads).hasValue(0);
          assertThat(callerTransaction.fetch("SELECT * FROM " + ACKNOWLEDGEMENT_TABLE).formatJSON())
              .isEqualTo(acknowledgementImageBeforeAmbientRefusals);
          assertThat(
                  callerTransaction
                      .fetch(
                          "SELECT * FROM game_session_canonical_issuer_binding_local_ack_gen_counter")
                      .formatJSON())
              .isEqualTo(acknowledgementCounterImageBeforeAmbientRefusals);
        });
    assertThat(dsl.fetch("SELECT * FROM " + ACKNOWLEDGEMENT_TABLE).formatJSON())
        .isEqualTo(acknowledgementImageBeforeAmbientRefusals);
    assertThat(
            dsl.fetch("SELECT * FROM game_session_canonical_issuer_binding_local_ack_gen_counter")
                .formatJSON())
        .isEqualTo(acknowledgementCounterImageBeforeAmbientRefusals);

    var issuerAcknowledgement = acknowledgementRepository.acknowledge(acknowledgementReadback);
    assertThat(issuerAcknowledgement.evidence().operationId()).isEqualTo(first.operationId());
    assertThat(issuerAcknowledgement.evidence().issuerBindingLocalAcknowledgementGeneration())
        .isEqualTo(BigInteger.ONE);
    assertThat(issuerAcknowledgement.evidence().anchor().globalCoverageGeneration())
        .isEqualTo(initialGlobalCoverageGeneration);
    assertThat(issuerAcknowledgement.evidence().reservation().reservationId())
        .isEqualTo(first.reservation().reservationId());
    assertThat(issuerAcknowledgement.evidence().reservation().issuerCoverageOperationFence())
        .isEqualTo(first.reservation().issuerCoverageOperationFence());
    assertThat(issuerAcknowledgement.evidence().anchor().operationFence())
        .isEqualTo(first.anchor().operationFence());
    assertThat(issuerAcknowledgement.evidence().memberOperation().generation())
        .isEqualTo(first.localMemberGeneration());
    assertThat(issuerAcknowledgement.evidence().physicalReadback().bindingValueBytes())
        .containsExactly(first.exactMemberValueBytes());
    assertThat(issuerAcknowledgement.evidence().physicalReadback().capacityValueBytes())
        .containsExactly(first.exactMemberValueBytes());
    assertThat(issuerAcknowledgement.evidence().physicalReadback().countedCapacity()).isEqualTo(2);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT member_status FROM"
                            + " game_session_canonical_issuer_binding_local_operation WHERE operation_id = ?",
                        first.operationId()),
                    "Expected immutable local issuer operation")
                .get("member_status", String.class))
        .isEqualTo("PENDING_PHYSICAL_PROOF");
    assertThat(count(dsl, ACKNOWLEDGEMENT_TABLE)).isEqualTo(1L);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT last_issued_binding_local_acknowledgement_generation FROM"
                            + " game_session_canonical_issuer_binding_local_ack_gen_counter"
                            + " WHERE issuer_id = ?",
                        ISSUER),
                    "Expected owner-issued local ACK generation counter")
                .get("last_issued_binding_local_acknowledgement_generation", BigDecimal.class))
        .isEqualTo(BigDecimal.ONE);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_issuer_binding_local_ack_gen_counter"
                        + " SET last_issued_binding_local_acknowledgement_generation = 3,"
                        + " counter_version = counter_version + 1 WHERE issuer_id = ?",
                    ISSUER))
        .hasMessageContaining("must advance its own domain by one");
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT last_issued_binding_local_acknowledgement_generation FROM"
                            + " game_session_canonical_issuer_binding_local_ack_gen_counter"
                            + " WHERE issuer_id = ?",
                        ISSUER),
                    "Expected counter to refuse a skipped generation")
                .get("last_issued_binding_local_acknowledgement_generation", BigDecimal.class))
        .isEqualTo(BigDecimal.ONE);
    var restartedAcknowledgementRepository =
        new CanonicalGameplayIssuerBindingLocalAcknowledgementRepository(dsl, currentState::get);
    var retriedAcknowledgement =
        restartedAcknowledgementRepository.acknowledge(
            projectionOwner(dsl, currentState::get).projectAndRead(first));
    assertThat(retriedAcknowledgement.exactBytes())
        .containsExactly(issuerAcknowledgement.exactBytes());
    assertThat(retriedAcknowledgement.digest()).isEqualTo(issuerAcknowledgement.digest());
    assertThat(retriedAcknowledgement.evidence().issuerBindingLocalAcknowledgementGeneration())
        .isEqualTo(issuerAcknowledgement.evidence().issuerBindingLocalAcknowledgementGeneration());
    assertThat(restartedAcknowledgementRepository.readHistorical(first.operationId()).exactBytes())
        .containsExactly(issuerAcknowledgement.exactBytes());
    assertThat(count(dsl, ACKNOWLEDGEMENT_TABLE)).isEqualTo(1L);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT last_issued_binding_local_acknowledgement_generation FROM"
                            + " game_session_canonical_issuer_binding_local_ack_gen_counter"
                            + " WHERE issuer_id = ?",
                        ISSUER),
                    "Expected replay to retain the owner-issued ACK generation")
                .get("last_issued_binding_local_acknowledgement_generation", BigDecimal.class))
        .isEqualTo(BigDecimal.ONE);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_issuer_binding_local_acknowledgement"
                        + " SET acknowledgement_bytes = ? WHERE operation_id = ?",
                    issuerAcknowledgement.exactBytes(),
                    first.operationId()))
        .hasMessageContaining("issuer binding-local acknowledgements are immutable");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM game_session_canonical_issuer_binding_local_acknowledgement"
                        + " WHERE operation_id = ?",
                    first.operationId()))
        .hasMessageContaining("issuer binding-local acknowledgements are immutable");
    assertThat(count(dsl, ACKNOWLEDGEMENT_TABLE)).isEqualTo(1L);
    currentState.set(
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            checkpointShape(BigInteger.valueOf(8)), incarnation));
    assertThatThrownBy(() -> restartedAcknowledgementRepository.acknowledge(acknowledgementReadback))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs from the installed anchor");
    assertThat(restartedAcknowledgementRepository.readHistorical(first.operationId()).exactBytes())
        .containsExactly(issuerAcknowledgement.exactBytes());
    currentState.set(initialState);

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

    byte[] key =
        CanonicalGameplayIssuerRedisKeys.forPartition(intent.layoutDescriptor(), BigInteger.ZERO)
            .getBytes(StandardCharsets.US_ASCII);
    String secondCapacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
            second.reservation().reservationId());
    byte[] secondCapacityFieldBytes = secondCapacityField.getBytes(StandardCharsets.US_ASCII);
    assertThat(administratorCommands.hlen(key)).isEqualTo(5L);
    assertThat(firstProjectedFields)
        .containsEntry(secondCapacityField, second.exactMemberValueBytes());

    var incarnationMovedState =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            intent.sourceCheckpoint(), stableIncarnation("moved-after-install"));
    currentState.set(incarnationMovedState);
    assertThatThrownBy(() -> projectionOwner.projectAndRead(second))
        .isInstanceOf(IllegalStateException.class);
    currentState.set(initialState);

    administratorCommands.hset(key, secondCapacityFieldBytes, first.exactMemberValueBytes());
    assertThatThrownBy(() -> projectionOwner.projectAndRead(second))
        .isInstanceOf(IllegalStateException.class);
    assertThat(administratorCommands.hget(key, secondCapacityFieldBytes))
        .containsExactly(first.exactMemberValueBytes());
    administratorCommands.hset(key, secondCapacityFieldBytes, second.exactMemberValueBytes());

    var secondValue = second.memberValue();
    var wrongAnchorValue =
        new CanonicalGameplayIssuerMemberV2Value(
            secondValue.owner(),
            secondValue.bindingGeneration(),
            secondValue.reservation(),
            new CanonicalGameplayIssuerMemberV2Value.Anchor(
                UUID.randomUUID(),
                secondValue.anchor().operationFence(),
                secondValue.anchor().coverageFence(),
                secondValue.anchor().issuerInventorySnapshotRevision()),
            secondValue.memberOperation(),
            secondValue.issuerControlEpoch(),
            secondValue.layoutDescriptor(),
            secondValue.sourceCheckpoint(),
            secondValue.incarnation());
    byte[] wrongAnchorBytes = CanonicalGameplayIssuerMemberV2Codec.encode(wrongAnchorValue);
    administratorCommands.hset(key, secondCapacityFieldBytes, wrongAnchorBytes);
    assertThatThrownBy(() -> projectionOwner.projectAndRead(second))
        .isInstanceOf(IllegalStateException.class);
    assertThat(administratorCommands.hget(key, secondCapacityFieldBytes))
        .containsExactly(wrongAnchorBytes);
    administratorCommands.hset(key, secondCapacityFieldBytes, second.exactMemberValueBytes());

    byte[] unknownField = "unregistered-issuer-member".getBytes(StandardCharsets.UTF_8);
    byte[] unknownValue = "newer-v2-value".getBytes(StandardCharsets.UTF_8);
    administratorCommands.hset(key, unknownField, unknownValue);
    assertThatThrownBy(() -> projectionOwner.projectAndRead(second))
        .isInstanceOf(IllegalStateException.class);
    assertThat(administratorCommands.hget(key, unknownField)).containsExactly(unknownValue);
    byte[][] unknownFields = {unknownField};
    administratorCommands.hdel(key, unknownFields);

    byte[] headerField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.METADATA_FIELD.getBytes(
            StandardCharsets.US_ASCII);
    byte[] headerValue =
        firstCapacityFields.get(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.METADATA_FIELD);
    byte[][] headerFields = {headerField};
    administratorCommands.hdel(key, headerFields);
    assertThatThrownBy(() -> projectionOwner.projectAndRead(second))
        .isInstanceOf(IllegalStateException.class);
    administratorCommands.hset(key, headerField, headerValue);

    administratorCommands.hset(
        key, secondCapacityFieldBytes, "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> projectionOwner.projectAndRead(second))
        .isInstanceOf(IllegalStateException.class);
    administratorCommands.hset(key, secondCapacityFieldBytes, second.exactMemberValueBytes());

    assertThat(administratorCommands.pexpire(key, 60_000L)).isTrue();
    assertThatThrownBy(() -> projectionOwner.projectAndRead(second))
        .isInstanceOf(IllegalStateException.class);
    assertThat(administratorCommands.persist(key)).isTrue();
    assertThat(administratorCommands.hlen(key)).isEqualTo(5L);

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
  }

  @Test
  void readsProjectedSubsetWhenTwoSamePartitionRegistrationsPrecedePhysicalProjection()
      throws Exception {
    DSLContext dsl = schema.dsl();
    establishActualLegacyDisposition(dsl);
    var layout = descriptor();
    UUID bootstrapRequestId = UUID.randomUUID();
    var source = sourceCheckpoint(BigInteger.valueOf(7));
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(dsl)
            .beginInitialBootstrapIntent(bootstrapRequestId, layout, source);
    migrateRemaining(schema);

    var incarnation = stableIncarnation("subset-readback-anchor");
    var initialState =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            intent.sourceCheckpoint(), incarnation);
    AtomicReference<CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState>
        currentState = new AtomicReference<>(initialState);
    CanonicalGameplayIssuerInstalledAnchor anchor =
        initialCoverageOwner(dsl, currentState::get)
            .completeInitialBootstrap(intent.operationId(), bootstrapRequestId);
    new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
        .installInitialOpen(anchor, currentState::get);

    var runtimeTarget =
        CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
            schema.dataSource(), uuid("subset-readback-tenant"));
    var targetCandidate = identity("subset-readback-target", runtimeTarget);
    var targetRequest =
        request(
            "subset-readback-target-request", "subset-readback-target-transition", targetCandidate);
    var unprojectedCandidate = identity("subset-readback-unprojected");
    var unprojectedRequest =
        request(
            "subset-readback-unprojected-request",
            "subset-readback-unprojected-transition",
            unprojectedCandidate);
    var operationRepository =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, currentState::get);
    var target = operationRepository.beginOrResumeOrdinaryNew(targetRequest);
    var unprojected = operationRepository.beginOrResumeOrdinaryNew(unprojectedRequest);
    assertThat(target.reservation().partitionId())
        .isEqualTo(unprojected.reservation().partitionId());

    String targetCapacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
            target.reservation().reservationId());
    String unprojectedCapacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
            unprojected.reservation().reservationId());
    byte[] partitionKey =
        CanonicalGameplayIssuerRedisKeys.forPartition(intent.layoutDescriptor(), BigInteger.ZERO)
            .getBytes(StandardCharsets.US_ASCII);
    assertThat(hashImage(partitionKey))
        .doesNotContainKey(targetCapacityField)
        .doesNotContainKey(unprojectedCapacityField);

    var projectionOwner = projectionOwner(dsl, currentState::get);
    var capacityOnly = projectionOwner.projectAndRead(target);
    assertThat(capacityOnly.scriptOutcome()).isEqualTo(1L);
    var capacityFields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
            capacityOnly.exactReadbackBytes());
    assertThat(capacityFields).containsEntry(targetCapacityField, target.exactMemberValueBytes());
    assertThat(capacityFields).doesNotContainKey(unprojectedCapacityField);
    assertThat(capacityFields)
        .doesNotContainKey(
            new String(unprojectedCandidate.bindingRef().bytes(), StandardCharsets.UTF_8));

    var provisionalEvidence =
        new CanonicalGameplayBindingInventoryRepository(dsl)
            .readProvisionalCasEvidence(targetRequest.transitionId());
    var targetLease =
        controlledLeaseEvidence(
            targetCandidate,
            provisionalEvidence.transition().bindingGeneration(),
            runtimeTarget.playableTarget());
    var targetDecision =
        new CanonicalGameplayAdmissionDecision(
            targetRequest.transitionId(),
            targetLease,
            targetCandidate,
            provisionalEvidence.transition().bindingGeneration(),
            null,
            runtimeTarget.playableTarget());
    new CanonicalGameplayBindingInventoryRepository(dsl)
        .markProvisional(provisionalEvidence, targetDecision);

    var addReadback = projectionOwner.projectAndRead(target);
    assertThat(addReadback.scriptOutcome()).isEqualTo(2L);
    var addedFields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
            addReadback.exactReadbackBytes());
    assertThat(addedFields)
        .containsEntry(
            new String(targetCandidate.bindingRef().bytes(), StandardCharsets.UTF_8),
            target.exactMemberValueBytes());
    assertThat(addedFields).containsEntry(targetCapacityField, target.exactMemberValueBytes());
    assertThat(addedFields).doesNotContainKey(unprojectedCapacityField);
  }

  @Test
  void malformedLuaBindingReferenceAndReleaseCountsRefuseWithoutChangingPartition()
      throws Exception {
    byte[] key =
        (ISSUER_KEY_PREFIX + "malformed-input-vectors").getBytes(StandardCharsets.US_ASCII);
    byte[] markerField = "original-field".getBytes(StandardCharsets.US_ASCII);
    byte[] markerValue = "original-value".getBytes(StandardCharsets.US_ASCII);
    administratorCommands.hset(key, markerField, markerValue);
    Map<String, String> before = hashImage(key);

    byte[] malformedBindingRef = "13:bindingRef/v1".getBytes(StandardCharsets.US_ASCII);
    byte[] minimalMember =
        ("{\"reservation\":{\"reservationId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"lifecycle\":\"RESERVED\"}}")
            .getBytes(StandardCharsets.UTF_8);
    List<byte[]> addArguments =
        new ArrayList<>(
            List.of(
                ascii(ISSUER),
                ascii("0"),
                ascii("1"),
                ascii("2"),
                ascii("1"),
                malformedBindingRef,
                minimalMember));
    while (addArguments.size() < 13) addArguments.add(ascii("unused"));
    assertThat(
            evalOwnerScript(
                CanonicalGameplayIssuerBindingLocalRedisScriptContribution.RESOURCE_PATH,
                key,
                addArguments.toArray(byte[][]::new)))
        .isEqualTo(-2L);
    assertThat(hashImage(key)).isEqualTo(before);

    for (String malformedCount : List.of("1.5", "0x1", "1e0", "9".repeat(256))) {
      List<byte[]> releaseArguments =
          new ArrayList<>(
              List.of(ascii(ISSUER), ascii("0"), ascii("1"), ascii("2"), ascii(malformedCount)));
      while (releaseArguments.size() < 16) releaseArguments.add(ascii("unused"));
      assertThat(
              evalOwnerScript(
                  CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution.RESOURCE_PATH,
                  key,
                  releaseArguments.toArray(byte[][]::new)))
          .isEqualTo(-2L);
      assertThat(hashImage(key)).isEqualTo(before);
    }
  }

  @Test
  void differentPartitionProgressDoesNotFenceTargetButSamePartitionProgressDoes() throws Exception {
    DSLContext dsl = schema.dsl();
    establishActualLegacyDisposition(dsl);
    BigInteger partitions = BigInteger.TWO;
    UUID bootstrapRequest = UUID.randomUUID();
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(dsl)
            .beginInitialBootstrapIntent(
                bootstrapRequest, descriptor(partitions), sourceCheckpoint(BigInteger.valueOf(7)));
    migrateRemaining(schema);
    var state =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            intent.sourceCheckpoint(), stableIncarnation("partition-isolation"));
    var anchor =
        initialCoverageOwner(dsl, () -> state)
            .completeInitialBootstrap(intent.operationId(), bootstrapRequest);
    new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
        .installInitialOpen(anchor, () -> state);
    var runtime =
        CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
            schema.dataSource(), uuid("partition-isolation-tenant"));
    List<CanonicalGameplayBindingIdentity> zero = new ArrayList<>();
    CanonicalGameplayBindingIdentity other = null;
    for (int index = 0; index < 128 && (zero.size() < 2 || other == null); index++) {
      var candidate = identity("partition-isolation-" + index, runtime, partitions);
      if (candidate.bindingRef().partitionId(partitions).equals(BigInteger.ZERO))
        zero.add(candidate);
      else other = candidate;
    }
    assertThat(zero).hasSizeGreaterThanOrEqualTo(2);
    assertThat(other).isNotNull();
    var operations = new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, () -> state);
    var first =
        operations.beginOrResumeOrdinaryNew(
            request("partition-target", "partition-target-transition", zero.get(0)));
    var sql = new CanonicalGameplayIssuerBindingLocalProjectionRepository(dsl, () -> state);
    var beforeOther = sql.readPreparedPartitionWork(first);
    var second =
        operations.beginOrResumeOrdinaryNew(
            request("partition-other", "partition-other-transition", other));
    projectionOwner(dsl, () -> state).projectAndRead(second);
    // This includes another partition's new reservation, capacity and committed physical revision.
    sql.requireStillCurrent(beforeOther);
    assertThat(sql.readPreparedPartitionWork(first).localEntries()).hasSize(1);
    operations.beginOrResumeOrdinaryNew(
        request("partition-same", "partition-same-transition", zero.get(1)));
    assertThatThrownBy(() -> sql.requireStillCurrent(beforeOther))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed during projection");
  }

  private static ConcurrentProjections concurrentProjection(
      CanonicalGameplayIssuerBindingLocalRedisOwner owner,
      CanonicalGameplayIssuerBindingLocalOperation first,
      CanonicalGameplayIssuerBindingLocalOperation second)
      throws Exception {
    ExecutorService callers = Executors.newFixedThreadPool(2);
    try {
      var firstCall = callers.submit(() -> owner.projectAndRead(first));
      var secondCall = callers.submit(() -> owner.projectAndRead(second));
      var secondResult = secondCall.get(30, TimeUnit.SECONDS);
      CanonicalGameplayIssuerBindingLocalRedisOwner.Readback firstResult;
      try {
        firstResult = firstCall.get(30, TimeUnit.SECONDS);
      } catch (java.util.concurrent.ExecutionException failure) {
        assertThat(failure.getCause())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("Issuer binding-local projection is unavailable");
        // The same-partition writer may fence a concurrent read-only replay. Retry the original
        // operation only after that writer has positively completed; never replay an old write.
        firstResult = owner.projectAndRead(first);
      }
      return new ConcurrentProjections(firstResult, secondResult);
    } finally {
      callers.shutdownNow();
    }
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

  private static CanonicalGameplayIssuerBindingLocalOperation reloadProgressedOperation(
      DSLContext dsl,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource source,
      CanonicalGameplayIssuerBindingLocalOperation original) {
    // A replacement worker has only the caller request; its repository reloads the durable source.
    var reloaded =
        new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, source)
            .beginOrResumeOrdinaryNew(original.request());
    assertThat(reloaded.preparedTransition().status())
        .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL);
    assertThat(reloaded.preparedTransition().inventoryRevision())
        .isGreaterThan(original.localInventorySnapshotRevision());
    assertThat(reloaded.request()).isEqualTo(original.request());
    assertThat(reloaded.reservation()).isEqualTo(original.reservation());
    assertThat(reloaded.candidateAccountIndexFence())
        .isEqualTo(original.candidateAccountIndexFence());
    assertThat(reloaded.operationId()).isEqualTo(original.operationId());
    assertThat(reloaded.operationFence()).isEqualTo(original.operationFence());
    assertThat(reloaded.localMemberGeneration()).isEqualTo(original.localMemberGeneration());
    assertThat(reloaded.localInventorySnapshotRevision())
        .isEqualTo(original.localInventorySnapshotRevision());
    assertThat(reloaded.memberValue()).isEqualTo(original.memberValue());
    assertThat(reloaded.exactMemberValueBytes()).containsExactly(original.exactMemberValueBytes());
    assertThat(reloaded.memberValueDigest()).isEqualTo(original.memberValueDigest());
    return reloaded;
  }

  private CanonicalGameplayIssuerBindingLocalRedisOwner projectionOwner(
      DSLContext dsl,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource source) {
    return new CanonicalGameplayIssuerBindingLocalRedisOwner(
        ownerClient,
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayIssuerBindingLocalRedisScriptContribution())),
        new CanonicalGameplayIssuerBindingLocalProjectionRepository(dsl, source),
        source);
  }

  private byte[][] malformedReleaseBaselineArguments(byte[] key, String malformedCapacityField)
      throws IOException {
    Map<String, byte[]> baseline = new TreeMap<>();
    administratorCommands
        .hgetall(key)
        .forEach(
            (field, value) ->
                baseline.put(new String(field, StandardCharsets.UTF_8), value.clone()));
    assertThat(baseline).containsKey(METADATA_FIELD).containsKey(malformedCapacityField);

    ObjectNode malformedMember =
        (ObjectNode) JSON.readTree(Objects.requireNonNull(baseline.get(malformedCapacityField)));
    malformedMember.remove("memberOperation");
    baseline.put(malformedCapacityField, JSON.writeValueAsBytes(malformedMember));

    List<byte[]> arguments = new ArrayList<>();
    arguments.add(ascii(ISSUER));
    arguments.add(ascii("0"));
    arguments.add(ascii(PARTITION_COUNT.toString()));
    arguments.add(ascii(PARTITION_CAPACITY.toString()));
    arguments.add(ascii(Integer.toString(baseline.size())));
    baseline.forEach(
        (field, value) -> {
          arguments.add(field.getBytes(StandardCharsets.UTF_8));
          arguments.add(value);
        });
    while (arguments.size() < 16) arguments.add(ascii("unused"));
    return arguments.toArray(byte[][]::new);
  }

  private long evalOwnerScript(String resourcePath, byte[] key, byte[][] arguments)
      throws IOException {
    byte[] script;
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      if (input == null) throw new IOException("Redis Lua test resource is missing");
      script = input.readAllBytes();
    }
    try (StatefulRedisConnection<byte[], byte[]> connection =
        ownerClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisCommands<byte[], byte[]> commands = connection.sync();
      assertThat(commands.aclWhoami()).isEqualTo(OWNER_PRINCIPAL);
      String sha = commands.scriptLoad(script);
      return Objects.requireNonNull(
          commands.evalsha(sha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments),
          "Expected integer Redis Lua outcome");
    }
  }

  private Map<String, String> hashImage(byte[] key) {
    Map<String, String> image = new TreeMap<>();
    administratorCommands
        .hgetall(key)
        .forEach(
            (field, value) ->
                image.put(
                    Base64.getEncoder().encodeToString(field),
                    Base64.getEncoder().encodeToString(value)));
    return image;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor
      descriptor() {
    return descriptor(PARTITION_COUNT);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor
      descriptor(BigInteger partitions) {
    String exact =
        "issuer-layout-descriptor/v1|"
            + ISSUER
            + "|"
            + LAYOUT_VERSION
            + "|"
            + partitions
            + "|"
            + PARTITION_CAPACITY
            + "|"
            + CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY;
    return new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor(
        ISSUER,
        LAYOUT_VERSION,
        partitions,
        PARTITION_CAPACITY,
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

  private static CanonicalGameplayBindingIdentity identity(
      String seed, CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget runtimeTarget) {
    return identity(seed, runtimeTarget, PARTITION_COUNT);
  }

  private static CanonicalGameplayBindingIdentity identity(
      String seed,
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget runtimeTarget,
      BigInteger partitions) {
    return new CanonicalGameplayBindingIdentity(
        uuid(seed + "-account"),
        runtimeTarget.canonicalTenantId(),
        runtimeTarget.playableStateNamespaceId(),
        uuid(seed + "-character"),
        runtimeTarget.playableStateScope(),
        runtimeTarget.gameInstanceUuid(),
        runtimeTarget.gameInstanceId(),
        uuid(seed + "-session").toString(),
        uuid(seed + "-region"),
        BigInteger.valueOf(12),
        ISSUER,
        BigInteger.valueOf(7),
        LAYOUT_VERSION,
        partitions,
        PARTITION_CAPACITY);
  }

  private static CanonicalGameplayBindingIdentity identityWithLayout(
      String seed, BigInteger layoutVersion) {
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
        PARTITION_CAPACITY);
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

  /** Controlled upstream lease input; the SQL transition owner remains the phase authority. */
  private static AccountGameplayAdmissionLeaseEvidence controlledLeaseEvidence(
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
    List<Map<String, Object>> checkpoints = new java.util.ArrayList<>();
    for (String stream :
        List.of(
            "account/" + candidate.accountId(),
            "issuer/" + ISSUER,
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

  private record ConcurrentProjections(
      CanonicalGameplayIssuerBindingLocalRedisOwner.Readback first,
      CanonicalGameplayIssuerBindingLocalRedisOwner.Readback second) {}
}
