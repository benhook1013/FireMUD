package net.firedevops.firemud.gamesession.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class GameInstanceRepositoryOperationKindValidationTest {
  @Test
  void scriptPinMutationsRejectMissingOrUnsupportedOperationKindBeforeDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    GameInstanceRepository repository = new GameInstanceRepository(dsl);

    for (String operationKind : new String[] {null, " ", "UNKNOWN"}) {
      String expectedMessage =
          operationKind == null || operationKind.isBlank()
              ? "operation_kind is required"
              : "operation_kind is not supported";
      IllegalArgumentException applyError =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  repository.applyScriptPin(
                      1L,
                      7L,
                      operationKind,
                      "patch-new",
                      "request-1",
                      "operator",
                      "pin",
                      "EXPECT_EPOCH",
                      1L));
      assertEquals(expectedMessage, applyError.getMessage());

      IllegalArgumentException failureError =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  repository.recordScriptPinFailure(
                      1L,
                      7L,
                      operationKind,
                      "patch-new",
                      "request-1",
                      "operator",
                      "authority unavailable",
                      "EXPECT_EPOCH",
                      1L,
                      "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE"));
      assertEquals(expectedMessage, failureError.getMessage());
    }

    verifyNoInteractions(dsl);
  }
}
