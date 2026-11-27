package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GameplayLoginSourceContext;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionGrpcReadClient;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginResult;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountOriginalNewBoundGameplayIssuanceRepository.Genesis;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionLeaseManualFactory;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.ActiveRegistryReadback;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation.Permit;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import net.firedevops.firemud.gamesession.client.AccountTenantCapacityClient.CapacityRefusedException;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual actual-owner final NEW composition, not default registration or public gameplay proof.
 * Real Account recovered LOGIN issuance passes GS signature/aggregate verification and actual
 * journal/INITIAL owners; its initial response delivery is controlled, not an Authenticate network
 * test. Actual Account lease, credential and confirmation receivers, concrete bidirectional mTLS
 * clients, GS protected final reader, physical indexes/region/final CAS and both SQL owners
 * compose. Test PKI, Account signer/materializer/JWKS custody, upstream published target and
 * World/Entity responses, issuer checkpoint and signed incarnation observations remain controlled
 * inputs. Recreated coordinator/Account owners retain the actual original region owner's private
 * grant.
 */
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("resource")
class AccountGameSessionOriginalNewFinalAdmissionCompositionIntegrationTest {
  private static final String GS = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ACCOUNT = "spiffe://firemud/ns/test/sa/account-service";
  private static final Clock CLOCK = Clock.systemUTC();
  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
  private static final String FAULT_FUNCTION = "actual_final_test_fail_confirmation";
  private static final String FAULT_TRIGGER = "actual_final_test_confirmation_fault";
  private final AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest accountFixtures =
      new AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest();
  @TempDir Path temporaryDirectory;

  @BeforeAll
  static void startPostgres() {
    AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.startPostgres();
  }

  @AfterAll
  static void stopPostgres() {
    AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.stopPostgres();
  }

  @AfterEach
  void dropOwnedSchemas() {
    accountFixtures.dropRunOwnedSchemas();
  }

  @Test
  void actualTamperedOriginRefusesClosureWithoutNegativeProofOrCandidateMutation()
      throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      session.gs.prepareReceivingOrigin(session.capacityRequest);
      var permit = session.beginOriginObservation(CLOCK);
      var reservation = session.capacityOwner.commit(session.capacityRequest, GS);
      var resolution = session.capacityOwner.resolveOrigin(session.capacityRequest, GS, false);
      byte[] candidate = session.gs.physicalSession();
      session.gs.corruptOriginDigestForFault();
      var physical = session.gs.originPhysicalImage();
      assertThatThrownBy(() -> session.deliverOriginResolution(resolution, permit, CLOCK))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(session.gs.originPhysicalImage()).isEqualTo(physical);
      assertThat(session.gs.physicalSession()).containsExactly(candidate);
      assertThatThrownBy(
              () ->
                  session.capacityRead.readExact(
                      session.capacityRequest, reservation, CLOCK.instant().plusSeconds(5)))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle("SELECT status FROM game_session_original_new_capacity_execution")
                  .get("status", String.class))
          .isEqualTo("CLOSURE_CLAIMED");
    }
  }

  @Test
  void expiredRedisBudgetCannotInitializeOriginWithLongerCallerDeadline() throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      // The actual Redis clock has passed the locally admitted five-second deadline.
      var clockBehindRedis = Clock.offset(CLOCK, java.time.Duration.ofSeconds(-10));
      long callerDeadline = CLOCK.millis() + 30_000L;
      String accountCapacity = session.capacityImage();
      assertThatThrownBy(
              () ->
                  session.gs.prepareReceivingOrigin(
                      session.capacityRequest, clockBehindRedis, callerDeadline))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Exact physical capacity origin custody unavailable");
      String retainedOrigin = session.gs.originSqlImage();
      assertThat(retainedOrigin).contains("\"state\": \"OPEN\"");
      assertThat(session.gs.originPhysicalImage())
          .containsOnlyKeys("pttl")
          .containsEntry("pttl", "-2");
      assertThatThrownBy(() -> session.gs.prepareReceivingOrigin(session.capacityRequest))
          .isInstanceOf(IllegalStateException.class);
      assertThat(session.gs.originPhysicalImage())
          .containsOnlyKeys("pttl")
          .containsEntry("pttl", "-2");
      assertThat(session.gs.originSqlImage()).isEqualTo(retainedOrigin);
      assertThat(session.capacityImage()).isEqualTo(accountCapacity);
    }
  }

  @Test
  void missingInstalledOriginMarkerRefusesRetryAndTransferWithoutRecreation() throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      session.gs.prepareReceivingOrigin(session.capacityRequest);
      var reservation = session.capacityOwner.commit(session.capacityRequest, GS);
      assertThat(reservation.state()).isEqualTo(TenantCapacityAdmission.State.RESERVED);

      String originSql = session.gs.originSqlImage();
      String accountCapacity = session.capacityImage();
      byte[] candidate = session.gs.physicalSession();
      assertThat(session.gs.originPhysicalImage())
          .containsEntry("state", "OPEN")
          .containsEntry("pttl", "-1");
      session.gs.removeOriginMarkerForFault();
      assertThat(session.gs.originPhysicalImage())
          .containsOnlyKeys("pttl")
          .containsEntry("pttl", "-2");

      assertThatThrownBy(() -> session.gs.prepareReceivingOrigin(session.capacityRequest))
          .isInstanceOf(IllegalStateException.class);
      assertThat(session.gs.originPhysicalImage())
          .containsOnlyKeys("pttl")
          .containsEntry("pttl", "-2");
      assertThat(session.gs.originSqlImage()).isEqualTo(originSql);
      assertThat(session.capacityImage()).isEqualTo(accountCapacity);
      assertThat(session.gs.physicalSession()).containsExactly(candidate);

      assertThatThrownBy(() -> session.gs.handoffReceivingOrigin())
          .isInstanceOf(IllegalStateException.class);
      assertThat(session.gs.originPhysicalImage())
          .containsOnlyKeys("pttl")
          .containsEntry("pttl", "-2");
      assertThat(session.gs.originSqlImage()).isEqualTo(originSql);
      assertThat(session.capacityImage()).isEqualTo(accountCapacity);
      assertThat(session.gs.physicalSession()).containsExactly(candidate);
    }
  }

  @Test
  void actualOriginCallbackRacesOwnerMovementWithoutLosingDenialOrReplenishingBudget()
      throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      session.gs.prepareReceivingOrigin(session.capacityRequest);
      var permit = session.beginOriginObservation(CLOCK);
      var denied = session.capacityOwner.resolveOrigin(session.capacityRequest, GS, true);
      var start = new java.util.concurrent.CountDownLatch(1);
      try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var callback =
            executor.submit(
                () -> {
                  start.await();
                  try {
                    session.deliverOriginResolution(denied, permit, CLOCK);
                    return true;
                  } catch (StatusRuntimeException refusal) {
                    return false;
                  }
                });
        var movement =
            executor.submit(
                () -> {
                  start.await();
                  session.gs.handoffReceivingOrigin();
                  return true;
                });
        start.countDown();
        assertThat(movement.get(10, TimeUnit.SECONDS)).isTrue();
        boolean received = callback.get(10, TimeUnit.SECONDS);
        var image = session.gs.originBudgetImage();
        assertThat(image.get("recovery_observations")).isEqualTo(1);
        assertThat((Long) image.get("custody_owner_fence")).isGreaterThan(permit.custodyFence());
        if (received) assertThat(image.get("state")).isEqualTo("DENIED");
        var successor = session.beginOriginObservation(CLOCK);
        assertThat(successor.ordinal()).isEqualTo(2);
        session.deliverOriginResolution(denied, successor, CLOCK);
        assertThat(session.gs.originPhysicalImage())
            .containsEntry("state", "DENIED")
            .containsEntry("pttl", "-1");
        assertThat(
                session.dsl.fetchCount(
                    org.jooq.impl.DSL.table("account_tenant_capacity_reservations")))
            .isZero();
      }
    }
  }

  @Test
  void actualExpiredOriginPolicyRetainsBudgetAndRejectsFurtherObservation() throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      session.gs.prepareReceivingOrigin(session.capacityRequest);
      var permit = session.beginOriginObservation(CLOCK);
      var image = session.gs.originBudgetImage();
      var expired =
          Clock.fixed(
              ((java.time.OffsetDateTime) image.get("recovery_deadline"))
                  .toInstant()
                  .plusNanos(1000),
              ZoneOffset.UTC);
      assertThatThrownBy(() -> session.beginOriginObservation(expired))
          .isInstanceOf(StatusRuntimeException.class);
      var unknown = session.capacityOwner.resolveOrigin(session.capacityRequest, GS, false);
      assertThatThrownBy(() -> session.deliverOriginResolution(unknown, permit, expired))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(session.gs.originBudgetImage()).isEqualTo(image);
      assertThat(session.gs.originPhysicalImage()).containsEntry("state", "OPEN");
    }
  }

  @Test
  void actualOriginCallbackConstructsDualStoreClosedFromRetainedAccountReservation()
      throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      session.gs.prepareReceivingOrigin(session.capacityRequest);
      var permit = session.beginOriginObservation(CLOCK);
      var reservation = session.capacityOwner.commit(session.capacityRequest, GS);
      var resolution = session.capacityOwner.resolveOrigin(session.capacityRequest, GS, false);
      assertThat(resolution.reservation()).isEqualTo(reservation);
      session.deliverOriginResolution(resolution, permit, CLOCK);
      var budget = session.gs.originBudgetImage();
      assertThat(budget.get("recovery_observations")).isEqualTo(1);
      assertThat(budget.get("reservation_policy_applied")).isEqualTo(true);
      assertThat(((java.time.OffsetDateTime) budget.get("recovery_deadline")).toInstant())
          .isEqualTo(resolution.continuationPolicy().deadline());
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle("SELECT status FROM game_session_original_new_capacity_execution")
                  .get("status", String.class))
          .isEqualTo("CLOSED");
      var read =
          session.capacityRead.readExact(
              session.capacityRequest, reservation, CLOCK.instant().plusSeconds(5));
      assertThat(read.disposition())
          .isEqualTo(
              net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker
                  .Disposition.NOT_EXECUTED_AFTER_AUTHORIZATION);
      assertThat(session.gs.originPhysicalImage())
          .containsEntry("state", "CLOSURE_CLAIMED")
          .containsEntry("pttl", "-1");
      assertThat(session.gs.finalReadCalls()).isZero();
      var physical = session.gs.originPhysicalImage();
      assertThatThrownBy(
              () ->
                  session.gs.complete(
                      session.client,
                      session.jwks,
                      session.jwt,
                      CLOCK.millis() + 5000,
                      session.capacityClient,
                      session.capacityRequest))
          .isInstanceOf(RuntimeException.class);
      assertThat(session.gs.originPhysicalImage()).isEqualTo(physical);
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle("SELECT status FROM game_session_original_new_capacity_execution")
                  .get("status", String.class))
          .isEqualTo("CLOSED");
    }
  }

  @Test
  void actualUnknownOriginCallbackConsumesDurableBudgetWithoutInventingReservation()
      throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      session.gs.prepareReceivingOrigin(session.capacityRequest);
      var permit = session.beginOriginObservation(CLOCK);
      var unknown = session.capacityOwner.resolveOrigin(session.capacityRequest, GS, false);
      assertThat(unknown.status().name()).isEqualTo("UNKNOWN");
      assertThatThrownBy(() -> session.deliverOriginResolution(unknown, permit, CLOCK))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(session.gs.originBudgetImage())
          .containsEntry("recovery_observations", 1)
          .containsEntry("state", "OPEN");
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchCount(
                      org.jooq.impl.DSL.table("game_session_original_new_capacity_execution")))
          .isZero();
      assertThat(
              session.dsl.fetchCount(
                  org.jooq.impl.DSL.table("account_tenant_capacity_reservations")))
          .isZero();
      var beforeRetry = session.gs.originBudgetImage();
      assertThatThrownBy(() -> session.beginOriginObservation(CLOCK))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(session.gs.originBudgetImage()).isEqualTo(beforeRetry);
      var next = session.beginOriginObservation(Clock.fixed(permit.deadline(), ZoneOffset.UTC));
      assertThat(next.ordinal()).isEqualTo(2);
    }
  }

  @Test
  void actualNoAcceptOriginCallbackRetiresOnlyExactCustodyAndExcludesDelayedCommit()
      throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      session.gs.prepareReceivingOrigin(session.capacityRequest);
      var permit = session.beginOriginObservation(CLOCK);
      var denied = session.capacityOwner.resolveOrigin(session.capacityRequest, GS, true);
      assertThat(denied.status().name()).isEqualTo("NO_ACCEPT_RETAINED");
      var beforeOverlap = session.gs.originBudgetImage();
      assertThatThrownBy(() -> session.beginOriginObservation(CLOCK))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(session.gs.originBudgetImage()).isEqualTo(beforeOverlap);
      session.deliverOriginResolution(denied, permit, CLOCK);
      assertThat(session.gs.originPhysicalImage())
          .containsEntry("state", "DENIED")
          .containsEntry("pttl", "-1");
      assertThatThrownBy(() -> session.capacityOwner.commit(session.capacityRequest, GS))
          .isInstanceOf(RuntimeException.class);
      assertThat(
              session.dsl.fetchCount(
                  org.jooq.impl.DSL.table("account_tenant_capacity_reservations")))
          .isZero();
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchCount(
                      org.jooq.impl.DSL.table("game_session_original_new_capacity_execution")))
          .isZero();
    }
  }

  @Test
  void actualOriginHandoffPreservesBudgetAndRefusesStillLiveOldCustodyCallback() throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      session.gs.prepareReceivingOrigin(session.capacityRequest);
      var permit = session.beginOriginObservation(CLOCK);
      var denied = session.capacityOwner.resolveOrigin(session.capacityRequest, GS, true);
      var before = session.gs.originBudgetImage();
      session.gs.handoffReceivingOrigin();
      var moved = session.gs.originBudgetImage();
      for (String field :
          List.of(
              "recovery_deadline",
              "recovery_max_observations",
              "recovery_observations",
              "last_observation_deadline",
              "last_observation_custody_fence",
              "reservation_policy_applied"))
        assertThat(moved.get(field)).isEqualTo(before.get(field));
      assertThat((Long) moved.get("custody_owner_fence"))
          .isGreaterThan((Long) before.get("custody_owner_fence"));
      assertThat(CLOCK.instant()).isBefore(permit.deadline());
      var physical = session.gs.originPhysicalImage();
      assertThatThrownBy(() -> session.deliverOriginResolution(denied, permit, CLOCK))
          .isInstanceOf(StatusRuntimeException.class);
      var rebound =
          new Permit(
              permit.requestDigest(),
              permit.originDigest(),
              (String) moved.get("custody_owner_id"),
              (Long) moved.get("custody_owner_fence"),
              permit.ordinal(),
              permit.deadline());
      assertThatThrownBy(() -> session.deliverOriginResolution(denied, rebound, CLOCK))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(session.gs.originBudgetImage()).isEqualTo(moved);
      assertThat(session.gs.originPhysicalImage()).isEqualTo(physical);
      var successor = session.beginOriginObservation(CLOCK);
      assertThat(successor.ordinal()).isEqualTo(2);
      assertThat(successor.custodyFence()).isGreaterThan(permit.custodyFence());
      assertThat(session.gs.originBudgetImage())
          .containsEntry("last_observation_custody_fence", successor.custodyFence());
      session.deliverOriginResolution(denied, successor, CLOCK);
      assertThat(session.gs.originPhysicalImage()).containsEntry("state", "DENIED");
    }
  }

  @Test
  void actualTransferredOpenOriginClosesTheOriginalRetainedReservation() throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      var request = session.capacityRequest;
      session.gs.prepareReceivingOrigin(request);
      var reservation = session.capacityClient.commit(request, CLOCK.millis() + 5000);
      assertThat(reservation.state()).isEqualTo(TenantCapacityAdmission.State.RESERVED);
      var original = session.beginOriginObservation(CLOCK);
      var resolution = session.capacityOwner.resolveOrigin(request, GS, false);
      assertThat(resolution.reservation()).isEqualTo(reservation);
      String accountBefore = session.capacityImage();
      byte[] candidate = session.gs.physicalSession();
      var execution =
          new OriginalNewCapacityExecution(
              request,
              reservation,
              session.gs.decisionId(),
              "sha256:" + OriginalNewCapacityExecution.sha256(candidate),
              original.custodyOwner(),
              original.custodyFence());

      session.gs.handoffReceivingOrigin();
      var moved = session.gs.originBudgetImage();
      assertThat(moved)
          .containsEntry("state", "OPEN")
          .containsEntry("recovery_observations", 1)
          .containsEntry("last_observation_custody_fence", original.custodyFence());
      assertThat(CLOCK.instant()).isBefore(original.deadline());
      assertThatThrownBy(() -> session.deliverOriginResolution(resolution, original, CLOCK))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(session.gs.originBudgetImage()).isEqualTo(moved);
      assertThat(session.gs.capacityExecutionPhysicalImage(request.admissionAttemptId()))
          .containsOnlyKeys("pttl")
          .containsEntry("pttl", "-2");

      String transitionBefore = session.gsReceiptImage();
      var successor = session.beginOriginObservation(CLOCK);
      assertThat(successor.ordinal()).isEqualTo(2);
      assertThat(successor.custodyFence()).isGreaterThan(original.custodyFence());
      session.deliverOriginResolution(resolution, successor, CLOCK);
      assertThat(session.gs.originBudgetImage())
          .containsEntry("state", "CLOSURE_CLAIMED")
          .containsEntry("last_observation_custody_fence", successor.custodyFence());
      assertThat(session.gs.originPhysicalImage())
          .containsEntry("state", "CLOSURE_CLAIMED")
          .containsEntry("owner", successor.custodyOwner())
          .containsEntry("fence", Long.toString(successor.custodyFence()))
          .containsEntry("pttl", "-1");

      var closed =
          session
              .gs
              .dsl()
              .fetchSingle("SELECT * FROM game_session_original_new_capacity_execution");
      assertThat(closed.get("status", String.class)).isEqualTo("CLOSED");
      assertThat(closed.get("selected_owner_id", String.class)).isEqualTo(original.custodyOwner());
      assertThat(closed.get("selected_owner_fence", Long.class)).isEqualTo(original.custodyFence());
      assertThat(closed.get("execution_digest", String.class)).isEqualTo(execution.digest());
      assertThat(closed.get("canonical_execution", byte[].class))
          .containsExactly(execution.canonicalBytes());
      assertThat(closed.get("terminal_readback", byte[].class)).isNotEmpty();
      assertThat(session.gs.capacityExecutionPhysicalImage(request.admissionAttemptId()))
          .isEqualTo(
              Map.of(
                  "state",
                  "CLOSED",
                  "digest",
                  execution.digest(),
                  "binding",
                  new String(execution.canonicalBytes(), StandardCharsets.UTF_8),
                  "pttl",
                  "-1"));

      var read =
          session.capacityRead.readExact(request, reservation, CLOCK.instant().plusSeconds(5));
      assertThat(read.disposition())
          .isEqualTo(
              net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker
                  .Disposition.NOT_EXECUTED_AFTER_AUTHORIZATION);
      assertThat(read.authenticatedOwnerWorkload()).isEqualTo(GS);
      assertThat(read.reservation()).isEqualTo(reservation);
      assertThat(read.currentOwnerFence()).isEqualTo(successor.custodyFence());
      assertThat(read.authoritativeStateDigest()).isEqualTo(execution.candidateDigest());
      var usage =
          session.dsl.fetchSingle(
              "SELECT held_units,used_units FROM account_tenant_capacity_usage WHERE tenant_uuid=? AND owner_service='game-session-service'",
              request.identity().tenantId());
      assertThat(usage.get("held_units", Long.class)).isOne();
      assertThat(usage.get("used_units", Long.class)).isZero();
      assertThat(session.capacityImage()).isEqualTo(accountBefore);
      assertThat(session.gsReceiptImage()).isEqualTo(transitionBefore);
      assertProvisionalUnchanged(session, candidate);
      assertThat(session.gs.finalReadCalls()).isZero();
    }
  }

  @Test
  void originOnlyCompletedHandoffRecoversWithTheSameLivePrivateGrant() throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      session.gs.prepareReceivingOrigin(session.capacityRequest);
      var before = session.gs.originBudgetImage();
      session.gs.handoffReceivingOriginAfterLostConfirmation();
      var after = session.gs.originBudgetImage();
      assertThat((Long) after.get("custody_owner_fence"))
          .isGreaterThan((Long) before.get("custody_owner_fence"));
      assertThat(after.get("recovery_observations")).isEqualTo(before.get("recovery_observations"));
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchCount(
                      org.jooq.impl.DSL.table("game_session_original_new_capacity_execution")))
          .isZero();
    }
  }

  @Test
  void completedOriginHandoffRecoversAfterTheSuccessorClaimsAnOpenOrigin() throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      session.gs.prepareReceivingOrigin(session.capacityRequest);
      var denied = session.capacityOwner.resolveOrigin(session.capacityRequest, GS, true);
      session.gs.handoffReceivingOriginAfterLostConfirmation(
          () -> {
            var permit = session.beginOriginObservation(CLOCK);
            session.deliverOriginResolution(denied, permit, CLOCK);
            assertThat(session.gs.originPhysicalImage()).containsEntry("state", "DENIED");
            return null;
          });
      assertThat(session.gs.originBudgetImage()).containsEntry("state", "DENIED");
      assertThat(session.gs.originPhysicalImage()).containsEntry("state", "DENIED");
    }
  }

  @Test
  void actualOriginObservationExhaustionRetainsHoldAndCannotResetOnRecreatedReceiver()
      throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      session.gs.prepareReceivingOrigin(session.capacityRequest);
      Clock observationClock = Clock.fixed(CLOCK.instant(), ZoneOffset.UTC);
      for (int ordinal = 1; ordinal <= 12; ordinal++) {
        var permit = session.beginOriginObservation(observationClock, Duration.ofSeconds(2));
        assertThat(permit.ordinal()).isEqualTo(ordinal);
        observationClock = Clock.fixed(permit.deadline(), ZoneOffset.UTC);
      }
      var before = session.gs.originBudgetImage();
      Clock exhaustedClock = observationClock;
      assertThat(exhaustedClock.instant())
          .isBefore(((java.time.OffsetDateTime) before.get("recovery_deadline")).toInstant());
      assertThatThrownBy(() -> session.beginOriginObservation(exhaustedClock))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(session.gs.originBudgetImage()).isEqualTo(before).containsEntry("state", "OPEN");
      assertThat(
              session.dsl.fetchCount(
                  org.jooq.impl.DSL.table("account_tenant_capacity_reservations")))
          .isZero();
    }
  }

  @Test
  void actualCoordinatorConfirmsAndRecreatedOwnersRetryExactOriginalResult() throws Exception {
    try (Session session = session();
        OriginalNewCredential before = session.issue()) {
      byte[] handle = before.opaqueRebindHandle();
      try {
        var first = session.complete();
        Genesis confirmed = session.genesis();
        assertConfirmed(first, confirmed);
        assertThat(session.gs.finalReadCalls()).isOne();
        ActiveRegistryReadback active =
            session.registry.requireExactOriginalNewActiveRecord(confirmed);
        byte[] sessionBytes = session.gs.physicalSession();
        assertPhysicalSession(sessionBytes, first);
        String rows = session.durableImage();
        session.restartAccount();
        var retry = session.complete();
        assertThat(retry).isEqualTo(first);
        assertThat(MessageDigest.isEqual(session.gs.physicalSession(), sessionBytes)).isTrue();
        assertThat(session.durableImage()).isEqualTo(rows);
        try (OriginalNewCredential replay = session.issue()) {
          assertSameCredential(before, replay, handle);
        }
        assertSameRegistry(
            active, session.registry.requireExactOriginalNewActiveRecord(session.genesis()));
        assertThat(session.gs.finalReadCalls()).isOne();
      } finally {
        Arrays.fill(handle, (byte) 0);
      }
    }
  }

  @Test
  void receivingOriginReplaysCommittedFinalWithoutAnotherDispatch() throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      var first = session.complete();
      assertConfirmed(first, session.genesis());
      var custody = session.gs.originPhysicalImage();
      assertThat(custody).containsEntry("state", "DISPATCH_CLAIMED");
      var budget = session.gs.originBudgetImage();
      String accountCapacity = session.capacityImage();
      String durable = session.durableImage();
      byte[] physical = session.gs.physicalSession();
      session.restartAccount();
      assertThat(session.complete()).isEqualTo(first);
      int confirmedReads = session.gs.finalReadCalls();
      session.gs.useOrdinaryFinalAdmission();
      assertThatThrownBy(session::complete)
          .isInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("Exact capacity origin custody unavailable");
      assertThat(session.gs.finalReadCalls()).isEqualTo(confirmedReads);
      assertThat(session.gs.originPhysicalImage()).isEqualTo(custody);
      assertThat(session.gs.originBudgetImage()).isEqualTo(budget);
      assertThat(session.capacityImage()).isEqualTo(accountCapacity);
      assertThat(session.durableImage()).isEqualTo(durable);
      assertThat(session.gs.physicalSession()).containsExactly(physical);
    }
  }

  @Test
  void actualCapacityCommitAndAuthenticatedFinalReadFinalizeExactlyOneHold() throws Exception {
    try (Session session = session()) {
      var first = session.complete();
      var recovery =
          new net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker(
              session.capacityOwner, session.dsl, session.capacityRead, CLOCK);
      var result =
          recovery.reconcile(
              session.capacityRequest,
              net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker
                  .NoCommitAction.RELEASE);
      assertThat(result.state())
          .isEqualTo(
              net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.State
                  .FINALIZED);
      assertThat(
              recovery.reconcile(
                  session.capacityRequest,
                  net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker
                      .NoCommitAction.RELEASE))
          .isEqualTo(result);
      assertThat(session.complete()).isEqualTo(first);
      var usage =
          session.dsl.fetchSingle(
              "SELECT held_units, used_units FROM account_tenant_capacity_usage WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
              result.identity().tenantId());
      assertThat(usage.get("held_units", Long.class)).isZero();
      assertThat(usage.get("used_units", Long.class)).isOne();
      assertThat(
              session.dsl.fetchCount(
                  org.jooq.impl.DSL.table("account_tenant_capacity_reservations")))
          .isOne();
    }
  }

  @Test
  void historicalFinalReceiptSettlesTheSameHoldAfterPhysicalFinalLosesOriginalSqlCommit()
      throws Exception {
    try (Session session = session()) {
      byte[] candidate = session.gs.physicalSession();
      assertThatThrownBy(
              () ->
                  session.gs.completeWithLostOriginalAdmissionCommit(
                      session.client,
                      session.jwks,
                      session.jwt,
                      System.currentTimeMillis() + 10_000L,
                      session.capacityClient,
                      session.capacityRequest))
          .isInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("Controlled loss of original admission SQL commit reply");

      byte[] finalSession = session.gs.physicalSession();
      assertThat(finalSession).isNotNull().isNotEqualTo(candidate);
      var physical =
          tools.jackson.databind.json.JsonMapper.builder().build().readTree(finalSession);
      assertThat(physical.path("schemaVersion").asInt()).isEqualTo(2);
      assertThat(physical.path("admissionState").asText()).isEqualTo("COMPLETE");
      var transition =
          session
              .gs
              .dsl()
              .fetchSingle(
                  "SELECT status, original_new_final_session_sha256 FROM game_session_canonical_binding_transition WHERE transition_id = ?",
                  session.gs.decisionId());
      assertThat(transition.get("status", String.class)).isEqualTo("PROVISIONAL");
      assertThat(transition.get("original_new_final_session_sha256", String.class)).isNull();
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_original_new_capacity_execution WHERE transition_id = ?",
                      session.gs.decisionId())
                  .get(0, String.class))
          .isEqualTo("AUTHORIZED");

      var reservation =
          session.capacityClient.commit(session.capacityRequest, CLOCK.millis() + 5_000L);
      assertThat(reservation.state()).isEqualTo(TenantCapacityAdmission.State.RESERVED);
      var usage =
          session.dsl.fetchSingle(
              "SELECT held_units, used_units FROM account_tenant_capacity_usage WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
              reservation.identity().tenantId());
      assertThat(usage.get("held_units", Long.class)).isOne();
      assertThat(usage.get("used_units", Long.class)).isZero();

      assertThatThrownBy(
              () ->
                  session.capacityRead.readExact(
                      session.capacityRequest, reservation, CLOCK.instant().plusSeconds(5)))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                      .isEqualTo(Status.Code.UNAVAILABLE));

      var recovery =
          new net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker(
              session.capacityOwner,
              session.dsl,
              session.capacityRead,
              session.capacityRead,
              CLOCK);
      var beforeFence = recovery.reconcileHistoricalFinal(session.capacityRequest);
      assertThat(beforeFence.state()).isEqualTo(TenantCapacityAdmission.State.RESERVED);
      assertThat(beforeFence.reservationId()).isEqualTo(reservation.reservationId());
      assertThat(beforeFence.admissionAttemptId()).isEqualTo(reservation.admissionAttemptId());
      assertThat(beforeFence.capacityRearmFence()).isEqualTo(reservation.capacityRearmFence());
      var unresolvedUsage =
          session.dsl.fetchSingle(
              "SELECT held_units, used_units FROM account_tenant_capacity_usage WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
              reservation.identity().tenantId());
      assertThat(unresolvedUsage.get("held_units", Long.class)).isOne();
      assertThat(unresolvedUsage.get("used_units", Long.class)).isZero();

      UUID fenceOperationId =
          session.gs.acquireAccountFenceAndCaptureFinal(
              session.capacityRequest, reservation, CLOCK.millis() + 5_000L);
      var frozenGameSession = session.gs.capacityStateImage();
      var frozenAccountFence = session.gs.accountAdmissionFenceImage();
      var frozenAdmission = session.durableImage();
      var historical =
          session.capacityRead.readHistoricalFinal(
              session.capacityRequest, reservation, CLOCK.instant().plusSeconds(5));
      assertThat(historical.authenticatedOwnerWorkload()).isEqualTo(GS);
      assertThat(historical.receipt().fenceOperationId()).isEqualTo(fenceOperationId);
      assertThat(historical.receipt().execution().reservation()).isEqualTo(reservation);
      assertThat(historical.receipt().finalSessionSha256())
          .isEqualTo(
              java.util.HexFormat.of()
                  .formatHex(MessageDigest.getInstance("SHA-256").digest(finalSession)));

      var settled = recovery.reconcileHistoricalFinal(session.capacityRequest);
      assertThat(settled.state()).isEqualTo(TenantCapacityAdmission.State.FINALIZED);
      assertThat(settled.reservationId()).isEqualTo(reservation.reservationId());
      assertThat(settled.reservationFence()).isEqualTo(reservation.reservationFence());
      assertThat(settled.admissionAttemptId()).isEqualTo(reservation.admissionAttemptId());
      assertThat(settled.capacityRearmFence()).isEqualTo(reservation.capacityRearmFence());

      var replayedHistorical =
          session.capacityRead.readHistoricalFinal(
              session.capacityRequest, reservation, CLOCK.instant().plusSeconds(5));
      assertThat(replayedHistorical).isEqualTo(historical);
      assertThat(recovery.reconcileHistoricalFinal(session.capacityRequest)).isEqualTo(settled);
      assertThatThrownBy(
              () ->
                  session.capacityRead.readExact(
                      session.capacityRequest, reservation, CLOCK.instant().plusSeconds(5)))
          .isInstanceOf(StatusRuntimeException.class);

      var settledUsage =
          session.dsl.fetchSingle(
              "SELECT held_units, used_units FROM account_tenant_capacity_usage WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
              reservation.identity().tenantId());
      assertThat(settledUsage.get("held_units", Long.class)).isZero();
      assertThat(settledUsage.get("used_units", Long.class)).isOne();
      assertThat(
              session.dsl.fetchCount(
                  org.jooq.impl.DSL.table("account_tenant_capacity_reservations")))
          .isOne();
      assertThat(
              session
                  .dsl
                  .fetchSingle(
                      "SELECT count(*) FROM account_tenant_capacity_recovery_evidence WHERE admission_attempt_id = ?",
                      reservation.admissionAttemptId())
                  .get(0, Long.class))
          .isEqualTo(2L);
      assertThat(session.gs.physicalSession()).containsExactly(finalSession);
      assertThat(session.gs.capacityStateImage()).isEqualTo(frozenGameSession);
      assertThat(session.gs.accountAdmissionFenceImage()).isEqualTo(frozenAccountFence);
      assertThat(session.durableImage()).isEqualTo(frozenAdmission);
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT status, original_new_final_session_sha256 FROM game_session_canonical_binding_transition WHERE transition_id = ?",
                      session.gs.decisionId())
                  .get("status", String.class))
          .isEqualTo("PROVISIONAL");
    }
  }

  @Test
  void retainedActualCasEvidenceStillFinalizesCapacityAfterControlledServingRetirement()
      throws Exception {
    try (Session session = session()) {
      var admitted = session.complete();
      var reservation =
          session.capacityClient.commit(session.capacityRequest, CLOCK.millis() + 5_000L);
      var before =
          session.capacityRead.readExact(
              session.capacityRequest, reservation, CLOCK.instant().plusSeconds(5));
      session.gs.removeServingIndexesAfterCapacityRetentionForFault();
      assertThat(session.gs.physicalSession()).isNull();
      var after =
          session.capacityRead.readExact(
              session.capacityRequest, reservation, CLOCK.instant().plusSeconds(5));
      assertThat(after.disposition())
          .isEqualTo(
              net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker
                  .Disposition.COMMITTED);
      assertThat(after.authoritativeStateDigest())
          .isEqualTo("sha256:" + admitted.finalSessionSha256());
      assertThat(after.canonicalOwnerReadback()).containsExactly(before.canonicalOwnerReadback());
      var recovery =
          new net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker(
              session.capacityOwner, session.dsl, session.capacityRead, CLOCK);
      assertThat(
              recovery
                  .reconcile(
                      session.capacityRequest,
                      net.firedevops.firemud.accountservice.service
                          .AccountTenantCapacityRecoveryWorker.NoCommitAction.RELEASE)
                  .state())
          .isEqualTo(
              net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.State
                  .FINALIZED);
    }
  }

  @Test
  void actualNormalHandoffKeepsUnknownCapacityHeldAndBoundedRecoveryQuarantinesSameReservation()
      throws Exception {
    try (Session session = session()) {
      var reservation =
          session.gs.authorizeThenFenceAndHandoffWithoutFinal(
              session.client,
              session.jwks,
              session.jwt,
              session.capacityClient,
              session.capacityRequest,
              System.currentTimeMillis() + 5000L);
      assertThatThrownBy(
              () ->
                  session.capacityRead.readExact(
                      session.capacityRequest, reservation, CLOCK.instant().plusSeconds(5)))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure ->
                  assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
      TenantCapacityAdmission.Result result = reservation;
      var recovery =
          new net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker(
              session.capacityOwner, session.dsl, session.capacityRead, CLOCK);
      for (int attempt = 0; attempt < 12; attempt++) {
        result =
            recovery.reconcile(
                session.capacityRequest,
                net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker
                    .NoCommitAction.RELEASE);
        assertThat(result.reservationId()).isEqualTo(reservation.reservationId());
        assertThat(result.reservationFence()).isEqualTo(reservation.reservationFence());
        assertThat(result.admissionAttemptId()).isEqualTo(reservation.admissionAttemptId());
        assertThat(result.identity()).isEqualTo(reservation.identity());
        assertThat(result.capacityRearmFence()).isEqualTo(reservation.capacityRearmFence());
      }
      assertThat(result.state())
          .isEqualTo(TenantCapacityAdmission.State.QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION);
      var retainedRecovery =
          session.dsl.fetchSingle(
              "SELECT recovery_attempt_count, recovery_claim_fence FROM account_tenant_capacity_reservations WHERE reservation_id=?",
              reservation.reservationId());
      assertThat(retainedRecovery.get("recovery_attempt_count", Integer.class)).isEqualTo(12);
      assertThat(retainedRecovery.get("recovery_claim_fence", Long.class)).isEqualTo(12L);
      assertThat(
              session
                  .dsl
                  .fetchSingle(
                      "SELECT count(*) FROM account_tenant_capacity_recovery_evidence WHERE admission_attempt_id=?",
                      reservation.admissionAttemptId())
                  .get(0, Long.class))
          .isEqualTo(12L);
      var usage =
          session.dsl.fetchSingle(
              "SELECT held_units,used_units FROM account_tenant_capacity_usage WHERE tenant_uuid=? AND owner_service='game-session-service'",
              reservation.identity().tenantId());
      assertThat(usage.get("held_units", Long.class)).isOne();
      assertThat(usage.get("used_units", Long.class)).isZero();
      assertThat(
              session.dsl.fetchCount(
                  org.jooq.impl.DSL.table("account_tenant_capacity_reservations")))
          .isOne();
      assertThat(
              session
                  .dsl
                  .fetchSingle(
                      "SELECT execution_status FROM account_tenant_capacity_reservations WHERE reservation_id=?",
                      reservation.reservationId())
                  .get(0, String.class))
          .isEqualTo("OWNER_EXECUTION_PENDING");
    }
  }

  @Test
  void targetResolutionRefusesChangedRetainedSelectorsWithoutMutatingOwners() throws Exception {
    try (Session session = session()) {
      var before = session.durableImage();
      var request =
          CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionRequest(
              session.targetSelector);
      for (var changed :
          java.util.List.of(
              request.toBuilder().setReadRequestUuid(0, UUID.randomUUID().toString()).build(),
              request.toBuilder().setAccountUuid(0, UUID.randomUUID().toString()).build(),
              request.toBuilder().setTenantSlug(0, "other-tenant").build(),
              request.toBuilder()
                  .setPointerVersion(
                      0,
                      Long.toString(Math.incrementExact(session.targetSelector.pointerVersion())))
                  .build())) {
        var selector =
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseTargetResolutionRequest(changed);
        assertThatThrownBy(() -> session.reverse.resolvePublishedRealmAdmissionTarget(selector))
            .isInstanceOfSatisfying(
                io.grpc.StatusRuntimeException.class,
                denied ->
                    assertThat(denied.getStatus().getCode())
                        .isEqualTo(io.grpc.Status.Code.FAILED_PRECONDITION));
      }
      var wrongNamespace =
          CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseTargetResolutionRequest(
              request.toBuilder().setTargetNamespace(0, "other").build());
      assertThatThrownBy(() -> session.reverse.resolvePublishedRealmAdmissionTarget(wrongNamespace))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured namespace");
      assertThat(session.durableImage()).isEqualTo(before);
    }
  }

  @Test
  void missingCapacityInputRefusesBeforeAnyFinalSessionOrReservationMutation() throws Exception {
    try (Session session = session()) {
      byte[] candidate = session.gs.physicalSession();
      assertThatThrownBy(
              () ->
                  session.gs.complete(
                      session.client,
                      session.jwks,
                      session.jwt,
                      System.currentTimeMillis() + 10_000L,
                      session.capacityClient,
                      null))
          .isInstanceOf(IllegalStateException.class);
      assertThat(session.gs.physicalSession()).containsExactly(candidate);
      assertThat(
              session.dsl.fetchCount(
                  org.jooq.impl.DSL.table("account_tenant_capacity_reservations")))
          .isZero();
    }
  }

  @Test
  void capacityStateImageDetectsTtlOnlyChangeAndMissingCapacityRefusalPreservesIt()
      throws Exception {
    try (Session session = session()) {
      byte[] candidate = session.gs.physicalSession();
      var before = new java.util.TreeMap<>(session.gs.capacityStateImage());
      String changedKey = session.gs.setPhysicalSessionExpiryForFault();
      var after = new java.util.TreeMap<>(session.gs.capacityStateImage());
      assertThat(session.gs.physicalSession()).containsExactly(candidate);
      assertThat(after.get(changedKey)).isNotEqualTo(before.get(changedKey));
      before.remove(changedKey);
      after.remove(changedKey);
      assertThat(after).isEqualTo(before);

      var physical = session.gs.capacityStateImage();
      String capacity = session.capacityImage();
      assertThatThrownBy(
              () ->
                  session.gs.complete(
                      session.client,
                      session.jwks,
                      session.jwt,
                      System.currentTimeMillis() + 10_000L,
                      session.capacityClient,
                      null))
          .isInstanceOf(IllegalStateException.class);
      assertThat(session.gs.capacityStateImage()).isEqualTo(physical);
      assertThat(session.capacityImage()).isEqualTo(capacity);
      assertThat(session.gs.physicalSession()).containsExactly(candidate);
      assertThat(session.gs.finalReadCalls()).isZero();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"catalogRevision", "pointerVersion", "activeWorldEpoch", "canonicalVersionId"})
  void changedTargetSnapshotRefusesBeforeAccountCapacityCommitment(String field) throws Exception {
    try (Session session = session()) {
      var original = session.capacityRequest;
      var target = (Map<?, ?>) original.evidence().fields().get("target");
      String changedValue =
          field.equals("canonicalVersionId")
              ? UUID.randomUUID().toString()
              : Long.toString(Math.incrementExact(Long.parseLong((String) target.get(field))));
      var changed =
          session.retainControlledEvaluation(true, null, Map.of(field, changedValue), null);
      changed.requireStructurallyValid();
      assertThat(changed.context()).isEqualTo(original.context());
      assertThat(changed.evidence().fields().get("authorityTuple"))
          .isEqualTo(original.evidence().fields().get("authorityTuple"));
      String capacityBefore = session.capacityImage();
      var gsBefore = session.gs.capacityStateImage();
      byte[] candidate = session.gs.physicalSession();
      assertThatThrownBy(
              () ->
                  session.gs.complete(
                      session.client,
                      session.jwks,
                      session.jwt,
                      System.currentTimeMillis() + 10_000L,
                      session.capacityClient,
                      changed))
          .isInstanceOf(IllegalStateException.class)
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("Original NEW capacity execution is indeterminate");
      assertThat(session.capacityImage()).isEqualTo(capacityBefore);
      assertThat(session.gs.capacityStateImage()).isEqualTo(gsBefore);
      assertThat(session.gs.physicalSession()).containsExactly(candidate);
      assertThat(session.gs.finalReadCalls()).isZero();
      assertThat(
              session.dsl.fetchCount(
                  org.jooq.impl.DSL.table("account_tenant_capacity_reservations")))
          .isZero();
    }
  }

  @Test
  void mismatchedCapacityRequestRefusesBeforeAccountCommit() throws Exception {
    try (Session session = session()) {
      var original = session.capacityRequest;
      var mismatched =
          new TenantCapacityAdmission.Request(
                  new TenantCapacityAdmission.Identity(
                      original.identity().tenantId(),
                      original.identity().requestId(),
                      UUID.randomUUID()),
                  original.admissionAttemptId(),
                  original.capacityRearmFence(),
                  original.targetNamespace(),
                  original.context(),
                  original.operation(),
                  original.priorShape(),
                  original.requestedShape(),
                  original.evidence(),
                  original.capacityDelta(),
                  null)
              .withDigest();
      mismatched.requireStructurallyValid();
      String capacityBefore = session.capacityImage();
      var gsBefore = session.gs.capacityStateImage();
      for (int attempt = 0; attempt < 2; attempt++) {
        assertThatThrownBy(
                () ->
                    session.gs.complete(
                        session.client,
                        session.jwks,
                        session.jwt,
                        System.currentTimeMillis() + 10_000L,
                        session.capacityClient,
                        mismatched))
            .isInstanceOf(IllegalStateException.class);
        assertThat(session.capacityImage()).isEqualTo(capacityBefore);
        assertThat(session.gs.capacityStateImage()).isEqualTo(gsBefore);
        assertThat(session.gs.finalReadCalls()).isZero();
      }

      // A structurally valid non-creating observation reaches the real Account policy owner.
      var denied = session.retainControlledEvaluation(false, null);
      denied.requireStructurallyValid();
      String beforeDenial = session.capacityImage();
      byte[] candidate = session.gs.physicalSession();
      assertThatThrownBy(
              () ->
                  session.gs.complete(
                      session.client,
                      session.jwks,
                      session.jwt,
                      System.currentTimeMillis() + 10_000L,
                      session.capacityClient,
                      denied))
          .isInstanceOfSatisfying(
              CapacityRefusedException.class,
              failure -> assertThat(failure.code()).isEqualTo(Failure.TENANT_BILLING_BLOCKED));
      assertThat(session.capacityImage()).isEqualTo(beforeDenial);
      assertProvisionalUnchanged(session, candidate);
    }
    try (Session session = session()) {
      var request = session.capacityRequest;
      byte[] candidate = session.gs.physicalSession();
      var fault =
          session.gs.commitThenMoveSelectedOwnerForFault(
              session.capacityClient, request, System.currentTimeMillis() + 10_000L);
      fault.reservation().requireBinding(request);
      assertThat(fault.reservation().state()).isEqualTo(TenantCapacityAdmission.State.RESERVED);
      String committedCapacity = session.capacityImage();
      var movedGs = session.gs.capacityStateImage();
      assertThatThrownBy(fault.bindOriginal()::run).isInstanceOf(IllegalStateException.class);
      assertThat(session.capacityImage()).isEqualTo(committedCapacity);
      assertThat(session.gs.capacityStateImage()).isEqualTo(movedGs);
      assertThat(session.gs.physicalSession()).containsExactly(candidate);
      assertThat(session.gs.finalReadCalls()).isZero();
    }
  }

  @Test
  void actualPostActiveSqlConfirmationLossRecoversThroughFreshProtectedFinalRead()
      throws Exception {
    try (Session session = session();
        OriginalNewCredential before = session.issue()) {
      byte[] handle = before.opaqueRebindHandle();
      try {
        session.installConfirmationFault();
        try {
          assertThatThrownBy(session::complete).isInstanceOf(IllegalStateException.class);
        } finally {
          session.removeConfirmationFault();
        }
        Genesis unconfirmed = session.genesis();
        assertThat(unconfirmed.confirmedAdmissionAtMillis()).isNull();
        assertThat(unconfirmed.confirmedContinuityExpiresAtMillis()).isNull();
        assertThat(unconfirmed.confirmationReadbackSha256()).isNull();
        assertThat(unconfirmed.tokenHash()).isEqualTo(before.tokenSha256());
        ActiveRegistryReadback active =
            session.registry.requireExactOriginalNewActiveRecord(unconfirmed);
        assertThat(session.gs.finalReadCalls()).isOne();
        byte[] committedSession = session.gs.physicalSession();
        var physical =
            tools.jackson.databind.json.JsonMapper.builder().build().readTree(committedSession);
        assertThat(physical.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(physical.path("admissionState").asText()).isEqualTo("COMPLETE");
        String gsReceipt = session.gsReceiptImage();
        session.restartAccount();
        var recovered = session.complete();
        assertConfirmed(recovered, session.genesis());
        assertThat(session.gs.finalReadCalls()).isEqualTo(2);
        assertThat(MessageDigest.isEqual(session.gs.physicalSession(), committedSession)).isTrue();
        assertThat(session.gsReceiptImage()).isEqualTo(gsReceipt);
        assertSameRegistry(
            active, session.registry.requireExactOriginalNewActiveRecord(session.genesis()));
        try (OriginalNewCredential replay = session.issue()) {
          assertSameCredential(before, replay, handle);
        }
        String confirmed = session.durableImage();
        assertThat(session.complete()).isEqualTo(recovered);
        assertThat(session.durableImage()).isEqualTo(confirmed);
        assertThat(session.gs.finalReadCalls()).isEqualTo(2);
      } finally {
        Arrays.fill(handle, (byte) 0);
      }
    }
  }

  @Test
  void missingActualInitialBarrierRefusesWithoutRecreatingOpenOrAdmittingCandidate()
      throws Exception {
    try (Session session = session();
        OriginalNewCredential before = session.issue()) {
      byte[] candidate = session.gs.physicalSession();
      session.gs.removeInitialBarrierForFault();
      String beforeRows = session.durableImage();
      assertThatThrownBy(session::complete).isInstanceOf(IllegalStateException.class);
      assertThat(session.gs.initialBarrierAbsent()).isTrue();
      assertThat(MessageDigest.isEqual(session.gs.physicalSession(), candidate)).isTrue();
      assertThat(session.durableImage()).isEqualTo(beforeRows);
      assertThat(session.gs.finalReadCalls()).isZero();
      assertThat(session.genesis().tokenHash()).isEqualTo(before.tokenSha256());
      assertThat(session.genesis().confirmedAdmissionAtMillis()).isNull();
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_canonical_binding_transition WHERE transition_id = ?",
                      session.gs.decisionId())
                  .get(0, String.class))
          .isEqualTo("PROVISIONAL");
    }
  }

  @Test
  void freshRetainedNonCreatingEvaluationIsPolicyDeniedWithoutOwnerMutation() throws Exception {
    try (Session session = session()) {
      var denied = session.retainControlledEvaluation(false, null);
      assertThat(denied.evidence().permitsCreation()).isFalse();
      byte[] candidate = session.gs.physicalSession();
      assertThat(candidate).isNotNull();
      String before = session.capacityImage() + session.durableImage();
      for (int attempt = 0; attempt < 2; attempt++) {
        CapacityRefusedException failure =
            assertThrows(
                CapacityRefusedException.class,
                () -> session.capacityClient.commit(denied, CLOCK.millis() + 5_000L));
        assertThat(failure.code()).isEqualTo(Failure.TENANT_BILLING_BLOCKED);
        assertThat(session.capacityImage() + session.durableImage()).isEqualTo(before);
        assertProvisionalUnchanged(session, candidate);
      }
    }
  }

  @Test
  void zeroQuotaRetainsOnlyTheExactAccountDenialAndReplaysIt() throws Exception {
    try (Session session = session()) {
      var request = session.retainControlledEvaluation(true, "0");
      assertThat(request.evidence().permitsCreation()).isTrue();
      assertThat(request.evidence().quota()).isZero();
      byte[] candidate = session.gs.physicalSession();
      assertThat(candidate).isNotNull();
      String before = session.capacityImage() + session.durableImage();
      UUID denialId;
      try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
        var start = new CountDownLatch(1);
        java.util.concurrent.Callable<CapacityRefusedException> deny =
            () -> {
              if (!start.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Concurrent quota requests did not start");
              return assertThrows(
                  CapacityRefusedException.class,
                  () -> session.capacityClient.commit(request, CLOCK.millis() + 5_000L));
            };
        var first = callers.submit(deny);
        var second = callers.submit(deny);
        start.countDown();
        denialId = assertExactAccountQuotaDenial(session, request, first.get(6, TimeUnit.SECONDS));
        var repeated = second.get(6, TimeUnit.SECONDS);
        assertThat(repeated.code()).isEqualTo(Failure.CAPACITY_QUOTA_EXCEEDED);
        assertThat(repeated.durableDenialId()).isEqualTo(denialId);
      }
      String retainedDecision = session.capacityDecisionImage();
      var replay =
          assertThrows(
              CapacityRefusedException.class,
              () -> session.capacityClient.commit(request, CLOCK.millis() + 5_000L));
      assertThat(replay.durableDenialId()).isEqualTo(denialId);
      assertThat(session.capacityRequest.evidence().quota()).isPositive();
      assertCapacityFailure(
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  session.capacityClient.commit(session.capacityRequest, CLOCK.millis() + 5_000L)),
          Status.Code.ALREADY_EXISTS,
          "IDEMPOTENCY_CONFLICT");
      assertThat(session.capacityImage() + session.durableImage()).isEqualTo(before);
      assertThat(session.capacityDecisionImage()).isEqualTo(retainedDecision);
      assertProvisionalUnchanged(session, candidate);
    }
  }

  @Test
  void actualCoordinatorSettlesTheLastActiveSlotAndReplaysTheDenialWithoutAccountCapacityRpc()
      throws Exception {
    try (Session session = session(CLOCK, 1, 2);
        OriginalNewCredential credential = session.issue()) {
      var request = session.retainControlledEvaluation(true, "0");
      String capacityBefore = session.capacityImage();
      String transitionBefore = session.gsReceiptImage();
      byte[] candidate = session.gs.physicalSession();

      var denial = assertThrows(CapacityRefusedException.class, () -> session.complete(request));
      UUID denialId = assertExactAccountQuotaDenial(session, request, denial);
      String retainedDecision = session.capacityDecisionImage();
      var charge =
          session
              .gs
              .dsl()
              .fetchSingle(
                  "SELECT * FROM game_session_capacity_evidence_charge WHERE admission_attempt_id=?",
                  request.admissionAttemptId());
      assertThat(charge.get("account_denial_id", UUID.class)).isEqualTo(denialId);
      assertThat(charge.get("tenant_id", UUID.class)).isEqualTo(request.identity().tenantId());
      assertThat(charge.get("admission_id", UUID.class))
          .isEqualTo(request.identity().admissionId());
      assertThat(charge.get("mutation_digest", String.class)).isEqualTo(request.mutationDigest());
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FILTER (WHERE account_denial_id IS NULL) AS active,"
                          + " count(*) AS retained FROM game_session_capacity_evidence_charge"
                          + " WHERE tenant_id=?",
                      request.identity().tenantId())
                  .intoMap())
          .containsEntry("active", 0L)
          .containsEntry("retained", 1L);
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle("SELECT count(*) FROM game_session_original_new_capacity_execution")
                  .get(0, Long.class))
          .isZero();
      var settledState = new java.util.TreeMap<>(session.gs.capacityStateImage());
      // The exact current owner can renew its ordinary private region lease during replay.
      settledState.keySet().removeIf(key -> key.startsWith("redis:tick-executor-lease:"));
      assertThat(settledState.keySet())
          .noneMatch(key -> key.startsWith("redis:session:game:capacity-execution:"));

      session.stopCapacityServer();
      var replay = assertThrows(CapacityRefusedException.class, () -> session.complete(request));
      assertThat(replay.code()).isEqualTo(Failure.CAPACITY_QUOTA_EXCEEDED);
      assertThat(replay.durableDenialId()).isEqualTo(denialId);
      assertThat(session.capacityImage()).isEqualTo(capacityBefore);
      assertThat(session.capacityDecisionImage()).isEqualTo(retainedDecision);
      assertThat(session.gsReceiptImage()).isEqualTo(transitionBefore);
      var replayState = new java.util.TreeMap<>(session.gs.capacityStateImage());
      replayState.keySet().removeIf(key -> key.startsWith("redis:tick-executor-lease:"));
      assertThat(replayState).isEqualTo(settledState);
      assertThat(session.gs.finalReadCalls()).isZero();
      assertThat(session.genesis().tokenHash()).isEqualTo(credential.tokenSha256());
      assertThat(session.genesis().confirmedAdmissionAtMillis()).isNull();
      assertProvisionalUnchanged(session, candidate);
    }
  }

  private static UUID assertExactAccountQuotaDenial(
      Session session, TenantCapacityAdmission.Request request, CapacityRefusedException denial) {
    assertThat(denial.code()).isEqualTo(Failure.CAPACITY_QUOTA_EXCEEDED);
    UUID denialId = denial.durableDenialId();
    assertThat(denialId).isNotNull();
    var decision =
        session.dsl.fetchSingle(
            "SELECT * FROM account_capacity_origin_decisions WHERE decision_id=?", denialId);
    assertThat(decision.get("decision", String.class)).isEqualTo("NO_ACCEPT");
    assertThat(decision.get("refusal_code", String.class)).isEqualTo("CAPACITY_QUOTA_EXCEEDED");
    assertThat(decision.get("admission_attempt_id", UUID.class))
        .isEqualTo(request.admissionAttemptId());
    assertThat(decision.get("canonical_payload", byte[].class))
        .containsExactly(CapacityAdmissionDigest.preimage(request));
    assertThat(
            session
                .dsl
                .fetchSingle("SELECT count(*) FROM account_tenant_capacity_reservations")
                .get(0, Long.class))
        .isZero();
    return denialId;
  }

  @Test
  void exactRetainedEvaluationOutsideOwnerWindowIsStaleWithoutOwnerMutation() throws Exception {
    try (Session session = session()) {
      waitForDatabaseTime(session.dsl, session.capacityRequest.evidence().expiresAt());
      assertCapacityRefusalWithoutMutation(
          session, session.capacityRequest, Status.Code.UNAVAILABLE, "CAPACITY_ADMISSION_STALE");
    }
  }

  @Test
  void capacityOwnerAcceptsFreshEvidenceWhenItsApplicationClockTrailsPostgres() throws Exception {
    try (Session session = session()) {
      var evidence = session.capacityRequest.evidence();
      Clock behindEvidence = Clock.fixed(evidence.evaluatedAt().minusSeconds(1), ZoneOffset.UTC);
      assertThat(behindEvidence.instant()).isBefore(evidence.evaluatedAt());
      assertThat(AccountTenantCapacityRepository.databaseNow(session.dsl))
          .isAfterOrEqualTo(evidence.evaluatedAt());

      var owner = session.account.capacityOwner(behindEvidence);
      var result = owner.commit(session.capacityRequest, GS);

      assertThat(result.state()).isEqualTo(TenantCapacityAdmission.State.RESERVED);
      var usage =
          session.dsl.fetchSingle(
              "SELECT held_units, used_units FROM account_tenant_capacity_usage "
                  + "WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
              result.identity().tenantId());
      assertThat(usage.get("held_units", Long.class)).isEqualTo(1L);
      assertThat(usage.get("used_units", Long.class)).isZero();
    }
  }

  @Test
  void actualUsageLockWaitAcrossExpiryCannotCreateAReservationEvenWithAFrozenOwnerClock()
      throws Exception {
    try (Session session = session();
        var worker = Executors.newSingleThreadExecutor();
        var lock = session.account.dataSource.getConnection()) {
      var request = session.retainControlledEvaluation(true, null);
      // Wait outside the owner transaction so the actual lock wait fits its five-second budget.
      waitForDatabaseTime(session.dsl, request.evidence().expiresAt().minusSeconds(2));
      lock.setAutoCommit(false);
      int blocker;
      try (var statement = lock.createStatement();
          var pid = statement.executeQuery("SELECT pg_backend_pid()")) {
        assertThat(pid.next()).isTrue();
        blocker = pid.getInt(1);
      }
      try (var statement =
          lock.prepareStatement(
              "SELECT * FROM account_tenant_capacity_usage WHERE tenant_uuid = ? "
                  + "AND owner_service = 'game-session-service' FOR UPDATE")) {
        statement.setObject(1, request.identity().tenantId());
        try (var usage = statement.executeQuery()) {
          assertThat(usage.next()).isTrue();
        }
      }
      String before = session.capacityImage();
      var owner =
          session.account.capacityOwner(
              Clock.fixed(request.evidence().evaluatedAt(), ZoneOffset.UTC));
      var result = worker.submit(() -> owner.commit(request, GS));
      try {
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        boolean waiting;
        do {
          waiting =
              session
                  .dsl
                  .fetchSingle(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE "
                          + "? = ANY(pg_blocking_pids(pid)) "
                          + "AND query LIKE 'SELECT * FROM account_tenant_capacity_usage%')",
                      blocker)
                  .get(0, boolean.class);
          if (!waiting) Thread.sleep(10);
        } while (!waiting && System.nanoTime() < waitUntil);
        assertThat(waiting).as("the actual owner must wait on the held usage row").isTrue();
        waitForDatabaseTime(session.dsl, request.evidence().expiresAt());
      } finally {
        lock.rollback();
      }
      assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
          .hasCauseInstanceOf(AccountTenantCapacityRepository.CapacityException.class)
          .hasStackTraceContaining("CAPACITY_ADMISSION_STALE");
      assertThat(session.capacityImage()).isEqualTo(before);
      assertThat(session.dsl.fetchCount(DSL.table("account_tenant_capacity_reservations")))
          .isZero();
    }
  }

  private static void waitForDatabaseTime(DSLContext dsl, Instant target) {
    dsl.fetchSingle(
        "SELECT pg_sleep(GREATEST(0, extract(epoch FROM (?::timestamptz - clock_timestamp()))))",
        AccountTenantCapacityRepository.at(target));
    assertThat(
            dsl.fetchSingle("SELECT clock_timestamp()")
                .get(0, java.time.OffsetDateTime.class)
                .toInstant())
        .isAfterOrEqualTo(target.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
  }

  private static void assertCapacityRefusalWithoutMutation(
      Session session, TenantCapacityAdmission.Request request, Status.Code status, String code)
      throws Exception {
    byte[] candidate = session.gs.physicalSession();
    assertThat(candidate).isNotNull();
    String before = session.capacityImage() + session.durableImage();
    assertCapacityFailure(
        assertThrows(
            StatusRuntimeException.class,
            () -> session.capacityClient.commit(request, CLOCK.millis() + 5_000L)),
        status,
        code);
    assertThat(session.capacityImage() + session.durableImage()).isEqualTo(before);
    assertProvisionalUnchanged(session, candidate);
  }

  @Test
  void retainedOriginalReplaysBeforeChangedNonCreatingEvidenceCanBecomeADenial() throws Exception {
    try (Session session = session()) {
      String unretainedScopeHash =
          RuntimePlayerAdmissionEntitlementEvidence.connectScopeTokenHash("unretained-test-scope");
      var wrongScope =
          session.retainControlledEvaluation(true, null, Map.of(), unretainedScopeHash);
      assertCapacityRefusalWithoutMutation(
          session, wrongScope, Status.Code.UNAVAILABLE, "ENTITLEMENT_UNAVAILABLE");
      var first = session.capacityClient.commit(session.capacityRequest, CLOCK.millis() + 5_000L);
      var changed = session.retainControlledEvaluation(false, null);
      var quotaRefusal = session.retainControlledEvaluation(true, "0");
      byte[] candidate = session.gs.physicalSession();
      assertThat(candidate).isNotNull();
      String before = session.capacityImage() + session.durableImage();
      String retainedDecision = session.capacityDecisionImage();
      StatusRuntimeException failure =
          assertThrows(
              StatusRuntimeException.class,
              () -> session.capacityClient.commit(changed, CLOCK.millis() + 5_000L));
      assertCapacityFailure(failure, Status.Code.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
      assertCapacityRefusalWithoutMutation(
          session, wrongScope, Status.Code.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
      assertCapacityRefusalWithoutMutation(
          session, quotaRefusal, Status.Code.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
      var player = (TenantCapacityAdmission.PlayerContext) session.capacityRequest.context();
      try (var replayCaller = Executors.newVirtualThreadPerTaskExecutor()) {
        session.serializable.executeWithoutResult(
            status -> {
              assertThat(
                      session.dsl.fetchOne(
                          "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE",
                          player.accountId()))
                  .isNotNull();
              assertThat(
                      CompletableFuture.supplyAsync(
                              () ->
                                  session.capacityClient.commit(
                                      session.capacityRequest, CLOCK.millis() + 5_000L),
                              replayCaller)
                          .orTimeout(6L, TimeUnit.SECONDS)
                          .join())
                  .isEqualTo(first);
            });
      }
      assertThat(session.capacityImage() + session.durableImage()).isEqualTo(before);
      assertThat(session.capacityDecisionImage()).isEqualTo(retainedDecision);
      assertProvisionalUnchanged(session, candidate);
    }
  }

  private static void assertProvisionalUnchanged(Session session, byte[] candidate) {
    assertThat(session.gs.physicalSession()).containsExactly(candidate);
    assertThat(
            session
                .gs
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_canonical_binding_transition WHERE transition_id = ?",
                    session.gs.decisionId())
                .get(0, String.class))
        .isEqualTo("PROVISIONAL");
  }

  private static void assertCapacityFailure(
      StatusRuntimeException failure, Status.Code expectedStatus, String expectedCode)
      throws Exception {
    assertThat(failure.getStatus().getCode()).isEqualTo(expectedStatus);
    var status = Objects.requireNonNull(StatusProto.fromThrowable(failure));
    assertThat(status.getDetailsCount()).isOne();
    assertThat(status.getDetails(0).unpack(ErrorDetail.class).getCode()).isEqualTo(expectedCode);
  }

  private Session session() throws Exception {
    return session(CLOCK);
  }

  private Session session(Clock capacityClock) throws Exception {
    return session(capacityClock, 128, 128);
  }

  private Session session(Clock capacityClock, int maximumActiveRecords, int maximumRetainedRecords)
      throws Exception {
    accountFixtures.temporaryDirectory = temporaryDirectory;
    return new Session(capacityClock, maximumActiveRecords, maximumRetainedRecords);
  }

  private final class Session implements AutoCloseable {
    private final AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.PhysicalRedisFixture
        redis =
            AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.PhysicalRedisFixture
                .start();
    private AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.Fixture account;
    private CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture gs;
    private CanonicalGameplayAdmissionDecisionReadClient reverse;
    private AccountOriginalNewFinalAdmissionGrpcReadClient finalRead;
    private AccountGameplayAdmissionLeaseClient client;
    private net.firedevops.firemud.gamesession.client.AccountTenantCapacityClient capacityClient;
    private net.firedevops.firemud.accountservice.client
            .AccountOriginalNewCapacityExecutionReadClient
        capacityRead;
    private net.firedevops.firemud.accountservice.service.AccountTenantCapacityOwner capacityOwner;
    private net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request
        capacityRequest;
    private Server capacityServer;
    private AccountGameplayDelegationTokenRegistry registry;
    private AccountResponseEnvelopeCryptography responseCrypto;
    private DSLContext dsl;
    private TransactionTemplate serializable;
    private Server accountServer;
    private LoopbackMtlsTestPki.Pki pki;
    private Path pkiDirectory;
    private AccountPublicJwksCache jwks;
    private String jwt;
    private AccountGameplayAdmissionLeaseEvidence lease;
    private CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector targetSelector;

    private void enableOriginReceiving() throws Exception {
      gs.enableOriginReceiving();
    }

    private Permit beginOriginObservation(Clock clock) throws Exception {
      return beginOriginObservation(clock, Duration.ofSeconds(5));
    }

    private Permit beginOriginObservation(Clock clock, Duration observationWindow)
        throws Exception {
      var receiver = gs.originReceiver(clock);
      return OriginReceivingMtlsTestBridge.call(
          pki.keyManagerFactory(pki.identity("gs")),
          pki.trustManagerFactory(),
          pki.keyManagerFactory(pki.identity("account-client")),
          net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest.preimage(
              capacityRequest),
          headers ->
              receiver.beginObservation(
                  capacityRequest, clock.instant().plus(observationWindow), headers));
    }

    private void deliverOriginResolution(
        net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution resolution,
        Permit permit,
        Clock clock)
        throws Exception {
      var receiver = gs.originReceiver(clock);
      OriginReceivingMtlsTestBridge.call(
          pki.keyManagerFactory(pki.identity("gs")),
          pki.trustManagerFactory(),
          pki.keyManagerFactory(pki.identity("account-client")),
          resolution.canonicalBytes(),
          headers -> {
            receiver.receiveExact(resolution, permit, headers);
            return null;
          });
    }

    private Session(Clock capacityClock, int maximumActiveRecords, int maximumRetainedRecords)
        throws Exception {
      try {
        account = accountFixtures.newCapacityPlayerEntitlementFixture(redis.client());
        dsl =
            DSL.using(new TransactionAwareDataSourceProxy(account.dataSource), SQLDialect.POSTGRES);
        var manager = new DataSourceTransactionManager(account.dataSource);
        serializable = new TransactionTemplate(manager);
        serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        var generations = new AccountAuthorityGenerationRepository(dsl);
        var outbox = new AccountAuthorityOutboxRepository(dsl);
        var source = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
        var bundles = new AccountAuthEvidenceBundleRepository(dsl, generations, outbox);
        registry =
            new AccountGameplayDelegationTokenRegistry(
                new AccountGameplayDelegationIssuanceRepository(dsl, source, bundles),
                manager,
                redis.client(),
                CLOCK,
                GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
                30_000L);
        responseCrypto =
            responseCryptography(
                temporaryDirectory.resolve("actual-final-key-" + UUID.randomUUID()));
        var identity = account.committedCandidate.identity();
        var recovered =
            account.responseRecoveryOwner.recoverInitialLoginResponse(
                identity.requestId(),
                identity.accountId(),
                new CallerIdentity(identity.callerWorkload(), identity.callerContextId()));
        var initial = InitialGameplayLoginResult.fromRecoveredCredential(recovered);
        byte[] token = initial.compactJwtBytes();
        try {
          jwt = new String(token, StandardCharsets.US_ASCII);
        } finally {
          Arrays.fill(token, (byte) 0);
        }
        var trusted = account.signerFixture.trustedJwksSource();
        jwks =
            new AccountPublicJwksCache(
                trusted, trusted.sourceIdentity(), CLOCK, Duration.ofSeconds(30));
        var request =
            AuthenticateRequest.newBuilder()
                .setRequestId(initial.requestId().toString())
                .setEmail("controlled-initial-delivery@example.test")
                .setPassword("controlled-input")
                .setSourceContext(
                    GameplayLoginSourceContext.newBuilder()
                        .setCallerContextId(initial.callerContextId().toString())
                        .setCanonicalClientAddress("203.0.113.10")
                        .setTransportClass(
                            GameplayLoginTransportClass
                                .GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY))
                .build();
        // Controlled initial delivery only: all response fields derive from real recovered owner
        // bytes. GS independently verifies signature and the complete aggregate before journaling.
        try (var validated =
            CanonicalGameplayLoginResult.fromAuthenticatedResponse(
                projectRecoveredInitial(initial),
                request,
                new AccountAsymmetricJwtVerifier(jwks, CLOCK),
                CLOCK)) {
          gs =
              CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture.open(
                  account.dataSource,
                  AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.admissionCandidate(
                      account),
                  validated,
                  Files.createDirectory(
                      temporaryDirectory.resolve("actual-final-sources-" + UUID.randomUUID())));
        }
        pkiDirectory =
            Files.createDirectory(
                temporaryDirectory.resolve("actual-final-pki-" + UUID.randomUUID()));
        pki =
            LoopbackMtlsTestPki.create(
                pkiDirectory,
                "Actual final composition test CA",
                new LoopbackMtlsTestPki.IdentitySpec("account", ACCOUNT, true),
                new LoopbackMtlsTestPki.IdentitySpec("gs", GS, true),
                new LoopbackMtlsTestPki.IdentitySpec("account-client", ACCOUNT, false),
                new LoopbackMtlsTestPki.IdentitySpec("gs-client", GS, false));
        UUID requestId = UUID.randomUUID();
        var gsServer =
            gs.startServer(
                pki.keyManagerFactory(pki.identity("gs")), pki.trustManagerFactory(), requestId);
        var endpoints = new ServiceEndpointsProperties();
        endpoints.setGameSessionService("localhost:" + gsServer.getPort());
        var accountTls = pki.clientProperties(pkiDirectory, pki.identity("account-client"));
        reverse =
            new CanonicalGameplayAdmissionDecisionReadClient(
                endpoints, accountTls, new GrpcChannelFactory(), "test");
        reverse.init();
        finalRead =
            new AccountOriginalNewFinalAdmissionGrpcReadClient(
                endpoints, accountTls, new GrpcChannelFactory(), "test");
        finalRead.init();
        restartAccount();
        capacityOwner = account.capacityOwner(capacityClock);
        capacityServer =
            new net.firedevops.firemud.accountservice.service.impl.AccountTenantCapacityGrpcService(
                    capacityOwner, "test")
                .createUnregisteredServer(
                    "127.0.0.1:0",
                    pki.keyManagerFactory(pki.identity("account")),
                    pki.trustManagerFactory());
        capacityServer.start();
        var capacityEndpoints = new ServiceEndpointsProperties();
        capacityEndpoints.setAccountService("localhost:" + capacityServer.getPort());
        capacityClient =
            new net.firedevops.firemud.gamesession.client.AccountTenantCapacityClient(
                capacityEndpoints,
                pki.clientProperties(pkiDirectory, pki.identity("gs-client")),
                new GrpcChannelFactory(),
                "test",
                CLOCK);
        capacityClient.init();
        // All containers, keys, source servers, and INITIAL setup precede the <=15-second lease.
        lease =
            client
                .acquire(requestId, jwt, gs.candidate(), gs.target(), gs.generation())
                .originalLeaseEvidence();
        gs.installActualCandidate(lease, maximumActiveRecords, maximumRetainedRecords);
        // Actual retained PLAYER_ADMISSION evaluation, with the same original request and exact
        // GS target. Published/World captures remain the fixture's explicit controlled sources.
        var scope = account.capacityScope(gs.target());
        dsl.execute(
            "INSERT INTO account_capacity_origin_refusal_budget (tenant_uuid,maximum_refusals) VALUES (?,?)",
            scope.tenantId(),
            maximumActiveRecords == 1 ? 1 : 128);
        targetSelector =
            new net.firedevops.firemud.common.gamesession
                .CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector(
                requestId,
                scope.accountId(),
                "test",
                scope.tenantId(),
                scope.tenantSlug(),
                scope.worldSlug(),
                scope.realmId(),
                scope.realmSlug(),
                scope.playableStateNamespaceId(),
                "SHARED",
                scope.gameInstanceId(),
                scope.catalogRevision(),
                scope.pointerVersion());
        var evidence =
            account
                .playerEntitlementOwner(reverse)
                .evaluate(
                    new net.firedevops.firemud.accountservice.service
                        .AccountPlayerAdmissionEntitlementOwner.Request(
                        targetSelector, scope.connectScopeId(), GS),
                    jwt);
        var binding = gs.candidate();
        capacityRequest =
            new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request(
                    new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission
                        .Identity(scope.tenantId(), requestId, gs.decisionId()),
                    UUID.randomUUID(),
                    1,
                    "test",
                    new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission
                        .PlayerContext(
                        scope.accountId(),
                        scope.tenantId(),
                        scope.playableStateNamespaceId(),
                        scope.gameInstanceId(),
                        binding.characterId(),
                        binding.sessionId(),
                        scope.realmId(),
                        requestId,
                        "SHARED"),
                    net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Operation
                        .CAPACITY_CREATING,
                    new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission
                        .Shape(0, 0, 0),
                    new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission
                        .Shape(1, 0, 0),
                    net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Evidence
                        .player(evidence),
                    1L,
                    null)
                .withDigest();
        capacityRead =
            new net.firedevops.firemud.accountservice.client
                .AccountOriginalNewCapacityExecutionReadClient(
                endpoints, accountTls, new GrpcChannelFactory(), "test", CLOCK);
        capacityRead.init();
        var finalized = client.finalizeLease(jwt, leaseBytes(), gs.decisionId());
        assertThat(finalized.state())
            .isEqualTo(
                net.firedevops.firemud.common.account.admission
                    .AccountGameplayAdmissionLeaseGrpcCodec.State.COMMITTED);
        assertThat(finalized.originalLeaseEvidence().canonicalJson())
            .isEqualTo(lease.canonicalJson());
      } catch (Exception | Error failure) {
        try {
          close();
        } catch (Exception cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    private void restartAccount() throws Exception {
      if (client != null) client.close();
      if (accountServer != null) {
        accountServer.shutdownNow();
        if (!accountServer.awaitTermination(5, TimeUnit.SECONDS))
          throw new IllegalStateException("Account teardown timed out");
      }
      var verifier =
          new AccountGameplayAdmissionCallerVerifier(
              account.signerFixture.trustedJwksSource(), account.committedIssuanceOwner, CLOCK);
      var leaseOwner =
          new AccountGameplayAdmissionLeaseOwner(verifier, reverse, "test", account.dataSource);
      var credentialOwner =
          new AccountOriginalNewBoundGameplayCredentialOwner(
              account.dataSource,
              "test",
              verifier,
              account.signerFixture.signer(),
              registry,
              responseCrypto,
              CLOCK,
              finalRead);
      accountServer =
          AccountGameplayAdmissionLeaseManualFactory.createUnregisteredServer(
              "127.0.0.1:0",
              pki.keyManagerFactory(pki.identity("account")),
              pki.trustManagerFactory(),
              leaseOwner,
              credentialOwner,
              "test");
      accountServer.start();
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setAccountService("localhost:" + accountServer.getPort());
      client =
          new AccountGameplayAdmissionLeaseClient(
              endpoints,
              pki.clientProperties(pkiDirectory, pki.identity("gs-client")),
              new GrpcChannelFactory(),
              "test");
      client.init();
    }

    private byte[] leaseBytes() {
      return lease.canonicalJson().getBytes(StandardCharsets.UTF_8);
    }

    private OriginalNewCredential issue() {
      return client.issueOriginalNewBoundGameplayCredential(jwt, leaseBytes());
    }

    private CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture.Receipt complete() {
      return complete(capacityRequest);
    }

    private CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture.Receipt complete(
        TenantCapacityAdmission.Request request) {
      return gs.complete(
          client, jwks, jwt, System.currentTimeMillis() + 10_000L, capacityClient, request);
    }

    private void stopCapacityServer() throws InterruptedException {
      if (capacityServer != null) {
        capacityServer.shutdownNow();
        if (!capacityServer.awaitTermination(5, TimeUnit.SECONDS))
          throw new IllegalStateException("Capacity server teardown timed out");
        capacityServer = null;
      }
    }

    private Genesis genesis() {
      return serializable.execute(
          status ->
              new AccountOriginalNewBoundGameplayIssuanceRepository(dsl)
                  .readByLease(UUID.fromString((String) lease.carrier().get("leaseId"))));
    }

    /** Controlled evaluation inputs with actual immutable retention. */
    private TenantCapacityAdmission.Request retainControlledEvaluation(
        boolean creationAllowed, @org.jspecify.annotations.Nullable String quota) {
      return retainControlledEvaluation(creationAllowed, quota, Map.of(), null);
    }

    @SuppressWarnings("unchecked")
    private TenantCapacityAdmission.Request retainControlledEvaluation(
        boolean creationAllowed,
        @org.jspecify.annotations.Nullable String quota,
        Map<String, String> targetChanges,
        @org.jspecify.annotations.Nullable String scopeTokenHash) {
      var original = capacityRequest;
      var parsed =
          RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(
              original.evidence().canonicalBytes());
      var fields = parsed.fields();
      Map<String, Object> facts = new LinkedHashMap<>();
      for (String name :
          List.of(
              "sourceKind",
              "subscriptionStatus",
              "provisioningStatus",
              "gameplayAvailable",
              "allowPublicJoin",
              "allowNewGameplayBindings",
              "allowNewInstanceStarts",
              "quotas",
              "entitlementVersion",
              "tenantBillingSequence",
              "tenantAuthorityGeneration")) {
        facts.put(name, fields.get(name));
      }
      facts.put("allowNewGameplayBindings", creationAllowed);
      if (quota != null) {
        var quotas = new LinkedHashMap<>((Map<String, Object>) facts.get("quotas"));
        quotas.put(original.context().owner().quota(), quota);
        facts.put("quotas", quotas);
      }
      var target = new LinkedHashMap<>((Map<String, Object>) fields.get("target"));
      target.putAll(targetChanges);
      var evaluation =
          RuntimePlayerAdmissionEntitlementEvidence.fromOwnerEvaluation(
              original.identity().requestId(),
              original.evidence().callerWorkload(),
              scopeTokenHash == null ? parsed.verifiedConnectScopeTokenHash() : scopeTokenHash,
              parsed.membershipSource(),
              target,
              facts,
              (Map<String, Object>) fields.get("sourceReferences"),
              CLOCK.instant());
      serializable.executeWithoutResult(
          ignored -> new AccountTenantCapacityRepository(dsl).retainPlayerEvaluation(evaluation));
      return new TenantCapacityAdmission.Request(
              original.identity(),
              original.admissionAttemptId(),
              original.capacityRearmFence(),
              original.targetNamespace(),
              original.context(),
              original.operation(),
              original.priorShape(),
              original.requestedShape(),
              TenantCapacityAdmission.Evidence.player(evaluation),
              original.capacityDelta(),
              null)
          .withDigest();
    }

    private String capacityImage() {
      StringBuilder image = new StringBuilder();
      for (String table :
          List.of(
              "account_tenant_capacity_evaluations",
              "account_tenant_capacity_usage",
              "account_tenant_capacity_reservations",
              "account_tenant_capacity_attempt_history",
              "account_tenant_capacity_recovery_evidence")) {
        image
            .append(table)
            .append(
                dsl.fetch(
                        "SELECT (to_jsonb(t) || jsonb_build_object('_original_xmin', t.xmin::text))::text AS image FROM "
                            + table
                            + " t ORDER BY 1")
                    .getValues("image", String.class));
      }
      return image.toString();
    }

    private String capacityDecisionImage() {
      return dsl.fetch(
              "SELECT (to_jsonb(t) || jsonb_build_object('_xmin', t.xmin::text))::text AS image"
                  + " FROM account_capacity_origin_decisions t ORDER BY 1")
          .getValues("image", String.class)
          .toString();
    }

    private String durableImage() {
      return gsReceiptImage()
          + dsl.fetch(
                  "SELECT to_jsonb(t)::text AS image FROM account_original_new_bound_gameplay_issuances t ORDER BY 1")
              .getValues("image", String.class);
    }

    private String gsReceiptImage() {
      return gs.dsl()
          .fetch(
              "SELECT to_jsonb(t)::text AS image FROM game_session_canonical_binding_transition t ORDER BY 1")
          .getValues("image", String.class)
          .toString();
    }

    private void installConfirmationFault() {
      dsl.execute(
          "CREATE FUNCTION "
              + FAULT_FUNCTION
              + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
              + "RAISE EXCEPTION 'controlled actual final confirmation SQL loss'; RETURN NEW; END; $$");
      dsl.execute(
          "CREATE TRIGGER "
              + FAULT_TRIGGER
              + " BEFORE UPDATE ON account_original_new_bound_gameplay_issuances "
              + "FOR EACH ROW WHEN (OLD.confirmed_admission_at_ms IS NULL AND NEW.confirmed_admission_at_ms IS NOT NULL) "
              + "EXECUTE FUNCTION "
              + FAULT_FUNCTION
              + "()");
    }

    private void removeConfirmationFault() {
      dsl.execute(
          "DROP TRIGGER " + FAULT_TRIGGER + " ON account_original_new_bound_gameplay_issuances");
      dsl.execute("DROP FUNCTION " + FAULT_FUNCTION + "()");
    }

    @Override
    public void close() throws Exception {
      try {
        if (capacityRead != null) capacityRead.close();
        if (capacityClient != null) capacityClient.close();
        stopCapacityServer();
        if (client != null) client.close();
      } finally {
        try {
          if (accountServer != null) {
            accountServer.shutdownNow();
            if (!accountServer.awaitTermination(5, TimeUnit.SECONDS))
              throw new IllegalStateException("Account teardown timed out");
          }
        } finally {
          try {
            if (finalRead != null) finalRead.close();
          } finally {
            try {
              if (reverse != null) reverse.close();
            } finally {
              try {
                if (gs != null) gs.close();
              } finally {
                redis.close();
              }
            }
          }
        }
      }
    }
  }

  private static void assertPhysicalSession(
      byte[] bytes, CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture.Receipt result)
      throws Exception {
    var value = tools.jackson.databind.json.JsonMapper.builder().build().readTree(bytes);
    assertThat(value.path("schemaVersion").asInt()).isEqualTo(2);
    assertThat(value.path("admissionState").asText()).isEqualTo("COMPLETE");
    assertThat(value.path("admissionAt").asLong()).isEqualTo(result.admissionAtMillis());
    assertThat(value.path("continuityBindingExpiresAt").asLong())
        .isEqualTo(result.continuityExpiresAtMillis());
  }

  private static void assertConfirmed(
      CanonicalGameplayOriginalNewFinalAdmissionCompositionFixture.Receipt result,
      Genesis genesis) {
    assertThat(genesis.confirmedAdmissionAtMillis()).isEqualTo(result.admissionAtMillis());
    assertThat(genesis.confirmedContinuityExpiresAtMillis())
        .isEqualTo(result.continuityExpiresAtMillis());
    // Account seals a different domain: final readback plus its ACTIVE registry proof.
    assertThat(genesis.confirmationReadbackSha256())
        .matches("[0-9a-f]{64}")
        .isNotEqualTo(result.finalSessionSha256());
    assertThat(result.continuityExpiresAtMillis() - result.admissionAtMillis()).isEqualTo(120_000L);
  }

  private static void assertSameCredential(
      OriginalNewCredential expected, OriginalNewCredential actual, byte[] originalHandle) {
    byte[] replayHandle = actual.opaqueRebindHandle();
    try {
      assertThat(MessageDigest.isEqual(originalHandle, replayHandle)).isTrue();
    } finally {
      Arrays.fill(replayHandle, (byte) 0);
    }
    assertThat(actual.signedBoundGsACompactJwt().equals(expected.signedBoundGsACompactJwt()))
        .isTrue();
    assertThat(actual.tokenSha256()).isEqualTo(expected.tokenSha256());
    assertThat(actual.tokenJti()).isEqualTo(expected.tokenJti());
    assertThat(actual.lineageId()).isEqualTo(expected.lineageId());
    assertThat(actual.issuanceFence()).isEqualTo(expected.issuanceFence());
    assertThat(actual.originalAckReceiptXid()).isEqualTo(expected.originalAckReceiptXid());
  }

  private static void assertSameRegistry(
      ActiveRegistryReadback expected, ActiveRegistryReadback actual) {
    assertThat(actual.tokenHash()).isEqualTo(expected.tokenHash());
    assertThat(actual.accountId()).isEqualTo(expected.accountId());
    assertThat(actual.jti()).isEqualTo(expected.jti());
    assertThat(actual.tokenExpiryEpochSecond()).isEqualTo(expected.tokenExpiryEpochSecond());
    assertThat(actual.absoluteExpiryMillis()).isEqualTo(expected.absoluteExpiryMillis());
    assertThat(actual.canonicalPendingRecordSha256())
        .isEqualTo(expected.canonicalPendingRecordSha256());
  }

  private static AuthenticateResponse projectRecoveredInitial(InitialGameplayLoginResult result) {
    byte[] token = result.compactJwtBytes();
    try {
      return AuthenticateResponse.newBuilder()
          .setAuthToken(new String(token, StandardCharsets.US_ASCII))
          .setAccountId(result.accountId().toString())
          .setRequestId(result.requestId().toString())
          .setCallerContextId(result.callerContextId().toString())
          .setTokenJti(result.tokenJti().toString())
          .setTokenSha256(result.tokenSha256())
          .setTokenProfile(result.profile())
          .setIssuedAtEpochSeconds(result.issuedAtEpochSecond())
          .setNotBeforeEpochSeconds(result.notBeforeEpochSecond())
          .setExpiresAtEpochSeconds(result.expiresAtEpochSecond())
          .setTokenGeneration(result.tokenGeneration())
          .setIssuanceFence(result.issuanceFence())
          .setAuthorityTupleCanonicalJson(ByteString.copyFrom(result.authorityTupleCanonicalJson()))
          .setOutboxCheckpointsCanonicalJson(
              ByteString.copyFrom(result.outboxCheckpointsCanonicalJson()))
          .setOutboxSourceEventEvidenceCanonicalJson(
              ByteString.copyFrom(result.outboxSourceEventEvidenceCanonicalJson()))
          .build();
    } finally {
      Arrays.fill(token, (byte) 0);
    }
  }

  private static AccountResponseEnvelopeCryptography responseCryptography(Path directory)
      throws Exception {
    Files.createDirectories(directory);
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    try {
      Files.writeString(
          directory.resolve("keyring"),
          "firemud-account-response-envelope-keyring-v1\nactive integration-key "
              + Base64.getUrlEncoder().withoutPadding().encodeToString(key)
              + "\n",
          StandardCharsets.US_ASCII);
      return new AccountResponseEnvelopeCryptography(
          new AccountResponseEnvelopeKeyring(directory.toString()));
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }
}
