package net.firedevops.firemud.accountservice.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Entity
@Table(name = "payment_transaction")
public class PaymentTransaction {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
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
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @Column(name = "amount_cents", nullable = false)
  private Long amountCents;

  @Column(name = "platform_fee_cents", nullable = false)
  private Long platformFeeCents;

  @Column(name = "creator_share_cents", nullable = false)
  private Long creatorShareCents;

  @Column(nullable = false, length = 10)
  private String currency;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(name = "provider_id", length = 50)
  private String providerId;

  @Column(nullable = false)
  private boolean donation;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;
}
