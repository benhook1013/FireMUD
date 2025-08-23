package net.firedevops.firemud.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "currency_balance")
public class CurrencyBalance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @SuppressFBWarnings(value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
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
