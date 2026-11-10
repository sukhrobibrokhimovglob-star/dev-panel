package io.github.devpanel.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.lang.reflect.Type;

/**
 * The panel's own JSON (its API through {@code DevPanelJsonConverter}, stored payloads, reports,
 * webhooks, error bodies), kept apart from the application's ObjectMapper, whose naming or date
 * settings would break the UI. All Jackson calls go through here: Spring Boot 4 has Jackson 3,
 * whose packages differ, and its copy of the panel swaps this one file
 * ({@code backend-boot4/src/override}). Unknown fields are ignored when reading; dates are ISO
 * text, as Spring Boot writes them.
 */
public final class Json {

  /** Boot's modules when present (they come with spring-boot-starter-json), loaded by name. */
  private static final String[] MODULES = {
      "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule",
      "com.fasterxml.jackson.datatype.jdk8.Jdk8Module",
      "com.fasterxml.jackson.module.paramnames.ParameterNamesModule"};

  private static final ObjectMapper MAPPER = mapper();
  private static final ObjectMapper PRETTY = mapper().enable(SerializationFeature.INDENT_OUTPUT);

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

  /** For request bodies: generic types such as {@code Map<String, Object>}. */
  public static Object read(byte[] json, Type type) {
    try {
      return MAPPER.readValue(json, MAPPER.getTypeFactory().constructType(type));
    } catch (IOException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  private static ObjectMapper mapper() {
    ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    for (String module : MODULES) {
      try {
        mapper.registerModule((Module) Class.forName(module, true, Json.class.getClassLoader())
            .getDeclaredConstructor().newInstance());
      } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
        // Not on the classpath: java.time values then fail to write, as they would anyway
      }
    }
    return mapper;
  }
}
