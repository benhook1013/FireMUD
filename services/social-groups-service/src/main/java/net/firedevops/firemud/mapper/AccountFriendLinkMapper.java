package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.AccountFriendLinkDto;
import net.firedevops.firemud.entity.AccountFriendLink;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** MapStruct mapper for account-level friend links. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AccountFriendLinkMapper {
  AccountFriendLinkDto toDto(AccountFriendLink entity);

  AccountFriendLink toEntity(AccountFriendLinkDto dto);
}
