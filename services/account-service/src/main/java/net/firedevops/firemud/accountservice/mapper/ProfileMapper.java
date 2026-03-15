package net.firedevops.firemud.accountservice.mapper;

import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.entity.Profile;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ProfileMapper {
  ProfileDto toDto(Profile entity);

  Profile toEntity(ProfileDto dto);
}
