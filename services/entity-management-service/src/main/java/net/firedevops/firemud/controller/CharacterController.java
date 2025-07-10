package net.firedevops.firemud.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.CharacterDto;
import net.firedevops.firemud.service.CharacterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for listing characters by account. */
@RestController
@RequestMapping("/accounts/{accountId}/characters")
@RequiredArgsConstructor
public class CharacterController {
  private final CharacterService characterService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<CharacterDto>>> list(@PathVariable Long accountId) {
    List<CharacterDto> list = characterService.listForAccount(accountId);
    return ResponseEntity.ok(ApiResponse.success(list));
  }
}
