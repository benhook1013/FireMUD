package net.firedevops.firemud.gamesession.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class GameInstanceRepositoryTest {
  @Test
  void scriptPinMutationsRejectBlankControlPlaneRequestIdBeforeDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    GameInstanceRepository repository = new GameInstanceRepository(dsl);

    for (String controlPlaneRequestId : new String[] {null, " "}) {
      IllegalArgumentException applyError =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  repository.applyScriptPin(
                      1L,
                      7L,
                      "SET",
                      "patch-new",
                      controlPlaneRequestId,
                      "operator",
                      "pin",
                      "EXPECT_EPOCH",
                      1L));
      assertEquals("control_plane_request_id is required", applyError.getMessage());

      IllegalArgumentException failureError =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  repository.recordScriptPinFailure(
                      1L,
                      7L,
                      "SET",
                      "patch-new",
                      controlPlaneRequestId,
                      "operator",
                      "authority unavailable",
                      "EXPECT_EPOCH",
                      1L,
                      "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE"));
      assertEquals("control_plane_request_id is required", failureError.getMessage());
    }

    verifyNoInteractions(dsl);
  }
}
