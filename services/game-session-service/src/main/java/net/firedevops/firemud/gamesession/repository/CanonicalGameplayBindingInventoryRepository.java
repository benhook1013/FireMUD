package net.firedevops.firemud.gamesession.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountCoverageEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountIndexMember;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountMemberAcknowledgement;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountMemberRepairOperation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortSource;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingAccountIndexObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingAccountIndexProjectionReceipt;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventorySnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIssuerIndexObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRegionBridgeObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationDisposition;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.client.CanonicalPreseededActorRuntimeReferenceClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Game Session-owned durable preparation ledger.
 *
 * <p>This class deliberately has no Spring stereotype and a package-private API. It owns the
 * binding-local account-member acknowledgement, but it is not a Redis or controller-CAS surface. A
 * completed member acknowledgement leaves the candidate provisional and non-admitting.
 */
public final class CanonicalGameplayBindingInventoryRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String INVENTORY = "game_session_canonical_gameplay_binding_inventory";
  private static final String TRANSITION = "game_session_canonical_binding_transition";
  private static final String RESERVATION = "game_session_canonical_issuer_partition_reservation";
  private static final String ACCOUNT_OBLIGATION =
      "game_session_canonical_binding_account_index_obligation";
  private static final String ISSUER_OBLIGATION =
      "game_session_canonical_binding_issuer_index_obligation";
  private static final String ISSUER_LOCAL_RELEASE =
      "game_session_canonical_issuer_binding_local_release_operation";
  private static final String REGION_OBLIGATION =
      "game_session_canonical_binding_region_bridge_obligation";
  private static final String GENERATION = "game_session_canonical_binding_generation";
  private static final String LEGACY_DISPOSITION =
      "game_session_canonical_binding_legacy_disposition";
  private static final String ISSUER_BOOTSTRAP_CONTROL =
      "game_session_canonical_issuer_layout_bootstrap_control";
  private static final String ISSUER_BOOTSTRAP_INTENT =
      "game_session_canonical_issuer_layout_bootstrap_intent";
  private final DSLContext dsl;

  CanonicalGameplayBindingInventoryRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  CanonicalGameplayBindingTransitionSnapshot prepare(
      CanonicalGameplayBindingTransitionRequest request) {
    Objects.requireNonNull(request, "request");
    validateRequest(request);
    return dsl.transactionResult(
        configuration -> prepareInTransaction(DSL.using(configuration), request));
  }

  /**
   * Prepares and registers one ordinary NEW candidate while the exact installed initial issuer
   * anchor remains current. The inventory clock and issuer control are locked before the request
   * factory resolves or allocates owner identities; Account remains the next lock.
   */
  CanonicalGameplayBindingTransitionSnapshot prepareAfterInstalledIssuerAnchor(
      InstalledAnchorPreparationRequestFactory requestFactory,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentIssuerState,
      InstalledAnchorPreparationRecorder registrationRecorder) {
    Objects.requireNonNull(requestFactory, "requestFactory");
    Objects.requireNonNull(currentIssuerState, "currentIssuerState");
    Objects.requireNonNull(registrationRecorder, "registrationRecorder");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger revision = lockInventoryRevision(transaction);
          CanonicalGameplayIssuerInstalledAnchor actualAnchor =
              CanonicalGameplayIssuerBootstrapCoverageRepository
                  .readInstalledAnchorForBindingPreparationInTransaction(
                      transaction, currentIssuerState);
          CanonicalGameplayIssuerAdmissionControlRepository.requireOpenInTransaction(
              transaction, actualAnchor, currentIssuerState);
          InstalledAnchorPreparationContext context =
              new InstalledAnchorPreparationContext(revision, actualAnchor, currentIssuerState);
          CanonicalGameplayBindingTransitionRequest request =
              Objects.requireNonNull(
                  requestFactory.create(transaction, actualAnchor, currentIssuerState),
                  "installed-anchor request factory returned null");
          validateRequest(request);
          if (request.expectedPriorBindingRef() != null
              || request.expectedPriorBindingGeneration() != null) {
            throw conflict("Installed-anchor local issuer operation accepts only ordinary NEW");
          }
          requireCandidateMatchesInstalledAnchor(request.candidate(), actualAnchor);
          return prepareInTransaction(transaction, request, context, registrationRecorder);
        });
  }

  @FunctionalInterface
  interface InstalledAnchorPreparationRequestFactory {
    /**
     * Resolves an exact retry or allocates new owner identities inside the serialized SQL
     * transaction.
     */
    CanonicalGameplayBindingTransitionRequest create(
        DSLContext transaction,
        CanonicalGameplayIssuerInstalledAnchor actualAnchor,
        CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentIssuerState);
  }

  @FunctionalInterface
  interface InstalledAnchorPreparationRecorder {
    /** Called in the same transaction after candidate preparation or exact retry qualification. */
    void record(
        DSLContext transaction,
        CanonicalGameplayBindingTransitionRequest request,
        CanonicalGameplayIssuerInstalledAnchor actualAnchor,
        CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentIssuerState,
        CanonicalGameplayBindingTransitionSnapshot prepared,
        boolean exactRetry);
  }

  private record InstalledAnchorPreparationContext(
      BigInteger inventoryRevision,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentIssuerState) {
    private InstalledAnchorPreparationContext {
      Objects.requireNonNull(inventoryRevision, "inventoryRevision");
      Objects.requireNonNull(anchor, "anchor");
      Objects.requireNonNull(currentIssuerState, "currentIssuerState");
    }
  }

  /**
   * Reads the complete typed inventory and all retained repair obligations from one DB snapshot.
   */
  CanonicalGameplayBindingInventorySnapshot readSnapshot() {
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          BigInteger revision =
              readBigInteger(
                  Objects.requireNonNull(
                      transaction.fetchOne(
                          "SELECT inventory_revision FROM "
                              + "game_session_canonical_binding_inventory_clock WHERE singleton_id = 1"),
                      "inventory revision clock row must exist"),
                  "inventory_revision");
          List<CanonicalGameplayBindingInventoryEntry> bindings =
              transaction
                  .fetch(
                      "SELECT * FROM "
                          + INVENTORY
                          + " WHERE inventory_revision <= ? ORDER BY binding_ref",
                      decimal(revision))
                  .map(CanonicalGameplayBindingInventoryRepository::toInventoryEntry);
          List<CanonicalGameplayBindingTransitionSnapshot> transitions =
              transaction
                  .fetch(
                      "SELECT t.transition_id, t.expected_prior_binding_ref,"
                          + " t.expected_prior_binding_generation, t.candidate_binding_generation,"
                          + " t.status, t.inventory_revision, t.issuer_reservation_id,"
                          + " t.candidate_account_index_fence, t.account_coverage_state,"
                          + " t.account_coverage_operation_id, t.account_coverage_lifecycle,"
                          + " t.account_coverage_operation_fence, t.account_admission_fence_kind,"
                          + " t.account_admission_fence, t.historical_account_admission_fence_kind,"
                          + " t.historical_account_admission_fence, t.resolved_scope_kind,"
                          + " t.resolved_scope_account_id, t.account_coverage_fence,"
                          + " t.account_coverage_generation, t.account_inventory_snapshot_revision,"
                          + " i.account_id, i.tenant_id, i.playable_state_namespace_id, i.character_id,"
                          + " i.playable_state_scope, i.game_instance_id,"
                          + " i.runtime_game_instance_id, i.session_id, i.region_id,"
                          + " i.region_epoch, i.issuer_id, i.issuer_auth_generation,"
                          + " i.issuer_index_layout_version, i.issuer_index_partition_count,"
                          + " i.issuer_index_partition_capacity, i.binding_ref, r.reservation_fence"
                          + " FROM "
                          + TRANSITION
                          + " t JOIN "
                          + INVENTORY
                          + " i ON i.binding_ref = t.candidate_binding_ref JOIN "
                          + RESERVATION
                          + " r ON r.reservation_id = t.issuer_reservation_id"
                          + " WHERE t.inventory_revision <= ? ORDER BY t.transition_id",
                      decimal(revision))
                  .map(CanonicalGameplayBindingInventoryRepository::toTransitionSnapshot);
          List<CanonicalIssuerPartitionReservation> reservations =
              transaction
                  .fetch(
                      "SELECT * FROM "
                          + RESERVATION
                          + " WHERE inventory_revision <= ? ORDER BY reservation_id",
                      decimal(revision))
                  .map(CanonicalGameplayBindingInventoryRepository::toReservation);
          List<CanonicalGameplayBindingAccountIndexObligation> accountObligations =
              transaction
                  .fetch(
                      "SELECT o.*, i.playable_state_namespace_id, i.character_id,"
                          + " i.playable_state_scope, i.game_instance_id,"
                          + " i.runtime_game_instance_id, i.session_id, i.region_id,"
                          + " i.region_epoch, i.issuer_id, i.issuer_auth_generation,"
                          + " i.issuer_index_layout_version, i.issuer_index_partition_count,"
                          + " i.issuer_index_partition_capacity FROM "
                          + ACCOUNT_OBLIGATION
                          + " o JOIN "
                          + INVENTORY
                          + " i ON i.binding_ref = o.binding_ref"
                          + " WHERE o.inventory_revision <= ? ORDER BY o.transition_id, o.obligation_ordinal",
                      decimal(revision))
                  .map(CanonicalGameplayBindingInventoryRepository::toAccountObligation);
          List<CanonicalGameplayBindingIssuerIndexObligation> issuerObligations =
              transaction
                  .fetch(
                      "SELECT o.*, i.account_id, i.tenant_id, i.playable_state_namespace_id,"
                          + " i.character_id, i.playable_state_scope, i.game_instance_id,"
                          + " i.runtime_game_instance_id, i.session_id,"
                          + " i.region_id, i.region_epoch, i.issuer_id, i.issuer_auth_generation,"
                          + " i.issuer_index_layout_version, i.issuer_index_partition_count,"
                          + " i.issuer_index_partition_capacity FROM "
                          + ISSUER_OBLIGATION
                          + " o JOIN "
                          + INVENTORY
                          + " i ON i.binding_ref = o.binding_ref"
                          + " WHERE o.inventory_revision <= ? ORDER BY o.transition_id, o.binding_ref",
                      decimal(revision))
                  .map(CanonicalGameplayBindingInventoryRepository::toIssuerObligation);
          List<CanonicalGameplayBindingRegionBridgeObligation> regionObligations =
              transaction
                  .fetch(
                      "SELECT * FROM "
                          + REGION_OBLIGATION
                          + " WHERE inventory_revision <= ? ORDER BY transition_id",
                      decimal(revision))
                  .map(CanonicalGameplayBindingInventoryRepository::toRegionObligation);
          return new CanonicalGameplayBindingInventorySnapshot(
              revision,
              bindings,
              transitions,
              reservations,
              accountObligations,
              issuerObligations,
              regionObligations);
        });
  }

  /**
   * Loads the exact prepared transfer and its still-active source from one durable read snapshot.
   * No Redis absence or caller-asserted acknowledgement is accepted as source evidence.
   */
  CanonicalGameplayBindingProvisionalCasEvidence readProvisionalCasEvidence(UUID transitionId) {
    Objects.requireNonNull(transitionId, "transitionId");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          return loadProvisionalCasEvidence(transaction, transitionId, false);
        });
  }

  /**
   * Claims the one permitted original-NEW candidate installation. The immutable candidate and
   * DISPATCH_POSSIBLE marker commit together before the caller can reach Redis. A returned create
   * permission exists only for that first committed claim; later calls are read-only.
   */
  OriginalNewCandidateInstallationClaim claimOriginalNewCandidateInstallation(
      CanonicalGameplayAdmissionDecision originalDecision) {
    Objects.requireNonNull(originalDecision, "originalDecision");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger currentRevision = lockInventoryRevision(transaction);
          CanonicalGameplayAccountCoverageEvidence accountCoverage =
              lockAccountCoverageEvidence(transaction, originalDecision.candidate().accountId());
          lockLeaseBoundRuntimeBeforeTransition(
              transaction,
              originalDecision.bindingDecisionId(),
              originalDecision.candidate().bindingRef().bytes(),
              originalDecision.targetEvidence());
          CanonicalGameplayBindingProvisionalCasEvidence evidence =
              loadProvisionalCasEvidence(transaction, originalDecision.bindingDecisionId(), true);
          if (!accountCoverage.equals(evidence.transition().accountCoverageEvidence())) {
            throw conflict("Original NEW account control changed before candidate dispatch claim");
          }
          requireDecisionMatchesOperation(originalDecision, evidence);
          CanonicalGameplayOriginalNewCandidate candidate;
          try {
            candidate =
                CanonicalGameplayOriginalNewCandidate.fromOriginalSources(
                    evidence, originalDecision);
          } catch (RuntimeException invalidSource) {
            throw conflict("Original NEW candidate source is not exact and public-production");
          }

          Record transition = findTransition(transaction, evidence.transition().transitionId());
          byte[] storedBytes = transition.get("original_new_candidate_bytes", byte[].class);
          String storedDigest = transition.get("original_new_candidate_digest", String.class);
          String storedState = transition.get("original_new_candidate_state", String.class);
          String storedReadbackDigest =
              transition.get("original_new_candidate_readback_digest", String.class);
          if (storedBytes == null) {
            if (storedDigest != null
                || storedState != null
                || storedReadbackDigest != null
                || evidence.transition().status()
                    != CanonicalGameplayBindingTransitionSnapshot.Status.PREPARED
                || transition.get("admission_lease_id", UUID.class) != null) {
              throw conflict("Only an untouched PREPARED original NEW row can claim creation");
            }
            byte[] bytes = candidate.canonicalBytes();
            String digest = CanonicalGameplayIssuerMemberV2Codec.sha256(bytes);
            BigInteger priorRevision = evidence.transition().inventoryRevision();
            BigInteger nextRevision = currentRevision.add(BigInteger.ONE);
            updateOriginalNewCandidateRevisionRows(
                transaction, evidence, priorRevision, nextRevision, bytes, digest);
            bumpInventoryClock(transaction, currentRevision, nextRevision);
            CanonicalGameplayBindingProvisionalCasEvidence current =
                loadProvisionalCasEvidence(transaction, evidence.transition().transitionId(), true);
            return new OriginalNewCandidateInstallationClaim(
                current,
                originalDecision,
                candidate,
                OriginalNewCandidateInstallationState.DISPATCH_POSSIBLE,
                new OriginalNewCandidateCreatePermission());
          }

          if (storedDigest == null
              || !storedDigest.equals(CanonicalGameplayIssuerMemberV2Codec.sha256(storedBytes))
              || !Arrays.equals(storedBytes, candidate.canonicalBytes())
              || (!"DISPATCH_POSSIBLE".equals(storedState)
                  && !"READBACK_CONFIRMED".equals(storedState))) {
            throw conflict("Original NEW candidate retry differs from its immutable SQL source");
          }
          CanonicalGameplayOriginalNewCandidate parsed =
              CanonicalGameplayOriginalNewCandidate.parseCanonical(storedBytes);
          if (!parsed.equals(candidate)
              || !parsed.matchesOriginalDecision(originalDecision)
              || !parsed.matchesProvisionalSources(evidence)) {
            throw conflict("Stored original NEW candidate no longer matches its durable sources");
          }
          if (evidence.transition().status()
              == CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL) {
            CanonicalGameplayAdmissionDecision stored = toAdmissionDecision(transition, evidence);
            requireSameAdmissionDecision(originalDecision, stored);
            if (!"READBACK_CONFIRMED".equals(storedState) || storedReadbackDigest == null) {
              throw conflict("PROVISIONAL original NEW source lacks retained physical readback");
            }
          }
          return new OriginalNewCandidateInstallationClaim(
              evidence,
              originalDecision,
              parsed,
              OriginalNewCandidateInstallationState.valueOf(storedState),
              null);
        });
  }

  /**
   * Records only actual owner-produced physical readback, then reuses the existing provisional CAS.
   */
  CanonicalGameplayBindingTransitionSnapshot markOriginalNewCandidateProvisional(
      OriginalNewCandidateInstallationClaim claim,
      CanonicalGameplayOriginalNewCandidateRedisOwner.PhysicalReadback readback) {
    Objects.requireNonNull(claim, "candidate installation claim");
    Objects.requireNonNull(readback, "owner-produced physical readback");
    return markProvisional(claim.evidence(), claim.originalDecision(), claim.candidate(), readback);
  }

  /**
   * Mints a durable source from the retained original candidate readback, including after final SQL
   * commit. The candidate is historical evidence only; this method never asserts that Redis still
   * contains the candidate preimage.
   */
  OriginalNewFinalAdmissionSource readOriginalNewFinalAdmissionSource(
      CanonicalGameplayAdmissionDecision originalDecision) {
    Objects.requireNonNull(originalDecision, "original Account decision");
    requireNoAmbientTransaction();
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger revision = lockInventoryRevision(transaction);
          requireNoActiveAccountControl(
              lockExistingAccountCoverageControl(
                  transaction, originalDecision.candidate().accountId()));
          lockLeaseBoundRuntimeBeforeTransition(
              transaction,
              originalDecision.bindingDecisionId(),
              originalDecision.candidate().bindingRef().bytes(),
              originalDecision.targetEvidence());

          Record row =
              transaction.fetchOne(
                  "SELECT * FROM " + TRANSITION + " WHERE transition_id = ? FOR UPDATE",
                  originalDecision.bindingDecisionId());
          if (row == null) throw conflict("Original NEW transition is missing");
          String status = row.get("status", String.class);
          boolean committed = "COMMITTED".equals(status);
          if (!committed && !"PROVISIONAL".equals(status)) {
            throw conflict("Only a provisional or committed original NEW can be a final source");
          }
          Record inventoryRow =
              transaction.fetchOne(
                  "SELECT * FROM " + INVENTORY + " WHERE binding_ref = ? FOR UPDATE",
                  originalDecision.candidate().bindingRef().bytes());
          UUID reservationId = row.get("issuer_reservation_id", UUID.class);
          Record reservationRow =
              reservationId == null
                  ? null
                  : transaction.fetchOne(
                      "SELECT * FROM " + RESERVATION + " WHERE reservation_id = ? FOR UPDATE",
                      reservationId);
          if (inventoryRow == null || reservationRow == null) {
            throw conflict("Original NEW candidate or reservation is missing");
          }
          var entry = toInventoryEntry(inventoryRow);
          var reservation = toReservation(reservationRow);
          var transition = toTransitionSnapshot(row, inventoryRow, reservationRow);
          var storedDecision = toAdmissionDecision(row, transition, entry);
          if (transition.inventoryRevision().compareTo(revision) > 0
              || transition.expectedPriorBindingRef() != null
              || transition.expectedPriorBindingGeneration() != null
              || !transition.transitionId().equals(originalDecision.bindingDecisionId())
              || !entry.identity().equals(originalDecision.candidate())
              || !entry.bindingGeneration().equals(transition.bindingGeneration())
              || !entry.transitionId().equals(transition.transitionId())
              || !entry.accountIndexFence().equals(transition.candidateAccountIndexFence())
              || !entry.issuerReservationId().equals(transition.issuerReservationId())
              || !reservation.owner().equals(entry.identity())
              || !reservation.transitionId().equals(transition.transitionId())
              || !reservation.inventoryRevision().equals(transition.inventoryRevision())
              || !entry.inventoryRevision().equals(transition.inventoryRevision())
              || entry.lifecycle()
                  != (committed
                      ? CanonicalGameplayBindingInventoryEntry.Lifecycle.ACTIVE
                      : CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL)
              || reservation.lifecycle()
                  != (committed
                      ? CanonicalIssuerPartitionReservation.Lifecycle.BOUND
                      : CanonicalIssuerPartitionReservation.Lifecycle.RESERVED)
              || entry.accountIndexState()
                  != CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED
              || entry.issuerIndexState()
                  != (committed
                      ? CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED
                      : CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED)) {
            throw conflict(
                "Current SQL identity, reservation, or lifecycle differs from original NEW");
          }
          requireSameAdmissionDecision(originalDecision, storedDecision);

          byte[] candidateBytes = row.get("original_new_candidate_bytes", byte[].class);
          byte[] canonicalBytes = null;
          try {
            String candidateDigest = row.get("original_new_candidate_digest", String.class);
            String readbackDigest = row.get("original_new_candidate_readback_digest", String.class);
            if (!"READBACK_CONFIRMED".equals(row.get("original_new_candidate_state", String.class))
                || candidateBytes == null
                || candidateBytes.length == 0
                || candidateBytes.length
                    > CanonicalGameplayOriginalNewCandidateCodec.MAX_CANDIDATE_BYTES
                || candidateDigest == null
                || !candidateDigest.equals(
                    CanonicalGameplayIssuerMemberV2Codec.sha256(candidateBytes))
                || readbackDigest == null
                || !readbackDigest.matches("sha256:[0-9a-f]{64}")
                || !Arrays.equals(
                    row.get("candidate_binding_ref", byte[].class),
                    originalDecision.candidate().bindingRef().bytes())
                || !Objects.equals(
                    row.get("issuer_reservation_id", UUID.class), reservation.reservationId())) {
              throw conflict("Original NEW has no exact retained candidate readback marker");
            }
            CanonicalGameplayOriginalNewCandidate candidate;
            try {
              candidate = CanonicalGameplayOriginalNewCandidate.parseCanonical(candidateBytes);
            } catch (RuntimeException malformed) {
              throw conflict("Retained original NEW candidate bytes are not canonical");
            }
            if (!candidate.matchesOriginalDecision(storedDecision)
                || !candidate.bindingIdentity().equals(entry.identity())
                || !candidate
                    .reservation()
                    .equals(
                        CanonicalGameplayOriginalNewCandidateCodec.reservationSource(reservation))
                || !candidate.transition().transitionId().equals(transition.transitionId())
                || !candidate
                    .transition()
                    .bindingGeneration()
                    .equals(transition.bindingGeneration())
                || !candidate
                    .transition()
                    .issuerReservationId()
                    .equals(transition.issuerReservationId())
                || !candidate.transition().reservationFence().equals(transition.reservationFence())
                || !candidate
                    .transition()
                    .candidateAccountIndexFence()
                    .equals(transition.candidateAccountIndexFence())) {
              throw conflict(
                  "Retained candidate differs from its original lease, decision, or reservation");
            }
            if (!committed) {
              CanonicalGameplayBindingProvisionalCasEvidence evidence =
                  loadProvisionalCasEvidence(transaction, transition.transitionId(), true);
              CanonicalGameplayOriginalNewCandidate current =
                  CanonicalGameplayOriginalNewCandidate.fromOriginalSources(
                      evidence, originalDecision);
              if (evidence.transition().status()
                      != CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL
                  || !current.equals(candidate)
                  || !candidate.matchesProvisionalSources(evidence)) {
                throw conflict("Provisional candidate no longer matches its exact source");
              }
            }

            var obligations =
                loadAccountIndexObligations(transaction, transition.transitionId(), true);
            if (obligations.size() != 1) {
              throw conflict("Original NEW account obligation is not exact");
            }
            requireCompletedMemberRepairAcknowledgement(
                transaction, transition, entry, obligations.getFirst());
            CanonicalGameplayIssuerBindingLocalAcknowledgement issuerAcknowledgement =
                requireOriginalNewFinalIssuerAcknowledgement(
                    transaction, candidate, transition, committed);
            CanonicalGameplayBindingRegionBridgeObligation regionAcknowledgement =
                requireOriginalNewFinalRegionAcknowledgement(
                    transaction, candidate, transition, entry);

            Optional<OriginalNewFinalAdmissionReceipt> receipt;
            if (committed) {
              receipt = Optional.of(readOriginalNewFinalAdmissionReceipt(row, storedDecision));
            } else {
              requireNoOriginalNewFinalAdmissionReceipt(row);
              receipt = Optional.empty();
            }
            canonicalBytes = candidate.canonicalBytes();
            if (!Arrays.equals(candidateBytes, canonicalBytes)) {
              throw conflict("Retained original NEW candidate changed during source decoding");
            }
            return new OriginalNewFinalAdmissionSource(
                candidate,
                candidateDigest,
                readbackDigest,
                CanonicalGameplayBindingTransitionSnapshot.Status.valueOf(status),
                receipt,
                regionAcknowledgement,
                issuerAcknowledgement);
          } finally {
            if (candidateBytes != null) Arrays.fill(candidateBytes, (byte) 0);
            if (canonicalBytes != null) Arrays.fill(canonicalBytes, (byte) 0);
          }
        });
  }

  void requireSameProvisionalSource(
      CanonicalGameplayBindingProvisionalCasEvidence expected,
      CanonicalGameplayBindingProvisionalCasEvidence current) {
    requireSameProvisionalOperation(
        Objects.requireNonNull(expected, "expected"), Objects.requireNonNull(current, "current"));
  }

  /**
   * Persists the exact selected first-NEW bridge operation and current region fence before Redis
   * can be changed. A retry reuses its operation identity and refuses changed Entity or World
   * provenance.
   */
  CanonicalGameplayBindingRegionBridgeObligation prepareRegionBridge(
      CanonicalGameplayBindingProvisionalCasEvidence expectedEvidence,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion resolvedRegion,
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference runtimeReference,
      CanonicalGameplayRegionRuntimeStatus ownerStatus,
      CanonicalGameplayRegionLeaseRedisOwner.BindingPreimage preimage) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(expectedEvidence, "expectedEvidence");
    Objects.requireNonNull(preimage, "preimage");
    BridgeInputs inputs =
        requireBridgeInputs(
            expectedEvidence, worldRequest, resolvedRegion, runtimeReference, ownerStatus);
    requireExactBridgePreimage(preimage, inputs, expectedEvidence, ownerStatus);
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger currentRevision = lockInventoryRevision(transaction);
          CanonicalGameplayBindingProvisionalCasEvidence currentEvidence =
              loadProvisionalCasEvidence(
                  transaction, expectedEvidence.transition().transitionId(), true);
          requireSameProvisionalOperation(expectedEvidence, currentEvidence);
          BridgeInputs currentInputs =
              requireBridgeInputs(
                  currentEvidence, worldRequest, resolvedRegion, runtimeReference, ownerStatus);
          if (!inputs.equals(currentInputs) || currentEvidence.expectedPrior() != null) {
            throw conflict("Region bridge source changed or is not a first-NEW transition");
          }
          requireCurrentRegionOwner(transaction, ownerStatus);
          Record regionRow =
              transaction.fetchOne(
                  "SELECT * FROM " + REGION_OBLIGATION + " WHERE transition_id = ? FOR UPDATE",
                  currentEvidence.transition().transitionId());
          if (regionRow == null) throw conflict("Region bridge obligation is missing");
          CanonicalGameplayBindingRegionBridgeObligation existing = toRegionObligation(regionRow);
          if (existing.bridgeOperationId() != null) {
            if (!matchesPreparedOperation(existing, inputs)) {
              throw conflict("A retry changed the retained Entity or World bridge source");
            }
            boolean sameGrant =
                Objects.equals(existing.executorFence(), ownerStatus.executorFence())
                    && Objects.equals(
                        existing.leaseTokenCorrelation(), ownerStatus.leaseTokenCorrelation());
            if (sameGrant) return existing;
            return rewritePreparedBridgeForCurrentGrant(
                transaction,
                currentRevision,
                currentEvidence,
                regionRow,
                existing,
                inputs,
                ownerStatus);
          }
          if (preimage.state() != CanonicalGameplayRegionLeaseRedisOwner.PreimageState.ABSENT) {
            throw conflict("First region bridge operation requires a proven absent Redis preimage");
          }
          UUID operationId = UUID.randomUUID();
          return writePreparedBridge(
              transaction, currentRevision, currentEvidence, inputs, ownerStatus, operationId);
        });
  }

  /**
   * Records a binding-local ACK only after the bridge owner's separate Lua readback capability,
   * current World/Entity sources, and current durable region owner have all been revalidated.
   */
  RegionBridgeAcknowledgement acknowledgeRegionBridge(
      CanonicalGameplayBindingRegionBridgeObligation prepared,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion resolvedRegion,
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference runtimeReference,
      CanonicalGameplayRegionRuntimeStatus ownerStatus,
      CanonicalGameplayRegionLeaseRedisOwner.BindingReadback readback,
      Instant acknowledgedAt) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(prepared, "prepared");
    Objects.requireNonNull(readback, "readback");
    Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
    Instant persistedAcknowledgedAt = acknowledgedAt.truncatedTo(ChronoUnit.MICROS);
    CanonicalGameplayBindingProvisionalCasEvidence expectedEvidence =
        readProvisionalCasEvidence(prepared.transitionId());
    BridgeInputs inputs =
        requireBridgeInputs(
            expectedEvidence, worldRequest, resolvedRegion, runtimeReference, ownerStatus);
    if (!matchesPreparedOperation(prepared, inputs)
        || !readback.runtimeEntityId().equals(runtimeReference.runtimeEntityId())
        || !readback.sessionId().equals(expectedEvidence.candidate().identity().sessionId())
        || !readback.bindingGeneration().equals(expectedEvidence.candidate().bindingGeneration())
        || readback.regionEpoch() != ownerStatus.regionEpoch()
        || !readback.executorFence().equals(ownerStatus.executorFence())
        || !readback.leaseTokenCorrelation().equals(ownerStatus.leaseTokenCorrelation())) {
      throw conflict("Exact fenced bridge readback does not match the prepared operation");
    }
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger currentRevision = lockInventoryRevision(transaction);
          CanonicalGameplayBindingProvisionalCasEvidence currentEvidence =
              loadProvisionalCasEvidence(transaction, prepared.transitionId(), true);
          requireSameProvisionalOperation(expectedEvidence, currentEvidence);
          BridgeInputs currentInputs =
              requireBridgeInputs(
                  currentEvidence, worldRequest, resolvedRegion, runtimeReference, ownerStatus);
          if (!inputs.equals(currentInputs) || currentEvidence.expectedPrior() != null) {
            throw conflict("Region bridge sources changed before durable acknowledgement");
          }
          requireCurrentRegionOwner(transaction, ownerStatus);
          Record regionRow =
              transaction.fetchOne(
                  "SELECT * FROM " + REGION_OBLIGATION + " WHERE transition_id = ? FOR UPDATE",
                  prepared.transitionId());
          if (regionRow == null) throw conflict("Region bridge obligation is missing");
          CanonicalGameplayBindingRegionBridgeObligation current = toRegionObligation(regionRow);
          if (!matchesPreparedOperation(current, inputs)
              || !current.bridgeOperationId().equals(prepared.bridgeOperationId())
              || !Objects.equals(current.executorFence(), ownerStatus.executorFence())
              || !Objects.equals(
                  current.leaseTokenCorrelation(), ownerStatus.leaseTokenCorrelation())) {
            throw conflict("Prepared region bridge grant or source changed before acknowledgement");
          }
          if (current.status()
              == CanonicalGameplayBindingRegionBridgeObligation.Status.ACKNOWLEDGED) {
            if (!ownerStatus.executorFence().equals(readback.executorFence())
                || !current.redisReadbackDigest().equals(readback.digest())) {
              throw conflict(
                  "A completed region acknowledgement has different readback provenance");
            }
            return new RegionBridgeAcknowledgement(current);
          }
          BigInteger oldRevision = currentEvidence.transition().inventoryRevision();
          BigInteger nextRevision = currentRevision.add(BigInteger.ONE);
          writeRevisionRows(transaction, currentEvidence, oldRevision, nextRevision);
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + REGION_OBLIGATION
                      + " SET status = 'ACKNOWLEDGED', redis_readback_digest = ?,"
                      + " acknowledged_at = ?::timestamptz, inventory_revision = ?"
                      + " WHERE transition_id = ? AND status = 'REQUIRED'"
                      + " AND bridge_operation_id = ? AND executor_fence = ?"
                      + " AND lease_token_correlation = ? AND inventory_revision = ?",
                  readback.digest(),
                  OffsetDateTime.ofInstant(persistedAcknowledgedAt, ZoneOffset.UTC),
                  decimal(nextRevision),
                  prepared.transitionId(),
                  prepared.bridgeOperationId(),
                  decimal(ownerStatus.executorFence()),
                  ownerStatus.leaseTokenCorrelation(),
                  decimal(oldRevision)),
              "Region bridge obligation changed before acknowledgement commit");
          bumpInventoryClock(transaction, currentRevision, nextRevision);
          Record acknowledgedRow =
              transaction.fetchOne(
                  "SELECT * FROM " + REGION_OBLIGATION + " WHERE transition_id = ?",
                  prepared.transitionId());
          CanonicalGameplayBindingRegionBridgeObligation acknowledged =
              acknowledgedRow == null ? null : toRegionObligation(acknowledgedRow);
          if (acknowledged == null
              || acknowledged.status()
                  != CanonicalGameplayBindingRegionBridgeObligation.Status.ACKNOWLEDGED
              || !acknowledged.bridgeOperationId().equals(prepared.bridgeOperationId())
              || !acknowledged.executorFence().equals(ownerStatus.executorFence())
              || !acknowledged.leaseTokenCorrelation().equals(ownerStatus.leaseTokenCorrelation())
              || !acknowledged.redisReadbackDigest().equals(readback.digest())
              || !acknowledged.acknowledgedAt().equals(persistedAcknowledgedAt)
              || !acknowledged.inventoryRevision().equals(nextRevision)) {
            throw conflict("Region bridge acknowledgement did not survive exact SQL readback");
          }
          return new RegionBridgeAcknowledgement(acknowledged);
        });
  }

  private CanonicalGameplayBindingRegionBridgeObligation rewritePreparedBridgeForCurrentGrant(
      DSLContext transaction,
      BigInteger currentRevision,
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      Record regionRow,
      CanonicalGameplayBindingRegionBridgeObligation existing,
      BridgeInputs inputs,
      CanonicalGameplayRegionRuntimeStatus ownerStatus) {
    BigInteger oldRevision = evidence.transition().inventoryRevision();
    BigInteger nextRevision = currentRevision.add(BigInteger.ONE);
    writeRevisionRows(transaction, evidence, oldRevision, nextRevision);
    requireOne(
        transaction.execute(
            "UPDATE "
                + REGION_OBLIGATION
                + " SET status = 'REQUIRED', executor_fence = ?, lease_token_correlation = ?,"
                + " redis_readback_digest = NULL, acknowledged_at = NULL, inventory_revision = ?"
                + " WHERE transition_id = ? AND bridge_operation_id = ?"
                + " AND inventory_revision = ?",
            decimal(ownerStatus.executorFence()),
            ownerStatus.leaseTokenCorrelation(),
            decimal(nextRevision),
            evidence.transition().transitionId(),
            existing.bridgeOperationId(),
            decimal(oldRevision)),
        "Prepared region bridge changed before current-grant revalidation");
    bumpInventoryClock(transaction, currentRevision, nextRevision);
    return toRegionObligation(
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT * FROM "
                    + REGION_OBLIGATION
                    + " WHERE transition_id = ? AND bridge_operation_id = ?",
                evidence.transition().transitionId(),
                existing.bridgeOperationId()),
            "prepared region bridge row must survive current-grant readback"));
  }

  private CanonicalGameplayBindingRegionBridgeObligation writePreparedBridge(
      DSLContext transaction,
      BigInteger currentRevision,
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      BridgeInputs inputs,
      CanonicalGameplayRegionRuntimeStatus ownerStatus,
      UUID operationId) {
    BigInteger oldRevision = evidence.transition().inventoryRevision();
    BigInteger nextRevision = currentRevision.add(BigInteger.ONE);
    writeRevisionRows(transaction, evidence, oldRevision, nextRevision);
    Object[] args =
        new Object[] {
          operationId,
          inputs.runtimeEntityId().value(),
          inputs.projectionGeneration(),
          inputs.projectionFence(),
          inputs.workloadNamespace(),
          inputs.assignmentId(),
          inputs.assignmentIntentDigest(),
          inputs.entityReferenceDigest(),
          inputs.worldSourceDigest(),
          inputs.canonicalRegionId(),
          inputs.lifecycleEpoch(),
          inputs.lifecycleRowVersion(),
          inputs.captureId(),
          inputs.graphSha256(),
          inputs.preparationInputDigest(),
          decimal(ownerStatus.executorFence()),
          ownerStatus.leaseTokenCorrelation(),
          decimal(nextRevision),
          evidence.transition().transitionId(),
          decimal(oldRevision)
        };
    String sql =
        "UPDATE "
            + REGION_OBLIGATION
            + " SET bridge_operation_id = ?, bridge_preimage_state = 'ABSENT',"
            + " runtime_entity_id = ?,"
            + " entity_projection_generation = ?, entity_projection_fence = ?,"
            + " entity_workload_namespace = ?, assignment_uuid = ?,"
            + " assignment_intent_digest = ?, entity_reference_digest = ?,"
            + " world_source_digest = ?, canonical_region_id = ?,"
            + " world_lifecycle_epoch = ?, world_lifecycle_row_version = ?,"
            + " world_capture_id = ?, world_graph_sha256 = ?,"
            + " world_preparation_input_digest = ?, executor_fence = ?,"
            + " lease_token_correlation = ?, inventory_revision = ?"
            + " WHERE transition_id = ? AND "
            + "bridge_operation_id IS NULL"
            + " AND inventory_revision = ?";
    requireOne(
        transaction.execute(sql, args),
        "Region bridge operation compare-and-set lost its exact obligation");
    bumpInventoryClock(transaction, currentRevision, nextRevision);
    Record row =
        transaction.fetchOne(
            "SELECT * FROM " + REGION_OBLIGATION + " WHERE transition_id = ?",
            evidence.transition().transitionId());
    CanonicalGameplayBindingRegionBridgeObligation result =
        row == null ? null : toRegionObligation(row);
    if (result == null || !matchesPreparedOperation(result, inputs)) {
      throw conflict("Region bridge operation did not survive durable SQL readback");
    }
    return result;
  }

  private static void writeRevisionRows(
      DSLContext transaction,
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      BigInteger oldRevision,
      BigInteger nextRevision) {
    UUID transitionId = evidence.transition().transitionId();
    requireOne(
        transaction.execute(
            "UPDATE "
                + TRANSITION
                + " SET inventory_revision = ? WHERE transition_id = ?"
                + " AND status = 'PROVISIONAL' AND inventory_revision = ?"
                + " AND candidate_binding_ref = ? AND candidate_binding_generation = ?",
            decimal(nextRevision),
            transitionId,
            decimal(oldRevision),
            evidence.candidate().bindingRef().bytes(),
            decimal(evidence.candidate().bindingGeneration())),
        "Provisional transition changed before region bridge revision");
    requireOne(
        transaction.execute(
            "UPDATE "
                + INVENTORY
                + " SET inventory_revision = ? WHERE binding_ref = ? AND transition_id = ?"
                + " AND lifecycle = 'PROVISIONAL' AND binding_generation = ?"
                + " AND inventory_revision = ?",
            decimal(nextRevision),
            evidence.candidate().bindingRef().bytes(),
            transitionId,
            decimal(evidence.candidate().bindingGeneration()),
            decimal(oldRevision)),
        "Provisional candidate changed before region bridge revision");
    requireOne(
        transaction.execute(
            "UPDATE "
                + ACCOUNT_OBLIGATION
                + " SET inventory_revision = ? WHERE transition_id = ? AND inventory_revision = ?",
            decimal(nextRevision),
            transitionId,
            decimal(oldRevision)),
        "Candidate account obligation changed before region bridge revision");
    requireOne(
        transaction.execute(
            "UPDATE "
                + ISSUER_OBLIGATION
                + " SET inventory_revision = ? WHERE transition_id = ? AND inventory_revision = ?",
            decimal(nextRevision),
            transitionId,
            decimal(oldRevision)),
        "Issuer obligation changed before region bridge revision");
    requireOne(
        transaction.execute(
            "UPDATE "
                + RESERVATION
                + " SET inventory_revision = ? WHERE reservation_id = ? AND transition_id = ?"
                + " AND lifecycle = 'RESERVED' AND inventory_revision = ?",
            decimal(nextRevision),
            evidence.candidateReservation().reservationId(),
            transitionId,
            decimal(oldRevision)),
        "Candidate issuer reservation changed before region bridge revision");
  }

  private static void updateOriginalNewCandidateRevisionRows(
      DSLContext transaction,
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      BigInteger oldRevision,
      BigInteger nextRevision,
      byte[] candidateBytes,
      String candidateDigest) {
    UUID transitionId = evidence.transition().transitionId();
    requireOne(
        transaction.execute(
            "UPDATE "
                + TRANSITION
                + " SET original_new_candidate_bytes = ?, original_new_candidate_digest = ?,"
                + " original_new_candidate_state = 'DISPATCH_POSSIBLE', inventory_revision = ?"
                + " WHERE transition_id = ? AND status = 'PREPARED' AND inventory_revision = ?"
                + " AND expected_prior_binding_ref IS NULL"
                + " AND expected_prior_binding_generation IS NULL"
                + " AND admission_lease_id IS NULL AND original_new_candidate_bytes IS NULL"
                + " AND original_new_candidate_digest IS NULL AND original_new_candidate_state IS NULL"
                + " AND original_new_candidate_readback_digest IS NULL",
            candidateBytes,
            candidateDigest,
            decimal(nextRevision),
            transitionId,
            decimal(oldRevision)),
        "Original NEW candidate lost its first durable dispatch claim");
    requireOne(
        transaction.execute(
            "UPDATE "
                + INVENTORY
                + " SET inventory_revision = ? WHERE binding_ref = ? AND transition_id = ?"
                + " AND lifecycle = 'CANDIDATE_PREPARED' AND binding_generation = ?"
                + " AND account_index_state = 'REPAIR_REQUIRED'"
                + " AND issuer_index_state = 'REPAIR_REQUIRED' AND inventory_revision = ?",
            decimal(nextRevision),
            evidence.candidate().bindingRef().bytes(),
            transitionId,
            decimal(evidence.transition().bindingGeneration()),
            decimal(oldRevision)),
        "Original NEW candidate inventory row changed before first dispatch claim");
    requireCount(
        transaction.execute(
            "UPDATE "
                + ACCOUNT_OBLIGATION
                + " SET inventory_revision = ? WHERE transition_id = ? AND inventory_revision = ?",
            decimal(nextRevision),
            transitionId,
            decimal(oldRevision)),
        1,
        "Original NEW account-index obligation changed before first dispatch claim");
    requireOne(
        transaction.execute(
            "UPDATE "
                + ISSUER_OBLIGATION
                + " SET inventory_revision = ? WHERE transition_id = ? AND inventory_revision = ?",
            decimal(nextRevision),
            transitionId,
            decimal(oldRevision)),
        "Original NEW issuer-index obligation changed before first dispatch claim");
    requireOne(
        transaction.execute(
            "UPDATE "
                + REGION_OBLIGATION
                + " SET inventory_revision = ? WHERE transition_id = ? AND inventory_revision = ?",
            decimal(nextRevision),
            transitionId,
            decimal(oldRevision)),
        "Original NEW region obligation changed before first dispatch claim");
    requireOne(
        transaction.execute(
            "UPDATE "
                + RESERVATION
                + " SET inventory_revision = ? WHERE reservation_id = ? AND transition_id = ?"
                + " AND lifecycle = 'RESERVED' AND inventory_revision = ?",
            decimal(nextRevision),
            evidence.candidateReservation().reservationId(),
            transitionId,
            decimal(oldRevision)),
        "Original NEW issuer reservation changed before first dispatch claim");
  }

  private static void bumpInventoryClock(
      DSLContext transaction, BigInteger currentRevision, BigInteger nextRevision) {
    requireOne(
        transaction.execute(
            "UPDATE game_session_canonical_binding_inventory_clock"
                + " SET inventory_revision = ? WHERE singleton_id = 1 AND inventory_revision = ?",
            decimal(nextRevision),
            decimal(currentRevision)),
        "Inventory revision compare-and-set lost during region bridge operation");
  }

  private static boolean matchesPreparedOperation(
      CanonicalGameplayBindingRegionBridgeObligation obligation, BridgeInputs inputs) {
    return obligation.bridgeOperationId() != null
        && "ABSENT".equals(obligation.bridgePreimageState())
        && obligation.runtimeEntityId() == inputs.runtimeEntityId().value()
        && obligation.entityProjectionGeneration() == inputs.projectionGeneration()
        && obligation.entityProjectionFence() == inputs.projectionFence()
        && obligation.entityWorkloadNamespace().equals(inputs.workloadNamespace())
        && obligation.assignmentId().equals(inputs.assignmentId())
        && obligation.assignmentIntentDigest().equals(inputs.assignmentIntentDigest())
        && obligation.entityReferenceDigest().equals(inputs.entityReferenceDigest())
        && obligation.worldSourceDigest().equals(inputs.worldSourceDigest())
        && obligation.canonicalRegionId().equals(inputs.canonicalRegionId())
        && obligation.worldLifecycleEpoch() == inputs.lifecycleEpoch()
        && obligation.worldLifecycleRowVersion() == inputs.lifecycleRowVersion()
        && obligation.worldCaptureId().equals(inputs.captureId())
        && obligation.worldGraphSha256().equals(inputs.graphSha256())
        && obligation.worldPreparationInputDigest().equals(inputs.preparationInputDigest());
  }

  private static void requireExactBridgePreimage(
      CanonicalGameplayRegionLeaseRedisOwner.BindingPreimage preimage,
      BridgeInputs inputs,
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      CanonicalGameplayRegionRuntimeStatus ownerStatus) {
    if (!preimage.runtimeEntityId().equals(inputs.runtimeEntityId())
        || !preimage.sessionId().equals(evidence.candidate().identity().sessionId())
        || !preimage.bindingGeneration().equals(evidence.candidate().bindingGeneration())
        || preimage.regionEpoch() != ownerStatus.regionEpoch()
        || !preimage.executorFence().equals(ownerStatus.executorFence())
        || !preimage.leaseTokenCorrelation().equals(ownerStatus.leaseTokenCorrelation())) {
      throw conflict("Region binding preimage proof does not match the current grant and tuple");
    }
  }

  private static BridgeInputs requireBridgeInputs(
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion resolvedRegion,
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference reference,
      CanonicalGameplayRegionRuntimeStatus ownerStatus) {
    Objects.requireNonNull(worldRequest, "worldRequest");
    Objects.requireNonNull(resolvedRegion, "resolvedRegion");
    Objects.requireNonNull(reference, "runtimeReference");
    Objects.requireNonNull(ownerStatus, "ownerStatus");
    if (evidence.transition().status()
            != CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL
        || evidence.candidate().lifecycle()
            != CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL
        || evidence.expectedPrior() != null
        || evidence.transition().expectedPriorBindingRef() != null
        || evidence.transition().expectedPriorBindingGeneration() != null) {
      throw conflict("Region bridge applies only to the selected initial PROVISIONAL NEW");
    }
    CanonicalGameplayBindingIdentity identity = evidence.candidate().identity();
    var target = worldRequest.getExpectedTarget();
    var assignment = resolvedRegion.lifecycleAssignment();
    if (worldRequest.getSchemaVersion() != 1
        || !worldRequest.getUnknownFields().asMap().isEmpty()
        || !reference.target().equals(target)
        || !reference.assignmentUuid().toString().equals(worldRequest.getAssignmentUuid())
        || !reference
            .canonicalAccountUuid()
            .toString()
            .equals(worldRequest.getCanonicalAccountUuid())
        || !reference.characterUuid().toString().equals(worldRequest.getCharacterUuid())
        || !reference.intentDigest().equals(worldRequest.getAssignmentIntentDigest())
        || !identity.accountId().toString().equals(worldRequest.getCanonicalAccountUuid())
        || !identity.characterId().toString().equals(worldRequest.getCharacterUuid())
        || !identity.tenantId().toString().equals(target.getCanonicalTenantUuid())
        || !identity.gameInstanceId().toString().equals(target.getGameInstanceId())
        || !identity
            .playableStateNamespaceId()
            .toString()
            .equals(target.getPlayableStateNamespaceUuid())
        || !"SHARED".equals(identity.playableStateScope())
        || !reference.runtimeEntityId().tenantId().equals(identity.tenantId())
        || !reference
            .runtimeEntityId()
            .gameInstanceId()
            .equals(identity.gameInstanceId().toString())
        || !identity.tenantId().equals(assignment.request().canonicalTenantId())
        || !identity.gameInstanceId().equals(assignment.request().canonicalGameInstanceId())
        || !identity.regionId().equals(assignment.operationalRegionId())
        || !identity.regionId().equals(resolvedRegion.operationalRegionId())
        || !identity.regionEpoch().equals(BigInteger.valueOf(ownerStatus.regionEpoch()))
        || !reference.workloadNamespace().equals(assignment.request().targetNamespace())
        || !identity.tenantId().equals(ownerStatus.tenantId())
        || !identity.gameInstanceId().equals(ownerStatus.gameInstanceId())
        || !resolvedRegion.canonicalRegionInstanceId().equals(ownerStatus.canonicalRegionId())
        || !resolvedRegion.operationalRegionId().equals(ownerStatus.regionId())
        || !assignment.captureId().equals(ownerStatus.captureId())
        || !assignment.graphSha256().equals(ownerStatus.graphSha256())
        || !assignment.preparationInputDigest().equals(ownerStatus.preparationInputDigest())
        || !CanonicalGameplayRegionRuntimeStatusRepository.sameLifecycleRequestExceptReadRequestId(
            assignment.request(), ownerStatus.lifecycleRequest())
        || assignment.lifecycleEpoch() != ownerStatus.lifecycleEpoch()
        || assignment.lifecycleRowVersion() != ownerStatus.lifecycleRowVersion()
        || ownerStatus.paused()
        || !CanonicalGameplayRegionRedisKeys.tenantRegionTag(
                ownerStatus.tenantId(), ownerStatus.gameInstanceId(), ownerStatus.regionId())
            .equals(ownerStatus.tenantRegionTag())) {
      throw conflict("Entity, World, binding, and region owner scopes do not form one exact join");
    }
    return new BridgeInputs(
        reference.runtimeEntityId(),
        reference.projectionGeneration(),
        reference.projectionFence(),
        reference.workloadNamespace(),
        reference.assignmentUuid(),
        reference.intentDigest(),
        CanonicalGameplayRegionBindingBridgeProofs.entityReferenceDigest(reference),
        CanonicalGameplayRegionBindingBridgeProofs.worldSourceDigest(worldRequest, resolvedRegion),
        resolvedRegion.canonicalRegionInstanceId(),
        assignment.lifecycleEpoch(),
        assignment.lifecycleRowVersion(),
        assignment.captureId(),
        assignment.graphSha256(),
        assignment.preparationInputDigest());
  }

  private static void requireCurrentRegionOwner(
      DSLContext transaction, CanonicalGameplayRegionRuntimeStatus expected) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM game_session_region_runtime_status"
                + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ? FOR SHARE",
            expected.tenantId(),
            expected.gameInstanceId(),
            expected.regionId());
    if (row == null
        || !Objects.equals(row.get("canonical_region_id", UUID.class), expected.canonicalRegionId())
        || !Objects.equals(row.get("tenant_region_tag", String.class), expected.tenantRegionTag())
        || !Objects.equals(row.get("region_epoch", Long.class), expected.regionEpoch())
        || !Objects.equals(
            row.get("executor_fence", Long.class), expected.executorFence().longValueExact())
        || !Objects.equals(
            row.get("lease_token_correlation", String.class), expected.leaseTokenCorrelation())
        || !Objects.equals(row.get("owner_service", String.class), expected.ownerService())
        || !Objects.equals(row.get("owner_instance_id", String.class), expected.ownerInstanceId())
        || !Objects.equals(row.get("lifecycle_epoch", Long.class), expected.lifecycleEpoch())
        || !Objects.equals(
            row.get("lifecycle_row_version", Long.class), expected.lifecycleRowVersion())
        || !Objects.equals(row.get("capture_id", UUID.class), expected.captureId())
        || !Arrays.equals(
            row.get("lifecycle_request_bytes", byte[].class),
            expected.lifecycleRequest().canonicalBytes())
        || !Objects.equals(row.get("graph_sha256", String.class), expected.graphSha256())
        || !Objects.equals(
            row.get("preparation_input_digest", String.class), expected.preparationInputDigest())
        || !Boolean.FALSE.equals(row.get("paused", Boolean.class))
        || !Objects.equals(
            row.get("last_committed_tick_id", Long.class), expected.lastCommittedTickId())
        || !Objects.equals(
            row.get("last_committed_tick_batch_id", String.class),
            expected.lastCommittedTickBatchId())) {
      throw conflict("Durable region owner, timeline, or executor fence changed");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw conflict("Region bridge repository calls require no ambient SQL transaction");
    }
  }

  private record BridgeInputs(
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeEntityId runtimeEntityId,
      long projectionGeneration,
      long projectionFence,
      String workloadNamespace,
      UUID assignmentId,
      String assignmentIntentDigest,
      String entityReferenceDigest,
      String worldSourceDigest,
      UUID canonicalRegionId,
      long lifecycleEpoch,
      long lifecycleRowVersion,
      UUID captureId,
      String graphSha256,
      String preparationInputDigest) {}

  /** Inventory-issued result of the exact durable region ACK and its independent SQL readback. */
  static final class RegionBridgeAcknowledgement {
    private final CanonicalGameplayBindingRegionBridgeObligation obligation;

    private RegionBridgeAcknowledgement(CanonicalGameplayBindingRegionBridgeObligation obligation) {
      if (obligation.status()
          != CanonicalGameplayBindingRegionBridgeObligation.Status.ACKNOWLEDGED) {
        throw conflict("Only a persisted region bridge ACK can create its receipt");
      }
      this.obligation = obligation;
    }

    CanonicalGameplayBindingRegionBridgeObligation obligation() {
      return obligation;
    }
  }

  /**
   * Reads a lease-bound PROVISIONAL decision from its existing transition and current inventory.
   * The supplied Account lease is only an expected value; all returned decision fields are loaded
   * from durable Game Session rows and the canonical lease carrier is reparsed and digest-checked.
   */
  public CanonicalGameplayAdmissionDecision readAdmissionDecision(
      UUID bindingDecisionId, AccountGameplayAdmissionLeaseEvidence expectedLeaseEvidence) {
    Objects.requireNonNull(bindingDecisionId, "bindingDecisionId");
    Objects.requireNonNull(expectedLeaseEvidence, "expectedLeaseEvidence");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          CanonicalGameplayBindingProvisionalCasEvidence operation =
              loadProvisionalCasEvidence(transaction, bindingDecisionId, false);
          if (operation.transition().status()
                  != CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL
              || operation.candidate().lifecycle()
                  != CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL) {
            throw conflict("Only an exact durable PROVISIONAL transition is a decision");
          }
          Record row = readTransition(transaction, bindingDecisionId);
          CanonicalGameplayAdmissionDecision stored = toAdmissionDecision(row, operation);
          if (!stored.leaseEvidence().hasSameIdentity(expectedLeaseEvidence)) {
            throw conflict("Expected Account lease differs from the immutable stored decision");
          }
          return stored;
        });
  }

  /**
   * Reads the retained exact first-NEW lease source for the purpose-specific Account abort path.
   * The caller supplies only the existing transition identity; every lease and route field comes
   * from the immutable Game Session transition row.
   */
  CanonicalGameplayAdmissionAbortSource readFirstNewAdmissionAbortSource(UUID transitionId) {
    Objects.requireNonNull(transitionId, "transitionId");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          return loadFirstNewAdmissionAbortSource(transaction, transitionId, false);
        });
  }

  /**
   * Reads only an already recorded definitive first-NEW Account abort for issuer cleanup. Its
   * original transition, candidate, reservation, and cleanup identity remain authoritative; a later
   * mutable Account-control value does not rewrite that retained terminal provenance.
   */
  CanonicalGameplayAdmissionAbortSource readRetainedDefinitiveFirstNewAccountAbortSource(
      UUID transitionId) {
    Objects.requireNonNull(transitionId, "transitionId");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          return loadFirstNewAdmissionAbortSource(transaction, transitionId, false, true);
        });
  }

  /**
   * Read-only package seam for the local issuer release owner inside its guarded SQL transaction.
   */
  static CanonicalGameplayAdmissionAbortSource readFirstNewAdmissionAbortSourceInTransaction(
      DSLContext transaction, UUID transitionId, boolean forUpdate) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(transitionId, "transitionId");
    return loadFirstNewAdmissionAbortSource(transaction, transitionId, forUpdate);
  }

  /** Read-only/update-locked seam for exact retained terminal cleanup inside the caller's SQL. */
  static CanonicalGameplayAdmissionAbortSource
      readRetainedDefinitiveFirstNewAccountAbortSourceInTransaction(
          DSLContext transaction, UUID transitionId, boolean forUpdate) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(transitionId, "transitionId");
    return loadFirstNewAdmissionAbortSource(transaction, transitionId, forUpdate, true);
  }

  /**
   * Commits Account's exact definitive first-NEW abort into the existing transition and retained
   * candidate/reservation source. The Account RPC is performed by the caller outside this method.
   */
  CanonicalGameplayAdmissionAbortSource recordFirstNewAccountAbort(
      CanonicalGameplayAdmissionAbortSource expectedSource, UUID orphanCleanupId) {
    Objects.requireNonNull(expectedSource, "expectedSource");
    requireNonNil(orphanCleanupId, "orphanCleanupId");
    if (expectedSource.isAccountAborted()) {
      if (!orphanCleanupId.equals(expectedSource.orphanCleanupId())) {
        throw conflict("Account abort retry changed the durable cleanup identity");
      }
      return readFirstNewAdmissionAbortSource(expectedSource.transition().transitionId());
    }

    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger currentRevision = lockInventoryRevision(transaction);
          UUID transitionId = expectedSource.transition().transitionId();
          CanonicalGameplayAdmissionAbortSource beforeRuntimeLock =
              loadFirstNewAdmissionAbortSource(transaction, transitionId, false);
          if (beforeRuntimeLock.isAccountAborted()) {
            requireSameFirstNewAdmissionAbortSource(expectedSource, beforeRuntimeLock);
            if (!orphanCleanupId.equals(beforeRuntimeLock.orphanCleanupId())) {
              throw conflict("Concurrent Account abort changed the durable cleanup identity");
            }
            return beforeRuntimeLock;
          }
          lockLeaseBoundRuntimeBeforeTransition(
              transaction,
              transitionId,
              expectedSource.transition().bindingRef().bytes(),
              expectedSource.originalDecision().targetEvidence());
          CanonicalGameplayAdmissionAbortSource current =
              loadFirstNewAdmissionAbortSource(transaction, transitionId, true);
          requireSameFirstNewAdmissionAbortSource(expectedSource, current);
          requireAccountCoverageEvidenceCurrent(
              transaction,
              current.candidate().identity().accountId(),
              current.transition().accountCoverageEvidence());

          if (current.isAccountAborted()) {
            if (!orphanCleanupId.equals(current.orphanCleanupId())) {
              throw conflict("Concurrent Account abort changed the durable cleanup identity");
            }
            return current;
          }
          if (current.transition().status()
              != CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL) {
            throw conflict("Only the exact PROVISIONAL first-NEW transition may abort");
          }

          BigInteger priorRevision = current.transition().inventoryRevision();
          BigInteger nextRevision = currentRevision.add(BigInteger.ONE);
          int transitionChanged =
              transaction.execute(
                  "UPDATE "
                      + TRANSITION
                      + " SET status = 'ABORTED', admission_orphan_cleanup_id = ?,"
                      + " inventory_revision = ?"
                      + " WHERE transition_id = ? AND status = 'PROVISIONAL'"
                      + " AND inventory_revision = ? AND candidate_binding_ref = ?"
                      + " AND candidate_binding_generation = ?"
                      + " AND candidate_account_index_fence = ? AND issuer_reservation_id = ?"
                      + " AND expected_prior_binding_ref IS NULL"
                      + " AND expected_prior_binding_generation IS NULL"
                      + " AND admission_lease_kind = 'NEW_BINDING'"
                      + " AND admission_request_id = ? AND admission_lease_id = ?"
                      + " AND admission_lease_fence = ? AND admission_lease_digest = ?"
                      + " AND admission_lease_evidence = ?"
                      + " AND admission_orphan_cleanup_id IS NULL",
                  orphanCleanupId,
                  decimal(nextRevision),
                  transitionId,
                  decimal(priorRevision),
                  current.transition().bindingRef().bytes(),
                  decimal(current.transition().bindingGeneration()),
                  current.transition().candidateAccountIndexFence(),
                  current.transition().issuerReservationId(),
                  current.originalDecision().requestId(),
                  current.originalDecision().leaseId(),
                  decimal(current.originalDecision().leaseFence()),
                  current.originalDecision().leaseSha256(),
                  current.originalDecision().leaseEvidenceJson());
          requireOne(
              transitionChanged,
              "Exact first-NEW Account abort lost its immutable transition compare-and-set");

          int reservationChanged =
              transaction.execute(
                  "UPDATE "
                      + RESERVATION
                      + " SET lifecycle = 'RELEASE_PENDING', inventory_revision = ?"
                      + " WHERE reservation_id = ? AND transition_id = ?"
                      + " AND lifecycle = 'RESERVED' AND inventory_revision = ?"
                      + " AND binding_ref = ? AND binding_generation = ?"
                      + " AND reservation_fence = ? AND account_id = ? AND tenant_id = ?"
                      + " AND playable_state_namespace_id = ? AND character_id = ?"
                      + " AND game_instance_id = ? AND runtime_game_instance_id = ?"
                      + " AND session_id = ? AND region_id = ? AND region_epoch = ?"
                      + " AND issuer_id = ? AND issuer_auth_generation = ?",
                  decimal(nextRevision),
                  current.reservation().reservationId(),
                  transitionId,
                  decimal(priorRevision),
                  current.transition().bindingRef().bytes(),
                  decimal(current.transition().bindingGeneration()),
                  current.reservation().reservationFence(),
                  current.candidate().identity().accountId(),
                  current.candidate().identity().tenantId(),
                  current.candidate().identity().playableStateNamespaceId(),
                  current.candidate().identity().characterId(),
                  current.candidate().identity().gameInstanceId(),
                  current.candidate().identity().runtimeGameInstanceId(),
                  current.candidate().identity().sessionId(),
                  current.candidate().identity().regionId(),
                  decimal(current.candidate().identity().regionEpoch()),
                  current.candidate().identity().issuerId(),
                  decimal(current.candidate().identity().issuerAuthGeneration()));
          requireOne(
              reservationChanged,
              "Exact first-NEW Account abort lost its retained reservation compare-and-set");

          int candidateChanged =
              transaction.execute(
                  "UPDATE "
                      + INVENTORY
                      + " SET lifecycle = 'TERMINAL_UNRESOLVED', inventory_revision = ?"
                      + " WHERE binding_ref = ? AND transition_id = ?"
                      + " AND lifecycle = 'PROVISIONAL' AND inventory_revision = ?"
                      + " AND binding_generation = ? AND account_index_fence = ?"
                      + " AND tenant_id = ? AND playable_state_namespace_id = ?"
                      + " AND character_id = ? AND account_id = ?",
                  decimal(nextRevision),
                  current.transition().bindingRef().bytes(),
                  transitionId,
                  decimal(priorRevision),
                  decimal(current.transition().bindingGeneration()),
                  current.transition().candidateAccountIndexFence(),
                  current.candidate().identity().tenantId(),
                  current.candidate().identity().playableStateNamespaceId(),
                  current.candidate().identity().characterId(),
                  current.candidate().identity().accountId());
          requireOne(
              candidateChanged,
              "Exact first-NEW Account abort lost its retained candidate compare-and-set");

          requireOne(
              transaction.execute(
                  "UPDATE "
                      + ACCOUNT_OBLIGATION
                      + " SET inventory_revision = ? WHERE transition_id = ?"
                      + " AND inventory_revision = ?",
                  decimal(nextRevision),
                  transitionId,
                  decimal(priorRevision)),
              "First-NEW Account abort lost its required account-index obligation");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + ISSUER_OBLIGATION
                      + " SET inventory_revision = ? WHERE transition_id = ?"
                      + " AND inventory_revision = ?",
                  decimal(nextRevision),
                  transitionId,
                  decimal(priorRevision)),
              "First-NEW Account abort lost its required issuer-index obligation");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + REGION_OBLIGATION
                      + " SET inventory_revision = ? WHERE transition_id = ?"
                      + " AND inventory_revision = ?",
                  decimal(nextRevision),
                  transitionId,
                  decimal(priorRevision)),
              "First-NEW Account abort lost its required region-bridge obligation");
          requireOne(
              transaction.execute(
                  "UPDATE game_session_canonical_binding_inventory_clock"
                      + " SET inventory_revision = ? WHERE singleton_id = 1"
                      + " AND inventory_revision = ?",
                  decimal(nextRevision),
                  decimal(currentRevision)),
              "First-NEW Account abort lost its inventory revision compare-and-set");
          return loadFirstNewAdmissionAbortSource(transaction, transitionId, false);
        });
  }

  /**
   * Persists exact Redis PRESENT readback for the candidate account member without satisfying the
   * account-index obligation. Account-wide v2 coverage remains a separate required owner result.
   */
  CanonicalGameplayBindingAccountIndexProjectionReceipt markCandidateAccountIndexPresent(
      CanonicalGameplayBindingProvisionalCasEvidence expectedEvidence, String member) {
    Objects.requireNonNull(expectedEvidence, "expectedEvidence");
    Objects.requireNonNull(member, "member");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger currentRevision = lockInventoryRevision(transaction);
          UUID transitionId = expectedEvidence.transition().transitionId();
          lockLeaseBoundRuntimeBeforeTransition(
              transaction, transitionId, expectedEvidence.candidate().bindingRef().bytes(), null);
          CanonicalGameplayBindingProvisionalCasEvidence current =
              loadProvisionalCasEvidence(transaction, transitionId, true);
          requireAccountCoverageEvidenceCurrent(
              transaction,
              current.candidate().identity().accountId(),
              current.transition().accountCoverageEvidence());
          requireSameProvisionalOperation(expectedEvidence, current);
          if (current.transition().status()
              != CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL) {
            throw conflict("Only the exact PROVISIONAL candidate may project its account member");
          }
          CanonicalGameplayAccountIndexMember exactMember =
              CanonicalGameplayAccountIndexMember.of(current.candidate());
          if (!exactMember.value().equals(member)) {
            throw conflict("Redis readback member differs from the exact durable candidate tuple");
          }

          List<CanonicalGameplayBindingAccountIndexObligation> obligations =
              loadAccountIndexObligations(transaction, transitionId, true);
          int expectedObligations = current.expectedPrior() == null ? 1 : 2;
          if (obligations.size() != expectedObligations) {
            throw conflict("The exact transition no longer has every required account obligation");
          }
          var candidateObligation = obligations.getFirst();
          if (candidateObligation.ordinal() != 0
              || candidateObligation.action()
                  != CanonicalGameplayBindingAccountIndexObligation.Action.ADD_OR_RETAIN
              || candidateObligation.executionPhase()
                  != CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.BEFORE_FINAL_CAS
              || !candidateObligation.bindingRef().equals(current.candidate().bindingRef())
              || !candidateObligation.accountId().equals(current.candidate().identity().accountId())
              || !candidateObligation.tenantId().equals(current.candidate().identity().tenantId())
              || !candidateObligation
                  .bindingGeneration()
                  .equals(current.candidate().bindingGeneration())
              || !candidateObligation
                  .accountIndexFence()
                  .equals(current.candidate().accountIndexFence())
              || (candidateObligation.status()
                      != CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED
                  && candidateObligation.status()
                      != CanonicalGameplayBindingAccountIndexObligation.Status.ACKNOWLEDGED)) {
            throw conflict("The account-index candidate obligation differs from durable ownership");
          }
          if (candidateObligation.status()
              == CanonicalGameplayBindingAccountIndexObligation.Status.ACKNOWLEDGED) {
            if (candidateObligation.projectionState()
                    != CanonicalGameplayBindingAccountIndexObligation.ProjectionState
                        .PRESENT_AWAITING_COVERAGE
                || !member.equals(candidateObligation.projectedMember())) {
              throw conflict(
                  "Completed account-index acknowledgement differs from the exact member");
            }
            return projectionReceipt(candidateObligation);
          }
          if (candidateObligation.projectionState()
              == CanonicalGameplayBindingAccountIndexObligation.ProjectionState
                  .PRESENT_AWAITING_COVERAGE) {
            if (!member.equals(candidateObligation.projectedMember())) {
              throw conflict("A prior exact account-index readback retained a different member");
            }
            return projectionReceipt(candidateObligation);
          }
          if (candidateObligation.projectionState()
              != CanonicalGameplayBindingAccountIndexObligation.ProjectionState.REQUIRED) {
            throw conflict(
                "Candidate account-index obligation is in a conflicting projection state");
          }

          BigInteger transitionRevision = current.transition().inventoryRevision();
          BigInteger nextRevision = currentRevision.add(BigInteger.ONE);
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + TRANSITION
                      + " SET inventory_revision = ?"
                      + " WHERE transition_id = ? AND status = 'PROVISIONAL'"
                      + " AND inventory_revision = ? AND candidate_binding_ref = ?"
                      + " AND candidate_binding_generation = ? AND candidate_account_index_fence = ?",
                  decimal(nextRevision),
                  transitionId,
                  decimal(transitionRevision),
                  current.candidate().bindingRef().bytes(),
                  decimal(current.transition().bindingGeneration()),
                  current.transition().candidateAccountIndexFence()),
              "Durable transition changed before account-index readback persistence");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + INVENTORY
                      + " SET inventory_revision = ?"
                      + " WHERE binding_ref = ? AND transition_id = ? AND lifecycle = 'PROVISIONAL'"
                      + " AND binding_generation = ? AND account_index_fence = ?"
                      + " AND account_index_state = 'REPAIR_REQUIRED' AND inventory_revision = ?",
                  decimal(nextRevision),
                  current.candidate().bindingRef().bytes(),
                  transitionId,
                  decimal(current.transition().bindingGeneration()),
                  current.transition().candidateAccountIndexFence(),
                  decimal(transitionRevision)),
              "Durable candidate changed before account-index readback persistence");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + ACCOUNT_OBLIGATION
                      + " SET projection_state = 'PRESENT_AWAITING_COVERAGE',"
                      + " projection_member = ?, projection_inventory_revision = ?, inventory_revision = ?"
                      + " WHERE transition_id = ? AND obligation_ordinal = 0"
                      + " AND action = 'ADD_OR_RETAIN' AND projection_state = 'REQUIRED'"
                      + " AND binding_ref = ? AND account_id = ? AND tenant_id = ?"
                      + " AND binding_generation = ? AND account_index_fence = ?"
                      + " AND inventory_revision = ?",
                  member,
                  decimal(nextRevision),
                  decimal(nextRevision),
                  transitionId,
                  candidateObligation.bindingRef().bytes(),
                  candidateObligation.accountId(),
                  candidateObligation.tenantId(),
                  decimal(candidateObligation.bindingGeneration()),
                  candidateObligation.accountIndexFence(),
                  decimal(transitionRevision)),
              "Durable candidate account-index obligation changed before readback persistence");
          requireCount(
              transaction.execute(
                  "UPDATE "
                      + ACCOUNT_OBLIGATION
                      + " SET inventory_revision = ?"
                      + " WHERE transition_id = ? AND obligation_ordinal = 1"
                      + " AND inventory_revision = ?",
                  decimal(nextRevision),
                  transitionId,
                  decimal(transitionRevision)),
              current.expectedPrior() == null ? 0 : 1,
              "Durable prior account-index obligation changed before readback persistence");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + ISSUER_OBLIGATION
                      + " SET inventory_revision = ?"
                      + " WHERE transition_id = ? AND inventory_revision = ?",
                  decimal(nextRevision),
                  transitionId,
                  decimal(transitionRevision)),
              "Durable issuer obligation changed before account-index readback persistence");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + REGION_OBLIGATION
                      + " SET inventory_revision = ?"
                      + " WHERE transition_id = ? AND inventory_revision = ?",
                  decimal(nextRevision),
                  transitionId,
                  decimal(transitionRevision)),
              "Durable region obligation changed before account-index readback persistence");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + RESERVATION
                      + " SET inventory_revision = ?"
                      + " WHERE reservation_id = ? AND transition_id = ?"
                      + " AND lifecycle = 'RESERVED' AND inventory_revision = ?",
                  decimal(nextRevision),
                  current.transition().issuerReservationId(),
                  transitionId,
                  decimal(transitionRevision)),
              "Durable issuer reservation changed before account-index readback persistence");
          requireOne(
              transaction.execute(
                  "UPDATE game_session_canonical_binding_inventory_clock"
                      + " SET inventory_revision = ? WHERE singleton_id = 1"
                      + " AND inventory_revision = ?",
                  decimal(nextRevision),
                  decimal(currentRevision)),
              "Durable inventory revision compare-and-set lost during account-index readback");
          var saved = loadAccountIndexObligations(transaction, transitionId, false).getFirst();
          return projectionReceipt(saved);
        });
  }

  /**
   * Allocates or returns the immutable binding-local account-member repair operation for one
   * ordinary NEW transition. The account-wide control is positively read and pinned by its epoch;
   * this method never creates or releases an account-wide admission fence.
   */
  CanonicalGameplayAccountMemberRepairOperation beginAccountMemberRepair(UUID transitionId) {
    Objects.requireNonNull(transitionId, "transitionId");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger inventoryRevision = lockInventoryRevision(transaction);
          CanonicalGameplayBindingProvisionalCasEvidence evidence =
              loadProvisionalCasEvidence(transaction, transitionId, true);
          UUID accountId = evidence.candidate().identity().accountId();
          AccountCoverageControl control =
              lockExistingAccountCoverageControl(transaction, accountId);
          requireNoActiveAccountControl(control);
          if (!control.evidence().equals(evidence.transition().accountCoverageEvidence())) {
            throw conflict(
                "Binding transition did not retain the current positively read account-control state");
          }
          readVerifiedLegacyDisposition(transaction, inventoryRevision, true);
          requireOrdinaryNewMemberRepairTransition(evidence);

          Record existing = findMemberRepairOperation(transaction, transitionId, true);
          if (existing != null) {
            CanonicalGameplayAccountMemberRepairOperation operation =
                toMemberRepairOperation(existing);
            if (!operation.accountControlEpoch().equals(control.epoch())) {
              throw conflict("The account-wide control fence advanced after member repair began");
            }
            CanonicalGameplayBindingAccountIndexObligation currentObligation =
                loadAccountIndexObligations(transaction, transitionId, true).getFirst();
            boolean completed = "ACKNOWLEDGED".equals(existing.get("status", String.class));
            requireMemberRepairObligationCurrent(
                operation, evidence.candidate(), currentObligation, completed);
            if (completed) {
              toMemberAcknowledgement(existing);
            }
            return operation;
          }

          CanonicalGameplayBindingAccountIndexObligation originalObligation =
              loadAccountIndexObligations(transaction, transitionId, true).getFirst();
          if (originalObligation.status()
                  != CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED
              || evidence.candidate().accountIndexState()
                  != CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED) {
            throw conflict(
                "A new member repair requires the original unresolved account obligation");
          }

          UUID operationId = freshOperationId(transitionId);
          UUID coverageFence =
              freshDistinctCoverageFence(
                  operationId, transitionId, evidence.candidate().accountIndexFence());
          BigInteger operationFence = allocateMemberRepairFence(transaction, accountId);
          String member = CanonicalGameplayAccountIndexMember.of(evidence.candidate()).value();
          CanonicalGameplayAccountMemberRepairOperation operation =
              new CanonicalGameplayAccountMemberRepairOperation(
                  transitionId,
                  operationId,
                  operationFence,
                  coverageFence,
                  inventoryRevision,
                  evidence.candidate().bindingRef(),
                  accountId,
                  evidence.candidate().identity().tenantId(),
                  evidence.candidate().bindingGeneration(),
                  evidence.candidate().accountIndexFence(),
                  originalObligation,
                  member,
                  control.epoch());
          insertMemberRepairOperation(transaction, operation);
          return operation;
        });
  }

  /**
   * Commits only an actual Redis write/read result bound to the stored original operation. The
   * durable operation, current obligation, account-control epoch, readback, and later evidence
   * generation are compared or written in one PostgreSQL transaction.
   */
  CanonicalGameplayAccountMemberAcknowledgement acknowledgeAccountMemberRepair(
      CanonicalGameplayAccountMemberRepairOperation expected,
      CanonicalGameplayAccountMemberRepairRedisOwner.Readback actual) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(actual, "actual");
    if (!expected.equals(actual.operation()) || !expected.member().equals(actual.member())) {
      throw conflict("Redis readback is not bound to the exact expected member repair operation");
    }
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger currentRevision = lockInventoryRevision(transaction);
          lockLeaseBoundRuntimeBeforeTransition(
              transaction, expected.transitionId(), expected.bindingRef().bytes(), null);
          AccountCoverageControl control =
              lockExistingAccountCoverageControl(transaction, expected.accountId());
          requireNoActiveAccountControl(control);
          if (!control.epoch().equals(expected.accountControlEpoch())) {
            throw conflict("The account-wide control fence changed during member repair");
          }
          readVerifiedLegacyDisposition(transaction, currentRevision, true);
          Record operationRow =
              findMemberRepairOperation(transaction, expected.transitionId(), true);
          if (operationRow == null) {
            throw conflict("The exact durable account-member repair operation is missing");
          }
          CanonicalGameplayAccountMemberRepairOperation stored =
              toMemberRepairOperation(operationRow);
          if (!stored.equals(expected)
              || !stored.equals(actual.operation())
              || !stored.member().equals(actual.member())) {
            throw conflict("Expected and actual member repair differ from the durable owner tuple");
          }
          CanonicalGameplayBindingProvisionalCasEvidence current =
              loadProvisionalCasEvidence(transaction, expected.transitionId(), true);
          requireOrdinaryNewMemberRepairTransition(current);
          CanonicalGameplayBindingAccountIndexObligation currentObligation =
              loadAccountIndexObligations(transaction, expected.transitionId(), true).getFirst();
          boolean completed = "ACKNOWLEDGED".equals(operationRow.get("status", String.class));
          requireMemberRepairObligationCurrent(
              stored, current.candidate(), currentObligation, completed);
          if (!current.transition().accountCoverageEvidence().equals(control.evidence())) {
            throw conflict(
                "Transition account-wide state no longer matches the pinned control row");
          }
          String exactMember = CanonicalGameplayAccountIndexMember.of(current.candidate()).value();
          if (!exactMember.equals(actual.member())) {
            throw conflict("Redis readback member differs from the exact durable candidate tuple");
          }
          if (completed) {
            return toMemberAcknowledgement(operationRow);
          }

          BigInteger nextRevision = currentRevision.add(BigInteger.ONE);
          BigInteger coverageGeneration =
              allocateMemberRepairCoverageGeneration(
                  transaction, stored.accountId(), stored.inventorySnapshotRevision());
          if (coverageGeneration.compareTo(stored.inventorySnapshotRevision()) <= 0) {
            throw conflict("Binding-local evidence generation must advance beyond its snapshot");
          }
          persistMemberRepairAcknowledgement(
              transaction, stored, current, nextRevision, coverageGeneration, actual.member());
          return new CanonicalGameplayAccountMemberAcknowledgement(
              stored, coverageGeneration, actual.member());
        });
  }

  /**
   * Records exact Redis provisional readback for this operation. This advances only the durable
   * preparation lifecycle; it does not acknowledge external indexes, the region bridge, or admit
   * the candidate.
   */
  CanonicalGameplayBindingTransitionSnapshot markProvisional(
      CanonicalGameplayBindingProvisionalCasEvidence expectedEvidence) {
    return markProvisional(expectedEvidence, null);
  }

  /**
   * Records exact Redis lease-bound provisional readback and its unchanged Account lease plus
   * point-in-time route evidence in one transition/inventory revision compare-and-set.
   */
  CanonicalGameplayBindingTransitionSnapshot markProvisional(
      CanonicalGameplayBindingProvisionalCasEvidence expectedEvidence,
      CanonicalGameplayAdmissionDecision expectedDecision) {
    return markProvisional(expectedEvidence, expectedDecision, null, null);
  }

  private CanonicalGameplayBindingTransitionSnapshot markProvisional(
      CanonicalGameplayBindingProvisionalCasEvidence expectedEvidence,
      CanonicalGameplayAdmissionDecision expectedDecision,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayOriginalNewCandidateRedisOwner.PhysicalReadback readback) {
    Objects.requireNonNull(expectedEvidence, "expectedEvidence");
    if ((candidate == null) != (readback == null)) {
      throw new IllegalArgumentException("Candidate and owner readback must be supplied together");
    }
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger currentRevision = lockInventoryRevision(transaction);
          if (expectedDecision != null) {
            lockLeaseBoundRuntimeBeforeTransition(
                transaction,
                expectedEvidence.transition().transitionId(),
                expectedEvidence.candidate().bindingRef().bytes(),
                expectedDecision.targetEvidence());
          }
          CanonicalGameplayBindingProvisionalCasEvidence current =
              loadProvisionalCasEvidence(
                  transaction, expectedEvidence.transition().transitionId(), true);
          requireAccountCoverageEvidenceCurrent(
              transaction,
              current.candidate().identity().accountId(),
              current.transition().accountCoverageEvidence());
          requireSameProvisionalOperation(expectedEvidence, current);
          if (current.expectedPrior() == null && expectedDecision == null) {
            throw conflict(
                "First-binding provisional publication requires the exact Account lease decision");
          }
          boolean originalNew = current.expectedPrior() == null && expectedDecision != null;
          if (originalNew && candidate != null) {
            if (readback == null) throw conflict("Original NEW readback source is unavailable");
            requireDecisionMatchesOperation(expectedDecision, current);
            CanonicalGameplayOriginalNewCandidate actualCandidate;
            try {
              actualCandidate =
                  CanonicalGameplayOriginalNewCandidate.fromOriginalSources(
                      current, expectedDecision);
            } catch (RuntimeException invalidSource) {
              throw conflict("Original NEW candidate sources are not exact");
            }
            if (!actualCandidate.equals(candidate)
                || !candidate.matchesOriginalDecision(expectedDecision)
                || !candidate.matchesProvisionalSources(current)
                || !readback.matches(candidate, current)) {
              throw conflict("Owner readback does not bind the exact original NEW source");
            }
            Record installation = findTransition(transaction, current.transition().transitionId());
            byte[] storedCandidate = installation.get("original_new_candidate_bytes", byte[].class);
            String storedCandidateDigest =
                installation.get("original_new_candidate_digest", String.class);
            String installationState =
                installation.get("original_new_candidate_state", String.class);
            String storedReadbackDigest =
                installation.get("original_new_candidate_readback_digest", String.class);
            if (storedCandidate == null
                || !Arrays.equals(storedCandidate, candidate.canonicalBytes())
                || !Objects.equals(
                    storedCandidateDigest,
                    CanonicalGameplayIssuerMemberV2Codec.sha256(storedCandidate))) {
              throw conflict("Original NEW candidate bytes differ from the retained SQL source");
            }
            if ("READBACK_CONFIRMED".equals(installationState)) {
              if (!Objects.equals(storedReadbackDigest, readback.digest())) {
                throw conflict("Original NEW candidate has a different retained physical readback");
              }
            } else if ("DISPATCH_POSSIBLE".equals(installationState)
                && current.transition().status()
                    == CanonicalGameplayBindingTransitionSnapshot.Status.PREPARED) {
              requireOne(
                  transaction.execute(
                      "UPDATE "
                          + TRANSITION
                          + " SET original_new_candidate_state = 'READBACK_CONFIRMED',"
                          + " original_new_candidate_readback_digest = ?"
                          + " WHERE transition_id = ? AND status = 'PREPARED'"
                          + " AND original_new_candidate_bytes = ?"
                          + " AND original_new_candidate_digest = ?"
                          + " AND original_new_candidate_state = 'DISPATCH_POSSIBLE'"
                          + " AND original_new_candidate_readback_digest IS NULL",
                      readback.digest(),
                      current.transition().transitionId(),
                      candidate.canonicalBytes(),
                      storedCandidateDigest),
                  "Original NEW candidate readback source changed before provisional CAS");
            } else {
              throw conflict("Original NEW candidate is not in a readback-confirmable state");
            }
          } else if (candidate != null || readback != null) {
            throw conflict("Original NEW candidate readback cannot authorize a transfer");
          }
          if (current.transition().status()
              == CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL) {
            Record row = findTransition(transaction, current.transition().transitionId());
            if (expectedDecision == null) {
              if (row.get("admission_lease_id", UUID.class) != null) {
                throw conflict("A lease-bound decision cannot be retried through legacy publish");
              }
            } else {
              CanonicalGameplayAdmissionDecision stored = toAdmissionDecision(row, current);
              requireSameAdmissionDecision(expectedDecision, stored);
            }
            return current.transition();
          }
          if (current.transition().status()
              != CanonicalGameplayBindingTransitionSnapshot.Status.PREPARED) {
            throw conflict("Only the exact PREPARED operation can become PROVISIONAL");
          }

          BigInteger priorRevision = current.transition().inventoryRevision();
          BigInteger nextRevision = currentRevision.add(BigInteger.ONE);
          if (expectedDecision != null) {
            requireDecisionMatchesOperation(expectedDecision, current);
          }
          int transitionChanged =
              expectedDecision == null
                  ? updateLegacyProvisionalTransition(
                      transaction, current, priorRevision, nextRevision)
                  : updateLeaseBoundProvisionalTransition(
                      transaction, current, expectedDecision, priorRevision, nextRevision);
          requireOne(
              transitionChanged, "Durable transition compare-and-set lost its exact prepared row");
          int candidateChanged =
              transaction.execute(
                  "UPDATE "
                      + INVENTORY
                      + " SET lifecycle = 'PROVISIONAL', inventory_revision = ?"
                      + " WHERE binding_ref = ? AND transition_id = ?"
                      + " AND lifecycle = 'CANDIDATE_PREPARED'"
                      + " AND binding_generation = ? AND account_index_fence = ?"
                      + " AND account_index_state = 'REPAIR_REQUIRED'"
                      + " AND issuer_index_state = 'REPAIR_REQUIRED' AND inventory_revision = ?",
                  decimal(nextRevision),
                  current.candidate().bindingRef().bytes(),
                  current.transition().transitionId(),
                  decimal(current.transition().bindingGeneration()),
                  current.transition().candidateAccountIndexFence(),
                  decimal(priorRevision));
          requireOne(
              candidateChanged, "Durable candidate compare-and-set lost its exact prepared row");
          requireCount(
              transaction.execute(
                  "UPDATE "
                      + ACCOUNT_OBLIGATION
                      + " SET inventory_revision = ?"
                      + " WHERE transition_id = ? AND inventory_revision = ?",
                  decimal(nextRevision),
                  current.transition().transitionId(),
                  decimal(priorRevision)),
              current.expectedPrior() == null ? 1 : 2,
              "Durable account-index obligations changed during provisional publication");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + ISSUER_OBLIGATION
                      + " SET inventory_revision = ?"
                      + " WHERE transition_id = ? AND inventory_revision = ?",
                  decimal(nextRevision),
                  current.transition().transitionId(),
                  decimal(priorRevision)),
              "Durable issuer-index obligation changed during provisional publication");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + REGION_OBLIGATION
                      + " SET inventory_revision = ?"
                      + " WHERE transition_id = ? AND inventory_revision = ?",
                  decimal(nextRevision),
                  current.transition().transitionId(),
                  decimal(priorRevision)),
              "Durable region-bridge obligation changed during provisional publication");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + RESERVATION
                      + " SET inventory_revision = ?"
                      + " WHERE reservation_id = ? AND transition_id = ?"
                      + " AND lifecycle = 'RESERVED' AND inventory_revision = ?",
                  decimal(nextRevision),
                  current.transition().issuerReservationId(),
                  current.transition().transitionId(),
                  decimal(priorRevision)),
              "Durable issuer reservation changed during provisional publication");
          requireOne(
              transaction.execute(
                  "UPDATE game_session_canonical_binding_inventory_clock"
                      + " SET inventory_revision = ? WHERE singleton_id = 1"
                      + " AND inventory_revision = ?",
                  decimal(nextRevision),
                  decimal(currentRevision)),
              "Durable inventory revision compare-and-set lost during provisional publication");
          return loadProvisionalCasEvidence(
                  transaction, expectedEvidence.transition().transitionId(), true)
              .transition();
        });
  }

  /**
   * Accepts only the final owner's separately obtained physical six-key readback. Remote current
   * sources and admission barriers are checked by the coordinator outside this SQL transaction;
   * this transaction records their exact original NEW operation, not cross-store atomicity.
   */
  CanonicalGameplayBindingTransitionSnapshot commitOriginalNewFinalAdmission(
      CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.Readback actual) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(actual, "actual");
    var source = actual.source();
    var candidate = source.candidate();
    if (!candidate.equals(actual.session().originalCandidate())
        || source.candidateInstallationProvenance()
            != OriginalNewCandidateInstallationProvenance.HISTORICAL_READBACK_CONFIRMED
        || !source
            .candidateDigest()
            .equals(CanonicalGameplayIssuerMemberV2Codec.sha256(candidate.canonicalBytes()))
        || !Arrays.equals(actual.exactCanonicalBytes(), actual.session().canonicalBytes())
        || !CanonicalGameplayIssuerMemberV2Codec.sha256(actual.exactCanonicalBytes())
            .substring(7)
            .equals(actual.finalSessionSha256())
        || actual.admissionAtMillis() != actual.session().admissionAtMillis()
        || actual.continuityExpiresAtMillis() != actual.session().continuityExpiresAtMillis()
        || actual.canonicalSessionDurationMillis()
            != actual.session().canonicalSessionDurationMillis()
        || actual.admissionAtMillis() <= 0
        || actual.canonicalSessionDurationMillis() <= 0
        || actual.canonicalSessionDurationMillis() > 300_000
        || actual.admissionAtMillis() > Long.MAX_VALUE - actual.canonicalSessionDurationMillis()
        || actual.continuityExpiresAtMillis()
            != actual.admissionAtMillis() + actual.canonicalSessionDurationMillis()) {
      throw conflict("Final controller readback differs from its authenticated original NEW");
    }
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BigInteger revision = lockInventoryRevision(transaction);
          requireNoActiveAccountControl(
              lockExistingAccountCoverageControl(
                  transaction, candidate.bindingIdentity().accountId()));
          lockLeaseBoundRuntimeBeforeTransition(
              transaction,
              candidate.transition().transitionId(),
              candidate.bindingIdentity().bindingRef().bytes(),
              candidate.targetEvidence());
          Record row =
              transaction.fetchOne(
                  "SELECT * FROM " + TRANSITION + " WHERE transition_id = ? FOR UPDATE",
                  candidate.transition().transitionId());
          if (row == null) throw conflict("Original NEW transition is missing");
          boolean committed = "COMMITTED".equals(row.get("status", String.class));
          if (!committed && !"PROVISIONAL".equals(row.get("status", String.class))) {
            throw conflict("Only an exact provisional or committed original NEW can finalize");
          }
          Record inventoryRow =
              transaction.fetchOne(
                  "SELECT * FROM " + INVENTORY + " WHERE binding_ref = ? FOR UPDATE",
                  candidate.bindingIdentity().bindingRef().bytes());
          Record reservationRow =
              transaction.fetchOne(
                  "SELECT * FROM " + RESERVATION + " WHERE reservation_id = ? FOR UPDATE",
                  candidate.reservation().reservationId());
          if (inventoryRow == null || reservationRow == null) {
            throw conflict("Original NEW candidate or reservation is missing");
          }
          var entry = toInventoryEntry(inventoryRow);
          var reservation = toReservation(reservationRow);
          var transition = toTransitionSnapshot(row, inventoryRow, reservationRow);
          var decision = toAdmissionDecision(row, transition, entry);
          if (transition.expectedPriorBindingRef() != null
              || transition.expectedPriorBindingGeneration() != null
              || !candidate.matchesOriginalDecision(decision)
              || !Arrays.equals(
                  row.get("candidate_binding_ref", byte[].class),
                  candidate.transition().bindingRef().bytes())
              || !transition.bindingGeneration().equals(candidate.transition().bindingGeneration())
              || !Objects.equals(
                  row.get("issuer_reservation_id", UUID.class),
                  candidate.transition().issuerReservationId())
              || !transition.reservationFence().equals(candidate.transition().reservationFence())
              || !transition
                  .candidateAccountIndexFence()
                  .equals(candidate.transition().candidateAccountIndexFence())
              || transition.inventoryRevision().compareTo(revision) > 0
              || !candidate.bindingIdentity().equals(entry.identity())
              || !candidate.transition().bindingGeneration().equals(entry.bindingGeneration())
              || !candidate.transition().transitionId().equals(entry.transitionId())
              || !candidate
                  .transition()
                  .candidateAccountIndexFence()
                  .equals(entry.accountIndexFence())
              || !candidate.transition().issuerReservationId().equals(entry.issuerReservationId())
              || !candidate
                  .reservation()
                  .equals(CanonicalGameplayOriginalNewCandidateCodec.reservationSource(reservation))
              || !reservation.owner().equals(entry.identity())
              || !entry.inventoryRevision().equals(transition.inventoryRevision())
              || !reservation.inventoryRevision().equals(transition.inventoryRevision())
              || entry.lifecycle()
                  != (committed
                      ? CanonicalGameplayBindingInventoryEntry.Lifecycle.ACTIVE
                      : CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL)
              || reservation.lifecycle()
                  != (committed
                      ? CanonicalIssuerPartitionReservation.Lifecycle.BOUND
                      : CanonicalIssuerPartitionReservation.Lifecycle.RESERVED)
              || entry.accountIndexState()
                  != CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED
              || entry.issuerIndexState()
                  != (committed
                      ? CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED
                      : CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED)
              || !"READBACK_CONFIRMED".equals(row.get("original_new_candidate_state", String.class))
              || !Arrays.equals(
                  candidate.canonicalBytes(), row.get("original_new_candidate_bytes", byte[].class))
              || !CanonicalGameplayIssuerMemberV2Codec.sha256(candidate.canonicalBytes())
                  .equals(row.get("original_new_candidate_digest", String.class))
              || !source
                  .candidateDigest()
                  .equals(row.get("original_new_candidate_digest", String.class))
              || !source
                  .originalCandidateReadbackDigest()
                  .equals(row.get("original_new_candidate_readback_digest", String.class))
              || (source.transitionStatus()
                      == CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED
                  && !committed)
              || BigInteger.valueOf(actual.admissionAtMillis())
                      .compareTo(decision.leaseExpiresAtEpochMillis())
                  >= 0) {
            throw conflict("Final controller source differs from the retained original NEW");
          }
          if (!committed) {
            var evidence = loadProvisionalCasEvidence(transaction, transition.transitionId(), true);
            if (!candidate.matchesProvisionalSources(evidence)) {
              throw conflict("Final readback lost its exact provisional source");
            }
          }
          var obligations =
              loadAccountIndexObligations(transaction, transition.transitionId(), true);
          if (obligations.size() != 1)
            throw conflict("Original NEW account obligation is not exact");
          requireCompletedMemberRepairAcknowledgement(
              transaction, transition, entry, obligations.getFirst());
          CanonicalGameplayIssuerBindingLocalAcknowledgement issuerAcknowledgement =
              requireOriginalNewFinalIssuerAcknowledgement(
                  transaction, candidate, transition, committed);
          if (!Arrays.equals(
              source.issuerAcknowledgement().exactBytes(), issuerAcknowledgement.exactBytes())) {
            throw conflict(
                "Final issuer acknowledgement differs from the retained original source");
          }
          requireOriginalNewFinalRegionAcknowledgement(transaction, candidate, transition, entry);
          if (committed) {
            requireOriginalNewFinalReceipt(row, actual, decision);
            return transition;
          }
          requireNoOriginalNewFinalAdmissionReceipt(row);
          BigInteger next = revision.add(BigInteger.ONE);
          BigDecimal prior = decimal(transition.inventoryRevision());
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + TRANSITION
                      + " SET status = 'COMMITTED', inventory_revision = ?,"
                      + " original_new_final_session_sha256 = ?, original_new_admission_at_ms = ?,"
                      + " original_new_continuity_expires_at_ms = ?, original_new_session_duration_ms = ?"
                      + " WHERE transition_id = ? AND status = 'PROVISIONAL' AND inventory_revision = ?",
                  decimal(next),
                  actual.finalSessionSha256(),
                  actual.admissionAtMillis(),
                  actual.continuityExpiresAtMillis(),
                  actual.canonicalSessionDurationMillis(),
                  transition.transitionId(),
                  prior),
              "Original NEW final transition CAS changed");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + INVENTORY
                      + " SET lifecycle = 'ACTIVE', issuer_index_state = 'ACKNOWLEDGED', inventory_revision = ?"
                      + " WHERE binding_ref = ? AND transition_id = ? AND lifecycle = 'PROVISIONAL'"
                      + " AND account_index_state = 'ACKNOWLEDGED' AND issuer_index_state = 'REPAIR_REQUIRED'"
                      + " AND inventory_revision = ?",
                  decimal(next),
                  entry.bindingRef().bytes(),
                  transition.transitionId(),
                  prior),
              "Original NEW final inventory CAS changed");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + ACCOUNT_OBLIGATION
                      + " SET inventory_revision = ?"
                      + " WHERE transition_id = ? AND obligation_ordinal = 0 AND status = 'ACKNOWLEDGED'"
                      + " AND inventory_revision = ?",
                  decimal(next),
                  transition.transitionId(),
                  prior),
              "Original NEW account receipt changed");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + ISSUER_OBLIGATION
                      + " SET status = 'ACKNOWLEDGED', inventory_revision = ?"
                      + " WHERE transition_id = ? AND status = 'REQUIRED' AND inventory_revision = ?",
                  decimal(next),
                  transition.transitionId(),
                  prior),
              "Original NEW issuer receipt changed");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + REGION_OBLIGATION
                      + " SET inventory_revision = ?"
                      + " WHERE transition_id = ? AND status = 'ACKNOWLEDGED' AND inventory_revision = ?",
                  decimal(next),
                  transition.transitionId(),
                  prior),
              "Original NEW region receipt changed");
          requireOne(
              transaction.execute(
                  "UPDATE "
                      + RESERVATION
                      + " SET lifecycle = 'BOUND', inventory_revision = ?"
                      + " WHERE reservation_id = ? AND transition_id = ? AND lifecycle = 'RESERVED'"
                      + " AND inventory_revision = ?",
                  decimal(next),
                  reservation.reservationId(),
                  transition.transitionId(),
                  prior),
              "Original NEW reservation CAS changed");
          bumpInventoryClock(transaction, revision, next);
          return toTransitionSnapshot(
              findTransition(transaction, transition.transitionId()),
              transaction.fetchOne(
                  "SELECT * FROM " + INVENTORY + " WHERE binding_ref = ?",
                  entry.bindingRef().bytes()),
              transaction.fetchOne(
                  "SELECT * FROM " + RESERVATION + " WHERE reservation_id = ?",
                  reservation.reservationId()));
        });
  }

  private static void requireOriginalNewFinalReceipt(
      Record row,
      CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.Readback actual,
      CanonicalGameplayAdmissionDecision decision) {
    OriginalNewFinalAdmissionReceipt receipt = readOriginalNewFinalAdmissionReceipt(row, decision);
    if (!receipt.finalSessionSha256().equals(actual.finalSessionSha256())
        || receipt.admissionAtMillis() != actual.admissionAtMillis()
        || receipt.continuityExpiresAtMillis() != actual.continuityExpiresAtMillis()
        || receipt.sessionDurationMillis() != actual.canonicalSessionDurationMillis()) {
      throw conflict("Committed original NEW has no identical immutable final readback receipt");
    }
  }

  private static OriginalNewFinalAdmissionReceipt readOriginalNewFinalAdmissionReceipt(
      Record row, CanonicalGameplayAdmissionDecision decision) {
    return readOriginalNewFinalAdmissionReceipt(row, decision.leaseExpiresAtEpochMillis());
  }

  private static OriginalNewFinalAdmissionReceipt readOriginalNewFinalAdmissionReceipt(
      Record row, BigInteger leaseExpiresAtEpochMillis) {
    String digest = row.get("original_new_final_session_sha256", String.class);
    Long admissionAt = row.get("original_new_admission_at_ms", Long.class);
    Long continuityExpiresAt = row.get("original_new_continuity_expires_at_ms", Long.class);
    Long duration = row.get("original_new_session_duration_ms", Long.class);
    if (digest == null
        || admissionAt == null
        || continuityExpiresAt == null
        || duration == null
        || BigInteger.valueOf(admissionAt).compareTo(leaseExpiresAtEpochMillis) >= 0) {
      throw conflict("Committed original NEW has no valid immutable final receipt");
    }
    try {
      return new OriginalNewFinalAdmissionReceipt(
          digest, admissionAt, continuityExpiresAt, duration);
    } catch (IllegalArgumentException invalid) {
      throw conflict("Committed original NEW final receipt is malformed");
    }
  }

  private static void requireNoOriginalNewFinalAdmissionReceipt(Record row) {
    if (row.get("original_new_final_session_sha256", String.class) != null
        || row.get("original_new_admission_at_ms", Long.class) != null
        || row.get("original_new_continuity_expires_at_ms", Long.class) != null
        || row.get("original_new_session_duration_ms", Long.class) != null) {
      throw conflict("Provisional NEW already contains a final receipt");
    }
  }

  private static CanonicalGameplayBindingRegionBridgeObligation
      requireOriginalNewFinalRegionAcknowledgement(
          DSLContext transaction,
          CanonicalGameplayOriginalNewCandidate candidate,
          CanonicalGameplayBindingTransitionSnapshot transition,
          CanonicalGameplayBindingInventoryEntry entry) {
    Record regionRow =
        transaction.fetchOne(
            "SELECT * FROM " + REGION_OBLIGATION + " WHERE transition_id = ? FOR UPDATE",
            transition.transitionId());
    if (regionRow == null) throw conflict("Original NEW region acknowledgement is missing");
    var region = toRegionObligation(regionRow);
    var identity = candidate.bindingIdentity();
    if (region.status() != CanonicalGameplayBindingRegionBridgeObligation.Status.ACKNOWLEDGED
        || !region.transitionId().equals(transition.transitionId())
        || !region.bindingRef().equals(identity.bindingRef())
        || !region.bindingGeneration().equals(entry.bindingGeneration())
        || !region.accountId().equals(identity.accountId())
        || !region.tenantId().equals(identity.tenantId())
        || !region.gameInstanceId().equals(identity.gameInstanceId())
        || region.runtimeGameInstanceId() != identity.runtimeGameInstanceId()
        || !region.sessionId().equals(identity.sessionId())
        || !region.regionId().equals(identity.regionId())
        || !region.regionEpoch().equals(identity.regionEpoch())
        || !region.inventoryRevision().equals(transition.inventoryRevision())) {
      throw conflict("Original NEW region acknowledgement differs from its controller");
    }
    return region;
  }

  private static CanonicalGameplayIssuerBindingLocalAcknowledgement
      requireOriginalNewFinalIssuerAcknowledgement(
          DSLContext transaction,
          CanonicalGameplayOriginalNewCandidate candidate,
          CanonicalGameplayBindingTransitionSnapshot transition,
          boolean committed) {
    Record obligation =
        transaction.fetchOne(
            "SELECT * FROM " + ISSUER_OBLIGATION + " WHERE transition_id = ? FOR UPDATE",
            transition.transitionId());
    if (obligation == null
        || !Arrays.equals(
            candidate.bindingIdentity().bindingRef().bytes(),
            obligation.get("binding_ref", byte[].class))
        || !candidate
            .transition()
            .bindingGeneration()
            .equals(readBigInteger(obligation, "binding_generation"))
        || !candidate
            .reservation()
            .reservationId()
            .equals(obligation.get("reservation_id", UUID.class))
        || !(committed ? "ACKNOWLEDGED" : "REQUIRED").equals(obligation.get("status", String.class))
        || !transition
            .inventoryRevision()
            .equals(readBigInteger(obligation, "inventory_revision"))) {
      throw conflict("Original NEW issuer obligation changed");
    }
    Record receipt =
        transaction.fetchOne(
            "SELECT * FROM game_session_canonical_issuer_binding_local_acknowledgement"
                + " WHERE transition_id = ? FOR SHARE",
            transition.transitionId());
    if (receipt == null) throw conflict("Original NEW has no native issuer acknowledgement");
    CanonicalGameplayIssuerBindingLocalAcknowledgement acknowledgement =
        CanonicalGameplayIssuerBindingLocalAcknowledgementRepository.readAcknowledgement(
            transaction, receipt);
    var evidence = acknowledgement.evidence();
    var reservation = candidate.reservation();
    // The decoder above checks the issuer-local request against its actual local operation.
    // Account's independent admission request is not that issuer-local request identity.
    if (!evidence.transitionId().equals(transition.transitionId())
        || !evidence
            .candidateAccountIndexFence()
            .equals(candidate.transition().candidateAccountIndexFence())
        || !evidence.issuerId().equals(candidate.bindingIdentity().issuerId())
        || !Arrays.equals(evidence.bindingRef(), candidate.bindingIdentity().bindingRef().bytes())
        || !evidence.bindingGeneration().equals(candidate.transition().bindingGeneration())
        || !evidence.reservation().reservationId().equals(reservation.reservationId())
        || !evidence.reservation().transitionId().equals(reservation.transitionId())
        || !evidence.reservation().reservationFence().equals(reservation.reservationFence())
        || !evidence.reservation().partitionId().equals(reservation.partitionId())
        || !"RESERVED".equals(evidence.reservation().lifecycle())
        || !evidence
            .reservation()
            .issuerCoverageOperationId()
            .equals(reservation.issuerCoverageOperationId())
        || !evidence
            .reservation()
            .issuerCoverageOperationFence()
            .equals(reservation.issuerCoverageOperationFence())
        || !evidence.reservation().coverageFence().equals(reservation.coverageFence())
        || !evidence
            .reservation()
            .inventorySnapshotRevision()
            .equals(reservation.inventorySnapshotRevision())
        || evidence.reservation().inventoryRevision().compareTo(transition.inventoryRevision())
            > 0) {
      throw conflict("Native issuer acknowledgement differs from the original NEW source");
    }
    return acknowledgement;
  }

  private static int updateLegacyProvisionalTransition(
      DSLContext transaction,
      CanonicalGameplayBindingProvisionalCasEvidence current,
      BigInteger priorRevision,
      BigInteger nextRevision) {
    String expectedPriorPredicate = expectedPriorPredicate(current);
    List<Object> values = new ArrayList<>();
    values.add(decimal(nextRevision));
    values.add(current.transition().transitionId());
    values.add(decimal(priorRevision));
    values.add(current.candidate().bindingRef().bytes());
    values.add(decimal(current.transition().bindingGeneration()));
    values.add(current.transition().candidateAccountIndexFence());
    addExpectedPriorValues(values, current);
    return transaction.execute(
        "UPDATE "
            + TRANSITION
            + " SET status = 'PROVISIONAL', inventory_revision = ?"
            + " WHERE transition_id = ? AND status = 'PREPARED'"
            + " AND inventory_revision = ? AND candidate_binding_ref = ?"
            + " AND candidate_binding_generation = ?"
            + " AND candidate_account_index_fence = ? "
            + expectedPriorPredicate
            + " AND admission_request_id IS NULL AND admission_lease_id IS NULL",
        values.toArray());
  }

  private static String expectedPriorPredicate(
      CanonicalGameplayBindingProvisionalCasEvidence current) {
    return current.expectedPrior() == null
        ? "AND expected_prior_binding_ref IS NULL AND expected_prior_binding_generation IS NULL"
        : "AND expected_prior_binding_ref = ? AND expected_prior_binding_generation = ?";
  }

  private static void addExpectedPriorValues(
      List<Object> values, CanonicalGameplayBindingProvisionalCasEvidence current) {
    if (current.expectedPrior() != null) {
      values.add(current.expectedPrior().bindingRef().bytes());
      values.add(decimal(current.expectedPrior().bindingGeneration()));
    }
  }

  private static void lockLeaseBoundRuntimeBeforeTransition(
      DSLContext transaction,
      UUID transitionId,
      byte[] candidateBindingRef,
      CanonicalPlayableTarget expectedTarget) {
    // Keep the runtime-before-transition lock order: resolve without row locks, then acquire only
    // the exact candidate runtime row before CAS evidence locks the transition. The V34 trigger
    // rechecks the complete launch tuple when the transition is updated.
    Record targetLink =
        transaction.fetchOne(
            "SELECT transition.status, transition.admission_lease_id,"
                + " transition.admission_target_tenant_id,"
                + " transition.admission_target_game_session_tenant_id,"
                + " transition.admission_target_game_instance_id,"
                + " transition.admission_target_canonical_game_instance_id,"
                + " candidate.tenant_id AS candidate_tenant_id,"
                + " candidate.runtime_game_instance_id,"
                + " candidate.game_instance_id AS candidate_game_instance_id,"
                + " launch.canonical_tenant_id AS launch_canonical_tenant_id,"
                + " launch.game_session_tenant_id,"
                + " launch.game_instance_id AS launch_game_instance_id,"
                + " launch.game_instance_uuid AS launch_game_instance_uuid"
                + " FROM "
                + TRANSITION
                + " transition JOIN "
                + INVENTORY
                + " candidate ON candidate.binding_ref = transition.candidate_binding_ref"
                + " JOIN game_session_canonical_instance_launch launch"
                + " ON launch.canonical_tenant_id = candidate.tenant_id"
                + " AND launch.game_instance_id = candidate.runtime_game_instance_id"
                + " AND launch.game_instance_uuid = candidate.game_instance_id"
                + " WHERE transition.transition_id = ? AND candidate.binding_ref = ?",
            transitionId,
            candidateBindingRef);
    if (targetLink == null) {
      if (expectedTarget != null) {
        throw conflict("Lease-bound decision has no exact candidate launch identity");
      }
      return;
    }

    if (expectedTarget == null) {
      String status = targetLink.get("status", String.class);
      if (targetLink.get("admission_lease_id", UUID.class) == null
          || (!"PROVISIONAL".equals(status) && !"AMBIGUOUS".equals(status))) {
        return;
      }
    }

    UUID canonicalTenantId =
        expectedTarget == null
            ? targetLink.get("admission_target_tenant_id", UUID.class)
            : expectedTarget.canonicalTenantId();
    Long gameSessionTenantId =
        expectedTarget == null
            ? targetLink.get("admission_target_game_session_tenant_id", Long.class)
            : expectedTarget.gameSessionTenantId();
    Long gameInstanceId =
        expectedTarget == null
            ? targetLink.get("admission_target_game_instance_id", Long.class)
            : expectedTarget.gameInstanceId();
    UUID canonicalGameInstanceId =
        expectedTarget == null
            ? targetLink.get("admission_target_canonical_game_instance_id", UUID.class)
            : expectedTarget.canonicalGameInstanceId();

    if (canonicalTenantId == null
        || gameSessionTenantId == null
        || gameInstanceId == null
        || canonicalGameInstanceId == null
        || !Objects.equals(canonicalTenantId, targetLink.get("candidate_tenant_id", UUID.class))
        || !Objects.equals(
            canonicalTenantId, targetLink.get("launch_canonical_tenant_id", UUID.class))
        || !Objects.equals(
            gameSessionTenantId, targetLink.get("game_session_tenant_id", Long.class))
        || !Objects.equals(gameInstanceId, targetLink.get("runtime_game_instance_id", Long.class))
        || !Objects.equals(gameInstanceId, targetLink.get("launch_game_instance_id", Long.class))
        || !Objects.equals(
            canonicalGameInstanceId, targetLink.get("candidate_game_instance_id", UUID.class))
        || !Objects.equals(
            canonicalGameInstanceId, targetLink.get("launch_game_instance_uuid", UUID.class))) {
      throw conflict("Lease-bound decision does not identify the exact candidate runtime row");
    }

    Record runtime =
        transaction.fetchOne(
            "SELECT id FROM game_instances"
                + " WHERE tenant_id = ? AND id = ? AND game_instance_uuid = ? FOR UPDATE",
            gameSessionTenantId,
            gameInstanceId,
            canonicalGameInstanceId);
    if (runtime == null) {
      throw conflict("Lease-bound decision has no exact current Game Instance row");
    }
  }

  private static int updateLeaseBoundProvisionalTransition(
      DSLContext transaction,
      CanonicalGameplayBindingProvisionalCasEvidence current,
      CanonicalGameplayAdmissionDecision decision,
      BigInteger priorRevision,
      BigInteger nextRevision) {
    List<Object> values = new ArrayList<>();
    values.add(decimal(nextRevision));
    values.addAll(admissionDecisionValues(decision));
    values.add(current.transition().transitionId());
    values.add(decimal(priorRevision));
    values.add(current.candidate().bindingRef().bytes());
    values.add(decimal(current.transition().bindingGeneration()));
    values.add(current.transition().candidateAccountIndexFence());
    String expectedPriorPredicate = expectedPriorPredicate(current);
    addExpectedPriorValues(values, current);
    values.add(decision.leaseExpiresAtEpochMillis().toString());
    String assignments =
        "admission_request_id = ?, admission_lease_id = ?, admission_lease_fence = ?,"
            + " admission_lease_kind = ?, admission_lease_digest = ?,"
            + " admission_lease_evidence = ?, admission_resume_episode_id = ?,"
            + " admission_expected_old_binding_generation = ?, admission_lease_expires_at = ?,"
            + " admission_target_namespace = ?, admission_target_tenant_slug = ?,"
            + " admission_target_tenant_id = ?, admission_target_world_slug = ?,"
            + " admission_target_world_display_name = ?, admission_target_realm_id = ?,"
            + " admission_target_realm_slug = ?, admission_target_realm_display_name = ?,"
            + " admission_target_game_session_tenant_id = ?, admission_target_game_instance_id = ?,"
            + " admission_target_playable_state_namespace_id = ?,"
            + " admission_target_playable_state_scope = ?,"
            + " admission_target_canonical_game_instance_id = ?,"
            + " admission_target_canonical_version_id = ?, admission_target_runtime_version_id = ?,"
            + " admission_target_catalog_revision = ?, admission_target_pointer_version = ?,"
            + " admission_target_pointer_snapshot_digest = ?,"
            + " admission_target_active_world_epoch = ?,"
            + " admission_target_initial_admission_request_id = ?,"
            + " admission_target_initial_admission_request_digest = ?,"
            + " admission_target_origin_kind = ?,"
            + " admission_target_expected_prior_pointer_version = ?,"
            + " admission_target_hold_id = ?, admission_target_hold_fence = ?,"
            + " admission_target_hold_binding_digest = ?, admission_target_audit_event_id = ?,"
            + " admission_target_owner_proof_digest = ?,"
            + " admission_target_owner_proof_outcome = ?,"
            + " admission_target_positive_durable_abort = ?,"
            + " admission_target_terminal_at = ?::timestamptz,"
            + " admission_target_character_creation_policy = ?";
    return transaction.execute(
        "UPDATE "
            + TRANSITION
            + " SET status = 'PROVISIONAL', inventory_revision = ?, "
            + assignments
            + " WHERE transition_id = ? AND status = 'PREPARED'"
            + " AND inventory_revision = ? AND candidate_binding_ref = ?"
            + " AND candidate_binding_generation = ? AND candidate_account_index_fence = ?"
            + " "
            + expectedPriorPredicate
            + " AND admission_request_id IS NULL AND admission_lease_id IS NULL"
            + " AND CAST(? AS NUMERIC) > FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)",
        values.toArray());
  }

  private static List<Object> admissionDecisionValues(CanonicalGameplayAdmissionDecision decision) {
    CanonicalPlayableTarget target = decision.targetEvidence();
    List<Object> values = new ArrayList<>();
    values.add(decision.requestId());
    values.add(decision.leaseId());
    values.add(decimal(decision.leaseFence()));
    values.add(decision.leaseKind().name());
    values.add(decision.leaseSha256());
    values.add(decision.leaseEvidenceJson());
    values.add(decision.resumeEpisodeId());
    values.add(
        decision.expectedOldBindingGeneration() == null
            ? null
            : decimal(decision.expectedOldBindingGeneration()));
    values.add(decimal(decision.leaseExpiresAtEpochMillis()));
    values.add(target.targetNamespace());
    values.add(target.tenantSlug());
    values.add(target.canonicalTenantId());
    values.add(target.worldSlug());
    values.add(target.worldDisplayName());
    values.add(target.realmId());
    values.add(target.realmSlug());
    values.add(target.realmDisplayName());
    values.add(target.gameSessionTenantId());
    values.add(target.gameInstanceId());
    values.add(target.playableStateNamespaceId());
    values.add(target.playableStateScope());
    values.add(target.canonicalGameInstanceId());
    values.add(target.canonicalVersionId());
    values.add(target.runtimeVersionId());
    values.add(target.catalogRevision());
    values.add(target.pointerVersion());
    values.add(target.admissionPointerSnapshotDigest());
    values.add(target.activeWorldEpoch());
    values.add(target.initialAdmissionRequestId());
    values.add(target.initialAdmissionRequestDigest());
    values.add(target.initialAdmissionOriginKind().name());
    values.add(target.expectedPriorPointerVersion());
    values.add(target.holdId());
    values.add(target.holdFence());
    values.add(target.holdBindingDigest());
    values.add(target.auditEventId());
    values.add(target.ownerProofDigest());
    values.add(target.ownerProofOutcome().name());
    values.add(target.positiveDurableAbort());
    values.add(OffsetDateTime.ofInstant(target.ownerProofTerminalAt(), ZoneOffset.UTC));
    values.add(target.characterCreationPolicy());
    return values;
  }

  private static List<CanonicalGameplayBindingAccountIndexObligation> loadAccountIndexObligations(
      DSLContext transaction, UUID transitionId, boolean forUpdate) {
    return transaction
        .fetch(
            "SELECT o.*, i.playable_state_namespace_id, i.character_id,"
                + " i.playable_state_scope, i.game_instance_id, i.runtime_game_instance_id,"
                + " i.session_id, i.region_id, i.region_epoch, i.issuer_id,"
                + " i.issuer_auth_generation, i.issuer_index_layout_version,"
                + " i.issuer_index_partition_count, i.issuer_index_partition_capacity"
                + " FROM "
                + ACCOUNT_OBLIGATION
                + " o JOIN "
                + INVENTORY
                + " i ON i.binding_ref = o.binding_ref WHERE o.transition_id = ?"
                + " ORDER BY o.obligation_ordinal"
                + (forUpdate ? " FOR UPDATE OF o" : ""),
            transitionId)
        .map(CanonicalGameplayBindingInventoryRepository::toAccountObligation);
  }

  private static CanonicalGameplayBindingAccountIndexProjectionReceipt projectionReceipt(
      CanonicalGameplayBindingAccountIndexObligation obligation) {
    if (obligation.projectionState()
        != CanonicalGameplayBindingAccountIndexObligation.ProjectionState
            .PRESENT_AWAITING_COVERAGE) {
      throw conflict("No exact PRESENT account-index readback is durably retained");
    }
    return new CanonicalGameplayBindingAccountIndexProjectionReceipt(
        obligation.transitionId(),
        obligation.ordinal(),
        obligation.bindingRef(),
        obligation.accountId(),
        obligation.tenantId(),
        obligation.bindingGeneration(),
        obligation.accountIndexFence(),
        obligation.inventoryRevision(),
        obligation.projectionInventoryRevision(),
        obligation.projectedMember(),
        CanonicalGameplayBindingAccountIndexProjectionReceipt.State.PRESENT_AWAITING_COVERAGE);
  }

  private static CanonicalGameplayAdmissionDecision toAdmissionDecision(
      Record row, CanonicalGameplayBindingProvisionalCasEvidence operation) {
    return toAdmissionDecision(row, operation.transition(), operation.candidate());
  }

  private static CanonicalGameplayAdmissionDecision toAdmissionDecision(
      Record row,
      CanonicalGameplayBindingTransitionSnapshot transition,
      CanonicalGameplayBindingInventoryEntry candidate) {
    if (row == null || !transition.transitionId().equals(row.get("transition_id", UUID.class))) {
      throw conflict("Durable binding decision row is missing or changed");
    }
    String canonicalEvidence = row.get("admission_lease_evidence", String.class);
    if (canonicalEvidence == null) {
      throw conflict("Transition has no Account lease-bound admission decision");
    }
    AccountGameplayAdmissionLeaseEvidence lease;
    try {
      lease = AccountGameplayAdmissionLeaseEvidence.parseCanonical(canonicalEvidence);
    } catch (RuntimeException malformed) {
      throw conflict("Stored Account lease evidence is not canonical: " + malformed.getMessage());
    }
    CanonicalPlayableTarget target = toAdmissionTarget(row);
    CanonicalGameplayAdmissionDecision decision;
    try {
      decision =
          new CanonicalGameplayAdmissionDecision(
              transition.transitionId(),
              lease,
              candidate.identity(),
              transition.bindingGeneration(),
              transition.expectedPriorBindingGeneration(),
              target);
    } catch (RuntimeException mismatch) {
      throw conflict(
          "Stored lease and current target do not bind the exact durable candidate: "
              + mismatch.getMessage());
    }
    if (!decision.requestId().equals(row.get("admission_request_id", UUID.class))
        || !decision.leaseId().equals(row.get("admission_lease_id", UUID.class))
        || !decision.leaseFence().equals(readBigInteger(row, "admission_lease_fence"))
        || !decision.leaseKind().name().equals(row.get("admission_lease_kind", String.class))
        || !decision.leaseSha256().equals(row.get("admission_lease_digest", String.class))
        || !decision.leaseEvidenceJson().equals(canonicalEvidence)
        || !Objects.equals(
            decision.resumeEpisodeId(), row.get("admission_resume_episode_id", UUID.class))
        || !Objects.equals(
            decision.expectedOldBindingGeneration(),
            nullableBigInteger(row, "admission_expected_old_binding_generation"))
        || !decision
            .leaseExpiresAtEpochMillis()
            .equals(readBigInteger(row, "admission_lease_expires_at"))) {
      throw conflict("Stored Account lease columns differ from the exact canonical carrier");
    }
    return decision;
  }

  private static CanonicalPlayableTarget toAdmissionTarget(Record row) {
    try {
      return new CanonicalPlayableTarget(
          row.get("admission_target_namespace", String.class),
          row.get("admission_target_tenant_slug", String.class),
          row.get("admission_target_tenant_id", UUID.class),
          row.get("admission_target_world_slug", String.class),
          row.get("admission_target_world_display_name", String.class),
          row.get("admission_target_realm_id", UUID.class),
          row.get("admission_target_realm_slug", String.class),
          row.get("admission_target_realm_display_name", String.class),
          row.get("admission_target_game_session_tenant_id", Long.class),
          row.get("admission_target_game_instance_id", Long.class),
          row.get("admission_target_playable_state_namespace_id", UUID.class),
          row.get("admission_target_playable_state_scope", String.class),
          row.get("admission_target_canonical_game_instance_id", UUID.class),
          row.get("admission_target_canonical_version_id", UUID.class),
          row.get("admission_target_runtime_version_id", Long.class),
          row.get("admission_target_catalog_revision", Long.class),
          row.get("admission_target_pointer_version", Long.class),
          row.get("admission_target_pointer_snapshot_digest", String.class),
          row.get("admission_target_active_world_epoch", Long.class),
          row.get("admission_target_initial_admission_request_id", String.class),
          row.get("admission_target_initial_admission_request_digest", String.class),
          CanonicalInitialAdmissionRequest.OriginKind.valueOf(
              row.get("admission_target_origin_kind", String.class)),
          row.get("admission_target_expected_prior_pointer_version", Long.class),
          row.get("admission_target_hold_id", UUID.class),
          row.get("admission_target_hold_fence", UUID.class),
          row.get("admission_target_hold_binding_digest", String.class),
          row.get("admission_target_audit_event_id", Long.class),
          row.get("admission_target_owner_proof_digest", String.class),
          CanonicalInitialAdmissionOwnerProof.Outcome.valueOf(
              row.get("admission_target_owner_proof_outcome", String.class)),
          row.get("admission_target_positive_durable_abort", Boolean.class),
          row.get("admission_target_terminal_at", OffsetDateTime.class).toInstant(),
          row.get("admission_target_character_creation_policy", String.class));
    } catch (RuntimeException malformed) {
      throw conflict(
          "Stored canonical current-target evidence is incomplete or invalid: "
              + malformed.getMessage());
    }
  }

  private static void requireDecisionMatchesOperation(
      CanonicalGameplayAdmissionDecision decision,
      CanonicalGameplayBindingProvisionalCasEvidence operation) {
    if (!decision.bindingDecisionId().equals(operation.transition().transitionId())
        || !decision.candidate().equals(operation.candidate().identity())
        || !decision.bindingGeneration().equals(operation.transition().bindingGeneration())
        || !Objects.equals(
            decision.expectedOldBindingGeneration(),
            operation.transition().expectedPriorBindingGeneration())) {
      throw conflict("Lease-bound decision differs from the exact durable transition");
    }
  }

  private static void requireSameAdmissionDecision(
      CanonicalGameplayAdmissionDecision expected, CanonicalGameplayAdmissionDecision current) {
    if (!expected.bindingDecisionId().equals(current.bindingDecisionId())
        || !expected.leaseEvidence().hasSameIdentity(current.leaseEvidence())
        || !expected.candidate().equals(current.candidate())
        || !expected.bindingGeneration().equals(current.bindingGeneration())
        || !Objects.equals(
            expected.expectedOldBindingGeneration(), current.expectedOldBindingGeneration())
        || !expected.targetEvidence().equals(current.targetEvidence())) {
      throw conflict("The immutable lease-bound decision changed during provisional publication");
    }
  }

  private static CanonicalGameplayBindingProvisionalCasEvidence loadProvisionalCasEvidence(
      DSLContext transaction, UUID transitionId, boolean forUpdate) {
    String lock = forUpdate ? " FOR UPDATE" : "";
    Record transition =
        transaction.fetchOne(
            "SELECT * FROM " + TRANSITION + " WHERE transition_id = ?" + lock, transitionId);
    if (transition == null) {
      throw conflict("Durable binding transition does not exist");
    }
    byte[] candidateRef = transition.get("candidate_binding_ref", byte[].class);
    Record candidateRow =
        transaction.fetchOne(
            "SELECT * FROM " + INVENTORY + " WHERE binding_ref = ?" + lock, candidateRef);
    UUID reservationId = transition.get("issuer_reservation_id", UUID.class);
    Record reservationRow =
        transaction.fetchOne(
            "SELECT * FROM " + RESERVATION + " WHERE reservation_id = ?" + lock, reservationId);
    byte[] expectedPriorRef = transition.get("expected_prior_binding_ref", byte[].class);
    BigInteger expectedPriorGeneration =
        nullableBigInteger(transition, "expected_prior_binding_generation");
    if ((expectedPriorRef == null) != (expectedPriorGeneration == null)) {
      throw conflict("Durable transition has an incomplete nullable prior tuple");
    }
    boolean initialBinding = expectedPriorRef == null;
    if (candidateRow == null || reservationRow == null) {
      throw conflict("Durable transition is missing its exact candidate or reservation row");
    }
    Record priorRow = null;
    Record priorReservationRow = null;
    CanonicalGameplayLegacyMigrationDisposition initialBindingDisposition = null;
    if (initialBinding) {
      Record active =
          transaction.fetchOne(
              "SELECT binding_ref FROM "
                  + INVENTORY
                  + " WHERE tenant_id = ? AND playable_state_namespace_id = ?"
                  + " AND character_id = ? AND lifecycle = 'ACTIVE'"
                  + (forUpdate ? " FOR UPDATE" : ""),
              transition.get("tenant_id", UUID.class),
              transition.get("playable_state_namespace_id", UUID.class),
              transition.get("character_id", UUID.class));
      if (active != null) {
        throw conflict("A first binding cannot proceed while an active controller exists");
      }
      initialBindingDisposition =
          readVerifiedLegacyDisposition(
              transaction, readCurrentInventoryRevision(transaction), forUpdate);
    } else {
      priorRow =
          transaction.fetchOne(
              "SELECT * FROM " + INVENTORY + " WHERE binding_ref = ?" + lock, expectedPriorRef);
      if (priorRow == null) {
        throw conflict("Durable transfer is missing its exact prior source row");
      }
      priorReservationRow =
          transaction.fetchOne(
              "SELECT * FROM " + RESERVATION + " WHERE reservation_id = ?" + lock,
              priorRow.get("issuer_reservation_id", UUID.class));
      if (priorReservationRow == null) {
        throw conflict("Durable transfer is missing its exact prior issuer reservation");
      }
    }

    CanonicalGameplayBindingTransitionSnapshot transitionSnapshot =
        toTransitionSnapshot(transition, candidateRow, reservationRow);
    if (initialBinding) {
      requireReadAccountCoverageEvidenceCurrent(
          transaction,
          transitionSnapshot.candidate().accountId(),
          transitionSnapshot.accountCoverageEvidence());
    }
    CanonicalGameplayBindingInventoryEntry candidate = toInventoryEntry(candidateRow);
    CanonicalIssuerPartitionReservation reservation = toReservation(reservationRow);
    CanonicalGameplayBindingInventoryEntry prior =
        priorRow == null ? null : toInventoryEntry(priorRow);
    CanonicalIssuerPartitionReservation priorReservation =
        priorReservationRow == null ? null : toReservation(priorReservationRow);
    CanonicalGameplayBindingProvisionalCasEvidence evidence =
        new CanonicalGameplayBindingProvisionalCasEvidence(
            transitionSnapshot,
            candidate,
            reservation,
            prior,
            priorReservation,
            initialBindingDisposition);
    List<CanonicalGameplayBindingAccountIndexObligation> accountObligations =
        transaction
            .fetch(
                "SELECT o.*, i.playable_state_namespace_id, i.character_id,"
                    + " i.playable_state_scope, i.game_instance_id, i.runtime_game_instance_id,"
                    + " i.session_id, i.region_id, i.region_epoch, i.issuer_id,"
                    + " i.issuer_auth_generation, i.issuer_index_layout_version,"
                    + " i.issuer_index_partition_count, i.issuer_index_partition_capacity"
                    + " FROM "
                    + ACCOUNT_OBLIGATION
                    + " o JOIN "
                    + INVENTORY
                    + " i ON i.binding_ref = o.binding_ref WHERE o.transition_id = ?"
                    + " ORDER BY o.obligation_ordinal"
                    + (forUpdate ? " FOR UPDATE OF o" : ""),
                transitionId)
            .map(CanonicalGameplayBindingInventoryRepository::toAccountObligation);
    Record issuerObligationRow =
        transaction.fetchOne(
            "SELECT o.*, i.account_id, i.tenant_id, i.playable_state_namespace_id,"
                + " i.character_id, i.playable_state_scope, i.game_instance_id,"
                + " i.runtime_game_instance_id, i.session_id, i.region_id, i.region_epoch,"
                + " i.issuer_id, i.issuer_auth_generation, i.issuer_index_layout_version,"
                + " i.issuer_index_partition_count, i.issuer_index_partition_capacity"
                + " FROM "
                + ISSUER_OBLIGATION
                + " o JOIN "
                + INVENTORY
                + " i ON i.binding_ref = o.binding_ref WHERE o.transition_id = ?"
                + " AND o.binding_ref = ?"
                + (forUpdate ? " FOR UPDATE OF o" : ""),
            transitionId,
            candidateRef);
    CanonicalGameplayBindingIssuerIndexObligation issuerObligation =
        issuerObligationRow == null ? null : toIssuerObligation(issuerObligationRow);
    Record regionObligationRow =
        transaction.fetchOne(
            "SELECT * FROM "
                + REGION_OBLIGATION
                + " WHERE transition_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            transitionId);
    CanonicalGameplayBindingRegionBridgeObligation regionObligation =
        regionObligationRow == null ? null : toProvisionalRegionObligation(regionObligationRow);
    requireExactProvisionalRows(evidence, accountObligations, issuerObligation, regionObligation);
    if (!accountObligations.isEmpty()
        && accountObligations.getFirst().status()
            == CanonicalGameplayBindingAccountIndexObligation.Status.ACKNOWLEDGED) {
      requireCompletedMemberRepairAcknowledgement(
          transaction, evidence, accountObligations.getFirst());
    }
    return evidence;
  }

  private static CanonicalGameplayAdmissionAbortSource loadFirstNewAdmissionAbortSource(
      DSLContext transaction, UUID transitionId, boolean forUpdate) {
    return loadFirstNewAdmissionAbortSource(transaction, transitionId, forUpdate, false);
  }

  private static CanonicalGameplayAdmissionAbortSource loadFirstNewAdmissionAbortSource(
      DSLContext transaction,
      UUID transitionId,
      boolean forUpdate,
      boolean retainedDefinitiveAbortCleanup) {
    String lock = forUpdate ? " FOR UPDATE" : "";
    Record transition =
        transaction.fetchOne(
            "SELECT * FROM " + TRANSITION + " WHERE transition_id = ?" + lock, transitionId);
    if (transition == null) {
      throw conflict("First-NEW admission abort source does not exist");
    }
    byte[] candidateRef = transition.get("candidate_binding_ref", byte[].class);
    Record candidateRow =
        transaction.fetchOne(
            "SELECT * FROM " + INVENTORY + " WHERE binding_ref = ?" + lock, candidateRef);
    UUID reservationId = transition.get("issuer_reservation_id", UUID.class);
    Record reservationRow =
        transaction.fetchOne(
            "SELECT * FROM " + RESERVATION + " WHERE reservation_id = ?" + lock, reservationId);
    if (candidateRow == null || reservationRow == null) {
      throw conflict("First-NEW admission abort source lost its retained candidate or reservation");
    }

    CanonicalGameplayBindingTransitionSnapshot transitionSnapshot =
        toTransitionSnapshot(transition, candidateRow, reservationRow);
    if (transitionSnapshot.expectedPriorBindingRef() != null
        || transitionSnapshot.expectedPriorBindingGeneration() != null) {
      throw conflict("Admission abort owner accepts first-NEW transitions only");
    }
    if (transitionSnapshot.status() != CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL
        && transitionSnapshot.status()
            != CanonicalGameplayBindingTransitionSnapshot.Status.ABORTED) {
      throw conflict("First-NEW admission abort source is not provisional or definitively aborted");
    }

    CanonicalGameplayBindingInventoryEntry candidate = toInventoryEntry(candidateRow);
    CanonicalIssuerPartitionReservation reservation = toReservation(reservationRow);
    if (transitionSnapshot.status()
        == CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL) {
      CanonicalGameplayBindingProvisionalCasEvidence provisional =
          loadProvisionalCasEvidence(transaction, transitionId, forUpdate);
      transitionSnapshot = provisional.transition();
      candidate = provisional.candidate();
      reservation = provisional.candidateReservation();
    } else {
      requireRetainedFirstNewAbortObligations(
          transaction, transitionId, transitionSnapshot, candidate, retainedDefinitiveAbortCleanup);
    }

    AccountGameplayAdmissionLeaseEvidence lease;
    try {
      String canonicalEvidence = transition.get("admission_lease_evidence", String.class);
      if (canonicalEvidence == null) {
        throw conflict("First-NEW transition has no original Account lease evidence");
      }
      lease = AccountGameplayAdmissionLeaseEvidence.parseCanonical(canonicalEvidence);
    } catch (RuntimeException malformed) {
      throw conflict("First-NEW transition has invalid original Account lease evidence");
    }
    CanonicalGameplayAdmissionDecision originalDecision =
        toAdmissionDecision(transition, transitionSnapshot, candidate);
    UUID orphanCleanupId = transition.get("admission_orphan_cleanup_id", UUID.class);
    CanonicalGameplayAdmissionAbortSource source;
    try {
      source =
          new CanonicalGameplayAdmissionAbortSource(
              transitionSnapshot, candidate, reservation, originalDecision, orphanCleanupId);
    } catch (RuntimeException invalidSource) {
      throw conflict("Retained first-NEW admission abort source is incomplete or inconsistent");
    }
    if (lease.leaseKind() != AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING
        || !lease.hasSameIdentity(originalDecision.leaseEvidence())) {
      throw conflict("First-NEW transition changed its complete Account lease evidence");
    }

    if (retainedDefinitiveAbortCleanup) {
      if (!source.isAccountAborted() || source.orphanCleanupId() == null) {
        throw conflict(
            "Issuer cleanup requires the exact retained definitive first-NEW Account abort");
      }
    } else if (forUpdate) {
      requireAccountCoverageEvidenceCurrent(
          transaction,
          candidate.identity().accountId(),
          transitionSnapshot.accountCoverageEvidence());
    } else {
      requireReadAccountCoverageEvidenceCurrent(
          transaction,
          candidate.identity().accountId(),
          transitionSnapshot.accountCoverageEvidence());
    }
    return source;
  }

  private static void requireRetainedFirstNewAbortObligations(
      DSLContext transaction,
      UUID transitionId,
      CanonicalGameplayBindingTransitionSnapshot transition,
      CanonicalGameplayBindingInventoryEntry retainedCandidate,
      boolean retainedDefinitiveAbortCleanup) {
    BigInteger revision = transition.inventoryRevision();
    byte[] bindingRef = transition.bindingRef().bytes();
    CanonicalGameplayBindingIdentity candidate = transition.candidate();
    Record account =
        transaction.fetchOne(
            "SELECT * FROM "
                + ACCOUNT_OBLIGATION
                + " WHERE transition_id = ? AND obligation_ordinal = 0",
            transitionId);
    Record issuer =
        transaction.fetchOne(
            "SELECT * FROM " + ISSUER_OBLIGATION + " WHERE transition_id = ? AND binding_ref = ?",
            transitionId,
            bindingRef);
    Record region =
        transaction.fetchOne(
            "SELECT * FROM " + REGION_OBLIGATION + " WHERE transition_id = ?", transitionId);
    if (account == null
        || issuer == null
        || region == null
        || !"ADD_OR_RETAIN".equals(account.get("action", String.class))
        || !Arrays.equals(bindingRef, account.get("binding_ref", byte[].class))
        || !"BEFORE_FINAL_CAS".equals(account.get("execution_phase", String.class))
        || !("REQUIRED".equals(account.get("status", String.class))
            || "ACKNOWLEDGED".equals(account.get("status", String.class)))
        || !transition.bindingGeneration().equals(readBigInteger(account, "binding_generation"))
        || nullableBigInteger(account, "expected_prior_generation") != null
        || !transition
            .candidateAccountIndexFence()
            .equals(account.get("account_index_fence", UUID.class))
        || !candidate.accountId().equals(account.get("account_id", UUID.class))
        || !candidate.tenantId().equals(account.get("tenant_id", UUID.class))
        || !revision.equals(readBigInteger(account, "inventory_revision"))
        || !("REQUIRED".equals(issuer.get("status", String.class))
            || "RELEASED".equals(issuer.get("status", String.class)))
        || !Arrays.equals(bindingRef, issuer.get("binding_ref", byte[].class))
        || !transition.issuerReservationId().equals(issuer.get("reservation_id", UUID.class))
        || !transition.bindingGeneration().equals(readBigInteger(issuer, "binding_generation"))
        || !revision.equals(readBigInteger(issuer, "inventory_revision"))
        || (!"REQUIRED".equals(region.get("status", String.class))
            && !"ACKNOWLEDGED".equals(region.get("status", String.class)))
        || !Arrays.equals(bindingRef, region.get("binding_ref", byte[].class))
        || !transition.bindingGeneration().equals(readBigInteger(region, "binding_generation"))
        || !candidate.accountId().equals(region.get("account_id", UUID.class))
        || !candidate.tenantId().equals(region.get("tenant_id", UUID.class))
        || !candidate.gameInstanceId().equals(region.get("game_instance_id", UUID.class))
        || !Objects.equals(
            candidate.runtimeGameInstanceId(), region.get("runtime_game_instance_id", Long.class))
        || !candidate.sessionId().equals(region.get("session_id", String.class))
        || !candidate.regionId().equals(region.get("region_id", UUID.class))
        || !candidate.regionEpoch().equals(readBigInteger(region, "region_epoch"))
        || !revision.equals(readBigInteger(region, "inventory_revision"))
        || transaction.fetchCount(
                DSL.table(ACCOUNT_OBLIGATION),
                DSL.field("transition_id", UUID.class).eq(transitionId))
            != 1
        || transaction.fetchCount(
                DSL.table(ISSUER_OBLIGATION),
                DSL.field("transition_id", UUID.class).eq(transitionId))
            != 1) {
      throw conflict("Aborted first-NEW source lost a required retained index obligation");
    }
    if ("ACKNOWLEDGED".equals(region.get("status", String.class))) {
      // Decode the complete retained ACK with the existing closed carrier contract. The exact
      // candidate, identity and revision checks above remain independent of cleanup or admission.
      toRegionObligation(region);
    }
    if ("ACKNOWLEDGED".equals(account.get("status", String.class))) {
      // This is historical ADD/readback evidence, never account-member removal or cleanup proof.
      Record repair = findMemberRepairOperation(transaction, transitionId, false);
      if (repair == null) throw conflict("Retained abort lost its completed account-member repair");
      var acknowledgement = toMemberAcknowledgement(repair);
      var operation = acknowledgement.operation();
      var obligation = loadAccountIndexObligations(transaction, transitionId, false).getFirst();
      requireMemberRepairObligationCurrent(operation, retainedCandidate, obligation, true);
      BigInteger completedRevision = readBigInteger(repair, "completed_inventory_revision");
      if (!CanonicalGameplayAccountIndexMember.of(retainedCandidate)
              .value()
              .equals(operation.member())
          || completedRevision.compareTo(operation.inventorySnapshotRevision()) <= 0
          || completedRevision.compareTo(revision) >= 0
          || (operation.originalObligation().projectionState()
                  == CanonicalGameplayBindingAccountIndexObligation.ProjectionState.REQUIRED
              && !completedRevision.equals(obligation.projectionInventoryRevision()))) {
        throw conflict("Retained abort changed the original completed account-member readback");
      }
      if (!retainedDefinitiveAbortCleanup) {
        Record control =
            transaction.fetchOne(
                "SELECT * FROM game_session_canonical_account_coverage_control WHERE account_id = ?",
                operation.accountId());
        if (control == null
            || !"NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(control.get("state", String.class))
            || !readBigInteger(control, "last_operation_fence")
                .equals(operation.accountControlEpoch())) {
          throw conflict("Retained account-member acknowledgement has a different control epoch");
        }
      }
    } else if (retainedCandidate.accountIndexState()
        != CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED) {
      throw conflict("Unacknowledged retained abort has contradictory account-index state");
    }

    String issuerStatus = issuer.get("status", String.class);
    UUID releaseOperationId = issuer.get("local_release_operation_id", UUID.class);
    if ("REQUIRED".equals(issuerStatus)) {
      if (releaseOperationId != null) {
        throw conflict("Pending issuer obligation contains an unearned release operation id");
      }
      Record pendingRelease =
          transaction.fetchOne(
              "SELECT * FROM " + ISSUER_LOCAL_RELEASE + " WHERE transition_id = ?", transitionId);
      if (pendingRelease != null
          && (!"PENDING_PHYSICAL_PROOF".equals(pendingRelease.get("status", String.class))
              || !revision.equals(readBigInteger(pendingRelease, "source_inventory_revision")))) {
        throw conflict("Pending issuer release differs from its retained abort source revision");
      }
    } else {
      Record releasedReservation =
          transaction.fetchOne(
              "SELECT * FROM " + RESERVATION + " WHERE reservation_id = ?",
              transition.issuerReservationId());
      if (releaseOperationId == null
          || releasedReservation == null
          || toReservation(releasedReservation).lifecycle()
              != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED) {
        throw conflict("Released issuer obligation lacks its exact terminal reservation");
      }
      requireCompletedIssuerLocalRelease(
          transaction, transitionId, bindingRef, revision, releaseOperationId);
    }
  }

  static void requireCompletedIssuerLocalRelease(
      DSLContext transaction,
      UUID transitionId,
      byte[] bindingRef,
      BigInteger completionRevision,
      UUID releaseOperationId) {
    Record release =
        transaction.fetchOne(
            "SELECT * FROM "
                + ISSUER_LOCAL_RELEASE
                + " WHERE operation_id = ? AND transition_id = ?",
            releaseOperationId,
            transitionId);
    if (release == null
        || !"RELEASED".equals(release.get("status", String.class))
        || !completionRevision.equals(readBigInteger(release, "completion_inventory_revision"))
        || readBigInteger(release, "source_inventory_revision").compareTo(completionRevision) >= 0
        || !Arrays.equals(bindingRef, release.get("binding_ref", byte[].class))
        || !releaseOperationId.equals(release.get("operation_id", UUID.class))) {
      throw conflict("Released issuer obligation lacks an exact fresh local-release receipt");
    }
    byte[] originalBytes = release.get("original_member_value_bytes", byte[].class);
    byte[] releasedBytes = release.get("released_member_value_bytes", byte[].class);
    byte[] readbackBytes = release.get("physical_readback_field_set_bytes", byte[].class);
    String originalDigest = release.get("original_member_value_digest", String.class);
    String releasedDigest = release.get("released_member_value_digest", String.class);
    String readbackDigest = release.get("physical_readback_field_set_digest", String.class);
    if (originalBytes == null
        || releasedBytes == null
        || readbackBytes == null
        || !CanonicalGameplayIssuerMemberV2Codec.sha256(originalBytes).equals(originalDigest)
        || !CanonicalGameplayIssuerMemberV2Codec.sha256(releasedBytes).equals(releasedDigest)
        || !CanonicalGameplayIssuerMemberV2Codec.sha256(readbackBytes).equals(readbackDigest)) {
      throw conflict("Released issuer receipt contains malformed exact-value digests");
    }
    Record original =
        transaction.fetchOne(
            "SELECT * FROM game_session_canonical_issuer_binding_local_operation"
                + " WHERE operation_id = ? AND transition_id = ?",
            release.get("original_local_operation_id", UUID.class),
            transitionId);
    if (original == null
        || !Arrays.equals(originalBytes, original.get("member_value_bytes", byte[].class))
        || !Objects.equals(originalDigest, original.get("member_value_digest", String.class))
        || !Objects.equals(
            original.get("reservation_id", UUID.class), release.get("reservation_id", UUID.class))
        || !Arrays.equals(original.get("binding_ref", byte[].class), bindingRef)) {
      throw conflict(
          "Released issuer receipt no longer retains its immutable original V1034 operation");
    }
    CanonicalGameplayIssuerMemberV2Value originalValue;
    CanonicalGameplayIssuerMemberV2Value releasedValue;
    try {
      originalValue = CanonicalGameplayIssuerMemberV2Codec.decode(originalBytes);
      releasedValue = CanonicalGameplayIssuerMemberV2Codec.decode(releasedBytes);
    } catch (RuntimeException malformed) {
      throw conflict("Released issuer receipt contains a malformed schema-v2 member");
    }
    BigInteger sourceRevision = readBigInteger(release, "source_inventory_revision");
    BigInteger operationFence = readBigInteger(release, "release_operation_fence");
    BigInteger generation = readBigInteger(release, "release_local_member_generation");
    UUID operationId = release.get("operation_id", UUID.class);
    CanonicalGameplayIssuerMemberV2Value expectedValue =
        new CanonicalGameplayIssuerMemberV2Value(
            originalValue.owner(),
            originalValue.bindingGeneration(),
            new CanonicalIssuerPartitionReservation(
                originalValue.reservation().reservationId(),
                originalValue.reservation().owner(),
                originalValue.reservation().bindingGeneration(),
                originalValue.reservation().transitionId(),
                originalValue.reservation().partitionId(),
                CanonicalIssuerPartitionReservation.Lifecycle.RELEASED,
                originalValue.reservation().reservationFence(),
                originalValue.reservation().issuerCoverageOperationId(),
                originalValue.reservation().issuerCoverageOperationFence(),
                originalValue.reservation().coverageFence(),
                originalValue.reservation().inventorySnapshotRevision(),
                sourceRevision),
            originalValue.anchor(),
            new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
                operationId, operationFence, generation),
            originalValue.issuerControlEpoch(),
            originalValue.layoutDescriptor(),
            originalValue.sourceCheckpoint(),
            originalValue.incarnation());
    if (!expectedValue.equals(releasedValue)
        || !operationFence.add(BigInteger.ONE).equals(generation)
        || !operationFence.equals(readBigInteger(release, "release_operation_fence"))) {
      throw conflict("Released issuer receipt rewrites its original owner or anchor provenance");
    }
    Map<String, byte[]> readback;
    try {
      readback = CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(readbackBytes);
    } catch (RuntimeException malformed) {
      throw conflict("Released issuer receipt contains malformed physical field-set bytes");
    }
    String capacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
            releasedValue.reservation().reservationId());
    String memberField = new String(bindingRef, java.nio.charset.StandardCharsets.UTF_8);
    if (!Arrays.equals(readback.get(capacityField), releasedBytes)
        || readback.containsKey(memberField)) {
      throw conflict("Released issuer receipt lacks its exact positive terminal capacity field");
    }
  }

  private static void requireSameFirstNewAdmissionAbortSource(
      CanonicalGameplayAdmissionAbortSource expected,
      CanonicalGameplayAdmissionAbortSource current) {
    var expectedTransition = expected.transition();
    var currentTransition = current.transition();
    if (!expectedTransition.transitionId().equals(currentTransition.transitionId())
        || !expectedTransition.candidate().equals(currentTransition.candidate())
        || !expectedTransition.bindingGeneration().equals(currentTransition.bindingGeneration())
        || !expectedTransition.bindingRef().equals(currentTransition.bindingRef())
        || !expectedTransition.issuerReservationId().equals(currentTransition.issuerReservationId())
        || !expectedTransition.reservationFence().equals(currentTransition.reservationFence())
        || !expectedTransition
            .candidateAccountIndexFence()
            .equals(currentTransition.candidateAccountIndexFence())
        || expectedTransition.expectedPriorBindingRef() != null
        || expectedTransition.expectedPriorBindingGeneration() != null
        || !expectedTransition
            .accountCoverageEvidence()
            .equals(currentTransition.accountCoverageEvidence())
        || !expected.candidate().identity().equals(current.candidate().identity())
        || !expected.candidate().bindingRef().equals(current.candidate().bindingRef())
        || !expected.candidate().accountIndexFence().equals(current.candidate().accountIndexFence())
        || !expected.reservation().reservationId().equals(current.reservation().reservationId())
        || !expected
            .reservation()
            .reservationFence()
            .equals(current.reservation().reservationFence())
        || !expected
            .originalDecision()
            .leaseEvidence()
            .hasSameIdentity(current.originalDecision().leaseEvidence())) {
      throw conflict("First-NEW admission abort source drifted before its guarded CAS");
    }
    if (expected.isAccountAborted()) {
      if (!current.isAccountAborted()
          || !Objects.equals(expected.orphanCleanupId(), current.orphanCleanupId())
          || !expected.candidate().equals(current.candidate())
          || !expected.reservation().equals(current.reservation())) {
        throw conflict("Retained first-NEW abort source changed during exact retry");
      }
    } else if (!current.isAccountAborted()) {
      if (!expectedTransition.equals(currentTransition)
          || !expected.candidate().equals(current.candidate())
          || !expected.reservation().equals(current.reservation())) {
        throw conflict("First-NEW abort source revision or lifecycle drifted before Account abort");
      }
    } else if (currentTransition.status()
            != CanonicalGameplayBindingTransitionSnapshot.Status.ABORTED
        || currentTransition.inventoryRevision().compareTo(expectedTransition.inventoryRevision())
            <= 0
        || current.candidate().lifecycle()
            != CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL_UNRESOLVED
        || !current.candidate().inventoryRevision().equals(currentTransition.inventoryRevision())
        || current.reservation().lifecycle()
            != CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING
        || !current.reservation().inventoryRevision().equals(currentTransition.inventoryRevision())
        || !expected.candidate().identity().equals(current.candidate().identity())
        || !expected.candidate().bindingRef().equals(current.candidate().bindingRef())
        || !expected.candidate().bindingGeneration().equals(current.candidate().bindingGeneration())
        || !expected.candidate().accountIndexFence().equals(current.candidate().accountIndexFence())
        || !expected
            .candidate()
            .issuerReservationId()
            .equals(current.candidate().issuerReservationId())
        || !expected.candidate().transitionId().equals(current.candidate().transitionId())
        || expected.candidate().accountIndexState() != current.candidate().accountIndexState()
        || expected.candidate().issuerIndexState() != current.candidate().issuerIndexState()
        || !expected.reservation().reservationId().equals(current.reservation().reservationId())
        || !expected.reservation().owner().equals(current.reservation().owner())
        || !expected
            .reservation()
            .bindingGeneration()
            .equals(current.reservation().bindingGeneration())
        || !expected.reservation().transitionId().equals(current.reservation().transitionId())
        || !expected.reservation().partitionId().equals(current.reservation().partitionId())
        || !expected
            .reservation()
            .reservationFence()
            .equals(current.reservation().reservationFence())
        || !expected
            .reservation()
            .issuerCoverageOperationId()
            .equals(current.reservation().issuerCoverageOperationId())
        || !expected
            .reservation()
            .issuerCoverageOperationFence()
            .equals(current.reservation().issuerCoverageOperationFence())
        || !expected.reservation().coverageFence().equals(current.reservation().coverageFence())
        || !expected
            .reservation()
            .inventorySnapshotRevision()
            .equals(current.reservation().inventorySnapshotRevision())) {
      throw conflict("Concurrent first-NEW abort did not preserve the exact retained source");
    }
    requireSameAdmissionDecision(expected.originalDecision(), current.originalDecision());
  }

  private static void requireCompletedMemberRepairAcknowledgement(
      DSLContext transaction,
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      CanonicalGameplayBindingAccountIndexObligation obligation) {
    requireOrdinaryNewMemberRepairTransition(evidence);
    requireCompletedMemberRepairAcknowledgement(
        transaction, evidence.transition(), evidence.candidate(), obligation);
  }

  private static void requireCompletedMemberRepairAcknowledgement(
      DSLContext transaction,
      CanonicalGameplayBindingTransitionSnapshot transition,
      CanonicalGameplayBindingInventoryEntry candidate,
      CanonicalGameplayBindingAccountIndexObligation obligation) {
    if (!obligation.transitionId().equals(transition.transitionId())
        || obligation.ordinal() != 0
        || !obligation.bindingRef().equals(candidate.bindingRef())
        || !obligation.accountId().equals(candidate.identity().accountId())
        || !obligation.tenantId().equals(candidate.identity().tenantId())
        || !obligation.bindingGeneration().equals(candidate.bindingGeneration())
        || !obligation.accountIndexFence().equals(candidate.accountIndexFence())
        || obligation.expectedPriorGeneration() != null
        || obligation.action()
            != CanonicalGameplayBindingAccountIndexObligation.Action.ADD_OR_RETAIN
        || obligation.executionPhase()
            != CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.BEFORE_FINAL_CAS) {
      throw conflict("Completed member receipt differs from the original NEW candidate");
    }
    Record operationRow = findMemberRepairOperation(transaction, transition.transitionId(), false);
    if (operationRow == null) {
      throw conflict(
          "Acknowledged account-index obligation has no durable member-repair operation");
    }
    CanonicalGameplayAccountMemberRepairOperation operation = toMemberRepairOperation(operationRow);
    if (!"ACKNOWLEDGED".equals(operationRow.get("status", String.class))) {
      throw conflict("Account-index obligation was acknowledged before its operation completed");
    }
    requireMemberRepairObligationCurrent(operation, candidate, obligation, true);
    toMemberAcknowledgement(operationRow);
    Record control =
        transaction.fetchOne(
            "SELECT * FROM game_session_canonical_account_coverage_control WHERE account_id = ?",
            operation.accountId());
    if (control == null) {
      throw conflict("Acknowledged member repair lost its positive account-control row");
    }
    CanonicalGameplayAccountCoverageEvidence currentControl =
        accountCoverageEvidenceFromControl(control, operation.accountId());
    if (!(currentControl instanceof CanonicalGameplayAccountCoverageEvidence.NoActiveFlow)
        || !transition.accountCoverageEvidence().equals(currentControl)
        || !readBigInteger(control, "last_operation_fence")
            .equals(operation.accountControlEpoch())) {
      throw conflict("Acknowledged member repair has stale account-control state or fence epoch");
    }
  }

  private static void requireExactProvisionalRows(
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      List<CanonicalGameplayBindingAccountIndexObligation> accountObligations,
      CanonicalGameplayBindingIssuerIndexObligation issuerObligation,
      CanonicalGameplayBindingRegionBridgeObligation regionObligation) {
    var transition = evidence.transition();
    var candidate = evidence.candidate();
    var prior = evidence.expectedPrior();
    var reservation = evidence.candidateReservation();
    var priorReservation = evidence.expectedPriorReservation();
    if (!reservation.owner().equals(candidate.identity())
        || !reservation.owner().bindingRef().equals(candidate.bindingRef())
        || !reservation.transitionId().equals(transition.transitionId())
        || !reservation.bindingGeneration().equals(transition.bindingGeneration())
        || !reservation.reservationId().equals(transition.issuerReservationId())
        || !reservation.reservationFence().equals(transition.reservationFence())
        || reservation.lifecycle() != CanonicalIssuerPartitionReservation.Lifecycle.RESERVED
        || !reservation.inventoryRevision().equals(transition.inventoryRevision())
        || ((prior == null) != (priorReservation == null))
        || ((prior == null) != (evidence.initialBindingMigrationDisposition() != null))
        || accountObligations.size() != (prior == null ? 1 : 2)) {
      throw conflict(
          "Durable reservation or account obligations differ from this exact transition");
    }
    var candidateAccount = accountObligations.getFirst();
    if (!candidateAccount.transitionId().equals(transition.transitionId())
        || candidateAccount.ordinal() != 0
        || !candidateAccount.bindingRef().equals(candidate.bindingRef())
        || !candidateAccount.accountId().equals(candidate.identity().accountId())
        || !candidateAccount.tenantId().equals(candidate.identity().tenantId())
        || candidateAccount.action()
            != CanonicalGameplayBindingAccountIndexObligation.Action.ADD_OR_RETAIN
        || !candidateAccount.bindingGeneration().equals(candidate.bindingGeneration())
        || !Objects.equals(
            candidateAccount.expectedPriorGeneration(), transition.expectedPriorBindingGeneration())
        || !candidateAccount.accountIndexFence().equals(candidate.accountIndexFence())
        || candidateAccount.executionPhase()
            != CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.BEFORE_FINAL_CAS
        || (candidateAccount.status()
                != CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED
            && candidateAccount.status()
                != CanonicalGameplayBindingAccountIndexObligation.Status.ACKNOWLEDGED)
        || (candidateAccount.status()
                == CanonicalGameplayBindingAccountIndexObligation.Status.ACKNOWLEDGED
            && (candidateAccount.projectionState()
                    != CanonicalGameplayBindingAccountIndexObligation.ProjectionState
                        .PRESENT_AWAITING_COVERAGE
                || candidate.accountIndexState()
                    != CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED))
        || !candidateAccount.inventoryRevision().equals(transition.inventoryRevision())) {
      throw conflict("Durable account-index repair obligations differ from this exact transition");
    }
    if (prior == null) {
      if (transition.expectedPriorBindingRef() != null
          || transition.expectedPriorBindingGeneration() != null) {
        throw conflict("Initial-binding transition retained an unexpected prior source");
      }
    } else {
      if (priorReservation == null
          || !priorReservation.owner().equals(prior.identity())
          || !priorReservation.owner().bindingRef().equals(prior.bindingRef())
          || !priorReservation.transitionId().equals(prior.transitionId())
          || !priorReservation.bindingGeneration().equals(prior.bindingGeneration())
          || !priorReservation.reservationId().equals(prior.issuerReservationId())
          || priorReservation.lifecycle() != CanonicalIssuerPartitionReservation.Lifecycle.BOUND
          || !priorReservation.inventoryRevision().equals(prior.inventoryRevision())) {
        throw conflict("Durable prior reservation differs from the exact transfer source");
      }
      var priorAccount = accountObligations.get(1);
      if (!priorAccount.transitionId().equals(transition.transitionId())
          || priorAccount.ordinal() != 1
          || !priorAccount.bindingRef().equals(prior.bindingRef())
          || !priorAccount.accountId().equals(prior.identity().accountId())
          || !priorAccount.tenantId().equals(prior.identity().tenantId())
          || priorAccount.action() != CanonicalGameplayBindingAccountIndexObligation.Action.REMOVE
          || !priorAccount.bindingGeneration().equals(prior.bindingGeneration())
          || !priorAccount.expectedPriorGeneration().equals(prior.bindingGeneration())
          || !priorAccount.accountIndexFence().equals(prior.accountIndexFence())
          || priorAccount.executionPhase()
              != CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.AFTER_FINAL_CAS
          || priorAccount.status() != CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED
          || !priorAccount.inventoryRevision().equals(transition.inventoryRevision())) {
        throw conflict("Durable prior account-index obligation differs from the exact transfer");
      }
    }
    if (issuerObligation == null
        || !issuerObligation.transitionId().equals(transition.transitionId())
        || !issuerObligation.bindingRef().equals(candidate.bindingRef())
        || !issuerObligation.bindingGeneration().equals(candidate.bindingGeneration())
        || !issuerObligation.reservationId().equals(reservation.reservationId())
        || issuerObligation.status()
            != CanonicalGameplayBindingIssuerIndexObligation.Status.REQUIRED
        || !issuerObligation.inventoryRevision().equals(transition.inventoryRevision())
        || regionObligation == null
        || !regionObligation.transitionId().equals(transition.transitionId())
        || !regionObligation.bindingRef().equals(candidate.bindingRef())
        || !regionObligation.bindingGeneration().equals(candidate.bindingGeneration())
        || !regionObligation.accountId().equals(candidate.identity().accountId())
        || !regionObligation.tenantId().equals(candidate.identity().tenantId())
        || !regionObligation.gameInstanceId().equals(candidate.identity().gameInstanceId())
        || regionObligation.runtimeGameInstanceId() != candidate.identity().runtimeGameInstanceId()
        || !regionObligation.sessionId().equals(candidate.identity().sessionId())
        || !regionObligation.regionId().equals(candidate.identity().regionId())
        || !regionObligation.regionEpoch().equals(candidate.identity().regionEpoch())
        || (regionObligation.status()
                != CanonicalGameplayBindingRegionBridgeObligation.Status.REQUIRED
            && regionObligation.status()
                != CanonicalGameplayBindingRegionBridgeObligation.Status.ACKNOWLEDGED)
        || (regionObligation.status()
                == CanonicalGameplayBindingRegionBridgeObligation.Status.ACKNOWLEDGED
            && prior != null)
        || !regionObligation.inventoryRevision().equals(transition.inventoryRevision())) {
      throw conflict("Durable issuer or region obligations differ from this exact transition");
    }
  }

  private static void requireSameProvisionalOperation(
      CanonicalGameplayBindingProvisionalCasEvidence expected,
      CanonicalGameplayBindingProvisionalCasEvidence current) {
    if (!expected.transition().transitionId().equals(current.transition().transitionId())
        || !expected.transition().bindingRef().equals(current.transition().bindingRef())
        || !expected
            .transition()
            .bindingGeneration()
            .equals(current.transition().bindingGeneration())
        || !expected
            .transition()
            .issuerReservationId()
            .equals(current.transition().issuerReservationId())
        || !expected.transition().reservationFence().equals(current.transition().reservationFence())
        || !expected
            .transition()
            .candidateAccountIndexFence()
            .equals(current.transition().candidateAccountIndexFence())
        || !expected
            .transition()
            .accountCoverageEvidence()
            .equals(current.transition().accountCoverageEvidence())
        || !Objects.equals(
            expected.initialBindingMigrationDisposition(),
            current.initialBindingMigrationDisposition())
        || !Objects.equals(
            expected.transition().expectedPriorBindingRef(),
            current.transition().expectedPriorBindingRef())
        || !Objects.equals(
            expected.transition().expectedPriorBindingGeneration(),
            current.transition().expectedPriorBindingGeneration())
        || !expected.candidate().identity().equals(current.candidate().identity())
        || !expected.candidate().bindingRef().equals(current.candidate().bindingRef())
        || !expected
            .candidateReservation()
            .reservationId()
            .equals(current.candidateReservation().reservationId())
        || !expected.candidateReservation().owner().equals(current.candidateReservation().owner())
        || !expected
            .candidateReservation()
            .bindingGeneration()
            .equals(current.candidateReservation().bindingGeneration())
        || !expected
            .candidateReservation()
            .transitionId()
            .equals(current.candidateReservation().transitionId())
        || !expected
            .candidateReservation()
            .partitionId()
            .equals(current.candidateReservation().partitionId())
        || expected.candidateReservation().lifecycle() != current.candidateReservation().lifecycle()
        || !expected
            .candidateReservation()
            .reservationFence()
            .equals(current.candidateReservation().reservationFence())
        || !expected
            .candidateReservation()
            .issuerCoverageOperationId()
            .equals(current.candidateReservation().issuerCoverageOperationId())
        || !expected
            .candidateReservation()
            .issuerCoverageOperationFence()
            .equals(current.candidateReservation().issuerCoverageOperationFence())
        || !expected
            .candidateReservation()
            .coverageFence()
            .equals(current.candidateReservation().coverageFence())
        || !expected
            .candidateReservation()
            .inventorySnapshotRevision()
            .equals(current.candidateReservation().inventorySnapshotRevision())
        || !Objects.equals(expected.expectedPrior(), current.expectedPrior())
        || !Objects.equals(
            expected.expectedPriorReservation(), current.expectedPriorReservation())) {
      throw conflict("The durable operation changed after its exact Redis readback");
    }
  }

  private static CanonicalGameplayBindingTransitionSnapshot prepareInTransaction(
      DSLContext transaction, CanonicalGameplayBindingTransitionRequest request) {
    return prepareInTransaction(transaction, request, null, null);
  }

  private static CanonicalGameplayBindingTransitionSnapshot prepareInTransaction(
      DSLContext transaction,
      CanonicalGameplayBindingTransitionRequest request,
      InstalledAnchorPreparationContext installedAnchorContext,
      InstalledAnchorPreparationRecorder registrationRecorder) {
    BigInteger revision;
    CanonicalGameplayIssuerInstalledAnchor actualAnchor;
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentIssuerState;
    boolean pendingIssuerLayoutBootstrapIntent;
    if (installedAnchorContext == null) {
      revision = lockInventoryRevision(transaction);
      actualAnchor = null;
      currentIssuerState = null;
      pendingIssuerLayoutBootstrapIntent =
          validatePendingIssuerLayoutBootstrapIntent(transaction, request.candidate().issuerId());
    } else {
      revision = installedAnchorContext.inventoryRevision();
      actualAnchor = installedAnchorContext.anchor();
      currentIssuerState = installedAnchorContext.currentIssuerState();
      requireCandidateMatchesInstalledAnchor(request.candidate(), actualAnchor);
      pendingIssuerLayoutBootstrapIntent = false;
    }
    CanonicalGameplayAccountCoverageEvidence accountCoverageEvidence =
        lockAccountCoverageEvidence(transaction, request.candidate().accountId());
    boolean initialBinding = request.expectedPriorBindingRef() == null;
    if (initialBinding) {
      readVerifiedLegacyDisposition(transaction, revision, true);
    }
    Record existing = findTransition(transaction, request.transitionId());
    if (existing != null) {
      CanonicalGameplayBindingTransitionSnapshot retry =
          requireExactRetry(transaction, request, existing);
      if (!retry.accountCoverageEvidence().equals(accountCoverageEvidence)) {
        throw conflict("Same transition retry carries stale account-wide coverage evidence");
      }
      if (registrationRecorder != null) {
        registrationRecorder.record(
            transaction,
            request,
            Objects.requireNonNull(actualAnchor),
            currentIssuerState,
            retry,
            true);
      }
      return retry;
    }
    if (pendingIssuerLayoutBootstrapIntent) {
      throw conflict("Initial issuer-layout bootstrap intent blocks new binding preparation");
    }

    CanonicalGameplayBindingIdentity candidate = request.candidate();
    byte[] candidateRef = candidate.bindingRef().bytes();
    CanonicalGameplayBindingRef expectedPriorBindingRef = request.expectedPriorBindingRef();
    byte[] expectedPriorRef =
        expectedPriorBindingRef == null ? null : expectedPriorBindingRef.bytes();
    Record active = findActiveController(transaction, candidate);
    if (expectedPriorRef == null) {
      if (active != null) {
        throw conflict("First binding cannot proceed while an active controller exists");
      }
    } else {
      if (active == null
          || !Arrays.equals(active.get("binding_ref", byte[].class), expectedPriorRef)
          || !readBigInteger(active, "binding_generation")
              .equals(request.expectedPriorBindingGeneration())
          || !Objects.equals(active.get("account_id", UUID.class), candidate.accountId())
          || !"ACKNOWLEDGED".equals(active.get("account_index_state", String.class))
          || !"ACKNOWLEDGED".equals(active.get("issuer_index_state", String.class))) {
        throw conflict("The exact expected prior controller binding is no longer active");
      }
      if (Arrays.equals(expectedPriorRef, candidateRef)) {
        throw conflict("A transfer candidate must have a distinct exact bindingRef");
      }
    }

    Record unresolved = findUnresolvedController(transaction, candidate);
    if (unresolved != null) {
      throw conflict("An unresolved candidate or repair obligation already fences this controller");
    }

    if (actualAnchor != null) {
      requireIssuerCapacityAvailable(transaction, request.candidate(), actualAnchor);
    }

    ensureGenerationRow(transaction, candidate);
    Record generationRow =
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT last_issued_generation FROM "
                    + GENERATION
                    + " WHERE tenant_id = ? AND playable_state_namespace_id = ? AND character_id = ? FOR UPDATE",
                candidate.tenantId(),
                candidate.playableStateNamespaceId(),
                candidate.characterId()),
            "binding generation authority row must exist");
    BigInteger lastIssued = readBigInteger(generationRow, "last_issued_generation");
    BigInteger priorGeneration =
        expectedPriorRef == null ? BigInteger.ZERO : request.expectedPriorBindingGeneration();
    if (lastIssued.compareTo(priorGeneration) < 0) {
      throw conflict("The durable generation authority is behind the exact prior binding");
    }
    BigInteger candidateGeneration = lastIssued.add(BigInteger.ONE);
    BigInteger nextRevision = revision.add(BigInteger.ONE);
    BigInteger partitionId =
        candidate.bindingRef().partitionId(candidate.issuerIndexPartitionCount());
    CanonicalGameplayBindingTransitionSnapshot prepared =
        new CanonicalGameplayBindingTransitionSnapshot(
            request.transitionId(),
            candidate,
            candidateGeneration,
            candidate.bindingRef(),
            nextRevision,
            request.issuerReservation().reservationId(),
            request.issuerReservation().reservationFence(),
            request.candidateAccountIndexFence(),
            expectedPriorBindingRef,
            expectedPriorRef == null ? null : request.expectedPriorBindingGeneration(),
            CanonicalGameplayBindingTransitionSnapshot.Status.PREPARED,
            accountCoverageEvidence);

    int generationUpdated =
        transaction.execute(
            "UPDATE "
                + GENERATION
                + " SET last_issued_generation = ? WHERE tenant_id = ?"
                + " AND playable_state_namespace_id = ? AND character_id = ?"
                + " AND last_issued_generation = ?",
            decimal(candidateGeneration),
            candidate.tenantId(),
            candidate.playableStateNamespaceId(),
            candidate.characterId(),
            decimal(lastIssued));
    requireOne(generationUpdated, "binding generation compare-and-set lost its locked row");

    insertCandidate(transaction, request, prepared, partitionId);
    insertTransition(transaction, request, prepared, expectedPriorRef);
    insertAccountObligations(transaction, request, prepared, active, expectedPriorRef);
    insertReservation(transaction, request, prepared, partitionId, nextRevision);
    insertIssuerObligation(transaction, prepared);
    insertRegionObligation(transaction, prepared);

    int revisionUpdated =
        transaction.execute(
            "UPDATE game_session_canonical_binding_inventory_clock"
                + " SET inventory_revision = ? WHERE singleton_id = 1 AND inventory_revision = ?",
            decimal(nextRevision),
            decimal(revision));
    requireOne(revisionUpdated, "durable inventory revision compare-and-set lost its locked row");
    if (registrationRecorder != null) {
      registrationRecorder.record(
          transaction,
          request,
          Objects.requireNonNull(actualAnchor),
          currentIssuerState,
          prepared,
          false);
    }
    return prepared;
  }

  private static void requireCandidateMatchesInstalledAnchor(
      CanonicalGameplayBindingIdentity candidate, CanonicalGameplayIssuerInstalledAnchor anchor) {
    if (!candidate.issuerId().equals(anchor.issuerId())
        || !candidate.issuerAuthGeneration().equals(anchor.issuerAuthGeneration())
        || !candidate.issuerIndexLayoutVersion().equals(anchor.layoutVersion())
        || !candidate.issuerIndexPartitionCount().equals(anchor.partitionCount())
        || !candidate.issuerIndexPartitionCapacity().equals(anchor.partitionCapacity())) {
      throw conflict(
          "Candidate issuer, Account source, or layout differs from the installed anchor");
    }
  }

  /**
   * Counts the current V29 owner rows under the inventory-clock lock. The initial immutable anchor
   * is a baseline; every post-baseline candidate is represented by this same inventory, never by a
   * parallel local-operation inventory.
   */
  private static void requireIssuerCapacityAvailable(
      DSLContext transaction,
      CanonicalGameplayBindingIdentity candidate,
      CanonicalGameplayIssuerInstalledAnchor anchor) {
    BigInteger candidatePartition = candidate.bindingRef().partitionId(anchor.partitionCount());
    BigInteger aggregate = BigInteger.ZERO;
    BigInteger partition = BigInteger.ZERO;
    List<Record> reservations =
        transaction.fetch(
            "SELECT issuer_index_layout_version, issuer_index_partition_count,"
                + " issuer_index_partition_capacity, partition_id, lifecycle FROM "
                + RESERVATION
                + " WHERE issuer_id = ? AND lifecycle <> 'RELEASED' ORDER BY reservation_id FOR UPDATE",
            candidate.issuerId());
    for (Record reservation : reservations) {
      BigInteger layoutVersion = readBigInteger(reservation, "issuer_index_layout_version");
      BigInteger partitionCount = readBigInteger(reservation, "issuer_index_partition_count");
      BigInteger partitionCapacity = readBigInteger(reservation, "issuer_index_partition_capacity");
      BigInteger partitionId = readBigInteger(reservation, "partition_id");
      if (!layoutVersion.equals(anchor.layoutVersion())
          || !partitionCount.equals(anchor.partitionCount())
          || !partitionCapacity.equals(anchor.partitionCapacity())
          || partitionId.signum() < 0
          || partitionId.compareTo(anchor.partitionCount()) >= 0) {
        throw conflict(
            "Current issuer inventory contains a reservation outside the installed layout");
      }
      aggregate = aggregate.add(BigInteger.ONE);
      if (partitionId.equals(candidatePartition)) partition = partition.add(BigInteger.ONE);
    }
    BigInteger nextPartition = partition.add(BigInteger.ONE);
    BigInteger nextAggregate = aggregate.add(BigInteger.ONE);
    BigInteger aggregateBound = anchor.partitionCount().multiply(anchor.partitionCapacity());
    if (nextPartition.compareTo(anchor.partitionCapacity()) > 0
        || nextAggregate.compareTo(aggregateBound) > 0) {
      throw conflict("Installed issuer partition or aggregate reservation capacity is full");
    }
  }

  private static boolean validatePendingIssuerLayoutBootstrapIntent(
      DSLContext transaction, String issuerId) {
    Record control =
        transaction.fetchOne(
            "SELECT * FROM " + ISSUER_BOOTSTRAP_CONTROL + " WHERE issuer_id = ? FOR UPDATE",
            issuerId);
    if (control == null) {
      Record orphanIntent =
          transaction.fetchOne(
              "SELECT operation_id FROM "
                  + ISSUER_BOOTSTRAP_INTENT
                  + " WHERE issuer_id = ? ORDER BY operation_id LIMIT 1 FOR UPDATE",
              issuerId);
      if (orphanIntent != null) {
        throw conflict("Issuer bootstrap intent exists without its positive current control row");
      }
      return false;
    }

    UUID operationId = control.get("current_operation_id", UUID.class);
    String state = control.get("state", String.class);
    BigInteger controlEpoch = readBigInteger(control, "control_epoch");
    BigInteger operationFence = readBigInteger(control, "current_operation_fence");
    BigInteger coverageFence = readBigInteger(control, "current_coverage_fence");
    BigInteger lastIssuedFence = readBigInteger(control, "last_issued_fence");
    if (!Objects.equals(issuerId, control.get("issuer_id", String.class))
        || !"INITIAL_BOOTSTRAP_INTENT".equals(state)
        || operationId == null
        || NIL_UUID.equals(operationId)
        || controlEpoch.signum() <= 0
        || operationFence.signum() <= 0
        || coverageFence.signum() <= 0
        || !operationFence.equals(controlEpoch)
        || operationFence.equals(coverageFence)
        || lastIssuedFence.compareTo(coverageFence) < 0) {
      throw conflict("Issuer bootstrap control has a malformed or unsupported current pointer");
    }

    Record intent =
        transaction.fetchOne(
            "SELECT * FROM "
                + ISSUER_BOOTSTRAP_INTENT
                + " WHERE operation_id = ? AND issuer_id = ? FOR UPDATE",
            operationId,
            issuerId);
    if (intent == null
        || !operationId.equals(intent.get("operation_id", UUID.class))
        || !Objects.equals(issuerId, intent.get("issuer_id", String.class))
        || !"INITIAL_BOOTSTRAP_INTENT".equals(intent.get("intent_state", String.class))
        || !controlEpoch.equals(readBigInteger(intent, "control_epoch"))
        || !operationFence.equals(readBigInteger(intent, "operation_fence"))
        || !coverageFence.equals(readBigInteger(intent, "coverage_fence"))
        || readBigInteger(intent, "inventory_snapshot_revision").signum() <= 0) {
      throw conflict("Issuer bootstrap control does not point to its exact durable intent tuple");
    }
    return true;
  }

  private static void validateRequest(CanonicalGameplayBindingTransitionRequest request) {
    request.candidate().bindingRef().partitionId(request.candidate().issuerIndexPartitionCount());
    if (request.candidateAccountIndexFence().equals(NIL_UUID)) {
      throw new IllegalArgumentException("candidateAccountIndexFence must not be nil");
    }
  }

  private static void ensureGenerationRow(
      DSLContext transaction, CanonicalGameplayBindingIdentity candidate) {
    transaction.execute(
        "INSERT INTO "
            + GENERATION
            + " (tenant_id, playable_state_namespace_id, character_id, last_issued_generation) "
            + "VALUES (?, ?, ?, 0) ON CONFLICT DO NOTHING",
        candidate.tenantId(),
        candidate.playableStateNamespaceId(),
        candidate.characterId());
  }

  static BigInteger lockInventoryRevision(DSLContext transaction) {
    return readBigInteger(
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                    + " WHERE singleton_id = 1 FOR UPDATE"),
            "inventory revision clock row must exist"),
        "inventory_revision");
  }

  private static BigInteger readCurrentInventoryRevision(DSLContext transaction) {
    return readBigInteger(
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                    + " WHERE singleton_id = 1"),
            "canonical inventory clock row is missing"),
        "inventory_revision");
  }

  private static CanonicalGameplayLegacyMigrationDisposition readVerifiedLegacyDisposition(
      DSLContext transaction, BigInteger currentInventoryRevision, boolean forShare) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM "
                + LEGACY_DISPOSITION
                + " WHERE singleton_id = 1"
                + (forShare ? " FOR SHARE" : ""));
    if (row == null || !"VERIFIED".equals(row.get("disposition_state", String.class))) {
      throw conflict(
          "First binding requires the exact VERIFIED legacy-index migration fence;"
              + " account coverage state alone is not migration proof");
    }
    CanonicalGameplayLegacyMigrationDisposition disposition;
    try {
      disposition =
          new CanonicalGameplayLegacyMigrationDisposition(
              row.get("cohort_id", UUID.class),
              row.get("owner_operation_id", UUID.class),
              row.get("legacy_writer_fence", UUID.class),
              readBigInteger(row, "source_snapshot_revision"),
              readBigInteger(row, "canonical_inventory_revision"),
              readBigInteger(row, "namespace_index_readback_revision"),
              row.get("evidence_digest", String.class));
    } catch (RuntimeException malformed) {
      throw conflict("The VERIFIED legacy-index migration disposition is incomplete or invalid");
    }
    if (disposition.canonicalInventoryRevision().compareTo(currentInventoryRevision) > 0) {
      throw conflict("The VERIFIED legacy disposition is newer than current canonical inventory");
    }
    return disposition;
  }

  private static CanonicalGameplayAccountCoverageEvidence lockAccountCoverageEvidence(
      DSLContext transaction, UUID accountId) {
    return lockAccountCoverageControl(transaction, accountId).evidence();
  }

  private static AccountCoverageControl lockAccountCoverageControl(
      DSLContext transaction, UUID accountId) {
    transaction.execute(
        "INSERT INTO game_session_canonical_account_coverage_control "
            + "(account_id, state, last_operation_fence) "
            + "VALUES (?, 'NO_ACTIVE_ACCOUNT_WIDE_FLOW', 0) ON CONFLICT (account_id) DO NOTHING",
        accountId);
    Record control =
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT * FROM game_session_canonical_account_coverage_control "
                    + "WHERE account_id = ? FOR UPDATE",
                accountId),
            "account coverage control row must exist");
    return toAccountCoverageControl(control, accountId);
  }

  private static AccountCoverageControl lockExistingAccountCoverageControl(
      DSLContext transaction, UUID accountId) {
    Record control =
        transaction.fetchOne(
            "SELECT * FROM game_session_canonical_account_coverage_control "
                + "WHERE account_id = ? FOR UPDATE",
            accountId);
    if (control == null) {
      throw conflict("Account coverage control row is missing; absence is not no-active proof");
    }
    return toAccountCoverageControl(control, accountId);
  }

  private static AccountCoverageControl toAccountCoverageControl(Record control, UUID accountId) {
    return new AccountCoverageControl(
        accountId,
        control.get("state", String.class),
        readBigInteger(control, "last_operation_fence"),
        control.get("current_operation_id", UUID.class),
        nullableBigInteger(control, "current_operation_fence"),
        control.get("account_admission_fence", UUID.class),
        control.get("historical_account_admission_fence", UUID.class),
        control.get("coverage_fence", UUID.class),
        nullableBigInteger(control, "inventory_snapshot_revision"),
        nullableBigInteger(control, "coverage_generation"),
        accountCoverageEvidenceFromControl(control, accountId));
  }

  private static void requireReadAccountCoverageEvidenceCurrent(
      DSLContext transaction, UUID accountId, CanonicalGameplayAccountCoverageEvidence expected) {
    Record control =
        transaction.fetchOne(
            "SELECT * FROM game_session_canonical_account_coverage_control WHERE account_id = ?",
            accountId);
    if (control == null
        || !accountCoverageEvidenceFromControl(control, accountId).equals(expected)) {
      throw conflict("First-binding account-coverage fence is missing or changed");
    }
  }

  private static CanonicalGameplayAccountCoverageEvidence accountCoverageEvidenceFromControl(
      Record control, UUID accountId) {
    String state = control.get("state", String.class);
    if ("ACTIVE".equals(state)) {
      throw conflict("Account-wide admission fence blocks a new binding transition");
    }
    if ("ACQUISITION_INTENT".equals(state)) {
      throw conflict("Account-fence acquisition intent blocks a new binding transition");
    }
    if ("NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(state)) {
      return CanonicalGameplayAccountCoverageEvidence.noActiveFlow();
    }
    if ("HISTORICAL".equals(state)) {
      return CanonicalGameplayAccountCoverageEvidence.historical(
          control.get("current_operation_id", UUID.class),
          readBigInteger(control, "current_operation_fence"),
          control.get("historical_account_admission_fence", UUID.class),
          control.get("coverage_fence", UUID.class),
          accountId,
          readBigInteger(control, "inventory_snapshot_revision"),
          readBigInteger(control, "coverage_generation"));
    }
    throw conflict("Account coverage control state is unknown and cannot authorize a transition");
  }

  private static void requireNoActiveAccountControl(AccountCoverageControl control) {
    if (!"NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(control.state())
        || control.currentOperationId() != null
        || control.currentOperationFence() != null
        || control.accountAdmissionFence() != null
        || control.historicalAccountAdmissionFence() != null
        || control.coverageFence() != null
        || control.inventorySnapshotRevision() != null
        || control.coverageGeneration() != null
        || !(control.evidence() instanceof CanonicalGameplayAccountCoverageEvidence.NoActiveFlow)) {
      throw conflict("Account-wide control is not the positively read no-active state");
    }
    if (control.epoch().signum() < 0) {
      throw conflict("Account-wide control has an invalid fence epoch");
    }
  }

  private static void requireOrdinaryNewMemberRepairTransition(
      CanonicalGameplayBindingProvisionalCasEvidence evidence) {
    if (evidence.transition().status()
            != CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL
        || evidence.candidate().lifecycle()
            != CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL
        || evidence.expectedPrior() != null
        || evidence.expectedPriorReservation() != null
        || evidence.transition().expectedPriorBindingRef() != null
        || evidence.transition().expectedPriorBindingGeneration() != null
        || !(evidence.transition().accountCoverageEvidence()
            instanceof CanonicalGameplayAccountCoverageEvidence.NoActiveFlow)) {
      throw conflict(
          "Binding-local member repair requires an ordinary NEW provisional transition in the no-active arm");
    }
  }

  private static void requireMemberRepairObligationCurrent(
      CanonicalGameplayAccountMemberRepairOperation operation,
      CanonicalGameplayBindingInventoryEntry candidate,
      CanonicalGameplayBindingAccountIndexObligation current,
      boolean completed) {
    CanonicalGameplayBindingAccountIndexObligation original = operation.originalObligation();
    if (!current.transitionId().equals(operation.transitionId())
        || current.ordinal() != original.ordinal()
        || !current.bindingRef().equals(operation.bindingRef())
        || !current.accountId().equals(operation.accountId())
        || !current.tenantId().equals(operation.tenantId())
        || current.action() != original.action()
        || !current.bindingGeneration().equals(operation.bindingGeneration())
        || !Objects.equals(current.expectedPriorGeneration(), original.expectedPriorGeneration())
        || !current.accountIndexFence().equals(operation.accountIndexFence())
        || current.executionPhase() != original.executionPhase()
        || !current.inventoryRevision().equals(candidate.inventoryRevision())) {
      throw conflict("Current account-index obligation differs from its immutable original tuple");
    }
    if (completed) {
      if (current.status() != CanonicalGameplayBindingAccountIndexObligation.Status.ACKNOWLEDGED
          || current.projectionState()
              != CanonicalGameplayBindingAccountIndexObligation.ProjectionState
                  .PRESENT_AWAITING_COVERAGE
          || !operation.member().equals(current.projectedMember())
          || (original.projectionState()
                  == CanonicalGameplayBindingAccountIndexObligation.ProjectionState
                      .PRESENT_AWAITING_COVERAGE
              && !Objects.equals(
                  original.projectionInventoryRevision(), current.projectionInventoryRevision()))
          || candidate.accountIndexState()
              != CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED) {
        throw conflict("Completed member repair lost its exact durable acknowledgement state");
      }
    } else {
      if (current.status() != CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED
          || candidate.accountIndexState()
              != CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED) {
        throw conflict("Pending member repair no longer owns its unresolved candidate obligation");
      }
      if (current.projectionState()
          == CanonicalGameplayBindingAccountIndexObligation.ProjectionState.REQUIRED) {
        if (original.projectionState()
                != CanonicalGameplayBindingAccountIndexObligation.ProjectionState.REQUIRED
            || current.projectedMember() != null
            || current.projectionInventoryRevision() != null) {
          throw conflict("Current account-index projection differs from its frozen original state");
        }
      } else if (current.projectionState()
          == CanonicalGameplayBindingAccountIndexObligation.ProjectionState
              .PRESENT_AWAITING_COVERAGE) {
        if (!operation.member().equals(current.projectedMember())) {
          throw conflict("Retained PRESENT observation differs from the exact repair member");
        }
        if (original.projectionState()
                == CanonicalGameplayBindingAccountIndexObligation.ProjectionState
                    .PRESENT_AWAITING_COVERAGE
            && (!Objects.equals(original.projectedMember(), current.projectedMember())
                || !Objects.equals(
                    original.projectionInventoryRevision(),
                    current.projectionInventoryRevision()))) {
          throw conflict("Original PRESENT observation changed after the repair was frozen");
        }
      } else {
        throw conflict("Pending ADD_OR_RETAIN has an unsupported account-index projection state");
      }
    }
  }

  private static Record findMemberRepairOperation(
      DSLContext transaction, UUID transitionId, boolean forUpdate) {
    return transaction.fetchOne(
        "SELECT * FROM game_session_canonical_account_member_repair_operation"
            + " WHERE transition_id = ? AND obligation_ordinal = 0"
            + (forUpdate ? " FOR UPDATE" : ""),
        transitionId);
  }

  private static BigInteger allocateMemberRepairFence(DSLContext transaction, UUID accountId) {
    transaction.execute(
        "INSERT INTO game_session_canonical_account_member_repair_control "
            + "(account_id, last_operation_fence) VALUES (?, 0) ON CONFLICT (account_id) DO NOTHING",
        accountId);
    Record fenceRow =
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT last_operation_fence FROM "
                    + "game_session_canonical_account_member_repair_control "
                    + "WHERE account_id = ? FOR UPDATE",
                accountId),
            "account member-repair fence row must exist");
    BigInteger previous = readBigInteger(fenceRow, "last_operation_fence");
    BigInteger next = previous.add(BigInteger.ONE);
    requireOne(
        transaction.execute(
            "UPDATE game_session_canonical_account_member_repair_control "
                + "SET last_operation_fence = ? WHERE account_id = ? AND last_operation_fence = ?",
            decimal(next),
            accountId,
            decimal(previous)),
        "Account member-repair operation fence compare-and-set lost its locked row");
    return next;
  }

  private static BigInteger allocateMemberRepairCoverageGeneration(
      DSLContext transaction, UUID accountId, BigInteger inventorySnapshotRevision) {
    Record generationRow =
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT last_coverage_generation FROM "
                    + "game_session_canonical_account_member_repair_control "
                    + "WHERE account_id = ? FOR UPDATE",
                accountId),
            "account member-repair generation row must exist");
    BigInteger previous = readBigInteger(generationRow, "last_coverage_generation");
    BigInteger next = previous.add(BigInteger.ONE);
    if (next.compareTo(inventorySnapshotRevision) <= 0) {
      next = inventorySnapshotRevision.add(BigInteger.ONE);
    }
    requireOne(
        transaction.execute(
            "UPDATE game_session_canonical_account_member_repair_control "
                + "SET last_coverage_generation = ? WHERE account_id = ?"
                + " AND last_coverage_generation = ?",
            decimal(next),
            accountId,
            decimal(previous)),
        "Account member-repair coverage generation compare-and-set lost its locked row");
    return next;
  }

  private static UUID freshNonNilUuid() {
    UUID value;
    do {
      value = UUID.randomUUID();
    } while (value.equals(NIL_UUID));
    return value;
  }

  private static UUID freshOperationId(UUID transitionId) {
    UUID value;
    do {
      value = freshNonNilUuid();
    } while (value.equals(transitionId));
    return value;
  }

  private static UUID freshDistinctCoverageFence(
      UUID operationId, UUID transitionId, UUID accountIndexFence) {
    UUID coverageFence;
    do {
      coverageFence = freshNonNilUuid();
    } while (coverageFence.equals(operationId)
        || coverageFence.equals(transitionId)
        || coverageFence.equals(accountIndexFence));
    return coverageFence;
  }

  private static void insertMemberRepairOperation(
      DSLContext transaction, CanonicalGameplayAccountMemberRepairOperation operation) {
    CanonicalGameplayBindingAccountIndexObligation original = operation.originalObligation();
    requireOne(
        transaction.execute(
            "INSERT INTO game_session_canonical_account_member_repair_operation "
                + "(operation_id, transition_id, obligation_ordinal, operation_fence, coverage_fence,"
                + " inventory_snapshot_revision, binding_ref, account_id, tenant_id, binding_generation,"
                + " account_index_fence, original_action, original_expected_prior_generation,"
                + " original_execution_phase, original_status, original_inventory_revision,"
                + " original_projection_state, original_projection_member,"
                + " original_projection_inventory_revision, desired_action, member,"
                + " account_control_state, account_control_epoch, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " 'NO_ACTIVE_ACCOUNT_WIDE_FLOW', ?, 'PENDING')",
            operation.operationId(),
            operation.transitionId(),
            original.ordinal(),
            decimal(operation.operationFence()),
            operation.coverageFence(),
            decimal(operation.inventorySnapshotRevision()),
            operation.bindingRef().bytes(),
            operation.accountId(),
            operation.tenantId(),
            decimal(operation.bindingGeneration()),
            operation.accountIndexFence(),
            original.action().name(),
            decimalNullable(original.expectedPriorGeneration()),
            original.executionPhase().name(),
            original.status().name(),
            decimal(original.inventoryRevision()),
            original.projectionState().name(),
            original.projectedMember(),
            decimalNullable(original.projectionInventoryRevision()),
            original.action().name(),
            operation.member(),
            decimal(operation.accountControlEpoch())),
        "Account member-repair operation insert conflicted with an existing obligation");
  }

  private static CanonicalGameplayAccountMemberRepairOperation toMemberRepairOperation(Record row) {
    try {
      if (!"ADD_OR_RETAIN".equals(row.get("desired_action", String.class))
          || !"NO_ACTIVE_ACCOUNT_WIDE_FLOW"
              .equals(row.get("account_control_state", String.class))) {
        throw conflict("Durable member-repair operation carries an unsupported action or scope");
      }
      CanonicalGameplayBindingRef bindingRef =
          CanonicalGameplayBindingRef.fromExactBytes(row.get("binding_ref", byte[].class));
      UUID accountId = row.get("account_id", UUID.class);
      UUID tenantId = row.get("tenant_id", UUID.class);
      if (!bindingRef.tenantId().equals(tenantId.toString())) {
        throw conflict("Durable member-repair bindingRef belongs to a different tenant");
      }
      CanonicalGameplayBindingAccountIndexObligation original =
          new CanonicalGameplayBindingAccountIndexObligation(
              row.get("transition_id", UUID.class),
              row.get("obligation_ordinal", Integer.class),
              bindingRef,
              accountId,
              tenantId,
              CanonicalGameplayBindingAccountIndexObligation.Action.valueOf(
                  row.get("original_action", String.class)),
              readBigInteger(row, "binding_generation"),
              nullableBigInteger(row, "original_expected_prior_generation"),
              row.get("account_index_fence", UUID.class),
              CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.valueOf(
                  row.get("original_execution_phase", String.class)),
              CanonicalGameplayBindingAccountIndexObligation.Status.valueOf(
                  row.get("original_status", String.class)),
              readBigInteger(row, "original_inventory_revision"),
              CanonicalGameplayBindingAccountIndexObligation.ProjectionState.valueOf(
                  row.get("original_projection_state", String.class)),
              row.get("original_projection_member", String.class),
              nullableBigInteger(row, "original_projection_inventory_revision"));
      return new CanonicalGameplayAccountMemberRepairOperation(
          row.get("transition_id", UUID.class),
          row.get("operation_id", UUID.class),
          readBigInteger(row, "operation_fence"),
          row.get("coverage_fence", UUID.class),
          readBigInteger(row, "inventory_snapshot_revision"),
          bindingRef,
          accountId,
          tenantId,
          readBigInteger(row, "binding_generation"),
          row.get("account_index_fence", UUID.class),
          original,
          row.get("member", String.class),
          readBigInteger(row, "account_control_epoch"));
    } catch (CanonicalGameplayBindingInventoryConflictException classified) {
      throw classified;
    } catch (RuntimeException malformed) {
      throw conflict("Durable account-member repair operation is incomplete or invalid");
    }
  }

  private static CanonicalGameplayAccountMemberAcknowledgement toMemberAcknowledgement(
      Record operationRow) {
    if (!"ACKNOWLEDGED".equals(operationRow.get("status", String.class))
        || !"PRESENT".equals(operationRow.get("readback_state", String.class))) {
      throw conflict(
          "Durable account-member repair has no complete exact readback acknowledgement");
    }
    try {
      return new CanonicalGameplayAccountMemberAcknowledgement(
          toMemberRepairOperation(operationRow),
          readBigInteger(operationRow, "coverage_generation"),
          operationRow.get("readback_member", String.class));
    } catch (RuntimeException malformed) {
      throw conflict("Durable binding-local account-index acknowledgement is invalid");
    }
  }

  private static void persistMemberRepairAcknowledgement(
      DSLContext transaction,
      CanonicalGameplayAccountMemberRepairOperation operation,
      CanonicalGameplayBindingProvisionalCasEvidence current,
      BigInteger nextRevision,
      BigInteger coverageGeneration,
      String member) {
    BigInteger transitionRevision = current.transition().inventoryRevision();
    requireOne(
        transaction.execute(
            "UPDATE "
                + TRANSITION
                + " SET inventory_revision = ?"
                + " WHERE transition_id = ? AND status = 'PROVISIONAL'"
                + " AND inventory_revision = ? AND candidate_binding_ref = ?"
                + " AND candidate_binding_generation = ? AND candidate_account_index_fence = ?"
                + " AND account_coverage_state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'"
                + " AND account_coverage_operation_id IS NULL"
                + " AND account_coverage_operation_fence IS NULL"
                + " AND account_coverage_lifecycle IS NULL"
                + " AND account_admission_fence_kind = 'NOT_APPLICABLE'"
                + " AND account_admission_fence IS NULL AND historical_account_admission_fence IS NULL"
                + " AND historical_account_admission_fence_kind = 'NOT_APPLICABLE'"
                + " AND resolved_scope_kind = 'NOT_APPLICABLE'"
                + " AND resolved_scope_account_id IS NULL AND account_coverage_fence IS NULL"
                + " AND account_coverage_generation IS NULL AND account_inventory_snapshot_revision IS NULL",
            decimal(nextRevision),
            operation.transitionId(),
            decimal(transitionRevision),
            operation.bindingRef().bytes(),
            decimal(operation.bindingGeneration()),
            operation.accountIndexFence()),
        "Durable NEW transition changed before member acknowledgement");
    requireOne(
        transaction.execute(
            "UPDATE "
                + INVENTORY
                + " SET account_index_state = 'ACKNOWLEDGED', inventory_revision = ?"
                + " WHERE binding_ref = ? AND transition_id = ? AND lifecycle = 'PROVISIONAL'"
                + " AND binding_generation = ? AND account_index_fence = ?"
                + " AND account_id = ? AND tenant_id = ?"
                + " AND account_index_state = 'REPAIR_REQUIRED' AND inventory_revision = ?",
            decimal(nextRevision),
            operation.bindingRef().bytes(),
            operation.transitionId(),
            decimal(operation.bindingGeneration()),
            operation.accountIndexFence(),
            operation.accountId(),
            operation.tenantId(),
            decimal(transitionRevision)),
        "Durable NEW candidate changed before member acknowledgement");
    requireOne(
        transaction.execute(
            "UPDATE "
                + ACCOUNT_OBLIGATION
                + " SET status = 'ACKNOWLEDGED', projection_state = 'PRESENT_AWAITING_COVERAGE',"
                + " projection_member = ?, projection_inventory_revision = COALESCE("
                + " projection_inventory_revision, ?), inventory_revision = ?"
                + " WHERE transition_id = ? AND obligation_ordinal = 0"
                + " AND binding_ref = ? AND account_id = ? AND tenant_id = ?"
                + " AND action = 'ADD_OR_RETAIN' AND binding_generation = ?"
                + " AND expected_prior_generation IS NULL AND account_index_fence = ?"
                + " AND execution_phase = 'BEFORE_FINAL_CAS' AND status = 'REQUIRED'"
                + " AND inventory_revision = ?"
                + " AND ((projection_state = 'REQUIRED' AND projection_member IS NULL"
                + " AND projection_inventory_revision IS NULL)"
                + " OR (projection_state = 'PRESENT_AWAITING_COVERAGE' AND projection_member = ?))",
            member,
            decimal(nextRevision),
            decimal(nextRevision),
            operation.transitionId(),
            operation.bindingRef().bytes(),
            operation.accountId(),
            operation.tenantId(),
            decimal(operation.bindingGeneration()),
            operation.accountIndexFence(),
            decimal(transitionRevision),
            member),
        "Original account-index obligation changed before complete v2 acknowledgement");
    requireCount(
        transaction.execute(
            "UPDATE "
                + ACCOUNT_OBLIGATION
                + " SET inventory_revision = ? WHERE transition_id = ? AND obligation_ordinal = 1"
                + " AND inventory_revision = ?",
            decimal(nextRevision),
            operation.transitionId(),
            decimal(transitionRevision)),
        0,
        "Ordinary NEW unexpectedly owns a prior account-index obligation");
    requireOne(
        transaction.execute(
            "UPDATE "
                + ISSUER_OBLIGATION
                + " SET inventory_revision = ? WHERE transition_id = ? AND binding_ref = ?"
                + " AND status = 'REQUIRED' AND inventory_revision = ?",
            decimal(nextRevision),
            operation.transitionId(),
            operation.bindingRef().bytes(),
            decimal(transitionRevision)),
        "Issuer obligation changed before member acknowledgement");
    requireOne(
        transaction.execute(
            "UPDATE "
                + REGION_OBLIGATION
                + " SET inventory_revision = ? WHERE transition_id = ? AND binding_ref = ?"
                + " AND status IN ('REQUIRED', 'ACKNOWLEDGED') AND inventory_revision = ?",
            decimal(nextRevision),
            operation.transitionId(),
            operation.bindingRef().bytes(),
            decimal(transitionRevision)),
        "Region obligation changed before member acknowledgement");
    requireOne(
        transaction.execute(
            "UPDATE "
                + RESERVATION
                + " SET inventory_revision = ? WHERE reservation_id = ? AND transition_id = ?"
                + " AND binding_ref = ? AND lifecycle = 'RESERVED' AND inventory_revision = ?",
            decimal(nextRevision),
            current.transition().issuerReservationId(),
            operation.transitionId(),
            operation.bindingRef().bytes(),
            decimal(transitionRevision)),
        "Issuer reservation changed before member acknowledgement");
    requireOne(
        transaction.execute(
            "UPDATE "
                + "game_session_canonical_account_member_repair_operation"
                + " SET status = 'ACKNOWLEDGED', coverage_generation = ?, readback_state = 'PRESENT',"
                + " readback_member = ?, completed_inventory_revision = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE operation_id = ? AND transition_id = ? AND obligation_ordinal = 0"
                + " AND operation_fence = ? AND coverage_fence = ?"
                + " AND inventory_snapshot_revision = ? AND binding_ref = ?"
                + " AND account_id = ? AND tenant_id = ? AND binding_generation = ?"
                + " AND account_index_fence = ? AND member = ? AND account_control_epoch = ?"
                + " AND original_action = 'ADD_OR_RETAIN'"
                + " AND original_expected_prior_generation IS NULL"
                + " AND original_execution_phase = 'BEFORE_FINAL_CAS'"
                + " AND original_status = 'REQUIRED' AND original_inventory_revision = ?"
                + " AND original_projection_state = ?"
                + " AND original_projection_member IS NOT DISTINCT FROM ?"
                + " AND original_projection_inventory_revision IS NOT DISTINCT FROM ?"
                + " AND desired_action = 'ADD_OR_RETAIN'"
                + " AND account_control_state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'"
                + " AND status = 'PENDING' AND coverage_generation IS NULL"
                + " AND readback_state IS NULL AND readback_member IS NULL",
            decimal(coverageGeneration),
            member,
            decimal(nextRevision),
            operation.operationId(),
            operation.transitionId(),
            decimal(operation.operationFence()),
            operation.coverageFence(),
            decimal(operation.inventorySnapshotRevision()),
            operation.bindingRef().bytes(),
            operation.accountId(),
            operation.tenantId(),
            decimal(operation.bindingGeneration()),
            operation.accountIndexFence(),
            member,
            decimal(operation.accountControlEpoch()),
            decimal(operation.originalObligation().inventoryRevision()),
            operation.originalObligation().projectionState().name(),
            operation.originalObligation().projectedMember(),
            decimalNullable(operation.originalObligation().projectionInventoryRevision())),
        "Durable member-repair operation changed before acknowledgement persistence");
    requireOne(
        transaction.execute(
            "UPDATE game_session_canonical_binding_inventory_clock"
                + " SET inventory_revision = ? WHERE singleton_id = 1 AND inventory_revision = ?",
            decimal(nextRevision),
            decimal(nextRevision.subtract(BigInteger.ONE))),
        "Inventory revision compare-and-set lost during member acknowledgement");
  }

  private static void requireAccountCoverageEvidenceCurrent(
      DSLContext transaction, UUID accountId, CanonicalGameplayAccountCoverageEvidence expected) {
    CanonicalGameplayAccountCoverageEvidence current =
        lockAccountCoverageEvidence(transaction, accountId);
    if (!current.equals(expected)) {
      throw conflict("Binding transition account-coverage carrier is stale or mismatched");
    }
  }

  private static Record findTransition(DSLContext transaction, UUID transitionId) {
    return transaction.fetchOne(
        "SELECT * FROM " + TRANSITION + " WHERE transition_id = ? FOR UPDATE", transitionId);
  }

  /** Reads a transition without acquiring a row lock for callers in read-only transactions. */
  private static Record readTransition(DSLContext transaction, UUID transitionId) {
    return transaction.fetchOne(
        "SELECT * FROM " + TRANSITION + " WHERE transition_id = ?", transitionId);
  }

  private static Record findActiveController(
      DSLContext transaction, CanonicalGameplayBindingIdentity candidate) {
    return transaction.fetchOne(
        "SELECT * FROM "
            + INVENTORY
            + " WHERE tenant_id = ? AND playable_state_namespace_id = ? AND character_id = ?"
            + " AND lifecycle = 'ACTIVE' FOR UPDATE",
        candidate.tenantId(),
        candidate.playableStateNamespaceId(),
        candidate.characterId());
  }

  private static Record findUnresolvedController(
      DSLContext transaction, CanonicalGameplayBindingIdentity candidate) {
    Record openTransition =
        transaction.fetchOne(
            "SELECT transition_id FROM "
                + TRANSITION
                + " WHERE tenant_id = ? AND playable_state_namespace_id = ? AND character_id = ?"
                + " AND status IN ('PREPARED', 'PROVISIONAL', 'AMBIGUOUS') LIMIT 1",
            candidate.tenantId(),
            candidate.playableStateNamespaceId(),
            candidate.characterId());
    if (openTransition != null) {
      return openTransition;
    }
    return transaction.fetchOne(
        "SELECT transition_id FROM "
            + INVENTORY
            + " WHERE tenant_id = ? AND playable_state_namespace_id = ? AND character_id = ?"
            + " AND lifecycle IN ('CANDIDATE_PREPARED', 'PROVISIONAL', 'TERMINAL_UNRESOLVED') LIMIT 1",
        candidate.tenantId(),
        candidate.playableStateNamespaceId(),
        candidate.characterId());
  }

  private static CanonicalGameplayBindingTransitionSnapshot requireExactRetry(
      DSLContext transaction,
      CanonicalGameplayBindingTransitionRequest request,
      Record transition) {
    CanonicalGameplayBindingIdentity candidate = request.candidate();
    CanonicalGameplayBindingRef expectedRef = request.expectedPriorBindingRef();
    byte[] storedPriorRef = transition.get("expected_prior_binding_ref", byte[].class);
    CanonicalGameplayBindingRef storedPriorBindingRef =
        storedPriorRef == null ? null : CanonicalGameplayBindingRef.fromExactBytes(storedPriorRef);
    BigInteger storedPriorGeneration =
        nullableBigInteger(transition, "expected_prior_binding_generation");
    String storedStatus = transition.get("status", String.class);
    String expectedCandidateLifecycle =
        "PROVISIONAL".equals(storedStatus) ? "PROVISIONAL" : "CANDIDATE_PREPARED";
    if (!Objects.equals(transition.get("transition_id", UUID.class), request.transitionId())
        || (!"PREPARED".equals(storedStatus) && !"PROVISIONAL".equals(storedStatus))
        || !Objects.equals(storedPriorBindingRef, expectedRef)
        || !Objects.equals(storedPriorGeneration, request.expectedPriorBindingGeneration())
        || !Objects.equals(transition.get("tenant_id", UUID.class), candidate.tenantId())
        || !Objects.equals(
            transition.get("playable_state_namespace_id", UUID.class),
            candidate.playableStateNamespaceId())
        || !Objects.equals(transition.get("character_id", UUID.class), candidate.characterId())
        || !Arrays.equals(
            transition.get("candidate_binding_ref", byte[].class), candidate.bindingRef().bytes())
        || !Objects.equals(
            transition.get("candidate_account_index_fence", UUID.class),
            request.candidateAccountIndexFence())
        || !Objects.equals(
            transition.get("issuer_reservation_id", UUID.class),
            request.issuerReservation().reservationId())) {
      throw conflict("The same transition identity was retried with a different exact request");
    }
    if ("PROVISIONAL".equals(storedStatus) && expectedRef == null) {
      // Keep the transition-first row-lock order and verify any completed member ACK from its
      // durable operation before this exact NEW retry accepts the acknowledged candidate state.
      loadProvisionalCasEvidence(transaction, request.transitionId(), true);
    }
    if (expectedRef == null && findActiveController(transaction, candidate) != null) {
      throw conflict("A first-binding retry cannot proceed while an active controller exists");
    }
    Record binding =
        transaction.fetchOne(
            "SELECT * FROM " + INVENTORY + " WHERE binding_ref = ? FOR UPDATE",
            candidate.bindingRef().bytes());
    boolean acknowledgedOrdinaryNewRetry =
        "PROVISIONAL".equals(storedStatus)
            && expectedRef == null
            && binding != null
            && "ACKNOWLEDGED".equals(binding.get("account_index_state", String.class));
    if (binding == null
        || !toIdentity(binding).equals(candidate)
        || !expectedCandidateLifecycle.equals(binding.get("lifecycle", String.class))
        || (!"REPAIR_REQUIRED".equals(binding.get("account_index_state", String.class))
            && !acknowledgedOrdinaryNewRetry)
        || !"REPAIR_REQUIRED".equals(binding.get("issuer_index_state", String.class))
        || !readBigInteger(binding, "binding_generation")
            .equals(readBigInteger(transition, "candidate_binding_generation"))
        || !readBigInteger(binding, "inventory_revision")
            .equals(readBigInteger(transition, "inventory_revision"))
        || !Objects.equals(
            binding.get("account_index_fence", UUID.class), request.candidateAccountIndexFence())
        || !Objects.equals(
            binding.get("issuer_reservation_id", UUID.class),
            request.issuerReservation().reservationId())) {
      throw conflict("Stored candidate identity differs from the exact transition retry");
    }
    Record reservation =
        transaction.fetchOne(
            "SELECT * FROM " + RESERVATION + " WHERE reservation_id = ? FOR UPDATE",
            request.issuerReservation().reservationId());
    if (reservation == null
        || !toIdentity(reservation).equals(candidate)
        || !Arrays.equals(
            reservation.get("binding_ref", byte[].class), candidate.bindingRef().bytes())
        || !Objects.equals(
            reservation.get("reservation_fence", UUID.class),
            request.issuerReservation().reservationFence())
        || !Objects.equals(
            reservation.get("issuer_coverage_operation_id", UUID.class),
            request.issuerReservation().issuerCoverageOperationId())
        || !readBigInteger(reservation, "issuer_coverage_operation_fence")
            .equals(request.issuerReservation().issuerCoverageOperationFence())
        || !readBigInteger(reservation, "coverage_fence")
            .equals(request.issuerReservation().coverageFence())
        || !readBigInteger(reservation, "inventory_snapshot_revision")
            .equals(request.issuerReservation().inventorySnapshotRevision())
        || !Objects.equals(reservation.get("account_id", UUID.class), candidate.accountId())
        || !Objects.equals(reservation.get("tenant_id", UUID.class), candidate.tenantId())
        || !Objects.equals(
            reservation.get("game_instance_id", UUID.class), candidate.gameInstanceId())
        || !Objects.equals(
            reservation.get("runtime_game_instance_id", Long.class),
            candidate.runtimeGameInstanceId())
        || !Objects.equals(reservation.get("session_id", String.class), candidate.sessionId())
        || !Objects.equals(reservation.get("issuer_id", String.class), candidate.issuerId())
        || !readBigInteger(reservation, "issuer_auth_generation")
            .equals(candidate.issuerAuthGeneration())
        || !readBigInteger(reservation, "issuer_index_layout_version")
            .equals(candidate.issuerIndexLayoutVersion())
        || !Objects.equals(
            readBigInteger(reservation, "issuer_index_partition_count"),
            candidate.issuerIndexPartitionCount())
        || !Objects.equals(
            readBigInteger(reservation, "issuer_index_partition_capacity"),
            candidate.issuerIndexPartitionCapacity())
        || !Objects.equals(
            readBigInteger(reservation, "partition_id"),
            candidate.bindingRef().partitionId(candidate.issuerIndexPartitionCount()))) {
      throw conflict("Stored reservation evidence differs from the exact transition retry");
    }
    requireExactStoredObligations(transaction, request, transition, binding, reservation);
    return toTransitionSnapshot(transition, binding, reservation);
  }

  private static void requireExactStoredObligations(
      DSLContext transaction,
      CanonicalGameplayBindingTransitionRequest request,
      Record transition,
      Record candidate,
      Record reservation) {
    CanonicalGameplayBindingRef expectedPriorBindingRef = request.expectedPriorBindingRef();
    byte[] expectedPriorRef =
        expectedPriorBindingRef == null ? null : expectedPriorBindingRef.bytes();
    Record source =
        expectedPriorRef == null
            ? null
            : transaction.fetchOne(
                "SELECT * FROM "
                    + INVENTORY
                    + " WHERE binding_ref = ? AND lifecycle = 'ACTIVE' FOR UPDATE",
                expectedPriorRef);
    if (expectedPriorRef != null
        && (source == null
            || !readBigInteger(source, "binding_generation")
                .equals(request.expectedPriorBindingGeneration()))) {
      throw conflict("Stored exact prior controller evidence is no longer active");
    }
    var obligations =
        transaction.fetch(
            "SELECT * FROM "
                + ACCOUNT_OBLIGATION
                + " WHERE transition_id = ? ORDER BY obligation_ordinal FOR UPDATE",
            request.transitionId());
    int expectedCount = source == null ? 1 : 2;
    if (obligations.size() != expectedCount) {
      throw conflict("Stored transition does not retain every required account-index obligation");
    }
    Record add = obligations.get(0);
    boolean completedOrdinaryNewMemberRepair =
        "PROVISIONAL".equals(transition.get("status", String.class))
            && expectedPriorBindingRef == null
            && "ACKNOWLEDGED".equals(add.get("status", String.class))
            && "PRESENT_AWAITING_COVERAGE".equals(add.get("projection_state", String.class))
            && "ACKNOWLEDGED".equals(candidate.get("account_index_state", String.class));
    BigInteger candidateGeneration = readBigInteger(transition, "candidate_binding_generation");
    BigInteger expectedPriorGeneration = request.expectedPriorBindingGeneration();
    if (!Arrays.equals(
            add.get("binding_ref", byte[].class), request.candidate().bindingRef().bytes())
        || !"ADD_OR_RETAIN".equals(add.get("action", String.class))
        || !"BEFORE_FINAL_CAS".equals(add.get("execution_phase", String.class))
        || (!"REQUIRED".equals(add.get("status", String.class))
            && !completedOrdinaryNewMemberRepair)
        || !readBigInteger(add, "binding_generation").equals(candidateGeneration)
        || !Objects.equals(add.get("account_id", UUID.class), request.candidate().accountId())
        || !Objects.equals(add.get("tenant_id", UUID.class), request.candidate().tenantId())
        || !Objects.equals(
            nullableBigInteger(add, "expected_prior_generation"), expectedPriorGeneration)
        || !Objects.equals(
            add.get("account_index_fence", UUID.class), request.candidateAccountIndexFence())
        || !readBigInteger(add, "inventory_revision")
            .equals(readBigInteger(transition, "inventory_revision"))) {
      throw conflict("Stored candidate account-index obligation differs from the exact retry");
    }
    if (source != null) {
      Record remove = obligations.get(1);
      if (!Arrays.equals(remove.get("binding_ref", byte[].class), expectedPriorRef)
          || !"REMOVE".equals(remove.get("action", String.class))
          || !"AFTER_FINAL_CAS".equals(remove.get("execution_phase", String.class))
          || !"REQUIRED".equals(remove.get("status", String.class))
          || !Objects.equals(
              remove.get("account_id", UUID.class), source.get("account_id", UUID.class))
          || !Objects.equals(
              remove.get("tenant_id", UUID.class), source.get("tenant_id", UUID.class))
          || !readBigInteger(remove, "binding_generation")
              .equals(request.expectedPriorBindingGeneration())
          || !readBigInteger(remove, "expected_prior_generation")
              .equals(request.expectedPriorBindingGeneration())
          || !Objects.equals(
              remove.get("account_index_fence", UUID.class),
              source.get("account_index_fence", UUID.class))
          || !readBigInteger(remove, "inventory_revision")
              .equals(readBigInteger(transition, "inventory_revision"))) {
        throw conflict("Stored prior account-index obligation differs from the exact retry");
      }
    }
    Record issuerObligation =
        transaction.fetchOne(
            "SELECT * FROM "
                + ISSUER_OBLIGATION
                + " WHERE transition_id = ? AND binding_ref = ? FOR UPDATE",
            request.transitionId(),
            request.candidate().bindingRef().bytes());
    if (issuerObligation == null
        || !"REQUIRED".equals(issuerObligation.get("status", String.class))
        || !Arrays.equals(
            issuerObligation.get("binding_ref", byte[].class),
            request.candidate().bindingRef().bytes())
        || !Objects.equals(
            issuerObligation.get("reservation_id", UUID.class),
            request.issuerReservation().reservationId())
        || !readBigInteger(issuerObligation, "binding_generation").equals(candidateGeneration)
        || !readBigInteger(issuerObligation, "inventory_revision")
            .equals(readBigInteger(transition, "inventory_revision"))) {
      throw conflict("Stored issuer-index obligation differs from the exact retry");
    }
    Record regionObligation =
        transaction.fetchOne(
            "SELECT * FROM " + REGION_OBLIGATION + " WHERE transition_id = ? FOR UPDATE",
            request.transitionId());
    String regionStatus =
        regionObligation == null ? null : regionObligation.get("status", String.class);
    if (regionObligation == null
        || (!"REQUIRED".equals(regionStatus) && !"ACKNOWLEDGED".equals(regionStatus))
        || ("ACKNOWLEDGED".equals(regionStatus) && expectedPriorRef != null)
        || !Arrays.equals(
            regionObligation.get("binding_ref", byte[].class),
            request.candidate().bindingRef().bytes())
        || !readBigInteger(regionObligation, "binding_generation").equals(candidateGeneration)
        || !Objects.equals(
            regionObligation.get("region_id", UUID.class), request.candidate().regionId())
        || !Objects.equals(
            regionObligation.get("account_id", UUID.class), request.candidate().accountId())
        || !Objects.equals(
            regionObligation.get("tenant_id", UUID.class), request.candidate().tenantId())
        || !Objects.equals(
            regionObligation.get("game_instance_id", UUID.class),
            request.candidate().gameInstanceId())
        || !Objects.equals(
            regionObligation.get("runtime_game_instance_id", Long.class),
            request.candidate().runtimeGameInstanceId())
        || !Objects.equals(
            regionObligation.get("session_id", String.class), request.candidate().sessionId())
        || !readBigInteger(regionObligation, "region_epoch")
            .equals(request.candidate().regionEpoch())
        || !readBigInteger(regionObligation, "inventory_revision")
            .equals(readBigInteger(transition, "inventory_revision"))) {
      throw conflict("Stored region bridge request differs from the exact retry");
    }
    if (!Objects.equals(reservation.get("transition_id", UUID.class), request.transitionId())
        || !Objects.equals(
            reservation.get("account_id", UUID.class), request.candidate().accountId())
        || !Objects.equals(reservation.get("tenant_id", UUID.class), request.candidate().tenantId())
        || !Objects.equals(
            reservation.get("game_instance_id", UUID.class), request.candidate().gameInstanceId())
        || !Objects.equals(
            reservation.get("runtime_game_instance_id", Long.class),
            request.candidate().runtimeGameInstanceId())
        || !Objects.equals(
            reservation.get("session_id", String.class), request.candidate().sessionId())
        || !Objects.equals(
            reservation.get("issuer_id", String.class), request.candidate().issuerId())
        || !readBigInteger(reservation, "issuer_auth_generation")
            .equals(request.candidate().issuerAuthGeneration())
        || !readBigInteger(reservation, "issuer_index_layout_version")
            .equals(request.candidate().issuerIndexLayoutVersion())
        || !Objects.equals(
            readBigInteger(reservation, "issuer_index_partition_count"),
            request.candidate().issuerIndexPartitionCount())
        || !Objects.equals(
            readBigInteger(reservation, "issuer_index_partition_capacity"),
            request.candidate().issuerIndexPartitionCapacity())
        || !Objects.equals(
            readBigInteger(reservation, "partition_id"),
            request
                .candidate()
                .bindingRef()
                .partitionId(request.candidate().issuerIndexPartitionCount()))
        || !readBigInteger(reservation, "inventory_revision")
            .equals(readBigInteger(transition, "inventory_revision"))
        || !Arrays.equals(
            reservation.get("binding_ref", byte[].class), request.candidate().bindingRef().bytes())
        || !readBigInteger(reservation, "binding_generation").equals(candidateGeneration)
        || !"RESERVED".equals(reservation.get("lifecycle", String.class))) {
      throw conflict("Stored issuer reservation owner or lifecycle differs from the exact retry");
    }
  }

  private static void insertCandidate(
      DSLContext transaction,
      CanonicalGameplayBindingTransitionRequest request,
      CanonicalGameplayBindingTransitionSnapshot prepared,
      BigInteger partitionId) {
    CanonicalGameplayBindingIdentity identity = request.candidate();
    transaction.execute(
        "INSERT INTO "
            + INVENTORY
            + " (binding_ref, account_id, tenant_id, playable_state_namespace_id, character_id,"
            + " playable_state_scope, game_instance_id, runtime_game_instance_id, session_id,"
            + " binding_generation, region_id,"
            + " region_epoch, issuer_id, issuer_auth_generation, issuer_index_layout_version,"
            + " issuer_index_partition_count, issuer_index_partition_capacity, issuer_partition_id,"
            + " account_index_fence, issuer_reservation_id, transition_id, lifecycle,"
            + " account_index_state, issuer_index_state, inventory_revision)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " 'CANDIDATE_PREPARED', 'REPAIR_REQUIRED', 'REPAIR_REQUIRED', ?)",
        prepared.bindingRef().bytes(),
        identity.accountId(),
        identity.tenantId(),
        identity.playableStateNamespaceId(),
        identity.characterId(),
        identity.playableStateScope(),
        identity.gameInstanceId(),
        identity.runtimeGameInstanceId(),
        identity.sessionId(),
        decimal(prepared.bindingGeneration()),
        identity.regionId(),
        decimal(identity.regionEpoch()),
        identity.issuerId(),
        decimal(identity.issuerAuthGeneration()),
        decimal(identity.issuerIndexLayoutVersion()),
        decimal(identity.issuerIndexPartitionCount()),
        decimal(identity.issuerIndexPartitionCapacity()),
        decimal(partitionId),
        request.candidateAccountIndexFence(),
        request.issuerReservation().reservationId(),
        request.transitionId(),
        decimal(prepared.inventoryRevision()));
  }

  private static void insertTransition(
      DSLContext transaction,
      CanonicalGameplayBindingTransitionRequest request,
      CanonicalGameplayBindingTransitionSnapshot prepared,
      byte[] expectedPriorRef) {
    BigInteger expectedPriorGeneration = request.expectedPriorBindingGeneration();
    CanonicalGameplayAccountCoverageEvidence evidence = prepared.accountCoverageEvidence();
    UUID coverageOperationId = null;
    String coverageLifecycle = null;
    BigInteger coverageOperationFence = null;
    UUID historicalAccountAdmissionFence = null;
    String resolvedScopeKind = "NOT_APPLICABLE";
    UUID resolvedScopeAccountId = null;
    UUID coverageFence = null;
    BigInteger coverageGeneration = null;
    BigInteger accountSnapshotRevision = null;
    if (evidence instanceof CanonicalGameplayAccountCoverageEvidence.Historical historical) {
      coverageOperationId = historical.operationId();
      coverageLifecycle = "HISTORICAL";
      coverageOperationFence = historical.operationFence();
      historicalAccountAdmissionFence = historical.historicalAccountAdmissionFence();
      resolvedScopeKind = "ACCOUNT_WIDE";
      resolvedScopeAccountId = historical.accountId();
      coverageFence = historical.coverageFence();
      coverageGeneration = historical.coverageGeneration();
      accountSnapshotRevision = historical.inventorySnapshotRevision();
    }
    transaction.execute(
        "INSERT INTO "
            + TRANSITION
            + " (transition_id, tenant_id, playable_state_namespace_id, character_id,"
            + " expected_prior_binding_ref, expected_prior_binding_generation, candidate_binding_ref,"
            + " candidate_binding_generation, candidate_account_index_fence, issuer_reservation_id,"
            + " status, inventory_revision, account_coverage_state, account_coverage_operation_id,"
            + " account_coverage_lifecycle, account_coverage_operation_fence,"
            + " account_admission_fence_kind, account_admission_fence,"
            + " historical_account_admission_fence_kind, historical_account_admission_fence,"
            + " resolved_scope_kind, resolved_scope_account_id, account_coverage_fence,"
            + " account_coverage_generation, account_inventory_snapshot_revision)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?, ?, ?, ?,"
            + " 'NOT_APPLICABLE', NULL, ?, ?, ?, ?, ?, ?, ?)",
        request.transitionId(),
        request.candidate().tenantId(),
        request.candidate().playableStateNamespaceId(),
        request.candidate().characterId(),
        expectedPriorRef,
        expectedPriorGeneration == null ? null : decimal(expectedPriorGeneration),
        prepared.bindingRef().bytes(),
        decimal(prepared.bindingGeneration()),
        request.candidateAccountIndexFence(),
        request.issuerReservation().reservationId(),
        decimal(prepared.inventoryRevision()),
        evidence.state().name(),
        coverageOperationId,
        coverageLifecycle,
        coverageOperationFence == null ? null : decimal(coverageOperationFence),
        historicalAccountAdmissionFence == null ? "NOT_APPLICABLE" : "VALUE",
        historicalAccountAdmissionFence,
        resolvedScopeKind,
        resolvedScopeAccountId,
        coverageFence,
        coverageGeneration == null ? null : decimal(coverageGeneration),
        accountSnapshotRevision == null ? null : decimal(accountSnapshotRevision));
  }

  private static void insertAccountObligations(
      DSLContext transaction,
      CanonicalGameplayBindingTransitionRequest request,
      CanonicalGameplayBindingTransitionSnapshot prepared,
      Record active,
      byte[] expectedPriorRef) {
    BigInteger expectedPriorGeneration = request.expectedPriorBindingGeneration();
    transaction.execute(
        "INSERT INTO "
            + ACCOUNT_OBLIGATION
            + " (transition_id, obligation_ordinal, binding_ref, account_id, tenant_id, action,"
            + " binding_generation, expected_prior_generation, account_index_fence, execution_phase,"
            + " status, inventory_revision) VALUES (?, 0, ?, ?, ?, 'ADD_OR_RETAIN', ?, ?, ?,"
            + " 'BEFORE_FINAL_CAS', 'REQUIRED', ?)",
        request.transitionId(),
        prepared.bindingRef().bytes(),
        request.candidate().accountId(),
        request.candidate().tenantId(),
        decimal(prepared.bindingGeneration()),
        expectedPriorGeneration == null ? null : decimal(expectedPriorGeneration),
        request.candidateAccountIndexFence(),
        decimal(prepared.inventoryRevision()));
    if (active != null) {
      transaction.execute(
          "INSERT INTO "
              + ACCOUNT_OBLIGATION
              + " (transition_id, obligation_ordinal, binding_ref, account_id, tenant_id, action,"
              + " binding_generation, expected_prior_generation, account_index_fence, execution_phase,"
              + " status, inventory_revision) VALUES (?, 1, ?, ?, ?, 'REMOVE', ?, ?, ?,"
              + " 'AFTER_FINAL_CAS', 'REQUIRED', ?)",
          request.transitionId(),
          active.get("binding_ref", byte[].class),
          active.get("account_id", UUID.class),
          active.get("tenant_id", UUID.class),
          active.get("binding_generation", BigDecimal.class),
          active.get("binding_generation", BigDecimal.class),
          active.get("account_index_fence", UUID.class),
          decimal(prepared.inventoryRevision()));
    }
  }

  private static void insertReservation(
      DSLContext transaction,
      CanonicalGameplayBindingTransitionRequest request,
      CanonicalGameplayBindingTransitionSnapshot prepared,
      BigInteger partitionId,
      BigInteger inventoryRevision) {
    CanonicalGameplayBindingIdentity owner = request.candidate();
    var fenceEvidence = request.issuerReservation();
    transaction.execute(
        "INSERT INTO "
            + RESERVATION
            + " (reservation_id, binding_ref, account_id, tenant_id, game_instance_id,"
            + " runtime_game_instance_id, session_id,"
            + " binding_generation, transition_id, issuer_id, issuer_auth_generation, partition_id,"
            + " playable_state_namespace_id, character_id, playable_state_scope, region_id, region_epoch,"
            + " issuer_index_layout_version, issuer_index_partition_count,"
            + " issuer_index_partition_capacity, lifecycle, reservation_fence,"
            + " issuer_coverage_operation_id, issuer_coverage_operation_fence, coverage_fence,"
            + " inventory_snapshot_revision, inventory_revision)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?, ?, ?, ?, ?, ?)",
        fenceEvidence.reservationId(),
        prepared.bindingRef().bytes(),
        owner.accountId(),
        owner.tenantId(),
        owner.gameInstanceId(),
        owner.runtimeGameInstanceId(),
        owner.sessionId(),
        decimal(prepared.bindingGeneration()),
        request.transitionId(),
        owner.issuerId(),
        decimal(owner.issuerAuthGeneration()),
        decimal(partitionId),
        owner.playableStateNamespaceId(),
        owner.characterId(),
        owner.playableStateScope(),
        owner.regionId(),
        decimal(owner.regionEpoch()),
        decimal(owner.issuerIndexLayoutVersion()),
        decimal(owner.issuerIndexPartitionCount()),
        decimal(owner.issuerIndexPartitionCapacity()),
        fenceEvidence.reservationFence(),
        fenceEvidence.issuerCoverageOperationId(),
        decimal(fenceEvidence.issuerCoverageOperationFence()),
        decimal(fenceEvidence.coverageFence()),
        decimal(fenceEvidence.inventorySnapshotRevision()),
        decimal(inventoryRevision));
  }

  private static void insertIssuerObligation(
      DSLContext transaction, CanonicalGameplayBindingTransitionSnapshot prepared) {
    transaction.execute(
        "INSERT INTO "
            + ISSUER_OBLIGATION
            + " (transition_id, binding_ref, binding_generation, reservation_id, status, inventory_revision)"
            + " VALUES (?, ?, ?, ?, 'REQUIRED', ?)",
        prepared.transitionId(),
        prepared.bindingRef().bytes(),
        decimal(prepared.bindingGeneration()),
        prepared.issuerReservationId(),
        decimal(prepared.inventoryRevision()));
  }

  private static void insertRegionObligation(
      DSLContext transaction, CanonicalGameplayBindingTransitionSnapshot prepared) {
    CanonicalGameplayBindingIdentity identity = prepared.candidate();
    transaction.execute(
        "INSERT INTO "
            + REGION_OBLIGATION
            + " (transition_id, binding_ref, binding_generation, account_id, tenant_id,"
            + " game_instance_id, runtime_game_instance_id, session_id, region_id, region_epoch,"
            + " status, inventory_revision)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUIRED', ?)",
        prepared.transitionId(),
        prepared.bindingRef().bytes(),
        decimal(prepared.bindingGeneration()),
        identity.accountId(),
        identity.tenantId(),
        identity.gameInstanceId(),
        identity.runtimeGameInstanceId(),
        identity.sessionId(),
        identity.regionId(),
        decimal(identity.regionEpoch()),
        decimal(prepared.inventoryRevision()));
  }

  private static CanonicalGameplayBindingTransitionSnapshot toTransitionSnapshot(
      Record transition, Record binding, Record reservation) {
    CanonicalGameplayBindingIdentity identity = toIdentity(binding);
    CanonicalGameplayBindingRef ref = identity.bindingRef();
    if (!Arrays.equals(binding.get("binding_ref", byte[].class), ref.bytes())) {
      throw conflict("Stored canonical bindingRef does not match the exact typed identity");
    }
    return new CanonicalGameplayBindingTransitionSnapshot(
        transition.get("transition_id", UUID.class),
        identity,
        readBigInteger(transition, "candidate_binding_generation"),
        ref,
        readBigInteger(transition, "inventory_revision"),
        binding.get("issuer_reservation_id", UUID.class),
        reservation.get("reservation_fence", UUID.class),
        transition.get("candidate_account_index_fence", UUID.class),
        optionalRef(transition.get("expected_prior_binding_ref", byte[].class)),
        nullableBigInteger(transition, "expected_prior_binding_generation"),
        CanonicalGameplayBindingTransitionSnapshot.Status.valueOf(
            transition.get("status", String.class)),
        toAccountCoverageEvidence(transition, identity.accountId()));
  }

  private static CanonicalGameplayBindingTransitionSnapshot toTransitionSnapshot(Record row) {
    CanonicalGameplayBindingIdentity identity = toIdentity(row);
    CanonicalGameplayBindingRef ref = identity.bindingRef();
    if (!Arrays.equals(row.get("binding_ref", byte[].class), ref.bytes())) {
      throw conflict("Stored transition candidate does not match its exact typed identity");
    }
    return new CanonicalGameplayBindingTransitionSnapshot(
        row.get("transition_id", UUID.class),
        identity,
        readBigInteger(row, "candidate_binding_generation"),
        ref,
        readBigInteger(row, "inventory_revision"),
        row.get("issuer_reservation_id", UUID.class),
        row.get("reservation_fence", UUID.class),
        row.get("candidate_account_index_fence", UUID.class),
        optionalRef(row.get("expected_prior_binding_ref", byte[].class)),
        nullableBigInteger(row, "expected_prior_binding_generation"),
        CanonicalGameplayBindingTransitionSnapshot.Status.valueOf(row.get("status", String.class)),
        toAccountCoverageEvidence(row, identity.accountId()));
  }

  static CanonicalGameplayAccountCoverageEvidence toAccountCoverageEvidence(
      Record row, UUID accountId) {
    String state = row.get("account_coverage_state", String.class);
    if ("NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(state)) {
      if (row.get("account_coverage_operation_id", UUID.class) != null
          || row.get("account_coverage_lifecycle", String.class) != null
          || row.get("account_coverage_operation_fence") != null
          || !"NOT_APPLICABLE".equals(row.get("account_admission_fence_kind", String.class))
          || row.get("account_admission_fence", UUID.class) != null
          || !"NOT_APPLICABLE"
              .equals(row.get("historical_account_admission_fence_kind", String.class))
          || row.get("historical_account_admission_fence", UUID.class) != null
          || !"NOT_APPLICABLE".equals(row.get("resolved_scope_kind", String.class))
          || row.get("resolved_scope_account_id", UUID.class) != null
          || row.get("account_coverage_fence", UUID.class) != null
          || row.get("account_coverage_generation") != null
          || row.get("account_inventory_snapshot_revision") != null) {
        throw conflict("Stored no-active account evidence carries a fabricated operation tuple");
      }
      return CanonicalGameplayAccountCoverageEvidence.noActiveFlow();
    }
    if (!"HISTORICAL".equals(state)
        || !"HISTORICAL".equals(row.get("account_coverage_lifecycle", String.class))
        || !"NOT_APPLICABLE".equals(row.get("account_admission_fence_kind", String.class))
        || row.get("account_admission_fence", UUID.class) != null
        || !"VALUE".equals(row.get("historical_account_admission_fence_kind", String.class))
        || !"ACCOUNT_WIDE".equals(row.get("resolved_scope_kind", String.class))
        || !accountId.equals(row.get("resolved_scope_account_id", UUID.class))) {
      throw conflict(
          "Stored historical account evidence is incomplete or belongs to another owner");
    }
    return CanonicalGameplayAccountCoverageEvidence.historical(
        row.get("account_coverage_operation_id", UUID.class),
        readBigInteger(row, "account_coverage_operation_fence"),
        row.get("historical_account_admission_fence", UUID.class),
        row.get("account_coverage_fence", UUID.class),
        row.get("resolved_scope_account_id", UUID.class),
        readBigInteger(row, "account_inventory_snapshot_revision"),
        readBigInteger(row, "account_coverage_generation"));
  }

  private static CanonicalGameplayBindingInventoryEntry toInventoryEntry(Record row) {
    CanonicalGameplayBindingIdentity identity = toIdentity(row);
    CanonicalGameplayBindingRef ref = identity.bindingRef();
    if (!Arrays.equals(row.get("binding_ref", byte[].class), ref.bytes())) {
      throw conflict("Stored canonical bindingRef does not match the exact typed identity");
    }
    return new CanonicalGameplayBindingInventoryEntry(
        identity,
        ref,
        readBigInteger(row, "binding_generation"),
        row.get("account_index_fence", UUID.class),
        row.get("issuer_reservation_id", UUID.class),
        row.get("transition_id", UUID.class),
        CanonicalGameplayBindingInventoryEntry.Lifecycle.valueOf(
            row.get("lifecycle", String.class)),
        CanonicalGameplayBindingInventoryEntry.IndexState.valueOf(
            row.get("account_index_state", String.class)),
        CanonicalGameplayBindingInventoryEntry.IndexState.valueOf(
            row.get("issuer_index_state", String.class)),
        readBigInteger(row, "inventory_revision"));
  }

  private static CanonicalIssuerPartitionReservation toReservation(Record row) {
    CanonicalGameplayBindingIdentity owner = toIdentity(row);
    CanonicalGameplayBindingRef ref = owner.bindingRef();
    if (!Arrays.equals(row.get("binding_ref", byte[].class), ref.bytes())) {
      throw conflict("Stored reservation owner does not match its exact bindingRef");
    }
    return new CanonicalIssuerPartitionReservation(
        row.get("reservation_id", UUID.class),
        owner,
        readBigInteger(row, "binding_generation"),
        row.get("transition_id", UUID.class),
        readBigInteger(row, "partition_id"),
        CanonicalIssuerPartitionReservation.Lifecycle.valueOf(row.get("lifecycle", String.class)),
        row.get("reservation_fence", UUID.class),
        row.get("issuer_coverage_operation_id", UUID.class),
        readBigInteger(row, "issuer_coverage_operation_fence"),
        readBigInteger(row, "coverage_fence"),
        readBigInteger(row, "inventory_snapshot_revision"),
        readBigInteger(row, "inventory_revision"));
  }

  private static CanonicalGameplayBindingAccountIndexObligation toAccountObligation(Record row) {
    CanonicalGameplayBindingIdentity identity = toIdentity(row);
    CanonicalGameplayBindingRef ref = identity.bindingRef();
    if (!Arrays.equals(row.get("binding_ref", byte[].class), ref.bytes())
        || !Objects.equals(row.get("account_id", UUID.class), identity.accountId())
        || !Objects.equals(row.get("tenant_id", UUID.class), identity.tenantId())) {
      throw conflict("Stored account obligation owner differs from its exact bindingRef");
    }
    return new CanonicalGameplayBindingAccountIndexObligation(
        row.get("transition_id", UUID.class),
        row.get("obligation_ordinal", Integer.class),
        ref,
        row.get("account_id", UUID.class),
        row.get("tenant_id", UUID.class),
        CanonicalGameplayBindingAccountIndexObligation.Action.valueOf(
            row.get("action", String.class)),
        readBigInteger(row, "binding_generation"),
        nullableBigInteger(row, "expected_prior_generation"),
        row.get("account_index_fence", UUID.class),
        CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.valueOf(
            row.get("execution_phase", String.class)),
        CanonicalGameplayBindingAccountIndexObligation.Status.valueOf(
            row.get("status", String.class)),
        readBigInteger(row, "inventory_revision"),
        CanonicalGameplayBindingAccountIndexObligation.ProjectionState.valueOf(
            row.get("projection_state", String.class)),
        row.get("projection_member", String.class),
        nullableBigInteger(row, "projection_inventory_revision"));
  }

  private static CanonicalGameplayBindingIssuerIndexObligation toIssuerObligation(Record row) {
    CanonicalGameplayBindingIdentity identity = toIdentity(row);
    CanonicalGameplayBindingRef ref = identity.bindingRef();
    if (!Arrays.equals(row.get("binding_ref", byte[].class), ref.bytes())) {
      throw conflict("Stored issuer obligation owner differs from its exact bindingRef");
    }
    return new CanonicalGameplayBindingIssuerIndexObligation(
        row.get("transition_id", UUID.class),
        ref,
        readBigInteger(row, "binding_generation"),
        row.get("reservation_id", UUID.class),
        CanonicalGameplayBindingIssuerIndexObligation.Status.valueOf(
            row.get("status", String.class)),
        readBigInteger(row, "inventory_revision"),
        row.get("local_release_operation_id", UUID.class));
  }

  /** Inventory preparation consumes the required obligation's identity, not bridge proof. */
  private static CanonicalGameplayBindingRegionBridgeObligation toProvisionalRegionObligation(
      Record row) {
    var status =
        CanonicalGameplayBindingRegionBridgeObligation.Status.valueOf(
            row.get("status", String.class));
    if (status == CanonicalGameplayBindingRegionBridgeObligation.Status.ACKNOWLEDGED) {
      // An ACK is never represented by the required-only projection: retain every closed
      // Entity/World/Redis proof check in its actual acknowledgement decoder.
      return toRegionObligation(row);
    }
    return new CanonicalGameplayBindingRegionBridgeObligation(
        row.get("transition_id", UUID.class),
        CanonicalGameplayBindingRef.fromExactBytes(row.get("binding_ref", byte[].class)),
        readBigInteger(row, "binding_generation"),
        row.get("account_id", UUID.class),
        row.get("tenant_id", UUID.class),
        row.get("game_instance_id", UUID.class),
        row.get("runtime_game_instance_id", Long.class),
        row.get("session_id", String.class),
        row.get("region_id", UUID.class),
        readBigInteger(row, "region_epoch"),
        status,
        readBigInteger(row, "inventory_revision"));
  }

  private static CanonicalGameplayBindingRegionBridgeObligation toRegionObligation(Record row) {
    UUID tenantId = row.get("tenant_id", UUID.class);
    UUID gameInstanceId = row.get("game_instance_id", UUID.class);
    String sessionId = row.get("session_id", String.class);
    CanonicalGameplayBindingRef ref =
        CanonicalGameplayBindingRef.of(tenantId, gameInstanceId, sessionId);
    if (!Arrays.equals(row.get("binding_ref", byte[].class), ref.bytes())) {
      throw conflict("Stored region obligation bindingRef differs from its exact owner tuple");
    }
    return new CanonicalGameplayBindingRegionBridgeObligation(
        row.get("transition_id", UUID.class),
        ref,
        readBigInteger(row, "binding_generation"),
        row.get("account_id", UUID.class),
        tenantId,
        gameInstanceId,
        row.get("runtime_game_instance_id", Long.class),
        sessionId,
        row.get("region_id", UUID.class),
        readBigInteger(row, "region_epoch"),
        CanonicalGameplayBindingRegionBridgeObligation.Status.valueOf(
            row.get("status", String.class)),
        readBigInteger(row, "inventory_revision"),
        row.get("bridge_operation_id", UUID.class),
        row.get("bridge_preimage_state", String.class),
        row.get("runtime_entity_id", Long.class),
        row.get("entity_projection_generation", Long.class),
        row.get("entity_projection_fence", Long.class),
        row.get("entity_workload_namespace", String.class),
        row.get("assignment_uuid", UUID.class),
        row.get("assignment_intent_digest", String.class),
        row.get("entity_reference_digest", String.class),
        row.get("world_source_digest", String.class),
        row.get("canonical_region_id", UUID.class),
        row.get("world_lifecycle_epoch", Long.class),
        row.get("world_lifecycle_row_version", Long.class),
        row.get("world_capture_id", UUID.class),
        row.get("world_graph_sha256", String.class),
        row.get("world_preparation_input_digest", String.class),
        nullableBigInteger(row, "executor_fence"),
        row.get("lease_token_correlation", String.class),
        row.get("redis_readback_digest", String.class),
        nullableInstant(row, "acknowledged_at"));
  }

  private static CanonicalGameplayBindingIdentity toIdentity(Record row) {
    return new CanonicalGameplayBindingIdentity(
        row.get("account_id", UUID.class),
        row.get("tenant_id", UUID.class),
        row.get("playable_state_namespace_id", UUID.class),
        row.get("character_id", UUID.class),
        row.get("playable_state_scope", String.class),
        row.get("game_instance_id", UUID.class),
        row.get("runtime_game_instance_id", Long.class),
        row.get("session_id", String.class),
        row.get("region_id", UUID.class),
        readBigInteger(row, "region_epoch"),
        row.get("issuer_id", String.class),
        readBigInteger(row, "issuer_auth_generation"),
        readBigInteger(row, "issuer_index_layout_version"),
        readBigInteger(row, "issuer_index_partition_count"),
        readBigInteger(row, "issuer_index_partition_capacity"));
  }

  private static BigInteger nullableBigInteger(Record row, String name) {
    BigDecimal value = row.get(name, BigDecimal.class);
    return value == null ? null : value.toBigIntegerExact();
  }

  private static Instant nullableInstant(Record row, String name) {
    OffsetDateTime value = row.get(name, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static CanonicalGameplayBindingRef optionalRef(byte[] bytes) {
    return bytes == null ? null : CanonicalGameplayBindingRef.fromExactBytes(bytes);
  }

  private static BigInteger readBigInteger(Record row, String name) {
    BigInteger value = nullableBigInteger(row, name);
    if (value == null) {
      throw conflict("Required exact numeric evidence is missing: " + name);
    }
    return value;
  }

  private static BigDecimal decimal(BigInteger value) {
    return new BigDecimal(value);
  }

  private static BigDecimal decimalNullable(BigInteger value) {
    return value == null ? null : decimal(value);
  }

  private record AccountCoverageControl(
      UUID accountId,
      String state,
      BigInteger epoch,
      UUID currentOperationId,
      BigInteger currentOperationFence,
      UUID accountAdmissionFence,
      UUID historicalAccountAdmissionFence,
      UUID coverageFence,
      BigInteger inventorySnapshotRevision,
      BigInteger coverageGeneration,
      CanonicalGameplayAccountCoverageEvidence evidence) {}

  enum OriginalNewCandidateInstallationState {
    DISPATCH_POSSIBLE,
    READBACK_CONFIRMED
  }

  enum OriginalNewCandidateInstallationProvenance {
    HISTORICAL_READBACK_CONFIRMED
  }

  /**
   * Durable SQL source for final admission. Its candidate installation is explicitly historical;
   * this capability contains no claim that the non-admitting preimage remains in Redis.
   */
  static final class OriginalNewFinalAdmissionSource {
    private final CanonicalGameplayOriginalNewCandidate candidate;
    private final String candidateDigest;
    private final String originalCandidateReadbackDigest;
    private final CanonicalGameplayBindingTransitionSnapshot.Status transitionStatus;
    private final Optional<OriginalNewFinalAdmissionReceipt> finalReceipt;
    private final CanonicalGameplayBindingRegionBridgeObligation regionAcknowledgement;
    private final CanonicalGameplayIssuerBindingLocalAcknowledgement issuerAcknowledgement;

    private OriginalNewFinalAdmissionSource(
        CanonicalGameplayOriginalNewCandidate candidate,
        String candidateDigest,
        String originalCandidateReadbackDigest,
        CanonicalGameplayBindingTransitionSnapshot.Status transitionStatus,
        Optional<OriginalNewFinalAdmissionReceipt> finalReceipt,
        CanonicalGameplayBindingRegionBridgeObligation regionAcknowledgement,
        CanonicalGameplayIssuerBindingLocalAcknowledgement issuerAcknowledgement) {
      this.candidate = Objects.requireNonNull(candidate, "candidate");
      this.candidateDigest = Objects.requireNonNull(candidateDigest, "candidateDigest");
      this.originalCandidateReadbackDigest =
          Objects.requireNonNull(
              originalCandidateReadbackDigest, "originalCandidateReadbackDigest");
      this.transitionStatus = Objects.requireNonNull(transitionStatus, "transitionStatus");
      this.finalReceipt = Objects.requireNonNull(finalReceipt, "finalReceipt");
      this.regionAcknowledgement =
          Objects.requireNonNull(regionAcknowledgement, "regionAcknowledgement");
      this.issuerAcknowledgement =
          Objects.requireNonNull(issuerAcknowledgement, "issuerAcknowledgement");
      if (!candidateDigest.matches("sha256:[0-9a-f]{64}")
          || !originalCandidateReadbackDigest.matches("sha256:[0-9a-f]{64}")
          || regionAcknowledgement.status()
              != CanonicalGameplayBindingRegionBridgeObligation.Status.ACKNOWLEDGED
          || (transitionStatus != CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL
              && transitionStatus != CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED)
          || finalReceipt.isPresent()
              != (transitionStatus
                  == CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED)) {
        throw new IllegalArgumentException("Original NEW final source is not exact");
      }
    }

    CanonicalGameplayOriginalNewCandidate candidate() {
      return candidate;
    }

    String candidateDigest() {
      return candidateDigest;
    }

    String originalCandidateReadbackDigest() {
      return originalCandidateReadbackDigest;
    }

    OriginalNewCandidateInstallationProvenance candidateInstallationProvenance() {
      return OriginalNewCandidateInstallationProvenance.HISTORICAL_READBACK_CONFIRMED;
    }

    CanonicalGameplayBindingTransitionSnapshot.Status transitionStatus() {
      return transitionStatus;
    }

    Optional<OriginalNewFinalAdmissionReceipt> finalReceipt() {
      return finalReceipt;
    }

    CanonicalGameplayBindingRegionBridgeObligation regionAcknowledgement() {
      return regionAcknowledgement;
    }

    CanonicalGameplayIssuerBindingLocalAcknowledgement issuerAcknowledgement() {
      return issuerAcknowledgement;
    }

    @Override
    public String toString() {
      return "OriginalNewFinalAdmissionSource[historicalCandidateReadback, transitionStatus="
          + transitionStatus
          + ", redacted]";
    }
  }

  record OriginalNewFinalAdmissionReceipt(
      String finalSessionSha256,
      long admissionAtMillis,
      long continuityExpiresAtMillis,
      long sessionDurationMillis) {
    OriginalNewFinalAdmissionReceipt {
      Objects.requireNonNull(finalSessionSha256, "finalSessionSha256");
      if (!finalSessionSha256.matches("[0-9a-f]{64}")
          || admissionAtMillis <= 0
          || sessionDurationMillis <= 0
          || sessionDurationMillis > 300_000
          || admissionAtMillis > Long.MAX_VALUE - sessionDurationMillis
          || continuityExpiresAtMillis != admissionAtMillis + sessionDurationMillis) {
        throw new IllegalArgumentException("Original NEW final receipt is malformed");
      }
    }
  }

  /** SQL-issued, one-use permission returned only by the transaction that first claims dispatch. */
  static final class OriginalNewCandidateCreatePermission {
    private boolean consumed;

    private OriginalNewCandidateCreatePermission() {}

    synchronized boolean consume() {
      if (consumed) return false;
      consumed = true;
      return true;
    }
  }

  /**
   * Exact SQL source plus the optional first-dispatch permission; constructors stay
   * repository-owned.
   */
  static final class OriginalNewCandidateInstallationClaim {
    private final CanonicalGameplayBindingProvisionalCasEvidence evidence;
    private final CanonicalGameplayAdmissionDecision originalDecision;
    private final CanonicalGameplayOriginalNewCandidate candidate;
    private final OriginalNewCandidateInstallationState state;
    private final OriginalNewCandidateCreatePermission createPermission;

    private OriginalNewCandidateInstallationClaim(
        CanonicalGameplayBindingProvisionalCasEvidence evidence,
        CanonicalGameplayAdmissionDecision originalDecision,
        CanonicalGameplayOriginalNewCandidate candidate,
        OriginalNewCandidateInstallationState state,
        OriginalNewCandidateCreatePermission createPermission) {
      this.evidence = Objects.requireNonNull(evidence, "evidence");
      this.originalDecision = Objects.requireNonNull(originalDecision, "originalDecision");
      this.candidate = Objects.requireNonNull(candidate, "candidate");
      this.state = Objects.requireNonNull(state, "state");
      this.createPermission = createPermission;
    }

    CanonicalGameplayBindingProvisionalCasEvidence evidence() {
      return evidence;
    }

    CanonicalGameplayAdmissionDecision originalDecision() {
      return originalDecision;
    }

    CanonicalGameplayOriginalNewCandidate candidate() {
      return candidate;
    }

    OriginalNewCandidateInstallationState state() {
      return state;
    }

    boolean consumeCreatePermission() {
      return createPermission != null && createPermission.consume();
    }
  }

  private static void requireOne(int changed, String message) {
    if (changed != 1) {
      throw conflict(message);
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  private static void requireCount(int changed, int expected, String message) {
    if (changed != expected) {
      throw conflict(message);
    }
  }

  private static CanonicalGameplayBindingInventoryConflictException conflict(String message) {
    return new CanonicalGameplayBindingInventoryConflictException(message);
  }
}
