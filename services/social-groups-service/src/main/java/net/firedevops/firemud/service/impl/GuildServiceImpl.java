package net.firedevops.firemud.service.impl;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.AddGuildStorageItemRequest;
import net.firedevops.firemud.dto.CreateAllianceRequest;
import net.firedevops.firemud.dto.GuildAllianceDto;
import net.firedevops.firemud.dto.GuildStorageItemDto;
import net.firedevops.firemud.entity.GuildAlliance;
import net.firedevops.firemud.entity.GuildStorageItem;
import net.firedevops.firemud.mapper.GuildAllianceMapper;
import net.firedevops.firemud.mapper.GuildStorageItemMapper;
import net.firedevops.firemud.repository.GuildAllianceRepository;
import net.firedevops.firemud.repository.GuildStorageItemRepository;
import net.firedevops.firemud.service.GuildService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuildServiceImpl implements GuildService {
  private static final Logger logger = LoggingUtil.getLogger(GuildServiceImpl.class);

  private final GuildStorageItemRepository storageRepo;
  private final GuildStorageItemMapper storageMapper;
  private final GuildAllianceRepository allianceRepo;
  private final GuildAllianceMapper allianceMapper;

  @Override
  @Transactional
  public GuildStorageItemDto addStorageItem(AddGuildStorageItemRequest request) {
    logger.info("Adding item {} to guild {}", request.itemName(), request.guildId());
    GuildStorageItem item = new GuildStorageItem();
    item.setTenantId(request.tenantId());
    item.setGuildId(request.guildId());
    item.setItemName(request.itemName());
    item.setQuantity(request.quantity());
    return storageMapper.toDto(storageRepo.save(item));
  }

  @Override
  @Transactional
  public GuildAllianceDto createAlliance(CreateAllianceRequest request) {
    logger.info("Creating alliance {} -> {}", request.guildId(), request.allyGuildId());
    GuildAlliance alliance = new GuildAlliance();
    alliance.setTenantId(request.tenantId());
    alliance.setGuildId(request.guildId());
    alliance.setAllyGuildId(request.allyGuildId());
    alliance.setCreatedAt(Instant.now());
    return allianceMapper.toDto(allianceRepo.save(alliance));
  }
}
