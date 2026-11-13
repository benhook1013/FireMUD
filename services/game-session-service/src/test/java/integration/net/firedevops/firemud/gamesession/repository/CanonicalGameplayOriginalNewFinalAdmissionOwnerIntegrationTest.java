package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountIndexMember;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIssuerIndexObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
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
class CanonicalGameplayOriginalNewFinalAdmissionOwnerIntegrationTest
    extends CanonicalGameplayOriginalNewFinalAdmissionOwnerIntegrationTestSupport {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @Override
  PostgreSQLContainer<?> postgresContainer() {
    return POSTGRES;
  }

  @Override
  GenericContainer<?> redisContainer() {
    return REDIS;
  }

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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"1,134217728", "128,1048576"})
  void lastPrechargedCapacityEvidenceSlotSurvivesFullBudgetAndSameAttemptReplay(
      int maximumRecords, long maximumBindingBytes) throws Exception {
    repairAccountMember();
    bridge(false, maximumRecords, maximumBindingBytes);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var owner = capacityOwner();
    var selected =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
            controlledCapacity.selectedOwnerId(), controlledCapacity.selectedOwnerFence());

    // The initial charge happened in bridge(); the exact replay is idempotent even at capacity.
    owner.precharge(source, controlledCapacity.request(), selected, deadline());
    var original = controlledCapacity.request();
    var unsupported =
        new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request(
                original.identity(),
                UUID.randomUUID(),
                original.capacityRearmFence(),
                original.targetNamespace(),
                original.context(),
                original.operation(),
                original.priorShape(),
                new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Shape(
                    2, 0, 0),
                original.evidence(),
                2L,
                null)
            .withDigest();
    unsupported.requireCommittable();
    assertThatThrownBy(() -> owner.precharge(source, unsupported, selected, deadline()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Exact original NEW capacity request required");
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT 1 FROM game_session_capacity_evidence_charge WHERE admission_attempt_id = ?",
                    unsupported.admissionAttemptId()))
        .isNull();
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE tenant_id = ?",
                    controlledCapacity.request().identity().tenantId())
                .get(0, Long.class))
        .isEqualTo(1L);

    var refusedRequest = anotherAttempt(controlledCapacity.request());
    assertThatThrownBy(() -> owner.precharge(source, refusedRequest, selected, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT 1 FROM game_session_capacity_evidence_charge WHERE admission_attempt_id = ?",
                    refusedRequest.admissionAttemptId()))
        .isNull();
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT 1 FROM game_session_original_new_capacity_execution WHERE admission_attempt_id = ?",
                    refusedRequest.admissionAttemptId()))
        .isNull();
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    String refusedKey =
        "session:game:capacity-execution:{"
            + assignment.tenantGameplayTag()
            + "}:"
            + refusedRequest.admissionAttemptId();
    assertThat(admin.type(ascii(refusedKey))).isEqualTo("none");

    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(controlledCapacity)) {
      owner.authorize(
          source, template, controlledAccountOpen(), issuerOpen(), controlledCapacity, deadline());
    }
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE admission_attempt_id = ?",
                    controlledCapacity.request().admissionAttemptId())
                .get(0, String.class))
        .isEqualTo("AUTHORIZED");
    assertThat(admin.type(ascii(capacityKey(assignment)))).isEqualTo("hash");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE tenant_id = ?",
                    controlledCapacity.request().identity().tenantId())
                .get(0, Long.class))
        .isEqualTo(1L);
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
                        changed, source.candidate(), assignment, UUID.randomUUID(), deadline()))
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
  void invalidInstallInputsRefuseBeforeCreatingSqlDispatchCustody() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    String sqlBefore = sqlImage();
    Map<String, String> physicalBefore = physicalImage();
    String capacityKey = capacityKey(assignment);

    assertThatThrownBy(
            () ->
                capacityOwner()
                    .authorize(
                        source, null, accountOpen, issuerOpen, controlledCapacity, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                    controlledCapacity.digest()))
        .isNull();
    assertThat(admin.type(ascii(capacityKey))).isEqualTo("none");
    assertThat(sqlImage()).isEqualTo(sqlBefore);
    assertThat(physicalImage()).isEqualTo(physicalBefore);

    var mismatchedTemplateExecution =
        new net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution(
            controlledCapacity.request(),
            controlledCapacity.reservation(),
            controlledCapacity.transitionId(),
            controlledCapacity.candidateDigest(),
            controlledCapacity.selectedOwnerId() + "-other",
            controlledCapacity.selectedOwnerFence());
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var mismatchedTemplate = template.bindCapacityExecution(mismatchedTemplateExecution)) {
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .authorize(
                          source,
                          mismatchedTemplate,
                          accountOpen,
                          issuerOpen,
                          controlledCapacity,
                          deadline()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                    controlledCapacity.digest()))
        .isNull();
    assertThat(admin.type(ascii(capacityKey))).isEqualTo("none");
    assertThat(sqlImage()).isEqualTo(sqlBefore);
    assertThat(physicalImage()).isEqualTo(physicalBefore);
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
  void historicalFinalReceiptWithoutCapacityEvidenceCannotMintCurrentSource() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
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
      inventory.commitOriginalNewFinalAdmission(readback);
    }

    fixture
        .dsl()
        .transaction(
            configuration -> {
              var historical = org.jooq.impl.DSL.using(configuration);
              // Reproduce the retained pre-V1063 receipt shape without granting a normal writer
              // this mutation.
              historical.execute("SET LOCAL session_replication_role = replica");
              assertThat(
                      historical.execute(
                          "UPDATE game_session_canonical_binding_transition"
                              + " SET original_new_capacity_execution_digest = NULL WHERE transition_id = ?",
                          fixture.transitionId()))
                  .isEqualTo(1);
            });

    String retained = sqlImage();
    var physical = physicalImage();
    assertThatThrownBy(() -> inventory.readOriginalNewFinalAdmissionSource(decision))
        .hasMessageContaining(
            "Historical original NEW receipt lacks exact capacity execution evidence");
    assertThat(sqlImage()).isEqualTo(retained);
    assertThat(physicalImage()).isEqualTo(physical);
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
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_session_original_new_capacity_execution"
                            + " SET status = 'CLOSED', terminal_readback = ? WHERE execution_digest = ?",
                        new byte[] {1},
                        controlledCapacity.digest()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("retained final receipt");
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
  void committedFinalAdmissionReplayAtomicallyRetainsPreviouslySplitExecutionEvidence()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
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
      commitHistoricalSplitReceipt(readback);
      var authorized =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, terminal_readback FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                  readback.execution().digest());
      assertThat(authorized.get("status", String.class)).isEqualTo("AUTHORIZED");
      assertThat(authorized.get("terminal_readback", byte[].class)).isNull();

      inventory.commitOriginalNewFinalAdmission(readback);

      var retainedRow =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, terminal_readback FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                  readback.execution().digest());
      assertThat(retainedRow.get("status", String.class)).isEqualTo("COMMITTED");
      assertThat(retainedRow.get("terminal_readback", byte[].class)).isNotNull();
      var retained =
          capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
      assertThat(retained.disposition()).isEqualTo("COMMITTED");
      assertThat(retained.physicalDigest()).isEqualTo("sha256:" + readback.finalSessionSha256());
      byte[] exactProof = retained.canonicalReadback();

      inventory.commitOriginalNewFinalAdmission(readback);

      assertThat(
              capacityOwner()
                  .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                  .canonicalReadback())
          .containsExactly(exactProof);
    }
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
  void concurrentExactClosuresReplayIdenticalRetainedProof() throws Exception {
    repairAccountMember();
    bridge();
    var firstOwner = capacityOwner();
    var secondOwner = capacityOwner();
    var start = new java.util.concurrent.CountDownLatch(1);
    var callers = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var first =
          callers.submit(
              () -> {
                start.await();
                firstOwner.closeOriginalAttempt(
                    controlledCapacity.request(), controlledCapacity.reservation());
                return true;
              });
      var second =
          callers.submit(
              () -> {
                start.await();
                secondOwner.closeOriginalAttempt(
                    controlledCapacity.request(), controlledCapacity.reservation());
                return true;
              });
      start.countDown();
      assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    } finally {
      callers.shutdownNow();
    }

    var retained =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(retained.disposition()).isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
    assertThat(retained.canonicalReadback()).isNotEmpty();
    var row =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT status, terminal_readback FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                retained.execution().digest());
    assertThat(row.get("status", String.class)).isEqualTo("CLOSED");
    assertThat(row.get("terminal_readback", byte[].class))
        .containsExactly(retained.canonicalReadback());
  }

  @Test
  void closeReplayAfterServingRetirementUsesRetainedClosedProof() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    var owner = capacityOwner();
    owner.closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var retained = owner.readExact(controlledCapacity.request(), controlledCapacity.reservation());
    controlledRemoveServingKeys(source, assignment);
    String rows = sqlImage();
    var physical = physicalImage();

    owner.closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());

    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
    var replay = owner.readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(replay.disposition()).isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
    assertThat(replay.canonicalReadback()).containsExactly(retained.canonicalReadback());
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
      assertThat(barrier.readFinalObservation(intent.operationId())).isEmpty();
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

  @Test
  void acknowledgedOriginInstallResumesExactFinalWithoutRedisInstallReplay() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var origin = prepareControlledOrigin(source);
    var account = controlledAccountOpen();
    var issuer = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var bound = template.bindCapacityExecution(controlledCapacity)) {
      capacityOwner()
          .authorizeWithOrigin(
              source, bound, account, issuer, controlledCapacity, deadline(), origin);
      var assignment = tags.assignmentForTransition(fixture.transitionId());
      var executionKey = ascii(capacityKey(assignment));
      assertThat(admin.hget(executionKey, ascii("state"))).containsExactly(ascii("AUTHORIZED"));
      assertThat(admin.get(ascii(installation.sessionKey())))
          .containsExactly(source.candidate().canonicalBytes());
      var executionBeforeRetry =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, xmin::text AS revision FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                  controlledCapacity.digest());
      assertThat(executionBeforeRetry.get("status", String.class)).isEqualTo("AUTHORIZED");

      // A recreated owner resumes after acknowledged INSTALL and before the first FINAL.
      var restarted = capacityOwner();
      String retainedBeforeOrdinary = sqlImage();
      Map<String, String> physicalBeforeOrdinary = physicalImage();
      assertThatThrownBy(() -> restarted.requireOrdinaryAdmission(origin.request(), deadline()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Exact capacity origin custody unavailable");
      assertThatThrownBy(
              () ->
                  restarted.authorize(
                      source, bound, account, issuer, controlledCapacity, deadline()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Exact capacity origin custody unavailable");
      assertThat(sqlImage()).isEqualTo(retainedBeforeOrdinary);
      assertThat(physicalImage()).isEqualTo(physicalBeforeOrdinary);
      admin.aclSetuser(PRINCIPAL, new AclSetuserArgs().removeCommand(CommandType.EVALSHA));
      try {
        byte[] originKey = ascii(origin.redisKey());
        admin.pexpire(originKey, 60_000);
        assertThatThrownBy(
                () ->
                    restarted.authorizeWithOrigin(
                        source, bound, account, issuer, controlledCapacity, deadline(), origin))
            .isInstanceOf(IllegalStateException.class);
        assertThat(admin.persist(originKey)).isTrue();
        restarted.authorizeWithOrigin(
            source, bound, account, issuer, controlledCapacity, deadline(), origin);
      } finally {
        admin.aclSetuser(PRINCIPAL, new AclSetuserArgs().addCommand(CommandType.EVALSHA));
      }
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT status, xmin::text AS revision FROM game_session_original_new_capacity_execution WHERE execution_digest = ?",
                      controlledCapacity.digest()))
          .isEqualTo(executionBeforeRetry);
      regionBinding.owner().revalidateOriginalNewFinalBinding(regionBinding.installation(), source);
      var finalOwner =
          restarted.bindFinalOwnerToOrigin(
              new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                  ownerClient, catalog, CLOCK, tags),
              origin,
              deadline());
      try (var actual =
          finalOwner.finalizeOriginalNewOnce(
              source, bound, account, issuer, controlledCapacity, deadline())) {
        inventory.commitOriginalNewFinalAdmission(actual);
        assertThat(admin.get(ascii(installation.sessionKey())))
            .containsExactly(actual.exactCanonicalBytes());
        assertThat(admin.hget(executionKey, ascii("state"))).containsExactly(ascii("COMMITTED"));
      }
    }
  }

  @Test
  void expiredRedisBudgetCannotDispatchOriginWithLongerCallerDeadline() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var origin = prepareControlledOrigin(source);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    Clock skewedClock = Clock.offset(CLOCK, Duration.ofSeconds(-10));
    var account = controlledAccountOpen(skewedClock);
    var issuer = issuerOpen();
    var skewedOwner =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            fixture.dsl(), ownerClient, catalog, skewedClock, tags);
    long callerDeadline = CLOCK.millis() + 30_000L;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var bound = template.bindCapacityExecution(controlledCapacity)) {
      assertThatThrownBy(
              () ->
                  skewedOwner.authorizeWithOrigin(
                      source, bound, account, issuer, controlledCapacity, callerDeadline, origin))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(admin.hget(ascii(origin.redisKey()), ascii("state"))).containsExactly(ascii("OPEN"));
    assertThat(admin.exists(ascii(capacityKey(assignment)))).isZero();
    var repository = new CanonicalGameplayCapacityOriginRepository(fixture.dsl());
    assertThat(repository.read(origin).state())
        .isEqualTo(CanonicalGameplayCapacityOrigin.State.DISPATCH_CLAIMED);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo("INSTALL_DISPATCHED");
  }

  @Test
  void originRecoveryUsesTheTwoBackgroundSlotsAndTheFourOperationCeiling() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var origin = prepareControlledOrigin(source);
    var owner = capacityOwner();
    var permit = owner.beginOriginObservation(origin.request(), deadline());
    var refusal =
        new AccountCapacityOriginResolution(
            origin.request(),
            AccountCapacityOriginResolution.Status.NO_ACCEPT_RETAINED,
            null,
            UUID.randomUUID());
    var backgroundEntered = new CountDownLatch(2);
    var foregroundEntered = new CountDownLatch(2);
    var release = new CompletableFuture<Void>();
    try (var callers = Executors.newFixedThreadPool(4)) {
      java.util.function.Function<CanonicalGameplayOriginalNewCapacityExecutionBudget, Boolean>
          background =
              budget -> {
                backgroundEntered.countDown();
                release.join();
                return true;
              };
      java.util.function.Function<CanonicalGameplayOriginalNewCapacityExecutionBudget, Boolean>
          foreground =
              budget -> {
                foregroundEntered.countDown();
                release.join();
                return true;
              };
      var firstBackground =
          callers.submit(
              () ->
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                      CLOCK, deadline(), background));
      var secondBackground =
          callers.submit(
              () ->
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                      CLOCK, deadline(), background));
      try {
        assertThat(backgroundEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> owner.beginOriginObservation(origin.request(), deadline()))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> owner.receiveOriginResolution(refusal, permit, deadline()))
            .isInstanceOf(IllegalStateException.class);
        var firstForeground =
            callers.submit(
                () ->
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                        CLOCK, deadline(), foreground));
        var secondForeground =
            callers.submit(
                () ->
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                        CLOCK, deadline(), foreground));
        assertThat(foregroundEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(
                () ->
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                        CLOCK, deadline(), budget -> true))
            .isInstanceOf(IllegalStateException.class);
        release.complete(null);
        assertThat(firstForeground.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(secondForeground.get(2, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.complete(null);
      }
      assertThat(firstBackground.get(2, TimeUnit.SECONDS)).isTrue();
      assertThat(secondBackground.get(2, TimeUnit.SECONDS)).isTrue();
    }
    Clock afterPermit =
        Clock.offset(CLOCK, Duration.between(CLOCK.instant(), permit.deadline()).plusMillis(1));
    var resumedOwner =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            fixture.dsl(), ownerClient, catalog, afterPermit, tags);
    long resumedDeadline = afterPermit.millis() + 10_000L;
    var nextPermit = resumedOwner.beginOriginObservation(origin.request(), resumedDeadline);
    assertThat(nextPermit.ordinal()).isEqualTo(permit.ordinal() + 1);
    resumedOwner.receiveOriginResolution(refusal, nextPermit, resumedDeadline);
    assertThat(admin.hget(ascii(origin.redisKey()), ascii("state")))
        .containsExactly(ascii("DENIED"));
  }
}
