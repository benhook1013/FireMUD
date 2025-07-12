package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.GuildMemberDto;
import net.firedevops.firemud.entity.GuildMember;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface GuildMemberMapper {
  GuildMemberDto toDto(GuildMember entity);

  GuildMember toEntity(GuildMemberDto dto);
}
