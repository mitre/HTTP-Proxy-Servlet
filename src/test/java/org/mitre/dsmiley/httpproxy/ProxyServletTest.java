package org.mitre.dsmiley.httpproxy;

import com.meterware.httpunit.GetMethodWebRequest;
import com.meterware.httpunit.PostMethodWebRequest;
import com.meterware.httpunit.WebRequest;
import com.meterware.httpunit.WebResponse;
import com.meterware.servletunit.ServletRunner;
import com.meterware.servletunit.ServletUnitClient;
import org.apache.http.localserver.EchoHandler;
import org.apache.http.localserver.LocalTestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author David Smiley - dsmiley@mitre.org
 */
public class ProxyServletTest
{

  private LocalTestServer localTestServer;
  private ServletRunner servletRunner;

  @Before
  public void setUp() throws Exception {
    localTestServer = new LocalTestServer(null, null);
    localTestServer.start();

    servletRunner = new ServletRunner();
    //servletRunner.
    Properties params = new Properties();
    params.setProperty(ProxyServlet.P_PROXY_HOST, "localhost");
    params.setProperty(ProxyServlet.P_PROXY_PORT, localTestServer.getServiceAddress().getPort()+"");
    params.setProperty(ProxyServlet.P_PROXY_PATH, "/targetPath");//dummy
    params.setProperty(ProxyServlet.P_LOG, "true");
    servletRunner.registerServlet("/proxyMe/*", ProxyServlet.class.getName(), params);//also matches /proxyMe (no path info)

  }

  @After
  public void tearDown() throws Exception {
   servletRunner.shutDown();
   localTestServer.stop();
  }

  @Test
  public void testDoGet() throws Exception {
    localTestServer.register("/targetPath*",new EchoHandler());//matches /targetPath and /targetPath/blahblah

    ServletUnitClient sc = servletRunner.newClient();

    assertEquals("", sc.getResponse( new GetMethodWebRequest( "http://localhost/proxyMe" ) ).getText());
    assertEquals("", sc.getResponse( new GetMethodWebRequest( "http://localhost/proxyMe/" ) ).getText());

    testEchoRequest(sc, new PostMethodWebRequest( "http://localhost/proxyMe" ));
    testEchoRequest(sc, new PostMethodWebRequest( "http://localhost/proxyMe/pathInfo" ));
    testEchoRequest(sc, new PostMethodWebRequest( "http://localhost/proxyMe?def=DEF" ));
    testEchoRequest(sc, new PostMethodWebRequest( "http://localhost/proxyMe/pathInfo?def=DEF" ));
  }

  private void testEchoRequest(ServletUnitClient sc, WebRequest request) throws IOException, SAXException {
    request.setParameter("abc","ABC");
    WebResponse response = sc.getResponse( request );
    assertTrue(response.getText().contains("ABC"));
  }
}
