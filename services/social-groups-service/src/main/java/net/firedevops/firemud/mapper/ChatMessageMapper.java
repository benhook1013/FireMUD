package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.ChatMessageDto;
import net.firedevops.firemud.entity.ChatMessage;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ChatMessageMapper {
  ChatMessageDto toDto(ChatMessage entity);

  ChatMessage toEntity(ChatMessageDto dto);
}
