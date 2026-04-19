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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.After;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class ParallelConnectionsTest {

  private Tomcat tomcat;
  private Context ctx;
  private int serverPort;

  @Before
  public void setUp() throws Exception {
    tomcat = new Tomcat();
    tomcat.setPort(0);
    String tempDir = System.getProperty("java.io.tmpdir");
    tomcat.setBaseDir(tempDir);
    tomcat.getConnector();
    ctx = tomcat.addContext("", tempDir);
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
  public void testHandlingMultipleConnectionsSameRoute() throws Exception {
    /*
     This test ensures, that a minimum number of parallel connections can be
     established. The test works by opening "parallelConnectionsToTest"
     connections, this is enforced by a countdownlatch, that ensures, that all
     connections to the backend are established before they are served by the
     demo servlet.
     */

    int parallelConnectionsToTest = 10;

    Wrapper proxyWrapper = Tomcat.addServlet(ctx, "proxy", ProxyServlet.class.getName());
    proxyWrapper.addInitParameter(ProxyServlet.P_LOG, "true");
    proxyWrapper.addInitParameter(
        ProxyServlet.P_MAXCONNECTIONS,
        Integer.toString(parallelConnectionsToTest));
    proxyWrapper.addInitParameter(
        ProxyServlet.P_TARGET_URI,
        String.format("http://localhost:%d/sampleBackend/", serverPort));
    ctx.addServletMappingDecoded("/sampleBackendProxied/*", "proxy");

    CountDownLatch requestsReceived = new CountDownLatch(parallelConnectionsToTest);

    Tomcat.addServlet(
        ctx, "backend", new HttpServlet() {
          @Override
          protected void doGet(HttpServletRequest req, HttpServletResponse resp)
              throws IOException {
            try {
              // The latch ensures, that all servlets wait until all expected
              // connections are made. Only after all clients have connected, the
              // request is fulfilled.
              requestsReceived.countDown();
              if (requestsReceived.await(10, TimeUnit.SECONDS)) {
                resp.setHeader("Content-Type", "text/plain; charset=utf-8");
                OutputStream os = resp.getOutputStream();
                OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                osw.write("Works");
                osw.flush();
              }
            } catch (InterruptedException ex) {
              throw new IOException(ex);
            }
          }
        });
    ctx.addServletMappingDecoded("/sampleBackend/*", "backend");

    URL url = new URL(String.format("http://localhost:%d/sampleBackendProxied/test", serverPort));

    ExecutorService es = Executors.newFixedThreadPool(parallelConnectionsToTest);

    class Client implements Callable<String> {
      @Override
      public String call() throws Exception {
        try (InputStream is = url.openStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
          byte[] buffer = new byte[10 * 1024];
          int read;
          while ((read = is.read(buffer)) > 0) {
            baos.write(buffer, 0, read);
          }
          return baos.toString("UTF-8");
        }
      }
    }

    List<Future<String>> result = new ArrayList<>(parallelConnectionsToTest);

    for (int i = 0; i < parallelConnectionsToTest; i++) {
      result.add(es.submit(new Client()));
    }

    for (Future<String> f : result) {
      assertEquals("Works", f.get());
    }
  }
}
