package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class GameAssetRepositoryTest {
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<String> FILE_NAME = DSL.field(DSL.name("file_name"), String.class);
  private static final Field<String> CONTENT_TYPE =
      DSL.field(DSL.name("content_type"), String.class);
  private static final Field<byte[]> DATA = DSL.field(DSL.name("data"), byte[].class);
  private static final Field<LocalDateTime> CREATED_AT =
      DSL.field(DSL.name("created_at"), LocalDateTime.class);

  @Test
  void insertUsesPostgresReturningFieldsAndMapsThePersistedRow() {
    AtomicReference<String> executedSql = new AtomicReference<>();
    DSLContext dsl =
        DSL.using(
            new MockConnection(
                context -> {
                  executedSql.set(context.sql());
                  var result =
                      DSL.using(SQLDialect.POSTGRES)
                          .newResult(ID, TENANT_ID, FILE_NAME, CONTENT_TYPE, DATA, CREATED_AT);
                  var returned =
                      DSL.using(SQLDialect.POSTGRES)
                          .newRecord(ID, TENANT_ID, FILE_NAME, CONTENT_TYPE, DATA, CREATED_AT);
                  returned.set(ID, 73L);
                  returned.set(TENANT_ID, "tenant-1");
                  returned.set(FILE_NAME, "map.png");
                  returned.set(CONTENT_TYPE, "image/png");
                  returned.set(DATA, new byte[] {1, 2, 3});
                  returned.set(CREATED_AT, LocalDateTime.of(2026, 10, 9, 12, 30));
                  result.add(returned);
                  return new MockResult[] {new MockResult(1, result)};
                }),
            SQLDialect.POSTGRES);
    GameAsset input = new GameAsset();
    input.setTenantId("tenant-1");
    input.setFileName("map.png");
    input.setContentType("image/png");
    input.setData(new byte[] {1, 2, 3});
    input.setCreatedAt(LocalDateTime.of(2026, 10, 9, 12, 30));

    GameAsset saved = new GameAssetRepository(dsl).save(input);

    assertThat(executedSql.get().toLowerCase(Locale.ROOT))
        .contains(
            "insert into \"game_assets\"",
            "returning \"id\", \"tenant_id\", \"file_name\", \"content_type\", "
                + "\"data\", \"created_at\"");
    assertThat(saved.getId()).isEqualTo(73L);
    assertThat(saved.getTenantId()).isEqualTo("tenant-1");
    assertThat(saved.getFileName()).isEqualTo("map.png");
    assertThat(saved.getContentType()).isEqualTo("image/png");
    assertThat(saved.getData()).containsExactly(1, 2, 3);
    assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 12, 30));
  }

  @Test
  void insertFailsClearlyWhenPostgresReturnsNoPersistedRow() {
    DSLContext dsl =
        DSL.using(
            new MockConnection(
                context ->
                    new MockResult[] {
                      new MockResult(
                          0,
                          DSL.using(SQLDialect.POSTGRES)
                              .newResult(ID, TENANT_ID, FILE_NAME, CONTENT_TYPE, DATA, CREATED_AT))
                    }),
            SQLDialect.POSTGRES);
    GameAsset input = new GameAsset();
    input.setTenantId("tenant-1");
    input.setFileName("map.png");
    input.setContentType("image/png");
    input.setData(new byte[] {1, 2, 3});

    assertThatThrownBy(() -> new GameAssetRepository(dsl).save(input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Game asset insert did not return its persisted row");
  }
}
