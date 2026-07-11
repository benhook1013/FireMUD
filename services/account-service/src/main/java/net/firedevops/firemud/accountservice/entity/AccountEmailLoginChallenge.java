package net.firedevops.firemud.accountservice.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class AccountEmailLoginChallenge {
  private Long id;
  private Long accountId;
  private String codeHash;
  private LocalDateTime expiresAt;
  private LocalDateTime resendAvailableAt;
  private int invalidAttemptCount;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
