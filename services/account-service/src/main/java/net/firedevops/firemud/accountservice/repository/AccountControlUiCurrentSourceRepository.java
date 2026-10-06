package net.firedevops.firemud.accountservice.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountGlobalRoleSourceRepository.FreshEmptySource;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceSnapshot;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiTokenChecks.InspectedToken;
import net.firedevops.firemud.common.security.AccountJwtExactValues;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import org.jooq.DSLContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Existing-only source observations for the genuinely unscoped fresh-creator control-ui cut.
 *
 * <p>All observations use the same locked Account transaction. The result is not a complete
 * account-auth-evidence-bundle/v1 and never authorizes creator registration: current Coordination
 * projections, durable token issuance and signer-generation retention remain separate
 * prerequisites. Reads cannot enroll missing authority or roles.
 */
public final class AccountControlUiCurrentSourceRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final DSLContext dsl;
  private final AccountRepository accounts;
  private final AccountGlobalRoleSourceRepository globalRoles;
  private final AccountAuthorityGenerationRepository generations;
  private final AccountAuthoritySourceEventReadback accountSource;
  private final AccountIssuerAuthorityEventProducer issuerSource;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected Account persistence collaborators remain internal owners.")
  public AccountControlUiCurrentSourceRepository(
      DSLContext dsl,
      AccountRepository accounts,
      AccountGlobalRoleSourceRepository globalRoles,
      AccountAuthorityGenerationRepository generations,
      AccountAuthoritySourceEventReadback accountSource,
      AccountIssuerAuthorityEventProducer issuerSource) {
    this.dsl = Objects.requireNonNull(dsl);
    this.accounts = Objects.requireNonNull(accounts);
    this.globalRoles = Objects.requireNonNull(globalRoles);
    this.generations = Objects.requireNonNull(generations);
    this.accountSource = Objects.requireNonNull(accountSource);
    this.issuerSource = Objects.requireNonNull(issuerSource);
  }

  /**
   * Compares the independently signed/registered candidate with existing current source rows. No
   * credential authentication or public caller principal is created by this method.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CurrentSourceObservation inspectUnscopedCurrent(InspectedToken candidate) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw invalid();
    }
    Objects.requireNonNull(candidate);
    Map<String, Object> claims = candidate.claims();
    if (!Map.of().equals(claims.get("scopedRoles"))
        || !Map.of().equals(claims.get("membershipVersion"))
        || (claims.containsKey("globalRoles") && !List.of().equals(claims.get("globalRoles")))) {
      throw invalid();
    }
    // Lock issuer first, preserving the composite source-writer lock order. Both issuer history
    // and recipient evidence remain fenced until this same Account transaction finishes.
    IssuerAuthoritySnapshot issuer =
        issuerSource.readCurrentInAccountSnapshot(ControlUiJwtProfileValidator.ISSUER);
    FreshEmptySource roles = globalRoles.readFreshEmptySourceForUpdate(candidate.accountId());
    Account account =
        accounts
            .findByAccountUuidForUpdate(candidate.accountId())
            .orElseThrow(AccountControlUiCurrentSourceRepository::invalid);
    if (account.getLifecycleState() != AccountLifecycleState.ACTIVE
        || !roles.accountUuid().equals(account.getAccountUuid())
        || account.getAccountUuidSourceNumericId() == null
        || roles.accountUuidSourceNumericId() != account.getAccountUuidSourceNumericId()
        || roles.accountUuidProvenance() != account.getAccountUuidProvenance()
        || account.getId() == null
        || roles.accountRowId() != account.getId()) throw invalid();
    CompositeSnapshot current =
        generations.readCompositeSnapshot(
            ControlUiJwtProfileValidator.ISSUER, candidate.accountId(), List.of(), List.of());
    if (issuer.issuerAuthGeneration() != current.issuer().generation()
        || issuer.sourceVersion() != current.issuer().sourceVersion()) {
      throw invalid();
    }
    var latest = accountSource.requireCurrentLatest(account, current.account());
    String stream = "account:auth-authority:v1:account/" + candidate.accountId();
    if (latest.outboxSequence() == 0L) {
      Long events =
          Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT COUNT(*) AS event_count FROM account_authority_outbox_events "
                          + "WHERE outbox_stream_key = ?",
                      stream),
                  "Account source event count returned no row")
              .get("event_count", Long.class);
      if (events == null || events != 0L) throw invalid();
    }
    AccountSourceSnapshot source =
        new AccountSourceSnapshot(
            candidate.accountId(),
            current.account(),
            stream,
            latest.outboxSequence(),
            latest.latestEvent());
    Map<String, Object> tuple = new java.util.LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", Long.toString(current.issuer().generation()));
    tuple.put("accountAuthorityGeneration", Long.toString(current.account().generation()));
    tuple.put("tenantAuthorityGeneration", Map.of());
    tuple.put("membershipAuthorityGeneration", Map.of());
    tuple.put("privateRealmGrantVersions", List.of());
    latest
        .latestEvent()
        .ifPresent(
            event -> {
              try {
                Map<String, Object> wire =
                    JSON.readValue(event.payload(), new TypeReference<>() {});
                tuple.put("accountSecurityCutoff", wire.get("accountSecurityCutoff"));
              } catch (IOException failure) {
                throw invalid();
              }
            });
    requireEqual(tuple, claims.get("authorityTuple"));
    requireEqual(Long.toString(current.issuanceFence().value()), claims.get("issuanceFence"));
    Map<String, Object> expectedVersions =
        Map.of(
            "issuerSourceVersion", current.issuer().sourceVersion(),
            "accountSourceVersion", current.account().sourceVersion(),
            "issuanceFenceSourceVersion", current.issuanceFence().sourceVersion());
    requireEqual(expectedVersions, candidate.registryRecord().get("authoritySourceVersions"));
    return new CurrentSourceObservation(
        candidate.accountId(), current, issuer, source, roles.globalRoleSourceVersion());
  }

  private static void requireEqual(Object left, Object right) {
    if (left == null || right == null || !AccountJwtExactValues.sameJsonValue(left, right)) {
      throw invalid();
    }
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Account control-ui current source observation failed");
  }

  /** Private-minted same-transaction source evidence; deliberately not an authority receipt. */
  public static final class CurrentSourceObservation {
    private final UUID accountId;
    private final CompositeSnapshot authority;
    private final IssuerAuthoritySnapshot issuerSource;
    private final AccountSourceSnapshot accountSource;
    private final long globalRoleSourceVersion;

    private CurrentSourceObservation(
        UUID accountId,
        CompositeSnapshot authority,
        IssuerAuthoritySnapshot issuerSource,
        AccountSourceSnapshot accountSource,
        long globalRoleSourceVersion) {
      this.accountId = accountId;
      this.authority = authority;
      this.issuerSource = issuerSource;
      this.accountSource = accountSource;
      this.globalRoleSourceVersion = globalRoleSourceVersion;
    }

    public UUID accountId() {
      return accountId;
    }

    public CompositeSnapshot authority() {
      return authority;
    }

    public AccountSourceSnapshot accountSource() {
      return accountSource;
    }

    public IssuerAuthoritySnapshot issuerSource() {
      return issuerSource;
    }

    public long globalRoleSourceVersion() {
      return globalRoleSourceVersion;
    }

    @Override
    public String toString() {
      return "AccountControlUiCurrentSourceObservation[non-authorizing]";
    }
  }
}
