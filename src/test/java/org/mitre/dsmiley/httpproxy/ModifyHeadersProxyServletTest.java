/*
 * Copyright MITRE
 *
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

import com.meterware.httpunit.GetMethodWebRequest;
import com.meterware.httpunit.WebResponse;
import org.junit.Test;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.http.HttpRequest;

import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import static org.junit.Assert.assertEquals;

/**
 * tests to show that by extending ProxyServlet we can override methods coping headers to proxied response and from it
 */
public class ModifyHeadersProxyServletTest extends ProxyServletTest {

  @Override
  public void setUp() throws Exception {
    servletName = ModifyHeadersProxyServlet.class.getName();
    super.setUp();
  }

  @Test
  public void testModifyRequestHeader() throws Exception {
    // Stop the target server and restart with header checking servlet
    targetServer.stop();
    targetServer = new org.eclipse.jetty.server.Server(targetServerPort);
    ServletHandler handler = new ServletHandler();
    targetServer.setHandler(handler);
    
    ServletHolder holder = new ServletHolder(new HttpServlet() {
      @Override
      protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String headerValue = request.getHeader(ModifyHeadersProxyServlet.REQUEST_HEADER);
        assertEquals("REQUEST_VALUE_MODIFIED", headerValue);
        
        // Call parent RequestInfoServlet behavior
        new RequestInfoServlet().service(request, response);
      }
    });
    handler.addServletWithMapping(holder, "/targetPath/*");
    targetServer.start();

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    req.setHeaderField(ModifyHeadersProxyServlet.REQUEST_HEADER, "INITIAL_VALUE");

    execAndAssert(req, "");
  }

  @Test
  public void testModifyResponseHeader() throws Exception {
    // Stop the target server and restart with header setting servlet
    targetServer.stop();
    targetServer = new org.eclipse.jetty.server.Server(targetServerPort);
    ServletHandler handler = new ServletHandler();
    targetServer.setHandler(handler);
    
    ServletHolder holder = new ServletHolder(new HttpServlet() {
      @Override
      protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setHeader(ModifyHeadersProxyServlet.RESPONSE_HEADER, "INITIAL_VALUE");
        
        // Call parent RequestInfoServlet behavior
        new RequestInfoServlet().service(request, response);
      }
    });
    handler.addServletWithMapping(holder, "/targetPath/*");
    targetServer.start();

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    WebResponse rsp = execAndAssert(req, "");

    assertEquals("RESPONSE_VALUE_MODIFIED", rsp.getHeaderField(ModifyHeadersProxyServlet.RESPONSE_HEADER));
  }

  @SuppressWarnings({ "serial" })
  public static class ModifyHeadersProxyServlet extends ProxyServlet {
    public static final String REQUEST_HEADER = "REQUEST_HEADER";
    public static final String RESPONSE_HEADER = "RESPONSE_HEADER";

    @Override
    protected void copyRequestHeader(HttpServletRequest servletRequest, HttpRequest.Builder proxyRequest, String headerName) {
      if (REQUEST_HEADER.equalsIgnoreCase(headerName)) {
        proxyRequest.header(headerName, "REQUEST_VALUE_MODIFIED");
      } else {
        super.copyRequestHeader(servletRequest, proxyRequest, headerName);
      }
    }

    @Override
    protected void copyResponseHeader(HttpServletRequest servletRequest,
                                      HttpServletResponse servletResponse, String headerName, String headerValue) {
      if (RESPONSE_HEADER.equalsIgnoreCase(headerName)) {
        servletResponse.addHeader(headerName, "RESPONSE_VALUE_MODIFIED");
      } else {
        super.copyResponseHeader(servletRequest, servletResponse, headerName, headerValue);
      }
    }

  }
}
