package net.firedevops.firemud.accountservice.authordraft;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository.InitialAssociationReadback;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.EnvironmentBoundCurrentness;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.CreatorControlCaptureSources;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapService;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered Account existing-source composition. No caller authentication, source provisioning,
 * Draft reservation, commit order or owner mutation is provided. The caller retains its transaction
 * through the intended local use; the returned observation cannot authorize a later commit.
 */
public final class AccountDraftSourceCompositionService {
  private static final String ISSUER = "firemud-account-service";
  private final DSLContext dsl;
  private final IndividualCreatorPartyRepository parties;
  private final AccountTenantCreationBootstrapService memberships;
  private final AccountHostedTermsService terms;
  private final AccountAuthoritySourceEvidenceRepository upstream;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Owner transaction collaborators are retained privately.")
  public AccountDraftSourceCompositionService(
      DSLContext dsl,
      IndividualCreatorPartyRepository parties,
      AccountTenantCreationBootstrapService memberships,
      AccountHostedTermsService terms,
      AccountAuthoritySourceEvidenceRepository upstream) {
    this.dsl = Objects.requireNonNull(dsl);
    this.parties = Objects.requireNonNull(parties);
    this.memberships = Objects.requireNonNull(memberships);
    this.terms = Objects.requireNonNull(terms);
    this.upstream = Objects.requireNonNull(upstream);
  }

  /** The environment observation must have been obtained outside this owner transaction. */
  public ExistingSources readExistingSources(
      CapturedEnvironmentBoundary environment, UUID canonicalTenantId) {
    requireOwnerTransaction();
    Objects.requireNonNull(environment, "Owner-captured environment boundary required");
    if (canonicalTenantId == null || canonicalTenantId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Canonical tenant UUID required");
    }
    var scope = parties.lockExistingInitialAssociationScope(canonicalTenantId);
    if (!canonicalTenantId.equals(scope.tenantId())) {
      throw mismatch();
    }
    var membership =
        memberships.readExistingCreatorControlCaptureSources(scope.accountId(), canonicalTenantId);
    if (!scope.accountId().equals(membership.accountUuid())
        || !canonicalTenantId.equals(membership.tenantUuid())) {
      throw mismatch();
    }
    // Preserve independently verified sequence-zero birth evidence as well as positive events.
    var issuerAccount = upstream.readCurrentIssuerAccountSources(ISSUER, scope.accountId());
    requireUpstream(membership, issuerAccount);
    var currentTerms =
        terms.requireCurrentnessInOwnerTransaction(environment, scope.creatorPartyId());
    if (!scope.accountId().equals(currentTerms.terms().accountId())
        || !scope.creatorPartyId().equals(currentTerms.terms().creatorPartyId())) {
      throw mismatch();
    }
    var association = parties.readExistingInitialAssociation(canonicalTenantId);
    var creator = association.creatorEvidence();
    var bootstrap = membership.bootstrapReceipt();
    if (!canonicalTenantId.equals(association.receipt().tenantId())
        || !scope.associationRequestId().equals(association.receipt().requestId())
        || !scope.creatorPartyId().equals(association.receipt().creatorPartyId())
        || !scope.accountId().equals(creator.initiatingAccountId())
        || !membership.creationSource().equals(creator.creationEvidence())
        || !creator.accountAuthorizationOperationId().equals(bootstrap.requestId())
        || !creator
            .accountAuthorizationOperationId()
            .equals(bootstrap.accountAuthorizationOperationId())
        || !creator.accountAuthorizationDigest().equals(bootstrap.accountAuthorizationDigest())
        || !creator.evidenceDigest().equals(bootstrap.creatorEvidenceDigest())
        || !Arrays.equals(
            CreatorPartyEncoding.creation(creator), bootstrap.creatorEvidencePayload())
        || currentTerms.sourceEvidence().stream()
            .filter(source -> source.kind() == SourceKind.CREATOR_PARTY)
            .noneMatch(
                source ->
                    source.scopeId().equals(association.partySource().creatorPartyId().toString())
                        && Objects.equals(
                            source.generation(),
                            Long.toString(association.partySource().identityVersion()))
                        && source
                            .sourceVersion()
                            .equals(Long.toString(association.partySource().sourceVersion()))
                        && Arrays.equals(
                            source.evidence(),
                            CreatorPartyEncoding.party(association.partySource())))) {
      throw mismatch();
    }
    return new ExistingSources(membership, issuerAccount, association, currentTerms);
  }

  private void requireUpstream(
      CreatorControlCaptureSources membership, IssuerAccountSourceSnapshot source) {
    var authority = membership.authoritySnapshot();
    if (!AuthorityScope.issuer(ISSUER).equals(source.issuer().scope())
        || !AuthorityScope.account(membership.accountUuid()).equals(source.account().scope())
        || !authority.issuanceFence().equals(source.issuanceFence())
        || !membership.issuanceFence().equals(Long.toString(source.issuanceFence().value()))) {
      throw mismatch();
    }
    requireSource(
        membership,
        authority.issuer(),
        source.issuer(),
        Objects.requireNonNull(source.canonicalIssuerProjection()).sourceEvent());
    requireSource(
        membership,
        authority.account(),
        source.account(),
        Objects.requireNonNull(source.canonicalAccountProjection()).sourceEvent());
  }

  private void requireSource(
      CreatorControlCaptureSources membership,
      ScopeState state,
      CurrentSourceEvidence source,
      Optional<String> event) {
    var checkpoint = source.checkpoint();
    var checkpoints =
        membership.outboxCheckpoints().stream()
            .filter(value -> value.outboxStreamKey().equals(checkpoint.outboxStreamKey()))
            .toList();
    var events =
        membership.outboxSourceEvidence().stream()
            .filter(value -> value.outboxStreamKey().equals(checkpoint.outboxStreamKey()))
            .toList();
    if (!state.scope().equals(source.scope())
        || state.generation() != source.generation()
        || state.sourceVersion() != source.sourceVersion()
        || !Objects.equals(state.issuanceFence(), source.issuanceFence())
        || checkpoints.size() != 1
        || !checkpoints.get(0).outboxSequence().equals(Long.toString(checkpoint.sequence()))
        || (checkpoint.sequence() == 0L
            ? !events.isEmpty() || event.isPresent()
            : events.size() != 1
                || event.isEmpty()
                || !events.get(0).outboxSequence().equals(Long.toString(checkpoint.sequence()))
                || !events.get(0).eventId().equals(checkpoint.sourceEventId().orElseThrow())
                || !events.get(0).eventDigest().equals(checkpoint.sourceEventDigest().orElseThrow())
                || !events.get(0).canonicalEventJson().equals(event.orElseThrow()))) {
      throw mismatch();
    }
  }

  private void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable READ_COMMITTED Account transaction required");
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException("Writable READ_COMMITTED Account transaction required");
          }
        });
  }

  private static IllegalStateException mismatch() {
    return new IllegalStateException(
        "Existing Account creator source identities or currentness differ");
  }

  /** Immutable existing owner observations only; no authenticated actor or commit permission. */
  public static final class ExistingSources {
    private final CreatorControlCaptureSources membership;
    private final IssuerAccountSourceSnapshot issuerAccount;
    private final InitialAssociationReadback association;
    private final EnvironmentBoundCurrentness hostedTerms;

    private ExistingSources(
        CreatorControlCaptureSources membership,
        IssuerAccountSourceSnapshot issuerAccount,
        InitialAssociationReadback association,
        EnvironmentBoundCurrentness hostedTerms) {
      this.membership = membership;
      this.issuerAccount = issuerAccount;
      this.association = association;
      this.hostedTerms = hostedTerms;
    }

    public CreatorControlCaptureSources membership() {
      return membership;
    }

    public IssuerAccountSourceSnapshot issuerAccount() {
      return issuerAccount;
    }

    public InitialAssociationReadback association() {
      return association;
    }

    public EnvironmentBoundCurrentness hostedTerms() {
      return hostedTerms;
    }
  }
}
