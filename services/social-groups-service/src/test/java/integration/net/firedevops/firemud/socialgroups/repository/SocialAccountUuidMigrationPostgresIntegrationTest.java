package integration.net.firedevops.firemud.socialgroups.repository;

import static net.firedevops.firemud.socialgroups.test.AccountUuidFixtures.accountUuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.socialgroups.entity.AccountFriendLink;
import net.firedevops.firemud.socialgroups.entity.ChatMessage;
import net.firedevops.firemud.socialgroups.entity.Guild;
import net.firedevops.firemud.socialgroups.entity.GuildMember;
import net.firedevops.firemud.socialgroups.entity.MailMessage;
import net.firedevops.firemud.socialgroups.enums.ChatType;
import net.firedevops.firemud.socialgroups.repository.AccountFriendLinkRepository;
import net.firedevops.firemud.socialgroups.repository.ChatMessageRepository;
import net.firedevops.firemud.socialgroups.repository.GuildMemberRepository;
import net.firedevops.firemud.socialgroups.repository.GuildRepository;
import net.firedevops.firemud.socialgroups.repository.MailMessageRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.CloseableDSLContext;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical PostgreSQL migration and generated-jOOQ proof; no mocked SQL success. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class SocialAccountUuidMigrationPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static final List<String> ACCOUNT_TABLES =
      List.of(
          "guilds",
          "guild_members",
          "chat_messages",
          "friend_links",
          "mail_messages",
          "account_friend_links");

  @Test
  void emptyLegacyFrontierConvergesAndTypedRepositoriesRoundTripCanonicalAccountUuids() {
    String schema = schema();
    migrate(schema, "1002");
    List<String> parentHistory;
    try (var dsl = database(schema)) {
      assertParentFrontier(dsl);
      parentHistory = migrationHistory(dsl);
    }
    migrate(schema, "1003");
    try (var dsl = database(schema)) {
      List<String> currentHistory = migrationHistory(dsl);
      assertThat(currentHistory).hasSize(parentHistory.size() + 1);
      assertThat(currentHistory.subList(0, parentHistory.size())).isEqualTo(parentHistory);
      assertThat(
              dsl.fetchCount(
                  DSL.table("flyway_schema_history"),
                  DSL.field("version")
                      .eq("1003")
                      .and(DSL.field("script").eq("V1003__canonical_account_uuid_carriers.sql"))
                      .and(DSL.field("success").eq(true))))
          .isOne();
      assertThat(
              dsl.fetch(
                      "SELECT data_type FROM information_schema.columns WHERE table_schema = ? "
                          + "AND column_name IN ('account_id', 'friend_account_id', 'owner_account_id', 'sender_account_id', 'recipient_account_id')",
                      schema)
                  .getValues(0, String.class))
          .hasSize(10)
          .containsOnly("uuid");
      assertThat(
              dsl.fetch(
                      "SELECT data_type FROM information_schema.columns WHERE table_schema = ? "
                          + "AND column_name IN ('id', 'tenant_id', 'guild_id', 'city_id')",
                      schema)
                  .getValues(0, String.class))
          .containsOnly("bigint");

      Guild guild = new Guild();
      guild.setTenantId(7L);
      guild.setName("UUID guild");
      guild.setOwnerAccountId(accountUuid(1L));
      guild.setCreatedAt(Instant.parse("2026-10-30T00:00:00Z"));
      GuildRepository guilds = new GuildRepository(dsl);
      guilds.save(guild);
      guild.setName("Updated UUID guild");
      assertThat(guilds.save(guild).getOwnerAccountId()).isEqualTo(accountUuid(1L));

      GuildMember member = new GuildMember();
      member.setTenantId(7L);
      member.setGuildId(guild.getId());
      member.setAccountId(accountUuid(2L));
      member.setRole("member");
      GuildMemberRepository members = new GuildMemberRepository(dsl);
      members.save(member);
      assertThat(
              members.findFirstByTenantIdAndGuildIdAndAccountId(7L, guild.getId(), accountUuid(2L)))
          .isPresent()
          .get()
          .extracting(GuildMember::getAccountId)
          .isEqualTo(accountUuid(2L));

      AccountFriendLinkRepository friends = new AccountFriendLinkRepository(dsl);
      friends.save(link(accountUuid(1L), accountUuid(2L)));
      assertThat(
              friends.findMutuallyAcceptedByTenantIdAndAccountIdAndStatus(
                  7L, accountUuid(1L), "active"))
          .isEmpty();
      friends.save(link(accountUuid(2L), accountUuid(1L)));
      assertThat(
              friends.findMutuallyAcceptedByTenantIdAndAccountIdAndStatus(
                  7L, accountUuid(1L), "active"))
          .extracting(AccountFriendLink::getFriendAccountId)
          .containsExactly(accountUuid(2L));
      assertThat(
              friends.findMutuallyAcceptedByTenantIdAndAccountIdAndStatus(
                  8L, accountUuid(1L), "active"))
          .isEmpty();
      friends.save(link(accountUuid(1L), accountUuid(3L)));
      var account =
          org.mockito.Mockito.mock(net.firedevops.firemud.socialgroups.client.AccountClient.class);
      var presence =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.socialgroups.client.GameSessionClient.class);
      org.mockito.Mockito.when(account.getPresenceVisibilityPolicies(7L, List.of(accountUuid(2L))))
          .thenReturn(
              Map.of(
                  accountUuid(2L),
                  net.firedevops.firemud.socialgroups.dto.FriendPresenceVisibilityPolicyValue
                      .PRIVATE));
      var friendService =
          new net.firedevops.firemud.socialgroups.service.impl.FriendServiceImpl(
              friends, presence, account);
      assertThat(friendService.getFriend(7L, accountUuid(1L), accountUuid(3L))).isEmpty();
      var roster = friendService.listFriends(7L, accountUuid(1L));
      assertThat(roster.friends()).hasSize(1);
      assertThat(roster.friends().getFirst().friendAccountId()).isEqualTo(accountUuid(2L));
      assertThat(roster.friends().getFirst().presence().visibilityPolicy()).isNull();
      assertThat(roster.friends().getFirst().presence().lastSeenAt()).isNull();
      assertThat(roster.friends().getFirst().presence().online()).isFalse();
      org.mockito.Mockito.verifyNoInteractions(presence);

      ChatMessage message = new ChatMessage();
      message.setTenantId(7L);
      message.setSenderAccountId(accountUuid(1L));
      message.setRecipientAccountId(accountUuid(2L));
      message.setContent("ordinary account message");
      message.setTimestamp(Instant.now());
      message.setEffectId("uuid-message");
      message.setType(ChatType.TELL);
      ChatMessageRepository chat = new ChatMessageRepository(dsl);
      chat.save(message);
      assertThat(chat.findByTenantIdAndEffectId(7L, "uuid-message"))
          .isPresent()
          .get()
          .extracting(ChatMessage::getSenderAccountId, ChatMessage::getRecipientAccountId)
          .containsExactly(accountUuid(1L), accountUuid(2L));
      message.setRecipientAccountId(null);
      assertThat(chat.save(message).getRecipientAccountId()).isNull();

      MailMessage mail = new MailMessage();
      mail.setTenantId(7L);
      mail.setSenderAccountId(accountUuid(1L));
      mail.setRecipientAccountId(accountUuid(2L));
      mail.setSubject("subject");
      mail.setContent("body");
      mail.setSentAt(Instant.now());
      MailMessageRepository mails = new MailMessageRepository(dsl);
      mails.save(mail);
      assertThat(mails.save(mail).getRecipientAccountId()).isEqualTo(accountUuid(2L));
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "INSERT INTO friend_links (tenant_id, account_id, friend_account_id, status) VALUES (7, ?::uuid, ?::uuid, 'active')",
                      "00000000-0000-0000-0000-000000000000",
                      accountUuid(2L)))
          .hasMessageContaining("ck_friend_account_uuid_non_nil");
      assertThatThrownBy(
              () -> members.findFirstByTenantIdAndGuildIdAndAccountId(7L, guild.getId(), "2"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "guilds",
        "guild_members",
        "chat_messages",
        "friend_links",
        "mail_messages",
        "account_friend_links"
      })
  void anyRetainedLegacyAccountReferenceRefusesBeforeDdlAndPreservesAllRowImages(String table) {
    String schema = schema();
    migrate(schema, "1002");
    try (var dsl = database(schema)) {
      assertParentFrontier(dsl);
      List<String> historyBefore = migrationHistory(dsl);
      switch (table) {
        case "guilds" ->
            dsl.execute(
                "INSERT INTO guilds (id, tenant_id, name, owner_account_id) VALUES (11, 7, 'retained', 101)");
        case "guild_members" -> {
          dsl.execute(
              "INSERT INTO guilds (id, tenant_id, name, owner_account_id) VALUES (11, 7, 'retained', 101)");
          dsl.execute(
              "INSERT INTO guild_members (tenant_id, guild_id, account_id, role) VALUES (7, 11, 102, 'member')");
        }
        case "chat_messages" ->
            dsl.execute(
                "INSERT INTO chat_messages (tenant_id, sender_account_id, recipient_account_id, content) VALUES (7, 101, 102, 'retained chat')");
        case "friend_links", "account_friend_links" ->
            dsl.execute(
                "INSERT INTO "
                    + table
                    + " (tenant_id, account_id, friend_account_id, status) VALUES (7, 101, 102, 'active')");
        case "mail_messages" ->
            dsl.execute(
                "INSERT INTO mail_messages (tenant_id, sender_account_id, recipient_account_id, subject, content) VALUES (7, 101, 102, 'retained', 'mail')");
        default -> throw new AssertionError(table);
      }
      Map<String, List<String>> before = rowImages(dsl);
      assertThatThrownBy(() -> migrate(schema, "1003"))
          .hasStackTraceContaining("SOCIAL_ACCOUNT_UUID_MIGRATION_BLOCKED");
      assertThat(rowImages(dsl)).isEqualTo(before);
      assertThat(migrationHistory(dsl)).isEqualTo(historyBefore);
      assertThat(
              dsl.fetch(
                      "SELECT data_type FROM information_schema.columns WHERE table_schema = ? "
                          + "AND column_name IN ('account_id', 'friend_account_id', 'owner_account_id', 'sender_account_id', 'recipient_account_id')",
                      schema)
                  .getValues(0, String.class))
          .hasSize(10)
          .containsOnly("bigint");
      assertThat(
              dsl.fetchCount(DSL.table("flyway_schema_history"), DSL.field("version").eq("1003")))
          .isZero();
    }
  }

  @Test
  void concurrentWriterCommitsBeforeEmptyCheckAndItsEvidenceIsPreserved() throws Exception {
    String schema = schema();
    migrate(schema, "1002");
    List<String> historyBefore;
    Map<String, List<String>> expectedRows;
    try (var dsl = database(schema)) {
      assertParentFrontier(dsl);
      historyBefore = migrationHistory(dsl);
      expectedRows = rowImages(dsl);
    }
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection writer =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      writer.setAutoCommit(false);
      int writerPid;
      try (Statement statement = writer.createStatement()) {
        statement.setQueryTimeout(5);
        statement.execute("SET search_path TO " + schema);
        try (var result = statement.executeQuery("SELECT pg_backend_pid()")) {
          result.next();
          writerPid = result.getInt(1);
        }
        statement.executeUpdate(
            "INSERT INTO mail_messages (tenant_id, sender_account_id, recipient_account_id, subject, content) VALUES (7, 9007199254740993, 102, 'retained', 'concurrent mail')");
        try (var result =
            statement.executeQuery("SELECT row_to_json(m)::text FROM mail_messages m")) {
          result.next();
          expectedRows.put("mail_messages", List.of(result.getString(1)));
        }
      }
      Future<?> migration = executor.submit(() -> migrate(schema, "1003"));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      boolean blocked = false;
      try (Connection observer =
              DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var query =
              observer.prepareStatement(
                  "SELECT EXISTS (SELECT 1 FROM pg_locks l WHERE l.relation = ?::regclass AND l.mode = 'AccessExclusiveLock' AND NOT l.granted AND ? = ANY(pg_blocking_pids(l.pid)))")) {
        query.setQueryTimeout(2);
        query.setString(1, schema + ".mail_messages");
        query.setInt(2, writerPid);
        while (System.nanoTime() < deadline && !migration.isDone()) {
          try (var result = query.executeQuery()) {
            result.next();
            blocked = result.getBoolean(1);
          }
          if (blocked) {
            break;
          }
          Thread.sleep(25);
        }
      }
      assertThat(blocked).as("V1003 must wait on the uncommitted legacy mail writer").isTrue();
      assertThat(migration.isDone()).isFalse();
      writer.commit();
      assertThatThrownBy(() -> migration.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasStackTraceContaining("SOCIAL_ACCOUNT_UUID_MIGRATION_BLOCKED");
      try (var dsl = database(schema)) {
        assertThat(rowImages(dsl)).isEqualTo(expectedRows);
        assertThat(migrationHistory(dsl)).isEqualTo(historyBefore);
        assertThat(
                dsl.fetch(
                        "SELECT data_type FROM information_schema.columns WHERE table_schema = ? AND column_name IN ('account_id', 'friend_account_id', 'owner_account_id', 'sender_account_id', 'recipient_account_id')",
                        schema)
                    .getValues(0, String.class))
            .hasSize(10)
            .containsOnly("bigint");
      }
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(25, TimeUnit.SECONDS))
          .as("bounded migration task must finish before fixture reuse")
          .isTrue();
    }
  }

  private static void assertParentFrontier(DSLContext dsl) {
    assertThat(
            dsl.fetch(
                    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .getValues(0, String.class))
        .endsWith("1000", "1001", "1002");
    assertThat(
            dsl.fetchCount(
                DSL.table("flyway_schema_history"),
                DSL.field("version")
                    .eq("1002")
                    .and(DSL.field("script", String.class).endsWith("V1002__saga_step_table.sql"))
                    .and(DSL.field("success").eq(true))))
        .isOne();
  }

  private static List<String> migrationHistory(DSLContext dsl) {
    return dsl.fetch(
            "SELECT row_to_json(h)::text FROM flyway_schema_history h ORDER BY installed_rank")
        .getValues(0, String.class);
  }

  private static AccountFriendLink link(String account, String friend) {
    AccountFriendLink link = new AccountFriendLink();
    link.setTenantId(7L);
    link.setAccountId(account);
    link.setFriendAccountId(friend);
    link.setStatus("active");
    link.setCreatedAt(Instant.now());
    return link;
  }

  private static Map<String, List<String>> rowImages(DSLContext dsl) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (String table : ACCOUNT_TABLES) {
      result.put(
          table,
          dsl.fetch("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY id")
              .getValues(0, String.class));
    }
    return result;
  }

  private static String schema() {
    return "social_account_uuid_" + UUID.randomUUID().toString().replace("-", "");
  }

  private static void migrate(String schema, String target) {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .initSql("SET lock_timeout = '15s'; SET statement_timeout = '20s'")
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .placeholders(Map.of("serviceSchema", schema))
        .target(target)
        .load()
        .migrate();
  }

  private static CloseableDSLContext database(String schema) {
    CloseableDSLContext dsl =
        DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl.configuration().set(SQLDialect.POSTGRES);
    dsl.execute("SET search_path TO " + schema);
    return dsl;
  }
}
