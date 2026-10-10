package net.firedevops.firemud.accountservice.service.session;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit, non-authorizing Account source-head projection publisher and observer.
 *
 * <p>The SQL owner snapshot is captured and its transaction ends before Coordination Redis is
 * contacted. The canonical keys are in distinct Redis Cluster hash slots, so publication uses the
 * Account client's same pinned physical connection for separate one-key CAS scripts, followed by
 * one WAITAOF and exact byte readback of all values. This is an asynchronous projection: it is
 * neither atomic with Account SQL nor by itself a token-authorization decision. No numeric
 * freshness window is declared here because the canonical owner contract supplies none.
 */
public final class AccountGameplayDelegationAuthorityProjection {
  public static final String ISSUER_KEY_PREFIX = "session:auth:generation:issuer:";
  public static final String ACCOUNT_KEY_PREFIX = "session:auth:generation:account:";
  public static final int MAX_PROJECTION_BYTES = 8 * 1024;

  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern EVENT_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final AccountAuthoritySourceEvidenceRepository sourceEvidence;
  private final TransactionTemplate accountTransaction;
  private final AccountGameplayDelegationRedisClient coordinationRedis;
  private SelectedOwner selectedOwner;

  public AccountGameplayDelegationAuthorityProjection(
      AccountAuthoritySourceEvidenceRepository sourceEvidence,
      PlatformTransactionManager transactionManager,
      AccountGameplayDelegationRedisClient coordinationRedis) {
    this(
        sourceEvidence,
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager is required")),
        coordinationRedis);
  }

  AccountGameplayDelegationAuthorityProjection(
      AccountAuthoritySourceEvidenceRepository sourceEvidence,
      TransactionTemplate accountTransaction,
      AccountGameplayDelegationRedisClient coordinationRedis) {
    this.sourceEvidence =
        Objects.requireNonNull(sourceEvidence, "Account source evidence is required");
    this.accountTransaction = Objects.requireNonNull(accountTransaction);
    this.coordinationRedis = Objects.requireNonNull(coordinationRedis);
  }

  /** Real owner dependencies; no caller-supplied snapshot/currentness callback is accepted. */
  public AccountGameplayDelegationAuthorityProjection(
      AccountAuthoritySourceEvidenceRepository sourceEvidence,
      PlatformTransactionManager transactionManager,
      AccountGameplayDelegationRedisClient coordinationRedis,
      AccountAuthorityGenerationRepository generations,
      AccountTenantMembershipRepository memberships,
      AccountMembershipPairAuthorityRepository pairs,
      AccountAuthorityOutboxRepository outbox,
      AccountTenantAuthorityEventRepository tenantSources) {
    this(sourceEvidence, transactionManager, coordinationRedis);
    this.selectedOwner =
        new SelectedOwner(
            Objects.requireNonNull(generations),
            Objects.requireNonNull(memberships),
            Objects.requireNonNull(pairs),
            Objects.requireNonNull(outbox),
            Objects.requireNonNull(tenantSources));
    accountTransaction.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    accountTransaction.setReadOnly(false);
  }

  /** Four separate canonical keys on one pinned Redis connection; never an admission decision. */
  public SelectedProjectionObservation publishSelectedCurrent(UUID accountId, UUID tenantId) {
    requireOutsideOwnerTransaction();
    SelectedSnapshot before = readSelectedSnapshot(accountId, tenantId);
    var outcome =
        coordinationRedis.publishSelectedAuthorityProjections(accountId, tenantId, before.values());
    SelectedSnapshot after = readSelectedSnapshot(accountId, tenantId);
    requireSameSelectedSnapshot(before, after);
    return new SelectedProjectionObservation(after, outcome);
  }

  /**
   * Actual SQL owner reads surround exact Redis observation, including independent member version.
   */
  public SelectedProjectionObservation observeSelectedCurrent(UUID accountId, UUID tenantId) {
    requireOutsideOwnerTransaction();
    SelectedSnapshot before = readSelectedSnapshot(accountId, tenantId);
    byte[][] actual = coordinationRedis.readSelectedAuthorityProjections(accountId, tenantId);
    SelectedSnapshot after = readSelectedSnapshot(accountId, tenantId);
    requireSameSelectedSnapshot(before, after);
    if (!Arrays.deepEquals(actual, after.values())) throw new StaleProjectionException();
    return new SelectedProjectionObservation(after, null);
  }

  private SelectedSnapshot readSelectedSnapshot(UUID accountId, UUID tenantId) {
    requireAccountId(accountId);
    requireAccountId(tenantId);
    if (selectedOwner == null) throw new ProjectionUnavailableException();
    SelectedSnapshot result =
        accountTransaction.execute(
            status -> {
              // The source and composite owners both take Account first; the composite fixes all
              // four scopes.
              var source =
                  sourceEvidence.readCurrentIssuerAccountSources(
                      GameSessionAccountDelegationProfile.ISSUER, accountId);
              var composite =
                  selectedOwner
                      .generations()
                      .readCompositeSnapshot(
                          GameSessionAccountDelegationProfile.ISSUER,
                          accountId,
                          List.of(tenantId),
                          List.of(tenantId));
              var tenant = selectedOwner.tenantSources().readCurrentByTenant(tenantId);
              var member =
                  selectedOwner
                      .memberships()
                      .findFreshMembershipForUpdate(accountId, tenantId)
                      .orElseThrow(ProjectionUnavailableException::new);
              var pair =
                  selectedOwner
                      .pairs()
                      .readForUpdate(accountId, tenantId)
                      .orElseThrow(ProjectionUnavailableException::new);
              String memberStream =
                  "account:auth-authority:v1:membership/" + accountId + "/" + tenantId;
              var checkpoint =
                  selectedOwner
                      .outbox()
                      .readCheckpoint(memberStream)
                      .orElseThrow(ProjectionUnavailableException::new);
              var event =
                  selectedOwner
                      .outbox()
                      .findEvent(memberStream, checkpoint.outboxSequence())
                      .orElseThrow(ProjectionUnavailableException::new);
              var verified =
                  MembershipAuthorityEventV1Codec.verify(
                      new String(event.payload(), StandardCharsets.UTF_8));
              var tenantState = composite.tenants().getFirst();
              var memberState = composite.memberships().getFirst();
              if (source.issuer().generation() != composite.issuer().generation()
                  || source.issuer().sourceVersion() != composite.issuer().sourceVersion()
                  || source.account().generation() != composite.account().generation()
                  || source.account().sourceVersion() != composite.account().sourceVersion()
                  || !source.issuanceFence().equals(composite.issuanceFence())
                  || tenant.tenantAuthorityGeneration() != tenantState.generation()
                  || tenant.tenantAuthoritySourceVersion() != tenantState.sourceVersion()
                  || !tenantId.equals(tenant.tenantId())
                  || !pair.provenance()
                      .sourceOperationId()
                      .equals(tenant.sourceEvidence().operationId())
                  || !pair.provenance().digest().equals(tenant.sourceEvidence().evidenceDigest())
                  || !pair.membershipExists()
                  || pair.lastTransitionInvalidated()
                  || !"ACTIVE".equals(member.getLifecycleState())
                  || !member.isGameplayAdmissionAllowed()
                  || member.getMembershipVersion() != pair.membershipVersion()
                  || member.getMembershipAuthorityGeneration()
                      != pair.membershipAuthorityGeneration()
                  || memberState.generation() != pair.membershipAuthorityGeneration()
                  || checkpoint.outboxSequence() != pair.eventSequence()
                  || !checkpoint.sourceEventId().equals(pair.eventId())
                  || !checkpoint.sourceEventDigest().equals(pair.eventDigest())
                  || !event.eventId().equals(pair.eventId())
                  || !event.eventDigest().equals(pair.eventDigest())
                  || !Long.toString(checkpoint.outboxSequence()).equals(verified.outboxSequence())
                  || !checkpoint.outboxStreamKey().equals(verified.outboxStreamKey())
                  || !Arrays.equals(event.payload(), verified.canonicalJsonUtf8())
                  || !accountId.toString().equals(verified.accountId())
                  || !tenantId.toString().equals(verified.tenantId())
                  || !Long.toString(pair.membershipVersion())
                      .equals(verified.membershipVersion().get(tenantId.toString()))
                  || !Long.toString(pair.membershipAuthorityGeneration())
                      .equals(verified.membershipAuthorityGeneration())
                  || !"ACTIVE".equals(verified.membershipLifecycleState())
                  || !verified.gameplayAdmissionAllowed()
                  || verified.callerBoundAuthorityInvalidated()
                  || !verified.roles().contains("player"))
                throw new ProjectionUnavailableException();
              // The immutable event proves this pair, not today's independent global/tenant
              // sources.
              // Derive today's tuple from the same locked owner read; never rewrite the historical
              // event.
              Map<String, Object> tuple = new LinkedHashMap<>();
              tuple.put("issuerAuthGeneration", Long.toString(composite.issuer().generation()));
              tuple.put(
                  "accountAuthorityGeneration", Long.toString(composite.account().generation()));
              tuple.put(
                  "tenantAuthorityGeneration",
                  Map.of(tenantId.toString(), Long.toString(tenantState.generation())));
              tuple.put(
                  "membershipAuthorityGeneration",
                  Map.of(tenantId.toString(), Long.toString(memberState.generation())));
              tuple.put("privateRealmGrantVersions", List.of());
              var accountCutoff = source.account().accountSecurityCutoff();
              if (accountCutoff.isPresent()) {
                var cutoff = accountCutoff.orElseThrow();
                tuple.put(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration",
                        cutoff.accountAuthorityGeneration(),
                        "outboxStreamKey",
                        cutoff.outboxStreamKey(),
                        "outboxSequence",
                        cutoff.outboxSequence()));
              }
              // The explicit demo owner has positive linked billing evidence; non-paid does not
              // mean absent cutoff.
              tuple.put(
                  "tenantBillingCutoff",
                  Map.of(
                      tenantId.toString(),
                      Map.of(
                          "tenantAuthorityGeneration",
                              Long.toString(tenant.tenantAuthorityGeneration()),
                          "tenantBillingSequence", Long.toString(tenant.tenantBillingSequence()),
                          "outboxStreamKey", tenant.tenantBillingStreamKey(),
                          "outboxSequence", Long.toString(tenant.tenantBillingSequence()))));
              byte[][] initial = canonicalProjectionPair(source);
              return new SelectedSnapshot(
                  source,
                  composite,
                  pair,
                  verified.canonicalJson(),
                  new String(tenant.payload(), StandardCharsets.UTF_8),
                  canonical(tuple),
                  new byte[][] {
                    initial[0],
                    initial[1],
                    AccountSelectedGameplayAuthorityProjection.tenant(tenant).canonicalBytes(),
                    AccountSelectedGameplayAuthorityProjection.membership(
                            verified, memberState.sourceVersion())
                        .canonicalBytes()
                  });
            });
    if (result == null) throw new ProjectionUnavailableException();
    return result;
  }

  private static void requireSameSelectedSnapshot(SelectedSnapshot before, SelectedSnapshot after) {
    if (!before.source().equals(after.source())
        || !before.composite().equals(after.composite())
        || !before.pair().equals(after.pair())
        || !before.membershipEvent().equals(after.membershipEvent())
        || !before.tenantEvent().equals(after.tenantEvent())
        || !Arrays.equals(before.currentTuple(), after.currentTuple())
        || !Arrays.deepEquals(before.values(), after.values()))
      throw new StaleProjectionException();
  }

  private static void requireOutsideOwnerTransaction() {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) {
      throw new ProjectionUnavailableException();
    }
  }

  private record SelectedOwner(
      AccountAuthorityGenerationRepository generations,
      AccountTenantMembershipRepository memberships,
      AccountMembershipPairAuthorityRepository pairs,
      AccountAuthorityOutboxRepository outbox,
      AccountTenantAuthorityEventRepository tenantSources) {}

  private record SelectedSnapshot(
      IssuerAccountSourceSnapshot source,
      AccountAuthorityGenerationRepository.CompositeSnapshot composite,
      AccountMembershipPairAuthorityRepository.PairAuthority pair,
      String membershipEvent,
      String tenantEvent,
      byte[] currentTuple,
      byte[][] values) {}

  /** Owner-generated informational evidence. It cannot be fabricated as a positive caller proof. */
  public static final class SelectedProjectionObservation {
    private final SelectedSnapshot snapshot;
    private final AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome
        publicationOutcome;

    private SelectedProjectionObservation(
        SelectedSnapshot snapshot,
        AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome publicationOutcome) {
      this.snapshot = snapshot;
      this.publicationOutcome = publicationOutcome;
    }

    public boolean admitting() {
      return false;
    }

    public long membershipVersion() {
      return snapshot.pair().membershipVersion();
    }

    public long membershipAuthorityGeneration() {
      return snapshot.pair().membershipAuthorityGeneration();
    }

    public byte[] canonicalCurrentAuthorityTuple() {
      return snapshot.currentTuple().clone();
    }

    public Map<String, Object> currentAuthorityTuple() {
      try {
        return JSON.readValue(snapshot.currentTuple(), new TypeReference<>() {});
      } catch (RuntimeException unavailable) {
        throw new ProjectionUnavailableException();
      }
    }

    public long issuanceFence() {
      return snapshot.composite().issuanceFence().value();
    }

    public void requireExactOwnerSnapshot(
        IssuerAccountSourceSnapshot source,
        AccountAuthorityGenerationRepository.CompositeSnapshot authority,
        AccountMembershipPairAuthorityRepository.PairAuthority pair,
        MembershipAuthorityEventV1Codec.MembershipEvent membership,
        net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec.Event tenant) {
      if (!snapshot.source().equals(source)
          || !snapshot.composite().equals(authority)
          || !snapshot.pair().equals(pair)
          || !snapshot.membershipEvent().equals(membership.canonicalJson())
          || !snapshot.tenantEvent().equals(new String(tenant.payload(), StandardCharsets.UTF_8)))
        throw new StaleProjectionException();
    }

    public void requireSameSnapshot(SelectedProjectionObservation current) {
      if (current == null) throw new StaleProjectionException();
      requireSameSelectedSnapshot(snapshot, current.snapshot);
    }

    public Map<String, String> projectionDigests() {
      return Map.of(
          "issuer",
          sha256(snapshot.values()[0]),
          "account",
          sha256(snapshot.values()[1]),
          "tenant",
          sha256(snapshot.values()[2]),
          "membership",
          sha256(snapshot.values()[3]));
    }

    public byte[][] canonicalProjections() {
      return Arrays.stream(snapshot.values()).map(byte[]::clone).toArray(byte[][]::new);
    }

    public AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome publicationOutcome() {
      return publicationOutcome;
    }
  }

  /**
   * Publishes the exact latest locked issuer/Account source heads. This method does not activate a
   * token or establish freshness authority; a missing/ambiguous Redis acknowledgement fails.
   */
  public PublicationObservation publishCurrent(UUID accountId) {
    requireAccountId(accountId);
    IssuerAccountSourceSnapshot source = readOwnerSnapshot(accountId);
    byte[][] values = canonicalProjectionPair(source);
    ProjectionValue issuer =
        ProjectionValue.decode(values[0], "issuer", GameSessionAccountDelegationProfile.ISSUER);
    ProjectionValue account = ProjectionValue.decode(values[1], "account", accountId.toString());
    AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome outcome =
        coordinationRedis.publishAuthorityProjections(accountId, values[0], values[1]);
    IssuerAccountSourceSnapshot after = readOwnerSnapshot(accountId);
    if (!source.equals(after) || !Arrays.deepEquals(values, canonicalProjectionPair(after))) {
      throw new StaleProjectionException();
    }
    return new PublicationObservation(
        issuer.digest(),
        account.digest(),
        source.issuer().generation(),
        source.account().generation(),
        outcome);
  }

  /**
   * Observes a Redis projection only when both values equal two consecutive exact Account
   * owner-source reads. This is informational, remains non-authorizing, and defines no elapsed-time
   * freshness policy or subsequent-use race guarantee.
   */
  public ProjectionObservation observeCurrent(UUID accountId) {
    requireAccountId(accountId);
    IssuerAccountSourceSnapshot before = readOwnerSnapshot(accountId);
    byte[][] raw = coordinationRedis.readAuthorityProjections(accountId);
    IssuerAccountSourceSnapshot after = readOwnerSnapshot(accountId);
    if (!before.equals(after)) throw new StaleProjectionException();

    ProjectionValue issuer =
        ProjectionValue.decode(raw[0], "issuer", GameSessionAccountDelegationProfile.ISSUER);
    ProjectionValue account = ProjectionValue.decode(raw[1], "account", accountId.toString());
    byte[][] expected = canonicalProjectionPair(after);
    if (!Arrays.equals(issuer.canonicalBytes(), expected[0])
        || !Arrays.equals(account.canonicalBytes(), expected[1])) {
      throw new StaleProjectionException();
    }
    return new ProjectionObservation(
        issuer.digest(), account.digest(), issuer.generation(), account.generation());
  }

  private IssuerAccountSourceSnapshot readOwnerSnapshot(UUID accountId) {
    IssuerAccountSourceSnapshot snapshot =
        accountTransaction.execute(
            status ->
                sourceEvidence.readCurrentIssuerAccountSources(
                    GameSessionAccountDelegationProfile.ISSUER, accountId));
    if (snapshot == null
        || !GameSessionAccountDelegationProfile.ISSUER.equals(snapshot.issuer().scope().issuerId())
        || !accountId.equals(snapshot.account().scope().accountId())) {
      throw new ProjectionUnavailableException();
    }
    return snapshot;
  }

  /** Encodes exact source DTOs for the two projection keys; these bytes are not authority proof. */
  public static byte[][] canonicalProjectionPair(IssuerAccountSourceSnapshot source) {
    Objects.requireNonNull(source, "Account authority source snapshot is required");
    var canonicalIssuer = source.canonicalIssuerProjection();
    if (canonicalIssuer == null
        || !source.issuer().scope().issuerId().equals(canonicalIssuer.issuerId())
        || !Long.toString(source.issuer().generation())
            .equals(canonicalIssuer.issuerAuthGeneration())
        || !Long.toString(source.issuer().sourceVersion()).equals(canonicalIssuer.sourceVersion())
        || !source.issuer().checkpoint().outboxStreamKey().equals(canonicalIssuer.outboxStreamKey())
        || !Long.toString(source.issuer().checkpoint().sequence())
            .equals(canonicalIssuer.lastAppliedSourceOutboxSequence())
        || !source
            .issuer()
            .checkpoint()
            .sourceEventId()
            .equals(canonicalIssuer.lastAppliedSourceEventId())
        || !source
            .issuer()
            .checkpoint()
            .sourceEventDigest()
            .equals(canonicalIssuer.lastAppliedSourceEventDigest())) {
      throw new ProjectionUnavailableException();
    }
    ProjectionValue issuer =
        ProjectionValue.decode(
            canonicalIssuer.toJson().getBytes(StandardCharsets.UTF_8),
            "issuer",
            canonicalIssuer.issuerId());
    AccountGenerationProjection canonicalAccount = source.canonicalAccountProjection();
    if (canonicalAccount == null
        || !source.account().scope().accountId().toString().equals(canonicalAccount.accountId())
        || !Long.toString(source.account().generation())
            .equals(canonicalAccount.accountAuthorityGeneration())
        || !Long.toString(source.account().sourceVersion()).equals(canonicalAccount.sourceVersion())
        || !source
            .account()
            .checkpoint()
            .outboxStreamKey()
            .equals(canonicalAccount.outboxStreamKey())
        || !Long.toString(source.account().checkpoint().sequence())
            .equals(canonicalAccount.outboxSequence())
        || !source.issuanceFence().equals(source.account().issuanceFence())) {
      throw new ProjectionUnavailableException();
    }
    ProjectionValue account =
        ProjectionValue.decode(
            canonicalAccount.toJson().getBytes(StandardCharsets.UTF_8),
            "account",
            canonicalAccount.accountId());
    return new byte[][] {issuer.canonicalBytes(), account.canonicalBytes()};
  }

  private static void requireAccountId(UUID accountId) {
    Objects.requireNonNull(accountId, "Canonical Account UUID is required");
    if (accountId.equals(new UUID(0L, 0L))
        || accountId.version() != 4
        || accountId.variant() != 2) {
      throw new IllegalArgumentException("Canonical high-entropy Account UUID is required");
    }
  }

  /** Strict canonical non-authorizing value stored at one canonical authority key. */
  public static final class ProjectionValue {
    private static final String ISSUER = "issuer";
    private static final String ACCOUNT = "account";

    private final String scope;
    private final String scopeId;
    private final BigInteger generation;
    private final String digest;
    private final byte[] canonicalBytes;

    private ProjectionValue(
        String scope, String scopeId, BigInteger generation, String digest, byte[] canonicalBytes) {
      this.scope = scope;
      this.scopeId = scopeId;
      this.generation = generation;
      this.digest = digest;
      this.canonicalBytes = canonicalBytes.clone();
    }

    static ProjectionValue fromSource(CurrentSourceEvidence source) {
      Objects.requireNonNull(source, "Verified Account authority source is required");
      final String scope;
      final String scopeId;
      if (source.scope().kind()
          == net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository
              .ScopeKind.ISSUER) {
        scope = ISSUER;
        scopeId = source.scope().issuerId();
        if (!GameSessionAccountDelegationProfile.ISSUER.equals(scopeId)) {
          throw new ProjectionUnavailableException();
        }
      } else {
        throw new ProjectionUnavailableException();
      }
      try {
        IssuerGenerationProjection issuer = IssuerGenerationProjection.fromSource(source);
        return decode(issuer.toJson().getBytes(StandardCharsets.UTF_8), scope, scopeId);
      } catch (IllegalArgumentException unsupportedSource) {
        throw new ProjectionUnavailableException();
      }
    }

    /** Strictly parses canonical owner projection bytes and verifies its preimage digest. */
    public static ProjectionValue decode(
        byte[] bytes, String expectedScope, String expectedScopeId) {
      if (bytes == null || bytes.length == 0 || bytes.length > MAX_PROJECTION_BYTES) {
        throw new ProjectionUnavailableException();
      }
      try {
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        if (ACCOUNT.equals(expectedScope)) {
          AccountGenerationProjection account = AccountGenerationProjection.parse(text);
          if (!expectedScopeId.equals(account.accountId()))
            throw new ProjectionUnavailableException();
          return new ProjectionValue(
              ACCOUNT, account.accountId(), account.generationValue(), sha256(bytes), bytes);
        }
        if (!ISSUER.equals(expectedScope)) throw new ProjectionUnavailableException();
        IssuerGenerationProjection issuer = IssuerGenerationProjection.parse(text);
        if (!expectedScopeId.equals(issuer.issuerId())) throw new ProjectionUnavailableException();
        return new ProjectionValue(
            ISSUER, issuer.issuerId(), issuer.generationValue(), sha256(bytes), bytes);
      } catch (ProjectionUnavailableException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new ProjectionUnavailableException();
      }
    }

    public String scope() {
      return scope;
    }

    public String scopeId() {
      return scopeId;
    }

    public long generation() {
      return generation.longValueExact();
    }

    public BigInteger generationValue() {
      return generation;
    }

    public String digest() {
      return digest;
    }

    public String key() {
      return AccountGameplayDelegationAuthorityProjection.key(scope, scopeId);
    }

    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }

    @Override
    public String toString() {
      return "AccountAuthorityProjection[redacted]";
    }
  }

  public record PublicationObservation(
      String issuerDigest,
      String accountDigest,
      long issuerGeneration,
      long accountGeneration,
      AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome redisOutcome) {
    public PublicationObservation {
      requireDigest(issuerDigest);
      requireDigest(accountDigest);
      if (issuerGeneration <= 0L || accountGeneration <= 0L) {
        throw new IllegalArgumentException("Projected generations must be positive");
      }
      Objects.requireNonNull(redisOutcome);
    }

    @Override
    public String toString() {
      return "AccountAuthorityProjectionPublication[redacted]";
    }
  }

  public record ProjectionObservation(
      String issuerDigest, String accountDigest, long issuerGeneration, long accountGeneration) {
    public ProjectionObservation {
      requireDigest(issuerDigest);
      requireDigest(accountDigest);
      if (issuerGeneration <= 0L || accountGeneration <= 0L) {
        throw new IllegalArgumentException("Observed generations must be positive");
      }
    }

    @Override
    public String toString() {
      return "AccountAuthorityProjectionObservation[non-authorizing]";
    }
  }

  public static final class ProjectionUnavailableException extends IllegalStateException {
    public ProjectionUnavailableException() {
      super("Account authority projection is unavailable or inconsistent");
    }
  }

  public static final class StaleProjectionException extends IllegalStateException {
    public StaleProjectionException() {
      super("Account authority projection is stale or changed during observation");
    }
  }

  private static String key(String scope, String scopeId) {
    return (ProjectionValue.ISSUER.equals(scope) ? ISSUER_KEY_PREFIX : ACCOUNT_KEY_PREFIX)
        + scopeId;
  }

  private static UUID parseCanonicalUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
      return parsed;
    } catch (RuntimeException ex) {
      throw new ProjectionUnavailableException();
    }
  }

  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> input)) throw new ProjectionUnavailableException();
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : input.entrySet()) {
      if (!(entry.getKey() instanceof String key) || entry.getValue() == null) {
        throw new ProjectionUnavailableException();
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static String text(Object value) {
    if (!(value instanceof String text) || text.isEmpty() || text.length() > 2048) {
      throw new ProjectionUnavailableException();
    }
    return text;
  }

  private static String requireBoundedText(Object value, int maxLength) {
    String text = text(value);
    if (text.length() > maxLength || text.isBlank() || !text.equals(text.strip())) {
      throw new ProjectionUnavailableException();
    }
    return text;
  }

  private static long parsePositive(Object value) {
    String text = text(value);
    if (!POSITIVE_DECIMAL.matcher(text).matches()) throw new ProjectionUnavailableException();
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException ex) {
      throw new ProjectionUnavailableException();
    }
  }

  private static long parseNonnegative(Object value) {
    String text = text(value);
    if (!text.matches("0|[1-9][0-9]{0,18}")) throw new ProjectionUnavailableException();
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException ex) {
      throw new ProjectionUnavailableException();
    }
  }

  private static void requireDigest(String digest) {
    if (digest == null || !SHA256.matcher(digest).matches()) {
      throw new IllegalArgumentException("Projection digest is malformed");
    }
  }

  private static byte[] canonical(Object value) {
    try {
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      if (bytes.length == 0 || bytes.length > MAX_PROJECTION_BYTES) {
        throw new ProjectionUnavailableException();
      }
      return bytes;
    } catch (IOException | RuntimeException ex) {
      if (ex instanceof ProjectionUnavailableException unavailable) throw unavailable;
      throw new ProjectionUnavailableException();
    }
  }

  private static byte[] canonicalize(String text) throws IOException {
    return Rfc8785CanonicalJson.canonicalizeUtf8(text);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new ProjectionUnavailableException();
    }
  }
}
