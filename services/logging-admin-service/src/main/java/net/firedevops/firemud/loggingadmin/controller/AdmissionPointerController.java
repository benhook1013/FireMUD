package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import java.util.List;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.AdmissionPointerDto;
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
@RequestMapping("/admin/admission-pointers")
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

  @GetMapping("/{worldSlug}/{realmSlug}/audit")
  @Timed(
      value = "listAdmissionPointerAudit",
      description = "List audit history for one admission pointer")
  public ResponseEntity<ApiResponse<List<AdmissionPointerDto>>> listAudit(
      @PathVariable String worldSlug, @PathVariable String realmSlug) {
    return ResponseEntity.ok(
        ApiResponse.success(admissionPointerService.listPointerAudit(worldSlug, realmSlug)));
  }

  @PostMapping
  @Timed(value = "setAdmissionPointer", description = "Update gameplay admission pointer")
  public ResponseEntity<ApiResponse<AdmissionPointerDto>> setPointer(
      @Valid @RequestBody SetAdmissionPointerRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    return ResponseEntity.ok(ApiResponse.success(admissionPointerService.setPointer(request)));
  }
}
