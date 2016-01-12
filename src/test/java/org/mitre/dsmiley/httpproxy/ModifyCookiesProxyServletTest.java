package org.mitre.dsmiley.httpproxy;

import com.meterware.httpunit.GetMethodWebRequest;
import com.meterware.httpunit.WebResponse;

import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.junit.Test;
import org.mitre.dsmiley.httpproxy.ProxyServletTest.RequestInfoHandler;
import org.xml.sax.SAXException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

/**
 * tests to show that by extending ProxyServlet we can override methods coping headers to proxied response and from it
 */
public class ModifyCookiesProxyServletTest extends ProxyServletTest {

  @Override
  public void setUp() throws Exception {
    servletName = ProxyServlet.class.getName();
    super.setUp();
  }
  
  @Override
  protected void setUpServlet(Properties servletProps) {
    //servletProps.putAll(servletProps);
	servletProps.setProperty(ProxyServlet.P_USE_ORIGINAL_COOKIE_PATH, "true");
    targetBaseUri = "http://localhost:"+localTestServer.getServiceAddress().getPort()+"/targetPath";
    servletProps.setProperty("targetUri", targetBaseUri);
    servletRunner.registerServlet(servletPath + "/*", servletName, servletProps);//also matches /proxyMe (no path info)
    sourceBaseUri = "http://localhost/proxyMe";//localhost:0 is hard-coded in ServletUnitHttpRequest
  }
  
  @Test
  public void testSetCookie() throws Exception {
    final String HEADER = "Set-Cookie";
    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        response.setHeader(HEADER, "JSESSIONID=1234; Path=/path/to/match; Expires=Wed, 13 Jan 2021 22:23:01 GMT; Domain=.foo.bar.com; HttpOnly");
        super.handle(request, response, context);
      }
    });

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    WebResponse rsp = execAndAssert(req, "");
    // note httpunit doesn't set all cookie fields, ignores max-agent, secure, etc.
    assertEquals("!Proxy!" + servletName + "JSESSIONID=1234;path=/path/to/match", rsp.getHeaderField(HEADER));
  }
  
  @Test
  public void testSetCookie2() throws Exception {
    final String HEADER = "Set-Cookie2";
    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        response.setHeader(HEADER, "JSESSIONID=1234; Path=/path/to/match; Max-Age=3600; Domain=.foo.bar.com; Secure");
        super.handle(request, response, context);
      }
    });

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    WebResponse rsp = execAndAssert(req, "");
    // note httpunit doesn't set all cookie fields, ignores max-agent, secure, etc.
    // also doesn't support more than one header of same name so I can't test this working on two cookies
    assertEquals("!Proxy!" + servletName + "JSESSIONID=1234;path=/path/to/match", rsp.getHeaderField("Set-Cookie"));
  }
  
  @Test
  public void testSetCookieHttpOnly() throws Exception { //See GH #50
    final String HEADER = "Set-Cookie";
    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        response.setHeader(HEADER, "JSESSIONID=1234; Path=/path/to/match; HttpOnly");
        super.handle(request, response, context);
      }
    });

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    WebResponse rsp = execAndAssert(req, "");
    // note httpunit doesn't set all cookie fields, ignores max-agent, secure, etc.
    assertEquals("!Proxy!" + servletName + "JSESSIONID=1234;path=/path/to/match", rsp.getHeaderField(HEADER));
  }
  
  @Test
  public void testRedirect() throws IOException, SAXException {
    final String COOKIE_SET_HEADER = "Set-Cookie";
    localTestServer.register("/targetPath*",new HttpRequestHandler()
    {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        response.setHeader(HttpHeaders.LOCATION,request.getFirstHeader("xxTarget").getValue());
        response.setHeader(COOKIE_SET_HEADER,"JSESSIONID=1234; path=/;");
        response.setStatusCode(HttpStatus.SC_MOVED_TEMPORARILY);
      }
    });//matches /targetPath and /targetPath/blahblah
    GetMethodWebRequest request = makeGetMethodRequest(sourceBaseUri + "/%64%69%72%2F");
    assertRedirect(request, "/dummy", "/dummy");//TODO represents a bug to fix
    assertRedirect(request, targetBaseUri+"/dummy?a=b", sourceBaseUri+"/dummy?a=b");
    // %-encoded Redirects must be rewritten
    assertRedirect(request, targetBaseUri+"/sample%20url", sourceBaseUri+"/sample%20url");
    assertRedirect(request, targetBaseUri+"/sample%20url?a=b", sourceBaseUri+"/sample%20url?a=b");
    assertRedirect(request, targetBaseUri+"/sample%20url?a=b#frag", sourceBaseUri+"/sample%20url?a=b#frag");
    assertRedirect(request, targetBaseUri+"/sample+url", sourceBaseUri+"/sample+url");
    assertRedirect(request, targetBaseUri+"/sample+url?a=b", sourceBaseUri+"/sample+url?a=b");
    assertRedirect(request, targetBaseUri+"/sample+url?a=b#frag", sourceBaseUri+"/sample+url?a=b#frag");
    assertRedirect(request, targetBaseUri+"/sample+url?a+b=b%20c#frag%23", sourceBaseUri+"/sample+url?a+b=b%20c#frag%23");
    // Absolute redirects to 3rd parties must pass-through unchanged
    assertRedirect(request, "http://blackhole.org/dir/file.ext?a=b#c", "http://blackhole.org/dir/file.ext?a=b#c");
  }
  
  private void assertRedirect(GetMethodWebRequest request, String origRedirect, String resultRedirect) throws IOException, SAXException {
	    request.setHeaderField("xxTarget", origRedirect);
	    WebResponse rsp = sc.getResponse(request);

	    assertEquals(HttpStatus.SC_MOVED_TEMPORARILY,rsp.getResponseCode());
	    assertEquals("",rsp.getText());
	    String gotLocation = rsp.getHeaderField(HttpHeaders.LOCATION);
	    assertEquals(resultRedirect, gotLocation);
	    assertEquals("!Proxy!"+servletName+"JSESSIONID=1234;path=/", rsp.getHeaderField("Set-Cookie"));
	  }
  
}
