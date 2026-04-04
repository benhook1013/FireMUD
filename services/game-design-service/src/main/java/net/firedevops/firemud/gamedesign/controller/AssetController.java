package net.firedevops.firemud.gamedesign.controller;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.dto.GameAssetDto;
import net.firedevops.firemud.gamedesign.service.GameAssetService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
public class AssetController {
  private final GameAssetService assetService;

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<GameAssetDto>> upload(
      @RequestParam String tenantId, @RequestParam("file") @NotNull MultipartFile file) {
    SessionContext.requireTenantAccess(Long.valueOf(tenantId));
    GameAssetDto dto = assetService.uploadAsset(tenantId, file);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }
}
