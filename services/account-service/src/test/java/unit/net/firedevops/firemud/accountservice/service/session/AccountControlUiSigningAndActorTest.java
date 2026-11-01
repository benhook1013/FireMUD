package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Actual RSA/profile verification and mounted signing. Registry, Account source, historical signer
 * and transaction collaborators below are EXPLICIT SERVICE DOUBLES. No production credential,
 * original lifecycle provenance, SQL transaction or physical mTLS/Coordination proof is asserted.
 */
class AccountControlUiSigningAndActorTest {
  private static final Instant NOW = Instant.parse("2026-10-08T11:00:00Z");
  private static KeyPair key;
  @TempDir Path temporary;

  @BeforeAll
  static void key() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    key = generator.generateKeyPair();
  }

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void distinctMountedSignerProducesExactOriginalProfileAndWipesCallbackCredential()
      throws Exception {
    Fixture f = new Fixture();
    Path privateRoot = Files.createDirectory(temporary.resolve("private"));
    Path publicRoot = Files.createDirectory(temporary.resolve("public"));
    String fingerprint =
        AccountControlUiIssuanceRepository.hash(
            AccountControlUiAuthority.canonical(
                Map.of("kty", "RSA", "n", f.modulus, "e", f.exponent)));
    var expected =
        new AccountMountedJwtSignerBundle.ExpectedIdentity(
            "test-control",
            "test-cluster",
            "test-control",
            UUID.randomUUID().toString(),
            "1",
            "test-only-control",
            fingerprint);
    Files.write(
        privateRoot.resolve("current.key"),
        AccountControlUiAuthority.canonical(
            Map.ofEntries(
                Map.entry("version", 1),
                Map.entry("environmentId", expected.environmentId()),
                Map.entry("clusterId", expected.clusterId()),
                Map.entry("namespace", expected.namespace()),
                Map.entry("operationId", expected.operationId()),
                Map.entry("generation", expected.generation()),
                Map.entry("kid", expected.kid()),
                Map.entry("algorithm", "RS256"),
                Map.entry("privateKeyPkcs8", b64(key.getPrivate().getEncoded())),
                Map.entry("publicKeyFingerprint", fingerprint))));
    Files.write(publicRoot.resolve("jwks.json"), f.jwks);
    var spec =
        new AccountControlUiSigningSpec(
            f.operationId, f.requestId, f.jti, f.snapshot, NOW, NOW.plusSeconds(300));
    AtomicReference<byte[]> retained = new AtomicReference<>();
    AtomicReference<byte[]> callback = new AtomicReference<>();
    String digest =
        AccountMountedJwtSignerBundle.signCommittedControlUiDigest(
            privateRoot,
            Path.of("current.key"),
            publicRoot,
            Path.of("jwks.json"),
            expected,
            spec,
            (hash, bytes) -> {
              callback.set(bytes);
              retained.set(bytes.clone());
              assertThat(hash).isEqualTo(AccountControlUiIssuanceRepository.hash(bytes));
            });
    assertThat(callback.get()).containsOnly((byte) 0);
    assertThat(digest).isEqualTo(AccountControlUiIssuanceRepository.hash(retained.get()));
    var verified = f.actors.verifySigned(new String(retained.get(), StandardCharsets.US_ASCII));
    var verifiedAgain =
        f.actors.verifySigned(new String(retained.get(), StandardCharsets.US_ASCII));
    assertThat(verified.claims.claims())
        .containsEntry("accountId", f.actor.toString())
        .containsEntry("scopedRoles", Map.of(f.tenant.toString(), List.of("tenantAdmin")));
    assertThat(verifiedAgain.claims.claims()).isEqualTo(verified.claims.claims());
    assertThat(verified.claims.claims())
        .doesNotContainKeys("globalRoles", "tokenProfile", "tokenType");
    verify(f.source, times(1)).sourceIdentity();
    verify(f.source, times(1)).load();
  }

  @Test
  void signedControlUiNeedsExactActiveOwnerAndIndependentCurrentCreatorSources() throws Exception {
    Fixture f = new Fixture();
    String compact = f.sign(f.claims);
    f.owner(compact);
    var actor = f.actors.authenticate(compact, f.tenant, f.environment);
    assertThat(actor.accountId()).isEqualTo(f.actor);
    assertThat(actor.tenantId()).isEqualTo(f.tenant);
    assertThat(actor.toString())
        .isEqualTo("AccountControlUiActorService.AuthenticatedActor[redacted]");
    assertThatThrownBy(() -> f.actors.authenticate(compact, UUID.randomUUID(), f.environment))
        .isInstanceOf(IllegalStateException.class);
    when(f.snapshot.evidence()).thenReturn(new byte[] {99});
    assertThatThrownBy(() -> f.actors.authenticate(compact, f.tenant, f.environment))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void committedCurrentnessByJtiRunsActionWithExactRegistryAndSourceInsideOwnerTransaction()
      throws Exception {
    Fixture f = new Fixture();
    String compact = f.sign(f.claims);
    f.owner(compact);
    when(f.signers.requireOriginal(any(byte[].class)))
        .thenReturn(mock(AccountControlUiSignerOwner.Capture.class));
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new byte[] {12};
            })
        .when(f.registry)
        .readActive(anyString());
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return f.snapshot;
            })
        .when(f.authority)
        .capture(f.actor, f.tenant, f.environment);

    String selectedActor =
        f.actors.withCurrentCommitted(
            f.actor,
            f.tenant,
            f.jti,
            f.environment,
            current -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(current.stored()).isSameAs(f.stored);
              return current.source().actor().toString();
            });

    assertThat(selectedActor).isEqualTo(f.actor.toString());
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  @Test
  void committedCurrentnessByJtiRejectsChangedAccountSourceBeforeReturningActor() throws Exception {
    Fixture f = new Fixture();
    String compact = f.sign(f.claims);
    f.owner(compact);
    when(f.signers.requireOriginal(any(byte[].class)))
        .thenReturn(mock(AccountControlUiSignerOwner.Capture.class));
    var changed = mock(AccountControlUiAuthority.Snapshot.class);
    when(changed.evidence()).thenReturn(new byte[] {99});
    when(f.authority.capture(f.actor, f.tenant, f.environment)).thenReturn(changed);

    assertThatThrownBy(
            () ->
                f.actors.withCurrentCommitted(
                    f.actor, f.tenant, f.jti, f.environment, current -> current.source().actor()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void committedCurrentnessByJtiRejectsRevokedOrMismatchedRegistryBeforeSourceRead()
      throws Exception {
    Fixture revoked = new Fixture();
    String revokedCompact = revoked.sign(revoked.claims);
    revoked.owner(revokedCompact);
    String revokedHash =
        AccountControlUiIssuanceRepository.hash(revokedCompact.getBytes(StandardCharsets.US_ASCII));
    doThrow(new IllegalStateException("test-only revoked token"))
        .when(revoked.registry)
        .readActive(revokedHash);
    assertThatThrownBy(
            () ->
                revoked.actors.withCurrentCommitted(
                    revoked.actor,
                    revoked.tenant,
                    revoked.jti,
                    revoked.environment,
                    current -> current.source().actor()))
        .isInstanceOf(IllegalStateException.class);
    verify(revoked.authority, never()).capture(revoked.actor, revoked.tenant, revoked.environment);

    Fixture mismatched = new Fixture();
    String mismatchedCompact = mismatched.sign(mismatched.claims);
    mismatched.owner(mismatchedCompact);
    String mismatchedHash =
        AccountControlUiIssuanceRepository.hash(
            mismatchedCompact.getBytes(StandardCharsets.US_ASCII));
    doReturn(new byte[] {99}).when(mismatched.registry).readActive(mismatchedHash);
    assertThatThrownBy(
            () ->
                mismatched.actors.withCurrentCommitted(
                    mismatched.actor,
                    mismatched.tenant,
                    mismatched.jti,
                    mismatched.environment,
                    current -> current.source().actor()))
        .isInstanceOf(IllegalStateException.class);
    verify(mismatched.authority, never())
        .capture(mismatched.actor, mismatched.tenant, mismatched.environment);
  }

  @Test
  void readinessGameplayForgedAndMissingRegistryCredentialsNeverCreateActor() throws Exception {
    Fixture f = new Fixture();
    for (String audience :
        List.of("account-service", "firemud-account-jwt-readiness", "player-bootstrap")) {
      var wrong = new HashMap<>(f.claims);
      wrong.put("aud", audience);
      assertThatThrownBy(() -> f.actors.authenticate(f.sign(wrong), f.tenant, f.environment))
          .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);
    }
    String compact = f.sign(f.claims);
    String forged = compact.substring(0, compact.lastIndexOf('.') + 1) + "A".repeat(512);
    assertThatThrownBy(() -> f.actors.authenticate(forged, f.tenant, f.environment))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);
    when(f.registry.readActive(anyString()))
        .thenThrow(new IllegalStateException("test-only missing/inactive registry"));
    assertThatThrownBy(() -> f.actors.authenticate(compact, f.tenant, f.environment))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void spoofedSubjectDifferentInitialAssociationAndHistoricalSignerFailureDeny() throws Exception {
    Fixture f = new Fixture();
    var wrong = new HashMap<>(f.claims);
    wrong.put("sub", UUID.randomUUID().toString());
    assertThatThrownBy(() -> f.actors.authenticate(f.sign(wrong), f.tenant, f.environment))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);
    String compact = f.sign(f.claims);
    f.owner(compact);
    when(f.authority.capture(f.actor, f.tenant, f.environment))
        .thenThrow(new IllegalStateException("test-only changed initial association"));
    assertThatThrownBy(() -> f.actors.authenticate(compact, f.tenant, f.environment))
        .isInstanceOf(IllegalStateException.class);
    doReturn(f.snapshot).when(f.authority).capture(f.actor, f.tenant, f.environment);
    when(f.signers.requireOriginal(any(byte[].class)))
        .thenThrow(new IllegalStateException("test-only absent original receipt"));
    assertThatThrownBy(() -> f.actors.authenticate(compact, f.tenant, f.environment))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void accountActorConsumesExactCurrentSourcesAndProducerFenceInsideOwnerTransaction()
      throws Exception {
    Fixture f = new Fixture(true);
    String compact = f.sign(f.claims);
    f.owner(compact);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new byte[] {12};
            })
        .when(f.registry)
        .readActive(anyString());
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return f.snapshot;
            })
        .when(f.authority)
        .captureAccount(f.actor);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return null;
            })
        .when(f.fences)
        .lockProducerSourcesNowait(any());
    var actor =
        f.actors.withCurrentAccount(
            compact,
            current -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              verify(f.fences).lockProducerSourcesNowait(f.snapshot.sources());
              return current;
            });
    assertThat(actor.accountId()).isEqualTo(f.actor);
    assertThat(actor.issuanceOperationId()).isEqualTo(f.operationId);
    assertThat(actor.tokenHash()).isEqualTo(f.stored.tokenHash);
    verify(f.authority, never()).capture(any(), any(), any());
    assertThatThrownBy(() -> f.actors.authenticate(compact, f.tenant, f.environment))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                f.actors.withCurrentCommitted(
                    f.actor, f.tenant, f.jti, f.environment, current -> current))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void accountActorRejectsChangedSourceOrRoleAndNeverInvokesMutation() throws Exception {
    Fixture f = new Fixture(true);
    String compact = f.sign(f.claims);
    f.owner(compact);
    when(f.snapshot.evidence()).thenReturn(new byte[] {99});
    assertThatThrownBy(
            () ->
                f.actors.withCurrentAccount(
                    compact,
                    current -> {
                      throw new AssertionError("mutation must not run");
                    }))
        .isInstanceOf(IllegalStateException.class);
    when(f.snapshot.evidence()).thenReturn(new byte[] {7});
    when(f.snapshot.globalRoles()).thenReturn(List.of());
    assertThatThrownBy(() -> f.actors.authenticateAccount(compact))
        .isInstanceOf(IllegalStateException.class);
    verify(f.fences, never()).lockProducerSourcesNowait(any());
  }

  @Test
  void accountActorRejectsTenantTokenAndLostProducerFence() throws Exception {
    Fixture tenant = new Fixture();
    String tenantJwt = tenant.sign(tenant.claims);
    tenant.owner(tenantJwt);
    assertThatThrownBy(() -> tenant.actors.authenticateAccount(tenantJwt))
        .isInstanceOf(IllegalStateException.class);
    verify(tenant.authority, never()).captureAccount(any());

    Fixture account = new Fixture(true);
    String compact = account.sign(account.claims);
    account.owner(compact);
    doThrow(new IllegalStateException("test-only concurrent source writer"))
        .when(account.fences)
        .lockProducerSourcesNowait(any());
    assertThatThrownBy(
            () ->
                account.actors.withCurrentAccount(
                    compact,
                    current -> {
                      throw new AssertionError("mutation must not run");
                    }))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void accountActorStillRequiresExactRegistryAndOriginalSigner() throws Exception {
    Fixture f = new Fixture(true);
    String compact = f.sign(f.claims);
    f.owner(compact);
    when(f.registry.readActive(anyString())).thenReturn(new byte[] {99});
    assertThatThrownBy(() -> f.actors.authenticateAccount(compact))
        .isInstanceOf(IllegalStateException.class);
    verify(f.authority, never()).captureAccount(any());
    when(f.registry.readActive(anyString())).thenReturn(new byte[] {12});
    when(f.signers.requireOriginal(any(byte[].class)))
        .thenThrow(new IllegalStateException("test-only missing original signer"));
    assertThatThrownBy(
            () ->
                f.actors.withCurrentAccount(
                    compact,
                    current -> {
                      throw new AssertionError("mutation must not run");
                    }))
        .isInstanceOf(IllegalStateException.class);
    verify(f.authority, never()).captureAccount(any());
    verify(f.fences, never()).lockProducerSourcesNowait(any());
  }

  @Test
  void accountSourceCallbacksUseExactOriginalInsideTransactionAndProducerLocks() throws Exception {
    Fixture f = new Fixture(true);
    String compact = f.sign(f.claims);
    f.owner(compact);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new AccountPublicJwksCache.PublicJwksSnapshot(f.pin, f.jwks);
            })
        .when(f.source)
        .load();
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new byte[] {12};
            })
        .when(f.registry)
        .readActive(anyString());
    var currentSources = f.snapshot.sources();
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return null;
            })
        .when(f.fences)
        .lockProducerSourcesNowait(currentSources);
    java.util.function.Function<AccountControlUiActorService.Current, UUID> callback =
        current -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
          assertThat(current.stored()).isSameAs(f.stored);
          assertThat(current.stored().optionalTenantId()).isEmpty();
          assertThat(current.source()).isSameAs(f.snapshot);
          return current.stored().operationId;
        };

    assertThat(f.actors.withCurrentAccountSources(compact, callback)).isEqualTo(f.operationId);
    verify(f.fences).lockProducerSourcesNowait(f.snapshot.sources());
    assertThat(f.actors.withCurrentAccountCommitted(f.actor, f.jti, callback))
        .isEqualTo(f.operationId);
    verify(f.fences, times(2)).lockProducerSourcesNowait(f.snapshot.sources());
    assertThat(f.actors.authenticateAccount(compact).accountId()).isEqualTo(f.actor);
    verify(f.fences, times(3)).lockProducerSourcesNowait(f.snapshot.sources());
    verify(f.authority, never()).capture(any(), any(), any());
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  @Test
  void accountOriginalRecoveryRejectsTenantChangedAccountAndExpiredOriginal() throws Exception {
    Fixture tenant = new Fixture();
    tenant.owner(tenant.sign(tenant.claims));
    assertThatThrownBy(
            () ->
                tenant.actors.withCurrentAccountCommitted(
                    tenant.actor,
                    tenant.jti,
                    current -> {
                      throw new AssertionError("callback must not run");
                    }))
        .isInstanceOf(IllegalStateException.class);
    verify(tenant.authority, never()).captureAccount(any());

    Fixture account = new Fixture(true);
    account.owner(account.sign(account.claims));
    assertThatThrownBy(
            () ->
                account.actors.withCurrentAccountCommitted(
                    UUID.randomUUID(),
                    account.jti,
                    current -> {
                      throw new AssertionError("callback must not run");
                    }))
        .isInstanceOf(IllegalStateException.class);
    verify(account.authority, never()).captureAccount(any());

    Fixture expired = new Fixture(true, NOW.plusSeconds(300));
    expired.owner(expired.sign(expired.claims));
    assertThatThrownBy(
            () ->
                expired.actors.withCurrentAccountCommitted(
                    expired.actor,
                    expired.jti,
                    current -> {
                      throw new AssertionError("callback must not run");
                    }))
        .isInstanceOf(IllegalStateException.class);
    verify(expired.authority, never()).captureAccount(any());
  }

  @Test
  void accountOriginalRecoveryRejectsChangedSourcesRegistrySignerAndProducerFence()
      throws Exception {
    List<java.util.function.Consumer<Fixture>> changes =
        List.of(
            f -> when(f.snapshot.evidence()).thenReturn(new byte[] {99}),
            f -> when(f.snapshot.issuanceFence()).thenReturn(2L),
            f -> when(f.snapshot.globalRoles()).thenReturn(List.of()),
            f ->
                when(f.operations.findTokenJti(f.jti))
                    .thenReturn(f.stored)
                    .thenReturn(f.stored)
                    .thenReturn(null),
            f -> when(f.registry.readActive(anyString())).thenReturn(new byte[] {99}),
            f ->
                when(f.signers.requireOriginal(any(byte[].class)))
                    .thenThrow(new IllegalStateException("test-only missing original signer")),
            f ->
                doThrow(new IllegalStateException("test-only concurrent source writer"))
                    .when(f.fences)
                    .lockProducerSourcesNowait(any()),
            f ->
                when(f.source.load())
                    .thenReturn(
                        new AccountPublicJwksCache.PublicJwksSnapshot(
                            f.pin,
                            AccountControlUiAuthority.canonical(Map.of("keys", List.of())))));
    for (var change : changes) {
      Fixture f = new Fixture(true);
      f.owner(f.sign(f.claims));
      change.accept(f);
      assertThatThrownBy(
              () ->
                  f.actors.withCurrentAccountCommitted(
                      f.actor,
                      f.jti,
                      current -> {
                        throw new AssertionError("callback must not run");
                      }))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  private static final class Fixture {
    final UUID actor = UUID.randomUUID(), tenant = UUID.randomUUID(), jti = UUID.randomUUID();
    final UUID operationId = UUID.randomUUID(), requestId = UUID.randomUUID();
    final String modulus = unsigned(((RSAPublicKey) key.getPublic()).getModulus());
    final String exponent = unsigned(((RSAPublicKey) key.getPublic()).getPublicExponent());
    final byte[] jwks =
        AccountControlUiAuthority.canonical(
            Map.of(
                "keys",
                List.of(
                    Map.of(
                        "kty",
                        "RSA",
                        "kid",
                        "test-only-control",
                        "use",
                        "sig",
                        "alg",
                        "RS256",
                        "key_ops",
                        List.of("verify"),
                        "n",
                        modulus,
                        "e",
                        exponent))));
    final AccountControlUiAuthority.Snapshot snapshot =
        mock(AccountControlUiAuthority.Snapshot.class);
    final AccountControlUiAuthority authority = mock(AccountControlUiAuthority.class);
    final AccountControlUiIssuanceRepository operations =
        mock(AccountControlUiIssuanceRepository.class);
    final AccountControlUiSignerOwner signers = mock(AccountControlUiSignerOwner.class);
    final AccountControlUiCoordination registry = mock(AccountControlUiCoordination.class);
    final CapturedEnvironmentBoundary environment = mock(CapturedEnvironmentBoundary.class);
    final AccountJwtJwksTrustedSource source = mock(AccountJwtJwksTrustedSource.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final AccountPublicJwksCache.SourceIdentity pin =
        new AccountPublicJwksCache.SourceIdentity(
            "test-control",
            "test-cluster",
            UUID.randomUUID().toString(),
            "test-control",
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "test-revision",
            "https://test-api.example.test:6443",
            "a".repeat(64));
    final DraftAuthorizationFenceRepository fences = mock(DraftAuthorizationFenceRepository.class);
    final AccountControlUiActorService actors;
    final Map<String, Object> claims;
    AccountControlUiIssuanceRepository.Stored stored;

    Fixture() {
      this(false);
    }

    Fixture(boolean accountOnly) {
      this(accountOnly, NOW);
    }

    Fixture(boolean accountOnly, Instant clockNow) {
      when(snapshot.scopeKind())
          .thenReturn(
              accountOnly
                  ? AccountControlUiAuthority.ScopeKind.ACCOUNT
                  : AccountControlUiAuthority.ScopeKind.TENANT);
      when(snapshot.optionalTenantId())
          .thenReturn(accountOnly ? java.util.Optional.empty() : java.util.Optional.of(tenant));
      when(snapshot.scopedRoles())
          .thenReturn(accountOnly ? Map.of() : Map.of(tenant.toString(), List.of("tenantAdmin")));
      when(snapshot.globalRoles()).thenReturn(accountOnly ? List.of("support") : List.of());
      if (accountOnly) {
        when(snapshot.sources())
            .thenReturn(
                List.of(
                    new SourceEvidence(
                        SourceKind.ACCOUNT,
                        actor.toString(),
                        "1",
                        "1",
                        "test/account",
                        "1",
                        new byte[] {1}),
                    new SourceEvidence(
                        SourceKind.GLOBAL_ROLES,
                        actor.toString(),
                        "1",
                        "1",
                        "test/account",
                        "1",
                        new byte[] {2}),
                    new SourceEvidence(
                        SourceKind.ISSUER,
                        "firemud-account-service",
                        "1",
                        "1",
                        "test/issuer",
                        "1",
                        new byte[] {3})));
      }
      when(snapshot.actor()).thenReturn(actor);
      when(snapshot.tenant()).thenReturn(tenant);
      when(snapshot.authorityTuple())
          .thenReturn(
              Map.of(
                  "issuerAuthGeneration",
                  1L,
                  "accountAuthorityGeneration",
                  1L,
                  "tenantAuthorityGeneration",
                  Map.of(tenant.toString(), 1L),
                  "membershipAuthorityGeneration",
                  Map.of(tenant.toString(), 1L),
                  "privateRealmGrantVersions",
                  List.of()));
      when(snapshot.membershipVersion())
          .thenReturn(accountOnly ? Map.of() : Map.of(tenant.toString(), 2L));
      if (accountOnly) {
        when(snapshot.authorityTuple())
            .thenReturn(
                Map.of(
                    "issuerAuthGeneration",
                    1L,
                    "accountAuthorityGeneration",
                    1L,
                    "tenantAuthorityGeneration",
                    Map.of(),
                    "membershipAuthorityGeneration",
                    Map.of(),
                    "privateRealmGrantVersions",
                    List.of()));
      }
      when(snapshot.issuanceFence()).thenReturn(1L);
      when(snapshot.evidence()).thenReturn(new byte[] {7});
      claims =
          new AccountControlUiSigningSpec(
                  operationId, requestId, jti, snapshot, NOW, NOW.plusSeconds(300))
              .claims();
      when(source.sourceIdentity()).thenReturn(pin);
      when(source.load()).thenReturn(new AccountPublicJwksCache.PublicJwksSnapshot(pin, jwks));
      when(transactions.getTransaction(any()))
          .thenAnswer(
              ignored -> {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                return new SimpleTransactionStatus();
              });
      doAnswer(
              ignored -> {
                TransactionSynchronizationManager.clear();
                return null;
              })
          .when(transactions)
          .commit(any());
      doAnswer(
              ignored -> {
                TransactionSynchronizationManager.clear();
                return null;
              })
          .when(transactions)
          .rollback(any());
      actors =
          new AccountControlUiActorService(
              operations,
              authority,
              signers,
              registry,
              source,
              fences,
              transactions,
              Clock.fixed(clockNow, ZoneOffset.UTC));
    }

    void owner(String compact) {
      String hash =
          AccountControlUiIssuanceRepository.hash(compact.getBytes(StandardCharsets.US_ASCII));
      Map<String, Object> values = new HashMap<>();
      values.put("request_id", requestId);
      values.put("operation_id", operationId);
      values.put("token_jti", jti);
      values.put("account_uuid", actor);
      values.put("authorization_scope", snapshot.scopeKind().name());
      values.put("tenant_uuid", snapshot.optionalTenantId().orElse(null));
      values.put("caller_context_id", UUID.randomUUID());
      values.put("caller_workload", "test-only-peer");
      values.put("request_mac_key_id", "test-only-mac");
      values.put("request_digest", "a".repeat(64));
      values.put("status", "COMMITTED");
      values.put("token_hash", hash);
      values.put("claims_payload", AccountControlUiAuthority.canonical(claims));
      values.put("source_payload", new byte[] {7});
      values.put("bundle_payload", new byte[] {8});
      values.put(
          "signer_receipt",
          AccountControlUiAuthority.canonical(
              Map.ofEntries(
                  Map.entry("kid", "test-only-control"),
                  Map.entry(
                      "publicKeyFingerprint",
                      AccountJwtJwksTrustedSource.publicKeyFingerprint(
                              new AccountPublicJwksCache.PublicJwksSnapshot(pin, jwks),
                              pin,
                              "test-only-control")
                          .orElseThrow()),
                  Map.entry("environmentId", pin.environmentId()),
                  Map.entry("clusterId", pin.clusterId()),
                  Map.entry("clusterIncarnationUid", pin.clusterIncarnationUid()),
                  Map.entry("namespace", pin.namespace()),
                  Map.entry("namespaceUid", pin.namespaceUid()),
                  Map.entry("publicConfigMapUid", pin.configMapUid()),
                  Map.entry("apiConfigRevision", pin.bindingRevision()))));
      values.put("pending_registry", new byte[] {11});
      values.put("active_registry", new byte[] {12});
      values.put("issued_at_epoch_second", NOW.getEpochSecond());
      values.put("expires_at_epoch_second", NOW.plusSeconds(300).getEpochSecond());
      values.put("registry_cleanup_deadline_epoch_millis", NOW.plusSeconds(600).toEpochMilli());
      values.put(
          "recovery_expires_at", OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));
      Record row = mock(Record.class);
      when(row.get(anyString(), any(Class.class)))
          .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
      stored = new AccountControlUiIssuanceRepository.Stored(row);
      when(operations.findToken(hash)).thenReturn(stored);
      when(operations.findTokenJti(jti)).thenReturn(stored);
      when(registry.readActive(hash)).thenReturn(new byte[] {12});
      when(authority.capture(actor, tenant, environment)).thenReturn(snapshot);
      when(authority.captureAccount(actor)).thenReturn(snapshot);
      var committed = mock(AccountControlUiIssuanceRepository.Committed.class);
      when(committed.tokenHash()).thenReturn(hash);
      when(operations.requireCommitted(stored)).thenReturn(committed);
    }

    String sign(Map<String, Object> value) throws Exception {
      String input =
          b64(
                  AccountControlUiAuthority.canonical(
                      Map.of("alg", "RS256", "kid", "test-only-control", "typ", "JWT")))
              + "."
              + b64(AccountControlUiAuthority.canonical(value));
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(key.getPrivate());
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + b64(signer.sign());
    }
  }

  private static String unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes[0] == 0) {
      bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return b64(bytes);
  }

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
