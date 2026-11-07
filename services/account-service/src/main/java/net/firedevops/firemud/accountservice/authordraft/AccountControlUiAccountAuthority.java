package net.firedevops.firemud.accountservice.authordraft;

import static net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.canonical;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.ScopeKind;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.Snapshot;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.RestrictionState;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Account-only source owner. No tenant, membership, creator or terms collaborator exists here. */
public sealed class AccountControlUiAccountAuthority permits AccountControlUiAuthority {
  private final AccountRepository accounts;
  private final AccountAuthoritySourceEvidenceRepository upstream;
  private final AccountPlatformRestrictionOperationRepository restrictions;
  private final DSLContext dsl;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Account transaction collaborators are retained privately.")
  public AccountControlUiAccountAuthority(
      AccountRepository accounts,
      AccountAuthoritySourceEvidenceRepository upstream,
      AccountPlatformRestrictionOperationRepository restrictions,
      DSLContext dsl) {
    this.accounts = Objects.requireNonNull(accounts);
    this.upstream = Objects.requireNonNull(upstream);
    this.restrictions = Objects.requireNonNull(restrictions);
    this.dsl = Objects.requireNonNull(dsl);
  }

  /**
   * Captures current Account-only owner sources in the caller's writable transaction. The supplied
   * UUID selects a source; this observation authenticates no caller and is not later commit proof.
   * Primary authentication and transaction-bound consumption belong to the issuance owner.
   */
  public final Snapshot captureAccount(UUID authenticatedAccountId) {
    requireOwnerTransaction();
    if (authenticatedAccountId == null || authenticatedAccountId.equals(new UUID(0L, 0L))) {
      throw unavailable();
    }
    Account account =
        accounts
            .findByAccountUuidForUpdate(authenticatedAccountId)
            .orElseThrow(AccountControlUiAccountAuthority::unavailable);
    if (account.getId() == null
        || account.getId() <= 0L
        || !authenticatedAccountId.equals(account.getAccountUuid())
        || !account.getId().equals(account.getAccountUuidSourceNumericId())
        || !AccountIdentityProvenance.isAccepted(account.getAccountUuidProvenance())
        || account.getLifecycleState() != AccountLifecycleState.ACTIVE) {
      throw unavailable();
    }
    var current =
        upstream.readCurrentIssuerAccountSources("firemud-account-service", authenticatedAccountId);
    var issuer = current.issuer();
    var source = current.account();
    var fence = current.issuanceFence();
    if (!AuthorityScope.issuer("firemud-account-service").equals(issuer.scope())
        || !AuthorityScope.account(authenticatedAccountId).equals(source.scope())
        || !fence.equals(source.issuanceFence())
        || !authenticatedAccountId.equals(fence.accountId())
        || !account.getId().equals(source.accountSourceNumericId())
        || !account.getAccountUuidProvenance().name().equals(source.accountUuidProvenance())
        || current.canonicalAccountProjection() == null
        || current.canonicalIssuerProjection() == null) {
      throw unavailable();
    }
    var restrictionEvidence = new ArrayList<Map<String, Object>>();
    for (Category category :
        List.of(Category.ACCOUNT_SECURITY_LOCK, Category.PLATFORM_ACCESS_BAN)) {
      var restriction = restrictions.readCurrent(authenticatedAccountId, category);
      if (!authenticatedAccountId.equals(restriction.accountUuid())
          || restriction.category() != category
          || restriction.state() != RestrictionState.NONRESTRICTED) {
        throw unavailable();
      }
      restrictionEvidence.add(
          Map.of(
              "category", category.name(),
              "revision", restriction.revision(),
              "enforcementEpoch", restriction.enforcementEpoch(),
              "resultId", restriction.resultId().toString(),
              "requestId", restriction.requestId().toString(),
              "state", restriction.state().name()));
    }
    var roleRow =
        dsl.fetchOne(
            "SELECT account_uuid, account_uuid_source_numeric_id, account_uuid_provenance, "
                + "global_roles, global_role_source_version FROM account_global_role_sources "
                + "WHERE account_uuid = ? FOR SHARE",
            authenticatedAccountId);
    if (roleRow == null
        || !authenticatedAccountId.equals(roleRow.get("account_uuid", UUID.class))
        || !account.getId().equals(roleRow.get("account_uuid_source_numeric_id", Long.class))
        || !account
            .getAccountUuidProvenance()
            .name()
            .equals(roleRow.get("account_uuid_provenance", String.class))) {
      throw unavailable();
    }
    Long roleVersion = roleRow.get("global_role_source_version", Long.class);
    String[] roleValues = roleRow.get("global_roles", String[].class);
    if (roleVersion == null || roleVersion <= 0L || roleValues == null) {
      throw unavailable();
    }
    List<String> roles = Arrays.asList(roleValues.clone());
    if (roles.stream()
            .anyMatch(
                value ->
                    value == null
                        || !java.util.Set.of("platformAdmin", "billingAdmin", "support")
                            .contains(value))
        || !roles.equals(roles.stream().distinct().sorted().toList())) {
      throw unavailable();
    }
    Map<String, Object> authority = new LinkedHashMap<>();
    authority.put("issuerAuthGeneration", issuer.generation());
    authority.put("accountAuthorityGeneration", source.generation());
    authority.put("tenantAuthorityGeneration", Map.of());
    authority.put("membershipAuthorityGeneration", Map.of());
    authority.put("privateRealmGrantVersions", List.of());
    source
        .accountSecurityCutoff()
        .ifPresent(
            cutoff ->
                authority.put(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration", cutoff.accountAuthorityGeneration(),
                        "outboxStreamKey", cutoff.outboxStreamKey(),
                        "outboxSequence", cutoff.outboxSequence())));
    byte[] issuerBytes =
        current.canonicalIssuerProjection().toJson().getBytes(StandardCharsets.UTF_8);
    byte[] accountBytes =
        current.canonicalAccountProjection().toJson().getBytes(StandardCharsets.UTF_8);
    var vector = new ArrayList<SourceEvidence>();
    vector.add(sourceEvidence(SourceKind.ISSUER, "firemud-account-service", issuer, issuerBytes));
    vector.add(
        sourceEvidence(
            SourceKind.ACCOUNT,
            authenticatedAccountId.toString(),
            source,
            canonical(
                Map.of(
                    "canonicalAccountProjection",
                    Base64.getEncoder().encodeToString(accountBytes),
                    "restrictions",
                    restrictionEvidence))));
    vector.add(
        new SourceEvidence(
            SourceKind.GLOBAL_ROLES,
            authenticatedAccountId.toString(),
            Long.toString(source.generation()),
            Long.toString(roleVersion),
            source.checkpoint().outboxStreamKey(),
            Long.toString(source.checkpoint().sequence()),
            canonical(
                Map.of(
                    "accountId",
                    authenticatedAccountId.toString(),
                    "sourceNumericId",
                    account.getId(),
                    "provenance",
                    account.getAccountUuidProvenance().name(),
                    "globalRoleSourceVersion",
                    roleVersion,
                    "globalRoles",
                    roles))));
    vector.sort(java.util.Comparator.comparing(SourceEvidence::key));
    Map<String, Object> identity =
        Map.of(
            "sourceRowId",
            account.getId(),
            "provenance",
            account.getAccountUuidProvenance().name(),
            "sourceNumericId",
            account.getId());
    byte[] evidence =
        canonical(
            Map.of(
                "schemaVersion",
                1,
                "scopeKind",
                "ACCOUNT",
                "accountId",
                authenticatedAccountId.toString(),
                "accountIdentitySource",
                identity,
                "issuanceFence",
                fence.value(),
                "issuanceFenceSourceVersion",
                fence.sourceVersion(),
                "sources",
                vector.stream()
                    .map(value -> Base64.getEncoder().encodeToString(value.canonicalBytes()))
                    .toList()));
    return new Snapshot(
        authenticatedAccountId,
        ScopeKind.ACCOUNT,
        Optional.empty(),
        Map.of(),
        roles,
        authority,
        Map.of(),
        fence.value(),
        fence.sourceVersion(),
        vector,
        evidence,
        List.of(checkpoint(issuer), checkpoint(source)),
        identity);
  }

  private static SourceEvidence sourceEvidence(
      SourceKind kind, String scope, CurrentSourceEvidence source, byte[] evidence) {
    return new SourceEvidence(
        kind,
        scope,
        Long.toString(source.generation()),
        Long.toString(source.sourceVersion()),
        source.checkpoint().outboxStreamKey(),
        Long.toString(source.checkpoint().sequence()),
        evidence);
  }

  private static Map<String, Object> checkpoint(CurrentSourceEvidence source) {
    var checkpoint = source.checkpoint();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("outboxStreamKey", checkpoint.outboxStreamKey());
    result.put("outboxSequence", Long.toString(checkpoint.sequence()));
    checkpoint.sourceEventId().ifPresent(value -> result.put("sourceEventId", value));
    checkpoint.sourceEventDigest().ifPresent(value -> result.put("sourceEventDigest", value));
    return Map.copyOf(result);
  }

  private void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw unavailable();
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw unavailable();
          }
        });
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Current Account-only control-ui authority is unavailable");
  }
}
