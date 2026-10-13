package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.*;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTERS;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.INVENTORY;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEMS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentityStatus;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.InventoryKey;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshot;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshotDigest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceipt;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentResult;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentResult.Outcome;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class CharacterRepository {
  private static final Table<?> ASSIGNMENT_OPERATIONS =
      DSL.table(DSL.name("entity_preseeded_actor_assignment_operations"));
  private static final Table<?> TENANT_IDENTITIES = DSL.table(DSL.name("entity_tenant_identities"));
  private static final Table<?> ROSTER_SNAPSHOTS =
      DSL.table(DSL.name("entity_canonical_gameplay_roster_snapshots"));
  private static final Table<?> ROSTER_SNAPSHOT_ACTORS =
      DSL.table(DSL.name("entity_canonical_gameplay_roster_snapshot_actors"));

  private static final Field<UUID> ASSIGNMENT_UUID = uuidField("assignment_uuid");
  private static final Field<String> INTENT_DIGEST = stringField("intent_digest");
  private static final Field<UUID> ACCOUNT_UUID = uuidField("account_uuid");
  private static final Field<String> ACCOUNT_UUID_PROVENANCE =
      stringField("account_uuid_provenance");
  private static final Field<Short> ACCOUNT_IDENTITY_SCHEMA_VERSION =
      DSL.field(DSL.name("account_identity_schema_version"), Short.class);
  private static final Field<String> ACCOUNT_IDENTITY_TARGET_NAMESPACE =
      stringField("account_identity_target_namespace");
  private static final Field<Long> SOURCE_ACCOUNT_ROW_ID = longField("source_account_row_id");
  private static final Field<String> ELIGIBILITY = stringField("eligibility");
  private static final Field<OffsetDateTime> ELIGIBILITY_EVALUATED_AT =
      DSL.field(DSL.name("eligibility_evaluated_at"), OffsetDateTime.class);
  private static final Field<Long> MEMBERSHIP_AUTHORITY_GENERATION =
      longField("membership_authority_generation");
  private static final Field<String> ELIGIBILITY_EVIDENCE_DIGEST =
      stringField("eligibility_evidence_digest");
  private static final Field<String> ACCOUNT_AUTHORITY_SNAPSHOT_DIGEST =
      stringField("account_authority_snapshot_digest");
  private static final Field<String> ACCOUNT_PURPOSE = stringField("account_purpose");
  private static final Field<String> ACCOUNT_CURRENTNESS = stringField("account_currentness");
  private static final Field<String> PUBLISHED_OWNER_PROOF_DIGEST =
      stringField("published_owner_proof_digest");
  private static final Field<String> PUBLISHED_RELEASE_BUNDLE_REF =
      stringField("published_release_bundle_ref");
  private static final Field<UUID> TENANT_UUID = uuidField("tenant_uuid");
  private static final Field<UUID> CANONICAL_TENANT_UUID = uuidField("canonical_tenant_uuid");
  private static final Field<UUID> REALM_UUID = uuidField("realm_uuid");
  private static final Field<String> WORLD_SLUG = stringField("world_slug");
  private static final Field<String> REALM_SLUG = stringField("realm_slug");
  private static final Field<String> GAME_INSTANCE_ID = stringField("game_instance_id");
  private static final Field<Long> CATALOG_REVISION = longField("catalog_revision");
  private static final Field<UUID> CANONICAL_VERSION_UUID = uuidField("canonical_version_uuid");
  private static final Field<String> PUBLICATION_EVIDENCE_FORMAT =
      stringField("publication_evidence_format");
  private static final String CURRENT_PUBLICATION_EVIDENCE_FORMAT = "CANONICAL_UUID";
  private static final Field<String> FROZEN_POLICY_DIGEST = stringField("frozen_policy_digest");
  private static final Field<UUID> NAMESPACE_UUID = uuidField("playable_state_namespace_id");
  private static final Field<String> PLAYABLE_STATE_SCOPE = stringField("playable_state_scope");
  private static final Field<String> ENTRY_POLICY = stringField("entry_policy");
  private static final Field<String> ACTOR_KIND = stringField("actor_kind");
  private static final Field<String> DISPLAY_NAME = stringField("display_name");
  private static final Field<String> ASSIGNMENT_STATUS = stringField("status");
  private static final Field<UUID> ASSIGNED_CHARACTER_UUID = uuidField("character_uuid");
  private static final Field<OffsetDateTime> ASSIGNMENT_COMPLETED_AT =
      DSL.field(DSL.name("completed_at"), OffsetDateTime.class);
  private static final Field<Long> ENTITY_TENANT_ROW_ID = longField("entity_tenant_row_id");
  private static final Field<UUID> ROSTER_SNAPSHOT_UUID = uuidField("snapshot_uuid");
  private static final Field<String> ROSTER_SNAPSHOT_DIGEST = stringField("snapshot_digest");
  private static final Field<UUID> ROSTER_ACCOUNT_UUID = uuidField("canonical_account_uuid");
  private static final Field<UUID> ROSTER_TENANT_UUID = uuidField("tenant_uuid");
  private static final Field<UUID> ROSTER_REALM_UUID = uuidField("realm_uuid");
  private static final Field<String> ROSTER_WORLD_SLUG = stringField("world_slug");
  private static final Field<String> ROSTER_REALM_SLUG = stringField("realm_slug");
  private static final Field<UUID> ROSTER_GAME_INSTANCE_UUID = uuidField("game_instance_uuid");
  private static final Field<Long> ROSTER_CATALOG_REVISION = longField("catalog_revision");
  private static final Field<UUID> ROSTER_CANONICAL_VERSION_UUID =
      uuidField("canonical_version_uuid");
  private static final Field<Long> ROSTER_POINTER_VERSION = longField("pointer_version");
  private static final Field<Long> ROSTER_ACTIVE_WORLD_EPOCH = longField("active_world_epoch");
  private static final Field<String> ROSTER_PUBLICATION_EVIDENCE_FORMAT =
      stringField("publication_evidence_format");
  private static final String CURRENT_ROSTER_EVIDENCE_FORMAT = "CANONICAL_UUID_V2";
  private static final Field<String> ROSTER_PUBLISHED_POLICY_DIGEST =
      stringField("published_policy_digest");
  private static final Field<String> ROSTER_PUBLISHED_RELEASE_REF =
      stringField("published_release_bundle_ref");
  private static final Field<String> ROSTER_POINTER_SNAPSHOT_DIGEST =
      stringField("admission_pointer_snapshot_digest");
  private static final Field<String> ROSTER_OWNER_PROOF_DIGEST =
      stringField("published_owner_proof_digest");
  private static final Field<UUID> ROSTER_NAMESPACE_UUID =
      uuidField("playable_state_namespace_uuid");
  private static final Field<String> ROSTER_PLAYABLE_SCOPE = stringField("playable_state_scope");
  private static final Field<String> ROSTER_ENTRY_POLICY = stringField("entry_policy");
  private static final Field<Short> ROSTER_COUNT = DSL.field(DSL.name("roster_count"), Short.class);
  private static final Field<String> ROSTER_CONSTRUCTION_STATE = stringField("construction_state");
  private static final Field<Short> ROSTER_ORDINAL =
      DSL.field(DSL.name("ordinal_position"), Short.class);
  private static final Field<UUID> ROSTER_CHARACTER_UUID = uuidField("character_uuid");
  private static final Field<String> ROSTER_DISPLAY_NAME = stringField("display_name");
  private static final Field<String> ROSTER_ACTOR_KIND = stringField("actor_kind");

  private final DSLContext dsl;

  public CharacterRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<Character> findWithInventoryById(Long id) {
    Character character =
        dsl.selectFrom(CHARACTERS)
            .where(CHARACTERS.ID.eq(id).and(CHARACTERS.ACTOR_IDENTITY_STATUS.eq("OWNER_RESOLVED")))
            .fetchOne(this::toEntity);
    if (character == null) {
      return Optional.empty();
    }
    character.setInventoryEntries(
        new LinkedHashSet<>(
            dsl.select(
                    INVENTORY.CHARACTER_ID,
                    INVENTORY.ITEM_ID,
                    INVENTORY.QUANTITY,
                    ITEMS.ID,
                    ITEMS.TENANT_ID,
                    ITEMS.VERSION_ID,
                    ITEMS.NAME,
                    ITEMS.DESCRIPTION,
                    ITEMS.EQUIPMENT_SLOT,
                    ITEMS.EQUIPMENT_SLOT_GROUP_KEY,
                    ITEMS.IS_CONTAINER,
                    ITEMS.IS_STACKABLE,
                    ITEMS.STACK_COMPATIBILITY_MODE,
                    ITEMS.STACK_VARIANT_KEY,
                    ITEMS.EFFECT_PAYLOAD_JSON)
                .from(INVENTORY)
                .join(ITEMS)
                .on(INVENTORY.ITEM_ID.eq(ITEMS.ID))
                .where(INVENTORY.CHARACTER_ID.eq(id))
                .orderBy(INVENTORY.ITEM_ID.asc())
                .fetch(record -> toInventoryEntry(record, character))));
    return Optional.of(character);
  }

  public Optional<Character> findById(Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(CHARACTERS)
            .where(CHARACTERS.ID.eq(id).and(CHARACTERS.ACTOR_IDENTITY_STATUS.eq("OWNER_RESOLVED")))
            .fetchOne(this::toEntity));
  }

  public Optional<Character> findByIdAndTenantId(Long id, Long tenantId) {
    return Optional.ofNullable(
        dsl.selectFrom(CHARACTERS)
            .where(
                CHARACTERS
                    .ID
                    .eq(id)
                    .and(CHARACTERS.TENANT_ID.eq(tenantId))
                    .and(CHARACTERS.ACTOR_IDENTITY_STATUS.eq("OWNER_RESOLVED")))
            .fetchOne(this::toEntity));
  }

  public Optional<Character> findByIdAndTenantIdAndPlayableStateKey(
      Long id, Long tenantId, String playableStateKey) {
    return Optional.ofNullable(
        dsl.selectFrom(CHARACTERS)
            .where(
                CHARACTERS
                    .ID
                    .eq(id)
                    .and(CHARACTERS.TENANT_ID.eq(tenantId))
                    .and(CHARACTERS.PLAYABLE_STATE_KEY.eq(playableStateKey))
                    .and(CHARACTERS.ACTOR_IDENTITY_STATUS.eq("OWNER_RESOLVED")))
            .fetchOne(this::toEntity));
  }

  public Page<Character> findByTenantIdAndAccountIdAndPlayableStateKey(
      Long tenantId, Long accountId, String playableStateKey, Pageable pageable) {
    var condition =
        CHARACTERS
            .TENANT_ID
            .eq(tenantId)
            .and(CHARACTERS.ACCOUNT_ID.eq(accountId))
            .and(CHARACTERS.PLAYABLE_STATE_KEY.eq(playableStateKey))
            .and(CHARACTERS.ACTOR_IDENTITY_STATUS.eq("OWNER_RESOLVED"));
    long total = dsl.fetchCount(CHARACTERS, condition);
    var content =
        dsl.selectFrom(CHARACTERS)
            .where(condition)
            .orderBy(CHARACTERS.ID.asc())
            .limit(limitOrDefault(pageable, Integer.MAX_VALUE))
            .offset(offsetOrZero(pageable))
            .fetch(this::toEntity);
    return pageable == null || pageable.isUnpaged()
        ? new PageImpl<>(content)
        : new PageImpl<>(content, pageable, total);
  }

  public Optional<Character> findByTenantIdAndPlayableStateKeyAndNameIgnoreCase(
      Long tenantId, String playableStateKey, String name) {
    return Optional.ofNullable(
        dsl.selectFrom(CHARACTERS)
            .where(
                CHARACTERS
                    .TENANT_ID
                    .eq(tenantId)
                    .and(CHARACTERS.PLAYABLE_STATE_KEY.eq(playableStateKey))
                    .and(DSL.lower(CHARACTERS.NAME).eq(name.toLowerCase()))
                    .and(CHARACTERS.ACTOR_IDENTITY_STATUS.eq("OWNER_RESOLVED")))
            .limit(1)
            .fetchOne(this::toEntity));
  }

  public long countByTenantId(Long tenantId) {
    return dsl.fetchCount(CHARACTERS, CHARACTERS.TENANT_ID.eq(tenantId));
  }

  public long countQuarantinedByTenantId(Long tenantId) {
    return dsl.fetchCount(
        CHARACTERS,
        CHARACTERS.TENANT_ID.eq(tenantId).and(CHARACTERS.ACTOR_IDENTITY_STATUS.eq("QUARANTINED")));
  }

  /**
   * Atomically enrolls the owner-proven namespace, records one replay guard, and allocates the
   * Entity-owned actor/account association after the protected assignment service authorizes the
   * exact run-owned action and owner evidence. The committed actor is non-admitting; this
   * repository operation has no direct RPC adapter and grants no PLAY authority.
   */
  @Transactional
  public PreseededActorAssignmentResult assignPreseededActor(
      PreseededActorAssignmentRequest request,
      PreseededActorAssignmentOwnerEvidence evidence,
      RuntimeAccountIdentityEvidence accountIdentityEvidence,
      String intentDigest) {
    if (request == null
        || evidence == null
        || evidence.eligibility() != PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE
        || evidence.entryPolicy()
            != PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY
        || evidence.accountPurpose()
            != PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING
        || evidence.accountCurrentness()
            != PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION
        || accountIdentityEvidence == null
        || !request.assignmentUuid().equals(evidence.assignmentUuid())
        || !request.canonicalAccountUuid().equals(evidence.canonicalAccountUuid())
        || !request.expectedTarget().exactlyMatches(evidence)
        || !evidence.assignmentUuid().equals(accountIdentityEvidence.requestId())
        || !evidence.canonicalAccountUuid().equals(accountIdentityEvidence.canonicalAccountId())
        || intentDigest == null
        || !intentDigest.matches("[0-9a-f]{64}")
        || !request.mutationIntentDigest().equals(intentDigest)) {
      throw new IllegalArgumentException("Exact eligible pre-seeded assignment proof is required");
    }

    var insertedGuard =
        insertAssignmentGuard(request, evidence, accountIdentityEvidence, intentDigest);
    if (insertedGuard == null) {
      return replayOrConflict(request, evidence, intentDigest);
    }

    enrollNamespace(evidence);
    long entityTenantRowId = requireEntityTenantRow(evidence.canonicalTenantUuid());
    if (hasOwnerResolvedAccountNamespace(evidence)) {
      completeWithoutActor(evidence.assignmentUuid(), Outcome.IDEMPOTENCY_CONFLICT);
      return new PreseededActorAssignmentResult(
          evidence.assignmentUuid(), Outcome.IDEMPOTENCY_CONFLICT, null, intentDigest);
    }
    UUID characterUuid = UUID.randomUUID();

    if (!insertOwnerResolvedCharacter(
        request,
        evidence,
        accountIdentityEvidence.sourceAccountRowId(),
        entityTenantRowId,
        characterUuid)) {
      // The partial unique index is the final guard if another writer does not use this row lock.
      // Persist the deterministic loser result after confirming the account/namespace slot won.
      if (!hasOwnerResolvedAccountNamespace(evidence)) {
        throw new IllegalStateException("PRESEEDED_ASSIGNMENT_ACTOR_INSERT_FAILED");
      }
      completeWithoutActor(evidence.assignmentUuid(), Outcome.IDEMPOTENCY_CONFLICT);
      return new PreseededActorAssignmentResult(
          evidence.assignmentUuid(), Outcome.IDEMPOTENCY_CONFLICT, null, intentDigest);
    }
    completeWithActor(evidence.assignmentUuid(), characterUuid);
    return new PreseededActorAssignmentResult(
        evidence.assignmentUuid(), Outcome.ASSIGNED, characterUuid, intentDigest);
  }

  private Record insertAssignmentGuard(
      PreseededActorAssignmentRequest request,
      PreseededActorAssignmentOwnerEvidence evidence,
      RuntimeAccountIdentityEvidence accountIdentityEvidence,
      String intentDigest) {
    return dsl.insertInto(ASSIGNMENT_OPERATIONS)
        .set(ASSIGNMENT_UUID, evidence.assignmentUuid())
        .set(INTENT_DIGEST, intentDigest)
        .set(ACCOUNT_UUID, evidence.canonicalAccountUuid())
        .set(ACCOUNT_UUID_PROVENANCE, accountIdentityEvidence.accountUuidProvenance())
        .set(ACCOUNT_IDENTITY_SCHEMA_VERSION, (short) accountIdentityEvidence.schemaVersion())
        .set(ACCOUNT_IDENTITY_TARGET_NAMESPACE, accountIdentityEvidence.targetNamespace())
        .set(SOURCE_ACCOUNT_ROW_ID, accountIdentityEvidence.sourceAccountRowId())
        .set(ELIGIBILITY, evidence.eligibility().name())
        .set(
            ELIGIBILITY_EVALUATED_AT,
            OffsetDateTime.ofInstant(evidence.eligibilityEvaluatedAt(), ZoneOffset.UTC))
        .set(MEMBERSHIP_AUTHORITY_GENERATION, evidence.membershipAuthorityGeneration())
        .set(ELIGIBILITY_EVIDENCE_DIGEST, evidence.eligibilityEvidenceDigest())
        .set(ACCOUNT_AUTHORITY_SNAPSHOT_DIGEST, evidence.accountAuthoritySnapshotDigest())
        .set(ACCOUNT_PURPOSE, evidence.accountPurpose().name())
        .set(ACCOUNT_CURRENTNESS, evidence.accountCurrentness().name())
        .set(PUBLISHED_OWNER_PROOF_DIGEST, evidence.publishedOwnerProofDigest())
        .set(PUBLISHED_RELEASE_BUNDLE_REF, evidence.publishedReleaseBundleRef())
        .set(TENANT_UUID, evidence.canonicalTenantUuid())
        .set(REALM_UUID, evidence.realmUuid())
        .set(WORLD_SLUG, evidence.worldSlug())
        .set(REALM_SLUG, evidence.realmSlug())
        .set(GAME_INSTANCE_ID, evidence.gameInstanceId())
        .set(CATALOG_REVISION, evidence.catalogRevision())
        .set(CANONICAL_VERSION_UUID, evidence.canonicalVersionUuid())
        .set(PUBLICATION_EVIDENCE_FORMAT, CURRENT_PUBLICATION_EVIDENCE_FORMAT)
        .set(FROZEN_POLICY_DIGEST, evidence.frozenPolicyDigest())
        .set(NAMESPACE_UUID, evidence.playableStateNamespaceId())
        .set(PLAYABLE_STATE_SCOPE, evidence.playableStateScope().name())
        .set(ENTRY_POLICY, evidence.entryPolicy().name())
        .set(ACTOR_KIND, request.corePayload().actorKind().name())
        .set(DISPLAY_NAME, request.corePayload().displayName())
        .set(ASSIGNMENT_STATUS, "PENDING")
        .onConflict(ASSIGNMENT_UUID)
        .doNothing()
        .returning(ASSIGNMENT_UUID)
        .fetchOne();
  }

  private PreseededActorAssignmentResult replayOrConflict(
      PreseededActorAssignmentRequest request,
      PreseededActorAssignmentOwnerEvidence evidence,
      String requestedDigest) {
    Record stored =
        dsl.select(
                INTENT_DIGEST,
                ACCOUNT_UUID,
                ASSIGNMENT_COMPLETED_AT,
                TENANT_UUID,
                REALM_UUID,
                WORLD_SLUG,
                REALM_SLUG,
                GAME_INSTANCE_ID,
                CATALOG_REVISION,
                CANONICAL_VERSION_UUID,
                PUBLICATION_EVIDENCE_FORMAT,
                FROZEN_POLICY_DIGEST,
                NAMESPACE_UUID,
                PLAYABLE_STATE_SCOPE,
                ENTRY_POLICY,
                ACTOR_KIND,
                DISPLAY_NAME,
                ASSIGNMENT_STATUS,
                ASSIGNED_CHARACTER_UUID,
                PUBLISHED_RELEASE_BUNDLE_REF)
            .from(ASSIGNMENT_OPERATIONS)
            .where(ASSIGNMENT_UUID.eq(evidence.assignmentUuid()))
            .forUpdate()
            .fetchOne();
    if (stored == null) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_REPLAY_GUARD_UNAVAILABLE");
    }
    if (!CURRENT_PUBLICATION_EVIDENCE_FORMAT.equals(stored.get(PUBLICATION_EVIDENCE_FORMAT))) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_LEGACY_PUBLICATION_EVIDENCE");
    }
    if (!requestedDigest.equals(stored.get(INTENT_DIGEST))) {
      return new PreseededActorAssignmentResult(
          evidence.assignmentUuid(), Outcome.IDEMPOTENCY_CONFLICT, null, requestedDigest);
    }
    requireSameStoredIntent(stored, request, evidence);

    String status = stored.get(ASSIGNMENT_STATUS);
    UUID characterUuid = stored.get(ASSIGNED_CHARACTER_UUID);
    OffsetDateTime completedAt = stored.get(ASSIGNMENT_COMPLETED_AT);
    if ("IDEMPOTENCY_CONFLICT".equals(status) && characterUuid == null && completedAt != null) {
      return new PreseededActorAssignmentResult(
          evidence.assignmentUuid(), Outcome.IDEMPOTENCY_CONFLICT, null, requestedDigest);
    }
    if ("ASSIGNED".equals(status) && characterUuid != null && completedAt != null) {
      requireStoredAssignedActor(evidence, characterUuid);
      return new PreseededActorAssignmentResult(
          evidence.assignmentUuid(), Outcome.ASSIGNED, characterUuid, requestedDigest);
    }
    throw new IllegalStateException("PRESEEDED_ASSIGNMENT_REPLAY_RESULT_INCOMPLETE");
  }

  private void requireSameStoredIntent(
      Record stored,
      PreseededActorAssignmentRequest request,
      PreseededActorAssignmentOwnerEvidence evidence) {
    boolean sameIntent =
        evidence.canonicalAccountUuid().equals(stored.get(ACCOUNT_UUID))
            && evidence.canonicalTenantUuid().equals(stored.get(TENANT_UUID))
            && evidence.realmUuid().equals(stored.get(REALM_UUID))
            && evidence.worldSlug().equals(stored.get(WORLD_SLUG))
            && evidence.realmSlug().equals(stored.get(REALM_SLUG))
            && evidence.gameInstanceId().equals(stored.get(GAME_INSTANCE_ID))
            && evidence.catalogRevision() == stored.get(CATALOG_REVISION)
            && evidence.canonicalVersionUuid().equals(stored.get(CANONICAL_VERSION_UUID))
            && evidence.frozenPolicyDigest().equals(stored.get(FROZEN_POLICY_DIGEST))
            && evidence.playableStateNamespaceId().equals(stored.get(NAMESPACE_UUID))
            && evidence.playableStateScope().name().equals(stored.get(PLAYABLE_STATE_SCOPE))
            && evidence.entryPolicy().name().equals(stored.get(ENTRY_POLICY))
            && evidence.publishedReleaseBundleRef().equals(stored.get(PUBLISHED_RELEASE_BUNDLE_REF))
            && request.corePayload().actorKind().name().equals(stored.get(ACTOR_KIND))
            && request.corePayload().displayName().equals(stored.get(DISPLAY_NAME));
    if (!sameIntent) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_STORED_INTENT_MISMATCH");
    }
  }

  /** Returns only an exact committed operation whose PLAYER row remains owner-resolved. */
  @Transactional(readOnly = true)
  public Optional<PreseededActorAssignmentReceipt> readPreseededActorAssignmentReceipt(
      PreseededActorAssignmentReceiptRequest request) {
    Record operation =
        dsl.select(
                ASSIGNMENT_UUID,
                INTENT_DIGEST,
                ACCOUNT_UUID,
                ACCOUNT_UUID_PROVENANCE,
                ACCOUNT_IDENTITY_SCHEMA_VERSION,
                ACCOUNT_IDENTITY_TARGET_NAMESPACE,
                SOURCE_ACCOUNT_ROW_ID,
                ELIGIBILITY,
                ELIGIBILITY_EVALUATED_AT,
                MEMBERSHIP_AUTHORITY_GENERATION,
                ELIGIBILITY_EVIDENCE_DIGEST,
                ACCOUNT_AUTHORITY_SNAPSHOT_DIGEST,
                ACCOUNT_PURPOSE,
                ACCOUNT_CURRENTNESS,
                PUBLISHED_OWNER_PROOF_DIGEST,
                PUBLISHED_RELEASE_BUNDLE_REF,
                TENANT_UUID,
                REALM_UUID,
                WORLD_SLUG,
                REALM_SLUG,
                GAME_INSTANCE_ID,
                CATALOG_REVISION,
                CANONICAL_VERSION_UUID,
                PUBLICATION_EVIDENCE_FORMAT,
                FROZEN_POLICY_DIGEST,
                NAMESPACE_UUID,
                PLAYABLE_STATE_SCOPE,
                ENTRY_POLICY,
                ACTOR_KIND,
                ASSIGNMENT_STATUS,
                ASSIGNED_CHARACTER_UUID,
                DISPLAY_NAME,
                ASSIGNMENT_COMPLETED_AT)
            .from(ASSIGNMENT_OPERATIONS)
            .where(ASSIGNMENT_UUID.eq(request.assignmentUuid()))
            .fetchOne();
    if (operation == null
        || !request.canonicalAccountUuid().equals(operation.get(ACCOUNT_UUID))
        || operation.get(ACCOUNT_UUID_PROVENANCE) == null
        || operation.get(ACCOUNT_UUID_PROVENANCE).isBlank()
        || operation.get(ACCOUNT_IDENTITY_SCHEMA_VERSION) == null
        || operation.get(ACCOUNT_IDENTITY_TARGET_NAMESPACE) == null
        || operation.get(SOURCE_ACCOUNT_ROW_ID) == null
        || operation.get(TENANT_UUID) == null
        || operation.get(REALM_UUID) == null
        || operation.get(WORLD_SLUG) == null
        || operation.get(REALM_SLUG) == null
        || operation.get(GAME_INSTANCE_ID) == null
        || operation.get(CATALOG_REVISION) == null
        || !CURRENT_PUBLICATION_EVIDENCE_FORMAT.equals(operation.get(PUBLICATION_EVIDENCE_FORMAT))
        || operation.get(CANONICAL_VERSION_UUID) == null
        || operation.get(FROZEN_POLICY_DIGEST) == null
        || operation.get(NAMESPACE_UUID) == null
        || operation.get(PUBLISHED_RELEASE_BUNDLE_REF) == null
        || operation.get(PLAYABLE_STATE_SCOPE) == null
        || operation.get(DISPLAY_NAME) == null
        || !"ELIGIBLE".equals(operation.get(ELIGIBILITY))
        || !request.intentDigest().equals(operation.get(INTENT_DIGEST))
        || !"PLAYER".equals(operation.get(ACTOR_KIND))
        || !"ASSIGNED".equals(operation.get(ASSIGNMENT_STATUS))
        || operation.get(ASSIGNMENT_COMPLETED_AT) == null
        || !request.characterUuid().equals(operation.get(ASSIGNED_CHARACTER_UUID))) {
      return Optional.empty();
    }

    Record actor =
        dsl.select(
                CHARACTERS.CHARACTER_UUID,
                CHARACTERS.ACCOUNT_ID,
                CHARACTERS.ACCOUNT_UUID,
                CHARACTERS.TENANT_UUID,
                CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID,
                CHARACTERS.PLAYABLE_STATE_SCOPE,
                CHARACTERS.ACTOR_IDENTITY_STATUS,
                CHARACTERS.ACTOR_IDENTITY_QUARANTINE_REASON,
                CHARACTERS.NAME)
            .from(CHARACTERS)
            .where(CHARACTERS.CHARACTER_UUID.eq(request.characterUuid()))
            .fetchOne();
    if (actor == null
        || !request.canonicalAccountUuid().equals(actor.get(CHARACTERS.ACCOUNT_UUID))
        || !Objects.equals(operation.get(SOURCE_ACCOUNT_ROW_ID), actor.get(CHARACTERS.ACCOUNT_ID))
        || !Objects.equals(operation.get(TENANT_UUID), actor.get(CHARACTERS.TENANT_UUID))
        || !Objects.equals(
            operation.get(NAMESPACE_UUID), actor.get(CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID))
        || !Objects.equals(
            operation.get(PLAYABLE_STATE_SCOPE), actor.get(CHARACTERS.PLAYABLE_STATE_SCOPE))
        || !"OWNER_RESOLVED".equals(actor.get(CHARACTERS.ACTOR_IDENTITY_STATUS))
        || actor.get(CHARACTERS.ACTOR_IDENTITY_QUARANTINE_REASON) != null
        || !Objects.equals(operation.get(DISPLAY_NAME), actor.get(CHARACTERS.NAME))) {
      return Optional.empty();
    }

    try {
      var target =
          new PreseededActorAssignmentExpectedTarget(
              operation.get(TENANT_UUID),
              operation.get(REALM_UUID),
              operation.get(WORLD_SLUG),
              operation.get(REALM_SLUG),
              operation.get(GAME_INSTANCE_ID),
              operation.get(CATALOG_REVISION),
              operation.get(CANONICAL_VERSION_UUID),
              operation.get(FROZEN_POLICY_DIGEST),
              operation.get(NAMESPACE_UUID),
              operation.get(PUBLISHED_RELEASE_BUNDLE_REF),
              net.firedevops.firemud.entitymanagement.v1.PlayableStateScope.valueOf(
                  operation.get(PLAYABLE_STATE_SCOPE)));
      if (!request.expectedTarget().equals(target)) {
        return Optional.empty();
      }
      OffsetDateTime evaluatedAt = operation.get(ELIGIBILITY_EVALUATED_AT);
      String evidenceDigest = operation.get(ELIGIBILITY_EVIDENCE_DIGEST);
      String authorityDigest = operation.get(ACCOUNT_AUTHORITY_SNAPSHOT_DIGEST);
      String purpose = operation.get(ACCOUNT_PURPOSE);
      String currentness = operation.get(ACCOUNT_CURRENTNESS);
      String ownerProofDigest = operation.get(PUBLISHED_OWNER_PROOF_DIGEST);
      String releaseRef = operation.get(PUBLISHED_RELEASE_BUNDLE_REF);
      String entryPolicy = operation.get(ENTRY_POLICY);
      Long generation = operation.get(MEMBERSHIP_AUTHORITY_GENERATION);
      if (evaluatedAt == null
          || generation == null
          || generation <= 0
          || evidenceDigest == null
          || authorityDigest == null
          || ownerProofDigest == null
          || releaseRef == null
          || purpose == null
          || currentness == null
          || !"PRESEEDED_ACTOR_STAGING".equals(purpose)
          || !"CURRENT_AT_REVALIDATION".equals(currentness)
          || !"PRESEEDED_ONLY".equals(entryPolicy)) {
        return Optional.empty();
      }
      return Optional.of(
          new PreseededActorAssignmentReceipt(
              request.assignmentUuid(),
              request.canonicalAccountUuid(),
              request.characterUuid(),
              request.intentDigest(),
              target,
              new net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload(
                  net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload
                      .ActorKind.valueOf(operation.get(ACTOR_KIND)),
                  operation.get(DISPLAY_NAME)),
              operation.get(ACCOUNT_IDENTITY_SCHEMA_VERSION).intValue(),
              operation.get(ACCOUNT_IDENTITY_TARGET_NAMESPACE),
              operation.get(SOURCE_ACCOUNT_ROW_ID),
              operation.get(ACCOUNT_UUID_PROVENANCE),
              PreseededActorAssignmentOwnerEvidence.Eligibility.valueOf(operation.get(ELIGIBILITY)),
              evaluatedAt.toInstant(),
              generation,
              evidenceDigest,
              authorityDigest,
              PreseededActorAssignmentOwnerEvidence.AccountPurpose.valueOf(purpose),
              PreseededActorAssignmentOwnerEvidence.AccountCurrentness.valueOf(currentness),
              ownerProofDigest,
              releaseRef,
              entryPolicy,
              operation.get(ASSIGNMENT_STATUS)));
    } catch (IllegalArgumentException invalidStoredReceipt) {
      return Optional.empty();
    }
  }

  /**
   * Resolves one historical assignment for an exact selected actor and immutable target. Current
   * owner counters are independently checked before this method is called; the persisted owner
   * proof digest must still match that owner-resolved target. Zero or multiple matches disclose no
   * assignment reference.
   */
  @Transactional(readOnly = true)
  public Optional<PreseededActorAssignmentReceipt> readSelectedPreseededActorAssignmentReceipt(
      UUID canonicalAccountUuid,
      UUID selectedCharacterUuid,
      PreseededActorAssignmentExpectedTarget expectedTarget,
      String publishedOwnerProofDigest) {
    Objects.requireNonNull(canonicalAccountUuid, "canonicalAccountUuid");
    Objects.requireNonNull(selectedCharacterUuid, "selectedCharacterUuid");
    Objects.requireNonNull(expectedTarget, "expectedTarget");
    if (publishedOwnerProofDigest == null || !publishedOwnerProofDigest.matches("[0-9a-f]{64}")) {
      return Optional.empty();
    }

    List<? extends Record> matchingOperations =
        dsl.select(ASSIGNMENT_UUID, INTENT_DIGEST)
            .from(ASSIGNMENT_OPERATIONS)
            .where(
                ACCOUNT_UUID
                    .eq(canonicalAccountUuid)
                    .and(ASSIGNED_CHARACTER_UUID.eq(selectedCharacterUuid))
                    .and(TENANT_UUID.eq(expectedTarget.canonicalTenantUuid()))
                    .and(REALM_UUID.eq(expectedTarget.realmUuid()))
                    .and(WORLD_SLUG.eq(expectedTarget.worldSlug()))
                    .and(REALM_SLUG.eq(expectedTarget.realmSlug()))
                    .and(GAME_INSTANCE_ID.eq(expectedTarget.gameInstanceId()))
                    .and(CATALOG_REVISION.eq(expectedTarget.catalogRevision()))
                    .and(CANONICAL_VERSION_UUID.eq(expectedTarget.canonicalVersionUuid()))
                    .and(FROZEN_POLICY_DIGEST.eq(expectedTarget.frozenPolicyDigest()))
                    .and(NAMESPACE_UUID.eq(expectedTarget.playableStateNamespaceId()))
                    .and(PLAYABLE_STATE_SCOPE.eq(expectedTarget.playableStateScope().name()))
                    .and(
                        PUBLISHED_RELEASE_BUNDLE_REF.eq(expectedTarget.publishedReleaseBundleRef()))
                    .and(PUBLISHED_OWNER_PROOF_DIGEST.eq(publishedOwnerProofDigest))
                    .and(ENTRY_POLICY.eq("PRESEEDED_ONLY"))
                    .and(ACTOR_KIND.eq("PLAYER"))
                    .and(ASSIGNMENT_STATUS.eq("ASSIGNED"))
                    .and(ASSIGNMENT_COMPLETED_AT.isNotNull()))
            .limit(2)
            .fetch();
    Optional<Record> uniqueOperation = onlyOneAssignmentMatch(matchingOperations);
    if (uniqueOperation.isEmpty()) {
      return Optional.empty();
    }

    Record operation = uniqueOperation.orElseThrow();
    UUID assignmentUuid = operation.get(ASSIGNMENT_UUID);
    String intentDigest = operation.get(INTENT_DIGEST);
    if (assignmentUuid == null || intentDigest == null || !intentDigest.matches("[0-9a-f]{64}")) {
      return Optional.empty();
    }
    Optional<PreseededActorAssignmentReceipt> receipt =
        readPreseededActorAssignmentReceipt(
            new PreseededActorAssignmentReceiptRequest(
                assignmentUuid,
                canonicalAccountUuid,
                selectedCharacterUuid,
                intentDigest,
                expectedTarget));
    if (receipt.isEmpty()
        || !publishedOwnerProofDigest.equals(receipt.orElseThrow().publishedOwnerProofDigest())) {
      return Optional.empty();
    }
    return receipt;
  }

  static <T> Optional<T> onlyOneAssignmentMatch(List<? extends T> matchingOperations) {
    if (matchingOperations == null || matchingOperations.size() != 1) {
      return Optional.empty();
    }
    return Optional.ofNullable(matchingOperations.get(0));
  }

  /**
   * Locks the immutable assignment and its current actor row before returning the exact receipt.
   * Callers use this only inside the same writable transaction that records an applicability hold,
   * so actor changes either win first and invalidate the receipt or observe the durable hold.
   */
  @Transactional
  public Optional<PreseededActorAssignmentReceipt> lockCurrentPreseededActorAssignmentReceipt(
      UUID assignmentUuid, UUID canonicalAccountUuid, UUID characterUuid, String intentDigest) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()
        || org.springframework.transaction.support.TransactionSynchronizationManager
            .isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Pre-seeded placement receipt locking requires the writable hold transaction");
    }
    Record operation =
        dsl.select(
                INTENT_DIGEST,
                ACCOUNT_UUID,
                TENANT_UUID,
                REALM_UUID,
                WORLD_SLUG,
                REALM_SLUG,
                GAME_INSTANCE_ID,
                CATALOG_REVISION,
                CANONICAL_VERSION_UUID,
                FROZEN_POLICY_DIGEST,
                NAMESPACE_UUID,
                PLAYABLE_STATE_SCOPE,
                PUBLISHED_RELEASE_BUNDLE_REF,
                ASSIGNED_CHARACTER_UUID)
            .from(ASSIGNMENT_OPERATIONS)
            .where(ASSIGNMENT_UUID.eq(assignmentUuid))
            .forUpdate()
            .fetchOne();
    if (operation == null
        || !canonicalAccountUuid.equals(operation.get(ACCOUNT_UUID))
        || !characterUuid.equals(operation.get(ASSIGNED_CHARACTER_UUID))
        || !intentDigest.equals(operation.get(INTENT_DIGEST))) {
      return Optional.empty();
    }
    Record actor =
        dsl.select(CHARACTERS.CHARACTER_UUID)
            .from(CHARACTERS)
            .where(CHARACTERS.CHARACTER_UUID.eq(characterUuid))
            .forUpdate()
            .fetchOne();
    if (actor == null) {
      return Optional.empty();
    }
    try {
      var target =
          new PreseededActorAssignmentExpectedTarget(
              operation.get(TENANT_UUID),
              operation.get(REALM_UUID),
              operation.get(WORLD_SLUG),
              operation.get(REALM_SLUG),
              operation.get(GAME_INSTANCE_ID),
              operation.get(CATALOG_REVISION),
              operation.get(CANONICAL_VERSION_UUID),
              operation.get(FROZEN_POLICY_DIGEST),
              operation.get(NAMESPACE_UUID),
              operation.get(PUBLISHED_RELEASE_BUNDLE_REF),
              net.firedevops.firemud.entitymanagement.v1.PlayableStateScope.valueOf(
                  operation.get(PLAYABLE_STATE_SCOPE)));
      return readPreseededActorAssignmentReceipt(
          new PreseededActorAssignmentReceiptRequest(
              assignmentUuid, canonicalAccountUuid, characterUuid, intentDigest, target));
    } catch (IllegalArgumentException invalidStoredTarget) {
      return Optional.empty();
    }
  }

  private void enrollNamespace(PreseededActorAssignmentOwnerEvidence evidence) {
    dsl.insertInto(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES)
        .set(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.TENANT_UUID, evidence.canonicalTenantUuid())
        .set(
            ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_NAMESPACE_ID,
            evidence.playableStateNamespaceId())
        .set(
            ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_SCOPE,
            evidence.playableStateScope().name())
        .onConflict(
            ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.TENANT_UUID,
            ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_NAMESPACE_ID)
        .doNothing()
        .execute();

    String enrolledScope =
        dsl.select(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_SCOPE)
            .from(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES)
            .where(
                ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES
                    .TENANT_UUID
                    .eq(evidence.canonicalTenantUuid())
                    .and(
                        ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_NAMESPACE_ID.eq(
                            evidence.playableStateNamespaceId())))
            .forUpdate()
            .fetchOne(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_SCOPE);
    if (!evidence.playableStateScope().name().equals(enrolledScope)) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_NAMESPACE_SCOPE_MISMATCH");
    }
  }

  private long requireEntityTenantRow(UUID tenantUuid) {
    dsl.insertInto(TENANT_IDENTITIES)
        .set(CANONICAL_TENANT_UUID, tenantUuid)
        .onConflict(CANONICAL_TENANT_UUID)
        .doNothing()
        .execute();
    Long rowId =
        dsl.select(ENTITY_TENANT_ROW_ID)
            .from(TENANT_IDENTITIES)
            .where(CANONICAL_TENANT_UUID.eq(tenantUuid))
            .forUpdate()
            .fetchOne(ENTITY_TENANT_ROW_ID);
    if (rowId == null || rowId <= 0L) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_ENTITY_TENANT_MAPPING_UNAVAILABLE");
    }
    return rowId;
  }

  private boolean hasOwnerResolvedAccountNamespace(PreseededActorAssignmentOwnerEvidence evidence) {
    UUID existingActor =
        dsl.select(CHARACTERS.CHARACTER_UUID)
            .from(CHARACTERS)
            .where(
                CHARACTERS
                    .TENANT_UUID
                    .eq(evidence.canonicalTenantUuid())
                    .and(CHARACTERS.ACCOUNT_UUID.eq(evidence.canonicalAccountUuid()))
                    .and(
                        CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID.eq(
                            evidence.playableStateNamespaceId()))
                    .and(
                        CHARACTERS.ACTOR_IDENTITY_STATUS.eq(
                            ActorIdentityStatus.OWNER_RESOLVED.name())))
            .limit(1)
            .forUpdate()
            .fetchOne(CHARACTERS.CHARACTER_UUID);
    return existingActor != null;
  }

  private boolean insertOwnerResolvedCharacter(
      PreseededActorAssignmentRequest request,
      PreseededActorAssignmentOwnerEvidence evidence,
      long sourceAccountRowId,
      long entityTenantRowId,
      UUID characterUuid) {
    Long characterRowId =
        dsl.insertInto(CHARACTERS)
            .set(CHARACTERS.CHARACTER_UUID, characterUuid)
            .set(CHARACTERS.ACCOUNT_UUID, evidence.canonicalAccountUuid())
            .set(CHARACTERS.TENANT_UUID, evidence.canonicalTenantUuid())
            .set(CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID, evidence.playableStateNamespaceId())
            .set(CHARACTERS.PLAYABLE_STATE_SCOPE, evidence.playableStateScope().name())
            .set(CHARACTERS.ACTOR_IDENTITY_STATUS, ActorIdentityStatus.OWNER_RESOLVED.name())
            .set(CHARACTERS.ACTOR_IDENTITY_QUARANTINE_REASON, (String) null)
            .set(CHARACTERS.ACCOUNT_ID, sourceAccountRowId)
            .set(CHARACTERS.TENANT_ID, entityTenantRowId)
            .set(CHARACTERS.PLAYABLE_STATE_KEY, evidence.playableStateNamespaceId().toString())
            .set(CHARACTERS.NAME, request.corePayload().displayName())
            .onConflictDoNothing()
            .returningResult(CHARACTERS.ID)
            .fetchOne(CHARACTERS.ID);
    return characterRowId != null;
  }

  private void completeWithActor(UUID assignmentUuid, UUID characterUuid) {
    int updated =
        dsl.update(ASSIGNMENT_OPERATIONS)
            .set(ASSIGNMENT_STATUS, "ASSIGNED")
            .set(ASSIGNED_CHARACTER_UUID, characterUuid)
            .set(ASSIGNMENT_COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(ASSIGNMENT_UUID.eq(assignmentUuid).and(ASSIGNMENT_STATUS.eq("PENDING")))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_OPERATION_COMPLETION_FAILED");
    }
  }

  private void completeWithoutActor(UUID assignmentUuid, Outcome outcome) {
    if (outcome != Outcome.IDEMPOTENCY_CONFLICT) {
      throw new IllegalArgumentException("Only terminal no-actor outcomes can omit a character");
    }
    int updated =
        dsl.update(ASSIGNMENT_OPERATIONS)
            .set(ASSIGNMENT_STATUS, outcome.name())
            .set(ASSIGNMENT_COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(ASSIGNMENT_UUID.eq(assignmentUuid).and(ASSIGNMENT_STATUS.eq("PENDING")))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_OPERATION_COMPLETION_FAILED");
    }
  }

  private void requireStoredAssignedActor(
      PreseededActorAssignmentOwnerEvidence evidence, UUID characterUuid) {
    Record actor =
        dsl.select(
                CHARACTERS.CHARACTER_UUID,
                CHARACTERS.ACCOUNT_UUID,
                CHARACTERS.TENANT_UUID,
                CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID,
                CHARACTERS.PLAYABLE_STATE_SCOPE,
                CHARACTERS.ACTOR_IDENTITY_STATUS)
            .from(CHARACTERS)
            .where(CHARACTERS.CHARACTER_UUID.eq(characterUuid))
            .fetchOne();
    if (actor == null
        || !evidence.canonicalAccountUuid().equals(actor.get(CHARACTERS.ACCOUNT_UUID))
        || !evidence.canonicalTenantUuid().equals(actor.get(CHARACTERS.TENANT_UUID))
        || !evidence
            .playableStateNamespaceId()
            .equals(actor.get(CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID))
        || !evidence.playableStateScope().name().equals(actor.get(CHARACTERS.PLAYABLE_STATE_SCOPE))
        || !ActorIdentityStatus.OWNER_RESOLVED
            .name()
            .equals(actor.get(CHARACTERS.ACTOR_IDENTITY_STATUS))) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_ACTOR_PROVENANCE_MISMATCH");
    }
  }

  /**
   * Captures an ordered roster only from fresh owner-resolved actor rows, then persists and reads
   * back its immutable target-bound snapshot. This lookup never uses legacy numeric actor fields.
   */
  @Transactional
  public CanonicalGameplayRosterSnapshot captureCanonicalGameplayRosterSnapshot(
      CanonicalGameplayRosterReadRequest request,
      CanonicalGameplayRosterOwnerEvidence ownerEvidence) {
    if (request == null
        || ownerEvidence == null
        || !request.requestUuid().equals(ownerEvidence.requestUuid())
        || !request.canonicalAccountUuid().equals(ownerEvidence.canonicalAccountUuid())
        || !request.expectedTarget().equals(ownerEvidence.target())
        || ownerEvidence.target().entryPolicy()
            != net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy
                .PRESEEDED_ONLY) {
      throw new IllegalArgumentException("Exact PRESEEDED_ONLY owner evidence is required");
    }

    CanonicalGameplayRosterTarget target = ownerEvidence.target();
    lockCanonicalRosterNamespace(target);
    requireEntityTenantRow(target.tenantUuid());

    List<CanonicalGameplayRosterActor> currentActors =
        readCurrentCanonicalRoster(request.canonicalAccountUuid(), target);
    String digest =
        CanonicalGameplayRosterSnapshotDigest.compute(
            request.canonicalAccountUuid(), target, currentActors);
    UUID proposedSnapshotUuid = UUID.randomUUID();
    UUID insertedSnapshotUuid =
        dsl.insertInto(ROSTER_SNAPSHOTS)
            .set(ROSTER_SNAPSHOT_UUID, proposedSnapshotUuid)
            .set(ROSTER_SNAPSHOT_DIGEST, digest)
            .set(ROSTER_ACCOUNT_UUID, request.canonicalAccountUuid())
            .set(ROSTER_TENANT_UUID, target.tenantUuid())
            .set(ROSTER_REALM_UUID, target.realmUuid())
            .set(ROSTER_WORLD_SLUG, target.worldSlug())
            .set(ROSTER_REALM_SLUG, target.realmSlug())
            .set(ROSTER_GAME_INSTANCE_UUID, target.gameInstanceUuid())
            .set(ROSTER_CATALOG_REVISION, target.catalogRevision())
            .set(ROSTER_CANONICAL_VERSION_UUID, target.canonicalVersionUuid())
            .set(ROSTER_POINTER_VERSION, target.pointerVersion())
            .set(ROSTER_ACTIVE_WORLD_EPOCH, target.activeWorldEpoch())
            .set(ROSTER_PUBLICATION_EVIDENCE_FORMAT, CURRENT_ROSTER_EVIDENCE_FORMAT)
            .set(ROSTER_PUBLISHED_POLICY_DIGEST, target.publishedPolicyDigest())
            .set(ROSTER_PUBLISHED_RELEASE_REF, target.publishedReleaseBundleRef())
            .set(ROSTER_POINTER_SNAPSHOT_DIGEST, target.admissionPointerSnapshotDigest())
            .set(ROSTER_OWNER_PROOF_DIGEST, target.publishedOwnerProofDigest())
            .set(ROSTER_NAMESPACE_UUID, target.playableStateNamespaceId())
            .set(ROSTER_PLAYABLE_SCOPE, target.playableStateScope().name())
            .set(ROSTER_ENTRY_POLICY, target.entryPolicy().name())
            .set(ROSTER_COUNT, (short) currentActors.size())
            .set(ROSTER_CONSTRUCTION_STATE, "BUILDING")
            .onConflict(ROSTER_SNAPSHOT_DIGEST)
            .doNothing()
            .returningResult(ROSTER_SNAPSHOT_UUID)
            .fetchOne(ROSTER_SNAPSHOT_UUID);

    if (insertedSnapshotUuid != null) {
      for (int index = 0; index < currentActors.size(); index++) {
        CanonicalGameplayRosterActor actor = currentActors.get(index);
        dsl.insertInto(ROSTER_SNAPSHOT_ACTORS)
            .set(ROSTER_SNAPSHOT_UUID, insertedSnapshotUuid)
            .set(ROSTER_ORDINAL, (short) index)
            .set(ROSTER_CHARACTER_UUID, actor.characterUuid())
            .set(ROSTER_DISPLAY_NAME, actor.displayName())
            .set(ROSTER_ACTOR_KIND, "PLAYER")
            .execute();
      }
      int sealed =
          dsl.update(ROSTER_SNAPSHOTS)
              .set(ROSTER_CONSTRUCTION_STATE, "SEALED")
              .where(
                  ROSTER_SNAPSHOT_UUID
                      .eq(insertedSnapshotUuid)
                      .and(ROSTER_CONSTRUCTION_STATE.eq("BUILDING")))
              .execute();
      if (sealed != 1) {
        throw new IllegalStateException("CANONICAL_GAMEPLAY_ROSTER_SNAPSHOT_SEAL_FAILED");
      }
    }

    return readBackCanonicalGameplayRosterSnapshot(
        request.canonicalAccountUuid(), target, currentActors, digest, null);
  }

  /**
   * Reads back a previously sealed snapshot only when it still exactly describes the current
   * owner-resolved roster. This never captures or creates a snapshot.
   */
  @Transactional
  public CanonicalGameplayRosterSnapshot readCurrentCanonicalGameplayRosterSnapshot(
      UUID canonicalAccountUuid,
      CanonicalGameplayRosterTarget expectedTarget,
      CanonicalGameplayRosterSnapshotReference expectedSnapshot) {
    Objects.requireNonNull(canonicalAccountUuid, "canonicalAccountUuid");
    Objects.requireNonNull(expectedTarget, "expectedTarget");
    Objects.requireNonNull(expectedSnapshot, "expectedSnapshot");
    if (expectedTarget.entryPolicy()
        != net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy
            .PRESEEDED_ONLY) {
      throw new IllegalArgumentException("PRESEEDED_ONLY target is required");
    }

    String enrolledScope =
        dsl.select(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_SCOPE)
            .from(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES)
            .where(
                ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES
                    .TENANT_UUID
                    .eq(expectedTarget.tenantUuid())
                    .and(
                        ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_NAMESPACE_ID.eq(
                            expectedTarget.playableStateNamespaceId())))
            .forShare()
            .fetchOne(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_SCOPE);
    if (!expectedTarget.playableStateScope().name().equals(enrolledScope)) {
      throw new IllegalStateException("CANONICAL_GAMEPLAY_ROSTER_NAMESPACE_SCOPE_MISMATCH");
    }

    List<CanonicalGameplayRosterActor> currentActors =
        readCurrentCanonicalRoster(canonicalAccountUuid, expectedTarget);
    String currentDigest =
        CanonicalGameplayRosterSnapshotDigest.compute(
            canonicalAccountUuid, expectedTarget, currentActors);
    if (!expectedSnapshot.snapshotDigest().equals(currentDigest)) {
      throw new IllegalStateException("CANONICAL_GAMEPLAY_ROSTER_SNAPSHOT_STALE");
    }
    return readBackCanonicalGameplayRosterSnapshot(
        canonicalAccountUuid,
        expectedTarget,
        currentActors,
        currentDigest,
        expectedSnapshot.snapshotUuid());
  }

  private void lockCanonicalRosterNamespace(CanonicalGameplayRosterTarget target) {
    dsl.insertInto(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES)
        .set(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.TENANT_UUID, target.tenantUuid())
        .set(
            ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_NAMESPACE_ID,
            target.playableStateNamespaceId())
        .set(
            ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_SCOPE,
            target.playableStateScope().name())
        .onConflict(
            ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.TENANT_UUID,
            ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_NAMESPACE_ID)
        .doNothing()
        .execute();

    String enrolledScope =
        dsl.select(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_SCOPE)
            .from(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES)
            .where(
                ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES
                    .TENANT_UUID
                    .eq(target.tenantUuid())
                    .and(
                        ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_NAMESPACE_ID.eq(
                            target.playableStateNamespaceId())))
            .forUpdate()
            .fetchOne(ENTITY_PLAYABLE_STATE_NAMESPACE_SCOPES.PLAYABLE_STATE_SCOPE);
    if (!target.playableStateScope().name().equals(enrolledScope)) {
      throw new IllegalStateException("CANONICAL_GAMEPLAY_ROSTER_NAMESPACE_SCOPE_MISMATCH");
    }
  }

  private List<CanonicalGameplayRosterActor> readCurrentCanonicalRoster(
      UUID canonicalAccountUuid, CanonicalGameplayRosterTarget target) {
    List<CanonicalGameplayRosterActor> actors =
        dsl.select(CHARACTERS.CHARACTER_UUID, CHARACTERS.NAME)
            .from(CHARACTERS)
            .where(
                CHARACTERS
                    .ACCOUNT_UUID
                    .eq(canonicalAccountUuid)
                    .and(CHARACTERS.TENANT_UUID.eq(target.tenantUuid()))
                    .and(
                        CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID.eq(
                            target.playableStateNamespaceId()))
                    .and(CHARACTERS.PLAYABLE_STATE_SCOPE.eq(target.playableStateScope().name()))
                    .and(CHARACTERS.ACTOR_IDENTITY_STATUS.eq("OWNER_RESOLVED"))
                    .and(CHARACTERS.ACTOR_IDENTITY_QUARANTINE_REASON.isNull()))
            .orderBy(CHARACTERS.CHARACTER_UUID.asc())
            .limit(CanonicalGameplayRosterSnapshotDigest.MAX_ROSTER_SIZE + 1)
            .forShare()
            .fetch(
                row ->
                    new CanonicalGameplayRosterActor(
                        row.get(CHARACTERS.CHARACTER_UUID), row.get(CHARACTERS.NAME)));
    if (actors.size() > CanonicalGameplayRosterSnapshotDigest.MAX_ROSTER_SIZE) {
      throw new IllegalStateException("CANONICAL_GAMEPLAY_ROSTER_LIMIT_EXCEEDED");
    }
    return actors;
  }

  private CanonicalGameplayRosterSnapshot readBackCanonicalGameplayRosterSnapshot(
      UUID canonicalAccountUuid,
      CanonicalGameplayRosterTarget expectedTarget,
      List<CanonicalGameplayRosterActor> expectedActors,
      String expectedDigest,
      UUID expectedSnapshotUuid) {
    Condition snapshotCondition = ROSTER_SNAPSHOT_DIGEST.eq(expectedDigest);
    if (expectedSnapshotUuid != null) {
      snapshotCondition = snapshotCondition.and(ROSTER_SNAPSHOT_UUID.eq(expectedSnapshotUuid));
    }
    Record stored =
        dsl.select(
                ROSTER_SNAPSHOT_UUID,
                ROSTER_SNAPSHOT_DIGEST,
                ROSTER_ACCOUNT_UUID,
                ROSTER_TENANT_UUID,
                ROSTER_REALM_UUID,
                ROSTER_WORLD_SLUG,
                ROSTER_REALM_SLUG,
                ROSTER_GAME_INSTANCE_UUID,
                ROSTER_CATALOG_REVISION,
                ROSTER_CANONICAL_VERSION_UUID,
                ROSTER_POINTER_VERSION,
                ROSTER_ACTIVE_WORLD_EPOCH,
                ROSTER_PUBLICATION_EVIDENCE_FORMAT,
                ROSTER_PUBLISHED_POLICY_DIGEST,
                ROSTER_PUBLISHED_RELEASE_REF,
                ROSTER_POINTER_SNAPSHOT_DIGEST,
                ROSTER_OWNER_PROOF_DIGEST,
                ROSTER_NAMESPACE_UUID,
                ROSTER_PLAYABLE_SCOPE,
                ROSTER_ENTRY_POLICY,
                ROSTER_COUNT,
                ROSTER_CONSTRUCTION_STATE)
            .from(ROSTER_SNAPSHOTS)
            .where(snapshotCondition)
            .forShare()
            .fetchOne();
    if (stored == null
        || !"SEALED".equals(stored.get(ROSTER_CONSTRUCTION_STATE))
        || !CURRENT_ROSTER_EVIDENCE_FORMAT.equals(stored.get(ROSTER_PUBLICATION_EVIDENCE_FORMAT))) {
      throw new IllegalStateException("CANONICAL_GAMEPLAY_ROSTER_SNAPSHOT_READBACK_UNAVAILABLE");
    }

    CanonicalGameplayRosterTarget storedTarget =
        new CanonicalGameplayRosterTarget(
            stored.get(ROSTER_TENANT_UUID),
            stored.get(ROSTER_REALM_UUID),
            stored.get(ROSTER_WORLD_SLUG),
            stored.get(ROSTER_REALM_SLUG),
            stored.get(ROSTER_GAME_INSTANCE_UUID),
            stored.get(ROSTER_CATALOG_REVISION),
            stored.get(ROSTER_POINTER_VERSION),
            stored.get(ROSTER_ACTIVE_WORLD_EPOCH),
            stored.get(ROSTER_CANONICAL_VERSION_UUID),
            stored.get(ROSTER_PUBLISHED_POLICY_DIGEST),
            stored.get(ROSTER_PUBLISHED_RELEASE_REF),
            stored.get(ROSTER_POINTER_SNAPSHOT_DIGEST),
            stored.get(ROSTER_OWNER_PROOF_DIGEST),
            stored.get(ROSTER_NAMESPACE_UUID),
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope.valueOf(
                stored.get(ROSTER_PLAYABLE_SCOPE)),
            net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy
                .valueOf(stored.get(ROSTER_ENTRY_POLICY)));

    List<? extends Record> storedActorRows =
        dsl.select(ROSTER_ORDINAL, ROSTER_CHARACTER_UUID, ROSTER_DISPLAY_NAME, ROSTER_ACTOR_KIND)
            .from(ROSTER_SNAPSHOT_ACTORS)
            .where(ROSTER_SNAPSHOT_UUID.eq(stored.get(ROSTER_SNAPSHOT_UUID)))
            .orderBy(ROSTER_ORDINAL.asc())
            .fetch();
    List<CanonicalGameplayRosterActor> storedActors = new ArrayList<>(storedActorRows.size());
    for (int index = 0; index < storedActorRows.size(); index++) {
      Record row = storedActorRows.get(index);
      Short ordinal = row.get(ROSTER_ORDINAL);
      if (ordinal == null || ordinal != index || !"PLAYER".equals(row.get(ROSTER_ACTOR_KIND))) {
        throw new IllegalStateException("CANONICAL_GAMEPLAY_ROSTER_SNAPSHOT_ORDER_INVALID");
      }
      storedActors.add(
          new CanonicalGameplayRosterActor(
              row.get(ROSTER_CHARACTER_UUID), row.get(ROSTER_DISPLAY_NAME)));
    }

    Short storedCount = stored.get(ROSTER_COUNT);
    UUID storedSnapshotUuid = stored.get(ROSTER_SNAPSHOT_UUID);
    if (storedSnapshotUuid == null
        || (expectedSnapshotUuid != null && !expectedSnapshotUuid.equals(storedSnapshotUuid))
        || !expectedDigest.equals(stored.get(ROSTER_SNAPSHOT_DIGEST))
        || !canonicalAccountUuid.equals(stored.get(ROSTER_ACCOUNT_UUID))
        || storedCount == null
        || storedCount != storedActors.size()
        || !expectedTarget.equals(storedTarget)
        || !expectedActors.equals(storedActors)
        || !expectedDigest.equals(
            CanonicalGameplayRosterSnapshotDigest.compute(
                canonicalAccountUuid, storedTarget, storedActors))) {
      throw new IllegalStateException("CANONICAL_GAMEPLAY_ROSTER_SNAPSHOT_READBACK_MISMATCH");
    }

    if (!expectedActors.equals(readCurrentCanonicalRoster(canonicalAccountUuid, expectedTarget))) {
      throw new IllegalStateException("CANONICAL_GAMEPLAY_ROSTER_CHANGED_DURING_READ");
    }
    return new CanonicalGameplayRosterSnapshot(
        canonicalAccountUuid, storedSnapshotUuid, expectedDigest, storedTarget, storedActors);
  }

  private static Field<UUID> uuidField(String name) {
    return DSL.field(DSL.name(name), UUID.class);
  }

  private static Field<String> stringField(String name) {
    return DSL.field(DSL.name(name), String.class);
  }

  private static Field<Long> longField(String name) {
    return DSL.field(DSL.name(name), Long.class);
  }

  public Character save(Character entity) {
    if (entity.getId() == null) {
      UUID characterUuid = UUID.randomUUID();
      Long id =
          dsl.insertInto(CHARACTERS)
              .set(CHARACTERS.CHARACTER_UUID, characterUuid)
              .set(CHARACTERS.ACTOR_IDENTITY_STATUS, "QUARANTINED")
              .set(CHARACTERS.ACTOR_IDENTITY_QUARANTINE_REASON, "OWNER_PROVENANCE_MISSING")
              .set(CHARACTERS.TENANT_ID, entity.getTenantId())
              .set(CHARACTERS.ACCOUNT_ID, entity.getAccountId())
              .set(CHARACTERS.PLAYABLE_STATE_KEY, entity.getPlayableStateKey())
              .set(CHARACTERS.NAME, entity.getName())
              .set(CHARACTERS.BODY_LAYOUT_KEY, entity.getBodyLayoutKey())
              .set(CHARACTERS.LEVEL, entity.getLevel())
              .set(CHARACTERS.EXPERIENCE, entity.getExperience())
              .set(CHARACTERS.STRENGTH, entity.getStrength())
              .set(CHARACTERS.AGILITY, entity.getAgility())
              .set(CHARACTERS.INTELLIGENCE, entity.getIntelligence())
              .set(CHARACTERS.STAMINA, entity.getStamina())
              .set(CHARACTERS.HEALTH, entity.getHealth())
              .set(CHARACTERS.MANA, entity.getMana())
              .set(CHARACTERS.LAST_LOGIN_AT, toLocalDateTime(entity.getLastLoginAt()))
              .set(CHARACTERS.VERSION, entity.getVersion())
              .returningResult(CHARACTERS.ID)
              .fetchOne(CHARACTERS.ID);
      return findStoredById(id).orElseThrow();
    }
    int updatedRows =
        dsl.update(CHARACTERS)
            .set(CHARACTERS.TENANT_ID, entity.getTenantId())
            .set(CHARACTERS.ACCOUNT_ID, entity.getAccountId())
            .set(CHARACTERS.PLAYABLE_STATE_KEY, entity.getPlayableStateKey())
            .set(CHARACTERS.NAME, entity.getName())
            .set(CHARACTERS.BODY_LAYOUT_KEY, entity.getBodyLayoutKey())
            .set(CHARACTERS.LEVEL, entity.getLevel())
            .set(CHARACTERS.EXPERIENCE, entity.getExperience())
            .set(CHARACTERS.STRENGTH, entity.getStrength())
            .set(CHARACTERS.AGILITY, entity.getAgility())
            .set(CHARACTERS.INTELLIGENCE, entity.getIntelligence())
            .set(CHARACTERS.STAMINA, entity.getStamina())
            .set(CHARACTERS.HEALTH, entity.getHealth())
            .set(CHARACTERS.MANA, entity.getMana())
            .set(CHARACTERS.LAST_LOGIN_AT, toLocalDateTime(entity.getLastLoginAt()))
            .set(CHARACTERS.VERSION, entity.getVersion() + 1)
            .where(
                CHARACTERS
                    .ID
                    .eq(entity.getId())
                    .and(CHARACTERS.TENANT_ID.eq(entity.getTenantId()))
                    .and(CHARACTERS.ACTOR_IDENTITY_STATUS.eq("OWNER_RESOLVED")))
            .execute();
    if (updatedRows != 1) {
      throw new IllegalStateException("CHARACTER_IDENTITY_NOT_OWNER_RESOLVED");
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Character toEntity(Record record) {
    if (record == null) {
      return null;
    }
    Character character = new Character();
    character.setId(record.get(CHARACTERS.ID));
    character.setActorIdentity(
        new net.firedevops.firemud.entitymanagement.entity.ActorIdentity(
            record.get(CHARACTERS.CHARACTER_UUID),
            record.get(CHARACTERS.ACCOUNT_UUID),
            record.get(CHARACTERS.TENANT_UUID),
            record.get(CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID),
            scopeFrom(record.get(CHARACTERS.PLAYABLE_STATE_SCOPE)),
            net.firedevops.firemud.entitymanagement.entity.ActorIdentityStatus.valueOf(
                record.get(CHARACTERS.ACTOR_IDENTITY_STATUS)),
            record.get(CHARACTERS.ACTOR_IDENTITY_QUARANTINE_REASON)));
    character.setTenantId(record.get(CHARACTERS.TENANT_ID));
    character.setAccountId(record.get(CHARACTERS.ACCOUNT_ID));
    character.setPlayableStateKey(record.get(CHARACTERS.PLAYABLE_STATE_KEY));
    character.setName(record.get(CHARACTERS.NAME));
    character.setBodyLayoutKey(record.get(CHARACTERS.BODY_LAYOUT_KEY));
    character.setLevel(record.get(CHARACTERS.LEVEL));
    character.setExperience(record.get(CHARACTERS.EXPERIENCE));
    character.setStrength(record.get(CHARACTERS.STRENGTH));
    character.setAgility(record.get(CHARACTERS.AGILITY));
    character.setIntelligence(record.get(CHARACTERS.INTELLIGENCE));
    character.setStamina(record.get(CHARACTERS.STAMINA));
    character.setHealth(record.get(CHARACTERS.HEALTH));
    character.setMana(record.get(CHARACTERS.MANA));
    character.setLastLoginAt(toInstant(record.get(CHARACTERS.LAST_LOGIN_AT)));
    character.setVersion(record.get(CHARACTERS.VERSION));
    return character;
  }

  private Optional<Character> findStoredById(Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(CHARACTERS).where(CHARACTERS.ID.eq(id)).fetchOne(this::toEntity));
  }

  private net.firedevops.firemud.entitymanagement.v1.PlayableStateScope scopeFrom(String value) {
    return value == null
        ? null
        : net.firedevops.firemud.entitymanagement.v1.PlayableStateScope.valueOf(value);
  }

  private InventoryEntry toInventoryEntry(Record record, Character character) {
    InventoryEntry entry = new InventoryEntry();
    InventoryKey key = new InventoryKey();
    key.setCharacterId(record.get(INVENTORY.CHARACTER_ID));
    key.setItemId(record.get(INVENTORY.ITEM_ID));
    entry.setId(key);
    entry.setCharacter(character);
    entry.setItem(
        JooqEntityManagementRepositorySupport.partialItem(
            record.get(ITEMS.ID),
            record.get(ITEMS.TENANT_ID),
            record.get(ITEMS.VERSION_ID),
            record.get(ITEMS.NAME),
            record.get(ITEMS.DESCRIPTION),
            record.get(ITEMS.EQUIPMENT_SLOT),
            record.get(ITEMS.EQUIPMENT_SLOT_GROUP_KEY),
            record.get(ITEMS.IS_CONTAINER),
            record.get(ITEMS.IS_STACKABLE),
            record.get(ITEMS.STACK_COMPATIBILITY_MODE),
            record.get(ITEMS.STACK_VARIANT_KEY),
            record.get(ITEMS.EFFECT_PAYLOAD_JSON)));
    entry.setQuantity(record.get(INVENTORY.QUANTITY));
    entry.setVersion(record.get(INVENTORY.VERSION));
    return entry;
  }
}
