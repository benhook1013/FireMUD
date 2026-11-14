package net.firedevops.firemud.accountservice.authordraft;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.CreatorControlCaptureSources;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.jooq.DSLContext;
import tools.jackson.databind.json.JsonMapper;

/** Account-only and existing creator source capture in the caller's owner transaction. */
public final class AccountControlUiAuthority extends AccountControlUiAccountAuthority {
  public enum ScopeKind {
    ACCOUNT,
    TENANT
  }

  /** Decodes the closed scope values retained by Account-owned Control-UI issuance rows. */
  public static ScopeKind decodeScope(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Account control-ui scope is required");
    }
    return switch (value) {
      case "ACCOUNT" -> ScopeKind.ACCOUNT;
      case "TENANT" -> ScopeKind.TENANT;
      default -> throw new IllegalArgumentException("Unknown Account control-ui scope");
    };
  }

  /** Decodes the tenant column under its exact Account or tenant scope rule. */
  public static UUID decodeScopedTenant(ScopeKind scopeKind, UUID tenantId) {
    if (scopeKind == null) {
      throw new IllegalArgumentException("Account control-ui scope is required");
    }
    return switch (scopeKind) {
      case TENANT -> {
        if (tenantId == null
            || (tenantId.getMostSignificantBits() == 0L
                && tenantId.getLeastSignificantBits() == 0L)) {
          throw new IllegalArgumentException("Tenant-scoped Account control-ui requires a tenant");
        }
        yield tenantId;
      }
      case ACCOUNT -> {
        if (tenantId != null) {
          throw new IllegalArgumentException("Account-scoped control-ui must omit its tenant");
        }
        yield null;
      }
    };
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final AccountDraftSourceCompositionService sources;

  public AccountControlUiAuthority(
      AccountDraftSourceCompositionService sources,
      AccountRepository accounts,
      AccountAuthoritySourceEvidenceRepository upstream,
      AccountPlatformRestrictionOperationRepository restrictions,
      DSLContext dsl) {
    super(accounts, upstream, restrictions, dsl);
    this.sources = Objects.requireNonNull(sources);
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
                        cutoff.accountAuthorityGeneration(),
                        "outboxStreamKey",
                        cutoff.outboxStreamKey(),
                        "outboxSequence",
                        cutoff.outboxSequence())));
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

    Snapshot(
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
