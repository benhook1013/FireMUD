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

  @Test
  void requirePositiveLongFromBoxedValueRejectsNullAndNonPositiveValues() {
    assertEquals(7L, RequestIdValidation.requirePositiveLong(Long.valueOf(7L), "tenantId"));

    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestIdValidation.requirePositiveLong((Long) null, "tenantId"));
    assertEquals("tenantId is required", missing.getMessage());

    IllegalArgumentException nonPositive =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestIdValidation.requirePositiveLong(Long.valueOf(0L), "tenantId"));
    assertEquals("tenantId must be positive", nonPositive.getMessage());
  }

  @Test
  void requirePositiveIntRejectsOversizedValues() {
    assertEquals(7, RequestIdValidation.requirePositiveInt("7", "ordinal"));

    IllegalArgumentException oversized =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestIdValidation.requirePositiveInt("2147483648", "ordinal"));
    assertEquals("ordinal must fit in a 32-bit integer", oversized.getMessage());
  }

  @Test
  void requireCanonicalRuntimeRoomIdRejectsLegacyAndMalformedShapes() {
    assertEquals("R-1021", RequestIdValidation.requireCanonicalRuntimeRoomId("R-1021", "roomId"));
    assertEquals(1021L, RequestIdValidation.requireCanonicalRuntimeRoomRowId("R-1021", "roomId"));

    IllegalArgumentException blank =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestIdValidation.requireCanonicalRuntimeRoomId("   ", "roomId"));
    assertEquals("roomId must be specified", blank.getMessage());

    IllegalArgumentException legacy =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestIdValidation.requireCanonicalRuntimeRoomId("room-1021", "roomId"));
    assertEquals("roomId must be a runtime room id like R-1021", legacy.getMessage());
  }
}
