package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.ValidateEntityUpgradeMappingsResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.dto.CutoverParticipantCompatibilityDto;
import net.firedevops.firemud.gamesession.dto.InstanceCutoverCompatibilityDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.InstanceCutoverCompatibilityService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.worldmanagement.v1.ValidateWorldUpgradeMappingsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstanceCutoverCompatibilityServiceImpl
    implements InstanceCutoverCompatibilityService {
  private final GameInstanceRepository gameInstanceRepository;
  private final GameDesignClient gameDesignClient;
  private final WorldManagementClient worldManagementClient;
  private final EntityManagementClient entityManagementClient;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected gRPC clients and repositories are framework-managed")
  public InstanceCutoverCompatibilityServiceImpl(
      GameInstanceRepository gameInstanceRepository,
      GameDesignClient gameDesignClient,
      WorldManagementClient worldManagementClient,
      EntityManagementClient entityManagementClient) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameDesignClient = gameDesignClient;
    this.worldManagementClient = worldManagementClient;
    this.entityManagementClient = entityManagementClient;
  }

  @Override
  @Transactional(readOnly = true)
  public InstanceCutoverCompatibilityDto validateInstanceCutoverCompatibility(
      long tenantId, long sourceGameInstanceId, long targetVersionId) {
    GameInstance sourceInstance = requireSourceInstance(tenantId, sourceGameInstanceId);
    long sourceVersionId = requireSourceVersionId(sourceInstance);
    long gameTemplateId = requireGameTemplateId(sourceInstance);
    List<String> reasons = new ArrayList<>();
    List<String> checkedParticipants = new ArrayList<>();
    List<CutoverParticipantCompatibilityDto> participantResults = new ArrayList<>();

    ResolveLaunchDescriptorResponse descriptor =
        resolveLaunchDescriptor(tenantId, gameTemplateId, sourceVersionId, targetVersionId);
    if (descriptor.hasError()) {
      String reason = errorSummary(descriptor.getError());
      return new InstanceCutoverCompatibilityDto(
          sourceVersionId,
          targetVersionId,
          null,
          "INCOMPATIBLE",
          List.of(reason),
          List.of("GAME_DESIGN"),
          Instant.now(),
          null,
          List.of(
              new CutoverParticipantCompatibilityDto(
                  "GAME_DESIGN",
                  List.of(),
                  List.of("launch_descriptor"),
                  false,
                  "INCOMPATIBLE",
                  List.of(reason))));
    }

    String remapSetId = normalizeBlank(descriptor.getLaunchDescriptor().getRemapSetId());
    checkedParticipants.add("GAME_DESIGN");
    participantResults.add(validateGameDesignParticipant(tenantId, targetVersionId, reasons));

    ValidateWorldUpgradeMappingsResponse worldValidation =
        worldManagementClient.validateWorldUpgradeMappings(
            tenantId, sourceGameInstanceId, targetVersionId, remapSetId);
    checkedParticipants.add("WORLD");
    participantResults.add(
        new CutoverParticipantCompatibilityDto(
            "WORLD",
            worldValidation.getStateClassesCheckedList(),
            worldValidation.getCheckedFamiliesList(),
            worldValidation.getHasS2Rows(),
            translateWorldResult(worldValidation),
            collectReasons(worldValidation.getReasonsList(), worldValidation.getError())));
    reasons.addAll(collectReasons(worldValidation.getReasonsList(), worldValidation.getError()));

    ValidateEntityUpgradeMappingsResponse entityValidation =
        entityManagementClient.validateEntityUpgradeMappings(
            tenantId, sourceGameInstanceId, targetVersionId, remapSetId);
    checkedParticipants.add("ENTITY");
    participantResults.add(
        new CutoverParticipantCompatibilityDto(
            "ENTITY",
            entityValidation.getStateClassesCheckedList(),
            entityValidation.getCheckedFamiliesList(),
            entityValidation.getHasS2Rows(),
            translateEntityResult(entityValidation),
            collectReasons(entityValidation.getReasonsList(), entityValidation.getError())));
    reasons.addAll(collectReasons(entityValidation.getReasonsList(), entityValidation.getError()));

    return new InstanceCutoverCompatibilityDto(
        sourceVersionId,
        targetVersionId,
        descriptor.getLaunchDescriptor().getLaunchDescriptorId(),
        summarizeOverallResult(participantResults),
        reasons,
        checkedParticipants,
        Instant.now(),
        remapSetId,
        participantResults);
  }

  private GameInstance requireSourceInstance(long tenantId, long sourceGameInstanceId) {
    GameInstance instance =
        gameInstanceRepository
            .findById(sourceGameInstanceId)
            .orElseThrow(() -> new IllegalArgumentException("Source game instance not found"));
    if (!Long.valueOf(tenantId).equals(instance.getTenantId())) {
      throw new IllegalArgumentException("Source game instance not found");
    }
    return instance;
  }

  private long requireSourceVersionId(GameInstance sourceInstance) {
    if (sourceInstance.getVersionId() != null) {
      return sourceInstance.getVersionId();
    }
    if (sourceInstance.getRuntimeVersion() != null
        && !sourceInstance.getRuntimeVersion().isBlank()) {
      try {
        return Long.parseLong(sourceInstance.getRuntimeVersion());
      } catch (NumberFormatException ignored) {
        // Fall through to canonical error below.
      }
    }
    throw new IllegalArgumentException("Source game instance version metadata missing");
  }

  private long requireGameTemplateId(GameInstance sourceInstance) {
    if (sourceInstance.getGameTemplateId() == null) {
      throw new IllegalArgumentException("Source game instance gameTemplateId missing");
    }
    return sourceInstance.getGameTemplateId();
  }

  private ResolveLaunchDescriptorResponse resolveLaunchDescriptor(
      long tenantId, long gameTemplateId, long sourceVersionId, long targetVersionId) {
    try {
      return gameDesignClient.resolveLaunchDescriptor(
          tenantId,
          gameTemplateId,
          "cutover-compatibility-" + UUID.randomUUID(),
          sourceVersionId,
          targetVersionId);
    } catch (StatusRuntimeException ex) {
      throw new IllegalStateException("GAME_DESIGN_UNAVAILABLE: launch descriptor unavailable", ex);
    }
  }

  private CutoverParticipantCompatibilityDto validateGameDesignParticipant(
      long tenantId, long targetVersionId, List<String> reasons) {
    List<String> participantReasons = new ArrayList<>();
    try {
      GetVersionStateResponse versionState =
          gameDesignClient.getVersionState(tenantId, targetVersionId);
      if (versionState.hasError()) {
        participantReasons.add(errorSummary(versionState.getError()));
      } else if (versionState.getVersionState().getVersionState()
              != net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                  .VERSION_LIFECYCLE_STATE_PUBLISHED
          && versionState.getVersionState().getVersionState()
              != net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                  .VERSION_LIFECYCLE_STATE_ACTIVE) {
        participantReasons.add(
            "VERSION_STATE_INVALID: target version must be PUBLISHED or ACTIVE for cutover preflight");
      }
      GetPublishedReleaseBundleResponse releaseBundle =
          gameDesignClient.getPublishedReleaseBundle(tenantId, targetVersionId);
      if (releaseBundle.hasError()) {
        participantReasons.add(errorSummary(releaseBundle.getError()));
      }
    } catch (StatusRuntimeException ex) {
      participantReasons.add("GAME_DESIGN_UNAVAILABLE: release attestation unavailable");
      reasons.addAll(participantReasons);
      return new CutoverParticipantCompatibilityDto(
          "GAME_DESIGN",
          List.of(),
          List.of("version_state", "published_release_bundle", "launch_descriptor"),
          false,
          "UNAVAILABLE",
          participantReasons);
    }
    reasons.addAll(participantReasons);
    return new CutoverParticipantCompatibilityDto(
        "GAME_DESIGN",
        List.of(),
        List.of("version_state", "published_release_bundle", "launch_descriptor"),
        false,
        participantReasons.isEmpty() ? "COMPATIBLE" : "INCOMPATIBLE",
        participantReasons);
  }

  private List<String> collectReasons(List<String> reasons, ErrorDetail error) {
    List<String> allReasons = new ArrayList<>(reasons);
    if (error != null && !error.getCode().isBlank()) {
      allReasons.add(errorSummary(error));
    }
    return allReasons;
  }

  private String translateWorldResult(ValidateWorldUpgradeMappingsResponse response) {
    if (response.hasError()) {
      return "UNAVAILABLE";
    }
    return switch (response.getResult()) {
      case UPGRADE_VALIDATION_RESULT_COMPATIBLE -> "COMPATIBLE";
      case UPGRADE_VALIDATION_RESULT_REQUIRES_MAPPING, UPGRADE_VALIDATION_RESULT_INCOMPATIBLE ->
          "INCOMPATIBLE";
      case UPGRADE_VALIDATION_RESULT_UNAVAILABLE,
          UPGRADE_VALIDATION_RESULT_UNSPECIFIED,
          UNRECOGNIZED ->
          "UNAVAILABLE";
    };
  }

  private String translateEntityResult(ValidateEntityUpgradeMappingsResponse response) {
    if (response.hasError()) {
      return "UNAVAILABLE";
    }
    return switch (response.getResult()) {
      case UPGRADE_VALIDATION_RESULT_COMPATIBLE -> "COMPATIBLE";
      case UPGRADE_VALIDATION_RESULT_REQUIRES_MAPPING, UPGRADE_VALIDATION_RESULT_INCOMPATIBLE ->
          "INCOMPATIBLE";
      case UPGRADE_VALIDATION_RESULT_UNAVAILABLE,
          UPGRADE_VALIDATION_RESULT_UNSPECIFIED,
          UNRECOGNIZED ->
          "UNAVAILABLE";
    };
  }

  private String summarizeOverallResult(
      List<CutoverParticipantCompatibilityDto> participantResults) {
    boolean unavailable =
        participantResults.stream().anyMatch(result -> "UNAVAILABLE".equals(result.result()));
    if (unavailable) {
      return "UNAVAILABLE";
    }
    boolean incompatible =
        participantResults.stream().anyMatch(result -> "INCOMPATIBLE".equals(result.result()));
    return incompatible ? "INCOMPATIBLE" : "COMPATIBLE";
  }

  private String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private String errorSummary(ErrorDetail error) {
    return error.getCode() + ": " + error.getMessage();
  }
}
