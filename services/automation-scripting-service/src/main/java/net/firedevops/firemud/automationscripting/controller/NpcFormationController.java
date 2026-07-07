package net.firedevops.firemud.automationscripting.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.dto.CreateFormationRequest;
import net.firedevops.firemud.automationscripting.service.NpcFormationService;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.common.security.RequestIdValidation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/formations")
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Service dependency is not exposed")
public class NpcFormationController {
  private final NpcFormationService formationService;

  @PostMapping
  public ResponseEntity<ApiResponse<Long>> createFormation(
      @RequestBody @Validated CreateFormationRequest request) {
    Long id =
        formationService.createFormation(
            request.tenantId(), request.name(), request.leaderNpcId(), request.formationType());
    return ResponseEntity.ok(ApiResponse.success(id));
  }

  @PostMapping("/{id}/members")
  public ResponseEntity<ApiResponse<Boolean>> addMember(
      @PathVariable String id, @RequestParam String tenantId, @RequestParam String npcId) {
    try {
      formationService.addMember(
          RequestIdValidation.requirePositiveLong(tenantId, "tenantId"),
          RequestIdValidation.requirePositiveLong(id, "formationId"),
          RequestIdValidation.requirePositiveLong(npcId, "npcId"));
      return ResponseEntity.ok(ApiResponse.success(true));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error(new ErrorDetail("INVALID_ARGUMENT", ex.getMessage())));
    }
  }

  @GetMapping("/{id}/members")
  public ResponseEntity<ApiResponse<List<Long>>> listMembers(
      @PathVariable String id, @RequestParam String tenantId) {
    try {
      List<Long> members =
          formationService.getMembers(
              RequestIdValidation.requirePositiveLong(tenantId, "tenantId"),
              RequestIdValidation.requirePositiveLong(id, "formationId"));
      return ResponseEntity.ok(ApiResponse.success(members));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error(new ErrorDetail("INVALID_ARGUMENT", ex.getMessage())));
    }
  }
}
