package net.firedevops.firemud.gamedesign.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapSetDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateLaunchConfigView;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class LaunchDescriptorServiceImpl implements LaunchDescriptorService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private final GameTemplateRepository gameTemplateRepository;
  private final LaunchDescriptorRepository launchDescriptorRepository;
  private final VersionRepository versionRepository;
  private final PublishedReleaseBundleService publishedReleaseBundleService;
  private final TemplateRemapSetService templateRemapSetService;
  private final GameAuthoredWorldSourceRepository authoredWorldSourceRepository;
  private final ObjectMapper objectMapper;

  @Value("${firemud.grpc.workload-namespace:}")
  private String workloadNamespace;

  @Override
  @Transactional(noRollbackFor = FrozenLaunchDescriptorDenialException.class)
  @Timed(value = "gamedesign.launchDescriptor.resolve")
  public ResolvedLaunchDescriptorDto resolveLaunchDescriptor(
      AuthoredWorldLaunchDescriptorEvidence.Request request) {
    requireConfiguredNamespace(request.targetNamespace());
    launchDescriptorRepository.lockBoundRequest(
        workloadNamespace, request.canonicalTenantId(), request.controlPlaneRequestId());
    String requestJson = writeJson(request);

    Optional<LaunchDescriptor> stored =
        launchDescriptorRepository.findBoundByRequest(
            workloadNamespace, request.canonicalTenantId(), request.controlPlaneRequestId());
    if (stored.isPresent()) {
      LaunchDescriptor descriptor = stored.orElseThrow();
      requireStoredRequestMatches(descriptor, request, requestJson);
      AuthoredWorldSourceEvidence source = readExactSource(request);
      String sourceJson = writeJson(source);
      if (LaunchDescriptor.OUTCOME_FAILED.equals(descriptor.getOutcomeStatus())) {
        throwStoredFailure(descriptor, request, source, requestJson, sourceJson);
      }
      if (!LaunchDescriptor.OUTCOME_SUCCESS.equals(descriptor.getOutcomeStatus())) {
        throw new IllegalArgumentException("LAUNCH_DESCRIPTOR_CONFLICT: stored outcome is unknown");
      }
      return readStored(descriptor, request, source, requestJson, sourceJson);
    }
    AuthoredWorldSourceEvidence source = readExactSource(request);
    String sourceJson = writeJson(source);
    if (launchDescriptorRepository
        .findByPrivateRequest(source.sourceGameTenantKey(), request.controlPlaneRequestId())
        .isPresent()) {
      throw new IllegalArgumentException(
          "LAUNCH_DESCRIPTOR_CONFLICT: retained private-owner request history is unbound or bound to another canonical identity");
    }

    try {
      return resolveNewLaunchDescriptor(request, source, requestJson, sourceJson);
    } catch (FrozenLaunchDescriptorDenialException denial) {
      LaunchDescriptor failure = frozenFailure(request, source, requestJson, sourceJson, denial);
      LaunchDescriptor persisted = launchDescriptorRepository.insertImmutable(failure);
      throwStoredFailure(persisted, request, source, requestJson, sourceJson);
      throw new IllegalStateException("Persisted deterministic launch denial was not returned");
    }
  }

  private ResolvedLaunchDescriptorDto resolveNewLaunchDescriptor(
      AuthoredWorldLaunchDescriptorEvidence.Request request,
      AuthoredWorldSourceEvidence source,
      String requestJson,
      String sourceJson) {
    GameTemplateLaunchConfigView template =
        gameTemplateRepository
            .findLaunchConfigByTenantIdAndId(source.sourceGameTenantKey(), request.gameTemplateId())
            .filter(row -> row.getId() == request.gameTemplateId())
            .filter(row -> source.sourceGameTenantKey().equals(row.getTenantId()))
            .orElseThrow(
                () ->
                    denial(
                        "INVALID_TEMPLATE_CONFIGURATION",
                        "template is not owned by the source game"));
    if (template.getTemplateReferencePhase() != TemplateReferencePhase.ENFORCED) {
      throw denial(
          "TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED", "template reference phase is not enforced");
    }
    Long versionId =
        request.targetVersionIdPresent()
            ? request.targetVersionId()
            : template.getDefaultVersionId();
    if (versionId == null || versionId <= 0) {
      throw denial("INVALID_TEMPLATE_CONFIGURATION", "template defaultVersionId is required");
    }
    VersionDto version =
        versionRepository
            .findById(versionId)
            .filter(row -> row.getId().equals(versionId))
            .filter(row -> source.sourceGameTenantKey().equals(row.getTenantId()))
            .map(
                row ->
                    new VersionDto(
                        row.getId(),
                        row.getTenantId(),
                        row.getVersionNumber(),
                        row.getVersionState(),
                        row.getVersionStateEpoch(),
                        row.getScriptPatchVersion(),
                        row.getBaseVersionId(),
                        row.isScriptOnly(),
                        row.getNotes(),
                        row.getCreatedAt(),
                        row.getUpdatedAt()))
            .orElseThrow(
                () ->
                    denial(
                        "INVALID_TEMPLATE_CONFIGURATION",
                        "target version is not owned by the source game"));
    if (version.versionState() != VersionLifecycleState.PUBLISHED
        && version.versionState() != VersionLifecycleState.ACTIVE) {
      throw denial("VERSION_STATE_EPOCH_STALE", "resolved version is not activation-eligible");
    }
    requireReadyScriptPatch(
        request.requestedScriptPatchVersionPresent()
            ? request.requestedScriptPatchVersion()
            : null);
    requireReadyScriptPatch(template.getDefaultScriptPatchVersion());
    requireReadyScriptPatch(version.scriptPatchVersion());
    String scriptPatch = resolveScriptPatchVersion(template, request, version);
    String runtimeFlags = resolveRuntimeFlagsJson(template, request);

    String remapSetId = null;
    if (request.sourceVersionIdPresent() && !request.sourceVersionId().equals(versionId)) {
      TemplateRemapSetDto remap =
          templateRemapSetService
              .findApprovedTemplateRemapSet(
                  source.sourceGameTenantKey(), request.sourceVersionId(), versionId)
              .orElseThrow(
                  () ->
                      denial(
                          "LAUNCH_REMAP_REQUIRED",
                          "replacement-instance launch requires an approved remapSetId"));
      remapSetId = remap.remapSetId();
    }
    PublishedReleaseBundleDto bundle =
        requirePublishedReleaseBundle(source.sourceGameTenantKey(), versionId);
    if (!source.sourceGameTenantKey().equals(bundle.tenantId())
        || !Objects.equals(bundle.versionId(), versionId)) {
      throw denial(
          "RELEASE_BUNDLE_NOT_FOUND", "release bundle is not owned by the resolved source version");
    }
    try {
      PublishedReleaseBundleContract.requireSupportedSchemaForLaunchDescriptor(bundle);
    } catch (IllegalArgumentException invalid) {
      if (invalid.getMessage() != null
          && invalid
              .getMessage()
              .startsWith(PublishedReleaseBundleContract.SCHEMA_VERSION_UNSUPPORTED + ":")) {
        throw denial(
            PublishedReleaseBundleContract.SCHEMA_VERSION_UNSUPPORTED,
            "unsupported published release bundle attestation schema "
                + bundle.attestationSchemaVersion());
      }
      throw invalid;
    }
    if (bundle.publishedReleaseBundleRef() == null
        || bundle.publishedReleaseBundleRef().isBlank()) {
      throw denial(
          "RELEASE_BUNDLE_NOT_FOUND", "published release bundle has no persisted opaque reference");
    }

    String descriptorId = "ld-" + UUID.randomUUID();
    AuthoredWorldLaunchDescriptorEvidence evidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            descriptorId,
            versionId,
            scriptPatch != null,
            scriptPatch,
            runtimeFlags,
            bundle.generationConfigRevision(),
            version.versionStateEpoch(),
            bundle.id(),
            bundle.publishedReleaseBundleRef(),
            remapSetId != null,
            remapSetId);
    LaunchDescriptor descriptor = new LaunchDescriptor();
    descriptor.setLaunchDescriptorId(descriptorId);
    descriptor.setTenantId(source.sourceGameTenantKey());
    descriptor.setGameTemplateId(request.gameTemplateId());
    descriptor.setControlPlaneRequestId(request.controlPlaneRequestId());
    descriptor.setRequestHash(evidence.requestDigest());
    descriptor.setVersionId(versionId);
    descriptor.setScriptPatchVersion(scriptPatch);
    descriptor.setRuntimeFlagsJson(runtimeFlags);
    descriptor.setGenerationConfigRevision(bundle.generationConfigRevision());
    descriptor.setVersionStateEpoch(version.versionStateEpoch());
    descriptor.setReleaseBundleId(bundle.id());
    descriptor.setPublishedReleaseBundleRef(evidence.publishedReleaseBundleRef());
    descriptor.setRemapSetId(remapSetId);
    descriptor.setDescriptorSchemaVersion(evidence.schemaVersion());
    descriptor.setTargetNamespace(source.targetNamespace());
    descriptor.setCanonicalTenantId(source.canonicalTenantId().toString());
    descriptor.setAuthoredWorldSourceTenantSlug(source.tenantSlug());
    descriptor.setWorldSlug(source.worldSlug());
    descriptor.setAuthoredWorldSourceOperationId(source.operationId().toString());
    descriptor.setAuthoredWorldSourceGameRowId(source.sourceGameRowId());
    descriptor.setAuthoredWorldSourceGameTenantKey(source.sourceGameTenantKey());
    descriptor.setAuthoredWorldSourceProvenanceKind(source.provenanceKind());
    descriptor.setAuthoredWorldSourceEvidenceDigest(source.evidenceDigest());
    descriptor.setRequestDigest(evidence.requestDigest());
    descriptor.setResultDigest(evidence.resultDigest());
    descriptor.setOriginalRequestJson(requestJson);
    descriptor.setSourceEvidenceJson(sourceJson);
    return readStored(
        launchDescriptorRepository.insertImmutable(descriptor),
        request,
        source,
        requestJson,
        sourceJson);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "gamedesign.launchDescriptor.read")
  public ResolvedLaunchDescriptorDto getLaunchDescriptor(
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      String controlPlaneRequestId,
      String expectedRequestDigest,
      String expectedResultDigest) {
    DescriptorReadContext context =
        findExactSuccessfulDescriptor(
            readRequestId,
            canonicalTenantId,
            worldSlug,
            controlPlaneRequestId,
            expectedRequestDigest,
            expectedResultDigest);
    LaunchDescriptor descriptor = context.descriptor();
    AuthoredWorldLaunchDescriptorEvidence.Request storedRequest = context.request();
    AuthoredWorldSourceEvidence source = readExactSource(storedRequest);
    String sourceJson = writeJson(source);
    if (!sourceJson.equals(descriptor.getSourceEvidenceJson())) {
      throw new IllegalArgumentException(
          "AUTHORED_WORLD_SOURCE_CHANGED: committed source differs from descriptor history");
    }
    return readStored(
        descriptor,
        storedRequest,
        source,
        descriptor.getOriginalRequestJson(),
        descriptor.getSourceEvidenceJson());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  @Timed(value = "gamedesign.launchDescriptor.readOwnerSnapshot")
  public ResolvedLaunchDescriptorDto getLaunchDescriptorInOwnerSnapshot(
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      String controlPlaneRequestId,
      String expectedRequestDigest,
      String expectedResultDigest) {
    requireOwnerSnapshot();
    DescriptorReadContext context =
        findExactSuccessfulDescriptor(
            readRequestId,
            canonicalTenantId,
            worldSlug,
            controlPlaneRequestId,
            expectedRequestDigest,
            expectedResultDigest);
    LaunchDescriptor descriptor = context.descriptor();
    AuthoredWorldLaunchDescriptorEvidence.Request storedRequest = context.request();
    var sourceSnapshot =
        authoredWorldSourceRepository
            .readVersionStateSnapshot(
                workloadNamespace,
                readRequestId,
                canonicalTenantId,
                worldSlug,
                storedRequest.authoredWorldSourceOperationId(),
                storedRequest.authoredWorldSourceEvidenceDigest(),
                Objects.requireNonNull(descriptor.getVersionId(), "descriptor versionId"))
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "AUTHORED_WORLD_SOURCE_NOT_FOUND: exact committed source or version is missing"));
    AuthoredWorldSourceEvidence source = sourceSnapshot.sourceEvidence();
    requireExactSource(storedRequest, source);
    String sourceJson = writeJson(source);
    if (!sourceJson.equals(descriptor.getSourceEvidenceJson())
        || !Objects.equals(source.sourceGameTenantKey(), descriptor.getTenantId())) {
      throw new IllegalArgumentException(
          "AUTHORED_WORLD_SOURCE_CHANGED: committed source differs from descriptor history");
    }
    return readStored(
        descriptor,
        storedRequest,
        source,
        descriptor.getOriginalRequestJson(),
        descriptor.getSourceEvidenceJson());
  }

  private DescriptorReadContext findExactSuccessfulDescriptor(
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      String controlPlaneRequestId,
      String expectedRequestDigest,
      String expectedResultDigest) {
    if (readRequestId == null || NIL_UUID.equals(readRequestId)) {
      throw new IllegalArgumentException("Canonical nonnil read request ID is required");
    }
    requireConfiguredNamespace(workloadNamespace);
    AuthoredWorldSourceDigest.validateReadSelector(workloadNamespace, canonicalTenantId, worldSlug);
    if (controlPlaneRequestId == null
        || controlPlaneRequestId.isBlank()
        || !GameTenantCreationDigest.isDigest(expectedRequestDigest)
        || !GameTenantCreationDigest.isDigest(expectedResultDigest)) {
      throw new IllegalArgumentException("Exact descriptor read selector and digests are required");
    }
    LaunchDescriptor descriptor =
        launchDescriptorRepository
            .findBoundByRequest(workloadNamespace, canonicalTenantId, controlPlaneRequestId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "LAUNCH_DESCRIPTOR_NOT_FOUND: no descriptor exists for the exact request"));
    if (!LaunchDescriptor.OUTCOME_SUCCESS.equals(descriptor.getOutcomeStatus())) {
      throw new IllegalArgumentException(
          "LAUNCH_DESCRIPTOR_CONFLICT: request outcome is not a successful descriptor");
    }
    if (descriptor.getDescriptorSchemaVersion() == null) {
      throw new IllegalArgumentException(
          "LAUNCH_DESCRIPTOR_UNBOUND: retained descriptor history has no authored-world binding");
    }
    if (!worldSlug.equals(descriptor.getWorldSlug())
        || !canonicalTenantId.toString().equals(descriptor.getCanonicalTenantId())
        || !expectedRequestDigest.equals(descriptor.getRequestDigest())
        || !expectedResultDigest.equals(descriptor.getResultDigest())) {
      throw new IllegalArgumentException(
          "LAUNCH_DESCRIPTOR_CONFLICT: exact readback selector or digest does not match");
    }
    AuthoredWorldLaunchDescriptorEvidence.Request storedRequest =
        readRequest(descriptor.getOriginalRequestJson());
    return new DescriptorReadContext(descriptor, storedRequest);
  }

  private AuthoredWorldSourceEvidence readExactSource(
      AuthoredWorldLaunchDescriptorEvidence.Request request) {
    AuthoredWorldSourceEvidence source =
        authoredWorldSourceRepository
            .read(
                request.authoredWorldSourceOperationId(),
                request.canonicalTenantId(),
                request.worldSlug(),
                workloadNamespace)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "AUTHORED_WORLD_SOURCE_NOT_FOUND: exact committed source evidence is missing"));
    requireExactSource(request, source);
    return source;
  }

  private void requireExactSource(
      AuthoredWorldLaunchDescriptorEvidence.Request request, AuthoredWorldSourceEvidence source) {
    if (!workloadNamespace.equals(source.targetNamespace())
        || !request.canonicalTenantId().equals(source.canonicalTenantId())
        || !request.worldSlug().equals(source.worldSlug())
        || !request.authoredWorldSourceOperationId().equals(source.operationId())
        || !request.authoredWorldSourceEvidenceDigest().equals(source.evidenceDigest())) {
      throw new IllegalArgumentException(
          "AUTHORED_WORLD_SOURCE_CHANGED: source evidence does not match the exact request");
    }
  }

  private void requireOwnerSnapshot() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Launch descriptor snapshot read requires a read-only REPEATABLE_READ owner snapshot");
    }
  }

  private record DescriptorReadContext(
      LaunchDescriptor descriptor, AuthoredWorldLaunchDescriptorEvidence.Request request) {}

  private ResolvedLaunchDescriptorDto readStored(
      LaunchDescriptor descriptor,
      AuthoredWorldLaunchDescriptorEvidence.Request request,
      AuthoredWorldSourceEvidence source,
      String requestJson,
      String sourceJson) {
    if (!matchesStoredSource(descriptor, source)
        || !LaunchDescriptor.OUTCOME_SUCCESS.equals(descriptor.getOutcomeStatus())
        || descriptor.getFailureCode() != null
        || descriptor.getFailureMessage() != null
        || descriptor.getDescriptorSchemaVersion() == null
        || !Objects.equals(
            descriptor.getDescriptorSchemaVersion(),
            AuthoredWorldLaunchDescriptorEvidence.SCHEMA_VERSION)
        || !workloadNamespace.equals(descriptor.getTargetNamespace())
        || !request.canonicalTenantId().toString().equals(descriptor.getCanonicalTenantId())
        || !request.worldSlug().equals(descriptor.getWorldSlug())
        || !request
            .authoredWorldSourceOperationId()
            .toString()
            .equals(descriptor.getAuthoredWorldSourceOperationId())
        || !request
            .authoredWorldSourceEvidenceDigest()
            .equals(descriptor.getAuthoredWorldSourceEvidenceDigest())
        || !request.controlPlaneRequestId().equals(descriptor.getControlPlaneRequestId())
        || request.gameTemplateId() != descriptor.getGameTemplateId()
        || !requestJson.equals(descriptor.getOriginalRequestJson())
        || !sourceJson.equals(descriptor.getSourceEvidenceJson())
        || !request.requestDigest().equals(descriptor.getRequestHash())
        || !request.requestDigest().equals(descriptor.getRequestDigest())) {
      throw new IllegalArgumentException(
          "LAUNCH_DESCRIPTOR_CONFLICT: stored descriptor is not the exact immutable request");
    }
    AuthoredWorldLaunchDescriptorEvidence evidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            descriptor.getLaunchDescriptorId(),
            descriptor.getVersionId(),
            descriptor.getScriptPatchVersion() != null,
            descriptor.getScriptPatchVersion(),
            descriptor.getRuntimeFlagsJson(),
            descriptor.getGenerationConfigRevision(),
            descriptor.getVersionStateEpoch(),
            descriptor.getReleaseBundleId(),
            descriptor.getPublishedReleaseBundleRef(),
            descriptor.getRemapSetId() != null,
            descriptor.getRemapSetId());
    evidence.requireValid();
    if (!evidence.resultDigest().equals(descriptor.getResultDigest())) {
      throw new IllegalArgumentException(
          "LAUNCH_DESCRIPTOR_CONFLICT: stored resolved result digest is inconsistent");
    }
    return new ResolvedLaunchDescriptorDto(
        evidence.launchDescriptorId(),
        evidence.canonicalTenantId().toString(),
        evidence.gameTemplateId(),
        evidence.controlPlaneRequestId(),
        evidence.versionId(),
        evidence.scriptPatchVersion(),
        evidence.runtimeFlagsJson(),
        evidence.generationConfigRevision(),
        evidence.versionStateEpoch(),
        evidence.releaseBundleId(),
        evidence.publishedReleaseBundleRef(),
        evidence.remapSetId(),
        evidence);
  }

  private LaunchDescriptor frozenFailure(
      AuthoredWorldLaunchDescriptorEvidence.Request request,
      AuthoredWorldSourceEvidence source,
      String requestJson,
      String sourceJson,
      FrozenLaunchDescriptorDenialException denial) {
    LaunchDescriptor descriptor = new LaunchDescriptor();
    descriptor.setTenantId(source.sourceGameTenantKey());
    descriptor.setControlPlaneRequestId(request.controlPlaneRequestId());
    descriptor.setRequestHash(request.requestDigest());
    descriptor.setDescriptorSchemaVersion(AuthoredWorldLaunchDescriptorEvidence.SCHEMA_VERSION);
    descriptor.setTargetNamespace(source.targetNamespace());
    descriptor.setCanonicalTenantId(source.canonicalTenantId().toString());
    descriptor.setAuthoredWorldSourceTenantSlug(source.tenantSlug());
    descriptor.setWorldSlug(source.worldSlug());
    descriptor.setAuthoredWorldSourceOperationId(source.operationId().toString());
    descriptor.setAuthoredWorldSourceGameRowId(source.sourceGameRowId());
    descriptor.setAuthoredWorldSourceGameTenantKey(source.sourceGameTenantKey());
    descriptor.setAuthoredWorldSourceProvenanceKind(source.provenanceKind());
    descriptor.setAuthoredWorldSourceEvidenceDigest(source.evidenceDigest());
    descriptor.setRequestDigest(request.requestDigest());
    descriptor.setOriginalRequestJson(requestJson);
    descriptor.setSourceEvidenceJson(sourceJson);
    descriptor.setOutcomeStatus(LaunchDescriptor.OUTCOME_FAILED);
    descriptor.setFailureCode(denial.failureCode());
    descriptor.setFailureMessage(denial.getMessage());
    return descriptor;
  }

  private void requireStoredRequestMatches(
      LaunchDescriptor descriptor,
      AuthoredWorldLaunchDescriptorEvidence.Request request,
      String requestJson) {
    if (!workloadNamespace.equals(descriptor.getTargetNamespace())
        || !request.canonicalTenantId().toString().equals(descriptor.getCanonicalTenantId())
        || !request.controlPlaneRequestId().equals(descriptor.getControlPlaneRequestId())
        || !request.requestDigest().equals(descriptor.getRequestDigest())
        || !request.requestDigest().equals(descriptor.getRequestHash())
        || !requestJson.equals(descriptor.getOriginalRequestJson())) {
      throw new IllegalArgumentException(
          "LAUNCH_DESCRIPTOR_CONFLICT: control-plane request identity was reused with changed input");
    }
  }

  private void throwStoredFailure(
      LaunchDescriptor descriptor,
      AuthoredWorldLaunchDescriptorEvidence.Request request,
      AuthoredWorldSourceEvidence source,
      String requestJson,
      String sourceJson) {
    if (!matchesStoredSource(descriptor, source)
        || !LaunchDescriptor.OUTCOME_FAILED.equals(descriptor.getOutcomeStatus())
        || !Objects.equals(
            descriptor.getDescriptorSchemaVersion(),
            AuthoredWorldLaunchDescriptorEvidence.SCHEMA_VERSION)
        || !workloadNamespace.equals(descriptor.getTargetNamespace())
        || !request.canonicalTenantId().toString().equals(descriptor.getCanonicalTenantId())
        || !request.worldSlug().equals(descriptor.getWorldSlug())
        || !request
            .authoredWorldSourceOperationId()
            .toString()
            .equals(descriptor.getAuthoredWorldSourceOperationId())
        || !request
            .authoredWorldSourceEvidenceDigest()
            .equals(descriptor.getAuthoredWorldSourceEvidenceDigest())
        || !request.controlPlaneRequestId().equals(descriptor.getControlPlaneRequestId())
        || !request.requestDigest().equals(descriptor.getRequestDigest())
        || !request.requestDigest().equals(descriptor.getRequestHash())
        || !requestJson.equals(descriptor.getOriginalRequestJson())
        || !sourceJson.equals(descriptor.getSourceEvidenceJson())
        || !source.sourceGameTenantKey().equals(descriptor.getTenantId())
        || descriptor.getLaunchDescriptorId() != null
        || descriptor.getGameTemplateId() != null
        || descriptor.getVersionId() != null
        || descriptor.getScriptPatchVersion() != null
        || descriptor.getRuntimeFlagsJson() != null
        || descriptor.getGenerationConfigRevision() != null
        || descriptor.getVersionStateEpoch() != null
        || descriptor.getReleaseBundleId() != null
        || descriptor.getPublishedReleaseBundleRef() != null
        || descriptor.getRemapSetId() != null
        || descriptor.getResultDigest() != null
        || !isPersistableFailureCode(descriptor.getFailureCode())
        || descriptor.getFailureMessage() == null
        || descriptor.getFailureMessage().isBlank()) {
      throw new IllegalArgumentException(
          "LAUNCH_DESCRIPTOR_CONFLICT: stored failure is not the exact immutable request outcome");
    }
    throw FrozenLaunchDescriptorDenialException.fromStored(
        descriptor.getFailureCode(), descriptor.getFailureMessage());
  }

  private boolean matchesStoredSource(
      LaunchDescriptor descriptor, AuthoredWorldSourceEvidence source) {
    return Objects.equals(descriptor.getTargetNamespace(), source.targetNamespace())
        && Objects.equals(descriptor.getCanonicalTenantId(), source.canonicalTenantId().toString())
        && Objects.equals(descriptor.getAuthoredWorldSourceTenantSlug(), source.tenantSlug())
        && Objects.equals(descriptor.getWorldSlug(), source.worldSlug())
        && Objects.equals(
            descriptor.getAuthoredWorldSourceOperationId(), source.operationId().toString())
        && Objects.equals(descriptor.getAuthoredWorldSourceGameRowId(), source.sourceGameRowId())
        && Objects.equals(
            descriptor.getAuthoredWorldSourceGameTenantKey(), source.sourceGameTenantKey())
        && Objects.equals(
            descriptor.getAuthoredWorldSourceProvenanceKind(), source.provenanceKind())
        && Objects.equals(
            descriptor.getAuthoredWorldSourceEvidenceDigest(), source.evidenceDigest());
  }

  private boolean isPersistableFailureCode(String failureCode) {
    return switch (failureCode == null ? "" : failureCode) {
      case "TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED",
          "INVALID_TEMPLATE_CONFIGURATION",
          "SCRIPT_PATCH_OVERRIDE_CONFLICT",
          "SCRIPT_PATCH_NOT_READY",
          "RELEASE_BUNDLE_NOT_FOUND",
          "RELEASE_ATTESTATION_MISMATCH",
          "VERSION_STATE_EPOCH_STALE",
          "LAUNCH_REMAP_REQUIRED",
          PublishedReleaseBundleContract.SCHEMA_VERSION_UNSUPPORTED ->
          true;
      default -> false;
    };
  }

  private static FrozenLaunchDescriptorDenialException denial(String code, String detail) {
    return FrozenLaunchDescriptorDenialException.create(code, detail);
  }

  private AuthoredWorldLaunchDescriptorEvidence.Request readRequest(String json) {
    if (json == null) {
      throw new IllegalArgumentException("LAUNCH_DESCRIPTOR_UNBOUND: original request is missing");
    }
    try {
      AuthoredWorldLaunchDescriptorEvidence.Request request =
          objectMapper.readValue(json, AuthoredWorldLaunchDescriptorEvidence.Request.class);
      if (!writeJson(request).equals(json)) {
        throw new IllegalArgumentException(
            "LAUNCH_DESCRIPTOR_UNBOUND: original request is not the closed owner encoding");
      }
      return request;
    } catch (Exception exception) {
      throw new IllegalArgumentException(
          "LAUNCH_DESCRIPTOR_UNBOUND: original request is unreadable", exception);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Launch descriptor evidence could not be serialized", exception);
    }
  }

  private void requireConfiguredNamespace(String requestNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        || !workloadNamespace.equals(requestNamespace)) {
      throw new IllegalArgumentException(
          "LAUNCH_DESCRIPTOR_NAMESPACE_MISMATCH: configured workload namespace is required");
    }
  }

  private String resolveScriptPatchVersion(
      GameTemplateLaunchConfigView template,
      AuthoredWorldLaunchDescriptorEvidence.Request request,
      VersionDto version) {
    String templateDefault = normalizeBlank(template.getDefaultScriptPatchVersion());
    String requested =
        request.requestedScriptPatchVersionPresent()
            ? normalizeBlank(request.requestedScriptPatchVersion())
            : null;
    if (templateDefault != null && requested != null && !templateDefault.equals(requested)) {
      throw denial(
          "SCRIPT_PATCH_OVERRIDE_CONFLICT",
          "requested script patch conflicts with template default");
    }
    String resolved = requested != null ? requested : templateDefault;
    if (resolved != null
        && version.scriptPatchVersion() != null
        && !version.scriptPatchVersion().isBlank()
        && !version.scriptPatchVersion().equals(resolved)) {
      throw denial(
          "SCRIPT_PATCH_NOT_READY",
          "requested script patch is not published for the resolved version");
    }
    return resolved;
  }

  private String resolveRuntimeFlagsJson(
      GameTemplateLaunchConfigView template,
      AuthoredWorldLaunchDescriptorEvidence.Request request) {
    String templateFlags =
        normalizeBlank(template.getDefaultRuntimeFlagsJson()) == null
            ? "{}"
            : template.getDefaultRuntimeFlagsJson();
    requireRuntimeFlagsObjectJson(templateFlags);
    String requested =
        request.requestedRuntimeFlagsJsonPresent() ? request.requestedRuntimeFlagsJson() : null;
    if (requested == null) {
      return templateFlags;
    }
    requireRuntimeFlagsObjectJson(requested);
    if (!"{}".equals(templateFlags)) {
      throw denial(
          "INVALID_TEMPLATE_CONFIGURATION", "template-owned runtime flags cannot be overridden");
    }
    return requested;
  }

  private void requireRuntimeFlagsObjectJson(String runtimeFlagsJson) {
    final JsonNode runtimeFlags;
    try {
      runtimeFlags =
          objectMapper
              .readerFor(JsonNode.class)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readValue(runtimeFlagsJson);
    } catch (Exception invalidJson) {
      throw denial("INVALID_TEMPLATE_CONFIGURATION", "runtime flags must be a JSON object");
    }
    if (runtimeFlags == null || !runtimeFlags.isObject()) {
      throw denial("INVALID_TEMPLATE_CONFIGURATION", "runtime flags must be a JSON object");
    }
  }

  private void requireReadyScriptPatch(String scriptPatchVersion) {
    if (scriptPatchVersion != null && !scriptPatchVersion.isBlank()) {
      throw denial(
          "SCRIPT_PATCH_NOT_READY",
          "exact published-for-base and Automation READY evidence is unavailable");
    }
  }

  private PublishedReleaseBundleDto requirePublishedReleaseBundle(String tenantId, long versionId) {
    try {
      return publishedReleaseBundleService.getPublishedReleaseBundle(tenantId, versionId);
    } catch (PublishedReleaseBundleNotFoundException exception) {
      throw denial(
          "RELEASE_BUNDLE_NOT_FOUND", "no published release bundle for the resolved version");
    }
  }

  private static String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  @Timed(value = "gamedesign.launchDescriptor.resolve")
  public ResolvedLaunchDescriptorDto resolveLaunchDescriptor(
      String tenantId,
      long gameTemplateId,
      String controlPlaneRequestId,
      String requestedScriptPatchVersion,
      Long sourceVersionId,
      Long targetVersionId,
      String requestedRuntimeFlagsJson) {
    throw new IllegalArgumentException(
        "AUTHORED_WORLD_LAUNCH_BINDING_REQUIRED: canonical authored-world source binding is"
            + " required to resolve a launch descriptor");
  }
}
