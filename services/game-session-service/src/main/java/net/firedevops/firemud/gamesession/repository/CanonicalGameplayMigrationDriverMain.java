package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.SslVerifyMode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Finite, non-listening entry point for the trusted Game Session empty-cohort migration. */
@SuppressFBWarnings(
    value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
    justification =
        "These fixed trusted Pod paths bind PostgreSQL to its Unix socket and credentials/CA to protected projections; no path override or network fallback is allowed.")
public final class CanonicalGameplayMigrationDriverMain {
  private static final Path POSTGRES_SOCKET = Path.of("/var/run/postgresql/.s.PGSQL.5432");
  private static final Path POSTGRES_PASSWORD = Path.of("/run/gs-owner/password");
  private static final Path REDIS_PASSWORD = Path.of("/run/gs-owner/redis/password");
  private static final Path REDIS_CA = Path.of("/run/gs-owner/redis/ca.crt");

  private static final String POSTGRES_SOCKET_FACTORY =
      "org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg";
  private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
  private static final Pattern REDIS_RUN_ID = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern SHA256_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final ObjectMapper CANONICAL_JSON_MAPPER =
      new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private static final Duration REDIS_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration TOTAL_RUN_TIMEOUT = Duration.ofSeconds(1800);
  private static final int MAX_CATALOG_ROWS = 65_536;
  private static final int MAX_CATALOG_BYTES = 16 * 1024 * 1024;
  private static final Map<String, String> EXPECTED_MIGRATION_SCRIPTS = expectedMigrationScripts();
  private static final String USER_SCHEMA_FILTER =
      "n.nspname <> 'information_schema' AND n.nspname <> 'pg_catalog'"
          + " AND n.nspname NOT LIKE 'pg_toast%' AND n.nspname NOT LIKE 'pg_temp_%'";
  private static final String SCHEMA_INVENTORY_SQL =
      "SELECT jsonb_build_object('schema', n.nspname, 'owner', pg_catalog.pg_get_userbyid(n.nspowner),"
          + " 'acl', n.nspacl::text)::text FROM pg_catalog.pg_namespace n WHERE "
          + USER_SCHEMA_FILTER
          + " ORDER BY n.nspname";
  private static final String RELATION_INVENTORY_SQL =
      "SELECT jsonb_build_object('schema', n.nspname, 'name', c.relname, 'kind', c.relkind::text,"
          + " 'persistence', c.relpersistence::text, 'owner', pg_catalog.pg_get_userbyid(c.relowner),"
          + " 'partitioned', c.relispartition, 'accessMethod', am.amname, 'tablespace', ts.spcname,"
          + " 'options', c.reloptions, 'rowSecurity', c.relrowsecurity, 'forceRowSecurity', c.relforcerowsecurity,"
          + " 'replicaIdentity', c.relreplident::text, 'viewDefinition', CASE WHEN c.relkind IN ('v','m')"
          + " THEN pg_catalog.pg_get_viewdef(c.oid, true) END, 'partitionBound', CASE WHEN c.relispartition"
          + " THEN pg_catalog.pg_get_expr(c.relpartbound, c.oid, true) END, 'acl', c.relacl::text,"
          + " 'foreignOptions', ft.ftoptions)::text FROM pg_catalog.pg_class c"
          + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
          + " LEFT JOIN pg_catalog.pg_am am ON am.oid = c.relam"
          + " LEFT JOIN pg_catalog.pg_tablespace ts ON ts.oid = c.reltablespace"
          + " LEFT JOIN pg_catalog.pg_foreign_table ft ON ft.ftrelid = c.oid WHERE "
          + USER_SCHEMA_FILTER
          + " ORDER BY n.nspname, c.relname, c.relkind";
  private static final String COLUMN_INVENTORY_SQL =
      "SELECT jsonb_build_object('schema', n.nspname, 'relation', c.relname, 'name', a.attname,"
          + " 'ordinal', a.attnum, 'type', pg_catalog.format_type(a.atttypid, a.atttypmod),"
          + " 'typeSchema', tn.nspname, 'typeName', t.typname, 'notNull', a.attnotnull,"
          + " 'hasDefault', a.atthasdef, 'default', pg_catalog.pg_get_expr(d.adbin, d.adrelid, true),"
          + " 'identity', a.attidentity::text, 'generated', a.attgenerated::text, 'storage', a.attstorage::text,"
          + " 'compression', a.attcompression::text, 'collationSchema', cn.nspname, 'collation', co.collname,"
          + " 'statisticsTarget', a.attstattarget, 'acl', a.attacl::text)::text FROM pg_catalog.pg_attribute a"
          + " JOIN pg_catalog.pg_class c ON c.oid = a.attrelid"
          + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
          + " JOIN pg_catalog.pg_type t ON t.oid = a.atttypid"
          + " JOIN pg_catalog.pg_namespace tn ON tn.oid = t.typnamespace"
          + " LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum"
          + " LEFT JOIN pg_catalog.pg_collation co ON co.oid = a.attcollation"
          + " LEFT JOIN pg_catalog.pg_namespace cn ON cn.oid = co.collnamespace"
          + " WHERE a.attnum > 0 AND NOT a.attisdropped AND "
          + USER_SCHEMA_FILTER
          + " ORDER BY n.nspname, c.relname, a.attnum";
  private static final String INDEX_INVENTORY_SQL =
      "SELECT jsonb_build_object('schema', n.nspname, 'table', tbl.relname, 'index', idx.relname,"
          + " 'owner', pg_catalog.pg_get_userbyid(idx.relowner), 'definition', pg_catalog.pg_get_indexdef(idx.oid),"
          + " 'unique', i.indisunique, 'primary', i.indisprimary, 'valid', i.indisvalid, 'ready', i.indisready,"
          + " 'live', i.indislive, 'clustered', i.indisclustered, 'replicaIdentity', i.indisreplident,"
          + " 'nullsNotDistinct', i.indnullsnotdistinct, 'keyAttributes', i.indnkeyatts, 'attributes', i.indnatts,"
          + " 'keys', i.indkey::text, 'classes', i.indclass::text, 'collations', i.indcollation::text,"
          + " 'options', i.indoption::text, 'accessMethod', am.amname, 'tablespace', ts.spcname,"
          + " 'relationOptions', idx.reloptions, 'acl', idx.relacl::text)::text"
          + " FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class tbl ON tbl.oid = i.indrelid"
          + " JOIN pg_catalog.pg_namespace n ON n.oid = tbl.relnamespace"
          + " JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid"
          + " LEFT JOIN pg_catalog.pg_am am ON am.oid = idx.relam"
          + " LEFT JOIN pg_catalog.pg_tablespace ts ON ts.oid = idx.reltablespace WHERE "
          + USER_SCHEMA_FILTER
          + " ORDER BY n.nspname, tbl.relname, idx.relname";
  private static final String CONSTRAINT_INVENTORY_SQL =
      "SELECT jsonb_build_object('schema', n.nspname, 'relation', c.relname, 'name', con.conname,"
          + " 'type', con.contype::text, 'definition', pg_catalog.pg_get_constraintdef(con.oid, true),"
          + " 'deferrable', con.condeferrable, 'deferred', con.condeferred, 'validated', con.convalidated,"
          + " 'local', con.conislocal, 'inheritanceCount', con.coninhcount, 'noInherit', con.connoinherit,"
          + " 'keys', con.conkey::text, 'referencedKeys', con.confkey::text, 'matchType', con.confmatchtype::text,"
          + " 'updateAction', con.confupdtype::text, 'deleteAction', con.confdeltype::text,"
          + " 'index', ic.relname)::text FROM pg_catalog.pg_constraint con"
          + " JOIN pg_catalog.pg_class c ON c.oid = con.conrelid"
          + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
          + " LEFT JOIN pg_catalog.pg_class ic ON ic.oid = con.conindid WHERE "
          + USER_SCHEMA_FILTER
          + " ORDER BY n.nspname, c.relname, con.conname";
  private static final String TYPE_INVENTORY_SQL =
      "SELECT jsonb_build_object('schema', n.nspname, 'name', t.typname, 'owner',"
          + " pg_catalog.pg_get_userbyid(t.typowner), 'kind', t.typtype::text, 'category', t.typcategory::text,"
          + " 'defined', t.typisdefined, 'length', t.typlen, 'byValue', t.typbyval, 'delimiter', t.typdelim::text,"
          + " 'input', t.typinput::regprocedure::text, 'output', t.typoutput::regprocedure::text,"
          + " 'receive', t.typreceive::regprocedure::text, 'send', t.typsend::regprocedure::text,"
          + " 'modifierInput', t.typmodin::regprocedure::text, 'modifierOutput', t.typmodout::regprocedure::text,"
          + " 'analyze', t.typanalyze::regprocedure::text, 'alignment', t.typalign::text,"
          + " 'storage', t.typstorage::text, 'subscript', t.typsubscript::regprocedure::text,"
          + " 'relation', t.typrelid::regclass::text, 'element', t.typelem::regtype::text,"
          + " 'array', t.typarray::regtype::text, 'base', t.typbasetype::regtype::text,"
          + " 'notNull', t.typnotnull, 'default', t.typdefault, 'baseTypeModifier', t.typtypmod,"
          + " 'collation', t.typcollation::regcollation::text, 'acl', t.typacl::text)::text"
          + " FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace WHERE "
          + USER_SCHEMA_FILTER
          + " ORDER BY n.nspname, t.typname";
  private static final String DOMAIN_CONSTRAINT_INVENTORY_SQL =
      "SELECT jsonb_build_object('schema', n.nspname, 'type', t.typname, 'name', con.conname,"
          + " 'definition', pg_catalog.pg_get_constraintdef(con.oid, true), 'validated', con.convalidated,"
          + " 'local', con.conislocal, 'inheritanceCount', con.coninhcount)::text"
          + " FROM pg_catalog.pg_constraint con JOIN pg_catalog.pg_type t ON t.oid = con.contypid"
          + " JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace WHERE "
          + USER_SCHEMA_FILTER
          + " ORDER BY n.nspname, t.typname, con.conname";
  private static final String ENUM_INVENTORY_SQL =
      "SELECT jsonb_build_object('schema', n.nspname, 'type', t.typname, 'label', e.enumlabel,"
          + " 'sortOrder', e.enumsortorder)::text FROM pg_catalog.pg_enum e"
          + " JOIN pg_catalog.pg_type t ON t.oid = e.enumtypid"
          + " JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace WHERE "
          + USER_SCHEMA_FILTER
          + " ORDER BY n.nspname, t.typname, e.enumsortorder";
  private static final String SEQUENCE_INVENTORY_SQL =
      "SELECT jsonb_build_object('schema', n.nspname, 'name', c.relname, 'owner',"
          + " pg_catalog.pg_get_userbyid(c.relowner), 'start', s.seqstart, 'increment', s.seqincrement,"
          + " 'maximum', s.seqmax, 'minimum', s.seqmin, 'cache', s.seqcache, 'cycle', s.seqcycle,"
          + " 'acl', c.relacl::text)::text FROM pg_catalog.pg_sequence s"
          + " JOIN pg_catalog.pg_class c ON c.oid = s.seqrelid"
          + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE "
          + USER_SCHEMA_FILTER
          + " ORDER BY n.nspname, c.relname";
  private static final String ROUTINE_INVENTORY_SQL =
      "SELECT jsonb_build_object('schema', n.nspname, 'name', p.proname, 'identityArguments',"
          + " pg_catalog.pg_get_function_identity_arguments(p.oid), 'result', pg_catalog.pg_get_function_result(p.oid),"
          + " 'kind', p.prokind::text, 'language', l.lanname, 'owner', pg_catalog.pg_get_userbyid(p.proowner),"
          + " 'arguments', p.proargtypes::regtype[]::text, 'configuration', p.proconfig::text, 'acl', p.proacl::text,"
          + " 'definition', CASE WHEN p.prokind = 'a' THEN NULL ELSE pg_catalog.pg_get_functiondef(p.oid) END)::text"
          + " FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
          + " JOIN pg_catalog.pg_language l ON l.oid = p.prolang WHERE "
          + USER_SCHEMA_FILTER
          + " ORDER BY n.nspname, p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid)";
  private static final String TRIGGER_INVENTORY_SQL =
      "SELECT jsonb_build_object('schema', n.nspname, 'relation', c.relname, 'name', t.tgname,"
          + " 'definition', pg_catalog.pg_get_triggerdef(t.oid, true), 'enabled', t.tgenabled::text,"
          + " 'internal', t.tgisinternal, 'constraint', t.tgconstraint::regclass::text,"
          + " 'arguments', encode(t.tgargs, 'hex'), 'when', pg_catalog.pg_get_expr(t.tgqual, t.tgrelid, true),"
          + " 'function', t.tgfoid::regprocedure::text)::text FROM pg_catalog.pg_trigger t"
          + " JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid"
          + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE "
          + USER_SCHEMA_FILTER
          + " ORDER BY n.nspname, c.relname, t.tgname";
  private static final String POLICY_INVENTORY_SQL =
      "SELECT jsonb_build_object('schema', n.nspname, 'relation', c.relname, 'name', p.polname,"
          + " 'command', p.polcmd::text, 'permissive', p.polpermissive, 'roles', p.polroles::text,"
          + " 'using', pg_catalog.pg_get_expr(p.polqual, p.polrelid, true),"
          + " 'check', pg_catalog.pg_get_expr(p.polwithcheck, p.polrelid, true))::text"
          + " FROM pg_catalog.pg_policy p JOIN pg_catalog.pg_class c ON c.oid = p.polrelid"
          + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE "
          + USER_SCHEMA_FILTER
          + " ORDER BY n.nspname, c.relname, p.polname";
  private static final String EXTENSION_INVENTORY_SQL =
      "SELECT jsonb_build_object('name', e.extname, 'version', e.extversion, 'owner',"
          + " pg_catalog.pg_get_userbyid(e.extowner), 'schema', n.nspname, 'relocatable', e.extrelocatable,"
          + " 'configurationRelations', e.extconfig::text, 'conditions', e.extcondition::text)::text"
          + " FROM pg_catalog.pg_extension e JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace"
          + " WHERE "
          + USER_SCHEMA_FILTER
          + " ORDER BY e.extname";

  /*
   * This source cut is intentionally the complete packaged Game Session migration directory
   * (V1-V15, V20-V34, and V34.1) plus the three common-saga migrations (V1000-V1002). Flyway computes
   * checksums from those packaged SQL resources and validates the applied schema history against
   * them; no filesystem, callback, or additional migration location is accepted.
   */

  private CanonicalGameplayMigrationDriverMain() {}

  public static void main(String[] args) {
    ScheduledExecutorService deadline =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "gs-migration-driver-deadline");
              thread.setDaemon(true);
              return thread;
            });
    deadline.schedule(
        () -> {
          System.err.println(
              "migration driver exceeded its finite run bound; retain the same cohort and writer fence");
          Runtime.getRuntime().halt(4);
        },
        TOTAL_RUN_TIMEOUT.toSeconds(),
        TimeUnit.SECONDS);
    int exitCode;
    try {
      exitCode = run(args, System.in, System.out, System.err);
    } finally {
      deadline.shutdownNow();
    }
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  static int run(
      String[] args,
      java.io.InputStream input,
      java.io.OutputStream output,
      java.io.PrintStream error) {
    if (args.length != 0) {
      error.println("migration driver accepts no command-line arguments");
      return 2;
    }
    try (CanonicalGameplayMigrationDriverProtocol protocol =
        new CanonicalGameplayMigrationDriverProtocol(input, output)) {
      CanonicalGameplayMigrationDriverProtocol.Initialization initialization =
          protocol.readInitialization();
      DriverConfiguration configuration = DriverConfiguration.fromEnvironment(initialization);
      DataSource postgres = createPostgresDataSource(configuration);
      LettuceConnectionFactory redisFactory = createRedisConnectionFactory(configuration);
      try {
        redisFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();
        LiveObservationReader observationReader =
            new LiveObservationReader(postgres, redis, REDIS_CA);
        CanonicalGameplayMigrationDriverProtocol.LocalObservation initialObservation =
            observationReader.read();
        requireIdentityMatches(
            initialization,
            initialObservation,
            configuration.postgresUser(),
            configuration.redisCaDigest());

        ProtocolFencedCohort cohort =
            new ProtocolFencedCohort(
                initialization,
                protocol,
                observationReader,
                initialObservation,
                configuration.postgresUser(),
                configuration.redisCaDigest());
        cohort.requireStillFenced(initialization.storageIdentity());
        // A partial Flyway run is intentionally not replayed as an "empty" cohort. After any
        // interrupted schema creation the same external fence must remain retained for owner-led
        // reconciliation; this driver invents no side marker or schema exemption.
        PhysicalSchemaSnapshot beforeMigration = readPhysicalSchemaSnapshot(postgres);
        requireEmptyCohortSchema(beforeMigration);
        cohort.requireStillFenced(initialization.storageIdentity());
        Flyway flyway = createFlyway(configuration, postgres);
        List<MigrationSourceIdentity> packagedMigrationSources = migrationSourceInventory(flyway);
        requireCompletePackagedMigrationSources(packagedMigrationSources);
        cohort.requireStillFenced(initialization.storageIdentity());
        flyway.migrate();
        cohort.requireStillFenced(initialization.storageIdentity());
        flyway.validate();
        requireFlywayCurrent(flyway);
        cohort.requireStillFenced(initialization.storageIdentity());
        PhysicalSchemaSnapshot ownerCreatedSchema = readPhysicalSchemaSnapshot(postgres);
        cohort.requireStillFenced(initialization.storageIdentity());
        flyway.validate();
        requireFlywayCurrent(flyway);
        requireSameMigrationSources(packagedMigrationSources, migrationSourceInventory(flyway));
        PhysicalSchemaSnapshot physicalReadback = readPhysicalSchemaSnapshot(postgres);
        requireSamePhysicalSchema(ownerCreatedSchema, physicalReadback);
        cohort.requireStillFenced(initialization.storageIdentity());

        DSLContext dsl = DSL.using(postgres, SQLDialect.POSTGRES);
        CanonicalGameplayBindingInventoryRepository inventory =
            new CanonicalGameplayBindingInventoryRepository(dsl);
        CanonicalGameplayLegacyMigrationOwner owner =
            new CanonicalGameplayLegacyMigrationOwner(
                new CanonicalGameplayLegacyMigrationRepository(dsl, inventory),
                inventory,
                () -> cohort,
                new CanonicalGameplayLegacyRedisSource(redis),
                new CanonicalGameplayFreshNamespaceRedisReconciler(redis));
        CanonicalGameplayLegacyMigrationOperation operation = owner.migrate();
        if (operation.state() != CanonicalGameplayLegacyMigrationOperation.State.VERIFIED) {
          error.println("migration driver did not verify the empty cohort; retain the fence");
          return 3;
        }
        return 0;
      } finally {
        redisFactory.destroy();
      }
    } catch (RuntimeException | IOException failure) {
      error.println("migration driver denied or failed; retain the same cohort and writer fence");
      return 4;
    }
  }

  private static DataSource createPostgresDataSource(DriverConfiguration configuration) {
    PostgresSocketConfiguration socket = postgresSocketConfiguration();
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setServerNames(new String[] {socket.host()});
    dataSource.setPortNumbers(new int[] {socket.port()});
    dataSource.setDatabaseName(configuration.postgresDatabase());
    dataSource.setUser(configuration.postgresUser());
    dataSource.setPassword(configuration.postgresPassword());
    try {
      dataSource.setProperty("socketFactory", socket.factoryClass());
      dataSource.setProperty("socketFactoryArg", socket.socketPath());
      dataSource.setProperty("sslmode", socket.sslMode());
      dataSource.setProperty("connectTimeout", "5");
      dataSource.setProperty("socketTimeout", "300");
      dataSource.setProperty("currentSchema", configuration.serviceSchema());
    } catch (SQLException invalidConfiguration) {
      throw new IllegalStateException("could not configure the socket-only PostgreSQL source");
    }
    dataSource.setApplicationName("gs-canonical-gameplay-migration-driver");
    return dataSource;
  }

  static PostgresSocketConfiguration postgresSocketConfiguration() {
    return new PostgresSocketConfiguration(
        "localhost", 5432, POSTGRES_SOCKET_FACTORY, POSTGRES_SOCKET.toString(), "disable");
  }

  record PostgresSocketConfiguration(
      String host, int port, String factoryClass, String socketPath, String sslMode) {}

  private static LettuceConnectionFactory createRedisConnectionFactory(
      DriverConfiguration configuration) throws IOException {
    RedisStandaloneConfiguration standalone =
        new RedisStandaloneConfiguration(configuration.redisHost(), 6380);
    if (configuration.redisUsername() != null) {
      standalone.setUsername(configuration.redisUsername());
    }
    standalone.setPassword(RedisPassword.of(configuration.redisPassword()));
    SslOptions sslOptions =
        SslOptions.builder().jdkSslProvider().trustManager(REDIS_CA.toFile()).build();
    ClientOptions clientOptions = ClientOptions.builder().sslOptions(sslOptions).build();
    LettuceClientConfiguration clientConfiguration =
        LettuceClientConfiguration.builder()
            .clientOptions(clientOptions)
            .commandTimeout(REDIS_TIMEOUT)
            .shutdownTimeout(REDIS_TIMEOUT)
            .useSsl()
            .verifyPeer(SslVerifyMode.FULL)
            .and()
            .build();
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(standalone, clientConfiguration);
    factory.setShareNativeConnection(false);
    return factory;
  }

  private static Flyway createFlyway(DriverConfiguration configuration, DataSource dataSource) {
    return configuredFlyway(configuration.serviceSchema(), dataSource).load();
  }

  static FluentConfiguration configuredFlyway(String serviceSchema, DataSource dataSource) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(serviceSchema)
        .defaultSchema(serviceSchema)
        .table(flywayHistoryTable(serviceSchema))
        .createSchemas(true)
        .locations("classpath:db/migration", "classpath:db/migration/saga")
        .placeholders(Map.of("serviceSchema", serviceSchema))
        .cleanDisabled(true)
        .baselineOnMigrate(false);
  }

  static String flywayHistoryTable(String serviceSchema) {
    if (serviceSchema == null
        || !SQL_IDENTIFIER.matcher(serviceSchema).matches()
        || ("flyway_schema_history_" + serviceSchema).length() > 63) {
      throw new IllegalArgumentException("migration service schema is not canonical");
    }
    return "flyway_schema_history_" + serviceSchema;
  }

  static Map<String, Object> validatedRedisObservation(
      String runId, String plainPortText, String tlsPortText, String trustCaDigest) {
    if (runId == null
        || !REDIS_RUN_ID.matcher(runId).matches()
        || !"0".equals(plainPortText)
        || !"6380".equals(tlsPortText)
        || trustCaDigest == null
        || !SHA256_DIGEST.matcher(trustCaDigest).matches()) {
      throw new IllegalStateException("Redis identity or TLS configuration was incomplete");
    }
    Map<String, Object> observed = new LinkedHashMap<>();
    observed.put("runId", runId);
    observed.put("tlsEnabled", true);
    observed.put("tlsPort", 6380);
    observed.put("trustCaSha256", trustCaDigest);
    return observed;
  }

  private static void requireFlywayCurrent(Flyway flyway) {
    var info = flyway.info();
    if (info.current() == null || info.pending().length != 0) {
      throw new IllegalStateException("Flyway did not reach the current schema version");
    }
    requireCompletePackagedMigrationSources(migrationSourceInventory(flyway));
  }

  private static void requireCompletePackagedMigrationSources(
      List<MigrationSourceIdentity> migrations) {
    if (migrations.size() != EXPECTED_MIGRATION_SCRIPTS.size()
        || migrations.stream()
            .anyMatch(
                migration ->
                    !expectedMigrationScriptName(migration.script())
                            .equals(EXPECTED_MIGRATION_SCRIPTS.get(migration.version()))
                        || !"SQL".equals(migration.type())
                        || migration.checksum() == null)) {
      throw new IllegalStateException("packaged migration source inventory was incomplete");
    }
  }

  private static void requireSameMigrationSources(
      List<MigrationSourceIdentity> expected, List<MigrationSourceIdentity> actual) {
    requireCompletePackagedMigrationSources(actual);
    if (!expected.equals(actual)) {
      throw new IllegalStateException("packaged Flyway migration checksum inventory changed");
    }
  }

  private static Map<String, String> expectedMigrationScripts() {
    return Map.ofEntries(
        Map.entry("1", "V1__baseline.sql"),
        Map.entry("2", "V2__scope_gameplay_command_identity.sql"),
        Map.entry("3", "V3__retain_exact_script_patch_base_provenance.sql"),
        Map.entry("4", "V4__retain_gameplay_command_script_patch_base_provenance.sql"),
        Map.entry("5", "V5__separate_gameplay_catalog_revision.sql"),
        Map.entry("6", "V6__persist_gameplay_realm_identity.sql"),
        Map.entry("7", "V7__audit_gameplay_catalog_revision.sql"),
        Map.entry("8", "V8__repair_legacy_bootstrap_public_realm.sql"),
        Map.entry("9", "V9__initial_admission_bind_owner_ledger.sql"),
        Map.entry("10", "V10__run_owned_initial_launch_identity.sql"),
        Map.entry("11", "V11__gameplay_command_account_uuid.sql"),
        Map.entry("12", "V12__retained_tenant_association.sql"),
        Map.entry("13", "V13__retained_tenant_payload_retention.sql"),
        Map.entry("14", "V14__published_realm_catalog_snapshots.sql"),
        Map.entry("15", "V15__published_initial_admission_bind.sql"),
        Map.entry("20", "V20__closed_published_realm_policy_evidence.sql"),
        Map.entry("21", "V21__canonical_game_instance_owner_identity.sql"),
        Map.entry("22", "V22__authored_world_source_intake.sql"),
        Map.entry("23", "V23__canonical_realm_catalog.sql"),
        Map.entry("24", "V24__canonical_launch_preparation.sql"),
        Map.entry("25", "V25__fresh_tenant_association.sql"),
        Map.entry("26", "V26__canonical_instance_launch_association.sql"),
        Map.entry("27", "V27__canonical_closed_admission_pointer.sql"),
        Map.entry("28", "V28__canonical_initial_admission_owner_and_intent.sql"),
        Map.entry("29", "V29__canonical_gameplay_binding_inventory_foundation.sql"),
        Map.entry("30", "V30__retain_canonical_account_index_projection_readback.sql"),
        Map.entry("31", "V31__canonical_account_coverage_fence_snapshot_and_ack.sql"),
        Map.entry("32", "V32__canonical_gameplay_admission_decision.sql"),
        Map.entry("33", "V33__canonical_legacy_binding_migration_operation.sql"),
        Map.entry("34", "V34__canonical_gameplay_decision_runtime_fence.sql"),
        Map.entry("34.1", "V34.1__canonical_gameplay_integrity_guards.sql"),
        Map.entry("1000", "V1000__create_saga_schema.sql"),
        Map.entry("1001", "V1001__saga_instance_table.sql"),
        Map.entry("1002", "V1002__saga_step_table.sql"));
  }

  private static List<MigrationSourceIdentity> migrationSourceInventory(Flyway flyway) {
    List<MigrationSourceIdentity> migrations = new ArrayList<>();
    for (MigrationInfo migration : flyway.info().all()) {
      if (migration.getVersion() == null || migration.getScript() == null) {
        throw new IllegalStateException("packaged migration source identity was incomplete");
      }
      migrations.add(
          new MigrationSourceIdentity(
              migration.getVersion().getVersion(),
              migration.getScript(),
              migration.getType().name(),
              migration.getChecksum()));
    }
    migrations.sort(
        java.util.Comparator.comparing(MigrationSourceIdentity::version)
            .thenComparing(MigrationSourceIdentity::script));
    if (migrations.stream().map(MigrationSourceIdentity::version).distinct().count()
        != migrations.size()) {
      throw new IllegalStateException("packaged migration source versions were ambiguous");
    }
    return List.copyOf(migrations);
  }

  private static String expectedMigrationScriptName(String script) {
    int separator = Math.max(script.lastIndexOf('/'), script.lastIndexOf('\\'));
    return script.substring(separator + 1);
  }

  private static PhysicalSchemaSnapshot readPhysicalSchemaSnapshot(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      List<String> schemas = catalogRows(connection, SCHEMA_INVENTORY_SQL);
      List<String> relations = catalogRows(connection, RELATION_INVENTORY_SQL);
      List<String> columns = catalogRows(connection, COLUMN_INVENTORY_SQL);
      List<String> indexes = catalogRows(connection, INDEX_INVENTORY_SQL);
      List<String> constraints = catalogRows(connection, CONSTRAINT_INVENTORY_SQL);
      List<String> types = catalogRows(connection, TYPE_INVENTORY_SQL);
      List<String> domainConstraints = catalogRows(connection, DOMAIN_CONSTRAINT_INVENTORY_SQL);
      List<String> enumLabels = catalogRows(connection, ENUM_INVENTORY_SQL);
      List<String> sequences = catalogRows(connection, SEQUENCE_INVENTORY_SQL);
      List<String> routines = catalogRows(connection, ROUTINE_INVENTORY_SQL);
      List<String> triggers = catalogRows(connection, TRIGGER_INVENTORY_SQL);
      List<String> policies = catalogRows(connection, POLICY_INVENTORY_SQL);
      List<String> extensions = catalogRows(connection, EXTENSION_INVENTORY_SQL);
      connection.commit();
      return new PhysicalSchemaSnapshot(
          schemas,
          relations,
          columns,
          indexes,
          constraints,
          types,
          domainConstraints,
          enumLabels,
          sequences,
          routines,
          triggers,
          policies,
          extensions);
    } catch (SQLException failure) {
      throw new IllegalStateException("physical PostgreSQL schema inventory failed");
    }
  }

  private static List<String> catalogRows(Connection connection, String sql) throws SQLException {
    List<String> rows = new ArrayList<>();
    int totalBytes = 0;
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet results = statement.executeQuery()) {
      while (results.next()) {
        String row = results.getString(1);
        if (row == null) {
          throw new SQLException("physical schema inventory contained a null record");
        }
        int rowBytes = row.getBytes(StandardCharsets.UTF_8).length;
        totalBytes += rowBytes;
        if (rows.size() >= MAX_CATALOG_ROWS || totalBytes > MAX_CATALOG_BYTES) {
          throw new SQLException("physical schema inventory exceeded its finite bound");
        }
        rows.add(row);
      }
    }
    return List.copyOf(rows);
  }

  private static void requireEmptyCohortSchema(PhysicalSchemaSnapshot snapshot) {
    if (!snapshot.isEmptyCohort()) {
      throw new IllegalStateException(
          "pre-migration PostgreSQL user relations or schema objects are not empty");
    }
  }

  private static void requireSamePhysicalSchema(
      PhysicalSchemaSnapshot expected, PhysicalSchemaSnapshot actual) {
    if (!expected.equals(actual)) {
      throw new IllegalStateException(
          "fresh PostgreSQL physical schema readback differed from owner-created migrations");
    }
  }

  private static void requireIdentityMatches(
      CanonicalGameplayMigrationDriverProtocol.Initialization initialization,
      CanonicalGameplayMigrationDriverProtocol.LocalObservation observation,
      String postgresUser,
      String redisCaDigest) {
    Map<String, Object> postgres = observation.postgres();
    Map<String, Object> redis = observation.redis();
    CanonicalGameplayLegacyMigrationStorageIdentity identity = initialization.storageIdentity();
    if (!identity.postgresSystemIdentifier().equals(postgres.get("systemIdentifier"))
        || !Long.valueOf(identity.postgresDatabaseOid()).equals(postgres.get("databaseOid"))
        || !identity.redisRunId().equals(redis.get("runId"))
        || !postgresUser.equals("gs_storage_owner")) {
      throw new IllegalStateException(
          "local PostgreSQL or Redis identity did not match the cohort");
    }
    if (!"".equals(postgres.get("listenAddresses"))
        || postgres.get("serverAddress") != null
        || postgres.get("serverPort") != null
        || !hasRequiredUnixSocketDirectory(postgres.get("unixSocketDirectories"))) {
      throw new IllegalStateException("PostgreSQL is not restricted to the required Unix socket");
    }
    if (!Boolean.TRUE.equals(redis.get("tlsEnabled"))
        || !Integer.valueOf(6380).equals(redis.get("tlsPort"))
        || !redisCaDigest.equals(redis.get("trustCaSha256"))) {
      throw new IllegalStateException("Redis TLS identity did not match the pinned endpoint");
    }
  }

  private static boolean hasRequiredUnixSocketDirectory(Object value) {
    if (!(value instanceof String directories)) {
      return false;
    }
    for (String directory : directories.split(",", -1)) {
      if ("/var/run/postgresql".equals(directory.strip())) {
        return true;
      }
    }
    return false;
  }

  private static String requiredEnvironment(Map<String, String> environment, String name) {
    String value = environment.get(name);
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalStateException("required migration driver configuration is unavailable");
    }
    return value;
  }

  private static String optionalEnvironment(Map<String, String> environment, String name) {
    String value = environment.get(name);
    if (value == null || value.isBlank()) {
      return null;
    }
    if (!value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalStateException("optional migration driver configuration is invalid");
    }
    return value;
  }

  private static String readSecret(Path path) throws IOException {
    long size = Files.size(path);
    if (size <= 0 || size > 4 * 1024) {
      throw new IOException("mounted credential file is empty or exceeds its bound");
    }
    byte[] bytes = Files.readAllBytes(path);
    if (bytes.length == 0 || bytes.length > 4 * 1024) {
      throw new IOException("mounted credential file changed beyond its bound");
    }
    String value = decodeUtf8(bytes);
    if (value.endsWith("\r\n")) {
      value = value.substring(0, value.length() - 2);
    } else if (value.endsWith("\n")) {
      value = value.substring(0, value.length() - 1);
    }
    if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
      throw new IOException("mounted credential file is not canonical text");
    }
    return value;
  }

  private static String decodeUtf8(byte[] bytes) throws IOException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw new IOException("mounted file is not UTF-8", malformed);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String canonicalJsonDigest(Object value) {
    try {
      return sha256(CANONICAL_JSON_MAPPER.writeValueAsBytes(value));
    } catch (IOException serializationFailure) {
      throw new IllegalStateException("local PostgreSQL observation could not be canonicalized");
    }
  }

  private static String singleText(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        throw new SQLException("local PostgreSQL observation returned no row");
      }
      String value = rows.getString(1);
      if (rows.next()) {
        throw new SQLException("local PostgreSQL observation returned multiple rows");
      }
      return value;
    }
  }

  private static String digestRoles(Connection connection) throws SQLException {
    List<String> loginRoles = new ArrayList<>();
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT rolname FROM pg_catalog.pg_roles WHERE rolcanlogin ORDER BY rolname");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        loginRoles.add(rows.getString(1));
        if (loginRoles.size() > 128) {
          throw new SQLException("PostgreSQL login-role inventory exceeded its bound");
        }
      }
    }
    if (!loginRoles.equals(List.of("gs_storage_owner"))) {
      throw new SQLException("PostgreSQL login-role inventory did not match the owner-only policy");
    }
    return canonicalJsonDigest(loginRoles);
  }

  private static String digestHbaRules(Connection connection) throws SQLException {
    List<Map<String, Object>> rules = new ArrayList<>();
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT type, database, user_name AS user, address, auth_method AS \"authMethod\", error"
                    + " FROM pg_catalog.pg_hba_file_rules ORDER BY rule_number NULLS LAST, line_number");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        Object error = rows.getObject("error");
        if (error != null) {
          throw new SQLException("PostgreSQL HBA rules contain a parse error");
        }
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", rows.getObject("type"));
        rule.put("database", postgresTextArray(rows.getArray("database")));
        rule.put("user", postgresTextArray(rows.getArray("user")));
        rule.put("address", rows.getObject("address"));
        rule.put("authMethod", rows.getObject("authMethod"));
        rule.put("error", null);
        rules.add(rule);
        if (rules.size() > 256) {
          throw new SQLException("PostgreSQL HBA inventory exceeded its bound");
        }
      }
    }
    return canonicalJsonDigest(rules);
  }

  private static List<String> postgresTextArray(java.sql.Array array) throws SQLException {
    if (array == null) {
      return null;
    }
    Object values = array.getArray();
    if (!(values instanceof Object[] entries)) {
      throw new SQLException("PostgreSQL HBA text array has an unsupported representation");
    }
    List<String> result = new ArrayList<>(entries.length);
    for (Object entry : entries) {
      if (entry != null && !(entry instanceof String)) {
        throw new SQLException("PostgreSQL HBA text array contains a non-text value");
      }
      result.add((String) entry);
    }
    return result;
  }

  private static String sha256File(Path path) throws IOException {
    long size = Files.size(path);
    if (size <= 0 || size > 1024 * 1024) {
      throw new IOException("mounted Redis CA file is empty or exceeds its bound");
    }
    byte[] bytes = Files.readAllBytes(path);
    if (bytes.length == 0 || bytes.length > 1024 * 1024) {
      throw new IOException("mounted Redis CA file changed beyond its bound");
    }
    return sha256(bytes);
  }

  private record DriverConfiguration(
      String postgresDatabase,
      String postgresUser,
      String postgresPassword,
      String serviceSchema,
      String redisHost,
      String redisUsername,
      String redisPassword,
      String redisCaDigest) {
    private DriverConfiguration {
      Objects.requireNonNull(postgresDatabase, "postgresDatabase");
      Objects.requireNonNull(postgresUser, "postgresUser");
      Objects.requireNonNull(postgresPassword, "postgresPassword");
      Objects.requireNonNull(serviceSchema, "serviceSchema");
      Objects.requireNonNull(redisHost, "redisHost");
      Objects.requireNonNull(redisPassword, "redisPassword");
      Objects.requireNonNull(redisCaDigest, "redisCaDigest");
    }

    private static DriverConfiguration fromEnvironment(
        CanonicalGameplayMigrationDriverProtocol.Initialization initialization) throws IOException {
      Map<String, String> environment = System.getenv();
      String database = requiredEnvironment(environment, "FIREMUD_POSTGRES_DB");
      String user = requiredEnvironment(environment, "FIREMUD_POSTGRES_USER");
      String schema = requiredEnvironment(environment, "SERVICE_SCHEMA");
      if (!SQL_IDENTIFIER.matcher(database).matches()
          || !SQL_IDENTIFIER.matcher(user).matches()
          || !SQL_IDENTIFIER.matcher(schema).matches()) {
        throw new IllegalStateException("migration database identifiers are not canonical");
      }
      String configuredHistoryTable = environment.get("SPRING_FLYWAY_TABLE");
      if (configuredHistoryTable != null
          && !flywayHistoryTable(schema).equals(configuredHistoryTable)) {
        throw new IllegalStateException("migration Flyway history table is not canonical");
      }
      return new DriverConfiguration(
          database,
          user,
          readSecret(POSTGRES_PASSWORD),
          schema,
          initialization.redisHost(),
          optionalEnvironment(environment, "FIREMUD_REDIS_USERNAME"),
          readSecret(REDIS_PASSWORD),
          sha256File(REDIS_CA));
    }
  }

  private static final class LiveObservationReader {
    private final DataSource postgres;
    private final StringRedisTemplate redis;
    private final Path redisCaFile;

    private LiveObservationReader(
        DataSource postgres, StringRedisTemplate redis, Path redisCaFile) {
      this.postgres = Objects.requireNonNull(postgres, "postgres");
      this.redis = Objects.requireNonNull(redis, "redis");
      this.redisCaFile = Objects.requireNonNull(redisCaFile, "redisCaFile");
    }

    private CanonicalGameplayMigrationDriverProtocol.LocalObservation read() {
      return new CanonicalGameplayMigrationDriverProtocol.LocalObservation(
          readPostgres(), readRedis());
    }

    private Map<String, Object> readPostgres() {
      try (Connection connection = postgres.getConnection()) {
        String systemIdentifier =
            singleText(
                connection, "SELECT system_identifier::text FROM pg_catalog.pg_control_system()");
        long databaseOid;
        try (PreparedStatement statement =
                connection.prepareStatement(
                    "SELECT oid::bigint FROM pg_catalog.pg_database"
                        + " WHERE datname = pg_catalog.current_database()");
            ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) {
            throw new SQLException("PostgreSQL database identity was unavailable");
          }
          databaseOid = rows.getLong(1);
          if (rows.wasNull() || rows.next()) {
            throw new SQLException("PostgreSQL database identity was ambiguous");
          }
        }
        String listenAddresses = singleText(connection, "SHOW listen_addresses");
        String hbaFile = singleText(connection, "SHOW hba_file");
        String unixSocketDirectories = singleText(connection, "SHOW unix_socket_directories");
        String serverAddress =
            singleNullableText(connection, "SELECT pg_catalog.inet_server_addr()::text");
        Integer serverPort =
            singleNullableInteger(connection, "SELECT pg_catalog.inet_server_port()");
        String rolesDigest = digestRoles(connection);
        String hbaRulesDigest = digestHbaRules(connection);
        if (systemIdentifier == null
            || !systemIdentifier.matches("[1-9][0-9]{0,19}")
            || databaseOid <= 0L
            || databaseOid > 0xffff_ffffL
            || hbaFile == null
            || hbaFile.isBlank()
            || unixSocketDirectories == null
            || listenAddresses == null) {
          throw new SQLException("PostgreSQL local identity or configuration was incomplete");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("systemIdentifier", systemIdentifier);
        values.put("databaseOid", databaseOid);
        values.put("serverAddress", serverAddress);
        values.put("serverPort", serverPort);
        values.put("listenAddresses", listenAddresses);
        values.put("hbaFile", hbaFile);
        values.put("unixSocketDirectories", unixSocketDirectories);
        values.put("rolesDigest", rolesDigest);
        values.put("hbaRulesDigest", hbaRulesDigest);
        return values;
      } catch (SQLException failure) {
        throw new IllegalStateException("local PostgreSQL identity/configuration recheck failed");
      }
    }

    private Map<String, Object> readRedis() {
      String trustCaDigest;
      try {
        trustCaDigest = sha256File(redisCaFile);
      } catch (IOException failure) {
        throw new IllegalStateException("local Redis trust anchor recheck failed");
      }
      try {
        Map<String, Object> values =
            redis.execute(
                (RedisConnection connection) -> {
                  Properties info = connection.serverCommands().info("server");
                  String runId = info == null ? null : info.getProperty("run_id");
                  Properties plainConfiguration = connection.serverCommands().getConfig("port");
                  String plainPortText =
                      plainConfiguration == null ? null : plainConfiguration.getProperty("port");
                  Properties tlsConfiguration = connection.serverCommands().getConfig("tls-port");
                  String tlsPortText =
                      tlsConfiguration == null ? null : tlsConfiguration.getProperty("tls-port");
                  return validatedRedisObservation(
                      runId, plainPortText, tlsPortText, trustCaDigest);
                });
        if (values == null) {
          throw new IllegalStateException("Redis live observation was unavailable");
        }
        return values;
      } catch (RuntimeException failure) {
        throw new IllegalStateException("local Redis identity/TLS recheck failed");
      }
    }
  }

  private static String singleNullableText(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        throw new SQLException("local PostgreSQL observation returned no row");
      }
      String value = rows.getString(1);
      if (rows.next()) {
        throw new SQLException("local PostgreSQL observation returned multiple rows");
      }
      return value;
    }
  }

  private static Integer singleNullableInteger(Connection connection, String sql)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        throw new SQLException("local PostgreSQL observation returned no row");
      }
      int value = rows.getInt(1);
      Integer result = rows.wasNull() ? null : value;
      if (rows.next()) {
        throw new SQLException("local PostgreSQL observation returned multiple rows");
      }
      return result;
    }
  }

  private static final class ProtocolFencedCohort
      implements CanonicalGameplayLegacyMigrationOwner.FencedCohort {
    private final CanonicalGameplayMigrationDriverProtocol.Initialization initialization;
    private final CanonicalGameplayMigrationDriverProtocol protocol;
    private final LiveObservationReader observationReader;
    private final CanonicalGameplayMigrationDriverProtocol.LocalObservation baseline;
    private final String postgresUser;
    private final String redisCaDigest;

    private ProtocolFencedCohort(
        CanonicalGameplayMigrationDriverProtocol.Initialization initialization,
        CanonicalGameplayMigrationDriverProtocol protocol,
        LiveObservationReader observationReader,
        CanonicalGameplayMigrationDriverProtocol.LocalObservation baseline,
        String postgresUser,
        String redisCaDigest) {
      this.initialization = Objects.requireNonNull(initialization, "initialization");
      this.protocol = Objects.requireNonNull(protocol, "protocol");
      this.observationReader = Objects.requireNonNull(observationReader, "observationReader");
      this.baseline = Objects.requireNonNull(baseline, "baseline");
      this.postgresUser = Objects.requireNonNull(postgresUser, "postgresUser");
      this.redisCaDigest = Objects.requireNonNull(redisCaDigest, "redisCaDigest");
    }

    @Override
    public java.util.UUID cohortId() {
      return initialization.cohortId();
    }

    @Override
    public java.util.UUID legacyWriterFence() {
      return initialization.legacyWriterFence();
    }

    @Override
    public CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity() {
      return initialization.storageIdentity();
    }

    @Override
    public void requireStillFenced(
        CanonicalGameplayLegacyMigrationStorageIdentity expectedIdentity) {
      if (!initialization.storageIdentity().equals(expectedIdentity)) {
        throw new IllegalStateException("migration storage identity changed");
      }
      CanonicalGameplayMigrationDriverProtocol.LocalObservation current = observationReader.read();
      requireIdentityMatches(initialization, current, postgresUser, redisCaDigest);
      if (!baseline.equals(current)) {
        throw new IllegalStateException(
            "local PostgreSQL/Redis configuration changed during migration");
      }
      protocol.requireFreshObservation(initialization, current);
    }
  }

  private record MigrationSourceIdentity(
      String version, String script, String type, Integer checksum) {}

  private record PhysicalSchemaSnapshot(
      List<String> schemas,
      List<String> relations,
      List<String> columns,
      List<String> indexes,
      List<String> constraints,
      List<String> types,
      List<String> domainConstraints,
      List<String> enumLabels,
      List<String> sequences,
      List<String> routines,
      List<String> triggers,
      List<String> policies,
      List<String> extensions) {
    private PhysicalSchemaSnapshot {
      schemas = List.copyOf(schemas);
      relations = List.copyOf(relations);
      columns = List.copyOf(columns);
      indexes = List.copyOf(indexes);
      constraints = List.copyOf(constraints);
      types = List.copyOf(types);
      domainConstraints = List.copyOf(domainConstraints);
      enumLabels = List.copyOf(enumLabels);
      sequences = List.copyOf(sequences);
      routines = List.copyOf(routines);
      triggers = List.copyOf(triggers);
      policies = List.copyOf(policies);
      extensions = List.copyOf(extensions);
      int totalRows = 0;
      long totalBytes = 0;
      for (List<String> inventory :
          List.of(
              schemas,
              relations,
              columns,
              indexes,
              constraints,
              types,
              domainConstraints,
              enumLabels,
              sequences,
              routines,
              triggers,
              policies,
              extensions)) {
        totalRows += inventory.size();
        for (String row : inventory) {
          totalBytes += row.getBytes(StandardCharsets.UTF_8).length;
        }
      }
      if (totalRows > MAX_CATALOG_ROWS || totalBytes > MAX_CATALOG_BYTES) {
        throw new IllegalArgumentException(
            "physical PostgreSQL schema inventory exceeded its total bound");
      }
    }

    private boolean isEmptyCohort() {
      return relations.isEmpty()
          && columns.isEmpty()
          && indexes.isEmpty()
          && constraints.isEmpty()
          && types.isEmpty()
          && domainConstraints.isEmpty()
          && enumLabels.isEmpty()
          && sequences.isEmpty()
          && routines.isEmpty()
          && triggers.isEmpty()
          && policies.isEmpty()
          && extensions.isEmpty();
    }
  }
}
