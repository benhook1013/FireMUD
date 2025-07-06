package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.AccountDto;
import net.firedevops.firemud.entity.Account;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AccountMapper {
  AccountDto toDto(Account entity);

  Account toEntity(AccountDto dto);
}
