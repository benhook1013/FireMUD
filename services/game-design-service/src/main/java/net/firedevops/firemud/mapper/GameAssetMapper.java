package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.GameAssetDto;
import net.firedevops.firemud.entity.GameAsset;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GameAssetMapper {
  GameAssetDto toDto(GameAsset entity);

  GameAsset toEntity(GameAssetDto dto);
}
