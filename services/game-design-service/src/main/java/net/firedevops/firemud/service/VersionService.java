package net.firedevops.firemud.service;

import java.util.List;
import net.firedevops.firemud.dto.VersionDto;

public interface VersionService {
  VersionDto publishVersion(Long gameId, String notes) throws Exception;

  List<VersionDto> listVersions(Long gameId);
}
