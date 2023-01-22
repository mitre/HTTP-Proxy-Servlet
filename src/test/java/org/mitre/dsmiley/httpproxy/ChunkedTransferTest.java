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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.http.MalformedChunkCodingException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.eclipse.jetty.server.Handler;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import org.junit.runners.Parameterized.Parameters;

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

  private Server server;
  private ServletHandler servletHandler;
  private int serverPort;
  private boolean supportBackendCompression;
  private boolean handleCompressionApacheClient;

  public ChunkedTransferTest(boolean supportBackendCompression, boolean handleCompressionApacheClient) {
    this.supportBackendCompression = supportBackendCompression;
    this.handleCompressionApacheClient = handleCompressionApacheClient;
  }

  @Before
  public void setUp() throws Exception {
    server = new Server(0);
    servletHandler = new ServletHandler();
    Handler serverHandler = servletHandler;
    if(supportBackendCompression) {
      GzipHandler gzipHandler = new GzipHandler();
      gzipHandler.setHandler(serverHandler);
      gzipHandler.setSyncFlush(true);
      serverHandler = gzipHandler;
    } else {
      serverHandler = servletHandler;
    }
    server.setHandler(serverHandler);
    server.start();

    serverPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  @After
  public void tearDown() throws Exception {
    server.stop();
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
     the second messsage is expected. This in turn must be completely be read.

     If the CountDownLatch is not released, it will timeout and the second
     message will not be send.
     */

    final CountDownLatch guardForSecondRead = new CountDownLatch(1);
    final byte[] data1 = "event: message\ndata: Dummy Data1\n\n".getBytes(StandardCharsets.UTF_8);
    final byte[] data2 = "event: message\ndata: Dummy Data2\n\n".getBytes(StandardCharsets.UTF_8);

    ServletHolder servletHolder = servletHandler.addServletWithMapping(ProxyServlet.class, "/chatProxied/*");
    servletHolder.setInitParameter(ProxyServlet.P_LOG, "true");
    servletHolder.setInitParameter(ProxyServlet.P_TARGET_URI, String.format("http://localhost:%d/chat/", serverPort));
    servletHolder.setInitParameter(ProxyServlet.P_HANDLECOMPRESSION, Boolean.toString(handleCompressionApacheClient));

    ServletHolder dummyBackend = new ServletHolder(new HttpServlet() {
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
    servletHandler.addServletWithMapping(dummyBackend, "/chat/*");

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

    ServletHolder servletHolder = servletHandler.addServletWithMapping(ProxyServlet.class, "/chatProxied/*");
    servletHolder.setInitParameter(ProxyServlet.P_LOG, "true");
    servletHolder.setInitParameter(ProxyServlet.P_TARGET_URI, String.format("http://localhost:%d/chat/", serverPort));
    servletHolder.setInitParameter(ProxyServlet.P_HANDLECOMPRESSION, Boolean.toString(handleCompressionApacheClient));

    ServletHolder dummyBackend = new ServletHolder(new HttpServlet() {
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
          if (! guardForSecondRead.await(10, TimeUnit.SECONDS)) {
            throw new IOException("Wait timed out");
          }
          try {
            for(int i = 0; i < 100; i++) {
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
    servletHandler.addServletWithMapping(dummyBackend, "/chat/*");

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
}
