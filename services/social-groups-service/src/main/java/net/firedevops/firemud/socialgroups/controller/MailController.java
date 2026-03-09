package net.firedevops.firemud.socialgroups.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.socialgroups.dto.MailMessageDto;
import net.firedevops.firemud.socialgroups.dto.SendMailRequest;
import net.firedevops.firemud.socialgroups.service.MailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mail")
public class MailController {
  private final MailService mailService;

  public MailController(MailService mailService) {
    this.mailService = mailService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<MailMessageDto>> sendMail(
      @Valid @RequestBody SendMailRequest request) {
    MailMessageDto dto = mailService.sendMail(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }
}
