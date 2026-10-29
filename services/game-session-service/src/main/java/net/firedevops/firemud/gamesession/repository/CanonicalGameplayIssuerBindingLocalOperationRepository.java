package net.firedevops.firemud.gamesession.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountCoverageEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortSource;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerBindingLocalOperation.Request;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SQL owner for an ordinary NEW issuer registration linked to the actual installed anchor. */
final class CanonicalGameplayIssuerBindingLocalOperationRepository {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String OPERATION = "game_session_canonical_issuer_binding_local_operation";
  private static final String RESERVATION = "game_session_canonical_issuer_partition_reservation";
  private static final String ACCOUNT_CONTROL = "game_session_canonical_account_coverage_control";
  private static final String MEMBER_COUNTER =
      "game_session_canonical_issuer_bootstrap_member_op_counter";
  private static final String BOOTSTRAP_MEMBER_OPERATION =
      "game_session_canonical_issuer_bootstrap_member_operation";

  private final DSLContext dsl;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      currentIssuerStateSource;

  CanonicalGameplayIssuerBindingLocalOperationRepository(
      DSLContext dsl,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          currentIssuerStateSource) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.currentIssuerStateSource =
        Objects.requireNonNull(currentIssuerStateSource, "currentIssuerStateSource");
  }

  /**
   * Begins or exactly replays one caller-correlated ordinary NEW request. The current Account
   * checkpoint and stable incarnation are explicit controlled collaborators; SQL still owns the
   * installed anchor, inventory/reservation, local operation identity and exact v2 bytes.
   */
  CanonicalGameplayIssuerBindingLocalOperation beginOrResumeOrdinaryNew(
      CanonicalGameplayIssuerBindingLocalOperation.Request request) {
    Objects.requireNonNull(request, "request");
    requireNoAmbientTransaction();
    if (request.expectedPriorBindingRef() != null
        || request.expectedPriorBindingGeneration() != null) {
      throw conflict("Local issuer registration is restricted to ordinary NEW");
    }

    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState =
        Objects.requireNonNull(
            currentIssuerStateSource.readFreshCurrentState(),
            "current issuer state source returned null");
    AtomicReference<CanonicalGameplayIssuerBindingLocalOperation> result = new AtomicReference<>();
    new CanonicalGameplayBindingInventoryRepository(dsl)
        .prepareAfterInstalledIssuerAnchor(
            (transaction, actualAnchor, ignoredCurrentState) ->
                resolveTransitionRequestInTransaction(transaction, request, actualAnchor),
            currentState,
            (transaction, exactRequest, actualAnchor, state, prepared, exactRetry) ->
                result.set(
                    recordOrReplayInTransaction(
                        request,
                        transaction,
                        exactRequest,
                        actualAnchor,
                        state,
                        prepared,
                        exactRetry)));
    if (result.get() == null) {
      throw conflict("Local issuer registration did not produce a durable operation");
    }
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState postReadState;
    try {
      postReadState =
          Objects.requireNonNull(
              currentIssuerStateSource.readFreshCurrentState(),
              "current issuer state source returned null after local registration");
    } catch (RuntimeException sourceFailure) {
      throw new IllegalStateException(
          "Could not requalify issuer source after SQL registration; the pending local operation remains durable",
          sourceFailure);
    }
    if (!sameCurrentIssuerState(currentState, postReadState)) {
      throw conflict(
          "Issuer source or incarnation moved during SQL registration; the pending local operation remains durable");
    }
    return result.get();
  }

  /** Reloads the immutable original registration only from the actual retained Account abort. */
  CanonicalGameplayIssuerBindingLocalOperation readOriginalForIssuerRelease(
      CanonicalGameplayAdmissionAbortSource abortSource) {
    return readOriginalForIssuerRelease(CanonicalGameplayIssuerCleanupSource.positive(abortSource));
  }

  CanonicalGameplayIssuerBindingLocalOperation readOriginalForIssuerRelease(
      CanonicalGameplayIssuerCleanupSource abortSource) {
    Objects.requireNonNull(abortSource, "Actual Account-aborted source required");
    requireNoAmbientTransaction();
    if (!abortSource.isAccountAborted()
        || (abortSource.reservation().lifecycle()
                != CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING
            && abortSource.reservation().lifecycle()
                != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED)) {
      throw conflict("Issuer release requires the actual retained first-NEW Account abort source");
    }
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState =
        Objects.requireNonNull(
            currentIssuerStateSource.readFreshCurrentState(),
            "current issuer state source returned null");
    CanonicalGameplayIssuerBindingLocalOperation operation =
        dsl.transactionResult(
            configuration -> {
              DSLContext transaction = org.jooq.impl.DSL.using(configuration);
              // This reader invokes the installed-anchor source loader, whose snapshot contract
              // starts with the global inventory clock. Keep the same clock-before-source order
              // used by ordinary registration, abort retry, and release completion.
              CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
              Record operationRow =
                  transaction.fetchOne(
                      "SELECT * FROM " + OPERATION + " WHERE issuer_id = ? AND transition_id = ?",
                      ISSUER,
                      abortSource.transition().transitionId());
              if (operationRow == null) {
                throw conflict(
                    "Account-aborted first-NEW source has no original local issuer operation");
              }
              CanonicalGameplayIssuerInstalledAnchor anchor =
                  CanonicalGameplayIssuerBootstrapCoverageRepository
                      .readInstalledAnchorForBindingPreparationInTransaction(
                          transaction, currentState);
              byte[] exactOriginal = requireBytes(operationRow, "member_value_bytes");
              CanonicalGameplayIssuerMemberV2Value original;
              try {
                original = CanonicalGameplayIssuerMemberV2Codec.decode(exactOriginal);
              } catch (RuntimeException malformed) {
                throw conflict("Original local issuer operation is not canonical schema v2");
              }
              CanonicalIssuerPartitionReservation reservation = original.reservation();
              var proof =
                  CanonicalGameplayIssuerBootstrapCoverageRepository
                      .readInstalledAnchorPartitionProof(
                          transaction, anchor, reservation.partitionId());
              AccountControlSnapshot accountControl =
                  readCapturedAccountControl(
                      operationRow,
                      abortSource.candidate().identity().accountId(),
                      abortSource.transition().accountCoverageEvidence());
              Request request =
                  new Request(
                      requireUuid(operationRow, "request_id"),
                      abortSource.transition().transitionId(),
                      abortSource.candidate().identity(),
                      null,
                      null);
              requireSameStoredOperation(
                  operationRow,
                  request,
                  abortSource.transition(),
                  reservation,
                  anchor,
                  proof,
                  currentState,
                  accountControl,
                  true);
              CanonicalGameplayIssuerMemberV2Value expected =
                  createMemberValue(
                      request.candidate(),
                      abortSource.transition().bindingGeneration(),
                      reservation,
                      anchor,
                      currentState.incarnation(),
                      requireUuid(operationRow, "operation_id"),
                      readPositive(operationRow, "operation_fence"),
                      readPositive(operationRow, "local_member_generation"));
              if (!expected.equals(original)
                  || !Arrays.equals(
                      exactOriginal, CanonicalGameplayIssuerMemberV2Codec.encode(expected))) {
                throw conflict(
                    "Original issuer member differs from its exact aborted source or anchor");
              }
              try {
                return new CanonicalGameplayIssuerBindingLocalOperation(
                    request,
                    requireUuid(operationRow, "candidate_account_index_fence"),
                    abortSource.transition(),
                    readPositive(operationRow, "local_inventory_snapshot_revision"),
                    reservation,
                    anchor,
                    proof,
                    expected,
                    requireUuid(operationRow, "operation_id"),
                    readPositive(operationRow, "operation_fence"),
                    readPositive(operationRow, "local_member_generation"),
                    exactOriginal,
                    requireText(operationRow, "member_value_digest"),
                    CanonicalGameplayIssuerBindingLocalOperation.Status.valueOf(
                        requireText(operationRow, "member_status")));
              } catch (RuntimeException malformed) {
                throw conflict("Original local issuer operation metadata is malformed");
              }
            });
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState after =
        Objects.requireNonNull(
            currentIssuerStateSource.readFreshCurrentState(),
            "current issuer state source returned null after original-operation read");
    if (!sameCurrentIssuerState(currentState, after)) {
      throw conflict(
          "Issuer source or incarnation moved while reloading the original local operation");
    }
    return operation;
  }

  private CanonicalGameplayBindingTransitionRequest resolveTransitionRequestInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerBindingLocalOperation.Request request,
      CanonicalGameplayIssuerInstalledAnchor anchor) {
    requireCandidateMatchesAnchor(request.candidate(), anchor);
    Record priorByTransition =
        transaction.fetchOne(
            "SELECT * FROM " + OPERATION + " WHERE issuer_id = ? AND transition_id = ?",
            ISSUER,
            request.transitionId());
    Record priorByRequest =
        transaction.fetchOne(
            "SELECT * FROM " + OPERATION + " WHERE issuer_id = ? AND request_id = ?",
            ISSUER,
            request.requestId());
    if ((priorByTransition == null) != (priorByRequest == null)
        || (priorByTransition != null
            && !requireUuid(priorByTransition, "operation_id")
                .equals(requireUuid(priorByRequest, "operation_id")))) {
      throw conflict(
          "Request and transition identities refer to different local issuer operations");
    }

    UUID accountIndexFence;
    CanonicalIssuerReservationFenceEvidence reservationEvidence;
    if (priorByTransition == null) {
      requirePhysicalOperationSlotAvailable(transaction, request.candidate(), anchor);
      accountIndexFence = freshNonNilUuid();
      reservationEvidence =
          new CanonicalIssuerReservationFenceEvidence(
              freshNonNilUuid(),
              freshNonNilUuid(),
              anchor.issuerCoverageOperationId(),
              anchor.operationFence(),
              anchor.coverageFence(),
              anchor.issuerInventorySnapshotRevision());
    } else {
      requireCallerRequestMatches(priorByTransition, request);
      accountIndexFence = requireUuid(priorByTransition, "candidate_account_index_fence");
      reservationEvidence = readOriginalReservationEvidence(priorByTransition);
    }

    return new CanonicalGameplayBindingTransitionRequest(
        request.transitionId(),
        accountIndexFence,
        request.candidate(),
        reservationEvidence,
        null,
        null);
  }

  private static void requirePhysicalOperationSlotAvailable(
      DSLContext transaction,
      CanonicalGameplayBindingIdentity candidate,
      CanonicalGameplayIssuerInstalledAnchor anchor) {
    // The request factory runs under the inventory/source locks, before preparing any candidate
    // rows or allocating identities. Match the projection owner's nonretired local-operation set;
    // RELEASED alone does not free a physical slot, and exact registration retries bypass this
    // gate.
    BigInteger partitionId = candidate.bindingRef().partitionId(anchor.partitionCount());
    Record countRow =
        Objects.requireNonNull(
            transaction.fetchOne(
                "SELECT count(*) FROM game_session_canonical_issuer_current_local_operation membership"
                    + " JOIN "
                    + OPERATION
                    + " operation ON operation.operation_id = membership.operation_id"
                    + " WHERE membership.issuer_id = ? AND membership.anchor_bootstrap_operation_id = ?"
                    + " AND membership.partition_id = ?",
                ISSUER,
                anchor.bootstrapOperationId(),
                decimal(partitionId)),
            "Expected local issuer operation count row");
    long operationCount =
        Objects.requireNonNull(
            countRow.get(0, Long.class), "Expected local issuer operation count");
    if (operationCount
        >= CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_MEMBER_OPERATIONS) {
      throw conflict("The local partition operation set has reached the physical owner bound");
    }
  }

  private CanonicalGameplayIssuerBindingLocalOperation recordOrReplayInTransaction(
      CanonicalGameplayIssuerBindingLocalOperation.Request request,
      DSLContext transaction,
      CanonicalGameplayBindingTransitionRequest transitionRequest,
      CanonicalGameplayIssuerInstalledAnchor actualAnchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState,
      CanonicalGameplayBindingTransitionSnapshot prepared,
      boolean exactRetry) {
    Record operationRow =
        transaction.fetchOne(
            "SELECT * FROM " + OPERATION + " WHERE issuer_id = ? AND transition_id = ? FOR UPDATE",
            ISSUER,
            transitionRequest.transitionId());
    if (exactRetry) {
      if (operationRow == null) {
        throw conflict("Prepared NEW transition has no matching local issuer registration");
      }
      requireCallerRequestMatches(operationRow, request);
      CanonicalIssuerPartitionReservation reservation =
          readAndRequireReservation(transaction, transitionRequest, prepared, operationRow);
      var proof =
          CanonicalGameplayIssuerBootstrapCoverageRepository.readInstalledAnchorPartitionProof(
              transaction, actualAnchor, reservation.partitionId());
      CanonicalGameplayIssuerBootstrapCoverageRepository.requireNewMemberAbsentFromBaseline(
          proof, prepared.bindingRef(), reservation.reservationId());
      AccountControlSnapshot accountControl =
          readAndRequireAccountControl(
              transaction,
              transitionRequest.candidate().accountId(),
              prepared.accountCoverageEvidence());
      return returnOperation(
          request,
          prepared,
          reservation,
          actualAnchor,
          proof,
          currentState,
          accountControl,
          operationRow,
          true);
    }
    if (operationRow != null) {
      throw conflict("A local issuer operation already exists for a newly prepared transition");
    }

    CanonicalIssuerPartitionReservation reservation =
        readAndRequireReservation(transaction, transitionRequest, prepared, null);
    CanonicalGameplayIssuerPartitionMutationRepository.PartitionMutationHead mutationHead =
        CanonicalGameplayIssuerPartitionMutationRepository.readHeadInTransaction(
            transaction, actualAnchor.bootstrapOperationId(), reservation.partitionId());
    if (mutationHead.pendingMutationId() != null) {
      throw conflict(
          "A same-partition physical mutation is unresolved; new issuer admission remains excluded");
    }
    var proof =
        CanonicalGameplayIssuerBootstrapCoverageRepository.readInstalledAnchorPartitionProof(
            transaction, actualAnchor, reservation.partitionId());
    CanonicalGameplayIssuerBootstrapCoverageRepository.requireNewMemberAbsentFromBaseline(
        proof, prepared.bindingRef(), reservation.reservationId());
    AccountControlSnapshot accountControl =
        readAndRequireAccountControl(
            transaction,
            transitionRequest.candidate().accountId(),
            prepared.accountCoverageEvidence());

    IssuedCounters counters = allocateLocalCounters(transaction, actualAnchor, reservation);
    UUID operationId = freshOperationId(request, reservation, actualAnchor);
    CanonicalGameplayIssuerMemberV2Value memberValue =
        createMemberValue(
            request.candidate(),
            prepared.bindingGeneration(),
            reservation,
            actualAnchor,
            currentState.incarnation(),
            operationId,
            counters.operationFence(),
            counters.localMemberGeneration());
    byte[] valueBytes = CanonicalGameplayIssuerMemberV2Codec.encode(memberValue);
    String valueDigest = CanonicalGameplayIssuerMemberV2Codec.sha256(valueBytes);
    insertOperation(
        transaction,
        request,
        transitionRequest,
        prepared,
        reservation,
        actualAnchor,
        proof,
        accountControl,
        operationId,
        counters,
        valueBytes,
        valueDigest);
    Record saved =
        transaction.fetchOne(
            "SELECT * FROM " + OPERATION + " WHERE issuer_id = ? AND transition_id = ? FOR UPDATE",
            ISSUER,
            transitionRequest.transitionId());
    if (saved == null) throw conflict("Local issuer operation is unreadable after persistence");
    return returnOperation(
        request,
        prepared,
        reservation,
        actualAnchor,
        proof,
        currentState,
        accountControl,
        saved,
        false);
  }

  private CanonicalGameplayIssuerBindingLocalOperation returnOperation(
      CanonicalGameplayIssuerBindingLocalOperation.Request request,
      CanonicalGameplayBindingTransitionSnapshot prepared,
      CanonicalIssuerPartitionReservation reservation,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof proof,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState,
      AccountControlSnapshot accountControl,
      Record row,
      boolean exactRetry) {
    requireSameStoredOperation(
        row,
        request,
        prepared,
        reservation,
        anchor,
        proof,
        currentState,
        accountControl,
        exactRetry);
    UUID operationId = requireUuid(row, "operation_id");
    BigInteger operationFence = readPositive(row, "operation_fence");
    BigInteger localMemberGeneration = readPositive(row, "local_member_generation");
    byte[] bytes = requireBytes(row, "member_value_bytes");
    String digest = requireText(row, "member_value_digest");
    CanonicalGameplayIssuerMemberV2Value value;
    try {
      value = CanonicalGameplayIssuerMemberV2Codec.decode(bytes);
    } catch (RuntimeException malformed) {
      throw conflict("Stored local issuer value is not canonical schema v2");
    }
    CanonicalGameplayIssuerMemberV2Value expected =
        createMemberValue(
            request.candidate(),
            prepared.bindingGeneration(),
            reservation,
            anchor,
            currentState.incarnation(),
            operationId,
            operationFence,
            localMemberGeneration);
    if (!expected.equals(value)
        || !Arrays.equals(bytes, CanonicalGameplayIssuerMemberV2Codec.encode(expected))
        || !digest.equals(CanonicalGameplayIssuerMemberV2Codec.sha256(bytes))) {
      throw conflict(
          "Stored local issuer operation differs from its exact anchor, source, or reservation");
    }
    try {
      return new CanonicalGameplayIssuerBindingLocalOperation(
          request,
          requireUuid(row, "candidate_account_index_fence"),
          prepared,
          readPositive(row, "local_inventory_snapshot_revision"),
          reservation,
          anchor,
          proof,
          expected,
          operationId,
          operationFence,
          localMemberGeneration,
          bytes,
          digest,
          CanonicalGameplayIssuerBindingLocalOperation.Status.valueOf(
              requireText(row, "member_status")));
    } catch (RuntimeException malformed) {
      throw conflict("Stored local issuer operation metadata is malformed");
    }
  }

  private static CanonicalGameplayIssuerMemberV2Value createMemberValue(
      CanonicalGameplayBindingIdentity candidate,
      BigInteger bindingGeneration,
      CanonicalIssuerPartitionReservation reservation,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation,
      UUID operationId,
      BigInteger operationFence,
      BigInteger localMemberGeneration) {
    var layout =
        new CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor(
            anchor.issuerId(),
            anchor.layoutVersion(),
            anchor.partitionCount(),
            anchor.partitionCapacity(),
            anchor.mappingIdentity(),
            anchor.layoutDescriptorBytes(),
            anchor.layoutDescriptorDigest());
    var source =
        new CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint(
            anchor.issuerId(),
            anchor.issuerAuthGeneration(),
            anchor.sourceCheckpointBytes(),
            anchor.sourceCheckpointDigest(),
            anchor.sourceEventBytes(),
            anchor.sourceEventDigest());
    return new CanonicalGameplayIssuerMemberV2Value(
        candidate,
        bindingGeneration,
        reservation,
        new CanonicalGameplayIssuerMemberV2Value.Anchor(
            anchor.issuerCoverageOperationId(),
            anchor.operationFence(),
            anchor.coverageFence(),
            anchor.issuerInventorySnapshotRevision()),
        new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
            operationId, operationFence, localMemberGeneration),
        anchor.controlEpoch(),
        layout,
        source,
        incarnation);
  }

  private static CanonicalIssuerPartitionReservation readAndRequireReservation(
      DSLContext transaction,
      CanonicalGameplayBindingTransitionRequest request,
      CanonicalGameplayBindingTransitionSnapshot prepared,
      Record operationRow) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM " + RESERVATION + " WHERE reservation_id = ? FOR UPDATE",
            prepared.issuerReservationId());
    if (row == null) throw conflict("Prepared ordinary NEW reservation is missing");
    CanonicalIssuerPartitionReservation reservation;
    try {
      if (operationRow == null) {
        reservation =
            new CanonicalIssuerPartitionReservation(
                requireUuid(row, "reservation_id"),
                requireIdentityMatchesReservation(request.candidate(), row),
                readPositive(row, "binding_generation"),
                requireUuid(row, "transition_id"),
                readNonNegative(row, "partition_id"),
                CanonicalIssuerPartitionReservation.Lifecycle.valueOf(
                    requireText(row, "lifecycle")),
                requireUuid(row, "reservation_fence"),
                requireUuid(row, "issuer_coverage_operation_id"),
                readPositive(row, "issuer_coverage_operation_fence"),
                readPositive(row, "coverage_fence"),
                readPositive(row, "inventory_snapshot_revision"),
                readPositive(row, "inventory_revision"));
      } else {
        CanonicalGameplayIssuerMemberV2Value stored =
            CanonicalGameplayIssuerMemberV2Codec.decode(
                requireBytes(operationRow, "member_value_bytes"));
        // The physical member retains its original revision while owner transitions advance SQL.
        // Exact retry must return those frozen bytes without accepting a regressed current source.
        reservation = stored.reservation();
        requireIdentityMatchesReservation(request.candidate(), row);
        if (!reservation.reservationId().equals(requireUuid(row, "reservation_id"))
            || !reservation.transitionId().equals(requireUuid(row, "transition_id"))
            || !reservation.bindingGeneration().equals(readPositive(row, "binding_generation"))
            || !reservation.partitionId().equals(readNonNegative(row, "partition_id"))
            || !reservation.lifecycle().name().equals(requireText(row, "lifecycle"))
            || !reservation.reservationFence().equals(requireUuid(row, "reservation_fence"))
            || !reservation
                .issuerCoverageOperationId()
                .equals(requireUuid(row, "issuer_coverage_operation_id"))
            || !reservation
                .issuerCoverageOperationFence()
                .equals(readPositive(row, "issuer_coverage_operation_fence"))
            || !reservation.coverageFence().equals(readPositive(row, "coverage_fence"))
            || !reservation
                .inventorySnapshotRevision()
                .equals(readPositive(row, "inventory_snapshot_revision"))
            || readPositive(row, "inventory_revision").compareTo(reservation.inventoryRevision())
                < 0) {
          throw conflict(
              "Current reservation no longer matches its immutable local operation source");
        }
      }
    } catch (IllegalStateException conflict) {
      throw conflict;
    } catch (RuntimeException malformed) {
      throw conflict("Prepared ordinary NEW reservation is malformed");
    }
    if (!reservation.reservationId().equals(prepared.issuerReservationId())
        || !reservation.transitionId().equals(request.transitionId())
        || !reservation.bindingGeneration().equals(prepared.bindingGeneration())
        || !reservation.reservationFence().equals(prepared.reservationFence())
        || !reservation.owner().equals(request.candidate())
        || !reservation
            .issuerCoverageOperationId()
            .equals(request.issuerReservation().issuerCoverageOperationId())
        || !reservation
            .issuerCoverageOperationFence()
            .equals(request.issuerReservation().issuerCoverageOperationFence())
        || !reservation.coverageFence().equals(request.issuerReservation().coverageFence())
        || !reservation
            .inventorySnapshotRevision()
            .equals(request.issuerReservation().inventorySnapshotRevision())
        || (operationRow != null
            && (!reservation.reservationId().equals(requireUuid(operationRow, "reservation_id"))
                || !reservation
                    .reservationFence()
                    .equals(requireUuid(operationRow, "reservation_fence"))
                || !reservation
                    .issuerCoverageOperationId()
                    .equals(requireUuid(operationRow, "original_issuer_coverage_operation_id"))
                || !reservation
                    .issuerCoverageOperationFence()
                    .equals(readPositive(operationRow, "original_issuer_coverage_operation_fence"))
                || !reservation
                    .coverageFence()
                    .equals(readPositive(operationRow, "original_coverage_fence"))
                || !reservation
                    .inventorySnapshotRevision()
                    .equals(readPositive(operationRow, "original_inventory_snapshot_revision"))))) {
      throw conflict("Persisted reservation differs from its exact original local request");
    }
    return reservation;
  }

  private static CanonicalGameplayBindingIdentity requireIdentityMatchesReservation(
      CanonicalGameplayBindingIdentity expected, Record row) {
    if (!expected.accountId().equals(row.get("account_id", UUID.class))
        || !expected.tenantId().equals(row.get("tenant_id", UUID.class))
        || !expected.gameInstanceId().equals(row.get("game_instance_id", UUID.class))
        || expected.runtimeGameInstanceId()
            != Objects.requireNonNull(row.get("runtime_game_instance_id", Long.class))
        || !expected.sessionId().equals(row.get("session_id", String.class))
        || !expected.issuerId().equals(row.get("issuer_id", String.class))
        || !expected.issuerAuthGeneration().equals(readPositive(row, "issuer_auth_generation"))
        || !expected
            .playableStateNamespaceId()
            .equals(row.get("playable_state_namespace_id", UUID.class))
        || !expected.characterId().equals(row.get("character_id", UUID.class))
        || !expected.playableStateScope().equals(row.get("playable_state_scope", String.class))
        || !expected.regionId().equals(row.get("region_id", UUID.class))
        || !expected.regionEpoch().equals(readPositive(row, "region_epoch"))
        || !expected
            .issuerIndexLayoutVersion()
            .equals(readPositive(row, "issuer_index_layout_version"))
        || !expected
            .issuerIndexPartitionCount()
            .equals(readPositive(row, "issuer_index_partition_count"))
        || !expected
            .issuerIndexPartitionCapacity()
            .equals(readPositive(row, "issuer_index_partition_capacity"))
        || !Arrays.equals(expected.bindingRef().bytes(), requireBytes(row, "binding_ref"))) {
      throw conflict(
          "Persisted issuer reservation owner differs from the exact candidate identity");
    }
    return expected;
  }

  private static AccountControlSnapshot readAndRequireAccountControl(
      DSLContext transaction,
      UUID accountId,
      CanonicalGameplayAccountCoverageEvidence expectedEvidence) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM " + ACCOUNT_CONTROL + " WHERE account_id = ?", accountId);
    if (row == null) throw conflict("Current Account coverage control row is missing");
    AccountControlSnapshot current = AccountControlSnapshot.from(row, accountId);
    if (!current.evidence().equals(expectedEvidence)) {
      throw conflict("Current Account coverage control changed during ordinary NEW preparation");
    }
    return current;
  }

  private static AccountControlSnapshot readCapturedAccountControl(
      Record operationRow,
      UUID accountId,
      CanonicalGameplayAccountCoverageEvidence originalEvidence) {
    AccountControlSnapshot captured = AccountControlSnapshot.fromOperation(operationRow, accountId);
    if (!captured.evidence().equals(originalEvidence)) {
      throw conflict(
          "Original Account-control provenance differs from the retained first-NEW transition");
    }
    return captured;
  }

  static void requireCapturedAccountControlMatchesTransition(
      Record operationRow, Record transitionRow, UUID accountId) {
    readCapturedAccountControl(
        operationRow,
        accountId,
        CanonicalGameplayBindingInventoryRepository.toAccountCoverageEvidence(
            transitionRow, accountId));
  }

  static void requireCurrentAccountControlMatchesCaptured(
      DSLContext transaction, Record operationRow, UUID accountId) {
    Record account =
        transaction.fetchOne(
            "SELECT * FROM " + ACCOUNT_CONTROL + " WHERE account_id = ? FOR UPDATE", accountId);
    if (account == null
        || !AccountControlSnapshot.from(account, accountId)
            .equals(AccountControlSnapshot.fromOperation(operationRow, accountId))) {
      throw conflict(
          "Current Account coverage control differs from the original ordinary NEW checkpoint");
    }
  }

  private static void insertOperation(
      DSLContext transaction,
      CanonicalGameplayIssuerBindingLocalOperation.Request request,
      CanonicalGameplayBindingTransitionRequest transitionRequest,
      CanonicalGameplayBindingTransitionSnapshot prepared,
      CanonicalIssuerPartitionReservation reservation,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof proof,
      AccountControlSnapshot account,
      UUID operationId,
      IssuedCounters counters,
      byte[] valueBytes,
      String valueDigest) {
    Object[] values = {
      operationId,
      request.requestId(),
      request.transitionId(),
      ISSUER,
      transitionRequest.candidateAccountIndexFence(),
      prepared.bindingRef().bytes(),
      decimal(prepared.bindingGeneration()),
      reservation.reservationId(),
      reservation.reservationFence(),
      decimal(reservation.partitionId()),
      decimal(prepared.inventoryRevision()),
      null,
      null,
      reservation.issuerCoverageOperationId(),
      decimal(reservation.issuerCoverageOperationFence()),
      decimal(reservation.coverageFence()),
      decimal(reservation.inventorySnapshotRevision()),
      anchor.bootstrapOperationId(),
      anchor.bootstrapRequestId(),
      anchor.buildOperationId(),
      anchor.buildPlanDigest(),
      decimal(anchor.controlEpoch()),
      anchor.issuerCoverageOperationId(),
      decimal(anchor.operationFence()),
      decimal(anchor.coverageFence()),
      decimal(anchor.issuerInventorySnapshotRevision()),
      decimal(anchor.layoutVersion()),
      decimal(anchor.partitionCount()),
      decimal(anchor.partitionCapacity()),
      anchor.mappingIdentity(),
      anchor.layoutDescriptorBytes(),
      anchor.layoutDescriptorDigest(),
      decimal(anchor.issuerAuthGeneration()),
      anchor.sourceCheckpointBytes(),
      anchor.sourceCheckpointDigest(),
      anchor.sourceEventBytes(),
      anchor.sourceEventDigest(),
      anchor.incarnationBytes(),
      anchor.incarnationDigest(),
      anchor.exactAnchorBytes(),
      anchor.exactAnchorDigest(),
      decimal(proof.partitionId()),
      proof.exactPhysicalFieldSetBytes(),
      proof.exactPhysicalFieldSetDigest(),
      proof.physicalCountedCapacityCount(),
      proof.physicalReservationCount(),
      proof.physicalBindingCount(),
      account.state(),
      decimal(account.epoch()),
      account.currentOperationId(),
      account.currentFenceIntentId(),
      decimalNullable(account.currentOperationFence()),
      account.accountAdmissionFence(),
      account.historicalAccountAdmissionFence(),
      account.coverageFence(),
      decimalNullable(account.inventorySnapshotRevision()),
      decimalNullable(account.coverageGeneration()),
      decimal(counters.operationFence()),
      decimal(counters.localMemberGeneration()),
      "ABSENT",
      "ABSENT",
      "PENDING_PHYSICAL_PROOF",
      (short) 2,
      valueBytes,
      valueDigest
    };
    String placeholders = String.join(",", java.util.Collections.nCopies(values.length, "?"));
    transaction.execute(
        "INSERT INTO "
            + OPERATION
            + " ("
            + "operation_id, request_id, transition_id, issuer_id, candidate_account_index_fence,"
            + "binding_ref, binding_generation, reservation_id, reservation_fence, partition_id,"
            + "local_inventory_snapshot_revision, expected_prior_binding_ref, expected_prior_binding_generation,"
            + "original_issuer_coverage_operation_id, original_issuer_coverage_operation_fence,"
            + "original_coverage_fence, original_inventory_snapshot_revision,"
            + "anchor_bootstrap_operation_id, anchor_bootstrap_request_id, anchor_build_operation_id,"
            + "anchor_build_plan_digest, anchor_control_epoch, anchor_issuer_coverage_operation_id,"
            + "anchor_operation_fence, anchor_coverage_fence, anchor_issuer_inventory_snapshot_revision,"
            + "anchor_layout_version, anchor_partition_count, anchor_partition_capacity, anchor_mapping_identity,"
            + "anchor_layout_descriptor_bytes, anchor_layout_descriptor_digest, anchor_issuer_auth_generation,"
            + "anchor_source_checkpoint_bytes, anchor_source_checkpoint_digest, anchor_source_event_bytes,"
            + "anchor_source_event_digest, anchor_incarnation_bytes, anchor_incarnation_digest,"
            + "exact_anchor_bytes, exact_anchor_digest, baseline_partition_id, baseline_field_set_bytes,"
            + "baseline_field_set_digest, baseline_counted_capacity, baseline_reservation_count, baseline_binding_count,"
            + "account_control_state, account_control_epoch, account_current_operation_id,"
            + "account_current_fence_intent_id, account_current_operation_fence, account_admission_fence,"
            + "account_historical_admission_fence, account_coverage_fence, account_inventory_snapshot_revision,"
            + "account_coverage_generation, operation_fence, local_member_generation, expected_old_member_state,"
            + "expected_old_capacity_state, member_status, member_schema_version, member_value_bytes, member_value_digest)"
            + " VALUES ("
            + placeholders
            + ")",
        values);
  }

  static IssuedCounters allocateLocalCounters(
      DSLContext transaction,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalIssuerPartitionReservation reservation) {
    Record counter =
        transaction.fetchOne(
            "SELECT * FROM " + MEMBER_COUNTER + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
    if (counter == null) {
      Record priorBootstrapOperation =
          transaction.fetchOne(
              "SELECT 1 FROM " + BOOTSTRAP_MEMBER_OPERATION + " WHERE issuer_id = ? LIMIT 1",
              ISSUER);
      Record priorLocalOperation =
          transaction.fetchOne(
              "SELECT 1 FROM " + OPERATION + " WHERE issuer_id = ? LIMIT 1", ISSUER);
      if (priorBootstrapOperation != null || priorLocalOperation != null) {
        throw conflict("Issuer-local operation counter is missing after prior issuance");
      }
      transaction.execute(
          "INSERT INTO "
              + MEMBER_COUNTER
              + " (issuer_id, last_issued_operation_fence, last_issued_member_generation, counter_version)"
              + " VALUES (?, 0, 0, 0) ON CONFLICT (issuer_id) DO NOTHING",
          ISSUER);
      counter =
          transaction.fetchOne(
              "SELECT * FROM " + MEMBER_COUNTER + " WHERE issuer_id = ? FOR UPDATE", ISSUER);
      if (counter == null) throw conflict("Issuer-local operation counter could not initialize");
    }
    BigInteger previousFence = readNonNegative(counter, "last_issued_operation_fence");
    BigInteger previousGeneration = readNonNegative(counter, "last_issued_member_generation");
    Long versionValue = counter.get("counter_version", Long.class);
    if (versionValue == null
        || versionValue < 0
        || (versionValue == 0 && (previousFence.signum() != 0 || previousGeneration.signum() != 0))
        || (versionValue > 0
            && (!previousFence.add(BigInteger.ONE).equals(previousGeneration)
                || previousFence.signum() <= 0))) {
      throw conflict("Issuer-local member-operation counter state is malformed");
    }
    BigInteger seed =
        max(
            anchor.controlEpoch(),
            anchor.operationFence(),
            anchor.coverageFence(),
            anchor.issuerInventorySnapshotRevision(),
            reservation.issuerCoverageOperationFence(),
            reservation.coverageFence(),
            reservation.inventorySnapshotRevision(),
            reservation.inventoryRevision(),
            previousFence,
            previousGeneration);
    BigInteger operationFence = seed.add(BigInteger.ONE);
    BigInteger generation = operationFence.add(BigInteger.ONE);
    requireCounterLimit(operationFence, "operationFence");
    requireCounterLimit(generation, "localMemberGeneration");
    long nextVersion;
    try {
      nextVersion = Math.addExact(versionValue, 1L);
    } catch (ArithmeticException overflow) {
      throw conflict("Issuer-local member-operation counter version is exhausted");
    }
    Record updated =
        transaction.fetchOne(
            "UPDATE "
                + MEMBER_COUNTER
                + " SET last_issued_operation_fence = ?, last_issued_member_generation = ?, counter_version = ?"
                + " WHERE issuer_id = ? AND last_issued_operation_fence = ?"
                + " AND last_issued_member_generation = ? AND counter_version = ? RETURNING issuer_id",
            decimal(operationFence),
            decimal(generation),
            nextVersion,
            ISSUER,
            decimal(previousFence),
            decimal(previousGeneration),
            versionValue);
    if (updated == null) throw conflict("Issuer-local operation counter compare-and-set lost");
    return new IssuedCounters(operationFence, generation);
  }

  private static void requireSameStoredOperation(
      Record row,
      CanonicalGameplayIssuerBindingLocalOperation.Request request,
      CanonicalGameplayBindingTransitionSnapshot prepared,
      CanonicalIssuerPartitionReservation reservation,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof proof,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState,
      AccountControlSnapshot account,
      boolean allowHistoricalPreparedRevision) {
    CanonicalGameplayIssuerMemberV2Value storedMemberValue;
    try {
      storedMemberValue =
          CanonicalGameplayIssuerMemberV2Codec.decode(requireBytes(row, "member_value_bytes"));
    } catch (RuntimeException malformed) {
      throw conflict("Stored local operation does not contain a canonical v2 member");
    }
    if (!requireUuid(row, "request_id").equals(request.requestId())
        || !requireUuid(row, "transition_id").equals(request.transitionId())
        || !ISSUER.equals(requireText(row, "issuer_id"))
        || !requireUuid(row, "candidate_account_index_fence")
            .equals(prepared.candidateAccountIndexFence())
        || !Arrays.equals(requireBytes(row, "binding_ref"), prepared.bindingRef().bytes())
        || !readPositive(row, "binding_generation").equals(prepared.bindingGeneration())
        || !requireUuid(row, "reservation_id").equals(reservation.reservationId())
        || !requireUuid(row, "reservation_fence").equals(reservation.reservationFence())
        || !readNonNegative(row, "partition_id").equals(reservation.partitionId())
        || row.get("expected_prior_binding_ref", byte[].class) != null
        || row.get("expected_prior_binding_generation") != null
        || !requireUuid(row, "original_issuer_coverage_operation_id")
            .equals(reservation.issuerCoverageOperationId())
        || !readPositive(row, "original_issuer_coverage_operation_fence")
            .equals(reservation.issuerCoverageOperationFence())
        || !readPositive(row, "original_coverage_fence").equals(reservation.coverageFence())
        || !readPositive(row, "original_inventory_snapshot_revision")
            .equals(reservation.inventorySnapshotRevision())
        || !requireUuid(row, "anchor_bootstrap_operation_id").equals(anchor.bootstrapOperationId())
        || !requireUuid(row, "anchor_bootstrap_request_id").equals(anchor.bootstrapRequestId())
        || !requireUuid(row, "anchor_build_operation_id").equals(anchor.buildOperationId())
        || !anchor.buildPlanDigest().equals(requireText(row, "anchor_build_plan_digest"))
        || !readPositive(row, "anchor_control_epoch").equals(anchor.controlEpoch())
        || !requireUuid(row, "anchor_issuer_coverage_operation_id")
            .equals(anchor.issuerCoverageOperationId())
        || !readPositive(row, "anchor_operation_fence").equals(anchor.operationFence())
        || !readPositive(row, "anchor_coverage_fence").equals(anchor.coverageFence())
        || !readPositive(row, "anchor_issuer_inventory_snapshot_revision")
            .equals(anchor.issuerInventorySnapshotRevision())
        || !readPositive(row, "anchor_layout_version").equals(anchor.layoutVersion())
        || !readPositive(row, "anchor_partition_count").equals(anchor.partitionCount())
        || !readPositive(row, "anchor_partition_capacity").equals(anchor.partitionCapacity())
        || !anchor.mappingIdentity().equals(requireText(row, "anchor_mapping_identity"))
        || !Arrays.equals(
            requireBytes(row, "anchor_layout_descriptor_bytes"), anchor.layoutDescriptorBytes())
        || !anchor
            .layoutDescriptorDigest()
            .equals(requireText(row, "anchor_layout_descriptor_digest"))
        || !readPositive(row, "anchor_issuer_auth_generation").equals(anchor.issuerAuthGeneration())
        || !Arrays.equals(
            requireBytes(row, "anchor_source_checkpoint_bytes"), anchor.sourceCheckpointBytes())
        || !anchor
            .sourceCheckpointDigest()
            .equals(requireText(row, "anchor_source_checkpoint_digest"))
        || !Arrays.equals(
            optionalBytes(row, "anchor_source_event_bytes"), anchor.sourceEventBytes())
        || !Objects.equals(
            row.get("anchor_source_event_digest", String.class), anchor.sourceEventDigest())
        || !Arrays.equals(requireBytes(row, "anchor_incarnation_bytes"), anchor.incarnationBytes())
        || !anchor.incarnationDigest().equals(requireText(row, "anchor_incarnation_digest"))
        || !Arrays.equals(requireBytes(row, "exact_anchor_bytes"), anchor.exactAnchorBytes())
        || !anchor.exactAnchorDigest().equals(requireText(row, "exact_anchor_digest"))
        || !readNonNegative(row, "baseline_partition_id").equals(proof.partitionId())
        || !Arrays.equals(
            requireBytes(row, "baseline_field_set_bytes"), proof.exactPhysicalFieldSetBytes())
        || !proof
            .exactPhysicalFieldSetDigest()
            .equals(requireText(row, "baseline_field_set_digest"))
        || readInt(row, "baseline_counted_capacity") != proof.physicalCountedCapacityCount()
        || readInt(row, "baseline_reservation_count") != proof.physicalReservationCount()
        || readInt(row, "baseline_binding_count") != proof.physicalBindingCount()
        || !Arrays.equals(
            anchor.incarnationBytes(),
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(currentState.incarnation()))
        || !account.equals(
            AccountControlSnapshot.fromOperation(row, request.candidate().accountId()))
        || !readPositive(row, "local_inventory_snapshot_revision")
            .equals(storedMemberValue.reservation().inventoryRevision())
        || (!allowHistoricalPreparedRevision
            && !readPositive(row, "local_inventory_snapshot_revision")
                .equals(prepared.inventoryRevision()))
        || !"ABSENT".equals(requireText(row, "expected_old_member_state"))
        || !"ABSENT".equals(requireText(row, "expected_old_capacity_state"))
        || !"PENDING_PHYSICAL_PROOF".equals(requireText(row, "member_status"))
        || !Objects.equals(row.get("member_schema_version", Short.class), (short) 2)) {
      throw conflict(
          "Stored ordinary NEW operation differs from its current exact anchor, Account, or reservation");
    }
  }

  private static void requireCallerRequestMatches(
      Record row, CanonicalGameplayIssuerBindingLocalOperation.Request request) {
    if (!request.requestId().equals(requireUuid(row, "request_id"))
        || !request.transitionId().equals(requireUuid(row, "transition_id"))
        || !ISSUER.equals(requireText(row, "issuer_id"))
        || !Arrays.equals(
            requireBytes(row, "binding_ref"), request.candidate().bindingRef().bytes())
        || request.expectedPriorBindingRef() != null
        || request.expectedPriorBindingGeneration() != null) {
      throw conflict("Stored local issuer operation is not an ordinary NEW transition");
    }
    CanonicalGameplayIssuerMemberV2Value storedValue;
    try {
      storedValue =
          CanonicalGameplayIssuerMemberV2Codec.decode(requireBytes(row, "member_value_bytes"));
    } catch (RuntimeException malformed) {
      throw conflict("Stored local issuer operation has a malformed candidate identity");
    }
    if (!storedValue.owner().equals(request.candidate())
        || !storedValue.reservation().transitionId().equals(request.transitionId())) {
      throw conflict("Same request or transition identity was retried with a different candidate");
    }
  }

  private static CanonicalIssuerReservationFenceEvidence readOriginalReservationEvidence(
      Record operationRow) {
    return new CanonicalIssuerReservationFenceEvidence(
        requireUuid(operationRow, "reservation_id"),
        requireUuid(operationRow, "reservation_fence"),
        requireUuid(operationRow, "original_issuer_coverage_operation_id"),
        readPositive(operationRow, "original_issuer_coverage_operation_fence"),
        readPositive(operationRow, "original_coverage_fence"),
        readPositive(operationRow, "original_inventory_snapshot_revision"));
  }

  private static void requireCandidateMatchesAnchor(
      CanonicalGameplayBindingIdentity candidate, CanonicalGameplayIssuerInstalledAnchor anchor) {
    if (!ISSUER.equals(candidate.issuerId())
        || !candidate.issuerId().equals(anchor.issuerId())
        || !candidate.issuerAuthGeneration().equals(anchor.issuerAuthGeneration())
        || !candidate.issuerIndexLayoutVersion().equals(anchor.layoutVersion())
        || !candidate.issuerIndexPartitionCount().equals(anchor.partitionCount())
        || !candidate.issuerIndexPartitionCapacity().equals(anchor.partitionCapacity())) {
      throw conflict("Caller candidate differs from the exact installed issuer source or layout");
    }
  }

  static boolean sameCurrentIssuerState(
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState left,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState right) {
    var leftCheckpoint = left.accountCheckpoint();
    var rightCheckpoint = right.accountCheckpoint();
    return leftCheckpoint.issuerId().equals(rightCheckpoint.issuerId())
        && leftCheckpoint.issuerAuthGeneration().equals(rightCheckpoint.issuerAuthGeneration())
        && Arrays.equals(
            leftCheckpoint.exactCheckpointBytes(), rightCheckpoint.exactCheckpointBytes())
        && leftCheckpoint.checkpointDigest().equals(rightCheckpoint.checkpointDigest())
        && Arrays.equals(
            leftCheckpoint.latestIssuerEventBytes(), rightCheckpoint.latestIssuerEventBytes())
        && Objects.equals(
            leftCheckpoint.latestIssuerEventDigest(), rightCheckpoint.latestIssuerEventDigest())
        && Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(left.incarnation()),
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(right.incarnation()));
  }

  private static UUID freshOperationId(
      CanonicalGameplayIssuerBindingLocalOperation.Request request,
      CanonicalIssuerPartitionReservation reservation,
      CanonicalGameplayIssuerInstalledAnchor anchor) {
    UUID result;
    do {
      result = freshNonNilUuid();
    } while (result.equals(request.requestId())
        || result.equals(request.transitionId())
        || result.equals(reservation.reservationId())
        || result.equals(reservation.reservationFence())
        || result.equals(anchor.bootstrapOperationId())
        || result.equals(anchor.bootstrapRequestId())
        || result.equals(anchor.buildOperationId()));
    return result;
  }

  private static UUID freshNonNilUuid() {
    UUID value;
    do {
      value = UUID.randomUUID();
    } while (value.equals(new UUID(0L, 0L)));
    return value;
  }

  private static BigInteger max(BigInteger... values) {
    BigInteger result = BigInteger.ZERO;
    for (BigInteger value : values) if (value.compareTo(result) > 0) result = value;
    return result;
  }

  private static void requireCounterLimit(BigInteger value, String name) {
    if (value.signum() <= 0 || value.toString().length() > 256) {
      throw conflict(name + " exceeds the bounded local issuer counter range");
    }
  }

  private static BigDecimal decimal(BigInteger value) {
    return new BigDecimal(value);
  }

  private static BigDecimal decimalNullable(BigInteger value) {
    return value == null ? null : decimal(value);
  }

  private static BigInteger readPositive(Record row, String field) {
    BigInteger value = readNonNegative(row, field);
    if (value.signum() <= 0) throw conflict(field + " must be positive");
    return value;
  }

  private static BigInteger readNonNegative(Record row, String field) {
    Object value = row.get(field);
    if (!(value instanceof Number number)) throw conflict(field + " is missing or nonnumeric");
    BigInteger integer;
    try {
      integer =
          number instanceof BigDecimal decimal
              ? decimal.toBigIntegerExact()
              : BigInteger.valueOf(number.longValue());
    } catch (ArithmeticException malformed) {
      throw conflict(field + " is not an exact integer");
    }
    if (integer.signum() < 0 || integer.toString().length() > 256)
      throw conflict(field + " is outside its bounded counter range");
    return integer;
  }

  private static int readInt(Record row, String field) {
    try {
      return readNonNegative(row, field).intValueExact();
    } catch (ArithmeticException malformed) {
      throw conflict(field + " exceeds its exact integer range");
    }
  }

  private static UUID requireUuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null) throw conflict(field + " is missing");
    return value;
  }

  private static String requireText(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) throw conflict(field + " is missing");
    return value;
  }

  private static byte[] requireBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null || value.length == 0) throw conflict(field + " is missing or empty");
    return value;
  }

  private static byte[] optionalBytes(Record row, String field) {
    return row.get(field, byte[].class);
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw conflict("Issuer local-operation owner cannot join an ambient SQL transaction");
    }
  }

  private static IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }

  record IssuedCounters(BigInteger operationFence, BigInteger localMemberGeneration) {}

  private record AccountControlSnapshot(
      String state,
      BigInteger epoch,
      UUID currentOperationId,
      UUID currentFenceIntentId,
      BigInteger currentOperationFence,
      UUID accountAdmissionFence,
      UUID historicalAccountAdmissionFence,
      UUID coverageFence,
      BigInteger inventorySnapshotRevision,
      BigInteger coverageGeneration,
      CanonicalGameplayAccountCoverageEvidence evidence) {
    private static AccountControlSnapshot from(Record row, UUID accountId) {
      String state = requireText(row, "state");
      BigInteger epoch = readNonNegative(row, "last_operation_fence");
      UUID operationId = row.get("current_operation_id", UUID.class);
      UUID intentId = row.get("current_fence_intent_id", UUID.class);
      BigInteger operationFence = nullableInteger(row, "current_operation_fence");
      UUID admissionFence = row.get("account_admission_fence", UUID.class);
      UUID historicalFence = row.get("historical_account_admission_fence", UUID.class);
      UUID coverageFence = row.get("coverage_fence", UUID.class);
      BigInteger inventoryRevision = nullableInteger(row, "inventory_snapshot_revision");
      BigInteger coverageGeneration = nullableInteger(row, "coverage_generation");
      CanonicalGameplayAccountCoverageEvidence evidence;
      if ("NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(state)) {
        if (operationId != null
            || intentId != null
            || operationFence != null
            || admissionFence != null
            || historicalFence != null
            || coverageFence != null
            || inventoryRevision != null
            || coverageGeneration != null) {
          throw conflict("No-active Account control tuple contains active fence fields");
        }
        evidence = CanonicalGameplayAccountCoverageEvidence.noActiveFlow();
        return new AccountControlSnapshot(
            state, epoch, null, null, null, null, null, null, null, null, evidence);
      } else if ("HISTORICAL".equals(state)) {
        if (operationId == null
            || intentId != null
            || operationFence == null
            || admissionFence != null
            || historicalFence == null
            || coverageFence == null
            || inventoryRevision == null
            || coverageGeneration == null) {
          throw conflict("Historical Account control tuple is incomplete");
        }
        evidence =
            CanonicalGameplayAccountCoverageEvidence.historical(
                operationId,
                operationFence,
                historicalFence,
                coverageFence,
                accountId,
                inventoryRevision,
                coverageGeneration);
        return new AccountControlSnapshot(
            state,
            epoch,
            operationId,
            null,
            operationFence,
            null,
            historicalFence,
            coverageFence,
            inventoryRevision,
            coverageGeneration,
            evidence);
      } else {
        throw conflict("Active or unknown Account control state blocks ordinary NEW");
      }
    }

    private static AccountControlSnapshot fromOperation(Record row, UUID accountId) {
      String state = requireText(row, "account_control_state");
      BigInteger epoch = readNonNegative(row, "account_control_epoch");
      UUID operationId = row.get("account_current_operation_id", UUID.class);
      UUID intentId = row.get("account_current_fence_intent_id", UUID.class);
      BigInteger operationFence = nullableInteger(row, "account_current_operation_fence");
      UUID admissionFence = row.get("account_admission_fence", UUID.class);
      UUID historicalFence = row.get("account_historical_admission_fence", UUID.class);
      UUID coverageFence = row.get("account_coverage_fence", UUID.class);
      BigInteger inventoryRevision = nullableInteger(row, "account_inventory_snapshot_revision");
      BigInteger coverageGeneration = nullableInteger(row, "account_coverage_generation");
      CanonicalGameplayAccountCoverageEvidence evidence;
      if ("NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(state)) {
        if (operationId != null
            || intentId != null
            || operationFence != null
            || admissionFence != null
            || historicalFence != null
            || coverageFence != null
            || inventoryRevision != null
            || coverageGeneration != null) {
          throw conflict("Stored no-active Account tuple contains active fence fields");
        }
        evidence = CanonicalGameplayAccountCoverageEvidence.noActiveFlow();
        return new AccountControlSnapshot(
            state, epoch, null, null, null, null, null, null, null, null, evidence);
      } else if ("HISTORICAL".equals(state)) {
        if (operationId == null
            || intentId != null
            || operationFence == null
            || admissionFence != null
            || historicalFence == null
            || coverageFence == null
            || inventoryRevision == null
            || coverageGeneration == null) {
          throw conflict("Stored historical Account control tuple is incomplete");
        }
        evidence =
            CanonicalGameplayAccountCoverageEvidence.historical(
                operationId,
                operationFence,
                historicalFence,
                coverageFence,
                accountId,
                inventoryRevision,
                coverageGeneration);
        return new AccountControlSnapshot(
            state,
            epoch,
            operationId,
            null,
            operationFence,
            null,
            historicalFence,
            coverageFence,
            inventoryRevision,
            coverageGeneration,
            evidence);
      } else {
        throw conflict("Stored operation has an active or unknown Account control state");
      }
    }
  }

  private static BigInteger nullableInteger(Record row, String field) {
    Object value = row.get(field);
    if (value == null) return null;
    if (!(value instanceof Number number)) throw conflict(field + " is not numeric");
    try {
      BigInteger result =
          number instanceof BigDecimal decimal
              ? decimal.toBigIntegerExact()
              : BigInteger.valueOf(number.longValue());
      if (result.signum() < 0) throw conflict(field + " must be nonnegative");
      return result;
    } catch (ArithmeticException malformed) {
      throw conflict(field + " is not an exact integer");
    }
  }
}
