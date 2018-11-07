package org.chimple.flores.application;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.chimple.flores.db.AppDatabase;
import org.chimple.flores.manager.BluetoothManager;

import java.util.UUID;

import static org.chimple.flores.application.P2PContext.LOG_TYPE;


public class P2PApplication extends Application {


    private static final String TAG = P2PApplication.class.getName();
    private static Context context;
    private P2PApplication that;
    public static AppDatabase db;
    public static BluetoothManager bluetoothManager;


    public void onCreate() {
        super.onCreate();
        initialize();
        context = this;
        that = this;
    }


    private void initialize() {
        Log.d(TAG, "Initializing...");

        Thread initializationThread = new Thread() {

            public void run() {
                P2PContext.getInstance().initialize(P2PApplication.this);
                db = AppDatabase.getInstance(P2PApplication.this);
                bluetoothManager = BluetoothManager.getInstance(P2PApplication.this);
                Log.i(TAG, "app database instance" + String.valueOf(db));

                initializationComplete();
            }
        };

        initializationThread.start();
    }

    private void initializationComplete() {
        Log.i(TAG, "Initialization complete...");
    }

    public static Context getContext() {
        return context;
    }

    public static String getLoggedInUser() {
        SharedPreferences pref = getContext().getSharedPreferences(P2PContext.SHARED_PREF, 0);
        String userId = pref.getString("USER_ID", null); // getting String
        return userId;
    }


    public static String getCurrentDevice() {
        SharedPreferences pref = getContext().getSharedPreferences(P2PContext.SHARED_PREF, 0);
        String deviceId = pref.getString("DEVICE_ID", null); // getting String
        return deviceId;
    }

    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }


}