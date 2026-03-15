package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.gamedesign.dto.GameAssetDto;
import org.springframework.web.multipart.MultipartFile;

public interface GameAssetService {
  GameAssetDto uploadAsset(String tenantId, MultipartFile file);
}
