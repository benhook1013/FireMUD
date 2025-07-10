package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.GameAssetDto;
import org.springframework.web.multipart.MultipartFile;

public interface GameAssetService {
  GameAssetDto uploadAsset(Long tenantId, MultipartFile file);
}
