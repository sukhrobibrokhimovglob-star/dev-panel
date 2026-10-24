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
 * is: no buffering, so large and streamed responses behave exactly as before.
 */
public class CountingResponse extends HttpServletResponseWrapper {

  private long count;
  private ServletOutputStream stream;
  private PrintWriter writer;

  public CountingResponse(HttpServletResponse response) {
    super(response);
  }

  public long count() {
    return count;
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
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
          delegate.write(b, off, len);
          count += len;
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
