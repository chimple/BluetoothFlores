package org.chimple.flores.manager;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.collections4.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.chimple.flores.application.P2PContext;
import org.chimple.flores.db.DBSyncManager;
import org.chimple.flores.db.P2PDBApiImpl;
import org.chimple.flores.db.entity.HandShakingInfo;
import org.chimple.flores.db.entity.HandShakingMessage;
import org.chimple.flores.db.entity.P2PSyncInfo;
import org.chimple.flores.db.entity.SyncInfoItem;
import org.chimple.flores.db.entity.SyncInfoMessage;
import org.chimple.flores.db.entity.SyncInfoRequestMessage;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.chimple.flores.application.P2PContext.CLEAR_CONSOLE_TYPE;
import static org.chimple.flores.application.P2PContext.CONSOLE_TYPE;
import static org.chimple.flores.application.P2PContext.LOG_TYPE;
import static org.chimple.flores.application.P2PContext.NEW_MESSAGE_ADDED;
import static org.chimple.flores.application.P2PContext.messageEvent;
import static org.chimple.flores.application.P2PContext.multiCastConnectionChangedEvent;
import static org.chimple.flores.application.P2PContext.newMessageAddedOnDevice;
import static org.chimple.flores.application.P2PContext.refreshDevice;
import static org.chimple.flores.application.P2PContext.uiMessageEvent;
import static org.chimple.flores.db.AppDatabase.SYNC_NUMBER_OF_LAST_MESSAGES;

public class BluetoothManager implements BtListenCallback, BtCallback, BluetoothStatusChanged {
    private static final String TAG = BluetoothManager.class.getSimpleName();
    private Context context;
    private static BluetoothManager instance;
    private P2PDBApiImpl p2PDBApiImpl;
    private DBSyncManager dbSyncManager;
    private Map<String, HandShakingMessage> handShakingMessagesInCurrentLoop = new ConcurrentHashMap<>();
    private Set<String> allSyncInfosReceived = new HashSet<String>();
    private BluetoothAdapter mAdapter;
    private final AtomicBoolean isDiscoverying = new AtomicBoolean(false);
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicInteger mState = new AtomicInteger(STATE_NONE);
    private final AtomicInteger mNewState = new AtomicInteger(STATE_NONE);
    private BtBrowdCastReceiver receiver = null;
    List<String> peerDevices = Collections.synchronizedList(new ArrayList<String>());
    List<String> supportedDevices = Collections.synchronizedList(new ArrayList<String>());
    private Handler mHandler = null;


    private final AtomicBoolean isSyncStarted = new AtomicBoolean(false);


    public static final long START_ALL_BLUETOOTH_ACTIVITY = 5 * 1000;
    public static final long STOP_ALL_BLUETOOTH_ACTIVITY = 5 * 1000;
    public static final long LONG_TIME_ALARM = 3 * 60 * 1000; // 30 sec
    public static final long IMMEDIATE_TIME_ALARM = 30 * 1000;
    private static final int START_HANDSHAKE_TIMER = 15 * 1000; // 15 sec
    private static final int STOP_DISCOVERY_TIMER = 15 * 1000; // 15 sec
    //    private CountDownTimer startBluetoothDiscoveryTimer;
    private CountDownTimer handShakeFailedTimer;
    private CountDownTimer nextRoundTimer;
    private CountDownTimer startAllBlueToothActivityTimer;
    private CountDownTimer stopAllBlueToothActivityTimer;

    private CountDownTimer repeatSyncActivityTimer;
    private CountDownTimer immediateSyncActivityTimer;

    private static final int POLLING_TIMER = 1 * 1000; // 1 sec

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device

    public static final String NAME_INSECURE = "BluetoothChatInsecure";
    // Unique UUID for this application
    public static final UUID MY_UUID_INSECURE =
            UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");


    public static final int MESSAGE_READ = 0x11;
    public static final int MESSAGE_WRITE = 0x22;
    public static final int SOCKET_DISCONNEDTED = 0x33;


    private AcceptThread mInsecureAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int pollingIndex = 0;
    private String connectedAddress = "";

    public static BluetoothManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BluetoothManager.class) {
                instance = new BluetoothManager(context);
                instance.mHandler = new Handler(context.getMainLooper());
                instance.dbSyncManager = DBSyncManager.getInstance(context);
                instance.p2PDBApiImpl = P2PDBApiImpl.getInstance(context);
                instance.mAdapter = BluetoothAdapter.getDefaultAdapter();
                instance.mState.set(STATE_NONE);
                instance.mNewState.set(instance.mState.get());
                instance.registerReceivers();

                instance.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        instance.handShakeFailedTimer = new CountDownTimer(START_HANDSHAKE_TIMER, 5000) {
                            @Override
                            public void onTick(long millisUntilFinished) {
                                instance.notifyUI("startHandShakeTimer ticking", " ------>", LOG_TYPE);
                            }

                            @Override
                            public void onFinish() {
                                instance.notifyUI("startHandShakeTimer TimeOut", " ------>", LOG_TYPE);
                                instance.HandShakeFailed("TimeOut", false);
                            }
                        };
                    }
                });

                instance.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        instance.nextRoundTimer = new CountDownTimer(POLLING_TIMER, 500) {
                            public void onTick(long millisUntilFinished) {
                                // not using
                            }

                            public void onFinish() {
                                instance.DoNextPollingRound();
                            }
                        };
                    }
                });

//                instance.mHandler.post(new Runnable() {
//                    @Override
//                    public void run() {
//                        instance.startBluetoothDiscoveryTimer = new CountDownTimer(STOP_DISCOVERY_TIMER, 1000) {
//                            @Override
//                            public void onTick(long millisUntilFinished) {
//                                if (!instance.isDiscoverying.get()) {
//                                    instance.doDiscovery();
//                                }
//                            }
//
//                            @Override
//                            public void onFinish() {
//                                instance.notifyUI("STOP_DISCOVERY_TIMER ...finished ", " ----->", LOG_TYPE);
//                                instance.startNextPolling();
//                                instance.stopDiscovery();
//                            }
//                        };
//                    }
//                });

                instance.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        instance.startAllBlueToothActivityTimer = new CountDownTimer(START_ALL_BLUETOOTH_ACTIVITY, 1000) {
                            @Override
                            public void onTick(long millisUntilFinished) {

                            }

                            @Override
                            public void onFinish() {
                                if (instance.repeatSyncActivityTimer != null) {
                                    instance.repeatSyncActivityTimer.cancel();
                                    instance.repeatSyncActivityTimer.start();
                                }

                                instance.startSync();
                            }
                        };
                    }
                });

                instance.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        instance.stopAllBlueToothActivityTimer = new CountDownTimer(STOP_ALL_BLUETOOTH_ACTIVITY, 1000) {
                            @Override
                            public void onTick(long millisUntilFinished) {

                            }

                            @Override
                            public void onFinish() {
                                instance.Stop();
                            }
                        };
                    }
                });

                instance.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        instance.immediateSyncActivityTimer = new CountDownTimer(IMMEDIATE_TIME_ALARM, 1000) {
                            @Override
                            public void onTick(long millisUntilFinished) {

                            }

                            @Override
                            public void onFinish() {
                                instance.startSync();
                            }
                        };
                    }
                });

                instance.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        instance.repeatSyncActivityTimer = new CountDownTimer(LONG_TIME_ALARM, 1000) {
                            @Override
                            public void onTick(long millisUntilFinished) {

                            }

                            @Override
                            public void onFinish() {
                                instance.startSync();
                            }
                        };
                    }
                });

//                instance.peerDevices.add("2C:FD:AB:88:7F:41");
//                instance.peerDevices.add("48:88:CA:4C:6E:D3");
//                instance.peerDevices.add("C0:EE:FB:53:9F:C2");
            }
        }
        return instance;
    }

    public void startSync() {
        if (!instance.isSyncStarted.get()) {
            if (instance.immediateSyncActivityTimer != null) {
                instance.immediateSyncActivityTimer.cancel();
            }
            instance.isSyncStarted.set(true);
            if (instance.peerDevices != null) {
                instance.peerDevices.clear();
            }
            instance.Start(0);
        }

    }


    public boolean isBluetoothEnabled() {
        return instance.mAdapter.isEnabled();

    }

    public void startBluetoothBased() {
        if (!isConnected.get() && BluetoothManager.getInstance(context).isBluetoothEnabled()) {
            if (instance.stopAllBlueToothActivityTimer != null) {
                instance.stopAllBlueToothActivityTimer.cancel();
            }
            if (instance.startAllBlueToothActivityTimer != null) {
                instance.startAllBlueToothActivityTimer.start();
            }
        }
    }

    private class BtBrowdCastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                Log.d(TAG, "started ACTION_DISCOVERY_STARTED");
                notifyUI("startDiscoveryTimer ...ACTION_DISCOVERY_STARTED", "---------->", LOG_TYPE);
            } else if (BluetoothAdapter.ACTION_SCAN_MODE_CHANGED.equals(action)) {
                int mode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.ERROR);
                if (instance != null) {
                    instance.BluetoothStateChanged(mode);
                }
            } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Get the BluetoothDevice object from the Intent
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // If it's already paired, skip it, because it's been listed already
                Log.d(TAG, "Adding device to list: " + device.getName() + "\n" + device.getAddress());
                notifyUI("Adding device to list: " + device.getName() + "\n" + device.getAddress(), " ----> ", LOG_TYPE);
                peerDevices.add(device.getAddress());
                // When discovery is finished, change the Activity title
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.d(TAG, "started ACTION_DISCOVERY_FINISHED");
                notifyUI("startDiscoveryTimer ...ACTION_DISCOVERY_FINISHED: found peers:" + instance.peerDevices.size(), "---------->", LOG_TYPE);
                if (instance.peerDevices.size() == 0) {
                    instance.peerDevices.add("48:88:CA:4C:6E:D3");
                    instance.peerDevices.add("C0:EE:FB:53:9F:C2");
                }
                instance.notifyUI("startDiscoveryTimer ...ACTION_DISCOVERY_FINISHED: added static peers:" + instance.peerDevices.size(), "---------->", LOG_TYPE);
                instance.stopDiscovery();
                instance.startNextPolling();
            }
        }
    }


    public void Start(int index) {
        synchronized (BluetoothManager.class) {
            instance.supportedDevices = p2PDBApiImpl.fetchAllSyncedDevices();
            instance.supportedDevices.add("48:88:CA:4C:6E:D3");
            instance.supportedDevices.add("C0:EE:FB:53:9F:C2");

            instance.startListener();
            pollingIndex = index;
            if (instance.peerDevices != null && instance.peerDevices.size() == 0) {
                notifyUI("startDiscoveryTimer ...", "---------->", LOG_TYPE);
                instance.startDiscoveryTimer();
            }

            notifyUI("Start All ... called on index:" + index, "---------->", LOG_TYPE);
        }
    }

    public void onCleanUp() {
        this.Stop();
        this.context.unregisterReceiver(receiver);

        if (mMessageEventReceiver != null) {
            LocalBroadcastManager.getInstance(this.context).unregisterReceiver(mMessageEventReceiver);
            mMessageEventReceiver = null;
        }

        if (newMessageAddedReceiver != null) {
            LocalBroadcastManager.getInstance(this.context).unregisterReceiver(newMessageAddedReceiver);
            newMessageAddedReceiver = null;
        }

        if (refreshDeviceReceiver != null) {
            LocalBroadcastManager.getInstance(this.context).unregisterReceiver(refreshDeviceReceiver);
            refreshDeviceReceiver = null;
        }

        if (netWorkChangerReceiver != null) {
            LocalBroadcastManager.getInstance(this.context).unregisterReceiver(netWorkChangerReceiver);
            netWorkChangerReceiver = null;
        }

        instance.receiver = null;
    }

    public void Stop() {
        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread.Stop();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.Stop();
            mConnectedThread = null;
        }

        // Cancel the accept thread because we only want to connect to one device
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.Stop();
            mInsecureAcceptThread = null;
        }


        instance.stopDiscovery();

        if (instance.handShakeFailedTimer != null) {
            instance.handShakeFailedTimer.cancel();
        }

//        if (instance.startBluetoothDiscoveryTimer != null) {
//            instance.startBluetoothDiscoveryTimer.cancel();
//        }


        if (instance.nextRoundTimer != null) {
            instance.nextRoundTimer.cancel();
        }

        if (startAllBlueToothActivityTimer != null) {
            startAllBlueToothActivityTimer.cancel();
        }

        if (stopAllBlueToothActivityTimer != null) {
            stopAllBlueToothActivityTimer.cancel();
        }

        if (instance.repeatSyncActivityTimer != null) {
            instance.repeatSyncActivityTimer.cancel();
        }

        if (instance.immediateSyncActivityTimer != null) {
            instance.immediateSyncActivityTimer.cancel();
        }

        instance.notifyUI("Stop All....", " ----->", LOG_TYPE);
    }

    public void stopDiscovery() {
//        if (startBluetoothDiscoveryTimer != null) {
//            startBluetoothDiscoveryTimer.cancel();
//        }

        instance.isDiscoverying.set(false);
        if (instance != null && instance.mAdapter.isDiscovering()) {
            instance.mAdapter.cancelDiscovery();
        }
    }


    private void registerReceivers() {
        if (instance.receiver == null) {
            receiver = new BtBrowdCastReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
            this.context.registerReceiver(receiver, filter);

            filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            this.context.registerReceiver(receiver, filter);


            filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
            this.context.registerReceiver(receiver, filter);

            filter.addAction(BluetoothDevice.ACTION_FOUND);
            this.context.registerReceiver(receiver, filter);
        }

        LocalBroadcastManager.getInstance(this.context).registerReceiver(netWorkChangerReceiver, new IntentFilter(multiCastConnectionChangedEvent));
        LocalBroadcastManager.getInstance(this.context).registerReceiver(mMessageEventReceiver, new IntentFilter(messageEvent));
        LocalBroadcastManager.getInstance(this.context).registerReceiver(newMessageAddedReceiver, new IntentFilter(newMessageAddedOnDevice));
        LocalBroadcastManager.getInstance(this.context).registerReceiver(refreshDeviceReceiver, new IntentFilter(refreshDevice));

    }

    private void startNextPolling() {

        if (instance.nextRoundTimer != null) {
            instance.notifyUI("startNextPolling ...", " ------>", LOG_TYPE);
            instance.nextRoundTimer.cancel();
            instance.nextRoundTimer.start();
        }
    }


    private void DoNextPollingRound() {
        String nextDevice = getNextToSync();
        if (nextDevice != null) {
            instance.notifyUI("Start Polling:" + nextDevice, " ----->", LOG_TYPE);
            Log.d(TAG, "Polling device : " + nextDevice);
            instance.connectedAddress = nextDevice;
            BluetoothDevice device = instance.mAdapter.getRemoteDevice(nextDevice.trim());
            instance.connect(device);
            if (instance.nextRoundTimer != null) {
                instance.nextRoundTimer.cancel();
            }
        } else {
            instance.isSyncStarted.set(false);
            Log.d(TAG, "DoNextPollingRound -> isSyncStarted false");
            instance.notifyUI("Stop polling", " ----->", LOG_TYPE);
            instance.Stop();
            if (instance.repeatSyncActivityTimer != null) {
                instance.repeatSyncActivityTimer.start();
            }
        }
    }

    public synchronized void connect(BluetoothDevice device) {
        Log.d(TAG, "start connect to: " + device);
        notifyUI("start connect to: " + device, " ----->", LOG_TYPE);

        // Cancel any thread attempting to make a connection
        if (mState.get() == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.Stop();
                mConnectThread = null;
            }
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.Stop();
            mConnectedThread = null;
        }

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device, context, instance);
        mConnectThread.start();
    }

    private synchronized String getNextToSync() {

        String ret = null;
        if (peerDevices != null && peerDevices.size() > 0) {
            String myAddress = getBluetoothMacAddress();
            notifyUI("My address:" + myAddress, " ------>", CONSOLE_TYPE);
            if (myAddress != null && peerDevices.contains(myAddress)) {
                peerDevices.remove(myAddress);
                supportedDevices.remove(myAddress);
            }

            List<String> blueToothDevices = (List<String>) CollectionUtils.intersection(peerDevices, supportedDevices);
            if (blueToothDevices.size() > 0) {
                pollingIndex = pollingIndex + 1;
                if (pollingIndex >= blueToothDevices.size()) {
                    pollingIndex = 0;
                }

                if (ret == null && pollingIndex >= 0 && pollingIndex < blueToothDevices.size()) {
                    ret = blueToothDevices.get(pollingIndex);
                    Log.d(TAG, "polling index: " + pollingIndex + ", ret: " + ret + ", size: " + blueToothDevices.size());
                    notifyUI("polling index: " + pollingIndex + ", ret: " + ret + ", size: " + blueToothDevices.size(), " ------>", CONSOLE_TYPE);
                }
            }
        }


        return ret;
    }

    private BluetoothManager(Context context) {
        this.context = context;
    }

    public int getmState() {
        return mState.get();
    }

    public void setmState(int mState) {
        this.mState.set(mState);
    }

    public int getmNewState() {
        return mNewState.get();
    }

    public void setmNewState(int mNewState) {
        this.mNewState.set(mNewState);
    }

    public AcceptThread getmInsecureAcceptThread() {
        return mInsecureAcceptThread;

    }

    public void setmInsecureAcceptThread(AcceptThread mInsecureAcceptThread) {
        this.mInsecureAcceptThread = mInsecureAcceptThread;
    }

    public ConnectThread getmConnectThread() {
        return mConnectThread;
    }

    public void setmConnectThread(ConnectThread mConnectThread) {
        this.mConnectThread = mConnectThread;
    }

    public ConnectedThread getmConnectedThread() {
        return mConnectedThread;
    }

    public void setmConnectedThread(ConnectedThread mConnectedThread) {
        this.mConnectedThread = mConnectedThread;
    }

    public P2PDBApiImpl getP2PDBApiImpl() {
        return p2PDBApiImpl;
    }

    public DBSyncManager getDbSyncManager() {
        return dbSyncManager;
    }

    public BluetoothAdapter getmAdapter() {
        return mAdapter;
    }

    private void reset() {
        if (mConnectThread != null) {
            mConnectThread.Stop();
            mConnectThread = null;
            notifyUI("mConnectThread stopped", " ------>", LOG_TYPE);
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.Stop();
            mConnectedThread = null;
            notifyUI("mConnectedThread stopped", " ------>", LOG_TYPE);
        }

        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.Stop();
            mInsecureAcceptThread = null;
            notifyUI("mInsecureAcceptThread stopped", " ------>", LOG_TYPE);
        }
    }

    @Override
    public void Connected(BluetoothSocket socket, BluetoothDevice device, String socketType) {
        synchronized (BluetoothManager.class) {
            Log.d(TAG, "Connected, Socket Type:" + socketType);
            notifyUI("Connected, Socket Type:" + socketType, " ----->", LOG_TYPE);

            instance.startHandShakeTimer();
            instance.reset();

            // Start the thread to manage the connection and perform transmissions
            mConnectedThread = new ConnectedThread(socket, socketType, instance, instance.context);
            mConnectedThread.start();
        }
    }

    @Override
    public void GotConnection(BluetoothSocket socket, BluetoothDevice device, String socketType) {
        synchronized (BluetoothManager.class) {
            Log.d(TAG, "GotConnection connected, Socket Type:" + socketType);
            notifyUI("GotConnection connected, Socket Type:" + socketType, " ----->", LOG_TYPE);

            instance.startHandShakeTimer();
            instance.reset();

            // Start the thread to manage the connection and perform transmissions
            mConnectedThread = new ConnectedThread(socket, socketType, instance, instance.context);
            mConnectedThread.start();
        }
    }

    @Override
    public void PollSocketFailed(String reason) {
        synchronized (BluetoothManager.class) {
            final String tmp = reason;
            Log.d(TAG, "conn PollSocketFailed: " + tmp);
            instance.HandShakeFailed("conn PollSocketFailed: " + tmp, false);
        }
    }

    @Override
    public void CreateSocketFailed(String reason) {
        synchronized (BluetoothManager.class) {
            final String tmp = reason;
            Log.d(TAG, "CreateSocketFailed Error: " + tmp);
            instance.HandShakeFailed("CreateSocketFailed Error: " + tmp, false);
        }
    }

    @Override
    public void ConnectionFailed(String reason) {
        // Start the service over to restart listening mode
        notifyUI("conn ConnectionFailed: " + reason, " ---->", LOG_TYPE);
        instance.HandShakeFailed("ConnectionFailed --> reason: " + reason, true);
    }

    @Override
    public void ListeningFailed(String reason) {
        synchronized (BluetoothManager.class) {
            final String tmp = reason;
            Log.d(TAG, "LISTEN Error: " + tmp);
            instance.HandShakeFailed("LISTEN Error: " + tmp, false);
        }
    }

    private void startDiscoveryTimer() {
        synchronized (BluetoothManager.class) {
            if (!instance.isDiscoverying.get()) {
                instance.doDiscovery();
            }

//            instance.startBluetoothDiscoveryTimer.start();
        }
    }


    private void startListener() {
        synchronized (BluetoothManager.class) {
            instance.reset();
            if (mInsecureAcceptThread == null && this.mAdapter != null) {
                mInsecureAcceptThread = new AcceptThread(this.context, instance);
                mInsecureAcceptThread.start();
                notifyUI("mInsecureAcceptThread start", " ------>", LOG_TYPE);
            }
        }
    }


    @Override
    public void BluetoothStateChanged(int state) {
        if (state == BluetoothAdapter.SCAN_MODE_NONE) {
            instance.stopAllBlueToothActivityTimer.start();
            Log.d(TAG, "Bluetooth DISABLED, stopping");
            instance.Stop();
            if (instance.immediateSyncActivityTimer != null) {
                instance.immediateSyncActivityTimer.cancel();
            }

            if (instance.repeatSyncActivityTimer != null) {
                instance.repeatSyncActivityTimer.cancel();
            }
        } else if (state == BluetoothAdapter.SCAN_MODE_CONNECTABLE
                || state == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Log.d(TAG, "Bluetooth enabled, re-starting");
            instance.startBluetoothBased();
        }
    }

    public Context getContext() {
        return context;
    }

    /**
     * Start device discover with the BluetoothAdapter
     */
    private void doDiscovery() {
        if (!isDiscoverying.get()) {
            Log.d(TAG, "doDiscovery()");
            isDiscoverying.set(true);
            // Indicate scanning in the title
            // If we're already discovering, stop it
            if (instance.mAdapter.isDiscovering()) {
                instance.mAdapter.cancelDiscovery();
            }

            // Request discover from BluetoothAdapter
            instance.mAdapter.startDiscovery();
        }
    }

    public String getBluetoothMacAddress() {
        BluetoothAdapter bluetoothAdapter = instance.getmAdapter();
        if (bluetoothAdapter != null) {
            String bluetoothMacAddress = "";
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                try {
                    Field mServiceField = bluetoothAdapter.getClass().getDeclaredField("mService");
                    mServiceField.setAccessible(true);

                    Object btManagerService = mServiceField.get(bluetoothAdapter);

                    if (btManagerService != null) {
                        bluetoothMacAddress = (String) btManagerService.getClass().getMethod("getAddress").invoke(btManagerService);
                    }
                } catch (NoSuchFieldException e) {

                } catch (NoSuchMethodException e) {

                } catch (IllegalAccessException e) {

                } catch (InvocationTargetException e) {

                }
            } else {
                bluetoothMacAddress = bluetoothAdapter.getAddress();
            }
            return bluetoothMacAddress;
        } else {
            return null;
        }
    }

    public void notifyUI(String message, String fromIP, String type) {

        final String consoleMessage = "[" + fromIP + "]: " + message + "\n";
        Log.d(TAG, "got message: " + consoleMessage);
        Intent intent = new Intent(uiMessageEvent);
        intent.putExtra("message", consoleMessage);
        intent.putExtra("type", type);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    public Set<String> getAllSyncInfosReceived() {
        return allSyncInfosReceived;
    }

    public void addNewMessage(String message) {
        dbSyncManager.addMessage(P2PContext.getLoggedInUser(), null, "Chat", message);
    }

    private BroadcastReceiver newMessageAddedReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            P2PSyncInfo info = (P2PSyncInfo) intent.getSerializableExtra(NEW_MESSAGE_ADDED);
            if (info != null) {
                if (instance.immediateSyncActivityTimer != null) {
                    instance.immediateSyncActivityTimer.cancel();
                    instance.immediateSyncActivityTimer.start();
                }
                //String syncMessage = p2PDBApiImpl.convertSingleP2PSyncInfoToJsonUsingStreaming(info, false);
                //instance.sendMulticastMessage(syncMessage);
            }
        }
    };

    public void sendMulticastMessage(String message) {
        try {
            ConnectedThread r;
            // Synchronize a copy of the ConnectedThread
            synchronized (this) {
                if (mState.get() != STATE_CONNECTED)
                    return;
                r = mConnectedThread;
            }
            message = "START" + message + "END";
            notifyUI("sending message:" + message, " ------> ", LOG_TYPE);
            // Perform the write unsynchronized
            r.write(message.getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private BroadcastReceiver netWorkChangerReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            synchronized (BluetoothManager.class) {
                boolean isConnected = intent.getBooleanExtra("isConnected", false);
                instance.isConnected.set(isConnected);
                if (!isConnected && BluetoothManager.getInstance(context).isBluetoothEnabled()) {
                    if (stopAllBlueToothActivityTimer != null) {
                        stopAllBlueToothActivityTimer.cancel();
                    }
                    instance.startAllBlueToothActivityTimer.start();

                } else {
                    Log.d(TAG, "network is connected or bluetooth is not enabled ...stopping all bluetooth activity");
                    if (startAllBlueToothActivityTimer != null) {
                        startAllBlueToothActivityTimer.cancel();
                    }
                    instance.stopAllBlueToothActivityTimer.start();
                }
            }
        }
    };

    private BroadcastReceiver mMessageEventReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            String message = intent.getStringExtra("message");
            String fromIP = intent.getStringExtra("fromIP");
            processInComingMessage(message, fromIP);
        }
    };


    public void processInComingMessage(String message, String fromIP) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                if (instance.isHandShakingMessage(message)) {
                    instance.processInComingHandShakingMessage(message);
                } else if (instance.isSyncRequestMessage(message)) {
                    List<String> syncInfoMessages = instance.processInComingSyncRequestMessage(message);
                    instance.sendMessages(syncInfoMessages);
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            instance.sendMulticastMessage("BLUETOOTH-SYNC-COMPLETED");
                        }
                    }, 1000);

                } else if (instance.isSyncInfoMessage(message)) {
                    instance.processInComingSyncInfoMessage(message);
                } else if (instance.isBluetoothSyncCompleteMessage(message)) {
                    instance.notifyUI("BLUETOOTH SYNC COMPLETED ....", " ------> ", LOG_TYPE);
                    notifyUI("peerDevices has  ... " + instance.peerDevices.size(), "---------->", LOG_TYPE);
                    if (peerDevices != null && peerDevices.contains(instance.connectedAddress)) {
                        peerDevices.remove(instance.connectedAddress);
                        notifyUI("peerDevices removed ... " + instance.connectedAddress, "---------->", LOG_TYPE);
                        instance.connectedAddress = "";
                    }

                    instance.startNextDeviceToSync();
                }
            }
        });

    }

    private MessageStatus validIncomingSyncMessage(P2PSyncInfo info, MessageStatus status) {
        // DON'T reject out of order message, send handshaking request for only missing data
        // reject duplicate messages if any
        boolean isValid = true;
        String iKey = info.getDeviceId() + "_" + info.getUserId() + "_" + Long.valueOf(info.getSequence().longValue());
        String iPreviousKey = info.getDeviceId() + "_" + info.getUserId() + "_" + Long.valueOf(info.getSequence().longValue() - 1);
        Log.d(TAG, "validIncomingSyncMessage previousKey" + iPreviousKey);
        // remove duplicates
        if (allSyncInfosReceived.contains(iKey)) {
            Log.d(TAG, "sync data message as key already found" + iKey);
            status.setDuplicateMessage(true);
            status.setOutOfSyncMessage(false);
            isValid = false;
        } else if ((info.getSequence().longValue() - 1) != 0
                && !allSyncInfosReceived.contains(iPreviousKey)) {
            Log.d(TAG, "found sync data message as out of sequence => previous key not found " + iPreviousKey + " for key:" + iKey);
            isValid = false;
            status.setDuplicateMessage(false);
            status.setOutOfSyncMessage(true);
        }

        if (isValid) {
            Log.d(TAG, "validIncomingSyncMessage adding to allSyncInfosReceived for key:" + iKey);
            allSyncInfosReceived.add(iKey);
        }

        return status;
    }

    public void processInComingSyncInfoMessage(String message) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "processInComingSyncInfoMessage -> " + message);
                Iterator<P2PSyncInfo> infos = p2PDBApiImpl.deSerializeP2PSyncInfoFromJson(message).iterator();
                while (infos != null && infos.hasNext()) {
                    P2PSyncInfo info = infos.next();
                    MessageStatus status = new MessageStatus(false, false);
                    status = instance.validIncomingSyncMessage(info, status);
                    if (status.isDuplicateMessage()) {
                        notifyUI(info.message + " ---------> duplicate - rejected ", info.getSender(), LOG_TYPE);
                        infos.remove();
                    } else if (status.isOutOfSyncMessage()) {
                        notifyUI(info.message + " with sequence " + info.getSequence() + " ---------> out of sync processed with filling Missing type message ", info.getSender(), LOG_TYPE);
                        String key = info.getDeviceId() + "_" + info.getUserId() + "_" + Long.valueOf(info.getSequence().longValue());
                        Log.d(TAG, "processing out of sync data message for key:" + key + " and sequence:" + info.sequence);
                        String rMessage = p2PDBApiImpl.persistOutOfSyncP2PSyncMessage(info);
                        // generate handshaking request
                        if (status.isOutOfSyncMessage()) {
                            Log.d(TAG, "validIncomingSyncMessage -> out of order -> sendInitialHandShakingMessage");
                            sendInitialHandShakingMessage(true);
                        }
                    } else if (!status.isOutOfSyncMessage() && !status.isDuplicateMessage()) {
                        String key = info.getDeviceId() + "_" + info.getUserId() + "_" + Long.valueOf(info.getSequence().longValue());
                        Log.d(TAG, "processing sync data message for key:" + key + " and message:" + info.message);
                        String rMessage = p2PDBApiImpl.persistP2PSyncInfo(info);
                    } else {
                        infos.remove();
                    }
                }
            }
        });

    }

    public List<String> processInComingSyncRequestMessage(String message) {
        Log.d(TAG, "processInComingSyncRequestMessage => " + message);
        List<String> jsonRequests = new CopyOnWriteArrayList<String>();
        SyncInfoRequestMessage request = p2PDBApiImpl.buildSyncRequstMessage(message);
        // process only if matching current device id
        if (request != null && request.getmDeviceId().equalsIgnoreCase(P2PContext.getCurrentDevice())) {
            Log.d(TAG, "processInComingSyncRequestMessage => device id matches with: " + P2PContext.getCurrentDevice());
            notifyUI("sync request message received", " ------> ", LOG_TYPE);
            List<SyncInfoItem> items = request.getItems();
            for (SyncInfoItem a : items) {
                Log.d(TAG, "processInComingSyncRequestMessage => adding to jsonRequest for sync messages");
                jsonRequests.addAll(p2PDBApiImpl.fetchP2PSyncInfoBySyncRequest(a));
            }
        }

        return jsonRequests;
    }

    public void processInComingHandShakingMessage(String message) {

        Log.d(TAG, "processInComingHandShakingMessage: " + message);
        notifyUI("handshaking message received", " ------> ", LOG_TYPE);
        //parse message and add to all messages
        HandShakingMessage handShakingMessage = instance.parseHandShakingMessage(message);
        boolean shouldSendAck = shouldSendAckForHandShakingMessage(handShakingMessage);

        // send handshaking information if message received "from" first time
        if (shouldSendAck) {
            Log.d(TAG, "replying back with initial hand shaking message with needAck => false");
            notifyUI("handshaking message sent with ack false", " ------> ", LOG_TYPE);
            sendInitialHandShakingMessage(false);
        }

        synchronized (BluetoothManager.class) {
            instance.generateSyncInfoPullRequest(instance.getAllHandShakeMessagesInCurrentLoop());
        }
    }

    public Map<String, HandShakingMessage> getAllHandShakeMessagesInCurrentLoop() {
        synchronized (BluetoothManager.class) {
            Map<String, HandShakingMessage> messagesTillNow = Collections.unmodifiableMap(handShakingMessagesInCurrentLoop);
            CollectionUtils.subtract(handShakingMessagesInCurrentLoop.keySet(), messagesTillNow.keySet());
            return messagesTillNow;
        }
    }

    public List<String> generateSyncInfoPullRequest(final Map<String, HandShakingMessage> messages) {
        List<String> jsons = new ArrayList<String>();
        final Collection<HandShakingInfo> pullSyncInfo = instance.computeSyncInfoRequired(messages);
        Log.d(TAG, "generateSyncInfoPullRequest -> computeSyncInfoRequired ->" + pullSyncInfo.size());
        notifyUI("generateSyncInfoPullRequest -> computeSyncInfoRequired ->" + pullSyncInfo.size(), " ------> ", LOG_TYPE);
        if (pullSyncInfo != null && pullSyncInfo.size() > 0) {
            jsons = p2PDBApiImpl.serializeSyncRequestMessages(pullSyncInfo);
            instance.sendMessages(jsons);
        } else {
            Log.d(TAG, "generateSyncInfoPullRequest count 0 .....");
            instance.notifyUI("generateSyncInfoPullRequest count 0 .....", " ------->", LOG_TYPE);
            // disconnect as data had been exchanged
            instance.startNextDeviceToSync();
        }
        return jsons;
    }

    private void sendMessages(List<String> computedMessages) {
        if (computedMessages != null && computedMessages.size() > 0) {
            Iterator<String> it = computedMessages.iterator();
            while (it.hasNext()) {
                String p = it.next();
                instance.sendMulticastMessage(p);
            }
        }
    }


    private Set<HandShakingInfo> sortHandShakingInfos(final Map<String, HandShakingMessage> messages) {
        final Set<HandShakingInfo> allHandShakingInfos = new TreeSet<HandShakingInfo>(new Comparator<HandShakingInfo>() {
            @Override
            public int compare(HandShakingInfo o1, HandShakingInfo o2) {
                if (o1.getDeviceId().equalsIgnoreCase(o2.getDeviceId())) {
                    if (o1.getSequence().longValue() > o2.getSequence().longValue()) {
                        return 1;
                    } else {
                        return -1;
                    }
                }
                return o1.getDeviceId().compareToIgnoreCase(o2.getDeviceId());
            }
        });

        Iterator<Map.Entry<String, HandShakingMessage>> entries = messages.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, HandShakingMessage> entry = entries.next();
            Iterator<HandShakingInfo> it = entry.getValue().getInfos().iterator();
            while (it.hasNext()) {
                HandShakingInfo i = it.next();
                i.setFrom(entry.getKey());
            }

            allHandShakingInfos.addAll(entry.getValue().getInfos());
        }
        return allHandShakingInfos;
    }


    private Collection<HandShakingInfo> computeSyncInfoRequired(final Map<String, HandShakingMessage> messages) {
        // sort by device id and sequence desc order
        synchronized (BluetoothManager.class) {
            final Set<HandShakingInfo> allHandShakingInfos = sortHandShakingInfos(messages);
            Iterator<HandShakingInfo> itReceived = allHandShakingInfos.iterator();
            final Map<String, HandShakingInfo> uniqueHandShakeInfosReceived = new ConcurrentHashMap<String, HandShakingInfo>();
            final Map<String, HandShakingInfo> photoProfileUpdateInfosReceived = new ConcurrentHashMap<String, HandShakingInfo>();

            while (itReceived.hasNext()) {
                HandShakingInfo info = itReceived.next();
                HandShakingInfo existingInfo = uniqueHandShakeInfosReceived.get(info.getUserId());
                if (existingInfo == null) {
                    uniqueHandShakeInfosReceived.put(info.getUserId(), info);
                } else {
                    if (existingInfo.getSequence().longValue() < info.getSequence().longValue()) {
                        uniqueHandShakeInfosReceived.put(info.getUserId(), info);
                    } else if (existingInfo.getSequence().longValue() == info.getSequence().longValue()) {

                        String myMissingMessageSequences = existingInfo.getMissingMessages();
                        String otherDeviceMissingMessageSequences = info.getMissingMessages();
                        List<String> list1 = new ArrayList<String>();
                        List<String> list2 = new ArrayList<String>();
                        if (myMissingMessageSequences != null) {
                            list1 = Lists.newArrayList(Splitter.on(",").split(myMissingMessageSequences));
                        }
                        if (otherDeviceMissingMessageSequences != null) {
                            list2 = Lists.newArrayList(Splitter.on(",").split(otherDeviceMissingMessageSequences));
                        }
                        if (list1.size() > list2.size()) {
                            uniqueHandShakeInfosReceived.put(info.getUserId(), info);
                        }
                    }
                }
            }

            final Map<String, HandShakingInfo> myHandShakingMessages = p2PDBApiImpl.handShakingInformationFromCurrentDevice();

            Iterator<String> keys = uniqueHandShakeInfosReceived.keySet().iterator();
            while (keys.hasNext()) {
                String userKey = keys.next();
                Log.d(TAG, "computeSyncInfoRequired user key:" + userKey);
                if (myHandShakingMessages.keySet().contains(userKey)) {
                    HandShakingInfo infoFromOtherDevice = uniqueHandShakeInfosReceived.get(userKey);
                    HandShakingInfo infoFromMyDevice = myHandShakingMessages.get(userKey);

                    if (infoFromMyDevice != null && infoFromOtherDevice != null) {
                        Long latestProfilePhotoInfo = infoFromOtherDevice.getProfileSequence();
                        Long latestUserProfileId = p2PDBApiImpl.findLatestProfilePhotoId(infoFromOtherDevice.getUserId(), infoFromOtherDevice.getDeviceId());

                        if (latestUserProfileId != null && latestUserProfileId != null
                                && latestUserProfileId.longValue() < latestProfilePhotoInfo.longValue()) {
                            photoProfileUpdateInfosReceived.put(infoFromOtherDevice.getUserId(), infoFromOtherDevice);
                        }

                        long askedThreshold = infoFromMyDevice.getSequence().longValue() > SYNC_NUMBER_OF_LAST_MESSAGES ? infoFromMyDevice.getSequence().longValue() + 1 - SYNC_NUMBER_OF_LAST_MESSAGES : -1;
                        if (infoFromMyDevice.getSequence().longValue() > infoFromOtherDevice.getSequence().longValue()) {
                            Log.d(TAG, "removing from uniqueHandShakeInfosReceived for key:" + userKey + " as infoFromMyDevice.getSequence()" + infoFromMyDevice.getSequence() + " infoFromOtherDevice.getSequence()" + infoFromOtherDevice.getSequence());
                            uniqueHandShakeInfosReceived.remove(userKey);
                        } else if (infoFromMyDevice.getSequence().longValue() == infoFromOtherDevice.getSequence().longValue()) {
                            //check for missing keys, if the same then remove otherwise only add missing key for infoFromMyDevice
                            String myMissingMessageSequences = infoFromMyDevice.getMissingMessages();
                            String otherDeviceMissingMessageSequences = infoFromOtherDevice.getMissingMessages();
                            List<String> list1 = new ArrayList<String>();
                            List<String> list2 = new ArrayList<String>();
                            if (myMissingMessageSequences != null) {
                                list1 = Lists.newArrayList(Splitter.on(",").split(myMissingMessageSequences));
                            }
                            if (otherDeviceMissingMessageSequences != null) {
                                list2 = Lists.newArrayList(Splitter.on(",").split(otherDeviceMissingMessageSequences));
                            }
                            List<String> missingSequencesToAsk = new ArrayList<>(CollectionUtils.subtract(list1, list2));
                            if (askedThreshold > -1) {
                                CollectionUtils.filter(missingSequencesToAsk, new Predicate<String>() {
                                    @Override
                                    public boolean evaluate(String o) {
                                        return o.compareTo(String.valueOf(askedThreshold)) >= 0;
                                    }
                                });
                            }
                            Set<String> missingMessagesSetToAsk = ImmutableSet.copyOf(missingSequencesToAsk);
                            if (missingMessagesSetToAsk != null && missingMessagesSetToAsk.size() > 0) {
                                infoFromOtherDevice.setMissingMessages(StringUtils.join(missingMessagesSetToAsk, ","));
                                infoFromOtherDevice.setStartingSequence(infoFromOtherDevice.getSequence() + 1);
                            } else {
                                Log.d(TAG, "removing from uniqueHandShakeInfosReceived for key:" + userKey + " as infoFromMyDevice.getSequence()" + infoFromMyDevice.getSequence() + " infoFromOtherDevice.getSequence()" + infoFromOtherDevice.getSequence());
                                uniqueHandShakeInfosReceived.remove(userKey);
                            }
                            missingSequencesToAsk = null;
                            missingMessagesSetToAsk = null;

                        } else {
                            Log.d(TAG, "uniqueHandShakeInfosReceived for key:" + userKey + " as infoFromOtherDevice.setStartingSequence" + infoFromMyDevice.getSequence().longValue());
                            // take other device's missing keys remove
                            // take my missing keys and remove if the same as other device's missing keys
                            // ask for all messages my sequence + 1
                            // ask for all my missing keys messages also

                            String myMissingMessageSequences = infoFromMyDevice.getMissingMessages();
                            String otherDeviceMissingMessageSequences = infoFromOtherDevice.getMissingMessages();
                            List<String> list1 = new ArrayList<String>();
                            List<String> list2 = new ArrayList<String>();
                            if (myMissingMessageSequences != null) {
                                list1 = Lists.newArrayList(Splitter.on(",").split(myMissingMessageSequences));
                            }
                            if (otherDeviceMissingMessageSequences != null) {
                                list2 = Lists.newArrayList(Splitter.on(",").split(otherDeviceMissingMessageSequences));
                            }
                            List<String> missingSequencesToAsk = new ArrayList<>(CollectionUtils.subtract(list1, list2));
                            if (askedThreshold > -1) {
                                CollectionUtils.filter(missingSequencesToAsk, new Predicate<String>() {
                                    @Override
                                    public boolean evaluate(String o) {
                                        return o.compareTo(String.valueOf(askedThreshold)) >= 0;
                                    }
                                });
                            }
                            Set<String> missingMessagesSetToAsk = ImmutableSet.copyOf(missingSequencesToAsk);
                            if (missingMessagesSetToAsk != null && missingMessagesSetToAsk.size() > 0) {
                                infoFromOtherDevice.setMissingMessages(StringUtils.join(missingMessagesSetToAsk, ","));
                            }
                            //infoFromOtherDevice.setStartingSequence(infoFromMyDevice.getSequence().longValue() + 1);
                            if (infoFromOtherDevice.getSequence() > SYNC_NUMBER_OF_LAST_MESSAGES) {
                                infoFromOtherDevice.setStartingSequence(infoFromOtherDevice.getSequence() - SYNC_NUMBER_OF_LAST_MESSAGES + 1);
                            } else {
                                infoFromOtherDevice.setStartingSequence(infoFromMyDevice.getSequence().longValue() + 1);
                            }

                            missingSequencesToAsk = null;
                            missingMessagesSetToAsk = null;
                        }
                    }
                }
            }


            List<HandShakingInfo> valuesToSend = new ArrayList<HandShakingInfo>();

            Collection<HandShakingInfo> photoValues = photoProfileUpdateInfosReceived.values();
            Iterator itPhotoValues = photoValues.iterator();
            while (itPhotoValues.hasNext()) {
                HandShakingInfo t = (HandShakingInfo) itPhotoValues.next();
                HandShakingInfo n = new HandShakingInfo(t.getUserId(), t.getDeviceId(), t.getProfileSequence(), null, null);
                n.setFrom(t.getFrom());
                n.setStartingSequence(Long.valueOf(t.getProfileSequence()));
                n.setSequence(Long.valueOf(t.getProfileSequence()));
                valuesToSend.add(n);
            }

            Collection<HandShakingInfo> values = uniqueHandShakeInfosReceived.values();
            Iterator itValues = values.iterator();
            while (itValues.hasNext()) {
                HandShakingInfo t = (HandShakingInfo) itValues.next();
                Log.d(TAG, "validating : " + t.getUserId() + " " + t.getDeviceId() + " " + t.getStartingSequence() + " " + t.getSequence());

                if (t.getMissingMessages() != null && t.getMissingMessages().length() > 0) {

                    List<String> missingMessages = Lists.newArrayList(Splitter.on(",").split(t.getMissingMessages()));
                    Set<String> missingMessagesSet = ImmutableSet.copyOf(missingMessages);
                    missingMessages = null;
                    for (String m : missingMessagesSet) {
                        HandShakingInfo n = new HandShakingInfo(t.getUserId(), t.getDeviceId(), t.getSequence(), null, null);
                        n.setFrom(t.getFrom());
                        n.setStartingSequence(Long.valueOf(m));
                        n.setSequence(Long.valueOf(m));
                        valuesToSend.add(n);
                    }
                }


                if (t.getStartingSequence() == null) {
                    t.setMissingMessages(null);
                    valuesToSend.add(t);
                } else if (t.getStartingSequence() != null && t.getStartingSequence().longValue() <= t.getSequence().longValue()) {
                    t.setMissingMessages(null);
                    valuesToSend.add(t);
                }
            }
            return valuesToSend;
        }
    }


    private boolean shouldSendAckForHandShakingMessage(HandShakingMessage handShakingMessage) {
        if (handShakingMessage != null) {
            boolean sendAck = handShakingMessage.getReply().equalsIgnoreCase("true");
            Log.d(TAG, "shouldSendAckForHandShaking: " + handShakingMessage.getFrom() + " sendAck:" + sendAck);
            return sendAck;
        } else {
            return false;
        }
    }


    public HandShakingMessage parseHandShakingMessage(String message) {
        HandShakingMessage handShakingMessage = p2PDBApiImpl.deSerializeHandShakingInformationFromJson(message);
        if (handShakingMessage != null) {
            Log.d(TAG, "storing handShakingMessage from : " + handShakingMessage.getFrom() + " in handShakingMessagesInCurrentLoop");
            instance.handShakingMessagesInCurrentLoop.put(handShakingMessage.getFrom(), handShakingMessage);
        }
        return handShakingMessage;
    }

    private boolean isHandShakingMessage(String message) {
        boolean isHandShakingMessage = false;
        if (message != null) {
            String handShakeMessage = "\"mt\":\"handshaking\"";
            isHandShakingMessage = message.contains(handShakeMessage);
        }
        return isHandShakingMessage;
    }

    private boolean isBluetoothSyncCompleteMessage(String message) {
        boolean isSyncCompletedMessage = false;
        if (message != null && message.equalsIgnoreCase("BLUETOOTH-SYNC-COMPLETED")) {
            isSyncCompletedMessage = true;
        }
        return isSyncCompletedMessage;
    }

    private boolean isSyncInfoMessage(String message) {
        boolean isSyncInfoMessage = false;
        if (message != null) {
            String syncInfoMessage = "\"mt\":\"syncInfoMessage\"";
            isSyncInfoMessage = message.contains(syncInfoMessage);
        }
        return isSyncInfoMessage;
    }

    private boolean isSyncRequestMessage(String message) {
        String messageType = "\"mt\":\"syncInfoRequestMessage\"";
        String messageType_1 = "\"mt\":\"syncInfoRequestMessage\"";
        return message != null && (message.contains(messageType) || message.contains(messageType_1)) ? true : false;
    }

    private void broadCastRefreshDevice() {
        Intent intent = new Intent(refreshDevice);
        LocalBroadcastManager.getInstance(this.context).sendBroadcast(intent);
    }

    private BroadcastReceiver refreshDeviceReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            synchronized (BluetoothManager.class) {
                AsyncTask.execute(new Runnable() {
                    @Override
                    public void run() {
                        notifyUI("Clear ALL...", " ------> ", CLEAR_CONSOLE_TYPE);
                        List<P2PSyncInfo> allInfos = p2PDBApiImpl.refreshAllMessages();
                        if (allInfos != null) {
                            Iterator<P2PSyncInfo> allInfosIt = allInfos.iterator();
                            while (allInfosIt.hasNext()) {
                                P2PSyncInfo p = allInfosIt.next();
                                instance.getAllSyncInfosReceived().add(p.getDeviceId() + "_" + p.getUserId() + "_" + Long.valueOf(p.getSequence().longValue()));
                                String sender = p.getSender().equals(P2PContext.getCurrentDevice()) ? "You" : p.getSender();
                                notifyUI(p.message, sender, CONSOLE_TYPE);
                            }
                        }
                        Log.d(TAG, "rebuild sync info received cache and updated UI");
                    }

                });
            }
        }
    };

    public void stopHandShakeTimer() {
        if (instance.handShakeFailedTimer != null) {
            instance.handShakeFailedTimer.cancel();
            instance.notifyUI("stopHandShakeTimer", " ------>", LOG_TYPE);
        }
    }

    public void connectedToRemote() {
        instance.notifyUI("connectedToRemote .... waiting for action ...", " ------>", LOG_TYPE);
        // stop polling
        if (instance.nextRoundTimer != null) {
            instance.nextRoundTimer.cancel();
        }
        instance.sendFindBuddyMessage();
    }

    public void sendFindBuddyMessage() {
        instance.sendInitialHandShakingMessage(true);
    }

    private void sendInitialHandShakingMessage(boolean needAck) {
        // construct handshaking message(s)
        // put in queue - TBD
        // send one by one from queue - TBD
        String serializedHandShakingMessage = instance.p2PDBApiImpl.serializeHandShakingMessage(needAck);
        Log.d(TAG, "sending initial handshaking message: " + serializedHandShakingMessage);
        instance.sendMulticastMessage(serializedHandShakingMessage);
    }

    public void startHandShakeTimer() {
        synchronized (BluetoothManager.class) {
            instance.handShakeFailedTimer.start();
        }
    }


    @Override
    public void HandShakeFailed(String reason, boolean isDisconnectAfterSync) {
        synchronized (BluetoothManager.class) {
            notifyUI("HandShakeFailed: " + reason, " ----> ", LOG_TYPE);
            notifyUI("peerDevices has  ... " + instance.peerDevices.size(), "---------->", LOG_TYPE);
            if (peerDevices != null && peerDevices.contains(instance.connectedAddress)) {
                peerDevices.remove(instance.connectedAddress);
                notifyUI("peerDevices removed ... " + instance.connectedAddress, "---------->", LOG_TYPE);
                instance.connectedAddress = "";
            }
            instance.stopHandShakeTimer();
            if(isDisconnectAfterSync) {
                instance.startNextDeviceToSync();
            } else  {
                notifyUI("WAITING FOR NEXT SYNC ROUND ....", " ----> ", LOG_TYPE);
            }
        }
    }

    public void startNextDeviceToSync() {
        synchronized (BluetoothManager.class) {
            instance.startListener();
            notifyUI("peerDevices has  ... " + instance.peerDevices.size(), "---------->", LOG_TYPE);
            if (peerDevices != null && peerDevices.contains(instance.connectedAddress)) {
                peerDevices.remove(instance.connectedAddress);
                notifyUI("peerDevices removed ... " + instance.connectedAddress, "---------->", LOG_TYPE);
                instance.connectedAddress = "";
            }
            instance.startNextPolling();
            notifyUI("startNextDeviceToSync ...", "---------->", LOG_TYPE);
        }
    }
}
