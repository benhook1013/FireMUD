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
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
              java.time.ZoneOffset.UTC);
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
      var next = session.beginOriginObservation(CLOCK);
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
              "reservation_policy_applied"))
        assertThat(moved.get(field)).isEqualTo(before.get(field));
      assertThat((Long) moved.get("custody_owner_fence"))
          .isGreaterThan((Long) before.get("custody_owner_fence"));
      assertThat(CLOCK.instant()).isBefore(permit.deadline());
      var physical = session.gs.originPhysicalImage();
      assertThatThrownBy(() -> session.deliverOriginResolution(denied, permit, CLOCK))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(session.gs.originBudgetImage()).isEqualTo(moved);
      assertThat(session.gs.originPhysicalImage()).isEqualTo(physical);
      var successor = session.beginOriginObservation(CLOCK);
      assertThat(successor.ordinal()).isEqualTo(2);
      session.deliverOriginResolution(denied, successor, CLOCK);
      assertThat(session.gs.originPhysicalImage()).containsEntry("state", "DENIED");
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
  void actualOriginObservationExhaustionRetainsHoldAndCannotResetOnRecreatedReceiver()
      throws Exception {
    try (Session session = session()) {
      session.enableOriginReceiving();
      session.gs.prepareReceivingOrigin(session.capacityRequest);
      for (int ordinal = 1; ordinal <= 12; ordinal++)
        assertThat(session.beginOriginObservation(CLOCK).ordinal()).isEqualTo(ordinal);
      var before = session.gs.originBudgetImage();
      assertThatThrownBy(() -> session.beginOriginObservation(CLOCK))
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

  @Test
  void changedCatalogRevisionRefusesBeforeAccountCapacityCommitment() throws Exception {
    try (Session session = session()) {
      var original = session.capacityRequest;
      var target = (Map<?, ?>) original.evidence().fields().get("target");
      long revision = Long.parseLong((String) target.get("catalogRevision"));
      var changed = session.retainControlledEvaluation(true, null, revision + 1L);
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
          .isInstanceOf(IllegalStateException.class)
          .hasCauseInstanceOf(CapacityRefusedException.class)
          .satisfies(
              failure ->
                  assertThat(((CapacityRefusedException) failure.getCause()).code())
                      .isEqualTo(Failure.TENANT_BILLING_BLOCKED));
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
  void freshCreationEligibleEvidenceWithZeroQuotaIsDeniedWithoutOwnerMutation() throws Exception {
    try (Session session = session()) {
      var request = session.retainControlledEvaluation(true, "0");
      assertThat(request.evidence().permitsCreation()).isTrue();
      assertThat(request.evidence().quota()).isZero();
      byte[] candidate = session.gs.physicalSession();
      assertThat(candidate).isNotNull();
      String before = session.capacityImage() + session.durableImage();
      assertThat(
              assertThrows(
                      CapacityRefusedException.class,
                      () -> session.capacityClient.commit(request, CLOCK.millis() + 5_000L))
                  .code())
          .isEqualTo(Failure.CAPACITY_QUOTA_EXCEEDED);
      assertThat(session.capacityImage() + session.durableImage()).isEqualTo(before);
      assertProvisionalUnchanged(session, candidate);
    }
  }

  @Test
  void exactRetainedEvaluationOutsideOwnerWindowIsStaleWithoutOwnerMutation() throws Exception {
    try (Session session = session(Clock.offset(CLOCK, Duration.ofSeconds(60)))) {
      assertCapacityRefusalWithoutMutation(
          session, session.capacityRequest, Status.Code.UNAVAILABLE, "CAPACITY_ADMISSION_STALE");
    }
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
      var wrongScope = session.retainControlledEvaluation(true, null, null, unretainedScopeHash);
      assertCapacityRefusalWithoutMutation(
          session, wrongScope, Status.Code.UNAVAILABLE, "ENTITLEMENT_UNAVAILABLE");
      var first = session.capacityClient.commit(session.capacityRequest, CLOCK.millis() + 5_000L);
      var changed = session.retainControlledEvaluation(false, null);
      byte[] candidate = session.gs.physicalSession();
      assertThat(candidate).isNotNull();
      String before = session.capacityImage() + session.durableImage();
      StatusRuntimeException failure =
          assertThrows(
              StatusRuntimeException.class,
              () -> session.capacityClient.commit(changed, CLOCK.millis() + 5_000L));
      assertCapacityFailure(failure, Status.Code.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
      assertCapacityRefusalWithoutMutation(
          session, wrongScope, Status.Code.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
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
    accountFixtures.temporaryDirectory = temporaryDirectory;
    return new Session(capacityClock);
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

    private net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation.Permit
        beginOriginObservation(Clock clock) throws Exception {
      var receiver = gs.originReceiver(clock);
      return OriginReceivingMtlsTestBridge.call(
          pki.keyManagerFactory(pki.identity("gs")),
          pki.trustManagerFactory(),
          pki.keyManagerFactory(pki.identity("account-client")),
          net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest.preimage(
              capacityRequest),
          headers ->
              receiver.beginObservation(capacityRequest, clock.instant().plusSeconds(5), headers));
    }

    private void deliverOriginResolution(
        net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution resolution,
        net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation.Permit
            permit,
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

    private Session(Clock capacityClock) throws Exception {
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
        // All containers, keys, source servers, and INITIAL setup precede the <=15-second lease.
        lease =
            client
                .acquire(requestId, jwt, gs.candidate(), gs.target(), gs.generation())
                .originalLeaseEvidence();
        gs.installActualCandidate(lease);
        // Actual retained PLAYER_ADMISSION evaluation, with the same original request and exact
        // GS target. Published/World captures remain the fixture's explicit controlled sources.
        var scope = account.capacityScope(gs.target());
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
      return gs.complete(
          client, jwks, jwt, System.currentTimeMillis() + 10_000L, capacityClient, capacityRequest);
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
      return retainControlledEvaluation(creationAllowed, quota, null);
    }

    private TenantCapacityAdmission.Request retainControlledEvaluation(
        boolean creationAllowed,
        @org.jspecify.annotations.Nullable String quota,
        @org.jspecify.annotations.Nullable Long catalogRevision) {
      return retainControlledEvaluation(creationAllowed, quota, catalogRevision, null);
    }

    @SuppressWarnings("unchecked")
    private TenantCapacityAdmission.Request retainControlledEvaluation(
        boolean creationAllowed,
        @org.jspecify.annotations.Nullable String quota,
        @org.jspecify.annotations.Nullable Long catalogRevision,
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
      if (catalogRevision != null) target.put("catalogRevision", Long.toString(catalogRevision));
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
        if (capacityServer != null) {
          capacityServer.shutdownNow();
          if (!capacityServer.awaitTermination(5, TimeUnit.SECONDS))
            throw new IllegalStateException("Capacity server teardown timed out");
        }
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
