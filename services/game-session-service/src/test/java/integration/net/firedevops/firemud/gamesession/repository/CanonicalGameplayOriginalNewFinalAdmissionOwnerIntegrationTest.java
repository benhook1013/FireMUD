package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountIndexMember;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIssuerIndexObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Native component composition: actual GS SQL/candidate/account-member/issuer/region/final owners.
 * Account lease, signer/JWKS, physical Account OPEN, World/Entity RPC response DTOs, incarnation,
 * launch and empty legacy cohort are controlled fixtures. Real mTLS clients mint private Entity
 * references; real region owners mint grants and ACKs. This does not exercise Account callbacks,
 * INITIAL protected observation, the full coordinator, external source custody or activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayOriginalNewFinalAdmissionOwnerIntegrationTest {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final String PRINCIPAL = "gamesession_coord_app";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @TempDir Path directory;

  private RedisClient administrator;
  private RedisClient ownerClient;
  private StatefulRedisConnection<byte[], byte[]> adminConnection;
  private RedisCommands<byte[], byte[]> admin;
  private CanonicalGameplayAdmissionAbortCompositionFixture fixture;
  private CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources sources;
  private CanonicalGameplayBindingInventoryRepository inventory;
  private CanonicalGameplayTenantGameplayTagRepository tags;
  private CanonicalGameplayAdmissionDecision decision;
  private CanonicalGameplayOriginalNewCandidateRedisOwner.Installation installation;
  private RedisScriptCatalog catalog;
  private net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution
      controlledCapacity;
  private CanonicalGameplayRegionOwnerCompositionFixture.ExistingBinding regionBinding;

  @Test
  void committedCapacityWithoutExecutionCustodyRemainsIndeterminate() throws Exception {
    repairAccountMember();
    bridge(false);
    var before = physicalImage();
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    org.jooq.impl.DSL.table("game_session_original_new_capacity_execution")))
        .isZero();
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .closeOriginalAttempt(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    org.jooq.impl.DSL.table("game_session_original_new_capacity_execution")))
        .isZero();
    assertThat(physicalImage()).isEqualTo(before);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "false,false",
    "false,true",
    "true,false",
    "true,true"
  })
  void exactAbortedOriginalCompletesSameTransferBeforeAndAfterCapture(
      boolean abortAfterCapture, boolean originalReservationExpired) throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    Clock transferClock =
        originalReservationExpired ? Clock.offset(CLOCK, Duration.ofHours(1)) : CLOCK;
    if (originalReservationExpired)
      assertThat(controlledCapacity.reservation().expiresAt()).isBefore(transferClock.instant());
    var repository =
        new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), transferClock, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture(transferClock)
            .receiver(repository, tags);
    var capacity =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            fixture.dsl(), ownerClient, catalog, transferClock, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), transferClock, repository, capacity, receiver);
    UUID transferId = null;
    if (abortAfterCapture) {
      transferId =
          repository.within(
              transferClock.millis() + 5000L,
              ignored -> repository.begin(original.source, original.status));
      UUID capturedId = transferId;
      var captured =
          repository.within(
              transferClock.millis() + 5000L, ignored -> repository.capture(capturedId));
      assertThat(captured).hasSize(1);
    }
    assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
    byte[] candidate = admin.get(ascii(installation.sessionKey()));
    UUID resumed =
        transfer.advance(original.source, original.status, transferClock.millis() + 5000L);
    if (transferId != null) assertThat(resumed).isEqualTo(transferId);
    String intent =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT intent_digest FROM game_session_region_owner_transfer WHERE transfer_id=?",
                resumed)
            .get(0, String.class);
    // Real expiry of the incumbent Redis lease, followed by actual grant acquisition and protected
    // installation.
    admin.pexpire(ascii(original.grant.key()), 0);
    var installed = installAbortedTransferSuccessor(transfer, original, resumed, transferClock);
    assertThat(installed.ownerInstanceId()).isEqualTo("aborted-original-successor");
    assertThat(installed.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(installed);
    var completed =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT transfer_id,intent_digest,state FROM game_session_region_owner_transfer");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(resumed);
    assertThat(completed.get("intent_digest", String.class)).isEqualTo(intent);
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(
            receiver
                .observeForRegionOwnerTransfer(resumed, 0, transferClock.millis() + 5000L)
                .regionOwnerTransferContext()
                .controlState())
        .isEqualTo("COMPLETED");
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(candidate);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_canonical_binding_transition WHERE transition_id=?",
                    fixture.transitionId())
                .get(0, String.class))
        .isEqualTo("ABORTED");
    assertThatThrownBy(() -> tags.assignmentForTransition(fixture.transitionId()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThatThrownBy(
            () ->
                capacity.readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    String terminalRows = sqlImage();
    assertThatThrownBy(
            () ->
                transfer.advance(original.source, original.status, transferClock.millis() + 5000L))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(terminalRows);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "binding", "ttl"})
  void abortedTransferCannotInstallWithMissingOrChangedRetainedFence(String fault)
      throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    UUID id = transfer.advance(original.source, original.status, deadline());
    byte[] key = ascii(capacityKey(assignment));
    if (fault.equals("missing")) admin.del(java.util.List.of(key).toArray(byte[][]::new));
    else if (fault.equals("binding")) admin.hset(key, ascii("binding"), ascii("changed"));
    else admin.pexpire(key, 60_000L);
    byte[] altered =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key);
    String retained = sqlImage();
    admin.pexpire(ascii(original.grant.key()), 0);
    assertThatThrownBy(() -> installAbortedTransferSuccessor(transfer, original, id, CLOCK))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(original.status);
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state FROM game_session_region_owner_transfer")
                .get(0, String.class))
        .isEqualTo("FROZEN");
    assertThat(sqlImage()).isEqualTo(retained);
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .containsExactly(altered);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void abortedCapturedOriginalRetainsPhysicalClosureBeforeSuccessorInstallation(
      boolean closeBeforeAbort) throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    UUID id =
        repository.within(
            deadline(), ignored -> repository.begin(original.source, original.status));
    var captured = repository.within(deadline(), ignored -> repository.capture(id));
    assertThat(captured).hasSize(1);
    if (closeBeforeAbort)
      capacityOwner()
          .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
    if (!closeBeforeAbort)
      capacityOwner()
          .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var proof =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(proof.disposition()).isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
    byte[] tombstone =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(
            admin,
            ascii(
                capacityKey(tags.assignmentForRetainedCapacityExecution(fixture.transitionId()))));
    assertThat(transfer.advance(original.source, original.status, deadline())).isEqualTo(id);
    admin.pexpire(ascii(original.grant.key()), 0);
    var installed = installAbortedTransferSuccessor(transfer, original, id, CLOCK);
    assertThat(installed.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(
            receiver
                .observeForRegionOwnerTransfer(id, 0, deadline())
                .regionOwnerTransferContext()
                .controlState())
        .isEqualTo("COMPLETED");
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(proof.canonicalReadback());
    assertThat(
            CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(
                admin,
                ascii(
                    capacityKey(
                        tags.assignmentForRetainedCapacityExecution(fixture.transitionId())))))
        .containsExactly(tombstone);
  }

  private net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus
      installAbortedTransferSuccessor(
          CanonicalGameplayRegionOwnerTransferCoordinator transfer,
          CanonicalGameplayRegionLeaseOwner.Installation original,
          UUID transferId,
          Clock transferClock) {
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "aborted-original-successor",
            "localhost",
            transferClock.instant(),
            "test",
            "test",
            "controlled-test-image");
    var leases = new CanonicalGameplayRegionLeaseRedisOwner(ownerClient, catalog);
    var grant =
        leases.acquire(
            original.source, original.status.regionEpoch(), Duration.ofSeconds(30), identity);
    leases.requireGrantScope(grant, original.source, original.status.regionEpoch(), identity);
    var preparation =
        new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
            .findTimelinePreparation(original.source)
            .orElseThrow();
    leases.verifyInitializedTimeline(grant, preparation);
    leases.requireCurrent(grant);
    var installed =
        transfer.install(
            transferId,
            original.source,
            original.status,
            grant.tokenCorrelation(),
            identity.service(),
            identity.serviceInstanceId(),
            transferClock.millis() + 5000L);
    leases.requireCurrent(grant);
    return installed;
  }

  @Test
  void physicalImageRetainsAbsoluteExpiryRatherThanOnlyItsPresence() {
    byte[] key = "physical-image-expiry".getBytes(StandardCharsets.US_ASCII);
    admin.set(key, "unchanged".getBytes(StandardCharsets.US_ASCII));
    byte[] persistent =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key);
    long expiresAt = System.currentTimeMillis() + 60_000L;
    admin.pexpireat(key, expiresAt);
    byte[] expiring =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key);
    assertThat(expiring).isNotEqualTo(persistent);
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .isEqualTo(expiring);
    admin.pexpireat(key, expiresAt + 600_000L);
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .isNotEqualTo(expiring);
    admin.persist(key);
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .isEqualTo(persistent);
    admin.del(java.util.List.of(key).toArray(byte[][]::new));
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .isNotEqualTo(persistent);
  }

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
    ownerClient = client(PRINCIPAL, password);
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = POSTGRES.getJdbcUrl();
    dataSource.setUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=public");
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
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

  @Test
  void missingCapacityExecutionRefusesTheCanonicalFinalPathWithoutMutation() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var account = controlledAccountOpen();
    var issuer = issuerOpen();
    String rows = sqlImage();
    var physical = physicalImage();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                          ownerClient, catalog, CLOCK, tags)
                      .finalizeOriginalNewOnce(source, template, account, issuer, null, deadline()))
          .isExactlyInstanceOf(NullPointerException.class)
          .hasMessage("Exact Account capacity execution is required");
    }
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
  }

  @Test
  void missingExecutionCannotBeReinstalledFromTheRetainedSqlDispatchOrUsedAsNoCommit()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    String key =
        "session:game:capacity-execution:{"
            + assignment.tenantGameplayTag()
            + "}:"
            + controlledCapacity.request().admissionAttemptId();
    admin.del(ascii(key));
    String rows = sqlImage();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(controlledCapacity)) {
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .authorize(
                          source,
                          template,
                          controlledAccountOpen(),
                          issuerOpen(),
                          controlledCapacity,
                          deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .closeOriginalAttempt(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(admin.type(ascii(key))).isEqualTo("none");
    assertThat(admin.get(ascii(installation.sessionKey())))
        .containsExactly(source.candidate().canonicalBytes());
    assertThat(sqlImage()).isEqualTo(rows);
  }

  @Test
  void failedInstallConnectRetainsDispatchAndNeverPermitsReinstallationOrNegativeProof()
      throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    RedisClient failedClient = mock(RedisClient.class);
    when(failedClient.connect(ByteArrayCodec.INSTANCE))
        .thenThrow(new IllegalStateException("Controlled connection failure"));
    var failedOwner =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            fixture.dsl(), failedClient, catalog, CLOCK, tags);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    String key =
        "session:game:capacity-execution:{"
            + assignment.tenantGameplayTag()
            + "}:"
            + controlledCapacity.request().admissionAttemptId();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(controlledCapacity)) {
      var account = controlledAccountOpen();
      var issuer = issuerOpen();
      assertThatThrownBy(
              () ->
                  failedOwner.authorize(
                      source, template, account, issuer, controlledCapacity, deadline()))
          .isInstanceOf(IllegalStateException.class);
      verify(failedClient, times(1)).connect(ByteArrayCodec.INSTANCE);
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                      controlledCapacity.digest())
                  .get(0, String.class))
          .isEqualTo("INSTALL_DISPATCHED");
      String retained = sqlImage();
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .authorize(source, template, account, issuer, controlledCapacity, deadline()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .closeOriginalAttempt(
                          controlledCapacity.request(), controlledCapacity.reservation()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(admin.type(ascii(key))).isEqualTo("none");
      assertThat(sqlImage()).isEqualTo(retained);
    }
  }

  @Test
  void recreatedCapacityOwnerClosesExactAttemptAndRejectsDelayedFinalCas() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    capacityOwner()
        .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var proof =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(proof.disposition()).isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
    assertThat(proof.currentOwnerFence())
        .isGreaterThanOrEqualTo(controlledCapacity.selectedOwnerFence());
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          source,
                          template,
                          controlledAccountOpen(),
                          issuerOpen(),
                          controlledCapacity,
                          deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    assertThat(
            admin.get(
                ascii(
                    CanonicalGameplaySessionRedisKeys.session(
                        assignment, source.candidate().bindingIdentity().bindingRef()))))
        .containsExactly(source.candidate().canonicalBytes());
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(proof.canonicalReadback());
  }

  @Test
  void transferFenceRejectsChangedCandidateDigestBeforeSubmittingRedis() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    var changed =
        new net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution(
            controlledCapacity.request(),
            controlledCapacity.reservation(),
            controlledCapacity.transitionId(),
            "sha256:" + "0".repeat(64),
            controlledCapacity.selectedOwnerId(),
            controlledCapacity.selectedOwnerFence());
    String retained = sqlImage();
    Map<String, String> physical = physicalImage();

    assertThatThrownBy(
            () ->
                new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                        ownerClient, catalog, CLOCK, tags)
                    .fenceCapacityExecutionOnce(
                        changed, source.candidate(), assignment, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(admin.type(ascii(capacityKey(assignment)))).isEqualTo("none");
    assertThat(sqlImage()).isEqualTo(retained);
    assertThat(physicalImage()).isEqualTo(physical);
  }

  @Test
  void changedExecutionCandidateRefusesBeforeCreatingSqlDispatchCustody() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var changed =
        new net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution(
            controlledCapacity.request(),
            controlledCapacity.reservation(),
            controlledCapacity.transitionId(),
            "sha256:" + "0".repeat(64),
            controlledCapacity.selectedOwnerId(),
            controlledCapacity.selectedOwnerFence());
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    var sqlBefore = sqlImage();
    var physicalBefore = physicalImage();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .authorize(source, template, accountOpen, issuerOpen, changed, deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(sqlImage()).isEqualTo(sqlBefore);
    assertThat(physicalImage()).isEqualTo(physicalBefore);
  }

  @Test
  void processStopAfterActualSqlAuthorizationBeforeRedisSendFencesUnknownAndActuallyHandsOff()
      throws Exception {
    repairAccountMember();
    bridge(false);
    var executionFile = directory.resolve("original-execution.json");
    java.nio.file.Files.write(executionFile, controlledCapacity.canonicalBytes());
    String jdbcUrl = POSTGRES.getJdbcUrl();
    String schema = fixture.dsl().fetchSingle("SELECT current_schema()").get(0, String.class);
    var childOutput = directory.resolve("stop-after-authorization.log");
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                childClasspath(),
                StopAfterAuthorizationCommit.class.getName(),
                jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                executionFile.toString())
            .redirectErrorStream(true)
            .redirectOutput(childOutput.toFile())
            .start();
    try {
      assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      String output = java.nio.file.Files.readString(childOutput, StandardCharsets.UTF_8);
      assertThat(process.exitValue()).as("Child output: %s", output).isEqualTo(72);
      assertThat(output).contains("INDEPENDENT_INSTALL_DISPATCHED_COMMIT");
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      }
    }
    String originalKey = capacityKey(tags.assignmentForTransition(fixture.transitionId()));
    assertThat(admin.type(ascii(originalKey))).isEqualTo("none");
    var original = regionBinding.installation();
    admin.pexpire(ascii(original.grant.key()), 0);
    var successor = actualTransferHandoff();
    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(
            admin.hget(
                ascii(capacityKey(tags.assignmentForTransition(fixture.transitionId()))),
                ascii("state")))
        .containsExactly(ascii("FENCED_INDETERMINATE"));
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo("INSTALL_DISPATCHED");
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .closeOriginalAttempt(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void contenderCannotFreezeRenewingOwnerAndExpiredLeaseStillTransfers() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var leases = new CanonicalGameplayRegionLeaseRedisOwner(ownerClient, catalog);
    String rows = sqlImage();
    Map<String, String> physical = physicalImage();
    String key = capacityKey(tags.assignmentForTransition(fixture.transitionId()));
    for (int attempt = 0; attempt < 2; attempt++) {
      leases.renew(original.grant, Duration.ofSeconds(30));
      assertThatThrownBy(this::actualTransferHandoff).isInstanceOf(IllegalStateException.class);
      leases.requireCurrent(original.grant);
      assertThat(sqlImage()).isEqualTo(rows);
      assertThat(physicalImage()).isEqualTo(physical);
      assertThat(admin.hget(ascii(key), ascii("state"))).containsExactly(ascii("AUTHORIZED"));
      assertThat(
              fixture
                  .dsl()
                  .fetchCount(org.jooq.impl.DSL.table("game_session_region_owner_transfer")))
          .isZero();
    }
    admin.pexpire(ascii(original.grant.key()), 0);
    var successor = actualTransferHandoff();
    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(admin.hget(ascii(key), ascii("state")))
        .containsExactly(ascii("FENCED_INDETERMINATE"));
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state FROM game_session_region_owner_transfer")
                .get(0, String.class))
        .isEqualTo("COMPLETED");
  }

  @Test
  void blockedTransferPreinstallRegionStatusReadKeepsCurrentOwner() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    String schema = fixture.dsl().fetchSingle("SELECT current_schema()").get(0, String.class);
    java.util.concurrent.CompletableFuture<Boolean> handoff = null;
    try (var lock =
        java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      lock.setSchema(schema);
      lock.setAutoCommit(false);
      int blockerPid;
      try (var statement = lock.createStatement()) {
        statement.execute("LOCK TABLE game_session_region_runtime_status IN ACCESS EXCLUSIVE MODE");
        try (var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
          rows.next();
          blockerPid = rows.getInt(1);
        }
      }

      handoff =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  actualTransferHandoff();
                  return false;
                } catch (IllegalStateException unavailable) {
                  return true;
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              });
      long blockedDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      boolean statusReadBlocked = false;
      while (System.nanoTime() < blockedDeadline && !statusReadBlocked && !handoff.isDone()) {
        statusReadBlocked =
            fixture
                    .dsl()
                    .fetchOne(
                        "SELECT pid FROM pg_stat_activity WHERE pid <> pg_backend_pid()"
                            + " AND ? = ANY(pg_blocking_pids(pid))"
                            + " AND query LIKE '%game_session_region_runtime_status%' LIMIT 1",
                        blockerPid)
                != null;
        if (!statusReadBlocked) Thread.sleep(20L);
      }
      boolean failedClosed = handoff.get(7, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(statusReadBlocked).as("handoff reached the blocked region status read").isTrue();
      assertThat(failedClosed).as("bounded status read fails closed").isTrue();
    } finally {
      if (handoff != null && !handoff.isDone()) {
        assertThat(handoff.get(7, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      }
    }

    var current =
        new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
            .find(original.source)
            .orElseThrow();
    assertThat(current.executorFence()).isEqualTo(original.status.executorFence());
    assertThat(current.ownerInstanceId()).isEqualTo(original.status.ownerInstanceId());
    assertThat(
            fixture.dsl().fetchCount(org.jooq.impl.DSL.table("game_session_region_owner_transfer")))
        .isZero();
  }

  @Test
  void authorizedUnknownHandoffKeepsOriginalCandidateAndRefusesCompletedMutation()
      throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    byte[] candidate = admin.get(ascii(installation.sessionKey()));
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    UUID transferId = transfer.advance(original.source, original.status, deadline());
    assertThatThrownBy(() -> replaceRegionOwner("unproved-successor"))
        .isInstanceOf(IllegalStateException.class);
    admin.pexpire(ascii(original.grant.key()), 0);
    var successor =
        CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
            fixture.dsl(), ownerClient, catalog, original, sources.resolver, transfer, deadline());
    var completed = receiver.observeForRegionOwnerTransfer(transferId, 0, deadline());
    assertThat(completed.regionOwnerTransferContext().controlState()).isEqualTo("COMPLETED");
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(candidate);
    assertThatThrownBy(
            () -> repository.within(deadline(), ignored -> repository.capture(transferId)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(successor.status.ownerInstanceId()).isEqualTo("final-owner-handoff");
  }

  @Test
  void completedTransferResumesOnlyWithTheSameLivePrivateGrant() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var failAfterCommit = new java.util.concurrent.atomic.AtomicBoolean(true);
    var observationSource =
        new CanonicalGameplayRegionOwnerTransferObservationSource() {
          @Override
          public Context readCurrent(UUID transferId, long targetOrdinal) {
            var context = repository.readCurrent(transferId, targetOrdinal);
            if ("COMPLETED".equals(context.controlState()) && failAfterCommit.getAndSet(false)) {
              throw new IllegalStateException("Controlled completed observation failure");
            }
            return context;
          }

          @Override
          public CanonicalGameplayTenantGameplayTagRepository.Assignment readAssignment(
              UUID transitionId, CanonicalGameplayTenantGameplayTagRepository sourceTags) {
            return repository.readAssignment(transitionId, sourceTags);
          }
        };
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(observationSource, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "final-owner-handoff",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    CanonicalGameplayRegionIncarnationSource incarnation =
        scope -> {
          assertThat(scope).isEqualTo(original.incarnation.scope());
          return original.incarnation;
        };
    var successor =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            catalog,
            incarnation,
            sources.resolver,
            identity,
            java.time.Duration.ofSeconds(30),
            CLOCK);
    admin.pexpire(ascii(original.grant.key()), 0);
    var failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.TransferCompletionUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));
    assertThat(failAfterCommit).isFalse();
    var durable =
        new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
            .find(original.source)
            .orElseThrow();
    assertThat(durable.executorFence()).isGreaterThan(original.status.executorFence());
    var completed =
        fixture
            .dsl()
            .fetchSingle("SELECT state, transfer_id FROM game_session_region_owner_transfer");
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThatThrownBy(
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()))
        .isInstanceOf(IllegalStateException.class);
    var recovered = successor.resumeCompletedTransfer(failure, deadline());
    assertThat(recovered.status).isEqualTo(durable);
    assertThat(recovered.grant.tokenCorrelation()).isEqualTo(durable.leaseTokenCorrelation());
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state, transfer_id FROM game_session_region_owner_transfer"))
        .isEqualTo(completed);
    var completedSql = sqlImage();
    assertThat(successor.resumeCompletedTransfer(failure, deadline()).status).isEqualTo(durable);
    assertThat(sqlImage()).isEqualTo(completedSql);
    admin.pexpire(ascii(recovered.grant.key()), 0);
    assertThatThrownBy(() -> successor.resumeCompletedTransfer(failure, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(durable);
  }

  @Test
  void frozenTransferResumesAfterAdvanceAndInstallFailuresWithTheSameLiveGrant() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var failFenceReceipt = new java.util.concurrent.atomic.AtomicBoolean(true);
    var failInstallCompletion = new java.util.concurrent.atomic.AtomicBoolean(true);
    var observer =
        new org.jooq.impl.DefaultExecuteListener() {
          @Override
          public void executeStart(org.jooq.ExecuteContext context) {
            String statement = context.sql();
            if (statement == null) return;
            if (statement.contains("UPDATE game_session_region_owner_transfer_item")
                && statement.contains("SET fence_readback=?")
                && failFenceReceipt.compareAndSet(true, false)) {
              throw new IllegalStateException("Controlled failure after successor fence CAS");
            }
            if (statement.contains("UPDATE game_session_region_owner_transfer")
                && statement.contains("SET state='COMPLETED'")
                && failInstallCompletion.compareAndSet(true, false)) {
              throw new IllegalStateException(
                  "Controlled failure before transfer completion commit");
            }
          }
        };
    var observedSql =
        org.jooq.impl.DSL.using(fixture.dsl().configuration().deriveAppending(observer));
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(observedSql, CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            observedSql, CLOCK, repository, capacityOwner(), receiver);
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "final-owner-handoff",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    CanonicalGameplayRegionIncarnationSource incarnation =
        scope -> {
          assertThat(scope).isEqualTo(original.incarnation.scope());
          return original.incarnation;
        };
    var successor =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            catalog,
            incarnation,
            sources.resolver,
            identity,
            java.time.Duration.ofSeconds(30),
            CLOCK);
    admin.pexpire(ascii(original.grant.key()), 0);

    var frozenFailure =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.FrozenTransferUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));
    assertThat(failFenceReceipt).isFalse();
    var frozen = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    UUID transferId = frozen.get("transfer_id", UUID.class);
    assertThat(frozen.get("state", String.class)).isEqualTo("FROZEN");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT fence_readback FROM game_session_region_owner_transfer_item WHERE transfer_id=? ORDER BY target_ordinal LIMIT 1",
                    transferId)
                .get(0, byte[].class))
        .isNull();
    String fencedKey = capacityKey(tags.assignmentForTransition(fixture.transitionId()));
    assertThat(admin.hget(ascii(fencedKey), ascii("state")))
        .containsExactly(ascii("FENCED_INDETERMINATE"));
    String frozenSql = sqlImage();
    Map<String, String> frozenPhysical = physicalImage();

    var differentOwner =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            catalog,
            incarnation,
            sources.resolver,
            identity,
            java.time.Duration.ofSeconds(30),
            CLOCK);
    assertThatThrownBy(() -> differentOwner.resumeFrozenTransfer(frozenFailure, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(frozenSql);
    assertThat(physicalImage()).isEqualTo(frozenPhysical);

    var installFailure =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.TransferCompletionUnconfirmed.class,
            () -> successor.resumeFrozenTransfer(frozenFailure, deadline()));
    assertThat(failInstallCompletion).isFalse();
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state,transfer_id FROM game_session_region_owner_transfer"))
        .satisfies(
            row -> {
              assertThat(row.get("state", String.class)).isEqualTo("FROZEN");
              assertThat(row.get("transfer_id", UUID.class)).isEqualTo(transferId);
            });
    long reservedFence =
        fixture
            .dsl()
            .fetchSingle("SELECT successor_owner_fence FROM game_session_region_owner_transfer")
            .get(0, Long.class);
    var recovered = successor.resumeFrozenTransfer(installFailure, deadline());
    assertThat(recovered.status.executorFence()).isEqualTo(reservedFence);
    assertThat(recovered.grant.tokenCorrelation())
        .isEqualTo(recovered.status.leaseTokenCorrelation());
    var completed =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT state,transfer_id,successor_owner_fence FROM game_session_region_owner_transfer");
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(transferId);
    assertThat(completed.get("successor_owner_fence", Long.class)).isEqualTo(reservedFence);
    String completedSql = sqlImage();
    admin.pexpire(ascii(recovered.grant.key()), 0);
    assertThatThrownBy(() -> successor.resumeFrozenTransfer(installFailure, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(completedSql);
  }

  @Test
  void retainedTransferFenceLossCannotBeReinstalledOrQualifyHandoff() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    transfer.advance(original.source, original.status, deadline());
    String key = capacityKey(tags.assignmentForTransition(fixture.transitionId()));
    admin.del(ascii(key));
    String retained = sqlImage();
    admin.pexpire(ascii(original.grant.key()), 0);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                    fixture.dsl(),
                    ownerClient,
                    catalog,
                    original,
                    sources.resolver,
                    transfer,
                    deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(admin.type(ascii(key))).isEqualTo("none");
    assertThat(sqlImage()).isEqualTo(retained);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
                .find(original.source)
                .orElseThrow()
                .executorFence())
        .isEqualTo(original.status.executorFence());
  }

  private CanonicalGameplayRegionLeaseOwner.Installation actualTransferHandoff() throws Exception {
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
        catalog,
        regionBinding.installation(),
        sources.resolver,
        transfer,
        deadline());
  }

  @Test
  void retainedTransferFenceWithTtlCannotQualifyNativeHandoff() throws Exception {
    retainedTransferTamperBlocks("ttl");
  }

  @Test
  void retainedTransferFenceWrongTypeCannotQualifyNativeHandoff() throws Exception {
    retainedTransferTamperBlocks("type");
  }

  @Test
  void retainedTransferFenceChangedEnvelopeCannotQualifyNativeHandoff() throws Exception {
    retainedTransferTamperBlocks("binding");
  }

  private void retainedTransferTamperBlocks(String fault) throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    transfer.advance(original.source, original.status, deadline());
    byte[] key = ascii(capacityKey(tags.assignmentForTransition(fixture.transitionId())));
    if (fault.equals("ttl")) admin.pexpire(key, 10000);
    else if (fault.equals("type")) {
      byte[][] keysToDelete = {key};
      admin.del(keysToDelete);
      admin.set(key, ascii("wrong-type"));
    } else admin.hset(key, ascii("binding"), ascii("{}"));
    byte[] physical =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key);
    admin.pexpire(ascii(original.grant.key()), 0);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                    fixture.dsl(),
                    ownerClient,
                    catalog,
                    original,
                    sources.resolver,
                    transfer,
                    deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .containsExactly(physical);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
                .find(original.source)
                .orElseThrow()
                .executorFence())
        .isEqualTo(original.status.executorFence());
  }

  @Test
  void correctlySignedDifferentStorageGenerationCannotAdoptRetainedTransferReceipts()
      throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var attestor = new CanonicalGameplayControlledRegionTransferObservationFixture();
    var originalTransfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), attestor.receiver(repository, tags));
    originalTransfer.advance(original.source, original.status, deadline());
    String retained = sqlImage();
    var changed =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(),
            CLOCK,
            repository,
            capacityOwner(),
            attestor.receiver(repository, tags, "storage-2"));
    admin.pexpire(ascii(original.grant.key()), 0);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                    fixture.dsl(),
                    ownerClient,
                    catalog,
                    original,
                    sources.resolver,
                    changed,
                    deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(retained);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
                .find(original.source)
                .orElseThrow()
                .executorFence())
        .isEqualTo(original.status.executorFence());
  }

  @Test
  void ordinaryProvisionalHandoffWithoutCapacityExecutionNeedsNoFinalAcknowledgements()
      throws Exception {
    regionBinding =
        CanonicalGameplayRegionOwnerCompositionFixture.installExistingOriginalCandidate(
            fixture.dsl(),
            ownerClient,
            fixture.transitionId(),
            sources.request,
            sources.resolver,
            sources.entity);
    var original = regionBinding.installation();
    var before = inventory.readProvisionalCasEvidence(fixture.transitionId());
    assertThat(before.candidate().accountIndexState())
        .isEqualTo(
            net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry
                .IndexState.REPAIR_REQUIRED);
    assertThatThrownBy(() -> inventory.readOriginalNewFinalAdmissionSource(decision))
        .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT count(*) FROM game_session_original_new_capacity_execution")
                .get(0, Long.class))
        .isZero();

    admin.pexpire(ascii(original.grant.key()), 0);
    var successor = actualTransferHandoff();

    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(inventory.readProvisionalCasEvidence(fixture.transitionId())).isEqualTo(before);
    assertThatThrownBy(() -> inventory.readOriginalNewFinalAdmissionSource(decision))
        .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT count(*) FROM game_session_region_owner_transfer")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void failedPositiveRetentionRollsBackFinalReceiptAndExactRecoveryCommitsBoth() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    String before = sqlImage();
    var retentionAttempted = new java.util.concurrent.atomic.AtomicBoolean();
    var observer =
        new org.jooq.impl.DefaultExecuteListener() {
          @Override
          public void executeStart(org.jooq.ExecuteContext context) {
            String query = context.sql();
            if (query == null
                || !query.startsWith("UPDATE game_session_original_new_capacity_execution")
                || !query.contains("terminal_readback")) return;
            retentionAttempted.set(true);
            assertThat(sqlImage()).isEqualTo(before);
            throw new IllegalStateException("Controlled positive execution retention failure");
          }
        };
    var observedSql =
        org.jooq.impl.DSL.using(fixture.dsl().configuration().deriveAppending(observer));
    var observedInventory = new CanonicalGameplayBindingInventoryRepository(observedSql);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source, template, accountOpen, issuerOpen, controlledCapacity, deadline())) {
      var committedRedis = physicalImage();
      assertThatThrownBy(
              () -> observedInventory.commitOriginalNewFinalAdmission(readback, CLOCK, deadline()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Controlled positive execution retention failure");
      assertThat(retentionAttempted).isTrue();
      assertThat(sqlImage()).isEqualTo(before);
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
          .isInstanceOf(IllegalStateException.class);
      var recovered =
          capacityOwner()
              .retainCommittedBeforeCleanup(
                  controlledCapacity.request(), controlledCapacity.reservation(), deadline());
      assertThat(recovered.disposition()).isEqualTo("COMMITTED");
      assertThat(recovered.physicalDigest()).isEqualTo("sha256:" + readback.finalSessionSha256());
      assertThat(
              inventory
                  .readOriginalNewFinalAdmissionSource(decision)
                  .finalReceipt()
                  .orElseThrow()
                  .finalSessionSha256())
          .isEqualTo(readback.finalSessionSha256());
      assertThat(
              capacityOwner()
                  .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                  .canonicalReadback())
          .containsExactly(recovered.canonicalReadback());
      assertThat(physicalImage()).isEqualTo(committedRedis);
    }
  }

  @Test
  void previouslySplitReceiptRequiresExactPhysicalProofAndRetainsIdempotently() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source, template, accountOpen, issuerOpen, controlledCapacity, deadline())) {
      commitHistoricalSplitReceipt(readback);
    }
    var request = controlledCapacity.request();
    var reservation = controlledCapacity.reservation();
    var owner = capacityOwner();
    assertThatThrownBy(() -> owner.readExact(request, reservation))
        .isInstanceOf(IllegalStateException.class);
    var originalPhysical = physicalImage();
    byte[] sessionKey = ascii(installation.sessionKey());
    byte[] originalSession = admin.get(sessionKey);
    assertThat(admin.pexpire(sessionKey, 0)).isTrue();
    assertThatThrownBy(() -> owner.retainCommittedBeforeCleanup(request, reservation, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> owner.readExact(request, reservation))
        .isInstanceOf(IllegalStateException.class);
    admin.set(sessionKey, originalSession);
    var retained = owner.retainCommittedBeforeCleanup(request, reservation, deadline());
    assertThat(retained.disposition()).isEqualTo("COMMITTED");
    assertThat(
            owner
                .retainCommittedBeforeCleanup(request, reservation, deadline())
                .canonicalReadback())
        .containsExactly(retained.canonicalReadback());
    assertThat(owner.readExact(request, reservation).canonicalReadback())
        .containsExactly(retained.canonicalReadback());
    assertThat(physicalImage()).isEqualTo(originalPhysical);
  }

  @Test
  void committedRedisWinnerMustAcquireFullPositiveSqlRetentionBeforeActualHandoff()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    byte[] committed;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var ignored =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      committed = admin.get(ascii(installation.sessionKey()));
    }
    // No SQL final receipt was retained by the interrupted final caller.
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT original_new_final_session_sha256 FROM game_session_canonical_binding_transition WHERE transition_id=?",
                    fixture.transitionId())
                .get(0, String.class))
        .isNull();
    var original = regionBinding.installation();
    var pinned = source;
    admin.pexpire(ascii(original.grant.key()), 0);
    var successor = actualTransferHandoff();
    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo("COMMITTED");
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .disposition())
        .isEqualTo("COMMITTED");
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(committed);
    var retained = inventory.readRegionTransferOriginalSource(fixture.transitionId(), pinned);
    assertThat(retained.transitionStatus())
        .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
    assertThat(retained.candidate()).isEqualTo(pinned.candidate());
    assertThat(retained.finalReceipt().orElseThrow().finalSessionSha256())
        .isEqualTo(
            net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution.sha256(
                committed));
    assertThat(
            inventory
                .readRegionTransferOriginalSource(fixture.transitionId(), retained)
                .finalReceipt())
        .isEqualTo(retained.finalReceipt());
    assertThatThrownBy(() -> inventory.readProvisionalCasEvidence(fixture.transitionId()))
        .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessage("A first binding cannot proceed while an active controller exists");
    assertThatThrownBy(
            () -> inventory.readRegionTransferOriginalSource(UUID.randomUUID(), retained))
        .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessage("Region transfer changed its original transition");
  }

  @Test
  void frozenTransferResumesExactTerminalOriginalThatCommittedBeforeCapture() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    UUID transferId;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      transferId =
          repository.within(
              deadline(), ignored -> repository.begin(original.source, original.status));
      assertThat(transferId).isNotNull();
      inventory.commitOriginalNewFinalAdmission(readback);
    }
    var captured = repository.within(deadline(), ignored -> repository.capture(transferId));
    assertThat(captured).isEmpty();
    var retained = inventory.readRegionTransferOriginalSource(fixture.transitionId(), source);
    byte[] committed = admin.get(ascii(installation.sessionKey()));
    assertThat(retained.finalReceipt()).isPresent();
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(original.status);
    admin.pexpire(ascii(original.grant.key()), 0);
    var successor =
        CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
            fixture.dsl(), ownerClient, catalog, original, sources.resolver, transfer, deadline());
    assertThat(successor.evidence).isNull();
    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    var completed = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(transferId);
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(
            fixture
                .dsl()
                .fetchCount(org.jooq.impl.DSL.table("game_session_region_owner_transfer_item")))
        .isZero();
    assertThat(
            inventory
                .readRegionTransferOriginalSource(fixture.transitionId(), retained)
                .finalReceipt())
        .isEqualTo(retained.finalReceipt());
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(committed);
  }

  @Test
  void frozenTransferResumesSameCommittedOriginalAfterFenceRetentionFailure() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    byte[] committed;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var ignored =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      committed = admin.get(ascii(installation.sessionKey()));
    }
    var original = regionBinding.installation();
    var failRetention = new java.util.concurrent.atomic.AtomicBoolean(true);
    var failed = new java.util.concurrent.atomic.AtomicBoolean();
    var observer =
        new org.jooq.impl.DefaultExecuteListener() {
          @Override
          public void executeStart(org.jooq.ExecuteContext context) {
            String query = context.sql();
            if (query == null || !query.contains(" SET fence_readback=")) return;
            if (!failRetention.get()) return;
            assertThat(
                    fixture
                        .dsl()
                        .fetchSingle(
                            "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                            controlledCapacity.digest())
                        .get(0, String.class))
                .isEqualTo("COMMITTED");
            assertThat(
                    fixture
                        .dsl()
                        .fetchSingle(
                            "SELECT original_new_final_session_sha256 FROM game_session_canonical_binding_transition WHERE transition_id=?",
                            fixture.transitionId())
                        .get(0, String.class))
                .isNotBlank();
            failed.set(true);
            throw new IllegalStateException("Controlled failure after positive SQL retention");
          }
        };
    var observedSql =
        org.jooq.impl.DSL.using(fixture.dsl().configuration().deriveAppending(observer));
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(observedSql, CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            observedSql, CLOCK, repository, capacityOwner(), receiver);
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "final-owner-handoff",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    CanonicalGameplayRegionIncarnationSource incarnation =
        scope -> {
          assertThat(scope).isEqualTo(original.incarnation.scope());
          return original.incarnation;
        };
    var successor =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            catalog,
            incarnation,
            sources.resolver,
            identity,
            Duration.ofSeconds(30),
            CLOCK);
    admin.pexpire(ascii(original.grant.key()), 0);
    var frozenFailure =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.FrozenTransferUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));
    assertThat(failed).isTrue();
    var frozen = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    UUID transferId = frozen.get("transfer_id", UUID.class);
    String intent = frozen.get("intent_digest", String.class);
    assertThat(frozen.get("state", String.class)).isEqualTo("FROZEN");
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(original.status);
    var retained = inventory.readRegionTransferOriginalSource(fixture.transitionId(), source);
    assertThat(retained.transitionStatus())
        .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
    assertThat(retained.finalReceipt()).isPresent();
    String executionImage =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT to_jsonb(e)::text FROM game_session_original_new_capacity_execution e WHERE execution_digest=?",
                controlledCapacity.digest())
            .get(0, String.class);
    long sequence =
        fixture
            .dsl()
            .fetchSingle("SELECT last_value FROM game_session_region_executor_fence_seq")
            .get(0, Long.class);
    failRetention.set(false);
    var recovered = successor.resumeFrozenTransfer(frozenFailure, deadline());
    assertThat(recovered.status.ownerInstanceId()).isEqualTo("final-owner-handoff");
    var completed = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(transferId);
    assertThat(completed.get("intent_digest", String.class)).isEqualTo(intent);
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT last_value FROM game_session_region_executor_fence_seq")
                .get(0, Long.class))
        .isEqualTo(sequence + 1);
    assertThat(
            inventory
                .readRegionTransferOriginalSource(fixture.transitionId(), retained)
                .finalReceipt())
        .isEqualTo(retained.finalReceipt());
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT to_jsonb(e)::text FROM game_session_original_new_capacity_execution e WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo(executionImage);
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(committed);
    assertThat(recovered.evidence)
        .as("committed continuation grants no provisional bridge authority")
        .isNull();
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                    fixture.dsl(),
                    ownerClient,
                    catalog,
                    original,
                    sources.resolver,
                    transfer,
                    deadline()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessage("A first binding cannot proceed while an active controller exists");
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(recovered.status);
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT last_value FROM game_session_region_executor_fence_seq")
                .get(0, Long.class))
        .isEqualTo(sequence + 1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
  void blockedCommittedSelectionOrPreInstallRevalidationCannotOutliveHandoffDeadline(
      int blockedRead) throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var ignored =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      // Leave positive SQL retention to the actual transfer, as in the crash-window test.
    }
    var original = regionBinding.installation();
    var transferRepository =
        new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(transferRepository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, transferRepository, capacityOwner(), receiver);
    String schema = fixture.dsl().fetchSingle("SELECT current_schema()").get(0, String.class);
    var blocked = new java.util.concurrent.atomic.AtomicBoolean();
    var completed = new java.util.concurrent.CountDownLatch(1);
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    try (var lock =
        java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      lock.setSchema(schema);
      lock.setAutoCommit(false);
      var observer =
          new org.jooq.impl.DefaultExecuteListener() {
            @Override
            public void executeStart(org.jooq.ExecuteContext context) {
              String sql = context.sql();
              if (sql == null
                  || !sql.contains("game_session_canonical_binding_inventory_clock")
                  || !sql.contains("FOR UPDATE")) return;
              // This derived observer belongs only to the successor inventory/status context.
              // The independently composed transfer has already retained the positive winner.
              String status =
                  fixture
                      .dsl()
                      .fetchSingle(
                          "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                          controlledCapacity.digest())
                      .get(0, String.class);
              if (!"COMMITTED".equals(status) || reads.incrementAndGet() != blockedRead) return;
              try (var statement = lock.createStatement()) {
                statement.setQueryTimeout(2);
                statement.execute(
                    "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock WHERE singleton_id=1 FOR UPDATE");
                blocked.set(true);
              } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
              }
            }

            @Override
            public void exception(org.jooq.ExecuteContext context) {
              if (blocked.get()) completed.countDown();
            }

            @Override
            public void executeEnd(org.jooq.ExecuteContext context) {
              if (blocked.get()) completed.countDown();
            }
          };
      var successorSql =
          org.jooq.impl.DSL.using(
              fixture
                  .dsl()
                  .configuration()
                  .derive(new org.jooq.impl.DefaultExecuteListenerProvider(observer)));
      admin.pexpire(ascii(original.grant.key()), 0);
      long started = System.nanoTime();
      long handoffDeadline = CLOCK.millis() + 4000;
      try {
        assertThatThrownBy(
                () ->
                    CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                        successorSql,
                        ownerClient,
                        catalog,
                        original,
                        sources.resolver,
                        transfer,
                        handoffDeadline))
            .isInstanceOf(IllegalStateException.class);
        assertThat(blocked).as("exact committed inventory read reached").isTrue();
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
            .isLessThan(6500);
        assertThat(completed.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      } finally {
        lock.rollback();
      }
    }
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT executor_fence FROM game_session_region_runtime_status")
                .get(0, BigInteger.class))
        .isEqualTo(original.status.executorFence());
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state FROM game_session_region_owner_transfer")
                .get(0, String.class))
        .isEqualTo("FROZEN");
  }

  @Test
  void committedHashWithoutCompletePositiveServingProofStillBlocksActualTransfer()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var ignored =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      admin.del(ascii(installation.sessionKey()));
    }
    var original = regionBinding.installation();
    admin.pexpire(ascii(original.grant.key()), 0);
    assertThatThrownBy(this::actualTransferHandoff).isInstanceOf(IllegalStateException.class);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
                .find(original.source)
                .orElseThrow()
                .executorFence())
        .isEqualTo(original.status.executorFence());
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo("AUTHORIZED");
    assertThat(
            admin.hget(
                ascii(capacityKey(tags.assignmentForTransition(fixture.transitionId()))),
                ascii("state")))
        .containsExactly(ascii("COMMITTED"));
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static String childClasspath() throws Exception {
    var paths = new java.util.LinkedHashSet<String>();
    paths.addAll(List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
    for (ClassLoader loader =
            CanonicalGameplayOriginalNewFinalAdmissionOwnerIntegrationTest.class.getClassLoader();
        loader != null;
        loader = loader.getParent()) {
      if (loader instanceof java.net.URLClassLoader urls)
        for (var url : urls.getURLs()) {
          if ("file".equals(url.getProtocol())) paths.add(Path.of(url.toURI()).toString());
        }
    }
    paths.add(
        Path.of(
                CanonicalGameplayOriginalNewFinalAdmissionOwnerIntegrationTest.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
            .toString());
    return String.join(java.io.File.pathSeparator, paths);
  }

  @Test
  void delayedActualPredecessorInstallRefusesAfterNativeHandoffWithinOriginalDeadline()
      throws Exception {
    delayedPredecessorAfterHandoff(true);
  }

  @Test
  void delayedActualPredecessorFinalRefusesAfterNativeHandoffWithinOriginalDeadline()
      throws Exception {
    delayedPredecessorAfterHandoff(false);
  }

  private void delayedPredecessorAfterHandoff(boolean installAction) throws Exception {
    repairAccountMember();
    bridge(!installAction);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    String oldSha =
        admin.scriptLoad(
            CanonicalGameplayOriginalNewFinalAdmissionLuaIntegrationTest
                .predecessorLuaForOwnerProof());
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var redisReply = new java.util.concurrent.CompletableFuture<Long>();
    var dispatchedAt = new java.util.concurrent.atomic.AtomicLong();
    String action = installAction ? "INSTALL" : "FINAL";
    RedisClient delayed = mock(RedisClient.class);
    when(delayed.connect(ByteArrayCodec.INSTANCE))
        .thenAnswer(
            ignored ->
                delayedConnection(
                    ownerClient.connect(ByteArrayCodec.INSTANCE),
                    oldSha,
                    action,
                    entered,
                    release,
                    calls,
                    redisReply,
                    dispatchedAt));
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(controlledCapacity)) {
      var account = controlledAccountOpen();
      var issuer = issuerOpen();
      long frozenDeadline = CLOCK.millis() + 5000L;
      var old =
          java.util.concurrent.CompletableFuture.runAsync(
              () -> {
                if (installAction) {
                  new CanonicalGameplayOriginalNewCapacityExecutionOwner(
                          fixture.dsl(), delayed, catalog, CLOCK, tags)
                      .authorize(
                          source, template, account, issuer, controlledCapacity, frozenDeadline);
                } else {
                  var owner =
                      new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                          delayed,
                          catalog,
                          CLOCK,
                          tags,
                          () -> delayed.connect(ByteArrayCodec.INSTANCE),
                          () -> frozenDeadline - CLOCK.millis());
                  try (var ignored =
                      owner.finalizeOriginalNewOnce(
                          source, template, account, issuer, controlledCapacity, frozenDeadline)) {
                    throw new AssertionError(
                        "Delayed old FINAL must not produce an admission receipt");
                  }
                }
              });
      try {
        assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var original = regionBinding.installation();
        admin.pexpire(ascii(original.grant.key()), 0);
        var successor =
            CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                fixture.dsl(),
                ownerClient,
                catalog,
                original,
                sources.resolver,
                transfer,
                frozenDeadline);
        assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
        assertThat(CLOCK.millis()).isLessThan(frozenDeadline);
        var fencedPhysical = physicalImage();
        release.countDown();
        assertThat(redisReply.get(2, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(-1L);
        assertThat(dispatchedAt.get()).isPositive().isLessThan(frozenDeadline);
        assertThat(CLOCK.millis()).isLessThan(frozenDeadline);
        assertThatThrownBy(() -> old.get(2, java.util.concurrent.TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isOne();
        assertThat(physicalImage()).isEqualTo(fencedPhysical);
        assertThat(admin.get(ascii(installation.sessionKey())))
            .containsExactly(source.candidate().canonicalBytes());
        assertThat(
                admin.hget(
                    ascii(capacityKey(tags.assignmentForTransition(fixture.transitionId()))),
                    ascii("state")))
            .containsExactly(ascii("FENCED_INDETERMINATE"));
      } finally {
        release.countDown();
        try {
          old.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException expected) {
          /* already asserted failure */
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static StatefulRedisConnection<byte[], byte[]> delayedConnection(
      StatefulRedisConnection<byte[], byte[]> actual,
      String predecessorSha,
      String action,
      java.util.concurrent.CountDownLatch entered,
      java.util.concurrent.CountDownLatch release,
      java.util.concurrent.atomic.AtomicInteger calls,
      java.util.concurrent.CompletableFuture<Long> redisReply,
      java.util.concurrent.atomic.AtomicLong dispatchedAt) {
    var commands = actual.async();
    Object delayedCommands =
        java.lang.reflect.Proxy.newProxyInstance(
            io.lettuce.core.api.async.RedisAsyncCommands.class.getClassLoader(),
            new Class<?>[] {io.lettuce.core.api.async.RedisAsyncCommands.class},
            (proxy, method, args) -> {
              if (method.getName().equals("evalsha")
                  && args.length == 4
                  && args[3] instanceof byte[][] values
                  && values.length == 19
                  && action.equals(new String(values[17], StandardCharsets.US_ASCII))) {
                entered.countDown();
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                  throw new AssertionError("Delayed old invocation not released to Redis");
                Object[] original = args.clone();
                original[0] = predecessorSha;
                try {
                  dispatchedAt.set(CLOCK.millis());
                  var reply = (io.lettuce.core.RedisFuture<Long>) method.invoke(commands, original);
                  calls.incrementAndGet();
                  reply.whenComplete(
                      (value, failure) -> {
                        if (failure == null) redisReply.complete(value);
                        else redisReply.completeExceptionally(failure);
                      });
                  return reply;
                } catch (java.lang.reflect.InvocationTargetException failure) {
                  throw failure.getCause();
                }
              }
              try {
                return method.invoke(commands, args);
              } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
    return (StatefulRedisConnection<byte[], byte[]>)
        java.lang.reflect.Proxy.newProxyInstance(
            StatefulRedisConnection.class.getClassLoader(),
            new Class<?>[] {StatefulRedisConnection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("async")) return delayedCommands;
              try {
                return method.invoke(actual, args);
              } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  /** A real child JVM halts only after independent visibility of the real owner's SQL commit. */
  public static final class StopAfterAuthorizationCommit {
    private StopAfterAuthorizationCommit() {}

    // The returned proxy owns actual: close delegates to it, including normal jOOQ cleanup.
    private static java.sql.Connection stopAfterCommit(
        java.sql.Connection actual, javax.sql.DataSource raw, String executionDigest)
        throws java.sql.SQLException {
      try {
        return (java.sql.Connection)
            java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Connection.class.getClassLoader(),
                new Class<?>[] {java.sql.Connection.class},
                (proxy, method, values) -> {
                  Object result;
                  try {
                    result = method.invoke(actual, values);
                  } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                  if (method.getName().equals("commit")) {
                    try (var independent = raw.getConnection();
                        var check =
                            independent.prepareStatement(
                                "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?")) {
                      check.setString(1, executionDigest);
                      try (var rows = check.executeQuery()) {
                        if (rows.next() && "INSTALL_DISPATCHED".equals(rows.getString(1))) {
                          System.out.println("INDEPENDENT_INSTALL_DISPATCHED_COMMIT");
                          System.out.flush();
                          Runtime.getRuntime().halt(72);
                        }
                      }
                    }
                  }
                  return result;
                });
      } catch (RuntimeException | Error failure) {
        try {
          actual.close();
        } catch (java.sql.SQLException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
        throw failure;
      }
    }

    public static void main(String[] args) throws Exception {
      var raw = new DriverManagerDataSource(args[0], args[1], args[2]);
      var execution =
          CanonicalGameplayRegionOwnerTransferRepository.decode(
              java.nio.file.Files.readAllBytes(Path.of(args[3])));
      var stopping =
          new org.springframework.jdbc.datasource.DelegatingDataSource(raw) {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
              return stopAfterCommit(raw.getConnection(), raw, execution.digest());
            }
          };
      var sql = org.jooq.impl.DSL.using(stopping, org.jooq.SQLDialect.POSTGRES);
      var candidate =
          CanonicalGameplayOriginalNewCandidate.parseCanonical(
              sql.fetchSingle(
                      "SELECT original_new_candidate_bytes FROM game_session_canonical_binding_transition WHERE transition_id=?",
                      execution.transitionId())
                  .get(0, byte[].class));
      var inventory = new CanonicalGameplayBindingInventoryRepository(sql);
      var decision =
          inventory.readAdmissionDecision(execution.transitionId(), candidate.leaseEvidence());
      var source = inventory.readOriginalNewFinalAdmissionSource(decision);
      try (var unused = RedisClient.create("redis://127.0.0.1:1")) {
        var scripts =
            RedisScriptCatalog.fromContributions(
                List.of(
                    new net.firedevops.firemud.gamesession.service
                        .CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution()));
        var owner =
            new CanonicalGameplayOriginalNewCapacityExecutionOwner(
                sql,
                unused,
                scripts,
                Clock.systemUTC(),
                new CanonicalGameplayTenantGameplayTagRepository(sql, "test", 1));
        owner.authorize(source, null, null, null, execution, System.currentTimeMillis() + 5000);
      }
      throw new IllegalStateException(
          "Expected committed authorization to stop the process before Redis dispatch");
    }
  }

  @Test
  void retainedClosedProofSurvivesControlledServingRemovalAndStillRejectsDelayedFinal()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    var account = controlledAccountOpen();
    var issuer = issuerOpen();
    capacityOwner()
        .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var retained =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    // Controlled physical retirement only. The existing genuine never-admitted owner accepts
    // untouched PREPARED attempts, not this already-acknowledged PROVISIONAL candidate.
    controlledRemoveServingKeys(source, assignment);
    var replay =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(replay.canonicalReadback()).containsExactly(retained.canonicalReadback());
    assertThat(replay.currentOwnerId()).isEqualTo(retained.currentOwnerId());
    assertThat(replay.currentOwnerFence()).isEqualTo(retained.currentOwnerFence());
    assertThat(replay.execution().selectedOwnerFence())
        .isEqualTo(controlledCapacity.selectedOwnerFence());
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          source, template, account, issuer, controlledCapacity, deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(admin.hget(ascii(capacityKey(assignment)), ascii("state")))
        .containsExactly(ascii("CLOSED"));
    assertThat(admin.type(ascii(installation.sessionKey()))).isEqualTo("none");
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(retained.canonicalReadback());
  }

  @Test
  void retainedClosedProofRequiresItsExactTombstoneAndReservationFence() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    capacityOwner()
        .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var original = controlledCapacity.reservation();
    var changedFence =
        new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result(
            original.identity(),
            original.admissionAttemptId(),
            original.capacityRearmFence(),
            original.mutationDigest(),
            original.reservationId(),
            original.reservationFence() + 1,
            original.expiresAt(),
            original.state(),
            original.snapshotIdentity(),
            original.evaluationIdentity());
    assertThatThrownBy(() -> capacityOwner().readExact(controlledCapacity.request(), changedFence))
        .isInstanceOf(IllegalStateException.class);
    String rows = sqlImage();
    admin.del(ascii(capacityKey(assignment)));
    assertThatThrownBy(() -> capacityOwner().readExact(controlledCapacity.request(), original))
        .isInstanceOf(IllegalStateException.class);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var bound = raw.bindCapacityExecution(controlledCapacity)) {
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .authorize(
                          source,
                          bound,
                          controlledAccountOpen(),
                          issuerOpen(),
                          controlledCapacity,
                          deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(admin.type(ascii(capacityKey(assignment)))).isEqualTo("none");
    assertThat(sqlImage()).isEqualTo(rows);
  }

  @Test
  void closedExecutionRejectsContradictoryFinalSqlReceiptWithoutLosingItsRetainedProof()
      throws Exception {
    repairAccountMember();
    bridge();
    capacityOwner()
        .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var retained =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    String rows = sqlImage();
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_session_canonical_binding_transition SET original_new_final_session_sha256 = ?, original_new_capacity_execution_digest = ?, original_new_admission_at_ms = ?, original_new_continuity_expires_at_ms = ?, original_new_session_duration_ms = ? WHERE transition_id = ?",
                        "a".repeat(64),
                        controlledCapacity.digest(),
                        CLOCK.millis(),
                        CLOCK.millis() + 1000,
                        1000L,
                        fixture.transitionId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(retained.canonicalReadback());
  }

  @Test
  void physicalClosureBeforeSqlProofRetentionRemainsIndeterminateEvenAfterServingRemoval()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    assertThat(
            new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                    ownerClient, catalog, CLOCK, tags)
                .closeCapacityExecutionOnce(controlledCapacity, source.candidate(), assignment))
        .isEqualTo(4);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    controlledRemoveServingKeys(source, assignment);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .closeOriginalAttempt(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo("AUTHORIZED");
  }

  @Test
  void recreatedCapacityOwnerRetainsCommittedSqlBeforeServingCleanup() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    String finalDigest;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      inventory.commitOriginalNewFinalAdmission(actual);
      finalDigest = actual.finalSessionSha256();
    }
    // A recreated owner can read the retained positive immediately after the receipt commits.
    String rows = sqlImage();
    var physical = physicalImage();
    var retained =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(retained.disposition()).isEqualTo("COMMITTED");
    assertThat(retained.physicalDigest()).isEqualTo("sha256:" + finalDigest);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .closeOriginalAttempt(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    controlledRemoveServingKeys(source, assignment);
    var replay =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(replay.canonicalReadback()).containsExactly(retained.canonicalReadback());
    assertThat(
            capacityOwner()
                .retainCommittedBeforeCleanup(
                    controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(retained.canonicalReadback());
  }

  @Test
  void actualFinalBeforeAccountFenceRetainsPhysicalPositiveButRecoveryRemainsIndeterminate()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      // This is the real physical FINAL, not an injected SQL or Redis positive receipt.
      byte[] finalBytes = admin.get(ascii(installation.sessionKey()));
      assertThat(finalBytes).containsExactly(actual.exactCanonicalBytes());
      assertThat(
              net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution.sha256(
                  finalBytes))
          .isEqualTo(actual.finalSessionSha256());
      var pending =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, original_new_final_session_sha256 FROM game_session_canonical_binding_transition WHERE transition_id=?",
                  fixture.transitionId());
      assertThat(pending.get("status", String.class)).isEqualTo("PROVISIONAL");
      assertThat(pending.get("original_new_final_session_sha256", String.class)).isNull();
      // The crash window intentionally precedes SQL commit. Physical FINAL is positive evidence,
      // but the Account-facing reader requires COMMITTED and must not expose it as final-readable.
      String beforeReader = sqlImage();
      var physicalBeforeFence = physicalImage();
      assertThatThrownBy(
              () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessage("Original NEW transition is not final-readable");
      assertThat(sqlImage()).isEqualTo(beforeReader);
      assertThat(physicalImage()).isEqualTo(physicalBeforeFence);
      assertThat(inventory.readOriginalNewFinalAdmissionSource(decision).transitionStatus())
          .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL);
      var barrier = new CanonicalGameplayAccountAdmissionBarrierRepository(fixture.dsl());
      var intent =
          barrier.beginFenceIntent(
              source.candidate().bindingIdentity().accountId(), UUID.randomUUID());
      assertThat(intent.state().name()).isEqualTo("ACQUISITION_INTENT");
      assertThat(intent.cohortRows())
          .filteredOn(
              row ->
                  row.kind() == CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.TRANSITION
                      && row.sourceKey().equals(fixture.transitionId().toString()))
          .hasSize(1);
      assertThat(physicalImage()).isEqualTo(physicalBeforeFence);
      String rows = sqlImage();
      var physical = physicalImage();
      for (int attempt = 0; attempt < 2; attempt++) {
        // Current authority deliberately cannot promote this historical positive through a
        // newer frozen Account cohort. A future receipt-only recovery owner needs its own proof.
        assertThatThrownBy(
                () ->
                    new CanonicalGameplayBindingInventoryRepository(fixture.dsl())
                        .commitOriginalNewFinalAdmission(actual))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessage("Account-fence acquisition intent blocks a new binding transition");
        assertThatThrownBy(
                () ->
                    new CanonicalGameplayBindingInventoryRepository(fixture.dsl())
                        .readOriginalNewFinalAdmissionSource(decision))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessage("Account-fence acquisition intent blocks a new binding transition");
        assertThatThrownBy(
                () ->
                    capacityOwner()
                        .retainCommittedBeforeCleanup(
                            controlledCapacity.request(), controlledCapacity.reservation()))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
        assertThatThrownBy(
                () ->
                    capacityOwner()
                        .closeOriginalAttempt(
                            controlledCapacity.request(), controlledCapacity.reservation()))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
        assertThatThrownBy(
                () ->
                    capacityOwner()
                        .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
            .isInstanceOf(IllegalStateException.class);
        // This remains the SQL-commit guard; the source/commit assertions above independently
        // prove that the newer Account fence prevents reconciliation of the physical positive.
        assertThatThrownBy(
                () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessage("Original NEW transition is not final-readable");
        assertThat(sqlImage()).isEqualTo(rows);
        assertThat(physicalImage()).isEqualTo(physical);
        assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(finalBytes);
        var frozen = barrier.readCurrentFenceIntent(intent.operationId());
        assertThat(frozen.state()).isEqualTo(intent.state());
        assertThat(frozen.inventorySnapshotRevision())
            .isEqualTo(intent.inventorySnapshotRevision());
        assertThat(frozen.cohortDigest()).isEqualTo(intent.cohortDigest());
        assertThat(frozen.operationFence()).isEqualTo(intent.operationFence());
        assertThat(frozen.plannedAccountAdmissionFence())
            .isEqualTo(intent.plannedAccountAdmissionFence());
        assertThat(frozen.cohortRows()).usingRecursiveComparison().isEqualTo(intent.cohortRows());
        assertThat(frozen.barrierTargetDigest()).isEqualTo(intent.barrierTargetDigest());
        assertThat(frozen.barrierTargets())
            .usingRecursiveComparison()
            .isEqualTo(intent.barrierTargets());
      }
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                      controlledCapacity.digest())
                  .get(0, String.class))
          .isEqualTo("AUTHORIZED");
    }
  }

  @Test
  void recreatedCapacityOwnerRecoversCommittedRedisWithoutOriginalCredentialOrGrant()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var account = controlledAccountOpen();
    var issuer = issuerOpen();
    String finalDigest;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source, template, account, issuer, controlledCapacity, deadline())) {
      finalDigest = actual.finalSessionSha256();
    }
    // Stop before the final SQL receipt. The original private credential is disposed, the grant
    // expires, and fresh OPEN barriers are unavailable. None may erase the actual positive effect.
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    admin.pexpire(ascii(regionBinding.installation().grant.key()), 0);
    admin.del(
        ascii(account.key()),
        ascii(
            CanonicalGameplayIssuerAdmissionBarrierKey.forTarget(
                source.candidate().bindingIdentity().issuerId(), assignment)));
    assertThatThrownBy(() -> replaceRegionOwner("successor-before-reconciliation"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .closeOriginalAttempt(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    var retained =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(retained.disposition()).isEqualTo("COMMITTED");
    assertThat(retained.physicalDigest()).isEqualTo("sha256:" + finalDigest);
    assertThat(inventory.readOriginalNewFinalAdmissionSource(decision).transitionStatus())
        .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
    var successor = replaceRegionOwner("successor-after-reconciliation");
    assertThat(successor.executorFence().longValueExact())
        .isGreaterThan(controlledCapacity.selectedOwnerFence());
    controlledRemoveServingKeys(source, assignment);
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(retained.canonicalReadback());
  }

  @Test
  void missingCommittedServingEvidenceCannotReleaseOriginalOwnerCustody() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      commitHistoricalSplitReceipt(actual);
    }
    assertThat(admin.del(ascii(installation.sessionKey()))).isEqualTo(1L);
    String rows = sqlImage();
    var physical = physicalImage();
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .closeOriginalAttempt(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .retainCommittedBeforeCleanup(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> replaceRegionOwner("successor-with-missing-committed-evidence"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
  }

  @Test
  void ownerMovementBeforeCapacityAuthorizationRefusesOriginalDispatchWithoutMutation()
      throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    replaceRegionOwner("successor-before-capacity-dispatch");
    var account = controlledAccountOpen();
    var issuer = issuerOpen();
    String rows = sqlImage();
    var physical = physicalImage();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var bound = raw.bindCapacityExecution(controlledCapacity)) {
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .authorize(source, bound, account, issuer, controlledCapacity, deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
  }

  @Test
  void unresolvedOriginalExecutionBlocksOwnerMovementUntilExactClosure() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    admin.pexpire(ascii(regionBinding.installation().grant.key()), 0);
    String rows = sqlImage();
    var physical = physicalImage();
    assertThatThrownBy(
            () ->
                regionBinding
                    .owner()
                    .revalidateOriginalNewFinalBinding(regionBinding.installation(), source))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> replaceRegionOwner("successor-before-closure"))
        .isInstanceOf(IllegalStateException.class);
    // The positive continuation cannot turn an unexecuted/missing final result into proof.
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .retainCommittedBeforeCleanup(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
    capacityOwner()
        .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var successor = replaceRegionOwner("successor-after-closure");
    assertThat(successor.executorFence().longValueExact())
        .isGreaterThan(controlledCapacity.selectedOwnerFence());
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .disposition())
        .isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
  }

  @Test
  void sameNativeCandidateFinalizesCommitsReadsAndRecoversExactTimesAndRows() throws Exception {
    repairAccountMember();
    assertSameNativeCandidateFinalizesCommitsReadsAndRecoversExactTimesAndRows();
  }

  @Test
  void requiredIssuerObligationDeleteRefusesExplicitlyAndRetainedCandidateStillFinalizes()
      throws Exception {
    String retainedObligationQuery =
        "SELECT xmin::text || '/' || to_jsonb(obligation)::text"
            + " FROM game_session_canonical_binding_issuer_index_obligation obligation"
            + " WHERE transition_id = ? AND status = 'REQUIRED'";
    String retainedObligation =
        fixture
            .dsl()
            .fetchSingle(retainedObligationQuery, fixture.transitionId())
            .get(0, String.class);
    String retainedRows = sqlImage();
    Map<String, String> retainedPhysical = physicalImage();

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "DELETE FROM game_session_canonical_binding_issuer_index_obligation"
                            + " WHERE transition_id = ?",
                        fixture.transitionId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            org.postgresql.util.PSQLException.class,
            failure -> {
              assertThat(failure.getSQLState()).isEqualTo("23514");
              assertThat(failure.getServerErrorMessage()).isNotNull();
              assertThat(failure.getServerErrorMessage().getConstraint())
                  .isEqualTo("gs_first_new_issuer_release_obligation_retained");
            });
    // A separate SQL read proves the row and its physical version survived, along with
    // retained source/proof rows, allocation counters and the actual Redis bytes.
    assertThat(
            fixture
                .dsl()
                .fetchSingle(retainedObligationQuery, fixture.transitionId())
                .get(0, String.class))
        .isEqualTo(retainedObligation);
    assertThat(sqlImage()).isEqualTo(retainedRows);
    assertThat(physicalImage()).isEqualTo(retainedPhysical);

    repairAccountMember();
    assertSameNativeCandidateFinalizesCommitsReadsAndRecoversExactTimesAndRows();
  }

  @Test
  void committedReceiptSurvivesDeleteRefusalAndClockUnavailabilityUntilCatchup() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source, template, accountOpen, issuerOpen, controlledCapacity, deadline())) {
      inventory.commitOriginalNewFinalAdmission(actual);
      String retainedReceiptQuery =
          "SELECT xmin::text || '/' || to_jsonb(transition)::text"
              + " FROM game_session_canonical_binding_transition transition WHERE transition_id = ?";
      String retainedReceipt =
          fixture
              .dsl()
              .fetchSingle(retainedReceiptQuery, fixture.transitionId())
              .get(0, String.class);
      String retainedRows = sqlImage();
      Map<String, String> retainedPhysical = physicalImage();

      // The candidate BEFORE DELETE guard is the first refusal, before retained-child FKs.
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "DELETE FROM game_session_canonical_binding_transition WHERE transition_id = ?",
                          fixture.transitionId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .satisfies(
              failure ->
                  assertGuardRefusal(
                      failure,
                      "23514",
                      "gs_original_new_candidate_retained",
                      "Original NEW candidate transition retention is held"));
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(retainedReceiptQuery, fixture.transitionId())
                  .get(0, String.class))
          .isEqualTo(retainedReceipt);
      assertThat(sqlImage()).isEqualTo(retainedRows);
      assertThat(physicalImage()).isEqualTo(retainedPhysical);

      for (long unobservableNow :
          List.of(actual.admissionAtMillis() - 1L, actual.admissionAtMillis() - 5_001L, 0L)) {
        var unavailableReader =
            reader(Clock.fixed(Instant.ofEpochMilli(unobservableNow), ZoneOffset.UTC));
        assertThatThrownBy(
                () ->
                    unavailableReader.readActualOriginalFinalAdmission(
                        credential.selector(), "test"))
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Current clock cannot observe the finalized original admission instant");
        assertThat(sqlImage()).isEqualTo(retainedRows);
        assertThat(physicalImage()).isEqualTo(retainedPhysical);
      }
      var observed =
          reader(Clock.fixed(Instant.ofEpochMilli(actual.admissionAtMillis()), ZoneOffset.UTC))
              .readActualOriginalFinalAdmission(credential.selector(), "test");
      assertThat(observed.admissionAtMillis()).isEqualTo(actual.admissionAtMillis());
      assertThat(observed.continuityExpiresAtMillis())
          .isEqualTo(actual.continuityExpiresAtMillis());
      assertThat(observed.canonicalSessionSha256()).isEqualTo(actual.finalSessionSha256());
      var expiredReader =
          reader(
              Clock.fixed(
                  Instant.ofEpochMilli(actual.continuityExpiresAtMillis()), ZoneOffset.UTC));
      assertThatThrownBy(
              () -> expiredReader.readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessage("Finalized original admission is outside its retained current time bounds");
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(retainedReceiptQuery, fixture.transitionId())
                  .get(0, String.class))
          .isEqualTo(retainedReceipt);
      assertThat(sqlImage()).isEqualTo(retainedRows);
      assertThat(physicalImage()).isEqualTo(retainedPhysical);
    }
  }

  @Test
  void alreadyPresentAccountProjectionFinalizesAndReadsWithItsOriginalRevision() throws Exception {
    var prepared = inventory.readProvisionalCasEvidence(fixture.transitionId());
    String member = CanonicalGameplayAccountIndexMember.of(prepared.candidate()).value();
    byte[] key = ascii("session:game:index:account:" + decision.candidate().accountId());
    assertThat(admin.sadd(key, ascii(member))).isEqualTo(1L);
    assertThat(admin.sismember(key, ascii(member))).isTrue();
    var projection = inventory.markCandidateAccountIndexPresent(prepared, member);
    repairAccountMember();
    var repair =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT original_projection_state, original_projection_member,"
                    + " original_projection_inventory_revision FROM"
                    + " game_session_canonical_account_member_repair_operation WHERE transition_id = ?",
                fixture.transitionId());
    assertThat(repair.get("original_projection_state", String.class))
        .isEqualTo("PRESENT_AWAITING_COVERAGE");
    assertThat(repair.get("original_projection_member", String.class)).isEqualTo(member);
    assertThat(
            repair
                .get("original_projection_inventory_revision", java.math.BigDecimal.class)
                .toBigIntegerExact())
        .isEqualTo(projection.projectionInventoryRevision());
    assertSameNativeCandidateFinalizesCommitsReadsAndRecoversExactTimesAndRows();
  }

  private void assertSameNativeCandidateFinalizesCommitsReadsAndRecoversExactTimesAndRows()
      throws Exception {
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    // World canonical region identity and its operational runtime projection are distinct.
    assertThat(source.regionAcknowledgement().canonicalRegionId())
        .isNotEqualTo(source.candidate().bindingIdentity().regionId());
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source, template, accountOpen, issuerOpen, controlledCapacity, deadline())) {
      assertThat(credential.boundJti().toString()).isNotEqualTo(credential.initialLoginJti());
      assertThat(new String(template.finalCasTemplateBytes(), StandardCharsets.UTF_8))
          .doesNotContain(credential.jwt())
          .contains("__ORIGINAL_NEW_ADMISSION_AT_MS__");
      assertThat(admin.get(ascii(installation.sessionKey())))
          .containsExactly(actual.exactCanonicalBytes());
      try (var provisionalReplay =
          finalOwner()
              .finalizeOriginalNewOnce(
                  inventory.readOriginalNewFinalAdmissionSource(decision),
                  template,
                  accountOpen,
                  issuerOpen,
                  controlledCapacity,
                  deadline())) {
        assertThat(provisionalReplay.exactCanonicalBytes())
            .containsExactly(actual.exactCanonicalBytes());
        assertThat(provisionalReplay.admissionAtMillis()).isEqualTo(actual.admissionAtMillis());
        assertThat(provisionalReplay.continuityExpiresAtMillis())
            .isEqualTo(actual.continuityExpiresAtMillis());
      }
      String beforeCommitRows = sqlImage();
      assertThatThrownBy(
              () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessage("Original NEW transition is not final-readable");
      assertThat(sqlImage()).isEqualTo(beforeCommitRows);
      // The native issuer receipt already exists, but provisional physical final readback is
      // insufficient to settle the issuer obligation before its matching SQL final commit.
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_binding_issuer_index_obligation"
                              + " SET status = 'ACKNOWLEDGED', inventory_revision = inventory_revision + 1"
                              + " WHERE transition_id = ?",
                          fixture.transitionId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .satisfies(
              failure ->
                  assertGuardRefusal(
                      failure,
                      "23514",
                      "gs_original_new_issuer_obligation_source",
                      "Final issuer obligation requires matching committed original NEW and native acknowledgement"));
      assertThat(sqlImage()).isEqualTo(beforeCommitRows);
      // SQL cannot terminalize the genuine provisional source without its complete receipt.
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_binding_transition"
                              + " SET status = 'COMMITTED', inventory_revision = inventory_revision + 1"
                              + " WHERE transition_id = ?",
                          fixture.transitionId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .satisfies(
              failure ->
                  assertGuardRefusal(
                      failure,
                      "23514",
                      "gs_binding_runtime_fence_terminal_owner_required",
                      "A lease-bound runtime fence cannot be released without definitive first-NEW Account abort evidence"));
      assertThat(sqlImage()).isEqualTo(beforeCommitRows);
      // Even genuine final readback values cannot authorize changing the original source.
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_binding_transition SET status = 'COMMITTED',"
                              + " inventory_revision = inventory_revision + 1,"
                              + " original_new_final_session_sha256 = ?, original_new_admission_at_ms = ?,"
                              + " original_new_continuity_expires_at_ms = ?, original_new_session_duration_ms = ?,"
                              + " candidate_account_index_fence = ? WHERE transition_id = ?",
                          actual.finalSessionSha256(),
                          actual.admissionAtMillis(),
                          actual.continuityExpiresAtMillis(),
                          actual.canonicalSessionDurationMillis(),
                          UUID.randomUUID(),
                          fixture.transitionId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .satisfies(
              failure ->
                  assertGuardRefusal(
                      failure,
                      "23514",
                      "gs_original_new_final_transition_source_immutable",
                      "Original NEW final commit cannot change its original transition source"));
      assertThat(sqlImage()).isEqualTo(beforeCommitRows);
      // The pending source fences the actual runtime writer, so a stopped precommit row cannot
      // be produced through this fixture without bypassing the existing owner guard.
      String runningRuntime =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT to_jsonb(runtime)::text FROM game_instances runtime WHERE game_instance_uuid = ?",
                  decision.candidate().gameInstanceId())
              .get(0, String.class);
      assertThatThrownBy(fixture::stopRuntime)
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .satisfies(
              failure ->
                  assertGuardRefusal(
                      failure,
                      "23514",
                      "gs_binding_runtime_row_mutation_fenced",
                      "Game Instance runtime row is fenced by an unresolved lease-bound binding decision"));
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT to_jsonb(runtime)::text FROM game_instances runtime WHERE game_instance_uuid = ?",
                      decision.candidate().gameInstanceId())
                  .get(0, String.class))
          .isEqualTo(runningRuntime);
      assertThat(sqlImage()).isEqualTo(beforeCommitRows);
      assertThat(admin.get(ascii(installation.sessionKey())))
          .containsExactly(actual.exactCanonicalBytes());
      // Advance the global clock through a separate actual SQL owner, not a fabricated revision.
      fixture.prepareOtherControllerNew("unrelated-before-final-commit");
      assertThat(inventory.commitOriginalNewFinalAdmission(actual).status())
          .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
      var committedInventory = inventory.readSnapshot();
      var committedIssuerObligation =
          committedInventory.issuerIndexObligations().stream()
              .filter(obligation -> obligation.transitionId().equals(fixture.transitionId()))
              .findFirst()
              .orElseThrow();
      var committedReservation =
          committedInventory.reservations().stream()
              .filter(reservation -> reservation.transitionId().equals(fixture.transitionId()))
              .findFirst()
              .orElseThrow();
      assertThat(committedIssuerObligation.transitionId()).isEqualTo(fixture.transitionId());
      assertThat(committedIssuerObligation.bindingRef())
          .isEqualTo(decision.candidate().bindingRef());
      assertThat(committedIssuerObligation.bindingGeneration())
          .isEqualTo(source.candidate().transition().bindingGeneration());
      assertThat(committedIssuerObligation.reservationId())
          .isEqualTo(committedReservation.reservationId());
      assertThat(committedIssuerObligation.status())
          .isEqualTo(CanonicalGameplayBindingIssuerIndexObligation.Status.ACKNOWLEDGED);
      assertThat(committedIssuerObligation.localReleaseOperationId()).isNull();
      assertThat(committedIssuerObligation.inventoryRevision())
          .isEqualTo(committedInventory.inventoryRevision());
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayBindingIssuerIndexObligation(
                      committedIssuerObligation.transitionId(),
                      committedIssuerObligation.bindingRef(),
                      committedIssuerObligation.bindingGeneration(),
                      committedIssuerObligation.reservationId(),
                      CanonicalGameplayBindingIssuerIndexObligation.Status.ACKNOWLEDGED,
                      committedIssuerObligation.inventoryRevision(),
                      UUID.randomUUID()))
          .isExactlyInstanceOf(IllegalArgumentException.class)
          .hasMessage("Issuer release linkage must match its obligation status");
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayBindingIssuerIndexObligation(
                      committedIssuerObligation.transitionId(),
                      committedIssuerObligation.bindingRef(),
                      committedIssuerObligation.bindingGeneration(),
                      committedIssuerObligation.reservationId(),
                      CanonicalGameplayBindingIssuerIndexObligation.Status.RELEASED,
                      committedIssuerObligation.inventoryRevision(),
                      null))
          .isExactlyInstanceOf(IllegalArgumentException.class)
          .hasMessage("Issuer release linkage must match its obligation status");
      var committedSource = inventory.readOriginalNewFinalAdmissionSource(decision);
      var committedTransition =
          committedInventory.transitions().stream()
              .filter(transition -> transition.transitionId().equals(fixture.transitionId()))
              .findFirst()
              .orElseThrow();
      assertThat(committedTransition.status())
          .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
      assertThat(committedSource.finalReceipt()).isPresent();
      var committedReceipt = committedSource.finalReceipt().orElseThrow();
      assertThat(committedReceipt.finalSessionSha256()).isEqualTo(actual.finalSessionSha256());
      assertThat(committedReceipt.admissionAtMillis()).isEqualTo(actual.admissionAtMillis());
      assertThat(committedReceipt.continuityExpiresAtMillis())
          .isEqualTo(actual.continuityExpiresAtMillis());
      assertThat(committedReceipt.sessionDurationMillis())
          .isEqualTo(actual.canonicalSessionDurationMillis());
      assertThat(committedSource.regionAcknowledgement().inventoryRevision())
          .isGreaterThan(
              source.regionAcknowledgement().inventoryRevision().add(java.math.BigInteger.ONE));
      assertThat(
              CanonicalGameplayOriginalNewFinalAdmissionCoordinator.sameRegionAcknowledgement(
                  source.regionAcknowledgement(), committedSource.regionAcknowledgement(), true))
          .isTrue();
      assertThat(
              CanonicalGameplayOriginalNewFinalAdmissionCoordinator.sameRegionAcknowledgement(
                  source.regionAcknowledgement(), committedSource.regionAcknowledgement(), false))
          .isFalse();
      // Inspect the production cohort query independently of the migration-test projections.
      // The real committed receipt must remain material in the captured transition source.
      var persistedReceipt =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT original_new_final_session_sha256, original_new_admission_at_ms,"
                      + " original_new_continuity_expires_at_ms, original_new_session_duration_ms"
                      + " FROM game_session_canonical_binding_transition WHERE transition_id = ?",
                  fixture.transitionId());
      assertThat(persistedReceipt.get("original_new_final_session_sha256", String.class))
          .isEqualTo(actual.finalSessionSha256());
      assertThat(persistedReceipt.get("original_new_admission_at_ms", Long.class))
          .isEqualTo(actual.admissionAtMillis());
      assertThat(persistedReceipt.get("original_new_continuity_expires_at_ms", Long.class))
          .isEqualTo(actual.continuityExpiresAtMillis());
      assertThat(persistedReceipt.get("original_new_session_duration_ms", Long.class))
          .isEqualTo(actual.canonicalSessionDurationMillis());
      var revision =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                      + " WHERE singleton_id = 1")
              .get(0, java.math.BigDecimal.class);
      var cohortTransition =
          fixture
              .dsl()
              .fetch(
                  CanonicalGameplayIssuerCohortSql.cohortSql(),
                  CanonicalGameplayIssuerCohortSql.cohortBindings(
                      decision.candidate().issuerId(), revision))
              .stream()
              .filter(
                  row ->
                      "TRANSITION".equals(row.get("source_kind", String.class))
                          && fixture
                              .transitionId()
                              .toString()
                              .equals(row.get("source_key", String.class)))
              .findFirst()
              .orElseThrow();
      var capturedTransition =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(cohortTransition.get("source_row_json", String.class));
      for (String field :
          List.of(
              "original_new_final_session_sha256",
              "original_new_admission_at_ms",
              "original_new_continuity_expires_at_ms",
              "original_new_session_duration_ms")) {
        assertThat(capturedTransition.hasNonNull(field))
            .as("material final receipt field %s", field)
            .isTrue();
        assertThat(capturedTransition.get(field).asText())
            .isEqualTo(
                java.util.Objects.requireNonNull(
                        persistedReceipt.get(field), "Persisted final receipt field " + field)
                    .toString());
      }
      var first = reader().readActualOriginalFinalAdmission(credential.selector(), "test");
      assertThat(first.admissionAtMillis()).isEqualTo(actual.admissionAtMillis());
      assertThat(first.continuityExpiresAtMillis()).isEqualTo(actual.continuityExpiresAtMillis());
      assertThat(first.canonicalSessionSha256()).isEqualTo(actual.finalSessionSha256());
      assertThat(first.actualAnchors().rebindHandleSha256())
          .isEqualTo(credential.selector().rebindHandleSha256());
      String rows = sqlImage();
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_canonical_binding_issuer_index_obligation"
                          + " WHERE transition_id = ?",
                      fixture.transitionId())
                  .get(0, String.class))
          .isEqualTo("ACKNOWLEDGED");
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_binding_issuer_index_obligation SET status = 'REQUIRED'"
                              + " WHERE transition_id = ?",
                          fixture.transitionId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .satisfies(
              failure ->
                  assertGuardRefusal(
                      failure,
                      "23514",
                      "gs_original_new_issuer_obligation_retained",
                      "Final original NEW issuer acknowledgement is retained and immutable"));
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_issuer_binding_local_acknowledgement"
                              + " SET acknowledgement_generation = acknowledgement_generation + 1"
                              + " WHERE transition_id = ?",
                          fixture.transitionId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .satisfies(
              failure ->
                  assertGuardRefusal(
                      failure,
                      "23514",
                      null,
                      "issuer binding-local acknowledgements are immutable"));
      assertThat(sqlImage()).isEqualTo(rows);
      // Once committed, the actual receipt and every original transition field remain immutable.
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_binding_transition SET candidate_account_index_fence = ?"
                              + " WHERE transition_id = ?",
                          UUID.randomUUID(),
                          fixture.transitionId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .satisfies(
              failure ->
                  assertGuardRefusal(
                      failure,
                      "23514",
                      "gs_original_new_final_terminal_source_immutable",
                      "Committed original NEW final source is immutable"));
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_binding_transition SET original_new_final_session_sha256 = NULL,"
                              + " original_new_admission_at_ms = NULL, original_new_continuity_expires_at_ms = NULL,"
                              + " original_new_session_duration_ms = NULL WHERE transition_id = ?",
                          fixture.transitionId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .satisfies(
              failure ->
                  assertGuardRefusal(
                      failure,
                      "23514",
                      "gs_original_new_final_terminal_source_immutable",
                      "Committed original NEW final source is immutable"));
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_binding_transition SET inventory_revision = inventory_revision - 1"
                              + " WHERE transition_id = ?",
                          fixture.transitionId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .satisfies(
              failure ->
                  assertGuardRefusal(
                      failure,
                      "23514",
                      "gs_original_new_final_terminal_source_immutable",
                      "Committed original NEW final source is immutable"));
      assertThat(sqlImage()).isEqualTo(rows);
      Map<String, String> physical = physicalImage();
      assertThatThrownBy(
              () ->
                  reader()
                      .readActualOriginalFinalAdmission(credential.wrongTokenSelector(), "test"))
          .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessage(
              "Finalized session authentication values differ from Account's original anchors");
      assertThat(sqlImage()).isEqualTo(rows);
      assertThat(physicalImage()).isEqualTo(physical);
      var recoveredInventory = new CanonicalGameplayBindingInventoryRepository(fixture.dsl());
      var recoveredSnapshot = recoveredInventory.readSnapshot();
      assertThat(recoveredSnapshot.issuerIndexObligations()).contains(committedIssuerObligation);
      var recoveredSource = recoveredInventory.readOriginalNewFinalAdmissionSource(decision);
      assertThat(recoveredSource.transitionStatus())
          .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
      assertThat(recoveredSource.finalReceipt()).isPresent();
      try (var replay =
          finalOwner()
              .finalizeOriginalNewOnce(
                  recoveredSource,
                  template,
                  accountOpen,
                  issuerOpen,
                  controlledCapacity,
                  deadline())) {
        assertThat(replay.exactCanonicalBytes()).containsExactly(actual.exactCanonicalBytes());
        assertThat(recoveredInventory.commitOriginalNewFinalAdmission(replay).status())
            .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
      }
      assertThat(reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isEqualTo(first);
      assertThat(sqlImage()).isEqualTo(rows);
      assertThat(physicalImage()).isEqualTo(physical);
    }
  }

  @Test
  void genuineAccountAndIssuerAcknowledgementsCannotSubstituteForMissingRegionAcknowledgement()
      throws Exception {
    repairAccountMember();
    String rows = sqlImage();
    Map<String, String> physical = physicalImage();
    assertThatThrownBy(() -> inventory.readOriginalNewFinalAdmissionSource(decision))
        .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessage("Original NEW region acknowledgement differs from its controller");
    var recovered = new CanonicalGameplayBindingInventoryRepository(fixture.dsl());
    assertThatThrownBy(() -> recovered.readOriginalNewFinalAdmissionSource(decision))
        .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessage("Original NEW region acknowledgement differs from its controller");
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
    bridge();
    assertThat(recovered.readOriginalNewFinalAdmissionSource(decision).transitionStatus())
        .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL);
  }

  @Test
  void missingOrChangedIndexesAndBarriersNeverRecreateCommittedPostimagesOrExtendTimes()
      throws Exception {
    repairAccountMember();
    bridge();
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source, template, accountOpen, issuerOpen, controlledCapacity, deadline())) {
      inventory.commitOriginalNewFinalAdmission(actual);
      var committed = inventory.readOriginalNewFinalAdmissionSource(decision);
      byte[] finalBytes = actual.exactCanonicalBytes();
      String rows = sqlImage();
      for (String key :
          List.of(
              installation.characterIndexKey(),
              installation.accountTenantIndexKey(),
              installation.tenantIndexKey(),
              accountOpen.key(),
              issuerKey(issuerOpen))) {
        byte[] retained = admin.dump(ascii(key));
        admin.del(ascii(key));
        assertThatThrownBy(
                () ->
                    finalOwner()
                        .finalizeOriginalNewOnce(
                            committed,
                            template,
                            accountOpen,
                            issuerOpen,
                            controlledCapacity,
                            deadline()))
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Original NEW final admission Redis readback is unavailable");
        assertThat(admin.exists(ascii(key))).isZero();
        assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(finalBytes);
        assertThat(sqlImage()).isEqualTo(rows);
        admin.restore(ascii(key), 0L, retained);
      }
      admin.hset(ascii(accountOpen.key()), ascii("state"), ascii("CLOSED"));
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          committed,
                          template,
                          accountOpen,
                          issuerOpen,
                          controlledCapacity,
                          deadline()))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Original NEW final admission Redis readback is unavailable");
      assertThat(admin.hget(ascii(accountOpen.key()), ascii("state")))
          .containsExactly(ascii("CLOSED"));
      assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(finalBytes);
      assertThat(sqlImage()).isEqualTo(rows);
      admin.flushdb();
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          committed,
                          template,
                          accountOpen,
                          issuerOpen,
                          controlledCapacity,
                          deadline()))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Original NEW final admission Redis readback is unavailable");
      assertThatThrownBy(
              () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessage("Finalized canonical session is absent, non-string, or expiring");
      assertThat(admin.dbsize()).isZero();
      assertThat(sqlImage()).isEqualTo(rows);
    }
  }

  @Test
  void provisionalFinalCasRefusesMissingIndexesAndChangedBarriersWithoutAdmission()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      String rows = sqlImage();
      byte[] candidateBytes = installation.exactCandidateBytes();
      for (String key :
          List.of(
              installation.characterIndexKey(),
              installation.accountTenantIndexKey(),
              installation.tenantIndexKey(),
              accountOpen.key(),
              issuerKey(issuerOpen))) {
        byte[] retained = admin.dump(ascii(key));
        admin.del(ascii(key));
        assertThatThrownBy(
                () ->
                    finalOwner()
                        .finalizeOriginalNewOnce(
                            source,
                            template,
                            accountOpen,
                            issuerOpen,
                            controlledCapacity,
                            deadline()))
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Original NEW final admission Redis readback is unavailable");
        assertThat(admin.exists(ascii(key))).isZero();
        assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(candidateBytes);
        assertThat(sqlImage()).isEqualTo(rows);
        admin.restore(ascii(key), 0L, retained);
      }
      admin.hset(ascii(issuerKey(issuerOpen)), ascii("state"), ascii("CLOSED"));
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          source,
                          template,
                          accountOpen,
                          issuerOpen,
                          controlledCapacity,
                          deadline()))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Original NEW final admission Redis readback is unavailable");
      assertThat(admin.hget(ascii(issuerKey(issuerOpen)), ascii("state")))
          .containsExactly(ascii("CLOSED"));
      assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(candidateBytes);
      assertThat(sqlImage()).isEqualTo(rows);
      fixture.moveIssuerSourceCheckpoint();
      assertThatThrownBy(this::issuerOpen)
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Issuer tenant admission barrier is unavailable");
      assertThat(sqlImage()).isEqualTo(rows);
      assertThatThrownBy(
              () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessage("Original NEW transition is not final-readable");
    }
  }

  @Test
  void retainedRevisionAdvanceAcrossPhysicalReadIsUnavailableAndRetryCanRead() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source, template, accountOpen, issuerOpen, controlledCapacity, deadline())) {
      inventory.commitOriginalNewFinalAdmission(actual);
      var assignment = tags.assignmentForTransition(fixture.transitionId());
      CanonicalGameplayTenantGameplayTagRepository racingTags = mock();
      // Controlled race injection after the first SQL snapshot and before the actual Redis read.
      // Advance retained accounting only, through the installed PostgreSQL guards. The immutable
      // original candidate, receipt and persisted tag assignment retain their exact values.
      when(racingTags.assignmentForTransition(fixture.transitionId()))
          .thenAnswer(
              ignored -> {
                fixture
                    .dsl()
                    .transaction(
                        configuration -> {
                          var tx = org.jooq.impl.DSL.using(configuration);
                          var revision =
                              tx.fetchSingle(
                                      "UPDATE game_session_canonical_binding_inventory_clock"
                                          + " SET inventory_revision = inventory_revision + 1"
                                          + " WHERE singleton_id = 1 RETURNING inventory_revision")
                                  .get(0, java.math.BigDecimal.class);
                          for (String table :
                              List.of(
                                  "game_session_canonical_binding_transition",
                                  "game_session_canonical_gameplay_binding_inventory",
                                  "game_session_canonical_issuer_partition_reservation")) {
                            assertThat(
                                    tx.execute(
                                        "UPDATE "
                                            + table
                                            + " SET inventory_revision = ? WHERE transition_id = ?",
                                        revision,
                                        fixture.transitionId()))
                                .isEqualTo(1);
                          }
                        });
                return assignment;
              });
      var racingReader =
          new CanonicalGameplayOriginalNewFinalAdmissionReadRepository(
              fixture.dsl(), ownerClient, racingTags, "test", CLOCK);
      Map<String, String> physical = physicalImage();
      assertThatThrownBy(
              () -> racingReader.readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Original NEW retained SQL source changed during Redis readback");
      String retainedRows = sqlImage();
      assertThat(
              reader()
                  .readActualOriginalFinalAdmission(credential.selector(), "test")
                  .canonicalSessionSha256())
          .isEqualTo(actual.finalSessionSha256());
      assertThat(sqlImage()).isEqualTo(retainedRows);
      assertThat(physicalImage()).isEqualTo(physical);
    }
  }

  @Test
  void changedPersistedSessionCannotRecoverAgainstTheActualCommittedReceipt() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source, template, accountOpen, issuerOpen, controlledCapacity, deadline())) {
      inventory.commitOriginalNewFinalAdmission(actual);
      var committed =
          new CanonicalGameplayBindingInventoryRepository(fixture.dsl())
              .readOriginalNewFinalAdmissionSource(decision);
      String rows = sqlImage();
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      var changed =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              json.readTree(actual.exactCanonicalBytes());
      changed.put("admissionAt", Long.toString(actual.admissionAtMillis() + 1L));
      changed.put(
          "continuityBindingExpiresAt", Long.toString(actual.continuityExpiresAtMillis() + 1L));
      byte[] changedBytes =
          net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
              json.writeValueAsString(changed));
      admin.set(ascii(installation.sessionKey()), changedBytes);
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          committed,
                          template,
                          accountOpen,
                          issuerOpen,
                          controlledCapacity,
                          deadline()))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Original NEW final admission Redis readback is unavailable");
      assertThatThrownBy(
              () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessage("Finalized session differs from the committed original controller receipt");
      assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(changedBytes);
      assertThat(sqlImage()).isEqualTo(rows);
      admin.set(ascii(installation.sessionKey()), actual.exactCanonicalBytes());
      assertThat(
              reader()
                  .readActualOriginalFinalAdmission(credential.selector(), "test")
                  .admissionAtMillis())
          .isEqualTo(actual.admissionAtMillis());
      fixture.stopRuntime();
      String stoppedRows = sqlImage();
      Map<String, String> stoppedPhysical = physicalImage();
      // Reuse the genuine final readback after COMMITTED: this retry has no physical SQL update
      // for the V1050 trigger to reject, so the Java runtime guard must refuse it explicitly.
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayBindingInventoryRepository(fixture.dsl())
                      .commitOriginalNewFinalAdmission(actual))
          .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessage("Original NEW final admission commit requires its current RUNNING runtime");
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayBindingInventoryRepository(fixture.dsl())
                      .readOriginalNewFinalAdmissionSource(decision))
          .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessage("Original NEW final admission source requires its current RUNNING runtime");
      assertThat(sqlImage()).isEqualTo(stoppedRows);
      assertThat(physicalImage()).isEqualTo(stoppedPhysical);
      assertThat(admin.get(ascii(installation.sessionKey())))
          .containsExactly(actual.exactCanonicalBytes());
    }
  }

  private static void assertGuardRefusal(
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

  private void repairAccountMember() {
    new CanonicalGameplayAccountMemberRepairOwner(fixture.dsl(), ownerClient, catalog)
        .repair(fixture.transitionId());
  }

  private void bridge() {
    bridge(true);
  }

  private void bridge(boolean authorizeCapacity) {
    regionBinding =
        CanonicalGameplayRegionOwnerCompositionFixture.installExistingOriginalCandidate(
            fixture.dsl(),
            ownerClient,
            fixture.transitionId(),
            sources.request,
            sources.resolver,
            sources.entity);
    assertThat(regionBinding.owner().bridge(regionBinding.installation()).obligation())
        .isEqualTo(regionBinding.acknowledgement().obligation());
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

  private CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen() {
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
  private CanonicalGameplayAccountInitialOpenProof.CurrentOpen controlledAccountOpen() {
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
    long now = CLOCK.millis();
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

  private String issuerKey(CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback readback) {
    var value = readback.value();
    return CanonicalGameplayIssuerAdmissionBarrierKey.forTarget(
        value.issuerId(),
        value.tenantId(),
        value.playableStateNamespaceId(),
        value.tenantGameplayTag());
  }

  private CanonicalGameplayOriginalNewCandidateRedisOwner candidateOwner(
      CanonicalGameplayBindingInventoryRepository repository) {
    return CanonicalGameplayOriginalNewCandidateRedisOwnerFactory.create(
        ownerClient, catalog, repository, tags);
  }

  private net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus
      replaceRegionOwner(String successor) {
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

  private CanonicalGameplayOriginalNewCapacityExecutionOwner capacityOwner() {
    return new CanonicalGameplayOriginalNewCapacityExecutionOwner(
        fixture.dsl(), ownerClient, catalog, CLOCK, tags);
  }

  private String capacityKey(CanonicalGameplayTenantGameplayTagRepository.Assignment assignment) {
    return "session:game:capacity-execution:{"
        + assignment.tenantGameplayTag()
        + "}:"
        + controlledCapacity.request().admissionAttemptId();
  }

  private void commitHistoricalSplitReceipt(
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

  private void controlledRemoveServingKeys(
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

  private ControlledCapacityFinalOwner finalOwner() {
    return new ControlledCapacityFinalOwner();
  }

  private final class ControlledCapacityFinalOwner {
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

  private CanonicalGameplayOriginalNewFinalAdmissionReadRepository reader() {
    return reader(CLOCK);
  }

  private CanonicalGameplayOriginalNewFinalAdmissionReadRepository reader(Clock readClock) {
    return new CanonicalGameplayOriginalNewFinalAdmissionReadRepository(
        fixture.dsl(),
        ownerClient,
        new CanonicalGameplayTenantGameplayTagRepository(fixture.dsl(), "test", 1),
        "test",
        readClock);
  }

  private String sqlImage() {
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

  private Map<String, String> physicalImage() {
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

  private static Candidate candidate() {
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

  private RedisClient client(String username, String password) {
    var uri =
        RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(6379))
            .withTimeout(Duration.ofSeconds(5));
    if (username != null) uri.withAuthentication(username, password);
    var client = RedisClient.create(uri.build());
    client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return client;
  }

  private static long deadline() {
    return CLOCK.millis() + 10_000L;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
