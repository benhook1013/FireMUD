package net.firedevops.firemud.accountservice.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;

class AccountMapperTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private final AccountMapper mapper = Mappers.getMapper(AccountMapper.class);

  @ParameterizedTest
  @EnumSource(AccountIdentityProvenance.class)
  void mapsCanonicalUuidForEverySupportedProvenance(AccountIdentityProvenance provenance) {
    Account account = account(42L, ACCOUNT_UUID, provenance, 42L);

    AccountDto result = mapper.toDto(account);

    assertEquals(ACCOUNT_UUID.toString(), result.id());
  }

  @Test
  void rejectsMissingUuidInsteadOfFallingBackToNumericRowId() {
    assertRejected(account(42L, null, AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT, 42L));
  }

  @Test
  void rejectsNilUuid() {
    assertRejected(
        account(42L, new UUID(0L, 0L), AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT, 42L));
  }

  @Test
  void rejectsMissingProvenance() {
    assertRejected(account(42L, ACCOUNT_UUID, null, 42L));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(longs = {0L, -1L})
  void rejectsMissingOrNonpositiveSourceRow(Long accountId) {
    assertRejected(
        account(
            accountId,
            ACCOUNT_UUID,
            AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT,
            accountId));
  }

  @Test
  void rejectsMissingSourceNumericId() {
    assertRejected(
        account(42L, ACCOUNT_UUID, AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT, null));
  }

  @Test
  void rejectsSourceNumericIdThatDoesNotMatchTheAccountRow() {
    assertRejected(
        account(42L, ACCOUNT_UUID, AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT, 43L));
  }

  private void assertRejected(Account account) {
    assertThrows(IllegalStateException.class, () -> mapper.toDto(account));
  }

  private Account account(
      Long id, UUID accountUuid, AccountIdentityProvenance provenance, Long sourceNumericId) {
    Account account = new Account();
    account.setId(id);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(provenance);
    account.setAccountUuidSourceNumericId(sourceNumericId);
    return account;
  }
}
