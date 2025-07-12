package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.AddGuildMemberRequest;
import net.firedevops.firemud.dto.AddGuildStorageItemRequest;
import net.firedevops.firemud.dto.CreateAllianceRequest;
import net.firedevops.firemud.dto.CreateGuildRequest;
import net.firedevops.firemud.dto.GuildAllianceDto;
import net.firedevops.firemud.dto.GuildDto;
import net.firedevops.firemud.dto.GuildMemberDto;
import net.firedevops.firemud.dto.GuildStorageItemDto;
import net.firedevops.firemud.dto.UpdateGuildMemberRoleRequest;

public interface GuildService {
  GuildDto createGuild(CreateGuildRequest request);

  GuildStorageItemDto addStorageItem(AddGuildStorageItemRequest request);

  GuildAllianceDto createAlliance(CreateAllianceRequest request);

  GuildMemberDto addMember(AddGuildMemberRequest request);

  GuildMemberDto updateMemberRole(UpdateGuildMemberRoleRequest request);

  void removeMember(long tenantId, long guildId, long accountId);
}
