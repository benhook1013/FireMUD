package net.firedevops.firemud.gamedesign.mapper;

import net.firedevops.firemud.gamedesign.dto.GameAssetDto;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GameAssetMapper {
  GameAssetDto toDto(GameAsset entity);

  GameAsset toEntity(GameAssetDto dto);
}
