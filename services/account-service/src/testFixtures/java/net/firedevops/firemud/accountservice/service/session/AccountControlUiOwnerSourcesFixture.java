package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mkammerer.argon2.Argon2Factory;
import io.grpc.Context;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftSourceCompositionService;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsCatalogVersion;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEncoding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBinding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBindingRepository;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsRepository;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCreatorMembershipSourceReader;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapAuthorizationSource;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapService;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.mapstruct.factory.Mappers;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real Account source/auth owners with explicitly stipulated upstream creator/legal inputs. */
final class AccountControlUiOwnerSourcesFixture {
  private static final String NAMESPACE = "control-ui-owner-proof";
  static final String CALLER =
      "spiffe://firemud/ns/control-ui-owner-proof/sa/logging-admin-service";
  public static final String OTP = "test-only-original-creator-otp";
  final DSLContext dsl;
  final TransactionTemplate transactions;
  final Account account;
  final AccountRepository accounts;
  final UUID tenant;
  final UUID request = UUID.randomUUID(), callerContext = UUID.randomUUID();
  final AtomicReference<HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary> boundary =
      new AtomicReference<>(testBoundary("test-production"));
  final AtomicInteger coordinationCalls = new AtomicInteger();
  final AccountEmailLoginChallengeRepository challenges;
  final AccountHostedTermsService terms;
  final AccountControlUiAuthority authority;
  final AccountControlUiIssuanceService service;
  final DataSourceTransactionManager manager;
  private final DriverManagerDataSource migrationSource;
  private final String migrationSchema;
  final DraftAuthorizationFenceRepository fences;
  final AccountServiceImpl primary;

  /**
   * Reuses actual Account/source/auth owners while a composed test supplies signer/Redis owners.
   */
  AccountControlUiOwnerSourcesFixture(
      String jdbcUrl, String username, String password, Path root, boolean installNegativeIssuer)
      throws Exception {
    this(jdbcUrl, username, password, root, installNegativeIssuer, UUID.randomUUID());
  }

  AccountControlUiOwnerSourcesFixture(
      String jdbcUrl,
      String username,
      String password,
      Path root,
      boolean installNegativeIssuer,
      UUID tenant)
      throws Exception {
    this(jdbcUrl, username, password, root, installNegativeIssuer, tenant, null);
  }

  /**
   * Test-only predecessor frontier; production owners and the complete migration history remain
   * unchanged.
   */
  AccountControlUiOwnerSourcesFixture(
      String jdbcUrl,
      String username,
      String password,
      Path root,
      boolean installNegativeIssuer,
      UUID tenant,
      String migrationTarget)
      throws Exception {
    if (migrationTarget != null && !"160.1".equals(migrationTarget) && !"1006".equals(migrationTarget) && !"1007".equals(migrationTarget) && !"1009".equals(migrationTarget)) {
