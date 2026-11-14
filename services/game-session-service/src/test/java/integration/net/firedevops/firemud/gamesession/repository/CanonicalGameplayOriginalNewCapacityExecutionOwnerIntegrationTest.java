package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Physical component tests for original NEW capacity dispatch custody and owner-movement refusal.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayOriginalNewCapacityExecutionOwnerIntegrationTest
    extends CanonicalGameplayOriginalNewFinalAdmissionOwnerTestSupport {

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
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void abortedOriginalRetainsPhysicalClosureAcrossAbortOrdering(boolean closeBeforeAbort)
      throws Exception {
    repairAccountMember();
    bridge();
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
    var terminal =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT status, terminal_readback FROM game_session_original_new_capacity_execution");
    assertThat(terminal.get("status", String.class)).isEqualTo("CLOSED");
    assertThat(terminal.get("terminal_readback", byte[].class)).isNotEmpty();
    byte[] tombstone =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(
            admin,
            ascii(
                capacityKey(tags.assignmentForRetainedCapacityExecution(fixture.transitionId()))));
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
  void lostDispatchCommitReplyBeforeRedisSendRetainsIndeterminateOriginal() throws Exception {
    repairAccountMember();
    bridge(false);
    String schema = fixture.dsl().fetchSingle("SELECT current_schema()").get(0, String.class);
    DriverManagerDataSource raw = new DriverManagerDataSource();
    raw.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = POSTGRES.getJdbcUrl();
    raw.setUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema);
    raw.setUsername(POSTGRES.getUsername());
    raw.setPassword(POSTGRES.getPassword());
    var stoppedAfterCommit = new java.util.concurrent.atomic.AtomicBoolean();
    var stoppingDataSource =
        new org.springframework.jdbc.datasource.DelegatingDataSource(raw) {
          @Override
          public java.sql.Connection getConnection() throws java.sql.SQLException {
            return failDispatchCommitReplyAfterMarker(
                super.getConnection(), raw, controlledCapacity.digest(), stoppedAfterCommit);
          }
        };
    var stoppingDsl = org.jooq.impl.DSL.using(stoppingDataSource, org.jooq.SQLDialect.POSTGRES);
    var stoppingOwner =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            stoppingDsl, ownerClient, catalog, CLOCK, tags);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    var physicalBefore = physicalImage();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var rawTemplate = credential.template();
        var template = rawTemplate.bindCapacityExecution(controlledCapacity)) {
      assertThatThrownBy(
              () ->
                  stoppingOwner.authorize(
                      source, template, accountOpen, issuerOpen, controlledCapacity, deadline()))
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(stoppedAfterCommit).isTrue();
    String originalKey = capacityKey(tags.assignmentForTransition(fixture.transitionId()));
    assertThat(admin.type(ascii(originalKey))).isEqualTo("none");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo("INSTALL_DISPATCHED");
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
    assertThat(physicalImage()).isEqualTo(physicalBefore);
  }

  private static java.sql.Connection failDispatchCommitReplyAfterMarker(
      java.sql.Connection actual,
      javax.sql.DataSource independentDataSource,
      String executionDigest,
      java.util.concurrent.atomic.AtomicBoolean stopped)
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
                if (method.getName().equals("commit") && !stopped.get()) {
                  try (var independent = independentDataSource.getConnection();
                      var check =
                          independent.prepareStatement(
                              "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?")) {
                    check.setString(1, executionDigest);
                    try (var rows = check.executeQuery()) {
                      if (rows.next()
                          && "INSTALL_DISPATCHED".equals(rows.getString(1))
                          && stopped.compareAndSet(false, true)) {
                        throw new java.sql.SQLException(
                            "Controlled lost INSTALL_DISPATCHED commit reply");
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

  @Test
  void ownerMovementBeforeCapacityAuthorizationRefusesOriginalDispatchWithoutMutation()
      throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var binding = source.candidate().bindingIdentity();
    assertThat(
            fixture
                .dsl()
                .execute(
                    "UPDATE game_session_region_runtime_status SET executor_fence = executor_fence + 1 WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
                    binding.tenantId(),
                    binding.gameInstanceId(),
                    binding.regionId()))
        .isEqualTo(1);
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
    assertThatThrownBy(() -> replaceRegionOwner("successor-after-closure"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .disposition())
        .isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
  }
}
