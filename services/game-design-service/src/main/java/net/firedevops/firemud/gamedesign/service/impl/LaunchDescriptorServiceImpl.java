package net.firedevops.firemud.gamedesign.service.impl;

import io.micrometer.core.annotation.Timed;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameTemplateLaunchConfigView;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class LaunchDescriptorServiceImpl implements LaunchDescriptorService {
  private static final Logger logger = LoggingUtil.getLogger(LaunchDescriptorServiceImpl.class);

  private final GameTemplateRepository gameTemplateRepository;
  private final LaunchDescriptorRepository launchDescriptorRepository;
  private final VersionRepository versionRepository;
  private final PublishedReleaseBundleService publishedReleaseBundleService;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional
  @Timed(value = "gamedesign.launchDescriptor.resolve")
  public ResolvedLaunchDescriptorDto resolveLaunchDescriptor(
      String tenantId,
      long gameTemplateId,
      String controlPlaneRequestId,
      String requestedScriptPatchVersion,
      Long sourceVersionId,
      Long targetVersionId,
      String requestedRuntimeFlagsJson) {
    if (controlPlaneRequestId == null || controlPlaneRequestId.isBlank()) {
      throw new IllegalArgumentException(
          "INVALID_TEMPLATE_CONFIGURATION: controlPlaneRequestId is required");
    }
    GameTemplateLaunchConfigView template =
        gameTemplateRepository
            .findLaunchConfigByTenantIdAndId(tenantId, gameTemplateId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "INVALID_TEMPLATE_CONFIGURATION: game template not found"));
    String requestHash =
        hashRequest(
            tenantId,
            gameTemplateId,
            controlPlaneRequestId,
            requestedScriptPatchVersion,
            sourceVersionId,
            targetVersionId,
            requestedRuntimeFlagsJson);
    var existing =
        launchDescriptorRepository.findByTenantIdAndGameTemplateIdAndControlPlaneRequestId(
            tenantId, gameTemplateId, controlPlaneRequestId);
    if (existing.isPresent()) {
      if (!existing.get().getRequestHash().equals(requestHash)) {
        throw new IllegalArgumentException(
            "INVALID_TEMPLATE_CONFIGURATION: controlPlaneRequestId already resolved with different inputs");
      }
      return toDto(existing.get());
    }
    if (template.getTemplateReferencePhase() != TemplateReferencePhase.ENFORCED) {
      throw new IllegalArgumentException(
          "TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED: template reference phase is not enforced");
    }
    Long resolvedVersionId =
        targetVersionId != null ? targetVersionId : template.getDefaultVersionId();
    if (resolvedVersionId == null || resolvedVersionId <= 0L) {
      throw new IllegalArgumentException(
          "INVALID_TEMPLATE_CONFIGURATION: template defaultVersionId is required");
    }
    VersionDto version =
        versionRepository
            .findById(resolvedVersionId)
            .filter(found -> found.getTenantId().equals(tenantId))
            .map(
                found ->
                    new VersionDto(
                        found.getId(),
                        found.getTenantId(),
                        found.getVersionNumber(),
                        found.getVersionState(),
                        found.getVersionStateEpoch(),
                        found.getScriptPatchVersion(),
                        found.getBaseVersionId(),
                        found.isScriptOnly(),
                        found.getNotes(),
                        found.getCreatedAt(),
                        found.getUpdatedAt()))
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "INVALID_TEMPLATE_CONFIGURATION: target version not found"));
    if (version.versionState() != VersionLifecycleState.PUBLISHED
        && version.versionState() != VersionLifecycleState.ACTIVE) {
      throw new IllegalArgumentException(
          "VERSION_STATE_EPOCH_STALE: resolved version is not activation-eligible");
    }
    String resolvedScriptPatchVersion =
        resolveScriptPatchVersion(template, requestedScriptPatchVersion, version);
    String resolvedRuntimeFlagsJson = resolveRuntimeFlagsJson(template, requestedRuntimeFlagsJson);
    if (sourceVersionId != null && !sourceVersionId.equals(resolvedVersionId)) {
      throw new IllegalArgumentException(
          "LAUNCH_REMAP_REQUIRED: replacement-instance launch requires an approved remapSetId");
    }
    var bundle = requirePublishedReleaseBundle(tenantId, resolvedVersionId);
    PublishedReleaseBundleContract.requireSupportedSchemaForLaunch(bundle);
    LaunchDescriptor descriptor = new LaunchDescriptor();
    descriptor.setLaunchDescriptorId("ld-" + UUID.randomUUID());
    descriptor.setTenantId(tenantId);
    descriptor.setGameTemplateId(gameTemplateId);
    descriptor.setControlPlaneRequestId(controlPlaneRequestId);
    descriptor.setRequestHash(requestHash);
    descriptor.setVersionId(resolvedVersionId);
    descriptor.setScriptPatchVersion(resolvedScriptPatchVersion);
    descriptor.setRuntimeFlagsJson(resolvedRuntimeFlagsJson);
    descriptor.setGenerationConfigRevision(bundle.generationConfigRevision());
    descriptor.setVersionStateEpoch(version.versionStateEpoch());
    descriptor.setReleaseBundleId(bundle.id());
    descriptor.setPublishedReleaseBundleRef(
        releaseBundleRef(tenantId, bundle.id(), resolvedVersionId));
    logger.info(
        "Resolved launch descriptor tenant={} template={} controlPlaneRequestId={} version={}",
        tenantId,
        gameTemplateId,
        controlPlaneRequestId,
        resolvedVersionId);
    return toDto(launchDescriptorRepository.save(descriptor));
  }

  private String resolveScriptPatchVersion(
      GameTemplateLaunchConfigView template,
      String requestedScriptPatchVersion,
      VersionDto version) {
    String templateDefault = normalizeBlank(template.getDefaultScriptPatchVersion());
    String requested = normalizeBlank(requestedScriptPatchVersion);
    if (templateDefault != null && requested != null && !templateDefault.equals(requested)) {
      throw new IllegalArgumentException(
          "SCRIPT_PATCH_OVERRIDE_CONFLICT: requested script patch conflicts with template default");
    }
    String resolved = requested != null ? requested : templateDefault;
    if (resolved != null
        && version.scriptPatchVersion() != null
        && !version.scriptPatchVersion().isBlank()
        && !version.scriptPatchVersion().equals(resolved)) {
      throw new IllegalArgumentException(
          "SCRIPT_PATCH_NOT_READY: requested script patch is not published for the resolved version");
    }
    return resolved;
  }

  private String resolveRuntimeFlagsJson(
      GameTemplateLaunchConfigView template, String requestedRuntimeFlagsJson) {
    String templateFlags =
        normalizeBlank(template.getDefaultRuntimeFlagsJson()) == null
            ? "{}"
            : template.getDefaultRuntimeFlagsJson();
    String requested = normalizeBlank(requestedRuntimeFlagsJson);
    if (requested == null) {
      return templateFlags;
    }
    if (!"{}".equals(templateFlags)) {
      throw new IllegalArgumentException(
          "INVALID_TEMPLATE_CONFIGURATION: template-owned runtime flags cannot be overridden");
    }
    return requested;
  }

  private net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto
      requirePublishedReleaseBundle(String tenantId, long resolvedVersionId) {
    try {
      return publishedReleaseBundleService.getPublishedReleaseBundle(tenantId, resolvedVersionId);
    } catch (PublishedReleaseBundleNotFoundException ex) {
      throw new IllegalArgumentException(
          "RELEASE_BUNDLE_NOT_FOUND: no published release bundle for the resolved version");
    }
  }

  private ResolvedLaunchDescriptorDto toDto(LaunchDescriptor descriptor) {
    return new ResolvedLaunchDescriptorDto(
        descriptor.getLaunchDescriptorId(),
        descriptor.getTenantId(),
        descriptor.getGameTemplateId(),
        descriptor.getControlPlaneRequestId(),
        descriptor.getVersionId(),
        descriptor.getScriptPatchVersion(),
        descriptor.getRuntimeFlagsJson(),
        descriptor.getGenerationConfigRevision(),
        descriptor.getVersionStateEpoch(),
        descriptor.getReleaseBundleId(),
        descriptor.getPublishedReleaseBundleRef());
  }

  private String hashRequest(
      String tenantId,
      long gameTemplateId,
      String controlPlaneRequestId,
      String requestedScriptPatchVersion,
      Long sourceVersionId,
      Long targetVersionId,
      String requestedRuntimeFlagsJson) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("tenantId", tenantId);
    request.put("gameTemplateId", gameTemplateId);
    request.put("controlPlaneRequestId", controlPlaneRequestId);
    request.put("requestedScriptPatchVersion", normalizeBlank(requestedScriptPatchVersion));
    request.put("sourceVersionId", sourceVersionId);
    request.put("targetVersionId", targetVersionId);
    request.put("requestedRuntimeFlagsJson", normalizeBlank(requestedRuntimeFlagsJson));
    try {
      return sha256(objectMapper.writeValueAsString(request));
    } catch (StreamReadException | tools.jackson.databind.DatabindException ex) {
      throw new IllegalStateException("failed to hash launch descriptor request", ex);
    }
  }

  private String releaseBundleRef(String tenantId, long bundleId, long versionId) {
    return "prb:" + tenantId + ":" + versionId + ":" + bundleId;
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("sha-256 unavailable", ex);
    }
  }

  private String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
