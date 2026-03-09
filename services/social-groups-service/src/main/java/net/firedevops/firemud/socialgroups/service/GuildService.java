package net.firedevops.firemud.socialgroups.service;

import net.firedevops.firemud.socialgroups.dto.AddGuildMemberRequest;
import net.firedevops.firemud.socialgroups.dto.AddGuildStorageItemRequest;
import net.firedevops.firemud.socialgroups.dto.CreateAllianceRequest;
import net.firedevops.firemud.socialgroups.dto.CreateGuildRequest;
import net.firedevops.firemud.socialgroups.dto.GuildAllianceDto;
import net.firedevops.firemud.socialgroups.dto.GuildDto;
import net.firedevops.firemud.socialgroups.dto.GuildMemberDto;
import net.firedevops.firemud.socialgroups.dto.GuildStorageItemDto;
import net.firedevops.firemud.socialgroups.dto.UpdateGuildMemberRoleRequest;

public interface GuildService {
  GuildDto createGuild(CreateGuildRequest request);

  GuildStorageItemDto addStorageItem(AddGuildStorageItemRequest request);

  GuildAllianceDto createAlliance(CreateAllianceRequest request);

  GuildMemberDto addMember(AddGuildMemberRequest request);

  GuildMemberDto updateMemberRole(UpdateGuildMemberRoleRequest request);

  void removeMember(long tenantId, long guildId, long accountId);
}
