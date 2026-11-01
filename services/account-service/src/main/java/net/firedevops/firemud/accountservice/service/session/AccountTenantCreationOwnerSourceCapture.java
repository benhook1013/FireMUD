package net.firedevops.firemud.accountservice.service.session;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.EnvironmentBoundCurrentness;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered same-transaction owner observation, never creation permission or a source hold. */
final class AccountTenantCreationOwnerSourceCapture {
  private final DSLContext dsl;
  private final AccountControlUiAuthority authority;
  private final AccountControlUiIssuanceRepository issuances;
  private final AccountTenantCreationAdmissionRepository admissions;
  private final AccountHostedTermsService hostedTerms;
  private final AccountRecentReauthenticationService reauthentication;
  private final AccountRecentReauthenticationPolicyRepository policies;

  AccountTenantCreationOwnerSourceCapture(
      DSLContext dsl,
      AccountControlUiAuthority authority,
      AccountControlUiIssuanceRepository issuances,
      AccountTenantCreationAdmissionRepository admissions,
      AccountHostedTermsService hostedTerms,
      AccountRecentReauthenticationService reauthentication,
      AccountRecentReauthenticationPolicyRepository policies) {
    this.dsl = Objects.requireNonNull(dsl);
    this.authority = Objects.requireNonNull(authority);
    this.issuances = Objects.requireNonNull(issuances);
    this.admissions = Objects.requireNonNull(admissions);
    this.hostedTerms = Objects.requireNonNull(hostedTerms);
    this.reauthentication = Objects.requireNonNull(reauthentication);
    this.policies = Objects.requireNonNull(policies);
  }

  /**
   * Caller must subsequently acquire the exact vector through SourceOrderRepository in this same
   * transaction. Accepted signing-key/overlap qualification belongs outside SQL to the coordinator;
   * this method makes no registry, signer-custody, Redis or remote JWKS call.
   */
  Captured captureLocked(
      OriginalIntent intent,
      Admitted admitted,
      CapturedEnvironmentBoundary capturedEnvironment,
      AccountRecentReauthenticationEvidence presented) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw denied();
    Objects.requireNonNull(intent);
    Objects.requireNonNull(admitted);
    Objects.requireNonNull(capturedEnvironment);
    Objects.requireNonNull(presented);

    // Preserve the owning Account-before-issuer/issuance/source row ordering. captureAccount also
    // checks the physical connection's writable READ_COMMITTED transaction and current
    // restrictions.
    var account = authority.captureAccount(intent.initiatingAccountId());
    requireExactPersistedIntent(intent);
    var persisted =
        admissions
            .readAdmitted(intent.creationOperationId())
            .orElseThrow(AccountTenantCreationOwnerSourceCapture::denied);
    requireSameAdmission(admitted, persisted);
    var binding = persisted.binding();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ? FOR SHARE",
            binding.issuanceOperationId());
    if (row == null || row.get("committed_at", OffsetDateTime.class) == null) throw denied();
    var original = issuances.decodeStored(row);
    requireOriginal(binding, original, account);

    var terms =
        hostedTerms.requireCurrentnessInOwnerTransaction(
            capturedEnvironment, intent.creatorPartyId());
    if (!terms.terms().accountId().equals(intent.initiatingAccountId())
        || !terms.terms().accountId().equals(binding.actorAccountId())
        || !terms.terms().creatorPartyId().equals(intent.creatorPartyId())) throw denied();
    if (!presented.evidenceId().equals(intent.recentReauthenticationEvidenceId())
        || !presented.evidenceId().equals(binding.reauthenticationEvidenceId())) throw denied();
    var recent =
        reauthentication.validatePendingCreationLocked(
            presented,
            intent.initiatingAccountId(),
            AccountRecentReauthenticationPolicy.PURPOSE,
            intent.creationOperationId());
    if (!recent.evidenceId().equals(presented.evidenceId())
        || !recent.accountId().equals(intent.initiatingAccountId())
        || !recent.creationOperationId().equals(intent.creationOperationId())
        || !recent.purpose().equals(AccountRecentReauthenticationPolicy.PURPOSE)) throw denied();
    var policy = policies.resolveCurrentSourcesForGameCreation();
    var effective = policy.effectivePolicy();
    if (recent.platformPolicyVersion() != effective.platformVersion()
        || recent.deploymentPolicyVersion() != effective.deploymentVersion()
        || !recent.effectivePolicyVersion().equals(effective.effectivePolicyVersion())
        || recent.recentAgeSeconds() != effective.recentAgeSeconds()
        || recent.maxClockSkewSeconds() != effective.maxClockSkewSeconds()) throw denied();

    // Use a fresh database clock after owner reads, never transaction_timestamp or a caller clock.
    Record clock = dsl.fetchOne("SELECT clock_timestamp() AS observed_at");
    if (clock == null) throw denied();
    Instant now =
        Objects.requireNonNull(clock.get("observed_at", OffsetDateTime.class)).toInstant();
    if (!now.isBefore(Instant.ofEpochMilli(binding.operationDeadlineEpochMillis()))
        || now.isBefore(Instant.ofEpochMilli(binding.preparedAtEpochMillis()))
        || !effective.isRecent(recent.issuedAt(), now)
        || terms.terms().validUntil() != null && !now.isBefore(terms.terms().validUntil())) {
      throw denied();
    }

    var sources = new ArrayList<>(account.sources());
    sources.addAll(terms.sourceEvidence());
    sources.addAll(policy.sources());
    sources.sort(Comparator.comparing(SourceEvidence::key));
    // Existing canonical binding checks exact original correlation, eight distinct closed keys,
    // actual Account/party scope, and excludes Tenant/Membership or extra sources.
    AccountTenantCreationSourceOrderBinding.from(intent, persisted, sources);
    return new Captured(intent, persisted, original, account, terms, recent, sources, now);
  }

  private static void requireSameAdmission(Admitted supplied, Admitted persisted) {
    if (!Arrays.equals(supplied.receiptPayload(), persisted.receiptPayload())
        || !supplied.receiptDigest().equals(persisted.receiptDigest())
        || !Arrays.equals(
            supplied.binding().canonicalReceipt(), persisted.binding().canonicalReceipt())
        || supplied.localAofCount() != persisted.localAofCount()
        || supplied.replicaAofCount() != persisted.replicaAofCount()
        || !supplied.protectedBindingId().equals(persisted.protectedBindingId())
        || supplied.qualificationFence() != persisted.qualificationFence()
        || !supplied.admittedAt().equals(persisted.admittedAt())) throw denied();
  }

  private static void requireOriginal(
      AccountTenantCreationAdmissionBinding binding,
      AccountControlUiIssuanceRepository.Stored original,
      AccountControlUiAuthority.Snapshot current) {
    // The admission trigger established COMMITTED before admission. Later ordinary per-token
    // revocation does not cancel the service-owned original; immutable issuance is still required.
    if (!List.of("COMMITTED", "REVOKING", "REVOKED").contains(original.status)
        || !original.operationId.equals(binding.issuanceOperationId())
        || !original.accountId.equals(binding.actorAccountId())
        || !original.tokenHash.equals(binding.tokenHash())
        || original.scopeKind() != AccountControlUiAuthority.ScopeKind.ACCOUNT
        || original.optionalTenantId().isPresent()
        || !AccountControlUiIssuanceRepository.hash(original.activeRegistry)
            .equals(binding.activeRegistryDigest())
        || original.expiryMillis() != binding.tokenCleanupDeadlineEpochMillis()
        || original.expiresAt.toEpochMilli() != binding.tokenExpiresAtEpochMillis()
        || !Arrays.equals(original.sources, current.evidence())) throw denied();
    Map<String, Object> claims = AccountControlUiIssuanceRepository.object(original.claims);
    ControlUiJwtProfileValidator.validateClaims(claims, 1);
    if (!original.accountId.toString().equals(claims.get("sub"))
        || !original.accountId.toString().equals(claims.get("accountId"))
        || !original.jti.toString().equals(claims.get("jti"))
        || !Long.toString(original.issuedAt.getEpochSecond())
            .equals(String.valueOf(claims.get("iat")))
        || !Long.toString(original.issuedAt.getEpochSecond())
            .equals(String.valueOf(claims.get("nbf")))
        || !Long.toString(original.expiresAt.getEpochSecond())
            .equals(String.valueOf(claims.get("exp")))
        || current.scopeKind() != AccountControlUiAuthority.ScopeKind.ACCOUNT
        || !current.actor().equals(original.accountId)
        || current.optionalTenantId().isPresent()
        || !current.scopedRoles().isEmpty()
        || !current.membershipVersion().isEmpty()
        || !same(current.authorityTuple(), claims.get("authorityTuple"))
        || !same(current.membershipVersion(), claims.get("membershipVersion"))
        || !same(current.scopedRoles(), claims.get("scopedRoles"))
        || !same(current.globalRoles(), claims.getOrDefault("globalRoles", List.of()))
        || !Long.toString(current.issuanceFence())
            .equals(String.valueOf(claims.get("issuanceFence")))) {
      throw denied();
    }
  }

  private static boolean same(Object first, Object second) {
    return Arrays.equals(
        AccountControlUiAuthority.canonical(first), AccountControlUiAuthority.canonical(second));
  }

  private void requireExactPersistedIntent(OriginalIntent intent) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_tenant_creation_original_intents "
                + "WHERE creation_operation_id = ? AND request_id = ?",
            intent.creationOperationId(),
            intent.requestId());
    if (row == null
        || !intent.creationOperationId().equals(row.get("creation_operation_id", UUID.class))
        || !intent.requestId().equals(row.get("request_id", UUID.class))
        || !intent
            .gameDesignCreationRequestId()
            .equals(row.get("game_design_creation_request_id", UUID.class))
        || !intent.initiatingAccountId().equals(row.get("initiating_account_uuid", UUID.class))
        || !intent.targetNamespace().equals(row.get("target_namespace", String.class))
        || !intent.sourceGameTenantKey().equals(row.get("source_game_tenant_key", String.class))
        || !intent.name().equals(row.get("name", String.class))
        || !Objects.equals(intent.description(), row.get("description", String.class))
        || !intent
            .recentReauthenticationEvidenceId()
            .equals(row.get("recent_reauthentication_evidence_id", UUID.class))
        || !intent.creatorPartyId().equals(row.get("creator_party_id", UUID.class))
        || !intent.consentOperationId().equals(row.get("consent_operation_id", UUID.class))
        || !Integer.valueOf(intent.consentStatementVersion())
            .equals(row.get("consent_statement_version", Integer.class))
        || !Boolean.valueOf(intent.affirmativeConsent())
            .equals(row.get("affirmative_consent", Boolean.class))
        || !intent
            .gameDesignRequestDigest()
            .equals(row.get("game_design_request_digest", String.class))
        || !Arrays.equals(intent.requestPayload(), row.get("request_payload", byte[].class))
        || !intent.requestDigest().equals(row.get("request_digest", String.class))) {
      throw denied();
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException(
        "Exact current original tenant-creation owner sources are unavailable");
  }

  /** Immutable data observed in the caller transaction; no durable protection or verified grant. */
  static final class Captured {
    private final OriginalIntent intent;
    private final Admitted admitted;
    private final java.util.UUID issuanceOperationId;
    private final java.util.UUID tokenJti;
    private final String tokenHash;
    private final byte[] originalClaims;
    private final byte[] originalSignerReceipt;
    private final AccountControlUiAuthority.Snapshot account;
    private final EnvironmentBoundCurrentness currentness;
    private final AccountRecentReauthenticationEvidence reauthentication;
    private final List<SourceEvidence> sources;
    private final Instant observedAt;

    private Captured(
        OriginalIntent intent,
        Admitted admitted,
        AccountControlUiIssuanceRepository.Stored original,
        AccountControlUiAuthority.Snapshot account,
        EnvironmentBoundCurrentness currentness,
        AccountRecentReauthenticationEvidence reauthentication,
        List<SourceEvidence> sources,
        Instant observedAt) {
      this.intent = intent;
      this.admitted = admitted;
      this.issuanceOperationId = original.operationId;
      this.tokenJti = original.jti;
      this.tokenHash = original.tokenHash;
      this.originalClaims = original.claims.clone();
      this.originalSignerReceipt = original.signerReceipt.clone();
      this.account = account;
      this.currentness = currentness;
      this.reauthentication = reauthentication;
      this.sources = List.copyOf(sources);
      this.observedAt = observedAt;
    }

    OriginalIntent intent() {
      return intent;
    }

    Admitted admitted() {
      return admitted;
    }

    java.util.UUID issuanceOperationId() {
      return issuanceOperationId;
    }

    java.util.UUID tokenJti() {
      return tokenJti;
    }

    String tokenHash() {
      return tokenHash;
    }

    byte[] originalClaims() {
      return originalClaims.clone();
    }

    byte[] originalSignerReceipt() {
      return originalSignerReceipt.clone();
    }

    AccountControlUiAuthority.Snapshot account() {
      return account;
    }

    EnvironmentBoundCurrentness currentness() {
      return currentness;
    }

    AccountRecentReauthenticationEvidence reauthentication() {
      return reauthentication;
    }

    List<SourceEvidence> sources() {
      return sources;
    }

    Instant observedAt() {
      return observedAt;
    }
  }
}
