package org.mitre.dsmiley.httpproxy;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

import javax.net.ssl.SSLContext;
import javax.servlet.ServletContext;

import org.apache.http.client.HttpClient;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.params.HttpParams;
import org.apache.http.ssl.SSLContexts;
import org.mitre.dsmiley.httpproxy.ProxyServlet;

/**
 * This class provides a proxy which authenticates with a client SSL certificate
 * at the server.
 * 
 * Uses two servlet init parameters keystore and keystorepassword. The parameter
 * keystore has to point to a JKS keystore with the certificate. The parameter
 * keystorepassword has to include the password.
 */
public class ClientCertificateProxy extends ProxyServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected HttpClient createHttpClient(HttpParams hcParams) {
		try {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		
		ServletContext context = getServletContext();
	    InputStream stream = context.getResourceAsStream(getServletConfig().getInitParameter("keystore"));
		keyStore.load(stream,getServletConfig().getInitParameter("keystorepassword").toCharArray());

		SSLContext sslcontext = SSLContexts.custom().loadTrustMaterial(keyStore,new TrustSelfSignedStrategy()).build();
        SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
                sslcontext, new String[] { "TLSv1" }, null, SSLConnectionSocketFactory.getDefaultHostnameVerifier());
		
        HttpClient httpclient = HttpClients.custom().setSSLSocketFactory(sslsf).build();
                
        // TODO deprecated hcParams. I do not know how to deal with them here.
        // httpclient.setDefaultHttpParams(hcParams);
        
        return httpclient;
        
		} catch (KeyManagementException e) {
			this.log("KeyManagementException", e);
		} catch (NoSuchAlgorithmException e) {
			this.log("NoSuchAlgorithmException", e);
		} catch (KeyStoreException e) {
			this.log("KeyStoreException", e);
		} catch (CertificateException e) {
			this.log("CertificateException", e);
		} catch (IOException e) {
			this.log("IOException", e);
		} 		
		// back to default if an error occurs
		return super.createHttpClient(hcParams);
	}
}