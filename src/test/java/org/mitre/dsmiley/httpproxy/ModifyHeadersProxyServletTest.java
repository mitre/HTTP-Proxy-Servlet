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
import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.protocol.HttpContext;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

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
    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        Header headerToTest = request.getFirstHeader(ModifyHeadersProxyServlet.REQUEST_HEADER);
        assertEquals("REQUEST_VALUE_MODIFIED", headerToTest.getValue());

        super.handle(request, response, context);
      }
    });

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    req.setHeaderField(ModifyHeadersProxyServlet.REQUEST_HEADER, "INITIAL_VALUE");

    execAndAssert(req, "");
  }

  @Test
  public void testModifyResponseHeader() throws Exception {
    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        response.setHeader(ModifyHeadersProxyServlet.RESPONSE_HEADER, "INITIAL_VALUE");
        super.handle(request, response, context);
      }
    });

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    WebResponse rsp = execAndAssert(req, "");

    assertEquals("RESPONSE_VALUE_MODIFIED", rsp.getHeaderField(ModifyHeadersProxyServlet.RESPONSE_HEADER));
  }

  @SuppressWarnings({ "serial" })
  public static class ModifyHeadersProxyServlet extends ProxyServlet {
    public static final String REQUEST_HEADER = "REQUEST_HEADER";
    public static final String RESPONSE_HEADER = "RESPONSE_HEADER";

    @Override
    protected void copyRequestHeader(HttpServletRequest servletRequest, HttpRequest proxyRequest, String headerName) {
      if (REQUEST_HEADER.equalsIgnoreCase(headerName)) {
        proxyRequest.addHeader(headerName, "REQUEST_VALUE_MODIFIED");
      } else {
        super.copyRequestHeader(servletRequest, proxyRequest, headerName);
      }
    }

    @Override
    protected void copyResponseHeader(HttpServletRequest servletRequest,
                                      HttpServletResponse servletResponse, Header header) {
      if (RESPONSE_HEADER.equalsIgnoreCase(header.getName())) {
        servletResponse.addHeader(header.getName(), "RESPONSE_VALUE_MODIFIED");
      } else {
        super.copyResponseHeader(servletRequest, servletResponse, header);
      }
    }

  }
}
