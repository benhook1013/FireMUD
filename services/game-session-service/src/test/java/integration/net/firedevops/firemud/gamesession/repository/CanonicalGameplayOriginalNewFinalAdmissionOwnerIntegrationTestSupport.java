package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayCapacityOriginCustodyRedisScriptContribution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@SuppressWarnings("resource")
abstract class CanonicalGameplayOriginalNewFinalAdmissionOwnerIntegrationTestSupport {
  abstract PostgreSQLContainer<?> postgresContainer();

  abstract GenericContainer<?> redisContainer();

  static final Clock CLOCK = Clock.systemUTC();
  static final String PRINCIPAL = "gamesession_coord_app";
  static final int TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS = 128;
  static final long TEST_CAPACITY_EVIDENCE_MAXIMUM_BINDING_BYTES = 128L * 1_048_576L;
  @TempDir Path directory;

  RedisClient administrator;
  RedisClient ownerClient;
  RedisURI ownerUri;
  StatefulRedisConnection<byte[], byte[]> adminConnection;
  RedisCommands<byte[], byte[]> admin;
  CanonicalGameplayAdmissionAbortCompositionFixture fixture;
  CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources sources;
  CanonicalGameplayBindingInventoryRepository inventory;
  CanonicalGameplayTenantGameplayTagRepository tags;
  CanonicalGameplayAdmissionDecision decision;
  CanonicalGameplayOriginalNewCandidateRedisOwner.Installation installation;
  RedisScriptCatalog catalog;
  net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution controlledCapacity;
  CanonicalGameplayRegionOwnerCompositionFixture.ExistingBinding regionBinding;

  @BeforeEach
  void setUp() throws Exception {
    administrator = client(null, null);
    adminConnection = administrator.connect(ByteArrayCodec.INSTANCE);
    admin = adminConnection.sync();
    admin.flushdb();
    String password = UUID.randomUUID().toString();
    admin.aclSetuser(
        PRINCIPAL,
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(password)
            .keyPattern("session:game:*")
            .keyPattern("tick:*")
            .keyPattern("tick-executor-lease:*")
            .addCommand(CommandType.HELLO)
            .addCommand(CommandType.PING)
            .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
            .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
            .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
            .addCommand(CommandType.EVALSHA)
            .addCommand(CommandType.EVALSHA_RO)
            .addCommand(CommandType.TYPE)
            .addCommand(CommandType.PTTL)
            .addCommand(CommandType.GET)
            .addCommand(CommandType.SET)
            .addCommand(CommandType.SADD)
            .addCommand(CommandType.SISMEMBER)
            .addCommand(CommandType.HGET)
            .addCommand(CommandType.HGETALL)
            .addCommand(CommandType.HSET)
            .addCommand(CommandType.HLEN)
            .addCommand(CommandType.PEXPIRE)
            .addCommand(CommandType.TIME));
    ownerUri = redisUri(PRINCIPAL, password);
    ownerClient = RedisClient.create(ownerUri);
    ownerClient.setOptions(ClientOptions.builder().autoReconnect(false).build());
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = postgresContainer().getJdbcUrl();
    dataSource.setUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=public");
    dataSource.setUsername(postgresContainer().getUsername());
    dataSource.setPassword(postgresContainer().getPassword());
    fixture =
        new CanonicalGameplayAdmissionAbortCompositionFixture(dataSource, candidate(), ownerClient);
    inventory = new CanonicalGameplayBindingInventoryRepository(fixture.dsl());
    tags = new CanonicalGameplayTenantGameplayTagRepository(fixture.dsl(), "test", 1);
    catalog =
        RedisScriptCatalog.fromContributions(
            List.of(
                new net.firedevops.firemud.gamesession.service
                    .CanonicalGameplayOriginalNewCandidateRedisScriptContribution(),
                new net.firedevops.firemud.gamesession.service
                    .CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution(),
                new net.firedevops.firemud.gamesession.service
                    .CanonicalGameplayOriginalNewOriginFinalAdmissionRedisScriptContribution(),
                new CanonicalGameplayCapacityOriginCustodyRedisScriptContribution(),
                new net.firedevops.firemud.gamesession.service
                    .CanonicalGameplayOriginalNewAdmittedLogoutRedisScriptContribution(),
                new net.firedevops.firemud.gamesession.service
                    .CanonicalGameplayAccountMemberRepairRedisScriptContribution(),
                new net.firedevops.firemud.gamesession.service
                    .CanonicalGameplayRegionLeaseRedisScriptContribution(),
                new net.firedevops.firemud.gamesession.service
                    .CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution()));
    // Allocate test PKI and start controlled source servers before the short lease starts.
    sources =
        new CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources(
            fixture.dsl(), fixture.originalPreparedDecisionWithControlledAccountLease(), directory);
    decision = fixture.originalPreparedDecisionWithControlledAccountLease();
    installation = candidateOwner(inventory).installOnce(decision);
    fixture.acknowledgeOriginalIssuer();
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (sources != null) sources.close();
    } finally {
      try {
        if (fixture != null) fixture.close();
      } finally {
        if (adminConnection != null) adminConnection.close();
        if (ownerClient != null) ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        if (administrator != null) administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      }
    }
  }

  static void assertGuardRefusal(
      Throwable failure, String sqlState, String constraint, String message) {
    assertThat(failure)
        .rootCause()
        .isInstanceOfSatisfying(
            org.postgresql.util.PSQLException.class,
            postgresFailure -> {
              assertThat(postgresFailure.getSQLState()).isEqualTo(sqlState);
              assertThat(postgresFailure.getServerErrorMessage()).isNotNull();
              assertThat(postgresFailure.getServerErrorMessage().getConstraint())
                  .isEqualTo(constraint);
              assertThat(postgresFailure.getServerErrorMessage().getMessage()).isEqualTo(message);
            });
  }

  void repairAccountMember() {
    new CanonicalGameplayAccountMemberRepairOwner(fixture.dsl(), ownerClient, catalog)
        .repair(fixture.transitionId());
  }

  void bridge() {
    bridge(true);
  }

  void bridge(boolean authorizeCapacity) {
    bridge(
        authorizeCapacity,
        TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS,
        TEST_CAPACITY_EVIDENCE_MAXIMUM_BINDING_BYTES);
  }

  void bridge(boolean authorizeCapacity, int maximumRecords, long maximumBindingBytes) {
    regionBinding =
        CanonicalGameplayRegionOwnerCompositionFixture.installExistingOriginalCandidate(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            fixture.transitionId(),
            sources.request,
            sources.resolver,
            sources.entity);
    assertThat(regionBinding.owner().bridge(regionBinding.installation()).obligation())
        .isEqualTo(regionBinding.acknowledgement().obligation());
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    initializeCapacityEvidenceBudget(assignment.tenantId(), maximumRecords, maximumBindingBytes);
    // Explicit controlled Account content for this native owner component proof. The separate
    // Account composition traverses actual evaluation/commit and is the complete capacity path.
    if (controlledCapacity == null) {
      var source = inventory.readOriginalNewFinalAdmissionSource(decision);
      var controlled =
          CanonicalGameplayOriginalNewFinalAdmissionTestFixture.controlledCapacity(source, CLOCK);
      var binding = source.candidate().bindingIdentity();
      String selectedOwner =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT owner_instance_id FROM game_session_region_runtime_status WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
                  binding.tenantId(),
                  binding.gameInstanceId(),
                  binding.regionId())
              .get(0, String.class);
      controlledCapacity =
          new net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution(
              controlled.request(),
              controlled.reservation(),
              controlled.transitionId(),
              controlled.candidateDigest(),
              selectedOwner,
              controlled.selectedOwnerFence());
      capacityOwner()
          .precharge(
              source,
              controlledCapacity.request(),
              new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
                  controlledCapacity.selectedOwnerId(), controlledCapacity.selectedOwnerFence()),
              deadline());
      if (!authorizeCapacity) return;
      try (var credential =
              new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
          var raw = credential.template();
          var bound = raw.bindCapacityExecution(controlledCapacity)) {
        capacityOwner()
            .authorize(
                source,
                bound,
                controlledAccountOpen(),
                issuerOpen(),
                controlledCapacity,
                deadline());
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }
  }

  void initializeCapacityEvidenceBudget(
      UUID tenantId, int maximumRecords, long maximumBindingBytes) {
    fixture
        .dsl()
        .execute(
            "INSERT INTO game_session_capacity_evidence_budget"
                + " (tenant_id,maximum_records,maximum_binding_bytes) VALUES (?,?,?)"
                + " ON CONFLICT DO NOTHING",
            tenantId,
            maximumRecords,
            maximumBindingBytes);
    fixture
        .dsl()
        .execute(
            "INSERT INTO game_session_region_transfer_history_budget"
                + " (tenant_id,maximum_transfers,maximum_items) VALUES (?,256,1024)"
                + " ON CONFLICT DO NOTHING",
            tenantId);
  }

  net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request anotherAttempt(
      net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request request) {
    return new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request(
            request.identity(),
            UUID.randomUUID(),
            request.capacityRearmFence(),
            request.targetNamespace(),
            request.context(),
            request.operation(),
            request.priorShape(),
            request.requestedShape(),
            request.evidence(),
            request.capacityDelta(),
            null)
        .withDigest();
  }

  CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen() {
    var repository =
        new CanonicalGameplayIssuerAdmissionBarrierRepository(
            fixture.dsl(), tags, fixture.currentIssuerSource());
    return new CanonicalGameplayIssuerAdmissionBarrierRedisOwner(
            ownerClient, catalog, repository, fixture.currentIssuerSource())
        .initializeAndRead(fixture.originalOperationId());
  }

  /**
   * Controlled upstream OPEN record and physical hash, without INITIAL owner/currentness claims.
   */
  CanonicalGameplayAccountInitialOpenProof.CurrentOpen controlledAccountOpen() {
    return controlledAccountOpen(CLOCK);
  }

  CanonicalGameplayAccountInitialOpenProof.CurrentOpen controlledAccountOpen(Clock clock) {
    var binding = decision.candidate();
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    UUID initializer = UUID.randomUUID();
    String incarnation = "sha256:" + "a".repeat(64);
    String key =
        "session:game:account-admission:{"
            + assignment.tenantGameplayTag()
            + "}:"
            + binding.accountId()
            + ":"
            + binding.playableStateNamespaceId();
    Map<String, String> fields =
        Map.of(
            "schema",
            CanonicalGameplayAccountInitialBarrierValue.SCHEMA,
            "state",
            "OPEN",
            "accountId",
            binding.accountId().toString(),
            "tenantId",
            binding.tenantId().toString(),
            "playableStateNamespaceId",
            binding.playableStateNamespaceId().toString(),
            "tenantGameplayTag",
            assignment.tenantGameplayTag(),
            "ownerOperationId",
            initializer.toString(),
            "ownerFenceEpoch",
            "1",
            "incarnationDigest",
            incarnation);
    fields.forEach((field, value) -> admin.hset(ascii(key), ascii(field), ascii(value)));
    long now = clock.millis();
    return new CanonicalGameplayAccountInitialOpenProof.CurrentOpen(
        initializer,
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        binding.accountId(),
        binding.tenantId(),
        binding.playableStateNamespaceId(),
        key,
        BigInteger.ONE,
        BigInteger.ONE,
        incarnation,
        incarnation,
        incarnation,
        now,
        now + 60_000L,
        fields);
  }

  String issuerKey(CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback readback) {
    var value = readback.value();
    return CanonicalGameplayIssuerAdmissionBarrierKey.forTarget(
        value.issuerId(),
        value.tenantId(),
        value.playableStateNamespaceId(),
        value.tenantGameplayTag());
  }

  CanonicalGameplayOriginalNewCandidateRedisOwner candidateOwner(
      CanonicalGameplayBindingInventoryRepository repository) {
    return CanonicalGameplayOriginalNewCandidateRedisOwnerFactory.create(
        ownerClient, catalog, repository, tags);
  }

  net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus replaceRegionOwner(
      String successor) {
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl());
    var current = repository.find(original.source).orElseThrow();
    // Exercise the actual serialized persistence boundary. The caller's replacement Redis grant
    // is controlled here; this is not a proof of production handoff or external source custody.
    return repository.installOwner(
        original.source,
        current.regionEpoch(),
        current,
        "a".repeat(64),
        "game-session-service",
        successor,
        CLOCK.instant());
  }

  CanonicalGameplayOriginalNewCapacityExecutionOwner capacityOwner() {
    return new CanonicalGameplayOriginalNewCapacityExecutionOwner(
        fixture.dsl(), ownerClient, catalog, CLOCK, tags);
  }

  CanonicalGameplayRegionLeaseOwner.Installation actualTransferHandoff() throws Exception {
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    return CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
        fixture.dsl(),
        ownerClient,
        ownerUri,
        catalog,
        regionBinding.installation(),
        sources.resolver,
        transfer,
        deadline());
  }

  String capacityKey(CanonicalGameplayTenantGameplayTagRepository.Assignment assignment) {
    return "session:game:capacity-execution:{"
        + assignment.tenantGameplayTag()
        + "}:"
        + controlledCapacity.request().admissionAttemptId();
  }

  void commitHistoricalSplitReceipt(
      CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.Readback readback) {
    var sql = fixture.dsl();
    // Model the old receipt-only writer while exercising the actual guarded receipt transaction.
    sql.execute(
        """
        CREATE FUNCTION fixture_preserve_authorized_execution() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          RETURN OLD;
        END;
        $$
        """);
    sql.execute(
        """
        CREATE TRIGGER aaa_fixture_preserve_authorized_execution
        BEFORE UPDATE ON game_session_original_new_capacity_execution
        FOR EACH ROW WHEN (OLD.status = 'AUTHORIZED' AND NEW.status = 'COMMITTED')
        EXECUTE FUNCTION fixture_preserve_authorized_execution()
        """);
    try {
      inventory.commitOriginalNewFinalAdmission(readback);
    } finally {
      sql.execute(
          "DROP TRIGGER aaa_fixture_preserve_authorized_execution ON game_session_original_new_capacity_execution");
      sql.execute("DROP FUNCTION fixture_preserve_authorized_execution()");
    }
    assertThat(
            inventory
                .readOriginalNewFinalAdmissionSource(decision)
                .finalReceipt()
                .orElseThrow()
                .finalSessionSha256())
        .isEqualTo(readback.finalSessionSha256());
    var execution =
        sql.fetchSingle(
            "SELECT status, terminal_readback FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
            readback.execution().digest());
    assertThat(execution.get("status", String.class)).isEqualTo("AUTHORIZED");
    assertThat(execution.get("terminal_readback", byte[].class)).isNull();
  }

  void controlledRemoveServingKeys(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment) {
    var binding = source.candidate().bindingIdentity();
    admin.del(
        ascii(CanonicalGameplaySessionRedisKeys.session(assignment, binding.bindingRef())),
        ascii(CanonicalGameplaySessionRedisKeys.characterIndex(assignment, binding.bindingRef())));
    admin.srem(
        ascii(
            CanonicalGameplaySessionRedisKeys.accountTenantIndex(assignment, binding.bindingRef())),
        ascii(binding.sessionId()));
    admin.srem(
        ascii(CanonicalGameplaySessionRedisKeys.tenantIndex(assignment, binding.bindingRef())),
        ascii(binding.sessionId()));
  }

  ControlledCapacityFinalOwner finalOwner() {
    return new ControlledCapacityFinalOwner();
  }

  final class ControlledCapacityFinalOwner {
    CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.Readback finalizeOriginalNewOnce(
        CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
        CanonicalGameplayOriginalNewAuthenticatedSession.Template template,
        CanonicalGameplayAccountInitialOpenProof.CurrentOpen account,
        CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuer,
        net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution execution,
        long deadline) {
      if (execution == null)
        throw new IllegalStateException(
            "Controlled capacity must be prepared by the native bridge fixture");
      try (var bound = template.bindCapacityExecution(execution)) {
        return new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                ownerClient, catalog, CLOCK, tags)
            .finalizeOriginalNewOnce(source, bound, account, issuer, execution, deadline);
      }
    }
  }

  CanonicalGameplayOriginalNewFinalAdmissionReadRepository reader() {
    return reader(CLOCK);
  }

  CanonicalGameplayOriginalNewFinalAdmissionReadRepository reader(Clock readClock) {
    return new CanonicalGameplayOriginalNewFinalAdmissionReadRepository(
        fixture.dsl(),
        ownerClient,
        new CanonicalGameplayTenantGameplayTagRepository(fixture.dsl(), "test", 1),
        "test",
        readClock);
  }

  String sqlImage() {
    Map<String, List<String>> rows = new LinkedHashMap<>();
    for (String table :
        List.of(
            "game_session_canonical_binding_transition",
            "game_session_canonical_gameplay_binding_inventory",
            "game_session_canonical_issuer_partition_reservation",
            "game_session_canonical_binding_account_index_obligation",
            "game_session_canonical_binding_issuer_index_obligation",
            "game_session_canonical_binding_region_bridge_obligation",
            "game_session_canonical_issuer_binding_local_acknowledgement",
            "game_session_canonical_account_member_repair_operation",
            "game_session_region_runtime_status",
            "game_session_region_timeline_preparation",
            "game_session_canonical_binding_inventory_clock",
            "game_session_canonical_issuer_layout_bootstrap_control",
            "game_session_canonical_issuer_bootstrap_member_op_counter",
            "game_session_canonical_issuer_bootstrap_coverage_counter",
            "game_session_canonical_issuer_binding_local_ack_gen_counter",
            "game_session_canonical_issuer_admission_epoch_counter",
            "game_session_canonical_account_member_repair_control",
            "game_session_canonical_account_coverage_control",
            "game_session_canonical_tenant_gameplay_tag",
            "game_session_canonical_issuer_admission_initializer",
            "game_session_canonical_issuer_admission_initializer_receipt",
            "game_session_original_new_capacity_execution",
            "game_session_capacity_evidence_budget",
            "game_session_capacity_evidence_charge",
            "game_session_region_owner_transfer",
            "game_session_region_owner_transfer_item")) {
      rows.put(
          table,
          fixture
              .dsl()
              .fetch(
                  "SELECT to_jsonb(t)::text AS image FROM "
                      + table
                      + " t ORDER BY to_jsonb(t)::text")
              .getValues("image", String.class));
    }
    return rows.toString();
  }

  Map<String, String> physicalImage() {
    Map<String, String> image = new java.util.TreeMap<>();
    for (byte[] key : admin.keys("session:game:*")) {
      image.put(
          new String(key, StandardCharsets.US_ASCII),
          java.util.Base64.getEncoder()
              .encodeToString(
                  CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(
                      admin, key)));
    }
    return image;
  }

  static Candidate candidate() {
    return new Candidate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        1L,
        UUID.randomUUID(),
        UUID.randomUUID(),
        BigInteger.ONE,
        GameSessionAccountDelegationProfile.ISSUER,
        BigInteger.ONE,
        BigInteger.ONE,
        BigInteger.ONE,
        BigInteger.valueOf(64));
  }

  RedisClient client(String username, String password) {
    var client = RedisClient.create(redisUri(username, password));
    client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return client;
  }

  RedisURI redisUri(String username, String password) {
    var uri =
        RedisURI.Builder.redis(redisContainer().getHost(), redisContainer().getMappedPort(6379))
            .withTimeout(Duration.ofSeconds(5));
    if (username != null) uri.withAuthentication(username, password);
    return uri.build();
  }

  static long deadline() {
    return CLOCK.millis() + 10_000L;
  }

  static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  CanonicalGameplayCapacityOrigin prepareControlledOrigin(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source)
      throws java.io.IOException {
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    var origin =
        new CanonicalGameplayCapacityOrigin(
            controlledCapacity.request(),
            source.candidate(),
            assignment.tenantGameplayTag(),
            controlledCapacity.selectedOwnerId(),
            controlledCapacity.selectedOwnerFence());
    assertThat(
            new CanonicalGameplayCapacityOriginRepository(fixture.dsl())
                .prepare(origin)
                .insertedByThisCall())
        .isTrue();
    var descriptor =
        catalog.require(CanonicalGameplayCapacityOriginCustodyRedisScriptContribution.SCRIPT_ID);
    try (var input = getClass().getClassLoader().getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw new IllegalStateException("Origin custody script is unavailable");
      new CanonicalGameplayCapacityOriginRedisOwner(
              () -> ownerClient.connect(ByteArrayCodec.INSTANCE),
              () -> TimeUnit.SECONDS.toMillis(5),
              input.readAllBytes(),
              descriptor.sha256())
          .apply(
              origin,
              CanonicalGameplayCapacityOriginRedisOwner.Action.INIT,
              origin.selectedOwnerId(),
              origin.selectedOwnerFence(),
              origin.selectedOwnerId(),
              origin.selectedOwnerFence(),
              CanonicalGameplayCapacityOrigin.State.OPEN,
              deadline());
    }
    return origin;
  }
}
