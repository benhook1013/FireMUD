package net.firedevops.firemud.gamesession.repository;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalogCodec;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manually composed local SQL capture and independent committed readback. No Spring registration,
 * actor/peer fiction, gameplay allocation, private preparation, registry or pointer mutation.
 * Dynamic SQL is confined to configured-schema PostgreSQL census and exact BYTEA evidence. The DSL
 * must use a TransactionAwareDataSourceProxy over the same durable-primary data source as the
 * transaction manager; unrelated or unbound DSL connections are not qualified composition.
 */
public final class GameSessionRuntimeFamilyCatalogRepository {
  private final DSLContext dsl;
  private final String schema;
  private final String namespace;
  private final TransactionTemplate capture;
  private final TransactionTemplate readback;

  public GameSessionRuntimeFamilyCatalogRepository(
      DSLContext dsl, PlatformTransactionManager transactions, String schema, String namespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    if (schema == null || !schema.matches("[a-z][a-z0-9_]{0,62}"))
      throw new IllegalArgumentException("Fixed owner schema required");
    GameSessionRuntimeFamilyCatalog.requireNamespace(namespace);
    this.schema = schema;
    this.namespace = namespace;
    capture = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    capture.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    capture.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    readback = new TransactionTemplate(transactions);
    readback.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readback.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readback.setReadOnly(true);
  }

  /**
   * Commits an exact request snapshot, then independently reads it. Exact concurrent retries
   * converge.
   */
  public Evidence capture(Request request) {
    requireIndependent(request);
    byte[] committed =
        capture.execute(
            status -> {
              var catalog = currentCatalog();
              byte[] requestBytes = GameSessionRuntimeFamilyCatalogCodec.requestBytes(request);
              var proposed = new Evidence(request, UUID.randomUUID(), catalog);
              byte[] evidenceBytes = GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(proposed);
              dsl.execute(
                  "INSERT INTO "
                      + table("game_session_runtime_family_catalog_snapshot")
                      + " (read_request_id,target_namespace,request_bytes,request_digest,snapshot_id,"
                      + "catalog_epoch,catalog_digest,evidence_bytes,evidence_digest) VALUES (?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (read_request_id) DO NOTHING",
                  request.readRequestId(),
                  request.targetNamespace(),
                  requestBytes,
                  GameSessionRuntimeFamilyCatalog.sha256(requestBytes),
                  proposed.snapshotId(),
                  catalog.catalogEpoch(),
                  catalog.digest(),
                  evidenceBytes,
                  proposed.digest());
              return retained(request, catalog);
            });
    Evidence result = readExact(request);
    if (!Arrays.equals(committed, GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(result)))
      throw new CatalogConflictException("Committed Game Session catalog readback changed");
    return result;
  }

  /**
   * Independent retained primary read. Missing, changed or invalidated evidence never becomes
   * empty.
   */
  public Evidence readExact(Request request) {
    requireIndependent(request);
    return readback.execute(
        status ->
            GameSessionRuntimeFamilyCatalogCodec.evidenceFromStored(
                retained(request, currentCatalog())));
  }

  private GameSessionRuntimeFamilyCatalog currentCatalog() {
    Record row =
        dsl.fetchOne(
            "SELECT catalog_epoch,catalog_bytes,catalog_digest,"
                + schema
                + ".gs_runtime_family_catalog_bytes(catalog_epoch) AS current_bytes FROM "
                + table("game_session_runtime_family_catalog_epoch")
                + " WHERE "
                + schema
                + ".gs_runtime_family_catalog_installed() ORDER BY catalog_epoch DESC LIMIT 1");
    if (row == null) throw new CatalogConflictException("No durable Game Session catalog epoch");
    byte[] sealed = row.get("catalog_bytes", byte[].class);
    if (!Arrays.equals(sealed, row.get("current_bytes", byte[].class)))
      throw new CatalogConflictException(
          "Game Session schema/classification drift invalidates its epoch");
    var catalog = GameSessionRuntimeFamilyCatalog.fromStored(sealed);
    if (catalog.catalogEpoch() != row.get("catalog_epoch", Long.class)
        || !catalog.digest().equals(row.get("catalog_digest", String.class)))
      throw new CatalogConflictException(
          "Game Session catalog epoch/digest binding is inconsistent");
    return catalog;
  }

  private byte[] retained(Request request, GameSessionRuntimeFamilyCatalog current) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + table("game_session_runtime_family_catalog_snapshot")
                + " WHERE read_request_id=?",
            request.readRequestId());
    if (row == null)
      throw new CatalogConflictException("Exact Game Session catalog snapshot is absent");
    byte[] bytes = row.get("evidence_bytes", byte[].class);
    Evidence result = GameSessionRuntimeFamilyCatalogCodec.evidenceFromStored(bytes);
    byte[] requestBytes = GameSessionRuntimeFamilyCatalogCodec.requestBytes(request);
    if (!request.equals(result.request())
        || !request.targetNamespace().equals(row.get("target_namespace", String.class))
        || !Arrays.equals(requestBytes, row.get("request_bytes", byte[].class))
        || !GameSessionRuntimeFamilyCatalog.sha256(requestBytes)
            .equals(row.get("request_digest", String.class))
        || !result.snapshotId().equals(row.get("snapshot_id", UUID.class))
        || result.catalog().catalogEpoch() != row.get("catalog_epoch", Long.class)
        || !result.catalog().digest().equals(row.get("catalog_digest", String.class))
        || !result.digest().equals(row.get("evidence_digest", String.class))
        || !Arrays.equals(current.exactBytes(), result.catalog().exactBytes()))
      throw new CatalogConflictException(
          "Changed request or invalidated Game Session catalog snapshot");
    return bytes;
  }

  private String table(String name) {
    return schema + "." + name;
  }

  private void requireIndependent(Request request) {
    Objects.requireNonNull(request, "request");
    if (!namespace.equals(request.targetNamespace()))
      throw new CatalogConflictException("Wrong workload namespace");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Catalog evidence requires independent owner transactions");
  }

  public static final class CatalogConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public CatalogConflictException(String message) {
      super(message);
    }
  }
}
