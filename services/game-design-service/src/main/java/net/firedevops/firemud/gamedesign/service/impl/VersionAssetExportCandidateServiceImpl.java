package net.firedevops.firemud.gamedesign.service.impl;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.AssetSelection;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService;
import net.firedevops.firemud.gamedesign.service.VersionAssetPublicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Commits exact candidate evidence before the exporter is allowed to write any object bytes. */
@Service
public final class VersionAssetExportCandidateServiceImpl
    implements VersionAssetExportCandidateService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String CANDIDATE_CONFLICT = "ASSET_EXPORT_CANDIDATE_CONFLICT";
  private static final String CANDIDATE_NOT_FOUND = "ASSET_EXPORT_CANDIDATE_NOT_FOUND";
  private static final String MANIFEST_OBJECT_ROOT = "manifests/sha256/";
  private static final String ARTIFACT_OBJECT_ROOT = "artifacts/sha256/";
  private static final String MANIFEST_USAGE_KEY = "manifest.json";
  private static final String BINARY_ARTIFACT_KIND = "BINARY";
  private final VersionRepository versionRepository;
  private final VersionAssetArtifactRepository artifactRepository;
  private final VersionAssetPublicationService publicationService;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate ownerWrite;
  private final TransactionTemplate ownerRead;

  public VersionAssetExportCandidateServiceImpl(
      VersionRepository versionRepository,
      VersionAssetArtifactRepository artifactRepository,
      VersionAssetPublicationService publicationService,
      PlatformTransactionManager transactionManager,
      ObjectMapper objectMapper) {
    this.versionRepository = Objects.requireNonNull(versionRepository, "versionRepository");
    this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
    this.publicationService = Objects.requireNonNull(publicationService, "publicationService");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    PlatformTransactionManager requiredTransactionManager =
        Objects.requireNonNull(transactionManager, "transactionManager");

    ownerWrite = new TransactionTemplate(requiredTransactionManager);
    ownerWrite.setName("version-asset-export-candidate-write");
    ownerWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerWrite.setTimeout(15);

    ownerRead = new TransactionTemplate(requiredTransactionManager);
    ownerRead.setName("version-asset-export-candidate-readback");
    ownerRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    ownerRead.setReadOnly(true);
    ownerRead.setTimeout(15);
  }

  @Override
  public ExportedAssetManifest recordExportCandidate(
      String tenantId, int versionNumber, ExportedAssetManifest candidate) {
    requireScope(tenantId, versionNumber);
    Objects.requireNonNull(candidate, "candidate");
    ExportedAssetManifest committed =
        ownerWrite.execute(status -> recordInOwnerTransaction(tenantId, versionNumber, candidate));
    if (committed == null) {
      throw new IllegalStateException("Export candidate transaction returned no proof");
    }
    // TransactionTemplate returns only after commit. The caller performs its own independent
    // readback before it is permitted to write any object-store bytes.
    return committed;
  }

  @Override
  public ExportedAssetManifest readExportCandidate(String tenantId, int versionNumber) {
    requireScope(tenantId, versionNumber);
    ExportedAssetManifest result =
        ownerRead.execute(status -> readInOwnerTransaction(tenantId, versionNumber));
    if (result == null) {
      throw new IllegalStateException("Independent export candidate readback returned no proof");
    }
    return result;
  }

  private ExportedAssetManifest recordInOwnerTransaction(
      String tenantId, int versionNumber, ExportedAssetManifest requested) {
    Version initiallyFound =
        versionRepository
            .findByTenantIdAndVersionNumber(tenantId, versionNumber)
            .orElseThrow(() -> new IllegalStateException(CANDIDATE_NOT_FOUND));
    if (initiallyFound.getId() == null || initiallyFound.getId() <= 0) {
      throw new IllegalStateException(CANDIDATE_CONFLICT);
    }
    Version version =
        versionRepository
            .findByTenantIdAndIdForUpdate(tenantId, initiallyFound.getId())
            .orElseThrow(() -> new IllegalStateException(CANDIDATE_NOT_FOUND));
    validateVersion(version, tenantId, versionNumber);

    VersionAssetArtifact artifact =
        artifactRepository
            .findByTenantIdAndVersionIdForUpdate(tenantId, version.getId())
            .orElseThrow(() -> new IllegalStateException(CANDIDATE_NOT_FOUND));
    validateArtifactScope(artifact, tenantId, version);

    ExportSnapshot snapshot = readAndValidateSnapshot(tenantId, versionNumber, version);
    CandidateEvidence requestedEvidence = candidateEvidence(requested, snapshot);
    if (hasAnyCandidateEvidence(artifact)) {
      CandidateEvidence storedEvidence = storedEvidence(artifact, version, snapshot);
      if (!sameCandidate(requested, storedEvidence.manifest())
          || !sameEvidence(requestedEvidence, storedEvidence)) {
        throw new IllegalStateException(CANDIDATE_CONFLICT);
      }
      return storedEvidence.manifest();
    }

    if (version.getVersionState() != VersionLifecycleState.DRAFT
        || version.getVersionStateEpoch() == null
        || version.getVersionStateEpoch() != snapshot.capturedVersionStateEpoch()
        || artifact.getArtifactState() != VersionAssetArtifactState.STAGED
        || artifact.getStateEpoch() <= 0
        || artifact.getLastWorkflowId() == null
        || artifact.getLastWorkflowId().isBlank()
        || artifact.getExportedVersionNumber() != versionNumber
        || artifact.getManifestHash() != null
        || !isEmptyKeysJson(artifact.getExportedManifestAssetKeysJson())) {
      throw new IllegalStateException(CANDIDATE_CONFLICT);
    }

    long nextEpoch;
    try {
      nextEpoch = Math.addExact(artifact.getStateEpoch(), 1L);
    } catch (ArithmeticException exception) {
      throw new IllegalStateException(CANDIDATE_CONFLICT, exception);
    }
    artifact.setManifestHash(requested.manifestHash());
    artifact.setManifestSchemaVersion(requested.manifestSchemaVersion());
    artifact.setArtifactDigestsJson(serialize(requested.artifactDigests()));
    artifact.setPublishedObjectProofsJson(serialize(requestedEvidence.objectProofs()));
    artifact.setCandidateSnapshotVersionId(snapshot.versionId());
    artifact.setExportedManifestAssetKeysJson(serialize(requested.requiredManifestAssetKeys()));
    artifact.setStateEpoch(nextEpoch);

    VersionAssetArtifact saved = artifactRepository.save(artifact);
    CandidateEvidence readback = storedEvidence(saved, version, snapshot);
    if (!sameEvidence(requestedEvidence, readback)
        || !sameCandidate(requested, readback.manifest())) {
      throw new IllegalStateException(CANDIDATE_CONFLICT);
    }
    return readback.manifest();
  }

  private ExportedAssetManifest readInOwnerTransaction(String tenantId, int versionNumber) {
    Version version =
        versionRepository
            .findByTenantIdAndVersionNumber(tenantId, versionNumber)
            .orElseThrow(() -> new IllegalStateException(CANDIDATE_NOT_FOUND));
    validateVersion(version, tenantId, versionNumber);
    VersionAssetArtifact artifact =
        artifactRepository
            .findByTenantIdAndVersionId(tenantId, version.getId())
            .orElseThrow(() -> new IllegalStateException(CANDIDATE_NOT_FOUND));
    validateArtifactScope(artifact, tenantId, version);
    ExportSnapshot snapshot = readAndValidateSnapshot(tenantId, versionNumber, version);
    if (!hasAnyCandidateEvidence(artifact)) {
      throw new IllegalStateException(CANDIDATE_NOT_FOUND);
    }
    return storedEvidence(artifact, version, snapshot).manifest();
  }

  private ExportSnapshot readAndValidateSnapshot(
      String tenantId, int versionNumber, Version version) {
    ExportSnapshot snapshot = publicationService.readFrozenSnapshot(tenantId, versionNumber);
    if (snapshot == null
        || !Objects.equals(snapshot.tenantId(), tenantId)
        || snapshot.versionId() != version.getId()
        || snapshot.versionNumber() != versionNumber
        || snapshot.capturedVersionStateEpoch() <= 0
        || version.getVersionStateEpoch() == null
        || version.getVersionStateEpoch() < snapshot.capturedVersionStateEpoch()
        || !Objects.equals(snapshot.canonicalTenantId(), version.getCanonicalTenantId())
        || !Objects.equals(snapshot.canonicalVersionId(), version.getCanonicalVersionId())
        || snapshot.items() == null) {
      throw new IllegalStateException(CANDIDATE_CONFLICT);
    }
    return snapshot;
  }

  private CandidateEvidence storedEvidence(
      VersionAssetArtifact artifact, Version version, ExportSnapshot snapshot) {
    if (artifact.getManifestSchemaVersion() == null
        || artifact.getManifestSchemaVersion() != 1
        || artifact.getArtifactDigestsJson() == null
        || artifact.getPublishedObjectProofsJson() == null
        || artifact.getCandidateSnapshotVersionId() == null
        || !Objects.equals(artifact.getCandidateSnapshotVersionId(), version.getId())
        || artifact.getManifestHash() == null
        || artifact.getExportedVersionNumber() != snapshot.versionNumber()
        || artifact.getLastWorkflowId() == null
        || artifact.getLastWorkflowId().isBlank()
        || artifact.getStateEpoch() <= 0) {
      throw new IllegalStateException(CANDIDATE_CONFLICT);
    }

    try {
      List<String> keys =
          objectMapper.readValue(
              requireJson(artifact.getExportedManifestAssetKeysJson()),
              objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
      List<PublishedArtifactDigest> digests =
          objectMapper.readValue(
              artifact.getArtifactDigestsJson(),
              objectMapper
                  .getTypeFactory()
                  .constructCollectionType(List.class, PublishedArtifactDigest.class));
      List<ObjectProof> proofs =
          objectMapper.readValue(
              artifact.getPublishedObjectProofsJson(),
              objectMapper.getTypeFactory().constructCollectionType(List.class, ObjectProof.class));
      ExportedAssetManifest manifest =
          new ExportedAssetManifest(
              artifact.getManifestHash(), artifact.getManifestSchemaVersion(), keys, digests);
      CandidateEvidence verified = candidateEvidence(manifest, snapshot);
      if (!Objects.equals(artifact.getArtifactDigestsJson(), serialize(digests))
          || !Objects.equals(artifact.getPublishedObjectProofsJson(), serialize(proofs))
          || !Objects.equals(artifact.getExportedManifestAssetKeysJson(), serialize(keys))
          || !Objects.equals(proofs, verified.objectProofs())) {
        throw new IllegalStateException(CANDIDATE_CONFLICT);
      }
      return verified;
    } catch (RuntimeException exception) {
      if (CANDIDATE_CONFLICT.equals(exception.getMessage())) {
        throw exception;
      }
      throw new IllegalStateException(CANDIDATE_CONFLICT, exception);
    }
  }

  private CandidateEvidence candidateEvidence(
      ExportedAssetManifest manifest, ExportSnapshot snapshot) {
    if (manifest.manifestSchemaVersion() != 1
        || manifest.manifestHash() == null
        || !manifest.manifestHash().matches("sha256:[0-9a-f]{64}")
        || !Objects.equals(
            manifest.requiredManifestAssetKeys(),
            snapshot.items().stream().map(AssetSelection::usageKey).toList())
        || manifest.artifactDigests().size() != snapshot.items().size()) {
      throw new IllegalStateException(CANDIDATE_CONFLICT);
    }

    Set<String> usageKeys = new HashSet<>();
    Set<Long> assetIds = new HashSet<>();
    LinkedHashMap<String, ObjectProof> proofsByKey = new LinkedHashMap<>();
    for (int index = 0; index < snapshot.items().size(); index++) {
      AssetSelection source = snapshot.items().get(index);
      PublishedArtifactDigest digest = manifest.artifactDigests().get(index);
      if (source == null
          || digest == null
          || source.usageKey() == null
          || source.usageKey().isBlank()
          || !isCanonicalText(source.usageKey())
          || MANIFEST_USAGE_KEY.equals(source.usageKey())
          || source.assetId() <= 0
          || source.bytes() == null
          || source.contentType() == null
          || source.contentType().isBlank()
          || !source.contentType().equals(source.contentType().trim())
          || source.contentType().contains("\r")
          || source.contentType().contains("\n")
          || !isCanonicalText(source.contentType())
          || source.contentDigest() == null
          || !source.contentDigest().matches("sha256:[0-9a-f]{64}")
          || !source.contentDigest().equals(digest(source.bytes()))
          || !usageKeys.add(source.usageKey())
          || !assetIds.add(source.assetId())
          || !Objects.equals(digest.usageKey(), source.usageKey())
          || !Objects.equals(digest.artifactKind(), BINARY_ARTIFACT_KIND)
          || digest.artifactSchemaVersion() != 1
          || !Objects.equals(digest.contentType(), source.contentType())
          || !Objects.equals(digest.contentDigest(), source.contentDigest())
          || !Objects.equals(
              digest.immutableObjectKey(),
              ARTIFACT_OBJECT_ROOT + source.contentDigest().substring(7))) {
        throw new IllegalStateException(CANDIDATE_CONFLICT);
      }
      putProof(proofsByKey, digest.immutableObjectKey(), digest.contentDigest());
    }

    String manifestDigest = manifest.manifestHash();
    putProof(
        proofsByKey,
        MANIFEST_OBJECT_ROOT + manifestDigest.substring("sha256:".length()),
        manifestDigest);
    List<ObjectProof> objectProofs =
        proofsByKey.values().stream()
            .sorted(Comparator.comparing(ObjectProof::immutableObjectKey))
            .toList();
    return new CandidateEvidence(manifest, objectProofs);
  }

  private void putProof(
      LinkedHashMap<String, ObjectProof> proofsByKey, String objectKey, String contentDigest) {
    ObjectProof proof = new ObjectProof(objectKey, contentDigest);
    ObjectProof existing = proofsByKey.putIfAbsent(objectKey, proof);
    if (existing != null && !existing.equals(proof)) {
      throw new IllegalStateException(CANDIDATE_CONFLICT);
    }
  }

  private void validateVersion(Version version, String tenantId, int versionNumber) {
    if (version.getId() == null
        || version.getId() <= 0
        || !Objects.equals(version.getTenantId(), tenantId)
        || version.getVersionNumber() != versionNumber
        || version.getVersionState() == null
        || !(version.getVersionState() == VersionLifecycleState.DRAFT
            || version.getVersionState() == VersionLifecycleState.PUBLISHED
            || version.getVersionState() == VersionLifecycleState.ACTIVE)
        || version.getVersionStateEpoch() == null
        || version.getVersionStateEpoch() <= 0
        || !isCanonicalUuid(version.getCanonicalTenantId())
        || !isCanonicalUuid(version.getCanonicalVersionId())
        || version.getIdentitySourceGameRowId() == null
        || version.getIdentitySourceGameRowId() <= 0
        || !Objects.equals(version.getIdentitySourceGameTenantKey(), tenantId)
        || !("NEW_GAME_ROW".equals(version.getIdentitySourceProvenanceKind())
            || "RETAINED_GAME_V30".equals(version.getIdentitySourceProvenanceKind()))) {
      throw new IllegalStateException(CANDIDATE_CONFLICT);
    }
  }

  private void validateArtifactScope(
      VersionAssetArtifact artifact, String tenantId, Version version) {
    if (artifact.getId() == null
        || artifact.getId() <= 0
        || !Objects.equals(artifact.getTenantId(), tenantId)
        || !Objects.equals(artifact.getVersionId(), version.getId())
        || artifact.getArtifactState() == null
        || !(artifact.getArtifactState() == VersionAssetArtifactState.STAGED
            || artifact.getArtifactState() == VersionAssetArtifactState.EXPORTED_UNATTESTED
            || artifact.getArtifactState() == VersionAssetArtifactState.PUBLISHED)
        || artifact.getStateEpoch() <= 0) {
      throw new IllegalStateException(CANDIDATE_CONFLICT);
    }
  }

  private boolean hasAnyCandidateEvidence(VersionAssetArtifact artifact) {
    return artifact.getManifestSchemaVersion() != null
        || artifact.getArtifactDigestsJson() != null
        || artifact.getPublishedObjectProofsJson() != null
        || artifact.getCandidateSnapshotVersionId() != null;
  }

  private boolean isEmptyKeysJson(String json) {
    try {
      List<String> keys =
          objectMapper.readValue(
              requireJson(json),
              objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
      return keys.isEmpty() && Objects.equals(json, serialize(keys));
    } catch (RuntimeException exception) {
      throw new IllegalStateException(CANDIDATE_CONFLICT, exception);
    }
  }

  private static boolean sameCandidate(ExportedAssetManifest left, ExportedAssetManifest right) {
    return Objects.equals(left, right);
  }

  private static boolean sameEvidence(CandidateEvidence left, CandidateEvidence right) {
    return sameCandidate(left.manifest(), right.manifest())
        && Objects.equals(left.objectProofs(), right.objectProofs());
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (RuntimeException exception) {
      throw new IllegalStateException(CANDIDATE_CONFLICT, exception);
    }
  }

  private static String requireJson(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalStateException(CANDIDATE_CONFLICT);
    }
    return json;
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static boolean isCanonicalUuid(UUID value) {
    return value != null && !NIL_UUID.equals(value);
  }

  private static boolean isCanonicalText(String value) {
    if (value == null || value.codePoints().anyMatch(Character::isISOControl)) {
      return false;
    }
    try {
      StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
      return true;
    } catch (CharacterCodingException exception) {
      return false;
    }
  }

  private static void requireScope(String tenantId, int versionNumber) {
    if (tenantId == null || tenantId.isBlank() || versionNumber <= 0) {
      throw new IllegalArgumentException("Exact tenant and Version number are required");
    }
  }

  /** Exact immutable key/content-digest proof; candidate rows use camel-case record JSON fields. */
  private record ObjectProof(String immutableObjectKey, String contentDigest) {}

  private record CandidateEvidence(ExportedAssetManifest manifest, List<ObjectProof> objectProofs) {
    private CandidateEvidence {
      objectProofs = List.copyOf(objectProofs);
    }
  }
}
