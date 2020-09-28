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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ChunkedTransferTest {

  private Server server;
  private ServletHandler servletHandler;
  private int serverPort;

  @Before
  public void setUp() throws Exception {
    server = new Server(0);
    servletHandler = new ServletHandler();
    server.setHandler(servletHandler);
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

    URL url = new URL(String.format("http://localhost:%d/chatProxied/test", serverPort));

    InputStream is = url.openStream();
    try {
      byte[] readData = readUntilBlocked(is);
      assertTrue("No data received (message1)", readData.length > 0);
      assertArrayEquals("Received data: '" + toString(readData) + "'  (message1)", data1, readData);
      guardForSecondRead.countDown();
      readData = readUntilBlocked(is);
      assertTrue("No data received  (message2)", readData.length > 0);
      assertArrayEquals("Received data: '" + toString(readData) + "'  (message2)", data2, readData);
    } finally {
      is.close();
    }
  }

  private static String toString(byte[] data) {
    return new String(data, StandardCharsets.UTF_8);
  }

  private static byte[] readUntilBlocked(InputStream is) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buffer = new byte[10 * 1024];
    do {
      int read = is.read(buffer);
      if( read >= 0) {
        baos.write(buffer, 0, read);
      } else {
        break;
      }
    } while(is.available() > 0);
    return baos.toByteArray();
  }
}
