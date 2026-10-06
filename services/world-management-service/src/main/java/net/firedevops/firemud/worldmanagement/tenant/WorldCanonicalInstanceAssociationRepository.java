package net.firedevops.firemud.worldmanagement.tenant;

import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * World-local storage and exact readback for a claimed canonical instance association.
 *
 * <p>This component has no public transport and does not authenticate Game Session. A positive
 * owner readback only returns retained correlation; JOIN, ACTIVE, and admission remain unavailable
 * until an authenticated same-namespace producer and complete prepare/materialization proof are
 * integrated by the lifecycle owner.
 */
public final class WorldCanonicalInstanceAssociationRepository {
  private static final Table<?> ASSOCIATION =
      DSL.table(DSL.name("world_canonical_instance_association"));
  private static final Table<?> WORLD_INSTANCE = DSL.table(DSL.name("world_instance"));
  private static final Table<?> COMPLETE_BINDING =
      DSL.table(DSL.name("world_complete_launch_binding"));
  private static final Table<?> SOURCE_INTAKE = DSL.table(DSL.name("world_authored_source_intake"));
  private static final Table<?> VERSION_IDENTITY =
      DSL.table(DSL.name("world_authored_version_identity"));
  private static final JsonMapper CLOSED_JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private static final Field<Short> SCHEMA_VERSION = field("schema_version", Short.class);
  private static final Field<UUID> CANONICAL_GAME_INSTANCE_ID =
      field("canonical_game_instance_id", UUID.class);
  private static final Field<String> CANONICAL_TARGET_NAMESPACE =
      field("canonical_target_namespace", String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID = field("canonical_tenant_id", UUID.class);
  private static final Field<String> CANONICAL_WORLD_SLUG =
      field("canonical_world_slug", String.class);
  private static final Field<UUID> PLAYABLE_STATE_NAMESPACE_ID =
      field("playable_state_namespace_id", UUID.class);
  private static final Field<String> PLAYABLE_STATE_SCOPE =
      field("playable_state_scope", String.class);
  private static final Field<Boolean> PUBLIC_PRODUCTION = field("public_production", Boolean.class);
  private static final Field<UUID> PLAYTEST_LIFECYCLE_ID =
      field("playtest_lifecycle_id", UUID.class);
  private static final Field<Long> PLAYTEST_STATE_GENERATION =
      field("playtest_state_generation", Long.class);
  private static final Field<String> CONTROL_PLANE_REQUEST_ID =
      field("control_plane_request_id", String.class);
  private static final Field<Long> WORLD_INSTANCE_ID = field("world_instance_id", Long.class);
  private static final Field<UUID> LAUNCH_BINDING_OPERATION_ID =
      field("canonical_launch_binding_operation_id", UUID.class);
  private static final Field<UUID> VERSION_IDENTITY_OPERATION_ID =
      field("version_identity_operation_id", UUID.class);
  private static final Field<UUID> CANONICAL_VERSION_ID = field("canonical_version_id", UUID.class);
  private static final Field<Long> LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<Long> PRIVATE_GAME_INSTANCE_KEY =
      field("private_game_instance_key", Long.class);
  private static final Field<Long> GAME_TEMPLATE_ID = field("game_template_id", Long.class);
  private static final Field<String> LAUNCH_DESCRIPTOR_ID =
      field("launch_descriptor_id", String.class);
  private static final Field<Long> LOCAL_VERSION_KEY = field("local_version_key", Long.class);
  private static final Field<String> SCRIPT_PATCH_VERSION =
      field("script_patch_version", String.class);
  private static final Field<String> RUNTIME_FLAGS_JSON = field("runtime_flags_json", String.class);
  private static final Field<String> GENERATION_CONFIG_REVISION =
      field("generation_config_revision", String.class);
  private static final Field<Long> RELEASE_BUNDLE_ID = field("release_bundle_id", Long.class);
  private static final Field<String> PUBLISHED_RELEASE_BUNDLE_REF =
      field("published_release_bundle_ref", String.class);
  private static final Field<Long> VERSION_STATE_EPOCH = field("version_state_epoch", Long.class);
  private static final Field<String> REMAP_SET_ID = field("remap_set_id", String.class);
  private static final Field<UUID> INTAKE_OPERATION_ID = field("intake_operation_id", UUID.class);
  private static final Field<UUID> INTAKE_REQUEST_ID = field("intake_request_id", UUID.class);
  private static final Field<UUID> SOURCE_OPERATION_ID = field("source_operation_id", UUID.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      field("source_evidence_digest", String.class);
  private static final Field<String> INTAKE_REQUEST_DIGEST =
      field("intake_request_digest", String.class);
  private static final Field<String> INTAKE_RECEIPT_DIGEST =
      field("intake_receipt_digest", String.class);
  private static final Field<String> DESCRIPTOR_REQUEST_DIGEST =
      field("descriptor_request_digest", String.class);
  private static final Field<String> DESCRIPTOR_RESULT_DIGEST =
      field("descriptor_result_digest", String.class);
  private static final Field<String> RELEASE_ATTESTATION_DIGEST =
      field("release_attestation_digest", String.class);

  private static final Field<Long> INSTANCE_ID = field("id", Long.class);
  private static final Field<Long> INSTANCE_TENANT_ID = field("tenant_id", Long.class);
  private static final Field<Long> INSTANCE_GAME_INSTANCE_ID =
      field("game_instance_id", Long.class);
  private static final Field<Long> INSTANCE_GAME_TEMPLATE_ID =
      field("game_template_id", Long.class);
  private static final Field<String> INSTANCE_CONTROL_PLANE_REQUEST_ID =
      field("control_plane_request_id", String.class);
  private static final Field<String> INSTANCE_LAUNCH_DESCRIPTOR_ID =
      field("launch_descriptor_id", String.class);
  private static final Field<Long> INSTANCE_VERSION_ID = field("version_id", Long.class);
  private static final Field<String> INSTANCE_SCRIPT_PATCH_VERSION =
      field("script_patch_version", String.class);
  private static final Field<String> INSTANCE_RUNTIME_FLAGS_JSON =
      field("runtime_flags_json", String.class);
  private static final Field<String> INSTANCE_GENERATION_CONFIG_REVISION =
      field("generation_config_revision", String.class);
  private static final Field<Long> INSTANCE_RELEASE_BUNDLE_ID =
      field("release_bundle_id", Long.class);
  private static final Field<String> INSTANCE_PUBLISHED_RELEASE_BUNDLE_REF =
      field("published_release_bundle_ref", String.class);
  private static final Field<Long> INSTANCE_VERSION_STATE_EPOCH =
      field("version_state_epoch", Long.class);
  private static final Field<String> INSTANCE_REMAP_SET_ID = field("remap_set_id", String.class);
  private static final Field<UUID> INSTANCE_CANONICAL_GAME_INSTANCE_ID =
      field("canonical_game_instance_id", UUID.class);
  private static final Field<String> INSTANCE_CANONICAL_TARGET_NAMESPACE =
      field("canonical_target_namespace", String.class);
  private static final Field<UUID> INSTANCE_CANONICAL_TENANT_ID =
      field("canonical_tenant_id", UUID.class);
  private static final Field<String> INSTANCE_CANONICAL_WORLD_SLUG =
      field("canonical_world_slug", String.class);
  private static final Field<UUID> INSTANCE_PLAYABLE_STATE_NAMESPACE_ID =
      field("playable_state_namespace_id", UUID.class);
  private static final Field<String> INSTANCE_PLAYABLE_STATE_SCOPE =
      field("playable_state_scope", String.class);
  private static final Field<Boolean> INSTANCE_PUBLIC_PRODUCTION =
      field("public_production", Boolean.class);
  private static final Field<UUID> INSTANCE_PLAYTEST_LIFECYCLE_ID =
      field("playtest_lifecycle_id", UUID.class);
  private static final Field<Long> INSTANCE_PLAYTEST_STATE_GENERATION =
      field("playtest_state_generation", Long.class);
  private static final Field<UUID> INSTANCE_LAUNCH_BINDING_OPERATION_ID =
      field("canonical_launch_binding_operation_id", UUID.class);

  private static final Field<UUID> BINDING_OPERATION_ID =
      DSL.field(DSL.name("binding_operation_id"), UUID.class);
  private static final Field<UUID> BINDING_CANONICAL_TENANT_ID =
      field("canonical_tenant_id", UUID.class);
  private static final Field<UUID> BINDING_CANONICAL_VERSION_ID =
      field("canonical_version_id", UUID.class);
  private static final Field<String> BINDING_TARGET_NAMESPACE =
      field("target_namespace", String.class);
  private static final Field<String> BINDING_WORLD_SLUG = field("world_slug", String.class);
  private static final Field<String> BINDING_CONTROL_PLANE_REQUEST_ID =
      field("control_plane_request_id", String.class);
  private static final Field<UUID> BINDING_INTAKE_OPERATION_ID =
      field("intake_operation_id", UUID.class);
  private static final Field<UUID> BINDING_INTAKE_REQUEST_ID =
      field("intake_request_id", UUID.class);
  private static final Field<Long> BINDING_LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<UUID> BINDING_SOURCE_OPERATION_ID =
      field("source_operation_id", UUID.class);
  private static final Field<String> BINDING_SOURCE_EVIDENCE_DIGEST =
      field("source_evidence_digest", String.class);
  private static final Field<String> BINDING_INTAKE_RECEIPT_DIGEST =
      field("intake_receipt_digest", String.class);
  private static final Field<String> BINDING_DESCRIPTOR_REQUEST_DIGEST =
      field("descriptor_request_digest", String.class);
  private static final Field<String> BINDING_DESCRIPTOR_RESULT_DIGEST =
      field("descriptor_result_digest", String.class);
  private static final Field<String> BINDING_RELEASE_ATTESTATION_DIGEST =
      field("release_attestation_digest", String.class);
  private static final Field<String> BINDING_DESCRIPTOR_JSON =
      field("descriptor_json", String.class);
  private static final Field<String> BINDING_RELEASE_ATTESTATION_JSON =
      field("release_attestation_json", String.class);

  private static final Field<UUID> SOURCE_OPERATION_ID_KEY =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<Short> SOURCE_SCHEMA_VERSION = field("schema_version", Short.class);
  private static final Field<String> SOURCE_TARGET_NAMESPACE =
      field("target_namespace", String.class);
  private static final Field<UUID> SOURCE_INTAKE_REQUEST_ID =
      field("intake_request_id", UUID.class);
  private static final Field<String> SOURCE_REQUEST_DIGEST = field("request_digest", String.class);
  private static final Field<Long> SOURCE_LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<Short> SOURCE_EVIDENCE_SCHEMA_VERSION =
      field("source_schema_version", Short.class);
  private static final Field<UUID> SOURCE_REGISTRATION_REQUEST_ID =
      field("source_registration_request_id", UUID.class);
  private static final Field<UUID> SOURCE_SOURCE_OPERATION_ID =
      field("source_operation_id", UUID.class);
  private static final Field<String> SOURCE_SOURCE_REQUEST_DIGEST =
      field("source_request_digest", String.class);
  private static final Field<UUID> SOURCE_CANONICAL_TENANT_ID =
      field("canonical_tenant_id", UUID.class);
  private static final Field<String> SOURCE_TENANT_SLUG = field("tenant_slug", String.class);
  private static final Field<String> SOURCE_WORLD_SLUG = field("world_slug", String.class);
  private static final Field<String> SOURCE_WORLD_DISPLAY_NAME =
      field("world_display_name", String.class);
  private static final Field<Long> SOURCE_GAME_ROW_ID = field("source_game_row_id", Long.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      field("source_game_tenant_key", String.class);
  private static final Field<String> SOURCE_PROVENANCE_KIND =
      field("source_provenance_kind", String.class);
  private static final Field<String> SOURCE_SOURCE_EVIDENCE_DIGEST =
      field("source_evidence_digest", String.class);
  private static final Field<String> SOURCE_RECEIPT_DIGEST = field("receipt_digest", String.class);

  private static final Field<UUID> VERSION_OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<String> VERSION_TARGET_NAMESPACE =
      field("target_namespace", String.class);
  private static final Field<UUID> VERSION_CANONICAL_TENANT_ID =
      field("canonical_tenant_id", UUID.class);
  private static final Field<String> VERSION_WORLD_SLUG = field("world_slug", String.class);
  private static final Field<UUID> VERSION_CANONICAL_VERSION_ID =
      field("canonical_version_id", UUID.class);
  private static final Field<Long> VERSION_GAME_DESIGN_VERSION_ID =
      field("game_design_version_id", Long.class);
  private static final Field<Long> VERSION_LOCAL_VERSION_KEY =
      field("local_version_key", Long.class);
  private static final Field<UUID> VERSION_INTAKE_OPERATION_ID =
      field("intake_operation_id", UUID.class);
  private static final Field<UUID> VERSION_INTAKE_REQUEST_ID =
      field("intake_request_id", UUID.class);
  private static final Field<Long> VERSION_LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<String> VERSION_INTAKE_REQUEST_DIGEST =
      field("intake_request_digest", String.class);
  private static final Field<UUID> VERSION_SOURCE_OPERATION_ID =
      field("source_operation_id", UUID.class);
  private static final Field<String> VERSION_SOURCE_EVIDENCE_DIGEST =
      field("source_evidence_digest", String.class);
  private static final Field<String> VERSION_INTAKE_RECEIPT_DIGEST =
      field("intake_receipt_digest", String.class);
  private static final Field<Short> VERSION_STATE_SCHEMA_VERSION =
      field("version_state_schema_version", Short.class);
  private static final Field<UUID> VERSION_STATE_READ_REQUEST_ID =
      field("version_state_read_request_id", UUID.class);
  private static final Field<String> VERSION_STATE_STATE =
      field("version_state_state", String.class);
  private static final Field<Long> VERSION_STATE_EVIDENCE_EPOCH =
      field("version_state_epoch", Long.class);
  private static final Field<String> VERSION_STATE_EVIDENCE_DIGEST =
      field("version_state_evidence_digest", String.class);
  private static final Field<String> VERSION_STATE_EVIDENCE_JSON =
      field("version_state_evidence_json", String.class);

  private final DSLContext dsl;
  private final WorldCompleteLaunchBindingRepository launchBindingRepository;
  private final WorldAuthoredSourceIntakeRepository sourceIntakeRepository;
  private final WorldAuthoredVersionIdentityRepository versionIdentityRepository;

  public WorldCanonicalInstanceAssociationRepository(
      DSLContext dsl,
      WorldCompleteLaunchBindingRepository launchBindingRepository,
      WorldAuthoredSourceIntakeRepository sourceIntakeRepository,
      WorldAuthoredVersionIdentityRepository versionIdentityRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
    this.launchBindingRepository =
        Objects.requireNonNull(launchBindingRepository, "launchBindingRepository");
    this.sourceIntakeRepository =
        Objects.requireNonNull(sourceIntakeRepository, "sourceIntakeRepository");
    this.versionIdentityRepository =
        Objects.requireNonNull(versionIdentityRepository, "versionIdentityRepository");
  }

  /**
   * Retains an exact non-authorizing claim in the caller's short writable READ COMMITTED World
   * transaction, after the same transaction created or selected its exact canonical World row.
   *
   * <p>This method does not commit the caller's transaction. The caller must commit, then use
   * {@link #readOwnerAssociation(UUID)} as a separate committed read before reporting storage
   * success. An exact retry compares the immutable V26 descriptor/release bytes and V23/V27 source
   * and Version provenance already supplied in {@code claim}; it never re-resolves Game Design.
   */
  public void retainClaimInOwnerTransaction(WorldCanonicalInstanceAssociation.Claim claim) {
    requireWritableReadCommittedOwnerTransaction();
    Objects.requireNonNull(claim, "claim");

    Record worldRow = lockWorldInstance(claim.worldInstanceId());
    WorldCanonicalInstanceAssociation.WorldPrepareFields prepareFields =
        toWorldPrepareFields(worldRow);
    requireInsertBornIdentity(worldRow, claim);
    prepareFields.requireMatches(
        claim.identity(), claim.completeLaunchBinding(), claim.versionIdentity());
    requireExactImmutableOwnerEvidence(claim);

    Record existing = findConflict(claim);
    if (existing != null) {
      requireSameStoredClaim(existing, claim, prepareFields);
      return;
    }

    int inserted =
        dsl.insertInto(ASSOCIATION)
            .set(SCHEMA_VERSION, (short) 1)
            .set(CANONICAL_GAME_INSTANCE_ID, claim.canonicalGameInstanceId())
            .set(CANONICAL_TARGET_NAMESPACE, claim.targetNamespace())
            .set(CANONICAL_TENANT_ID, claim.canonicalTenantId())
            .set(CANONICAL_WORLD_SLUG, claim.worldSlug())
            .set(PLAYABLE_STATE_NAMESPACE_ID, claim.playableStateNamespaceId())
            .set(PLAYABLE_STATE_SCOPE, claim.playableStateScope())
            .set(PUBLIC_PRODUCTION, claim.identity().publicProduction())
            .set(PLAYTEST_LIFECYCLE_ID, (UUID) null)
            .set(PLAYTEST_STATE_GENERATION, (Long) null)
            .set(CONTROL_PLANE_REQUEST_ID, claim.controlPlaneRequestId())
            .set(WORLD_INSTANCE_ID, prepareFields.worldInstanceId())
            .set(LAUNCH_BINDING_OPERATION_ID, claim.launchBindingOperationId())
            .set(VERSION_IDENTITY_OPERATION_ID, claim.versionIdentityOperationId())
            .set(CANONICAL_VERSION_ID, claim.canonicalVersionId())
            .set(LOCAL_TENANT_KEY, prepareFields.privateTenantKey())
            .set(PRIVATE_GAME_INSTANCE_KEY, prepareFields.privateGameInstanceKey())
            .set(GAME_TEMPLATE_ID, prepareFields.gameTemplateId())
            .set(LAUNCH_DESCRIPTOR_ID, prepareFields.launchDescriptorId())
            .set(LOCAL_VERSION_KEY, prepareFields.localVersionKey())
            .set(SCRIPT_PATCH_VERSION, prepareFields.scriptPatchVersion())
            .set(RUNTIME_FLAGS_JSON, prepareFields.runtimeFlagsJson())
            .set(GENERATION_CONFIG_REVISION, prepareFields.generationConfigRevision())
            .set(RELEASE_BUNDLE_ID, prepareFields.releaseBundleId())
            .set(PUBLISHED_RELEASE_BUNDLE_REF, prepareFields.publishedReleaseBundleRef())
            .set(VERSION_STATE_EPOCH, prepareFields.versionStateEpoch())
            .set(REMAP_SET_ID, prepareFields.remapSetId())
            .set(
                INTAKE_OPERATION_ID,
                claim.completeLaunchBinding().sourceIntakeReceipt().operationId())
            .set(
                INTAKE_REQUEST_ID,
                claim.completeLaunchBinding().sourceIntakeReceipt().intakeRequestId())
            .set(
                SOURCE_OPERATION_ID,
                claim.completeLaunchBinding().sourceIntakeReceipt().sourceOperationId())
            .set(
                SOURCE_EVIDENCE_DIGEST,
                claim.completeLaunchBinding().sourceIntakeReceipt().sourceEvidenceDigest())
            .set(
                INTAKE_REQUEST_DIGEST,
                claim.completeLaunchBinding().sourceIntakeReceipt().requestDigest())
            .set(
                INTAKE_RECEIPT_DIGEST,
                claim.completeLaunchBinding().sourceIntakeReceipt().receiptDigest())
            .set(
                DESCRIPTOR_REQUEST_DIGEST,
                claim.completeLaunchBinding().descriptor().requestDigest())
            .set(
                DESCRIPTOR_RESULT_DIGEST, claim.completeLaunchBinding().descriptor().resultDigest())
            .set(
                RELEASE_ATTESTATION_DIGEST,
                claim.completeLaunchBinding().evidence().releaseAttestation().evidenceDigest())
            .onConflictDoNothing()
            .execute();

    Record stored = findConflict(claim);
    if (stored == null) {
      throw new InvalidAssociationEvidenceException(
          "World canonical instance association is missing after its owner claim");
    }
    requireSameStoredClaim(stored, claim, prepareFields);
    if (inserted == 0) {
      // The complete exact compare above is the only idempotent conflict outcome.
      return;
    }
  }

  /**
   * Independently reads and reconstructs one immutable owner association after the write
   * transaction commits. A present result is non-authorizing and carries no lifecycle status.
   */
  public Optional<WorldCanonicalInstanceAssociation> readOwnerAssociation(
      UUID canonicalGameInstanceId) {
    requireNoActiveTransaction("World canonical instance association read");
    requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
    Record row = findByCanonicalGameInstance(canonicalGameInstanceId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(toAssociation(row));
  }

  private WorldCanonicalInstanceAssociation toAssociation(Record row) {
    try {
      short schemaVersion = required(row, SCHEMA_VERSION);
      if (schemaVersion != 1) {
        throw new IllegalArgumentException(
            "Unsupported World canonical instance association schema");
      }
      String namespace = required(row, CANONICAL_TARGET_NAMESPACE);
      UUID tenantId = required(row, CANONICAL_TENANT_ID);
      String worldSlug = required(row, CANONICAL_WORLD_SLUG);
      String controlRequestId = required(row, CONTROL_PLANE_REQUEST_ID);
      UUID bindingOperationId = required(row, LAUNCH_BINDING_OPERATION_ID);
      UUID intakeRequestId = required(row, INTAKE_REQUEST_ID);
      WorldCompleteLaunchBindingRepository.StoredBinding storedBinding =
          launchBindingRepository
              .read(namespace, tenantId, controlRequestId)
              .orElseThrow(
                  () ->
                      new InvalidAssociationEvidenceException(
                          "World canonical association lost its immutable launch binding"));
      if (!storedBinding.operationId().equals(bindingOperationId)) {
        throw new InvalidAssociationEvidenceException(
            "World canonical association substituted its complete launch binding");
      }
      WorldAuthoredSourceIntakeReceipt sourceReceipt =
          sourceIntakeRepository
              .read(namespace, intakeRequestId)
              .orElseThrow(
                  () ->
                      new InvalidAssociationEvidenceException(
                          "World canonical association lost its original source intake"));
      WorldCompleteLaunchBindingReceipt completeBinding =
          launchBindingRepository.toReceipt(storedBinding, sourceReceipt);
      UUID canonicalVersionId = required(row, CANONICAL_VERSION_ID);
      WorldAuthoredVersionIdentityReceipt versionIdentity =
          versionIdentityRepository
              .readByCanonicalVersion(namespace, tenantId, worldSlug, canonicalVersionId)
              .orElseThrow(
                  () ->
                      new InvalidAssociationEvidenceException(
                          "World canonical association lost its UUID-to-private-Version receipt"));
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity =
          new WorldCanonicalInstanceAssociation.CanonicalIdentity(
              required(row, CANONICAL_GAME_INSTANCE_ID),
              namespace,
              tenantId,
              worldSlug,
              required(row, PLAYABLE_STATE_NAMESPACE_ID),
              required(row, PLAYABLE_STATE_SCOPE),
              required(row, PUBLIC_PRODUCTION),
              controlRequestId);
      if (row.get(PLAYTEST_LIFECYCLE_ID) != null || row.get(PLAYTEST_STATE_GENERATION) != null) {
        throw new InvalidAssociationEvidenceException(
            "Persisted playtest association lacks the current producer's conditional lifecycle/generation proof");
      }
      long worldInstanceId = required(row, WORLD_INSTANCE_ID);
      Record worldRow = readWorldInstance(worldInstanceId);
      WorldCanonicalInstanceAssociation.WorldPrepareFields prepareFields =
          toWorldPrepareFields(worldRow);
      requireInsertBornIdentity(worldRow, identity, completeBinding.operationId());
      prepareFields.requireMatches(identity, completeBinding, versionIdentity);
      requireStoredAssociationColumns(
          row, identity, worldInstanceId, completeBinding, versionIdentity, prepareFields);
      return new WorldCanonicalInstanceAssociation(
          identity, worldInstanceId, completeBinding, versionIdentity, prepareFields);
    } catch (RuntimeException exception) {
      if (exception instanceof InvalidAssociationEvidenceException) {
        throw exception;
      }
      throw new InvalidAssociationEvidenceException(
          "Persisted World canonical instance association is invalid", exception);
    }
  }

  private void requireExactImmutableOwnerEvidence(WorldCanonicalInstanceAssociation.Claim claim) {
    var sourceReceipt = claim.completeLaunchBinding().sourceIntakeReceipt();
    var evidence = claim.completeLaunchBinding().evidence();
    var descriptor = evidence.descriptor();
    var release = evidence.releaseAttestation();

    Record binding =
        dsl.selectFrom(COMPLETE_BINDING)
            .where(BINDING_OPERATION_ID.eq(claim.launchBindingOperationId()))
            .fetchOne();
    if (binding == null
        || !Objects.equals(
            binding.get(BINDING_TARGET_NAMESPACE, String.class), claim.targetNamespace())
        || !Objects.equals(
            binding.get(BINDING_CANONICAL_TENANT_ID, UUID.class), claim.canonicalTenantId())
        || !Objects.equals(
            binding.get(BINDING_CANONICAL_VERSION_ID, UUID.class), claim.canonicalVersionId())
        || !Objects.equals(binding.get(BINDING_WORLD_SLUG, String.class), claim.worldSlug())
        || !Objects.equals(
            binding.get(BINDING_CONTROL_PLANE_REQUEST_ID, String.class),
            claim.controlPlaneRequestId())
        || !Objects.equals(
            binding.get(BINDING_INTAKE_OPERATION_ID, UUID.class), sourceReceipt.operationId())
        || !Objects.equals(
            binding.get(BINDING_INTAKE_REQUEST_ID, UUID.class), sourceReceipt.intakeRequestId())
        || !Objects.equals(
            binding.get(BINDING_LOCAL_TENANT_KEY, Long.class), sourceReceipt.localTenantKey())
        || !Objects.equals(
            binding.get(BINDING_SOURCE_OPERATION_ID, UUID.class), sourceReceipt.sourceOperationId())
        || !Objects.equals(
            binding.get(BINDING_SOURCE_EVIDENCE_DIGEST, String.class),
            sourceReceipt.sourceEvidenceDigest())
        || !Objects.equals(
            binding.get(BINDING_INTAKE_RECEIPT_DIGEST, String.class), sourceReceipt.receiptDigest())
        || !Objects.equals(
            binding.get(BINDING_DESCRIPTOR_REQUEST_DIGEST, String.class),
            descriptor.requestDigest())
        || !Objects.equals(
            binding.get(BINDING_DESCRIPTOR_RESULT_DIGEST, String.class), descriptor.resultDigest())
        || !Objects.equals(
            binding.get(BINDING_RELEASE_ATTESTATION_DIGEST, String.class), release.evidenceDigest())
        || !Objects.equals(
            binding.get(BINDING_DESCRIPTOR_JSON, String.class), writeJson(descriptor))
        || !Objects.equals(
            binding.get(BINDING_RELEASE_ATTESTATION_JSON, String.class), writeJson(release))) {
      throw new RegistrationConflictException(
          "Canonical instance claim differs from exact immutable V26 descriptor/release bytes");
    }

    Record source =
        dsl.selectFrom(SOURCE_INTAKE)
            .where(SOURCE_OPERATION_ID_KEY.eq(sourceReceipt.operationId()))
            .fetchOne();
    requireExactSourceColumns(source, sourceReceipt);

    WorldAuthoredVersionIdentityReceipt identity = claim.versionIdentity();
    AuthoredWorldVersionStateEvidence versionEvidence = identity.versionStateEvidence();
    Record version =
        dsl.selectFrom(VERSION_IDENTITY)
            .where(VERSION_OPERATION_ID.eq(identity.operationId()))
            .fetchOne();
    if (version == null
        || !Objects.equals(
            version.get(VERSION_TARGET_NAMESPACE, String.class), identity.targetNamespace())
        || !Objects.equals(
            version.get(VERSION_CANONICAL_TENANT_ID, UUID.class), identity.canonicalTenantId())
        || !Objects.equals(version.get(VERSION_WORLD_SLUG, String.class), identity.worldSlug())
        || !Objects.equals(
            version.get(VERSION_CANONICAL_VERSION_ID, UUID.class), identity.canonicalVersionId())
        || !Objects.equals(
            version.get(VERSION_GAME_DESIGN_VERSION_ID, Long.class), identity.gameDesignVersionId())
        || !Objects.equals(
            version.get(VERSION_LOCAL_VERSION_KEY, Long.class), identity.localVersionKey())
        || !Objects.equals(
            version.get(VERSION_INTAKE_OPERATION_ID, UUID.class), sourceReceipt.operationId())
        || !Objects.equals(
            version.get(VERSION_INTAKE_REQUEST_ID, UUID.class), sourceReceipt.intakeRequestId())
        || !Objects.equals(
            version.get(VERSION_LOCAL_TENANT_KEY, Long.class), sourceReceipt.localTenantKey())
        || !Objects.equals(
            version.get(VERSION_INTAKE_REQUEST_DIGEST, String.class), sourceReceipt.requestDigest())
        || !Objects.equals(
            version.get(VERSION_SOURCE_OPERATION_ID, UUID.class), sourceReceipt.sourceOperationId())
        || !Objects.equals(
            version.get(VERSION_SOURCE_EVIDENCE_DIGEST, String.class),
            sourceReceipt.sourceEvidenceDigest())
        || !Objects.equals(
            version.get(VERSION_INTAKE_RECEIPT_DIGEST, String.class), sourceReceipt.receiptDigest())
        || !Objects.equals(
            version.get(VERSION_STATE_SCHEMA_VERSION, Short.class),
            (short) versionEvidence.request().schemaVersion())
        || !Objects.equals(
            version.get(VERSION_STATE_READ_REQUEST_ID, UUID.class),
            versionEvidence.request().readRequestId())
        || !Objects.equals(
            version.get(VERSION_STATE_STATE, String.class),
            versionEvidence.versionState().name().replace("VERSION_LIFECYCLE_STATE_", ""))
        || !Objects.equals(
            version.get(VERSION_STATE_EVIDENCE_EPOCH, Long.class),
            versionEvidence.versionStateEpoch())
        || !Objects.equals(
            version.get(VERSION_STATE_EVIDENCE_DIGEST, String.class),
            versionEvidence.evidenceDigest())
        || !Objects.equals(
            version.get(VERSION_STATE_EVIDENCE_JSON, String.class), writeJson(versionEvidence))) {
      throw new RegistrationConflictException(
          "Canonical instance claim differs from exact immutable V27 Version/source bytes");
    }
  }

  private static void requireExactSourceColumns(
      Record row, WorldAuthoredSourceIntakeReceipt receipt) {
    if (row == null) {
      throw new RegistrationConflictException(
          "Canonical instance claim lost its original source intake");
    }
    AuthoredWorldSourceEvidence source = receipt.source();
    if (!Objects.equals(
            row.get(SOURCE_SCHEMA_VERSION, Short.class), (short) receipt.schemaVersion())
        || !Objects.equals(
            row.get(SOURCE_TARGET_NAMESPACE, String.class), receipt.targetNamespace())
        || !Objects.equals(row.get(SOURCE_INTAKE_REQUEST_ID, UUID.class), receipt.intakeRequestId())
        || !Objects.equals(row.get(SOURCE_REQUEST_DIGEST, String.class), receipt.requestDigest())
        || !Objects.equals(row.get(SOURCE_LOCAL_TENANT_KEY, Long.class), receipt.localTenantKey())
        || !Objects.equals(
            row.get(SOURCE_EVIDENCE_SCHEMA_VERSION, Short.class), (short) source.schemaVersion())
        || !Objects.equals(
            row.get(SOURCE_REGISTRATION_REQUEST_ID, UUID.class), source.registrationRequestId())
        || !Objects.equals(row.get(SOURCE_SOURCE_OPERATION_ID, UUID.class), source.operationId())
        || !Objects.equals(
            row.get(SOURCE_SOURCE_REQUEST_DIGEST, String.class), source.requestDigest())
        || !Objects.equals(
            row.get(SOURCE_CANONICAL_TENANT_ID, UUID.class), source.canonicalTenantId())
        || !Objects.equals(row.get(SOURCE_TENANT_SLUG, String.class), source.tenantSlug())
        || !Objects.equals(row.get(SOURCE_WORLD_SLUG, String.class), source.worldSlug())
        || !Objects.equals(
            row.get(SOURCE_WORLD_DISPLAY_NAME, String.class), source.worldDisplayName())
        || !Objects.equals(row.get(SOURCE_GAME_ROW_ID, Long.class), source.sourceGameRowId())
        || !Objects.equals(
            row.get(SOURCE_GAME_TENANT_KEY, String.class), source.sourceGameTenantKey())
        || !Objects.equals(row.get(SOURCE_PROVENANCE_KIND, String.class), source.provenanceKind())
        || !Objects.equals(
            row.get(SOURCE_SOURCE_EVIDENCE_DIGEST, String.class), source.evidenceDigest())
        || !Objects.equals(row.get(SOURCE_RECEIPT_DIGEST, String.class), receipt.receiptDigest())) {
      throw new RegistrationConflictException(
          "Canonical instance claim differs from exact immutable World source intake fields");
    }
  }

  private void requireSameStoredClaim(
      Record stored,
      WorldCanonicalInstanceAssociation.Claim claim,
      WorldCanonicalInstanceAssociation.WorldPrepareFields prepareFields) {
    requireStoredAssociationColumns(
        stored,
        claim.identity(),
        claim.worldInstanceId(),
        claim.completeLaunchBinding(),
        claim.versionIdentity(),
        prepareFields);
    WorldCanonicalInstanceAssociation.WorldPrepareFields actual =
        toWorldPrepareFields(readWorldInstance(claim.worldInstanceId()));
    if (!actual.equals(prepareFields)) {
      throw new InvalidAssociationEvidenceException(
          "World canonical association row changed during exact owner retry");
    }
  }

  private static void requireStoredAssociationColumns(
      Record row,
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity,
      long worldInstanceId,
      WorldCompleteLaunchBindingReceipt completeLaunchBinding,
      WorldAuthoredVersionIdentityReceipt versionIdentity,
      WorldCanonicalInstanceAssociation.WorldPrepareFields prepareFields) {
    var source = completeLaunchBinding.sourceIntakeReceipt();
    var descriptor = completeLaunchBinding.descriptor();
    var release = completeLaunchBinding.evidence().releaseAttestation();
    if (required(row, SCHEMA_VERSION) != 1
        || !Objects.equals(
            required(row, CANONICAL_GAME_INSTANCE_ID), identity.canonicalGameInstanceId())
        || !Objects.equals(required(row, CANONICAL_TARGET_NAMESPACE), identity.targetNamespace())
        || !Objects.equals(required(row, CANONICAL_TENANT_ID), identity.canonicalTenantId())
        || !Objects.equals(required(row, CANONICAL_WORLD_SLUG), identity.worldSlug())
        || !Objects.equals(
            required(row, PLAYABLE_STATE_NAMESPACE_ID), identity.playableStateNamespaceId())
        || !Objects.equals(required(row, PLAYABLE_STATE_SCOPE), identity.playableStateScope())
        || !Objects.equals(required(row, PUBLIC_PRODUCTION), identity.publicProduction())
        || row.get(PLAYTEST_LIFECYCLE_ID) != null
        || row.get(PLAYTEST_STATE_GENERATION) != null
        || !Objects.equals(
            required(row, CONTROL_PLANE_REQUEST_ID), identity.controlPlaneRequestId())
        || !Objects.equals(required(row, WORLD_INSTANCE_ID), worldInstanceId)
        || !Objects.equals(
            required(row, LAUNCH_BINDING_OPERATION_ID), completeLaunchBinding.operationId())
        || !Objects.equals(
            required(row, VERSION_IDENTITY_OPERATION_ID), versionIdentity.operationId())
        || !Objects.equals(required(row, CANONICAL_VERSION_ID), release.canonicalVersionId())
        || !Objects.equals(required(row, LOCAL_TENANT_KEY), prepareFields.privateTenantKey())
        || !Objects.equals(
            required(row, PRIVATE_GAME_INSTANCE_KEY), prepareFields.privateGameInstanceKey())
        || !Objects.equals(required(row, GAME_TEMPLATE_ID), prepareFields.gameTemplateId())
        || !Objects.equals(required(row, LAUNCH_DESCRIPTOR_ID), prepareFields.launchDescriptorId())
        || !Objects.equals(required(row, LOCAL_VERSION_KEY), prepareFields.localVersionKey())
        || !Objects.equals(row.get(SCRIPT_PATCH_VERSION), prepareFields.scriptPatchVersion())
        || !Objects.equals(required(row, RUNTIME_FLAGS_JSON), prepareFields.runtimeFlagsJson())
        || !Objects.equals(
            required(row, GENERATION_CONFIG_REVISION), prepareFields.generationConfigRevision())
        || !Objects.equals(required(row, RELEASE_BUNDLE_ID), prepareFields.releaseBundleId())
        || !Objects.equals(
            required(row, PUBLISHED_RELEASE_BUNDLE_REF), prepareFields.publishedReleaseBundleRef())
        || !Objects.equals(required(row, VERSION_STATE_EPOCH), prepareFields.versionStateEpoch())
        || !Objects.equals(row.get(REMAP_SET_ID), prepareFields.remapSetId())
        || !Objects.equals(required(row, INTAKE_OPERATION_ID), source.operationId())
        || !Objects.equals(required(row, INTAKE_REQUEST_ID), source.intakeRequestId())
        || !Objects.equals(required(row, SOURCE_OPERATION_ID), source.sourceOperationId())
        || !Objects.equals(required(row, SOURCE_EVIDENCE_DIGEST), source.sourceEvidenceDigest())
        || !Objects.equals(required(row, INTAKE_REQUEST_DIGEST), source.requestDigest())
        || !Objects.equals(required(row, INTAKE_RECEIPT_DIGEST), source.receiptDigest())
        || !Objects.equals(required(row, DESCRIPTOR_REQUEST_DIGEST), descriptor.requestDigest())
        || !Objects.equals(required(row, DESCRIPTOR_RESULT_DIGEST), descriptor.resultDigest())
        || !Objects.equals(required(row, RELEASE_ATTESTATION_DIGEST), release.evidenceDigest())) {
      throw new RegistrationConflictException(
          "Canonical Game Instance, World row, request, source, Version, descriptor, or release binding conflicts");
    }
  }

  private void requireInsertBornIdentity(
      Record row, WorldCanonicalInstanceAssociation.Claim claim) {
    requireInsertBornIdentity(row, claim.identity(), claim.launchBindingOperationId());
  }

  private void requireInsertBornIdentity(
      Record row,
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity,
      UUID launchBindingOperationId) {
    if (row == null
        || !Objects.equals(
            row.get(INSTANCE_CANONICAL_GAME_INSTANCE_ID), identity.canonicalGameInstanceId())
        || !Objects.equals(row.get(INSTANCE_CANONICAL_TARGET_NAMESPACE), identity.targetNamespace())
        || !Objects.equals(row.get(INSTANCE_CANONICAL_TENANT_ID), identity.canonicalTenantId())
        || !Objects.equals(row.get(INSTANCE_CANONICAL_WORLD_SLUG), identity.worldSlug())
        || !Objects.equals(
            row.get(INSTANCE_PLAYABLE_STATE_NAMESPACE_ID), identity.playableStateNamespaceId())
        || !Objects.equals(row.get(INSTANCE_PLAYABLE_STATE_SCOPE), identity.playableStateScope())
        || !Objects.equals(row.get(INSTANCE_PUBLIC_PRODUCTION), identity.publicProduction())
        || row.get(INSTANCE_PLAYTEST_LIFECYCLE_ID) != null
        || row.get(INSTANCE_PLAYTEST_STATE_GENERATION) != null
        || !Objects.equals(
            row.get(INSTANCE_LAUNCH_BINDING_OPERATION_ID), launchBindingOperationId)) {
      throw new InvalidAssociationEvidenceException(
          "World row has no matching insert-born canonical prepare identity; retained numeric rows cannot be associated");
    }
  }

  private Record lockWorldInstance(long worldInstanceId) {
    Record row =
        dsl.selectFrom(WORLD_INSTANCE)
            .where(INSTANCE_ID.eq(worldInstanceId))
            .forUpdate()
            .fetchOne();
    if (row == null) {
      throw new InvalidAssociationEvidenceException(
          "Canonical instance association requires an already-created World instance row");
    }
    return row;
  }

  private Record readWorldInstance(long worldInstanceId) {
    Record row = dsl.selectFrom(WORLD_INSTANCE).where(INSTANCE_ID.eq(worldInstanceId)).fetchOne();
    if (row == null) {
      throw new InvalidAssociationEvidenceException(
          "Canonical instance association lost its exact World instance row");
    }
    return row;
  }

  private WorldCanonicalInstanceAssociation.WorldPrepareFields toWorldPrepareFields(Record row) {
    if (row == null) {
      throw new InvalidAssociationEvidenceException("World instance row is missing");
    }
    try {
      return new WorldCanonicalInstanceAssociation.WorldPrepareFields(
          required(row, INSTANCE_ID),
          required(row, INSTANCE_TENANT_ID),
          required(row, INSTANCE_GAME_INSTANCE_ID),
          required(row, INSTANCE_GAME_TEMPLATE_ID),
          required(row, INSTANCE_CONTROL_PLANE_REQUEST_ID),
          required(row, INSTANCE_LAUNCH_DESCRIPTOR_ID),
          required(row, INSTANCE_VERSION_ID),
          row.get(INSTANCE_SCRIPT_PATCH_VERSION),
          required(row, INSTANCE_RUNTIME_FLAGS_JSON),
          required(row, INSTANCE_GENERATION_CONFIG_REVISION),
          required(row, INSTANCE_RELEASE_BUNDLE_ID),
          required(row, INSTANCE_PUBLISHED_RELEASE_BUNDLE_REF),
          required(row, INSTANCE_VERSION_STATE_EPOCH),
          row.get(INSTANCE_REMAP_SET_ID));
    } catch (RuntimeException exception) {
      if (exception instanceof InvalidAssociationEvidenceException) {
        throw exception;
      }
      throw new InvalidAssociationEvidenceException(
          "World instance prepare fields are incomplete or invalid", exception);
    }
  }

  private Record findConflict(WorldCanonicalInstanceAssociation.Claim claim) {
    Record row = findByCanonicalGameInstance(claim.canonicalGameInstanceId());
    if (row == null) {
      row =
          dsl.selectFrom(ASSOCIATION)
              .where(
                  CANONICAL_TARGET_NAMESPACE
                      .eq(claim.targetNamespace())
                      .and(CANONICAL_TENANT_ID.eq(claim.canonicalTenantId()))
                      .and(CONTROL_PLANE_REQUEST_ID.eq(claim.controlPlaneRequestId())))
              .fetchOne();
    }
    if (row == null) {
      row =
          dsl.selectFrom(ASSOCIATION)
              .where(WORLD_INSTANCE_ID.eq(claim.worldInstanceId()))
              .fetchOne();
    }
    if (row == null) {
      row =
          dsl.selectFrom(ASSOCIATION)
              .where(LAUNCH_BINDING_OPERATION_ID.eq(claim.launchBindingOperationId()))
              .fetchOne();
    }
    return row;
  }

  private Record findByCanonicalGameInstance(UUID canonicalGameInstanceId) {
    return dsl.selectFrom(ASSOCIATION)
        .where(CANONICAL_GAME_INSTANCE_ID.eq(canonicalGameInstanceId))
        .fetchOne();
  }

  private static String writeJson(Object value) {
    try {
      return CLOSED_JSON.writeValueAsString(value);
    } catch (Exception exception) {
      throw new InvalidAssociationEvidenceException(
          "Immutable World owner evidence could not be encoded for exact comparison", exception);
    }
  }

  private static <T> T required(Record row, Field<T> field) {
    return Objects.requireNonNull(row.get(field), "Persisted " + field.getName() + " is null");
  }

  private static <T> Field<T> field(String name, Class<T> type) {
    return DSL.field(DSL.name(name), type);
  }

  private static void requireNoActiveTransaction(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(label + " requires an independent committed owner read");
    }
  }

  private static void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World canonical instance association requires an active World owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "World canonical instance association requires a writable owner transaction");
    }
    Integer declaredIsolation =
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (declaredIsolation == null || declaredIsolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException(
          "World canonical instance association requires READ COMMITTED isolation");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  public static final class RegistrationConflictException extends IllegalStateException {
    public RegistrationConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidAssociationEvidenceException extends IllegalStateException {
    public InvalidAssociationEvidenceException(String message) {
      super(message);
    }

    public InvalidAssociationEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
