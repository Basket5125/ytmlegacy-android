package com.ytmlegacy.android.net;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.ytmlegacy.android.R;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Makes HTTPS work on old Android.
 *
 * <ul>
 * <li>Android &lt; 5 does not negotiate TLS 1.2 with Google by default: Conscrypt (BoringSSL) is
 * installed as the first security provider and used for every HttpsURLConnection.</li>
 * <li>Android &lt; 7.1 lacks some current roots (e.g. ISRG Root X1): a few roots are bundled in
 * {@code res/raw/extra_roots.pem} and trusted <b>in addition to</b> the system store.
 * Certificate and host name verification are never turned off.</li>
 * </ul>
 */
public final class Tls {

    private static final String TAG = "Tls";
    /**
     * Conscrypt 2.5 aborts natively (SIGABRT in pthread_once) on Android 2.3, which no Java code
     * can catch; it is only loaded from Android 4.0 on. Verified on emulators: 2.3 (abort), 4.4 (ok).
     */
    private static final int CONSCRYPT_MIN_SDK = 14;
    private static boolean installed;
    private static String provider = "system";

    private Tls() { }

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        // Reuse connections: a TLS handshake costs hundreds of ms on old phones.
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "8");
        if (Build.VERSION.SDK_INT >= 25) return; // modern platform: defaults are fine
        try {
            X509TrustManager system = systemTrustManager();
            X509TrustManager extra = bundledTrustManager(context);
            TrustManager[] managers = {new CompositeTrustManager(system, extra)};
            SSLContext ssl = null;
            if (Build.VERSION.SDK_INT < 21 && Build.VERSION.SDK_INT >= CONSCRYPT_MIN_SDK) {
                try {
                    Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1);
                    ssl = SSLContext.getInstance("TLSv1.2", "Conscrypt");
                    provider = "Conscrypt";
                } catch (Throwable e) {
                    // No native library for this CPU (e.g. ARMv6): keep the platform TLS.
                    Log.w(TAG, "Conscrypt unavailable: " + e);
                }
            }
            if (ssl == null) ssl = SSLContext.getInstance("TLS");
            ssl.init(null, managers, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.getSocketFactory());
            Log.i(TAG, "TLS via " + provider);
        } catch (Throwable e) {
            Log.w(TAG, "TLS setup failed, using platform defaults", e);
        }
    }

    public static String provider() {
        return provider;
    }

    private static X509TrustManager systemTrustManager() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return firstX509(factory.getTrustManagers());
    }

    private static X509TrustManager bundledTrustManager(Context context) {
        InputStream in = null;
        try {
            in = context.getResources().openRawResource(R.raw.extra_roots);
            CertificateFactory certificates = CertificateFactory.getInstance("X.509");
            Collection<? extends java.security.cert.Certificate> roots =
                    certificates.generateCertificates(in);
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (java.security.cert.Certificate root : roots) {
                store.setCertificateEntry("extra" + (i++), root);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            return firstX509(factory.getTrustManagers());
        } catch (Throwable e) {
            Log.w(TAG, "bundled roots unavailable: " + e);
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    private static X509TrustManager firstX509(TrustManager[] managers) {
        if (managers != null) {
            for (TrustManager manager : managers) {
                if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
            }
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    /** Trusts a chain when the system store or the bundled roots do. */
    private static final class CompositeTrustManager implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager extra;

        CompositeTrustManager(X509TrustManager system, X509TrustManager extra) {
            this.system = system;
            this.extra = extra;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                if (extra == null) throw e;
                extra.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<X509Certificate>();
            for (X509Certificate c : system.getAcceptedIssuers()) all.add(c);
            if (extra != null) for (X509Certificate c : extra.getAcceptedIssuers()) all.add(c);
            return all.toArray(new X509Certificate[all.size()]);
        }
    }
}
