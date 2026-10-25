package io.github.devpanel.insight.trace;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

/**
 * Counts the bytes a response sends (characters, for writers), passing everything through as it
 * is: no buffering, so large and streamed responses behave exactly as before. A copy of the first
 * {@value #CAPTURE} bytes is kept on the side, so an error response can show what it said.
 */
public class CountingResponse extends HttpServletResponseWrapper {

  static final int CAPTURE = 4096;

  private long count;
  private final java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
  private final StringBuilder capturedChars = new StringBuilder();
  private ServletOutputStream stream;
  private PrintWriter writer;

  public CountingResponse(HttpServletResponse response) {
    super(response);
  }

  public long count() {
    return count;
  }

  /**
   * The start of the body when it is text (JSON, XML, plain text), null otherwise or when nothing
   * was written.
   */
  public String capturedText() {
    String type = getContentType();
    if (type == null) {
      type = "";
    }
    String lower = type.toLowerCase(java.util.Locale.ROOT);
    boolean text = lower.isEmpty() || lower.contains("json") || lower.contains("xml")
        || lower.startsWith("text/");
    if (!text) {
      return count == 0 ? null : "[" + type + ", " + count + " bytes]";
    }
    if (capturedChars.length() > 0) {
      return capturedChars.toString();
    }
    if (captured.size() == 0) {
      return null;
    }
    return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
  }

  private void keep(byte[] bytes, int off, int len) {
    int room = CAPTURE - captured.size();
    if (room > 0) {
      captured.write(bytes, off, Math.min(room, len));
    }
  }

  private void keep(char[] chars, int off, int len) {
    int room = CAPTURE - capturedChars.length();
    if (room > 0) {
      capturedChars.append(chars, off, Math.min(room, len));
    }
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    if (stream == null) {
      ServletOutputStream delegate = super.getOutputStream();
      stream = new ServletOutputStream() {
        @Override
        public void write(int b) throws IOException {
          delegate.write(b);
          count++;
          if (captured.size() < CAPTURE) {
            captured.write(b);
          }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
          delegate.write(b, off, len);
          count += len;
          keep(b, off, len);
        }

        @Override
        public void flush() throws IOException {
          delegate.flush();
        }

        @Override
        public void close() throws IOException {
          delegate.close();
        }

        @Override
        public boolean isReady() {
          return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
          delegate.setWriteListener(listener);
        }
      };
    }
    return stream;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    if (writer == null) {
      PrintWriter delegate = super.getWriter();
      writer = new PrintWriter(new Writer() {
        @Override
        public void write(char[] buffer, int off, int len) {
          delegate.write(buffer, off, len);
          count += len;
          keep(buffer, off, len);
        }

        @Override
        public void flush() {
          delegate.flush();
        }

        @Override
        public void close() {
          delegate.close();
        }
      });
    }
    return writer;
  }
}
