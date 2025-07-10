package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.MailMessageDto;
import net.firedevops.firemud.entity.MailMessage;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MailMessageMapper {
  MailMessageDto toDto(MailMessage entity);

  MailMessage toEntity(MailMessageDto dto);
}
