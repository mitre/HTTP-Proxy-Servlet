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
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AcceptEncodingTest {

  private Tomcat tomcat;
  private Context ctx;
  private int serverPort;

  @Before
  public void setUp() throws Exception {
    tomcat = new Tomcat();
    tomcat.setPort(0);
    String tempDir = System.getProperty("java.io.tmpdir");
    tomcat.setBaseDir( tempDir );
    tomcat.getConnector();
    ctx = tomcat.addContext("", tempDir );
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

    Wrapper w1 = Tomcat.addServlet(ctx, "proxy1", ProxyServlet.class.getName());
    w1.addInitParameter(ProxyServlet.P_LOG, "true");
    w1.addInitParameter(ProxyServlet.P_TARGET_URI, String.format("http://localhost:%d/acceptEncoding/", serverPort));
    w1.addInitParameter(ProxyServlet.P_HANDLECOMPRESSION, Boolean.TRUE.toString());
    ctx.addServletMappingDecoded("/acceptEncodingProxyHandleCompression/*", "proxy1");

    Wrapper w2 = Tomcat.addServlet(ctx, "proxy2", ProxyServlet.class.getName());
    w2.addInitParameter(ProxyServlet.P_LOG, "true");
    w2.addInitParameter(ProxyServlet.P_TARGET_URI, String.format("http://localhost:%d/acceptEncoding/", serverPort));
    w2.addInitParameter(ProxyServlet.P_HANDLECOMPRESSION, Boolean.FALSE.toString());
    ctx.addServletMappingDecoded("/acceptEncodingProxy/*", "proxy2");

    Tomcat.addServlet(ctx, "backend", new HttpServlet() {
      @Override
      protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.getOutputStream().write(req.getHeader("Accept-Encoding").getBytes(StandardCharsets.UTF_8));
      }
    });
    ctx.addServletMappingDecoded("/acceptEncoding/*", "backend");

    HttpGet queryHandleCompression = new HttpGet(String.format("http://localhost:%d/acceptEncodingProxyHandleCompression/test", serverPort));
    HttpGet query = new HttpGet(String.format("http://localhost:%d/acceptEncodingProxy/test", serverPort));

    final String dummyCompression = "DummyCompression";

    query.setHeader("Accept-Encoding", dummyCompression);
    queryHandleCompression.setHeader("Accept-Encoding", dummyCompression);

    try (CloseableHttpClient chc = HttpClientBuilder.create().disableContentCompression().build();
            CloseableHttpResponse responseHandleCompression = chc.execute(queryHandleCompression);
            CloseableHttpResponse response = chc.execute(query)
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
