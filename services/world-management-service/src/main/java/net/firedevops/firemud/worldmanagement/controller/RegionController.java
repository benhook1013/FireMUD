package net.firedevops.firemud.worldmanagement.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.dto.RegionDto;
import net.firedevops.firemud.worldmanagement.service.RegionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/regions")
@RequiredArgsConstructor
public class RegionController {
  private final RegionService regionService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<RegionDto>>> list(@RequestParam Long tenantId) {
    SessionContext.requireTenantAccess(tenantId);
    return ResponseEntity.ok(ApiResponse.success(regionService.listRegions(tenantId)));
  }

  @PostMapping("/{id}/move")
  public ResponseEntity<ApiResponse<RegionDto>> moveRegion(
      @PathVariable Long id, @RequestParam Long tenantId, @RequestParam Integer shardId) {
    SessionContext.requireTenantAccess(tenantId);
    RegionDto result = regionService.moveRegion(tenantId, id, shardId);
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
