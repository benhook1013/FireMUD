package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.PeerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveSigner;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.DesiredState;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationPhase;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.SignedProbeDigest;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.PlayerBootstrapJwtProfileValidator;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable, operation-scoped readiness-probe planning and result evidence for Account's interim
 * mounted signer.
 *
 * <p>This repository never advances signer lifecycle state. It can record only a validator
 * observation already authenticated and verified by the owner-internal readiness service. Every
 * operation re-locks and compares the existing desired-state, generation, JWKS publication, and
 * mounted-correspondence authorities; probe plans are evidence attached to that owner operation,
 * not a second key authority.
 */
@Repository
public class AccountJwtReadinessProbeRepository {
  public static final int MAX_VALIDATOR_CACHE_AGE_SECONDS = 300;
  public static final int PROBE_LIFETIME_SECONDS = 300;
  public static final int MAX_ENTRIES_PER_OPERATION = 256;
  public static final String INVENTORY_STATUS = "PARTIAL_UNCONFIRMED";

  private static final String PLAN_TABLE = "account_jwt_readiness_probe_plans";
  private static final String ENTRY_TABLE = "account_jwt_readiness_probe_entries";
  private static final String DELIVERY_CLAIM_TABLE = "account_jwt_readiness_delivery_claims";
  private static final String VALIDATOR_ID = "account-service";
  private static final String CANARY_PROFILE = "account-jwt-readiness-canary";
  private static final String CANARY_AUDIENCE = "firemud-account-jwt-readiness";
  private static final String CONTROL_UI_PROFILE = ControlUiJwtProfileValidator.PROFILE;
  private static final String CONTROL_UI_AUDIENCE = ControlUiJwtProfileValidator.AUDIENCE;
  private static final String PLAYER_BOOTSTRAP_PROFILE = PlayerBootstrapJwtProfileValidator.PROFILE;
  private static final String PLAYER_BOOTSTRAP_AUDIENCE =
      PlayerBootstrapJwtProfileValidator.AUDIENCE;
  private static final String REPRESENTATIVE_PROFILE = GameSessionAccountDelegationProfile.PROFILE;
  private static final String REPRESENTATIVE_AUDIENCE =
      GameSessionAccountDelegationProfile.AUDIENCE;
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DSLContext dsl;
  private final AccountJwtSignerDesiredStateRepository desiredStateRepository;
  private final AccountJwtJwksPublicationRepository publicationRepository;
  private final AccountJwtValidatorInventoryRepository inventoryRepository;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The constructor only validates trusted Spring collaborators and constructs an in-memory repository; it performs no I/O or resource acquisition and defines no finalizer.")
  public AccountJwtReadinessProbeRepository(
      DSLContext dsl,
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtJwksPublicationRepository publicationRepository) {
    this(
        dsl,
        desiredStateRepository,
        publicationRepository,
        new AccountJwtValidatorInventoryRepository(dsl));
  }

  @Autowired
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The constructor only validates trusted Spring collaborators; it performs no I/O or resource acquisition and defines no finalizer.")
  public AccountJwtReadinessProbeRepository(
      DSLContext dsl,
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtJwksPublicationRepository publicationRepository,
      AccountJwtValidatorInventoryRepository inventoryRepository) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
    this.desiredStateRepository =
        Objects.requireNonNull(desiredStateRepository, "Desired-state repository is required");
    this.publicationRepository =
        Objects.requireNonNull(publicationRepository, "JWKS publication repository is required");
    this.inventoryRepository =
        Objects.requireNonNull(inventoryRepository, "Inventory repository is required");
  }

  /**
   * Creates one immutable plan from the exact current Account operation and publication evidence.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ReadinessProbePlan planCurrent(Binding binding, TrustFence trust, Instant plannedAt) {
    return planCurrent(binding, trust, plannedAt, Optional.empty());
  }

  /**
   * Creates a still-partial plan bound to the exact Account-owned immutable inventory snapshot. The
   * live inventory observation does not establish per-Pod acceptance or promotion evidence.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ReadinessProbePlan planCurrent(
      Binding binding, TrustFence trust, Instant plannedAt, InventorySnapshot inventorySnapshot) {
    return planCurrent(
        binding, trust, plannedAt, Optional.of(Objects.requireNonNull(inventorySnapshot)));
  }

  private ReadinessProbePlan planCurrent(
      Binding binding,
      TrustFence trust,
      Instant plannedAt,
      Optional<InventorySnapshot> inventorySnapshot) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(plannedAt, "Probe plan time is required");
    long plannedEpoch = exactEpochSecond(plannedAt);
    CurrentEvidence current = lockCurrentEvidence(binding, trust);
    UUID operationId = current.request().operationId();
    ReadinessProbePlan existing = selectPlan(operationId, true);
    if (existing != null) {
      requirePlanMatchesCurrent(existing, current);
      if (inventorySnapshot.isPresent()) {
        String requestedDigest = inventorySnapshot.orElseThrow().digest();
        if (!existing.inventorySnapshotDigest().equals(Optional.of(requestedDigest))) {
          throw new InventoryPlanConflictException();
        }
        inventoryRepository.readRequired(requestedDigest, binding, trust);
      }
      return readbackPlan(existing, true);
    }

    Optional<String> snapshotDigest = Optional.empty();
    if (inventorySnapshot.isPresent()) {
      InventorySnapshot snapshot = inventorySnapshot.orElseThrow();
      requireInventorySnapshotWithinPlanWindow(snapshot, plannedEpoch);
      AccountJwtValidatorInventoryRepository.StoredSnapshot stored =
          inventoryRepository.persistOrReadback(snapshot, binding, trust);
      snapshotDigest = Optional.of(stored.digest());
    }
    ReadinessProbePlan candidate = newPlan(current, plannedEpoch, snapshotDigest);
    insertPlan(candidate);
    for (ProbeEntry entry : candidate.entries()) {
      insertEntry(entry);
    }
    ReadinessProbePlan readback = selectPlan(operationId, true);
    if (readback == null) {
      throw new StorageUnavailableException("Account JWT readiness probe plan did not read back");
    }
    requirePlanMatchesCurrent(readback, current);
    if (!candidate.equals(readback)) {
      throw new QuarantinedStateException("Account JWT readiness probe plan changed on readback");
    }
    return readback;
  }

  private static void requireInventorySnapshotWithinPlanWindow(
      InventorySnapshot snapshot, long plannedEpoch) {
    long observedEpoch;
    try {
      observedEpoch = snapshot.observedAt().getEpochSecond();
    } catch (RuntimeException failure) {
      throw new InventoryPlanConflictException();
    }
    if (observedEpoch <= 0L
        || observedEpoch > plannedEpoch
        || plannedEpoch - observedEpoch > MAX_VALIDATOR_CACHE_AGE_SECONDS) {
      throw new InventoryPlanConflictException();
    }
  }

  /**
   * Durably claims the one transient delivery attempt for this exact current plan before signing. A
   * claim is never cleared or reused: a lost response or interrupted attempt remains quarantined.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public DeliveryClaim claimSingleDelivery(
      net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository
              .Binding
          binding,
      TrustFence trust,
      ReadinessProbePlan expectedPlan,
      net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.Binding
          readinessBinding,
      PeerIdentity peer,
      Instant claimedAt) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(expectedPlan, "Exact persisted readiness plan is required");
    Objects.requireNonNull(readinessBinding, "Authenticated readiness binding is required");
    Objects.requireNonNull(peer, "TLS-authenticated readiness peer is required");
    long claimedEpoch = exactEpochSecond(claimedAt);
    if (!readinessBinding.isCurrentAt(claimedEpoch)
        || !readinessBinding.matches(peer)
        || !binding.environmentId().equals(readinessBinding.environmentId())
        || !binding.clusterId().equals(readinessBinding.clusterId())
        || !binding.namespace().equals(readinessBinding.namespace())
        || !trust
            .expectedClusterIncarnationUid()
            .equals(readinessBinding.expectedClusterIncarnationUid())
        || !trust.expectedNamespaceUid().equals(readinessBinding.expectedNamespaceUid())) {
      throw new DeliveryClaimRejectedException();
    }
    CurrentEvidence current = lockCurrentEvidence(binding, trust);
    requireCurrentOperation(current, expectedPlan.operationId().toString());
    ReadinessProbePlan stored = requireStoredPlan(current);
    ReadinessProbePlan currentPlan = readbackPlan(stored, true);
    if (!currentPlan.equals(expectedPlan)) {
      throw new StaleOperationException(
          "Readiness delivery claim does not match the exact current plan");
    }
    if (claimedEpoch < currentPlan.notBeforeEpochSecond()) {
      throw new CacheAgeNotElapsedException();
    }
    if (claimedEpoch >= currentPlan.expiresAtEpochSecond()) {
      throw new ProbeExpiredException();
    }
    if (currentPlan.entries().isEmpty()
        || currentPlan.entries().size() > MAX_ENTRIES_PER_OPERATION
        || currentPlan.entries().stream()
            .anyMatch(
                entry ->
                    entry.state() != ProbeState.PLANNED
                        || entry.signingAttemptedAtEpochSecond().isPresent()
                        || entry.compactTokenSha256().isPresent()
                        || entry.verificationReceipt().isPresent())) {
      throw new DeliveryAlreadyClaimedException();
    }
    if (selectDeliveryClaim(currentPlan.operationId(), true) != null) {
      throw new DeliveryAlreadyClaimedException();
    }

    DeliveryClaim candidate =
        DeliveryClaim.create(currentPlan, trust, readinessBinding, peer, claimedEpoch);
    insertDeliveryClaim(candidate);
    DeliveryClaim readback = selectDeliveryClaim(candidate.rotationOperationId(), true);
    if (readback == null || !readback.equals(candidate)) {
      throw new QuarantinedStateException(
          "Account readiness single-delivery claim did not read back exactly");
    }
    return readback;
  }

  /** Rechecks the immutable one-shot claim immediately before any pending-key signing. */
  @Transactional(propagation = Propagation.MANDATORY)
  public DeliveryClaim requireCurrentDeliveryClaim(
      Binding binding,
      TrustFence trust,
      ReadinessProbePlan expectedPlan,
      DeliveryClaim expectedClaim,
      Instant checkedAt) {
    requireAccountTransaction();
    Objects.requireNonNull(expectedPlan, "Exact persisted readiness plan is required");
    Objects.requireNonNull(expectedClaim, "Durable single-delivery claim is required");
    long checkedEpoch = exactEpochSecond(checkedAt);
    CurrentEvidence current = lockCurrentEvidence(binding, trust);
    requireCurrentOperation(current, expectedPlan.operationId().toString());
    ReadinessProbePlan plan = readbackPlan(requireStoredPlan(current), true);
    DeliveryClaim persisted = selectDeliveryClaim(plan.operationId(), true);
    if (!plan.equals(expectedPlan)
        || persisted == null
        || !persisted.equals(expectedClaim)
        || !persisted.planDigest().equals(plan.planDigest())
        || !persisted.materializerTrustBindingDigest().equals(trust.bindingDigest())
        || !persisted.materializerTrustConfigRevision().equals(trust.configRevision())
        || !persisted.expectedClusterUid().equals(trust.expectedClusterIncarnationUid())
        || !persisted.expectedNamespaceUid().equals(trust.expectedNamespaceUid())
        || !persisted.targetGeneration().equals(plan.targetGeneration())
        || !persisted.targetKid().equals(plan.targetKid())) {
      throw new DeliveryAlreadyClaimedException();
    }
    if (checkedEpoch < plan.notBeforeEpochSecond()) {
      throw new CacheAgeNotElapsedException();
    }
    if (checkedEpoch >= plan.expiresAtEpochSecond()) {
      throw new ProbeExpiredException();
    }
    return persisted;
  }

  /**
   * Recovery-only read for the exact generation/publication currently owned by PREPARED state. The
   * static applicability matrix and immutable Deployment inventory snapshot are not per-Pod
   * acceptance evidence. These PARTIAL_UNCONFIRMED plans cannot satisfy promotion.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<ReadinessPromotionProof> readCurrentPromotionPrerequisites(
      Binding binding, TrustFence trust, UUID rotationOperationId) {
    // This is a nonmutating proof read, but it joins the writable lifecycle transaction because
    // the existing owner evidence readers lock their rows to fence the adjacent PREPARED CAS.
    requireWritableAccountTransaction();
    requireOperationId(rotationOperationId);
    CurrentEvidence current = lockCurrentEvidence(binding, trust);
    if (!current.request().operationId().equals(rotationOperationId)) {
      throw new StaleOperationException(
          "Readiness prerequisite proof is not for Account's current operation");
    }
    ReadinessProbePlan plan = selectPlan(rotationOperationId, false);
    if (plan == null) {
      throw new MissingPlanException("Current signer generation has no readiness plan");
    }
    ReadinessProbePlan stored = readbackPlan(plan, false);
    requirePlanMatchesCurrent(stored, current);
    if (readinessPlanOutOfWindow(stored)) {
      return Optional.empty();
    }
    if (!stored.validatorInventoryComplete()) {
      return Optional.empty();
    }
    // The immutable inventory snapshot is not per-Pod acceptance evidence. Never upgrade the
    // static matrix or receipt set into complete inventory proof.
    throw new QuarantinedStateException(
        "Readiness plan claims complete inventory without protected inventory evidence");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<ReadinessPromotionProof> readPromotionProof(
      Binding binding, TrustFence trust, UUID rotationOperationId) {
    // Recovery evidence is likewise a locked, nonmutating read inside the writable reconciliation
    // transaction; do not split these reads into an unlocked read-only snapshot.
    requireWritableAccountTransaction();
    requireOperationId(rotationOperationId);
    var prepared = desiredStateRepository.readPreparedGenerationForRecovery(binding, trust);
    var generation = prepared.generationResult();
    var promotion = prepared.promotion();
    if (!generation.operationId().equals(rotationOperationId)
        || !promotion.generationOperationId().equals(rotationOperationId)
        || !promotion.binding().equals(binding)
        || !promotion.trustFence().equals(trust)) {
      throw new StaleOperationException(
          "Readiness promotion proof does not match the exact PREPARED generation");
    }
    AccountJwtJwksPublicationRepository.PreparedPublicationEvidence publication =
        publicationRepository.readPreparedPublicationForRecovery(binding, trust);
    ReadinessProbePlan plan = selectPlan(rotationOperationId, false);
    if (plan == null) {
      throw new MissingPlanException("Prepared signer generation has no readiness plan");
    }
    ReadinessProbePlan stored = readbackPlan(plan, false);
    requirePreparedPlanMatches(stored, generation, promotion, publication);
    if (readinessPlanOutOfWindow(stored)) {
      return Optional.empty();
    }
    if (!stored.validatorInventoryComplete()) {
      return Optional.empty();
    }
    // The immutable inventory snapshot is not per-Pod acceptance evidence. Never upgrade the
    // static matrix or receipt set into complete inventory proof.
    throw new QuarantinedStateException(
        "Readiness plan claims complete inventory without protected inventory evidence");
  }

  private boolean readinessPlanOutOfWindow(ReadinessProbePlan plan) {
    Long databaseNow =
        dsl.resultQuery("SELECT floor(extract(epoch FROM CURRENT_TIMESTAMP))::bigint")
            .fetchOne(0, Long.class);
    if (databaseNow == null) {
      throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
          "Account readiness database clock readback is unavailable");
    }
    return databaseNow < plan.notBeforeEpochSecond() || databaseNow >= plan.expiresAtEpochSecond();
  }

  /** Reads one plan only while its original generation and publication evidence remain current. */
  @Transactional(propagation = Propagation.MANDATORY)
  public ReadinessProbePlan readCurrentPlan(Binding binding, TrustFence trust, UUID operationId) {
    requireAccountTransaction();
    requireOperationId(operationId);
    CurrentEvidence current = lockCurrentEvidence(binding, trust);
    if (!current.request().operationId().equals(operationId)) {
      throw new StaleOperationException(
          "Readiness probe plan is not for Account's current operation");
    }
    ReadinessProbePlan stored = selectPlan(operationId, true);
    if (stored == null) {
      throw new MissingPlanException("Account JWT readiness probe plan is missing");
    }
    requirePlanMatchesCurrent(stored, current);
    return readbackPlan(stored, true);
  }

  /** Reads one exact current probe selected by the complete key and its immutable JTI. */
  @Transactional(propagation = Propagation.MANDATORY)
  public ProbeEntry readCurrentEntry(
      Binding binding,
      TrustFence trust,
      UUID operationId,
      String validatorId,
      String tokenProfile,
      String audience,
      ProbeKind probeKind,
      UUID jti) {
    requireAccountTransaction();
    requireOperationId(operationId);
    CurrentEvidence current = lockCurrentEvidence(binding, trust);
    requireCurrentOperation(current, operationId.toString());
    ReadinessProbePlan plan = requireStoredPlan(current);
    ProbeEntry entry =
        selectEntry(operationId, validatorId, tokenProfile, audience, probeKind, true);
    if (entry == null
        || !entry.jti().equals(jti)
        || !entry.planDigest().equals(plan.planDigest())) {
      throw new StaleOperationException("Readiness probe selector is not the exact current entry");
    }
    return entry;
  }

  /**
   * Records only the exact authenticated, cryptographically verified observation for one current
   * ISSUED entry. A matching VERIFIED retry reads back the original immutable receipt.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ProbeEntry recordVerified(
      Binding binding,
      TrustFence trust,
      UUID operationId,
      String validatorId,
      String tokenProfile,
      String audience,
      ProbeKind probeKind,
      UUID jti,
      VerifiedProbeObservation observation) {
    requireWritableAccountTransaction();
    requireOperationId(operationId);
    Objects.requireNonNull(observation, "Authenticated verification observation is required");
    long observedAt = exactEpochSecond(Instant.ofEpochSecond(observation.observedAtEpochSecond()));
    CurrentEvidence current = lockCurrentEvidence(binding, trust);
    requireCurrentOperation(current, operationId.toString());
    ReadinessProbePlan plan = requireStoredPlan(current);
    ProbeEntry entry =
        selectEntry(operationId, validatorId, tokenProfile, audience, probeKind, true);
    if (entry == null
        || !entry.jti().equals(jti)
        || !entry.planDigest().equals(plan.planDigest())
        || !entry.targetGeneration().equals(plan.targetGeneration())
        || !entry.targetKid().equals(plan.targetKid())
        || !entry.expectedActive().equals(plan.expectedFence().durableActive())
        || !entry.expectedActive().equals(plan.expectedFence().publishedActive())
        || entry.registryVersion() != 1
        || !entry.compactTokenSha256().equals(Optional.of(observation.compactTokenSha256()))
        || !entry.targetKid().equals(observation.verifiedKid())) {
      throw new StaleOperationException(
          "Verified readiness probe does not match the exact current operation entry");
    }
    requireNotExpired(entry, observedAt);
    if (observedAt < entry.plannedIssuedAtEpochSecond()) {
      throw new CacheAgeNotElapsedException();
    }

    if (entry.state() == ProbeState.VERIFIED) {
      VerificationReceipt existing =
          entry
              .verificationReceipt()
              .orElseThrow(
                  () ->
                      new QuarantinedStateException(
                          "Verified readiness entry has no immutable receipt"));
      requireMatchingObservation(existing, observation);
      return entry;
    }
    if (entry.state() != ProbeState.ISSUED || entry.verificationReceipt().isPresent()) {
      throw new AmbiguousDeliveryException("Readiness probe is not the exact current ISSUED entry");
    }

    long resultEntryVersion = Math.addExact(entry.entryVersion(), 1L);
    VerificationReceipt receipt = verificationReceipt(plan, entry, observation, resultEntryVersion);
    int changed =
        dsl.execute(
            "UPDATE "
                + ENTRY_TABLE
                + " SET state = 'VERIFIED', entry_version = ?, verification_receipt_version = ?, "
                + "verification_source_entry_version = ?, verified_at_epoch_seconds = ?, "
                + "verified_kid = ?, validator_instance_id = ?, validator_binding_digest = ?, "
                + "validator_config_revision = ?, validator_peer_uri = ?, "
                + "validator_peer_spki_sha256 = ?, verification_receipt_sha256 = ? "
                + "WHERE rotation_operation_id = ? AND validator_id = ? AND token_profile = ? "
                + "AND audience = ? AND probe_kind = ? AND jti = ? AND plan_digest = ? "
                + "AND state = 'ISSUED' AND entry_version = ? AND target_generation = ? "
                + "AND target_kid = ? AND compact_token_sha256 = ? "
                + "AND verification_receipt_sha256 IS NULL "
                + "AND CURRENT_TIMESTAMP >= to_timestamp(planned_issued_at_epoch_seconds) "
                + "AND CURRENT_TIMESTAMP < to_timestamp(expires_at_epoch_seconds)",
            receipt.resultEntryVersion(),
            receipt.receiptVersion(),
            receipt.sourceEntryVersion(),
            receipt.observedAtEpochSecond(),
            receipt.verifiedKid(),
            receipt.validatorInstanceId(),
            receipt.validatorBindingDigest(),
            receipt.validatorConfigRevision(),
            receipt.validatorPeerUri(),
            receipt.validatorPeerSpkiSha256(),
            receipt.receiptSha256(),
            operationId,
            validatorId,
            tokenProfile,
            audience,
            probeKind.name(),
            jti,
            plan.planDigest(),
            entry.entryVersion(),
            plan.targetGeneration(),
            plan.targetKid(),
            observation.compactTokenSha256());
    if (changed != 1) {
      throw new VersionConflictException("Readiness VERIFIED transition lost its exact CAS");
    }
    ProbeEntry readback = selectEntry(entry, true);
    if (readback == null
        || readback.state() != ProbeState.VERIFIED
        || readback.entryVersion() != receipt.resultEntryVersion()
        || readback.verificationReceipt().filter(receipt::equals).isEmpty()) {
      throw new QuarantinedStateException("Verified readiness receipt did not read back exactly");
    }
    return readback;
  }

  /**
   * Pins the exact signed-token hash before delivery. A row with this pin but no ISSUED result is
   * ambiguous after a crash and must be quarantined; it is never signed or delivered again.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ProbeEntry recordSigningAttempt(
      Binding binding, TrustFence trust, SignedProbeDigest signed, Instant attemptedAt) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(signed, "Mounted signer result is required");
    long attemptedEpoch = exactEpochSecond(attemptedAt);
    CurrentEvidence current = lockCurrentEvidence(binding, trust);
    requireCurrentOperation(current, signed.operationId());
    ReadinessProbePlan plan = requireStoredPlan(current);
    ProbeEntry entry =
        selectEntry(
            plan.operationId(),
            signed.validatorId(),
            signed.tokenProfile(),
            signed.audience(),
            signed.probeKind(),
            true);
    requireSignedResultMatches(plan, entry, signed);
    requireNotExpired(entry, attemptedEpoch);
    if (attemptedEpoch < entry.plannedIssuedAtEpochSecond()) {
      throw new CacheAgeNotElapsedException();
    }
    if (entry.state() != ProbeState.PLANNED
        || entry.compactTokenSha256().isPresent()
        || entry.signingAttemptedAtEpochSecond().isPresent()) {
      throw new AmbiguousDeliveryException("Readiness probe already has a signing attempt");
    }
    int changed =
        dsl.execute(
            "UPDATE "
                + ENTRY_TABLE
                + " SET signing_attempted_at_epoch_seconds = ?, compact_token_sha256 = ?, "
                + "entry_version = entry_version + 1 WHERE rotation_operation_id = ? "
                + "AND validator_id = ? AND token_profile = ? AND audience = ? AND probe_kind = ? "
                + "AND plan_digest = ? AND state = 'PLANNED' AND entry_version = ? "
                + "AND compact_token_sha256 IS NULL AND signing_attempted_at_epoch_seconds IS NULL",
            attemptedEpoch,
            signed.compactTokenSha256(),
            plan.operationId(),
            signed.validatorId(),
            signed.tokenProfile(),
            signed.audience(),
            signed.probeKind().name(),
            plan.planDigest(),
            entry.entryVersion());
    if (changed != 1) {
      throw new VersionConflictException("Readiness probe signing attempt lost its exact CAS");
    }
    ProbeEntry readback =
        selectEntry(
            plan.operationId(),
            signed.validatorId(),
            signed.tokenProfile(),
            signed.audience(),
            signed.probeKind(),
            true);
    if (readback == null
        || readback.state() != ProbeState.PLANNED
        || readback.entryVersion() != entry.entryVersion() + 1
        || !readback.compactTokenSha256().equals(Optional.of(signed.compactTokenSha256()))
        || !readback.signingAttemptedAtEpochSecond().equals(Optional.of(attemptedEpoch))) {
      throw new QuarantinedStateException(
          "Readiness probe signing attempt did not read back exactly");
    }
    return readback;
  }

  /** Durably records ISSUED before the exact compact probe reaches its transient consumer. */
  @Transactional(propagation = Propagation.MANDATORY)
  public ProbeEntry recordIssued(
      Binding binding, TrustFence trust, SignedProbeDigest signed, Instant issuedAt) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(signed, "Mounted signer result is required");
    long issuedEpoch = exactEpochSecond(issuedAt);
    CurrentEvidence current = lockCurrentEvidence(binding, trust);
    requireCurrentOperation(current, signed.operationId());
    ReadinessProbePlan plan = requireStoredPlan(current);
    ProbeEntry entry =
        selectEntry(
            plan.operationId(),
            signed.validatorId(),
            signed.tokenProfile(),
            signed.audience(),
            signed.probeKind(),
            true);
    requireSignedResultMatches(plan, entry, signed);
    requireNotExpired(entry, issuedEpoch);
    if (issuedEpoch < entry.plannedIssuedAtEpochSecond()) {
      throw new CacheAgeNotElapsedException();
    }
    if (entry.state() == ProbeState.ISSUED) {
      if (!entry.compactTokenSha256().equals(Optional.of(signed.compactTokenSha256()))) {
        throw new IdempotencyConflictException("Issued readiness probe hash changed");
      }
      return entry;
    }
    if (entry.state() != ProbeState.PLANNED
        || !entry.compactTokenSha256().equals(Optional.of(signed.compactTokenSha256()))
        || entry.signingAttemptedAtEpochSecond().isEmpty()) {
      throw new AmbiguousDeliveryException("Readiness probe is not pinned for exact delivery");
    }
    int changed =
        dsl.execute(
            "UPDATE "
                + ENTRY_TABLE
                + " SET state = 'ISSUED', entry_version = entry_version + 1 "
                + "WHERE rotation_operation_id = ? AND validator_id = ? AND token_profile = ? "
                + "AND audience = ? AND probe_kind = ? AND plan_digest = ? "
                + "AND state = 'PLANNED' AND entry_version = ? AND compact_token_sha256 = ? "
                + "AND signing_attempted_at_epoch_seconds IS NOT NULL",
            plan.operationId(),
            signed.validatorId(),
            signed.tokenProfile(),
            signed.audience(),
            signed.probeKind().name(),
            plan.planDigest(),
            entry.entryVersion(),
            signed.compactTokenSha256());
    if (changed != 1) {
      throw new VersionConflictException("Readiness probe ISSUED transition lost its exact CAS");
    }
    ProbeEntry readback =
        selectEntry(
            plan.operationId(),
            signed.validatorId(),
            signed.tokenProfile(),
            signed.audience(),
            signed.probeKind(),
            true);
    if (readback == null
        || readback.state() != ProbeState.ISSUED
        || readback.entryVersion() != entry.entryVersion() + 1
        || !readback.compactTokenSha256().equals(Optional.of(signed.compactTokenSha256()))) {
      throw new QuarantinedStateException("Issued readiness probe did not read back exactly");
    }
    return readback;
  }

  /** Quarantines a pinned PLANNED attempt after delivery or persistence became ambiguous. */
  @Transactional(propagation = Propagation.MANDATORY)
  public ProbeEntry abortAmbiguousAttempt(
      Binding binding,
      TrustFence trust,
      UUID operationId,
      String validatorId,
      String tokenProfile,
      String audience,
      ProbeKind probeKind,
      Instant terminalAt) {
    requireWritableAccountTransaction();
    long terminalEpoch = exactEpochSecond(terminalAt);
    CurrentEvidence current = lockCurrentEvidence(binding, trust);
    requireCurrentOperation(current, operationId.toString());
    requireStoredPlan(current);
    ProbeEntry entry =
        selectEntry(operationId, validatorId, tokenProfile, audience, probeKind, true);
    if (entry == null
        || entry.state() != ProbeState.PLANNED
        || entry.compactTokenSha256().isEmpty()) {
      throw new AmbiguousDeliveryException("No ambiguous pinned readiness attempt exists");
    }
    return transitionTerminal(entry, ProbeState.ABORTED, terminalEpoch);
  }

  /** Aborts only entries belonging to the exact current Account operation. */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<ProbeEntry> abortCurrent(
      Binding binding, TrustFence trust, UUID operationId, Instant terminalAt) {
    requireWritableAccountTransaction();
    long terminalEpoch = exactEpochSecond(terminalAt);
    CurrentEvidence current = lockCurrentEvidence(binding, trust);
    requireCurrentOperation(current, operationId.toString());
    ReadinessProbePlan plan = requireStoredPlan(current);
    List<ProbeEntry> result = new ArrayList<>();
    for (ProbeEntry entry : plan.entries()) {
      ProbeEntry currentEntry = selectEntry(entry, true);
      if (currentEntry.state() == ProbeState.PLANNED
          || currentEntry.state() == ProbeState.ISSUED
          || currentEntry.state() == ProbeState.VERIFIED) {
        result.add(transitionTerminal(currentEntry, ProbeState.ABORTED, terminalEpoch));
      } else {
        result.add(currentEntry);
      }
    }
    return List.copyOf(result);
  }

  /** Expires only this exact current operation after its immutable entry expiry. */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<ProbeEntry> expireCurrent(
      Binding binding, TrustFence trust, UUID operationId, Instant now) {
    requireWritableAccountTransaction();
    long nowEpoch = exactEpochSecond(now);
    CurrentEvidence current = lockCurrentEvidence(binding, trust);
    requireCurrentOperation(current, operationId.toString());
    ReadinessProbePlan plan = requireStoredPlan(current);
    List<ProbeEntry> result = new ArrayList<>();
    for (ProbeEntry entry : plan.entries()) {
      ProbeEntry currentEntry = selectEntry(entry, true);
      if ((currentEntry.state() == ProbeState.PLANNED
              || currentEntry.state() == ProbeState.ISSUED
              || currentEntry.state() == ProbeState.VERIFIED)
          && nowEpoch >= currentEntry.expiresAtEpochSecond()) {
        result.add(transitionTerminal(currentEntry, ProbeState.EXPIRED, nowEpoch));
      } else {
        result.add(currentEntry);
      }
    }
    return List.copyOf(result);
  }

  /**
   * Bounded cleanup of terminal entries for one explicit operation. It never queries or scans
   * another operation and retains all non-secret identity/hash/terminal evidence.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<ProbeEntry> cleanTerminalEntries(UUID operationId, int maximumEntries) {
    requireWritableAccountTransaction();
    requireOperationId(operationId);
    if (maximumEntries < 1 || maximumEntries > MAX_ENTRIES_PER_OPERATION) {
      throw new IllegalArgumentException("Readiness cleanup bound is outside its fixed limit");
    }
    Result<Record> terminalRows =
        dsl.fetch(
            "SELECT * FROM "
                + ENTRY_TABLE
                + " WHERE rotation_operation_id = ? AND state IN ('ABORTED', 'EXPIRED') "
                + "ORDER BY validator_id, token_profile, audience, probe_kind LIMIT ? FOR UPDATE",
            operationId,
            maximumEntries);
    if (terminalRows.size() > maximumEntries) {
      throw new QuarantinedStateException("Readiness cleanup exceeded its exact operation bound");
    }
    List<ProbeEntry> cleaned = new ArrayList<>();
    for (Record row : terminalRows) {
      ProbeEntry entry = decodeEntry(row);
      cleaned.add(transitionTerminal(entry, ProbeState.CLEANED, null));
    }
    return List.copyOf(cleaned);
  }

  private ProbeEntry transitionTerminal(ProbeEntry entry, ProbeState target, Long transitionEpoch) {
    if (target == ProbeState.EXPIRED && transitionEpoch == null) {
      throw new IllegalArgumentException("Expiry time is required");
    }
    String outcome =
        target == ProbeState.CLEANED ? entry.terminalOutcome().orElseThrow() : target.name();
    int changed =
        dsl.execute(
            "UPDATE "
                + ENTRY_TABLE
                + " SET state = ?, terminal_outcome = ?, entry_version = entry_version + 1 "
                + "WHERE rotation_operation_id = ? AND validator_id = ? AND token_profile = ? "
                + "AND audience = ? AND probe_kind = ? AND state = ? AND entry_version = ?",
            target.name(),
            outcome,
            entry.rotationOperationId(),
            entry.validatorId(),
            entry.tokenProfile(),
            entry.audience(),
            entry.probeKind().name(),
            entry.state().name(),
            entry.entryVersion());
    if (changed != 1) {
      throw new VersionConflictException("Readiness terminal transition lost its exact CAS");
    }
    ProbeEntry readback = selectEntry(entry, true);
    if (readback == null
        || readback.state() != target
        || readback.entryVersion() != entry.entryVersion() + 1
        || !readback.terminalOutcome().equals(Optional.of(outcome))) {
      throw new QuarantinedStateException(
          "Readiness terminal transition did not read back exactly");
    }
    return readback;
  }

  private ReadinessProbePlan newPlan(
      CurrentEvidence current,
      long plannedAtEpochSecond,
      Optional<String> inventorySnapshotDigest) {
    long notBefore = add(plannedAtEpochSecond, MAX_VALIDATOR_CACHE_AGE_SECONDS);
    long expiresAt = add(notBefore, PROBE_LIFETIME_SECONDS);
    String matrixJson = applicabilityMatrixJson();
    String matrixDigest = sha256(matrixJson.getBytes(StandardCharsets.UTF_8));
    List<ProbeEntry> entries =
        List.of(
                newEntry(
                    current,
                    ProbeKind.CANARY,
                    CANARY_PROFILE,
                    CANARY_AUDIENCE,
                    notBefore,
                    expiresAt),
                newEntry(
                    current,
                    ProbeKind.REPRESENTATIVE,
                    CONTROL_UI_PROFILE,
                    CONTROL_UI_AUDIENCE,
                    notBefore,
                    expiresAt),
                newEntry(
                    current,
                    ProbeKind.REPRESENTATIVE,
                    PLAYER_BOOTSTRAP_PROFILE,
                    PLAYER_BOOTSTRAP_AUDIENCE,
                    notBefore,
                    expiresAt),
                newEntry(
                    current,
                    ProbeKind.REPRESENTATIVE,
                    REPRESENTATIVE_PROFILE,
                    REPRESENTATIVE_AUDIENCE,
                    notBefore,
                    expiresAt))
            .stream()
            .sorted(PROBE_ENTRY_ORDER)
            .toList();
    String planDigest =
        planDigest(
            current,
            matrixJson,
            matrixDigest,
            plannedAtEpochSecond,
            notBefore,
            expiresAt,
            entries,
            inventorySnapshotDigest);
    List<ProbeEntry> boundEntries =
        entries.stream().map(entry -> entry.withPlanDigest(planDigest)).toList();
    return ReadinessProbePlan.fromCurrent(
        current,
        matrixJson,
        matrixDigest,
        false,
        MAX_VALIDATOR_CACHE_AGE_SECONDS,
        plannedAtEpochSecond,
        notBefore,
        expiresAt,
        planDigest,
        boundEntries,
        inventorySnapshotDigest);
  }

  private static ProbeEntry newEntry(
      CurrentEvidence current,
      ProbeKind kind,
      String profile,
      String audience,
      long notBefore,
      long expiresAt) {
    return new ProbeEntry(
        current.request().operationId(),
        "0".repeat(64),
        VALIDATOR_ID,
        profile,
        audience,
        kind,
        UUID.randomUUID(),
        current.request().targetGeneration(),
        current.request().targetKid(),
        current.state().durableActive(),
        1,
        1,
        notBefore,
        expiresAt,
        ProbeState.PLANNED,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private void insertPlan(ReadinessProbePlan plan) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("rotation_operation_id", plan.operationId());
    fields.put("environment_id", plan.binding().environmentId());
    fields.put("cluster_id", plan.binding().clusterId());
    fields.put("kubernetes_namespace", plan.binding().namespace());
    fields.put("custody_mode", plan.binding().mode().value());
    fields.put("operation_digest", plan.operationDigest());
    fields.put("generation_request_digest", plan.generationRequestDigest());
    fields.put("generation_receipt_digest", plan.generationReceiptDigest());
    fields.put("desired_state_version", plan.desiredStateVersion());
    fields.put(
        "expected_cluster_incarnation_uid",
        UUID.fromString(plan.trustFence().expectedClusterIncarnationUid()));
    fields.put("expected_namespace_uid", UUID.fromString(plan.trustFence().expectedNamespaceUid()));
    fields.put("trust_binding_digest", plan.trustFence().bindingDigest());
    fields.put("trust_config_revision", plan.trustFence().configRevision());
    fields.put("target_generation", Long.parseLong(plan.targetGeneration()));
    fields.put("target_kid", plan.targetKid());
    fields.put("target_public_key_fingerprint", plan.targetPublicKeyFingerprint());
    fields.put(
        "expected_active_generation",
        plan.expectedFence()
            .durableActive()
            .map(value -> Long.parseLong(value.generation()))
            .orElse(null));
    fields.put(
        "expected_active_kid",
        plan.expectedFence().durableActive().map(ActiveSigner::kid).orElse(null));
    fields.put(
        "expected_published_generation",
        plan.expectedFence()
            .publishedActive()
            .map(value -> Long.parseLong(value.generation()))
            .orElse(null));
    fields.put(
        "expected_published_kid",
        plan.expectedFence().publishedActive().map(ActiveSigner::kid).orElse(null));
    fields.put("publication_intent_digest", plan.publicationIntentDigest());
    fields.put("publication_receipt_digest", plan.publicationReceiptDigest());
    fields.put("mounted_observation_digest", plan.mountedObservationDigest());
    fields.put("applicability_matrix_json", plan.applicabilityMatrixJson());
    fields.put("applicability_matrix_digest", plan.applicabilityMatrixDigest());
    fields.put("validator_inventory_complete", plan.validatorInventoryComplete());
    fields.put("inventory_snapshot_digest", plan.inventorySnapshotDigest().orElse(null));
    fields.put("maximum_cache_age_seconds", plan.maximumCacheAgeSeconds());
    fields.put("planned_at_epoch_seconds", plan.plannedAtEpochSecond());
    fields.put("not_before_epoch_seconds", plan.notBeforeEpochSecond());
    fields.put("expires_at_epoch_seconds", plan.expiresAtEpochSecond());
    fields.put("plan_version", 1);
    fields.put("plan_digest", plan.planDigest());
    insertOne(dsl, PLAN_TABLE, fields);
  }

  private void insertDeliveryClaim(DeliveryClaim claim) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("rotation_operation_id", claim.rotationOperationId());
    fields.put("plan_digest", claim.planDigest());
    fields.put("expected_cluster_incarnation_uid", UUID.fromString(claim.expectedClusterUid()));
    fields.put("expected_namespace_uid", UUID.fromString(claim.expectedNamespaceUid()));
    fields.put("materializer_trust_binding_digest", claim.materializerTrustBindingDigest());
    fields.put("materializer_trust_config_revision", claim.materializerTrustConfigRevision());
    fields.put("target_generation", Long.parseLong(claim.targetGeneration()));
    fields.put("target_kid", claim.targetKid());
    fields.put(
        "expected_active_generation",
        claim.expectedActive().map(value -> Long.parseLong(value.generation())).orElse(null));
    fields.put("expected_active_kid", claim.expectedActive().map(ActiveSigner::kid).orElse(null));
    fields.put("readiness_binding_digest", claim.readinessBindingDigest());
    fields.put("readiness_config_revision", claim.readinessConfigRevision());
    fields.put(
        "readiness_binding_valid_until_epoch_seconds",
        claim.readinessBindingValidUntilEpochSecond());
    fields.put("validator_id", claim.validatorId());
    fields.put("validator_instance_id", claim.validatorInstanceId());
    fields.put("validator_peer_uri", claim.validatorPeerUri());
    fields.put("validator_peer_spki_sha256", claim.validatorPeerSpkiSha256());
    fields.put("claimed_at_epoch_seconds", claim.claimedAtEpochSecond());
    fields.put("expires_at_epoch_seconds", claim.expiresAtEpochSecond());
    fields.put("claim_digest", claim.claimDigest());
    insertOne(dsl, DELIVERY_CLAIM_TABLE, fields);
  }

  private DeliveryClaim selectDeliveryClaim(UUID operationId, boolean lock) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + DELIVERY_CLAIM_TABLE
                + " WHERE rotation_operation_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            operationId);
    if (row == null) {
      return null;
    }
    Long activeGeneration = row.get("expected_active_generation", Long.class);
    String activeKid = row.get("expected_active_kid", String.class);
    DeliveryClaim result =
        new DeliveryClaim(
            row.get("rotation_operation_id", UUID.class),
            row.get("plan_digest", String.class),
            row.get("expected_cluster_incarnation_uid", UUID.class).toString(),
            row.get("expected_namespace_uid", UUID.class).toString(),
            row.get("materializer_trust_binding_digest", String.class),
            row.get("materializer_trust_config_revision", String.class),
            String.valueOf(row.get("target_generation", Long.class)),
            row.get("target_kid", String.class),
            decodeActive(activeGeneration, activeKid),
            row.get("readiness_binding_digest", String.class),
            row.get("readiness_config_revision", String.class),
            row.get("readiness_binding_valid_until_epoch_seconds", Long.class),
            row.get("validator_id", String.class),
            row.get("validator_instance_id", String.class),
            row.get("validator_peer_uri", String.class),
            row.get("validator_peer_spki_sha256", String.class),
            row.get("claimed_at_epoch_seconds", Long.class),
            row.get("expires_at_epoch_seconds", Long.class),
            row.get("claim_digest", String.class));
    if (!result.claimDigest().equals(deliveryClaimDigest(result))) {
      throw new QuarantinedStateException("Account readiness delivery claim digest is invalid");
    }
    return result;
  }

  private void insertEntry(ProbeEntry entry) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("rotation_operation_id", entry.rotationOperationId());
    fields.put("plan_digest", entry.planDigest());
    fields.put("validator_id", entry.validatorId());
    fields.put("token_profile", entry.tokenProfile());
    fields.put("audience", entry.audience());
    fields.put("probe_kind", entry.probeKind().name());
    fields.put("jti", entry.jti());
    fields.put("target_generation", Long.parseLong(entry.targetGeneration()));
    fields.put("target_kid", entry.targetKid());
    fields.put(
        "expected_active_generation",
        entry.expectedActive().map(value -> Long.parseLong(value.generation())).orElse(null));
    fields.put("expected_active_kid", entry.expectedActive().map(ActiveSigner::kid).orElse(null));
    fields.put("registry_version", entry.registryVersion());
    fields.put("entry_version", entry.entryVersion());
    fields.put("planned_issued_at_epoch_seconds", entry.plannedIssuedAtEpochSecond());
    fields.put("expires_at_epoch_seconds", entry.expiresAtEpochSecond());
    fields.put("state", entry.state().name());
    fields.put("terminal_outcome", null);
    fields.put("signing_attempted_at_epoch_seconds", null);
    fields.put("compact_token_sha256", null);
    insertOne(dsl, ENTRY_TABLE, fields);
  }

  private ReadinessProbePlan selectPlan(UUID operationId, boolean lock) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + PLAN_TABLE
                + " WHERE rotation_operation_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            operationId);
    return row == null ? null : decodePlan(row, selectEntries(operationId, lock));
  }

  private ReadinessProbePlan readbackPlan(ReadinessProbePlan plan, boolean lock) {
    List<ProbeEntry> entries = selectEntries(plan.operationId(), lock);
    if (!entries.equals(plan.entries())) {
      throw new QuarantinedStateException("Readiness probe entries changed after plan persistence");
    }
    if (!plan.planDigest().equals(planDigest(plan))) {
      throw new QuarantinedStateException("Readiness probe plan digest is invalid");
    }
    plan.inventorySnapshotDigest()
        .ifPresent(
            digest -> inventoryRepository.readRequired(digest, plan.binding(), plan.trustFence()));
    return plan;
  }

  private List<ProbeEntry> selectEntries(UUID operationId, boolean lock) {
    Result<Record> rows =
        dsl.fetch(
            "SELECT * FROM "
                + ENTRY_TABLE
                + " WHERE rotation_operation_id = ? ORDER BY validator_id, CASE token_profile WHEN '"
                + CANARY_PROFILE
                + "' THEN 0 WHEN '"
                + CONTROL_UI_PROFILE
                + "' THEN 1 WHEN '"
                + PLAYER_BOOTSTRAP_PROFILE
                + "' THEN 2 WHEN '"
                + REPRESENTATIVE_PROFILE
                + "' THEN 3 ELSE 4 END, token_profile, audience, probe_kind"
                + (lock ? " FOR UPDATE" : ""),
            operationId);
    List<ProbeEntry> entries = new ArrayList<>(rows.size());
    for (Record row : rows) {
      entries.add(decodeEntry(row));
    }
    return List.copyOf(entries);
  }

  // Keep the candidate's digest/equality order identical to the durable key order. If a future
  // schema adds identity dimensions (for example Pod membership), append them to both this
  // comparator and SELECT's ORDER BY; V1 plans retain their existing tuple and digest semantics.
  private static final Comparator<ProbeEntry> PROBE_ENTRY_ORDER =
      Comparator.comparing(ProbeEntry::validatorId)
          // V1 plan digests bind the established tuple order. Keep it explicit and shared with
          // durable readback while appending any future identity dimensions consistently.
          .thenComparingInt(entry -> profileOrder(entry.tokenProfile()))
          .thenComparing(ProbeEntry::audience)
          .thenComparing(entry -> entry.probeKind().name());

  private static int profileOrder(String profile) {
    if (CANARY_PROFILE.equals(profile)) return 0;
    if (CONTROL_UI_PROFILE.equals(profile)) return 1;
    if (PLAYER_BOOTSTRAP_PROFILE.equals(profile)) return 2;
    if (REPRESENTATIVE_PROFILE.equals(profile)) return 3;
    throw new QuarantinedStateException("Readiness probe profile has no canonical plan order");
  }

  private ProbeEntry selectEntry(
      UUID operationId,
      String validatorId,
      String tokenProfile,
      String audience,
      ProbeKind probeKind,
      boolean lock) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + ENTRY_TABLE
                + " WHERE rotation_operation_id = ? AND validator_id = ? AND token_profile = ? "
                + "AND audience = ? AND probe_kind = ?"
                + (lock ? " FOR UPDATE" : ""),
            operationId,
            validatorId,
            tokenProfile,
            audience,
            probeKind.name());
    return row == null ? null : decodeEntry(row);
  }

  private ProbeEntry selectEntry(ProbeEntry entry, boolean lock) {
    return selectEntry(
        entry.rotationOperationId(),
        entry.validatorId(),
        entry.tokenProfile(),
        entry.audience(),
        entry.probeKind(),
        lock);
  }

  private ProbeEntry decodeEntry(Record row) {
    Long activeGeneration = row.get("expected_active_generation", Long.class);
    String activeKid = row.get("expected_active_kid", String.class);
    Optional<ActiveSigner> expectedActive = decodeActive(activeGeneration, activeKid);
    Long attemptAt = row.get("signing_attempted_at_epoch_seconds", Long.class);
    String hash = row.get("compact_token_sha256", String.class);
    String terminalOutcome = row.get("terminal_outcome", String.class);
    VerificationReceipt receipt = decodeVerificationReceipt(row);
    return new ProbeEntry(
        row.get("rotation_operation_id", UUID.class),
        row.get("plan_digest", String.class),
        row.get("validator_id", String.class),
        row.get("token_profile", String.class),
        row.get("audience", String.class),
        ProbeKind.valueOf(row.get("probe_kind", String.class)),
        row.get("jti", UUID.class),
        String.valueOf(row.get("target_generation", Long.class)),
        row.get("target_kid", String.class),
        expectedActive,
        row.get("registry_version", Short.class).intValue(),
        row.get("entry_version", Long.class),
        row.get("planned_issued_at_epoch_seconds", Long.class),
        row.get("expires_at_epoch_seconds", Long.class),
        ProbeState.valueOf(row.get("state", String.class)),
        Optional.ofNullable(terminalOutcome),
        Optional.ofNullable(attemptAt),
        Optional.ofNullable(hash),
        Optional.ofNullable(receipt));
  }

  private VerificationReceipt decodeVerificationReceipt(Record row) {
    Short version = row.get("verification_receipt_version", Short.class);
    Long sourceVersion = row.get("verification_source_entry_version", Long.class);
    Long verifiedAt = row.get("verified_at_epoch_seconds", Long.class);
    String verifiedKid = row.get("verified_kid", String.class);
    String validatorInstanceId = row.get("validator_instance_id", String.class);
    String bindingDigest = row.get("validator_binding_digest", String.class);
    String configRevision = row.get("validator_config_revision", String.class);
    String peerUri = row.get("validator_peer_uri", String.class);
    String peerSpki = row.get("validator_peer_spki_sha256", String.class);
    String receiptSha256 = row.get("verification_receipt_sha256", String.class);
    if (version == null
        && sourceVersion == null
        && verifiedAt == null
        && verifiedKid == null
        && validatorInstanceId == null
        && bindingDigest == null
        && configRevision == null
        && peerUri == null
        && peerSpki == null
        && receiptSha256 == null) {
      return null;
    }
    if (version == null
        || sourceVersion == null
        || verifiedAt == null
        || verifiedKid == null
        || validatorInstanceId == null
        || bindingDigest == null
        || configRevision == null
        || peerUri == null
        || peerSpki == null
        || receiptSha256 == null) {
      throw new QuarantinedStateException("Readiness verification receipt is partial");
    }
    Long resultVersion = Math.addExact(sourceVersion, 1L);
    return new VerificationReceipt(
        version.intValue(),
        sourceVersion,
        resultVersion,
        verifiedAt,
        receiptSha256,
        verifiedKid,
        validatorInstanceId,
        bindingDigest,
        configRevision,
        peerUri,
        peerSpki);
  }

  private ReadinessProbePlan decodePlan(Record row, List<ProbeEntry> entries) {
    Long activeGeneration = row.get("expected_active_generation", Long.class);
    String activeKid = row.get("expected_active_kid", String.class);
    Long publishedGeneration = row.get("expected_published_generation", Long.class);
    String publishedKid = row.get("expected_published_kid", String.class);
    SignerFence fence =
        new SignerFence(
            decodeActive(activeGeneration, activeKid),
            decodeActive(publishedGeneration, publishedKid));
    return new ReadinessProbePlan(
        row.get("rotation_operation_id", UUID.class),
        new Binding(
            row.get("environment_id", String.class),
            row.get("cluster_id", String.class),
            row.get("kubernetes_namespace", String.class),
            AccountJwtSignerDesiredStateRepository.CustodyMode
                .INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK),
        new TrustFence(
            row.get("expected_cluster_incarnation_uid", UUID.class).toString(),
            row.get("expected_namespace_uid", UUID.class).toString(),
            row.get("trust_binding_digest", String.class),
            row.get("trust_config_revision", String.class)),
        row.get("operation_digest", String.class),
        row.get("generation_request_digest", String.class),
        row.get("generation_receipt_digest", String.class),
        row.get("desired_state_version", Long.class),
        String.valueOf(row.get("target_generation", Long.class)),
        row.get("target_kid", String.class),
        row.get("target_public_key_fingerprint", String.class),
        fence,
        row.get("publication_intent_digest", String.class),
        row.get("publication_receipt_digest", String.class),
        row.get("mounted_observation_digest", String.class),
        row.get("applicability_matrix_json", String.class),
        row.get("applicability_matrix_digest", String.class),
        row.get("validator_inventory_complete", Boolean.class),
        row.get("maximum_cache_age_seconds", Short.class).intValue(),
        row.get("planned_at_epoch_seconds", Long.class),
        row.get("not_before_epoch_seconds", Long.class),
        row.get("expires_at_epoch_seconds", Long.class),
        row.get("plan_version", Short.class).intValue(),
        row.get("plan_digest", String.class),
        entries,
        Optional.ofNullable(row.get("inventory_snapshot_digest", String.class)));
  }

  private static Optional<ActiveSigner> decodeActive(Long generation, String kid) {
    if (generation == null && kid == null) {
      return Optional.empty();
    }
    if (generation == null || kid == null) {
      throw new QuarantinedStateException("Readiness signer fence has a partial active pair");
    }
    return Optional.of(new ActiveSigner(generation.toString(), kid));
  }

  private void requirePlanMatchesCurrent(ReadinessProbePlan plan, CurrentEvidence current) {
    GenerationRequest request = current.request();
    GenerationResult result = current.result();
    AccountJwtJwksPublicationRepository.PublicationEvidence publication = current.publication();
    AccountJwtJwksPublicationRepository.PrepublicationIntent intent = publication.intent();
    AccountJwtJwksPublicationRepository.PublicationReceipt receipt =
        publication.receipt().orElseThrow();
    AccountJwtJwksPublicationRepository.MountObservation mount =
        publication.mountedCorrespondence().orElseThrow();
    if (!plan.operationId().equals(request.operationId())
        || !plan.binding().equals(request.binding())
        || !plan.trustFence().equals(request.trustFence())
        || !plan.operationDigest().equals(request.operationDigest())
        || !plan.generationRequestDigest().equals(result.generationRequestDigest())
        || !plan.generationReceiptDigest().equals(result.receiptDigest())
        || plan.desiredStateVersion() != result.desiredStateVersion()
        || !plan.targetGeneration().equals(result.targetGeneration())
        || !plan.targetKid().equals(result.targetKid())
        || !plan.targetPublicKeyFingerprint().equals(result.publicKeyFingerprint())
        || !plan.expectedFence().durableActive().equals(request.expectedActive())
        || !plan.expectedFence().publishedActive().equals(request.expectedPublishedActive())
        || !plan.publicationIntentDigest().equals(intent.intentDigest())
        || !plan.publicationReceiptDigest().equals(receipt.receiptDigest())
        || !plan.mountedObservationDigest().equals(mount.observationDigest())
        || !plan.applicabilityMatrixJson().equals(applicabilityMatrixJson())
        || !plan.applicabilityMatrixDigest()
            .equals(sha256(plan.applicabilityMatrixJson().getBytes(StandardCharsets.UTF_8)))
        || plan.validatorInventoryComplete()
        || !plan.planDigest().equals(planDigest(plan))) {
      throw new StaleOperationException(
          "Readiness probe plan no longer matches current Account evidence");
    }
  }

  private static void requirePreparedPlanMatches(
      ReadinessProbePlan plan,
      GenerationResult generation,
      AccountJwtSignerDesiredStateRepository.PromotionOperationEvidence promotion,
      AccountJwtJwksPublicationRepository.PreparedPublicationEvidence publication) {
    AccountJwtJwksPublicationRepository.PrepublicationIntent intent = publication.intent();
    AccountJwtJwksPublicationRepository.PublicationReceipt receipt = publication.receipt();
    AccountJwtJwksPublicationRepository.MountObservation mount =
        publication.mountedCorrespondence();
    if (!plan.operationId().equals(generation.operationId())
        || !plan.binding().equals(generation.binding())
        || !plan.trustFence().equals(generation.trustFence())
        || !plan.operationDigest().equals(generation.operationDigest())
        || !plan.generationRequestDigest().equals(generation.generationRequestDigest())
        || !plan.generationReceiptDigest().equals(generation.receiptDigest())
        || plan.desiredStateVersion() != generation.desiredStateVersion()
        || !plan.targetGeneration().equals(generation.targetGeneration())
        || !plan.targetKid().equals(generation.targetKid())
        || !plan.targetPublicKeyFingerprint().equals(generation.publicKeyFingerprint())
        || !plan.expectedFence().durableActive().equals(promotion.expectedPreviousActive())
        || !plan.expectedFence().publishedActive().equals(promotion.expectedPublishedActive())
        || !plan.publicationIntentDigest().equals(intent.intentDigest())
        || !plan.publicationReceiptDigest().equals(receipt.receiptDigest())
        || !plan.mountedObservationDigest().equals(mount.observationDigest())
        || !plan.planDigest().equals(promotion.readinessPlanDigest())
        || !intent.operationId().equals(generation.operationId())
        || !receipt.operationId().equals(generation.operationId())
        || !mount.operationId().equals(generation.operationId())
        || !promotion.prepublicationIntentDigest().equals(intent.intentDigest())
        || !promotion.prepublicationReceiptDigest().equals(receipt.receiptDigest())
        || !promotion.mountedObservationDigest().equals(mount.observationDigest())
        || !promotion.publicConfigMapUid().equals(intent.configMapUid())
        || !promotion.expectedPublicResourceVersion().equals(receipt.observedResourceVersion())
        || !promotion.expectedPublicJwksJson().equals(intent.jwksJson())
        || !promotion.apiBindingDigest().equals(intent.apiBindingDigest())
        || !promotion.apiConfigRevision().equals(intent.apiConfigRevision())
        || !plan.planDigest().equals(planDigest(plan))) {
      throw new QuarantinedStateException(
          "Prepared readiness evidence differs from exact Account generation/publication owner records");
    }
  }

  private CurrentEvidence lockCurrentEvidence(Binding binding, TrustFence trust) {
    Objects.requireNonNull(binding, "Account signer binding is required");
    Objects.requireNonNull(trust, "Current signer trust fence is required");
    GenerationRequest request = desiredStateRepository.readCurrentGenerationRequest(binding, trust);
    GenerationResult result = desiredStateRepository.readCurrentGenerationResult(binding, trust);
    DesiredState state = desiredStateRepository.read(binding);
    AccountJwtJwksPublicationRepository.PublicationEvidence publication =
        publicationRepository
            .readCurrentPublication(binding, trust)
            .orElseThrow(() -> new MissingPublicationEvidenceException());
    if (request.phase() != GenerationPhase.GENERATION_RECORDED
        || state.preparedOperationId().isPresent()
        || !state.generationOperationId().equals(Optional.of(request.operationId()))
        || !request.operationId().equals(result.operationId())
        || !request.binding().equals(binding)
        || !result.binding().equals(binding)
        || !request.trustFence().equals(trust)
        || !result.trustFence().equals(trust)
        || state.recordVersion() != result.desiredStateVersion()
        || !request.targetGeneration().equals(result.targetGeneration())
        || !request.targetKid().equals(result.targetKid())
        || !request.expectedActive().equals(state.durableActive())
        || !request.expectedPublishedActive().equals(state.publishedActive())
        || !state.durableActive().equals(state.publishedActive())) {
      throw new StaleOperationException(
          "Current Account JWT signer evidence is incomplete or changed");
    }
    AccountJwtJwksPublicationRepository.PrepublicationIntent intent = publication.intent();
    AccountJwtJwksPublicationRepository.PublicationReceipt receipt =
        publication.receipt().orElseThrow(() -> new MissingPublicationEvidenceException());
    AccountJwtJwksPublicationRepository.MountObservation mount =
        publication
            .mountedCorrespondence()
            .orElseThrow(() -> new MissingPublicationEvidenceException());
    if (!intent.operationId().equals(result.operationId())
        || !intent.operationDigest().equals(result.operationDigest())
        || !intent.generationRequestDigest().equals(result.generationRequestDigest())
        || !intent.generationReceiptDigest().equals(result.receiptDigest())
        || !intent.targetGeneration().equals(result.targetGeneration())
        || !intent.targetKid().equals(result.targetKid())
        || !intent.publicKeyFingerprint().equals(result.publicKeyFingerprint())
        || !receipt.operationId().equals(result.operationId())
        || !receipt.intentDigest().equals(intent.intentDigest())
        || !mount.operationId().equals(result.operationId())
        || !mount.intentDigest().equals(intent.intentDigest())
        || !mount.publicationReceiptDigest().equals(receipt.receiptDigest())
        || !mount.publicKeyFingerprint().equals(result.publicKeyFingerprint())) {
      throw new StaleOperationException(
          "Account JWT publication evidence is not exact for readiness");
    }
    return new CurrentEvidence(state, request, result, publication);
  }

  private ReadinessProbePlan requireStoredPlan(CurrentEvidence current) {
    ReadinessProbePlan plan = selectPlan(current.request().operationId(), true);
    if (plan == null) {
      throw new MissingPlanException("Account JWT readiness probe plan is missing");
    }
    requirePlanMatchesCurrent(plan, current);
    return readbackPlan(plan, true);
  }

  private static void requireCurrentOperation(CurrentEvidence current, String operationId) {
    if (!current.request().operationId().toString().equals(operationId)) {
      throw new StaleOperationException("Readiness probe belongs to a stale Account operation");
    }
  }

  private static void requireSignedResultMatches(
      ReadinessProbePlan plan, ProbeEntry entry, SignedProbeDigest signed) {
    if (entry == null
        || !signed.operationId().equals(plan.operationId().toString())
        || !signed.validatorId().equals(entry.validatorId())
        || signed.probeKind() != entry.probeKind()
        || !signed.tokenProfile().equals(entry.tokenProfile())
        || !signed.audience().equals(entry.audience())
        || !signed.jti().equals(entry.jti())
        || !signed.targetGeneration().equals(entry.targetGeneration())
        || !signed.targetKid().equals(entry.targetKid())
        || !signed.publicKeyFingerprint().equals(plan.targetPublicKeyFingerprint())
        || signed.issuedAtEpochSecond() != entry.plannedIssuedAtEpochSecond()
        || signed.expiresAtEpochSecond() != entry.expiresAtEpochSecond()
        || !SHA256.matcher(signed.compactTokenSha256()).matches()) {
      throw new IdempotencyConflictException(
          "Mounted readiness signature does not match its exact plan");
    }
  }

  private static VerificationReceipt verificationReceipt(
      ReadinessProbePlan plan,
      ProbeEntry entry,
      VerifiedProbeObservation observation,
      long resultEntryVersion) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("receiptVersion", 1);
    fields.put("rotationOperationId", plan.operationId().toString());
    fields.put("planDigest", plan.planDigest());
    fields.put("operationDigest", plan.operationDigest());
    fields.put("generationRequestDigest", plan.generationRequestDigest());
    fields.put("generationReceiptDigest", plan.generationReceiptDigest());
    fields.put("desiredStateVersion", plan.desiredStateVersion());
    fields.put("environmentId", plan.binding().environmentId());
    fields.put("clusterId", plan.binding().clusterId());
    fields.put("namespace", plan.binding().namespace());
    fields.put(
        "trustFence",
        Map.of(
            "clusterIncarnationUid", plan.trustFence().expectedClusterIncarnationUid(),
            "namespaceUid", plan.trustFence().expectedNamespaceUid(),
            "bindingDigest", plan.trustFence().bindingDigest(),
            "configRevision", plan.trustFence().configRevision()));
    fields.put("targetGeneration", entry.targetGeneration());
    fields.put("targetKid", entry.targetKid());
    fields.put("targetPublicKeyFingerprint", plan.targetPublicKeyFingerprint());
    fields.put("expectedActive", activeMap(plan.expectedFence().durableActive()));
    fields.put("expectedPublishedActive", activeMap(plan.expectedFence().publishedActive()));
    fields.put("validatorId", entry.validatorId());
    fields.put("tokenProfile", entry.tokenProfile());
    fields.put("audience", entry.audience());
    fields.put("probeKind", entry.probeKind().name());
    fields.put("jti", entry.jti().toString());
    fields.put("registryVersion", entry.registryVersion());
    fields.put("sourceEntryVersion", entry.entryVersion());
    fields.put("resultEntryVersion", resultEntryVersion);
    fields.put("issuedAtEpochSecond", entry.plannedIssuedAtEpochSecond());
    fields.put("expiresAtEpochSecond", entry.expiresAtEpochSecond());
    fields.put("compactTokenSha256", observation.compactTokenSha256());
    fields.put("verifiedKid", observation.verifiedKid());
    fields.put("validatorInstanceId", observation.validatorInstanceId());
    fields.put("validatorBindingDigest", observation.validatorBindingDigest());
    fields.put("validatorConfigRevision", observation.validatorConfigRevision());
    fields.put("validatorPeerUri", observation.validatorPeerUri());
    fields.put("validatorPeerSpkiSha256", observation.validatorPeerSpkiSha256());
    fields.put("observedAtEpochSecond", observation.observedAtEpochSecond());
    return new VerificationReceipt(
        1,
        entry.entryVersion(),
        resultEntryVersion,
        observation.observedAtEpochSecond(),
        digest(fields),
        observation.verifiedKid(),
        observation.validatorInstanceId(),
        observation.validatorBindingDigest(),
        observation.validatorConfigRevision(),
        observation.validatorPeerUri(),
        observation.validatorPeerSpkiSha256());
  }

  private static void requireMatchingObservation(
      VerificationReceipt receipt, VerifiedProbeObservation observation) {
    if (!receipt.verifiedKid().equals(observation.verifiedKid())
        || !receipt.validatorInstanceId().equals(observation.validatorInstanceId())
        || !receipt.validatorBindingDigest().equals(observation.validatorBindingDigest())
        || !receipt.validatorConfigRevision().equals(observation.validatorConfigRevision())
        || !receipt.validatorPeerUri().equals(observation.validatorPeerUri())
        || !receipt.validatorPeerSpkiSha256().equals(observation.validatorPeerSpkiSha256())) {
      throw new IdempotencyConflictException(
          "Readiness verification retry differs from its immutable authenticated receipt");
    }
  }

  private void requireNotExpired(ProbeEntry entry, long nowEpoch) {
    if (nowEpoch >= entry.expiresAtEpochSecond()) {
      throw new ProbeExpiredException();
    }
  }

  static String applicabilityMatrixJson() {
    List<Map<String, Object>> applicableProfiles =
        List.of(
            Map.of("audience", CONTROL_UI_AUDIENCE, "tokenProfile", CONTROL_UI_PROFILE),
            Map.of(
                "audience", PLAYER_BOOTSTRAP_AUDIENCE,
                "tokenProfile", PLAYER_BOOTSTRAP_PROFILE),
            Map.of("audience", REPRESENTATIVE_AUDIENCE, "tokenProfile", REPRESENTATIVE_PROFILE));
    Map<String, Object> validator = new LinkedHashMap<>();
    validator.put("applicableProfiles", applicableProfiles);
    validator.put("validatorId", VALIDATOR_ID);
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put("inventoryStatus", INVENTORY_STATUS);
    matrix.put("schemaVersion", 1);
    matrix.put("validators", List.of(validator));
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(matrix)),
          StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new StorageUnavailableException(
          "Readiness applicability matrix cannot be canonicalized");
    }
  }

  private static String planDigest(
      CurrentEvidence current,
      String matrixJson,
      String matrixDigest,
      long plannedAt,
      long notBefore,
      long expiresAt,
      List<ProbeEntry> entries,
      Optional<String> inventorySnapshotDigest) {
    Map<String, Object> preimage = new LinkedHashMap<>();
    GenerationRequest request = current.request();
    GenerationResult result = current.result();
    AccountJwtJwksPublicationRepository.PublicationEvidence publication = current.publication();
    preimage.put("digestVersion", "account-jwt-readiness-probe-plan/v1");
    preimage.put("rotationOperationId", request.operationId().toString());
    preimage.put("environmentId", request.binding().environmentId());
    preimage.put("clusterId", request.binding().clusterId());
    preimage.put("namespace", request.binding().namespace());
    preimage.put("custodyMode", request.binding().mode().value());
    preimage.put("operationDigest", request.operationDigest());
    preimage.put("generationRequestDigest", result.generationRequestDigest());
    preimage.put("generationReceiptDigest", result.receiptDigest());
    preimage.put("desiredStateVersion", result.desiredStateVersion());
    preimage.put(
        "trustFence",
        Map.of(
            "clusterIncarnationUid", request.trustFence().expectedClusterIncarnationUid(),
            "namespaceUid", request.trustFence().expectedNamespaceUid(),
            "bindingDigest", request.trustFence().bindingDigest(),
            "configRevision", request.trustFence().configRevision()));
    preimage.put("targetGeneration", result.targetGeneration());
    preimage.put("targetKid", result.targetKid());
    preimage.put("targetPublicKeyFingerprint", result.publicKeyFingerprint());
    preimage.put("expectedActive", activeMap(request.expectedActive()));
    preimage.put("expectedPublishedActive", activeMap(request.expectedPublishedActive()));
    preimage.put("publicationIntentDigest", publication.intent().intentDigest());
    preimage.put("publicationReceiptDigest", publication.receipt().orElseThrow().receiptDigest());
    preimage.put(
        "mountedObservationDigest",
        publication.mountedCorrespondence().orElseThrow().observationDigest());
    preimage.put("applicabilityMatrixJson", matrixJson);
    preimage.put("applicabilityMatrixDigest", matrixDigest);
    preimage.put("validatorInventoryComplete", false);
    inventorySnapshotDigest.ifPresent(digest -> preimage.put("inventorySnapshotDigest", digest));
    preimage.put("maximumCacheAgeSeconds", MAX_VALIDATOR_CACHE_AGE_SECONDS);
    preimage.put("plannedAtEpochSecond", plannedAt);
    preimage.put("notBeforeEpochSecond", notBefore);
    preimage.put("expiresAtEpochSecond", expiresAt);
    preimage.put(
        "entries",
        entries.stream().map(AccountJwtReadinessProbeRepository::entryDigestMap).toList());
    return digest(preimage);
  }

  private static String planDigest(ReadinessProbePlan plan) {
    Map<String, Object> preimage = planDigestMap(plan);
    return digest(preimage);
  }

  private static Map<String, Object> planDigestMap(ReadinessProbePlan plan) {
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("digestVersion", "account-jwt-readiness-probe-plan/v1");
    preimage.put("rotationOperationId", plan.operationId().toString());
    preimage.put("environmentId", plan.binding().environmentId());
    preimage.put("clusterId", plan.binding().clusterId());
    preimage.put("namespace", plan.binding().namespace());
    preimage.put("custodyMode", plan.binding().mode().value());
    preimage.put("operationDigest", plan.operationDigest());
    preimage.put("generationRequestDigest", plan.generationRequestDigest());
    preimage.put("generationReceiptDigest", plan.generationReceiptDigest());
    preimage.put("desiredStateVersion", plan.desiredStateVersion());
    preimage.put(
        "trustFence",
        Map.of(
            "clusterIncarnationUid", plan.trustFence().expectedClusterIncarnationUid(),
            "namespaceUid", plan.trustFence().expectedNamespaceUid(),
            "bindingDigest", plan.trustFence().bindingDigest(),
            "configRevision", plan.trustFence().configRevision()));
    preimage.put("targetGeneration", plan.targetGeneration());
    preimage.put("targetKid", plan.targetKid());
    preimage.put("targetPublicKeyFingerprint", plan.targetPublicKeyFingerprint());
    preimage.put("expectedActive", activeMap(plan.expectedFence().durableActive()));
    preimage.put("expectedPublishedActive", activeMap(plan.expectedFence().publishedActive()));
    preimage.put("publicationIntentDigest", plan.publicationIntentDigest());
    preimage.put("publicationReceiptDigest", plan.publicationReceiptDigest());
    preimage.put("mountedObservationDigest", plan.mountedObservationDigest());
    preimage.put("applicabilityMatrixJson", plan.applicabilityMatrixJson());
    preimage.put("applicabilityMatrixDigest", plan.applicabilityMatrixDigest());
    preimage.put("validatorInventoryComplete", plan.validatorInventoryComplete());
    plan.inventorySnapshotDigest()
        .ifPresent(digest -> preimage.put("inventorySnapshotDigest", digest));
    preimage.put("maximumCacheAgeSeconds", plan.maximumCacheAgeSeconds());
    preimage.put("plannedAtEpochSecond", plan.plannedAtEpochSecond());
    preimage.put("notBeforeEpochSecond", plan.notBeforeEpochSecond());
    preimage.put("expiresAtEpochSecond", plan.expiresAtEpochSecond());
    preimage.put(
        "entries",
        plan.entries().stream().map(AccountJwtReadinessProbeRepository::entryDigestMap).toList());
    return preimage;
  }

  private static Map<String, Object> entryDigestMap(ProbeEntry entry) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("validatorId", entry.validatorId());
    item.put("tokenProfile", entry.tokenProfile());
    item.put("audience", entry.audience());
    item.put("probeKind", entry.probeKind().name());
    item.put("jti", entry.jti().toString());
    item.put("targetGeneration", entry.targetGeneration());
    item.put("targetKid", entry.targetKid());
    item.put("expectedActive", activeMap(entry.expectedActive()));
    item.put("registryVersion", entry.registryVersion());
    item.put("entryVersion", 1);
    item.put("plannedIssuedAtEpochSecond", entry.plannedIssuedAtEpochSecond());
    item.put("expiresAtEpochSecond", entry.expiresAtEpochSecond());
    return item;
  }

  private static Map<String, Object> activeMap(Optional<ActiveSigner> active) {
    if (active.isEmpty()) {
      return Map.of("present", false);
    }
    ActiveSigner signer = active.orElseThrow();
    return Map.of("present", true, "generation", signer.generation(), "kid", signer.kid());
  }

  private static String deliveryClaimDigest(DeliveryClaim claim) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("rotationOperationId", claim.rotationOperationId().toString());
    value.put("planDigest", claim.planDigest());
    value.put("expectedClusterUid", claim.expectedClusterUid());
    value.put("expectedNamespaceUid", claim.expectedNamespaceUid());
    value.put("materializerTrustBindingDigest", claim.materializerTrustBindingDigest());
    value.put("materializerTrustConfigRevision", claim.materializerTrustConfigRevision());
    value.put("targetGeneration", claim.targetGeneration());
    value.put("targetKid", claim.targetKid());
    value.put("expectedActive", activeMap(claim.expectedActive()));
    value.put("readinessBindingDigest", claim.readinessBindingDigest());
    value.put("readinessConfigRevision", claim.readinessConfigRevision());
    value.put(
        "readinessBindingValidUntilEpochSecond", claim.readinessBindingValidUntilEpochSecond());
    value.put("validatorId", claim.validatorId());
    value.put("validatorInstanceId", claim.validatorInstanceId());
    value.put("validatorPeerUri", claim.validatorPeerUri());
    value.put("validatorPeerSpkiSha256", claim.validatorPeerSpkiSha256());
    value.put("claimedAtEpochSecond", claim.claimedAtEpochSecond());
    value.put("expiresAtEpochSecond", claim.expiresAtEpochSecond());
    try {
      return sha256(Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)));
    } catch (IOException failure) {
      throw new StorageUnavailableException("Readiness delivery claim cannot be canonicalized");
    }
  }

  private static String readinessPromotionDigest(
      ReadinessProbePlan plan,
      GenerationResult generation,
      AccountJwtJwksPublicationRepository.PreparedPublicationEvidence publication,
      String inventoryEvidenceReference,
      String inventoryEvidenceDigest,
      List<VerifiedProbeEvidence> verifiedProbes) {
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("digestVersion", "account-jwt-readiness-promotion-proof/v1");
    preimage.put("plan", planDigestMap(plan));
    preimage.put("planDigest", plan.planDigest());
    preimage.put("generation", generationEvidenceMap(generation));
    preimage.put("publication", preparedPublicationEvidenceMap(publication));
    preimage.put("inventoryEvidenceReference", inventoryEvidenceReference);
    preimage.put("inventoryEvidenceDigest", inventoryEvidenceDigest);
    preimage.put(
        "verifiedProbes",
        verifiedProbes.stream()
            .map(AccountJwtReadinessProbeRepository::verifiedProbeEvidenceMap)
            .toList());
    return digest(preimage);
  }

  private static Map<String, Object> generationEvidenceMap(GenerationResult result) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("operationId", result.operationId().toString());
    value.put("environmentId", result.binding().environmentId());
    value.put("clusterId", result.binding().clusterId());
    value.put("namespace", result.binding().namespace());
    value.put("custodyMode", result.binding().mode().value());
    value.put("operationDigest", result.operationDigest());
    value.put("generationRequestDigest", result.generationRequestDigest());
    value.put("desiredStateVersion", result.desiredStateVersion());
    value.put("trustFence", trustFenceMap(result.trustFence()));
    value.put("privateSecretName", result.privateSecretName());
    value.put("secretUid", result.secretUid());
    value.put("expectedPriorResourceVersion", result.expectedPriorResourceVersion());
    value.put("observedResourceVersion", result.observedResourceVersion());
    value.put("targetGeneration", result.targetGeneration());
    value.put("targetKid", result.targetKid());
    value.put("targetAlgorithm", result.targetAlgorithm());
    value.put("publicKeyFingerprint", result.publicKeyFingerprint());
    value.put("publicJwkJson", result.publicJwkJson());
    value.put("receiptDigest", result.receiptDigest());
    return value;
  }

  private static Map<String, Object> trustFenceMap(TrustFence trust) {
    return Map.of(
        "clusterIncarnationUid", trust.expectedClusterIncarnationUid(),
        "namespaceUid", trust.expectedNamespaceUid(),
        "bindingDigest", trust.bindingDigest(),
        "configRevision", trust.configRevision());
  }

  private static Map<String, Object> preparedPublicationEvidenceMap(
      AccountJwtJwksPublicationRepository.PreparedPublicationEvidence evidence) {
    var intent = evidence.intent();
    var receipt = evidence.receipt();
    var mount = evidence.mountedCorrespondence();
    Map<String, Object> intentValue = new LinkedHashMap<>();
    intentValue.put("operationId", intent.operationId().toString());
    intentValue.put("environmentId", intent.binding().environmentId());
    intentValue.put("clusterId", intent.binding().clusterId());
    intentValue.put("namespace", intent.binding().namespace());
    intentValue.put("operationDigest", intent.operationDigest());
    intentValue.put("generationRequestDigest", intent.generationRequestDigest());
    intentValue.put("generationReceiptDigest", intent.generationReceiptDigest());
    intentValue.put("desiredStateVersion", intent.desiredStateVersion());
    intentValue.put("trustFence", trustFenceMap(intent.trustFence()));
    intentValue.put("apiBindingDigest", intent.apiBindingDigest());
    intentValue.put("apiConfigRevision", intent.apiConfigRevision());
    intentValue.put("configMapName", intent.configMapName());
    intentValue.put("configMapUid", intent.configMapUid());
    intentValue.put("expectedResourceVersion", intent.expectedResourceVersion());
    intentValue.put("expectedSnapshotDigest", intent.expectedSnapshotDigest());
    intentValue.put("targetGeneration", intent.targetGeneration());
    intentValue.put("targetKid", intent.targetKid());
    intentValue.put("publicKeyFingerprint", intent.publicKeyFingerprint());
    intentValue.put("expectedDurableActive", activeMap(intent.expectedDurableActive()));
    intentValue.put("expectedPublishedActive", activeMap(intent.expectedPublishedActive()));
    intentValue.put("publicDataDigest", intent.publicDataDigest());
    intentValue.put("generationMarkerDigest", intent.generationMarkerDigest());
    intentValue.put("intentDigest", intent.intentDigest());

    Map<String, Object> receiptValue = new LinkedHashMap<>();
    receiptValue.put("operationId", receipt.operationId().toString());
    receiptValue.put("intentDigest", receipt.intentDigest());
    receiptValue.put("configMapUid", receipt.configMapUid());
    receiptValue.put("expectedResourceVersion", receipt.expectedResourceVersion());
    receiptValue.put("observedResourceVersion", receipt.observedResourceVersion());
    receiptValue.put("publicDataDigest", receipt.publicDataDigest());
    receiptValue.put("receiptDigest", receipt.receiptDigest());

    Map<String, Object> mountValue = new LinkedHashMap<>();
    mountValue.put("operationId", mount.operationId().toString());
    mountValue.put("intentDigest", mount.intentDigest());
    mountValue.put("publicationReceiptDigest", mount.publicationReceiptDigest());
    mountValue.put("generationMarkerDigest", mount.generationMarkerDigest());
    mountValue.put("publicDataDigest", mount.publicDataDigest());
    mountValue.put("publicKeyFingerprint", mount.publicKeyFingerprint());
    mountValue.put("observationDigest", mount.observationDigest());
    return Map.of("intent", intentValue, "receipt", receiptValue, "mount", mountValue);
  }

  private static Map<String, Object> verifiedProbeEvidenceMap(VerifiedProbeEvidence probe) {
    VerificationReceipt receipt = probe.receipt();
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("rotationOperationId", probe.rotationOperationId().toString());
    value.put("planDigest", probe.planDigest());
    value.put("validatorId", probe.validatorId());
    value.put("tokenProfile", probe.tokenProfile());
    value.put("audience", probe.audience());
    value.put("probeKind", probe.probeKind().name());
    value.put("jti", probe.jti().toString());
    value.put("targetGeneration", probe.targetGeneration());
    value.put("targetKid", probe.targetKid());
    value.put("expectedActive", activeMap(probe.expectedActive()));
    value.put("registryVersion", probe.registryVersion());
    value.put("entryVersion", probe.entryVersion());
    value.put("plannedIssuedAtEpochSecond", probe.plannedIssuedAtEpochSecond());
    value.put("expiresAtEpochSecond", probe.expiresAtEpochSecond());
    value.put("compactTokenSha256", probe.compactTokenSha256());
    Map<String, Object> receiptValue = new LinkedHashMap<>();
    receiptValue.put("receiptVersion", receipt.receiptVersion());
    receiptValue.put("sourceEntryVersion", receipt.sourceEntryVersion());
    receiptValue.put("resultEntryVersion", receipt.resultEntryVersion());
    receiptValue.put("observedAtEpochSecond", receipt.observedAtEpochSecond());
    receiptValue.put("receiptSha256", receipt.receiptSha256());
    receiptValue.put("verifiedKid", receipt.verifiedKid());
    receiptValue.put("validatorInstanceId", receipt.validatorInstanceId());
    receiptValue.put("validatorBindingDigest", receipt.validatorBindingDigest());
    receiptValue.put("validatorConfigRevision", receipt.validatorConfigRevision());
    receiptValue.put("validatorPeerUri", receipt.validatorPeerUri());
    receiptValue.put("validatorPeerSpkiSha256", receipt.validatorPeerSpkiSha256());
    value.put("verificationReceipt", receiptValue);
    return value;
  }

  private static String digest(Map<String, Object> value) {
    try {
      return sha256(Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)));
    } catch (IOException ex) {
      throw new StorageUnavailableException("Readiness evidence cannot be canonicalized");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new StorageUnavailableException("SHA-256 is unavailable");
    }
  }

  private static long exactEpochSecond(Instant instant) {
    Objects.requireNonNull(instant, "Readiness timestamp is required");
    if (instant.getNano() != 0 || instant.getEpochSecond() <= 0L) {
      throw new IllegalArgumentException("Readiness time must be a positive exact epoch second");
    }
    return instant.getEpochSecond();
  }

  private static long add(long value, long amount) {
    try {
      return Math.addExact(value, amount);
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException("Readiness timestamp overflow", ex);
    }
  }

  private static void requireOperationId(UUID operationId) {
    if (operationId == null || operationId.version() != 4 || operationId.variant() != 2) {
      throw new IllegalArgumentException("Account JWT readiness operation ID must be UUIDv4");
    }
  }

  private static void requireAccountTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("An owning Account transaction is required");
    }
  }

  private static void requireWritableAccountTransaction() {
    requireAccountTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("A writable Account transaction is required");
    }
  }

  private static void insertOne(DSLContext dsl, String table, Map<String, Object> fields) {
    String columns = String.join(", ", fields.keySet());
    String placeholders = String.join(", ", java.util.Collections.nCopies(fields.size(), "?"));
    dsl.execute(
        "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")",
        fields.values().toArray());
  }

  private record CurrentEvidence(
      DesiredState state,
      GenerationRequest request,
      GenerationResult result,
      AccountJwtJwksPublicationRepository.PublicationEvidence publication) {}

  public enum ProbeState {
    PLANNED,
    ISSUED,
    VERIFIED,
    CLEANED,
    EXPIRED,
    ABORTED
  }

  public record SignerFence(
      Optional<ActiveSigner> durableActive, Optional<ActiveSigner> publishedActive) {
    public SignerFence {
      durableActive = Objects.requireNonNull(durableActive);
      publishedActive = Objects.requireNonNull(publishedActive);
      if (!durableActive.equals(publishedActive)) {
        throw new QuarantinedStateException("Durable and published signer fences disagree");
      }
    }
  }

  public record ProbeEntry(
      UUID rotationOperationId,
      String planDigest,
      String validatorId,
      String tokenProfile,
      String audience,
      ProbeKind probeKind,
      UUID jti,
      String targetGeneration,
      String targetKid,
      Optional<ActiveSigner> expectedActive,
      int registryVersion,
      long entryVersion,
      long plannedIssuedAtEpochSecond,
      long expiresAtEpochSecond,
      ProbeState state,
      Optional<String> terminalOutcome,
      Optional<Long> signingAttemptedAtEpochSecond,
      Optional<String> compactTokenSha256,
      Optional<VerificationReceipt> verificationReceipt) {
    public ProbeEntry {
      Objects.requireNonNull(rotationOperationId);
      requireDigest(planDigest, "probe plan digest");
      Objects.requireNonNull(probeKind);
      Objects.requireNonNull(jti);
      Objects.requireNonNull(expectedActive);
      Objects.requireNonNull(state);
      terminalOutcome = Objects.requireNonNull(terminalOutcome);
      signingAttemptedAtEpochSecond = Objects.requireNonNull(signingAttemptedAtEpochSecond);
      compactTokenSha256 = Objects.requireNonNull(compactTokenSha256);
      verificationReceipt = Objects.requireNonNull(verificationReceipt);
      requireValidatorId(validatorId);
      requireProfile(tokenProfile);
      requireAudience(audience);
      requireGeneration(targetGeneration);
      requireKid(targetKid);
      if (jti.version() != 4
          || jti.variant() != 2
          || registryVersion != 1
          || entryVersion <= 0L
          || plannedIssuedAtEpochSecond <= 0L
          || expiresAtEpochSecond <= plannedIssuedAtEpochSecond
          || expiresAtEpochSecond - plannedIssuedAtEpochSecond > PROBE_LIFETIME_SECONDS) {
        throw new QuarantinedStateException("Readiness probe entry identity is malformed");
      }
      compactTokenSha256.ifPresent(value -> requireDigest(value, "compact probe hash"));
      if (compactTokenSha256.isPresent() != signingAttemptedAtEpochSecond.isPresent()) {
        throw new QuarantinedStateException("Readiness signing-attempt evidence is partial");
      }
      signingAttemptedAtEpochSecond.ifPresent(
          attemptedAt -> {
            if (attemptedAt < plannedIssuedAtEpochSecond || attemptedAt >= expiresAtEpochSecond) {
              throw new QuarantinedStateException(
                  "Readiness signing-attempt time is outside its plan");
            }
          });
      if ((state == ProbeState.ABORTED
                  || state == ProbeState.EXPIRED
                  || state == ProbeState.CLEANED)
              != terminalOutcome.isPresent()
          || (state == ProbeState.ABORTED && terminalOutcome.filter("ABORTED"::equals).isEmpty())
          || (state == ProbeState.EXPIRED && terminalOutcome.filter("EXPIRED"::equals).isEmpty())
          || (state == ProbeState.CLEANED
              && terminalOutcome
                  .filter(value -> value.equals("ABORTED") || value.equals("EXPIRED"))
                  .isEmpty())) {
        throw new QuarantinedStateException("Readiness terminal outcome does not match its state");
      }
      if ((state == ProbeState.ISSUED || state == ProbeState.VERIFIED)
          && compactTokenSha256.isEmpty()) {
        throw new QuarantinedStateException("Issued readiness probe has no exact token hash");
      }
      if ((state == ProbeState.VERIFIED && verificationReceipt.isEmpty())
          || ((state == ProbeState.PLANNED || state == ProbeState.ISSUED)
              && verificationReceipt.isPresent())) {
        throw new QuarantinedStateException("Readiness state and verification receipt disagree");
      }
      verificationReceipt.ifPresent(
          receipt -> {
            if (!receipt.verifiedKid().equals(targetKid)
                || receipt.resultEntryVersion() > entryVersion
                || receipt.sourceEntryVersion() + 1L != receipt.resultEntryVersion()
                || (state == ProbeState.VERIFIED && receipt.resultEntryVersion() != entryVersion)) {
              throw new QuarantinedStateException(
                  "Readiness verification receipt differs from its immutable entry");
            }
          });
      if (verificationReceipt.isPresent()
          && verificationReceipt.orElseThrow().resultEntryVersion() > entryVersion) {
        throw new QuarantinedStateException("Terminal readiness receipt version is invalid");
      }
      if (state == ProbeState.CLEANED && terminalOutcome.isEmpty()) {
        throw new QuarantinedStateException("Cleaned readiness probe lost its terminal evidence");
      }
    }

    private ProbeEntry withPlanDigest(String value) {
      return new ProbeEntry(
          rotationOperationId,
          value,
          validatorId,
          tokenProfile,
          audience,
          probeKind,
          jti,
          targetGeneration,
          targetKid,
          expectedActive,
          registryVersion,
          entryVersion,
          plannedIssuedAtEpochSecond,
          expiresAtEpochSecond,
          state,
          terminalOutcome,
          signingAttemptedAtEpochSecond,
          compactTokenSha256,
          verificationReceipt);
    }
  }

  /** Exact authenticated transport and cryptographic verification evidence; contains no token. */
  public record VerifiedProbeObservation(
      String compactTokenSha256,
      String verifiedKid,
      String validatorInstanceId,
      String validatorBindingDigest,
      String validatorConfigRevision,
      String validatorPeerUri,
      String validatorPeerSpkiSha256,
      long observedAtEpochSecond) {
    public VerifiedProbeObservation {
      requireDigest(compactTokenSha256, "verified compact token hash");
      requireKid(verifiedKid);
      if (validatorInstanceId == null
          || !validatorInstanceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
          || validatorConfigRevision == null
          || !validatorConfigRevision.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
          || validatorPeerUri == null
          || !validatorPeerUri.matches(
              "spiffe://firemud/ns/[a-z0-9-]{1,63}/sa/account-jwt-readiness-harness")
          || observedAtEpochSecond <= 0L) {
        throw new QuarantinedStateException("Readiness verification observation is malformed");
      }
      requireDigest(validatorBindingDigest, "validator trust binding digest");
      requireDigest(validatorPeerSpkiSha256, "validator peer SPKI digest");
    }
  }

  /** Immutable digest and identity fields read back from the readiness entry row. */
  public record VerificationReceipt(
      int receiptVersion,
      long sourceEntryVersion,
      long resultEntryVersion,
      long observedAtEpochSecond,
      String receiptSha256,
      String verifiedKid,
      String validatorInstanceId,
      String validatorBindingDigest,
      String validatorConfigRevision,
      String validatorPeerUri,
      String validatorPeerSpkiSha256) {
    public VerificationReceipt {
      if (receiptVersion != 1
          || sourceEntryVersion <= 0L
          || resultEntryVersion != sourceEntryVersion + 1L
          || observedAtEpochSecond <= 0L) {
        throw new QuarantinedStateException("Readiness verification receipt version is malformed");
      }
      requireDigest(receiptSha256, "readiness verification receipt digest");
      requireKid(verifiedKid);
      if (validatorInstanceId == null
          || !validatorInstanceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
          || validatorConfigRevision == null
          || !validatorConfigRevision.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
          || validatorPeerUri == null
          || !validatorPeerUri.matches(
              "spiffe://firemud/ns/[a-z0-9-]{1,63}/sa/account-jwt-readiness-harness")) {
        throw new QuarantinedStateException("Readiness verification receipt identity is malformed");
      }
      requireDigest(validatorBindingDigest, "validator trust binding digest");
      requireDigest(validatorPeerSpkiSha256, "validator peer SPKI digest");
    }
  }

  public record ReadinessProbePlan(
      UUID operationId,
      Binding binding,
      TrustFence trustFence,
      String operationDigest,
      String generationRequestDigest,
      String generationReceiptDigest,
      long desiredStateVersion,
      String targetGeneration,
      String targetKid,
      String targetPublicKeyFingerprint,
      SignerFence expectedFence,
      String publicationIntentDigest,
      String publicationReceiptDigest,
      String mountedObservationDigest,
      String applicabilityMatrixJson,
      String applicabilityMatrixDigest,
      boolean validatorInventoryComplete,
      int maximumCacheAgeSeconds,
      long plannedAtEpochSecond,
      long notBeforeEpochSecond,
      long expiresAtEpochSecond,
      int planVersion,
      String planDigest,
      List<ProbeEntry> entries,
      Optional<String> inventorySnapshotDigest) {
    public ReadinessProbePlan(
        UUID operationId,
        Binding binding,
        TrustFence trustFence,
        String operationDigest,
        String generationRequestDigest,
        String generationReceiptDigest,
        long desiredStateVersion,
        String targetGeneration,
        String targetKid,
        String targetPublicKeyFingerprint,
        SignerFence expectedFence,
        String publicationIntentDigest,
        String publicationReceiptDigest,
        String mountedObservationDigest,
        String applicabilityMatrixJson,
        String applicabilityMatrixDigest,
        boolean validatorInventoryComplete,
        int maximumCacheAgeSeconds,
        long plannedAtEpochSecond,
        long notBeforeEpochSecond,
        long expiresAtEpochSecond,
        int planVersion,
        String planDigest,
        List<ProbeEntry> entries) {
      this(
          operationId,
          binding,
          trustFence,
          operationDigest,
          generationRequestDigest,
          generationReceiptDigest,
          desiredStateVersion,
          targetGeneration,
          targetKid,
          targetPublicKeyFingerprint,
          expectedFence,
          publicationIntentDigest,
          publicationReceiptDigest,
          mountedObservationDigest,
          applicabilityMatrixJson,
          applicabilityMatrixDigest,
          validatorInventoryComplete,
          maximumCacheAgeSeconds,
          plannedAtEpochSecond,
          notBeforeEpochSecond,
          expiresAtEpochSecond,
          planVersion,
          planDigest,
          entries,
          Optional.empty());
    }

    public ReadinessProbePlan {
      requireOperationId(operationId);
      Objects.requireNonNull(binding);
      Objects.requireNonNull(trustFence);
      Objects.requireNonNull(expectedFence);
      requireDigest(operationDigest, "operation digest");
      requireDigest(generationRequestDigest, "generation request digest");
      requireDigest(generationReceiptDigest, "generation receipt digest");
      requireGeneration(targetGeneration);
      requireKid(targetKid);
      requireDigest(targetPublicKeyFingerprint, "target public key fingerprint");
      requireDigest(publicationIntentDigest, "publication intent digest");
      requireDigest(publicationReceiptDigest, "publication receipt digest");
      requireDigest(mountedObservationDigest, "mounted observation digest");
      Objects.requireNonNull(applicabilityMatrixJson);
      requireDigest(applicabilityMatrixDigest, "applicability matrix digest");
      requireDigest(planDigest, "plan digest");
      Objects.requireNonNull(inventorySnapshotDigest);
      inventorySnapshotDigest.ifPresent(
          value -> requireDigest(value, "validator inventory snapshot digest"));
      entries = List.copyOf(entries);
      if (desiredStateVersion <= 1L
          || maximumCacheAgeSeconds != MAX_VALIDATOR_CACHE_AGE_SECONDS
          || plannedAtEpochSecond <= 0L
          || notBeforeEpochSecond < plannedAtEpochSecond + maximumCacheAgeSeconds
          || expiresAtEpochSecond <= notBeforeEpochSecond
          || expiresAtEpochSecond - notBeforeEpochSecond > PROBE_LIFETIME_SECONDS
          || planVersion != 1
          || entries.isEmpty()
          || entries.size() > MAX_ENTRIES_PER_OPERATION) {
        throw new QuarantinedStateException("Readiness probe plan is malformed");
      }
    }

    private static ReadinessProbePlan fromCurrent(
        CurrentEvidence current,
        String matrixJson,
        String matrixDigest,
        boolean inventoryComplete,
        int maxCacheAge,
        long plannedAt,
        long notBefore,
        long expiresAt,
        String planDigest,
        List<ProbeEntry> entries,
        Optional<String> inventorySnapshotDigest) {
      GenerationRequest request = current.request();
      GenerationResult result = current.result();
      var publication = current.publication();
      return new ReadinessProbePlan(
          request.operationId(),
          request.binding(),
          request.trustFence(),
          request.operationDigest(),
          result.generationRequestDigest(),
          result.receiptDigest(),
          result.desiredStateVersion(),
          result.targetGeneration(),
          result.targetKid(),
          result.publicKeyFingerprint(),
          new SignerFence(request.expectedActive(), request.expectedPublishedActive()),
          publication.intent().intentDigest(),
          publication.receipt().orElseThrow().receiptDigest(),
          publication.mountedCorrespondence().orElseThrow().observationDigest(),
          matrixJson,
          matrixDigest,
          inventoryComplete,
          maxCacheAge,
          plannedAt,
          notBefore,
          expiresAt,
          1,
          planDigest,
          entries,
          inventorySnapshotDigest);
    }
  }

  /**
   * Owner-created, non-secret readiness evidence accepted by the Account promotion owner. Its
   * constructor is private: callers cannot turn an applicability matrix or receipt DTO into proof.
   */
  public static final class ReadinessPromotionProof {
    private final ReadinessProbePlan plan;
    private final GenerationResult generationResult;
    private final AccountJwtJwksPublicationRepository.PreparedPublicationEvidence publication;
    private final String inventoryEvidenceReference;
    private final String inventoryEvidenceDigest;
    private final List<VerifiedProbeEvidence> verifiedProbes;
    private final String readinessEvidenceDigest;

    private ReadinessPromotionProof(
        ReadinessProbePlan plan,
        GenerationResult generationResult,
        AccountJwtJwksPublicationRepository.PreparedPublicationEvidence publication,
        String inventoryEvidenceReference,
        String inventoryEvidenceDigest) {
      this.plan = Objects.requireNonNull(plan);
      this.generationResult = Objects.requireNonNull(generationResult);
      this.publication = Objects.requireNonNull(publication);
      if (!plan.validatorInventoryComplete()) {
        throw new QuarantinedStateException(
            "Partial readiness inventory cannot create promotion proof");
      }
      if (inventoryEvidenceReference == null
          || !inventoryEvidenceReference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,511}")) {
        throw new QuarantinedStateException("Protected readiness inventory reference is malformed");
      }
      requireDigest(inventoryEvidenceDigest, "protected readiness inventory digest");
      this.inventoryEvidenceReference = inventoryEvidenceReference;
      this.inventoryEvidenceDigest = inventoryEvidenceDigest;
      List<VerifiedProbeEvidence> verifiedProbes =
          plan.entries().stream().map(VerifiedProbeEvidence::from).toList();
      if (verifiedProbes.isEmpty()
          || verifiedProbes.size() > MAX_ENTRIES_PER_OPERATION
          || !plan.operationId().equals(generationResult.operationId())
          || !plan.operationDigest().equals(generationResult.operationDigest())
          || !plan.generationRequestDigest().equals(generationResult.generationRequestDigest())
          || !plan.generationReceiptDigest().equals(generationResult.receiptDigest())
          || plan.desiredStateVersion() != generationResult.desiredStateVersion()
          || !plan.targetGeneration().equals(generationResult.targetGeneration())
          || !plan.targetKid().equals(generationResult.targetKid())
          || !plan.targetPublicKeyFingerprint().equals(generationResult.publicKeyFingerprint())
          || !plan.planDigest().equals(planDigest(plan))) {
        throw new QuarantinedStateException(
            "Readiness proof differs from exact generation and verified probe evidence");
      }
      this.verifiedProbes = List.copyOf(verifiedProbes);
      this.readinessEvidenceDigest =
          readinessPromotionDigest(
              plan,
              generationResult,
              publication,
              inventoryEvidenceReference,
              inventoryEvidenceDigest,
              this.verifiedProbes);
    }

    public ReadinessProbePlan plan() {
      return plan;
    }

    public GenerationResult generationResult() {
      return generationResult;
    }

    public AccountJwtJwksPublicationRepository.PreparedPublicationEvidence publication() {
      return publication;
    }

    public String inventoryEvidenceReference() {
      return inventoryEvidenceReference;
    }

    public String inventoryEvidenceDigest() {
      return inventoryEvidenceDigest;
    }

    public List<VerifiedProbeEvidence> verifiedProbes() {
      return verifiedProbes;
    }

    /** Digest computed by Account over all canonical proof fields except this digest itself. */
    public String readinessEvidenceDigest() {
      return readinessEvidenceDigest;
    }
  }

  /** Ordered immutable row and authenticated receipt identity for one VERIFIED registry entry. */
  public record VerifiedProbeEvidence(
      UUID rotationOperationId,
      String planDigest,
      String validatorId,
      String tokenProfile,
      String audience,
      ProbeKind probeKind,
      UUID jti,
      String targetGeneration,
      String targetKid,
      Optional<ActiveSigner> expectedActive,
      int registryVersion,
      long entryVersion,
      long plannedIssuedAtEpochSecond,
      long expiresAtEpochSecond,
      String compactTokenSha256,
      VerificationReceipt receipt) {
    public VerifiedProbeEvidence {
      Objects.requireNonNull(rotationOperationId);
      requireDigest(planDigest, "verified probe plan digest");
      requireValidatorId(validatorId);
      requireProfile(tokenProfile);
      requireAudience(audience);
      Objects.requireNonNull(probeKind);
      Objects.requireNonNull(jti);
      requireGeneration(targetGeneration);
      requireKid(targetKid);
      expectedActive = Objects.requireNonNull(expectedActive);
      requireDigest(compactTokenSha256, "verified probe token digest");
      Objects.requireNonNull(receipt);
      if (registryVersion != 1
          || entryVersion <= 0L
          || plannedIssuedAtEpochSecond <= 0L
          || expiresAtEpochSecond <= plannedIssuedAtEpochSecond
          || !targetKid.equals(receipt.verifiedKid())
          || receipt.resultEntryVersion() != entryVersion) {
        throw new QuarantinedStateException(
            "Verified probe evidence does not match its immutable receipt");
      }
    }

    private static VerifiedProbeEvidence from(ProbeEntry entry) {
      if (entry.state() != ProbeState.VERIFIED) {
        throw new QuarantinedStateException(
            "Every promotion readiness entry must be owner-recorded VERIFIED");
      }
      return new VerifiedProbeEvidence(
          entry.rotationOperationId(),
          entry.planDigest(),
          entry.validatorId(),
          entry.tokenProfile(),
          entry.audience(),
          entry.probeKind(),
          entry.jti(),
          entry.targetGeneration(),
          entry.targetKid(),
          entry.expectedActive(),
          entry.registryVersion(),
          entry.entryVersion(),
          entry.plannedIssuedAtEpochSecond(),
          entry.expiresAtEpochSecond(),
          entry.compactTokenSha256().orElseThrow(),
          entry.verificationReceipt().orElseThrow());
    }
  }

  /** Immutable winner of the only permitted transient delivery for a readiness operation. */
  public record DeliveryClaim(
      UUID rotationOperationId,
      String planDigest,
      String expectedClusterUid,
      String expectedNamespaceUid,
      String materializerTrustBindingDigest,
      String materializerTrustConfigRevision,
      String targetGeneration,
      String targetKid,
      Optional<ActiveSigner> expectedActive,
      String readinessBindingDigest,
      String readinessConfigRevision,
      long readinessBindingValidUntilEpochSecond,
      String validatorId,
      String validatorInstanceId,
      String validatorPeerUri,
      String validatorPeerSpkiSha256,
      long claimedAtEpochSecond,
      long expiresAtEpochSecond,
      String claimDigest) {
    public DeliveryClaim {
      requireOperationId(rotationOperationId);
      requireDigest(planDigest, "delivery plan digest");
      requireUuid(expectedClusterUid, "delivery cluster UID");
      requireUuid(expectedNamespaceUid, "delivery namespace UID");
      requireDigest(materializerTrustBindingDigest, "materializer trust digest");
      requireRevision(materializerTrustConfigRevision, "materializer trust revision");
      requireGeneration(targetGeneration);
      requireKid(targetKid);
      expectedActive = Objects.requireNonNull(expectedActive);
      expectedActive.ifPresent(
          active -> {
            requireGeneration(active.generation());
            requireKid(active.kid());
            if (Long.parseLong(targetGeneration) <= Long.parseLong(active.generation())
                || targetKid.equals(active.kid())) {
              throw new QuarantinedStateException("Delivery active fence is not older than target");
            }
          });
      requireDigest(readinessBindingDigest, "readiness trust digest");
      requireRevision(readinessConfigRevision, "readiness trust revision");
      if (readinessBindingValidUntilEpochSecond <= claimedAtEpochSecond) {
        throw new QuarantinedStateException("Readiness binding expires before delivery claim");
      }
      requireValidatorId(validatorId);
      if (!VALIDATOR_ID.equals(validatorId)
          || validatorInstanceId == null
          || !validatorInstanceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
          || validatorPeerUri == null
          || !validatorPeerUri.matches(
              "spiffe://firemud/ns/[a-z0-9-]{1,63}/sa/account-jwt-readiness-harness")) {
        throw new QuarantinedStateException("Readiness delivery identity is malformed");
      }
      requireDigest(validatorPeerSpkiSha256, "readiness peer SPKI digest");
      if (claimedAtEpochSecond <= 0L || expiresAtEpochSecond <= claimedAtEpochSecond) {
        throw new QuarantinedStateException("Readiness delivery claim window is malformed");
      }
      requireDigest(claimDigest, "readiness delivery claim digest");
    }

    private static DeliveryClaim create(
        ReadinessProbePlan plan,
        TrustFence trust,
        net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.Binding
            readinessBinding,
        PeerIdentity peer,
        long claimedAt) {
      DeliveryClaim unsigned =
          new DeliveryClaim(
              plan.operationId(),
              plan.planDigest(),
              trust.expectedClusterIncarnationUid(),
              trust.expectedNamespaceUid(),
              trust.bindingDigest(),
              trust.configRevision(),
              plan.targetGeneration(),
              plan.targetKid(),
              plan.expectedFence().durableActive(),
              readinessBinding.bindingDigest(),
              readinessBinding.configRevision(),
              readinessBinding.validUntilEpochSecond(),
              readinessBinding.validatorId(),
              readinessBinding.validatorInstanceId(),
              peer.uri(),
              peer.spkiSha256(),
              claimedAt,
              plan.expiresAtEpochSecond(),
              "0".repeat(64));
      return new DeliveryClaim(
          unsigned.rotationOperationId(),
          unsigned.planDigest(),
          unsigned.expectedClusterUid(),
          unsigned.expectedNamespaceUid(),
          unsigned.materializerTrustBindingDigest(),
          unsigned.materializerTrustConfigRevision(),
          unsigned.targetGeneration(),
          unsigned.targetKid(),
          unsigned.expectedActive(),
          unsigned.readinessBindingDigest(),
          unsigned.readinessConfigRevision(),
          unsigned.readinessBindingValidUntilEpochSecond(),
          unsigned.validatorId(),
          unsigned.validatorInstanceId(),
          unsigned.validatorPeerUri(),
          unsigned.validatorPeerSpkiSha256(),
          unsigned.claimedAtEpochSecond(),
          unsigned.expiresAtEpochSecond(),
          deliveryClaimDigest(unsigned));
    }
  }

  private static String requireDigest(String value, String label) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new QuarantinedStateException(label + " is malformed");
    }
    return value;
  }

  private static String requireUuid(String value, String label) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)
          || parsed.version() != 4
          || parsed.variant() != 2
          || parsed.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException();
      }
      return value;
    } catch (RuntimeException invalid) {
      throw new QuarantinedStateException(label + " is malformed");
    }
  }

  private static String requireRevision(String value, String label) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new QuarantinedStateException(label + " is malformed");
    }
    return value;
  }

  private static String requireValidatorId(String value) {
    if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
      throw new QuarantinedStateException("Readiness validator ID is malformed");
    }
    return value;
  }

  private static String requireProfile(String value) {
    if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,127}")) {
      throw new QuarantinedStateException("Readiness token profile is malformed");
    }
    return value;
  }

  private static String requireAudience(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new QuarantinedStateException("Readiness audience is malformed");
    }
    return value;
  }

  private static String requireGeneration(String value) {
    if (value == null || !value.matches("[1-9][0-9]{0,63}")) {
      throw new QuarantinedStateException("Readiness signer generation is malformed");
    }
    return value;
  }

  private static String requireKid(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) {
      throw new QuarantinedStateException("Readiness signer kid is malformed");
    }
    return value;
  }

  public static final class MissingPlanException extends IllegalStateException {
    public MissingPlanException(String message) {
      super(message);
    }
  }

  public static final class DeliveryAlreadyClaimedException extends IllegalStateException {
    public DeliveryAlreadyClaimedException() {
      super("Readiness probe delivery was already claimed");
    }
  }

  public static final class DeliveryClaimRejectedException extends SecurityException {
    public DeliveryClaimRejectedException() {
      super("Authenticated readiness delivery caller is not bound to the current plan");
    }
  }

  public static final class MissingPublicationEvidenceException extends IllegalStateException {
    public MissingPublicationEvidenceException() {
      super("Current JWKS publication evidence is incomplete");
    }
  }

  public static final class InventoryPlanConflictException extends IllegalStateException {
    public InventoryPlanConflictException() {
      super("Account validator inventory snapshot does not match the immutable readiness plan");
    }
  }

  public static final class StaleOperationException extends IllegalStateException {
    public StaleOperationException(String message) {
      super(message);
    }
  }

  public static final class CacheAgeNotElapsedException extends IllegalStateException {
    public CacheAgeNotElapsedException() {
      super("Validator maximum cache age has not elapsed");
    }
  }

  public static final class ProbeExpiredException extends IllegalStateException {
    public ProbeExpiredException() {
      super("Readiness probe is expired");
    }
  }

  public static final class AmbiguousDeliveryException extends IllegalStateException {
    public AmbiguousDeliveryException(String message) {
      super(message);
    }
  }

  public static final class VersionConflictException extends IllegalStateException {
    public VersionConflictException(String message) {
      super(message);
    }
  }

  public static final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException(String message) {
      super(message);
    }
  }

  public static final class QuarantinedStateException extends IllegalStateException {
    public QuarantinedStateException(String message) {
      super(message);
    }
  }

  public static final class StorageUnavailableException extends IllegalStateException {
    public StorageUnavailableException(String message) {
      super(message);
    }
  }
}
