package net.firedevops.firemud.entitymanagement.controller;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.service.CharacterService;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for listing characters by account. */
@RestController
@RequestMapping("/tenants/{tenantId}/accounts/{accountId}/characters")
@RequiredArgsConstructor
public class CharacterController {
  private final CharacterService characterService;

  @GetMapping
  public ResponseEntity<ApiResponse<Page<CharacterDto>>> list(
      @PathVariable String tenantId,
      @PathVariable String accountId,
      @RequestParam String gameInstanceId,
      @RequestParam PlayableStateScope playableStateScope,
      Pageable pageable) {
    try {
      long parsedTenantId = RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
      long parsedAccountId = RequestIdValidation.requirePositiveLong(accountId, "accountId");
      SessionContext.requireTenantAccess(parsedTenantId);
      Page<CharacterDto> list =
          characterService.listForGameplayScope(
              parsedTenantId, parsedAccountId, gameInstanceId, playableStateScope, pageable);
      return ResponseEntity.ok(ApiResponse.success(list));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error(new ErrorDetail("INVALID_ARGUMENT", ex.getMessage())));
    }
  }
}
