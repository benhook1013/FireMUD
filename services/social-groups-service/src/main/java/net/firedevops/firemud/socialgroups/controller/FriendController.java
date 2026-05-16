package net.firedevops.firemud.socialgroups.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresencePolicyViewDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceViewDto;
import net.firedevops.firemud.socialgroups.dto.FriendRosterEntryDto;
import net.firedevops.firemud.socialgroups.dto.FriendRosterFilter;
import net.firedevops.firemud.socialgroups.dto.FriendRosterSummaryDto;
import net.firedevops.firemud.socialgroups.dto.FriendRosterViewDto;
import net.firedevops.firemud.socialgroups.dto.UpdateFriendPresencePolicyRequest;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import net.firedevops.firemud.socialgroups.service.FriendService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/friends")
public class FriendController {
  private final FriendService friendService;
  private final SocialAccessGuard socialAccessGuard;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring manages FriendService bean lifecycle")
  public FriendController(FriendService friendService, SocialAccessGuard socialAccessGuard) {
    this.friendService = friendService;
    this.socialAccessGuard = socialAccessGuard;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<FriendLinkDto>> addFriend(
      @Valid @RequestBody AddFriendRequest request) {
    socialAccessGuard.requireAccountAccess(request.tenantId(), request.accountId());
    try {
      FriendLinkDto dto = friendService.addFriend(request);
      return ResponseEntity.ok(ApiResponse.success(dto));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  @DeleteMapping("/{friendAccountId}")
  public ResponseEntity<ApiResponse<Void>> removeFriend(
      @PathVariable long friendAccountId,
      @RequestParam long tenantId,
      @RequestParam long accountId) {
    socialAccessGuard.requireAccountAccess(tenantId, accountId);
    try {
      friendService.removeFriend(tenantId, accountId, friendAccountId);
      return ResponseEntity.ok(ApiResponse.success(null));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  @GetMapping("/{friendAccountId}")
  public ResponseEntity<ApiResponse<FriendRosterEntryDto>> getFriend(
      @PathVariable long friendAccountId,
      @RequestParam long tenantId,
      @RequestParam long accountId) {
    socialAccessGuard.requireAccountAccess(tenantId, accountId);
    return friendService
        .getFriend(tenantId, accountId, friendAccountId)
        .map(dto -> ResponseEntity.ok(ApiResponse.success(dto)))
        .orElseGet(
            () ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(
                        ApiResponse.error(
                            new ErrorDetail(
                                "FRIEND_NOT_FOUND",
                                "Friend not found for accountId=" + friendAccountId))));
  }

  @GetMapping("/entry/{ordinal}")
  public ResponseEntity<ApiResponse<FriendRosterEntryDto>> getFriendByOrdinal(
      @PathVariable int ordinal, @RequestParam long tenantId, @RequestParam long accountId) {
    socialAccessGuard.requireAccountAccess(tenantId, accountId);
    try {
      return friendService
          .getFriendByOrdinal(tenantId, accountId, ordinal)
          .map(dto -> ResponseEntity.ok(ApiResponse.success(dto)))
          .orElseGet(
              () ->
                  ResponseEntity.status(HttpStatus.NOT_FOUND)
                      .body(
                          ApiResponse.error(
                              new ErrorDetail(
                                  "FRIEND_NOT_FOUND", "Friend not found for ordinal=" + ordinal))));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  @GetMapping
  public ResponseEntity<ApiResponse<FriendRosterViewDto>> listFriends(
      @RequestParam long tenantId,
      @RequestParam long accountId,
      @RequestParam(defaultValue = "ALL") FriendRosterFilter filter) {
    socialAccessGuard.requireAccountAccess(tenantId, accountId);
    return ResponseEntity.ok(
        ApiResponse.success(friendService.listFriends(tenantId, accountId, filter)));
  }

  @GetMapping("/summary")
  public ResponseEntity<ApiResponse<FriendRosterSummaryDto>> getFriendRosterSummary(
      @RequestParam long tenantId, @RequestParam long accountId) {
    socialAccessGuard.requireAccountAccess(tenantId, accountId);
    return ResponseEntity.ok(
        ApiResponse.success(friendService.getFriendRosterSummary(tenantId, accountId)));
  }

  @DeleteMapping("/entry/{ordinal}")
  public ResponseEntity<ApiResponse<FriendRosterEntryDto>> removeFriendByOrdinal(
      @PathVariable int ordinal, @RequestParam long tenantId, @RequestParam long accountId) {
    socialAccessGuard.requireAccountAccess(tenantId, accountId);
    try {
      return friendService
          .removeFriendByOrdinal(tenantId, accountId, ordinal)
          .map(dto -> ResponseEntity.ok(ApiResponse.success(dto)))
          .orElseGet(
              () ->
                  ResponseEntity.status(HttpStatus.NOT_FOUND)
                      .body(
                          ApiResponse.error(
                              new ErrorDetail(
                                  "FRIEND_NOT_FOUND", "Friend not found for ordinal=" + ordinal))));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  @GetMapping("/presence")
  public ResponseEntity<ApiResponse<FriendPresenceViewDto>> listFriendPresence(
      @RequestParam long tenantId,
      @RequestParam long accountId,
      @RequestParam(defaultValue = "ALL") FriendRosterFilter filter) {
    socialAccessGuard.requireAccountAccess(tenantId, accountId);
    return ResponseEntity.ok(
        ApiResponse.success(friendService.listFriendPresence(tenantId, accountId, filter)));
  }

  @GetMapping("/visibility")
  public ResponseEntity<ApiResponse<FriendPresencePolicyViewDto>> getFriendPresencePolicy(
      @RequestParam long tenantId, @RequestParam long accountId) {
    socialAccessGuard.requireAccountAccess(tenantId, accountId);
    try {
      return ResponseEntity.ok(
          ApiResponse.success(friendService.getFriendPresencePolicy(tenantId, accountId)));
    } catch (IllegalStateException ex) {
      return unavailable(ex);
    }
  }

  @PutMapping("/visibility")
  public ResponseEntity<ApiResponse<FriendPresencePolicyViewDto>> updateFriendPresencePolicy(
      @Valid @RequestBody UpdateFriendPresencePolicyRequest request) {
    socialAccessGuard.requireAccountAccess(request.tenantId(), request.accountId());
    try {
      return ResponseEntity.ok(
          ApiResponse.success(
              friendService.updateFriendPresencePolicy(
                  request.tenantId(), request.accountId(), request.visibilityPolicy())));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    } catch (IllegalStateException ex) {
      return unavailable(ex);
    }
  }

  private <T> ResponseEntity<ApiResponse<T>> badRequest(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.error(new ErrorDetail("INVALID_ARGUMENT", ex.getMessage())));
  }

  private <T> ResponseEntity<ApiResponse<T>> unavailable(IllegalStateException ex) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.error(new ErrorDetail("UNAVAILABLE", ex.getMessage())));
  }
}
