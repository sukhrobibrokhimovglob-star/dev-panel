package io.github.devpanel.web;

import io.github.devpanel.account.DevAccount;
import io.github.devpanel.session.DevSessions.Session;
import io.github.devpanel.support.DevPanelException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * A {@link DevAccount} or {@link Session} controller parameter is the logged-in account or its
 * session, as {@link DevPanelAuthFilter} found them.
 */
public class CurrentAccountResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    Class<?> type = parameter.getParameterType();
    return DevAccount.class.equals(type) || Session.class.equals(type);
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
    String attribute = DevAccount.class.equals(parameter.getParameterType())
        ? DevPanelAuthFilter.ACCOUNT_ATTRIBUTE
        : DevPanelAuthFilter.SESSION_ATTRIBUTE;
    Object value = webRequest.getAttribute(attribute, RequestAttributes.SCOPE_REQUEST);
    if (value == null) {
      throw new DevPanelException(HttpStatus.UNAUTHORIZED, "Login required");
    }
    return value;
  }
}
