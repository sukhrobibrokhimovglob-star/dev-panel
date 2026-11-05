package io.github.devpanel.support;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Spring Boot 4 (Jackson 3) twin of backend/.../support/Json.java: same methods, same
 * behaviour. The build puts it in place of that file in the Boot 4 copy.
 */
public final class Json {

  private static final JsonMapper MAPPER = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();
  private static final JsonMapper PRETTY = JsonMapper.builder()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .build();

  private Json() {
  }

  /** @throws IllegalArgumentException when the value cannot be written */
  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(e.getOriginalMessage(), e);
    }
  }

  public static String writePretty(Object value) {
    try {
      return PRETTY.writeValueAsString(value);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(e.getOriginalMessage(), e);
    }
  }

  public static byte[] bytes(Object value) {
    try {
      return MAPPER.writeValueAsBytes(value);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(e.getOriginalMessage(), e);
    }
  }

  /** Maps, lists, strings, numbers and booleans for {@code Object.class}. */
  public static <T> T read(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(e.getOriginalMessage(), e);
    }
  }
}
