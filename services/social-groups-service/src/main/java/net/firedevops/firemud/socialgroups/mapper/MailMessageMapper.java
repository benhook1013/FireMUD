package net.firedevops.firemud.socialgroups.mapper;

import net.firedevops.firemud.socialgroups.dto.MailMessageDto;
import net.firedevops.firemud.socialgroups.entity.MailMessage;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MailMessageMapper {
  MailMessageDto toDto(MailMessage entity);

  MailMessage toEntity(MailMessageDto dto);
}
