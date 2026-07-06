package unit.net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
