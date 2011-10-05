package org.mitre.dsmiley.httpproxy;

import com.meterware.httpunit.GetMethodWebRequest;
import com.meterware.httpunit.PostMethodWebRequest;
import com.meterware.httpunit.WebRequest;
import com.meterware.httpunit.WebResponse;
import com.meterware.servletunit.ServletRunner;
import com.meterware.servletunit.ServletUnitClient;
import org.apache.http.*;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.localserver.LocalTestServer;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.*;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author David Smiley - dsmiley@mitre.org
 */
public class ProxyServletTest
{

  /**
   * From Apache httpcomponents/httpclient. Note httpunit has a similar thing called PseudoServlet but it is
   * not as good since you can't even make it echo the request back.
   */
  private LocalTestServer localTestServer;

  /** From Meterware httpunit. */
  private ServletRunner servletRunner;
  private ServletUnitClient sc;

  @Before
  public void setUp() throws Exception {
    localTestServer = new LocalTestServer(null, null);
    localTestServer.start();
    localTestServer.register("/targetPath*",new RequestInfoHandler());//matches /targetPath and /targetPath/blahblah

    servletRunner = new ServletRunner();
    Properties params = new Properties();
    params.setProperty(ProxyServlet.P_PROXY_HOST, "localhost");
    params.setProperty(ProxyServlet.P_PROXY_PORT, localTestServer.getServiceAddress().getPort()+"");
    params.setProperty(ProxyServlet.P_PROXY_PATH, "/targetPath");//dummy
    params.setProperty(ProxyServlet.P_LOG, "true");
    servletRunner.registerServlet("/proxyMe/*", ProxyServlet.class.getName(), params);//also matches /proxyMe (no path info)
    sc = servletRunner.newClient();
  }

  @After
  public void tearDown() throws Exception {
   servletRunner.shutDown();
   localTestServer.stop();
  }

  @Test
  public void test() throws Exception {
    execGetAndAssert(new GetMethodWebRequest("http://localhost/proxyMe"));
    execGetAndAssert(new GetMethodWebRequest("http://localhost/proxyMe/"));

    execPostAndAssert(new PostMethodWebRequest("http://localhost/proxyMe"));
    execPostAndAssert(new PostMethodWebRequest("http://localhost/proxyMe/pathInfo"));
    execPostAndAssert(new PostMethodWebRequest("http://localhost/proxyMe?def=DEF"));
    execPostAndAssert(new PostMethodWebRequest("http://localhost/proxyMe/pathInfo?def=DEF"));
  }

  @Test
  public void testSendFile() throws Exception {
    final PostMethodWebRequest request = new PostMethodWebRequest("http://localhost/proxyMe",true);//true: mime encoded
    InputStream data = new ByteArrayInputStream("testFileData".getBytes("UTF-8"));
    request.selectFile("fileNameParam", "fileName", data, "text/plain");
    WebResponse rsp = execPostAndAssert(request);
    assertTrue(rsp.getText().contains("Content-Type: multipart/form-data; boundary="));
  }

  private WebResponse execGetAndAssert(GetMethodWebRequest request) throws IOException, SAXException {
    WebResponse rsp = execAndAssert(request);
    //TODO     //no assertions for GET but a failure should throw
    return rsp;
  }

  private WebResponse execPostAndAssert(PostMethodWebRequest request) throws IOException, SAXException {
    request.setParameter("abc","ABC");

    WebResponse rsp = execAndAssert(request);

    assertTrue(rsp.getText().contains("ABC"));
    return rsp;
  }

  private WebResponse execAndAssert(WebRequest request) throws IOException, SAXException {
    WebResponse rsp = sc.getResponse( request );
    assertEquals(200,rsp.getResponseCode());
    //assertEquals("TESTREASON",rsp.getResponseMessage());
    assertTrue(rsp.getText().startsWith("REQUESTLINE:"));
    return rsp;
  }


  /**
   * Writes the input 
   */
  private static class RequestInfoHandler implements HttpRequestHandler
  {

    public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      PrintWriter pw = new PrintWriter(baos,false);
      final RequestLine rl = request.getRequestLine();
      pw.println("REQUESTLINE: " + rl.getProtocolVersion() + " " + rl.getMethod() + " " + rl.getUri());
      for (Header header : request.getAllHeaders()) {
        pw.println(header.getName() + ": " + header.getValue());
      }
      pw.println("BODY: (below)");
      pw.flush();//done with pw now

      if (request instanceof HttpEntityEnclosingRequest) {
        HttpEntityEnclosingRequest enclosingRequest = (HttpEntityEnclosingRequest) request;
        HttpEntity entity = enclosingRequest.getEntity();
        byte[] body = EntityUtils.toByteArray(entity);
        baos.write(body);
      }

      response.setStatusCode(200);
      response.setReasonPhrase("TESTREASON");
      response.setEntity(new ByteArrayEntity(baos.toByteArray()));
    }
  }
}
