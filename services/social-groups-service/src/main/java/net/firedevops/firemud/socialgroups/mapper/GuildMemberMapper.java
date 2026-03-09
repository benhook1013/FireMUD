package net.firedevops.firemud.socialgroups.mapper;

import net.firedevops.firemud.socialgroups.dto.GuildMemberDto;
import net.firedevops.firemud.socialgroups.entity.GuildMember;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface GuildMemberMapper {
  GuildMemberDto toDto(GuildMember entity);

  GuildMember toEntity(GuildMemberDto dto);
}
