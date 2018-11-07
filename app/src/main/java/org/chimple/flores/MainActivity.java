package org.chimple.flores;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chimple.flores.application.P2PApplication;
import org.chimple.flores.application.P2PContext;
import org.chimple.flores.db.DBSyncManager;
import org.chimple.flores.manager.BluetoothManager;

import java.util.ArrayList;
import java.util.List;

import static org.chimple.flores.application.P2PContext.CLEAR_CONSOLE_TYPE;
import static org.chimple.flores.application.P2PContext.LOG_TYPE;
import static org.chimple.flores.application.P2PContext.refreshDevice;

public class MainActivity extends AppCompatActivity {

    private Toolbar toolbar;
    private TextView consoleView;
    private TextView logView;
    private EditText messageToSendField;
    private BluetoothManager manager;
    private MainActivity that = this;
    private static List<BroadcastReceiver> receivers = new ArrayList<BroadcastReceiver>();


    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        this.toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setLogo(R.mipmap.ic_launcher);
    }


    protected void onStart() {
        super.onStart();
        this.manager = BluetoothManager.getInstance(this);
        this.consoleView = (TextView) findViewById(R.id.consoleTextView);
        this.logView = (TextView) findViewById(R.id.logTextView);
        this.messageToSendField = (EditText) findViewById(R.id.messageToSend);
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, new IntentFilter(P2PContext.uiMessageEvent));
        broadCastRefreshDevice();

        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                //Do something after 100ms
                P2PContext.getInstance().createShardProfilePreferences();
                String userId = P2PContext.getLoggedInUser();
                String deviceId = P2PContext.getCurrentDevice();
                DBSyncManager.getInstance(that).upsertUser(userId, deviceId, "Photo Message");
                that.manager.notifyUI("Added user -> " + userId, " --------> ", LOG_TYPE);
                that.manager.startBluetoothBased();
            }
        }, 2000);

    }

    private void broadCastRefreshDevice() {
        Intent intent = new Intent(refreshDevice);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

    }

    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);
    }


    protected void onDestroy() {
        super.onDestroy();
        manager.onCleanUp();

    }


    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    public boolean onOptionsItemSelected(MenuItem item) {
        return super.onOptionsItemSelected(item);
    }


    public void onButton(View view) {
        // Hide the keyboard
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);

        if (view.getId() == R.id.clearConsoleButton) {
            clearConsole();
        } else if (view.getId() == R.id.sendMessageButton) {
            sendMulticastMessage(getMessageToSend());
        }
    }

    private void clearConsole() {
        this.consoleView.setText("");
        this.consoleView.setTextColor(getResources().getColor(R.color.colorPrimaryDark));
    }

    public String getMessageToSend() {
        return this.messageToSendField.getText().toString();
    }

    private void sendMulticastMessage(String message) {
        manager.addNewMessage(message);
        final String consoleMessage = "[" + "You" + "]: " + message + "\n";
        this.outputTextToConsole(consoleMessage);
    }

    public void outputTextToLog(String message) {
        this.logView.append(message);
        ScrollView logScrollView = ((ScrollView) this.logView.getParent());
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }

    public void outputTextToConsole(String message) {
        this.consoleView.append(message);
        ScrollView scrollView = ((ScrollView) this.consoleView.getParent());
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));


    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            String message = intent.getStringExtra("message");
            String type = intent.getStringExtra("type");
            if (type.equals(P2PContext.CONSOLE_TYPE)) {
                that.outputTextToConsole(message);
            } else if (type.equals(P2PContext.LOG_TYPE)) {
                that.outputTextToLog(message);
            } else if (type.equals(CLEAR_CONSOLE_TYPE)) {
                that.clearConsole();
            }
        }
    };
}
