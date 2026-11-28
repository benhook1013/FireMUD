package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.firedevops.firemud.accountservice.repository.ProfileRepository;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual repository SQL against PostgreSQL; isolated projection tables, not migration proof. */
class ProfilePresenceMembershipPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void policyBatchRequiresExactMembershipPairWithoutChangingRetainedProfiles() {
    var data = POSTGRES.dataSource();
    var jdbc = new JdbcTemplate(data);
    jdbc.execute(
        "CREATE TABLE accounts (id bigint PRIMARY KEY, username text, email text, password_hash text, role text, email_verified boolean, login_auth_modes text)");
    jdbc.execute(
        "CREATE TABLE profiles (id bigint PRIMARY KEY, account_id bigint, tenant_id bigint, display_name text, bio text, presence_visibility_policy text)");
    jdbc.execute("CREATE TABLE account_tenant_membership (account_id bigint, tenant_id bigint)");
    jdbc.execute(
        "INSERT INTO accounts VALUES (1,'one','one@example.test','hash','USER',true,'PASSWORD'), (2,'two','two@example.test','hash','USER',true,'PASSWORD'), (3,'three','three@example.test','hash','USER',true,'PASSWORD')");
    jdbc.execute(
        "INSERT INTO profiles VALUES (1,1,10,'one','','PUBLIC'), (2,2,10,'two','','PUBLIC'), (3,3,10,'three','','FRIENDS_ONLY'), (4,1,20,'other','','PUBLIC')");
    jdbc.execute("INSERT INTO account_tenant_membership VALUES (1,10), (2,20)");
    var repository =
        new ProfileRepository(
            DSL.using(data, SQLDialect.POSTGRES, new Settings().withRenderSchema(false)));

    assertThat(repository.findByTenantIdAndAccountIds(10L, List.of(1L, 2L, 3L)))
        .extracting(profile -> profile.getAccount().getId())
        .containsExactly(1L);
    assertThat(repository.findByTenantIdAndAccountIds(20L, List.of(1L))).isEmpty();
    assertThat(repository.findByTenantIdAndAccountIds(10L, List.of(2L, 3L))).isEmpty();
    assertThat(repository.findByTenantIdAndAccountIds(10L, List.of())).isEmpty();
    jdbc.update("INSERT INTO account_tenant_membership VALUES (3,10)");
    assertThat(repository.findByTenantIdAndAccountIds(10L, List.of(1L, 2L, 3L)))
        .extracting(profile -> profile.getAccount().getId())
        .containsExactly(1L, 3L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM profiles", Integer.class)).isEqualTo(4);
  }
}
