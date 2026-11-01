package net.firedevops.firemud.accountservice.authordraft;

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
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.RestrictionState;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.CreatorControlCaptureSources;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Account-only and existing creator source capture in the caller's owner transaction. */
public final class AccountControlUiAuthority {
  public enum ScopeKind {
    ACCOUNT,
    TENANT
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final AccountDraftSourceCompositionService sources;
  private final AccountRepository accounts;
  private final AccountAuthoritySourceEvidenceRepository upstream;
  private final AccountPlatformRestrictionOperationRepository restrictions;
  private final DSLContext dsl;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Account transaction collaborators are retained privately.")
  public AccountControlUiAuthority(
      AccountDraftSourceCompositionService sources,
      AccountRepository accounts,
      AccountAuthoritySourceEvidenceRepository upstream,
      AccountPlatformRestrictionOperationRepository restrictions,
      DSLContext dsl) {
    this.sources = Objects.requireNonNull(sources);
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
  public Snapshot captureAccount(UUID authenticatedAccountId) {
    requireOwnerTransaction();
    if (authenticatedAccountId == null || authenticatedAccountId.equals(new UUID(0L, 0L))) {
      throw unavailable();
    }
    Account account =
        accounts
            .findByAccountUuidForUpdate(authenticatedAccountId)
            .orElseThrow(AccountControlUiAuthority::unavailable);
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
                        "accountAuthorityGeneration",
                            Long.parseLong(cutoff.accountAuthorityGeneration()),
                        "outboxStreamKey", cutoff.outboxStreamKey(),
                        "outboxSequence", Long.parseLong(cutoff.outboxSequence()))));
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

  public Snapshot capture(UUID actor, UUID tenant, CapturedEnvironmentBoundary environment) {
    var existing = sources.readExistingSources(environment, tenant);
    requireNoApplicableDeadline(existing);
    return snapshot(actor, tenant, existing);
  }

  /** Discovers only the existing initial creator; primary authentication must still match it. */
  public Snapshot captureInitial(UUID tenant, CapturedEnvironmentBoundary environment) {
    var existing = sources.readExistingSources(environment, tenant);
    requireNoApplicableDeadline(existing);
    return snapshot(existing.membership().accountUuid(), tenant, existing);
  }

  private static void requireNoApplicableDeadline(
      AccountDraftSourceCompositionService.ExistingSources existing) {
    // Only the locked typed owner readback proves absence; opaque source bytes are not a lease.
    var terms = Objects.requireNonNull(Objects.requireNonNull(existing.hostedTerms()).terms());
    if (terms.validUntil() != null || terms.disclosedDeadline() != null) {
      throw new IllegalStateException(
          "Deadline-qualified hosted terms cannot authorize this minimum commit producer");
    }
  }

  private Snapshot snapshot(
      UUID actor, UUID tenant, AccountDraftSourceCompositionService.ExistingSources existing) {
    var member = existing.membership();
    if (!actor.equals(member.accountUuid())
        || !tenant.equals(member.tenantUuid())
        || !member.roleSource().roles().equals(List.of("tenantAdmin"))
        || !member.pairSource().membershipExists()
        || member.pairSource().lastTransitionInvalidated()) {
      throw new IllegalStateException("Current authenticated initial creator authority required");
    }
    var tuple = member.authorityTuple();
    var selected = java.util.Set.of(tenant.toString());
    if (!tuple.tenantAuthorityGeneration().keySet().equals(selected)
        || !tuple.membershipAuthorityGeneration().keySet().equals(selected)
        || !member.membershipVersion().keySet().equals(selected)
        || member.authoritySnapshot().tenants().size() != 1
        || member.authoritySnapshot().memberships().size() != 1) {
      throw new IllegalStateException("Exact single selected creator tenant authority required");
    }
    Map<String, Object> authority = new LinkedHashMap<>();
    authority.put("issuerAuthGeneration", Long.parseLong(tuple.issuerAuthGeneration()));
    authority.put("accountAuthorityGeneration", Long.parseLong(tuple.accountAuthorityGeneration()));
    authority.put("tenantAuthorityGeneration", numeric(tuple.tenantAuthorityGeneration()));
    authority.put("membershipAuthorityGeneration", numeric(tuple.membershipAuthorityGeneration()));
    if (!tuple.privateRealmGrantVersions().isEmpty()) {
      throw new IllegalStateException("Creator control does not acquire gameplay grants");
    }
    authority.put("privateRealmGrantVersions", List.of());
    tuple
        .accountSecurityCutoff()
        .ifPresent(
            cutoff ->
                authority.put(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration",
                        Long.parseLong(cutoff.accountAuthorityGeneration()),
                        "outboxStreamKey",
                        cutoff.outboxStreamKey(),
                        "outboxSequence",
                        Long.parseLong(cutoff.outboxSequence()))));
    tuple
        .tenantBillingCutoff()
        .ifPresent(
            cutoffs -> {
              Map<String, Object> values = new LinkedHashMap<>();
              cutoffs.forEach(
                  (id, cutoff) ->
                      values.put(
                          id,
                          Map.of(
                              "tenantAuthorityGeneration",
                              Long.parseLong(cutoff.tenantAuthorityGeneration()),
                              "tenantBillingSequence",
                              Long.parseLong(cutoff.tenantBillingSequence()),
                              "outboxStreamKey",
                              cutoff.outboxStreamKey(),
                              "outboxSequence",
                              Long.parseLong(cutoff.outboxSequence()))));
              authority.put("tenantBillingCutoff", Map.copyOf(values));
            });
    var upstream = existing.issuerAccount();
    var issuanceFenceSource = requireIssuanceFenceSource(member);
    byte[] issuer = upstream.canonicalIssuerProjection().toJson().getBytes(StandardCharsets.UTF_8);
    byte[] account =
        upstream.canonicalAccountProjection().toJson().getBytes(StandardCharsets.UTF_8);
    byte[] membership = member.sourceEvent().canonicalJsonUtf8();
    var vector = new ArrayList<SourceEvidence>(existing.hostedTerms().sourceEvidence());
    vector.add(
        new SourceEvidence(
            SourceKind.ISSUER,
            "firemud-account-service",
            Long.toString(upstream.issuer().generation()),
            Long.toString(upstream.issuer().sourceVersion()),
            upstream.issuer().checkpoint().outboxStreamKey(),
            Long.toString(upstream.issuer().checkpoint().sequence()),
            issuer));
    vector.add(
        new SourceEvidence(
            SourceKind.ACCOUNT,
            actor.toString(),
            Long.toString(upstream.account().generation()),
            Long.toString(upstream.account().sourceVersion()),
            upstream.account().checkpoint().outboxStreamKey(),
            Long.toString(upstream.account().checkpoint().sequence()),
            account));
    vector.add(
        new SourceEvidence(
            SourceKind.GLOBAL_ROLES,
            actor.toString(),
            Long.toString(upstream.account().generation()),
            Long.toString(upstream.account().sourceVersion()),
            upstream.account().checkpoint().outboxStreamKey(),
            Long.toString(upstream.account().checkpoint().sequence()),
            account));
    var tenantState = member.authoritySnapshot().tenants().getFirst();
    vector.add(
        new SourceEvidence(
            SourceKind.TENANT,
            tenant.toString(),
            Long.toString(tenantState.generation()),
            Long.toString(tenantState.sourceVersion()),
            null,
            null,
            canonical(
                Map.of(
                    "creation",
                    member.creationSource(),
                    "checkpoints",
                    member.outboxCheckpoints(),
                    "events",
                    member.outboxSourceEvidence()))));
    var membershipState = member.authoritySnapshot().memberships().getFirst();
    vector.add(
        new SourceEvidence(
            SourceKind.MEMBERSHIP,
            actor + "/" + tenant,
            Long.toString(membershipState.generation()),
            Long.toString(membershipState.sourceVersion()),
            null,
            null,
            canonical(
                Map.of(
                    "event",
                    Base64.getEncoder().encodeToString(membership),
                    "membershipVersion",
                    member.membershipVersion(),
                    "roleSnapshotVersion",
                    member.roleSource().snapshotVersion(),
                    "roles",
                    member.roleSource().roles()))));
    vector.sort(java.util.Comparator.comparing(SourceEvidence::key));
    byte[] evidence =
        canonical(
            Map.of(
                "schemaVersion",
                1,
                "accountId",
                actor.toString(),
                "tenantId",
                tenant.toString(),
                "sources",
                vector.stream()
                    .map(value -> Base64.getEncoder().encodeToString(value.canonicalBytes()))
                    .toList()));
    var checkpoints =
        member.outboxCheckpoints().stream()
            .map(
                checkpoint -> {
                  Map<String, Object> value = new LinkedHashMap<>();
                  value.put("outboxStreamKey", checkpoint.outboxStreamKey());
                  value.put("outboxSequence", checkpoint.outboxSequence());
                  member.outboxSourceEvidence().stream()
                      .filter(event -> event.outboxStreamKey().equals(checkpoint.outboxStreamKey()))
                      .findFirst()
                      .ifPresent(
                          event -> {
                            value.put("sourceEventId", event.eventId());
                            value.put("sourceEventDigest", event.eventDigest());
                          });
                  return Map.copyOf(value);
                })
            .toList();
    Map<String, Object> identity =
        Map.of(
            "sourceRowId",
            upstream.account().accountSourceNumericId(),
            "provenance",
            upstream.account().accountUuidProvenance(),
            "sourceNumericId",
            upstream.account().accountSourceNumericId());
    return new Snapshot(
        actor,
        tenant,
        Map.copyOf(authority),
        numeric(member.membershipVersion()),
        Long.parseLong(member.issuanceFence()),
        issuanceFenceSource.sourceVersion(),
        List.copyOf(vector),
        evidence,
        checkpoints,
        identity);
  }

  static IssuanceFence requireIssuanceFenceSource(CreatorControlCaptureSources member) {
    var source =
        Objects.requireNonNull(
            Objects.requireNonNull(member).authoritySnapshot().issuanceFence(),
            "Authenticated Account issuance fence source required");
    if (source.sourceVersion() <= 0L
        || !Long.toString(source.value()).equals(member.issuanceFence())) {
      throw new IllegalStateException("Authenticated Account issuance fence source required");
    }
    return source;
  }

  private static Map<String, Long> numeric(Map<String, String> values) {
    Map<String, Long> result = new LinkedHashMap<>();
    values.forEach((key, value) -> result.put(key, Long.parseLong(value)));
    return Map.copyOf(result);
  }

  public static byte[] canonical(Object value) {
    try {
      String original = JSON.writeValueAsString(value);
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(original);
      var exactNumbers =
          JsonMapper.builder()
              .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
              .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
              .build();
      if (!sameValues(
          exactNumbers.readValue(original, Object.class),
          exactNumbers.readValue(canonical, Object.class))) {
        throw new IllegalArgumentException("Canonical numeric authority must remain exact");
      }
      return canonical;
    } catch (java.io.IOException | RuntimeException exception) {
      throw new IllegalArgumentException("Canonical control-ui value required");
    }
  }

  private static boolean sameValues(Object original, Object canonical) {
    if (original instanceof Number left && canonical instanceof Number right) {
      return new java.math.BigDecimal(left.toString())
              .compareTo(new java.math.BigDecimal(right.toString()))
          == 0;
    }
    if (original instanceof Map<?, ?> left && canonical instanceof Map<?, ?> right) {
      return left.keySet().equals(right.keySet())
          && left.entrySet().stream()
              .allMatch(entry -> sameValues(entry.getValue(), right.get(entry.getKey())));
    }
    if (original instanceof List<?> left && canonical instanceof List<?> right) {
      if (left.size() != right.size()) {
        return false;
      }
      for (int index = 0; index < left.size(); index++) {
        if (!sameValues(left.get(index), right.get(index))) {
          return false;
        }
      }
      return true;
    }
    return java.util.Objects.equals(original, canonical);
  }

  public static final class Snapshot {
    private final UUID actor;
    private final UUID tenant;
    private final ScopeKind scopeKind;
    private final Map<String, List<String>> scopedRoles;
    private final List<String> globalRoles;
    private final Map<String, Object> tuple;
    private final Map<String, Long> membership;
    private final long fence;
    private final long issuanceFenceSourceVersion;
    private final List<SourceEvidence> vector;
    private final byte[] evidence;
    private final List<Map<String, Object>> checkpoints;
    private final Map<String, Object> identity;

    private Snapshot(
        UUID actor,
        UUID tenant,
        Map<String, Object> tuple,
        Map<String, Long> membership,
        long fence,
        long issuanceFenceSourceVersion,
        List<SourceEvidence> vector,
        byte[] evidence,
        List<Map<String, Object>> checkpoints,
        Map<String, Object> identity) {
      this(
          actor,
          ScopeKind.TENANT,
          Optional.of(tenant),
          Map.of(tenant.toString(), List.of("tenantAdmin")),
          List.of(),
          tuple,
          membership,
          fence,
          issuanceFenceSourceVersion,
          vector,
          evidence,
          checkpoints,
          identity);
    }

    private Snapshot(
        UUID actor,
        ScopeKind scopeKind,
        Optional<UUID> tenant,
        Map<String, List<String>> scopedRoles,
        List<String> globalRoles,
        Map<String, Object> tuple,
        Map<String, Long> membership,
        long fence,
        long issuanceFenceSourceVersion,
        List<SourceEvidence> vector,
        byte[] evidence,
        List<Map<String, Object>> checkpoints,
        Map<String, Object> identity) {
      this.actor = actor;
      this.scopeKind = Objects.requireNonNull(scopeKind);
      Objects.requireNonNull(tenant);
      if ((scopeKind == ScopeKind.TENANT) != tenant.isPresent()) {
        throw new IllegalArgumentException("Exact control-ui scope and tenant required");
      }
      this.tenant = tenant.orElse(null);
      var roles = new LinkedHashMap<String, List<String>>();
      scopedRoles.forEach((key, value) -> roles.put(key, List.copyOf(value)));
      this.scopedRoles = Map.copyOf(roles);
      this.globalRoles = List.copyOf(globalRoles);
      this.tuple = Map.copyOf(tuple);
      this.membership = Map.copyOf(membership);
      this.fence = fence;
      this.issuanceFenceSourceVersion = issuanceFenceSourceVersion;
      this.vector = List.copyOf(vector);
      this.evidence = evidence.clone();
      this.checkpoints = List.copyOf(checkpoints);
      this.identity = Map.copyOf(identity);
    }

    public UUID actor() {
      return actor;
    }

    public UUID tenant() {
      if (scopeKind != ScopeKind.TENANT) {
        throw new IllegalStateException("Account-scoped control-ui authority has no tenant");
      }
      return tenant;
    }

    public ScopeKind scopeKind() {
      return scopeKind;
    }

    public Optional<UUID> optionalTenantId() {
      return Optional.ofNullable(tenant);
    }

    public Map<String, List<String>> scopedRoles() {
      return scopedRoles;
    }

    public List<String> globalRoles() {
      return globalRoles;
    }

    public Map<String, Object> authorityTuple() {
      return tuple;
    }

    public Map<String, Long> membershipVersion() {
      return membership;
    }

    public long issuanceFence() {
      return fence;
    }

    public long issuanceFenceSourceVersion() {
      return issuanceFenceSourceVersion;
    }

    public List<SourceEvidence> sources() {
      return vector;
    }

    public byte[] evidence() {
      return evidence.clone();
    }

    public List<Map<String, Object>> outboxCheckpoints() {
      return checkpoints;
    }

    public Map<String, Object> accountIdentitySource() {
      return identity;
    }

    @Override
    public String toString() {
      return "AccountControlUiAuthority.Snapshot[redacted]";
    }
  }
}
