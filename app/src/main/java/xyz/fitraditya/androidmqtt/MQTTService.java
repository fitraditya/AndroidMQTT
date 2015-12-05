package xyz.fitraditya.androidmqtt;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.security.ProviderInstaller;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.eclipse.paho.client.mqttv3.MqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Locale;


public class MQTTService extends Service implements MqttCallback {

    private static String PREFS     = "mqtt-prefs";
    private static String sHOSTNAME = "-Hostname";
    private static String sPORT     = "-Port";
    private static String sUSERNAME = "-Username";
    private static String sPASSWORD = "-Password";
    private static String sTOPIC    = "-Topic";

    private String MQTT_BROKER;
    private String MQTT_PORT;
    private String USERNAME;
    private String PASSWORD;
    private String TOPIC;

    public static final String DEBUG_TAG = "MqttService";

    public int nCount = 0;

    public static final int	MQTT_QOS_0 = 0;
    public static final int MQTT_QOS_1 = 1;
    public static final int	MQTT_QOS_2 = 2;

    private static final String	MQTT_THREAD_NAME = "MqttService[" + DEBUG_TAG + "]";
    private static final int MQTT_KEEP_ALIVE = 120000;
    private static final String	MQTT_KEEP_ALIVE_TOPIC_FORMAT = "/users/%s/keepalive";
    private static final byte[] MQTT_KEEP_ALIVE_MESSAGE = { 0 };
    private static final int MQTT_KEEP_ALIVE_QOS = MQTT_QOS_2;
    private static final boolean MQTT_CLEAN_SESSION = false;
    private static final String ACTION_START = DEBUG_TAG + ".START";
    private static final String ACTION_STOP	= DEBUG_TAG + ".STOP";
    private static final String ACTION_KEEPALIVE = DEBUG_TAG + ".KEEPALIVE";
    private static final String ACTION_RECONNECT = DEBUG_TAG + ".RECONNECT";
    private static final String ACTION_FORCE_RECONNECT = DEBUG_TAG + ".FORCE_RECONNECT";
    private static final String ACTION_SANITY = DEBUG_TAG + ".SANITY";
    private static final String DEVICE_ID_FORMAT = "andr_%s";

    private String MQTT_URL_FORMAT;

    private boolean mStarted, isReconnecting = false;
    private String mDeviceId;
    private Handler mConnHandler;
    private MqttDefaultFilePersistence mDataStore;
    private MemoryPersistence mMemStore;
    private MqttConnectOptions mOpts;
    private MqttTopic mKeepAliveTopic;
    private MqttClient mClient;
    private AlarmManager mAlarmManager;
    private ConnectivityManager mConnectivityManager;

    Notification n = null;

    public static void actionStart(Context ctx) {
        Intent i = new Intent(ctx,MQTTService.class);
        i.setAction(ACTION_START);
        ctx.startService(i);
    }

    public static void actionStop(Context ctx) {
        Intent i = new Intent(ctx,MQTTService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    public static void actionKeepalive(Context ctx) {
        Intent i = new Intent(ctx,MQTTService.class);
        i.setAction(ACTION_KEEPALIVE);
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        final SharedPreferences sharedPref = this.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        super.onCreate();

        String vHostname, vTopic, vUsername, vPassword, vPort;
        vHostname	= sharedPref.getString(sHOSTNAME, "");
        vTopic		= sharedPref.getString(sTOPIC,"");
        vUsername	= sharedPref.getString(sUSERNAME, "");
        vPassword	= sharedPref.getString(sPASSWORD, "");
        vPort		= sharedPref.getString(sPORT, "");

        MQTT_BROKER = vHostname;
        MQTT_PORT = vPort;
        USERNAME = vUsername;
        PASSWORD = vPassword;
        TOPIC = vTopic;

        if (MQTT_PORT.equals("1883")) {
            MQTT_URL_FORMAT = "tcp://%s:%s";
        } else {
            MQTT_URL_FORMAT = "ssl://%s:%s";
        }

        try {
            ProviderInstaller.installIfNeeded(getApplicationContext());
        } catch (GooglePlayServicesRepairableException e) {
            e.printStackTrace();
        } catch (GooglePlayServicesNotAvailableException e) {
            e.printStackTrace();
        }

        mDeviceId = String.format(DEVICE_ID_FORMAT, Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ANDROID_ID));

        HandlerThread thread = new HandlerThread(MQTT_THREAD_NAME);
        thread.start();

        mConnHandler = new Handler(thread.getLooper());
        mDataStore = new MqttDefaultFilePersistence(getCacheDir().getAbsolutePath());

        mOpts = new MqttConnectOptions();
        mOpts.setCleanSession(MQTT_CLEAN_SESSION);

        if (USERNAME != null && !USERNAME.isEmpty()) {
            mOpts.setUserName(USERNAME);
        }

        if (PASSWORD != null && !PASSWORD.isEmpty()) {
            mOpts.setPassword(PASSWORD.toCharArray());
        }

        mAlarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        mConnectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sanityTimerStop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        String action = null;

        if (intent != null) {
            action = intent.getAction();
        }

        if (action == null) {
            start();
        } else {
            if (action.equals(ACTION_START)) {
                start();
            } else if (action.equals(ACTION_STOP)) {
                stop();
            } else if (action.equals(ACTION_KEEPALIVE)) {
                keepAlive();
            } else if (action.equals(ACTION_RECONNECT)) {
                if (isNetworkAvailable()) {
                    reconnectIfNecessary();
                }
            } else if (action.equals(ACTION_FORCE_RECONNECT)) {
                if (isNetworkAvailable()) {
                    forceReconnect();
                }
            } else if (action.equals(ACTION_SANITY)) {
                if (isNetworkAvailable() && !isConnected()) {
                    forceReconnect();
                }
            }
        }

        return Service.START_STICKY;
    }

    private synchronized void start() {
        if (mStarted) {
            return;
        }

        if (hasScheduledKeepAlives()) {
            stopKeepAlives();
        }

        connect();
        registerReceiver(mConnectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    private synchronized void stop() {
        if (!mStarted) {
            return;
        }

        if (mClient != null) {
            mConnHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        mClient.disconnect();
                    } catch (MqttException ex) {
                        ex.printStackTrace();
                    }

                    mClient = null;
                    mStarted = false;
                    stopKeepAlives();
                    sanityTimerStop();
                    statusIcon(false);
                }
            });
        }

        unregisterReceiver(mConnectivityReceiver);
    }

    private synchronized void connect() {
        String url = String.format(Locale.US, MQTT_URL_FORMAT, MQTT_BROKER, MQTT_PORT);

        try {
            if(mDataStore != null) {
                mClient = new MqttClient(url,mDeviceId,mDataStore);
            } else {
                mClient = new MqttClient(url,mDeviceId,mMemStore);
            }
        } catch(MqttException e) {
            e.printStackTrace();
        }

        mConnHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mClient != null && mClient.isConnected())
                        return;

                    mClient.connect(mOpts);
                    String[] topics = TOPIC.split(",");

                    for (String topic : topics) {
                        mClient.subscribe(topic, 2);
                    }

                    mClient.setCallback(MQTTService.this);
                    mStarted = true;
                    statusIcon(true);
                    startKeepAlives();
                    isReconnecting = false;
                } catch (Exception e) {
                    e.printStackTrace();
                    forceReconnect();
                }

                sanityTimerStart();
            }
        });
    }

    private void startKeepAlives() {
        Intent i = new Intent();
        i.setClass(this, MQTTService.class);
        i.setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        mAlarmManager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + MQTT_KEEP_ALIVE, MQTT_KEEP_ALIVE, pi);
    }

    private void stopKeepAlives() {
        Intent i = new Intent();
        i.setClass(this, MQTTService.class);
        i.setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getService(this, 0, i , 0);
        mAlarmManager.cancel(pi);
    }

    private void sanityTimerStart() {
        Intent i = new Intent();
        i.setClass(this, MQTTService.class);
        i.setAction(ACTION_SANITY);
        PendingIntent pi = PendingIntent.getService(this, 1, i, 0);
        mAlarmManager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + MQTT_KEEP_ALIVE, MQTT_KEEP_ALIVE, pi);
    }

    private void sanityTimerStop() {
        Intent i = new Intent();
        i.setClass(this, MQTTService.class);
        PendingIntent pi = PendingIntent.getService(this, 1, i , 0);
        mAlarmManager.cancel(pi);
    }

    private synchronized void keepAlive() {
        boolean kFailed = false;

        if (isConnected()) {
            try {
                sendKeepAlive();
                return;
            } catch (MqttConnectivityException ex) {
                kFailed = true;
            } catch(MqttPersistenceException ex) {
                kFailed = true;
            } catch(MqttException ex) {
                kFailed = true;
            } catch (Exception ex) {
                kFailed = true;
            }

            if (kFailed) {
                forceReconnect();
            }
        }
    }

    private synchronized void reconnectIfNecessary() {
        statusIcon(false);

        if (mStarted && mClient == null) {
            connect();
        }
    }

    private boolean isNetworkAvailable() {
        NetworkInfo info = mConnectivityManager.getActiveNetworkInfo();
        return (info == null) ? false : info.isConnected();
    }

    private boolean isConnected() {
        if (mStarted && mClient != null && !mClient.isConnected()) {}

        if (mClient != null) {
            return (mStarted && mClient.isConnected()) ? true : false;
        }

        return false;
    }

    private final BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            statusIcon(false);
        }
    };

    private synchronized MqttDeliveryToken sendKeepAlive()
            throws MqttConnectivityException, MqttPersistenceException, MqttException, Exception {
        if (!isConnected())
            throw new MqttConnectivityException();

        if (mKeepAliveTopic == null) {
            mKeepAliveTopic = mClient.getTopic(String.format(Locale.US,
                    MQTT_KEEP_ALIVE_TOPIC_FORMAT, mDeviceId));
        }

        MqttMessage message = new MqttMessage(MQTT_KEEP_ALIVE_MESSAGE);
        message.setQos(MQTT_KEEP_ALIVE_QOS);

        return mKeepAliveTopic.publish(message);
    }

    private synchronized boolean hasScheduledKeepAlives() {
        Intent i = new Intent();
        i.setClass(this, MQTTService.class);
        i.setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_NO_CREATE);

        return (pi != null) ? true : false;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public void connectionLost(Throwable arg0) {
        stopKeepAlives();
        mClient = null;
        statusIcon(false);

        if (isNetworkAvailable()) {
            forceReconnect();
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken arg0) {}

    @Override
    public void messageArrived(String topic, MqttMessage message)
            throws Exception {
        System.out.println("Message Arrived: " + topic + " - " + message);

        final Vibrator vibrator = (Vibrator) getApplicationContext().getSystemService(Context.VIBRATOR_SERVICE);
        vibrator.vibrate(500);

        Intent intent = new Intent(this, MQTTService.class);
        PendingIntent pIntent = PendingIntent.getActivity(this, 0, intent, 0);

        Notification n = new Notification.Builder(this)
                .setContentTitle("New msg @ " + topic)
                .setContentIntent(pIntent)
                .setSmallIcon(R.drawable.m2mgreen)
                .setStyle(new Notification.BigTextStyle().bigText(message.toString()))
                .setAutoCancel(false).build();
        n.ledARGB = 0xff00ff00;
        n.ledOnMS = 100;
        n.ledOffMS = 100;
        n.flags |= Notification.FLAG_SHOW_LIGHTS;

        NotificationManager notificationManager =(NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(nCount, n);
        nCount++;
    }

    public void statusIcon(boolean status) {
        Intent intent = new Intent(this, MQTTService.class);
        PendingIntent pIntent = PendingIntent.getActivity(this, 0, intent, 0);

        if (status) {
            intent.setAction(ACTION_STOP);
            PendingIntent actionPendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

            n = new Notification.Builder(this)
                    .setContentTitle("MQTT Active")
                    .setContentIntent(pIntent)
                    .addAction(android.R.drawable.presence_offline, "Disconnect", actionPendingIntent)
                    .setSmallIcon(R.drawable.m2mgreen)
                    .setAutoCancel(false).build();
        } else {
            intent.setAction(ACTION_START);
            PendingIntent actionPendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

            n = new Notification.Builder(this)
                    .setContentTitle("MQTT Inactive")
                    .setContentIntent(pIntent)
                    .setSmallIcon(R.drawable.m2mgrey)
                    .addAction(android.R.drawable.presence_online, "Connect", actionPendingIntent)
                    .setAutoCancel(false).build();
        }

        n.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;

        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(-1, n);
    }

    private void forceReconnect() {
        if (isReconnecting) {
            return;
        }

        isReconnecting = true;

        stopKeepAlives();
        mClient = null;
        mStarted = false;
        statusIcon(false);

        if (MQTT_BROKER.length() > 0) {
            while (!isAvailable(MQTT_BROKER)) {
                try {
                    Thread.sleep(MQTT_KEEP_ALIVE / 4);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            start();
        }
    }

    public Boolean isAvailable(String vHostname) {
        try {
            Process p1 = java.lang.Runtime.getRuntime().exec("ping -c 1 " + vHostname);
            int returnVal = p1.waitFor();
            boolean reachable = (returnVal == 0);

            if (reachable) {
                return reachable;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    private class MqttConnectivityException extends Exception {
        private static final long serialVersionUID = -1234567890123456780L;
    }

}
