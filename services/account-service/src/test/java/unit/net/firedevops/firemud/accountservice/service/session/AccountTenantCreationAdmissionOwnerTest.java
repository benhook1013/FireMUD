package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.AcceptedSignerKey;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.AccountAuthenticatedActor;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRedisClient.QualifiedReceipt;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.PreparedAdmission;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Controlled owner-composition proof only; no external Redis, JWKS, database, or dispatch proof.
 */
class AccountTenantCreationAdmissionOwnerTest {
  private static final String JWT = "controlled-current-account-jwt";

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void wrongFreshAcceptedKeyCannotReachInitialReceipt() {
    var fixture = new Fixture();
    when(fixture.actors.readAcceptedSignerKey("original-kid"))
        .thenReturn(new AcceptedSignerKey("original-kid", "sha256:" + "f".repeat(64)));

    assertThatThrownBy(fixture::start).isInstanceOf(IllegalStateException.class);

    assertThat(fixture.commits).isEqualTo(2);
    verify(fixture.actors).readAcceptedSignerKey("original-kid");
    verify(fixture.redis, never()).withInitialReceipt(any(), any(), any());
    verify(fixture.admissions, never()).admitLocked(any(), any());
    verifyNoInteractions(fixture.capture, fixture.orders, fixture.preparation);
  }

  @Test
  void lostQualificationAfterOwnerCaptureRollsBackFinalAdmissionTransaction() {
    var fixture = new Fixture();
    var qualificationChecks = new AtomicInteger();
    doAnswer(
            ignored -> {
              if (qualificationChecks.incrementAndGet() == 3) {
                throw new IllegalStateException("Controlled qualification loss");
              }
              return null;
            })
        .when(fixture.qualified)
        .requireCurrent();
    fixture.allowInitialReceipt();
    fixture.allowAdmissionUntilQualificationLoss();

    assertThatThrownBy(fixture::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Controlled qualification loss");

    assertThat(qualificationChecks).hasValue(3);
    assertThat(fixture.commits).isEqualTo(2);
    assertThat(fixture.rollbacks).isEqualTo(1);
    verify(fixture.admissions).admitLocked(fixture.prepared, fixture.qualified);
    verify(fixture.capture)
        .captureLocked(fixture.intent, fixture.admitted, fixture.environment, fixture.reauth);
    verify(fixture.orders).acquireLocked(fixture.intent, fixture.admitted, fixture.sources);
    verify(fixture.preparation, never()).prepare(any(), any(), any());
  }

  @Test
  void freshAdmissionFreezesDatabaseDeadlinesAndExactRetryKeepsThemAfterClockAdvances() {
    var fixture = new Fixture();
    OffsetDateTime firstDatabaseTime = fixture.now.plusSeconds(10).atOffset(ZoneOffset.UTC);
    fixture.enableFreshAdmission(firstDatabaseTime);
    fixture.allowInitialReceipt();

    var first = fixture.start();

    var frozen = first.admission();
    long preparedAt = firstDatabaseTime.toInstant().toEpochMilli();
    assertThat(frozen.preparedAtEpochMillis()).isEqualTo(preparedAt);
    assertThat(frozen.tokenExpiresAtEpochMillis())
        .isEqualTo(fixture.issuance.expiresAt.toEpochMilli());
    assertThat(frozen.tokenCleanupDeadlineEpochMillis()).isEqualTo(fixture.issuance.expiryMillis());
    assertThat(frozen.operationDeadlineEpochMillis())
        .isEqualTo(
            Math.min(
                frozen.tokenExpiresAtEpochMillis(),
                preparedAt + AccountTenantCreationAdmissionBinding.MAX_OPERATION_MILLIS));
    assertThat(frozen.operationDeadlineEpochMillis()).isGreaterThan(preparedAt);
    assertThat(frozen.operationDeadlineEpochMillis() - preparedAt)
        .isLessThanOrEqualTo(AccountTenantCreationAdmissionBinding.MAX_OPERATION_MILLIS);
    assertThat(frozen.receiptCleanupDeadlineEpochMillis() - frozen.operationDeadlineEpochMillis())
        .isEqualTo(AccountTenantCreationAdmissionBinding.RECEIPT_RETENTION_MILLIS);

    int firstCommit = fixture.events.indexOf("commit:1");
    int independentOriginalReadback = fixture.events.indexOf("unqualified-sql-readback");
    int acceptedKeyRead = fixture.events.indexOf("accepted-key-read");
    int firstRedisCall = fixture.events.indexOf("initial-redis");
    assertThat(firstCommit).isGreaterThanOrEqualTo(0).isLessThan(independentOriginalReadback);
    assertThat(independentOriginalReadback).isLessThan(firstRedisCall);
    assertThat(fixture.events.indexOf("commit:2")).isLessThan(firstRedisCall);
    assertThat(independentOriginalReadback).isLessThan(acceptedKeyRead);
    assertThat(acceptedKeyRead).isLessThan(firstRedisCall);
    assertThat(fixture.clockReads).isEqualTo(1);
    assertThat(fixture.admitTransactionId).isEqualTo(fixture.ownerCaptureTransactionId);
    assertThat(fixture.admitTransactionId).isEqualTo(fixture.sourceOrderTransactionId);
    assertThat(fixture.events.indexOf("admit")).isLessThan(fixture.events.indexOf("owner-capture"));
    assertThat(fixture.events.indexOf("owner-capture"))
        .isLessThan(fixture.events.indexOf("source-order-acquire"));
    assertThat(fixture.events.indexOf("qualified-commit:3"))
        .isLessThan(fixture.events.indexOf("source-preparation-readback"));
    assertThat(fixture.events.indexOf("source-preparation-readback"))
        .isLessThan(fixture.events.indexOf("qualified-sql-readback"));
    assertThat(fixture.events.indexOf("qualified-sql-readback"))
        .isLessThan(fixture.events.indexOf("qualified-commit:4"));
    assertThat(fixture.preRedisReadbackTransactionId)
        .isNotEqualTo(fixture.finalReadbackTransactionId);

    fixture.databaseClock = fixture.now.plusSeconds(90).atOffset(ZoneOffset.UTC);
    assertThat(fixture.databaseClock.toInstant().toEpochMilli())
        .isGreaterThan(frozen.preparedAtEpochMillis())
        .isLessThan(frozen.operationDeadlineEpochMillis());
    var retry = fixture.start();

    assertThat(fixture.clockReads).isEqualTo(1);
    assertThat(retry.admission().canonicalReceipt()).containsExactly(frozen.canonicalReceipt());
    assertThat(fixture.initialReceiptBindings).hasSize(2);
    assertThat(fixture.initialReceiptBindings.get(1).canonicalReceipt())
        .containsExactly(frozen.canonicalReceipt());
    assertThat(retry.admission().preparedAtEpochMillis()).isEqualTo(frozen.preparedAtEpochMillis());
    assertThat(retry.admission().operationDeadlineEpochMillis())
        .isEqualTo(frozen.operationDeadlineEpochMillis());
    assertThat(retry.admission().receiptCleanupDeadlineEpochMillis())
        .isEqualTo(frozen.receiptCleanupDeadlineEpochMillis());
  }

  @Test
  void changedRequestCannotRebindAnExistingPreparedAdmissionOrReachRedis() {
    var fixture = new Fixture();
    var changedRequest =
        new AccountTenantCreationIntentRepository.Request(
            fixture.request.requestId(),
            fixture.request.creationOperationId(),
            fixture.request.gameDesignCreationRequestId(),
            fixture.request.initiatingAccountId(),
            fixture.request.targetNamespace(),
            fixture.request.sourceGameTenantKey(),
            "Changed game name",
            fixture.request.description(),
            fixture.request.recentReauthenticationEvidenceId(),
            fixture.request.creatorPartyId(),
            fixture.request.consentOperationId(),
            fixture.request.consentStatementVersion(),
            fixture.request.affirmativeConsent());
    when(fixture.intents.claim(changedRequest)).thenReturn(fixture.intent);

    assertThatThrownBy(() -> fixture.start(JWT, changedRequest))
        .isInstanceOf(IllegalStateException.class);

    assertThat(fixture.rollbacks).isEqualTo(1);
    verify(fixture.admissions, never()).prepareLocked(any(), any(), any());
    verify(fixture.redis, never()).withInitialReceipt(any(), any(), any());
  }

  @Test
  void changedTokenCannotRebindAnExistingPreparedAdmissionOrReachRedis() {
    var fixture = new Fixture();
    String changedJwt = "different-account-token";
    String changedTokenHash = "b".repeat(64);
    UUID changedIssuanceId = UUID.randomUUID();
    var changedActor = mock(AccountAuthenticatedActor.class);
    when(changedActor.accountId()).thenReturn(fixture.accountId);
    when(changedActor.issuanceOperationId()).thenReturn(changedIssuanceId);
    when(changedActor.tokenHash()).thenReturn(changedTokenHash);
    var changedIssuanceRow = new HashMap<>(fixture.issuanceRow);
    changedIssuanceRow.put("token_hash", changedTokenHash);
    changedIssuanceRow.put("operation_id", changedIssuanceId);
    changedIssuanceRow.put("token_jti", UUID.randomUUID());
    changedIssuanceRow.put("active_registry", new byte[] {31, 32});
    var changedIssuance =
        new AccountControlUiIssuanceRepository.Stored(Fixture.row(changedIssuanceRow));
    when(fixture.issuances.findToken(changedTokenHash)).thenReturn(changedIssuance);
    when(fixture.actors.withCurrentAccount(eq(changedJwt), any()))
        .thenAnswer(
            invocation -> {
              Function<AccountAuthenticatedActor, Object> action = invocation.getArgument(1);
              return new TransactionTemplate(fixture.transactionManager)
                  .execute(ignored -> action.apply(changedActor));
            });

    assertThatThrownBy(() -> fixture.start(changedJwt, fixture.request))
        .isInstanceOf(IllegalStateException.class);

    assertThat(fixture.rollbacks).isEqualTo(1);
    verify(fixture.admissions).readPrepared(fixture.operation);
    verify(fixture.admissions, never()).prepareLocked(any(), any(), any());
    verify(fixture.redis, never()).withInitialReceipt(any(), any(), any());
  }

  @Test
  void recoveryUsesOnlyExistingReceiptAfterPersistedAdmissionAndReturnsRedactedData() {
    var fixture = new Fixture("REVOKED");
    fixture.allowExistingReceipt();
    when(fixture.preparation.prepare(fixture.operation, fixture.environment, fixture.reauth))
        .thenReturn(fixture.sourceReadback);

    AccountTenantCreationAdmissionOwner.PreparedResult result =
        fixture.owner.recover(fixture.operation, fixture.environment, fixture.reauth);

    assertThat(fixture.commits).isEqualTo(2);
    assertThat(result.admission()).isEqualTo(fixture.binding);
    assertThat(result.sourceOrderBinding()).containsExactly(8, 9, 10);
    byte[] changed = result.sourceOrderBinding();
    changed[0] = 0;
    assertThat(result.sourceOrderBinding()).containsExactly(8, 9, 10);
    assertThat(result.toString()).contains("data-only", "redacted");
    assertThat(result.toString()).doesNotContain(JWT, fixture.tokenHash);
    verify(fixture.redis).withExistingReceipt(eq(fixture.binding), any());
    verify(fixture.redis, never()).withInitialReceipt(any(), any(), any());
    verify(fixture.admissions, never()).prepareLocked(any(), any(), any());
    verify(fixture.admissions, never()).admitLocked(any(), any());
    verifyNoInteractions(fixture.actors);
  }

  @Test
  void missingPersistedAdmissionCannotEnterEitherRedisReceiptMode() {
    var fixture = new Fixture();
    when(fixture.admissions.readPrepared(fixture.operation)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> fixture.owner.recover(fixture.operation, fixture.environment, fixture.reauth))
        .isInstanceOf(IllegalStateException.class);

    assertThat(fixture.commits).isZero();
    verify(fixture.redis, never()).withExistingReceipt(any(), any());
    verify(fixture.redis, never()).withInitialReceipt(any(), any(), any());
    verifyNoInteractions(fixture.actors, fixture.preparation);
  }

  private static final class Fixture {
    final Instant now = Instant.parse("2026-11-01T00:00:00Z");
    final UUID requestId = UUID.randomUUID();
    final UUID operation = UUID.randomUUID();
    final UUID issuanceId = UUID.randomUUID();
    final UUID jti = UUID.randomUUID();
    final UUID accountId = UUID.randomUUID();
    final UUID gameDesignRequestId = UUID.randomUUID();
    final UUID evidenceId = UUID.randomUUID();
    final UUID partyId = UUID.randomUUID();
    final UUID consentOperationId = UUID.randomUUID();
    final String tokenHash = "a".repeat(64);
    final byte[] claims = new byte[] {1, 2, 3};
    final byte[] activeRegistry = new byte[] {4, 5, 6};
    final byte[] signerReceipt =
        AccountControlUiAuthority.canonical(
            Map.of("kid", "original-kid", "publicKeyFingerprint", "sha256:" + "a".repeat(64)));
    final String gameDesignDigest = "sha256:" + "b".repeat(64);
    final String intentDigest = "sha256:" + "c".repeat(64);
    private static final String NAMESPACE = "example";
    final byte[] intentPayload = new byte[] {20, 21};
    final byte[] sourceOrderPayload = new byte[] {8, 9, 10};
    final String sourceOrderDigest = DraftAuthorizationFenceBinding.digest(sourceOrderPayload);
    final AccountTenantCreationIntentRepository.Request request =
        new AccountTenantCreationIntentRepository.Request(
            requestId,
            operation,
            gameDesignRequestId,
            accountId,
            NAMESPACE,
            "source-key",
            "Example Game",
            null,
            evidenceId,
            partyId,
            consentOperationId,
            1,
            true);
    final OriginalIntent intent =
        new OriginalIntent(
            requestId,
            operation,
            gameDesignRequestId,
            accountId,
            NAMESPACE,
            "source-key",
            "Example Game",
            null,
            evidenceId,
            partyId,
            consentOperationId,
            1,
            true,
            gameDesignDigest,
            intentPayload,
            intentDigest,
            now);
    final AccountRecentReauthenticationEvidence reauth =
        new AccountRecentReauthenticationEvidence(
            evidenceId,
            UUID.randomUUID(),
            accountId,
            AccountRecentReauthenticationPolicy.PURPOSE,
            operation,
            intentDigest,
            AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT,
            1L,
            AccountLifecycleState.ACTIVE,
            1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            "effective-policy-v1",
            300L,
            30L,
            now);
    final CapturedEnvironmentBoundary environment = mock(CapturedEnvironmentBoundary.class);
    final DSLContext dsl = mock(DSLContext.class);
    final AccountTenantCreationIntentRepository intents =
        mock(AccountTenantCreationIntentRepository.class);
    final AccountTenantCreationAdmissionRepository admissions =
        mock(AccountTenantCreationAdmissionRepository.class);
    final AccountControlUiIssuanceRepository issuances =
        mock(AccountControlUiIssuanceRepository.class);
    final AccountControlUiActorService actors = mock(AccountControlUiActorService.class);
    final AccountRecentReauthenticationService reauthentication =
        mock(AccountRecentReauthenticationService.class);
    final AccountTenantCreationAdmissionRedisClient redis =
        mock(AccountTenantCreationAdmissionRedisClient.class);
    final AccountTenantCreationOwnerSourceCapture capture =
        mock(AccountTenantCreationOwnerSourceCapture.class);
    final AccountTenantCreationSourceOrderRepository orders =
        mock(AccountTenantCreationSourceOrderRepository.class);
    final AccountTenantCreationAuthorizationPreparation preparation =
        mock(AccountTenantCreationAuthorizationPreparation.class);
    final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    final AccountAuthenticatedActor actor = mock(AccountAuthenticatedActor.class);
    final AccountTenantCreationOwnerSourceCapture.Captured captured =
        mock(AccountTenantCreationOwnerSourceCapture.Captured.class);
    final AccountTenantCreationSourceOrderBinding sourceHolder =
        mock(AccountTenantCreationSourceOrderBinding.class);
    final AccountTenantCreationAuthorizationPreparation.Prepared sourceReadback =
        mock(AccountTenantCreationAuthorizationPreparation.Prepared.class);
    final PreparedAdmission prepared = mock(PreparedAdmission.class);
    final Admitted admitted = mock(Admitted.class);
    final QualifiedReceipt qualified = mock(QualifiedReceipt.class);
    final AccountControlUiIssuanceRepository.Stored issuance;
    AccountTenantCreationAdmissionBinding binding;
    final List<SourceEvidence> sources;
    final List<String> events = new ArrayList<>();
    final List<AccountTenantCreationAdmissionBinding> initialReceiptBindings = new ArrayList<>();
    final AtomicBoolean qualificationHeld = new AtomicBoolean();
    final Map<String, Object> issuanceRow = new HashMap<>();
    final AccountTenantCreationAdmissionOwner owner;
    int begins;
    int commits;
    int rollbacks;
    int currentTransactionId;
    int clockReads;
    int admitTransactionId;
    int ownerCaptureTransactionId;
    int sourceOrderTransactionId;
    int preRedisReadbackTransactionId;
    int finalReadbackTransactionId;
    boolean preparedRetained;
    boolean admissionRetained;
    OffsetDateTime databaseClock;

    Fixture() {
      this("COMMITTED");
    }

    Fixture(String status) {
      long issuedAt = now.getEpochSecond();
      long expiresAt = now.plusSeconds(360).getEpochSecond();
      long tokenCleanupDeadline = now.plusSeconds(600).toEpochMilli();
      issuanceRow.put("request_id", UUID.randomUUID());
      issuanceRow.put("operation_id", issuanceId);
      issuanceRow.put("token_jti", jti);
      issuanceRow.put("account_uuid", accountId);
      issuanceRow.put("authorization_scope", "ACCOUNT");
      issuanceRow.put("tenant_uuid", null);
      issuanceRow.put("caller_context_id", UUID.randomUUID());
      issuanceRow.put("caller_workload", "firemud-account-service");
      issuanceRow.put("request_mac_key_id", "request-key");
      issuanceRow.put("request_digest", "sha256:" + "d".repeat(64));
      issuanceRow.put("status", status);
      issuanceRow.put("token_hash", tokenHash);
      issuanceRow.put("claims_payload", claims);
      issuanceRow.put("source_payload", new byte[] {7});
      issuanceRow.put("bundle_payload", new byte[] {8});
      issuanceRow.put("signer_receipt", signerReceipt);
      issuanceRow.put("pending_registry", new byte[] {9});
      issuanceRow.put("active_registry", activeRegistry);
      issuanceRow.put("issued_at_epoch_second", issuedAt);
      issuanceRow.put("expires_at_epoch_second", expiresAt);
      issuanceRow.put("registry_cleanup_deadline_epoch_millis", tokenCleanupDeadline);
      issuanceRow.put("recovery_expires_at", now.plusSeconds(600).atOffset(ZoneOffset.UTC));
      issuance = new AccountControlUiIssuanceRepository.Stored(row(issuanceRow));
      binding =
          new AccountTenantCreationAdmissionBinding(
              requestId,
              operation,
              issuanceId,
              accountId,
              NAMESPACE,
              gameDesignRequestId,
              gameDesignDigest,
              intentDigest,
              AccountControlUiIssuanceRepository.hash(activeRegistry),
              evidenceId,
              tokenHash,
              tokenCleanupDeadline,
              now.plusSeconds(360).toEpochMilli(),
              now.toEpochMilli(),
              now.plusSeconds(300).toEpochMilli(),
              now.plusSeconds(300).toEpochMilli()
                  + AccountTenantCreationAdmissionBinding.RECEIPT_RETENTION_MILLIS);
      when(prepared.binding()).thenReturn(binding);
      when(admitted.binding()).thenReturn(binding);
      when(admitted.receiptPayload()).thenReturn(binding.canonicalReceipt());
      when(admitted.receiptDigest()).thenReturn("receipt-digest");
      when(admitted.localAofCount()).thenReturn(1L);
      when(admitted.replicaAofCount()).thenReturn(1L);
      when(admitted.protectedBindingId()).thenReturn("controlled-binding");
      when(admitted.qualificationFence()).thenReturn(1L);
      when(admitted.admittedAt()).thenReturn(now);

      sources = exactSortedSources();
      byte[] holderBytes = new byte[] {11, 12};
      when(sourceHolder.intentPayload()).thenReturn(intentPayload);
      when(sourceHolder.intentDigest()).thenReturn(intentDigest);
      when(sourceHolder.admissionPayload()).thenReturn(binding.canonicalReceipt());
      when(sourceHolder.admissionDigest()).thenReturn("receipt-digest");
      when(sourceHolder.creationOperationId()).thenReturn(operation);
      when(sourceHolder.canonicalBytes()).thenReturn(holderBytes);
      when(sourceHolder.authorizationDigest())
          .thenReturn(DraftAuthorizationFenceBinding.digest(holderBytes));
      when(captured.originalClaims()).thenReturn(claims);
      when(captured.originalSignerReceipt()).thenReturn(signerReceipt);
      when(captured.issuanceOperationId()).thenReturn(issuanceId);
      when(captured.tokenJti()).thenReturn(jti);
      when(captured.tokenHash()).thenReturn(tokenHash);
      when(captured.sources()).thenReturn(sources);

      when(transactionManager.getTransaction(any()))
          .thenAnswer(
              invocation -> {
                TransactionDefinition definition = invocation.getArgument(0);
                if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {
                  assertThat(definition.getIsolationLevel())
                      .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                }
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
                currentTransactionId = ++begins;
                events.add("begin:" + currentTransactionId);
                return mock(TransactionStatus.class);
              });
      doAnswer(
              ignored -> {
                events.add("commit:" + currentTransactionId);
                if (qualificationHeld.get()) {
                  events.add("qualified-commit:" + currentTransactionId);
                }
                commits++;
                TransactionSynchronizationManager.clear();
                return null;
              })
          .when(transactionManager)
          .commit(any());
      doAnswer(
              ignored -> {
                events.add("rollback:" + currentTransactionId);
                rollbacks++;
                TransactionSynchronizationManager.clear();
                return null;
              })
          .when(transactionManager)
          .rollback(any());

      when(actors.withCurrentAccount(eq(JWT), any()))
          .thenAnswer(
              invocation -> {
                events.add("account-actor");
                Function<AccountAuthenticatedActor, Object> action = invocation.getArgument(1);
                return new TransactionTemplate(transactionManager)
                    .execute(ignored -> action.apply(actor));
              });
      when(actor.accountId()).thenReturn(accountId);
      when(actor.issuanceOperationId()).thenReturn(issuanceId);
      when(actor.tokenHash()).thenReturn(tokenHash);
      when(intents.claim(request)).thenReturn(intent);
      doAnswer(
              ignored -> {
                events.add(
                    qualificationHeld.get()
                        ? "qualified-sql-readback"
                        : "unqualified-sql-readback");
                if (qualificationHeld.get()) {
                  finalReadbackTransactionId = currentTransactionId;
                } else {
                  preRedisReadbackTransactionId = currentTransactionId;
                }
                return Optional.of(intent);
              })
          .when(intents)
          .readOriginal(operation);
      when(reauthentication.validatePendingCreationLocked(
              reauth, accountId, AccountRecentReauthenticationPolicy.PURPOSE, operation))
          .thenReturn(reauth);
      when(issuances.findToken(tokenHash)).thenReturn(issuance);
      when(admissions.readPrepared(operation)).thenReturn(Optional.of(prepared));
      when(admissions.prepareLocked(intent, actor, binding)).thenReturn(prepared);
      when(admissions.readAdmitted(operation)).thenReturn(Optional.of(admitted));
      when(capture.captureLocked(intent, admitted, environment, reauth)).thenReturn(captured);
      when(orders.acquireLocked(eq(intent), eq(admitted), any())).thenReturn(sourceHolder);
      when(orders.readSettlement(operation)).thenReturn(Optional.empty());
      when(sourceReadback.binding()).thenReturn(sourceOrderPayload);
      when(sourceReadback.digest()).thenReturn(sourceOrderDigest);

      owner =
          new AccountTenantCreationAdmissionOwner(
              NAMESPACE,
              dsl,
              intents,
              admissions,
              issuances,
              actors,
              reauthentication,
              redis,
              capture,
              orders,
              preparation,
              transactionManager);
    }

    AccountTenantCreationAdmissionOwner.PreparedResult start() {
      return start(JWT, request);
    }

    AccountTenantCreationAdmissionOwner.PreparedResult start(
        String jwt, AccountTenantCreationIntentRepository.Request originalRequest) {
      return owner.start(jwt, originalRequest, reauth, environment);
    }

    void allowInitialReceipt() {
      doAnswer(
              ignored -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                events.add("accepted-key-read");
                return new AcceptedSignerKey("original-kid", "sha256:" + "a".repeat(64));
              })
          .when(actors)
          .readAcceptedSignerKey("original-kid");
      doAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(invocation.<byte[]>getArgument(1)).containsExactly(activeRegistry);
                AccountTenantCreationAdmissionBinding supplied = invocation.getArgument(0);
                assertThat(supplied.canonicalReceipt()).containsExactly(binding.canonicalReceipt());
                initialReceiptBindings.add(supplied);
                events.add("initial-redis");
                assertThat(commits).isGreaterThanOrEqualTo(2);
                assertThat(events).contains("unqualified-sql-readback");
                Function<QualifiedReceipt, AccountTenantCreationAdmissionOwner.PreparedResult>
                    callback = invocation.getArgument(2);
                qualificationHeld.set(true);
                try {
                  var result = callback.apply(qualified);
                  qualified.requireCurrent();
                  return result;
                } finally {
                  qualificationHeld.set(false);
                }
              })
          .when(redis)
          .withInitialReceipt(any(), any(byte[].class), any());
    }

    void enableFreshAdmission(OffsetDateTime initialDatabaseClock) {
      databaseClock = initialDatabaseClock;
      doAnswer(
              ignored -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                clockReads++;
                events.add("database-clock");
                return row(Map.of("prepared_at", databaseClock));
              })
          .when(dsl)
          .fetchOne("SELECT clock_timestamp() AS prepared_at");

      doAnswer(ignored -> preparedRetained ? Optional.of(prepared) : Optional.empty())
          .when(admissions)
          .readPrepared(operation);
      doAnswer(ignored -> admissionRetained ? Optional.of(admitted) : Optional.empty())
          .when(admissions)
          .readAdmitted(operation);
      doAnswer(
              invocation -> {
                binding = invocation.getArgument(2);
                preparedRetained = true;
                events.add("prepare-admission");
                return prepared;
              })
          .when(admissions)
          .prepareLocked(eq(intent), eq(actor), any());
      doAnswer(ignored -> binding).when(prepared).binding();
      doAnswer(ignored -> binding).when(admitted).binding();
      doAnswer(ignored -> binding.canonicalReceipt()).when(admitted).receiptPayload();
      doAnswer(ignored -> binding.canonicalReceipt()).when(sourceHolder).admissionPayload();
      doAnswer(
              ignored -> AccountControlUiCoordination.digest("SHA-256", binding.canonicalReceipt()))
          .when(admitted)
          .receiptDigest();
      doAnswer(
              ignored -> AccountControlUiCoordination.digest("SHA-256", binding.canonicalReceipt()))
          .when(sourceHolder)
          .admissionDigest();

      doAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                admitTransactionId = currentTransactionId;
                events.add("admit");
                ((QualifiedReceipt) invocation.getArgument(1)).requireCurrent();
                admissionRetained = true;
                return admitted;
              })
          .when(admissions)
          .admitLocked(any(), any());
      doAnswer(
              ignored -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                ownerCaptureTransactionId = currentTransactionId;
                events.add("owner-capture");
                return captured;
              })
          .when(capture)
          .captureLocked(intent, admitted, environment, reauth);
      doAnswer(
              ignored -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                sourceOrderTransactionId = currentTransactionId;
                events.add("source-order-acquire");
                return sourceHolder;
              })
          .when(orders)
          .acquireLocked(eq(intent), eq(admitted), any());
      doAnswer(
              ignored -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(qualificationHeld.get()).isTrue();
                events.add("source-preparation-readback");
                return sourceReadback;
              })
          .when(preparation)
          .prepare(operation, environment, reauth);
      doAnswer(
              ignored -> {
                assertThat(qualificationHeld.get()).isTrue();
                events.add("qualification-check");
                return null;
              })
          .when(qualified)
          .requireCurrent();
    }

    void allowAdmissionUntilQualificationLoss() {
      doAnswer(
              invocation -> {
                QualifiedReceipt receipt = invocation.getArgument(1);
                receipt.requireCurrent();
                return admitted;
              })
          .when(admissions)
          .admitLocked(prepared, qualified);
    }

    void allowExistingReceipt() {
      doAnswer(
              invocation -> {
                Function<QualifiedReceipt, AccountTenantCreationAdmissionOwner.PreparedResult>
                    callback = invocation.getArgument(1);
                events.add("existing-redis");
                qualificationHeld.set(true);
                try {
                  var result = callback.apply(qualified);
                  qualified.requireCurrent();
                  return result;
                } finally {
                  qualificationHeld.set(false);
                }
              })
          .when(redis)
          .withExistingReceipt(any(), any());
    }

    private List<SourceEvidence> exactSortedSources() {
      List<SourceEvidence> current = new ArrayList<>();
      current.add(source(SourceKind.ACCOUNT, accountId.toString(), "1", "1"));
      current.add(source(SourceKind.CREATOR_PARTY, partyId.toString(), "1", "1"));
      current.add(source(SourceKind.GLOBAL_ROLES, accountId.toString(), "1", "1"));
      current.add(source(SourceKind.HOSTED_TERMS, "environment-boundary/test", "1", "1"));
      current.add(source(SourceKind.HOSTED_TERMS, UUID.randomUUID().toString(), "1", "1"));
      current.add(source(SourceKind.ISSUER, "firemud-account-service", "1", "1"));
      current.add(
          new SourceEvidence(
              SourceKind.RECENT_REAUTH_POLICY,
              "SELF_SERVICE_GAME_CREATION/DEPLOYMENT",
              null,
              "1",
              null,
              null,
              new byte[] {1}));
      current.add(
          new SourceEvidence(
              SourceKind.RECENT_REAUTH_POLICY,
              "SELF_SERVICE_GAME_CREATION/PLATFORM",
              null,
              "1",
              null,
              null,
              new byte[] {1}));
      return current.stream().sorted(java.util.Comparator.comparing(SourceEvidence::key)).toList();
    }

    private SourceEvidence source(
        SourceKind kind, String scope, String generation, String version) {
      return new SourceEvidence(kind, scope, generation, version, null, null, new byte[] {1});
    }

    private static Record row(Map<String, Object> values) {
      Record row = mock(Record.class);
      when(row.get(any(String.class), org.mockito.ArgumentMatchers.<Class<Object>>any()))
          .thenAnswer(invocation -> values.get(invocation.getArgument(0, String.class)));
      return row;
    }
  }
}
