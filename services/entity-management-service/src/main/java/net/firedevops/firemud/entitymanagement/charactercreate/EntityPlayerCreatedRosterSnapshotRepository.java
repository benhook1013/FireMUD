package net.firedevops.firemud.entitymanagement.charactercreate;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered owner-local immutable PLAYER_CREATED roster capture. The only source input is an
 * AuthenticatedSource produced by Entity's configured mTLS client; this repository performs no
 * remote calls and establishes no selection, activation, admission, or PKI authority.
 */
public final class EntityPlayerCreatedRosterSnapshotRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;
  private final TransactionTemplate transaction;

  public EntityPlayerCreatedRosterSnapshotRepository(
      DSLContext dsl, PlatformTransactionManager transactionManager) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    transaction.setReadOnly(false);
  }

  /**
   * Captures the current persisted owner-resolved roster after the authenticated source RPC has
   * completed. The original owner fences, local admission nonce, snapshot, and retained receipt
   * commit together in one short writable transaction.
   */
  public CanonicalPlayerCreatedRosterResponse capture(
      CanonicalPlayerCreatedRosterRequest request,
      EntityPlayerCreatedRosterSourceClient.AuthenticatedSource source,
      GetCanonicalGameplayRosterReferenceResponse original,
      UUID localAdmission) {
    EntityCharacterCreateRepository.outsideTransaction();
    CanonicalPlayerCreatedRosterEvidence.requireRequest(request);
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(original, "original");
    requireAdmissionNonce(localAdmission);

    var target = request.getExpectedTarget();
    if (!source.target().toWire().equals(target)) {
      throw new IllegalStateException("CANONICAL_ROSTER_PLAYER_CREATED_SOURCE_CHANGED");
    }
    var sourceIdentity = sourceIdentity(source);
    if (!request.getExpectedSource().equals(sourceIdentity)) {
      throw new IllegalStateException("CANONICAL_ROSTER_PLAYER_CREATED_SOURCE_CHANGED");
    }
    var published = EntityPlayerCreatedRosterReadService.PublishedIdentity.from(source);
    var expectedOriginal =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
            UUID.fromString(request.getRequestUuid()),
            UUID.fromString(request.getCanonicalAccountUuid()),
            UUID.fromString(request.getPlayerExecutionContext().getSessionId()),
            published.targetNamespace(),
            target,
            sourceIdentity);
    var checkedOriginal =
        CanonicalGameplayRosterReferenceEvidence.requireResponse(expectedOriginal, original);
    CanonicalGameplayRosterReferenceEvidence.requireDereference(checkedOriginal);

    EntityPlayerCreatedRosterReadService.AuthenticatedAccountSelector account =
        new EntityPlayerCreatedRosterReadService.AuthenticatedAccountSelector(
            UUID.fromString(request.getCanonicalAccountUuid()));
    return Objects.requireNonNull(
        transaction.execute(
            ignored -> {
              DSLContext tx = dsl;
              var retention = new CanonicalGameplayRosterRetentionRepository(tx);

              // Keep the retention family's established admission-before-snapshot lock order.
              retention.requireAdmission(request, localAdmission);
              Optional<CanonicalPlayerCreatedRosterResponse> retained =
                  retention.originalPlayerCreatedReceipt(checkedOriginal);
              if (retained.isPresent()) {
                Record boundAdmission =
                    tx.fetchOne(
                        "SELECT snapshot_uuid FROM entity_canonical_roster_admissions WHERE operation_uuid = ? AND admission_uuid = ? AND admission_state = 'OPEN' FOR UPDATE",
                        UUID.fromString(request.getRequestUuid()),
                        localAdmission);
                if (boundAdmission == null
                    || !UUID.fromString(retained.orElseThrow().getRoster().getSnapshotUuid())
                        .equals(boundAdmission.get("snapshot_uuid", UUID.class))) {
                  throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
                }
                if (!checkedOriginal.getCanonicalPlayerCreatedRosterReceipt().isEmpty()
                    && !checkedOriginal
                        .getCanonicalPlayerCreatedRosterReceipt()
                        .equals(retained.get().toByteString())) {
                  throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_CONFLICT");
                }
                return CanonicalPlayerCreatedRosterEvidence.requireResponse(
                    request, retained.orElseThrow());
              }
              if (!checkedOriginal.getCanonicalPlayerCreatedRosterReceipt().isEmpty()) {
                throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_UNRETAINED");
              }

              CanonicalGameplayRosterTarget currentTarget =
                  CanonicalGameplayRosterTarget.fromWire(target);
              lockCreationRoster(tx, currentTarget, account.accountId());

              List<CanonicalGameplayRosterActor> actors =
                  EntityCharacterCreateRepository.readPlayerCreatedRosterWithinOwnerTransaction(
                      tx, account, published);
              List<net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor>
                  wireActors =
                      actors.stream()
                          .map(EntityPlayerCreatedRosterSnapshotRepository::wireActor)
                          .toList();
              String snapshotDigest =
                  CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
                      request.getCanonicalAccountUuid(), target, sourceIdentity, wireActors);

              CanonicalPlayerCreatedRosterResponse receipt =
                  persistOrReuseSnapshot(
                      tx,
                      request,
                      currentTarget,
                      sourceIdentity,
                      sourceIdentity.toByteArray(),
                      wireActors,
                      snapshotDigest);

              UUID snapshotUuid = UUID.fromString(receipt.getRoster().getSnapshotUuid());
              retention.bindAdmission(
                  UUID.fromString(request.getRequestUuid()), localAdmission, snapshotUuid);
              retention.bindPlayerCreatedOriginal(checkedOriginal, receipt);
              CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                  checkedOriginal.getRequest(),
                  checkedOriginal.getOwnerFence(),
                  UUID.fromString(checkedOriginal.getTargetFenceUuid()),
                  receipt);
              return CanonicalPlayerCreatedRosterEvidence.requireResponse(request, receipt);
            }),
        "PLAYER_CREATED capture transaction returned no receipt");
  }

  /**
   * Reads the exact committed terminal for the frozen selected actor without creating a receipt.
   */
  public CanonicalSelectedPlayerCreatedCharacterResponse readSelected(
      CanonicalSelectedPlayerCreatedCharacterRequest request,
      EntityPlayerCreatedRosterSourceClient.AuthenticatedSource source,
      GetCanonicalGameplayRosterReferenceResponse original) {
    EntityCharacterCreateRepository.outsideTransaction();
    CanonicalPlayerCreatedRosterEvidence.requireSelectedRequest(request);
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(original, "original");

    var target = request.getExpectedTarget();
    if (!source.target().toWire().equals(target)) {
      throw new IllegalStateException("CANONICAL_ROSTER_PLAYER_CREATED_SOURCE_CHANGED");
    }
    var sourceIdentity = sourceIdentity(source);
    if (!request.getExpectedSource().equals(sourceIdentity)) {
      throw new IllegalStateException("CANONICAL_ROSTER_PLAYER_CREATED_SOURCE_CHANGED");
    }
    var published = EntityPlayerCreatedRosterReadService.PublishedIdentity.from(source);
    var createdRequest =
        CanonicalPlayerCreatedRosterRequest.newBuilder()
            .setRequestUuid(request.getRequestUuid())
            .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
            .setExpectedTarget(target)
            .setPlayerExecutionContext(
                request.getPlayerExecutionContext().toBuilder().clearCharacterId().build())
            .setExpectedSource(sourceIdentity)
            .build();
    CanonicalPlayerCreatedRosterEvidence.requireRequest(createdRequest);
    var expectedOriginal =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
            UUID.fromString(request.getRequestUuid()),
            UUID.fromString(request.getCanonicalAccountUuid()),
            UUID.fromString(request.getPlayerExecutionContext().getSessionId()),
            published.targetNamespace(),
            target,
            sourceIdentity);
    var checkedOriginal =
        CanonicalGameplayRosterReferenceEvidence.requireResponse(expectedOriginal, original);
    CanonicalGameplayRosterReferenceEvidence.requireDereference(checkedOriginal);
    if (!request.getSelectedCharacterUuid().equals(checkedOriginal.getSelectedCharacterUuid())) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_SELECTION_CHANGED");
    }
    var originalRoster =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedReceipt(checkedOriginal);
    if (!request
            .getExpectedSnapshot()
            .getSnapshotUuid()
            .equals(originalRoster.getRoster().getSnapshotUuid())
        || !request
            .getExpectedSnapshot()
            .getSnapshotDigest()
            .equals(originalRoster.getRoster().getSnapshotDigest())
        || originalRoster.getRoster().getActorsList().stream()
                .filter(
                    actor -> request.getSelectedCharacterUuid().equals(actor.getCharacterUuid()))
                .count()
            != 1L) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_SELECTION_CHANGED");
    }

    var account =
        new EntityPlayerCreatedRosterReadService.AuthenticatedAccountSelector(
            UUID.fromString(request.getCanonicalAccountUuid()));
    return Objects.requireNonNull(
        transaction.execute(
            ignored -> {
              DSLContext tx = dsl;
              var retention = new CanonicalGameplayRosterRetentionRepository(tx);
              UUID operation = UUID.fromString(request.getRequestUuid());
              Record admission =
                  tx.fetchOne(
                      "SELECT admission_uuid, snapshot_uuid FROM entity_canonical_roster_admissions WHERE operation_uuid = ? FOR UPDATE",
                      operation);
              if (admission == null) {
                throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
              }
              UUID nonce = admission.get("admission_uuid", UUID.class);
              retention.requireAdmission(createdRequest, nonce);
              if (!UUID.fromString(request.getExpectedSnapshot().getSnapshotUuid())
                  .equals(admission.get("snapshot_uuid", UUID.class))) {
                throw new IllegalStateException("CANONICAL_ROSTER_SNAPSHOT_STALE");
              }
              CanonicalPlayerCreatedRosterResponse retained =
                  retention
                      .originalPlayerCreatedReceipt(checkedOriginal)
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "CANONICAL_ROSTER_ORIGINAL_RECEIPT_UNRETAINED"));
              if (!retained.equals(originalRoster)) {
                throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_CONFLICT");
              }

              CanonicalGameplayRosterTarget currentTarget =
                  CanonicalGameplayRosterTarget.fromWire(target);
              lockCreationRoster(tx, currentTarget, account.accountId());
              List<CanonicalGameplayRosterActor> actors =
                  EntityCharacterCreateRepository.readPlayerCreatedRosterWithinOwnerTransaction(
                      tx, account, published);
              List<net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor>
                  wireActors =
                      actors.stream()
                          .map(EntityPlayerCreatedRosterSnapshotRepository::wireActor)
                          .toList();
              String digest =
                  CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
                      request.getCanonicalAccountUuid(), target, sourceIdentity, wireActors);
              if (!digest.equals(request.getExpectedSnapshot().getSnapshotDigest())) {
                throw new IllegalStateException("CANONICAL_ROSTER_SNAPSHOT_STALE");
              }
              CanonicalPlayerCreatedRosterResponse readback =
                  readBackSnapshot(
                      tx,
                      createdRequest,
                      UUID.fromString(request.getExpectedSnapshot().getSnapshotUuid()),
                      currentTarget,
                      sourceIdentity,
                      sourceIdentity.toByteArray(),
                      wireActors,
                      digest);
              long selectedCount =
                  actors.stream()
                      .filter(
                          actor ->
                              actor
                                  .characterUuid()
                                  .toString()
                                  .equals(request.getSelectedCharacterUuid()))
                      .count();
              if (selectedCount != 1L) {
                throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_SELECTION_CHANGED");
              }

              var terminal =
                  EntityCharacterCreateRepository
                      .readSelectedPlayerCreatedTerminalWithinOwnerTransaction(
                          tx,
                          account,
                          published,
                          UUID.fromString(request.getSelectedCharacterUuid()));
              var originalCreateRequest = terminal.getOriginalRequest().getOriginal();
              var result =
                  CanonicalSelectedPlayerCreatedCharacterResponse.newBuilder()
                      .setRequestUuid(request.getRequestUuid())
                      .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
                      .setSelectedCharacterUuid(request.getSelectedCharacterUuid())
                      .setTarget(target)
                      .setSnapshot(
                          CanonicalGameplayRosterSnapshotReference.newBuilder()
                              .setSnapshotUuid(readback.getRoster().getSnapshotUuid())
                              .setSnapshotDigest(readback.getRoster().getSnapshotDigest()))
                      .setSourceIdentity(sourceIdentity)
                      .setCreationOperationUuid(originalCreateRequest.getOperationId())
                      .setMutationDigest(originalCreateRequest.getMutationDigest())
                      .setTerminal(terminal)
                      .build();
              var checkedResult =
                  CanonicalPlayerCreatedRosterEvidence.requireSelectedResponse(request, result);
              if (!checkedOriginal.getCanonicalSelectedPlayerCreatedCharacterReceipt().isEmpty()
                  && !CanonicalGameplayRosterReferenceEvidence
                      .selectedPlayerCreatedCharacterReceipt(checkedOriginal)
                      .equals(checkedResult)) {
                throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_CONFLICT");
              }
              return checkedResult;
            }),
        "PLAYER_CREATED selected read returned no terminal");
  }

  private static void lockCreationRoster(
      DSLContext tx, CanonicalGameplayRosterTarget target, UUID account) {
    UUID tenant = target.tenantUuid();
    UUID namespace = target.playableStateNamespaceId();
    String scope = target.playableStateScope().name();

    tx.execute(
        "INSERT INTO entity_playable_state_namespace_scopes (tenant_uuid, playable_state_namespace_id, playable_state_scope) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
        tenant,
        namespace,
        scope);
    Record enrollment =
        tx.fetchOne(
            "SELECT playable_state_scope FROM entity_playable_state_namespace_scopes WHERE tenant_uuid = ? AND playable_state_namespace_id = ? FOR UPDATE",
            tenant,
            namespace);
    if (enrollment == null || !scope.equals(enrollment.get("playable_state_scope", String.class))) {
      throw new IllegalStateException("PLAYER_CREATED namespace enrollment changed");
    }

    tx.execute(
        "INSERT INTO entity_tenant_identities (canonical_tenant_uuid) VALUES (?) ON CONFLICT DO NOTHING",
        tenant);
    Record tenantIdentity =
        tx.fetchOne(
            "SELECT entity_tenant_row_id FROM entity_tenant_identities WHERE canonical_tenant_uuid = ? FOR UPDATE",
            tenant);
    if (tenantIdentity == null) {
      throw new IllegalStateException("Entity tenant identity unavailable");
    }

    tx.execute(
        "INSERT INTO entity_character_create_roster_guards (tenant_uuid, playable_state_namespace_id, account_uuid) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
        tenant,
        namespace,
        account);
    Record rosterGuard =
        tx.fetchOne(
            "SELECT account_uuid FROM entity_character_create_roster_guards WHERE tenant_uuid = ? AND playable_state_namespace_id = ? AND account_uuid = ? FOR UPDATE",
            tenant,
            namespace,
            account);
    if (rosterGuard == null || !account.equals(rosterGuard.get("account_uuid", UUID.class))) {
      throw new IllegalStateException("PLAYER_CREATED account roster guard unavailable");
    }
  }

  private static CanonicalPlayerCreatedRosterResponse persistOrReuseSnapshot(
      DSLContext tx,
      CanonicalPlayerCreatedRosterRequest request,
      CanonicalGameplayRosterTarget target,
      CanonicalPlayerCreatedRosterSourceIdentity source,
      byte[] sourceBytes,
      List<net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor> actors,
      String digest) {
    Record existing =
        tx.fetchOne(
            "SELECT snapshot_uuid FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_digest = ? FOR UPDATE",
            digest);
    UUID snapshotUuid;
    if (existing != null) {
      snapshotUuid = existing.get("snapshot_uuid", UUID.class);
    } else {
      snapshotUuid = UUID.randomUUID();
      tx.execute(
          "INSERT INTO entity_canonical_gameplay_roster_snapshots (snapshot_uuid, snapshot_digest, canonical_account_uuid, tenant_uuid, realm_uuid, world_slug, realm_slug, game_instance_uuid, catalog_revision, canonical_version_uuid, pointer_version, active_world_epoch, published_policy_digest, published_release_bundle_ref, admission_pointer_snapshot_digest, published_owner_proof_digest, playable_state_namespace_uuid, playable_state_scope, entry_policy, publication_evidence_format, canonical_player_created_source_identity, roster_count, construction_state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PLAYER_CREATED', 'CANONICAL_UUID_V2', ?, ?, 'BUILDING')",
          snapshotUuid,
          digest,
          UUID.fromString(request.getCanonicalAccountUuid()),
          target.tenantUuid(),
          target.realmUuid(),
          target.worldSlug(),
          target.realmSlug(),
          target.gameInstanceUuid(),
          target.catalogRevision(),
          target.canonicalVersionUuid(),
          target.pointerVersion(),
          target.activeWorldEpoch(),
          target.publishedPolicyDigest(),
          target.publishedReleaseBundleRef(),
          target.admissionPointerSnapshotDigest(),
          target.publishedOwnerProofDigest(),
          target.playableStateNamespaceId(),
          target.playableStateScope().name(),
          sourceBytes,
          (short) actors.size());
      for (int i = 0; i < actors.size(); i++) {
        var actor = actors.get(i);
        tx.execute(
            "INSERT INTO entity_canonical_gameplay_roster_snapshot_actors (snapshot_uuid, ordinal_position, character_uuid, display_name, actor_kind) VALUES (?, ?, ?, ?, 'PLAYER')",
            snapshotUuid,
            (short) i,
            UUID.fromString(actor.getCharacterUuid()),
            actor.getDisplayName());
      }
      int sealed =
          tx.execute(
              "UPDATE entity_canonical_gameplay_roster_snapshots SET construction_state = 'SEALED' WHERE snapshot_uuid = ? AND construction_state = 'BUILDING'",
              snapshotUuid);
      if (sealed != 1) {
        throw new IllegalStateException("PLAYER_CREATED roster snapshot did not seal");
      }
    }

    return readBackSnapshot(tx, request, snapshotUuid, target, source, sourceBytes, actors, digest);
  }

  private static CanonicalPlayerCreatedRosterResponse readBackSnapshot(
      DSLContext tx,
      CanonicalPlayerCreatedRosterRequest request,
      UUID snapshotUuid,
      CanonicalGameplayRosterTarget target,
      CanonicalPlayerCreatedRosterSourceIdentity expectedSource,
      byte[] expectedSourceBytes,
      List<net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor> expectedActors,
      String expectedDigest) {
    Record snapshot =
        tx.fetchOne(
            "SELECT * FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ? FOR SHARE",
            snapshotUuid);
    if (snapshot == null
        || !expectedDigest.equals(snapshot.get("snapshot_digest", String.class))
        || !UUID.fromString(request.getCanonicalAccountUuid())
            .equals(snapshot.get("canonical_account_uuid", UUID.class))
        || !target.tenantUuid().equals(snapshot.get("tenant_uuid", UUID.class))
        || !target.realmUuid().equals(snapshot.get("realm_uuid", UUID.class))
        || !target.worldSlug().equals(snapshot.get("world_slug", String.class))
        || !target.realmSlug().equals(snapshot.get("realm_slug", String.class))
        || !target.gameInstanceUuid().equals(snapshot.get("game_instance_uuid", UUID.class))
        || target.catalogRevision() != snapshot.get("catalog_revision", Long.class)
        || !target.canonicalVersionUuid().equals(snapshot.get("canonical_version_uuid", UUID.class))
        || target.pointerVersion() != snapshot.get("pointer_version", Long.class)
        || target.activeWorldEpoch() != snapshot.get("active_world_epoch", Long.class)
        || !target
            .publishedPolicyDigest()
            .equals(snapshot.get("published_policy_digest", String.class))
        || !target
            .publishedReleaseBundleRef()
            .equals(snapshot.get("published_release_bundle_ref", String.class))
        || !target
            .admissionPointerSnapshotDigest()
            .equals(snapshot.get("admission_pointer_snapshot_digest", String.class))
        || !target
            .publishedOwnerProofDigest()
            .equals(snapshot.get("published_owner_proof_digest", String.class))
        || !target
            .playableStateNamespaceId()
            .equals(snapshot.get("playable_state_namespace_uuid", UUID.class))
        || !target
            .playableStateScope()
            .name()
            .equals(snapshot.get("playable_state_scope", String.class))
        || !"PLAYER_CREATED".equals(snapshot.get("entry_policy", String.class))
        || !"CANONICAL_UUID_V2".equals(snapshot.get("publication_evidence_format", String.class))
        || !Arrays.equals(
            expectedSourceBytes,
            snapshot.get("canonical_player_created_source_identity", byte[].class))
        || !"SEALED".equals(snapshot.get("construction_state", String.class))) {
      throw new IllegalStateException("PLAYER_CREATED roster snapshot readback changed");
    }

    List<Record> storedActors =
        tx.fetch(
            "SELECT ordinal_position, character_uuid, display_name, actor_kind FROM entity_canonical_gameplay_roster_snapshot_actors WHERE snapshot_uuid = ? ORDER BY ordinal_position",
            snapshotUuid);
    Number persistedCountValue = (Number) snapshot.get("roster_count");
    if (persistedCountValue == null) {
      throw new IllegalStateException("PLAYER_CREATED roster snapshot actor count changed");
    }
    int persistedCount = persistedCountValue.intValue();
    if (persistedCount != expectedActors.size() || storedActors.size() != expectedActors.size()) {
      throw new IllegalStateException("PLAYER_CREATED roster snapshot actor count changed");
    }
    var readbackActors =
        new java.util.ArrayList<
            net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor>(
            storedActors.size());
    for (int i = 0; i < storedActors.size(); i++) {
      Record actor = storedActors.get(i);
      var expected = expectedActors.get(i);
      UUID characterUuid = actor.get("character_uuid", UUID.class);
      if (characterUuid == null) {
        throw new IllegalStateException("PLAYER_CREATED roster snapshot actor readback changed");
      }
      Number ordinalPosition = (Number) actor.get("ordinal_position");
      if (ordinalPosition == null) {
        throw new IllegalStateException("PLAYER_CREATED roster snapshot actor readback changed");
      }
      if (ordinalPosition.intValue() != i
          || !"PLAYER".equals(actor.get("actor_kind", String.class))
          || !expected.getCharacterUuid().equals(characterUuid.toString())
          || !expected.getDisplayName().equals(actor.get("display_name", String.class))) {
        throw new IllegalStateException("PLAYER_CREATED roster snapshot actor readback changed");
      }
      readbackActors.add(
          wireActor(
              new CanonicalGameplayRosterActor(
                  characterUuid, actor.get("display_name", String.class))));
    }

    var roster =
        CanonicalGameplayRosterResponse.newBuilder()
            .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
            .setSnapshotUuid(snapshotUuid.toString())
            .setSnapshotDigest(expectedDigest)
            .setTarget(request.getExpectedTarget())
            .addAllActors(readbackActors)
            .build();
    var receipt =
        CanonicalPlayerCreatedRosterResponse.newBuilder()
            .setRoster(roster)
            .setSourceIdentity(expectedSource)
            .build();
    CanonicalPlayerCreatedRosterEvidence.requireResponse(request, receipt);
    return receipt;
  }

  private static net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor wireActor(
      CanonicalGameplayRosterActor actor) {
    return net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
        .setCharacterUuid(actor.characterUuid().toString())
        .setDisplayName(actor.displayName())
        .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
        .build();
  }

  static CanonicalPlayerCreatedRosterSourceIdentity sourceIdentity(
      EntityPlayerCreatedRosterSourceClient.AuthenticatedSource source) {
    if (source.authored().projections().size() != 1) {
      throw new IllegalArgumentException("One current authored PLAYER_CREATED projection required");
    }
    var projection = source.authored().projections().getFirst();
    var descriptor = source.creationDescriptor();
    return CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(
        CanonicalPlayerCreatedRosterSourceIdentity.newBuilder()
            .setSchemaVersion(1)
            .setDescriptorUuid(descriptor.descriptorId())
            .setDescriptorRevisionUuid(descriptor.descriptorRevisionId())
            .setDescriptorDigest(descriptor.digest())
            .setAuthoredBindingUuid(projection.bindingId().toString())
            .setAuthoredBindingDigest(projection.bindingDigest())
            .build());
  }

  private static void requireAdmissionNonce(UUID admission) {
    Objects.requireNonNull(admission, "localAdmission");
    if (NIL_UUID.equals(admission)) {
      throw new IllegalArgumentException("PLAYER_CREATED admission nonce must be non-nil");
    }
  }
}
