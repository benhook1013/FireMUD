package net.firedevops.firemud.loggingadmin.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import java.util.List;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.AdmissionPointerDto;
import net.firedevops.firemud.loggingadmin.dto.ExecutePreparedVersionCutoverRequest;
import net.firedevops.firemud.loggingadmin.dto.GameInstanceRuntimeStateDto;
import net.firedevops.firemud.loggingadmin.dto.InstanceCutoverCompatibilityDto;
import net.firedevops.firemud.loggingadmin.dto.PrepareVersionUpgradeRequest;
import net.firedevops.firemud.loggingadmin.dto.PreparedVersionUpgradeDto;
import net.firedevops.firemud.loggingadmin.dto.SetAdmissionPointerRequest;
import net.firedevops.firemud.loggingadmin.service.AdmissionPointerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
      @PathVariable long tenantId, @PathVariable String worldSlug, @PathVariable String realmSlug) {
    SessionContext.requireTenantAccess(tenantId);
    return ResponseEntity.ok(
        ApiResponse.success(
            admissionPointerService.listPointerAudit(tenantId, worldSlug, realmSlug)));
  }

  @GetMapping("/runtime-state/{tenantId}/{gameInstanceId}")
  @Timed(
      value = "getAdmissionPointerRuntimeState",
      description = "Read canonical current runtime state and current admission pointers")
  public ResponseEntity<ApiResponse<GameInstanceRuntimeStateDto>> getRuntimeState(
      @PathVariable long tenantId, @PathVariable long gameInstanceId) {
    SessionContext.requireTenantAccess(tenantId);
    return ResponseEntity.ok(
        ApiResponse.success(admissionPointerService.getRuntimeState(tenantId, gameInstanceId)));
  }

  @PostMapping
  @Timed(value = "setAdmissionPointer", description = "Update gameplay admission pointer")
  public ResponseEntity<ApiResponse<AdmissionPointerDto>> setPointer(
      @Valid @RequestBody SetAdmissionPointerRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    return ResponseEntity.ok(ApiResponse.success(admissionPointerService.setPointer(request)));
  }

  @PostMapping("/cutover")
  @Timed(
      value = "executePreparedVersionCutover",
      description = "Execute a prepared version cutover for one admission pointer")
  public ResponseEntity<ApiResponse<AdmissionPointerDto>> executePreparedVersionCutover(
      @Valid @RequestBody ExecutePreparedVersionCutoverRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    return ResponseEntity.ok(
        ApiResponse.success(admissionPointerService.executePreparedVersionCutover(request)));
  }

  @PostMapping("/version-upgrades")
  @Timed(
      value = "prepareVersionUpgrade",
      description = "Persist a prepared version upgrade compatibility proof")
  public ResponseEntity<ApiResponse<PreparedVersionUpgradeDto>> prepareVersionUpgrade(
      @Valid @RequestBody PrepareVersionUpgradeRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    return ResponseEntity.ok(
        ApiResponse.success(admissionPointerService.prepareVersionUpgrade(request)));
  }

  @GetMapping("/version-upgrades/{tenantId}/{preparationId}")
  @Timed(
      value = "getPreparedVersionUpgrade",
      description = "Read a prepared version upgrade compatibility proof")
  public ResponseEntity<ApiResponse<PreparedVersionUpgradeDto>> getPreparedVersionUpgrade(
      @PathVariable long tenantId, @PathVariable String preparationId) {
    SessionContext.requireTenantAccess(tenantId);
    return ResponseEntity.ok(
        ApiResponse.success(
            admissionPointerService.getPreparedVersionUpgrade(tenantId, preparationId)));
  }

  @GetMapping("/version-upgrades/{tenantId}/{sourceGameInstanceId}/compatibility/{targetVersionId}")
  @Timed(
      value = "validateInstanceCutoverCompatibility",
      description =
          "Read bounded version cutover compatibility for one source instance and target version")
  public ResponseEntity<ApiResponse<InstanceCutoverCompatibilityDto>>
      validateInstanceCutoverCompatibility(
          @PathVariable long tenantId,
          @PathVariable long sourceGameInstanceId,
          @PathVariable long targetVersionId) {
    SessionContext.requireTenantAccess(tenantId);
    return ResponseEntity.ok(
        ApiResponse.success(
            admissionPointerService.validateInstanceCutoverCompatibility(
                tenantId, sourceGameInstanceId, targetVersionId)));
  }
}
