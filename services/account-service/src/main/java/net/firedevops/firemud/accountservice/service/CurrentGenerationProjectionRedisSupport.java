package net.firedevops.firemud.accountservice.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Objects;
import net.firedevops.firemud.common.redis.contracts.RedisInvocationContract;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Private Redis mechanics shared only by Account's typed current-generation projection stores. */
final class CurrentGenerationProjectionRedisSupport {
  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
  private static final String RESOURCE_PATH = "redis/account_generation_projection_cas.lua";
  private static final byte[] CAS_SCRIPT_BYTES = scriptBytes();
  private static final String CAS_SCRIPT_SHA1 = digest("SHA-1", CAS_SCRIPT_BYTES);
  private static final String CAS_SCRIPT_SHA256 = digest("SHA-256", CAS_SCRIPT_BYTES);

  private CurrentGenerationProjectionRedisSupport() {}

  static InitializedClient initialize(String host, int port, String principal, String password) {
    if (!"account_coord_app".equals(principal)) {
      throw new IllegalArgumentException(
          "Account current-generation Redis principal must be account_coord_app");
    }
    RedisStandaloneConfiguration redisConfiguration = new RedisStandaloneConfiguration(host, port);
    redisConfiguration.setUsername(principal);
    redisConfiguration.setPassword(RedisPassword.of(password));
    LettuceClientConfiguration clientConfiguration =
        LettuceClientConfiguration.builder().commandTimeout(COMMAND_TIMEOUT).build();

    LettuceConnectionFactory connectionFactory =
        new LettuceConnectionFactory(redisConfiguration, clientConfiguration);
    connectionFactory.afterPropertiesSet();
    StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
    return new InitializedClient(connectionFactory, template);
  }

  static void verifyScriptDigest(RedisScriptDescriptor descriptor) {
    requireSupportedDescriptor(descriptor);
    if (!CAS_SCRIPT_SHA256.equals(descriptor.sha256())) {
      throw new IllegalStateException(
          "Account current-generation projection Lua resource digest differs from its registration");
    }
  }

  static StoredValue readStoredValue(StringRedisTemplate template, String key) {
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    return template.execute(
        (RedisCallback<StoredValue>)
            connection -> {
              byte[] value = connection.stringCommands().get(keyBytes);
              long ttlMillis = value == null ? -2L : connection.keyCommands().pTtl(keyBytes);
              return new StoredValue(value, ttlMillis);
            });
  }

  static String executeRegistered(
      StringRedisTemplate template,
      RedisInvocationContract invocation,
      RedisScriptDescriptor expectedDescriptor,
      String key,
      String expectedMode,
      String expectedBytes,
      String candidateBytes) {
    requireSupportedDescriptor(expectedDescriptor);
    if (invocation == null || invocation.descriptor() != expectedDescriptor) {
      throw new IllegalStateException(
          "Account current-generation invocation is not its registered owner contract");
    }
    verifyScriptDigest(expectedDescriptor);
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    byte[] expectedModeBytes = expectedMode.getBytes(StandardCharsets.US_ASCII);
    byte[] expectedBytesUtf8 = expectedBytes.getBytes(StandardCharsets.UTF_8);
    byte[] candidateBytesUtf8 = candidateBytes.getBytes(StandardCharsets.UTF_8);
    return template.execute(
        (RedisCallback<String>)
            connection -> {
              String loadedSha = connection.scriptingCommands().scriptLoad(CAS_SCRIPT_BYTES);
              if (!CAS_SCRIPT_SHA1.equals(loadedSha)) {
                throw new IllegalStateException(
                    "Redis loaded a different Account current-generation projection script");
              }
              Object result =
                  connection
                      .scriptingCommands()
                      .evalSha(
                          loadedSha,
                          ReturnType.VALUE,
                          1,
                          keyBytes,
                          expectedModeBytes,
                          expectedBytesUtf8,
                          candidateBytesUtf8);
              return decodeScriptResult(result);
            });
  }

  static String decodeUtf8(byte[] value) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value))
        .toString();
  }

  private static void requireSupportedDescriptor(RedisScriptDescriptor descriptor) {
    if (descriptor != AccountGenerationProjectionRedisContract.descriptor()
        && descriptor != TenantGenerationProjectionRedisContract.descriptor()
        && descriptor != MembershipGenerationProjectionRedisContract.descriptor()) {
      throw new IllegalStateException(
          "Only Account, tenant, and membership current-generation projection scripts may use this client");
    }
    if (!"account-service".equals(descriptor.owner())
        || !"account_coord_app".equals(descriptor.principal())
        || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
        || !RESOURCE_PATH.equals(descriptor.resourcePath())) {
      throw new IllegalStateException(
          "Account current-generation script descriptor has an invalid owner or resource");
    }
  }

  private static byte[] scriptBytes() {
    try (var input = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
      return input.readAllBytes();
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static String decodeScriptResult(Object result) {
    if (result instanceof byte[] bytes) {
      try {
        return StandardCharsets.US_ASCII
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
      } catch (CharacterCodingException malformed) {
        throw new IllegalStateException(
            "Registered Account projection script returned non-ASCII", malformed);
      }
    }
    if (result instanceof String text) {
      return text;
    }
    if (result == null) {
      return null;
    }
    throw new IllegalStateException(
        "Registered Account projection script returned an unsupported result type");
  }

  record InitializedClient(
      LettuceConnectionFactory connectionFactory, StringRedisTemplate template) {
    InitializedClient {
      Objects.requireNonNull(connectionFactory, "connection factory is required");
      Objects.requireNonNull(template, "Redis template is required");
    }
  }

  record StoredValue(byte[] bytes, long ttlMillis) {}
}
