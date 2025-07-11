package net.firedevops.firemud.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.GenerationRuleDto;
import net.firedevops.firemud.service.GenerationRuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/generation/rules")
@RequiredArgsConstructor
public class GenerationRuleController {
  private final GenerationRuleService generationRuleService;

  @PostMapping
  public ResponseEntity<ApiResponse<GenerationRuleDto>> save(@RequestBody GenerationRuleDto dto) {
    GenerationRuleDto result = generationRuleService.saveRule(dto);
    return ResponseEntity.ok(ApiResponse.success(result));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<GenerationRuleDto>>> list(@RequestParam Long tenantId) {
    List<GenerationRuleDto> list = generationRuleService.listRules(tenantId);
    return ResponseEntity.ok(ApiResponse.success(list));
  }
}
