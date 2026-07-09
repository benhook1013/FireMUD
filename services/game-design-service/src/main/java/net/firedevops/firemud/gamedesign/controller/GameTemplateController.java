package net.firedevops.firemud.gamedesign.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.gamedesign.dto.GameTemplateDto;
import net.firedevops.firemud.gamedesign.service.GameTemplateService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/templates")
@RequiredArgsConstructor
public class GameTemplateController {
  private final GameTemplateService templateService;

  @PostMapping
  public ResponseEntity<ApiResponse<GameTemplateDto>> create(
      @Valid @RequestBody GameTemplateDto dto) {
    return GameDesignRequestReaders.withBadRequest(
        () -> {
          GameDesignRequestReaders.requireTenantAccess(dto.tenantId());
          GameTemplateDto created = templateService.createTemplate(dto);
          return ResponseEntity.ok(ApiResponse.success(created));
        });
  }

  @GetMapping
  public ResponseEntity<ApiResponse<Page<GameTemplateDto>>> list(
      @RequestParam String tenantId, Pageable pageable) {
    return GameDesignRequestReaders.withBadRequest(
        () -> {
          GameDesignRequestReaders.requireTenantAccess(tenantId);
          Page<GameTemplateDto> templates = templateService.listTemplates(tenantId, pageable);
          return ResponseEntity.ok(ApiResponse.success(templates));
        });
  }
}
