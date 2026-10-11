package net.firedevops.firemud.gamedesign.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.service.GameAssetService;
import net.firedevops.firemud.gamedesign.service.MutationOwnerProofUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

class AssetControllerTest {
  @Test
  void uploadReturnsStructuredOwnerProofRefusal() {
    GameAssetService assetService = mock(GameAssetService.class);
    MultipartFile file = mock(MultipartFile.class);
    when(assetService.uploadAsset("1", file))
        .thenThrow(
            new MutationOwnerProofUnavailableException(
                "CREATOR_MUTATION_OWNER_PROOF_UNAVAILABLE",
                "Account commit-bound authority is unavailable for asset upload"));
    AssetController controller = new AssetController(assetService);

    try (MockedStatic<SessionContext> ignored = Mockito.mockStatic(SessionContext.class)) {
      var response = controller.upload("1", file);

      assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
      assertEquals("CREATOR_MUTATION_OWNER_PROOF_UNAVAILABLE", response.getBody().error().code());
    }

    verify(assetService).uploadAsset("1", file);
  }
}
