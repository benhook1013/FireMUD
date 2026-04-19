package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;

public interface ControlPlaneDigestService {
  DesignControlPlaneDigestDto getDigestForVersion(VersionDto version);

  DesignControlPlaneDigestDto getDigestForScriptPatch(VersionDto version);
}
