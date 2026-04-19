package net.firedevops.firemud.gamesession.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.Serializable;

public record GameInstanceDto(
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
    @NotNull @Size(max = 20) String status)
    implements Serializable {
  private static final long serialVersionUID = 1L;
}
