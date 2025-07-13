package net.firedevops.firemud.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "currency_balance")
public class CurrencyBalance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @Column(name = "currency_code", nullable = false, length = 20)
  private String currencyCode;

  @Column(nullable = false)
  private Long balance;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;
}
