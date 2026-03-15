package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.GameManifestDto;

public interface GameManifestService {
  GameManifestDto createManifest(GameManifestDto dto);
}
