/*
 * Copyright 2020 Matthias Bläsing.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mitre.dsmiley.httpproxy;

import java.io.IOException;
import java.util.zip.GZIPOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.http.MalformedChunkCodingException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class ChunkedTransferTest {
  @Parameters
  public static List<Object[]> data() {
    return Arrays.asList(new Object[][] {
      {false, false},
      {false, true},
      {true, false},
      {true, true}
    });
  }

  private Tomcat tomcat;
  private Context ctx;
  private int serverPort;
  private final boolean supportBackendCompression;
  private final boolean handleCompressionApacheClient;

  public ChunkedTransferTest(boolean supportBackendCompression, boolean handleCompressionApacheClient) {
    this.supportBackendCompression = supportBackendCompression;
    this.handleCompressionApacheClient = handleCompressionApacheClient;
  }

  @Before
  public void setUp() throws Exception {
    tomcat = new Tomcat();
    tomcat.setPort(0);
    String tempDir = System.getProperty("java.io.tmpdir");
    tomcat.setBaseDir(tempDir);
    tomcat.getConnector();
    ctx = tomcat.addContext("", tempDir);

    if (supportBackendCompression) {
      FilterDef filterDef = new FilterDef();
      filterDef.setFilterName("gzip");
      filterDef.setFilter(new GzipFilter());
      ctx.addFilterDef(filterDef);
      FilterMap filterMap = new FilterMap();
      filterMap.setFilterName("gzip");
      filterMap.addURLPattern("/chat/*");
      ctx.addFilterMap(filterMap);
    }

    tomcat.start();
    serverPort = tomcat.getConnector().getLocalPort();
  }

  @After
  public void tearDown() throws Exception {
    tomcat.stop();
    tomcat.destroy();
    serverPort = -1;
  }

  @Test
  public void testChunkedTransfer() throws Exception {
    /*
     Check that proxy requests are not buffered in the ProxyServlet, but
     immediately flushed. The test works by creating a servlet, that writes
     the first message and flushes the outputstream, further processing
     is blocked by a count down latch.

     The client now reads the first message. The message must be completely
     received and further data must not be present.

     After the first message is consumed, the CountDownLatch is released and
     the second message is expected. This in turn must be completely be read.

     If the CountDownLatch is not released, it will timeout and the second
     message will not be send.
     */

    final CountDownLatch guardForSecondRead = new CountDownLatch(1);
    final byte[] data1 = "event: message\ndata: Dummy Data1\n\n".getBytes(StandardCharsets.UTF_8);
    final byte[] data2 = "event: message\ndata: Dummy Data2\n\n".getBytes(StandardCharsets.UTF_8);

    Wrapper proxyWrapper = Tomcat.addServlet(ctx, "proxy", ProxyServlet.class.getName());
    proxyWrapper.addInitParameter(ProxyServlet.P_LOG, "true");
    proxyWrapper.addInitParameter(ProxyServlet.P_TARGET_URI, String.format("http://localhost:%d/chat/", serverPort));
    proxyWrapper.addInitParameter(ProxyServlet.P_HANDLECOMPRESSION, Boolean.toString(handleCompressionApacheClient));
    ctx.addServletMappingDecoded("/chatProxied/*", "proxy");

    Tomcat.addServlet(ctx, "backend", new HttpServlet() {
      @Override
      protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/event-stream");
        OutputStream os = resp.getOutputStream();
        // Write first message for client and flush it out
        os.write(data1);
        os.flush();
        try {
          // Wait for client to request the second message by counting down the
          // latch - if the latch times out, the second message will not be
          // send and the corresponding assert will fail
          if (guardForSecondRead.await(10, TimeUnit.SECONDS)) {
            os.write(data2);
            os.flush();
          }
        } catch (InterruptedException ex) {
          throw new IOException(ex);
        }
      }
    });
    ctx.addServletMappingDecoded("/chat/*", "backend");

    HttpGet url = new HttpGet(String.format("http://localhost:%d/chatProxied/test", serverPort));

    try (CloseableHttpClient chc = HttpClientBuilder.create().build();
            CloseableHttpResponse chr = chc.execute(url)) {
      try (InputStream is = chr.getEntity().getContent()) {
        byte[] readData = readBlock(is);
        assertTrue("No data received (message1)", readData.length > 0);
        assertArrayEquals("Received data: '" + toString(readData) + "'  (message1)", data1, readData);
        guardForSecondRead.countDown();
        readData = readBlock(is);
        assertTrue("No data received  (message2)", readData.length > 0);
        assertArrayEquals("Received data: '" + toString(readData) + "'  (message2)", data2, readData);
      }
    }
  }

  @Test
  public void testChunkedTransferClosing() throws Exception {
    /*
     This test ensures, that the chunk encoded backing connection is closed,
     when the closing of the proxy (frontend) connection is detected.

     The idea is, that in the servlet the closing of the backing connection can
     be detected, because the output stream is closed and an IOException is
     raised. If no exception is raised when writing multiple chunks, it must
     be assumed, that the backing connection is not closed.
     */
    final CountDownLatch guardForSecondRead = new CountDownLatch(1);
    final CountDownLatch guardForEnd = new CountDownLatch(1);
    final byte[] data1 = "event: message\ndata: Dummy Data1\n\n".getBytes(StandardCharsets.UTF_8);
    final byte[] data2 = "event: message\ndata: Dummy Data2\n\n".getBytes(StandardCharsets.UTF_8);

    Wrapper proxyWrapper = Tomcat.addServlet(ctx, "proxy", ProxyServlet.class.getName());
    proxyWrapper.addInitParameter(ProxyServlet.P_LOG, "true");
    proxyWrapper.addInitParameter(ProxyServlet.P_TARGET_URI, String.format("http://localhost:%d/chat/", serverPort));
    proxyWrapper.addInitParameter(ProxyServlet.P_HANDLECOMPRESSION, Boolean.toString(handleCompressionApacheClient));
    ctx.addServletMappingDecoded("/chatProxied/*", "proxy");

    Tomcat.addServlet(ctx, "backend", new HttpServlet() {
      @Override
      protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/event-stream");
        OutputStream os = resp.getOutputStream();
        // Write first message for client and flush it out
        os.write(data1);
        os.flush();
        try {
          // Wait for client to request the second message by counting down the
          // latch - if the latch times out, the second message will not be
          // send and the corresponding assert will fail
          if (!guardForSecondRead.await(10, TimeUnit.SECONDS)) {
            throw new IOException("Wait timed out");
          }
          try {
            for (int i = 0; i < 100; i++) {
              os.write(data2);
              os.flush();
              Thread.sleep(100);
            }
          } catch (IOException ex) {
            // This point is reached when the output stream is closed - the
            // count down latch is count down to indicate success
            guardForEnd.countDown();
          }
        } catch (InterruptedException ex) {
          throw new IOException(ex);
        }
      }
    });
    ctx.addServletMappingDecoded("/chat/*", "backend");

    HttpGet url = new HttpGet(String.format("http://localhost:%d/chatProxied/test", serverPort));

    try (CloseableHttpClient chc = HttpClientBuilder.create().build()) {
      CloseableHttpResponse chr = chc.execute(url);
      try (InputStream is = chr.getEntity().getContent()) {
        byte[] readData = readBlock(is);
        assertTrue("No data received (message1)", readData.length > 0);
        assertArrayEquals("Received data: '" + toString(readData) + "'  (message1)", data1, readData);
        chr.close();
      } catch (MalformedChunkCodingException ex) {
        // this is expected
      } finally {
        chr.close();
      }
    }
    // Release sending of further messages
    guardForSecondRead.countDown();
    // Wait for the reporting of the closed connection
    assertTrue(guardForEnd.await(10, TimeUnit.SECONDS));
  }

  private static String toString(byte[] data) {
    return new String(data, StandardCharsets.UTF_8);
  }

  private static byte[] readBlock(InputStream is) throws IOException {
    byte[] buffer = new byte[10 * 1024];
    int read = is.read(buffer);
    return Arrays.copyOfRange(buffer, 0, read);
  }

  // Wraps the backend response in GZIP encoding with sync-flush so chunked
  // data is flushed to the client incrementally rather than buffered.
  private static class GzipFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
      HttpServletResponse httpResponse = (HttpServletResponse) response;
      httpResponse.setHeader("Content-Encoding", "gzip");
      GzipResponseWrapper wrapper = new GzipResponseWrapper(httpResponse);
      try {
        chain.doFilter(request, wrapper);
      } finally {
        try { wrapper.finish(); } catch (IOException ignored) {}
      }
    }

    @Override
    public void destroy() {}
  }

  private static class GzipResponseWrapper extends HttpServletResponseWrapper {
    private final GZIPOutputStream gzipOut;
    private final ServletOutputStream servletOut;

    GzipResponseWrapper(HttpServletResponse response) throws IOException {
      super(response);
      // true = syncFlush: each flush() also flushes the underlying gzip stream
      gzipOut = new GZIPOutputStream(response.getOutputStream(), true);
      servletOut = new ServletOutputStream() {
        @Override
        public void write(int b) throws IOException { gzipOut.write(b); }
        @Override
        public void write(byte[] b, int off, int len) throws IOException { gzipOut.write(b, off, len); }
        @Override
        public void flush() throws IOException { gzipOut.flush(); }
        @Override
        public boolean isReady() { return true; }
        @Override
        public void setWriteListener(WriteListener writeListener) {}
      };
    }

    @Override
    public ServletOutputStream getOutputStream() { return servletOut; }

    void finish() throws IOException { gzipOut.finish(); }
  }
}
