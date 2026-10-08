package net.firedevops.firemud.accountservice.hostedterms;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Account owner workflow for hosted terms publication, individual acceptance and
 * currentness. Its typed collaborators are trusted-owner boundaries, not caller DTO authority.
 *
 * <p>This does not prove live legal publication or identity verification, add a route/bean, or
 * authorize Game Design commits. Currentness includes any disclosed future effective deadline; the
 * commit consumer must recheck that deadline at its own linearization point.
 */
public final class AccountHostedTermsService {
  private final HostedTermsRepository repository;
  private final IndividualCreatorPartyRepository individualParties;
  private final DraftAuthorizationFenceRepository draftFences;
  private final OperatorPublicationAuthority publicationAuthority;
  private final IndividualAcceptanceAuthority acceptanceAuthority;
  private final TransactionTemplate ownerTransaction;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Trusted owner collaborators are retained privately and never exposed.")
  public AccountHostedTermsService(
      PlatformTransactionManager transactionManager,
      HostedTermsRepository repository,
      IndividualCreatorPartyRepository individualParties,
      DraftAuthorizationFenceRepository draftFences,
      OperatorPublicationAuthority publicationAuthority,
      IndividualAcceptanceAuthority acceptanceAuthority) {
    this.repository = Objects.requireNonNull(repository);
    this.individualParties = Objects.requireNonNull(individualParties);
    this.draftFences = Objects.requireNonNull(draftFences);
    this.publicationAuthority = Objects.requireNonNull(publicationAuthority);
    this.acceptanceAuthority = Objects.requireNonNull(acceptanceAuthority);
    ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
  }

  /** Resolves publication solely from the trusted operator owner before opening an Account tx. */
  public PublicationResult publish(UUID requestId) {
    HostedTermsEncoding.requireUuid(requestId, "publication request");
    PublicationEvidence evidence =
        Objects.requireNonNull(
            publicationAuthority.resolve(requestId), "operator publication evidence");
    byte[] requestPayload = HostedTermsEncoding.publication(requestId, evidence);
    return Objects.requireNonNull(
        ownerTransaction.execute(
            ignored -> publishInTransaction(requestId, evidence, requestPayload)),
        "Account publication transaction result");
  }

  /** Recovery uses the already durable exact publication and never remints operator evidence. */
  public PublicationResult resumePublication(UUID requestId) {
    HostedTermsEncoding.requireUuid(requestId, "publication request");
    return Objects.requireNonNull(
        ownerTransaction.execute(
            ignored -> {
              HostedTermsRepository.PublicationOperation operation =
                  repository
                      .readPublication(requestId, true)
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException(
                                  "Original publication request is absent"));
              return advancePublication(operation, null);
            }),
        "Account publication recovery result");
  }

  /**
   * Records only a trusted upstream-authenticated individual's exact affirmative UI/action
   * evidence. The accepting account/party comes from that collaborator, then Account independently
   * reads and locks the persisted local VERIFIED party source.
   */
  public IndividualHostedTermsAcceptance accept(UUID actionRequestId) {
    HostedTermsEncoding.requireUuid(actionRequestId, "acceptance action request");
    AcceptanceAction action =
        Objects.requireNonNull(
            acceptanceAuthority.resolve(actionRequestId), "authenticated individual action");
    if (!actionRequestId.equals(action.actionRequestId())) {
      throw new IllegalArgumentException(
          "Trusted acceptance action has a changed request identity");
    }
    if (!action.affirmative()) {
      throw new IllegalArgumentException("Affirmative hosted-terms acceptance is required");
    }
    byte[] actionBytes = HostedTermsEncoding.affirmativeAction(action);
    return Objects.requireNonNull(
        ownerTransaction.execute(ignored -> acceptInTransaction(action, actionBytes)),
        "Account acceptance transaction result");
  }

  /**
   * Fresh Account evidence for one hosted scope and party. A scheduled deadline is included even
   * before it is due; at/after it, old terms are denied even if a source-change fence is
   * unresolved.
   */
  public CurrentnessEvidence requireCurrentness(UUID hostedScopeId, UUID creatorPartyId) {
    HostedTermsEncoding.requireUuid(hostedScopeId, "hosted scope");
    HostedTermsEncoding.requireUuid(creatorPartyId, "creator party");
    return Objects.requireNonNull(
        ownerTransaction.execute(
            ignored -> currentnessInTransaction(hostedScopeId, creatorPartyId)),
        "Account hosted terms currentness result");
  }

  private PublicationResult publishInTransaction(
      UUID requestId, PublicationEvidence evidence, byte[] requestPayload) {
    repository.ensureScope(evidence.hostedScopeId());
    HostedTermsRepository.PublicationOperation operation =
        repository.claimPublication(requestId, evidence.hostedScopeId(), requestPayload);
    if (operation.status() != HostedTermsRepository.PublicationStatus.RECEIVED) {
      return advancePublication(operation, evidence);
    }
    HostedTermsRepository.ScopeSnapshot scope = repository.lockScope(evidence.hostedScopeId());
    if (scope.unsettledPublication() != null
        && !scope.unsettledPublication().requestId().equals(requestId)) {
      throw new IllegalStateException("Another hosted terms publication is scheduled or pending");
    }
    HostedTermsCatalogVersion candidate = candidate(evidence, scope.current());
    repository.insertCandidate(candidate);
    if (candidate.effectiveAt().isAfter(scope.databaseNow().toInstant())) {
      repository.markScheduled(requestId, candidate);
      return result(HostedTermsRepository.PublicationStatus.SCHEDULED, candidate);
    }
    return activate(operation, candidate, scope.current(), null, scope.databaseNow());
  }

  private PublicationResult advancePublication(
      HostedTermsRepository.PublicationOperation operation, PublicationEvidence suppliedEvidence) {
    if (operation.status() == HostedTermsRepository.PublicationStatus.COMMITTED) {
      return result(operation, repository.readCandidate(operation));
    }
    if (operation.status() == HostedTermsRepository.PublicationStatus.RECEIVED) {
      throw new IllegalStateException(
          "Publication intent is incomplete; retry the original trusted publication request");
    }
    if (suppliedEvidence != null) {
      byte[] supplied = HostedTermsEncoding.publication(operation.requestId(), suppliedEvidence);
      if (!Arrays.equals(operation.requestPayload(), supplied)) {
        throw new IllegalArgumentException(
            "Publication request identity conflicts with exact prior intent");
      }
    }
    HostedTermsRepository.ScopeSnapshot scope = repository.lockScope(operation.hostedScopeId());
    HostedTermsCatalogVersion candidate = repository.readCandidate(operation);
    if (candidate.effectiveAt().isAfter(scope.databaseNow().toInstant())) {
      if (operation.status() != HostedTermsRepository.PublicationStatus.SCHEDULED) {
        throw new IllegalStateException(
            "Owner-pending publication cannot precede its effective date");
      }
      return result(operation, candidate);
    }
    if (operation.status() == HostedTermsRepository.PublicationStatus.PENDING_OWNER_SETTLEMENT) {
      DraftAuthorizationFenceRepository.SourceChange change =
          DraftAuthorizationFenceRepository.SourceChange.fromStored(
              operation.sourceChangeBinding());
      boolean settled = draftFences.requestSourceChange(change);
      if (!settled || !draftFences.sourceMutationPermitted(change)) {
        return result(operation, candidate);
      }
      repository.completePublication(
          operation.requestId(),
          candidate,
          HostedTermsEncoding.catalog(candidate),
          change.canonicalBytes());
      draftFences.markSourceCommitted(change);
      return result(HostedTermsRepository.PublicationStatus.COMMITTED, candidate);
    }
    return activate(operation, candidate, scope.current(), null, scope.databaseNow());
  }

  private PublicationResult activate(
      HostedTermsRepository.PublicationOperation operation,
      HostedTermsCatalogVersion candidate,
      HostedTermsCatalogVersion current,
      byte[] priorChangeBinding,
      OffsetDateTime databaseNow) {
    if (candidate.effectiveAt().isAfter(databaseNow.toInstant())) {
      throw new IllegalStateException("A future catalog version cannot become operative early");
    }
    byte[] changeBinding = priorChangeBinding;
    if (current != null) {
      DraftAuthorizationFenceRepository.SourceChange change =
          changeBinding == null
              ? sourceChange(operation.requestId(), current, candidate)
              : DraftAuthorizationFenceRepository.SourceChange.fromStored(changeBinding);
      if (changeBinding == null) {
        changeBinding = change.canonicalBytes();
      }
      boolean settled = draftFences.requestSourceChange(change);
      if (!settled || !draftFences.sourceMutationPermitted(change)) {
        if (operation.status()
            != HostedTermsRepository.PublicationStatus.PENDING_OWNER_SETTLEMENT) {
          repository.markOwnerSettlementPending(operation.requestId(), candidate, changeBinding);
        }
        return result(HostedTermsRepository.PublicationStatus.PENDING_OWNER_SETTLEMENT, candidate);
      }
      if (operation.status() == HostedTermsRepository.PublicationStatus.RECEIVED) {
        repository.markScheduled(operation.requestId(), candidate);
      }
      repository.completePublication(
          operation.requestId(), candidate, HostedTermsEncoding.catalog(candidate), changeBinding);
      draftFences.markSourceCommitted(change);
      return result(HostedTermsRepository.PublicationStatus.COMMITTED, candidate);
    }
    if (operation.status() == HostedTermsRepository.PublicationStatus.RECEIVED) {
      repository.markScheduled(operation.requestId(), candidate);
    }
    repository.completePublication(
        operation.requestId(), candidate, HostedTermsEncoding.catalog(candidate), null);
    return result(HostedTermsRepository.PublicationStatus.COMMITTED, candidate);
  }

  private HostedTermsCatalogVersion candidate(
      PublicationEvidence evidence, HostedTermsCatalogVersion predecessor) {
    HostedTermsCatalogVersion.Materiality materiality =
        predecessor == null
            ? HostedTermsCatalogVersion.Materiality.INITIAL
            : evidence.materiality();
    if (predecessor == null
        && evidence.materiality() != HostedTermsCatalogVersion.Materiality.INITIAL) {
      throw new IllegalArgumentException(
          "Initial catalog birth must not claim a prior materiality change");
    }
    if (predecessor != null
        && evidence.materiality() == HostedTermsCatalogVersion.Materiality.INITIAL) {
      throw new IllegalArgumentException(
          "A catalog update requires an audited materiality classification");
    }
    boolean operatorChanged =
        predecessor != null
            && (!predecessor.operatorLegalIdentity().equals(evidence.operatorLegalIdentity())
                || predecessor.operatorIdentityVersion() != evidence.operatorIdentityVersion());
    if (operatorChanged && materiality != HostedTermsCatalogVersion.Materiality.MATERIAL) {
      throw new IllegalArgumentException(
          "Operator identity change requires material classification");
    }
    long sourceVersion = predecessor == null ? 1 : Math.addExact(predecessor.sourceVersion(), 1);
    long generation =
        predecessor == null
            ? 1
            : Math.addExact(
                predecessor.materialGeneration(),
                materiality == HostedTermsCatalogVersion.Materiality.MATERIAL ? 1 : 0);
    UUID versionId = UUID.randomUUID();
    return new HostedTermsCatalogVersion(
        versionId,
        evidence.hostedScopeId(),
        predecessor == null ? null : predecessor.versionId(),
        evidence.operatorLegalIdentity(),
        evidence.operatorIdentityVersion(),
        evidence.documentBytes(),
        HostedTermsEncoding.digest(evidence.documentBytes()),
        sourceVersion,
        generation,
        materiality,
        evidence.materialityEvidenceReference(),
        evidence.materialityEvidenceVersion(),
        evidence.publicationEvidenceReference(),
        evidence.publicationEvidenceVersion(),
        evidence.noticeEvidenceReference(),
        evidence.noticeEvidenceVersion(),
        evidence.effectiveAt());
  }

  private IndividualHostedTermsAcceptance acceptInTransaction(
      AcceptanceAction action, byte[] actionBytes) {
    IndividualHostedTermsAcceptance prior =
        repository.readAcceptanceByRequest(action.actionRequestId()).orElse(null);
    if (prior != null) {
      if (!prior.accountId().equals(action.accountId())
          || !prior.creatorPartyId().equals(action.creatorPartyId())
          || !prior.hostedScopeId().equals(action.hostedScopeId())
          || !prior.termsVersionId().equals(action.shownVersionId())
          || !prior.documentDigest().equals(action.shownDocumentDigest())
          || !prior.operatorLegalIdentity().equals(action.shownOperatorLegalIdentity())
          || prior.operatorIdentityVersion() != action.shownOperatorIdentityVersion()
          || !Arrays.equals(prior.affirmativeActionEvidence(), actionBytes)) {
        throw new IllegalArgumentException(
            "Acceptance request identity conflicts with prior evidence");
      }
      return prior;
    }
    IndividualCreatorPartySource party =
        individualParties.readIndividualSource(action.creatorPartyId(), action.accountId());
    requireOwnVerifiedIndividual(action, party);
    HostedTermsRepository.ScopeSnapshot scope = repository.lockScope(action.hostedScopeId());
    HostedTermsCatalogVersion shown =
        repository.readCatalog(action.hostedScopeId(), action.shownVersionId());
    if (!action.shownVersionId().equals(shown.versionId())
        || !action.hostedScopeId().equals(shown.hostedScopeId())
        || !action.shownDocumentDigest().equals(shown.documentDigest())
        || !action.shownOperatorLegalIdentity().equals(shown.operatorLegalIdentity())
        || action.shownOperatorIdentityVersion() != shown.operatorIdentityVersion()) {
      throw new IllegalArgumentException(
          "Affirmative action differs from exact shown catalog version");
    }
    boolean current =
        scope.current() != null && scope.current().versionId().equals(shown.versionId());
    boolean scheduled =
        scope.unsettledPublication() != null
            && Objects.equals(scope.unsettledPublication().candidateVersionId(), shown.versionId());
    if (!current && !scheduled) {
      throw new IllegalStateException(
          "Shown hosted terms version is neither operative nor scheduled");
    }
    IndividualHostedTermsAcceptance accepted =
        repository.insertAcceptance(UUID.randomUUID(), action, party, shown, actionBytes);
    return accepted;
  }

  private CurrentnessEvidence currentnessInTransaction(UUID scopeId, UUID partyId) {
    UUID accountId = repository.lockPartyAccount(partyId);
    IndividualCreatorPartySource party = individualParties.readIndividualSource(partyId, accountId);
    if (!party.locallyVerified()) {
      throw new IllegalStateException(
          "Current individual identity evidence is unavailable or unverified");
    }
    HostedTermsRepository.ScopeSnapshot scope = repository.lockScope(scopeId);
    HostedTermsCatalogVersion current = scope.current();
    if (current == null || current.effectiveAt().isAfter(scope.databaseNow().toInstant())) {
      throw new IllegalStateException("No operative hosted terms version is available");
    }
    Instant validUntil = null;
    HostedTermsRepository.PublicationOperation pending = scope.unsettledPublication();
    if (pending != null) {
      HostedTermsCatalogVersion next = repository.readCandidate(pending);
      validUntil = next.effectiveAt();
      if (pending.status() == HostedTermsRepository.PublicationStatus.PENDING_OWNER_SETTLEMENT
          || !next.effectiveAt().isAfter(scope.databaseNow().toInstant())) {
        throw new IllegalStateException(
            "Disclosed hosted-terms effective date has arrived; currentness is unavailable until owner settlement");
      }
    }
    IndividualHostedTermsAcceptance acceptance =
        repository
            .readCurrentAcceptance(partyId, scopeId, current)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No current affirmative party-wide hosted terms acceptance"));
    byte[] currentParty = HostedTermsEncoding.individualParty(party);
    if (!acceptance.accountId().equals(accountId)
        || !acceptance.creatorPartyId().equals(partyId)
        || !acceptance.hostedScopeId().equals(scopeId)
        || !Arrays.equals(acceptance.individualPartySource(), currentParty)
        || !CreatorPartyEncoding.digest(currentParty)
            .equals(acceptance.individualPartySourceDigest())
        || acceptance.materialGeneration() != current.materialGeneration()
        || !acceptance.operatorLegalIdentity().equals(current.operatorLegalIdentity())
        || acceptance.operatorIdentityVersion() != current.operatorIdentityVersion()) {
      throw new IllegalStateException(
          "Acceptance or individual identity source is stale or changed");
    }
    return new CurrentnessEvidence(
        scopeId,
        partyId,
        accountId,
        current.versionId(),
        current.documentDigest(),
        current.operatorLegalIdentity(),
        current.operatorIdentityVersion(),
        current.sourceVersion(),
        current.materialGeneration(),
        acceptance.evidenceId(),
        acceptance.termsVersionId(),
        acceptance.documentDigest(),
        validUntil);
  }

  private void requireOwnVerifiedIndividual(
      AcceptanceAction action, IndividualCreatorPartySource party) {
    if (!party.creatorPartyId().equals(action.creatorPartyId())
        || !party.accountId().equals(action.accountId())
        || party.verificationStatus() != VerificationStatus.VERIFIED) {
      throw new IllegalStateException(
          "Only the authenticated owner of their own locally verified individual party may accept");
    }
  }

  private DraftAuthorizationFenceRepository.SourceChange sourceChange(
      UUID changeId, HostedTermsCatalogVersion prior, HostedTermsCatalogVersion next) {
    SourceEvidence source =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS,
            prior.hostedScopeId().toString(),
            Long.toString(prior.materialGeneration()),
            Long.toString(prior.sourceVersion()),
            null,
            null,
            HostedTermsEncoding.catalog(prior));
    return new DraftAuthorizationFenceRepository.SourceChange(
        changeId, List.of(source), HostedTermsEncoding.catalog(next));
  }

  private PublicationResult result(
      HostedTermsRepository.PublicationStatus status, HostedTermsCatalogVersion candidate) {
    return new PublicationResult(status, candidate, HostedTermsEncoding.catalog(candidate));
  }

  private PublicationResult result(
      HostedTermsRepository.PublicationOperation operation, HostedTermsCatalogVersion candidate) {
    return result(operation.status(), candidate);
  }

  public interface OperatorPublicationAuthority {
    PublicationEvidence resolve(UUID stableRequestId);
  }

  public interface IndividualAcceptanceAuthority {
    AcceptanceAction resolve(UUID stableActionRequestId);
  }

  /** Typed result of an independently trusted operator publication/audit owner. */
  public record PublicationEvidence(
      UUID hostedScopeId,
      String operatorLegalIdentity,
      long operatorIdentityVersion,
      byte[] documentBytes,
      HostedTermsCatalogVersion.Materiality materiality,
      String materialityEvidenceReference,
      Long materialityEvidenceVersion,
      String publicationEvidenceReference,
      long publicationEvidenceVersion,
      String noticeEvidenceReference,
      long noticeEvidenceVersion,
      Instant effectiveAt) {
    public PublicationEvidence {
      HostedTermsEncoding.requireUuid(hostedScopeId, "hosted scope");
      operatorLegalIdentity = HostedTermsEncoding.requireText(operatorLegalIdentity, 2048);
      if (operatorIdentityVersion <= 0) {
        throw new IllegalArgumentException("Positive exact operator identity version required");
      }
      documentBytes = HostedTermsEncoding.requireDocument(documentBytes);
      Objects.requireNonNull(materiality, "audited materiality classification is required");
      if (materiality == HostedTermsCatalogVersion.Materiality.INITIAL) {
        if (materialityEvidenceReference != null || materialityEvidenceVersion != null) {
          throw new IllegalArgumentException(
              "Initial catalog birth has no fabricated materiality predecessor");
        }
      } else {
        materialityEvidenceReference =
            HostedTermsEncoding.requireText(materialityEvidenceReference, 512);
        if (materialityEvidenceVersion == null || materialityEvidenceVersion <= 0) {
          throw new IllegalArgumentException(
              "Audited materiality evidence requires a positive version");
        }
      }
      publicationEvidenceReference =
          HostedTermsEncoding.requireText(publicationEvidenceReference, 512);
      noticeEvidenceReference = HostedTermsEncoding.requireText(noticeEvidenceReference, 512);
      if (publicationEvidenceVersion <= 0 || noticeEvidenceVersion <= 0) {
        throw new IllegalArgumentException(
            "Publication and notice evidence versions must be positive");
      }
      Objects.requireNonNull(effectiveAt, "explicit disclosed effective instant is required");
      if (effectiveAt.getNano() % 1_000 != 0) {
        throw new IllegalArgumentException(
            "Disclosed effective instant must preserve PostgreSQL microsecond precision");
      }
    }

    @Override
    public byte[] documentBytes() {
      return documentBytes.clone();
    }
  }

  /** Typed action returned only by an authenticated individual-action owner. */
  public record AcceptanceAction(
      UUID actionRequestId,
      boolean affirmative,
      UUID accountId,
      UUID creatorPartyId,
      UUID hostedScopeId,
      UUID shownVersionId,
      String shownDocumentDigest,
      String shownOperatorLegalIdentity,
      long shownOperatorIdentityVersion,
      String authenticatedActionReference,
      long authenticatedActionVersion,
      OffsetDateTime actionCreatedAt) {
    public AcceptanceAction {
      HostedTermsEncoding.requireUuid(actionRequestId, "acceptance action request");
      HostedTermsEncoding.requireUuid(accountId, "authenticated Account");
      HostedTermsEncoding.requireUuid(creatorPartyId, "authenticated creator party");
      HostedTermsEncoding.requireUuid(hostedScopeId, "hosted scope");
      HostedTermsEncoding.requireUuid(shownVersionId, "shown catalog version");
      shownDocumentDigest = HostedTermsEncoding.requireDigest(shownDocumentDigest);
      shownOperatorLegalIdentity =
          HostedTermsEncoding.requireText(shownOperatorLegalIdentity, 2048);
      if (shownOperatorIdentityVersion <= 0 || authenticatedActionVersion <= 0) {
        throw new IllegalArgumentException(
            "Positive shown operator and action evidence versions required");
      }
      authenticatedActionReference =
          HostedTermsEncoding.requireText(authenticatedActionReference, 512);
      Objects.requireNonNull(actionCreatedAt, "trusted action evidence time is required");
    }
  }

  public record PublicationResult(
      HostedTermsRepository.PublicationStatus status,
      HostedTermsCatalogVersion candidate,
      byte[] exactCandidateEvidence) {
    public PublicationResult {
      Objects.requireNonNull(status);
      Objects.requireNonNull(candidate);
      exactCandidateEvidence = HostedTermsEncoding.requireBytes(exactCandidateEvidence);
      if (!Arrays.equals(exactCandidateEvidence, HostedTermsEncoding.catalog(candidate))) {
        throw new IllegalArgumentException(
            "Publication result differs from exact immutable catalog");
      }
    }

    @Override
    public byte[] exactCandidateEvidence() {
      return exactCandidateEvidence.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof PublicationResult that
          && status == that.status
          && candidate.equals(that.candidate)
          && Arrays.equals(exactCandidateEvidence, that.exactCandidateEvidence);
    }

    @Override
    public int hashCode() {
      return Objects.hash(status, candidate, Arrays.hashCode(exactCandidateEvidence));
    }
  }

  /** Not an owner commit token; deadline must be rechecked at consumer commit linearization. */
  public record CurrentnessEvidence(
      UUID hostedScopeId,
      UUID creatorPartyId,
      UUID accountId,
      UUID currentVersionId,
      String currentDocumentDigest,
      String operatorLegalIdentity,
      long operatorIdentityVersion,
      long sourceVersion,
      long materialGeneration,
      UUID acceptanceEvidenceId,
      UUID acceptedVersionId,
      String acceptedDocumentDigest,
      Instant validUntil) {
    public CurrentnessEvidence {
      HostedTermsEncoding.requireUuid(hostedScopeId, "hosted scope");
      HostedTermsEncoding.requireUuid(creatorPartyId, "creator party");
      HostedTermsEncoding.requireUuid(accountId, "party-owning Account");
      HostedTermsEncoding.requireUuid(currentVersionId, "current terms version");
      currentDocumentDigest = HostedTermsEncoding.requireDigest(currentDocumentDigest);
      operatorLegalIdentity = HostedTermsEncoding.requireText(operatorLegalIdentity, 2048);
      HostedTermsEncoding.requireUuid(acceptanceEvidenceId, "acceptance evidence");
      HostedTermsEncoding.requireUuid(acceptedVersionId, "accepted terms version");
      acceptedDocumentDigest = HostedTermsEncoding.requireDigest(acceptedDocumentDigest);
      if (operatorIdentityVersion <= 0 || sourceVersion <= 0 || materialGeneration <= 0) {
        throw new IllegalArgumentException("Positive current hosted terms source required");
      }
    }
  }
}
