package unit.net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.firedevops.firemud.common.security.RequestIdValidation;
import org.junit.jupiter.api.Test;

class RequestIdValidationTest {
  @Test
  void requirePositiveLongParsesPositiveValuesAndRejectsInvalidInput() {
    assertEquals(7L, RequestIdValidation.requirePositiveLong("7", "tenantId"));

    IllegalArgumentException nonNumeric =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestIdValidation.requirePositiveLong("abc", "tenantId"));
    assertEquals("tenantId must be numeric", nonNumeric.getMessage());

    IllegalArgumentException nonPositive =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestIdValidation.requirePositiveLong("0", "tenantId"));
    assertEquals("tenantId must be positive", nonPositive.getMessage());
  }

  @Test
  void parseOptionalPositiveLongTreatsBlankAsAbsentAndRejectsMalformedInput() {
    assertNull(RequestIdValidation.parseOptionalPositiveLong(null, "recipientId"));
    assertNull(RequestIdValidation.parseOptionalPositiveLong("   ", "recipientId"));
    assertEquals(7L, RequestIdValidation.parseOptionalPositiveLong("7", "recipientId"));

    IllegalArgumentException malformed =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestIdValidation.parseOptionalPositiveLong("abc", "recipientId"));
    assertEquals("recipientId must be numeric", malformed.getMessage());
  }
}
