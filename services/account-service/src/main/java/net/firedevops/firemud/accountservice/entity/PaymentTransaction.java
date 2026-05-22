package net.firedevops.firemud.accountservice.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
public class PaymentTransaction {
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

  private Long amountCents;
  private Long platformFeeCents;
  private Long creatorShareCents;
  private String currency;
  private String status;
  private String providerId;
  private boolean donation;
  private Long tenantId;
}
