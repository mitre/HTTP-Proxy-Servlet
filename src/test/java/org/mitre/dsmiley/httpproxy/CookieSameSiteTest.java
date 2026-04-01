/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mitre.dsmiley.httpproxy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CookieSameSiteTest {

  private Server server;
  private ServletContextHandler context;
  private int serverPort;

  @Before
  public void setUp() throws Exception {
    server = new Server(0);
    context = new ServletContextHandler();
    context.setContextPath("/");
    server.setHandler(context);
    server.start();
    serverPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  @After
  public void tearDown() throws Exception {
    server.stop();
    serverPort = -1;
  }

  @Test
  public void testSameSiteAttributeIsPreserved() throws Exception {
    // Backend returns a cookie with SameSite=Strict
    ServletHolder backendHolder = new ServletHolder(new HttpServlet() {
      @Override
      protected void doGet(HttpServletRequest req, HttpServletResponse resp)
          throws ServletException, IOException {
        resp.addHeader("Set-Cookie", "JSESSIONID=1234; Path=/backend; SameSite=Strict");
      }
    });
    context.addServlet(backendHolder, "/backend/*");

    ServletHolder proxyHolder = context.addServlet(ProxyServlet.class, "/proxy/*");
    proxyHolder.setInitParameter(ProxyServlet.P_TARGET_URI,
        String.format("http://localhost:%d/backend/", serverPort));

    HttpGet request = new HttpGet(String.format("http://localhost:%d/proxy/test", serverPort));
    try (CloseableHttpClient client = HttpClientBuilder.create().disableRedirectHandling().build();
         CloseableHttpResponse response = client.execute(request)) {
      Header setCookieHeader = response.getFirstHeader("Set-Cookie");
      assertNotNull("Set-Cookie header must be present", setCookieHeader);
      assertTrue("SameSite attribute must be preserved when proxying cookies",
          setCookieHeader.getValue().contains("SameSite=Strict"));
    }
  }
}
