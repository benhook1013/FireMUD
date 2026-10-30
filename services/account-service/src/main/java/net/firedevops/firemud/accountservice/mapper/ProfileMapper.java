package net.firedevops.firemud.accountservice.mapper;

import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.entity.Profile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ProfileMapper {
  @Mapping(target = "accountId", source = "accountUuid")
  @Mapping(
      target = "presenceVisibilityPolicy",
      expression =
          "java(net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy"
              + ".toProfileResponseValue(entity == null ? null : entity.getPresenceVisibilityPolicy()))")
  ProfileDto toProfileResponse(Profile entity, String accountUuid);
}
