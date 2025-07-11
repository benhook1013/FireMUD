package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.ProfileDto;
import net.firedevops.firemud.entity.Profile;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ProfileMapper {
  ProfileDto toDto(Profile entity);

  Profile toEntity(ProfileDto dto);
}
