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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution;
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
class CanonicalGameplayOriginalNewCapacityExecutionOwnerIntegrationTest
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
                    "SELECT 1 FROM game_session_capacity_evidence_charge WHERE admission_attempt_id"
                        + " = ?",
                    unsupported.admissionAttemptId()))
        .isNull();
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE tenant_id ="
                        + " ?",
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
                    "SELECT 1 FROM game_session_capacity_evidence_charge WHERE admission_attempt_id"
                        + " = ?",
                    refusedRequest.admissionAttemptId()))
        .isNull();
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT 1 FROM game_session_original_new_capacity_execution WHERE"
                        + " admission_attempt_id = ?",
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
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE"
                        + " admission_attempt_id = ?",
                    controlledCapacity.request().admissionAttemptId())
                .get(0, String.class))
        .isEqualTo("AUTHORIZED");
    assertThat(admin.type(ascii(capacityKey(assignment)))).isEqualTo("hash");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE tenant_id ="
                        + " ?",
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
                      "SELECT status FROM game_session_original_new_capacity_execution WHERE"
                          + " execution_digest = ?",
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
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE"
                        + " execution_digest = ?",
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
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE"
                        + " execution_digest = ?",
                    controlledCapacity.digest()))
        .isNull();
    assertThat(admin.type(ascii(capacityKey))).isEqualTo("none");
    assertThat(sqlImage()).isEqualTo(sqlBefore);
    assertThat(physicalImage()).isEqualTo(physicalBefore);
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
                  "SELECT status, xmin::text AS revision FROM"
                      + " game_session_original_new_capacity_execution WHERE execution_digest = ?",
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
                      "SELECT status, xmin::text AS revision FROM"
                          + " game_session_original_new_capacity_execution WHERE execution_digest ="
                          + " ?",
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
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE"
                        + " execution_digest=?",
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
