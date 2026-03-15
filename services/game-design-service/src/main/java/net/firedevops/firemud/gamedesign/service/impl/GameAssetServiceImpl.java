package net.firedevops.firemud.gamedesign.service.impl;

import io.micrometer.core.annotation.Timed;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamedesign.dto.GameAssetDto;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.mapper.GameAssetMapper;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.service.GameAssetService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class GameAssetServiceImpl implements GameAssetService {
  private static final Logger logger = LoggingUtil.getLogger(GameAssetServiceImpl.class);

  private final GameAssetRepository repository;
  private final GameAssetMapper mapper;

  @Override
  @Transactional
  @Timed(value = "gamedesign.asset.upload")
  public GameAssetDto uploadAsset(String tenantId, MultipartFile file) {
    logger.info("Uploading asset {}", file.getOriginalFilename());
    GameAsset entity = new GameAsset();
    entity.setTenantId(tenantId);
    entity.setFileName(file.getOriginalFilename());
    entity.setContentType(file.getContentType());
    try {
      entity.setData(file.getBytes());
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to read file", e);
    }
    entity = repository.save(entity);
    return mapper.toDto(entity);
  }
}
