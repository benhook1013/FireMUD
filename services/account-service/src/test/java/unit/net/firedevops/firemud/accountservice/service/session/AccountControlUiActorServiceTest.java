package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountControlUiActorServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-08T11:00:00Z");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactCommittedTokenIdentityRevalidatesAndRunsCallbackInsideFinalOwnerTransaction() {
    Fixture f = new Fixture();
    f.stubCommitted();
    AtomicBoolean called = new AtomicBoolean();

    String result =
        f.actors.withCurrentCommitted(
            f.accountId,
            f.tenantId,
            f.tokenJti,
            f.environment,
            current -> {
              called.set(true);
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(current.stored()).isSameAs(f.stored);
              assertThat(current.source()).isSameAs(f.snapshot);
              return "current";
            });

    assertThat(result).isEqualTo("current");
    assertThat(called).isTrue();
    verify(f.operations).findCommittedByTokenJti(f.accountId, f.tenantId, f.tokenJti);
    verify(f.operations).lockCommittedByTokenJti(f.stored);
    verify(f.operations).requireCommitted(f.stored);
    verify(f.signers).requireOriginal(f.stored.signerReceipt);
  }

  @Test
  void absentOrNoncommittedOriginalNeverReadsExternalEvidenceOrCallsBack() {
    Fixture f = new Fixture();
    AtomicBoolean called = new AtomicBoolean();
    when(f.operations.findCommittedByTokenJti(f.accountId, f.tenantId, f.tokenJti))
        .thenReturn(null);

    assertDeniedWithoutCallback(f, called);
    verify(f.registry, never()).readActive(anyString());
    verify(f.publicSource, never()).sourceIdentity();
    verify(f.operations, never()).lockCommittedByTokenJti(any());

    Fixture noncommitted = new Fixture();
    noncommitted.stubCommitted();
    var noncommittedRecord =
        noncommitted.recordWithIdentity(
            noncommitted.accountId, noncommitted.tenantId, noncommitted.tokenJti, "CANDIDATE");
    when(noncommitted.operations.findCommittedByTokenJti(
            noncommitted.accountId, noncommitted.tenantId, noncommitted.tokenJti))
        .thenReturn(noncommittedRecord);
    assertDeniedWithoutCallback(noncommitted, new AtomicBoolean());
  }

  @Test
  void mismatchedActorTenantOrJtiReturnedByExactLookupFailsClosed() {
    Fixture f = new Fixture();
    f.stubCommitted();
    var actorMismatchRecord =
        f.recordWithIdentity(UUID.randomUUID(), f.tenantId, f.tokenJti, "COMMITTED");
    when(f.operations.findCommittedByTokenJti(f.accountId, f.tenantId, f.tokenJti))
        .thenReturn(actorMismatchRecord);
    AtomicBoolean called = new AtomicBoolean();

    assertDeniedWithoutCallback(f, called);
    verify(f.registry, never()).readActive(anyString());

    Fixture tenantMismatch = new Fixture();
    tenantMismatch.stubCommitted();
    var tenantMismatchRecord =
        tenantMismatch.recordWithIdentity(
            tenantMismatch.accountId, UUID.randomUUID(), tenantMismatch.tokenJti, "COMMITTED");
    when(tenantMismatch.operations.findCommittedByTokenJti(
            tenantMismatch.accountId, tenantMismatch.tenantId, tenantMismatch.tokenJti))
        .thenReturn(tenantMismatchRecord);
    assertDeniedWithoutCallback(tenantMismatch, new AtomicBoolean());

    Fixture jtiMismatch = new Fixture();
    jtiMismatch.stubCommitted();
    var jtiMismatchRecord =
        jtiMismatch.recordWithIdentity(
            jtiMismatch.accountId, jtiMismatch.tenantId, UUID.randomUUID(), "COMMITTED");
    when(jtiMismatch.operations.findCommittedByTokenJti(
            jtiMismatch.accountId, jtiMismatch.tenantId, jtiMismatch.tokenJti))
        .thenReturn(jtiMismatchRecord);
    assertDeniedWithoutCallback(jtiMismatch, new AtomicBoolean());
  }

  @Test
  void expiredAndFutureCommittedTokenClaimsAreRejected() {
    for (Instant issuedAt : List.of(NOW.minusSeconds(400), NOW.plusSeconds(30))) {
      Fixture f = new Fixture(issuedAt, issuedAt.plusSeconds(300));
      f.stubCommitted();
      AtomicBoolean called = new AtomicBoolean();

      assertDeniedWithoutCallback(f, called);
    }
  }

  @Test
  void expiryReachedDuringCurrentSourceCaptureIsRejectedBeforeCallback() {
    Fixture f = new Fixture();
    f.stubCommitted();
    when(f.authority.capture(f.accountId, f.tenantId, f.environment))
        .thenAnswer(
            ignored -> {
              f.clock.set(f.expiresAt);
              return f.snapshot;
            });

    assertDeniedWithoutCallback(f, new AtomicBoolean());
  }

  @Test
  void expiryReachedDuringCommittedRecoveryIsRejectedBeforeCallback() {
    Fixture f = new Fixture();
    f.stubCommitted();
    var committed = mock(AccountControlUiIssuanceRepository.Committed.class);
    when(committed.tokenHash()).thenReturn(f.stored.tokenHash);
    when(f.operations.requireCommitted(f.stored))
        .thenAnswer(
            ignored -> {
              f.clock.set(f.expiresAt);
              return committed;
            });

    assertDeniedWithoutCallback(f, new AtomicBoolean());
  }

  @Test
  void missingOrChangedActiveRegistryIsRejectedBeforeCallback() {
    Fixture missing = new Fixture();
    missing.stubCommitted();
    when(missing.registry.readActive(missing.stored.tokenHash))
        .thenThrow(new IllegalStateException("test-only missing active registry"));
    assertDeniedWithoutCallback(missing, new AtomicBoolean());

    Fixture changed = new Fixture();
    changed.stubCommitted();
    when(changed.registry.readActive(changed.stored.tokenHash)).thenReturn(new byte[] {99});
    assertDeniedWithoutCallback(changed, new AtomicBoolean());
  }

  @Test
  void stalePublicPinOrOriginalSignerReceiptIsRejected() {
    Fixture pinChanged = new Fixture();
    pinChanged.stubCommitted();
    when(pinChanged.publicSource.sourceIdentity()).thenReturn(pinChanged.differentPin());
    assertDeniedWithoutCallback(pinChanged, new AtomicBoolean());

    Fixture signerChanged = new Fixture();
    signerChanged.stubCommitted();
    when(signerChanged.signers.requireOriginal(signerChanged.stored.signerReceipt))
        .thenThrow(new IllegalStateException("test-only original signer unavailable"));
    assertDeniedWithoutCallback(signerChanged, new AtomicBoolean());
  }

  @Test
  void currentSourceEvidenceTupleMembershipAndActualFenceMustMatchOriginalClaims() {
    Fixture sourceChanged = new Fixture();
    sourceChanged.stubCommitted();
    when(sourceChanged.snapshot.evidence()).thenReturn(new byte[] {8});
    assertDeniedWithoutCallback(sourceChanged, new AtomicBoolean());

    Fixture tupleChanged = new Fixture();
    tupleChanged.stubCommitted();
    when(tupleChanged.snapshot.authorityTuple())
        .thenReturn(Map.of("accountAuthorityGeneration", 2L));
    assertDeniedWithoutCallback(tupleChanged, new AtomicBoolean());

    Fixture membershipChanged = new Fixture();
    membershipChanged.stubCommitted();
    when(membershipChanged.snapshot.membershipVersion())
        .thenReturn(Map.of(membershipChanged.tenantId.toString(), 3L));
    assertDeniedWithoutCallback(membershipChanged, new AtomicBoolean());

    Fixture fenceChanged = new Fixture();
    fenceChanged.stubCommitted();
    when(fenceChanged.snapshot.issuanceFence()).thenReturn(2L);
    assertDeniedWithoutCallback(fenceChanged, new AtomicBoolean());
  }

  @Test
  void concurrentCommittedRowChangeAndAmbientSqlTransactionDenyWithoutCallback() {
    Fixture changed = new Fixture();
    changed.stubCommitted();
    var changedCommittedRecord =
        changed.recordWithIdentity(
            changed.accountId, changed.tenantId, changed.tokenJti, "COMMITTED", UUID.randomUUID());
    when(changed.operations.lockCommittedByTokenJti(changed.stored))
        .thenReturn(changedCommittedRecord);
    assertDeniedWithoutCallback(changed, new AtomicBoolean());

    Fixture ambient = new Fixture();
    ambient.stubCommitted();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    AtomicBoolean called = new AtomicBoolean();
    assertDeniedWithoutCallback(ambient, called);
    verify(ambient.operations, never())
        .findCommittedByTokenJti(ambient.accountId, ambient.tenantId, ambient.tokenJti);
  }

  private static void assertDeniedWithoutCallback(Fixture fixture, AtomicBoolean called) {
    assertThatThrownBy(
            () ->
                fixture.actors.withCurrentCommitted(
                    fixture.accountId,
                    fixture.tenantId,
                    fixture.tokenJti,
                    fixture.environment,
                    current -> {
                      called.set(true);
                      return "unexpected";
                    }))
        .isInstanceOf(RuntimeException.class);
    assertThat(called).isFalse();
  }

  private static final class Fixture {
    final UUID accountId = UUID.randomUUID();
    final UUID tenantId = UUID.randomUUID();
    final UUID tokenJti = UUID.randomUUID();
    final UUID requestId = UUID.randomUUID();
    final UUID operationId = UUID.randomUUID();
    final AccountControlUiAuthority.Snapshot snapshot =
        mock(AccountControlUiAuthority.Snapshot.class);
    final AccountControlUiAuthority authority = mock(AccountControlUiAuthority.class);
    final AccountControlUiIssuanceRepository operations =
        mock(AccountControlUiIssuanceRepository.class);
    final AccountControlUiSignerOwner signers = mock(AccountControlUiSignerOwner.class);
    final AccountControlUiCoordination registry = mock(AccountControlUiCoordination.class);
    final AccountJwtJwksTrustedSource publicSource = mock(AccountJwtJwksTrustedSource.class);
    final CapturedEnvironmentBoundary environment = mock(CapturedEnvironmentBoundary.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final AccountPublicJwksCache.SourceIdentity pin =
        new AccountPublicJwksCache.SourceIdentity(
            "test-control",
            "test-cluster",
            UUID.randomUUID().toString(),
            "test-control",
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "test-revision",
            "https://test-api.example.test:6443",
            "a".repeat(64));
    final MutableClock clock = new MutableClock(NOW);
    final AccountControlUiActorService actors;
    final Instant issuedAt;
    final Instant expiresAt;
    final byte[] signerReceipt;
    final Map<String, Object> claims;
    final byte[] activeRegistry;
    AccountControlUiIssuanceRepository.Stored stored;

    Fixture() {
      this(NOW.minusSeconds(1), NOW.plusSeconds(299));
    }

    Fixture(Instant issuedAt, Instant expiresAt) {
      this.issuedAt = issuedAt;
      this.expiresAt = expiresAt;
      when(snapshot.actor()).thenReturn(accountId);
      when(snapshot.tenant()).thenReturn(tenantId);
      when(snapshot.authorityTuple())
          .thenReturn(
              Map.of(
                  "issuerAuthGeneration",
                  1L,
                  "accountAuthorityGeneration",
                  1L,
                  "tenantAuthorityGeneration",
                  Map.of(tenantId.toString(), 1L),
                  "membershipAuthorityGeneration",
                  Map.of(tenantId.toString(), 1L),
                  "privateRealmGrantVersions",
                  List.of()));
      when(snapshot.membershipVersion()).thenReturn(Map.of(tenantId.toString(), 2L));
      when(snapshot.issuanceFence()).thenReturn(1L);
      when(snapshot.evidence()).thenReturn(new byte[] {7});
      when(snapshot.issuanceFenceSourceVersion()).thenReturn(4L);
      claims =
          new AccountControlUiSigningSpec(
                  operationId, requestId, tokenJti, snapshot, issuedAt, expiresAt)
              .claims();
      signerReceipt =
          AccountControlUiAuthority.canonical(
              Map.ofEntries(
                  Map.entry("schema", "account-control-ui-committed-signer/v1"),
                  Map.entry("kid", "test-only-control"),
                  Map.entry("signerGeneration", "1"),
                  Map.entry("environmentId", pin.environmentId()),
                  Map.entry("clusterId", pin.clusterId()),
                  Map.entry("clusterIncarnationUid", pin.clusterIncarnationUid()),
                  Map.entry("namespace", pin.namespace()),
                  Map.entry("namespaceUid", pin.namespaceUid()),
                  Map.entry("publicConfigMapUid", pin.configMapUid()),
                  Map.entry("apiConfigRevision", pin.bindingRevision())));
      String tokenHash = "b".repeat(64);
      activeRegistry =
          AccountControlUiAuthority.canonical(
              Map.ofEntries(
                  Map.entry("profile", "control-ui"),
                  Map.entry("type", "control-ui"),
                  Map.entry("audience", "control-ui"),
                  Map.entry("state", "active"),
                  Map.entry("tokenHash", tokenHash),
                  Map.entry("jti", tokenJti.toString()),
                  Map.entry("operationId", operationId.toString()),
                  Map.entry("requestId", requestId.toString()),
                  Map.entry("accountId", accountId.toString()),
                  Map.entry("exp", expiresAt.getEpochSecond()),
                  Map.entry("kid", "test-only-control"),
                  Map.entry("signerGeneration", "1")));
      stored =
          recordWithIdentity(accountId, tenantId, tokenJti, "COMMITTED", operationId, tokenHash);
      AccountControlUiSignerOwner.Capture currentSigner =
          mock(AccountControlUiSignerOwner.Capture.class);
      when(currentSigner.kid()).thenReturn("test-only-control");
      when(currentSigner.generation()).thenReturn("1");
      when(signers.requireOriginal(signerReceipt)).thenReturn(currentSigner);
      when(publicSource.sourceIdentity()).thenReturn(pin);
      when(registry.readActive(tokenHash))
          .thenAnswer(
              ignored -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return activeRegistry.clone();
              });
      when(authority.capture(accountId, tenantId, environment)).thenReturn(snapshot);
      when(transactions.getTransaction(any()))
          .thenAnswer(
              ignored -> {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                return new SimpleTransactionStatus();
              });
      doAnswer(
              ignored -> {
                TransactionSynchronizationManager.clear();
                return null;
              })
          .when(transactions)
          .commit(any());
      doAnswer(
              ignored -> {
                TransactionSynchronizationManager.clear();
                return null;
              })
          .when(transactions)
          .rollback(any());
      actors =
          new AccountControlUiActorService(
              operations,
              authority,
              signers,
              registry,
              publicSource,
              mock(DraftAuthorizationFenceRepository.class),
              transactions,
              clock);
    }

    void stubCommitted() {
      when(operations.findCommittedByTokenJti(accountId, tenantId, tokenJti)).thenReturn(stored);
      when(operations.lockCommittedByTokenJti(stored)).thenReturn(stored);
      var committed = mock(AccountControlUiIssuanceRepository.Committed.class);
      when(committed.tokenHash()).thenReturn(stored.tokenHash);
      when(operations.requireCommitted(stored)).thenReturn(committed);
    }

    AccountPublicJwksCache.SourceIdentity differentPin() {
      return new AccountPublicJwksCache.SourceIdentity(
          "other-control",
          pin.clusterId(),
          pin.clusterIncarnationUid(),
          pin.namespace(),
          pin.namespaceUid(),
          pin.configMapUid(),
          pin.bindingRevision(),
          pin.apiServerOrigin(),
          pin.servingCaSha256());
    }

    AccountControlUiIssuanceRepository.Stored recordWithIdentity(
        UUID rowAccountId, UUID rowTenantId, UUID rowJti, String status) {
      return recordWithIdentity(rowAccountId, rowTenantId, rowJti, status, operationId);
    }

    AccountControlUiIssuanceRepository.Stored recordWithIdentity(
        UUID rowAccountId, UUID rowTenantId, UUID rowJti, String status, UUID rowOperationId) {
      return recordWithIdentity(
          rowAccountId, rowTenantId, rowJti, status, rowOperationId, "b".repeat(64));
    }

    private AccountControlUiIssuanceRepository.Stored recordWithIdentity(
        UUID rowAccountId,
        UUID rowTenantId,
        UUID rowJti,
        String status,
        UUID rowOperationId,
        String tokenHash) {
      Map<String, Object> values = new HashMap<>();
      values.put("request_id", requestId);
      values.put("operation_id", rowOperationId);
      values.put("token_jti", rowJti);
      values.put("account_uuid", rowAccountId);
      values.put("tenant_uuid", rowTenantId);
      values.put("caller_context_id", UUID.randomUUID());
      values.put("caller_workload", "test-only-peer");
      values.put("request_mac_key_id", "test-only-mac");
      values.put("request_digest", "a".repeat(64));
      values.put("status", status);
      values.put("token_hash", tokenHash);
      values.put("claims_payload", AccountControlUiAuthority.canonical(claims));
      values.put("source_payload", new byte[] {7});
      values.put("bundle_payload", new byte[] {8});
      values.put("signer_receipt", signerReceipt);
      values.put("pending_registry", new byte[] {11});
      values.put("active_registry", activeRegistry);
      values.put("issued_at_epoch_second", issuedAt.getEpochSecond());
      values.put("expires_at_epoch_second", expiresAt.getEpochSecond());
      values.put(
          "recovery_expires_at",
          OffsetDateTime.ofInstant(expiresAt.plusSeconds(60), ZoneOffset.UTC));
      Record row = mock(Record.class);
      when(row.get(anyString(), any(Class.class)))
          .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
      return new AccountControlUiIssuanceRepository.Stored(row);
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    MutableClock(Instant initial) {
      this(initial, ZoneOffset.UTC);
    }

    private MutableClock(Instant initial, ZoneId zone) {
      instant = new AtomicReference<>(initial);
      this.zone = zone;
    }

    void set(Instant value) {
      instant.set(value);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
      return new MutableClock(instant(), requestedZone);
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
