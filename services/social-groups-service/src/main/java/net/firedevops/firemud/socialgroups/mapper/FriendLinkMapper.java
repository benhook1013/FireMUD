package net.firedevops.firemud.socialgroups.mapper;

import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.entity.FriendLink;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FriendLinkMapper {
  FriendLinkDto toDto(FriendLink entity);

  FriendLink toEntity(FriendLinkDto dto);
}
