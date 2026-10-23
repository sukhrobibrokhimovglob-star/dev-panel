package io.github.devpanel.web;

import io.github.devpanel.account.DevAccount;
import io.github.devpanel.support.DevPanelException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** A {@link DevAccount} controller parameter is the account {@link DevPanelAuthFilter} logged in. */
public class CurrentAccountResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return DevAccount.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
    Object account = webRequest.getAttribute(DevPanelAuthFilter.ACCOUNT_ATTRIBUTE,
        RequestAttributes.SCOPE_REQUEST);
    if (account instanceof DevAccount devAccount) {
      return devAccount;
    }
    throw new DevPanelException(HttpStatus.UNAUTHORIZED, "Login required");
  }
}
