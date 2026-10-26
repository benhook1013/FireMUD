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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot.Family;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual one-partition Redis readback composed from SQL-issued initial-bootstrap work. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayIssuerBootstrapPartitionRedisOwnerIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String OWNER_PRINCIPAL = "gamesession_coord_app";
  private static final String ISSUER_KEY_PREFIX = "session:game:index:issuer:";
  private static final UUID TERMINAL_CHARACTER_ID =
      UUID.fromString("88888888-8888-4888-8888-888888888888");
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
  void buildsCompletePopulatedPartitionAndCountsReleasedOnlyAsRetainedEvidence() {
    Fixture fixture = fixture(true);
    CanonicalGameplayIssuerBootstrapPartitionWork work =
        partitionWork(fixture, fixture.partitionId());

    assertThat(work.buildPlan().state())
        .isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED);
    assertThat(work.memberOperations()).hasSize(2);
    assertThat(work.memberOperations())
        .filteredOn(CanonicalGameplayIssuerBootstrapMemberOperation::expectedBindingPresent)
        .singleElement()
        .satisfies(
            operation ->
                assertThat(operation.bindingLifecycle())
                    .isEqualTo(
                        net.firedevops.firemud.gamesession.binding
                            .CanonicalGameplayBindingInventoryEntry.Lifecycle.ACTIVE));
    assertThat(work.memberOperations())
        .filteredOn(operation -> !operation.countsTowardCapacity())
        .singleElement()
        .satisfies(
            operation ->
                assertThat(operation.expectedMemberValue().reservation().lifecycle())
                    .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASED));

    var active =
        work.memberOperations().stream()
            .filter(CanonicalGameplayIssuerBootstrapMemberOperation::expectedBindingPresent)
            .findFirst()
            .orElseThrow();
    var value = active.expectedMemberValue();
    var originalReservationCoverageTuple =
        List.of(
            value.reservation().issuerCoverageOperationId(),
            value.reservation().issuerCoverageOperationFence(),
            value.reservation().coverageFence(),
            value.reservation().inventorySnapshotRevision());
    var bootstrapAnchorTuple =
        List.of(
            value.anchor().issuerCoverageOperationId(),
            value.anchor().operationFence(),
            value.anchor().coverageFence(),
            value.anchor().issuerInventorySnapshotRevision());
    assertThat(originalReservationCoverageTuple).isNotEqualTo(bootstrapAnchorTuple);

    var firstOwner = owner(work, currentState(fixture, work));
    var first = firstOwner.buildAndRead(work);
    assertThat(first.physicalFieldCount()).isEqualTo(5);
    assertThat(first.physicalPartitionReservationCount()).isEqualTo(2);
    assertThat(first.physicalBindingCount()).isEqualTo(1);
    assertThat(first.physicalCountedCapacityCount()).isEqualTo(1);
    assertThat(first.plannedAggregateCapacityCount()).isEqualTo(BigInteger.ONE);

    CanonicalGameplayIssuerBootstrapPartitionWork restartedWork =
        partitionWork(fixture, fixture.partitionId());
    assertThat(restartedWork.memberOperations())
        .extracting(CanonicalGameplayIssuerBootstrapMemberOperation::exactMemberValueBytes)
        .containsExactlyElementsOf(
            work.memberOperations().stream()
                .map(CanonicalGameplayIssuerBootstrapMemberOperation::exactMemberValueBytes)
                .toList());
    var afterRestart =
        owner(restartedWork, currentState(fixture, restartedWork)).buildAndRead(restartedWork);
    assertThat(afterRestart.physicalFieldCount()).isEqualTo(first.physicalFieldCount());
    assertThat(afterRestart.physicalCountedCapacityCount()).isEqualTo(1);

    byte[] key = key(fixture, work.partitionId());
    assertThat(administratorCommands.type(key)).isEqualTo("hash");
    assertThat(administratorCommands.pttl(key)).isEqualTo(-1L);
    assertThat(administratorCommands.hlen(key)).isEqualTo(5L);
    assertThat(statusesRemainPending(fixture.dsl())).isTrue();
  }

  @Test
  void positivelyPreparedEmptyPartitionBuildsOnlyItsCommonMetadata() {
    Fixture fixture = fixture(false);
    BigInteger emptyPartition = fixture.partitionId().add(BigInteger.ONE).mod(PARTITION_COUNT);
    CanonicalGameplayIssuerBootstrapPartitionWork work = partitionWork(fixture, emptyPartition);
    assertThat(work.buildPlan().state())
        .isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED);
    assertThat(work.buildPlan().memberWorks()).hasSize(1);
    assertThat(work.memberOperations()).isEmpty();

    byte[] key = key(fixture, emptyPartition);
    assertThat(administratorCommands.type(key)).isEqualTo("none");
    var readback = owner(work, currentState(fixture, work)).buildAndRead(work);

    assertThat(readback.physicalFieldCount()).isEqualTo(2);
    assertThat(readback.physicalPartitionReservationCount()).isZero();
    assertThat(readback.physicalCountedCapacityCount()).isZero();
    assertThat(readback.plannedAggregateCapacityCount()).isEqualTo(BigInteger.ONE);
    assertThat(administratorCommands.hlen(key)).isEqualTo(2L);
    assertThat(statusesRemainPending(fixture.dsl())).isTrue();
  }

  @Test
  void candidatePreparedSourceRowRetainsCapacityWithoutCreatingPhysicalBinding() {
    Fixture fixture = fixtureWithPreparedCandidate();
    CanonicalGameplayIssuerBootstrapPartitionWork work =
        partitionWork(fixture, fixture.partitionId());
    var candidateOperation =
        work.memberOperations().stream()
            .filter(
                operation ->
                    operation.bindingLifecycle()
                        == net.firedevops.firemud.gamesession.binding
                            .CanonicalGameplayBindingInventoryEntry.Lifecycle.CANDIDATE_PREPARED)
            .findFirst()
            .orElseThrow();

    assertThat(candidateOperation.expectedBindingPresent()).isFalse();
    assertThat(candidateOperation.countsTowardCapacity()).isTrue();
    assertThat(candidateOperation.expectedMemberValue().reservation().lifecycle())
        .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RESERVED);
    var readback = owner(work, currentState(fixture, work)).buildAndRead(work);

    assertThat(readback.physicalPartitionReservationCount()).isEqualTo(2);
    assertThat(readback.physicalBindingCount()).isEqualTo(1);
    assertThat(readback.physicalCountedCapacityCount()).isEqualTo(2);
    assertThat(readback.plannedAggregateCapacityCount()).isEqualTo(BigInteger.valueOf(2));
    byte[] key = key(fixture, fixture.partitionId());
    byte[] bindingRef = candidateOperation.expectedMemberValue().owner().bindingRef().bytes();
    byte[] reservationField =
        ascii(
            CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                candidateOperation.expectedMemberValue().reservation().reservationId()));
    assertThat(administratorCommands.hget(key, bindingRef)).isNull();
    assertThat(administratorCommands.hget(key, reservationField))
        .containsExactly(candidateOperation.exactMemberValueBytes());
    assertThat(administratorCommands.hlen(key)).isEqualTo(5L);
    assertThat(statusesRemainPending(fixture.dsl())).isTrue();
  }

  @Test
  void sameBindingWithDifferentAnchorReservationLifecycleOrLocalOperationIsAConflict() {
    Fixture fixture = fixture(false);
    CanonicalGameplayIssuerBootstrapPartitionWork work =
        partitionWork(fixture, fixture.partitionId());
    var active = work.memberOperations().getFirst();
    assertThat(active.expectedBindingPresent()).isTrue();
    owner(work, currentState(fixture, work)).buildAndRead(work);

    byte[] key = key(fixture, work.partitionId());
    byte[] bindingField = active.expectedMemberValue().owner().bindingRef().bytes();
    byte[] exact = active.exactMemberValueBytes();
    List<byte[]> conflicts =
        List.of(
            encodeWith(
                active.expectedMemberValue(),
                active.expectedMemberValue().reservation(),
                new CanonicalGameplayIssuerMemberV2Value.Anchor(
                    UUID.randomUUID(),
                    active.expectedMemberValue().anchor().operationFence(),
                    active.expectedMemberValue().anchor().coverageFence(),
                    active.expectedMemberValue().anchor().issuerInventorySnapshotRevision()),
                active.expectedMemberValue().memberOperation()),
            encodeWith(
                active.expectedMemberValue(),
                withReservation(
                    active.expectedMemberValue().reservation(),
                    UUID.randomUUID(),
                    active.expectedMemberValue().reservation().lifecycle()),
                active.expectedMemberValue().anchor(),
                active.expectedMemberValue().memberOperation()),
            encodeWith(
                active.expectedMemberValue(),
                withReservation(
                    active.expectedMemberValue().reservation(),
                    active.expectedMemberValue().reservation().reservationId(),
                    CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING),
                active.expectedMemberValue().anchor(),
                active.expectedMemberValue().memberOperation()),
            encodeWith(
                active.expectedMemberValue(),
                active.expectedMemberValue().reservation(),
                active.expectedMemberValue().anchor(),
                new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
                    UUID.randomUUID(),
                    active.expectedMemberValue().memberOperation().operationFence(),
                    active.expectedMemberValue().memberOperation().coverageGeneration())),
            encodeNewerBinding(active.expectedMemberValue()));

    for (byte[] conflict : conflicts) {
      administratorCommands.hset(key, bindingField, conflict);
      assertThatThrownBy(() -> owner(work, currentState(fixture, work)).buildAndRead(work))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("projection is unavailable");
      assertThat(administratorCommands.hget(key, bindingField)).containsExactly(conflict);
      assertThat(administratorCommands.hlen(key)).isEqualTo(4L);
      administratorCommands.hset(key, bindingField, exact);
    }
    assertThat(statusesRemainPending(fixture.dsl())).isTrue();
  }

  @Test
  void malformedLegacyForeignAndExpiringPhysicalStateRefuseWithoutMutation() {
    Fixture fixture = fixture(false);
    CanonicalGameplayIssuerBootstrapPartitionWork populated =
        partitionWork(fixture, fixture.partitionId());
    var active = populated.memberOperations().getFirst();
    byte[] populatedKey = key(fixture, populated.partitionId());
    byte[] bindingField = active.expectedMemberValue().owner().bindingRef().bytes();
    byte[] legacyV1 = "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
    administratorCommands.hset(populatedKey, bindingField, legacyV1);

    assertThatThrownBy(
            () -> owner(populated, currentState(fixture, populated)).buildAndRead(populated))
        .isInstanceOf(IllegalStateException.class);
    assertThat(administratorCommands.hget(populatedKey, bindingField)).containsExactly(legacyV1);
    assertThat(administratorCommands.hlen(populatedKey)).isEqualTo(1L);

    BigInteger foreignPartition = populated.partitionId().add(BigInteger.TWO).mod(PARTITION_COUNT);
    var foreignWork = partitionWork(fixture, foreignPartition);
    byte[] foreignKey = key(fixture, foreignPartition);
    byte[] foreignField = ascii("unrelated-operator-field");
    byte[] foreignValue = ascii("retained-source");
    administratorCommands.hset(foreignKey, foreignField, foreignValue);
    assertThatThrownBy(
            () -> owner(foreignWork, currentState(fixture, foreignWork)).buildAndRead(foreignWork))
        .isInstanceOf(IllegalStateException.class);
    assertThat(administratorCommands.hlen(foreignKey)).isEqualTo(1L);
    assertThat(administratorCommands.hget(foreignKey, foreignField)).containsExactly(foreignValue);

    BigInteger expiringPartition =
        populated.partitionId().add(BigInteger.valueOf(3)).mod(PARTITION_COUNT);
    var expiringWork = partitionWork(fixture, expiringPartition);
    byte[] expiringKey = key(fixture, expiringPartition);
    byte[] expiringField = ascii("foreign-ttl-field");
    byte[] expiringValue = ascii("retained-ttl-source");
    administratorCommands.hset(expiringKey, expiringField, expiringValue);
    administratorCommands.pexpire(expiringKey, 60_000);
    long ttlBefore = administratorCommands.pttl(expiringKey);

    assertThatThrownBy(
            () ->
                owner(expiringWork, currentState(fixture, expiringWork)).buildAndRead(expiringWork))
        .isInstanceOf(IllegalStateException.class);
    assertThat(administratorCommands.pttl(expiringKey)).isPositive().isLessThanOrEqualTo(ttlBefore);
    assertThat(administratorCommands.hget(expiringKey, expiringField))
        .containsExactly(expiringValue);

    BigInteger wrongTypePartition =
        populated.partitionId().add(BigInteger.valueOf(4)).mod(PARTITION_COUNT);
    var wrongTypeWork = partitionWork(fixture, wrongTypePartition);
    byte[] wrongTypeKey = key(fixture, wrongTypePartition);
    byte[] wrongTypeValue = ascii("foreign-persistent-string");
    administratorCommands.set(wrongTypeKey, wrongTypeValue);
    assertThatThrownBy(
            () ->
                owner(wrongTypeWork, currentState(fixture, wrongTypeWork))
                    .buildAndRead(wrongTypeWork))
        .isInstanceOf(IllegalStateException.class);
    assertThat(administratorCommands.type(wrongTypeKey)).isEqualTo("string");
    assertThat(administratorCommands.get(wrongTypeKey)).containsExactly(wrongTypeValue);
    assertThat(statusesRemainPending(fixture.dsl())).isTrue();
  }

  @Test
  void changedFreshCheckpointOrIncarnationBeforeAndAfterRedisLeavesNoReadbackCapability() {
    Fixture fixture = fixture(false);
    CanonicalGameplayIssuerBootstrapPartitionWork work =
        partitionWork(fixture, fixture.partitionId());
    byte[] key = key(fixture, work.partitionId());
    var changedCheckpoint = alternateCheckpointShape(BigInteger.valueOf(8));
    AtomicInteger beforeCalls = new AtomicInteger();
    var beforeOwner =
        owner(
            work,
            () -> {
              beforeCalls.incrementAndGet();
              return new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
                  changedCheckpoint, work.incarnation());
            });

    assertThatThrownBy(() -> beforeOwner.buildAndRead(work))
        .isInstanceOf(IllegalStateException.class);
    assertThat(beforeCalls).hasValue(1);
    assertThat(administratorCommands.type(key)).isEqualTo("none");

    AtomicInteger beforeIncarnationCalls = new AtomicInteger();
    var beforeIncarnationOwner =
        owner(
            work,
            () -> {
              beforeIncarnationCalls.incrementAndGet();
              return new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
                  fixture.intent().sourceCheckpoint(), changedIncarnation(work.incarnation()));
            });
    assertThatThrownBy(() -> beforeIncarnationOwner.buildAndRead(work))
        .isInstanceOf(IllegalStateException.class);
    assertThat(beforeIncarnationCalls).hasValue(1);
    assertThat(administratorCommands.type(key)).isEqualTo("none");

    AtomicInteger afterCheckpointCalls = new AtomicInteger();
    var changedAfterCheckpointOwner =
        owner(
            work,
            () ->
                new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
                    afterCheckpointCalls.incrementAndGet() == 1
                        ? fixture.intent().sourceCheckpoint()
                        : changedCheckpoint,
                    work.incarnation()));
    assertThatThrownBy(() -> changedAfterCheckpointOwner.buildAndRead(work))
        .isInstanceOf(IllegalStateException.class);
    assertThat(afterCheckpointCalls).hasValue(2);
    assertThat(administratorCommands.type(key)).isEqualTo("hash");
    assertThat(administratorCommands.hlen(key)).isEqualTo(4L);

    AtomicInteger afterCalls = new AtomicInteger();
    var changedAfterOwner =
        owner(
            work,
            () ->
                new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
                    fixture.intent().sourceCheckpoint(),
                    afterCalls.incrementAndGet() == 1
                        ? work.incarnation()
                        : changedIncarnation(work.incarnation())));
    assertThatThrownBy(() -> changedAfterOwner.buildAndRead(work))
        .isInstanceOf(IllegalStateException.class);
    assertThat(afterCalls).hasValue(2);
    assertThat(administratorCommands.type(key)).isEqualTo("hash");
    assertThat(administratorCommands.hlen(key)).isEqualTo(4L);
    assertThat(statusesRemainPending(fixture.dsl())).isTrue();
  }

  @Test
  void retainedCommitAfterLostReplyReplaysFromFreshSqlWorkAndSeparatePhysicalReadback()
      throws Exception {
    Fixture fixture = fixture(false);
    CanonicalGameplayIssuerBootstrapPartitionWork work =
        partitionWork(fixture, fixture.partitionId());
    var physical = CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.expectedFields(work);
    byte[] script =
        resource(CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution.RESOURCE_PATH);
    String scriptSha = administratorCommands.scriptLoad(script);
    byte[] key = key(fixture, work.partitionId());

    // The test deliberately discards this post-commit script reply to model a lost reply/crash.
    administratorCommands.evalsha(
        scriptSha, ScriptOutputType.INTEGER, new byte[][] {key}, scriptArguments(work, physical));

    CanonicalGameplayIssuerBootstrapPartitionWork restartedWork =
        partitionWork(fixture, work.partitionId());
    var readback =
        owner(restartedWork, currentState(fixture, restartedWork)).buildAndRead(restartedWork);
    assertThat(readback.physicalFieldCount()).isEqualTo(4);
    assertThat(readback.physicalCountedCapacityCount()).isEqualTo(1);
    assertThat(statusesRemainPending(fixture.dsl())).isTrue();
  }

  @Test
  void deliberatelyLowerCapacityLuaArgumentsRefuseWithoutChangingOwnedHashImage() throws Exception {
    Fixture fixture = fixture(false, 2);
    CanonicalGameplayIssuerBootstrapPartitionWork work =
        partitionWork(fixture, fixture.partitionId());
    assertThat(work.buildPlan().state())
        .isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED);
    assertThat(work.memberOperations()).hasSize(2);
    assertThat(work.memberOperations())
        .allSatisfy(operation -> assertThat(operation.countsTowardCapacity()).isTrue());
    var physical = CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.expectedFields(work);
    var catalog =
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution()));
    RedisScriptDescriptor descriptor =
        catalog.require(CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution.SCRIPT_ID);
    assertThat(descriptor.category())
        .isEqualTo(RedisScriptDescriptor.ScriptCategory.SESSION_ISSUER_INDEX);
    assertThat(descriptor.principal()).isEqualTo(OWNER_PRINCIPAL);
    byte[] script = resource(descriptor.resourcePath());
    assertThat(digest(script)).isEqualTo("sha256:" + descriptor.sha256());
    byte[] key = key(fixture, work.partitionId());
    byte[][] arguments = scriptArguments(work, physical);
    // Deliberately inconsistent negative Lua arguments probe refusal only; the complete SQL plan
    // remains valid at its pinned capacity of 32 and is never treated as positive overflow work.
    arguments[3] = ascii("1");
    arguments[4] = lowerCapacityMetadata(physical.metadataBytes());
    for (int index = 0; index < work.memberOperations().size(); index++) {
      int memberArgument = 7 + index * 4;
      arguments[memberArgument] = lowerCapacityMember(arguments[memberArgument]);
    }

    assertThat(administratorCommands.type(key)).isEqualTo("none");
    Map<String, String> before = hashImage(key);
    try (StatefulRedisConnection<byte[], byte[]> connection =
        ownerClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisCommands<byte[], byte[]> commands = connection.sync();
      assertThat(commands.aclWhoami()).isEqualTo(OWNER_PRINCIPAL);
      String loadedSha = commands.scriptLoad(script);
      Long refused =
          commands.evalsha(loadedSha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments);
      assertThat(refused).isEqualTo(-2L);
    }
    assertThat(administratorCommands.type(key)).isEqualTo("none");
    assertThat(hashImage(key)).isEqualTo(before);
    assertThat(statusesRemainPending(fixture.dsl())).isTrue();
  }

  private Fixture fixture(boolean includeReleasedReservation) {
    return fixture(includeReleasedReservation, 1);
  }

  private Fixture fixture(boolean includeReleasedReservation, int activeBindingCount) {
    if (activeBindingCount < 1 || activeBindingCount > 2) {
      throw new IllegalArgumentException("Test fixtures support one or two active bindings");
    }
    TestSchema testSchema = newTestSchemaAtVersion("gs_issuer_physical_", "1030");
    schemas.add(testSchema);
    establishActualLegacyDisposition(testSchema.dsl());
    CanonicalGameplayBindingIdentity active =
        CanonicalGameplayBindingInventoryRepositoryIntegrationTest.identity(
            UUID.randomUUID().toString());
    CanonicalGameplayBindingInventoryRepositoryIntegrationTest.seedActiveSource(
        testSchema.dsl(), active);
    BigInteger partitionId = active.bindingRef().partitionId(PARTITION_COUNT);
    for (int index = 1; index < activeBindingCount; index++) {
      CanonicalGameplayBindingIdentity samePartition =
          seedSamePartitionActiveBinding(testSchema.dsl(), partitionId);
      assertThat(samePartition.bindingRef().partitionId(PARTITION_COUNT)).isEqualTo(partitionId);
    }
    if (includeReleasedReservation)
      insertReleasedReservation(testSchema.dsl(), active, partitionId);

    UUID requestId = UUID.randomUUID();
    var source = sourceCheckpoint(BigInteger.valueOf(7));
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl())
            .beginInitialBootstrapIntent(requestId, descriptor(), source);
    migrateRemaining(testSchema);
    var plan =
        new CanonicalGameplayIssuerPartitionBuildRepository(testSchema.dsl())
            .prepareInitialBuildPlan(intent.operationId(), requestId);
    assertThat(plan.state()).isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED);
    return new Fixture(testSchema, active, intent, requestId, source, partitionId);
  }

  private Fixture fixtureWithPreparedCandidate() {
    TestSchema testSchema = newTestSchemaAtVersion("gs_issuer_prepared_physical_", "1030");
    schemas.add(testSchema);
    establishActualLegacyDisposition(testSchema.dsl());
    CanonicalGameplayBindingIdentity active =
        CanonicalGameplayBindingInventoryRepositoryIntegrationTest.identity(
            UUID.randomUUID().toString());
    CanonicalGameplayBindingInventoryRepositoryIntegrationTest.seedActiveSource(
        testSchema.dsl(), active);
    BigInteger partitionId = active.bindingRef().partitionId(PARTITION_COUNT);
    CanonicalGameplayBindingIdentity candidate = preparedCandidateIdentity(partitionId);
    var reservationEvidence =
        new CanonicalIssuerReservationFenceEvidence(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            BigInteger.valueOf(13),
            BigInteger.valueOf(17),
            BigInteger.valueOf(7));
    new CanonicalGameplayBindingInventoryRepository(testSchema.dsl())
        .prepare(
            new CanonicalGameplayBindingTransitionRequest(
                UUID.randomUUID(), UUID.randomUUID(), candidate, reservationEvidence, null, null));

    UUID requestId = UUID.randomUUID();
    var source = sourceCheckpoint(BigInteger.valueOf(7));
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl())
            .beginInitialBootstrapIntent(requestId, descriptor(), source);
    migrateRemaining(testSchema);
    var plan =
        new CanonicalGameplayIssuerPartitionBuildRepository(testSchema.dsl())
            .prepareInitialBuildPlan(intent.operationId(), requestId);
    assertThat(plan.state()).isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED);
    return new Fixture(testSchema, active, intent, requestId, source, partitionId);
  }

  private static CanonicalGameplayBindingIdentity preparedCandidateIdentity(
      BigInteger targetPartition) {
    for (int candidate = 0; candidate < 10_000; candidate++) {
      String seed = "prepared-physical-candidate-" + candidate;
      CanonicalGameplayBindingIdentity identity =
          new CanonicalGameplayBindingIdentity(
              namedUuid(seed + "-account"),
              namedUuid(seed + "-tenant"),
              namedUuid(seed + "-namespace"),
              namedUuid(seed + "-character"),
              "SHARED",
              namedUuid(seed + "-game"),
              90_000L + candidate,
              namedUuid(seed + "-session").toString(),
              namedUuid(seed + "-region"),
              BigInteger.valueOf(12),
              ISSUER,
              BigInteger.valueOf(7),
              LAYOUT_VERSION,
              PARTITION_COUNT,
              PARTITION_CAPACITY);
      if (identity.bindingRef().partitionId(PARTITION_COUNT).equals(targetPartition)) {
        return identity;
      }
    }
    throw new IllegalStateException(
        "Could not create a prepared candidate in the target partition");
  }

  private static UUID namedUuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private CanonicalGameplayIssuerBootstrapPartitionWork partitionWork(
      Fixture fixture, BigInteger partitionId) {
    return new CanonicalGameplayIssuerBootstrapMemberOperationRepository(fixture.dsl())
        .preparePartitionWork(
            fixture.intent().operationId(),
            fixture.requestId(),
            partitionId,
            fixture.currentAccountCheckpoint(),
            stableIncarnation("physical-owner"));
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner owner(
      CanonicalGameplayIssuerBootstrapPartitionWork work,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource source) {
    return new CanonicalGameplayIssuerBootstrapPartitionRedisOwner(
        ownerClient,
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayIssuerBootstrapPartitionRedisScriptContribution())),
        source);
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner owner(
      CanonicalGameplayIssuerBootstrapPartitionWork work,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState source) {
    return owner(work, () -> source);
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState(
      Fixture fixture, CanonicalGameplayIssuerBootstrapPartitionWork work) {
    return new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
        fixture.intent().sourceCheckpoint(), work.incarnation());
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor
      descriptor() {
    String bytes =
        "issuer-layout-descriptor/v1|"
            + ISSUER
            + "|"
            + LAYOUT_VERSION
            + "|"
            + PARTITION_COUNT
            + "|"
            + PARTITION_CAPACITY
            + "|"
            + CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY;
    return new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor(
        ISSUER,
        LAYOUT_VERSION,
        PARTITION_COUNT,
        PARTITION_CAPACITY,
        CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY,
        bytes.getBytes(StandardCharsets.UTF_8));
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
    byte[] exactEvent = event.canonicalJsonUtf8();
    return new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint(
        ISSUER, generation, exactEvent, exactEvent);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
      alternateTrustedCheckpoint(BigInteger generation) {
    return sourceCheckpoint(generation);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint
      alternateCheckpointShape(BigInteger generation) {
    var trusted = alternateTrustedCheckpoint(generation);
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

  private static CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation
      stableIncarnation(String seed) {
    String digest = digest(seed.getBytes(StandardCharsets.UTF_8));
    return new CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation(
        "controlled-attestor-" + seed,
        digest,
        targetDeploymentEvidence(seed, digest),
        digest,
        digest);
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
      targetDeploymentEvidence(String seed, String digest) {
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

  private static byte[] encodeWith(
      CanonicalGameplayIssuerMemberV2Value base,
      CanonicalIssuerPartitionReservation reservation,
      CanonicalGameplayIssuerMemberV2Value.Anchor anchor,
      CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation memberOperation) {
    return CanonicalGameplayIssuerMemberV2Codec.encode(
        new CanonicalGameplayIssuerMemberV2Value(
            base.owner(),
            base.bindingGeneration(),
            reservation,
            anchor,
            memberOperation,
            base.issuerControlEpoch(),
            base.layoutDescriptor(),
            base.sourceCheckpoint(),
            base.incarnation()));
  }

  private static byte[] encodeNewerBinding(CanonicalGameplayIssuerMemberV2Value original) {
    BigInteger nextGeneration = original.bindingGeneration().add(BigInteger.ONE);
    CanonicalIssuerPartitionReservation nextReservation =
        new CanonicalIssuerPartitionReservation(
            UUID.randomUUID(),
            original.owner(),
            nextGeneration,
            UUID.randomUUID(),
            original.reservation().partitionId(),
            CanonicalIssuerPartitionReservation.Lifecycle.BOUND,
            UUID.randomUUID(),
            original.reservation().issuerCoverageOperationId(),
            original.reservation().issuerCoverageOperationFence(),
            original.reservation().coverageFence(),
            original.reservation().inventorySnapshotRevision(),
            original.reservation().inventoryRevision().add(BigInteger.ONE));
    return CanonicalGameplayIssuerMemberV2Codec.encode(
        new CanonicalGameplayIssuerMemberV2Value(
            original.owner(),
            nextGeneration,
            nextReservation,
            original.anchor(),
            new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
                UUID.randomUUID(),
                original.memberOperation().operationFence().add(BigInteger.ONE),
                original.memberOperation().coverageGeneration().add(BigInteger.ONE)),
            original.issuerControlEpoch(),
            original.layoutDescriptor(),
            original.sourceCheckpoint(),
            original.incarnation()));
  }

  private static CanonicalIssuerPartitionReservation withReservation(
      CanonicalIssuerPartitionReservation original,
      UUID reservationId,
      CanonicalIssuerPartitionReservation.Lifecycle lifecycle) {
    return new CanonicalIssuerPartitionReservation(
        reservationId,
        original.owner(),
        original.bindingGeneration(),
        original.transitionId(),
        original.partitionId(),
        lifecycle,
        original.reservationFence(),
        original.issuerCoverageOperationId(),
        original.issuerCoverageOperationFence(),
        original.coverageFence(),
        original.inventorySnapshotRevision(),
        original.inventoryRevision());
  }

  private static byte[][] scriptArguments(
      CanonicalGameplayIssuerBootstrapPartitionWork work,
      CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.PhysicalFieldSet physical) {
    var intent = work.buildPlan().bootstrapIntent();
    byte[][] arguments = new byte[6 + work.memberOperations().size() * 4][];
    arguments[0] = ascii(intent.issuerId());
    arguments[1] = ascii(work.partitionId().toString());
    arguments[2] = ascii(intent.layoutDescriptor().partitionCount().toString());
    arguments[3] = ascii(intent.layoutDescriptor().partitionCapacity().toString());
    arguments[4] = physical.metadataBytes();
    arguments[5] = ascii(Integer.toString(work.memberOperations().size()));
    int offset = 6;
    for (CanonicalGameplayIssuerBootstrapMemberOperation operation : work.memberOperations()) {
      arguments[offset++] = operation.expectedMemberValue().owner().bindingRef().bytes();
      arguments[offset++] = operation.exactMemberValueBytes();
      arguments[offset++] =
          ascii(
              CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                  operation.expectedMemberValue().reservation().reservationId()));
      arguments[offset++] = ascii(operation.expectedBindingPresent() ? "1" : "0");
    }
    return arguments;
  }

  private static byte[] key(Fixture fixture, BigInteger partitionId) {
    return CanonicalGameplayIssuerRedisKeys.forPartition(
            fixture.intent().layoutDescriptor(), partitionId)
        .getBytes(StandardCharsets.US_ASCII);
  }

  private void insertReleasedReservation(
      DSLContext dsl, CanonicalGameplayBindingIdentity template, BigInteger targetPartition) {
    UUID sessionId = sessionUuidForPartition(template, targetPartition);
    UUID reservationId = UUID.randomUUID();
    UUID transitionId = UUID.randomUUID();
    UUID reservationFence = UUID.randomUUID();
    UUID originalCoverageOperationId = UUID.randomUUID();
    byte[] bindingRef =
        CanonicalGameplayBindingRef.of(
                template.tenantId().toString(),
                template.gameInstanceId().toString(),
                sessionId.toString())
            .bytes();
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
            + " VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " 'RELEASED', ?, ?, 17, 19, 7, 8)",
        reservationId,
        bindingRef,
        template.accountId(),
        template.tenantId(),
        template.gameInstanceId(),
        template.runtimeGameInstanceId(),
        sessionId.toString(),
        transitionId,
        ISSUER,
        new BigDecimal(template.issuerAuthGeneration()),
        template.playableStateNamespaceId(),
        TERMINAL_CHARACTER_ID,
        template.playableStateScope(),
        template.regionId(),
        new BigDecimal(template.regionEpoch()),
        new BigDecimal(targetPartition),
        new BigDecimal(LAYOUT_VERSION),
        new BigDecimal(PARTITION_COUNT),
        new BigDecimal(PARTITION_CAPACITY),
        reservationFence,
        originalCoverageOperationId);
  }

  private static UUID sessionUuidForPartition(
      CanonicalGameplayBindingIdentity template, BigInteger targetPartition) {
    for (int candidate = 1; candidate <= 10_000; candidate++) {
      UUID session =
          UUID.nameUUIDFromBytes(
              ("physical-release-session-" + candidate).getBytes(StandardCharsets.UTF_8));
      byte[] ref =
          CanonicalGameplayBindingRef.of(
                  template.tenantId().toString(),
                  template.gameInstanceId().toString(),
                  session.toString())
              .bytes();
      if (CanonicalGameplayBindingRef.fromExactBytes(ref)
              .partitionId(PARTITION_COUNT)
              .equals(targetPartition)
          && !session.toString().equals(template.sessionId())) {
        return session;
      }
    }
    throw new IllegalStateException("Could not create an isolated released reservation field");
  }

  private static byte[] resource(String name) throws Exception {
    try (var input =
        CanonicalGameplayIssuerBootstrapPartitionRedisOwnerIntegrationTest.class
            .getClassLoader()
            .getResourceAsStream(name)) {
      if (input == null) throw new IllegalStateException("Redis script resource is missing");
      return input.readAllBytes();
    }
  }

  private Map<String, String> hashImage(byte[] key) {
    Map<byte[], byte[]> raw = administratorCommands.hgetall(key);
    Map<String, String> image = new LinkedHashMap<>();
    raw.forEach(
        (field, value) ->
            image.put(
                Base64.getEncoder().encodeToString(field),
                Base64.getEncoder().encodeToString(value)));
    return image;
  }

  private static byte[] lowerCapacityMetadata(byte[] exactMetadata) {
    return lowerCapacityJson(exactMetadata);
  }

  private static byte[] lowerCapacityMember(byte[] exactMemberValue) {
    return lowerCapacityJson(exactMemberValue);
  }

  private static byte[] lowerCapacityJson(byte[] exactJson) {
    String original = new String(exactJson, StandardCharsets.UTF_8);
    String fullCapacity = "\"partitionCapacity\":\"" + PARTITION_CAPACITY + "\"";
    String reducedCapacity = "\"partitionCapacity\":\"1\"";
    if (!original.contains(fullCapacity)) {
      throw new IllegalStateException(
          "Controlled physical input has no exact partition capacity field");
    }
    return original.replace(fullCapacity, reducedCapacity).getBytes(StandardCharsets.UTF_8);
  }

  private static CanonicalGameplayBindingIdentity seedSamePartitionActiveBinding(
      DSLContext dsl, BigInteger partitionId) {
    for (int candidate = 0; candidate < 10_000; candidate++) {
      CanonicalGameplayBindingIdentity identity =
          new CanonicalGameplayBindingIdentity(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              "SHARED",
              UUID.randomUUID(),
              90_000L + candidate,
              UUID.randomUUID().toString(),
              UUID.randomUUID(),
              BigInteger.valueOf(12),
              ISSUER,
              BigInteger.valueOf(7),
              LAYOUT_VERSION,
              PARTITION_COUNT,
              PARTITION_CAPACITY);
      if (identity.bindingRef().partitionId(PARTITION_COUNT).equals(partitionId)) {
        insertAdditionalActiveSource(dsl, identity);
        return identity;
      }
    }
    throw new IllegalStateException("Could not seed an active binding in the controlled partition");
  }

  private static void insertAdditionalActiveSource(
      DSLContext dsl, CanonicalGameplayBindingIdentity identity) {
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

  private static boolean statusesRemainPending(DSLContext dsl) {
    var operationsRow =
        dsl.fetchOne(
            "SELECT count(*) FROM game_session_canonical_issuer_bootstrap_member_operation");
    var pendingRow =
        dsl.fetchOne(
            "SELECT count(*) FROM game_session_canonical_issuer_bootstrap_member_operation"
                + " WHERE member_status = 'PENDING_PHYSICAL_PROOF'");
    if (operationsRow == null || pendingRow == null) return false;
    Long operations = operationsRow.get(0, Long.class);
    Long pending = pendingRow.get(0, Long.class);
    return operations != null && pending != null && operations.equals(pending);
  }

  private static void establishActualLegacyDisposition(DSLContext dsl) {
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

  private TestSchema newTestSchemaAtVersion(String prefix, String version) {
    String schema = prefix + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion(version))
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

  private DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = POSTGRES.getJdbcUrl();
    dataSource.setUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema);
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  private void dropSchema(String schema) {
    try (Connection connection = dataSource("public").getConnection()) {
      DSL.using(connection, SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Could not drop the owned issuer physical test schema", failure);
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

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private record Fixture(
      TestSchema schema,
      CanonicalGameplayBindingIdentity activeBinding,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent,
      UUID requestId,
      CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
          currentAccountCheckpoint,
      BigInteger partitionId) {
    DSLContext dsl() {
      return schema.dsl();
    }
  }

  private record TestSchema(String schema, DriverManagerDataSource dataSource, DSLContext dsl) {}
}
