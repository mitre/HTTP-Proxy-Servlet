package org.mitre.dsmiley.httpproxy;

import com.meterware.httpunit.GetMethodWebRequest;
import com.meterware.httpunit.WebResponse;
import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.protocol.HttpContext;
import org.junit.Test;

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

  public static class ModifyHeadersProxyServlet extends ProxyServlet {
    public static final String REQUEST_HEADER = "REQUEST_HEADER";
    public static final String RESPONSE_HEADER = "RESPONSE_HEADER";

    @Override
    protected void addHeaderToProxyRequest(HttpRequest proxyRequest, String headerName, String headerValue) {
      if (REQUEST_HEADER.equalsIgnoreCase(headerName)) {
        headerValue = "REQUEST_VALUE_MODIFIED";
      }
      proxyRequest.addHeader(headerName, headerValue);
    }

    @Override
    protected void addHeaderToResponse(HttpServletResponse servletResponse, String headerName, String headerValue) {
      if (RESPONSE_HEADER.equalsIgnoreCase(headerName)) {
        headerValue = "RESPONSE_VALUE_MODIFIED";
      }
      servletResponse.addHeader(headerName, headerValue);
    }

  }
}
