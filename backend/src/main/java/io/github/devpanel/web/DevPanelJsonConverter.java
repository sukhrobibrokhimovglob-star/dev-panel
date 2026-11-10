package io.github.devpanel.web;

import io.github.devpanel.support.Json;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractGenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * JSON of the panel's own API, written and read with {@link Json} instead of the application's
 * ObjectMapper. An application mapper without java.time support, with snake_case names or with
 * dates as numbers would otherwise break the UI. Put first in the converter list, it answers only
 * for {@link DevPanelApi} controllers; every other endpoint keeps the application's converters.
 */
public class DevPanelJsonConverter extends AbstractGenericHttpMessageConverter<Object> {

  public DevPanelJsonConverter() {
    super(MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));
    setDefaultCharset(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean supports(Class<?> type) {
    return !(CharSequence.class.isAssignableFrom(type) || type == byte[].class
        || Resource.class.isAssignableFrom(type) || ResourceRegion.class.isAssignableFrom(type)
        || MultiValueMap.class.isAssignableFrom(type));
  }

  @Override
  public boolean canRead(Class<?> type, MediaType mediaType) {
    return panelRequest() && super.canRead(type, mediaType);
  }

  @Override
  public boolean canRead(Type type, Class<?> contextClass, MediaType mediaType) {
    return panelRequest() && supports(rawClass(type)) && super.canRead(type, contextClass,
        mediaType);
  }

  @Override
  public boolean canWrite(Class<?> type, MediaType mediaType) {
    return panelRequest() && super.canWrite(type, mediaType);
  }

  @Override
  public boolean canWrite(Type type, Class<?> type2, MediaType mediaType) {
    return panelRequest() && supports(type2 != null ? type2 : rawClass(type))
        && super.canWrite(type, type2, mediaType);
  }

  @Override
  public Object read(Type type, Class<?> contextClass, HttpInputMessage input)
      throws IOException {
    try {
      return Json.read(input.getBody().readAllBytes(), type);
    } catch (IllegalArgumentException e) {
      throw new HttpMessageNotReadableException("Invalid JSON: " + e.getMessage(), e, input);
    }
  }

  @Override
  protected Object readInternal(Class<?> type, HttpInputMessage input) throws IOException {
    return read(type, null, input);
  }

  @Override
  protected void writeInternal(Object value, Type type, HttpOutputMessage output)
      throws IOException {
    byte[] json;
    try {
      json = Json.bytes(value);
    } catch (IllegalArgumentException e) {
      throw new HttpMessageNotWritableException("Could not write JSON: " + e.getMessage(), e);
    }
    output.getBody().write(json);
  }

  /** A request handled by one of the panel's controllers (also inside its exception handler). */
  static boolean panelRequest() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return false;
    }
    Object handler = attributes.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
        RequestAttributes.SCOPE_REQUEST);
    return handler instanceof HandlerMethod method
        && AnnotatedElementUtils.hasAnnotation(method.getBeanType(), DevPanelApi.class);
  }

  private static Class<?> rawClass(Type type) {
    if (type instanceof Class<?> raw) {
      return raw;
    }
    if (type instanceof ParameterizedType parameterized
        && parameterized.getRawType() instanceof Class<?> raw) {
      return raw;
    }
    return Object.class;
  }
}
