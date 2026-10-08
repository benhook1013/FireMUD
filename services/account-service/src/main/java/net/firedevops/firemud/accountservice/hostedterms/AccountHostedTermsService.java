package net.firedevops.firemud.accountservice.hostedterms;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
  private final HostedTermsEnvironmentBindingRepository environmentBindings;
  private final EnvironmentBindingPublicationAuthority environmentBindingPublicationAuthority;
  private final CurrentEnvironmentBoundaryAuthority currentEnvironmentBoundaryAuthority;
  private final TransactionTemplate ownerTransaction;

  public AccountHostedTermsService(
      PlatformTransactionManager transactionManager,
      HostedTermsRepository repository,
      IndividualCreatorPartyRepository individualParties,
      DraftAuthorizationFenceRepository draftFences,
      OperatorPublicationAuthority publicationAuthority,
      IndividualAcceptanceAuthority acceptanceAuthority) {
    this(
        transactionManager,
        repository,
        individualParties,
        draftFences,
        publicationAuthority,
        acceptanceAuthority,
        null,
        null,
        null);
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Trusted owner collaborators are retained privately and never exposed.")
  public AccountHostedTermsService(
      PlatformTransactionManager transactionManager,
      HostedTermsRepository repository,
      IndividualCreatorPartyRepository individualParties,
      DraftAuthorizationFenceRepository draftFences,
      OperatorPublicationAuthority publicationAuthority,
      IndividualAcceptanceAuthority acceptanceAuthority,
      HostedTermsEnvironmentBindingRepository environmentBindings,
      EnvironmentBindingPublicationAuthority environmentBindingPublicationAuthority,
      CurrentEnvironmentBoundaryAuthority currentEnvironmentBoundaryAuthority) {
    this.repository = Objects.requireNonNull(repository);
    this.individualParties = Objects.requireNonNull(individualParties);
    this.draftFences = Objects.requireNonNull(draftFences);
    this.publicationAuthority = Objects.requireNonNull(publicationAuthority);
    this.acceptanceAuthority = Objects.requireNonNull(acceptanceAuthority);
    this.environmentBindings = environmentBindings;
    this.environmentBindingPublicationAuthority = environmentBindingPublicationAuthority;
    this.currentEnvironmentBoundaryAuthority = currentEnvironmentBoundaryAuthority;
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

  /**
   * Resolves the authenticated environment-owner handoff before opening the Account transaction,
   * then exact-validates its catalog and predecessor while holding owner-local locks.
   */
  public EnvironmentBindingPublicationResult publishEnvironmentBinding(UUID requestId) {
    requireEnvironmentBindingPublicationAuthority();
    HostedTermsEncoding.requireUuid(requestId, "environment binding publication request");
    HostedTermsEnvironmentBinding.PublicationEvidence evidence =
        Objects.requireNonNull(
            environmentBindingPublicationAuthority.resolve(requestId),
            "authenticated environment-owner publication evidence");
    byte[] requestPayload = HostedTermsEnvironmentBindingEncoding.publication(requestId, evidence);
    return Objects.requireNonNull(
        ownerTransaction.execute(
            ignored -> publishEnvironmentBindingInTransaction(requestId, evidence, requestPayload)),
        "Account environment binding publication result");
  }

  /** Resumes only the exact durable environment-binding publication and source-change intent. */
  public EnvironmentBindingPublicationResult resumeEnvironmentBinding(UUID requestId) {
    requireEnvironmentBindingRepository();
    HostedTermsEncoding.requireUuid(requestId, "environment binding publication request");
    return Objects.requireNonNull(
        ownerTransaction.execute(
            ignored -> {
              HostedTermsEnvironmentBindingRepository.PublicationOperation operation =
                  environmentBindings
                      .readPublication(requestId, true)
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException(
                                  "Original environment binding publication is absent"));
              return advanceEnvironmentBinding(operation);
            }),
        "Account environment binding recovery result");
  }

  /**
   * Derives the official environment boundary from a trusted owner collaborator, then reads the
   * exact binding, current catalog, verified individual party and acceptance in one Account
   * READ_COMMITTED owner transaction. The result is currentness/source evidence, not permission.
   */
  public EnvironmentBoundCurrentness requireCurrentnessForCurrentEnvironment(UUID creatorPartyId) {
    HostedTermsEncoding.requireUuid(creatorPartyId, "creator party");
    CapturedEnvironmentBoundary captured = captureCurrentEnvironmentBoundary();
    return Objects.requireNonNull(
        ownerTransaction.execute(
            ignored -> requireCurrentnessInOwnerTransaction(captured, creatorPartyId)),
        "Account environment-bound currentness result");
  }

  /**
   * Obtains the trusted environment observation before any Account transaction begins. This
   * service-issued handle is source material only and cannot authorize a Draft mutation.
   */
  public CapturedEnvironmentBoundary captureCurrentEnvironmentBoundary() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Current environment boundary must be captured outside an Account transaction");
    }
    requireCurrentEnvironmentBoundaryAuthority();
    HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary environment =
        Objects.requireNonNull(
            currentEnvironmentBoundaryAuthority.currentBoundary(),
            "authenticated current environment boundary");
    return new CapturedEnvironmentBoundary(this, environment);
  }

  /**
   * Locks and rereads the persisted mapping, catalog, party, acceptance and deadline in the
   * caller's writable READ_COMMITTED Account transaction. No external owner is called here; the
   * returned evidence is not a commit token or proof of later external environment continuity.
   */
  public EnvironmentBoundCurrentness requireCurrentnessInOwnerTransaction(
      CapturedEnvironmentBoundary captured, UUID creatorPartyId) {
    if (captured == null || captured.owner != this) {
      throw new IllegalArgumentException("Current environment capture belongs to another owner");
    }
    HostedTermsEncoding.requireUuid(creatorPartyId, "creator party");
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable Account owner transaction required");
    }
    // lockHead performs the physical connection's writable READ_COMMITTED check before reading.
    return currentnessForEnvironmentInTransaction(captured.environment, creatorPartyId);
  }

  /** Opaque, immutable observation scoped to the issuing service instance. */
  public static final class CapturedEnvironmentBoundary {
    private final AccountHostedTermsService owner;
    private final HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary environment;

    private CapturedEnvironmentBoundary(
        AccountHostedTermsService owner,
        HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary environment) {
      this.owner = owner;
      this.environment = environment;
    }
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

  private EnvironmentBindingPublicationResult publishEnvironmentBindingInTransaction(
      UUID requestId,
      HostedTermsEnvironmentBinding.PublicationEvidence evidence,
      byte[] requestPayload) {
    environmentBindings.ensureHead(evidence.environmentBoundary());
    var operation =
        environmentBindings.claimPublication(
            requestId, evidence.environmentBoundary(), requestPayload);
    if (operation.status() == HostedTermsEnvironmentBindingRepository.PublicationStatus.COMMITTED) {
      return environmentBindingResult(operation, environmentBindings.readCandidate(operation));
    }
    if (operation.status()
        == HostedTermsEnvironmentBindingRepository.PublicationStatus.PENDING_OWNER_SETTLEMENT) {
      if (!Arrays.equals(operation.requestPayload(), requestPayload)) {
        throw new IllegalArgumentException("Binding retry differs from exact owner publication");
      }
      return advanceEnvironmentBinding(operation);
    }

    HostedTermsEnvironmentBindingRepository.HeadSnapshot head =
        environmentBindings.lockHead(evidence.environmentBoundary());
    if (head.unsettledPublication() != null
        && !head.unsettledPublication().requestId().equals(requestId)) {
      throw new IllegalStateException("Another environment binding change is pending");
    }
    HostedTermsEnvironmentBinding predecessor = head.current();
    if (!Objects.equals(
            evidence.predecessorBindingId(), predecessor == null ? null : predecessor.bindingId())
        || !Objects.equals(
            evidence.predecessorSourceVersion(),
            predecessor == null ? null : predecessor.sourceVersion())) {
      throw new IllegalArgumentException(
          "Authenticated environment publication does not extend exact current binding");
    }
    Map<UUID, HostedTermsRepository.ScopeSnapshot> catalogScopes =
        lockBindingCatalogScopes(predecessor, evidence.hostedScopeId());
    if (predecessor != null && catalogScopes.get(predecessor.hostedScopeId()).current() == null) {
      throw new IllegalStateException("Prior environment binding catalog source is unavailable");
    }
    HostedTermsRepository.ScopeSnapshot targetScope = catalogScopes.get(evidence.hostedScopeId());
    HostedTermsCatalogVersion targetCatalog = targetScope.current();
    requireExactBindingCatalog(evidence, targetCatalog, targetScope.databaseNow());

    boolean introducesFutureDeadline =
        hasFutureScheduledDeadline(targetScope)
            && (predecessor == null
                || !predecessor.hostedScopeId().equals(evidence.hostedScopeId()));
    if (introducesFutureDeadline) {
      List<SourceEvidence> protectedSources =
          predecessor == null
              ? List.of(catalogSourceEvidence(targetCatalog))
              : bindingSourceEvidence(predecessor, catalogScopes);
      draftFences.requireDisclosurePreparation(protectedSources);
    }
    HostedTermsEnvironmentBinding candidate =
        environmentBindings.insertCandidate(UUID.randomUUID(), requestId, evidence, predecessor);
    DraftAuthorizationFenceRepository.SourceChange change =
        predecessor == null
            ? null
            : bindingSourceChange(requestId, predecessor, catalogScopes, candidate);
    if (change != null) {
      boolean settled = draftFences.requestSourceChange(change);
      if (!settled || !draftFences.sourceMutationPermitted(change)) {
        environmentBindings.markOwnerSettlementPending(
            requestId, candidate, change.canonicalBytes());
        return environmentBindingResult(
            HostedTermsEnvironmentBindingRepository.PublicationStatus.PENDING_OWNER_SETTLEMENT,
            candidate);
      }
      environmentBindings.completePublication(
          requestId, candidate, change.canonicalBytes(), predecessor);
      draftFences.markSourceCommitted(change);
      return environmentBindingResult(
          HostedTermsEnvironmentBindingRepository.PublicationStatus.COMMITTED, candidate);
    }
    environmentBindings.completePublication(requestId, candidate, null, null);
    return environmentBindingResult(
        HostedTermsEnvironmentBindingRepository.PublicationStatus.COMMITTED, candidate);
  }

  private EnvironmentBindingPublicationResult advanceEnvironmentBinding(
      HostedTermsEnvironmentBindingRepository.PublicationOperation operation) {
    if (operation.status() == HostedTermsEnvironmentBindingRepository.PublicationStatus.COMMITTED) {
      return environmentBindingResult(operation, environmentBindings.readCandidate(operation));
    }
    if (operation.status() == HostedTermsEnvironmentBindingRepository.PublicationStatus.RECEIVED) {
      throw new IllegalStateException(
          "Binding publication intent is incomplete; retry the original trusted request");
    }
    if (operation.sourceChangeBinding() == null) {
      throw new IllegalStateException("Pending binding lacks its durable source-change intent");
    }
    HostedTermsEnvironmentBindingRepository.HeadSnapshot head =
        environmentBindings.lockHead(operation.environmentBoundary());
    HostedTermsEnvironmentBinding candidate = environmentBindings.readCandidate(operation);
    HostedTermsEnvironmentBinding predecessor = head.current();
    if (head.unsettledPublication() == null
        || !head.unsettledPublication().requestId().equals(operation.requestId())
        || !Objects.equals(
            candidate.predecessorBindingId(), predecessor == null ? null : predecessor.bindingId())
        || !Objects.equals(
            candidate.predecessorSourceVersion(),
            predecessor == null ? null : predecessor.sourceVersion())) {
      throw new IllegalStateException("Pending binding no longer matches exact environment head");
    }
    HostedTermsRepository.ScopeSnapshot targetScope =
        repository.lockScope(candidate.hostedScopeId());
    requireBindingCatalog(candidate, targetScope.current(), targetScope.databaseNow());
    DraftAuthorizationFenceRepository.SourceChange change =
        DraftAuthorizationFenceRepository.SourceChange.fromStored(operation.sourceChangeBinding());
    boolean settled = draftFences.requestSourceChange(change);
    if (!settled || !draftFences.sourceMutationPermitted(change)) {
      return environmentBindingResult(
          HostedTermsEnvironmentBindingRepository.PublicationStatus.PENDING_OWNER_SETTLEMENT,
          candidate);
    }
    environmentBindings.completePublication(
        operation.requestId(), candidate, change.canonicalBytes(), predecessor);
    draftFences.markSourceCommitted(change);
    return environmentBindingResult(
        HostedTermsEnvironmentBindingRepository.PublicationStatus.COMMITTED, candidate);
  }

  private Map<UUID, HostedTermsRepository.ScopeSnapshot> lockBindingCatalogScopes(
      HostedTermsEnvironmentBinding predecessor, UUID targetScopeId) {
    Map<UUID, HostedTermsRepository.ScopeSnapshot> snapshots = new LinkedHashMap<>();
    java.util.stream.Stream.of(
            predecessor == null ? targetScopeId : predecessor.hostedScopeId(), targetScopeId)
        .distinct()
        .sorted(Comparator.comparing(UUID::toString))
        .forEach(scopeId -> snapshots.put(scopeId, repository.lockScope(scopeId)));
    return Map.copyOf(snapshots);
  }

  private DraftAuthorizationFenceRepository.SourceChange bindingSourceChange(
      UUID changeId,
      HostedTermsEnvironmentBinding predecessor,
      Map<UUID, HostedTermsRepository.ScopeSnapshot> catalogScopes,
      HostedTermsEnvironmentBinding candidate) {
    return new DraftAuthorizationFenceRepository.SourceChange(
        changeId,
        bindingSourceEvidence(predecessor, catalogScopes),
        HostedTermsEnvironmentBindingEncoding.receipt(candidate));
  }

  private List<SourceEvidence> bindingSourceEvidence(
      HostedTermsEnvironmentBinding predecessor,
      Map<UUID, HostedTermsRepository.ScopeSnapshot> catalogScopes) {
    Map<String, SourceEvidence> sources = new LinkedHashMap<>();
    SourceEvidence bindingSource = predecessor.sourceEvidence();
    sources.put(bindingSource.key(), bindingSource);
    catalogScopes.values().stream()
        .map(HostedTermsRepository.ScopeSnapshot::current)
        .filter(Objects::nonNull)
        .map(this::catalogSourceEvidence)
        .forEach(source -> sources.put(source.key(), source));
    return sources.values().stream().sorted(Comparator.comparing(SourceEvidence::key)).toList();
  }

  private SourceEvidence catalogSourceEvidence(HostedTermsCatalogVersion catalog) {
    return new SourceEvidence(
        SourceKind.HOSTED_TERMS,
        catalog.hostedScopeId().toString(),
        Long.toString(catalog.materialGeneration()),
        Long.toString(catalog.sourceVersion()),
        null,
        null,
        HostedTermsEncoding.catalog(catalog));
  }

  private boolean hasFutureScheduledDeadline(HostedTermsRepository.ScopeSnapshot scope) {
    HostedTermsRepository.PublicationOperation pending = scope.unsettledPublication();
    if (pending == null || pending.status() != HostedTermsRepository.PublicationStatus.SCHEDULED) {
      return false;
    }
    return repository.readCandidate(pending).effectiveAt().isAfter(scope.databaseNow().toInstant());
  }

  private void requireExactBindingCatalog(
      HostedTermsEnvironmentBinding.PublicationEvidence evidence,
      HostedTermsCatalogVersion current,
      OffsetDateTime databaseNow) {
    if (current == null || current.effectiveAt().isAfter(databaseNow.toInstant())) {
      throw new IllegalStateException("Environment binding requires a current due terms catalog");
    }
    if (!current.versionId().equals(evidence.catalogVersionId())
        || !current.hostedScopeId().equals(evidence.hostedScopeId())
        || current.sourceVersion() != evidence.catalogSourceVersion()
        || !current.operatorLegalIdentity().equals(evidence.operatorLegalIdentity())
        || current.operatorIdentityVersion() != evidence.operatorIdentityVersion()) {
      throw new IllegalArgumentException(
          "Environment publication differs from exact current catalog and legal operator");
    }
  }

  private void requireBindingCatalog(
      HostedTermsEnvironmentBinding binding,
      HostedTermsCatalogVersion current,
      OffsetDateTime databaseNow) {
    if (current == null || current.effectiveAt().isAfter(databaseNow.toInstant())) {
      throw new IllegalStateException("Environment binding catalog is not currently operative");
    }
    if (!current.versionId().equals(binding.catalogVersionId())
        || !current.hostedScopeId().equals(binding.hostedScopeId())
        || current.sourceVersion() != binding.catalogSourceVersion()
        || !current.operatorLegalIdentity().equals(binding.operatorLegalIdentity())
        || current.operatorIdentityVersion() != binding.operatorIdentityVersion()) {
      throw new IllegalStateException("Environment binding points at changed catalog authority");
    }
  }

  private EnvironmentBoundCurrentness currentnessForEnvironmentInTransaction(
      HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary environment, UUID creatorPartyId) {
    HostedTermsEnvironmentBindingRepository.HeadSnapshot head =
        environmentBindings.lockHead(environment.environmentBoundary());
    if (head.unsettledPublication() != null) {
      throw new IllegalStateException("Environment binding source change is unresolved");
    }
    HostedTermsEnvironmentBinding binding = head.current();
    if (binding == null) {
      throw new IllegalStateException(
          "No authenticated environment-to-hosted-scope binding exists");
    }
    CurrentnessEvidence terms = currentnessInTransaction(binding.hostedScopeId(), creatorPartyId);
    HostedTermsRepository.ScopeSnapshot lockedScope = repository.lockScope(binding.hostedScopeId());
    HostedTermsCatalogVersion catalog = lockedScope.current();
    requireBindingCatalog(binding, catalog, lockedScope.databaseNow());
    if (!catalog.versionId().equals(terms.currentVersionId())) {
      throw new IllegalStateException("Currentness and locked catalog readback differ");
    }
    IndividualCreatorPartySource party =
        individualParties.readIndividualSource(terms.creatorPartyId(), terms.accountId());
    if (!party.locallyVerified()) {
      throw new IllegalStateException(
          "Current individual party source is unavailable or unverified");
    }
    IndividualHostedTermsAcceptance acceptance =
        repository
            .readCurrentAcceptance(terms.creatorPartyId(), binding.hostedScopeId(), catalog)
            .orElseThrow(
                () -> new IllegalStateException("Current affirmative acceptance is unavailable"));
    if (!acceptance.evidenceId().equals(terms.acceptanceEvidenceId())) {
      throw new IllegalStateException("Current acceptance readback changed during capture");
    }
    byte[] exactTermsSource =
        HostedTermsEncoding.currentnessSource(catalog, acceptance, terms.disclosedDeadline());
    if (!Arrays.equals(exactTermsSource, terms.exactCurrentnessSource())) {
      throw new IllegalStateException(
          "Currentness source differs from exact locked acceptance and deadline evidence");
    }
    SourceEvidence partySource =
        new SourceEvidence(
            SourceKind.CREATOR_PARTY,
            party.creatorPartyId().toString(),
            Long.toString(party.identityVersion()),
            Long.toString(party.sourceVersion()),
            null,
            null,
            CreatorPartyEncoding.party(party));
    SourceEvidence termsSource =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS,
            catalog.hostedScopeId().toString(),
            Long.toString(catalog.materialGeneration()),
            Long.toString(catalog.sourceVersion()),
            null,
            null,
            exactTermsSource);
    List<SourceEvidence> sources =
        java.util.stream.Stream.of(binding.sourceEvidence(), termsSource, partySource)
            .sorted(Comparator.comparing(SourceEvidence::key))
            .toList();
    return new EnvironmentBoundCurrentness(environment, binding, terms, sources);
  }

  private EnvironmentBindingPublicationResult environmentBindingResult(
      HostedTermsEnvironmentBindingRepository.PublicationOperation operation,
      HostedTermsEnvironmentBinding candidate) {
    return environmentBindingResult(operation.status(), candidate);
  }

  private EnvironmentBindingPublicationResult environmentBindingResult(
      HostedTermsEnvironmentBindingRepository.PublicationStatus status,
      HostedTermsEnvironmentBinding candidate) {
    return new EnvironmentBindingPublicationResult(
        status, candidate, HostedTermsEnvironmentBindingEncoding.receipt(candidate));
  }

  private void requireEnvironmentBindingRepository() {
    if (environmentBindings == null) {
      throw new IllegalStateException("Account environment-binding persistence is unavailable");
    }
  }

  private void requireEnvironmentBindingPublicationAuthority() {
    requireEnvironmentBindingRepository();
    if (environmentBindingPublicationAuthority == null) {
      throw new IllegalStateException(
          "Authenticated environment publication authority is unavailable");
    }
  }

  private void requireCurrentEnvironmentBoundaryAuthority() {
    requireEnvironmentBindingRepository();
    if (currentEnvironmentBoundaryAuthority == null) {
      throw new IllegalStateException("Authenticated current environment boundary is unavailable");
    }
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
    if (candidate.effectiveAt().isAfter(scope.databaseNow().toInstant())) {
      if (scope.current() != null) {
        draftFences.requireDisclosurePreparation(List.of(catalogSourceEvidence(scope.current())));
      }
      repository.insertCandidate(candidate);
      repository.markScheduled(requestId, candidate);
      return result(HostedTermsRepository.PublicationStatus.SCHEDULED, candidate);
    }
    repository.insertCandidate(candidate);
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
    HostedTermsCatalogVersion disclosedDeadline = null;
    HostedTermsRepository.PublicationOperation pending = scope.unsettledPublication();
    if (pending != null) {
      HostedTermsCatalogVersion next = repository.readCandidate(pending);
      validUntil = next.effectiveAt();
      disclosedDeadline = next;
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
    byte[] exactCurrentnessSource =
        HostedTermsEncoding.currentnessSource(current, acceptance, disclosedDeadline);
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
        validUntil,
        disclosedDeadline,
        exactCurrentnessSource);
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

  /** Authenticated owner handoff; the result is obtained before any Account DB lock is taken. */
  public interface EnvironmentBindingPublicationAuthority {
    HostedTermsEnvironmentBinding.PublicationEvidence resolve(UUID stableRequestId);
  }

  /** Current official boundary source; no boundary identifier is accepted from the caller. */
  public interface CurrentEnvironmentBoundaryAuthority {
    HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary currentBoundary();
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

  /** Immutable binding receipt result; PENDING_OWNER_SETTLEMENT is never current permission. */
  public record EnvironmentBindingPublicationResult(
      HostedTermsEnvironmentBindingRepository.PublicationStatus status,
      HostedTermsEnvironmentBinding candidate,
      byte[] exactReceipt) {
    public EnvironmentBindingPublicationResult {
      Objects.requireNonNull(status);
      Objects.requireNonNull(candidate);
      exactReceipt = HostedTermsEncoding.requireBytes(exactReceipt);
      if (!Arrays.equals(exactReceipt, HostedTermsEnvironmentBindingEncoding.receipt(candidate))) {
        throw new IllegalArgumentException("Binding result differs from exact immutable receipt");
      }
    }

    @Override
    public byte[] exactReceipt() {
      return exactReceipt.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof EnvironmentBindingPublicationResult that
          && status == that.status
          && candidate.equals(that.candidate)
          && Arrays.equals(exactReceipt, that.exactReceipt);
    }

    @Override
    public int hashCode() {
      return Objects.hash(status, candidate, Arrays.hashCode(exactReceipt));
    }
  }

  /** Combined currentness and exact independent source vector; not a Game Design commit token. */
  public record EnvironmentBoundCurrentness(
      HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary environment,
      HostedTermsEnvironmentBinding binding,
      CurrentnessEvidence terms,
      List<SourceEvidence> sourceEvidence) {
    public EnvironmentBoundCurrentness {
      Objects.requireNonNull(environment);
      Objects.requireNonNull(binding);
      Objects.requireNonNull(terms);
      sourceEvidence =
          List.copyOf(Objects.requireNonNull(sourceEvidence)).stream()
              .sorted(Comparator.comparing(SourceEvidence::key))
              .toList();
      if (!environment.environmentBoundary().equals(binding.environmentBoundary())
          || !terms.hostedScopeId().equals(binding.hostedScopeId())
          || !terms.currentVersionId().equals(binding.catalogVersionId())
          || terms.sourceVersion() != binding.catalogSourceVersion()
          || !terms.operatorLegalIdentity().equals(binding.operatorLegalIdentity())
          || terms.operatorIdentityVersion() != binding.operatorIdentityVersion()) {
        throw new IllegalArgumentException(
            "Environment binding, current catalog and party acceptance do not agree");
      }
      if (sourceEvidence.size() != 3
          || sourceEvidence.stream().map(SourceEvidence::key).distinct().count() != 3
          || sourceEvidence.stream()
              .noneMatch(
                  source ->
                      source.key().equals(binding.sourceEvidence().key())
                          && Arrays.equals(
                              source.canonicalBytes(), binding.sourceEvidence().canonicalBytes()))
          || sourceEvidence.stream()
              .noneMatch(
                  source ->
                      source.kind() == SourceKind.HOSTED_TERMS
                          && source.scopeId().equals(terms.hostedScopeId().toString())
                          && Objects.equals(
                              source.generation(), Long.toString(terms.materialGeneration()))
                          && source.sourceVersion().equals(Long.toString(terms.sourceVersion()))
                          && Arrays.equals(source.evidence(), terms.exactCurrentnessSource()))
          || sourceEvidence.stream()
              .noneMatch(
                  source ->
                      source.kind() == SourceKind.CREATOR_PARTY
                          && source.scopeId().equals(terms.creatorPartyId().toString())
                          && source.generation() != null
                          && source.sourceVersion() != null)) {
        throw new IllegalArgumentException(
            "Bound currentness must retain exact binding, catalog/acceptance and party sources");
      }
    }

    public List<SourceEvidence> sourceEvidence() {
      return List.copyOf(sourceEvidence);
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
      Instant validUntil,
      HostedTermsCatalogVersion disclosedDeadline,
      byte[] exactCurrentnessSource) {
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
      exactCurrentnessSource = HostedTermsEncoding.requireBytes(exactCurrentnessSource);
      if (operatorIdentityVersion <= 0 || sourceVersion <= 0 || materialGeneration <= 0) {
        throw new IllegalArgumentException("Positive current hosted terms source required");
      }
      if ((validUntil == null) != (disclosedDeadline == null)
          || (disclosedDeadline != null
              && (!validUntil.equals(disclosedDeadline.effectiveAt())
                  || !disclosedDeadline.hostedScopeId().equals(hostedScopeId)))) {
        throw new IllegalArgumentException(
            "Disclosed deadline time and exact immutable catalog evidence must agree");
      }
    }

    @Override
    public byte[] exactCurrentnessSource() {
      return exactCurrentnessSource.clone();
    }
  }
}
