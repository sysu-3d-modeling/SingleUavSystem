package com.example.modeling3d.singleuavsystem;

import android.app.Application;
import android.content.Context;
import android.support.multidex.MultiDex;

import com.secneo.sdk.Helper;

public class MApplication extends Application {

    private SingleApplication singleApplication;
    @Override
    protected void attachBaseContext(Context paramContext) {
        super.attachBaseContext(paramContext);
        Helper.install(MApplication.this);
        if (singleApplication == null) {
            singleApplication = new SingleApplication();
            singleApplication.setContext(this);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        //MultiDex.install(this);
        singleApplication.onCreate();
    }
}