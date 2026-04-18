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
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CookieSameSiteTest {

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
  public void testSameSiteAttributeIsPreserved() throws Exception {
    Tomcat.addServlet(ctx, "backend", new HttpServlet() {
      @Override
      protected void doGet(HttpServletRequest req, HttpServletResponse resp)
          throws ServletException, IOException {
        resp.addHeader("Set-Cookie", "JSESSIONID=1234; Path=/backend; SameSite=Strict");
      }
    });
    ctx.addServletMappingDecoded("/backend/*", "backend");

    Wrapper proxyWrapper = Tomcat.addServlet(ctx, "proxy", ProxyServlet.class.getName());
    proxyWrapper.addInitParameter(ProxyServlet.P_TARGET_URI,
        String.format("http://localhost:%d/backend/", serverPort));
    ctx.addServletMappingDecoded("/proxy/*", "proxy");

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
