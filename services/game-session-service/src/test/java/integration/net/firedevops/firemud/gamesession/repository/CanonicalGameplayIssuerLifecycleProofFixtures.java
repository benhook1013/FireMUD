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
import io.lettuce.core.protocol.ProtocolVersion;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared PostgreSQL/Redis setup and owner helpers for the separate local-capacity and retirement
 * proofs.
 */
@SuppressWarnings("resource")
abstract class CanonicalGameplayIssuerLifecycleProofFixtures {
  protected static final String OWNER_PRINCIPAL = "gamesession_coord_app";
  protected static final String ISSUER_KEY_PREFIX = "session:game:index:issuer:";
  protected static final BigInteger PARTITION_CAPACITY = BigInteger.valueOf(256);
  protected static final UUID NIL_UUID = new UUID(0L, 0L);

  protected abstract PostgreSQLContainer<?> postgres();

  protected abstract GenericContainer<?> redis();

  protected RedisClient administrator;
  protected RedisClient issuerOwner;
  protected StatefulRedisConnection<byte[], byte[]> administratorConnection;
  protected RedisCommands<byte[], byte[]> administratorCommands;

  @BeforeEach
  void setUp() {
    String password = UUID.randomUUID().toString();
    administrator = createRedisClient(null, null);
    administratorConnection = administrator.connect(ByteArrayCodec.INSTANCE);
    administratorCommands = administratorConnection.sync();
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
            .addCommand(CommandType.HGET)
            .addCommand(CommandType.HLEN)
            .addCommand(CommandType.HGETALL)
            .addCommand(CommandType.HSET)
            .addCommand(CommandType.HDEL));
    issuerOwner = createRedisClient(OWNER_PRINCIPAL, password);
  }

  @AfterEach
  void tearDown() {
    if (administratorConnection != null) administratorConnection.close();
    if (issuerOwner != null) issuerOwner.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (administrator != null) administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
  }

  protected RedisClient createRedisClient(String username, String password) {
    RedisURI.Builder builder =
        RedisURI.Builder.redis(redis().getHost(), redis().getMappedPort(6379));
    if (username != null) builder.withAuthentication(username, password);
    RedisURI uri = builder.build();
    uri.setTimeout(Duration.ofSeconds(5));
    RedisClient client = RedisClient.create(uri);
    client.setOptions(
        ClientOptions.builder()
            .autoReconnect(false)
            .protocolVersion(ProtocolVersion.RESP2)
            .build());
    return client;
  }

  protected CanonicalGameplayAdmissionAbortCompositionFixture newFixture() {
    return newFixture(controlledCandidate(), 0);
  }

  protected CanonicalGameplayAdmissionAbortCompositionFixture newFixture(
      BigInteger layoutVersion, BigInteger partitionCount, int seedBootstrapMemberCount) {
    return newFixture(controlledCandidate(layoutVersion, partitionCount), seedBootstrapMemberCount);
  }

  protected CanonicalGameplayAdmissionAbortCompositionFixture newFixture(
      BigInteger layoutVersion,
      BigInteger partitionCount,
      BigInteger partitionCapacity,
      int seedBootstrapMemberCount) {
    return newFixture(
        controlledCandidate(layoutVersion, partitionCount, partitionCapacity),
        seedBootstrapMemberCount);
  }

  protected CanonicalGameplayAdmissionAbortCompositionFixture newFixture(
      Candidate candidate, int seedBootstrapMemberCount) {
    DriverManagerDataSource accountDataSource = new DriverManagerDataSource();
    accountDataSource.setDriverClassName("org.postgresql.Driver");
    String postgresJdbcUrl = postgres().getJdbcUrl();
    accountDataSource.setUrl(
        postgresJdbcUrl + (postgresJdbcUrl.contains("?") ? "&" : "?") + "currentSchema=public");
    accountDataSource.setUsername(postgres().getUsername());
    accountDataSource.setPassword(postgres().getPassword());
    return new CanonicalGameplayAdmissionAbortCompositionFixture(
        accountDataSource, candidate, issuerOwner, seedBootstrapMemberCount);
  }

  protected static Candidate controlledCandidate() {
    return controlledCandidate(BigInteger.valueOf(3), BigInteger.ONE);
  }

  protected static Candidate controlledCandidate(
      BigInteger layoutVersion, BigInteger partitionCount) {
    return controlledCandidate(layoutVersion, partitionCount, PARTITION_CAPACITY);
  }

  protected static Candidate controlledCandidate(
      BigInteger layoutVersion, BigInteger partitionCount, BigInteger partitionCapacity) {
    return new Candidate(
        uuid("retirement-account"),
        uuid("retirement-tenant"),
        uuid("retirement-playable-state"),
        uuid("retirement-character"),
        "SHARED",
        uuid("retirement-game"),
        1L,
        uuid("retirement-session"),
        uuid("retirement-region"),
        BigInteger.ONE,
        GameSessionAccountDelegationProfile.ISSUER,
        BigInteger.ONE,
        layoutVersion,
        partitionCount,
        partitionCapacity);
  }

  protected static CanonicalGameplayBindingIdentity otherAccountInPartition(
      CanonicalGameplayBindingIdentity template, BigInteger partitionId) {
    CanonicalGameplayBindingIdentity candidate;
    do {
      candidate =
          new CanonicalGameplayBindingIdentity(
              UUID.randomUUID(),
              template.tenantId(),
              template.playableStateNamespaceId(),
              UUID.randomUUID(),
              template.playableStateScope(),
              template.gameInstanceId(),
              template.runtimeGameInstanceId(),
              UUID.randomUUID().toString(),
              template.regionId(),
              template.regionEpoch(),
              template.issuerId(),
              template.issuerAuthGeneration(),
              template.issuerIndexLayoutVersion(),
              template.issuerIndexPartitionCount(),
              template.issuerIndexPartitionCapacity());
    } while (!candidate
        .bindingRef()
        .partitionId(template.issuerIndexPartitionCount())
        .equals(partitionId));
    return candidate;
  }

  protected void assertMissingRevisionRefusal(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture,
      byte[] partitionKey,
      CanonicalGameplayIssuerPartitionMutation mutation,
      String ownerTable,
      String ownerIdColumn,
      UUID ownerOperationId,
      LuaInvocation exactInvocation,
      Runnable ordinaryOwnerCall) {
    byte[] beforeFault = physicalFieldSet(partitionKey);
    assertThat(decodeFields(beforeFault))
        .containsKey(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD);
    String pendingCustodyBefore =
        pendingMutationCustodyImage(
            fixture.dsl(), mutation, ownerTable, ownerIdColumn, ownerOperationId);

    byte[] revisionField =
        CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD.getBytes(
            StandardCharsets.US_ASCII);
    byte[][] revisionFields = {revisionField};
    assertThat(administratorCommands.hdel(partitionKey, revisionFields)).isEqualTo(1L);
    byte[] afterInjectedAbsence = physicalFieldSet(partitionKey);
    assertThat(decodeFields(afterInjectedAbsence))
        .doesNotContainKey(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD);
    assertThat(
            pendingMutationCustodyImage(
                fixture.dsl(), mutation, ownerTable, ownerIdColumn, ownerOperationId))
        .isEqualTo(pendingCustodyBefore);

    assertThat(evaluate(exactInvocation)).isNegative();
    assertThat(physicalFieldSet(partitionKey)).isEqualTo(afterInjectedAbsence);
    assertThatThrownBy(ordinaryOwnerCall::run).isInstanceOf(IllegalStateException.class);
    assertThat(physicalFieldSet(partitionKey)).isEqualTo(afterInjectedAbsence);
    assertThat(administratorCommands.hget(partitionKey, revisionField)).isNull();
    assertThat(
            pendingMutationCustodyImage(
                fixture.dsl(), mutation, ownerTable, ownerIdColumn, ownerOperationId))
        .isEqualTo(pendingCustodyBefore);
  }

  protected static String pendingMutationCustodyImage(
      DSLContext dsl,
      CanonicalGameplayIssuerPartitionMutation mutation,
      String ownerTable,
      String ownerIdColumn,
      UUID ownerOperationId) {
    String sql =
        "SELECT jsonb_build_object("
            + "'head', (SELECT to_jsonb(head) FROM "
            + CanonicalGameplayIssuerPartitionMutationRepository.HEAD_TABLE
            + " head WHERE head.issuer_id = ? AND head.partition_id = ?),"
            + "'mutation', (SELECT to_jsonb(mutation) FROM "
            + "game_session_canonical_issuer_partition_mutation"
            + " mutation WHERE mutation.mutation_id = ?),"
            + "'owner', (SELECT to_jsonb(owner_row) FROM "
            + ownerTable
            + " owner_row WHERE owner_row."
            + ownerIdColumn
            + " = ?))::text";
    return dsl.fetchSingle(
            sql,
            GameSessionAccountDelegationProfile.ISSUER,
            mutation.partitionId(),
            mutation.mutationId(),
            ownerOperationId)
        .get(0, String.class);
  }

  protected static String localOperationImage(DSLContext dsl, UUID operationId) {
    return dsl.fetchSingle(
            "SELECT to_jsonb(operation)::text FROM"
                + " game_session_canonical_issuer_binding_local_operation operation"
                + " WHERE operation.operation_id = ?",
            operationId)
        .get(0, String.class);
  }

  protected static String partitionHeadImage(DSLContext dsl, BigInteger partitionId) {
    return dsl.fetchSingle(
            "SELECT to_jsonb(head)::text FROM "
                + CanonicalGameplayIssuerPartitionMutationRepository.HEAD_TABLE
                + " head WHERE head.issuer_id = ? AND head.partition_id = ?",
            GameSessionAccountDelegationProfile.ISSUER,
            partitionId)
        .get(0, String.class);
  }

  protected void assertOuterBootstrapRetryIsReadOnly(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture, byte[] partitionKey) {
    byte[] physicalBefore = physicalFieldSet(partitionKey);
    String revisionBefore = partitionHeadImage(fixture.dsl(), fixture.originalPartitionId());
    String anchorAndProofsBefore = anchorAndPartitionProofImage(fixture.dsl());
    byte[] originalAnchorBytes =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT exact_anchor_bytes FROM"
                    + " game_session_canonical_issuer_binding_local_operation"
                    + " WHERE operation_id = ?",
                fixture.originalOperationId())
            .get(0, byte[].class);

    var retriedAnchor = fixture.retryOriginalIssuerBootstrap();

    assertThat(retriedAnchor.exactAnchorBytes()).containsExactly(originalAnchorBytes);
    assertThat(physicalFieldSet(partitionKey)).isEqualTo(physicalBefore);
    assertThat(partitionHeadImage(fixture.dsl(), fixture.originalPartitionId()))
        .isEqualTo(revisionBefore);
    assertThat(anchorAndPartitionProofImage(fixture.dsl())).isEqualTo(anchorAndProofsBefore);
  }

  protected static String anchorAndPartitionProofImage(DSLContext dsl) {
    return dsl.fetchSingle(
            "SELECT jsonb_build_object("
                + "'anchors', COALESCE((SELECT jsonb_agg(to_jsonb(anchor)"
                + " ORDER BY anchor.issuer_id) FROM"
                + " game_session_canonical_issuer_installed_anchor anchor), '[]'::jsonb),"
                + "'partitionProofs', COALESCE((SELECT jsonb_agg(to_jsonb(proof)"
                + " ORDER BY proof.issuer_id, proof.partition_id) FROM"
                + " game_session_canonical_issuer_bootstrap_partition_proof proof), '[]'::jsonb)"
                + ")::text")
        .get(0, String.class);
  }

  protected static UUID insertUnaccountedCurrentReservation(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture,
      String sourceSeed,
      BigInteger partitionId) {
    // This row is deliberate run-owned SQL drift: it has no transition or local owner operation.
    // The ordinary projection must detect that missing accounting and make no Redis change.
    var source = fixture.otherOperation(sourceSeed).memberValue();
    var owner = source.owner();
    var reservation = source.reservation();
    String sessionId;
    CanonicalGameplayBindingRef bindingRef;
    do {
      sessionId = UUID.randomUUID().toString();
      bindingRef =
          CanonicalGameplayBindingRef.of(owner.tenantId(), owner.gameInstanceId(), sessionId);
    } while (!bindingRef.partitionId(owner.issuerIndexPartitionCount()).equals(partitionId));
    UUID reservationId = UUID.randomUUID();
    fixture
        .dsl()
        .execute(
            "INSERT INTO game_session_canonical_issuer_partition_reservation"
                + " (reservation_id, binding_ref, account_id, tenant_id, game_instance_id,"
                + " runtime_game_instance_id, session_id, binding_generation, transition_id, issuer_id,"
                + " issuer_auth_generation, playable_state_namespace_id, character_id,"
                + " playable_state_scope, region_id, region_epoch, partition_id,"
                + " issuer_index_layout_version, issuer_index_partition_count,"
                + " issuer_index_partition_capacity, lifecycle, reservation_fence,"
                + " issuer_coverage_operation_id, issuer_coverage_operation_fence, coverage_fence,"
                + " inventory_snapshot_revision, inventory_revision)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?)",
            reservationId,
            bindingRef.bytes(),
            owner.accountId(),
            owner.tenantId(),
            owner.gameInstanceId(),
            owner.runtimeGameInstanceId(),
            sessionId,
            source.bindingGeneration(),
            UUID.randomUUID(),
            owner.issuerId(),
            owner.issuerAuthGeneration(),
            owner.playableStateNamespaceId(),
            UUID.randomUUID(),
            owner.playableStateScope(),
            owner.regionId(),
            owner.regionEpoch(),
            partitionId,
            owner.issuerIndexLayoutVersion(),
            owner.issuerIndexPartitionCount(),
            owner.issuerIndexPartitionCapacity(),
            "RESERVED",
            UUID.randomUUID(),
            UUID.randomUUID(),
            reservation.issuerCoverageOperationFence(),
            reservation.coverageFence(),
            reservation.inventorySnapshotRevision(),
            reservation.inventoryRevision());
    return reservationId;
  }

  protected static String reservationImage(DSLContext dsl, UUID reservationId) {
    return dsl.fetchSingle(
            "SELECT to_jsonb(reservation)::text FROM"
                + " game_session_canonical_issuer_partition_reservation reservation"
                + " WHERE reservation.reservation_id = ?",
            reservationId)
        .get(0, String.class);
  }

  protected static CanonicalGameplayIssuerBindingLocalReleaseOperation
      completeReleaseAfterLostReply(
          CanonicalGameplayAdmissionAbortCompositionFixture fixture, String seed) {
    var pending =
        seed == null ? fixture.prepareIssuerRelease() : fixture.prepareIssuerRelease(seed);
    assertThatThrownBy(() -> fixture.projectIssuerReleaseAndLoseReply(pending))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("controlled lost issuer release reply");
    var resumed =
        seed == null ? fixture.prepareIssuerRelease() : fixture.prepareIssuerRelease(seed);
    var recoveredReadback = fixture.projectIssuerRelease(resumed);
    assertThat(recoveredReadback.scriptOutcome()).isEqualTo(2L);
    return fixture.completeIssuerRelease(resumed, recoveredReadback);
  }

  protected static String lifetimeSeed(int index) {
    return "lifetime-" + index;
  }

  protected static LuaInvocation addInvocation(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture,
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work)
      throws IOException, ReflectiveOperationException {
    return new LuaInvocation(
        readScript(CanonicalGameplayIssuerBindingLocalRedisScriptContribution.RESOURCE_PATH),
        fixture.issuerPartitionKey(),
        scriptArguments(
            CanonicalGameplayIssuerBindingLocalRedisOwner.class,
            new Class<?>[] {
              CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork.class
            },
            work));
  }

  protected static LuaInvocation releaseInvocation(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture,
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation)
      throws IOException, ReflectiveOperationException {
    var work = fixture.readPreparedPartitionWork(operation.originalOperation());
    return new LuaInvocation(
        readScript(CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution.RESOURCE_PATH),
        fixture.issuerPartitionKey(),
        scriptArguments(
            CanonicalGameplayIssuerBindingLocalReleaseRedisOwner.class,
            new Class<?>[] {
              CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork.class,
              CanonicalGameplayIssuerBindingLocalReleaseOperation.class
            },
            work,
            operation));
  }

  protected static byte[][] scriptArguments(
      Class<?> owner, Class<?>[] parameterTypes, Object... arguments)
      throws ReflectiveOperationException {
    Method method = owner.getDeclaredMethod("scriptArguments", parameterTypes);
    if (!method.trySetAccessible()) {
      throw new IllegalStateException("Test could not capture the exact owner Lua arguments");
    }
    try {
      return (byte[][]) method.invoke(null, arguments);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw failure;
    }
  }

  protected static byte[] readScript(String resourcePath) throws IOException {
    try (InputStream input =
        CanonicalGameplayIssuerLifecycleProofFixtures.class
            .getClassLoader()
            .getResourceAsStream(resourcePath)) {
      if (input == null) throw new IOException("Missing Redis Lua resource: " + resourcePath);
      byte[] bytes = input.readAllBytes();
      if (bytes.length == 0) throw new IOException("Empty Redis Lua resource: " + resourcePath);
      return bytes;
    }
  }

  protected Long evaluate(LuaInvocation invocation) {
    return administratorCommands.eval(
        invocation.script(),
        ScriptOutputType.INTEGER,
        new byte[][] {invocation.key()},
        invocation.arguments());
  }

  protected void assertStaleLuaRejectedWithoutWrites(
      LuaInvocation staleInvocation, byte[] partitionKey) {
    byte[] before = physicalFieldSet(partitionKey);
    Long outcome = evaluate(staleInvocation);
    assertThat(outcome).isNegative();
    assertThat(physicalFieldSet(partitionKey)).isEqualTo(before);
  }

  protected byte[] physicalFieldSet(byte[] key) {
    Map<byte[], byte[]> raw = administratorCommands.hgetall(key);
    Map<String, byte[]> fields = new HashMap<>();
    raw.forEach(
        (field, value) -> fields.put(new String(field, StandardCharsets.UTF_8), value.clone()));
    return CanonicalGameplayIssuerBootstrapCoverageRepository.encodeFieldSet(fields);
  }

  protected static Map<String, byte[]> decodeFields(byte[] fieldSet) {
    return CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(fieldSet);
  }

  protected static long capacityFieldCount(byte[] fieldSet) {
    return decodeFields(fieldSet).keySet().stream()
        .filter(
            field ->
                field.startsWith(
                    CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec
                        .RESERVATION_FIELD_PREFIX))
        .count();
  }

  protected static long localOperationCount(DSLContext dsl) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM game_session_canonical_issuer_binding_local_operation")
        .get(0, Long.class);
  }

  protected static long partitionMutationCount(DSLContext dsl) {
    return dsl.fetchSingle("SELECT count(*) FROM game_session_canonical_issuer_partition_mutation")
        .get(0, Long.class);
  }

  protected static long releaseCount(DSLContext dsl, String status) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM game_session_canonical_issuer_binding_local_release_operation"
                + " WHERE status = ?",
            status)
        .get(0, Long.class);
  }

  protected static long releaseProofCount(DSLContext dsl) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM game_session_canonical_issuer_binding_local_release_operation"
                + " WHERE status = 'RELEASED' AND physical_readback_field_set_bytes IS NOT NULL"
                + " AND completion_inventory_revision > source_inventory_revision")
        .get(0, Long.class);
  }

  protected static String durableHistoryImage(DSLContext dsl, UUID excludedTransitionId) {
    String sql =
        "SELECT jsonb_build_object("
            + "'transitions', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.transition_id)"
            + " FROM game_session_canonical_binding_transition entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'inventory', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.binding_ref)"
            + " FROM game_session_canonical_gameplay_binding_inventory entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'reservations', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.reservation_id)"
            + " FROM game_session_canonical_issuer_partition_reservation entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'accountObligations', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.transition_id, entry.obligation_ordinal)"
            + " FROM game_session_canonical_binding_account_index_obligation entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'issuerObligations', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.transition_id, entry.binding_ref)"
            + " FROM game_session_canonical_binding_issuer_index_obligation entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'regionObligations', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.transition_id)"
            + " FROM game_session_canonical_binding_region_bridge_obligation entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'localOperations', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.operation_id)"
            + " FROM game_session_canonical_issuer_binding_local_operation entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'releases', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.operation_id)"
            + " FROM game_session_canonical_issuer_binding_local_release_operation entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'mutations', COALESCE((SELECT jsonb_agg(to_jsonb(mutation) ORDER BY mutation.mutation_id)"
            + " FROM game_session_canonical_issuer_partition_mutation mutation WHERE mutation.owner_operation_id IN ("
            + " SELECT local_operation.operation_id FROM game_session_canonical_issuer_binding_local_operation local_operation WHERE local_operation.transition_id <> ?"
            + " UNION SELECT release_entry.operation_id FROM game_session_canonical_issuer_binding_local_release_operation release_entry WHERE release_entry.transition_id <> ?)), '[]'::jsonb)"
            + ")::text";
    return dsl.fetchSingle(
            sql,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId)
        .get(0, String.class);
  }

  protected static UUID uuid(String seed) {
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
  }

  protected record LuaInvocation(byte[] script, byte[] key, byte[][] arguments) {
    protected LuaInvocation {
      script = script.clone();
      key = key.clone();
      arguments = Arrays.stream(arguments).map(byte[]::clone).toArray(byte[][]::new);
    }

    @Override
    public byte[] script() {
      return script.clone();
    }

    @Override
    public byte[] key() {
      return key.clone();
    }

    @Override
    public byte[][] arguments() {
      return Arrays.stream(arguments).map(byte[]::clone).toArray(byte[][]::new);
    }
  }
}
