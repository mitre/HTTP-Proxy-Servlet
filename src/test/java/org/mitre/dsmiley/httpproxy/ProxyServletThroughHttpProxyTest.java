package org.mitre.dsmiley.httpproxy;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.util.Properties;

public class ProxyServletThroughHttpProxyTest extends ProxyServletTest {

    private static HttpProxyServer proxyServer;

    @BeforeClass
    public static void setUpBeforeClass() {
        proxyServer = DefaultHttpProxyServer.bootstrap().start();
    }

    @AfterClass
    public static void tearDownAfterClass() {
        proxyServer.stop();
    }

    @Override
    protected void setUpServlet(Properties servletProps) {
        servletProps.setProperty(ProxyServlet.P_HTTP_PROXY_HOST_AND_PORT, "localhost:" + proxyServer.getListenAddress().getPort());
        super.setUpServlet(servletProps);
    }

}
