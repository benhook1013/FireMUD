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
   * <p>The script patch argument is intentionally discarded: a patch without its positive epoch and
   * owner request ID is not a valid pinned state, so this constructor normalizes the tuple to
   * unpinned rather than forwarding partial authority.
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
        null,
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
}
