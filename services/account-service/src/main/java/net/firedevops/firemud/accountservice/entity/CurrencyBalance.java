package net.firedevops.firemud.accountservice.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "currency_balance")
public class CurrencyBalance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "account_id", nullable = false)
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private Account account;

  @Column(name = "currency_code", nullable = false, length = 20)
  private String currencyCode;

  @Column(nullable = false)
  private Long balance;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Account reference is managed by JPA and intentionally exposed")
  public Account getAccount() {
    return account;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Account reference is managed by JPA and intentionally stored")
  public void setAccount(Account account) {
    this.account = account;
  }
}
