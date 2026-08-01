package net.firedevops.firemud.loggingadmin.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.List;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.loggingadmin.dto.AdmissionPointerDto;
import net.firedevops.firemud.loggingadmin.dto.GameInstanceRuntimeStateDto;
import net.firedevops.firemud.loggingadmin.dto.InstanceCutoverCompatibilityDto;
import net.firedevops.firemud.loggingadmin.dto.PreparedVersionUpgradeDto;
import net.firedevops.firemud.loggingadmin.service.AdmissionPointerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admission-pointers")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed service dependency is stored and not exposed")
public class AdmissionPointerController {
  private final AdmissionPointerService admissionPointerService;

  public AdmissionPointerController(AdmissionPointerService admissionPointerService) {
    this.admissionPointerService = admissionPointerService;
  }

  @GetMapping
  @Timed(
      value = "listAdmissionPointers",
      description = "List accessible gameplay admission pointers")
  public ResponseEntity<ApiResponse<List<AdmissionPointerDto>>> listPointers() {
    return ResponseEntity.ok(ApiResponse.success(admissionPointerService.listPointers()));
  }

  @GetMapping("/{tenantId}/{worldSlug}/{realmSlug}/audit")
  @Timed(
      value = "listAdmissionPointerAudit",
      description = "List audit history for one admission pointer")
  public ResponseEntity<ApiResponse<List<AdmissionPointerDto>>> listAudit(
      @PathVariable String tenantId,
      @PathVariable String worldSlug,
      @PathVariable String realmSlug) {
    return LoggingAdminRequestReaders.withBadRequest(
        () -> {
          long parsedTenantId = LoggingAdminRequestReaders.requireTenantAccess(tenantId);
          return ResponseEntity.ok(
              ApiResponse.success(
                  admissionPointerService.listPointerAudit(parsedTenantId, worldSlug, realmSlug)));
        });
  }

  @GetMapping("/runtime-state/{tenantId}/{gameInstanceId}")
  @Timed(
      value = "getAdmissionPointerRuntimeState",
      description = "Read canonical current runtime state and current admission pointers")
  public ResponseEntity<ApiResponse<GameInstanceRuntimeStateDto>> getRuntimeState(
      @PathVariable String tenantId, @PathVariable String gameInstanceId) {
    return LoggingAdminRequestReaders.withBadRequest(
        () -> {
          long parsedTenantId = LoggingAdminRequestReaders.requireTenantAccess(tenantId);
          long parsedGameInstanceId =
              LoggingAdminRequestReaders.requirePositiveLong(gameInstanceId, "gameInstanceId");
          return ResponseEntity.ok(
              ApiResponse.success(
                  admissionPointerService.getRuntimeState(parsedTenantId, parsedGameInstanceId)));
        });
  }

  @GetMapping("/version-upgrades/{tenantId}/{preparationId}")
  @Timed(
      value = "getPreparedVersionUpgrade",
      description = "Read a prepared version upgrade compatibility proof")
  public ResponseEntity<ApiResponse<PreparedVersionUpgradeDto>> getPreparedVersionUpgrade(
      @PathVariable String tenantId, @PathVariable String preparationId) {
    return LoggingAdminRequestReaders.withBadRequest(
        () -> {
          long parsedTenantId = LoggingAdminRequestReaders.requireTenantAccess(tenantId);
          return ResponseEntity.ok(
              ApiResponse.success(
                  admissionPointerService.getPreparedVersionUpgrade(
                      parsedTenantId, preparationId)));
        });
  }

  @GetMapping("/version-upgrades/{tenantId}/{sourceGameInstanceId}/compatibility/{targetVersionId}")
  @Timed(
      value = "validateInstanceCutoverCompatibility",
      description =
          "Read bounded version cutover compatibility for one source instance and target version")
  public ResponseEntity<ApiResponse<InstanceCutoverCompatibilityDto>>
      validateInstanceCutoverCompatibility(
          @PathVariable String tenantId,
          @PathVariable String sourceGameInstanceId,
          @PathVariable String targetVersionId) {
    return LoggingAdminRequestReaders.withBadRequest(
        () -> {
          long parsedTenantId = LoggingAdminRequestReaders.requireTenantAccess(tenantId);
          long parsedSourceGameInstanceId =
              LoggingAdminRequestReaders.requirePositiveLong(
                  sourceGameInstanceId, "sourceGameInstanceId");
          long parsedTargetVersionId =
              LoggingAdminRequestReaders.requirePositiveLong(targetVersionId, "targetVersionId");
          return ResponseEntity.ok(
              ApiResponse.success(
                  admissionPointerService.validateInstanceCutoverCompatibility(
                      parsedTenantId, parsedSourceGameInstanceId, parsedTargetVersionId)));
        });
  }
}
