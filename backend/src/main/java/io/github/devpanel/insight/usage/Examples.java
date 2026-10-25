package io.github.devpanel.insight.usage;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An example JSON value for a Java type, from its fields: what the API runner fills a request
 * body with, so a call can be tried without looking up the DTO. Three levels deep at most.
 */
final class Examples {

  private static final int MAX_DEPTH = 3;
  private static final int MAX_FIELDS = 60;

  private Examples() {
  }

  static Object of(Type type, int depth) {
    if (type instanceof ParameterizedType parameterized) {
      Class<?> raw = (Class<?>) parameterized.getRawType();
      Type[] arguments = parameterized.getActualTypeArguments();
      if (Collection.class.isAssignableFrom(raw)) {
        List<Object> list = new ArrayList<>();
        if (depth < MAX_DEPTH && arguments.length == 1) {
          list.add(of(arguments[0], depth + 1));
        }
        return list;
      }
      if (Map.class.isAssignableFrom(raw)) {
        return new LinkedHashMap<>();
      }
      return of(raw, depth);
    }
    if (!(type instanceof Class<?> clazz)) {
      return null;
    }
    if (clazz == String.class || clazz == Character.class || clazz == char.class) {
      return "";
    }
    if (clazz == boolean.class || clazz == Boolean.class) {
      return false;
    }
    if (clazz.isPrimitive() || Number.class.isAssignableFrom(clazz)
        || clazz == BigDecimal.class || clazz == BigInteger.class) {
      return 0;
    }
    if (clazz.isEnum()) {
      Object[] constants = clazz.getEnumConstants();
      return constants.length == 0 ? "" : ((Enum<?>) constants[0]).name();
    }
    if (clazz == LocalDate.class) {
      return LocalDate.now().toString();
    }
    if (clazz == LocalDateTime.class || clazz == Date.class || clazz == java.sql.Timestamp.class
        || clazz == OffsetDateTime.class || clazz == ZonedDateTime.class) {
      return LocalDate.now() + "T00:00:00";
    }
    if (clazz == LocalTime.class) {
      return "00:00:00";
    }
    if (Temporal.class.isAssignableFrom(clazz)) {
      return "";
    }
    if (clazz == UUID.class) {
      return UUID.randomUUID().toString();
    }
    if (clazz.isArray()) {
      List<Object> list = new ArrayList<>();
      if (depth < MAX_DEPTH) {
        list.add(of(clazz.getComponentType(), depth + 1));
      }
      return list;
    }
    if (Collection.class.isAssignableFrom(clazz)) {
      return new ArrayList<>();
    }
    if (Map.class.isAssignableFrom(clazz) || clazz == Object.class
        || clazz.getName().startsWith("java.") || clazz.getName().startsWith("javax.")
        || clazz.getName().startsWith("org.springframework.")) {
      return new LinkedHashMap<>();
    }
    Map<String, Object> object = new LinkedHashMap<>();
    if (depth >= MAX_DEPTH) {
      return object;
    }
    for (Field field : fields(clazz)) {
      object.put(jsonName(field), of(field.getGenericType(), depth + 1));
    }
    return object;
  }

  /** Instance fields, superclasses first, without static, transient or synthetic ones. */
  static List<Field> fields(Class<?> type) {
    List<Class<?>> hierarchy = new ArrayList<>();
    for (Class<?> current = type; current != null && current != Object.class;
        current = current.getSuperclass()) {
      hierarchy.add(0, current);
    }
    List<Field> fields = new ArrayList<>();
    for (Class<?> current : hierarchy) {
      for (Field field : current.getDeclaredFields()) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)
            || field.isSynthetic() || ignored(field) || fields.size() >= MAX_FIELDS) {
          continue;
        }
        fields.add(field);
      }
    }
    return fields;
  }

  private static boolean ignored(Field field) {
    for (java.lang.annotation.Annotation annotation : field.getAnnotations()) {
      String name = annotation.annotationType().getName();
      if (name.equals("com.fasterxml.jackson.annotation.JsonIgnore")) {
        return true;
      }
    }
    return false;
  }

  private static String jsonName(Field field) {
    for (java.lang.annotation.Annotation annotation : field.getAnnotations()) {
      if (annotation.annotationType().getName()
          .equals("com.fasterxml.jackson.annotation.JsonProperty")) {
        try {
          Object value = annotation.annotationType().getMethod("value").invoke(annotation);
          if (value instanceof String text && !text.isEmpty()) {
            return text;
          }
        } catch (ReflectiveOperationException ignored) {
          // field name
        }
      }
    }
    return field.getName();
  }
}
