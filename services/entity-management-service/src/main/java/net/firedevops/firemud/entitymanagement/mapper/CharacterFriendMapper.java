package net.firedevops.firemud.entitymanagement.mapper;

import net.firedevops.firemud.entitymanagement.dto.CharacterFriendDto;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriend;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CharacterFriendMapper {
  @Mapping(target = "characterId", source = "id.characterId")
  @Mapping(target = "friendId", source = "id.friendId")
  @Mapping(
      target = "createdAtEpochMs",
      expression =
          "java(entity.getCreatedAt() == null ? null : entity.getCreatedAt().toEpochMilli())")
  CharacterFriendDto toDto(CharacterFriend entity);
}
