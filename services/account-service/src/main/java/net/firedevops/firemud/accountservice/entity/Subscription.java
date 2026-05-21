package net.firedevops.firemud.accountservice.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
public class Subscription {
  private Long id;

  @Getter(
      onMethod_ =
          @SuppressFBWarnings(
              value = "EI_EXPOSE_REP",
              justification = "JPA association is intentionally exposed"))
  @Setter(
      onMethod_ =
          @SuppressFBWarnings(
              value = "EI_EXPOSE_REP2",
              justification = "JPA association stored directly"))
  private Account account;

  private String planId;
  private String status;
  private LocalDateTime startedAt;
  private LocalDateTime endedAt;
  private Long tenantId;
}
