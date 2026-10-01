package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AccountIdsTest {

  @Test
  void acceptsCanonicalNonNilUuid() {
    assertTrue(AccountIds.isCanonicalNonNilUuid("123e4567-e89b-12d3-a456-426614174000"));
  }

  @Test
  void rejectsNullEmptyNumericNilUppercaseAndShortFormValues() {
    assertFalse(AccountIds.isCanonicalNonNilUuid(null));
    assertFalse(AccountIds.isCanonicalNonNilUuid(""));
    assertFalse(AccountIds.isCanonicalNonNilUuid("12345"));
    assertFalse(AccountIds.isCanonicalNonNilUuid("00000000-0000-0000-0000-000000000000"));
    assertFalse(AccountIds.isCanonicalNonNilUuid("123E4567-E89B-12D3-A456-426614174000"));
    assertFalse(AccountIds.isCanonicalNonNilUuid("1-1-1-1-1"));
  }
}
