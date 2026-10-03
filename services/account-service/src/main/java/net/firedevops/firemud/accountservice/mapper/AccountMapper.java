package net.firedevops.firemud.accountservice.mapper;

import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.entity.Account;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AccountMapper {
  @Mapping(target = "id", source = "accountUuid")
  AccountDto toDto(Account entity);

  @AfterMapping
  default void requireExactPersistedIdentity(Account entity, @MappingTarget AccountDto accountDto) {
    if (entity == null) {
      return;
    }
    if (entity.getId() == null
        || entity.getId() <= 0L
        || entity.getAccountUuid() == null
        || new java.util.UUID(0L, 0L).equals(entity.getAccountUuid())
        || entity.getAccountUuidProvenance() == null
        || entity.getAccountUuidSourceNumericId() == null
        || !entity.getId().equals(entity.getAccountUuidSourceNumericId())
        || !entity.getAccountUuid().toString().equals(accountDto.id())) {
      throw new IllegalStateException(
          "Account UUID readback did not match its exact persisted source row");
    }
  }
}
