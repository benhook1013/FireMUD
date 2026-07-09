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
  public ResponseEntity<ApiResponse<List<RegionDto>>> list(@RequestParam String tenantId) {
    long parsedTenantId = WorldManagementRequestReaders.requireTenantId(tenantId);
    SessionContext.requireTenantAccess(parsedTenantId);
    return ResponseEntity.ok(ApiResponse.success(regionService.listRegions(parsedTenantId)));
  }

  @PostMapping("/{id}/move")
  public ResponseEntity<ApiResponse<RegionDto>> moveRegion(
      @PathVariable String id, @RequestParam String tenantId, @RequestParam String shardId) {
    long parsedTenantId = WorldManagementRequestReaders.requireTenantId(tenantId);
    long parsedRegionId = WorldManagementRequestReaders.requireRegionId(id);
    int parsedShardId = WorldManagementRequestReaders.requireShardId(shardId);
    SessionContext.requireTenantAccess(parsedTenantId);
    RegionDto result = regionService.moveRegion(parsedTenantId, parsedRegionId, parsedShardId);
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
