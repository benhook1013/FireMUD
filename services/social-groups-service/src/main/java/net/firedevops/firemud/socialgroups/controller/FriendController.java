package net.firedevops.firemud.socialgroups.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import java.util.List;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceDto;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import net.firedevops.firemud.socialgroups.service.FriendService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    FriendLinkDto dto = friendService.addFriend(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @GetMapping("/presence")
  public ResponseEntity<ApiResponse<List<FriendPresenceDto>>> listFriendPresence(
      @RequestParam long tenantId, @RequestParam long accountId) {
    socialAccessGuard.requireAccountAccess(tenantId, accountId);
    return ResponseEntity.ok(
        ApiResponse.success(friendService.listFriendPresence(tenantId, accountId)));
  }
}
