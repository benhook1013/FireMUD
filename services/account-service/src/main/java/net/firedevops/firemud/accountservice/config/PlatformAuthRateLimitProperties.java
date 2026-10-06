package net.firedevops.firemud.accountservice.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Base64;
import net.firedevops.firemud.common.ratelimit.RateLimitSubjectHash;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Account-owned platform-auth abuse-control settings and its separate Cache Redis role. */
@ConfigurationProperties(prefix = "firemud.account.platform-auth-abuse")
public class PlatformAuthRateLimitProperties {
  private CacheRedis cacheRedis = new CacheRedis();
  private String hmacKeyId = "";
  private String hmacKeyBase64 = "";
  private int subjectWindowSeconds = 300;
  private int globalWindowSeconds = 60;
  private int globalAdmissionsPerWindow = 1000;
  private int sourceAttemptsPerWindow = 60;
  private int candidateAttemptsPerWindow = 20;
  private int sourceFailuresPerWindow = 20;
  private int candidateFailuresPerWindow = 5;
  private int sourceRetryBaseSeconds = 5;
  private int candidateRetryBaseSeconds = 15;
  private int globalRetryBaseSeconds = 5;
  private int maxRetrySeconds = 300;
  private long maxCounterValue = 1_000_000L;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Spring binds this mutable nested configuration before owner bean creation.")
  public CacheRedis getCacheRedis() {
    return cacheRedis;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring supplies the nested configuration through its property binder.")
  public void setCacheRedis(CacheRedis cacheRedis) {
    this.cacheRedis = cacheRedis;
  }

  public String getHmacKeyId() {
    return hmacKeyId;
  }

  public void setHmacKeyId(String hmacKeyId) {
    this.hmacKeyId = hmacKeyId;
  }

  public String getHmacKeyBase64() {
    return hmacKeyBase64;
  }

  public void setHmacKeyBase64(String hmacKeyBase64) {
    this.hmacKeyBase64 = hmacKeyBase64;
  }

  public int getSubjectWindowSeconds() {
    return subjectWindowSeconds;
  }

  public void setSubjectWindowSeconds(int subjectWindowSeconds) {
    this.subjectWindowSeconds = subjectWindowSeconds;
  }

  public int getGlobalWindowSeconds() {
    return globalWindowSeconds;
  }

  public void setGlobalWindowSeconds(int globalWindowSeconds) {
    this.globalWindowSeconds = globalWindowSeconds;
  }

  public int getGlobalAdmissionsPerWindow() {
    return globalAdmissionsPerWindow;
  }

  public void setGlobalAdmissionsPerWindow(int globalAdmissionsPerWindow) {
    this.globalAdmissionsPerWindow = globalAdmissionsPerWindow;
  }

  public int getSourceAttemptsPerWindow() {
    return sourceAttemptsPerWindow;
  }

  public void setSourceAttemptsPerWindow(int sourceAttemptsPerWindow) {
    this.sourceAttemptsPerWindow = sourceAttemptsPerWindow;
  }

  public int getCandidateAttemptsPerWindow() {
    return candidateAttemptsPerWindow;
  }

  public void setCandidateAttemptsPerWindow(int candidateAttemptsPerWindow) {
    this.candidateAttemptsPerWindow = candidateAttemptsPerWindow;
  }

  public int getSourceFailuresPerWindow() {
    return sourceFailuresPerWindow;
  }

  public void setSourceFailuresPerWindow(int sourceFailuresPerWindow) {
    this.sourceFailuresPerWindow = sourceFailuresPerWindow;
  }

  public int getCandidateFailuresPerWindow() {
    return candidateFailuresPerWindow;
  }

  public void setCandidateFailuresPerWindow(int candidateFailuresPerWindow) {
    this.candidateFailuresPerWindow = candidateFailuresPerWindow;
  }

  public int getSourceRetryBaseSeconds() {
    return sourceRetryBaseSeconds;
  }

  public void setSourceRetryBaseSeconds(int sourceRetryBaseSeconds) {
    this.sourceRetryBaseSeconds = sourceRetryBaseSeconds;
  }

  public int getCandidateRetryBaseSeconds() {
    return candidateRetryBaseSeconds;
  }

  public void setCandidateRetryBaseSeconds(int candidateRetryBaseSeconds) {
    this.candidateRetryBaseSeconds = candidateRetryBaseSeconds;
  }

  public int getGlobalRetryBaseSeconds() {
    return globalRetryBaseSeconds;
  }

  public void setGlobalRetryBaseSeconds(int globalRetryBaseSeconds) {
    this.globalRetryBaseSeconds = globalRetryBaseSeconds;
  }

  public int getMaxRetrySeconds() {
    return maxRetrySeconds;
  }

  public void setMaxRetrySeconds(int maxRetrySeconds) {
    this.maxRetrySeconds = maxRetrySeconds;
  }

  public long getMaxCounterValue() {
    return maxCounterValue;
  }

  public void setMaxCounterValue(long maxCounterValue) {
    this.maxCounterValue = maxCounterValue;
  }

  public RateLimitSubjectHash.HmacKey activeHmacKey() {
    if (hmacKeyId == null
        || hmacKeyId.isBlank()
        || hmacKeyBase64 == null
        || hmacKeyBase64.isBlank()) {
      throw new IllegalStateException("platform-auth rate-limit HMAC key is not configured");
    }
    try {
      return new RateLimitSubjectHash.HmacKey(hmacKeyId, Base64.getDecoder().decode(hmacKeyBase64));
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("platform-auth rate-limit HMAC key is invalid", ex);
    }
  }

  public static class CacheRedis {
    private String url = "";
    private String host = "redis-cache";
    private int port = 6379;
    private int database;
    private int commandTimeoutMillis = 1_000;
    private String username = "";
    private String password = "";
    private boolean useSsl;
    private boolean startTls;
    private boolean verifyPeer = true;

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getHost() {
      return host;
    }

    public void setHost(String host) {
      this.host = host;
    }

    public int getPort() {
      return port;
    }

    public void setPort(int port) {
      this.port = port;
    }

    public int getDatabase() {
      return database;
    }

    public void setDatabase(int database) {
      this.database = database;
    }

    public int getCommandTimeoutMillis() {
      return commandTimeoutMillis;
    }

    public void setCommandTimeoutMillis(int commandTimeoutMillis) {
      this.commandTimeoutMillis = commandTimeoutMillis;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String username) {
      this.username = username;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }

    public boolean isUseSsl() {
      return useSsl;
    }

    public void setUseSsl(boolean useSsl) {
      this.useSsl = useSsl;
    }

    public boolean isStartTls() {
      return startTls;
    }

    public void setStartTls(boolean startTls) {
      this.startTls = startTls;
    }

    public boolean isVerifyPeer() {
      return verifyPeer;
    }

    public void setVerifyPeer(boolean verifyPeer) {
      this.verifyPeer = verifyPeer;
    }
  }
}
