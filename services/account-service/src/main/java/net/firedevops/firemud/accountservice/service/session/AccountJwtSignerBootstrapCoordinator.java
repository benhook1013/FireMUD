package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessPromotionProof;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveJwksPromotionObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveJwksPromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CommittedSignerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.DesiredState;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.EnrollmentIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationPhase;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PreparedGenerationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PreparedPromotion;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionPreparation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.BindingIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.CasObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ConfigMapSnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationPurpose;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit Account-owned enrollment and durable-generation reconciliation for interim JWT signer
 * materialization.
 *
 * <p>This coordinator is not scheduled or run at application startup. Enrollment derives only from
 * Account's protected materializer binding, protected JWKS API binding, and a live observation of
 * the fixed public ConfigMap. Enrollment initializes the short Account state row without selecting
 * a generation request. Durable-generation reconciliation reads only an already selected request.
 * Those two bootstrap paths do not commit signer promotion or treat mounted correspondence as
 * validator readiness; the separate prepared-promotion path below requires the private receipt and
 * complete current readiness evidence before it owns the public ACTIVE CAS and durable commit.
 */
public final class AccountJwtSignerBootstrapCoordinator {
  private static final String JWKS_DATA_KEY = AccountJwtJwksPublicationRepository.JWKS_DATA_KEY;

  private final AccountJwtSignerMaterializerTrustBinding materializerTrustBinding;
  private final AccountJwtJwksConfigMapClient configMapClient;
  private final AccountJwtSignerDesiredStateRepository desiredStateRepository;
  private final AccountJwtJwksPrepublicationService prepublicationService;
  private final AccountJwtReadinessProbeRepository readinessRepository;
  private final ObjectProvider<AccountJwtValidatorInventorySource> inventorySourceProvider;
  private final TransactionTemplate accountTransaction;

  public AccountJwtSignerBootstrapCoordinator(
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtJwksConfigMapClient configMapClient,
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtJwksPrepublicationService prepublicationService,
      TransactionTemplate accountTransaction) {
    this(
        materializerTrustBinding,
        configMapClient,
        desiredStateRepository,
        prepublicationService,
        null,
        null,
        accountTransaction);
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The Account readiness repository is a live Spring owner used for transactional proof reads.")
  public AccountJwtSignerBootstrapCoordinator(
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtJwksConfigMapClient configMapClient,
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtJwksPrepublicationService prepublicationService,
      AccountJwtReadinessProbeRepository readinessRepository,
      ObjectProvider<AccountJwtValidatorInventorySource> inventorySourceProvider,
      TransactionTemplate accountTransaction) {
    this.materializerTrustBinding =
        Objects.requireNonNull(
            materializerTrustBinding, "Protected materializer trust is required");
    this.configMapClient =
        Objects.requireNonNull(configMapClient, "Protected JWKS client is required");
    this.desiredStateRepository =
        Objects.requireNonNull(desiredStateRepository, "Account signer repository is required");
    this.prepublicationService =
        Objects.requireNonNull(prepublicationService, "Account prepublication owner is required");
    this.readinessRepository = readinessRepository;
    this.inventorySourceProvider = inventorySourceProvider;
    this.accountTransaction =
        Objects.requireNonNull(accountTransaction, "Account transaction template is required");
    this.accountTransaction.setReadOnly(false);
  }

  /**
   * Performs only the authenticated, finite enrollment step. It does not select a generation
   * request, advance a signer phase, publish JWKS, or inspect mounted private material.
   */
  public EnrollmentAcknowledgement enrollOnce() {
    EnrollmentSources sources = captureEnrollmentSources();
    DesiredState initialized = initializeEnrollment(sources);
    EnrollmentIdentity enrolled =
        initialized
            .enrollmentIdentity()
            .orElseThrow(
                () -> new BootstrapOperationException(FailureCode.ACCOUNT_STATE_AMBIGUOUS));
    return acknowledgement(sources.materializerBinding(), sources.apiIdentity(), enrolled);
  }

  /**
   * Reconciles publication and mounted correspondence for the exact current generated receipt. The
   * authenticated RPC owner calls this only after its short generation-read transaction has
   * returned {@code GENERATION_RECORDED}; this method never creates a new operation.
   */
  public BootstrapProgress reconcileCurrentGenerationOnce(UUID expectedOperationId) {
    Objects.requireNonNull(expectedOperationId, "Expected generation operation is required");
    EnrollmentSources sources = captureEnrollmentSources();
    GenerationRequest request = readCurrentGenerationRequest(sources);
    if (!expectedOperationId.equals(request.operationId())
        || request.phase() != GenerationPhase.GENERATION_RECORDED) {
      throw new BootstrapOperationException(FailureCode.ACCOUNT_OWNER_OPERATION_MISMATCH);
    }
    return reconcileSelectedGeneration(sources, request);
  }

  /**
   * Enters PREPARED only for the exact current durable generation after refreshed publication,
   * mounted correspondence, and owner-verified live validator closure. The materializer's
   * authenticated promotion-request RPC is the only production caller. All API, filesystem, and
   * Kubernetes reads finish before the short Account transaction that obtains the private proof and
   * performs the owner CAS.
   */
  public Optional<PreparedPromotion> prepareCurrentPromotionOnce() {
    if (readinessRepository == null || inventorySourceProvider == null) {
      throw new BootstrapOperationException(FailureCode.VALIDATOR_INVENTORY_UNAVAILABLE);
    }
    EnrollmentSources sources = captureEnrollmentSources();
    DesiredState enrolled = readEnrollmentState(sources);
    if (enrolled.generationOperationId().isEmpty()) {
      requireUnselectedInitialEnrollment(enrolled, sources);
      requireUnchangedProtectedBindings(sources);
      return Optional.empty();
    }
    GenerationRequest request = readCurrentGenerationRequest(sources);
    if (request.phase() != GenerationPhase.GENERATION_RECORDED) {
      if (request.phase() == GenerationPhase.OBSERVE_PRIVATE_SECRET
          || request.phase() == GenerationPhase.GENERATE_PENDING) {
        requireUnchangedProtectedBindings(sources);
        return Optional.empty();
      }
      throw new BootstrapOperationException(FailureCode.ACCOUNT_OWNER_OPERATION_MISMATCH);
    }

    BootstrapProgress refreshed = reconcileSelectedGeneration(sources, request);
    if (refreshed.stage() != Stage.BLOCKED_FOR_VALIDATOR_ACCEPTANCE
        || !refreshed.publicKeyPrepublished()
        || !refreshed.mountedKeyCorrespondence()) {
      return Optional.empty();
    }

    InventorySnapshot liveInventory = observeCurrentValidatorInventory(sources, request);
    requireUnchangedProtectedBindings(sources);
    PreparedPromotion prepared =
        accountTransaction.execute(
            status -> {
              requireUnchangedProtectedBindings(sources);
              var proof =
                  readinessRepository.readCurrentPromotionPrerequisites(
                      sources.materializerBinding().accountBinding(),
                      trustFence(sources.materializerBinding()),
                      request.operationId(),
                      liveInventory);
              if (proof.isEmpty()) {
                return null;
              }
              ReadinessPromotionProof verifiedProof = proof.orElseThrow();
              DesiredState state =
                  desiredStateRepository.read(sources.materializerBinding().accountBinding());
              EnrollmentIdentity enrollment =
                  state
                      .enrollmentIdentity()
                      .orElseThrow(
                          () ->
                              new BootstrapOperationException(FailureCode.ACCOUNT_STATE_AMBIGUOUS));
              var publication = verifiedProof.publication();
              PromotionPreparation preparation =
                  new PromotionPreparation(
                      verifiedProof.generationResult().operationId(),
                      verifiedProof.generationResult(),
                      enrollment,
                      publication.intent().apiBindingDigest(),
                      publication.intent().apiConfigRevision(),
                      publication.intent().configMapUid(),
                      publication.receipt().observedResourceVersion(),
                      publication.intent().intentDigest(),
                      publication.receipt().receiptDigest(),
                      publication.mountedCorrespondence().observationDigest(),
                      verifiedProof.plan().planDigest(),
                      verifiedProof.readinessEvidenceDigest(),
                      publication.intent().jwksJson());
              PreparedPromotion persisted =
                  desiredStateRepository.prepareCurrentGeneration(
                      sources.materializerBinding().accountBinding(),
                      trustFence(sources.materializerBinding()),
                      preparation,
                      verifiedProof);
              PreparedGenerationEvidence readback =
                  desiredStateRepository.readPreparedGenerationForRecovery(
                      sources.materializerBinding().accountBinding(),
                      trustFence(sources.materializerBinding()));
              if (!readback.promotion().operationId().equals(persisted.operationId())
                  || !readback.promotion().requestDigest().equals(persisted.requestDigest())
                  || !readback.promotion().generationOperationId().equals(request.operationId())
                  || !readback.generationResult().equals(verifiedProof.generationResult())
                  || !"PREPARED".equals(persisted.status())) {
                throw new BootstrapOperationException(FailureCode.ACCOUNT_STATE_AMBIGUOUS);
              }
              requireUnchangedProtectedBindings(sources);
              return persisted;
            });
    if (prepared == null) {
      return Optional.empty();
    }
    requireUnchangedProtectedBindings(sources);
    return Optional.of(prepared);
  }

  /**
   * Completes only the exact Account PREPARED operation whose authenticated private-slot receipt
   * has already been recorded. Account owns the public ConfigMap ACTIVE CAS and terminal durable
   * commit; this finite call is never started at boot or by a scheduler.
   */
  public void reconcilePreparedPromotionOnce(
      PrivatePromotionObservation observation, PrivatePromotionReceipt expectedReceipt) {
    Objects.requireNonNull(observation, "Exact private promotion observation is required");
    Objects.requireNonNull(expectedReceipt, "Durable private promotion receipt is required");
    Binding binding = currentMaterializerBinding();
    BindingIdentity apiIdentity = currentApiIdentity();
    requireMatchingProtectedPins(binding, apiIdentity);
    requireReceiptIdentity(observation, expectedReceipt);

    PromotionOwnerSnapshot start =
        readPromotionOwnerSnapshot(binding, apiIdentity, observation, expectedReceipt);
    requireUnchangedProtectedBindings(binding, apiIdentity);
    if (!expectedReceipt.equals(start.privateReceipt())) {
      throw new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
          "Account private promotion receipt changed during reconciliation");
    }
    if (start.committedEvidence() != null) {
      return;
    }
    if (readinessRepository == null) {
      throw new BootstrapOperationException(FailureCode.VALIDATOR_INVENTORY_UNAVAILABLE);
    }

    PreparedGenerationEvidence prepared = start.preparedEvidence();
    PromotionOperationEvidence promotion = prepared.promotion();
    ObservationContext context = observationContext(promotion);
    InventorySnapshot beforeInventory = observePreparedInventory(context);
    requireUnchangedProtectedBindings(binding, apiIdentity);

    ConfigMapSnapshot observedConfigMap =
        prepublicationService.observePreparedCurrentKeyCorrespondence(promotion);
    requireUnchangedProtectedBindings(binding, apiIdentity);

    readPreparedPromotionProof(
        binding, apiIdentity, observation, expectedReceipt, promotion, beforeInventory);
    requireUnchangedProtectedBindings(binding, apiIdentity);
    requireExpectedPublicSnapshot(promotion, observedConfigMap);
    requireUnchangedProtectedBindings(binding, apiIdentity);

    CasObservation activeReadback =
        configMapClient.publishActiveProjection(
            observedConfigMap,
            promotion.expectedPublicResourceVersion(),
            java.util.Map.of(
                JWKS_DATA_KEY,
                promotion.expectedPublicJwksJson(),
                AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                promotion.expectedActiveMarkerJson()));
    requireExactActiveReadback(promotion, activeReadback);
    requireUnchangedProtectedBindings(binding, apiIdentity);

    ActiveJwksPromotionObservation activeObservation =
        new ActiveJwksPromotionObservation(
            activeReadback.uid(),
            activeReadback.priorResourceVersion(),
            activeReadback.resourceVersion(),
            promotion.expectedPublicJwksJson(),
            promotion.expectedActiveMarkerJson());
    ActiveJwksPromotionReceipt activeReceipt =
        accountTransaction.execute(
            status -> {
              PrivatePromotionReceipt currentPrivateReceipt =
                  desiredStateRepository.recordPrivatePromotionResult(
                      binding.accountBinding(), trustFence(binding), observation);
              if (!expectedReceipt.equals(currentPrivateReceipt)) {
                throw new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
                    "Original private promotion receipt changed before ACTIVE readback recording");
              }
              return desiredStateRepository.recordActiveJwksPromotionResult(
                  binding.accountBinding(), trustFence(binding), activeObservation);
            });
    if (activeReceipt == null) {
      throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
          "Account ACTIVE JWKS receipt is unavailable");
    }
    requireUnchangedProtectedBindings(binding, apiIdentity);
    if (!activeReceipt.promotionOperationId().equals(promotion.operationId())
        || !activeReceipt.configMapUid().equals(promotion.publicConfigMapUid())
        || !activeReceipt.priorResourceVersion().equals(promotion.expectedPublicResourceVersion())
        || !activeReceipt.observedResourceVersion().equals(activeReadback.resourceVersion())
        || !activeReceipt
            .publicDataDigest()
            .equals(
                AccountJwtJwksPublicationRepository.publicDataDigest(
                    promotion.expectedPublicJwksJson(), promotion.expectedActiveMarkerJson()))) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account ACTIVE JWKS receipt did not match its exact CAS readback");
    }

    PreparedGenerationEvidence afterActiveReceipt =
        accountTransaction.execute(
            status ->
                desiredStateRepository.readPreparedGenerationForRecovery(
                    binding.accountBinding(), trustFence(binding)));
    if (afterActiveReceipt == null) {
      throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
          "Prepared Account promotion evidence is unavailable after ACTIVE readback");
    }
    PromotionOperationEvidence promotionWithActiveReceipt = afterActiveReceipt.promotion();
    requirePromotionIdentity(promotion, promotionWithActiveReceipt, observation, expectedReceipt);
    if (!promotionWithActiveReceipt
            .activeJwksReceiptDigest()
            .equals(Optional.of(activeReceipt.receiptDigest()))
        || !promotionWithActiveReceipt
            .activeJwksPublicDataDigest()
            .equals(Optional.of(activeReceipt.publicDataDigest()))
        || !promotionWithActiveReceipt
            .activeJwksObservedResourceVersion()
            .equals(Optional.of(activeReceipt.observedResourceVersion()))) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account ACTIVE JWKS receipt is not bound to the current PREPARED operation");
    }
    requireUnchangedProtectedBindings(binding, apiIdentity);

    InventorySnapshot beforeCommitInventory = observePreparedInventory(context);
    requireUnchangedProtectedBindings(binding, apiIdentity);
    prepublicationService.observePreparedCurrentKeyCorrespondence(promotionWithActiveReceipt);
    requireUnchangedProtectedBindings(binding, apiIdentity);
    CommittedSignerEvidence committed =
        commitPreparedPromotion(
            binding,
            apiIdentity,
            observation,
            expectedReceipt,
            promotionWithActiveReceipt,
            beforeCommitInventory);
    requireUnchangedProtectedBindings(binding, apiIdentity);
    if (committed == null
        || !promotion.operationId().equals(committed.promotion().operationId())
        || !"COMMITTED".equals(committed.promotion().status())) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account ACTIVE signer commit did not read back exactly");
    }
    // The final commit obtains a separate readiness proof from the independent post-CAS inventory
    // observation.
  }

  private PromotionOwnerSnapshot readPromotionOwnerSnapshot(
      Binding binding,
      BindingIdentity apiIdentity,
      PrivatePromotionObservation observation,
      PrivatePromotionReceipt expectedReceipt) {
    TrustFence trust = trustFence(binding);
    PromotionOwnerSnapshot snapshot =
        accountTransaction.execute(
            status -> {
              PrivatePromotionReceipt receipt =
                  desiredStateRepository.recordPrivatePromotionResult(
                      binding.accountBinding(), trust, observation);
              if (!expectedReceipt.equals(receipt)) {
                throw new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
                    "Private promotion receipt changed before Account reconciliation");
              }
              try {
                PreparedGenerationEvidence prepared =
                    desiredStateRepository.readPreparedGenerationForRecovery(
                        binding.accountBinding(), trust);
                requirePreparedReceipt(prepared, observation, receipt);
                return new PromotionOwnerSnapshot(prepared, null, receipt);
              } catch (AccountJwtSignerDesiredStateRepository.NoPreparedPromotionException done) {
                CommittedSignerEvidence committed =
                    desiredStateRepository
                        .readCurrentCommittedSigner(
                            binding.accountBinding(),
                            trust,
                            apiIdentity.bindingDigest(),
                            apiIdentity.configRevision())
                        .orElseThrow(
                            () ->
                                new AccountJwtSignerDesiredStateRepository
                                    .PromotionPrerequisitesIncompleteException(
                                    "Private promotion is neither PREPARED nor currently committed"));
                if (!committed.privateReceipt().equals(receipt)
                    || !committed
                        .promotion()
                        .operationId()
                        .equals(observation.promotionOperationId())) {
                  throw new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
                      "Committed private promotion retry differs from Account evidence");
                }
                return new PromotionOwnerSnapshot(null, committed, receipt);
              }
            });
    if (snapshot == null) {
      throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
          "Account signer promotion evidence is unavailable");
    }
    return snapshot;
  }

  private ReadinessPromotionProof readPreparedPromotionProof(
      Binding binding,
      BindingIdentity apiIdentity,
      PrivatePromotionObservation observation,
      PrivatePromotionReceipt expectedReceipt,
      PromotionOperationEvidence expectedPromotion,
      InventorySnapshot liveInventory) {
    TrustFence trust = trustFence(binding);
    ReadinessPromotionProof proof =
        accountTransaction.execute(
            status -> {
              PrivatePromotionReceipt receipt =
                  desiredStateRepository.recordPrivatePromotionResult(
                      binding.accountBinding(), trust, observation);
              if (!expectedReceipt.equals(receipt)) {
                throw new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
                    "Private promotion receipt changed before ACTIVE publication");
              }
              PreparedGenerationEvidence current =
                  desiredStateRepository.readPreparedGenerationForRecovery(
                      binding.accountBinding(), trust);
              requireSamePreparedPromotion(current, expectedPromotion, observation, receipt);
              return readinessRepository
                  .readPromotionProof(
                      binding.accountBinding(),
                      trust,
                      expectedPromotion.generationOperationId(),
                      liveInventory)
                  .orElseThrow(
                      () ->
                          new AccountJwtSignerDesiredStateRepository
                              .PromotionPrerequisitesIncompleteException(
                              "Fresh complete Account readiness proof is unavailable"));
            });
    if (proof == null) {
      throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
          "Account readiness proof is unavailable");
    }
    requireUnchangedProtectedBindings(binding, apiIdentity);
    return proof;
  }

  private CommittedSignerEvidence commitPreparedPromotion(
      Binding binding,
      BindingIdentity apiIdentity,
      PrivatePromotionObservation observation,
      PrivatePromotionReceipt expectedReceipt,
      PromotionOperationEvidence expectedPromotion,
      InventorySnapshot liveInventory) {
    TrustFence trust = trustFence(binding);
    CommittedSignerEvidence committed =
        accountTransaction.execute(
            status -> {
              PrivatePromotionReceipt receipt =
                  desiredStateRepository.recordPrivatePromotionResult(
                      binding.accountBinding(), trust, observation);
              if (!expectedReceipt.equals(receipt)) {
                throw new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
                    "Original private promotion observation no longer matches Account evidence");
              }
              PreparedGenerationEvidence current =
                  desiredStateRepository.readPreparedGenerationForRecovery(
                      binding.accountBinding(), trust);
              requireSamePreparedPromotion(current, expectedPromotion, observation, receipt);
              ReadinessPromotionProof proof =
                  readinessRepository
                      .readPromotionProof(
                          binding.accountBinding(),
                          trust,
                          expectedPromotion.generationOperationId(),
                          liveInventory)
                      .orElseThrow(
                          () ->
                              new AccountJwtSignerDesiredStateRepository
                                  .PromotionPrerequisitesIncompleteException(
                                  "Post-CAS complete Account readiness proof is unavailable"));
              return desiredStateRepository.commitPreparedPromotion(
                  binding.accountBinding(), trust, expectedPromotion.operationId(), proof);
            });
    if (committed == null) {
      throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
          "Account signer promotion commit is unavailable");
    }
    requireUnchangedProtectedBindings(binding, apiIdentity);
    return committed;
  }

  private InventorySnapshot observePreparedInventory(ObservationContext context) {
    if (inventorySourceProvider == null) {
      throw new BootstrapOperationException(FailureCode.VALIDATOR_INVENTORY_UNAVAILABLE);
    }
    AccountJwtValidatorInventorySource source = inventorySourceProvider.getIfAvailable();
    if (source == null) {
      throw new BootstrapOperationException(FailureCode.VALIDATOR_INVENTORY_UNAVAILABLE);
    }
    try {
      return source.observe(context);
    } catch (RuntimeException unavailableOrAmbiguous) {
      throw new BootstrapOperationException(FailureCode.VALIDATOR_INVENTORY_UNAVAILABLE);
    }
  }

  private static ObservationContext observationContext(PromotionOperationEvidence promotion) {
    if (!promotion.expectedPreviousActive().equals(promotion.expectedPublishedActive())) {
      throw new AccountJwtSignerDesiredStateRepository.PromotionPrerequisitesIncompleteException(
          "Prepared Account active signer fence is inconsistent");
    }
    return new ObservationContext(
        promotion.expectedPreviousActive().isEmpty()
            ? ObservationPurpose.INITIAL_NO_ACTIVE_SIGNER_CANDIDATES
            : ObservationPurpose.STRICT_READY,
        promotion.generationOperationId(),
        promotion.generationOperationDigest());
  }

  private static void requireReceiptIdentity(
      PrivatePromotionObservation observation, PrivatePromotionReceipt receipt) {
    if (!observation.promotionOperationId().equals(receipt.promotionOperationId())
        || !observation.generationOperationId().equals(receipt.generationOperationId())
        || !observation.observedResourceVersion().equals(receipt.observedResourceVersion())) {
      throw new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
          "Private promotion result does not match the exact Account receipt");
    }
  }

  private static void requirePreparedReceipt(
      PreparedGenerationEvidence prepared,
      PrivatePromotionObservation observation,
      PrivatePromotionReceipt receipt) {
    requireSamePreparedPromotion(prepared, prepared.promotion(), observation, receipt);
  }

  private static void requireSamePreparedPromotion(
      PreparedGenerationEvidence prepared,
      PromotionOperationEvidence expected,
      PrivatePromotionObservation observation,
      PrivatePromotionReceipt receipt) {
    PromotionOperationEvidence current = prepared.promotion();
    if (!current.equals(expected)
        || !current.operationId().equals(observation.promotionOperationId())
        || !current.generationOperationId().equals(observation.generationOperationId())
        || !current.generationOperationDigest().equals(observation.generationOperationDigest())
        || !current.requestDigest().equals(observation.promotionRequestDigest())
        || !current.privatePromotionDispatched()
        || !current.privatePromotionReceiptDigest().equals(Optional.of(receipt.receiptDigest()))) {
      throw new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
          "Private promotion receipt does not match the exact current PREPARED operation");
    }
  }

  private static void requirePromotionIdentity(
      PromotionOperationEvidence expected,
      PromotionOperationEvidence current,
      PrivatePromotionObservation observation,
      PrivatePromotionReceipt receipt) {
    if (!current.operationId().equals(expected.operationId())
        || !current.requestDigest().equals(expected.requestDigest())
        || current.expectedRecordVersion() != expected.expectedRecordVersion()
        || !current.binding().equals(expected.binding())
        || !current.expectedPreviousActive().equals(expected.expectedPreviousActive())
        || !current
            .expectedPreviousPublicKeyFingerprint()
            .equals(expected.expectedPreviousPublicKeyFingerprint())
        || !current.expectedPublishedActive().equals(expected.expectedPublishedActive())
        || !current.targetAlgorithm().equals(expected.targetAlgorithm())
        || !current.trustFence().equals(expected.trustFence())
        || !current.generationOperationId().equals(expected.generationOperationId())
        || !current.generationOperationDigest().equals(expected.generationOperationDigest())
        || !current.generationReceiptDigest().equals(expected.generationReceiptDigest())
        || !current.targetGeneration().equals(expected.targetGeneration())
        || !current.targetKid().equals(expected.targetKid())
        || !current.targetPublicKeyFingerprint().equals(expected.targetPublicKeyFingerprint())
        || !current.secretUid().equals(expected.secretUid())
        || !current
            .expectedPrivateResourceVersion()
            .equals(expected.expectedPrivateResourceVersion())
        || !current.apiBindingDigest().equals(expected.apiBindingDigest())
        || !current.apiConfigRevision().equals(expected.apiConfigRevision())
        || !current.publicConfigMapUid().equals(expected.publicConfigMapUid())
        || !current.expectedPublicResourceVersion().equals(expected.expectedPublicResourceVersion())
        || !current.prepublicationIntentDigest().equals(expected.prepublicationIntentDigest())
        || !current.prepublicationReceiptDigest().equals(expected.prepublicationReceiptDigest())
        || !current.mountedObservationDigest().equals(expected.mountedObservationDigest())
        || !current.readinessPlanDigest().equals(expected.readinessPlanDigest())
        || !current.readinessEvidenceDigest().equals(expected.readinessEvidenceDigest())
        || !current.expectedPublicJwksJson().equals(expected.expectedPublicJwksJson())
        || !current.expectedActiveMarkerJson().equals(expected.expectedActiveMarkerJson())
        || !"PREPARED".equals(current.status())
        || !current.operationId().equals(observation.promotionOperationId())
        || !current.generationOperationId().equals(observation.generationOperationId())
        || !current.privatePromotionDispatched()
        || !current.privatePromotionReceiptDigest().equals(Optional.of(receipt.receiptDigest()))) {
      throw new AccountJwtSignerDesiredStateRepository.IdempotencyConflictException(
          "Account PREPARED operation changed during ACTIVE publication");
    }
  }

  private static void requireExpectedPublicSnapshot(
      PromotionOperationEvidence promotion, ConfigMapSnapshot snapshot) {
    String marker =
        snapshot.data().get(AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY);
    boolean active = promotion.expectedActiveMarkerJson().equals(marker);
    if (!promotion.publicConfigMapUid().equals(snapshot.uid())
        || !promotion.expectedPublicJwksJson().equals(snapshot.data().get(JWKS_DATA_KEY))
        || (!promotion.expectedPublicResourceVersion().equals(snapshot.resourceVersion())
            && !active)
        || (promotion.expectedPublicResourceVersion().equals(snapshot.resourceVersion())
            && active)) {
      throw new AccountJwtSignerDesiredStateRepository.PromotionPrerequisitesIncompleteException(
          "Account public ConfigMap changed before its exact ACTIVE CAS");
    }
  }

  private static void requireExactActiveReadback(
      PromotionOperationEvidence promotion, CasObservation observation) {
    if (!promotion.publicConfigMapUid().equals(observation.uid())
        || !promotion.expectedPublicResourceVersion().equals(observation.priorResourceVersion())
        || !promotion
            .expectedPublicJwksJson()
            .equals(observation.publishedData().get(JWKS_DATA_KEY))
        || !promotion
            .expectedActiveMarkerJson()
            .equals(
                observation
                    .publishedData()
                    .get(AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY))
        || !promotion.expectedPublicJwksJson().equals(observation.readbackData().get(JWKS_DATA_KEY))
        || !promotion
            .expectedActiveMarkerJson()
            .equals(
                observation
                    .readbackData()
                    .get(AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY))
        || promotion.expectedPublicResourceVersion().equals(observation.resourceVersion())) {
      throw new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
          "Account ACTIVE ConfigMap CAS did not return its exact immutable readback");
    }
  }

  private InventorySnapshot observeCurrentValidatorInventory(
      EnrollmentSources sources, GenerationRequest request) {
    try {
      AccountJwtValidatorInventorySource source = inventorySourceProvider.getIfAvailable();
      if (source == null) {
        throw new BootstrapOperationException(FailureCode.VALIDATOR_INVENTORY_UNAVAILABLE);
      }
      ObservationContext context =
          accountTransaction.execute(
              status -> {
                requireUnchangedProtectedBindings(sources);
                ObservationContext selected =
                    readinessRepository.readCurrentInventoryObservationContext(
                        sources.materializerBinding().accountBinding(),
                        trustFence(sources.materializerBinding()),
                        request.operationId());
                requireUnchangedProtectedBindings(sources);
                return selected;
              });
      if (context == null) {
        throw new BootstrapOperationException(FailureCode.VALIDATOR_INVENTORY_UNAVAILABLE);
      }
      return source.observe(context);
    } catch (BootstrapOperationException failure) {
      throw failure;
    } catch (RuntimeException unavailableOrAmbiguous) {
      throw new BootstrapOperationException(FailureCode.VALIDATOR_INVENTORY_UNAVAILABLE);
    }
  }

  private BootstrapProgress reconcileSelectedGeneration(
      EnrollmentSources sources, GenerationRequest request) {
    try {
      AccountJwtJwksPublicationRepository.PublicationReceipt publication =
          prepublicationService.prepublishCurrentGeneration();
      requireUnchangedProtectedBindings(sources);
      if (!request.operationId().equals(publication.operationId())) {
        return progress(
            Stage.PREPUBLICATION_BLOCKED,
            request,
            false,
            false,
            FailureCode.ACCOUNT_OWNER_OPERATION_MISMATCH);
      }
    } catch (BootstrapOperationException failure) {
      throw failure;
    } catch (RuntimeException ambiguousOrRejected) {
      return progress(
          Stage.PREPUBLICATION_BLOCKED,
          request,
          false,
          false,
          FailureCode.ACCOUNT_PREPUBLICATION_UNAVAILABLE);
    }

    try {
      AccountJwtJwksPublicationRepository.MountObservation mount =
          prepublicationService.observeCurrentMountedCorrespondence();
      requireUnchangedProtectedBindings(sources);
      if (!request.operationId().equals(mount.operationId())) {
        return progress(
            Stage.MOUNT_CORRESPONDENCE_BLOCKED,
            request,
            true,
            false,
            FailureCode.ACCOUNT_OWNER_OPERATION_MISMATCH);
      }
    } catch (BootstrapOperationException failure) {
      throw failure;
    } catch (RuntimeException unavailableOrRejected) {
      return progress(
          Stage.MOUNT_CORRESPONDENCE_BLOCKED,
          request,
          true,
          false,
          FailureCode.ACCOUNT_MOUNT_CORRESPONDENCE_UNAVAILABLE);
    }

    return progress(
        Stage.BLOCKED_FOR_VALIDATOR_ACCEPTANCE,
        request,
        true,
        true,
        FailureCode.VALIDATOR_ACCEPTANCE_INCOMPLETE);
  }

  private DesiredState initializeEnrollment(EnrollmentSources sources) {
    AccountJwtSignerDesiredStateRepository.Binding accountBinding =
        sources.materializerBinding().accountBinding();
    try {
      DesiredState initialized =
          accountTransaction.execute(
              status -> {
                requireUnchangedProtectedBindings(sources);
                DesiredState persisted =
                    desiredStateRepository.initialize(accountBinding, sources.enrollmentIdentity());
                requireUnchangedProtectedBindings(sources);
                return persisted;
              });
      if (initialized == null) {
        throw new BootstrapOperationException(FailureCode.ACCOUNT_STATE_AMBIGUOUS);
      }
      requireUnchangedProtectedBindings(sources);
      return initialized;
    } catch (BootstrapOperationException failure) {
      throw failure;
    } catch (RuntimeException unavailableOrAmbiguous) {
      throw new BootstrapOperationException(FailureCode.ACCOUNT_STATE_AMBIGUOUS);
    }
  }

  private DesiredState readEnrollmentState(EnrollmentSources sources) {
    AccountJwtSignerDesiredStateRepository.Binding accountBinding =
        sources.materializerBinding().accountBinding();
    try {
      Optional<DesiredState> state =
          accountTransaction.execute(
              status -> {
                requireUnchangedProtectedBindings(sources);
                Optional<DesiredState> current =
                    desiredStateRepository.readEnrollmentState(accountBinding);
                requireUnchangedProtectedBindings(sources);
                return current;
              });
      if (state == null || state.isEmpty()) {
        throw new BootstrapOperationException(FailureCode.ACCOUNT_STATE_AMBIGUOUS);
      }
      DesiredState current = state.orElseThrow();
      EnrollmentIdentity enrollment =
          current
              .enrollmentIdentity()
              .orElseThrow(
                  () -> new BootstrapOperationException(FailureCode.ACCOUNT_STATE_AMBIGUOUS));
      if (!enrollment.sameStablePins(sources.enrollmentIdentity())) {
        throw new BootstrapOperationException(FailureCode.PROTECTED_BINDING_MISMATCH);
      }
      requireUnchangedProtectedBindings(sources);
      return current;
    } catch (BootstrapOperationException failure) {
      throw failure;
    } catch (RuntimeException unavailableOrAmbiguous) {
      throw new BootstrapOperationException(FailureCode.ACCOUNT_STATE_AMBIGUOUS);
    }
  }

  private static void requireUnselectedInitialEnrollment(
      DesiredState state, EnrollmentSources sources) {
    if (state.recordVersion() != 1
        || state.durableActive().isPresent()
        || state.publishedActive().isPresent()
        || state.preparedOperationId().isPresent()
        || state.generationOperationId().isPresent()
        || state.enrollmentIdentity().isEmpty()
        || !state.enrollmentIdentity().orElseThrow().sameStablePins(sources.enrollmentIdentity())) {
      throw new BootstrapOperationException(FailureCode.ACCOUNT_STATE_AMBIGUOUS);
    }
  }

  private GenerationRequest readCurrentGenerationRequest(EnrollmentSources sources) {
    AccountJwtSignerDesiredStateRepository.Binding accountBinding =
        sources.materializerBinding().accountBinding();
    TrustFence trustFence = trustFence(sources.materializerBinding());
    try {
      GenerationRequest request =
          accountTransaction.execute(
              status -> {
                requireUnchangedProtectedBindings(sources);
                GenerationRequest current =
                    desiredStateRepository.readCurrentGenerationRequest(accountBinding, trustFence);
                requireUnchangedProtectedBindings(sources);
                return current;
              });
      if (request == null) {
        throw new BootstrapOperationException(FailureCode.ACCOUNT_STATE_AMBIGUOUS);
      }
      requireUnchangedProtectedBindings(sources);
      return request;
    } catch (BootstrapOperationException failure) {
      throw failure;
    } catch (RuntimeException unavailableOrAmbiguous) {
      throw new BootstrapOperationException(FailureCode.ACCOUNT_STATE_AMBIGUOUS);
    }
  }

  private static EnrollmentAcknowledgement acknowledgement(
      Binding materializer,
      AccountJwtJwksConfigMapClient.BindingIdentity api,
      EnrollmentIdentity enrollment) {
    return new EnrollmentAcknowledgement(
        materializer.environmentId(),
        materializer.clusterId(),
        materializer.namespace(),
        enrollment.expectedClusterIncarnationUid(),
        enrollment.expectedNamespaceUid(),
        enrollment.materializerTrustBindingDigest(),
        enrollment.materializerTrustConfigRevision(),
        enrollment.apiBindingDigest(),
        enrollment.apiConfigRevision(),
        api.apiServerUrl(),
        api.tlsServerName(),
        api.servingCaSha256(),
        api.expectedApiUsername(),
        AccountJwtSignerDesiredStateRepository.PRIVATE_SECRET_NAME,
        AccountJwtSignerDesiredStateRepository.PUBLIC_JWKS_CONFIG_MAP_NAME,
        enrollment.publicConfigMapUid(),
        enrollment.publicConfigMapResourceVersion(),
        enrollment.publicConfigMapSnapshotDigest());
  }

  private EnrollmentSources captureEnrollmentSources() {
    Binding materializerBefore = currentMaterializerBinding();
    AccountJwtJwksConfigMapClient.BindingIdentity apiBefore = currentApiIdentity();
    requireMatchingProtectedPins(materializerBefore, apiBefore);

    AccountJwtJwksConfigMapClient.ConfigMapSnapshot publicConfigMap;
    try {
      publicConfigMap = Objects.requireNonNull(configMapClient.observe());
    } catch (RuntimeException unavailableOrAmbiguous) {
      throw new BootstrapOperationException(FailureCode.PUBLIC_CONFIG_MAP_UNAVAILABLE);
    }

    Binding materializerAfter = currentMaterializerBinding();
    AccountJwtJwksConfigMapClient.BindingIdentity apiAfter = currentApiIdentity();
    if (!materializerBefore.equals(materializerAfter) || !apiBefore.equals(apiAfter)) {
      throw new BootstrapOperationException(FailureCode.PROTECTED_BINDING_CHANGED);
    }
    requireMatchingProtectedPins(materializerAfter, apiAfter);
    String initialJwks = publicConfigMap.data().get(JWKS_DATA_KEY);
    if (initialJwks == null || initialJwks.isBlank()) {
      throw new BootstrapOperationException(FailureCode.PUBLIC_CONFIG_MAP_INCOMPLETE);
    }

    try {
      EnrollmentIdentity enrollment =
          new EnrollmentIdentity(
              materializerAfter.expectedClusterIncarnationUid(),
              materializerAfter.expectedNamespaceUid(),
              materializerAfter.bindingDigest(),
              materializerAfter.configRevision(),
              apiAfter.bindingDigest(),
              apiAfter.configRevision(),
              publicConfigMap.uid(),
              publicConfigMap.resourceVersion(),
              AccountJwtJwksPublicationRepository.snapshotDigest(publicConfigMap.data()));
      return new EnrollmentSources(materializerAfter, apiAfter, enrollment);
    } catch (RuntimeException malformedSource) {
      throw new BootstrapOperationException(FailureCode.PUBLIC_CONFIG_MAP_INCOMPLETE);
    }
  }

  private Binding currentMaterializerBinding() {
    try {
      return materializerTrustBinding
          .current()
          .orElseThrow(
              () -> new BootstrapOperationException(FailureCode.PROTECTED_BINDING_UNAVAILABLE));
    } catch (BootstrapOperationException failure) {
      throw failure;
    } catch (RuntimeException unavailable) {
      throw new BootstrapOperationException(FailureCode.PROTECTED_BINDING_UNAVAILABLE);
    }
  }

  private AccountJwtJwksConfigMapClient.BindingIdentity currentApiIdentity() {
    try {
      return Objects.requireNonNull(configMapClient.identity());
    } catch (RuntimeException unavailable) {
      throw new BootstrapOperationException(FailureCode.PROTECTED_API_BINDING_UNAVAILABLE);
    }
  }

  private void requireUnchangedProtectedBindings(EnrollmentSources sources) {
    Binding currentMaterializer = currentMaterializerBinding();
    AccountJwtJwksConfigMapClient.BindingIdentity currentApi = currentApiIdentity();
    if (!sources.materializerBinding().equals(currentMaterializer)
        || !sources.apiIdentity().equals(currentApi)) {
      throw new BootstrapOperationException(FailureCode.PROTECTED_BINDING_CHANGED);
    }
    requireMatchingProtectedPins(currentMaterializer, currentApi);
  }

  private void requireUnchangedProtectedBindings(
      Binding expectedMaterializer, BindingIdentity expectedApiIdentity) {
    Binding currentMaterializer = currentMaterializerBinding();
    BindingIdentity currentApi = currentApiIdentity();
    if (!expectedMaterializer.equals(currentMaterializer)
        || !expectedApiIdentity.equals(currentApi)) {
      throw new BootstrapOperationException(FailureCode.PROTECTED_BINDING_CHANGED);
    }
    requireMatchingProtectedPins(currentMaterializer, currentApi);
  }

  private static void requireMatchingProtectedPins(
      Binding materializer, AccountJwtJwksConfigMapClient.BindingIdentity api) {
    if (!materializer.environmentId().equals(api.environmentId())
        || !materializer.clusterId().equals(api.clusterId())
        || !materializer.namespace().equals(api.namespace())
        || !materializer.expectedClusterIncarnationUid().equals(api.expectedClusterIncarnationUid())
        || !materializer.expectedNamespaceUid().equals(api.expectedNamespaceUid())) {
      throw new BootstrapOperationException(FailureCode.PROTECTED_BINDING_MISMATCH);
    }
  }

  private static TrustFence trustFence(Binding binding) {
    return new TrustFence(
        binding.expectedClusterIncarnationUid(),
        binding.expectedNamespaceUid(),
        binding.bindingDigest(),
        binding.configRevision());
  }

  private static BootstrapProgress progress(
      Stage stage,
      GenerationRequest request,
      boolean prepublished,
      boolean mountedCorrespondence,
      FailureCode failure) {
    boolean validatorAcceptanceComplete = false;
    List<FailureCode> blockers = List.of(failure);
    if (stage == Stage.BLOCKED_FOR_VALIDATOR_ACCEPTANCE) {
      blockers =
          List.of(
              FailureCode.VALIDATOR_ACCEPTANCE_INCOMPLETE,
              FailureCode.SIGNER_PROMOTION_NOT_ATTEMPTED);
    }
    return new BootstrapProgress(
        stage,
        EnrollmentSource.PROTECTED_BINDINGS_AND_LIVE_FIXED_JWKS_CONFIG_MAP,
        true,
        true,
        request.operationId(),
        request.phase(),
        request.targetGeneration(),
        request.targetKid(),
        request.phase() == GenerationPhase.GENERATION_RECORDED,
        prepublished,
        mountedCorrespondence,
        AccountJwtReadinessProbeRepository.INVENTORY_STATUS,
        validatorAcceptanceComplete,
        false,
        false,
        blockers);
  }

  private record EnrollmentSources(
      Binding materializerBinding,
      AccountJwtJwksConfigMapClient.BindingIdentity apiIdentity,
      EnrollmentIdentity enrollmentIdentity) {}

  private record PromotionOwnerSnapshot(
      PreparedGenerationEvidence preparedEvidence,
      CommittedSignerEvidence committedEvidence,
      PrivatePromotionReceipt privateReceipt) {}

  /** Closed, non-secret identity acknowledgment for one idempotent durable enrollment. */
  public record EnrollmentAcknowledgement(
      String environmentId,
      String clusterId,
      String namespace,
      String expectedClusterIncarnationUid,
      String expectedNamespaceUid,
      String materializerTrustBindingDigest,
      String materializerTrustConfigRevision,
      String apiBindingDigest,
      String apiConfigRevision,
      String apiServerUrl,
      String apiTlsServerName,
      String apiServingCaSha256,
      String apiExpectedUsername,
      String privateSecretName,
      String publicConfigMapName,
      String publicConfigMapUid,
      String publicConfigMapResourceVersion,
      String publicConfigMapSnapshotDigest) {}

  public enum Stage {
    PREPUBLICATION_BLOCKED,
    MOUNT_CORRESPONDENCE_BLOCKED,
    BLOCKED_FOR_VALIDATOR_ACCEPTANCE
  }

  public enum EnrollmentSource {
    PROTECTED_BINDINGS_AND_LIVE_FIXED_JWKS_CONFIG_MAP
  }

  /** Non-secret progress; `false` readiness fields are deliberate until fleet proof exists. */
  public record BootstrapProgress(
      Stage stage,
      EnrollmentSource enrollmentSource,
      boolean enrollmentInitialized,
      boolean generationRequestSelected,
      UUID operationId,
      GenerationPhase generationPhase,
      String targetGeneration,
      String targetKid,
      boolean generationReadbackComplete,
      boolean publicKeyPrepublished,
      boolean mountedKeyCorrespondence,
      String validatorInventoryStatus,
      boolean validatorAcceptanceComplete,
      boolean signerPromotionCommitted,
      boolean issuanceReady,
      List<FailureCode> blockers) {
    public BootstrapProgress {
      Objects.requireNonNull(stage);
      Objects.requireNonNull(enrollmentSource);
      Objects.requireNonNull(operationId);
      Objects.requireNonNull(generationPhase);
      Objects.requireNonNull(targetGeneration);
      Objects.requireNonNull(targetKid);
      Objects.requireNonNull(validatorInventoryStatus);
      blockers = List.copyOf(blockers);
      if (validatorAcceptanceComplete || signerPromotionCommitted || issuanceReady) {
        throw new IllegalArgumentException(
            "Signer bootstrap progress cannot claim validator acceptance, promotion, or readiness");
      }
      if (!enrollmentInitialized || !generationRequestSelected) {
        throw new IllegalArgumentException(
            "Signer bootstrap progress requires completed Account enrollment and request selection");
      }
    }
  }

  public enum FailureCode {
    PROTECTED_BINDING_UNAVAILABLE,
    PROTECTED_API_BINDING_UNAVAILABLE,
    PROTECTED_BINDING_MISMATCH,
    PROTECTED_BINDING_CHANGED,
    PUBLIC_CONFIG_MAP_UNAVAILABLE,
    PUBLIC_CONFIG_MAP_INCOMPLETE,
    SIGNER_PROMOTION_NOT_ATTEMPTED,
    ACCOUNT_STATE_AMBIGUOUS,
    ACCOUNT_OWNER_OPERATION_MISMATCH,
    ACCOUNT_PREPUBLICATION_UNAVAILABLE,
    ACCOUNT_MOUNT_CORRESPONDENCE_UNAVAILABLE,
    VALIDATOR_INVENTORY_UNAVAILABLE,
    VALIDATOR_ACCEPTANCE_INCOMPLETE
  }

  public static final class BootstrapOperationException extends IllegalStateException {
    private final FailureCode failureCode;

    public BootstrapOperationException(FailureCode failureCode) {
      super("Account JWT signer bootstrap is quarantined: " + failureCode);
      this.failureCode = Objects.requireNonNull(failureCode);
    }

    public FailureCode failureCode() {
      return failureCode;
    }
  }
}
