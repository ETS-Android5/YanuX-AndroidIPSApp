package android.example.findlocation.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.example.findlocation.BuildConfig;
import android.example.findlocation.IndoorApp;
import android.example.findlocation.R;
import android.example.findlocation.exceptions.SharedPreferencesException;
import android.example.findlocation.interfaces.SharedPreferencesInterface;
import android.example.findlocation.objects.client.BluetoothObject;
import android.example.findlocation.objects.client.SensorObject;
import android.example.findlocation.objects.client.WifiObject;
import android.example.findlocation.objects.server.ScanningObject;
import android.example.findlocation.ui.activities.scanning.ScanningActivity;
import android.example.findlocation.utils.PreferenceUtils;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

//TODO: Disable Wi-Fi scanning. Since we are not using it right now it's a way to save resources.
//import android.net.wifi.ScanResult;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ActiveScanningService extends Service implements SensorEventListener, BeaconConsumer, SharedPreferencesInterface {
    public static final int NOTIFICATION_ID = 8165;
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    static final String CHANNEL_ID = "indoorApp.BackgroundScanningService";

    private static final String PREF_USERNAME = "PREF_USERNAME";
    private static final String PREF_DEVICE_UUID = "PREF_DEVICE_UUID";

    private static final long SERVICE_DELAY = 3000;
    private static final String IBEACON_LAYOUT = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24";
    private static final String TAG = ActiveScanningService.class.getSimpleName();
    private static final Gson GSON = new Gson();

    // Binder given to clients
    private final IBinder mBinder = new LocalBinder();
    //TECHNOLOGIES RELATED STRUCTURES
    private final List<WifiObject> mAccessPointsList = Collections.synchronizedList(new ArrayList<>());
    private final List<BluetoothObject> mBeaconsList = Collections.synchronizedList(new ArrayList<>());
    private final List<SensorObject> mSensorInformationList = Collections.synchronizedList(new ArrayList<>());
    private ServiceHandler mServiceHandler;
    private OkHttpClient mHttpClient;
    private SensorManager mSensorManager;

    //TODO: Disable Wi-Fi scanning. Since we are not using it right now it's a way to save resources.
    //private WifiManager wifiManager;
    private BeaconManager beaconManager;
    private int mLatestKnownAps;
    private int mLatestKnownBeacons;
    private int mNotFoundServerCount;
    // Accelerometer and magnetometer sensors, as retrieved from the
    // sensor manager.
    private Sensor mSensorAccelerometer;
    private Sensor mSensorMagnetometer;
    // Current data from accelerometer & magnetometer.  The arrays hold values
    // for X, Y, and Z.
    private float[] mAccelerometerData;
    private float[] mMagnetometerData;
    private Display mDisplay;
    private SharedPreferences mAppPreferences;
    private String mUsername;
    private String mDeviceUuid;
    //Just a flag to check if the service is started
    private boolean mStarted;

    //TODO: Disable Wi-Fi scanning. Since we are not using it right now it's a way to save resources.
    //private final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
    //    @Override
    //    public void onReceive(Context c, Intent intent) {
    //        boolean success = intent.getBooleanExtra(
    //                WifiManager.EXTRA_RESULTS_UPDATED, false);
    //        if (mStarted) {
    //            if (success) {
    //                // Refresh access points information
    //                mAccessPointsList.clear();
    //                scanSuccess();
    //            } else {
    //                // Scan failure handling
    //                scanFailure();
    //            }
    //            wifiManager.startScan();
    //        }
    //    }
    //};
    private NotificationCompat.Builder mBuilder;

    @Override
    public void onCreate() {
        super.onCreate();
        initializeSharedPreferences();
        loadVariablesFromSharedPreferences();
        mHttpClient = new OkHttpClient();
        mAccelerometerData = new float[3];
        mMagnetometerData = new float[3];
        mLatestKnownAps = 0;
        mLatestKnownBeacons = 0;
        mNotFoundServerCount = 0;
        mStarted = false;
        startThreadAndHandler();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!mStarted) {
            mStarted = true;
            Log.i(TAG, "Starting Scanning Service...");
            Notification mNotification = structureNotificationForForegroundUsage();
            Log.i(TAG, "Notification created for Foreground usage");
            startForeground(NOTIFICATION_ID, mNotification);
            Log.i(TAG, "Started Foreground Service");
            activateSensorScan();
            handleScanningService();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mStarted) {
            mStarted = false;
            cleanUpService();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void initializeSharedPreferences() {
        mAppPreferences = IndoorApp.appPreferences;
    }

    @Override
    public void loadVariablesFromSharedPreferences() {
        mUsername = mAppPreferences.getString(PREF_USERNAME, null);
        mDeviceUuid = mAppPreferences.getString(PREF_DEVICE_UUID, null);
        if (mUsername == null || mDeviceUuid == null) {
            try {
                throw new SharedPreferencesException("Username or Device UUID not found in shared preferences. Something went wrong in the authentication process.");
            } catch (SharedPreferencesException e) {
                e.printStackTrace();
            }
        }
    }

    private void cleanUpService() {
        stopForeground(true);
        beaconManager.unbind(this);
        //TODO: Disable Wi-Fi scanning. Since we are not using it right now it's a way to save resources.
        //unregisterReceiver(wifiScanReceiver);
        mSensorManager.unregisterListener(this);
        mServiceHandler.removeCallbacksAndMessages(null);
    }

    private void startThreadAndHandler() {
        HandlerThread thread = new HandlerThread("ServiceStartArguments");
        thread.start();
        Log.i(TAG, "Starting new thread for Active Scanning Service");
        // Get the HandlerThread's Looper and use it for our Handler
        Looper mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);
    }

    private Notification structureNotificationForForegroundUsage() {
        createNotificationChannel();
        PendingIntent mPendingIntent = createPendingIntentForNotification();
        return createNotification(mPendingIntent);
    }

    /**
     * Create Notification Channel for Foreground Usage
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence mNotificationName = "Scanning Service";
            int mImportance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, mNotificationName, mImportance);
            channel.enableVibration(false);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private PendingIntent createPendingIntentForNotification() {
        Intent mNotificationIntent = new Intent(this, ScanningActivity.class);
        mNotificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, mNotificationIntent, PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification createNotification(PendingIntent mPendingIntent) {
        Intent disableIntent = new Intent(this, ActiveScanningServiceBroadcastReceiver.class);
        disableIntent.setAction(ActiveScanningServiceBroadcastReceiver.ACTION_DISABLE_SERVICE);
        PendingIntent disablePendingIntent = PendingIntent.getBroadcast(this, 0, disableIntent, PendingIntent.FLAG_IMMUTABLE);

        mBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getText(R.string.notification_title))
                .setContentText("APs Detected: 0 | Beacons Detected: 0")
                .setSmallIcon(R.drawable.ic_notifications_black_24dp)
                .setContentIntent(mPendingIntent)
                .addAction(R.drawable.baseline_stop_24, getString(R.string.notication_action_disable_active_scanning_service), disablePendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVibrate(null);
        return mBuilder.build();
    }

    private void updateNotification(String message) {
        mBuilder.setContentText(message);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(NOTIFICATION_ID, mBuilder.build());
    }

    private void restartScan() {
        mSensorInformationList.clear();
        mBeaconsList.clear();
        //TODO: Disable Wi-Fi scanning. Since we are not using it right now it's a way to save resources.
        //wifiManager.startScan();
    }

    protected void handleScanningService() {
        mServiceHandler.postDelayed(new Runnable() {
            @RequiresApi(api = Build.VERSION_CODES.M)
            public void run() {
                if (mStarted) {
                    if (mLatestKnownAps != mAccessPointsList.size() || mLatestKnownBeacons != mBeaconsList.size()) {
                        updateNotification("APs: " + mAccessPointsList.size() + " | Beacons Detected: " + mBeaconsList.size());
                        mLatestKnownAps = mAccessPointsList.size();
                        mLatestKnownBeacons = mBeaconsList.size();
                    }
                    if (mAccessPointsList.size() != 0 || mBeaconsList.size() != 0)
                        sendDataToServer();

                    mServiceHandler.postDelayed(this, SERVICE_DELAY); // Uncomment this to become cyclic
                }
            }
        }, SERVICE_DELAY);
    }

    private synchronized void sendDataToServer() {
        ScanningObject mScanningObject = new ScanningObject(mUsername, mDeviceUuid, mAccessPointsList, mBeaconsList, mSensorInformationList);

        String mScanningObjectInJson;
        synchronized (mAccessPointsList) {
            synchronized (mBeaconsList) {
                synchronized (mSensorInformationList) {
                    mScanningObjectInJson = GSON.toJson(mScanningObject);
                }
            }
        }

        sendPostHTTPRequest(PreferenceUtils.getIndoorAppServerScanningEndpoint(this), mScanningObjectInJson);
    }

    private void sendPostHTTPRequest(String mUrl, String mJsonString) {
        Handler mainHandler = new Handler(getMainLooper());
        Request mPostRequest = structureRequest(mUrl, mJsonString);
        try (Response response = mHttpClient.newCall(mPostRequest).execute()) {
            if (!response.isSuccessful()) {
                Log.e(TAG, "Unexpected code " + response);
                if (response.code() == 404) {
                    mNotFoundServerCount++;
                    if (mNotFoundServerCount == 4) {
                        mNotFoundServerCount = 0;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                // Do your stuff here related to UI, e.g. show toast
                                Toast.makeText(getApplicationContext(), "Server failed to position user.", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            } else {
                Log.i(TAG, "Successfully sent data to the server.");
            }
            restartScan();
            response.body().close();
        } catch (ConnectException | SocketTimeoutException e) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    // Do your stuff here related to UI, e.g. show toast
                    Toast.makeText(getApplicationContext(), "Failed to connect to the server", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Request structureRequest(String url, String jsonString) {
        RequestBody mRequestBody = RequestBody.create(jsonString, JSON);
        return new Request.Builder()
                .url(url)
                .post(mRequestBody)
                .build();
    }

    protected void activateSensorScan() {
        initializeDeviceSensor();
        initializeBluetoothSensor();
        //TODO: Disable Wi-Fi scanning. Since we are not using it right now it's a way to save resources.
        //initializeWifiSensor();
    }

    private void initializeDeviceSensor() {
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mSensorAccelerometer = mSensorManager.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER);
        mSensorMagnetometer = mSensorManager.getDefaultSensor(
                Sensor.TYPE_MAGNETIC_FIELD);
        // Get the display from the window manager (for rotation).
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mDisplay = wm.getDefaultDisplay();
        mSensorInformationList.clear();
        listenForOrientationSensor();
        Log.i(TAG, "Successfully initialized device sensor information");
    }

    private void listenForOrientationSensor() {
        if (mSensorAccelerometer != null) {
            mSensorManager.registerListener(this, mSensorAccelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (mSensorMagnetometer != null) {
            mSensorManager.registerListener(this, mSensorMagnetometer,
                    SensorManager.SENSOR_DELAY_NORMAL);
        }
        Log.i(TAG, "Added Orientation Sensor information to list.");
    }

    private float[] updateOrientation(float[] mRotationMatrix) {
        float[] mRotationMatrixAdjusted = new float[9];
        switch (mDisplay.getRotation()) {
            case Surface.ROTATION_0:
            default:
                mRotationMatrixAdjusted = mRotationMatrix.clone();
                break;
            case Surface.ROTATION_90:
                SensorManager.remapCoordinateSystem(mRotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, mRotationMatrixAdjusted);
                break;
            case Surface.ROTATION_180:
                SensorManager.remapCoordinateSystem(mRotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, mRotationMatrixAdjusted);
                break;
            case Surface.ROTATION_270:
                SensorManager.remapCoordinateSystem(mRotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, mRotationMatrixAdjusted);
                break;
        }
        return mRotationMatrixAdjusted;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        Sensor mSensorDetected = event.sensor;
        int sensorType = event.sensor.getType();

        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER:
                mAccelerometerData = event.values.clone();
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                mMagnetometerData = event.values.clone();
                break;
            default:
                return;
        }
        float[] mRotationMatrix = new float[9];
        boolean isRotationAvailable = SensorManager.getRotationMatrix(mRotationMatrix,
                null, mAccelerometerData, mMagnetometerData);
        float[] mRotationMatrixAdjusted = updateOrientation(mRotationMatrix);
        float[] mOrientation = new float[3];
        if (isRotationAvailable) {
            SensorManager.getOrientation(mRotationMatrixAdjusted, mOrientation);
        }
        if (BuildConfig.DEBUG)
            Log.d(TAG, "New values for orientation: " + Arrays.toString(mOrientation));

        if (mSensorInformationList.isEmpty()) {
            SensorObject mOrientationSensor = new SensorObject("ORIENTATION", mOrientation);
            mSensorInformationList.add(mOrientationSensor);
        }


        SensorObject mOrientationSensor = mSensorInformationList.get(0);
        mOrientationSensor.setValue(mOrientation);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    private void initializeBluetoothSensor() {
        mBeaconsList.clear();
        beaconManager = BeaconManager.getInstanceForApplication(this);
        beaconManager.getBeaconParsers().clear();
        beaconManager.getBeaconParsers().add(new BeaconParser("iBeacon").setBeaconLayout(IBEACON_LAYOUT));
        setBeaconScanningSettings();
        beaconManager.bind(this);
    }

    private void setBeaconScanningSettings() {
        beaconManager.setEnableScheduledScanJobs(false);
        beaconManager.setBackgroundMode(false);
        beaconManager.setForegroundScanPeriod(150);
        beaconManager.setForegroundBetweenScanPeriod(0);
        beaconManager.setBackgroundScanPeriod(150);
        beaconManager.setBackgroundBetweenScanPeriod(0);
        Log.i(TAG, "Successfully set Alt-Beacon settings.");
    }

    @Override
    public void onBeaconServiceConnect() {
        beaconManager.removeAllRangeNotifiers();
        beaconManager.addRangeNotifier(new RangeNotifier() {
            @Override
            public void didRangeBeaconsInRegion(Collection<Beacon> beacons, org.altbeacon.beacon.Region region) {
                for (Beacon mBeaconScanned : beacons) {
                    int mRssi = mBeaconScanned.getRssi();
                    if (BuildConfig.DEBUG)
                        Log.d(TAG, "New values for beacon: " + mBeaconScanned.getBluetoothAddress() + " | RSSI: " + mRssi);
                    boolean mBeaconExists = false;
                    synchronized (mBeaconsList) {
                        for (BluetoothObject mKnownBeacon : mBeaconsList) {
                            String mIdString = mBeaconScanned.getId1().toString() + "-" + mBeaconScanned.getId2().toString()
                                    + "-" + mBeaconScanned.getId3().toString();
                            if (mKnownBeacon.getName().equals(mIdString)) {
                                mKnownBeacon.setSingleValue(mRssi);
                                mKnownBeacon.addValue(mRssi);
                                mBeaconExists = true;
                                break;
                            }
                        }
                        if (!mBeaconExists) {
                            String mIdString = mBeaconScanned.getId1().toString() + "-" + mBeaconScanned.getId2().toString()
                                    + "-" + mBeaconScanned.getId3().toString();
                            BluetoothObject mNewBeaconFound = new BluetoothObject(mIdString, mBeaconScanned.getBluetoothAddress(), mRssi);
                            mNewBeaconFound.addValue(mRssi);
                            mBeaconsList.add(mNewBeaconFound);
                            Log.i(TAG, "Added beacon " + mNewBeaconFound.getName() + " to the known list.");
                        }
                    }
                }
            }
        });
        try {
            beaconManager.startRangingBeaconsInRegion(new Region("uniqueIdRegion", null, null, null));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    //TODO: Disable Wi-Fi scanning. Since we are not using it right now it's a way to save resources.
    //private void initializeWifiSensor() {
    //    mAccessPointsList.clear();
    //    wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    //    wifiManager.setWifiEnabled(true);
    //    IntentFilter intentFilter = new IntentFilter();
    //    intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
    //    this.registerReceiver(wifiScanReceiver, intentFilter);
    //    boolean scanSuccess = wifiManager.startScan();
    //    if (!scanSuccess)
    //        scanFailure();
    //    Log.i(TAG, "Successfully initialized wifi settings for scanning.");
    //}


    //TODO: Disable Wi-Fi scanning. Since we are not using it right now it's a way to save resources.
    //private void scanFailure() {
    //    // handle failure: new scan did NOT succeed
    //    // consider using old scan results: these are the OLD results!
    //    Log.e(TAG, "Scanned of Wi-Fi Access Points failed. Consider using old scan results but for now just this log");
    //}

    //TODO: Disable Wi-Fi scanning. Since we are not using it right now it's a way to save resources.
    //private void scanSuccess() {
    //    List<ScanResult> mAvailableResults = wifiManager.getScanResults();
    //    for (ScanResult mScanResult : mAvailableResults
    //    ) {
    //        int mRssi = mScanResult.level;
    //        if (BuildConfig.DEBUG)
    //            Log.d(TAG, "New values for access point: " + mScanResult.BSSID + " | RSSI: " + mRssi);
    //        boolean mAccessPointExists = false;
    //        synchronized (mAccessPointsList) {
    //            for (WifiObject mKnownAccessPoint : mAccessPointsList) {
    //                if (mKnownAccessPoint.getName().equals(mScanResult.BSSID)) {
    //                    mKnownAccessPoint.setSingleValue(mRssi);
    //                    mAccessPointExists = true;
    //                    break;
    //                }
    //            }
    //            if (!mAccessPointExists) {
    //                WifiObject mNewAccessPointFound = new WifiObject(mScanResult.BSSID, mRssi);
    //                Log.i(TAG, "Added access point " + mNewAccessPointFound.getName() + " to the known list.");
    //                mAccessPointsList.add(mNewAccessPointFound);
    //            }
    //        }
    //    }
    //}

    // Handler that receives messages from the thread
    private static class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }
    }

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        public ActiveScanningService getService() {
            // Return this instance of LocalService so clients can call public methods
            return ActiveScanningService.this;
        }
    }
}
