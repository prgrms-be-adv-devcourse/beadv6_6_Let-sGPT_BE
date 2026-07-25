package com.openat.chat.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chat.data")
public class ChatQueryDataSourceProperties {

  private boolean enabled;
  private String url = "";
  private String username = "ai_query_app";
  private String password = "";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
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

  public boolean isConfigured() {
    return enabled && hasText(url) && hasText(username) && hasText(password);
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
