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

import static org.junit.Assert.assertEquals;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AcceptEncodingTest {

  private Server server;
  private ServletHandler servletHandler;
  private int serverPort;

  @Before
  public void setUp() throws Exception {
    server = new Server(0);
    servletHandler = new ServletHandler();
    Handler serverHandler = servletHandler;
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
  public void testHandlingAcceptEncodingHeader() throws Exception {
    /*
     Check the two compression handling modes for the proxy servlet. The
     proxy servlet can use the apache http clients content compression handling,
     in this case the stream is decompressed by the servlet and potentially
     recompressed by the servlet container. The Accept-Encoding header the
     client supplied must not be passed to the backend in that case, as the
     compression of the backend connection needs to be supported by apache
     http client.

     If the compression is not handled by the proxy servlet, the stream from the
     backend server is passed through. In this case, the Accept-Encoding header
     of the client needs to be passed through as is.
     */

    ServletHolder servletHolder = servletHandler.addServletWithMapping(ProxyServlet.class, "/acceptEncodingProxyHandleCompression/*");
    servletHolder.setInitParameter(ProxyServlet.P_LOG, "true");
    servletHolder.setInitParameter(ProxyServlet.P_TARGET_URI, String.format("http://localhost:%d/acceptEncoding/", serverPort));
    servletHolder.setInitParameter(ProxyServlet.P_HANDLECOMPRESSION, Boolean.TRUE.toString());

    ServletHolder servletHolder2 = servletHandler.addServletWithMapping(ProxyServlet.class, "/acceptEncodingProxy/*");
    servletHolder2.setInitParameter(ProxyServlet.P_LOG, "true");
    servletHolder2.setInitParameter(ProxyServlet.P_TARGET_URI, String.format("http://localhost:%d/acceptEncoding/", serverPort));
    servletHolder2.setInitParameter(ProxyServlet.P_HANDLECOMPRESSION, Boolean.FALSE.toString());

    ServletHolder dummyBackend = new ServletHolder(new HttpServlet() {
      @Override
      protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.getOutputStream().write(req.getHeader("Accept-Encoding").getBytes(StandardCharsets.UTF_8));
      }
    });
    servletHandler.addServletWithMapping(dummyBackend, "/acceptEncoding/*");

    HttpGet queryHandleCompression = new HttpGet(String.format("http://localhost:%d/acceptEncodingProxyHandleCompression/test", serverPort));
    HttpGet query = new HttpGet(String.format("http://localhost:%d/acceptEncodingProxy/test", serverPort));

    final String dummyCompression = "DummyCompression";

    query.setHeader("Accept-Encoding", dummyCompression);
    queryHandleCompression.setHeader("Accept-Encoding", dummyCompression);

    try (CloseableHttpClient chc = HttpClientBuilder.create().disableContentCompression().build();
            CloseableHttpResponse responseHandleCompression = chc.execute(queryHandleCompression);
            CloseableHttpResponse response = chc.execute(query);
    ) {
      try (InputStream is = response.getEntity().getContent()) {
        byte[] readData = readBlock(is);
        assertEquals(dummyCompression, toString(readData));
      }
      try (InputStream is = responseHandleCompression.getEntity().getContent()) {
        byte[] readData = readBlock(is);
        // Apache http client supports gzip and deflate by default
        assertEquals(
                new HashSet<>(Arrays.asList("gzip", "deflate")),
                new HashSet<>(Arrays.asList(toString(readData).split("\\s*,\\s*")))
        );
      }
    }
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
