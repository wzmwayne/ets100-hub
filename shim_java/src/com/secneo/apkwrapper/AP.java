package com.secneo.apkwrapper;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

import com.ets.unpack.repack.CrashGuard;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class AP extends AppComponentFactory {
    static {
        CrashGuard.install();
        CrashGuard.breadcrumb("AP.<clinit>");
    }

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo ai) {
        CrashGuard.install();
        try {
            ClassLoader r = super.instantiateClassLoader(cl, ai);
            CrashGuard.breadcrumb("instantiateClassLoader ok src=" + ai.sourceDir);
            return r;
        } catch (Throwable t) {
            CrashGuard.log(t);
            return cl;
        }
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        CrashGuard.install();
        CrashGuard.breadcrumb("instantiateApplication: " + className);
        try {
            Application app = super.instantiateApplication(cl, className);
            CrashGuard.breadcrumb("instantiateApplication ok: " + app.getClass().getName());
            return app;
        } catch (Throwable t) {
            CrashGuard.log(t);
            try {
                Application fallback = new Application();
                CrashGuard.breadcrumb("instantiateApplication fallback plain Application");
                return fallback;
            } catch (Throwable t2) {
                CrashGuard.log(t2);
                throw new InstantiationException(className + ": " + t);
            }
        }
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        CrashGuard.breadcrumb("instantiateActivity: " + className);
        try {
            return super.instantiateActivity(cl, className, intent);
        } catch (Throwable t) {
            CrashGuard.log(t);
            throw new InstantiationException(className + ": " + t);
        }
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        CrashGuard.breadcrumb("instantiateService: " + className);
        try {
            return super.instantiateService(cl, className, intent);
        } catch (Throwable t) {
            CrashGuard.log(t);
            throw new InstantiationException(className + ": " + t);
        }
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        CrashGuard.breadcrumb("instantiateReceiver: " + className);
        try {
            return super.instantiateReceiver(cl, className, intent);
        } catch (Throwable t) {
            CrashGuard.log(t);
            throw new InstantiationException(className + ": " + t);
        }
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        CrashGuard.breadcrumb("instantiateProvider: " + className);
        try {
            return super.instantiateProvider(cl, className);
        } catch (Throwable t) {
            CrashGuard.log(t);
            throw new InstantiationException(className + ": " + t);
        }
    }
}
