package net.firedevops.firemud.accountservice.mapper;

import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.entity.Profile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ProfileMapper {
  @Mapping(target = "accountId", source = "accountUuid")
  ProfileDto toDto(Profile entity, String accountUuid);
}
