package net.firedevops.firemud.entitymanagement.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import java.util.function.Supplier;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.AddFriendRequest;
import net.firedevops.firemud.entitymanagement.dto.CharacterFriendDto;
import net.firedevops.firemud.entitymanagement.service.FriendService;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** REST endpoints for managing per-game friend links. */
@RestController
@RequestMapping("/tenants/{tenantId}/characters/{characterId}/friends")
public class FriendController {
  private final FriendService friendService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring manages FriendService bean lifecycle")
  public FriendController(FriendService friendService) {
    this.friendService = friendService;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<Page<CharacterFriendDto>>> list(
      @PathVariable String tenantId,
      @PathVariable String characterId,
      @RequestParam String gameInstanceId,
      @RequestParam PlayableStateScope playableStateScope,
      Pageable pageable) {
    return withBadRequest(
        () -> {
          CharacterScope scope = requireCharacterScope(tenantId, characterId);
          SessionContext.requireTenantAccess(scope.tenantId());
          Page<CharacterFriendDto> list =
              friendService.listFriends(
                  scope.tenantId(),
                  scope.characterId(),
                  gameInstanceId,
                  playableStateScope,
                  pageable);
          return ResponseEntity.ok(ApiResponse.success(list));
        });
  }

  @PostMapping
  public ResponseEntity<ApiResponse<CharacterFriendDto>> add(
      @PathVariable String tenantId,
      @PathVariable String characterId,
      @RequestParam String gameInstanceId,
      @RequestParam PlayableStateScope playableStateScope,
      @Valid @RequestBody AddFriendRequest request) {
    return withBadRequest(
        () -> {
          CharacterScope scope = requireCharacterScope(tenantId, characterId);
          SessionContext.requireTenantAccess(scope.tenantId());
          CharacterFriendDto dto =
              friendService.addFriend(
                  scope.tenantId(),
                  scope.characterId(),
                  gameInstanceId,
                  playableStateScope,
                  request.friendId());
          return ResponseEntity.ok(ApiResponse.success(dto));
        });
  }

  @DeleteMapping("/{friendId}")
  public ResponseEntity<ApiResponse<Void>> remove(
      @PathVariable String tenantId,
      @PathVariable String characterId,
      @PathVariable String friendId,
      @RequestParam String gameInstanceId,
      @RequestParam PlayableStateScope playableStateScope) {
    return withBadRequest(
        () -> {
          CharacterScope scope = requireCharacterScope(tenantId, characterId);
          long parsedFriendId = RequestIdValidation.requirePositiveLong(friendId, "friendId");
          SessionContext.requireTenantAccess(scope.tenantId());
          friendService.removeFriend(
              scope.tenantId(),
              scope.characterId(),
              gameInstanceId,
              playableStateScope,
              parsedFriendId);
          return ResponseEntity.ok(ApiResponse.success(null));
        });
  }

  private CharacterScope requireCharacterScope(String tenantId, String characterId) {
    return new CharacterScope(
        RequestIdValidation.requirePositiveLong(tenantId, "tenantId"),
        RequestIdValidation.requirePositiveLong(characterId, "characterId"));
  }

  private <T> ResponseEntity<ApiResponse<T>> withBadRequest(
      Supplier<ResponseEntity<ApiResponse<T>>> action) {
    try {
      return action.get();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error(new ErrorDetail("INVALID_ARGUMENT", ex.getMessage())));
    }
  }

  private record CharacterScope(long tenantId, long characterId) {}
}
