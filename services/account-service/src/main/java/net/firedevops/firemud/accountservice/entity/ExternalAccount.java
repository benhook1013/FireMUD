package net.firedevops.firemud.accountservice.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(
    name = "external_account",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"provider", "external_id"})})
public class ExternalAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 20)
  private String provider;

  @Column(name = "external_id", nullable = false, length = 100)
  private String externalId;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA association is intentionally exposed")
  public Account getAccount() {
    return account;
  }

  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "JPA association stored directly")
  public void setAccount(Account account) {
    this.account = account;
  }
}
