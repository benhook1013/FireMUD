package net.firedevops.firemud.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "payment_transaction")
public class PaymentTransaction {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @Column(name = "amount_cents", nullable = false)
  private Long amountCents;

  @Column(nullable = false, length = 10)
  private String currency;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;
}
