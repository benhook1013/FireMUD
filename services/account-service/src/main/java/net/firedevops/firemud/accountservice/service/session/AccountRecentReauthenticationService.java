package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl.ControlUiPrimaryIdentity;
import net.firedevops.firemud.common.EmailCanonicalization;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manually composed, unregistered Account source for recent ordinary reauthentication. It uses
 * Account's existing real primary-authentication producer and creates no token, route or session.
 */
public final class AccountRecentReauthenticationService {
  private static final String PURPOSE = AccountRecentReauthenticationPolicy.PURPOSE;
  private final AccountServiceImpl primaryAuthentication;
  private final AccountRepository accounts;
  private final AccountAuthorityGenerationRepository generations;
  private final AccountRecentReauthenticationRepository evidenceRepository;
  private final AccountRecentReauthenticationPolicyRepository policyRepository;
  private final TransactionTemplate transaction;
  private final Clock clock;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected owner transaction collaborators are retained privately and never exposed.")
  public AccountRecentReauthenticationService(
      AccountServiceImpl primaryAuthentication,
      AccountRepository accounts,
      AccountAuthorityGenerationRepository generations,
      AccountRecentReauthenticationRepository evidenceRepository,
      AccountRecentReauthenticationPolicyRepository policyRepository,
      PlatformTransactionManager transactions,
      Clock clock) {
    this.primaryAuthentication = Objects.requireNonNull(primaryAuthentication);
    this.accounts = Objects.requireNonNull(accounts);
    this.generations = Objects.requireNonNull(generations);
    this.evidenceRepository = Objects.requireNonNull(evidenceRepository);
    this.policyRepository = Objects.requireNonNull(policyRepository);
    this.clock = Objects.requireNonNull(clock);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Performs actual enabled-password or stored verified-email-OTP reauthentication and retains its
   * exact Account/action/creation-operation evidence in the same writable owner transaction.
   */
  public AccountRecentReauthenticationEvidence reauthenticate(Request request) {
    Objects.requireNonNull(request, "Account reauthentication request is required");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Recent reauthentication must own its Account transaction");
    }
    requireNonNilUuid(request.requestId, "stable reauthentication request ID");
    requireNonNilUuid(request.accountId, "expected Account identity");
    requireNonNilUuid(request.creationOperationId, "stable creation operation ID");
    if (request.email == null
        || request.email.isBlank()
        || request.email.length() > 254
        || request.secret == null
        || request.secret.isEmpty()
        || request.secret.length() > 1024) {
      throw new IllegalArgumentException("Bounded Account reauthentication request is required");
    }

    Attempt attempt =
        transaction.execute(
            ignored -> {
              evidenceRepository.lockRequest(request.requestId);
              Optional<AccountRecentReauthenticationEvidence> existingRequest =
                  evidenceRepository.findByRequest(request.requestId);
              if (existingRequest.isPresent()) {
                AccountRecentReauthenticationEvidence existing = existingRequest.orElseThrow();
                requireExactRequest(existing, request);
                return Attempt.success(existing);
              }
              if (!PURPOSE.equals(request.purpose)) {
                throw conflict();
              }

              evidenceRepository.lockCreationOperation(request.creationOperationId);
              if (evidenceRepository
                  .findByCreationOperation(request.creationOperationId)
                  .isPresent()) {
                throw conflict();
              }

              // Resolve and lock the current policy before primary authentication. Missing policy
              // therefore cannot consume an OTP or commit a new authentication grant.
              AccountRecentReauthenticationPolicy.Effective policy =
                  policyRepository.resolveCurrentForGameCreation();
              Account account = requireCurrentAccount(request.accountId);
              String email = EmailCanonicalization.normalize(request.email);
              if (!email.equals(EmailCanonicalization.normalize(account.getEmail()))) {
                throw conflict();
              }

              ControlUiPrimaryIdentity authenticated;
              try {
                authenticated =
                    primaryAuthentication.authenticateControlUiPrimaryIdentity(
                        email, request.secret);
              } catch (AuthenticationException denied) {
                // Return the denial from the transaction callback so normal failed-attempt
                // accounting commits. A successful OTP remains rollback-capable with retention.
                return Attempt.denied(denied.getCode(), denied.getMessage());
              }
              if (!request.accountId.equals(authenticated.accountId())
                  || !request.accountId.equals(account.getAccountUuid())) {
                throw conflict();
              }

              ScopeState authority = generations.read(AuthorityScope.account(request.accountId));
              requireAccountAuthority(request.accountId, authority);
              IssuanceFence fence = Objects.requireNonNull(authority.issuanceFence());
              var issuedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
              byte[] requestPayload =
                  AccountRecentReauthenticationRepository.requestPayload(
                      request.requestId,
                      request.accountId,
                      request.purpose,
                      request.creationOperationId);
              var evidence =
                  new AccountRecentReauthenticationEvidence(
                      UUID.randomUUID(),
                      request.requestId,
                      request.accountId,
                      request.purpose,
                      request.creationOperationId,
                      AccountRecentReauthenticationRepository.digest(requestPayload),
                      account.getAccountUuidProvenance(),
                      account.getAccountUuidSourceNumericId(),
                      account.getLifecycleState(),
                      authority.generation(),
                      authority.sourceVersion(),
                      fence.value(),
                      fence.sourceVersion(),
                      policy.platformVersion(),
                      policy.deploymentVersion(),
                      policy.effectivePolicyVersion(),
                      policy.recentAgeSeconds(),
                      policy.maxClockSkewSeconds(),
                      issuedAt);
              evidenceRepository.insert(evidence, requestPayload);
              return Attempt.success(
                  evidenceRepository.findByRequest(request.requestId).orElseThrow());
            });
    if (attempt == null) {
      throw unavailable();
    }
    if (attempt.denial != null) {
      throw new AuthenticationException(attempt.denial.code, attempt.denial.message);
    }
    return Objects.requireNonNull(attempt.evidence);
  }

  /**
   * Locked consume point for a later Account game-creation commit transaction. A CONSUMED result is
   * new validated evidence; EXACT_REPLAY is only a replay marker and the caller must return the
   * original durable creation result, never execute another creation side effect.
   */
  public Validation validateAndConsumeLocked(
      AccountRecentReauthenticationEvidence presented,
      UUID expectedAccountId,
      String expectedPurpose,
      UUID expectedCreationOperationId) {
    AccountRecentReauthenticationEvidence evidence =
        lockExactBinding(
            presented, expectedAccountId, expectedPurpose, expectedCreationOperationId);
    Optional<AccountRecentReauthenticationRepository.Consumption> previous =
        evidenceRepository.findConsumption(evidence.evidenceId());
    if (previous.isPresent()) {
      if (!previous.orElseThrow().matches(evidence)) {
        throw conflict();
      }
      return new Validation(Disposition.EXACT_REPLAY, evidence);
    }

    requireCurrentEvidence(evidence);
    evidenceRepository.insertConsumption(evidence, clock.instant());
    return new Validation(Disposition.CONSUMED, evidence);
  }

  /**
   * Checks exact unconsumed evidence for pending creation without consuming it. Every call checks
   * current Account identity, authority, policy and age in the caller's writable owner transaction.
   * The returned evidence is only a same-transaction current observation, not a durable grant;
   * producers must still order applicable source changes and revalidate at their commit boundary.
   * Already consumed evidence is refused even when final-result recovery may replay its
   * consumption.
   */
  public AccountRecentReauthenticationEvidence validatePendingCreationLocked(
      AccountRecentReauthenticationEvidence presented,
      UUID expectedAccountId,
      String expectedPurpose,
      UUID expectedCreationOperationId) {
    AccountRecentReauthenticationEvidence evidence =
        lockExactBinding(
            presented, expectedAccountId, expectedPurpose, expectedCreationOperationId);
    if (evidenceRepository.findConsumption(evidence.evidenceId()).isPresent()) {
      throw unavailable();
    }
    requireCurrentEvidence(evidence);
    return evidence;
  }

  private AccountRecentReauthenticationEvidence lockExactBinding(
      AccountRecentReauthenticationEvidence presented,
      UUID expectedAccountId,
      String expectedPurpose,
      UUID expectedCreationOperationId) {
    requireWritableTransaction();
    Objects.requireNonNull(presented, "Typed Account reauthentication evidence is required");
    requireNonNilUuid(expectedAccountId, "expected Account identity");
    requireNonNilUuid(expectedCreationOperationId, "expected creation operation ID");
    if (!PURPOSE.equals(expectedPurpose)) {
      throw conflict();
    }

    AccountRecentReauthenticationEvidence evidence = evidenceRepository.lockExact(presented);
    if (!expectedAccountId.equals(evidence.accountId())
        || !expectedPurpose.equals(evidence.purpose())
        || !expectedCreationOperationId.equals(evidence.creationOperationId())) {
      throw conflict();
    }
    return evidence;
  }

  private void requireCurrentEvidence(AccountRecentReauthenticationEvidence evidence) {
    Account account = requireCurrentAccount(evidence.accountId());
    if (account.getAccountUuidProvenance() != evidence.identityProvenance()
        || !Objects.equals(
            account.getAccountUuidSourceNumericId(), evidence.identitySourceNumericId())
        || account.getLifecycleState() != AccountLifecycleState.ACTIVE
        || evidence.lifecycleState() != AccountLifecycleState.ACTIVE) {
      throw unavailable();
    }
    ScopeState authority = generations.read(AuthorityScope.account(evidence.accountId()));
    requireAccountAuthority(evidence.accountId(), authority);
    IssuanceFence fence = Objects.requireNonNull(authority.issuanceFence());
    if (authority.generation() != evidence.accountAuthorityGeneration()
        || authority.sourceVersion() != evidence.accountAuthoritySourceVersion()
        || fence.value() != evidence.issuanceFence()
        || fence.sourceVersion() != evidence.issuanceFenceSourceVersion()) {
      throw unavailable();
    }

    AccountRecentReauthenticationPolicy.Effective policy =
        policyRepository.resolveCurrentForGameCreation();
    if (policy.platformVersion() != evidence.platformPolicyVersion()
        || policy.deploymentVersion() != evidence.deploymentPolicyVersion()
        || !policy.effectivePolicyVersion().equals(evidence.effectivePolicyVersion())
        || policy.recentAgeSeconds() != evidence.recentAgeSeconds()
        || policy.maxClockSkewSeconds() != evidence.maxClockSkewSeconds()
        || !policy.isRecent(evidence.issuedAt(), clock.instant())) {
      throw unavailable();
    }
  }

  private Account requireCurrentAccount(UUID accountId) {
    Account account =
        accounts
            .findByAccountUuidForUpdate(accountId)
            .orElseThrow(AccountRecentReauthenticationService::unavailable);
    if (account.getId() == null
        || account.getId() <= 0L
        || !accountId.equals(account.getAccountUuid())
        || account.getAccountUuidSourceNumericId() == null
        || !account.getId().equals(account.getAccountUuidSourceNumericId())
        || !AccountIdentityProvenance.isAccepted(account.getAccountUuidProvenance())
        || account.getLifecycleState() != AccountLifecycleState.ACTIVE) {
      throw unavailable();
    }
    return account;
  }

  private static void requireAccountAuthority(UUID accountId, ScopeState authority) {
    if (authority == null
        || !AuthorityScope.account(accountId).equals(authority.scope())
        || authority.generation() <= 0L
        || authority.sourceVersion() <= 0L
        || authority.issuanceFence() == null
        || !accountId.equals(authority.issuanceFence().accountId())
        || authority.issuanceFence().value() <= 0L
        || authority.issuanceFence().sourceVersion() <= 0L) {
      throw unavailable();
    }
  }

  private static void requireExactRequest(
      AccountRecentReauthenticationEvidence evidence, Request request) {
    byte[] payload =
        AccountRecentReauthenticationRepository.requestPayload(
            request.requestId, request.accountId, request.purpose, request.creationOperationId);
    if (!evidence.requestId().equals(request.requestId)
        || !evidence.accountId().equals(request.accountId)
        || !evidence.purpose().equals(request.purpose)
        || !evidence.creationOperationId().equals(request.creationOperationId)
        || !evidence
            .requestDigest()
            .equals(AccountRecentReauthenticationRepository.digest(payload))) {
      throw conflict();
    }
  }

  private static void requireNonNilUuid(UUID value, String field) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Non-nil UUID " + field + " is required");
    }
  }

  private static void requireWritableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Recent reauthentication consume requires the writable creation transaction");
    }
  }

  private static IllegalStateException conflict() {
    return new IdempotencyConflictException();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Account recent-reauthentication authority is unavailable");
  }

  /** Secret-bearing input is transient and deliberately has a redacted string representation. */
  public static final class Request {
    private final UUID requestId;
    private final UUID accountId;
    private final String purpose;
    private final UUID creationOperationId;
    private final String email;
    private final String secret;

    public Request(
        UUID requestId,
        UUID accountId,
        String purpose,
        UUID creationOperationId,
        String email,
        String secret) {
      this.requestId = requestId;
      this.accountId = accountId;
      this.purpose = purpose;
      this.creationOperationId = creationOperationId;
      this.email = email;
      this.secret = secret;
    }

    @Override
    public String toString() {
      return "AccountRecentReauthenticationService.Request[redacted]";
    }
  }

  public enum Disposition {
    CONSUMED,
    EXACT_REPLAY
  }

  public static final class Validation {
    private final Disposition disposition;
    private final AccountRecentReauthenticationEvidence evidence;

    private Validation(Disposition disposition, AccountRecentReauthenticationEvidence evidence) {
      this.disposition = Objects.requireNonNull(disposition);
      this.evidence = Objects.requireNonNull(evidence);
    }

    public Disposition disposition() {
      return disposition;
    }

    public AccountRecentReauthenticationEvidence evidence() {
      return evidence;
    }

    @Override
    public String toString() {
      return "AccountRecentReauthenticationService.Validation[redacted]";
    }
  }

  public static final class IdempotencyConflictException extends IllegalStateException {
    private IdempotencyConflictException() {
      super("Recent reauthentication request identity conflicts with retained evidence");
    }
  }

  private static final class Attempt {
    private final AccountRecentReauthenticationEvidence evidence;
    private final PrimaryDenial denial;

    private Attempt(AccountRecentReauthenticationEvidence evidence, PrimaryDenial denial) {
      if ((evidence == null) == (denial == null)) {
        throw new IllegalArgumentException("Exactly one reauthentication result is required");
      }
      this.evidence = evidence;
      this.denial = denial;
    }

    static Attempt success(AccountRecentReauthenticationEvidence evidence) {
      return new Attempt(Objects.requireNonNull(evidence), null);
    }

    static Attempt denied(String code, String message) {
      return new Attempt(null, new PrimaryDenial(code, message));
    }
  }

  private record PrimaryDenial(String code, String message) {
    private PrimaryDenial {
      Objects.requireNonNull(code);
      Objects.requireNonNull(message);
    }
  }
}
