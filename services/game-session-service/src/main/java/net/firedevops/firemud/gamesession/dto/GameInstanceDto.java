package net.firedevops.firemud.gamesession.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.Serializable;

public record GameInstanceDto(
    Long id,
    @NotNull Long tenantId,
    @NotNull @Size(max = 100) String runtimeVersion,
    String scriptPatchVersion,
    Long scriptPinEpoch,
    Long gameTemplateId,
    String launchDescriptorId,
    Long versionId,
    Long releaseBundleId,
    Long versionStateEpoch,
    String generationConfigRevision,
    String remapSetId,
    @NotNull Long ownerAccountId,
    @NotNull @Size(max = 20) String status)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  /**
   * Legacy constructor for callers that do not provide a complete script-pin tuple.
   *
   * <p>A nonblank script patch is rejected because this constructor cannot carry the complete
   * pinned tuple. Callers with pinned state must use the canonical record constructor.
   */
  public GameInstanceDto(
      Long id,
      @NotNull Long tenantId,
      @NotNull @Size(max = 100) String runtimeVersion,
      String scriptPatchVersion,
      Long gameTemplateId,
      String launchDescriptorId,
      Long versionId,
      Long releaseBundleId,
      Long versionStateEpoch,
      String generationConfigRevision,
      @NotNull Long ownerAccountId,
      @NotNull @Size(max = 20) String status) {
    this(
        id,
        tenantId,
        runtimeVersion,
        rejectLegacyScriptPatch(scriptPatchVersion),
        null,
        gameTemplateId,
        launchDescriptorId,
        versionId,
        releaseBundleId,
        versionStateEpoch,
        generationConfigRevision,
        null,
        ownerAccountId,
        status);
  }

  private static String rejectLegacyScriptPatch(String scriptPatchVersion) {
    if (scriptPatchVersion != null && !scriptPatchVersion.isBlank()) {
      throw new IllegalArgumentException(
          "scriptPatchVersion requires scriptPinEpoch and script pin owner request id");
    }
    return null;
  }
}
