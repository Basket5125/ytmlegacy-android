package com.ytmlegacy.android;

import android.app.Application;

import com.ytmlegacy.android.net.Tls;

/** Sets up TLS before anything talks to the network (activity, player or downloads). */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Tls.install(this);
    }
}
