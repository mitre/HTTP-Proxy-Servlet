package org.mitre.dsmiley.httpproxy;

import com.meterware.servletunit.ServletRunner;
import org.junit.Test;

import java.util.Hashtable;

/**
 * @author David Smiley - dsmiley@mitre.org
 */
public class ProxyServletTest
{
//  @Before
//  public void setUp() throws Exception {
//
//  }
//
//  @After
//  public void tearDown() throws Exception {
//
//  }

  @Test
  public void testDoGet() throws Exception {
    ServletRunner sr = new ServletRunner();
    Hashtable<String,String> params = new Hashtable<String,String>();
    params.put(ProxyServlet.P_PROXY_HOST,"localhost");
    params.put(ProxyServlet.P_PROXY_PORT, "0");//dummy
    params.put(ProxyServlet.P_PROXY_PATH, "/targetPath");//dummy
    sr.registerServlet("proxyMe", ProxyServlet.class.getName(), params);
  }
}
