package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.AddGuildStorageItemRequest;
import net.firedevops.firemud.dto.CreateAllianceRequest;
import net.firedevops.firemud.dto.GuildAllianceDto;
import net.firedevops.firemud.dto.GuildStorageItemDto;

public interface GuildService {
  GuildStorageItemDto addStorageItem(AddGuildStorageItemRequest request);

  GuildAllianceDto createAlliance(CreateAllianceRequest request);
}
