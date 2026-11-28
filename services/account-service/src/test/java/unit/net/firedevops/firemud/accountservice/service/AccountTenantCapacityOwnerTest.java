package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateExpiredException;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository;
import net.firedevops.firemud.accountservice.service.AccountTenantCapacityOwner;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controlled ordering/denial only. Physical transaction and positive current-source proof are
 * separate.
 */
class AccountTenantCapacityOwnerTest {
  @AfterEach
  void clear() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void sharedRejectedUsageVectorsReachAccountReservationGuardWithoutWrites() throws Exception {
    var resource = getClass().getResourceAsStream("/capacity/capacity-admission-v1-vectors.json");
    assertThat(resource).isNotNull();
    try (resource) {
      var corpus = JsonMapper.builder().build().readTree(resource);
      for (var vector : corpus.path("rejectedUsage")) {
        DSLContext dsl = mock(DSLContext.class);
        Record usage = mock(Record.class);
        when(usage.get("held_units", Long.class))
            .thenReturn(TenantCapacityAdmission.decimal(vector.path("held").asText()));
        when(usage.get("used_units", Long.class))
            .thenReturn(TenantCapacityAdmission.decimal(vector.path("usage").asText()));
        when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(usage);
        var request =
            CapacityAdmissionTestFixture.worldRequest(
                TenantCapacityAdmission.decimal(vector.path("capacityDelta").asText()));
        request.requireCommittable();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
          assertThat(
                  new AccountTenantCapacityRepository(dsl)
                      .createIfQuotaAvailable(
                          request,
                          request.evidence().expiresAt(),
                          Clock.fixed(request.evidence().evaluatedAt(), ZoneOffset.UTC)))
              .as(vector.path("name").asText())
              .isEmpty();
          verify(dsl, never()).execute(anyString(), any(Object[].class));
        } finally {
          TransactionSynchronizationManager.setActualTransactionActive(false);
        }
      }
    }
  }

  @Test
  void expiryWhileWaitingForUsageDoesNotReserveCapacity() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    DSLContext dsl = mock(DSLContext.class);
    Clock clock = mock(Clock.class);
    when(clock.instant()).thenThrow(new AssertionError("read before acquiring usage row"));
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(invocation -> {
          org.mockito.Mockito.doReturn(request.evidence().expiresAt()).when(clock).instant();
          return mock(Record.class);
        });
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() ->
            new AccountTenantCapacityRepository(dsl)
                .createIfQuotaAvailable(request, request.evidence().expiresAt(), clock))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.CAPACITY_ADMISSION_STALE));
    verify(dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void exactReplayPrecedesFreshnessCurrentSourcesAndUsageEvenAfterOriginalExpiry()
      throws Exception {
    var f = new Fixture(true);
    var result = f.owner.commit(f.request, f.request.evidence().callerWorkload());
    assertThat(result.reservationId()).isEqualTo(f.reservation);
    assertThat(f.owner.commit(f.request, f.request.evidence().callerWorkload())).isEqualTo(result);
    verifyNoInteractions(f.accounts, f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void genericNoAcceptCannotReplayAsQuotaDenial() throws Exception {
    var f = new Fixture(false);
    f.existing = false;
    Record genericRefusal = mock(Record.class);
    UUID decisionId = UUID.randomUUID();
    when(genericRefusal.get("tenant_uuid", UUID.class)).thenReturn(f.request.identity().tenantId());
    when(genericRefusal.get("request_id", UUID.class)).thenReturn(f.request.identity().requestId());
    when(genericRefusal.get("admission_id", UUID.class))
        .thenReturn(f.request.identity().admissionId());
    when(genericRefusal.get("admission_attempt_id", UUID.class))
        .thenReturn(f.request.admissionAttemptId());
    when(genericRefusal.get("mutation_digest", String.class))
        .thenReturn(f.request.mutationDigest());
    when(genericRefusal.get("canonical_payload", byte[].class))
        .thenReturn(CapacityAdmissionDigest.preimage(f.request));
    when(genericRefusal.get("decision", String.class)).thenReturn("NO_ACCEPT");
    when(genericRefusal.get("decision_id", UUID.class)).thenReturn(decisionId);
    when(genericRefusal.get("refusal_code", String.class)).thenReturn(null);
    f.originDecision = genericRefusal;

    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.IDEMPOTENCY_CONFLICT));
    verifyNoInteractions(f.accounts, f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  private enum InitialDenialPath {
    QUOTA,
    EXPIRES_AT_MEMBERSHIP_OBSERVATION,
    EXPIRES_DURING_SCOPE_QUALIFICATION,
    EXPIRES_ACROSS_SCOPE_CLOCK_SAMPLES,
    EXPIRES_DURING_DELEGATION_READ,
    EXPIRES_DURING_USAGE_LOCK
  }

  @ParameterizedTest
  @EnumSource(InitialDenialPath.class)
  void durableDenialReplaysWithoutConsultingMutableCapacity(InitialDenialPath path)
      throws Exception {
    Failure cause =
        path == InitialDenialPath.QUOTA
            ? Failure.CAPACITY_QUOTA_EXCEEDED
            : Failure.CAPACITY_EVALUATION_EXPIRED;
    var f = new Fixture(false, qualifiedRequest(true));
    f.existing = false;
    Record genericRefusal = mock(Record.class);
    UUID decisionId = UUID.randomUUID();
    String originLookupSql =
        "SELECT * FROM account_capacity_origin_decisions WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ? AND capacity_rearm_fence = ?";
    String originInsertSql =
        "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload, decision, decision_id, refusal_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    Object[] originKey = {
      f.request.identity().tenantId(),
      f.request.identity().requestId(),
      f.request.identity().admissionId(),
      f.request.capacityRearmFence()
    };
    doAnswer(
            invocation -> {
              Object[] arguments = (Object[]) invocation.getRawArguments()[1];
              assertThat(arguments).hasSize(4);
              assertThat(arguments[0]).isEqualTo(f.request.identity().tenantId());
              assertThat(arguments[1]).isEqualTo(f.request.identity().requestId());
              assertThat(arguments[2]).isEqualTo(f.request.identity().admissionId());
              assertThat(arguments[3]).isEqualTo(f.request.capacityRearmFence());
              return f.pendingOriginDecision != null ? f.pendingOriginDecision : f.originDecision;
            })
        .when(f.dsl)
        .fetchOne(eq(originLookupSql), any(Object[].class));
    when(genericRefusal.get("tenant_uuid", UUID.class)).thenReturn(f.request.identity().tenantId());
    when(genericRefusal.get("request_id", UUID.class)).thenReturn(f.request.identity().requestId());
    when(genericRefusal.get("admission_id", UUID.class))
        .thenReturn(f.request.identity().admissionId());
    when(genericRefusal.get("admission_attempt_id", UUID.class))
        .thenReturn(f.request.admissionAttemptId());
    when(genericRefusal.get("mutation_digest", String.class))
        .thenReturn(f.request.mutationDigest());
    when(genericRefusal.get("canonical_payload", byte[].class))
        .thenReturn(CapacityAdmissionDigest.preimage(f.request));
    when(genericRefusal.get("decision", String.class)).thenReturn("NO_ACCEPT");
    when(genericRefusal.get("decision_id", UUID.class)).thenReturn(decisionId);
    when(genericRefusal.get("refusal_code", String.class)).thenReturn(cause.name());
    Record usage = mock(Record.class);
    when(usage.get("held_units", Long.class)).thenReturn(f.request.evidence().quota());
    when(usage.get("used_units", Long.class)).thenReturn(0L);
    org.mockito.Mockito.doReturn(usage)
        .when(f.dsl)
        .fetchOne(startsWith("SELECT * FROM account_tenant_capacity_usage "), any(Object[].class));
    doAnswer(
            invocation -> {
              Object[] arguments = (Object[]) invocation.getRawArguments()[1];
              assertThat(arguments).hasSize(10);
              assertThat(arguments[0]).isEqualTo(f.request.identity().tenantId());
              assertThat(arguments[1]).isEqualTo(f.request.identity().requestId());
              assertThat(arguments[2]).isEqualTo(f.request.identity().admissionId());
              assertThat(arguments[3]).isEqualTo(f.request.capacityRearmFence());
              assertThat(arguments[4]).isEqualTo(f.request.admissionAttemptId());
              assertThat(arguments[5]).isEqualTo(f.request.mutationDigest());
              assertThat(
                      java.util.Arrays.equals(
                          (byte[]) arguments[6], CapacityAdmissionDigest.preimage(f.request)))
                  .isTrue();
              assertThat(arguments[7]).isEqualTo("NO_ACCEPT");
              assertThat(arguments[8]).isInstanceOf(UUID.class);
              assertThat(arguments[9]).isEqualTo(cause.name());
              when(genericRefusal.get("decision_id", UUID.class)).thenReturn((UUID) arguments[8]);
              f.pendingOriginDecision = genericRefusal;
              return 1;
            })
        .when(f.dsl)
        .execute(eq(originInsertSql), any(Object[].class));
    f.retained = true;
    var scope = qualifySources(f);
    Runnable expire =
        () -> {
          when(f.clock.instant()).thenReturn(f.request.evidence().expiresAt());
          f.databaseExpired = true;
        };
    if (path == InitialDenialPath.EXPIRES_DURING_DELEGATION_READ) {
      when(scope.connectScopeExpiresAt()).thenReturn(f.request.evidence().expiresAt().toString());
    }
    switch (path) {
      case QUOTA -> {}
      case EXPIRES_AT_MEMBERSHIP_OBSERVATION -> f.onDatabaseObservation = expire;
      case EXPIRES_DURING_DELEGATION_READ ->
          when(f.issuances.readCurrentCommittedCandidateByJti(
                  CapacityAdmissionTestFixture.DELEGATION_JTI))
              .thenAnswer(ignored -> {
                throw markDelegationExpired(f);
              });
      case EXPIRES_ACROSS_SCOPE_CLOCK_SAMPLES ->
          when(f.scopes.findCanonicalEvidenceByTokenHash(verifiedScopeTokenHash(f.request)))
              .thenAnswer(ignored -> {
                when(f.clock.instant()).thenReturn(f.request.evidence().expiresAt());
                var observations = new AtomicInteger();
                f.onDatabaseObservation =
                    () -> f.databaseExpired = observations.incrementAndGet() >= 2;
                return Optional.of(scope);
              });
      case EXPIRES_DURING_SCOPE_QUALIFICATION ->
          when(f.scopes.findCanonicalEvidenceByTokenHash(verifiedScopeTokenHash(f.request)))
              .thenAnswer(ignored -> {
                expire.run();
                return Optional.of(scope);
              });
      case EXPIRES_DURING_USAGE_LOCK ->
          doAnswer(
                  ignored -> {
                    expire.run();
                    return usage;
                  })
              .when(f.dsl)
              .fetchOne(
                  startsWith("SELECT * FROM account_tenant_capacity_usage "), any(Object[].class));
    }
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(failure.code()).isEqualTo(cause);
              assertThat(failure.durableDenialId())
                  .isEqualTo(genericRefusal.get("decision_id", UUID.class));
            });
    assertThat(f.originDecision).isSameAs(genericRefusal);
    assertThat(f.pendingOriginDecision).isNull();
    UUID committedId = genericRefusal.get("decision_id", UUID.class);
    if (path == InitialDenialPath.EXPIRES_AT_MEMBERSHIP_OBSERVATION) {
      verifyNoInteractions(f.issuances, f.scopes);
    } else {
      verify(f.scopes).findCanonicalEvidenceByTokenHash(verifiedScopeTokenHash(f.request));
    }
    if (path != InitialDenialPath.QUOTA && path != InitialDenialPath.EXPIRES_DURING_USAGE_LOCK) {
      verify(f.dsl, never())
          .fetchOne(
              startsWith("SELECT * FROM account_tenant_capacity_usage "), any(Object[].class));
    }
    verify(f.dsl, never())
        .execute(startsWith("UPDATE account_tenant_capacity_usage "), any(Object[].class));
    verify(f.dsl, never())
        .execute(
            startsWith("INSERT INTO account_tenant_capacity_reservations "), any(Object[].class));
    verify(f.dsl, times(1)).execute(eq(originInsertSql), any(Object[].class));
    verify(f.dsl, times(3)).fetchOne(eq(originLookupSql), aryEq(originKey));
    when(usage.get("held_units", Long.class)).thenReturn(0L);
    org.mockito.Mockito.clearInvocations(f.dsl, f.accounts, f.memberships, f.issuances, f.scopes);

    // Capacity is now available. Exact retries return the original committed denial.
    for (int retry = 0; retry < 2; retry++) {
      assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
          .isInstanceOfSatisfying(
              AccountTenantCapacityRepository.CapacityException.class,
              failure -> {
                assertThat(failure.code()).isEqualTo(cause);
                assertThat(failure.durableDenialId()).isEqualTo(committedId);
              });
    }
    verifyNoInteractions(f.accounts, f.memberships, f.issuances, f.scopes);
    verify(f.dsl, times(2)).fetchOne(eq(originLookupSql), aryEq(originKey));
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
    verify(f.dsl, never())
        .fetchOne(startsWith("SELECT * FROM account_tenant_capacity_usage "), any(Object[].class));
  }

  @Test
  void delegationExpiryDuringLiveQualificationRequiresAnExactScope() throws Exception {
    var f = new Fixture(false, qualifiedRequest(true));
    f.existing = false;
    f.retained = true;
    qualifySources(f);
    String scopeTokenHash = verifiedScopeTokenHash(f.request);
    when(f.scopes.findCanonicalEvidenceByTokenHash(scopeTokenHash)).thenReturn(Optional.empty());
    when(f.issuances.readCurrentCommittedCandidateByJti(
            CapacityAdmissionTestFixture.DELEGATION_JTI))
        .thenAnswer(ignored -> {
          throw markDelegationExpired(f);
        });

    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(Failure.ENTITLEMENT_UNAVAILABLE);
              assertThat(failure.durableDenialId()).isNull();
            });
    verify(f.scopes).findCanonicalEvidenceByTokenHash(scopeTokenHash);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  private static CommittedCandidateExpiredException markDelegationExpired(Fixture f) {
    f.databaseExpired = true;
    Instant tokenExpiry = sourceTokenExpiry(f.request);
    assertThat(tokenExpiry).isAfter(f.request.evidence().expiresAt());
    when(f.clock.instant()).thenReturn(tokenExpiry);
    return new CommittedCandidateExpiredException(
        CapacityAdmissionTestFixture.DELEGATION_JTI, tokenExpiry.getEpochSecond());
  }

  @Test
  void unavailableDelegationDuringExpiryDoesNotRetainDenial() throws Exception {
    var f = new Fixture(false, qualifiedRequest(true));
    f.existing = false;
    f.retained = true;
    qualifySources(f);
    when(f.issuances.readCurrentCommittedCandidateByJti(
            CapacityAdmissionTestFixture.DELEGATION_JTI))
        .thenAnswer(ignored -> {
          when(f.clock.instant()).thenReturn(f.request.evidence().expiresAt());
          f.databaseExpired = true;
          throw new AccountGameplayDelegationIssuanceRepository.StorageUnavailableException();
        });

    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(Failure.ENTITLEMENT_UNAVAILABLE);
              assertThat(failure.durableDenialId()).isNull();
            });
    verifyNoInteractions(f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void localExpiryWithoutPostgresExpiryDoesNotRetainDenial() throws Exception {
    var f = new Fixture(false, qualifiedRequest(true));
    f.existing = false;
    f.retained = true;
    qualifySources(f);
    when(f.clock.instant()).thenReturn(f.request.evidence().expiresAt());

    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(Failure.CAPACITY_ADMISSION_STALE);
              assertThat(failure.durableDenialId()).isNull();
            });
    verify(f.dsl, times(2))
        .fetchOne(
            eq("SELECT clock_timestamp() >= ?::timestamptz AS expired"),
            eq(AccountTenantCapacityRepository.at(f.request.evidence().expiresAt())));
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
    verifyNoInteractions(f.issuances, f.scopes);
  }

  @Test
  void notYetValidExactEvaluationDoesNotRetainDenial() throws Exception {
    var f = new Fixture(false, qualifiedRequest(true));
    f.existing = false;
    f.retained = true;
    when(f.clock.instant()).thenReturn(f.request.evidence().evaluatedAt().minusMillis(1));

    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .hasMessage("CAPACITY_ADMISSION_STALE");
    verify(f.dsl, never()).fetchOne(startsWith("SELECT clock_timestamp()"), any(Object[].class));
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
    verifyNoInteractions(f.memberships, f.issuances, f.scopes);
  }

  private enum ExpiryDuringOriginWrite {
    ACCOUNT_AFTER_ACCEPTANCE,
    ACCOUNT_AFTER_QUOTA_DENIAL,
    POSTGRES_AFTER_QUOTA_DENIAL
  }

  @ParameterizedTest
  @EnumSource(ExpiryDuringOriginWrite.class)
  void expiryDuringOriginRetentionRollsBackAcceptanceAndRefusal(ExpiryDuringOriginWrite expiry)
      throws Exception {
    boolean quotaFull = expiry != ExpiryDuringOriginWrite.ACCOUNT_AFTER_ACCEPTANCE;
    var f = new Fixture(false, qualifiedRequest(true));
    f.existing = false;
    f.retained = true;
    qualifySources(f);
    Record usage = mock(Record.class);
    when(usage.get("held_units", Long.class))
        .thenReturn(quotaFull ? f.request.evidence().quota() : 0L);
    when(usage.get("used_units", Long.class)).thenReturn(0L);
    org.mockito.Mockito.doReturn(usage)
        .when(f.dsl)
        .fetchOne(startsWith("SELECT * FROM account_tenant_capacity_usage "), any(Object[].class));
    retainOriginThen(
        f,
        f.request,
        quotaFull ? "NO_ACCEPT" : "ACCEPTED",
        () -> {
          if (expiry == ExpiryDuringOriginWrite.POSTGRES_AFTER_QUOTA_DENIAL) {
            f.databaseExpired = true;
          } else {
            when(f.clock.instant()).thenReturn(f.request.evidence().expiresAt());
          }
        });

    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(Failure.CAPACITY_ADMISSION_STALE);
              assertThat(failure.durableDenialId()).isNull();
            });
    verify(f.dsl)
        .execute(startsWith("INSERT INTO account_capacity_origin_decisions "), any(Object[].class));
    verify(f.dsl, never())
        .execute(startsWith("UPDATE account_tenant_capacity_usage "), any(Object[].class));
    verify(f.dsl, never())
        .execute(
            startsWith("INSERT INTO account_tenant_capacity_reservations "), any(Object[].class));
    assertThat(f.transactionEvents).containsExactly("begin", "rollback");
    assertThat(f.originDecision).isNull();
    assertThat(f.pendingOriginDecision).isNull();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void expiryAfterReservationWriteRollsBackAndDoesNotSupplyDenial(boolean databaseRejectsCommit)
      throws Exception {
    var f = new Fixture(false, qualifiedRequest(true));
    f.databaseRejectsDeferredCommit = databaseRejectsCommit;
    f.existing = false;
    f.retained = true;
    qualifySources(f);
    Record usage = mock(Record.class);
    when(usage.get("held_units", Long.class)).thenReturn(0L);
    when(usage.get("used_units", Long.class)).thenReturn(0L);
    when(usage.get("usage_revision", Long.class)).thenReturn(1L);
    org.mockito.Mockito.doReturn(usage)
        .when(f.dsl)
        .fetchOne(startsWith("SELECT * FROM account_tenant_capacity_usage "), any(Object[].class));
    retainOriginThen(f, f.request, "ACCEPTED", () -> {});
    when(f.dsl.execute(startsWith("UPDATE account_tenant_capacity_usage "), any(Object[].class)))
        .thenReturn(1);
    when(f.dsl.execute(
            startsWith("INSERT INTO account_tenant_capacity_reservations "), any(Object[].class)))
        .thenAnswer(ignored -> {
          f.pendingReservation = true;
          if (!databaseRejectsCommit) {
            when(f.clock.instant()).thenReturn(f.request.evidence().expiresAt());
          }
          return 1;
        });

    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(Failure.CAPACITY_ADMISSION_STALE);
              assertThat(failure.durableDenialId()).isNull();
            });
    verify(f.dsl).execute(startsWith("UPDATE account_tenant_capacity_usage "), any(Object[].class));
    verify(f.dsl)
        .execute(
            startsWith("INSERT INTO account_tenant_capacity_reservations "), any(Object[].class));
    assertThat(f.transactionEvents).containsExactly("begin", "rollback");
    assertThat(f.originDecision).isNull();
    assertThat(f.pendingReservation).isFalse();
  }

  private enum RearmExpiry {
    EVALUATION_AFTER_USAGE_LOCK,
    CLAIM_AFTER_ACCEPTANCE,
    CLAIM_DURING_HISTORY_INSERT
  }

  @ParameterizedTest
  @EnumSource(RearmExpiry.class)
  void expiryDuringRearmRollsBackBeforeFenceMutation(RearmExpiry expiry) throws Exception {
    boolean evaluationExpires = expiry == RearmExpiry.EVALUATION_AFTER_USAGE_LOCK;
    var f = new Fixture(false, qualifiedRequest(true));
    f.retained = true;
    qualifySources(f);
    var next =
        new Request(
                f.request.identity(),
                UUID.randomUUID(),
                2,
                f.request.targetNamespace(),
                f.request.context(),
                f.request.operation(),
                f.request.priorShape(),
                f.request.requestedShape(),
                f.request.evidence(),
                f.request.capacityDelta(),
                null)
            .withDigest();
    Record row =
        java.util.Objects.requireNonNull(
            f.dsl.fetchOne("SELECT * FROM account_tenant_capacity_reservations ", new Object[0]));
    UUID claimOwner = UUID.randomUUID();
    var claimExpiry =
        evaluationExpires
            ? next.evidence().expiresAt().plusSeconds(1)
            : next.evidence().expiresAt().minusSeconds(1);
    when(row.get("recovery_owner", UUID.class)).thenReturn(claimOwner);
    when(row.get("recovery_claim_fence", Long.class)).thenReturn(7L);
    when(row.get("recovery_claim_expires_at", java.time.OffsetDateTime.class))
        .thenReturn(AccountTenantCapacityRepository.at(claimExpiry));
    when(row.get("execution_status", String.class)).thenReturn("NOT_EXECUTED_AFTER_AUTHORIZATION");
    when(row.get("owner_readback", byte[].class)).thenReturn(new byte[] {1});
    String usageLock =
        "SELECT * FROM account_tenant_capacity_usage WHERE tenant_uuid = ? AND owner_service = ? FOR UPDATE";
    Record usage = mock(Record.class);
    doAnswer(
            invocation -> {
              if (evaluationExpires) {
                f.databaseExpired = true;
                when(f.clock.instant()).thenReturn(next.evidence().expiresAt());
              }
              return usage;
            })
        .when(f.dsl)
        .fetchOne(startsWith(usageLock), any(Object[].class));
    if (!evaluationExpires) {
      retainOriginThen(
          f,
          next,
          "ACCEPTED",
          () -> {
            if (expiry == RearmExpiry.CLAIM_AFTER_ACCEPTANCE) {
              when(f.clock.instant()).thenReturn(claimExpiry);
            }
          });
    }
    if (expiry == RearmExpiry.CLAIM_DURING_HISTORY_INSERT) {
      when(f.dsl.execute(
              startsWith("INSERT INTO account_tenant_capacity_attempt_history "),
              any(Object[].class)))
          .thenAnswer(ignored -> {
            when(f.clock.instant()).thenReturn(claimExpiry);
            return 1;
          });
    }
    var rearm =
        AccountTenantCapacityOwner.class.getDeclaredMethod(
            "rearm", Request.class, Request.class, UUID.class, long.class);
    rearm.setAccessible(true);
    assertThatThrownBy(() -> rearm.invoke(f.owner, f.request, next, claimOwner, 7L))
        .hasCauseInstanceOf(AccountTenantCapacityRepository.CapacityException.class)
        .hasRootCauseMessage(
            evaluationExpires ? "CAPACITY_ADMISSION_STALE" : "IDEMPOTENCY_CONFLICT");
    var lockOrder = org.mockito.Mockito.inOrder(f.dsl);
    lockOrder.verify(f.dsl).fetchOne(startsWith(usageLock), any(Object[].class));
    lockOrder
        .verify(f.dsl, times(evaluationExpires ? 0 : 1))
        .execute(startsWith("INSERT INTO account_capacity_origin_decisions "), any(Object[].class));
    verify(f.dsl, times(expiry == RearmExpiry.CLAIM_DURING_HISTORY_INSERT ? 1 : 0))
        .execute(
            startsWith("INSERT INTO account_tenant_capacity_attempt_history "),
            any(Object[].class));
    verify(f.dsl, never())
        .execute(startsWith("UPDATE account_tenant_capacity_reservations "), any(Object[].class));
    assertThat(f.originDecision).isNull();
    assertThat(f.pendingOriginDecision).isNull();
    assertThat(f.transactionEvents).containsExactly("begin", "rollback");
  }

  private static void retainOriginThen(
      Fixture f, Request request, String decision, Runnable afterWrite) {
    Record retained = mock(Record.class);
    when(retained.get("admission_attempt_id", UUID.class)).thenReturn(request.admissionAttemptId());
    when(retained.get("mutation_digest", String.class)).thenReturn(request.mutationDigest());
    when(retained.get("canonical_payload", byte[].class))
        .thenReturn(CapacityAdmissionDigest.preimage(request));
    when(retained.get("decision", String.class)).thenReturn(decision);
    when(retained.get("decision_id", UUID.class)).thenReturn(UUID.randomUUID());
    when(retained.get("refusal_code", String.class))
        .thenReturn("NO_ACCEPT".equals(decision) ? "CAPACITY_QUOTA_EXCEEDED" : null);
    when(f.dsl.execute(
            startsWith("INSERT INTO account_capacity_origin_decisions "), any(Object[].class)))
        .thenAnswer(invocation -> {
          f.pendingOriginDecision = retained;
          afterWrite.run();
          return 1;
        });
  }

  @Test
  void rearmRetriesSerializationInFreshTransactionThenRechecksAccount() throws Exception {
    var f = new Fixture(false, qualifiedRequest(true));
    var next =
        new Request(
                f.request.identity(),
                UUID.randomUUID(),
                2,
                f.request.targetNamespace(),
                f.request.context(),
                f.request.operation(),
                f.request.priorShape(),
                f.request.requestedShape(),
                f.request.evidence(),
                f.request.capacityDelta(),
                null)
            .withDigest();
    var aborted = serializationFailure();
    var lookups = new AtomicInteger();
    when(f.accounts.findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT))
        .thenAnswer(invocation -> {
          f.transactionEvents.add("lookup");
          if (lookups.incrementAndGet() == 1) {
            throw aborted;
          }
          return Optional.empty();
        });
    var rearm =
        AccountTenantCapacityOwner.class.getDeclaredMethod(
            "rearm", Request.class, Request.class, UUID.class, long.class);
    rearm.setAccessible(true);
    assertThatThrownBy(() -> rearm.invoke(f.owner, f.request, next, UUID.randomUUID(), 7L))
        .hasCauseInstanceOf(AccountTenantCapacityRepository.CapacityException.class);
    assertThat(f.transactionEvents)
        .containsExactly("begin", "lookup", "rollback", "begin", "lookup", "rollback");
    verify(f.accounts, times(2)).findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void repeatedSerializationFailuresRetryAndReplayInAFreshTransaction() throws Exception {
    var f = new Fixture(true);
    f.existing = false;
    var aborted = serializationFailure();
    var abortedAttempts = new AtomicInteger();
    when(f.accounts.findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT))
        .thenAnswer(invocation -> {
          if (abortedAttempts.incrementAndGet() == 2) f.existing = true;
          throw aborted;
        });

    assertThat(f.owner.commit(f.request, f.request.evidence().callerWorkload()).reservationId())
        .isEqualTo(f.reservation);
    verify(f.accounts, times(2)).findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT);
    verify(f.dsl, times(3))
        .fetchOne(
            startsWith("SELECT * FROM account_tenant_capacity_reservations "), any(Object[].class));
    verifyNoInteractions(f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void repeatedSerializationFailuresStopAtTheAttemptLimit() throws Exception {
    var f = new Fixture(true);
    f.existing = false;
    var aborted = serializationFailure();
    when(f.accounts.findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT))
        .thenThrow(aborted);

    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isSameAs(aborted);
    verify(f.accounts, times(4)).findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void cancelledCallDoesNotRetryAnAbortedTransaction() throws Exception {
    var f = new Fixture(true);
    f.existing = false;
    var aborted = serializationFailure();
    try (var caller = Context.current().withCancellation()) {
      when(f.accounts.findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT))
          .thenAnswer(invocation -> {
            caller.cancel(null);
            throw aborted;
          });

      caller.run(() ->
          assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
              .isSameAs(aborted));
    }
    verify(f.accounts).findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  private static org.jooq.exception.DataAccessException serializationFailure() {
    return new org.jooq.exception.DataAccessException(
        "concurrent reservation", new SQLException("serialization failure", "40001"));
  }

  @Test
  void changedAttemptOrPayloadOrFenceConflictsBeforeAnyCurrentSourceOrUsageMutation()
      throws Exception {
    var f = new Fixture(false);
    for (Request changed :
        java.util.List.of(
            CapacityAdmissionTestFixture.playerRequest(false),
            new Request(
                    f.request.identity(),
                    UUID.randomUUID(),
                    f.request.capacityRearmFence(),
                    f.request.targetNamespace(),
                    f.request.context(),
                    f.request.operation(),
                    f.request.priorShape(),
                    f.request.requestedShape(),
                    f.request.evidence(),
                    f.request.capacityDelta(),
                    null)
                .withDigest(),
            new Request(
                    f.request.identity(),
                    f.request.admissionAttemptId(),
                    2,
                    f.request.targetNamespace(),
                    f.request.context(),
                    f.request.operation(),
                    f.request.priorShape(),
                    f.request.requestedShape(),
                    f.request.evidence(),
                    f.request.capacityDelta(),
                    null)
                .withDigest(),
            new Request(
                    f.request.identity(),
                    f.request.admissionAttemptId(),
                    f.request.capacityRearmFence(),
                    f.request.targetNamespace(),
                    f.request.context(),
                    f.request.operation(),
                    f.request.priorShape(),
                    new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission
                        .Shape(2, 0, 0),
                    f.request.evidence(),
                    f.request.capacityDelta(),
                    null)
                .withDigest())) {
      assertThatThrownBy(() -> f.owner.commit(changed, changed.evidence().callerWorkload()))
          .hasMessage("IDEMPOTENCY_CONFLICT");
    }
    verifyNoInteractions(f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void matchingContentWithoutAccountRetainedAuthorityCannotCreateUsage() throws Exception {
    var f = new Fixture(false);
    f.existing = false;
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .hasMessage("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void wrongPeerOrAbsentDeltaNeverStartsAccountWork() throws Exception {
    var f = new Fixture(false);
    assertThatThrownBy(
            () -> f.owner.commit(f.request, "spiffe://firemud/ns/prod/sa/world-management-service"))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(Failure.PERMISSION_DENIED);
              assertThat(failure.code().toStatusException().getStatus().getCode())
                  .isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED);
            });
    var absent =
        new Request(
                f.request.identity(),
                f.request.admissionAttemptId(),
                1,
                f.request.targetNamespace(),
                f.request.context(),
                f.request.operation(),
                f.request.priorShape(),
                f.request.requestedShape(),
                f.request.evidence(),
                null,
                null)
            .withDigest();
    assertThatThrownBy(() -> f.owner.commit(absent, absent.evidence().callerWorkload()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(f.accounts, f.dsl, f.memberships, f.issuances, f.scopes);
  }

  @Test
  void authenticatedGameSessionCannotSelectWorldOwnerBeforeAccountWork() throws Exception {
    var f = new Fixture(false, CapacityAdmissionTestFixture.worldRequest(1L));
    assertThatThrownBy(() -> f.owner.commit(f.request, CapacityAdmissionTestFixture.PEER))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.PERMISSION_DENIED));
    verifyNoInteractions(f.accounts, f.dsl, f.memberships, f.issuances, f.scopes);
  }

  @Test
  void authenticatedWorldRequestCannotReplayWhileWorldCapacityRemainsHeld() throws Exception {
    var f = new Fixture(false, CapacityAdmissionTestFixture.worldRequest(1L));
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.ENTITLEMENT_UNAVAILABLE));
    verifyNoInteractions(f.accounts, f.dsl, f.memberships, f.issuances, f.scopes);
  }

  @Test
  void wrongTargetNamespaceOrMissingRequestIsInvalidBeforeAccountWork() throws Exception {
    var f = new Fixture(false, CapacityAdmissionTestFixture.playerRequest(), "staging");
    for (String peer :
        java.util.List.of(
            CapacityAdmissionTestFixture.PEER,
            "spiffe://firemud/ns/staging/sa/game-session-service")) {
      assertThatThrownBy(() -> f.owner.commit(f.request, peer))
          .isInstanceOfSatisfying(
              AccountTenantCapacityRepository.CapacityException.class,
              failure -> {
                assertThat(failure.code()).isEqualTo(Failure.INVALID_ARGUMENT);
                assertThat(failure.code().toStatusException().getStatus().getCode())
                    .isEqualTo(io.grpc.Status.Code.INVALID_ARGUMENT);
              });
    }
    assertThatThrownBy(() -> f.owner.commit(null, CapacityAdmissionTestFixture.PEER))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.INVALID_ARGUMENT));
    verifyNoInteractions(f.accounts, f.dsl, f.memberships, f.issuances, f.scopes);
  }

  @Test
  void unretainedNonCreatingEvidenceRemainsUnavailableWithoutAnyUsageWrite() throws Exception {
    var f = new Fixture(false, CapacityAdmissionTestFixture.playerRequest(false));
    f.existing = false;
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .hasMessage("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void exactRetainedNonCreatingEvidenceOutsideWindowIsStale() throws Exception {
    var f = new Fixture(true, CapacityAdmissionTestFixture.playerRequest(false));
    f.existing = false;
    f.retained = true;
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .hasMessage("CAPACITY_ADMISSION_STALE");
    verifyNoInteractions(f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void qualifiedNewerBillingCutoffMakesExactRetainedEvaluationStale(boolean expired)
      throws Exception {
    var f = new Fixture(false);
    f.existing = false;
    f.retained = true;
    if (expired) {
      when(f.clock.instant()).thenReturn(f.request.evidence().expiresAt());
      f.databaseExpired = true;
    }
    var current = mock(Snapshot.class);
    var tuple = mock(AuthorityTuple.class);
    var tenant = f.request.identity().tenantId().toString();
    var sequence =
        Long.parseLong((String) f.request.evidence().fields().get("tenantBillingSequence"));
    when(current.membershipExists()).thenReturn(true);
    when(current.membershipLifecycleState()).thenReturn("ACTIVE");
    when(current.gameplayAdmissionAllowed()).thenReturn(true);
    when(current.tenantId()).thenReturn(tenant);
    when(current.authorityTuple()).thenReturn(tuple);
    when(tuple.tenantBillingCutoff())
        .thenReturn(
            Optional.of(
                Map.of(
                    tenant,
                    new TenantBillingCutoff("1", Long.toString(sequence + 1), "billing", "2"))));
    when(f.memberships.readCurrent(
            CapacityAdmissionTestFixture.ACCOUNT, f.request.identity().tenantId()))
        .thenReturn(current);
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .hasMessage("CAPACITY_ADMISSION_STALE");
    verify(f.memberships)
        .readCurrent(CapacityAdmissionTestFixture.ACCOUNT, f.request.identity().tenantId());
    verifyNoInteractions(f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void currentOwnerReadWithoutQualifiedEvidenceIsUnavailable() throws Exception {
    var f = new Fixture(false);
    f.existing = false;
    f.retained = true;
    when(f.memberships.readCurrent(
            CapacityAdmissionTestFixture.ACCOUNT, f.request.identity().tenantId()))
        .thenThrow(new IllegalStateException("Missing committed source"));
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .hasMessage("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void nonCreatingEvidenceCannotStartRecoveryOrRearmWork() throws Exception {
    var f = new Fixture(false);
    var source =
        mock(
            net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker
                .ExecutionReadClient.class);
    var recovery =
        new net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker(
            f.owner, f.dsl, source, Clock.fixed(CapacityAdmissionTestFixture.NOW, ZoneOffset.UTC));
    var denied = CapacityAdmissionTestFixture.playerRequest(false);
    var next =
        new Request(
                denied.identity(),
                UUID.randomUUID(),
                2,
                denied.targetNamespace(),
                denied.context(),
                denied.operation(),
                denied.priorShape(),
                denied.requestedShape(),
                denied.evidence(),
                denied.capacityDelta(),
                null)
            .withDigest();
    assertThatThrownBy(() -> recovery.rearm(f.request, next))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            recovery.reconcile(
                denied,
                net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker
                    .NoCommitAction.RETAIN_FOR_REARM))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(f.accounts, f.dsl, f.memberships, f.issuances, f.scopes, source);
  }

  @Test
  void attemptAlreadyBoundToAnotherReservationCannotBecomeQuotaDenial() throws Exception {
    var f = new Fixture(false, qualifiedRequest(true));
    f.existing = false;
    f.retained = true;
    qualifySources(f);
    Record usage = mock(Record.class);
    when(usage.get("held_units", Long.class)).thenReturn(f.request.evidence().quota());
    when(usage.get("used_units", Long.class)).thenReturn(0L);
    org.mockito.Mockito.doReturn(usage)
        .when(f.dsl)
        .fetchOne(startsWith("SELECT * FROM account_tenant_capacity_usage "), any(Object[].class));
    // A retained reservation/history/origin with this attempt but a different outer identity.
    org.mockito.Mockito.doReturn(mock(Record.class))
        .when(f.dsl)
        .fetchOne(startsWith("SELECT 1 FROM (SELECT tenant_uuid"), any(Object[].class));
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.IDEMPOTENCY_CONFLICT));
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void globalAttemptConflictPrecedesFreshOrExpiredNonCreatingEvidence() throws Exception {
    for (boolean expired : new boolean[] {false, true}) {
      var f = new Fixture(false, qualifiedRequest(false));
      f.existing = false;
      f.retained = true;
      when(f.clock.instant())
          .thenReturn(
              expired
                  ? f.request.evidence().expiresAt()
                  : CapacityAdmissionTestFixture.NOW.plusSeconds(1));
      org.mockito.Mockito.doReturn(mock(Record.class))
          .when(f.dsl)
          .fetchOne(startsWith("SELECT 1 FROM (SELECT tenant_uuid"), any(Object[].class));
      assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
          .isInstanceOfSatisfying(
              AccountTenantCapacityRepository.CapacityException.class,
              failure -> assertThat(failure.code()).isEqualTo(Failure.IDEMPOTENCY_CONFLICT));
      verify(f.dsl, never()).execute(anyString(), any(Object[].class));
      verifyNoInteractions(f.accounts, f.memberships, f.issuances, f.scopes);
    }
  }

  @Test
  void expiryDuringSourceQualificationCannotBecomeBillingRefusal() throws Exception {
    var f = new Fixture(false, qualifiedRequest(false));
    f.existing = false;
    f.retained = true;
    var scope = qualifySources(f);
    var scopeTokenHash = verifiedScopeTokenHash(f.request);
    var now =
        new java.util.concurrent.atomic.AtomicReference<>(
            CapacityAdmissionTestFixture.NOW.plusSeconds(1));
    when(f.clock.instant()).thenAnswer(ignored -> now.get());
    when(f.scopes.findCanonicalEvidenceByTokenHash(scopeTokenHash))
        .thenAnswer(ignored -> {
          now.set(f.request.evidence().expiresAt());
          f.databaseExpired = true;
          return Optional.of(scope);
        });
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.CAPACITY_ADMISSION_STALE));
    verify(f.issuances)
        .readCurrentCommittedCandidateByJti(CapacityAdmissionTestFixture.DELEGATION_JTI);
    verify(f.scopes).findCanonicalEvidenceByTokenHash(scopeTokenHash);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @SuppressWarnings("unchecked")
  private static Request qualifiedRequest(boolean creating) {
    var original = CapacityAdmissionTestFixture.playerRequest();
    var player = (TenantCapacityAdmission.PlayerContext) original.context();
    var target = (Map<String, Object>) original.evidence().fields().get("target");
    var admitted =
        CapacityAdmissionTestFixture.playerRequestFor(
            player,
            original.identity().admissionId(),
            original.admissionAttemptId(),
            "prod",
            target,
            CapacityAdmissionTestFixture.NOW);
    if (creating) return admitted;
    var fields = admitted.evidence().fields();
    var parsed =
        net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence
            .parseCanonical(admitted.evidence().canonicalBytes());
    Map<String, Object> facts = new java.util.LinkedHashMap<>();
    for (String key :
        java.util.List.of(
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
      facts.put(key, fields.get(key));
    }
    facts.put("allowNewGameplayBindings", false);
    var evidence =
        net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence
            .fromOwnerEvaluation(
                player.requestId(),
                admitted.evidence().callerWorkload(),
                parsed.verifiedConnectScopeTokenHash(),
                parsed.membershipSource(),
                target,
                facts,
                (Map<String, Object>) fields.get("sourceReferences"),
                CapacityAdmissionTestFixture.NOW);
    return new Request(
            admitted.identity(),
            admitted.admissionAttemptId(),
            1,
            admitted.targetNamespace(),
            admitted.context(),
            admitted.operation(),
            admitted.priorShape(),
            admitted.requestedShape(),
            TenantCapacityAdmission.Evidence.player(evidence),
            1L,
            null)
        .withDigest();
  }

  private static Instant sourceTokenExpiry(Request request) {
    var source = (Map<?, ?>) request.evidence().fields().get("sourceReferences");
    return Instant.parse((String) source.get("callerTokenExpiresAt"));
  }

  private static String verifiedScopeTokenHash(Request request) {
    return net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence
        .parseCanonical(request.evidence().canonicalBytes())
        .verifiedConnectScopeTokenHash();
  }

  private static AccountConnectScopeRepository.CanonicalConnectScopeEvidence qualifySources(
      Fixture f) {
    var player = (TenantCapacityAdmission.PlayerContext) f.request.context();
    var parsed =
        net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence
            .parseCanonical(f.request.evidence().canonicalBytes());
    when(f.memberships.readCurrent(player.accountId(), player.tenantId()))
        .thenReturn(parsed.membershipSource());
    var source = (Map<?, ?>) f.request.evidence().fields().get("sourceReferences");
    var issuance =
        mock(
            AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData.class,
            org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(issuance.identity().accountId()).thenReturn(player.accountId());
    when(issuance.identity().callerWorkload()).thenReturn(f.request.evidence().callerWorkload());
    when(issuance.identity().callerContextId())
        .thenReturn(UUID.fromString((String) source.get("callerContextId")));
    when(issuance.identity().operationId())
        .thenReturn(UUID.fromString((String) source.get("callerOperationId")));
    when(issuance.identity().notBeforeEpochSecond())
        .thenReturn(CapacityAdmissionTestFixture.NOW.minusSeconds(60).getEpochSecond());
    when(issuance.identity().expiresAtEpochSecond())
        .thenReturn(sourceTokenExpiry(f.request).getEpochSecond());
    when(issuance.tokenSha256()).thenReturn((String) source.get("callerTokenSha256"));
    when(issuance.canonicalRegistryRecordSha256())
        .thenReturn((String) source.get("callerRegistryRecordSha256"));
    when(issuance.commitProofSha256()).thenReturn((String) source.get("callerCommitProofSha256"));
    var delegationJti = UUID.fromString((String) source.get("callerTokenJti"));
    when(f.issuances.readCurrentCommittedCandidateByJti(delegationJti))
        .thenReturn(Optional.of(issuance));
    var scope = mock(AccountConnectScopeRepository.CanonicalConnectScopeEvidence.class);
    when(scope.accountUuid()).thenReturn(player.accountId());
    when(scope.tenantUuid()).thenReturn(player.tenantId());
    when(scope.scopeDigest()).thenReturn((String) source.get("scopeDigest"));
    when(scope.gameInstanceUuid()).thenReturn(player.gameInstanceId());
    when(scope.playableStateNamespaceUuid()).thenReturn(player.playableStateNamespaceId());
    when(scope.realmId()).thenReturn(player.realmId());
    when(scope.playableStateScope()).thenReturn(player.playableStateScope());
    when(scope.connectScopeExpiresAt())
        .thenReturn(CapacityAdmissionTestFixture.NOW.plusSeconds(3600).toString());
    when(f.scopes.findCanonicalEvidenceByTokenHash(parsed.verifiedConnectScopeTokenHash()))
        .thenReturn(Optional.of(scope));
    return scope;
  }

  private static final class Fixture {
    private final List<String> transactionEvents = new ArrayList<>();
    private final DSLContext dsl = mock(DSLContext.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountCanonicalRuntimeMembershipSnapshotRepository memberships =
        mock(AccountCanonicalRuntimeMembershipSnapshotRepository.class);
    private final AccountGameplayDelegationIssuanceRepository issuances =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    private final AccountConnectScopeRepository scopes = mock(AccountConnectScopeRepository.class);
    private final Clock clock = mock(Clock.class);
    private final Request request;
    private final UUID reservation = UUID.randomUUID();
    private final AccountTenantCapacityOwner owner;
    private boolean existing = true;
    private boolean retained;
    private boolean databaseExpired;
    private boolean databaseRejectsDeferredCommit;
    private Runnable onDatabaseObservation = () -> {};
    private boolean pendingReservation;
    private Record originDecision;
    private Record pendingOriginDecision;

    private Fixture(boolean expired) throws Exception {
      this(expired, CapacityAdmissionTestFixture.playerRequest());
    }

    private Fixture(boolean expired, Request request) throws Exception {
      this(expired, request, "prod");
    }

    private Fixture(boolean expired, Request request, String namespace) throws Exception {
      this.request = request;
      when(clock.instant())
          .thenReturn(CapacityAdmissionTestFixture.NOW.plusSeconds(expired ? 60 : 1));
      when(accounts.findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT))
          .thenReturn(Optional.of(new Account()));
      Connection connection = mock(Connection.class);
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
      doAnswer(
              invocation -> {
                invocation.getArgument(0, ConnectionRunnable.class).run(connection);
                return null;
              })
          .when(dsl)
          .connection(any(ConnectionRunnable.class));
      Record row = mock(Record.class);
      when(row.get("tenant_uuid", UUID.class)).thenReturn(request.identity().tenantId());
      when(row.get("request_id", UUID.class)).thenReturn(request.identity().requestId());
      when(row.get("admission_id", UUID.class)).thenReturn(request.identity().admissionId());
      when(row.get("admission_attempt_id", UUID.class)).thenReturn(request.admissionAttemptId());
      when(row.get("reservation_id", UUID.class)).thenReturn(reservation);
      when(row.get("capacity_rearm_fence", Long.class)).thenReturn(1L);
      when(row.get("reservation_fence", Long.class)).thenReturn(1L);
      when(row.get("mutation_digest", String.class)).thenReturn(request.mutationDigest());
      when(row.get("canonical_payload", byte[].class))
          .thenReturn(CapacityAdmissionDigest.preimage(request));
      when(row.get("caller_workload", String.class))
          .thenReturn(request.evidence().callerWorkload());
      when(row.get("evaluation_identity", String.class))
          .thenReturn(request.evidence().evaluationIdentity());
      when(row.get("expires_at", java.time.OffsetDateTime.class))
          .thenReturn(AccountTenantCapacityRepository.at(request.evidence().expiresAt()));
      when(row.get("state", String.class)).thenReturn("RESERVED");
      Record evaluation = mock(Record.class);
      when(evaluation.get("snapshot_identity", String.class))
          .thenReturn(request.evidence().snapshotIdentity());
      var evidence = request.evidence();
      when(evaluation.get("tenant_uuid", UUID.class)).thenReturn(evidence.tenantId());
      when(evaluation.get("caller_workload", String.class)).thenReturn(evidence.callerWorkload());
      when(evaluation.get("owner_service", String.class)).thenReturn(evidence.owner().service());
      when(evaluation.get("canonical_evidence", byte[].class))
          .thenReturn(evidence.canonicalBytes());
      when(evaluation.get("evaluated_at", java.time.OffsetDateTime.class))
          .thenReturn(AccountTenantCapacityRepository.at(evidence.evaluatedAt()));
      when(evaluation.get("expires_at", java.time.OffsetDateTime.class))
          .thenReturn(AccountTenantCapacityRepository.at(evidence.expiresAt()));
      Record databaseObservation = mock(Record.class);
      when(databaseObservation.get("expired", Boolean.class))
          .thenAnswer(ignored -> databaseExpired);
      when(dsl.fetchOne(anyString(), any(Object[].class)))
          .thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.startsWith("SELECT * FROM account_tenant_capacity_reservations "))
              return existing || pendingReservation ? row : null;
            if (sql.startsWith("SELECT 1 FROM (SELECT tenant_uuid")) return null;
            if (sql.startsWith("SELECT * FROM account_capacity_origin_decisions "))
              return pendingOriginDecision != null ? pendingOriginDecision : originDecision;
            if (sql.startsWith(
                "SELECT snapshot_identity FROM account_tenant_capacity_evaluations "))
              return evaluation;
            if (sql.startsWith("SELECT * FROM account_tenant_capacity_evaluations "))
              return retained ? evaluation : null;
            if (sql.startsWith("SELECT clock_timestamp()")) {
              onDatabaseObservation.run();
              return databaseObservation;
            }
            throw new AssertionError(sql);
          });
      owner =
          new AccountTenantCapacityOwner(
              dsl,
              accounts,
              memberships,
              issuances,
              scopes,
              new ControlledTransactions(this),
              clock,
              namespace);
    }
  }

  private static final class ControlledTransactions implements PlatformTransactionManager {
    private final Fixture fixture;

    private ControlledTransactions(Fixture fixture) {
      this.fixture = fixture;
    }

    @Override
    public TransactionStatus getTransaction(
        @org.jspecify.annotations.Nullable TransactionDefinition definition) {
      assertThat(definition).isNotNull();
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(definition.getTimeout()).isEqualTo(5);
      assertThat(definition.isReadOnly()).isFalse();
      fixture.transactionEvents.add("begin");
      fixture.pendingOriginDecision = null;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      if (fixture.databaseRejectsDeferredCommit) {
        rollback(status);
        throw new org.springframework.transaction.TransactionSystemException(
            "Deferred transaction validation failed",
            new SQLException("Evaluation expired", "P1040"));
      }
      if (fixture.pendingOriginDecision != null)
        fixture.originDecision = fixture.pendingOriginDecision;
      fixture.pendingOriginDecision = null;
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Override
    public void rollback(TransactionStatus status) {
      fixture.transactionEvents.add("rollback");
      fixture.pendingReservation = false;
      fixture.pendingOriginDecision = null;
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }
}
