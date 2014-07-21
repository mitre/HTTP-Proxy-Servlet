package org.mitre.dsmiley.httpproxy;

import com.meterware.httpunit.WebRequest;
import org.junit.Ignore;
import org.junit.Test;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;

public class ParameterizedTest extends ProxyServletTest {

  String urlParams;

  //a hack to pass info from rewriteMakeMethodUrl to getExpectTargetUri
  String lastMakeMethodUrl;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    lastMakeMethodUrl = null;
  }

  @Override
  protected void setUpServlet(Properties servletProps) {
    //Register a parameterized proxy servlet.
    // for the test, host should be localhost, $2 should be localTestServer port, and path should be targetPath
    String hostParam = "localhost";
    String portParam = String.valueOf(localTestServer.getServiceAddress().getPort());
    String pathParam = "targetPath";
    urlParams = "_host=" + hostParam + "&_port=" + portParam + "&_path=" + pathParam;
    targetBaseUri = "http://" + hostParam + ":" + portParam + "/" + pathParam;
    servletProps.setProperty("targetUri", "http://{_host}:{_port}/{_path}");//template
    servletRunner.registerServlet("/proxyParameterized/*", URITemplateProxyServlet.class.getName(), servletProps);
    sourceBaseUri = "http://localhost/proxyParameterized";//localhost:0 is hard-coded in ServletUnitHttpRequest
  }

  @Override
  protected String rewriteMakeMethodUrl(String url) {
    lastMakeMethodUrl = url;
    //append parameters for the template
    url += (url.indexOf('?')<0 ? '?' : '&') + urlParams;
    return url;
  }

  @Override
  protected String getExpectedTargetUri(WebRequest request, String expectedUri) throws MalformedURLException, URISyntaxException {
    if (expectedUri == null) {
      expectedUri = lastMakeMethodUrl.substring(sourceBaseUri.length());
    } else {
      if (expectedUri.endsWith(urlParams))
        expectedUri = expectedUri.substring(0, expectedUri.length() - urlParams.length());
    }
    return new URI(this.targetBaseUri).getPath() + expectedUri;
  }

  @Override @Test
  @Ignore // because internally uses "new URI()" which is strict
  public void testProxyWithUnescapedChars() throws Exception {
  }

  @Override @Test
  @Ignore //because HttpUnit is faulty
  public void testSendFile() throws Exception {
  }
}
