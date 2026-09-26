package com.ets.unpack.repack;

import android.content.Context;

import com.ets100.secondary.app.EtsApp;

public class SafeApp extends EtsApp {
    static {
        CrashGuard.install();
        CrashGuard.breadcrumb("SafeApp.<clinit>");
    }

    public SafeApp() {
        super();
        CrashGuard.breadcrumb("SafeApp.<init>");
    }

    @Override
    protected void attachBaseContext(Context base) {
        CrashGuard.setContext(base);
        CrashGuard.install();
        CrashGuard.breadcrumb("SafeApp.attachBaseContext enter");
        try {
            super.attachBaseContext(base);
        } catch (Throwable t) {
            CrashGuard.log(t);
        }
        CrashGuard.breadcrumb("SafeApp.attachBaseContext exit");
    }

    @Override
    public void onCreate() {
        CrashGuard.install();
        CrashGuard.breadcrumb("SafeApp.onCreate enter");
        try {
            com.ets.unpack.hub.Hub.install(this);
        } catch (Throwable t) {
            CrashGuard.log(t);
        }
        try {
            super.onCreate();
        } catch (Throwable t) {
            CrashGuard.log(t);
        }
        CrashGuard.breadcrumb("SafeApp.onCreate exit");
    }
}
