package io.github.devpanel.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DevPanelExceptionHandlerTest {

  private final DevPanelExceptionHandler handler = new DevPanelExceptionHandler();

  @Test
  void aBrowserThatLeftGetsNoAnswer() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setContentType("text/event-stream");
    Exception gone = new IllegalStateException("send failed",
        new IOException("Broken pipe"));

    assertThat(DevPanelExceptionHandler.clientGone(gone)).isTrue();
    assertThat(handler.unexpected(gone, request(), response)).isNull();
  }

  @Test
  void anEventStreamNeverGetsAJsonBody() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setContentType("text/event-stream");

    assertThat(handler.unexpected(new IllegalStateException("boom"), request(), response))
        .isNull();
  }

  @Test
  void otherFailuresStillAnswerWithTheErrorBody() {
    Exception failure = new IllegalStateException("boom");

    assertThat(DevPanelExceptionHandler.clientGone(failure)).isFalse();
    assertThat(handler.unexpected(failure, request(), new MockHttpServletResponse())
        .getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  private static MockHttpServletRequest request() {
    return new MockHttpServletRequest("GET", "/dev-panel/api/logs/live");
  }
}
