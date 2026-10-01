package net.firedevops.firemud.accountservice.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountRequestReadersTest {
  private static final String ACCOUNT_UUID = "550e8400-e29b-41d4-a716-446655440000";

  @Test
  void readsCanonicalNonNilAccountUuid() {
    assertEquals(
        UUID.fromString(ACCOUNT_UUID), AccountRequestReaders.requireAccountUuid(ACCOUNT_UUID));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "42",
        "0",
        "00000000-0000-0000-0000-000000000000",
        "550E8400-E29B-41D4-A716-446655440000",
        "not-a-uuid"
      })
  void rejectsAccountIdsThatAreNotCanonicalNonNilUuids(String value) {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> AccountRequestReaders.requireAccountUuid(value));

    assertEquals("accountId must be a canonical non-nil UUID", exception.getMessage());
  }
}
