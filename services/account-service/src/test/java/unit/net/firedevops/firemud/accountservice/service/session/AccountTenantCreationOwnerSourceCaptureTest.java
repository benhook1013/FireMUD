package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftSourceCompositionService;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.RestrictionState;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CurrentnessEvidence;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.EnvironmentBoundCurrentness;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsCatalogVersion;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBinding;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository.CurrentProjection;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceSnapshot;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Conditional composition proof with controlled owner reads; no physical/current authority claim.
 */
class AccountTenantCreationOwnerSourceCaptureTest {
  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void actualAccountCaptureAndPublicEncodingsProduceClosedSortedImmutableVector() throws Exception {
    var f = new Fixture();
    var result = f.capture();
    assertThat(result.sources()).hasSize(8);
    assertThat(result.sources()).extracting(SourceEvidence::key).isSorted().doesNotHaveDuplicates();
    assertThat(result.sources())
        .extracting(SourceEvidence::kind)
        .doesNotContain(SourceKind.TENANT, SourceKind.MEMBERSHIP);
    assertThat(result.intent()).isSameAs(f.intent);
    assertThat(result.issuanceOperationId()).isEqualTo(f.issuanceId);
    assertThat(result.reauthentication()).isSameAs(f.recent);
    assertThatThrownBy(() -> result.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    byte[] original = result.originalClaims();
    original[0] ^= 1;
    assertThat(result.originalClaims()).isNotEqualTo(original);
    verify(f.reauth)
        .validatePendingCreationLocked(
            f.recent, f.owner.id, AccountRecentReauthenticationPolicy.PURPOSE, f.operation);
    verify(f.reauth, never())
        .validateAndConsumeLocked(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            anyString(),
            org.mockito.ArgumentMatchers.any());
    verify(f.dsl, never()).execute(anyString(), org.mockito.ArgumentMatchers.<Object[]>any());
  }

  @Test
  void ordinaryPostAdmissionRevocationDoesNotCancelOriginal() throws Exception {
    for (String status : List.of("REVOKING", "REVOKED")) {
      var f = new Fixture();
      f.issuance.put("status", status);
      assertThat(f.capture().tokenHash()).isEqualTo(f.tokenHash);
    }
  }

  @Test
  void changedPersistedAccountOperationTokenScopeAndSourceAreDenied() throws Exception {
    for (String field :
        List.of(
            "account_uuid",
            "operation_id",
            "token_hash",
            "authorization_scope",
            "source_payload",
            "active_registry")) {
      var f = new Fixture();
      Object changed =
          switch (field) {
            case "account_uuid", "operation_id" -> UUID.randomUUID();
            case "token_hash" -> "b".repeat(64);
            case "authorization_scope" -> "TENANT";
            default -> new byte[] {1};
          };
      f.issuance.put(field, changed);
      assertThatThrownBy(f::capture).isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void changedIntentAndMissingAdmissionFailBeforeTermsObservation() throws Exception {
    var f = new Fixture();
    f.intentRow.put("creator_party_id", UUID.randomUUID());
    assertThatThrownBy(f::capture).isInstanceOf(IllegalStateException.class);
    org.mockito.Mockito.verifyNoInteractions(f.terms);
    f = new Fixture();
    when(f.dsl.fetchOne(
            "SELECT * FROM account_tenant_creation_admissions WHERE creation_operation_id = ?",
            f.operation))
        .thenReturn(null);
    assertThatThrownBy(f::capture).isInstanceOf(IllegalStateException.class);
    org.mockito.Mockito.verifyNoInteractions(f.terms);
  }

  @Test
  void changedClaimGenerationAndCurrentRoleSourceAreDenied() throws Exception {
    var f = new Fixture();
    var claims =
        new HashMap<>(
            AccountControlUiIssuanceRepository.object((byte[]) f.issuance.get("claims_payload")));
    var tuple = new HashMap<String, Object>();
    ((Map<?, ?>) claims.get("authorityTuple"))
        .forEach((key, value) -> tuple.put(key.toString(), value));
    tuple.put("accountAuthorityGeneration", 2L);
    claims.put("authorityTuple", tuple);
    f.issuance.put("claims_payload", AccountControlUiAuthority.canonical(claims));
    assertThatThrownBy(f::capture).isInstanceOf(RuntimeException.class);
    f = new Fixture();
    when(f.owner.roleRow.get("global_role_source_version", Long.class)).thenReturn(8L);
    assertThatThrownBy(f::capture).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void wrongTermsPartyOrAccountAndExpiredOperationAreDenied() throws Exception {
    var f = new Fixture();
    when(f.terms.requireCurrentnessInOwnerTransaction(f.environment, f.party))
        .thenReturn(f.currentness(UUID.randomUUID(), f.owner.id));
    assertThatThrownBy(f::capture).isInstanceOf(IllegalStateException.class);
    f = new Fixture();
    when(f.terms.requireCurrentnessInOwnerTransaction(f.environment, f.party))
        .thenReturn(f.currentness(f.party, UUID.randomUUID()));
    assertThatThrownBy(f::capture).isInstanceOf(IllegalStateException.class);
    f = new Fixture();
    f.clock.put("observed_at", f.now.plusSeconds(120).atOffset(ZoneOffset.UTC));
    assertThatThrownBy(f::capture).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void stalePolicyAndReauthenticationBindingAreDeniedWithoutConsumption() throws Exception {
    var f = new Fixture();
    var platform = new AccountRecentReauthenticationPolicy.PlatformValue(2L, 300L, 30L);
    var deployment = new AccountRecentReauthenticationPolicy.DeploymentValue(1L, 300L, 30L);
    when(f.policies.resolveCurrentSourcesForGameCreation())
        .thenReturn(
            new AccountRecentReauthenticationPolicyRepository.CurrentSourcesForGameCreation(
                AccountRecentReauthenticationPolicy.resolve(platform, deployment),
                AccountRecentReauthenticationPolicy.sourceEvidence(platform, deployment)));
    assertThatThrownBy(f::capture).isInstanceOf(IllegalStateException.class);
    f = new Fixture();
    var wrong = f.evidence(UUID.randomUUID(), f.operation);
    var denied = f;
    assertThatThrownBy(
            () ->
                denied.capture.captureLocked(
                    denied.intent, denied.admitted, denied.environment, wrong))
        .isInstanceOf(IllegalStateException.class);
    org.mockito.Mockito.verifyNoInteractions(f.reauth);
    f = new Fixture();
    f.clock.put("observed_at", f.now.minusSeconds(1).atOffset(ZoneOffset.UTC));
    assertThatThrownBy(f::capture).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void exactTermsDeadlineAndStaleEvidenceAgeAreDenied() throws Exception {
    var f = new Fixture();
    when(f.terms.requireCurrentnessInOwnerTransaction(f.environment, f.party))
        .thenReturn(f.currentness(f.party, f.owner.id, f.now.plusSeconds(1)));
    assertThatThrownBy(f::capture).isInstanceOf(IllegalStateException.class);
    f = new Fixture();
    var stale =
        new AccountRecentReauthenticationEvidence(
            f.evidenceId,
            UUID.randomUUID(),
            f.owner.id,
            AccountRecentReauthenticationPolicy.PURPOSE,
            f.operation,
            "sha256:" + "a".repeat(64),
            AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT,
            7L,
            AccountLifecycleState.ACTIVE,
            1L,
            1L,
            4L,
            12L,
            1L,
            1L,
            f.policy.effectivePolicyVersion(),
            300L,
            30L,
            f.now.minusSeconds(270));
    when(f.reauth.validatePendingCreationLocked(
            f.recent, f.owner.id, AccountRecentReauthenticationPolicy.PURPOSE, f.operation))
        .thenReturn(stale);
    assertThatThrownBy(f::capture).isInstanceOf(IllegalStateException.class);
    f = new Fixture();
    when(f.reauth.validatePendingCreationLocked(
            f.recent, f.owner.id, AccountRecentReauthenticationPolicy.PURPOSE, f.operation))
        .thenReturn(f.evidence(f.evidenceId, UUID.randomUUID()));
    assertThatThrownBy(f::capture).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void ownerReauthenticationDenialAndIncompleteSourceVectorPropagate() throws Exception {
    var f = new Fixture();
    when(f.reauth.validatePendingCreationLocked(
            f.recent, f.owner.id, AccountRecentReauthenticationPolicy.PURPOSE, f.operation))
        .thenThrow(new IllegalStateException("consumed or stale"));
    assertThatThrownBy(f::capture).isInstanceOf(IllegalStateException.class);
    f = new Fixture();
    var current = f.currentness(f.party, f.owner.id);
    var sources = new ArrayList<>(current.sourceEvidence());
    sources.add(sources.getFirst());
    assertThatThrownBy(
            () ->
                new EnvironmentBoundCurrentness(
                    current.environment(), current.binding(), current.terms(), sources))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresWritableTransactionBeforeAnyOwnerRead() throws Exception {
    var f = new Fixture();
    TransactionSynchronizationManager.clear();
    assertThatThrownBy(() -> f.capture.captureLocked(f.intent, f.admitted, f.environment, f.recent))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> f.capture.captureLocked(f.intent, f.admitted, f.environment, f.recent))
        .isInstanceOf(IllegalStateException.class);
  }

  private static final class Fixture {
    final AccountCaptureContext owner = new AccountCaptureContext();
    final DSLContext dsl = mock(DSLContext.class);
    final AccountHostedTermsService terms = mock(AccountHostedTermsService.class);
    final CapturedEnvironmentBoundary environment = mock(CapturedEnvironmentBoundary.class);
    final AccountRecentReauthenticationService reauth =
        mock(AccountRecentReauthenticationService.class);
    final AccountRecentReauthenticationPolicyRepository policies =
        mock(AccountRecentReauthenticationPolicyRepository.class);
    final UUID operation = UUID.randomUUID(),
        party = UUID.randomUUID(),
        issuanceId = UUID.randomUUID();
    final UUID evidenceId = UUID.randomUUID(),
        intentId = UUID.randomUUID(),
        gdRequest = UUID.randomUUID();
    final String tokenHash = "a".repeat(64);
    final Instant now = Instant.parse("2026-11-01T00:00:00Z");
    final Map<String, Object> issuance = new HashMap<>(),
        intentRow = new HashMap<>(),
        clock = new HashMap<>();
    final AccountRecentReauthenticationPolicy.PlatformValue platform =
        new AccountRecentReauthenticationPolicy.PlatformValue(1L, 300L, 30L);
    final AccountRecentReauthenticationPolicy.DeploymentValue deployment =
        new AccountRecentReauthenticationPolicy.DeploymentValue(1L, 300L, 30L);
    final AccountRecentReauthenticationPolicy.Effective policy =
        AccountRecentReauthenticationPolicy.resolve(platform, deployment);
    final OriginalIntent intent;
    final AccountTenantCreationAdmissionRepository.Admitted admitted;
    final AccountRecentReauthenticationEvidence recent;
    final AccountTenantCreationOwnerSourceCapture capture;

    Fixture() throws Exception {
      var account = owner.capture();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      UUID consent = UUID.randomUUID();
      String gdDigest =
          GameTenantCreationDigest.requestDigest("example", gdRequest, "source", "Game", null);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      for (String value :
          List.of(
              "account-tenant-creation-intent-request/v1",
              "requestId",
              intentId.toString(),
              "creationOperationId",
              operation.toString(),
              "gameDesignCreationRequestId",
              gdRequest.toString(),
              "initiatingAccountId",
              owner.id.toString(),
              "targetNamespace",
              "example",
              "sourceGameTenantKey",
              "source",
              "name",
              "Game",
              "description",
              "ABSENT",
              "recentReauthenticationEvidenceId",
              evidenceId.toString(),
              "creatorPartyId",
              party.toString(),
              "consentOperationId",
              consent.toString(),
              "consentStatementVersion",
              "1",
              "affirmativeConsent",
              "true")) DraftAuthorizationFenceBinding.frame(bytes, value);
      String intentDigest =
          "sha256:" + AccountControlUiIssuanceRepository.hash(bytes.toByteArray());
      intent =
          new OriginalIntent(
              intentId,
              operation,
              gdRequest,
              owner.id,
              "example",
              "source",
              "Game",
              null,
              evidenceId,
              party,
              consent,
              1,
              true,
              gdDigest,
              bytes.toByteArray(),
              intentDigest,
              now);
      intentRow.putAll(
          Map.of(
              "request_id",
              intentId,
              "creation_operation_id",
              operation,
              "game_design_creation_request_id",
              gdRequest,
              "initiating_account_uuid",
              owner.id,
              "target_namespace",
              "example",
              "source_game_tenant_key",
              "source",
              "name",
              "Game",
              "recent_reauthentication_evidence_id",
              evidenceId,
              "creator_party_id",
              party,
              "consent_operation_id",
              consent));
      intentRow.putAll(
          Map.of(
              "consent_statement_version",
              1,
              "affirmative_consent",
              true,
              "game_design_request_digest",
              gdDigest,
              "request_payload",
              intent.requestPayload(),
              "request_digest",
              intentDigest));
      Record intentRecord = row(intentRow);
      when(dsl.fetchOne(
              "SELECT * FROM account_tenant_creation_original_intents WHERE creation_operation_id = ? AND request_id = ?",
              operation,
              intentId))
          .thenReturn(intentRecord);
      UUID jti = UUID.randomUUID();
      var claims =
          new AccountControlUiSigningSpec(
              issuanceId, UUID.randomUUID(), jti, account, now, now.plusSeconds(300));
      byte[] registry =
          AccountControlUiAuthority.canonical(Map.of("frozen", "original-active-registry"));
      issuance.putAll(
          Map.of(
              "request_id",
              claims.requestId(),
              "operation_id",
              issuanceId,
              "token_jti",
              jti,
              "account_uuid",
              owner.id,
              "authorization_scope",
              "ACCOUNT",
              "status",
              "COMMITTED",
              "token_hash",
              tokenHash,
              "claims_payload",
              AccountControlUiAuthority.canonical(claims.claims()),
              "source_payload",
              account.evidence(),
              "active_registry",
              registry));
      issuance.putAll(
          Map.of(
              "issued_at_epoch_second",
              now.getEpochSecond(),
              "expires_at_epoch_second",
              now.plusSeconds(300).getEpochSecond(),
              "registry_cleanup_deadline_epoch_millis",
              now.plusSeconds(300).toEpochMilli(),
              "recovery_expires_at",
              now.plusSeconds(300).atOffset(ZoneOffset.UTC),
              "committed_at",
              now.atOffset(ZoneOffset.UTC),
              "signer_receipt",
              AccountControlUiAuthority.canonical(
                  Map.of(
                      "kid",
                      "controlled-original-key",
                      "algorithm",
                      "RS256",
                      "publicKeyFingerprint",
                      "sha256:" + "a".repeat(64)))));
      Record issuanceRecord = row(issuance);
      when(dsl.fetchOne(
              "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ? FOR SHARE",
              issuanceId))
          .thenReturn(issuanceRecord);
      var binding =
          new AccountTenantCreationAdmissionBinding(
              intentId,
              operation,
              issuanceId,
              owner.id,
              "example",
              gdRequest,
              gdDigest,
              intentDigest,
              AccountControlUiIssuanceRepository.hash(registry),
              evidenceId,
              tokenHash,
              now.plusSeconds(300).toEpochMilli(),
              now.plusSeconds(300).toEpochMilli(),
              now.toEpochMilli(),
              now.plusSeconds(120).toEpochMilli(),
              now.plusSeconds(120).toEpochMilli()
                  + AccountTenantCreationAdmissionBinding.RECEIPT_RETENTION_MILLIS);
      var request = new HashMap<String, Object>();
      request.putAll(
          Map.of(
              "intent_request_id",
              intentId,
              "creation_operation_id",
              operation,
              "issuance_operation_id",
              issuanceId,
              "actor_account_id",
              owner.id,
              "target_namespace",
              "example",
              "game_design_creation_request_id",
              gdRequest,
              "game_design_request_digest",
              gdDigest,
              "intent_digest",
              intentDigest,
              "active_registry_digest",
              binding.activeRegistryDigest(),
              "reauthentication_evidence_id",
              evidenceId));
      request.putAll(
          Map.of(
              "token_hash",
              tokenHash,
              "token_cleanup_deadline_epoch_millis",
              binding.tokenCleanupDeadlineEpochMillis(),
              "token_expires_at_epoch_millis",
              binding.tokenExpiresAtEpochMillis(),
              "prepared_at_epoch_millis",
              binding.preparedAtEpochMillis(),
              "operation_deadline_epoch_millis",
              binding.operationDeadlineEpochMillis(),
              "receipt_cleanup_deadline_epoch_millis",
              binding.receiptCleanupDeadlineEpochMillis(),
              "binding_payload",
              binding.canonicalReceipt(),
              "binding_digest",
              AccountControlUiIssuanceRepository.hash(binding.canonicalReceipt())));
      var receipt =
          Map.<String, Object>of(
              "receipt_payload",
              binding.canonicalReceipt(),
              "receipt_digest",
              AccountControlUiIssuanceRepository.hash(binding.canonicalReceipt()),
              "local_aof_count",
              1L,
              "replica_aof_count",
              1L,
              "protected_binding_id",
              "controlled-binding",
              "qualification_fence",
              1L,
              "admitted_at",
              now.atOffset(ZoneOffset.UTC));
      Record requestRecord = row(request);
      Record receiptRecord = row(receipt);
      when(dsl.fetchOne(
              "SELECT * FROM account_tenant_creation_admission_requests WHERE creation_operation_id = ?",
              operation))
          .thenReturn(requestRecord);
      when(dsl.fetchOne(
              "SELECT * FROM account_tenant_creation_admissions WHERE creation_operation_id = ?",
              operation))
          .thenReturn(receiptRecord);
      var admissions = new AccountTenantCreationAdmissionRepository(dsl);
      admitted = admissions.readAdmitted(operation).orElseThrow();
      recent = evidence(evidenceId, operation);
      when(reauth.validatePendingCreationLocked(
              recent, owner.id, AccountRecentReauthenticationPolicy.PURPOSE, operation))
          .thenReturn(recent);
      when(policies.resolveCurrentSourcesForGameCreation())
          .thenReturn(
              new AccountRecentReauthenticationPolicyRepository.CurrentSourcesForGameCreation(
                  policy,
                  AccountRecentReauthenticationPolicy.sourceEvidence(platform, deployment)));
      when(terms.requireCurrentnessInOwnerTransaction(environment, party))
          .thenReturn(currentness(party, owner.id));
      clock.put("observed_at", now.plusSeconds(1).atOffset(ZoneOffset.UTC));
      Record clockRecord = row(clock);
      when(dsl.fetchOne("SELECT clock_timestamp() AS observed_at")).thenReturn(clockRecord);
      capture =
          new AccountTenantCreationOwnerSourceCapture(
              dsl,
              owner.authority,
              new AccountControlUiIssuanceRepository(dsl),
              admissions,
              terms,
              reauth,
              policies);
    }

    AccountRecentReauthenticationEvidence evidence(UUID id, UUID creation) {
      return new AccountRecentReauthenticationEvidence(
          id,
          UUID.randomUUID(),
          owner.id,
          AccountRecentReauthenticationPolicy.PURPOSE,
          creation,
          "sha256:" + "a".repeat(64),
          AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT,
          7L,
          AccountLifecycleState.ACTIVE,
          1L,
          1L,
          4L,
          12L,
          1L,
          1L,
          policy.effectivePolicyVersion(),
          300L,
          30L,
          now);
    }

    EnvironmentBoundCurrentness currentness(UUID selectedParty, UUID accountId) {
      return currentness(selectedParty, accountId, null);
    }

    EnvironmentBoundCurrentness currentness(UUID selectedParty, UUID accountId, Instant deadline) {
      UUID scope = UUID.randomUUID(), version = UUID.randomUUID();
      byte[] evidence = "controlled-owner-read".getBytes(StandardCharsets.UTF_8);
      String digest = "sha256:" + AccountControlUiIssuanceRepository.hash(evidence);
      var environmentData =
          new HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary(
              "controlled-environment", "owner", "event", evidence, digest);
      var binding =
          new HostedTermsEnvironmentBinding(
              UUID.randomUUID(),
              UUID.randomUUID(),
              "controlled-environment",
              scope,
              "operator",
              1L,
              version,
              1L,
              "publisher",
              "publication",
              digest,
              null,
              null,
              1L);
      var disclosed =
          deadline == null
              ? null
              : new HostedTermsCatalogVersion(
                  UUID.randomUUID(),
                  scope,
                  null,
                  "operator",
                  1L,
                  evidence,
                  digest,
                  1L,
                  1L,
                  HostedTermsCatalogVersion.Materiality.INITIAL,
                  null,
                  null,
                  "publication",
                  1L,
                  "notice",
                  1L,
                  deadline);
      var termsData =
          new CurrentnessEvidence(
              scope,
              selectedParty,
              accountId,
              version,
              digest,
              "operator",
              1L,
              1L,
              1L,
              UUID.randomUUID(),
              version,
              digest,
              deadline,
              disclosed,
              evidence);
      return new EnvironmentBoundCurrentness(
          environmentData,
          binding,
          termsData,
          List.of(
              binding.sourceEvidence(),
              new SourceEvidence(
                  SourceKind.HOSTED_TERMS, scope.toString(), "1", "1", null, null, evidence),
              new SourceEvidence(
                  SourceKind.CREATOR_PARTY,
                  selectedParty.toString(),
                  "1",
                  "1",
                  null,
                  null,
                  evidence)));
    }

    AccountTenantCreationOwnerSourceCapture.Captured capture() {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      return capture.captureLocked(intent, admitted, environment, recent);
    }
  }

  private static Record row(Map<String, Object> values) {
    Record row = mock(Record.class);
    when(row.get(anyString(), org.mockito.ArgumentMatchers.<Class<Object>>any()))
        .thenAnswer(call -> values.get(call.getArgument(0, String.class)));
    return row;
  }

  private static final class AccountCaptureContext {
    final UUID id = UUID.randomUUID();
    final UUID lockResult = UUID.randomUUID();
    final Account account = new Account();
    final AccountRepository accounts = mock(AccountRepository.class);
    final AccountAuthoritySourceEvidenceRepository upstream =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    final AccountPlatformRestrictionOperationRepository restrictions =
        mock(AccountPlatformRestrictionOperationRepository.class);
    final AccountDraftSourceCompositionService drafts =
        mock(AccountDraftSourceCompositionService.class);
    final DSLContext dsl = mock(DSLContext.class);
    final Record roleRow = mock(Record.class);
    final AccountControlUiAuthority authority;

    AccountCaptureContext() throws Exception {
      account.setId(7L);
      account.setAccountUuid(id);
      account.setAccountUuidSourceNumericId(7L);
      account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
      account.setLifecycleState(AccountLifecycleState.ACTIVE);
      when(accounts.findByAccountUuidForUpdate(id)).thenReturn(Optional.of(account));
      var fence = new IssuanceFence(id, 4L, 12L);
      var issuer =
          new CurrentSourceEvidence(
              AuthorityScope.issuer("firemud-account-service"),
              1L,
              1L,
              null,
              new SourceCheckpoint(
                  "account:auth-authority:v1:issuer/firemud-account-service",
                  0L,
                  Optional.empty(),
                  Optional.empty()),
              Optional.empty(),
              "ISSUER_SCOPE_INSERT",
              null,
              null,
              3L,
              null);
      var source =
          new CurrentSourceEvidence(
              AuthorityScope.account(id),
              1L,
              1L,
              fence,
              new SourceCheckpoint(
                  "account:auth-authority:v1:account/" + id,
                  0L,
                  Optional.empty(),
                  Optional.empty()),
              Optional.empty(),
              "ACCOUNT_REPOSITORY_INSERT",
              7L,
              "ACCOUNT_REPOSITORY_INSERT",
              3L,
              3L);
      var projection =
          AccountGenerationProjection.fromSource(
              new AccountSourceSnapshot(
                  id,
                  new ScopeState(AuthorityScope.account(id), 1L, 1L, fence),
                  source.checkpoint().outboxStreamKey(),
                  0L,
                  Optional.empty()));
      when(upstream.readCurrentIssuerAccountSources("firemud-account-service", id))
          .thenReturn(
              new IssuerAccountSourceSnapshot(
                  issuer,
                  source,
                  fence,
                  projection,
                  IssuerGenerationProjection.fromSource(issuer)));
      restriction(Category.ACCOUNT_SECURITY_LOCK, 2L, lockResult, RestrictionState.NONRESTRICTED);
      restriction(
          Category.PLATFORM_ACCESS_BAN, 2L, UUID.randomUUID(), RestrictionState.NONRESTRICTED);
      when(dsl.fetchOne(anyString(), eq(id))).thenReturn(roleRow);
      when(roleRow.get("account_uuid", UUID.class)).thenReturn(id);
      when(roleRow.get("account_uuid_source_numeric_id", Long.class)).thenReturn(7L);
      when(roleRow.get("account_uuid_provenance", String.class))
          .thenReturn("ACCOUNT_REPOSITORY_INSERT");
      when(roleRow.get("global_role_source_version", Long.class)).thenReturn(7L);
      when(roleRow.get("global_roles", String[].class)).thenReturn(new String[0]);
      var connection = mock(Connection.class);
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
      doAnswer(
              invocation -> {
                invocation.getArgument(0, ConnectionRunnable.class).run(connection);
                return null;
              })
          .when(dsl)
          .connection(org.mockito.ArgumentMatchers.any(ConnectionRunnable.class));
      authority = new AccountControlUiAuthority(drafts, accounts, upstream, restrictions, dsl);
    }

    void restriction(Category category, long revision, UUID result, RestrictionState state) {
      doReturn(
              new CurrentProjection(
                  id, category, revision, revision, result, state, UUID.randomUUID()))
          .when(restrictions)
          .readCurrent(id, category);
    }

    AccountControlUiAuthority.Snapshot capture() {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        return authority.captureAccount(id);
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
  }
}
