package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient.ClientUnavailableException;
import net.firedevops.firemud.accountservice.dto.BootstrapWorldDto;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceKind;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner.Evaluation;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapWorldsOwner;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.VisiblePublicProductionTargets;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled owner composition; no genuine JWT custody, Game Session transport, or PostgreSQL. */
class AccountPublicBootstrapWorldsOwnerTest {
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
  private static final UUID ACCOUNT_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_A = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID TENANT_B = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final long ACCOUNT_ID = 41L;
  private static final String TOKEN = "controlled-bootstrap-token";

  @AfterEach
  void clearThreadContexts() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void returnsSortedPublicWorldsAfterCompleteStableReorderedSourceAndFinalCallerCheck() {
    Fixture f = new Fixture();
    Target zulu = target(TENANT_A, "tenant-a", "zulu", "Zulu", 1);
    Target alpha = target(TENANT_B, "tenant-b", "alpha", "Alpha", 1);
    f.stubTargetReads(List.of(zulu, alpha), List.of(alpha, zulu));
    when(f.entitlements.evaluate(TENANT_A))
        .thenReturn(evaluation(TENANT_A, true, NOW.plusSeconds(30)));
    when(f.entitlements.evaluate(TENANT_B))
        .thenReturn(evaluation(TENANT_B, true, NOW.plusSeconds(30)));

    List<BootstrapWorldDto> worlds = f.owner.listWorlds(TOKEN);

    assertThat(worlds)
        .containsExactly(
            new BootstrapWorldDto("tenant-b", "alpha", "Alpha"),
            new BootstrapWorldDto("tenant-a", "zulu", "Zulu"));
    verify(f.targets, times(2)).listVisiblePublicProductionAdmissionTargets(any());
    verify(f.entitlements).evaluate(TENANT_A);
    verify(f.entitlements).evaluate(TENANT_B);
    verify(f.accounts, times(2)).findByAccountUuid(ACCOUNT_UUID);
    verify(f.sessions, times(2)).isAccountSessionActive(ACCOUNT_ID, TOKEN);
  }

  @Test
  void mixedPublicCreationPoliciesBothParticipateInCompleteWorldDiscovery() {
    Fixture f = new Fixture();
    var seeded = target(TENANT_A, "tenant-a", "seeded", "Seeded", 1);
    var created = target(TENANT_B, "tenant-b", "created", "Created", 1, "PLAYER_CREATED");
    f.stubTargetReads(List.of(seeded, created), List.of(created, seeded));
    when(f.entitlements.evaluate(TENANT_A))
        .thenReturn(evaluation(TENANT_A, true, NOW.plusSeconds(30)));
    when(f.entitlements.evaluate(TENANT_B))
        .thenReturn(evaluation(TENANT_B, true, NOW.plusSeconds(30)));
    assertThat(f.owner.listWorlds(TOKEN))
        .containsExactly(
            new BootstrapWorldDto("tenant-b", "created", "Created"),
            new BootstrapWorldDto("tenant-a", "seeded", "Seeded"));
    verify(f.entitlements, times(1)).evaluate(TENANT_A);
    verify(f.entitlements, times(1)).evaluate(TENANT_B);
  }

  @Test
  void denialFiltersGameplayUnavailableTenantButDoesNotRequireJoinEligibility() {
    Fixture f = new Fixture();
    Target denied = target(TENANT_A, "tenant-a", "closed", "Closed", 1);
    f.stubTargetReads(List.of(denied), List.of(denied));
    when(f.entitlements.evaluate(TENANT_A))
        .thenReturn(
            new Evaluation(
                TENANT_A, SourceKind.NON_PAID_DEMO, false, true, 1, 1, NOW, NOW.plusSeconds(30)));

    assertThat(f.owner.listWorlds(TOKEN)).isEmpty();

    verify(f.targets, times(2)).listVisiblePublicProductionAdmissionTargets(any());
    verify(f.entitlements).evaluate(TENANT_A);
    verify(f.accounts, times(2)).findByAccountUuid(ACCOUNT_UUID);
  }

  @Test
  void changedCompleteTargetRefusesTheWholeList() {
    Fixture f = new Fixture();
    Target original = target(TENANT_A, "tenant-a", "world", "World", 1);
    Target changedPointer = target(TENANT_A, "tenant-a", "world", "World", 2);
    f.stubTargetReads(List.of(original), List.of(changedPointer));
    when(f.entitlements.evaluate(TENANT_A))
        .thenReturn(evaluation(TENANT_A, true, NOW.plusSeconds(30)));

    assertCode("ADMISSION_POINTER_UNAVAILABLE", () -> f.owner.listWorlds(TOKEN));

    verify(f.accounts).findByAccountUuid(ACCOUNT_UUID);
  }

  @Test
  void sharedWorldSlugAcrossTenantsReturnsBothQualifiedSelectors() {
    Fixture f = new Fixture();
    Target first = target(TENANT_A, "tenant-a", "same-slug", "First", 1);
    Target second = target(TENANT_B, "tenant-b", "same-slug", "Second", 1);
    f.stubTargetReads(List.of(first, second), List.of(second, first));
    when(f.entitlements.evaluate(TENANT_A))
        .thenReturn(evaluation(TENANT_A, true, NOW.plusSeconds(30)));
    when(f.entitlements.evaluate(TENANT_B))
        .thenReturn(evaluation(TENANT_B, true, NOW.plusSeconds(30)));

    assertThat(f.owner.listWorlds(TOKEN))
        .containsExactly(
            new BootstrapWorldDto("tenant-a", "same-slug", "First"),
            new BootstrapWorldDto("tenant-b", "same-slug", "Second"));
  }

  @Test
  void expiredEntitlementDuringFinalCollectionReadRefusesTheWholeList() {
    Fixture f = new Fixture();
    Target target = target(TENANT_A, "tenant-a", "world", "World", 1);
    when(f.targets.listVisiblePublicProductionAdmissionTargets(any()))
        .thenAnswer(
            invocation -> {
              f.readCount++;
              if (f.readCount == 1) return f.evidence(List.of(target));
              f.time.set(NOW.plusSeconds(15));
              return f.evidence(List.of(target));
            });
    when(f.entitlements.evaluate(TENANT_A))
        .thenReturn(evaluation(TENANT_A, true, NOW.plusSeconds(15)));

    assertCode("ENTITLEMENT_UNAVAILABLE", () -> f.owner.listWorlds(TOKEN));
  }

  @Test
  void changedCallerIdentityDuringSourceReadsRefusesTheWholeList() {
    Fixture f = new Fixture();
    Target target = target(TENANT_A, "tenant-a", "world", "World", 1);
    f.stubTargetReads(List.of(target), List.of(target));
    when(f.entitlements.evaluate(TENANT_A))
        .thenReturn(evaluation(TENANT_A, true, NOW.plusSeconds(30)));
    when(f.accounts.findByAccountUuid(ACCOUNT_UUID))
        .thenReturn(Optional.of(account(ACCOUNT_ID)), Optional.of(account(ACCOUNT_ID + 1)));
    when(f.sessions.isAccountSessionActive(ACCOUNT_ID + 1, TOKEN)).thenReturn(true);

    assertCode("CONNECT_CONTEXT_INVALID", () -> f.owner.listWorlds(TOKEN));

    verify(f.accounts, times(2)).findByAccountUuid(ACCOUNT_UUID);
    verify(f.sessions).isAccountSessionActive(ACCOUNT_ID, TOKEN);
    verify(f.sessions).isAccountSessionActive(ACCOUNT_ID + 1, TOKEN);
  }

  @Test
  void entitlementSourceFailureIsNotReplacedWithAnotherSourceOrPartialSuccess() {
    Fixture f = new Fixture();
    Target target = target(TENANT_A, "tenant-a", "world", "World", 1);
    f.stubTargetReads(List.of(target), List.of(target));
    when(f.entitlements.evaluate(TENANT_A))
        .thenThrow(
            new AccountPublicBootstrapEntitlementOwner.UnavailableException(
                new IllegalStateException("source unavailable")));

    assertCode("ENTITLEMENT_UNAVAILABLE", () -> f.owner.listWorlds(TOKEN));

    verify(f.targets, times(1)).listVisiblePublicProductionAdmissionTargets(any());
  }

  @Test
  void unreachableGameSessionAuthorityIsAuthUnavailable() {
    for (Status failure :
        new Status[] {
          Status.UNAVAILABLE, Status.DEADLINE_EXCEEDED, Status.CANCELLED, Status.ABORTED
        }) {
      Fixture f = new Fixture();
      when(f.targets.listVisiblePublicProductionAdmissionTargets(any()))
          .thenThrow(failure.asRuntimeException());
      assertCode("AUTH_UNAVAILABLE", () -> f.owner.listWorlds(TOKEN));
      verify(f.entitlements, never()).evaluate(any());
    }
  }

  @Test
  void typedClientLifecycleFailureIsUnavailableWithoutReadingEntitlements() {
    Fixture f = new Fixture();
    when(f.targets.listVisiblePublicProductionAdmissionTargets(any()))
        .thenThrow(new ClientUnavailableException(new IllegalStateException()));

    assertCode("AUTH_UNAVAILABLE", () -> f.owner.listWorlds(TOKEN));

    verifyNoInteractions(f.entitlements);
  }

  @Test
  void malformedUnsupportedAndOtherSemanticSourceFailuresRemainPointerUnavailable() {
    for (RuntimeException failure :
        List.of(
            new IllegalStateException("Malformed or mismatched source response"),
            new IllegalArgumentException("Unsupported source value"),
            new IllegalStateException(
                "Malformed source content", Status.UNAVAILABLE.asRuntimeException()),
            Status.RESOURCE_EXHAUSTED
                .withDescription("gRPC message exceeds maximum size")
                .asRuntimeException(),
            Status.INVALID_ARGUMENT.asRuntimeException(),
            Status.FAILED_PRECONDITION.asRuntimeException(),
            Status.UNIMPLEMENTED.asRuntimeException(),
            Status.PERMISSION_DENIED.asRuntimeException())) {
      Fixture f = new Fixture();
      when(f.targets.listVisiblePublicProductionAdmissionTargets(any())).thenThrow(failure);

      assertCode("ADMISSION_POINTER_UNAVAILABLE", () -> f.owner.listWorlds(TOKEN));

      verifyNoInteractions(f.entitlements);
    }
  }

  @Test
  void ambientSqlOrCallerSessionContextRefusesBeforeAnyAuthorityRead() {
    Fixture ambientSql = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> ambientSql.owner.listWorlds(TOKEN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient SQL");
    verifyNoInteractions(ambientSql.targets, ambientSql.entitlements, ambientSql.accounts);

    TransactionSynchronizationManager.clear();
    Fixture callerContext = new Fixture();
    SessionContext.setContext("account", List.of(), java.util.Map.of());
    assertThatThrownBy(() -> callerContext.owner.listWorlds(TOKEN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("workload-only");
    verifyNoInteractions(callerContext.targets, callerContext.entitlements, callerContext.accounts);
  }

  @Test
  void independentTenantEvaluationsOverlapWithEightWorkerBoundAndNoAmbientContexts() {
    Fixture f = new Fixture();
    var tenants = IntStream.range(0, 24).mapToObj(index -> UUID.randomUUID()).toList();
    var targets =
        IntStream.range(0, tenants.size())
            .mapToObj(
                index ->
                    target(
                        tenants.get(index),
                        "tenant-" + index,
                        "world-" + index,
                        "World " + index,
                        1))
            .toList();
    f.stubTargetReads(targets, targets.reversed());
    var firstWave = new CountDownLatch(8);
    var entered = new AtomicInteger();
    var active = new AtomicInteger();
    var peak = new AtomicInteger();
    when(f.entitlements.evaluate(any()))
        .thenAnswer(
            invocation -> {
              int current = active.incrementAndGet();
              peak.accumulateAndGet(current, Math::max);
              try {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
                if (entered.incrementAndGet() <= 8) {
                  firstWave.countDown();
                  assertThat(firstWave.await(5, TimeUnit.SECONDS)).isTrue();
                }
                return evaluation(invocation.getArgument(0), true, NOW.plusSeconds(15));
              } finally {
                active.decrementAndGet();
              }
            });
    assertThat(f.owner.listWorlds(TOKEN)).hasSize(24);
    assertThat(peak.get()).isEqualTo(8);
    assertThat(entered.get()).isEqualTo(24);
    for (UUID tenant : tenants) verify(f.entitlements, times(1)).evaluate(tenant);
    verify(f.targets, times(2)).listVisiblePublicProductionAdmissionTargets(any());
    verify(f.accounts, times(2)).findByAccountUuid(ACCOUNT_UUID);
  }

  @Test
  void parallelEvaluationForAnotherTenantRefusesInsteadOfReturningPartialList() {
    Fixture f = new Fixture();
    var first = target(TENANT_A, "tenant-a", "alpha", "Alpha", 1);
    var second = target(TENANT_B, "tenant-b", "beta", "Beta", 1);
    f.stubTargetReads(List.of(first, second), List.of(first, second));
    when(f.entitlements.evaluate(TENANT_A))
        .thenReturn(evaluation(TENANT_A, true, NOW.plusSeconds(15)));
    when(f.entitlements.evaluate(TENANT_B))
        .thenReturn(evaluation(TENANT_A, true, NOW.plusSeconds(15)));
    assertCode("ENTITLEMENT_UNAVAILABLE", () -> f.owner.listWorlds(TOKEN));
    verify(f.targets, times(1)).listVisiblePublicProductionAdmissionTargets(any());
  }

  @Test
  void simultaneousDiscoveryCallsAcrossOwnerInstancesShareTheEightEvaluationLimit()
      throws Exception {
    Fixture first = new Fixture();
    Fixture second = new Fixture();
    var tenants = IntStream.range(0, 16).mapToObj(index -> UUID.randomUUID()).toList();
    var targets =
        IntStream.range(0, tenants.size())
            .mapToObj(
                index ->
                    target(
                        tenants.get(index),
                        "tenant-" + index,
                        "world-" + index,
                        "World " + index,
                        1))
            .toList();
    first.stubTargetReads(targets, targets);
    second.stubTargetReads(targets, targets);
    var firstEight = new CountDownLatch(8);
    var release = new CountDownLatch(1);
    var exceeded = new CountDownLatch(1);
    var active = new AtomicInteger();
    var peak = new AtomicInteger();
    org.mockito.stubbing.Answer<Evaluation> evaluation =
        invocation -> {
          int count = active.incrementAndGet();
          peak.accumulateAndGet(count, Math::max);
          if (count > 8) exceeded.countDown();
          firstEight.countDown();
          try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return evaluation(invocation.getArgument(0), true, NOW.plusSeconds(30));
          } finally {
            active.decrementAndGet();
          }
        };
    when(first.entitlements.evaluate(any())).thenAnswer(evaluation);
    when(second.entitlements.evaluate(any())).thenAnswer(evaluation);
    try (var callers = Executors.newFixedThreadPool(2)) {
      var left = callers.submit(() -> first.owner.listWorlds(TOKEN));
      var right = callers.submit(() -> second.owner.listWorlds(TOKEN));
      try {
        assertThat(firstEight.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exceeded.await(200, TimeUnit.MILLISECONDS)).isFalse();
      } finally {
        release.countDown();
      }
      assertThat(left.get(5, TimeUnit.SECONDS)).hasSize(16);
      assertThat(right.get(5, TimeUnit.SECONDS)).hasSize(16);
    }
    assertThat(peak.get()).isEqualTo(8);
    for (UUID tenant : tenants) {
      verify(first.entitlements, times(1)).evaluate(tenant);
      verify(second.entitlements, times(1)).evaluate(tenant);
    }
  }

  private static void assertCode(String expectedCode, ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(AuthenticationException.class)
        .extracting(failure -> ((AuthenticationException) failure).getCode())
        .isEqualTo(expectedCode);
  }

  private static Evaluation evaluation(UUID tenantId, boolean available, Instant validUntil) {
    return new Evaluation(
        tenantId, SourceKind.NON_PAID_DEMO, available, false, 1, 1, NOW, validUntil);
  }

  private static Target target(
      UUID tenantId, String tenantSlug, String worldSlug, String displayName, long pointerVersion) {
    return target(tenantId, tenantSlug, worldSlug, displayName, pointerVersion, "PRESEEDED_ONLY");
  }

  private static Target target(
      UUID tenantId,
      String tenantSlug,
      String worldSlug,
      String displayName,
      long pointerVersion,
      String creationPolicy) {
    String suffix = tenantId.toString();
    return new Target(
        "prod",
        tenantSlug,
        tenantId,
        worldSlug,
        displayName,
        UUID.nameUUIDFromBytes(
            ("realm:" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        "main",
        "Main Realm",
        1,
        2,
        UUID.nameUUIDFromBytes(
            ("namespace:" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        "SHARED",
        UUID.nameUUIDFromBytes(
            ("instance:" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes(
            ("version:" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        3,
        4,
        pointerVersion,
        "a".repeat(64),
        1,
        "initial-request-" + tenantSlug,
        "b".repeat(64),
        OriginKind.NO_PRIOR_POINTER,
        null,
        UUID.nameUUIDFromBytes(
            ("hold:" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes(
            ("fence:" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        "hold-binding-" + tenantSlug,
        5,
        "owner-proof-" + tenantSlug,
        OwnerProofOutcome.COMMITTED,
        false,
        NOW,
        creationPolicy);
  }

  private static Account account(long id) {
    Account account = new Account();
    account.setId(id);
    account.setAccountUuid(ACCOUNT_UUID);
    account.setAccountUuidSourceNumericId(id);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    return account;
  }

  private static final class Fixture {
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final SessionService sessions = mock(SessionService.class);
    private final CanonicalGameplayAdmissionDecisionReadClient targets =
        mock(CanonicalGameplayAdmissionDecisionReadClient.class);
    private final AccountPublicBootstrapEntitlementOwner entitlements =
        mock(AccountPublicBootstrapEntitlementOwner.class);
    private final Jws<Claims> signedToken = mock(Jws.class);
    private final Claims claims = mock(Claims.class);
    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    private final MutableClock clock = new MutableClock(time);
    private final AccountPlayerBootstrapCallerVerifier callerVerifier;
    private final AccountPublicBootstrapWorldsOwner owner;
    private int readCount;

    private Fixture() {
      when(jwtUtil.parseToken(TOKEN)).thenReturn(signedToken);
      when(signedToken.getPayload()).thenReturn(claims);
      when(claims.get("aud")).thenReturn("player-bootstrap");
      when(claims.get("sub")).thenReturn(ACCOUNT_UUID.toString());
      when(claims.get("accountId")).thenReturn(ACCOUNT_UUID.toString());
      when(accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account(ACCOUNT_ID)));
      when(sessions.isAccountSessionActive(ACCOUNT_ID, TOKEN)).thenReturn(true);
      callerVerifier = new AccountPlayerBootstrapCallerVerifier(jwtUtil, accounts, sessions);
      owner = new AccountPublicBootstrapWorldsOwner(callerVerifier, targets, entitlements, clock);
    }

    private void stubTargetReads(List<Target> before, List<Target> after) {
      VisiblePublicProductionTargets initial = evidence(before);
      VisiblePublicProductionTargets current = evidence(after);
      when(targets.listVisiblePublicProductionAdmissionTargets(any())).thenReturn(initial, current);
    }

    private VisiblePublicProductionTargets evidence(List<Target> evidenceTargets) {
      VisiblePublicProductionTargets evidence =
          mock(
              CanonicalGameplayAdmissionDecisionReadGrpcCodec.VisiblePublicProductionTargets.class);
      when(evidence.targets()).thenReturn(evidenceTargets);
      return evidence;
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> time;

    private MutableClock(AtomicReference<Instant> time) {
      this.time = time;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return time.get();
    }
  }
}
