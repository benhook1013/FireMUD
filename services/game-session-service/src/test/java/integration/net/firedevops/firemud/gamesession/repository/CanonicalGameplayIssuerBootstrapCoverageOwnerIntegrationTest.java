package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
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

/** Actual finite SQL-and-Redis source proof for the local initial issuer anchor owner. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayIssuerBootstrapCoverageOwnerIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String OWNER_PRINCIPAL = "gamesession_coord_app";
  private static final String ISSUER_KEY_PREFIX = "session:game:index:issuer:";
  private static final BigInteger LAYOUT_VERSION = BigInteger.valueOf(3);
  private static final BigInteger PARTITION_COUNT = BigInteger.valueOf(16);
  private static final BigInteger PARTITION_CAPACITY = BigInteger.valueOf(32);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  private final List<TestSchema> schemas = new ArrayList<>();
  private RedisClient administrator;
  private RedisClient ownerClient;
  private StatefulRedisConnection<byte[], byte[]> administratorConnection;
  private RedisCommands<byte[], byte[]> administratorCommands;

  @BeforeEach
  void setUpRedis() {
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
  }

  @AfterEach
  void tearDown() {
    if (administratorConnection != null) administratorConnection.close();
    if (ownerClient != null) ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (administrator != null) administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    for (TestSchema schema : schemas) dropSchema(schema.schema());
  }

  @Test
  void installsOnlyAfterEveryPopulatedAndEmptyPartitionHasExactPhysicalProofAndReplaysOriginal() {
    Fixture fixture = fixture(PARTITION_COUNT, PARTITION_CAPACITY, 1);
    String originalReservationImage = originalReservationImage(fixture.dsl());
    var current = currentState(fixture, stableIncarnation("finite"));

    CanonicalGameplayIssuerInstalledAnchor first =
        owner(fixture, () -> current)
            .completeInitialBootstrap(fixture.intent().operationId(), fixture.requestId());

    String installedAnchorImage = anchorStorageImage(fixture.dsl());
    String installedProofImage = proofStorageImage(fixture.dsl());
    var installedPhysicalImage = physicalPartitionImages(fixture);
    assertThatThrownBy(
            () ->
                new CanonicalGameplayIssuerLayoutBootstrapRepository(fixture.dsl())
                    .supersedeStaleInitialBootstrapIntent(
                        fixture.intent().operationId(),
                        UUID.randomUUID(),
                        descriptor(PARTITION_COUNT, PARTITION_CAPACITY),
                        fixture.currentAccountCheckpoint()))
        .hasMessageContaining("installed issuer anchor forbids");
    assertThat(anchorStorageImage(fixture.dsl())).isEqualTo(installedAnchorImage);
    assertThat(proofStorageImage(fixture.dsl())).isEqualTo(installedProofImage);
    assertThat(physicalPartitionImages(fixture)).isEqualTo(installedPhysicalImage);
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT count(*) FROM game_session_canonical_issuer_bootstrap_supersession")
                .get(0, Long.class))
        .isZero();

    assertThat(first.issuerCoverageOperationId()).isEqualTo(fixture.intent().operationId());
    assertThat(first.operationFence()).isEqualTo(fixture.intent().operationFence());
    assertThat(first.coverageFence()).isEqualTo(fixture.intent().coverageFence());
    assertThat(first.partitionProofCount()).isEqualTo(PARTITION_COUNT.longValueExact());
    assertThat(first.physicalReservationCount()).isEqualTo(1L);
    assertThat(first.physicalBindingCount()).isEqualTo(1L);
    CanonicalGameplayIssuerBootstrapPartitionWork originalWork =
        partitionWork(
            fixture, fixture.activePartition(), trustedIncarnation(current.incarnation()));
    var physicalValue = originalWork.memberOperations().getFirst().expectedMemberValue();
    var originalReservationRow =
        fetchRequired(
            fixture.dsl(),
            "SELECT issuer_coverage_operation_id, issuer_coverage_operation_fence, coverage_fence,"
                + " inventory_snapshot_revision, inventory_revision"
                + " FROM game_session_canonical_issuer_partition_reservation WHERE reservation_id = ?",
            physicalValue.reservation().reservationId());
    assertThat(physicalValue.reservation().issuerCoverageOperationId())
        .isEqualTo(originalReservationRow.get("issuer_coverage_operation_id", UUID.class))
        .isNotEqualTo(physicalValue.anchor().issuerCoverageOperationId());
    assertThat(physicalValue.reservation().issuerCoverageOperationFence())
        .isEqualTo(
            originalReservationRow
                .get("issuer_coverage_operation_fence", BigDecimal.class)
                .toBigIntegerExact());
    assertThat(physicalValue.reservation().coverageFence())
        .isEqualTo(
            originalReservationRow.get("coverage_fence", BigDecimal.class).toBigIntegerExact());
    assertThat(physicalValue.reservation().inventorySnapshotRevision())
        .isEqualTo(
            originalReservationRow
                .get("inventory_snapshot_revision", BigDecimal.class)
                .toBigIntegerExact());
    assertThat(physicalValue.reservation().inventoryRevision())
        .isEqualTo(
            originalReservationRow.get("inventory_revision", BigDecimal.class).toBigIntegerExact());
    assertThat(physicalValue.anchor().issuerCoverageOperationId())
        .isEqualTo(first.issuerCoverageOperationId());
    assertThat(physicalValue.anchor().operationFence()).isEqualTo(first.operationFence());
    assertThat(physicalValue.anchor().coverageFence()).isEqualTo(first.coverageFence());
    assertThat(physicalValue.anchor().issuerInventorySnapshotRevision())
        .isEqualTo(first.issuerInventorySnapshotRevision());
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT count(*) AS proof_count FROM game_session_canonical_issuer_bootstrap_partition_proof")
                .get("proof_count", Long.class))
        .isEqualTo(PARTITION_COUNT.longValueExact());
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT count(*) AS anchor_count FROM game_session_canonical_issuer_installed_anchor")
                .get("anchor_count", Long.class))
        .isEqualTo(1L);
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT count(*) AS pending_count FROM game_session_canonical_issuer_bootstrap_member_operation"
                        + " WHERE member_status = 'PENDING_PHYSICAL_PROOF'")
                .get("pending_count", Long.class))
        .isEqualTo((long) fixture.plan().memberWorks().size());
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT last_issued_coverage_generation FROM game_session_canonical_issuer_bootstrap_coverage_counter WHERE issuer_id = ?",
                    ISSUER)
                .get("last_issued_coverage_generation", BigDecimal.class))
        .isEqualTo(new BigDecimal(first.globalCoverageGeneration()));
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT state FROM game_session_canonical_issuer_layout_bootstrap_control WHERE issuer_id = ?",
                    ISSUER)
                .get("state", String.class))
        .isEqualTo("INITIAL_BOOTSTRAP_INTENT");

    for (BigInteger partitionId = BigInteger.ZERO;
        partitionId.compareTo(PARTITION_COUNT) < 0;
        partitionId = partitionId.add(BigInteger.ONE)) {
      byte[] key = key(fixture, partitionId);
      assertThat(administratorCommands.type(key)).isEqualTo("hash");
      assertThat(administratorCommands.pttl(key)).isEqualTo(-1L);
      if (partitionId.equals(fixture.activePartition())) {
        assertThat(administratorCommands.hlen(key)).isEqualTo(4L);
      } else {
        assertThat(administratorCommands.hlen(key)).isEqualTo(2L);
      }
    }
    assertThat(originalReservationImage(fixture.dsl())).isEqualTo(originalReservationImage);

    byte[] exactAnchorBeforeCounterAdvance = first.exactAnchorBytes();
    String anchorStorageBeforeCounterAdvance = anchorStorageImage(fixture.dsl());
    String proofStorageBeforeCounterAdvance = proofStorageImage(fixture.dsl());
    BigInteger laterCoverageGeneration =
        first.globalCoverageGeneration().add(BigInteger.valueOf(4));
    fixture
        .dsl()
        .execute(
            "UPDATE game_session_canonical_issuer_bootstrap_coverage_counter"
                + " SET last_issued_coverage_generation = ?, counter_version = 2"
                + " WHERE issuer_id = ? AND last_issued_coverage_generation = ? AND counter_version = 1",
            laterCoverageGeneration,
            ISSUER,
            first.globalCoverageGeneration());
    assertThat(originalReservationImage(fixture.dsl())).isEqualTo(originalReservationImage);
    assertThat(anchorStorageImage(fixture.dsl())).isEqualTo(anchorStorageBeforeCounterAdvance);
    assertThat(proofStorageImage(fixture.dsl())).isEqualTo(proofStorageBeforeCounterAdvance);

    // Discard the first reply and replay the same operation identity; it must reuse the pointer.
    CanonicalGameplayIssuerInstalledAnchor replay =
        owner(fixture, () -> current)
            .completeInitialBootstrap(fixture.intent().operationId(), fixture.requestId());
    assertThat(replay.exactAnchorBytes()).containsExactly(first.exactAnchorBytes());
    assertThat(replay.exactAnchorBytes()).containsExactly(exactAnchorBeforeCounterAdvance);
    assertThat(replay.globalCoverageGeneration()).isEqualTo(first.globalCoverageGeneration());
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT counter_version FROM game_session_canonical_issuer_bootstrap_coverage_counter WHERE issuer_id = ?",
                    ISSUER)
                .get("counter_version", Long.class))
        .isEqualTo(2L);
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT last_issued_coverage_generation FROM game_session_canonical_issuer_bootstrap_coverage_counter WHERE issuer_id = ?",
                    ISSUER)
                .get("last_issued_coverage_generation", BigDecimal.class))
        .isEqualTo(new BigDecimal(laterCoverageGeneration));
    assertThat(originalReservationImage(fixture.dsl())).isEqualTo(originalReservationImage);
  }

  @Test
  void freshPhysicalVerificationRefusesMissingPartitionWithoutRebuildingOrAllocatingGeneration() {
    Fixture fixture = fixture(BigInteger.valueOf(4), PARTITION_CAPACITY, 1);
    var current = currentState(fixture, stableIncarnation("missing-installed"));
    CanonicalGameplayIssuerInstalledAnchor installed =
        owner(fixture, () -> current)
            .completeInitialBootstrap(fixture.intent().operationId(), fixture.requestId());
    byte[] missingKey = key(fixture, fixture.activePartition());
    byte[][] missingKeyArguments = new byte[1][];
    missingKeyArguments[0] = missingKey;
    administratorCommands.del(missingKeyArguments);

    assertThatThrownBy(
            () ->
                owner(fixture, () -> current)
                    .verifyInstalledPhysicalCoverage(
                        fixture.intent().operationId(), fixture.requestId()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(administratorCommands.type(missingKey)).isEqualTo("none");
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT global_coverage_generation FROM game_session_canonical_issuer_installed_anchor WHERE issuer_id = ?",
                    ISSUER)
                .get("global_coverage_generation", BigDecimal.class))
        .isEqualTo(new BigDecimal(installed.globalCoverageGeneration()));
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT counter_version FROM game_session_canonical_issuer_bootstrap_coverage_counter WHERE issuer_id = ?",
                    ISSUER)
                .get("counter_version", Long.class))
        .isEqualTo(1L);
  }

  @Test
  void installedAnchorReadAndRetryUseFrozenPlanAfterOrdinaryNewAdvancesInventoryClock() {
    Fixture fixture = fixture(PARTITION_COUNT, PARTITION_CAPACITY, 1);
    var current = currentState(fixture, stableIncarnation("ordinary-new-after-anchor"));
    CanonicalGameplayIssuerInstalledAnchor installed =
        owner(fixture, () -> current)
            .completeInitialBootstrap(fixture.intent().operationId(), fixture.requestId());
    new CanonicalGameplayIssuerAdmissionControlRepository(fixture.dsl())
        .installInitialOpen(installed, () -> current);

    var candidate = identity(PARTITION_COUNT, PARTITION_CAPACITY, "post-anchor-ordinary-new");
    new CanonicalGameplayIssuerBindingLocalOperationRepository(fixture.dsl(), () -> current)
        .beginOrResumeOrdinaryNew(
            new CanonicalGameplayIssuerBindingLocalOperation.Request(
                UUID.nameUUIDFromBytes("post-anchor-new-request".getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes(
                    "post-anchor-new-transition".getBytes(StandardCharsets.UTF_8)),
                candidate,
                null,
                null));
    BigInteger currentInventoryRevision =
        fetchRequired(
                fixture.dsl(),
                "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                    + " WHERE singleton_id = 1")
            .get("inventory_revision", BigDecimal.class)
            .toBigIntegerExact();
    assertThat(currentInventoryRevision).isGreaterThan(installed.issuerInventorySnapshotRevision());
    String anchorImageBeforeRead = anchorStorageImage(fixture.dsl());
    String proofImageBeforeRead = proofStorageImage(fixture.dsl());
    String counterImageBeforeRead = coverageCounterImage(fixture.dsl());
    List<Map<String, String>> physicalImageBeforeRead = physicalPartitionImages(fixture);

    CanonicalGameplayIssuerInstalledAnchor retried =
        owner(fixture, () -> current)
            .completeInitialBootstrap(fixture.intent().operationId(), fixture.requestId());

    assertThat(retried.exactAnchorBytes()).containsExactly(installed.exactAnchorBytes());
    assertThat(anchorStorageImage(fixture.dsl())).isEqualTo(anchorImageBeforeRead);
    assertThat(proofStorageImage(fixture.dsl())).isEqualTo(proofImageBeforeRead);
    assertThat(coverageCounterImage(fixture.dsl())).isEqualTo(counterImageBeforeRead);
    assertThat(physicalPartitionImages(fixture)).isEqualTo(physicalImageBeforeRead);
    assertThat(fixtureProofCount(fixture)).isEqualTo(PARTITION_COUNT.longValueExact());
  }

  @Test
  void installedOriginalRetryRejectsWrongTupleAndChangedSourceOrIncarnation() {
    Fixture fixture = fixture(PARTITION_COUNT, PARTITION_CAPACITY, 1);
    var current = currentState(fixture, stableIncarnation("installed-retry-guards"));
    owner(fixture, () -> current)
        .completeInitialBootstrap(fixture.intent().operationId(), fixture.requestId());
    String anchorImage = anchorStorageImage(fixture.dsl());
    String proofImage = proofStorageImage(fixture.dsl());
    String counterImage = coverageCounterImage(fixture.dsl());
    List<Map<String, String>> physicalImage = physicalPartitionImages(fixture);

    assertThatThrownBy(
            () ->
                owner(fixture, () -> current)
                    .completeInitialBootstrap(UUID.randomUUID(), fixture.requestId()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                owner(fixture, () -> current)
                    .completeInitialBootstrap(fixture.intent().operationId(), UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);

    var changedSource =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            checkpointShape(BigInteger.valueOf(8)), current.incarnation());
    assertThatThrownBy(
            () ->
                owner(fixture, () -> changedSource)
                    .completeInitialBootstrap(fixture.intent().operationId(), fixture.requestId()))
        .isInstanceOf(IllegalStateException.class);
    var changedIncarnation =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            current.accountCheckpoint(), changedIncarnation(current.incarnation()));
    assertThatThrownBy(
            () ->
                owner(fixture, () -> changedIncarnation)
                    .completeInitialBootstrap(fixture.intent().operationId(), fixture.requestId()))
        .isInstanceOf(IllegalStateException.class);

    assertThat(anchorStorageImage(fixture.dsl())).isEqualTo(anchorImage);
    assertThat(proofStorageImage(fixture.dsl())).isEqualTo(proofImage);
    assertThat(coverageCounterImage(fixture.dsl())).isEqualTo(counterImage);
    assertThat(physicalPartitionImages(fixture)).isEqualTo(physicalImage);
  }

  @Test
  void movedAccountCheckpointOrStorageIncarnationLeavesNoCoverageGenerationOrPointer() {
    Fixture sourceFixture = fixture(PARTITION_COUNT, PARTITION_CAPACITY, 1);
    var sourceIncarnation = stableIncarnation("source-movement");
    var changedCheckpoint = checkpointShape(BigInteger.valueOf(8));
    AtomicInteger sourceCalls = new AtomicInteger();
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
        changedAfterReadback =
            () ->
                new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
                    sourceCalls.incrementAndGet() == 5
                        ? changedCheckpoint
                        : sourceFixture.intent().sourceCheckpoint(),
                    sourceIncarnation);
    assertThatThrownBy(
            () ->
                owner(sourceFixture, changedAfterReadback)
                    .completeInitialBootstrap(
                        sourceFixture.intent().operationId(), sourceFixture.requestId()))
        .isInstanceOf(IllegalStateException.class);
    assertNoInstalledCoverage(sourceFixture);
    assertThat(fixtureProofCount(sourceFixture)).isZero();

    Fixture incarnationFixture = fixture(PARTITION_COUNT, PARTITION_CAPACITY, 1);
    var initialIncarnation = stableIncarnation("incarnation-movement");
    var changedIncarnation = changedIncarnation(initialIncarnation);
    AtomicInteger incarnationCalls = new AtomicInteger();
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
        changedIncarnationAfterReadback =
            () ->
                new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
                    incarnationFixture.intent().sourceCheckpoint(),
                    incarnationCalls.incrementAndGet() == 5
                        ? changedIncarnation
                        : initialIncarnation);
    assertThatThrownBy(
            () ->
                owner(incarnationFixture, changedIncarnationAfterReadback)
                    .completeInitialBootstrap(
                        incarnationFixture.intent().operationId(), incarnationFixture.requestId()))
        .isInstanceOf(IllegalStateException.class);
    assertNoInstalledCoverage(incarnationFixture);
    assertThat(fixtureProofCount(incarnationFixture)).isZero();
  }

  @Test
  void absentOrMalformedPartitionAndLegacyValueDoNotIssueCoverageGeneration() {
    Fixture fixture = fixture(BigInteger.ONE, PARTITION_CAPACITY, 1);
    byte[] partitionKey = key(fixture, BigInteger.ZERO);
    administratorCommands.set(partitionKey, ascii("retained-wrong-type"));
    var current = currentState(fixture, stableIncarnation("wrong-type"));

    assertThatThrownBy(
            () ->
                owner(fixture, () -> current)
                    .completeInitialBootstrap(fixture.intent().operationId(), fixture.requestId()))
        .isInstanceOf(IllegalStateException.class);
    assertNoInstalledCoverage(fixture);
    assertThat(administratorCommands.type(partitionKey)).isEqualTo("string");
    assertThat(fixtureProofCount(fixture)).isZero();
  }

  @Test
  void originalReservationAndNewAnchorTupleConflictsRefuseTheSamePhysicalBinding() {
    assertPhysicalTupleConflict(false);
    assertPhysicalTupleConflict(true);
  }

  @Test
  void finitePlanCapacityConflictDoesNotCreateAnyPartitionOrCoverageGeneration() {
    Fixture fixture = fixture(BigInteger.ONE, BigInteger.ONE, 2);
    assertThat(fixture.plan().state())
        .isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.BLOCKED);
    var current = currentState(fixture, stableIncarnation("capacity-conflict"));

    assertThatThrownBy(
            () ->
                owner(fixture, () -> current)
                    .completeInitialBootstrap(fixture.intent().operationId(), fixture.requestId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("blockers");
    assertNoInstalledCoverage(fixture);
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT count(*) AS proof_count FROM game_session_canonical_issuer_bootstrap_partition_proof")
                .get("proof_count", Long.class))
        .isZero();
    assertThat(administratorCommands.type(key(fixture, BigInteger.ZERO))).isEqualTo("none");
  }

  private void assertPhysicalTupleConflict(boolean wrongNewAnchor) {
    Fixture fixture = fixture(BigInteger.ONE, PARTITION_CAPACITY, 1);
    administratorCommands.del(new byte[][] {key(fixture, BigInteger.ZERO)});
    var incarnation = stableIncarnation(wrongNewAnchor ? "wrong-anchor" : "wrong-original");
    CanonicalGameplayIssuerBootstrapPartitionWork work =
        partitionWork(fixture, BigInteger.ZERO, trustedIncarnation(incarnation));
    var current = currentState(fixture, incarnation);
    partitionRedisOwner(fixture, () -> current).buildAndRead(work);
    var operation = work.memberOperations().getFirst();
    var expected = operation.expectedMemberValue();
    CanonicalGameplayIssuerMemberV2Value changed =
        wrongNewAnchor
            ? new CanonicalGameplayIssuerMemberV2Value(
                expected.owner(),
                expected.bindingGeneration(),
                expected.reservation(),
                new CanonicalGameplayIssuerMemberV2Value.Anchor(
                    UUID.randomUUID(),
                    expected.anchor().operationFence(),
                    expected.anchor().coverageFence(),
                    expected.anchor().issuerInventorySnapshotRevision()),
                expected.memberOperation(),
                expected.issuerControlEpoch(),
                expected.layoutDescriptor(),
                expected.sourceCheckpoint(),
                expected.incarnation())
            : new CanonicalGameplayIssuerMemberV2Value(
                expected.owner(),
                expected.bindingGeneration(),
                changedOriginalReservation(expected.reservation()),
                expected.anchor(),
                expected.memberOperation(),
                expected.issuerControlEpoch(),
                expected.layoutDescriptor(),
                expected.sourceCheckpoint(),
                expected.incarnation());
    byte[] changedBytes = CanonicalGameplayIssuerMemberV2Codec.encode(changed);
    byte[] key = key(fixture, BigInteger.ZERO);
    administratorCommands.hset(key, expected.owner().bindingRef().bytes(), changedBytes);
    administratorCommands.hset(
        key,
        ascii(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                expected.reservation().reservationId())),
        changedBytes);

    assertThatThrownBy(
            () ->
                owner(fixture, () -> current)
                    .completeInitialBootstrap(fixture.intent().operationId(), fixture.requestId()))
        .isInstanceOf(IllegalStateException.class);
    assertNoInstalledCoverage(fixture);
    assertThat(fixtureProofCount(fixture)).isZero();
  }

  private Fixture fixture(BigInteger partitions, BigInteger capacity, int activeBindingCount) {
    TestSchema schema = newTestSchemaAtVersion("gs_issuer_coverage_", "1030");
    schemas.add(schema);
    establishActualLegacyDisposition(schema.dsl());
    CanonicalGameplayBindingIdentity active = identity(partitions, capacity, "coverage-active");
    seedActiveSource(schema.dsl(), active);
    BigInteger activePartition = active.bindingRef().partitionId(partitions);
    for (int index = 1; index < activeBindingCount; index++) {
      CanonicalGameplayBindingIdentity samePartition =
          identityInPartition(partitions, capacity, activePartition, index);
      seedActiveSource(schema.dsl(), samePartition);
    }
    UUID requestId = UUID.randomUUID();
    var checkpoint = sourceCheckpoint(BigInteger.valueOf(7));
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(schema.dsl())
            .beginInitialBootstrapIntent(requestId, descriptor(partitions, capacity), checkpoint);
    migrateRemaining(schema);
    var plan =
        new CanonicalGameplayIssuerPartitionBuildRepository(schema.dsl())
            .prepareInitialBuildPlan(intent.operationId(), requestId);
    return new Fixture(schema, activePartition, intent, requestId, checkpoint, plan);
  }

  private CanonicalGameplayIssuerBootstrapCoverageOwner owner(
      Fixture fixture,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource source) {
    return new CanonicalGameplayIssuerBootstrapCoverageOwner(
        fixture.dsl(), partitionRedisOwner(fixture, source), source);
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner partitionRedisOwner(
      Fixture fixture,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource source) {
    return new CanonicalGameplayIssuerBootstrapPartitionRedisOwner(
        ownerClient,
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution())),
        source);
  }

  private CanonicalGameplayIssuerBootstrapPartitionWork partitionWork(
      Fixture fixture,
      BigInteger partitionId,
      CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation
          incarnation) {
    return new CanonicalGameplayIssuerBootstrapMemberOperationRepository(fixture.dsl())
        .preparePartitionWork(
            fixture.intent().operationId(),
            fixture.requestId(),
            partitionId,
            fixture.currentAccountCheckpoint(),
            incarnation);
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState(
      Fixture fixture, CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation) {
    return new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
        fixture.intent().sourceCheckpoint(), incarnation);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor
      descriptor(BigInteger partitions, BigInteger capacity) {
    return CanonicalGameplayIssuerTestFixtures.descriptor(
        ISSUER, LAYOUT_VERSION, partitions, capacity);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
      sourceCheckpoint(BigInteger generation) {
    return CanonicalGameplayIssuerTestFixtures.sourceCheckpoint(ISSUER, generation);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint
      checkpointShape(BigInteger generation) {
    return CanonicalGameplayIssuerTestFixtures.checkpointShape(ISSUER, generation);
  }

  private static CanonicalGameplayIssuerMemberV2Value.Incarnation stableIncarnation(String seed) {
    return CanonicalGameplayIssuerTestFixtures.stableIncarnation(seed);
  }

  private static CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation
      trustedIncarnation(CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation) {
    return new CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation(
        incarnation.attestorBinding(),
        incarnation.acceptedAttestationDigest(),
        incarnation.targetDeploymentEvidence(),
        incarnation.coordOpsRoAclDigest(),
        incarnation.coordRedisConfigObservedDigest());
  }

  private static CanonicalGameplayIssuerMemberV2Value.Incarnation changedIncarnation(
      CanonicalGameplayIssuerMemberV2Value.Incarnation current) {
    return new CanonicalGameplayIssuerMemberV2Value.Incarnation(
        current.attestorBinding() + "-changed",
        current.acceptedAttestationDigest(),
        current.targetDeploymentEvidence(),
        current.coordOpsRoAclDigest(),
        current.coordRedisConfigObservedDigest());
  }

  private static CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
      deploymentEvidence(String seed, String digest) {
    return new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
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
  }

  private static CanonicalIssuerPartitionReservation changedOriginalReservation(
      CanonicalIssuerPartitionReservation original) {
    return new CanonicalIssuerPartitionReservation(
        original.reservationId(),
        original.owner(),
        original.bindingGeneration(),
        original.transitionId(),
        original.partitionId(),
        original.lifecycle(),
        original.reservationFence(),
        UUID.randomUUID(),
        original.issuerCoverageOperationFence(),
        original.coverageFence(),
        original.inventorySnapshotRevision(),
        original.inventoryRevision());
  }

  private static CanonicalGameplayBindingIdentity identity(
      BigInteger partitions, BigInteger capacity, String seed) {
    return new CanonicalGameplayBindingIdentity(
        UUID.nameUUIDFromBytes((seed + "-account").getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes((seed + "-tenant").getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes((seed + "-namespace").getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes((seed + "-character").getBytes(StandardCharsets.UTF_8)),
        "SHARED",
        UUID.nameUUIDFromBytes((seed + "-game").getBytes(StandardCharsets.UTF_8)),
        90_000L + Math.abs((long) seed.hashCode()),
        UUID.nameUUIDFromBytes((seed + "-session").getBytes(StandardCharsets.UTF_8)).toString(),
        UUID.nameUUIDFromBytes((seed + "-region").getBytes(StandardCharsets.UTF_8)),
        BigInteger.valueOf(12),
        ISSUER,
        BigInteger.valueOf(7),
        LAYOUT_VERSION,
        partitions,
        capacity);
  }

  private static CanonicalGameplayBindingIdentity identityInPartition(
      BigInteger partitions, BigInteger capacity, BigInteger partitionId, int seed) {
    for (int candidate = 0; candidate < 100_000; candidate++) {
      CanonicalGameplayBindingIdentity identity =
          identity(partitions, capacity, "coverage-active-" + seed + "-" + candidate);
      if (identity.bindingRef().partitionId(partitions).equals(partitionId)) return identity;
    }
    throw new IllegalStateException(
        "Could not create a second reservation in the target partition");
  }

  private static void seedActiveSource(DSLContext dsl, CanonicalGameplayBindingIdentity identity) {
    byte[] bindingRef = identity.bindingRef().bytes();
    BigInteger partitionId =
        identity.bindingRef().partitionId(identity.issuerIndexPartitionCount());
    UUID accountIndexFence = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID transitionId = UUID.randomUUID();
    UUID reservationFence = UUID.randomUUID();
    UUID originalCoverageOperationId = UUID.randomUUID();
    dsl.execute(
        "UPDATE game_session_canonical_binding_inventory_clock SET inventory_revision = 8"
            + " WHERE singleton_id = 1");
    dsl.execute(
        "INSERT INTO game_session_canonical_binding_generation"
            + " (tenant_id, playable_state_namespace_id, character_id, last_issued_generation)"
            + " VALUES (?, ?, ?, 5)",
        identity.tenantId(),
        identity.playableStateNamespaceId(),
        identity.characterId());
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
        bindingRef,
        identity.accountId(),
        identity.tenantId(),
        identity.playableStateNamespaceId(),
        identity.characterId(),
        identity.playableStateScope(),
        identity.gameInstanceId(),
        identity.runtimeGameInstanceId(),
        identity.sessionId(),
        identity.regionId(),
        new BigDecimal(identity.regionEpoch()),
        identity.issuerId(),
        new BigDecimal(identity.issuerAuthGeneration()),
        new BigDecimal(identity.issuerIndexLayoutVersion()),
        new BigDecimal(identity.issuerIndexPartitionCount()),
        new BigDecimal(identity.issuerIndexPartitionCapacity()),
        new BigDecimal(partitionId),
        accountIndexFence,
        reservationId,
        transitionId);
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
            + " VALUES (?, ?, ?, ?, ?, ?, ?, 5, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " 'BOUND', ?, ?, 1, 1, 7, 8)",
        reservationId,
        bindingRef,
        identity.accountId(),
        identity.tenantId(),
        identity.gameInstanceId(),
        identity.runtimeGameInstanceId(),
        identity.sessionId(),
        transitionId,
        identity.issuerId(),
        new BigDecimal(identity.issuerAuthGeneration()),
        identity.playableStateNamespaceId(),
        identity.characterId(),
        identity.playableStateScope(),
        identity.regionId(),
        new BigDecimal(identity.regionEpoch()),
        new BigDecimal(partitionId),
        new BigDecimal(identity.issuerIndexLayoutVersion()),
        new BigDecimal(identity.issuerIndexPartitionCount()),
        new BigDecimal(identity.issuerIndexPartitionCapacity()),
        reservationFence,
        originalCoverageOperationId);
    dsl.execute(
        "INSERT INTO game_session_canonical_binding_transition"
            + " (transition_id, tenant_id, playable_state_namespace_id, character_id,"
            + " expected_prior_binding_ref, expected_prior_binding_generation, candidate_binding_ref,"
            + " candidate_binding_generation, candidate_account_index_fence, issuer_reservation_id,"
            + " status, inventory_revision)"
            + " VALUES (?, ?, ?, ?, NULL, NULL, ?, 5, ?, ?, 'COMMITTED', 8)",
        transitionId,
        identity.tenantId(),
        identity.playableStateNamespaceId(),
        identity.characterId(),
        bindingRef,
        accountIndexFence,
        reservationId);
  }

  private static String originalReservationImage(DSLContext dsl) {
    return fetchRequired(
            dsl,
            "SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY reservation_id), '[]'::jsonb)::text AS image "
                + "FROM game_session_canonical_issuer_partition_reservation r")
        .get("image", String.class);
  }

  private static String anchorStorageImage(DSLContext dsl) {
    return fetchRequired(
            dsl,
            "SELECT encode(exact_anchor_bytes, 'base64') || ':' || exact_anchor_digest || ':'"
                + " || global_coverage_generation::text AS image"
                + " FROM game_session_canonical_issuer_installed_anchor WHERE issuer_id = ?",
            ISSUER)
        .get("image", String.class);
  }

  private static String proofStorageImage(DSLContext dsl) {
    return fetchRequired(
            dsl,
            "SELECT coalesce(string_agg(partition_id::text || ':'"
                + " || encode(exact_metadata_bytes, 'base64') || ':'"
                + " || encode(exact_physical_field_set_bytes, 'base64') || ':'"
                + " || exact_physical_field_set_digest, '|' ORDER BY partition_id), '') AS image"
                + " FROM game_session_canonical_issuer_bootstrap_partition_proof")
        .get("image", String.class);
  }

  private static String coverageCounterImage(DSLContext dsl) {
    Record counter =
        fetchRequired(
            dsl,
            "SELECT last_issued_coverage_generation, counter_version FROM "
                + "game_session_canonical_issuer_bootstrap_coverage_counter WHERE issuer_id = ?",
            ISSUER);
    return counter.get("last_issued_coverage_generation", BigDecimal.class).toPlainString()
        + ":"
        + counter.get("counter_version", Long.class);
  }

  private List<Map<String, String>> physicalPartitionImages(Fixture fixture) {
    List<Map<String, String>> images = new ArrayList<>();
    for (BigInteger partitionId = BigInteger.ZERO;
        partitionId.compareTo(fixture.intent().layoutDescriptor().partitionCount()) < 0;
        partitionId = partitionId.add(BigInteger.ONE)) {
      Map<byte[], byte[]> raw = administratorCommands.hgetall(key(fixture, partitionId));
      Map<String, String> image = new LinkedHashMap<>();
      raw.forEach(
          (field, value) ->
              image.put(
                  Base64.getEncoder().encodeToString(field),
                  Base64.getEncoder().encodeToString(value)));
      images.add(Map.copyOf(image));
    }
    return List.copyOf(images);
  }

  private static long fixtureProofCount(Fixture fixture) {
    return fetchRequired(
            fixture.dsl(),
            "SELECT count(*) AS proof_count FROM game_session_canonical_issuer_bootstrap_partition_proof")
        .get("proof_count", Long.class);
  }

  private static void assertNoInstalledCoverage(Fixture fixture) {
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT count(*) AS anchor_count FROM game_session_canonical_issuer_installed_anchor")
                .get("anchor_count", Long.class))
        .isZero();
    assertThat(
            fetchRequired(
                    fixture.dsl(),
                    "SELECT count(*) AS counter_count FROM game_session_canonical_issuer_bootstrap_coverage_counter")
                .get("counter_count", Long.class))
        .isZero();
  }

  private static Record fetchRequired(DSLContext dsl, String sql, Object... bindings) {
    Record record = dsl.fetchOne(sql, bindings);
    if (record == null) throw new IllegalStateException("Required focused query returned no row");
    return record;
  }

  private static byte[] key(Fixture fixture, BigInteger partitionId) {
    return CanonicalGameplayIssuerRedisKeys.forPartition(
            fixture.intent().layoutDescriptor(), partitionId)
        .getBytes(StandardCharsets.US_ASCII);
  }

  private void establishActualLegacyDisposition(DSLContext dsl) {
    CanonicalGameplayIssuerTestFixtures.establishActualLegacyDisposition(dsl);
  }

  private TestSchema newTestSchemaAtVersion(String prefix, String version) {
    String schema = CanonicalGameplayIssuerTestFixtures.schemaName(prefix);
    DriverManagerDataSource dataSource = dataSource(schema);
    CanonicalGameplayIssuerTestFixtures.migrateSchema(
        dataSource, schema, MIGRATION_LOCATION, version);
    return new TestSchema(schema, dataSource, DSL.using(dataSource, SQLDialect.POSTGRES));
  }

  private static void migrateRemaining(TestSchema schema) {
    CanonicalGameplayIssuerTestFixtures.migrateSchema(
        schema.dataSource(), schema.schema(), MIGRATION_LOCATION, null);
  }

  private DriverManagerDataSource dataSource(String schema) {
    return CanonicalGameplayIssuerTestFixtures.dataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), schema);
  }

  private void dropSchema(String schema) {
    CanonicalGameplayIssuerTestFixtures.dropSchema(
        dataSource("public"), schema, "Could not drop the owned issuer coverage test schema");
  }

  private static RedisClient createRedisClient(String username, String password) {
    return CanonicalGameplayIssuerTestFixtures.createRedisClient(
        REDIS.getHost(), REDIS.getMappedPort(6379), username, password);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Fixture(
      TestSchema schema,
      BigInteger activePartition,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent,
      UUID requestId,
      CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
          currentAccountCheckpoint,
      CanonicalGameplayIssuerPartitionBuildPlan plan) {
    DSLContext dsl() {
      return schema.dsl();
    }
  }

  private record TestSchema(String schema, DriverManagerDataSource dataSource, DSLContext dsl) {}
}
