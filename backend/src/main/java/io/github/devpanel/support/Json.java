package io.github.devpanel.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The panel's own JSON (stored payloads, reports, webhooks, error bodies), kept apart from the
 * application's ObjectMapper. All Jackson calls go through here: Spring Boot 4 has Jackson 3,
 * whose packages differ, and its copy of the panel swaps this one file
 * ({@code backend-boot4/src/override}). Unknown fields are ignored when reading.
 */
public final class Json {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private static final ObjectMapper PRETTY = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

  private Json() {
  }

  /** @throws IllegalArgumentException when the value cannot be written */
  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e.getOriginalMessage(), e);
    }
  }

  public static String writePretty(Object value) {
    try {
      return PRETTY.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e.getOriginalMessage(), e);
    }
  }

  public static byte[] bytes(Object value) {
    try {
      return MAPPER.writeValueAsBytes(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e.getOriginalMessage(), e);
    }
  }

  /** Maps, lists, strings, numbers and booleans for {@code Object.class}. */
  public static <T> T read(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e.getOriginalMessage(), e);
    }
  }
}
