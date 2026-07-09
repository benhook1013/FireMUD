package net.firedevops.firemud.entitymanagement.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.AddFriendRequest;
import net.firedevops.firemud.entitymanagement.dto.CharacterFriendDto;
import net.firedevops.firemud.entitymanagement.service.FriendService;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    return EntityManagementRequestReaders.withBadRequest(
        () -> {
          EntityManagementRequestReaders.CharacterScope scope =
              EntityManagementRequestReaders.requireCharacterScope(tenantId, characterId);
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
    return EntityManagementRequestReaders.withBadRequest(
        () -> {
          EntityManagementRequestReaders.CharacterScope scope =
              EntityManagementRequestReaders.requireCharacterScope(tenantId, characterId);
          long parsedFriendId =
              RequestIdValidation.requirePositiveLong(request.friendId(), "friendId");
          SessionContext.requireTenantAccess(scope.tenantId());
          CharacterFriendDto dto =
              friendService.addFriend(
                  scope.tenantId(),
                  scope.characterId(),
                  gameInstanceId,
                  playableStateScope,
                  parsedFriendId);
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
    return EntityManagementRequestReaders.withBadRequest(
        () -> {
          EntityManagementRequestReaders.CharacterScope scope =
              EntityManagementRequestReaders.requireCharacterScope(tenantId, characterId);
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
}
