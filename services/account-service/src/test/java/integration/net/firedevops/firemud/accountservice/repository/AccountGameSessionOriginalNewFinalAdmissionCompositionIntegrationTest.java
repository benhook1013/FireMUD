package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.ByteString;
import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.math.BigInteger;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionResponse;
import net.firedevops.firemud.account.v1.GameplayLoginSourceContext;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionGrpcReadClient;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
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
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import net.firedevops.firemud.gamesession.client.AccountTenantCapacityClient;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture(true);
  private final AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest accountFixtures =
      AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.withPostgres(POSTGRES);
  @TempDir Path temporaryDirectory;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @AfterEach
  void dropOwnedSchemas() {
    accountFixtures.dropRunOwnedSchemas();
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
        String capacity = session.capacityImage();
        String decisions = session.capacityDecisionImage();
        var retained =
            session.capacityClient.commitWithSendBoundary(
                session.capacityRequest,
                CLOCK.millis() + 5_000L,
                () -> AccountTenantCapacityClient.SendDisposition.READ_RETAINED);
        assertThat(retained.identity()).isEqualTo(session.capacityRequest.identity());
        assertThat(retained.admissionAttemptId())
            .isEqualTo(session.capacityRequest.admissionAttemptId());
        assertThat(retained.mutationDigest()).isEqualTo(session.capacityRequest.mutationDigest());
        assertThat(session.durableImage()).isEqualTo(rows);
        assertThat(session.capacityImage()).isEqualTo(capacity);
        assertThat(session.capacityDecisionImage()).isEqualTo(decisions);
        var physical = new java.util.TreeMap<>(session.gs.capacityStateImage());
        var physicalLeases = session.gs.capacityLeaseOwnerImage();
        var physicalWithoutLeaseValues = withoutLeaseValues(physical);
        var regionFences = regionFenceImage(session);
        session.stopCapacityServer();
        session.restartAccount();
        var retry = session.complete();
        assertThat(retry).isEqualTo(first);
        assertThat(MessageDigest.isEqual(session.gs.physicalSession(), sessionBytes)).isTrue();
        assertThat(session.durableImage()).isEqualTo(rows);
        assertThat(session.capacityImage()).isEqualTo(capacity);
        assertThat(session.capacityDecisionImage()).isEqualTo(decisions);
        var replayedPhysical = new java.util.TreeMap<>(session.gs.capacityStateImage());
        assertThat(withoutLeaseValues(replayedPhysical)).isEqualTo(physicalWithoutLeaseValues);
        assertThat(session.gs.capacityLeaseOwnerImage()).isEqualTo(physicalLeases);
        assertThat(regionFenceImage(session)).isEqualTo(regionFences);
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
      assertThatThrownBy(() ->
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
      assertThatThrownBy(() ->
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
      assertThatThrownBy(() ->
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
        assertThatThrownBy(() ->
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
      String beforeDenialDecision = session.capacityDecisionImage();
      byte[] candidate = session.gs.physicalSession();
      assertThatThrownBy(() ->
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
      assertThat(session.capacityDecisionImage()).isEqualTo(beforeDenialDecision);
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
      assertThatThrownBy(fault.bindOriginal()::run)
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Original NEW capacity execution is indeterminate");
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
        var original = session.capacityRequest;
        var otherAttempt =
            new TenantCapacityAdmission.Request(
                    original.identity(),
                    UUID.randomUUID(),
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
        assertThatThrownBy(() -> session.complete(otherAttempt))
            .isExactlyInstanceOf(IllegalStateException.class);
        assertThat(session.gs.finalReadCalls()).isOne();
        assertThat(session.genesis().confirmationReadbackSha256()).isNull();
        assertThat(session.gsReceiptImage()).isEqualTo(gsReceipt);
        var changedOriginal = session.retainControlledEvaluation(true, "0");
        assertThat(changedOriginal.admissionAttemptId()).isEqualTo(original.admissionAttemptId());
        assertThat(changedOriginal.mutationDigest()).isNotEqualTo(original.mutationDigest());
        assertThatThrownBy(() -> session.complete(changedOriginal))
            .isExactlyInstanceOf(IllegalStateException.class);
        assertThat(session.gs.finalReadCalls()).isOne();
        assertThat(session.genesis().confirmationReadbackSha256()).isNull();
        assertThat(session.gsReceiptImage()).isEqualTo(gsReceipt);
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
                      "SELECT status FROM game_session_canonical_binding_transition WHERE"
                          + " transition_id = ?",
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
      String beforeDecision = session.capacityDecisionImage();
      for (int attempt = 0; attempt < 2; attempt++) {
        CapacityRefusedException failure =
            assertThrows(
                CapacityRefusedException.class,
                () -> session.capacityClient.commit(denied, CLOCK.millis() + 5_000L));
        assertThat(failure.code()).isEqualTo(Failure.TENANT_BILLING_BLOCKED);
        assertThat(session.capacityImage() + session.durableImage()).isEqualTo(before);
        assertThat(session.capacityDecisionImage()).isEqualTo(beforeDecision);
        assertProvisionalUnchanged(session, candidate);
      }
    }
  }

  // The fresh owner path takes the account row lock after retained-decision lookup;
  // sequential replay never takes it. A second waiter may queue behind the first waiter.
  private static void awaitCompetingFreshAccountDecisions(DSLContext inspector, int blocker)
      throws InterruptedException {
    long expiry = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    do {
      long waiting =
          inspector
              .fetchSingle(
                  "WITH RECURSIVE blocked(pid) AS (SELECT CAST(? AS integer) UNION"
                      + " SELECT activity.pid FROM pg_stat_activity activity JOIN blocked parent"
                      + " ON parent.pid = ANY(pg_blocking_pids(activity.pid))"
                      + " WHERE activity.datname = current_database())"
                      + " SELECT count(*) FROM blocked WHERE pid <> ?",
                  blocker,
                  blocker)
              .get(0, Long.class);
      if (waiting >= 2) return;
      Thread.sleep(10);
    } while (System.nanoTime() < expiry);
    throw new AssertionError("Both fresh Account decisions did not wait on the held owner row");
  }

  @Test
  void zeroQuotaRetainsOnlyTheExactAccountDenialAndReplaysIt() throws Exception {
    try (Session session = session()) {
      session.provisionCapacityRefusalBudget();
      var request = session.retainControlledEvaluation(true, "0");
      assertThat(request.evidence().permitsCreation()).isTrue();
      assertThat(request.evidence().quota()).isZero();
      byte[] candidate = session.gs.physicalSession();
      assertThat(candidate).isNotNull();
      String before = session.capacityImage() + session.durableImage();
      UUID denialId;
      try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
        java.util.concurrent.Callable<CapacityRefusedException> deny =
            () ->
                assertThrows(
                    CapacityRefusedException.class,
                    () -> session.capacityClient.commit(request, CLOCK.millis() + 5_000L));
        java.util.concurrent.Future<CapacityRefusedException> first;
        java.util.concurrent.Future<CapacityRefusedException> second;
        var player = (TenantCapacityAdmission.PlayerContext) request.context();
        try (var holder = session.account.dataSource.getConnection()) {
          holder.setAutoCommit(false);
          var locked = DSL.using(holder, SQLDialect.POSTGRES);
          assertThat(
                  locked.fetchOne(
                      "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE",
                      player.accountId()))
              .isNotNull();
          int blocker = locked.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class);
          first = callers.submit(deny);
          second = callers.submit(deny);
          try {
            awaitCompetingFreshAccountDecisions(session.dsl, blocker);
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();
          } finally {
            holder.rollback();
          }
        }
        denialId = assertExactAccountQuotaDenial(session, request, first.get(6, TimeUnit.SECONDS));
        var repeated = second.get(6, TimeUnit.SECONDS);
        assertThat(repeated.code()).isEqualTo(Failure.CAPACITY_QUOTA_EXCEEDED);
        assertThat(repeated.durableDenialId()).isEqualTo(denialId);
      }
      String retainedDecision = session.capacityDecisionImage();
      assertThrows(
          net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository
              .CapacityException.class,
          () ->
              session.serializable.execute(status ->
                  new AccountCapacityRefusalBudgetRepository(session.dsl)
                      .requireReady(request.identity().tenantId(), 1, 1)));
      session.serializable.executeWithoutResult(status ->
          new AccountCapacityRefusalBudgetRepository(session.dsl)
              .provision(request.identity().tenantId(), 1));
      assertThat(session.capacityDecisionImage()).isEqualTo(retainedDecision);
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
      session.provisionCapacityRefusalBudget();
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
                  "SELECT * FROM game_session_capacity_evidence_charge WHERE"
                      + " admission_attempt_id=?",
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
      var settledLeases = session.gs.capacityLeaseOwnerImage();
      var settledWithoutLeaseValues = withoutLeaseValues(settledState);
      var regionFences = regionFenceImage(session);
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
      assertThat(withoutLeaseValues(replayState)).isEqualTo(settledWithoutLeaseValues);
      assertThat(session.gs.capacityLeaseOwnerImage()).isEqualTo(settledLeases);
      assertThat(regionFenceImage(session)).isEqualTo(regionFences);
      assertThat(session.gs.finalReadCalls()).isZero();
      assertThat(session.genesis().tokenHash()).isEqualTo(credential.tokenSha256());
      assertThat(session.genesis().confirmedAdmissionAtMillis()).isNull();
      assertProvisionalUnchanged(session, candidate);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void localSettlementRollbackPreservesAccountRefusalAndAllowsExactRetry(boolean openExpired)
      throws Exception {
    try (Session session = session(CLOCK, 1, 2);
        OriginalNewCredential credential = session.issue()) {
      session.provisionCapacityRefusalBudget();
      var request = session.retainControlledEvaluation(true, "0");
      byte[] candidate = session.gs.physicalSession();
      var sql = session.gs.dsl();
      sql.execute("CREATE SEQUENCE controlled_settlement_rollback_attempt");
      sql.execute(
          "CREATE FUNCTION controlled_settlement_rollback() RETURNS trigger LANGUAGE plpgsql"
              + " AS $$ BEGIN PERFORM nextval('controlled_settlement_rollback_attempt');"
              + " RAISE EXCEPTION 'controlled settlement rollback'; END; $$");
      sql.execute(
          "CREATE TRIGGER controlled_settlement_rollback BEFORE UPDATE"
              + " ON game_session_capacity_evidence_charge FOR EACH ROW"
              + " WHEN (OLD.account_denial_id IS NULL AND NEW.account_denial_id IS NOT NULL)"
              + " EXECUTE FUNCTION controlled_settlement_rollback()");
      UUID denialId;
      try {
        var refusal = assertThrows(CapacityRefusedException.class, () -> session.complete(request));
        denialId = assertExactAccountQuotaDenial(session, request, refusal);
        assertThat(
                sql.fetchSingle("SELECT is_called FROM controlled_settlement_rollback_attempt")
                    .get(0, Boolean.class))
            .isTrue();
        assertThat(
                sql.fetchSingle(
                        "SELECT account_denial_id FROM game_session_capacity_evidence_charge WHERE"
                            + " admission_attempt_id=?",
                        request.admissionAttemptId())
                    .get(0, UUID.class))
            .isNull();
        assertThat(
                sql.fetchSingle("SELECT count(*) FROM game_session_original_new_capacity_execution")
                    .get(0, Long.class))
            .isZero();
        assertProvisionalUnchanged(session, candidate);
      } finally {
        sql.execute(
            "DROP TRIGGER controlled_settlement_rollback ON game_session_capacity_evidence_charge");
        sql.execute("DROP FUNCTION controlled_settlement_rollback()");
        sql.execute("DROP SEQUENCE controlled_settlement_rollback_attempt");
      }
      var beforeRetry = session.capacityDecisionImage();
      if (openExpired) {
        assertThat(
                assertThrows(
                    IllegalStateException.class,
                    () ->
                        session.gs.completeAfterInitialOpenWindow(
                            session.client,
                            session.jwks,
                            session.jwt,
                            session.capacityClient,
                            request)))
            .isNotInstanceOf(CapacityRefusedException.class);
      } else {
        var replay = assertThrows(CapacityRefusedException.class, () -> session.complete(request));
        assertThat(replay.durableDenialId()).isEqualTo(denialId);
      }
      assertThat(session.capacityDecisionImage()).isEqualTo(beforeRetry);
      assertThat(
              sql.fetchSingle(
                      "SELECT account_denial_id FROM game_session_capacity_evidence_charge WHERE"
                          + " admission_attempt_id=?",
                      request.admissionAttemptId())
                  .get(0, UUID.class))
          .isEqualTo(denialId);
      assertProvisionalUnchanged(session, candidate);
      assertThat(session.genesis().tokenHash()).isEqualTo(credential.tokenSha256());
    }
  }

  @Test
  void lostCommittedQuotaResponseKeepsReserveUntilExactRetry() throws Exception {
    try (Session session = session(CLOCK, 1, 2);
        OriginalNewCredential credential = session.issue()) {
      session.provisionCapacityRefusalBudget();
      var request = session.retainControlledEvaluation(true, "0");
      var fault = new DropFirstQuotaResponse(request);
      // Preserve the real TLS server and channel setup; inject caller-side transport loss only.
      session.startCapacityClient(fault.channels());
      byte[] candidate = session.gs.physicalSession();
      String capacityBefore = session.capacityImage();
      String transitionsBefore = session.gsReceiptImage();

      var uncertain = assertThrows(IllegalStateException.class, () -> session.complete(request));
      assertThat(uncertain).isNotInstanceOf(CapacityRefusedException.class);
      assertThat(uncertain.getCause())
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              status -> {
                assertThat(status.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
                assertThat(status.getTrailers()).isNotNull();
                assertThat(status.getTrailers().keys()).isEmpty();
              });
      assertThat(fault.successfullyDropped.get()).isTrue();
      assertThat(fault.calls.get()).isEqualTo(1);
      UUID committedId = fault.observedDenial.get();
      assertThat(committedId).isNotNull();
      String decision = session.capacityDecisionImage();
      var accountDenial =
          session.dsl.fetchSingle(
              "SELECT * FROM account_capacity_origin_decisions WHERE admission_attempt_id=?",
              request.admissionAttemptId());
      assertThat(accountDenial.get("decision_id", UUID.class)).isEqualTo(committedId);
      assertThat(accountDenial.get("decision", String.class)).isEqualTo("NO_ACCEPT");
      assertThat(accountDenial.get("refusal_code", String.class))
          .isEqualTo("CAPACITY_QUOTA_EXCEEDED");
      assertThat(accountDenial.get("canonical_payload", byte[].class))
          .containsExactly(CapacityAdmissionDigest.preimage(request));
      var active =
          session
              .gs
              .dsl()
              .fetchSingle(
                  "SELECT account_denial_id, tenant_id, admission_id, mutation_digest"
                      + " FROM game_session_capacity_evidence_charge WHERE admission_attempt_id=?",
                  request.admissionAttemptId());
      assertThat(active.get("tenant_id", UUID.class)).isEqualTo(request.identity().tenantId());
      assertThat(active.get("admission_id", UUID.class))
          .isEqualTo(request.identity().admissionId());
      assertThat(active.get("account_denial_id", UUID.class)).isNull();
      assertThat(active.get("mutation_digest", String.class)).isEqualTo(request.mutationDigest());
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle("SELECT count(*) FROM game_session_original_new_capacity_execution")
                  .get(0, Long.class))
          .isZero();
      assertThat(session.capacityImage()).isEqualTo(capacityBefore);
      assertThat(session.gsReceiptImage()).isEqualTo(transitionsBefore);
      assertProvisionalUnchanged(session, candidate);

      var exactRetry =
          assertThrows(CapacityRefusedException.class, () -> session.complete(request));
      assertThat(assertExactAccountQuotaDenial(session, request, exactRetry))
          .isEqualTo(committedId);
      assertThat(fault.calls.get()).isEqualTo(1);
      assertThat(fault.retainedReads.get()).isEqualTo(1);
      assertThat(session.capacityDecisionImage()).isEqualTo(decision);
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT account_denial_id FROM game_session_capacity_evidence_charge"
                          + " WHERE admission_attempt_id=?",
                      request.admissionAttemptId())
                  .get(0, UUID.class))
          .isEqualTo(committedId);
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_capacity_evidence_charge"
                          + " WHERE tenant_id=? AND account_denial_id IS NULL",
                      request.identity().tenantId())
                  .get(0, Long.class))
          .isZero();

      session.stopCapacityServer();
      var replay = assertThrows(CapacityRefusedException.class, () -> session.complete(request));
      assertThat(replay.durableDenialId()).isEqualTo(committedId);
      assertThat(fault.calls.get()).isEqualTo(1);
      assertThat(fault.retainedReads.get()).isEqualTo(1);
      assertThat(session.capacityDecisionImage()).isEqualTo(decision);
      assertThat(session.capacityImage()).isEqualTo(capacityBefore);
      assertThat(session.gsReceiptImage()).isEqualTo(transitionsBefore);
      assertThat(session.genesis().tokenHash()).isEqualTo(credential.tokenSha256());
      assertThat(session.genesis().confirmedAdmissionAtMillis()).isNull();
      assertProvisionalUnchanged(session, candidate);
    }
  }

  @Test
  void accountGeneratedUnavailableKeepsExactGameSessionReserveUnsettled() throws Exception {
    try (Session session = session();
        OriginalNewCredential credential = session.issue();
        var worker = Executors.newSingleThreadExecutor();
        var lock = session.account.dataSource.getConnection()) {
      var request = session.capacityRequest;
      assertThat(request.evidence().permitsCreation()).isTrue();
      byte[] candidate = session.gs.physicalSession();
      String accountBefore = session.capacityImage() + session.capacityDecisionImage();
      String transitionsBefore = session.gsReceiptImage();
      String chargeLookup =
          "SELECT tenant_id, admission_id, mutation_digest, account_denial_id"
              + " FROM game_session_capacity_evidence_charge WHERE admission_attempt_id=?";
      assertThat(session.gs.dsl().fetchOne(chargeLookup, request.admissionAttemptId())).isNull();

      int blocker = lockGameSessionUsage(lock, request.identity().tenantId());
      // Start the worker thread before narrowing the window for reaching the actual SQL lock.
      worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
      waitForDatabaseTime(session.dsl, request.evidence().expiresAt().minusSeconds(3));
      var result = worker.submit(() -> session.complete(request));
      try {
        requireUsageWaitBeforeExpiry(session.dsl, blocker, request.evidence().expiresAt());
        // The actual Account owner is waiting on the held row, after Game Session precharges.
        assertThat(session.gs.dsl().fetchOne(chargeLookup, request.admissionAttemptId()))
            .isNotNull();
        waitForDatabaseTime(session.dsl, request.evidence().expiresAt());
      } finally {
        lock.rollback();
      }
      var uncertain =
          assertThrows(
                  java.util.concurrent.ExecutionException.class,
                  () -> result.get(3, TimeUnit.SECONDS))
              .getCause();
      assertThat(uncertain)
          .isInstanceOf(IllegalStateException.class)
          .isNotInstanceOf(CapacityRefusedException.class);
      assertThat(uncertain.getCause()).isInstanceOf(StatusRuntimeException.class);
      assertCapacityFailure(
          (StatusRuntimeException) uncertain.getCause(),
          Status.Code.UNAVAILABLE,
          "CAPACITY_ADMISSION_STALE");

      var retained = session.gs.dsl().fetchSingle(chargeLookup, request.admissionAttemptId());
      assertThat(retained.get("tenant_id", UUID.class)).isEqualTo(request.identity().tenantId());
      assertThat(retained.get("admission_id", UUID.class))
          .isEqualTo(request.identity().admissionId());
      assertThat(retained.get("mutation_digest", String.class)).isEqualTo(request.mutationDigest());
      assertThat(retained.get("account_denial_id", UUID.class)).isNull();
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle("SELECT count(*) FROM game_session_original_new_capacity_execution")
                  .get(0, Long.class))
          .isZero();
      assertThat(session.capacityImage() + session.capacityDecisionImage())
          .isEqualTo(accountBefore);
      assertThat(session.gsReceiptImage()).isEqualTo(transitionsBefore);
      assertThat(session.genesis().tokenHash()).isEqualTo(credential.tokenSha256());
      assertProvisionalUnchanged(session, candidate);
    }
  }

  /** Changes one completed, exact authenticated response into plain caller-facing UNAVAILABLE. */
  private static final class DropFirstQuotaResponse implements ClientInterceptor {
    private final TenantCapacityAdmission.Request exact;
    final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger retainedReads =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicReference<UUID> observedDenial =
        new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicBoolean successfullyDropped =
        new java.util.concurrent.atomic.AtomicBoolean();

    DropFirstQuotaResponse(TenantCapacityAdmission.Request exact) {
      this.exact = exact;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
      var delegate = next.newCall(method, options);
      if (net.firedevops.firemud.account.v1.AccountServiceGrpc
          .getReadTenantCapacityAdmissionOutcomeMethod()
          .getFullMethodName()
          .equals(method.getFullMethodName())) {
        retainedReads.incrementAndGet();
      }
      if (!net.firedevops.firemud.account.v1.AccountServiceGrpc
              .getCommitTenantCapacityAdmissionMethod()
              .getFullMethodName()
              .equals(method.getFullMethodName())
          || calls.incrementAndGet() != 1) {
        return delegate;
      }
      return new SimpleForwardingClientCall<ReqT, RespT>(delegate) {
        @Override
        public void start(Listener<RespT> listener, Metadata headers) {
          super.start(
              new SimpleForwardingClientCallListener<RespT>(listener) {
                private UUID withheld;

                @Override
                public void onMessage(RespT message) {
                  if (message instanceof CommitTenantCapacityAdmissionResponse response) {
                    var refusal =
                        TenantCapacityAdmissionGrpcCodec.readRefusalDetails(exact, response);
                    if (refusal.isPresent()
                        && refusal.orElseThrow().code() == Failure.CAPACITY_QUOTA_EXCEEDED
                        && refusal.orElseThrow().durableDenialId() != null) {
                      withheld = refusal.orElseThrow().durableDenialId();
                      return;
                    }
                  }
                  super.onMessage(message);
                }

                @Override
                public void onClose(Status status, Metadata trailers) {
                  if (status.isOk() && withheld != null) {
                    observedDenial.set(withheld);
                    successfullyDropped.set(true);
                    super.onClose(Status.UNAVAILABLE, new Metadata());
                    return;
                  }
                  super.onClose(status, trailers);
                }
              },
              headers);
        }
      };
    }

    GrpcChannelFactory channels() {
      return new GrpcChannelFactory() {
        @Override
        public ManagedChannel buildChannelWithoutRetries(
            String target,
            int defaultPort,
            CommonGrpcClientProperties properties,
            boolean keepAlive)
            throws javax.net.ssl.SSLException {
          var channel =
              super.buildChannelWithoutRetries(target, defaultPort, properties, keepAlive);
          var intercepted = ClientInterceptors.intercept(channel, DropFirstQuotaResponse.this);
          return new ManagedChannel() {
            @Override
            public ManagedChannel shutdown() {
              channel.shutdown();
              return this;
            }

            @Override
            public ManagedChannel shutdownNow() {
              channel.shutdownNow();
              return this;
            }

            @Override
            public boolean isShutdown() {
              return channel.isShutdown();
            }

            @Override
            public boolean isTerminated() {
              return channel.isTerminated();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
              return channel.awaitTermination(timeout, unit);
            }

            @Override
            public String authority() {
              return intercepted.authority();
            }

            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions options) {
              return intercepted.newCall(method, options);
            }
          };
        }
      };
    }
  }

  @Test
  void authenticatedQuotaDenialFreesTheLastActiveSlotForADistinctAdmission() throws Exception {
    try (Session session = new Session(CLOCK, 1, 2, true)) {
      session.provisionCapacityRefusalBudget();
      // Keep the lease's current DEMO authority eligible. Control only the retained first
      // capacity evaluation's quota; Account still decides and retains the actual refusal.
      var first = session.retainControlledEvaluation(true, "0");
      assertThat(first.evidence().quota()).isZero();
      assertThat(first.evidence().permitsCreation()).isTrue();
      byte[] firstCandidate = session.gs.physicalSession();
      String firstCapacity = session.capacityImage();
      var denial = assertThrows(CapacityRefusedException.class, () -> session.complete(first));
      UUID denialId = assertExactAccountQuotaDenial(session, first, denial);
      String decisionReadback =
          "SELECT to_jsonb(d)::text FROM account_capacity_origin_decisions d WHERE decision_id=?";
      String retainedDecision =
          session.dsl.fetchSingle(decisionReadback, denialId).get(0, String.class);
      assertThat(session.capacityImage()).isEqualTo(firstCapacity);
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle("SELECT count(*) FROM game_session_original_new_capacity_execution")
                  .get(0, Long.class))
          .isZero();
      var firstCharge =
          session
              .gs
              .dsl()
              .fetchSingle(
                  "SELECT * FROM game_session_capacity_evidence_charge WHERE"
                      + " admission_attempt_id=?",
                  first.admissionAttemptId());
      assertThat(firstCharge.get("account_denial_id", UUID.class)).isEqualTo(denialId);
      assertProvisionalUnchanged(session, firstCandidate);

      session.stopCapacityServer();
      var replay = assertThrows(CapacityRefusedException.class, () -> session.complete(first));
      assertThat(replay.code()).isEqualTo(Failure.CAPACITY_QUOTA_EXCEEDED);
      assertThat(replay.durableDenialId()).isEqualTo(denialId);
      assertThat(session.dsl.fetchSingle(decisionReadback, denialId).get(0, String.class))
          .isEqualTo(retainedDecision);

      session.startCapacityServer();
      var second = session.gs.prepareSecondAdmission();
      assertThatThrownBy(session.gs::prepareSecondAdmission)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Second admission has already been prepared");
      var secondLease =
          session
              .client
              .acquire(
                  second.requestId(),
                  session.jwt,
                  second.candidate(),
                  session.gs.target(),
                  second.generation())
              .originalLeaseEvidence();
      second.install(secondLease);
      var scope = session.account.capacityScope(session.gs.target());
      var secondRequest =
          session.evaluateCapacity(
              scope,
              session.targetSelector(second.requestId(), scope),
              second.candidate(),
              second.decisionId());
      session.finalizeLease(secondLease, second.decisionId());
      assertThat(secondRequest.evidence().quota())
          .isPositive()
          .isEqualTo(session.capacityRequest.evidence().quota());
      assertThat(secondRequest.identity().tenantId()).isEqualTo(first.identity().tenantId());
      assertThat(secondRequest.identity().requestId()).isNotEqualTo(first.identity().requestId());
      assertThat(secondRequest.identity().admissionId())
          .isNotEqualTo(first.identity().admissionId());
      assertThat(secondRequest.admissionAttemptId()).isNotEqualTo(first.admissionAttemptId());

      var receipt =
          session.gs.complete(
              second,
              session.client,
              session.jwks,
              session.jwt,
              CLOCK.millis() + 10_000L,
              session.capacityClient,
              secondRequest);
      assertThat(receipt.transitionId()).isEqualTo(second.decisionId());
      assertPhysicalSession(second.physicalSession(), receipt);
      assertThat(session.gs.finalReadCalls()).isOne();
      assertProvisionalUnchanged(session, firstCandidate);

      session.stopCapacityServer();
      var replayAfterSlotReuse =
          assertThrows(CapacityRefusedException.class, () -> session.complete(first));
      assertThat(replayAfterSlotReuse.code()).isEqualTo(Failure.CAPACITY_QUOTA_EXCEEDED);
      assertThat(replayAfterSlotReuse.durableDenialId()).isEqualTo(denialId);

      var charges =
          session
              .gs
              .dsl()
              .fetch(
                  "SELECT * FROM game_session_capacity_evidence_charge WHERE tenant_id=?",
                  first.identity().tenantId());
      assertThat(charges).hasSize(2).contains(firstCharge);
      var secondCharge =
          charges.stream()
              .filter(charge ->
                  secondRequest
                      .admissionAttemptId()
                      .equals(charge.get("admission_attempt_id", UUID.class)))
              .findFirst()
              .orElseThrow();
      assertThat(secondCharge.get("account_denial_id", UUID.class)).isNull();
      var execution =
          session
              .gs
              .dsl()
              .fetchSingle("SELECT * FROM game_session_original_new_capacity_execution");
      var reservation =
          session.dsl.fetchSingle("SELECT * FROM account_tenant_capacity_reservations");
      assertThat(execution.get("admission_attempt_id", UUID.class))
          .isEqualTo(secondRequest.admissionAttemptId());
      assertThat(execution.get("status", String.class)).isEqualTo("COMMITTED");
      assertThat(reservation.get("admission_attempt_id", UUID.class))
          .isEqualTo(secondRequest.admissionAttemptId());
      assertThat(execution.get("reservation_id", UUID.class))
          .isEqualTo(reservation.get("reservation_id", UUID.class));
      assertThat(session.dsl.fetchSingle(decisionReadback, denialId).get(0, String.class))
          .isEqualTo(retainedDecision);
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
      Instant callerNotBefore =
          Instant.ofEpochSecond(
              session.account.committedCandidate.identity().notBeforeEpochSecond());
      Clock behindEvidence = Clock.fixed(evidence.evaluatedAt().minusNanos(1), ZoneOffset.UTC);
      assertThat(behindEvidence.instant())
          .isAfterOrEqualTo(callerNotBefore)
          .isBefore(evidence.evaluatedAt());
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
  void applicationAuthorityCutoffAfterReservationStagingRollsBackWhileDatabaseEvidenceIsFresh()
      throws Exception {
    try (Session session = session()) {
      var request = session.retainControlledEvaluation(true, null);
      var evidence = request.evidence();
      var caller = session.account.committedCandidate.identity();
      Instant stillCurrent = evidence.evaluatedAt().minusNanos(1);
      assertThat(stillCurrent)
          .isAfterOrEqualTo(Instant.ofEpochSecond(caller.notBeforeEpochSecond()))
          .isBefore(evidence.evaluatedAt());
      Instant callerCutoff = Instant.ofEpochSecond(caller.expiresAtEpochSecond());
      var parsed =
          RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(evidence.canonicalBytes());
      Instant scopeCutoff =
          session.account.retainedCanonicalConnectScopeCutoff(
              parsed.verifiedConnectScopeTokenHash());
      Instant appCutoff = callerCutoff.isBefore(scopeCutoff) ? callerCutoff : scopeCutoff;
      assertThat(stillCurrent).isBefore(appCutoff);
      assertThat(AccountTenantCapacityRepository.databaseNow(session.dsl))
          .isBefore(evidence.expiresAt());
      String before = session.capacityImage() + session.capacityDecisionImage();
      var staged = new java.util.concurrent.atomic.AtomicBoolean();
      Clock appClock = org.mockito.Mockito.mock(Clock.class);
      org.mockito.Mockito.when(appClock.instant())
          .thenAnswer(ignored -> {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) return stillCurrent;
            // session.dsl is backed by TransactionAwareDataSourceProxy over the very same data
            // source used by capacityOwner; only the enlisted thread can see the staged row.
            boolean reservationStaged =
                session.dsl.fetchExists(
                    DSL.selectOne()
                        .from(DSL.table("account_tenant_capacity_reservations"))
                        .where(
                            DSL.field("request_id", UUID.class)
                                .eq(request.identity().requestId())));
            if (!reservationStaged) return stillCurrent;
            assertThat(AccountTenantCapacityRepository.databaseNow(session.dsl))
                .isBefore(evidence.expiresAt());
            staged.set(true);
            return appCutoff;
          });

      assertThatThrownBy(() -> session.account.capacityOwner(appClock).commit(request, GS))
          .isInstanceOfSatisfying(
              AccountTenantCapacityRepository.CapacityException.class,
              failure -> assertThat(failure.code()).isEqualTo(Failure.ENTITLEMENT_UNAVAILABLE));
      assertThat(staged).isTrue();
      assertThat(session.capacityImage() + session.capacityDecisionImage()).isEqualTo(before);

      var stillEligible = session.account.capacityOwner(Clock.fixed(stillCurrent, ZoneOffset.UTC));
      assertThat(stillEligible.commit(request, GS).state())
          .isEqualTo(TenantCapacityAdmission.State.RESERVED);
    }
  }

  @Test
  void actualUsageLockWaitAcrossExpiryCannotCreateAReservationEvenWithAFrozenOwnerClock()
      throws Exception {
    try (Session session = session();
        var worker = Executors.newSingleThreadExecutor();
        var lock = session.account.dataSource.getConnection()) {
      // Exercise the same owner, transaction, and SQL path on the worker before timing the
      // actual lock wait. Non-creating evidence makes the warm-up refuse without a reservation.
      var warmup = session.retainControlledEvaluation(false, null);
      var warmupOwner = session.account.capacityOwner(CLOCK);
      assertThatThrownBy(
              () -> worker.submit(() -> warmupOwner.commit(warmup, GS)).get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(AccountTenantCapacityRepository.CapacityException.class)
          .hasStackTraceContaining("TENANT_BILLING_BLOCKED");
      int blocker = lockGameSessionUsage(lock, warmup.identity().tenantId());
      var request = session.retainControlledEvaluation(true, null);
      String before = session.capacityImage();
      var owner =
          session.account.capacityOwner(
              Clock.fixed(request.evidence().evaluatedAt(), ZoneOffset.UTC));
      // The owner has a five-second transaction budget. Start within it, with enough
      // time for the physical row wait to register before the retained evidence expires.
      var expiry = request.evidence().expiresAt();
      waitForDatabaseTime(session.dsl, expiry.minusMillis(4_500));
      Instant beforeSubmit = AccountTenantCapacityRepository.databaseNow(session.dsl);
      assertThat(beforeSubmit).as("database time before submitting the owner").isBefore(expiry);
      var result = worker.submit(() -> owner.commit(request, GS));
      try {
        requireUsageWaitBeforeExpiry(session.dsl, blocker, request.evidence().expiresAt());
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

  private static int lockGameSessionUsage(java.sql.Connection lock, UUID tenantId)
      throws java.sql.SQLException {
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
      statement.setObject(1, tenantId);
      try (var usage = statement.executeQuery()) {
        assertThat(usage.next()).isTrue();
      }
    }
    return blocker;
  }

  private static void requireUsageWaitBeforeExpiry(DSLContext dsl, int blocker, Instant expiry)
      throws InterruptedException {
    long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    java.time.OffsetDateTime waitStartedAt;
    do {
      waitStartedAt =
          dsl.fetchSingle(
                  "SELECT min(waiter.waitstart) FROM pg_locks waiter "
                      + "JOIN pg_stat_activity activity ON activity.pid = waiter.pid "
                      + "WHERE NOT waiter.granted AND ? = ANY(pg_blocking_pids(waiter.pid)) "
                      + "AND activity.query LIKE 'SELECT * FROM account_tenant_capacity_usage%'",
                  blocker)
              .get(0, java.time.OffsetDateTime.class);
      if (waitStartedAt == null) Thread.sleep(10);
    } while (waitStartedAt == null && System.nanoTime() < waitUntil);
    assertThat(waitStartedAt).as("the actual owner must wait on the held usage row").isNotNull();
    assertThat(waitStartedAt.toInstant())
        .as("the database recorded the usage lock wait starting before expiry")
        .isBefore(expiry);
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
    String beforeDecision = session.capacityDecisionImage();
    assertCapacityFailure(
        assertThrows(
            StatusRuntimeException.class,
            () -> session.capacityClient.commit(request, CLOCK.millis() + 5_000L)),
        status,
        code);
    assertThat(session.capacityImage() + session.durableImage()).isEqualTo(before);
    assertThat(session.capacityDecisionImage()).isEqualTo(beforeDecision);
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
        session.serializable.executeWithoutResult(status -> {
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
                    "SELECT status FROM game_session_canonical_binding_transition WHERE"
                        + " transition_id = ?",
                    session.gs.decisionId())
                .get(0, String.class))
        .isEqualTo("PROVISIONAL");
  }

  private static java.util.Map<String, String> withoutLeaseValues(
      java.util.Map<String, String> image) {
    var result = new java.util.TreeMap<>(image);
    result.keySet().removeIf(key -> key.startsWith("redis:tick-executor-lease:"));
    return result;
  }

  private static java.util.List<Long> regionFenceImage(Session session) {
    return session
        .gs
        .dsl()
        .fetch(
            "SELECT executor_fence FROM game_session_region_runtime_status"
                + " ORDER BY tenant_id, game_instance_id, region_id")
        .getValues("executor_fence", Long.class);
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
    private AccountTenantCapacityClient capacityClient;
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

    private Session(Clock capacityClock, int maximumActiveRecords, int maximumRetainedRecords)
        throws Exception {
      this(capacityClock, maximumActiveRecords, maximumRetainedRecords, false);
    }

    private Session(
        Clock capacityClock,
        int maximumActiveRecords,
        int maximumRetainedRecords,
        boolean allowSecondAdmission)
        throws Exception {
      try {
        accountFixtures.temporaryDirectory = temporaryDirectory;
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
                      account, allowSecondAdmission ? BigInteger.TWO : BigInteger.ONE),
                  validated,
                  Files.createDirectory(
                      temporaryDirectory.resolve("actual-final-sources-" + UUID.randomUUID())));
        }
        pkiDirectory =
            Files.createDirectory(
                temporaryDirectory.resolve("actual-final-pki-" + UUID.randomUUID()));
        pki =
            LoopbackMtlsTestPki.createWithDirectSignedLeafRead(
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
        startCapacityServer();
        // All containers, keys, source servers, and INITIAL setup precede the <=15-second lease.
        lease =
            client
                .acquire(requestId, jwt, gs.candidate(), gs.target(), gs.generation())
                .originalLeaseEvidence();
        gs.installActualCandidate(lease, maximumActiveRecords, maximumRetainedRecords);
        var scope = account.capacityScope(gs.target());
        targetSelector = targetSelector(requestId, scope);
        capacityRequest = evaluateCapacity(scope, targetSelector, gs.candidate(), gs.decisionId());
        finalizeLease(lease, gs.decisionId());
      } catch (Exception | Error failure) {
        try {
          close();
        } catch (Exception cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    private void startCapacityServer() throws Exception {
      capacityServer =
          new net.firedevops.firemud.accountservice.service.impl.AccountTenantCapacityGrpcService(
                  capacityOwner, "test")
              .createUnregisteredServer(
                  "127.0.0.1:0",
                  pki.keyManagerFactory(pki.identity("account")),
                  pki.trustManagerFactory());
      capacityServer.start();
      startCapacityClient(new GrpcChannelFactory());
    }

    private void startCapacityClient(GrpcChannelFactory channels) throws Exception {
      if (capacityClient != null) capacityClient.close();
      var capacityEndpoints = new ServiceEndpointsProperties();
      capacityEndpoints.setAccountService("localhost:" + capacityServer.getPort());
      capacityClient =
          new AccountTenantCapacityClient(
              capacityEndpoints,
              pki.clientProperties(pkiDirectory, pki.identity("gs-client")),
              channels,
              "test",
              CLOCK);
      capacityClient.init();
    }

    private CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector targetSelector(
        UUID requestId, CanonicalJoinScopeV2 scope) {
      return new CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector(
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
    }

    private TenantCapacityAdmission.Request evaluateCapacity(
        CanonicalJoinScopeV2 scope,
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector selector,
        Candidate candidate,
        UUID decisionId) {
      var evidence =
          account
              .playerEntitlementOwner(reverse)
              .evaluate(
                  new net.firedevops.firemud.accountservice.service
                      .AccountPlayerAdmissionEntitlementOwner.Request(
                      selector, scope.connectScopeId(), GS),
                  jwt);
      return new TenantCapacityAdmission.Request(
              new TenantCapacityAdmission.Identity(
                  scope.tenantId(), selector.readRequestId(), decisionId),
              UUID.randomUUID(),
              1,
              "test",
              new TenantCapacityAdmission.PlayerContext(
                  scope.accountId(),
                  scope.tenantId(),
                  scope.playableStateNamespaceId(),
                  scope.gameInstanceId(),
                  candidate.characterId(),
                  candidate.sessionId(),
                  scope.realmId(),
                  selector.readRequestId(),
                  "SHARED"),
              TenantCapacityAdmission.Operation.CAPACITY_CREATING,
              new TenantCapacityAdmission.Shape(0, 0, 0),
              new TenantCapacityAdmission.Shape(1, 0, 0),
              TenantCapacityAdmission.Evidence.player(evidence),
              1L,
              null)
          .withDigest();
    }

    private void finalizeLease(
        AccountGameplayAdmissionLeaseEvidence originalLease, UUID decisionId) {
      var finalized =
          client.finalizeLease(
              jwt, originalLease.canonicalJson().getBytes(StandardCharsets.UTF_8), decisionId);
      assertThat(finalized.state())
          .isEqualTo(
              net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec
                  .State.COMMITTED);
      assertThat(finalized.originalLeaseEvidence().canonicalJson())
          .isEqualTo(originalLease.canonicalJson());
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

    private void provisionCapacityRefusalBudget() {
      serializable.executeWithoutResult(status -> {
        var budgets = new AccountCapacityRefusalBudgetRepository(dsl);
        budgets.provision(capacityRequest.identity().tenantId(), 1);
        assertThat(budgets.requireReady(capacityRequest.identity().tenantId(), 1, 1)).isEqualTo(1);
      });
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
      return serializable.execute(status ->
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
                        "SELECT (to_jsonb(t) || jsonb_build_object('_original_xmin',"
                            + " t.xmin::text))::text AS image FROM "
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
                  "SELECT to_jsonb(t)::text AS image FROM"
                      + " account_original_new_bound_gameplay_issuances t ORDER BY 1")
              .getValues("image", String.class);
    }

    private String gsReceiptImage() {
      return gs.dsl()
          .fetch(
              "SELECT to_jsonb(t)::text AS image FROM game_session_canonical_binding_transition t"
                  + " ORDER BY 1")
          .getValues("image", String.class)
          .toString();
    }

    private void installConfirmationFault() {
      dsl.execute(
          "CREATE FUNCTION "
              + FAULT_FUNCTION
              + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled actual"
              + " final confirmation SQL loss'; RETURN NEW; END; $$");
      dsl.execute(
          "CREATE TRIGGER "
              + FAULT_TRIGGER
              + " BEFORE UPDATE ON account_original_new_bound_gameplay_issuances FOR EACH ROW WHEN"
              + " (OLD.confirmed_admission_at_ms IS NULL AND NEW.confirmed_admission_at_ms IS NOT"
              + " NULL) EXECUTE FUNCTION "
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
      closeFixtureResources(
          () -> {
            if (capacityClient != null) capacityClient.close();
          },
          this::stopCapacityServer,
          () -> {
            if (client != null) client.close();
          },
          () -> {
            if (accountServer != null) {
              accountServer.shutdownNow();
              if (!accountServer.awaitTermination(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Account teardown timed out");
            }
          },
          () -> {
            if (finalRead != null) finalRead.close();
          },
          () -> {
            if (reverse != null) reverse.close();
          },
          () -> {
            if (gs != null) gs.close();
          },
          () -> redis.close());
    }
  }

  private static void closeFixtureResources(AutoCloseable... resources) throws Exception {
    Throwable firstFailure = null;
    for (AutoCloseable resource : resources) {
      try {
        resource.close();
      } catch (Exception | Error failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        } else if (failure != firstFailure) {
          firstFailure.addSuppressed(failure);
        }
      }
    }
    if (firstFailure instanceof Exception exception) throw exception;
    if (firstFailure instanceof Error error) throw error;
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
