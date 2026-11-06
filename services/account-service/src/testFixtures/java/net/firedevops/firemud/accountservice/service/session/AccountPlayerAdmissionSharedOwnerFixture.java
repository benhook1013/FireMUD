package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.GameplayLoginSourceContext;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.accountservice.dto.CanonicalGameplayLoginRequest;
import net.firedevops.firemud.accountservice.dto.GameplayCredentialSourceContext;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginResult;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthEvidenceBundleRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.GameplayLoginReplayState;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjectionRedisScriptContribution;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource.CredentialDigestKey;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared-source Account LOGIN composition for the original creator or a separate global player. SQL
 * owners, primary verification, signer correspondence, encrypted response recovery, and physical
 * Redis registration/projection are real; upstream creator/legal/platform evidence, key custody,
 * the newly stored OTP, and the verified caller argument remain controlled inputs.
 *
 * <p>This helper borrows the original fixture's connections and does not activate runtime wiring.
 * Its ACTIVE tenantAdmin creator membership remains non-admitting. LOGIN establishes global
 * identity only; no positive membership, entitlement, connect scope, or PLAY authority is made.
 */
public final class AccountPlayerAdmissionSharedOwnerFixture {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int REGISTRY_BYTES =
      GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES;
  private static final long REGISTRY_CLEANUP_MILLIS = 30_000L;
  private final AccountControlUiOwnerSourcesFixture sources;
  private final Account account;
  private final AccountJwtJwksTrustedSource publicSource;
  private final Clock clock;
  private final AccountCoordinationPinnedConnectionProvider connections;
  private final AccountGameplayDelegationIssuanceRepository issuance;
  private final AccountAuthoritySourceEvidenceRepository authoritySources;
  private final AccountGameplayDelegationAuthorityProjection projection;
  private final AccountGameplayCanonicalLoginOwner login;
  private final AccountGameplayPrimaryCredentialVerifier verifier;
  private final AccountGameplayDelegationCommittedIssuanceOwner committedOwner;
  private final String otp = "shared-login-" + UUID.randomUUID();

  public AccountPlayerAdmissionSharedOwnerFixture(
      AccountControlUiOriginalOrderFixture original, Path temporary) throws Exception {
    this(original, temporary, Clock.systemUTC());
  }

  public AccountPlayerAdmissionSharedOwnerFixture(
      AccountControlUiOriginalOrderFixture original, Path temporary, Clock clock) throws Exception {
    this(original, temporary, clock, false);
  }

  static AccountPlayerAdmissionSharedOwnerFixture freshGlobalPlayer(
      AccountControlUiOriginalOrderFixture original, Path temporary, Clock clock) throws Exception {
    return new AccountPlayerAdmissionSharedOwnerFixture(original, temporary, clock, true);
  }

  private AccountPlayerAdmissionSharedOwnerFixture(
      AccountControlUiOriginalOrderFixture original,
      Path temporary,
      Clock clock,
      boolean separateGlobalPlayer)
      throws Exception {
    Objects.requireNonNull(original, "Original Account owner fixture is required");
    this.clock = Objects.requireNonNull(clock, "Clock is required");
    sources = original.preparedOriginalCreator().sources();
    account =
        separateGlobalPlayer
            ? sources.createGlobalAccount("shared-player-" + UUID.randomUUID())
            : sources.account;
    publicSource = original.publicSource;
    connections = original.gameplayConnectionProvider();
    assertCreatorRemainsNonAdmitting();
    assertThat(sources.challenges.findByAccountId(account.getId())).isEmpty();

    var generations = new AccountAuthorityGenerationRepository(sources.dsl);
    var outbox = new AccountAuthorityOutboxRepository(sources.dsl);
    authoritySources =
        new AccountAuthoritySourceEvidenceRepository(sources.dsl, generations, outbox);
    sources.tx(
        () -> {
          authoritySources.initializeIssuerIfAbsent(GameSessionAccountDelegationProfile.ISSUER);
          return null;
        });
    var bundles = new AccountAuthEvidenceBundleRepository(sources.dsl, generations, outbox);
    var issuanceTarget =
        new AccountGameplayDelegationIssuanceRepository(sources.dsl, authoritySources, bundles);
    // Preserve MANDATORY writes and independent REQUIRES_NEW committed-proof readbacks.
    var issuanceProxy = new ProxyFactory(issuanceTarget);
    issuanceProxy.setProxyTargetClass(true);
    issuanceProxy.addAdvice(
        new TransactionInterceptor(sources.manager, new AnnotationTransactionAttributeSource()));
    issuance =
        (AccountGameplayDelegationIssuanceRepository)
            issuanceProxy.getProxy(
                AccountGameplayDelegationIssuanceRepository.class.getClassLoader());
    var cryptography =
        new AccountResponseEnvelopeCryptography(
            new AccountResponseEnvelopeKeyring(writeResponseKeyring(temporary).toString()));
    var envelopes =
        new AccountGameplayDelegationResponseEnvelopeRepository(
            sources.dsl, authoritySources, bundles, issuance, cryptography);
    var lifecycle = original.signerFixture;
    var signer =
        new AccountGameplayDelegationSigner(
            issuance,
            lifecycle.desired,
            lifecycle.trust,
            lifecycle.api,
            original.publicSource,
            new AccountGameplayDelegationResponseEnvelopeService(envelopes),
            sources.manager,
            clock,
            lifecycle.privateRoot,
            Path.of("current.key"),
            lifecycle.publicRoot,
            Path.of("jwks.json"));
    var redis =
        new AccountGameplayDelegationRedisClient(
            connections,
            loginScriptCatalog(),
            new AcknowledgementRequirements(1, 1, 5_000),
            clock,
            REGISTRY_BYTES,
            AccountPlayerAdmissionSharedOwnerFixture.class.getClassLoader());
    projection =
        new AccountGameplayDelegationAuthorityProjection(authoritySources, sources.manager, redis);
    var registry =
        new AccountGameplayDelegationTokenRegistry(
            issuance, sources.manager, redis, clock, REGISTRY_BYTES, REGISTRY_CLEANUP_MILLIS);
    var commit =
        new AccountGameplayDelegationIssuanceCommitService(
            signer, registry, projection, issuance, sources.manager, clock);
    committedOwner =
        new AccountGameplayDelegationCommittedIssuanceOwner(
            issuance, signer, registry, sources.manager, clock);
    var recovery =
        new AccountGameplayDelegationResponseRecoveryOwner(
            envelopes, committedOwner, sources.manager);
    verifier = new AccountGameplayPrimaryCredentialVerifier(sources.primary);
    login =
        new AccountGameplayCanonicalLoginOwner(
            sources.accounts,
            issuance,
            digestKeys(clock),
            commit,
            recovery,
            sources.manager,
            clock);
    // Control-UI consumed the original challenge. Never resurrect or reuse that consumed row.
    sources.installGlobalOtp(account, otp);
  }

  public UUID accountId() {
    return account.getAccountUuid();
  }

  public FreshTenantCreationEvidence creationEvidence() {
    return sources.creationEvidence;
  }

  /** The test supplies caller coordinates; the actual owner binds and consumes the fresh OTP. */
  public CanonicalGameplayLoginRequest loginRequest(
      UUID requestId, GameplayCredentialSourceContext context) {
    return new CanonicalGameplayLoginRequest(requestId, account.getEmail(), otp, context);
  }

  /**
   * Real stored OTP input for the canonical receiver; source/socket custody is caller-controlled.
   */
  public AuthenticateRequest wireLoginRequest(
      UUID requestId, GameplayCredentialSourceContext context) {
    var request = loginRequest(requestId, context);
    if (!"trusted_tcp_proxy".equals(context.transportClass())) {
      throw new IllegalArgumentException("Canonical receiver requires trusted TCP Proxy context");
    }
    return AuthenticateRequest.newBuilder()
        .setRequestId(request.requestId().toString())
        .setEmail(account.getEmail())
        .setPassword(otp)
        .setSourceContext(
            GameplayLoginSourceContext.newBuilder()
                .setCallerContextId(context.contextId().toString())
                .setCanonicalClientAddress(context.canonicalClientAddress())
                .setTransportClass(
                    GameplayLoginTransportClass.GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY))
        .build();
  }

  /** The caller must install the real TLS peer interceptor on its private mTLS server. */
  public AccountGameplayAuthenticateReceiver authenticateReceiver(String namespace) {
    if (!creationEvidence().targetNamespace().equals(namespace)) {
      throw new IllegalArgumentException("Receiver namespace must match the shared tenant source");
    }
    return new AccountGameplayAuthenticateReceiver(login, verifier, namespace);
  }

  /** Actual receipt-backed public source, not a replacement JWKS document. */
  public AccountJwtJwksTrustedSource trustedJwksSource() {
    return publicSource;
  }

  AccountGameplayAdmissionCallerVerifier admissionCallerVerifier() {
    return new AccountGameplayAdmissionCallerVerifier(publicSource, committedOwner, clock);
  }

  AccountGameplayDelegationIssuanceRepository issuanceRepository() {
    return issuance;
  }

  /** Direct owner invocation, not evidence of an authenticated transport or deployed caller. */
  public InitialGameplayLoginResult authenticate(
      CanonicalGameplayLoginRequest request, String verifiedGameSessionWorkload) {
    return login.authenticate(request, verifiedGameSessionWorkload, verifier);
  }

  /** Exact SQL, active Redis, and projection readback; identity remains distinct from admission. */
  public void assertCommittedNonAdmittingLogin(InitialGameplayLoginResult result) {
    assertThat(result.accountId()).isEqualTo(accountId());
    assertThat(result.profile()).isEqualTo(GameSessionAccountDelegationProfile.PROFILE);
    assertCommittedNonAdmittingLogin(
        result.requestId(),
        result.callerContextId(),
        result.tokenJti(),
        result.tokenSha256(),
        result.expiresAtEpochSecond());
  }

  /** Compares nonsecret receiving-client evidence to actual SQL, Redis and current sources. */
  public void assertCommittedNonAdmittingLogin(
      UUID requestId,
      UUID callerContextId,
      UUID tokenJti,
      String tokenSha256,
      long expiresAtEpochSecond) {
    assertThat(sources.challenges.findByAccountId(account.getId())).isEmpty();
    var replay = sources.tx(() -> issuance.readCurrentGameplayLoginReplay(requestId).orElseThrow());
    assertThat(replay.state()).isEqualTo(GameplayLoginReplayState.COMMITTED);
    var committed = sources.tx(() -> issuance.readCurrentCommittedCandidate(requestId));
    assertThat(committed.identity()).isEqualTo(replay.identity());
    assertThat(committed.identity().accountId()).isEqualTo(accountId());
    assertThat(committed.identity().callerContextId()).isEqualTo(callerContextId);
    assertThat(committed.identity().tokenJti()).isEqualTo(tokenJti);
    assertThat(committed.tokenSha256()).isEqualTo(tokenSha256);
    assertThat(committed.commitProofSha256()).isNotBlank();
    assertThat(committed.registrationLocalAofCount()).isGreaterThanOrEqualTo(1L);
    assertThat(committed.registrationReplicaAofCount()).isGreaterThanOrEqualTo(1L);
    assertThat(committed.registryAbsoluteExpiryMillis())
        .isEqualTo(
            Math.addExact(
                Math.multiplyExact(expiresAtEpochSecond, 1_000L), REGISTRY_CLEANUP_MILLIS));
    var snapshot =
        sources.tx(
            () ->
                authoritySources.readCurrentIssuerAccountSources(
                    GameSessionAccountDelegationProfile.ISSUER, accountId()));
    byte[][] expected =
        AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(snapshot);
    var observation = projection.observeCurrent(accountId());
    assertThat(observation.issuerGeneration()).isEqualTo(snapshot.issuer().generation());
    assertThat(observation.accountGeneration()).isEqualTo(snapshot.account().generation());
    try (var connection = connections.openPinnedConnection()) {
      var commands = connection.sync();
      assertThat(commands.aclWhoami())
          .isEqualTo(AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY);
      byte[] tokenKey = ascii(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + tokenSha256);
      var active =
          GameSessionAccountDelegationRegistryRecord.decode(commands.get(tokenKey), REGISTRY_BYTES);
      assertThat(active.state()).isEqualTo("active");
      assertThat(active.tokenHash()).isEqualTo(tokenSha256);
      assertThat(active.requestId()).isEqualTo(requestId.toString());
      assertThat(commands.pexpiretime(tokenKey))
          .isEqualTo(committed.registryAbsoluteExpiryMillis());
      assertThat(
              commands.get(
                  ascii(
                      AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX
                          + GameSessionAccountDelegationProfile.ISSUER)))
          .containsExactly(expected[0]);
      assertThat(
              commands.get(
                  ascii(
                      AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX
                          + accountId())))
          .containsExactly(expected[1]);
    }
    assertCreatorRemainsNonAdmitting();
    if (!accountId().equals(sources.account.getAccountUuid())) {
      sources.tx(
          () -> {
            var fresh =
                new FreshTenantIdentityAssociationRepository(
                    sources.dsl, creationEvidence().targetNamespace());
            var memberships =
                new AccountTenantMembershipRepository(
                    sources.dsl,
                    sources.accounts,
                    fresh,
                    new AccountMembershipPairAuthorityRepository(sources.dsl));
            assertThat(memberships.findFreshMembership(accountId(), sources.tenant)).isEmpty();
            return null;
          });
    }
  }

  /** Real canonical membership and role readback, with the original source tuple unchanged. */
  public void assertCreatorRemainsNonAdmitting() {
    UUID creatorId = sources.account.getAccountUuid();
    sources.tx(
        () -> {
          var fresh =
              new FreshTenantIdentityAssociationRepository(
                  sources.dsl, creationEvidence().targetNamespace());
          assertThat(fresh.read(sources.tenant)).contains(creationEvidence());
          var pairs = new AccountMembershipPairAuthorityRepository(sources.dsl);
          var memberships =
              new AccountTenantMembershipRepository(sources.dsl, sources.accounts, fresh, pairs);
          var membership = memberships.findFreshMembership(creatorId, sources.tenant).orElseThrow();
          assertThat(membership.getLifecycleState()).isEqualTo("ACTIVE");
          assertThat(membership.getAuthorityProvenance()).isEqualTo("TENANT_CREATION");
          assertThat(membership.isGameplayAdmissionAllowed()).isFalse();
          var pair = pairs.readPositive(creatorId, sources.tenant).orElseThrow();
          var roles =
              new AccountTenantMembershipRoleSnapshotRepository(sources.dsl)
                  .findForCanonicalUpdate(
                      creatorId,
                      sources.tenant,
                      pair.provenance(),
                      membership.getId(),
                      membership.getMembershipVersion())
                  .orElseThrow();
          assertThat(roles.roles()).containsExactly("tenantAdmin");
          return null;
        });
  }

  private static Path writeResponseKeyring(Path temporary) throws IOException {
    Path root =
        Files.createTempDirectory(Files.createDirectories(temporary), "shared-login-keyring-")
            .toAbsolutePath();
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    try {
      Files.writeString(
          root.resolve("keyring"),
          "firemud-account-response-envelope-keyring-v1\nactive shared-login "
              + Base64.getUrlEncoder().withoutPadding().encodeToString(key)
              + "\n",
          StandardCharsets.US_ASCII);
    } finally {
      Arrays.fill(key, (byte) 0);
    }
    return root;
  }

  private static AccountGameplayCredentialRequestDigestKeySource digestKeys(Clock clock) {
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    CredentialDigestKey digest;
    try {
      digest =
          new CredentialDigestKey(
              "shared-login",
              new SecretKeySpec(key, "HmacSHA256"),
              Instant.now(clock).plusSeconds(3_600));
    } finally {
      Arrays.fill(key, (byte) 0);
    }
    return new AccountGameplayCredentialRequestDigestKeySource() {
      @Override
      public CredentialDigestKey currentKey() {
        return digest;
      }

      @Override
      public CredentialDigestKey requireKey(String keyId) {
        if (!digest.keyId().equals(keyId)) {
          throw new IllegalStateException("Unknown test-only LOGIN digest key");
        }
        return digest;
      }
    };
  }

  private static RedisScriptCatalog loginScriptCatalog() throws IOException {
    List<RedisScriptDescriptor> descriptors = new ArrayList<>();
    var json = JsonMapper.builder().build();
    for (String resource :
        List.of(
            "/redis/scripts/account-game-session-delegation-pending-register.json",
            "/redis/scripts/account-game-session-delegation-activate-committed.json",
            "/redis/scripts/account-game-session-delegation-authority-project-issuer.json")) {
      try (var input =
          AccountPlayerAdmissionSharedOwnerFixture.class.getResourceAsStream(resource)) {
        if (input == null) throw new IllegalStateException("Account LOGIN descriptor is absent");
        descriptors.add(json.readValue(input, RedisScriptDescriptor.class));
      }
    }
    return RedisScriptCatalog.fromContributions(
        List.of(
            new AccountGenerationProjectionRedisScriptContribution(),
            new RedisScriptContribution() {
              @Override
              public String ownerId() {
                return "account-service";
              }

              @Override
              public Collection<RedisScriptDescriptor> descriptors() {
                return List.copyOf(descriptors);
              }
            }));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
