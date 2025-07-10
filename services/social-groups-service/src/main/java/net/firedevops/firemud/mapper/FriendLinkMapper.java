package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.FriendLinkDto;
import net.firedevops.firemud.entity.FriendLink;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FriendLinkMapper {
  FriendLinkDto toDto(FriendLink entity);

  FriendLink toEntity(FriendLinkDto dto);
}
