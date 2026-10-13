package net.firedevops.firemud.gamedesign.service.impl;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftAssetInventory;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Commits exact selected inventory and manifest candidates before immutable object writes. */
@Service
public final class VersionAssetExportCandidateServiceImpl
    implements VersionAssetExportCandidateService {
  private static final String CONFLICT = "SELECTED_ASSET_EXPORT_CANDIDATE_CONFLICT";
  private static final Table<?> CANDIDATE = DSL.table(DSL.name("version_asset_export_candidate"));
  private static final Field<String> TENANT_ID = field("tenant_id", String.class);
  private static final Field<Long> VERSION_ID = field("version_id", Long.class);
  private static final Field<UUID> CANONICAL_TENANT_ID = field("canonical_tenant_id", UUID.class);
  private static final Field<UUID> CANONICAL_VERSION_ID = field("canonical_version_id", UUID.class);
  private static final Field<Long> EXPECTED_VERSION_STATE_EPOCH =
      field("expected_version_state_epoch", Long.class);
  private static final Field<String> WORKFLOW_ID = field("workflow_id", String.class);
  private static final Field<String> REQUEST_DIGEST = field("request_digest", String.class);
  private static final Field<byte[]> REQUEST_PREIMAGE = field("request_preimage", byte[].class);
  private static final Field<String> OPERATION_DIGEST = field("operation_digest", String.class);
  private static final Field<String> SELECTED_COMMIT_DIGEST =
      field("selected_commit_digest", String.class);
  private static final Field<String> INVENTORY_SCHEMA = field("inventory_schema", String.class);
  private static final Field<String> INVENTORY_DIGEST = field("inventory_digest", String.class);
  private static final Field<byte[]> INVENTORY_BYTES = field("inventory_bytes", byte[].class);
  private static final Field<Integer> MANIFEST_SCHEMA_VERSION =
      field("manifest_schema_version", Integer.class);
  private static final Field<String> MANIFEST_HASH = field("manifest_hash", String.class);
  private static final Field<byte[]> MANIFEST_BYTES = field("manifest_bytes", byte[].class);
  private static final Field<String> ARTIFACT_DIGESTS_JSON =
      field("artifact_digests_json", String.class);
  private static final Field<String> REQUIRED_USAGE_KEYS_JSON =
      field("required_usage_keys_json", String.class);

  private final DSLContext dsl;
  private final GameRepository gameRepository;
  private final VersionRepository versionRepository;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate ownerWrite;
  private final TransactionTemplate ownerRead;

  public VersionAssetExportCandidateServiceImpl(
      DSLContext dsl,
      GameRepository gameRepository,
      VersionRepository versionRepository,
      PlatformTransactionManager transactionManager,
      ObjectMapper objectMapper) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.gameRepository = Objects.requireNonNull(gameRepository, "gameRepository");
    this.versionRepository = Objects.requireNonNull(versionRepository, "versionRepository");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    var transactions = Objects.requireNonNull(transactionManager, "transactionManager");
    ownerWrite = new TransactionTemplate(transactions);
    ownerWrite.setName("selected-asset-export-candidate-write");
    ownerWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerWrite.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerWrite.setTimeout(15);
    ownerRead = new TransactionTemplate(transactions);
    ownerRead.setName("selected-asset-export-candidate-readback");
    ownerRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    ownerRead.setReadOnly(true);
    ownerRead.setTimeout(15);
  }

  @Override
  public void recordSelectedCandidate(
      SelectedDraftAssetInventory inventory,
      ExportedAssetManifest candidate,
      byte[] manifestBytes) {
    Objects.requireNonNull(inventory, "inventory");
    Objects.requireNonNull(candidate, "candidate").requireImmutableArtifactEvidence();
    Objects.requireNonNull(manifestBytes, "manifestBytes");
    CandidateBinding expected = binding(inventory, candidate, manifestBytes);
    ownerWrite.executeWithoutResult(ignored -> recordInOwnerTransaction(inventory, expected));
  }

  @Override
  public CandidateBinding readSelectedCandidate(PublicationDigestRequestBinding request) {
    requireCanonicalRequest(request);
    CandidateBinding result = ownerRead.execute(ignored -> readInOwnerTransaction(request));
    if (result == null) {
      throw new IllegalStateException("SELECTED_ASSET_EXPORT_CANDIDATE_NOT_FOUND");
    }
    return result;
  }

  @Override
  public void requireSelectedCandidateForFinalization(
      PublicationDigestRequestBinding request, CandidateBinding expected) {
    requireCanonicalRequest(request);
    Objects.requireNonNull(expected, "expected").manifest().requireImmutableArtifactEvidence();
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Selected candidate finalization requires caller-owned writable READ_COMMITTED transaction");
    }
    if (!request.requestDigest().equals(expected.requestDigest())
        || !Arrays.equals(request.canonicalPreimage(), expected.requestPreimage())) {
      throw new IllegalStateException(CONFLICT);
    }
    CandidateBinding retained = readInOwnerTransaction(request);
    if (!expected.equals(retained)) {
      throw new IllegalStateException(CONFLICT);
    }
  }

  private void recordInOwnerTransaction(
      SelectedDraftAssetInventory inventory, CandidateBinding expected) {
    var request = inventory.request();
    var target = inventory.selectedCommit().target();
    String tenantKey = target.gameDesignVersionTenantKey();
    long versionId = target.gameDesignVersionRowId();
    requireOwnerRows(inventory, tenantKey, versionId);

    Record retained =
        dsl.selectFrom(CANDIDATE)
            .where(TENANT_ID.eq(tenantKey).and(VERSION_ID.eq(versionId)))
            .forUpdate()
            .fetchOne();
    if (retained != null) {
      CandidateBinding actual = bindingFromRecord(retained);
      if (!expected.equals(actual)) {
        throw new IllegalStateException(CONFLICT);
      }
      return;
    }

    int inserted =
        dsl.insertInto(CANDIDATE)
            .set(TENANT_ID, tenantKey)
            .set(VERSION_ID, versionId)
            .set(CANONICAL_TENANT_ID, target.canonicalTenantId())
            .set(CANONICAL_VERSION_ID, target.canonicalVersionId())
            .set(EXPECTED_VERSION_STATE_EPOCH, expectedVersionEpoch(inventory))
            .set(WORKFLOW_ID, request.derivedWorkflowIdentity())
            .set(REQUEST_DIGEST, request.requestDigest())
            .set(REQUEST_PREIMAGE, expected.requestPreimage())
            .set(OPERATION_DIGEST, expected.operationDigest())
            .set(SELECTED_COMMIT_DIGEST, expected.selectedCommitDigest())
            .set(INVENTORY_SCHEMA, expected.inventorySchema())
            .set(INVENTORY_DIGEST, expected.inventoryDigest())
            .set(INVENTORY_BYTES, expected.inventoryBytes())
            .set(MANIFEST_SCHEMA_VERSION, expected.manifest().manifestSchemaVersion())
            .set(MANIFEST_HASH, expected.manifest().manifestHash())
            .set(MANIFEST_BYTES, expected.manifestBytes())
            .set(ARTIFACT_DIGESTS_JSON, writeJson(expected.manifest().artifactDigests()))
            .set(
                REQUIRED_USAGE_KEYS_JSON,
                writeJson(expected.manifest().requiredManifestAssetKeys()))
            .execute();
    if (inserted != 1) {
      throw new IllegalStateException(CONFLICT);
    }
    Record readback =
        dsl.selectFrom(CANDIDATE)
            .where(TENANT_ID.eq(tenantKey).and(VERSION_ID.eq(versionId)))
            .fetchOne();
    if (readback == null || !expected.equals(bindingFromRecord(readback))) {
      throw new IllegalStateException("SELECTED_ASSET_EXPORT_CANDIDATE_WRITE_READBACK_MISMATCH");
    }
  }

  private void requireOwnerRows(
      SelectedDraftAssetInventory inventory, String tenantKey, long versionId) {
    var target = inventory.selectedCommit().target();
    Game game = gameRepository.findByTenantIdForUpdate(tenantKey);
    Version version =
        versionRepository
            .findByTenantIdAndIdForUpdate(tenantKey, versionId)
            .orElseThrow(() -> new IllegalStateException(CONFLICT));
    var provenance = gameRepository.findRuntimeTenantIdentityByTenantKey(tenantKey).orElse(null);
    if (game == null
        || game.getId() == null
        || provenance == null
        || !Objects.equals(game.getId(), target.sourceGameRowId())
        || !Objects.equals(game.getTenantId(), target.sourceGameTenantKey())
        || !Objects.equals(provenance.canonicalTenantId(), target.canonicalTenantId())
        || !Objects.equals(provenance.sourceGameId(), target.sourceGameRowId())
        || !Objects.equals(provenance.sourceLegacyTenantId(), target.sourceGameTenantKey())
        || !Objects.equals(version.getId(), target.gameDesignVersionRowId())
        || !Objects.equals(version.getTenantId(), tenantKey)
        || !Objects.equals(version.getCanonicalTenantId(), target.canonicalTenantId())
        || !Objects.equals(version.getCanonicalVersionId(), target.canonicalVersionId())
        || !Objects.equals(version.getIdentitySourceGameRowId(), target.sourceGameRowId())
        || !Objects.equals(version.getIdentitySourceGameTenantKey(), target.sourceGameTenantKey())
        || !Objects.equals(version.getIdentitySourceProvenanceKind(), target.sourceProvenanceKind())
        || version.getVersionState() != VersionLifecycleState.DRAFT
        || !Objects.equals(version.getVersionStateEpoch(), expectedVersionEpoch(inventory))
        || version.getVersionNumber() <= 0) {
      throw new IllegalStateException(CONFLICT);
    }
  }

  private CandidateBinding readInOwnerTransaction(PublicationDigestRequestBinding request) {
    long versionId = Long.parseLong(request.versionId());
    UUID tenantId = UUID.fromString(request.tenantId());
    List<? extends Record> matches =
        dsl.selectFrom(CANDIDATE)
            .where(
                CANONICAL_TENANT_ID
                    .eq(tenantId)
                    .and(VERSION_ID.eq(versionId))
                    .and(REQUEST_DIGEST.eq(request.requestDigest()))
                    .and(WORKFLOW_ID.eq(request.derivedWorkflowIdentity())))
            .fetch();
    if (matches.size() != 1) {
      throw new IllegalStateException("SELECTED_ASSET_EXPORT_CANDIDATE_NOT_FOUND");
    }
    CandidateBinding binding = bindingFromRecord(matches.getFirst());
    if (!binding.requestDigest().equals(request.requestDigest())
        || !Arrays.equals(binding.requestPreimage(), request.canonicalPreimage())) {
      throw new IllegalStateException(CONFLICT);
    }
    return binding;
  }

  private CandidateBinding binding(
      SelectedDraftAssetInventory inventory, ExportedAssetManifest manifest, byte[] manifestBytes) {
    requireCanonicalRequest(inventory.request());
    byte[] inventoryBytes = inventory.canonicalBytes();
    byte[] operationBytes = inventory.operation().canonicalBytes();
    if (!sha256(inventoryBytes).equals(inventory.digest())
        || !sha256(operationBytes).equals(operationDigest(inventory))
        || !sha256(manifestBytes).equals(manifest.manifestHash())
        || !requestMatchesOperation(inventory)) {
      throw new IllegalStateException(CONFLICT);
    }
    List<PublishedArtifactDigest> expectedDigests =
        inventory.assets().stream()
            .map(
                asset ->
                    new PublishedArtifactDigest(
                        asset.usageKey(),
                        "BINARY",
                        "artifacts/sha256/" + asset.contentDigest().substring("sha256:".length()),
                        asset.contentDigest(),
                        asset.contentType(),
                        1))
            .toList();
    if (manifest.manifestSchemaVersion() != 1
        || !manifest.artifactDigests().equals(expectedDigests)
        || !manifest
            .requiredManifestAssetKeys()
            .equals(expectedDigests.stream().map(PublishedArtifactDigest::usageKey).toList())) {
      throw new IllegalStateException(CONFLICT);
    }
    var inventoryJson = objectMapper.readTree(inventoryBytes);
    requireCanonicalJson(inventoryBytes, inventoryJson);
    if (!inventory.request().requestDigest().equals(inventoryJson.path("requestDigest").textValue())
        || !Base64.getEncoder()
            .encodeToString(inventory.request().canonicalPreimage())
            .equals(inventoryJson.path("requestPreimageBase64").textValue())) {
      throw new IllegalStateException(CONFLICT);
    }
    requireManifestMatchesInventory(
        inventoryJson, inventory.digest(), manifestBytes, expectedDigests);
    return new CandidateBinding(
        inventory.request().requestDigest(),
        operationDigest(inventory),
        inventory.selectedCommit().digest(),
        SelectedDraftAssetInventory.SCHEMA,
        inventory.digest(),
        manifest,
        inventory.request().canonicalPreimage(),
        inventoryBytes,
        manifestBytes);
  }

  private CandidateBinding bindingFromRecord(Record record) {
    byte[] requestPreimage = record.get(REQUEST_PREIMAGE);
    byte[] inventoryBytes = record.get(INVENTORY_BYTES);
    byte[] manifestBytes = record.get(MANIFEST_BYTES);
    if (requestPreimage == null
        || inventoryBytes == null
        || manifestBytes == null
        || !sha256(inventoryBytes).equals(record.get(INVENTORY_DIGEST))
        || !sha256(manifestBytes).equals(record.get(MANIFEST_HASH))) {
      throw new IllegalStateException(CONFLICT);
    }
    String requestDigest = record.get(REQUEST_DIGEST);
    if (!sha256Hex(requestPreimage).equals(requestDigest)) {
      throw new IllegalStateException(CONFLICT);
    }
    var inventoryJson = objectMapper.readTree(inventoryBytes);
    if (!SelectedDraftAssetInventory.SCHEMA.equals(inventoryJson.path("schema").textValue())
        || !Base64.getEncoder()
            .encodeToString(requestPreimage)
            .equals(inventoryJson.path("requestPreimageBase64").textValue())
        || !record
            .get(OPERATION_DIGEST)
            .equals(sha256(decodeBase64(inventoryJson.path("operationBase64").textValue())))
        || !record
            .get(SELECTED_COMMIT_DIGEST)
            .equals(inventoryJson.path("selectedCommitDigest").textValue())
        || !record.get(REQUEST_DIGEST).equals(inventoryJson.path("requestDigest").textValue())) {
      throw new IllegalStateException(CONFLICT);
    }
    List<PublishedArtifactDigest> digests =
        objectMapper.readValue(
            record.get(ARTIFACT_DIGESTS_JSON),
            objectMapper
                .getTypeFactory()
                .constructCollectionType(List.class, PublishedArtifactDigest.class));
    List<String> keys =
        objectMapper.readValue(record.get(REQUIRED_USAGE_KEYS_JSON), new TypeReference<>() {});
    requireCanonicalJson(inventoryBytes, inventoryJson);
    requireManifestMatchesInventory(
        inventoryJson, record.get(INVENTORY_DIGEST), manifestBytes, digests);
    ExportedAssetManifest manifest =
        new ExportedAssetManifest(
            record.get(MANIFEST_HASH), record.get(MANIFEST_SCHEMA_VERSION), keys, digests);
    return new CandidateBinding(
        requestDigest,
        record.get(OPERATION_DIGEST),
        record.get(SELECTED_COMMIT_DIGEST),
        record.get(INVENTORY_SCHEMA),
        record.get(INVENTORY_DIGEST),
        manifest,
        requestPreimage,
        inventoryBytes,
        manifestBytes);
  }

  private static byte[] decodeBase64(String value) {
    try {
      return Base64.getDecoder().decode(value);
    } catch (RuntimeException invalid) {
      throw new IllegalStateException(CONFLICT, invalid);
    }
  }

  private void requireManifestMatchesInventory(
      tools.jackson.databind.JsonNode inventoryJson,
      String inventoryDigest,
      byte[] manifestBytes,
      List<PublishedArtifactDigest> digests) {
    try {
      var manifestJson = objectMapper.readTree(manifestBytes);
      requireCanonicalJson(manifestBytes, manifestJson);
      var sourceAssets = inventoryJson.path("assets");
      var manifestAssets = manifestJson.path("artifacts");
      if (!"game-design-selected-asset-manifest/v1".equals(manifestJson.path("schema").textValue())
          || manifestJson.path("manifestSchemaVersion").asInt(-1) != 1
          || !SelectedDraftAssetInventory.SCHEMA.equals(
              manifestJson.path("selectedInventorySchema").textValue())
          || !inventoryDigest.equals(manifestJson.path("selectedInventoryDigest").textValue())
          || !sourceAssets.isArray()
          || !manifestAssets.isArray()
          || sourceAssets.size() != digests.size()
          || manifestAssets.size() != digests.size()) {
        throw new IllegalStateException(CONFLICT);
      }
      for (int index = 0; index < digests.size(); index++) {
        var source = sourceAssets.get(index);
        var observed = manifestAssets.get(index);
        var proof = digests.get(index);
        if (!proof.usageKey().equals(source.path("usageKey").textValue())
            || !proof.usageKey().equals(observed.path("usageKey").textValue())
            || !source.path("family").textValue().equals(observed.path("family").textValue())
            || !source.path("role").textValue().equals(observed.path("role").textValue())
            || !source
                .path("requiredness")
                .textValue()
                .equals(observed.path("requiredness").textValue())
            || !"BINARY".equals(proof.artifactKind())
            || !proof.artifactKind().equals(observed.path("artifactKind").textValue())
            || !proof.immutableObjectKey().equals(observed.path("objectKey").textValue())
            || !proof.contentDigest().equals(source.path("contentDigest").textValue())
            || !proof.contentDigest().equals(observed.path("contentDigest").textValue())
            || !proof.contentType().equals(source.path("contentType").textValue())
            || !proof.contentType().equals(observed.path("contentType").textValue())
            || proof.artifactSchemaVersion() != observed.path("artifactSchemaVersion").asInt(-1)
            || !source
                .path("byteSize")
                .textValue()
                .equals(Long.toString(observed.path("byteSize").asLong(-1L)))) {
          throw new IllegalStateException(CONFLICT);
        }
      }
    } catch (RuntimeException invalid) {
      if (CONFLICT.equals(invalid.getMessage())) {
        throw invalid;
      }
      throw new IllegalStateException(CONFLICT, invalid);
    }
  }

  private void requireCanonicalJson(byte[] bytes, tools.jackson.databind.JsonNode value) {
    try {
      byte[] canonical =
          Rfc8785CanonicalJson.canonicalizeUtf8(objectMapper.writeValueAsString(value));
      if (!Arrays.equals(bytes, canonical)) {
        throw new IllegalStateException(CONFLICT);
      }
    } catch (IOException invalid) {
      throw new IllegalStateException(CONFLICT, invalid);
    } catch (RuntimeException invalid) {
      if (CONFLICT.equals(invalid.getMessage())) {
        throw invalid;
      }
      throw new IllegalStateException(CONFLICT, invalid);
    }
  }

  private static String operationDigest(SelectedDraftAssetInventory inventory) {
    return sha256(inventory.operation().canonicalBytes());
  }

  private static boolean requestMatchesOperation(SelectedDraftAssetInventory inventory) {
    var request = inventory.request();
    var operation = inventory.operation();
    var target = operation.account().input().selection().target();
    var intent = operation.account().input().selection().intent();
    return request.tenantId().equals(target.canonicalTenantId().toString())
        && request.versionId().equals(Long.toString(target.gameDesignVersionRowId()))
        && request.publishRequestId().equals(intent.publishRequestId())
        && request.derivedWorkflowIdentity().equals(operation.workflowId());
  }

  private static long expectedVersionEpoch(SelectedDraftAssetInventory inventory) {
    try {
      return Long.parseLong(
          inventory.operation().account().input().selection().intent().expectedVersionStateEpoch());
    } catch (RuntimeException invalid) {
      throw new IllegalStateException(CONFLICT, invalid);
    }
  }

  private void requireCanonicalRequest(PublicationDigestRequestBinding request) {
    if (request == null
        || request.scopeKind() != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION) {
      throw new IllegalArgumentException("Canonical full-version request required");
    }
    var canonical =
        PublicationDigestRequestBinding.full(
            request.tenantId(), request.versionId(), request.publishRequestId());
    if (!Arrays.equals(request.canonicalPreimage(), canonical.canonicalPreimage())
        || !request.requestDigest().equals(canonical.requestDigest())
        || !request.derivedWorkflowIdentity().equals(canonical.derivedWorkflowIdentity())
        || !UUID.fromString(request.tenantId()).toString().equals(request.tenantId())) {
      throw new IllegalArgumentException("Noncanonical selected publication request");
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception invalid) {
      throw new IllegalStateException(
          "SELECTED_ASSET_EXPORT_CANDIDATE_SERIALIZATION_FAILED", invalid);
    }
  }

  private static String sha256(byte[] bytes) {
    return "sha256:" + sha256Hex(bytes);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static <T> Field<T> field(String name, Class<T> type) {
    return DSL.field(DSL.name(name), type);
  }
}
