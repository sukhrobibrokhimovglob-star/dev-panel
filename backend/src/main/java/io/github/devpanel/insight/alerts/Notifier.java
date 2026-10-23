package io.github.devpanel.insight.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Delivers alert texts: Telegram (a bot of your own, free) and/or any webhook. */
public class Notifier {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final String telegramToken;
  private final String telegramChatId;
  private final String webhookUrl;

  public Notifier(String telegramToken, String telegramChatId, String webhookUrl) {
    this.telegramToken = blankToNull(telegramToken);
    this.telegramChatId = blankToNull(telegramChatId);
    this.webhookUrl = blankToNull(webhookUrl);
  }

  public boolean telegramConfigured() {
    return telegramToken != null && telegramChatId != null;
  }

  public boolean webhookConfigured() {
    return webhookUrl != null;
  }

  public boolean configured() {
    return telegramConfigured() || webhookConfigured();
  }

  /** @throws IOException when a configured channel refused the message */
  public void send(String text, Map<String, Object> payload) throws IOException {
    if (telegramConfigured()) {
      String form = "chat_id=" + encode(telegramChatId) + "&text=" + encode(cut(text))
          + "&disable_web_page_preview=true";
      post("https://api.telegram.org/bot" + telegramToken + "/sendMessage",
          "application/x-www-form-urlencoded", form.getBytes(StandardCharsets.UTF_8));
    }
    if (webhookConfigured()) {
      post(webhookUrl, "application/json", JSON.writeValueAsBytes(payload));
    }
  }

  private static void post(String url, String contentType, byte[] body) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setRequestMethod("POST");
    connection.setConnectTimeout(10_000);
    connection.setReadTimeout(10_000);
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", contentType);
    try (OutputStream out = connection.getOutputStream()) {
      out.write(body);
    }
    int status = connection.getResponseCode();
    connection.disconnect();
    if (status >= 300) {
      // The token is part of the Telegram URL: never put the URL into the message
      throw new IOException("Alert channel answered HTTP " + status);
    }
  }

  /** Telegram allows 4096 characters per message. */
  private static String cut(String text) {
    return text.length() > 4000 ? text.substring(0, 4000) + "…" : text;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
