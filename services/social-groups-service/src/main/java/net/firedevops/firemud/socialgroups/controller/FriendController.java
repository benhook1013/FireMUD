package net.firedevops.firemud.socialgroups.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import java.util.function.Supplier;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.common.security.RequestIdValidation;
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
      @PathVariable String friendAccountId,
      @RequestParam String tenantId,
      @RequestParam String accountId) {
    return withBadRequest(
        () -> {
          long parsedFriendAccountId = requireFriendAccountId(friendAccountId);
          AccountScope scope = requireAccountScope(tenantId, accountId);
          socialAccessGuard.requireAccountAccess(scope.tenantId(), scope.accountId());
          friendService.removeFriend(scope.tenantId(), scope.accountId(), parsedFriendAccountId);
          return ResponseEntity.ok(ApiResponse.success(null));
        });
  }

  @GetMapping("/{friendAccountId}")
  public ResponseEntity<ApiResponse<FriendRosterEntryDto>> getFriend(
      @PathVariable String friendAccountId,
      @RequestParam String tenantId,
      @RequestParam String accountId) {
    return withBadRequest(
        () -> {
          long parsedFriendAccountId = requireFriendAccountId(friendAccountId);
          AccountScope scope = requireAccountScope(tenantId, accountId);
          socialAccessGuard.requireAccountAccess(scope.tenantId(), scope.accountId());
          return friendService
              .getFriend(scope.tenantId(), scope.accountId(), parsedFriendAccountId)
              .map(dto -> ResponseEntity.ok(ApiResponse.success(dto)))
              .orElseGet(
                  () ->
                      ResponseEntity.status(HttpStatus.NOT_FOUND)
                          .body(
                              ApiResponse.error(
                                  new ErrorDetail(
                                      "FRIEND_NOT_FOUND",
                                      "Friend not found for accountId=" + parsedFriendAccountId))));
        });
  }

  @GetMapping("/entry/{ordinal}")
  public ResponseEntity<ApiResponse<FriendRosterEntryDto>> getFriendByOrdinal(
      @PathVariable String ordinal, @RequestParam String tenantId, @RequestParam String accountId) {
    return withBadRequest(
        () -> {
          int parsedOrdinal = requireOrdinal(ordinal);
          AccountScope scope = requireAccountScope(tenantId, accountId);
          socialAccessGuard.requireAccountAccess(scope.tenantId(), scope.accountId());
          return friendService
              .getFriendByOrdinal(scope.tenantId(), scope.accountId(), parsedOrdinal)
              .map(dto -> ResponseEntity.ok(ApiResponse.success(dto)))
              .orElseGet(
                  () ->
                      ResponseEntity.status(HttpStatus.NOT_FOUND)
                          .body(
                              ApiResponse.error(
                                  new ErrorDetail(
                                      "FRIEND_NOT_FOUND",
                                      "Friend not found for ordinal=" + parsedOrdinal))));
        });
  }

  @GetMapping
  public ResponseEntity<ApiResponse<FriendRosterViewDto>> listFriends(
      @RequestParam String tenantId,
      @RequestParam String accountId,
      @RequestParam(defaultValue = "ALL") FriendRosterFilter filter) {
    return withBadRequest(
        () -> {
          AccountScope scope = requireAccountScope(tenantId, accountId);
          socialAccessGuard.requireAccountAccess(scope.tenantId(), scope.accountId());
          return ResponseEntity.ok(
              ApiResponse.success(
                  friendService.listFriends(scope.tenantId(), scope.accountId(), filter)));
        });
  }

  @GetMapping("/summary")
  public ResponseEntity<ApiResponse<FriendRosterSummaryDto>> getFriendRosterSummary(
      @RequestParam String tenantId, @RequestParam String accountId) {
    return withBadRequest(
        () -> {
          AccountScope scope = requireAccountScope(tenantId, accountId);
          socialAccessGuard.requireAccountAccess(scope.tenantId(), scope.accountId());
          return ResponseEntity.ok(
              ApiResponse.success(
                  friendService.getFriendRosterSummary(scope.tenantId(), scope.accountId())));
        });
  }

  @DeleteMapping("/entry/{ordinal}")
  public ResponseEntity<ApiResponse<FriendRosterEntryDto>> removeFriendByOrdinal(
      @PathVariable String ordinal, @RequestParam String tenantId, @RequestParam String accountId) {
    return withBadRequest(
        () -> {
          int parsedOrdinal = requireOrdinal(ordinal);
          AccountScope scope = requireAccountScope(tenantId, accountId);
          socialAccessGuard.requireAccountAccess(scope.tenantId(), scope.accountId());
          return friendService
              .removeFriendByOrdinal(scope.tenantId(), scope.accountId(), parsedOrdinal)
              .map(dto -> ResponseEntity.ok(ApiResponse.success(dto)))
              .orElseGet(
                  () ->
                      ResponseEntity.status(HttpStatus.NOT_FOUND)
                          .body(
                              ApiResponse.error(
                                  new ErrorDetail(
                                      "FRIEND_NOT_FOUND",
                                      "Friend not found for ordinal=" + parsedOrdinal))));
        });
  }

  @GetMapping("/presence")
  public ResponseEntity<ApiResponse<FriendPresenceViewDto>> listFriendPresence(
      @RequestParam String tenantId,
      @RequestParam String accountId,
      @RequestParam(defaultValue = "ALL") FriendRosterFilter filter) {
    return withBadRequest(
        () -> {
          AccountScope scope = requireAccountScope(tenantId, accountId);
          socialAccessGuard.requireAccountAccess(scope.tenantId(), scope.accountId());
          return ResponseEntity.ok(
              ApiResponse.success(
                  friendService.listFriendPresence(scope.tenantId(), scope.accountId(), filter)));
        });
  }

  @GetMapping("/visibility")
  public ResponseEntity<ApiResponse<FriendPresencePolicyViewDto>> getFriendPresencePolicy(
      @RequestParam String tenantId, @RequestParam String accountId) {
    return withBadRequest(
        () -> {
          AccountScope scope = requireAccountScope(tenantId, accountId);
          socialAccessGuard.requireAccountAccess(scope.tenantId(), scope.accountId());
          try {
            return ResponseEntity.ok(
                ApiResponse.success(
                    friendService.getFriendPresencePolicy(scope.tenantId(), scope.accountId())));
          } catch (IllegalStateException ex) {
            return unavailable(ex);
          }
        });
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

  private long requireFriendAccountId(String friendAccountId) {
    return RequestIdValidation.requirePositiveLong(friendAccountId, "friendAccountId");
  }

  private int requireOrdinal(String ordinal) {
    return RequestIdValidation.requirePositiveInt(ordinal, "ordinal");
  }

  private AccountScope requireAccountScope(String tenantId, String accountId) {
    return new AccountScope(
        RequestIdValidation.requirePositiveLong(tenantId, "tenantId"),
        RequestIdValidation.requirePositiveLong(accountId, "accountId"));
  }

  private <T> ResponseEntity<ApiResponse<T>> withBadRequest(
      Supplier<ResponseEntity<ApiResponse<T>>> action) {
    try {
      return action.get();
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
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

  private record AccountScope(long tenantId, long accountId) {}
}
