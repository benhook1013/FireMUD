package net.firedevops.firemud.service;

import java.util.List;
import net.firedevops.firemud.dto.VersionDto;

public interface VersionService {
  VersionDto publishVersion(Long tenantId, String notes) throws Exception;

  VersionDto publishScriptPatchVersion(
      Long tenantId, Long baseVersionId, String scriptPatchVersion, String notes) throws Exception;

  List<VersionDto> listVersions(Long tenantId);
}
