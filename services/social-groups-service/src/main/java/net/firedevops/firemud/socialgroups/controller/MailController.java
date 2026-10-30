package net.firedevops.firemud.socialgroups.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.socialgroups.dto.MailMessageDto;
import net.firedevops.firemud.socialgroups.dto.SendMailRequest;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import net.firedevops.firemud.socialgroups.service.MailService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/mail")
public class MailController {
  private final MailService mailService;
  private final SocialAccessGuard socialAccessGuard;

  public MailController(MailService mailService, SocialAccessGuard socialAccessGuard) {
    this.mailService = mailService;
    this.socialAccessGuard = socialAccessGuard;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<MailMessageDto>> sendMail(
      @Valid @RequestBody SendMailRequest request) {
    String callerAccountUuid = currentAccountUuidOrNull();
    if (!request.senderAccountId().equals(callerAccountUuid)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Sender must match authenticated account");
    }
    socialAccessGuard.requireAccountAccess(request.tenantId(), request.senderAccountId());
    MailMessageDto dto = mailService.sendMail(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  private static String currentAccountUuidOrNull() {
    String callerAccountId = SessionContext.getAccountId();
    if (callerAccountId == null || callerAccountId.isBlank()) {
      return null;
    }
    try {
      return JwtClaims.requireCanonicalAccountUuid(callerAccountId, "accountId");
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
