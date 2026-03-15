package net.firedevops.firemud.gamedesign.service;

import java.util.List;
import net.firedevops.firemud.gamedesign.dto.VersionDto;

public interface VersionService {
  VersionDto publishVersion(String tenantId, String notes) throws Exception;

  VersionDto publishScriptPatchVersion(
      String tenantId, Long baseVersionId, String scriptPatchVersion, String notes)
      throws Exception;

  List<VersionDto> listVersions(String tenantId);
}
