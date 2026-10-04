package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.gamedesign.entity.Game;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameRepository {
  private static final Table<?> GAME_TABLE = DSL.table(DSL.name("game"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<String> NAME = DSL.field(DSL.name("name"), String.class);
  private static final Field<String> DESCRIPTION = DSL.field(DSL.name("description"), String.class);

  private final DSLContext dsl;

  public GameRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<Game> findAll() {
    return dsl.selectFrom(GAME_TABLE).orderBy(ID.asc()).fetch(this::toEntity);
  }

  public Game save(Game game) {
    if (game.getId() == null) {
      Record record =
          dsl.insertInto(GAME_TABLE)
              .set(TENANT_ID, game.getTenantId())
              .set(NAME, game.getName())
              .set(DESCRIPTION, game.getDescription())
              .returning(ID, TENANT_ID, NAME, DESCRIPTION)
              .fetchOne();
      return toEntity(record);
    }
    dsl.update(GAME_TABLE)
        .set(TENANT_ID, game.getTenantId())
        .set(NAME, game.getName())
        .set(DESCRIPTION, game.getDescription())
        .where(ID.eq(game.getId()))
        .execute();
    return findByTenantId(game.getTenantId());
  }

  public Game findByTenantId(String tenantId) {
    return dsl.selectFrom(GAME_TABLE)
        .where(TENANT_ID.eq(tenantId))
        .limit(1)
        .fetchOne(this::toEntity);
  }

  public Game findByTenantIdForUpdate(String tenantId) {
    return dsl.selectFrom(GAME_TABLE)
        .where(TENANT_ID.eq(tenantId))
        .forUpdate()
        .fetchOne(this::toEntity);
  }

  private Game toEntity(Record record) {
    if (record == null) {
      return null;
    }
    Game game = new Game();
    game.setId(record.get(ID));
    game.setTenantId(record.get(TENANT_ID));
    game.setName(record.get(NAME));
    game.setDescription(record.get(DESCRIPTION));
    return game;
  }
}
