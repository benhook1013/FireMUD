package net.firedevops.firemud.socialgroups.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "firemud.chat")
public class ChatProperties {
  private ChatCacheSettings says = new ChatCacheSettings(7200L, 50);
  private ChatCacheSettings tells = new ChatCacheSettings(172800L, 50);
  private ChatCacheSettings guild = new ChatCacheSettings(172800L, 50);
  private ChatCacheSettings city = new ChatCacheSettings(172800L, 50);
  private ChatCacheSettings account = new ChatCacheSettings(172800L, 50);

  public ChatCacheSettings getSays() {
    return says;
  }

  public void setSays(ChatCacheSettings says) {
    this.says = says;
  }

  public ChatCacheSettings getTells() {
    return tells;
  }

  public void setTells(ChatCacheSettings tells) {
    this.tells = tells;
  }

  public ChatCacheSettings getGuild() {
    return guild;
  }

  public void setGuild(ChatCacheSettings guild) {
    this.guild = guild;
  }

  public ChatCacheSettings getCity() {
    return city;
  }

  public void setCity(ChatCacheSettings city) {
    this.city = city;
  }

  public ChatCacheSettings getAccount() {
    return account;
  }

  public void setAccount(ChatCacheSettings account) {
    this.account = account;
  }

  public record ChatCacheSettings(long historyTtlSeconds, int maxMessages) {}
}
