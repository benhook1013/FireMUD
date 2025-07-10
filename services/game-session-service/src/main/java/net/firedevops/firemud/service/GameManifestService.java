package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.GameManifestDto;

public interface GameManifestService {
  GameManifestDto createManifest(GameManifestDto dto);
}
