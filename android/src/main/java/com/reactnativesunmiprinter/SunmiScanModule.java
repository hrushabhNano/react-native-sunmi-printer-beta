package com.reactnativesunmiprinter;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import androidx.core.content.ContextCompat;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

public class SunmiScanModule extends ReactContextBaseJavaModule {
  private static ReactApplicationContext reactContext;
  // choose a request code unlikely to conflict with others
  private static final int START_SCAN = 0x1001;
  private static final String E_ACTIVITY_DOES_NOT_EXIST = "E_ACTIVITY_DOES_NOT_EXIST";
  private static final String E_FAILED_TO_SHOW_SCAN = "E_FAILED_TO_SHOW_SCAN";
  private static final String ACTION_DATA_CODE_RECEIVED = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED";
  private static final String DATA = "data";
  private static final String SOURCE = "source_byte";
  private Promise mPickerPromise;

  private BroadcastReceiver receiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      if (ACTION_DATA_CODE_RECEIVED.equals(action)) {
        String code = intent.getStringExtra(DATA);
        byte[] arr = intent.getByteArrayExtra(SOURCE);
        if (code != null && !code.isEmpty()) {
          sendEvent(code);
        }
      }
    }
  };

  private final ActivityEventListener mActivityEventListener = new BaseActivityEventListener() {
    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent intent) {
      // --- IMPORTANT: only handle results for our START_SCAN requestCode ---
      if (requestCode != START_SCAN) {
        // not our scan result — ignore so other modules (camera/pickers) receive their results
        return;
      }

      // optional: only proceed if result OK and intent not null
      if (resultCode != Activity.RESULT_OK || intent == null) {
        // optionally reject promise if you want to notify JS of cancellation
        if (mPickerPromise != null) {
          mPickerPromise.reject(E_FAILED_TO_SHOW_SCAN, "Scan cancelled or failed");
          mPickerPromise = null;
        }
        return;
      }

      Bundle bundle = intent.getExtras();
      if (bundle == null) {
        if (mPickerPromise != null) {
          mPickerPromise.reject(E_FAILED_TO_SHOW_SCAN, "No result bundle");
          mPickerPromise = null;
        }
        return;
      }

      ArrayList<HashMap<String, String>> result =
          (ArrayList<HashMap<String, String>>) bundle.getSerializable("data");
      if (result != null) {
        Iterator<HashMap<String, String>> it = result.iterator();
        while (it.hasNext()) {
          HashMap hashMap = it.next();
          Object value = hashMap.get("VALUE");
          if (value != null) {
            sendEvent(value.toString());
          }
        }
      }

      if (mPickerPromise != null) {
        // resolve or keep behavior as emit-only. Example: resolve with success true
        mPickerPromise.resolve(true);
        mPickerPromise = null;
      }
    }
  };

  public SunmiScanModule(ReactApplicationContext context) {
    super(context);
    reactContext = context;
    reactContext.addActivityEventListener(mActivityEventListener);
    registerReceiver();
  }

  @Override
  public String getName() {
    return "SunmiScanModule";
  }

  @ReactMethod
  public void scan(final Promise promise) {
    Activity currentActivity = getCurrentActivity();
    if (currentActivity == null) {
      promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
      return;
    }
    mPickerPromise = promise;
    try {
      Intent intent = new Intent("com.sunmi.scan");
      intent.setPackage("com.sunmi.sunmiqrcodescanner");
      intent.putExtra("PLAY_SOUND", true);
      currentActivity.startActivityForResult(intent, START_SCAN);
    } catch (Exception e) {
      if (mPickerPromise != null) {
        mPickerPromise.reject(E_FAILED_TO_SHOW_SCAN, e);
        mPickerPromise = null;
      }
    }
  }

  private void registerReceiver() {
    IntentFilter filter = new IntentFilter();
    filter.addAction(ACTION_DATA_CODE_RECEIVED);

    try {
      // Android 14 (API 34) requires explicit receiver exported/not exported flag when context-registering
      if (Build.VERSION.SDK_INT >= 34) {
        // choose exported or not exported based on your desired exposure;
        // many apps should use RECEIVER_NOT_EXPORTED to limit broadcasts to the app only.
        int flags = Context.RECEIVER_NOT_EXPORTED; // or Context.RECEIVER_EXPORTED if you want exports
        ContextCompat.registerReceiver(reactContext, receiver, filter, flags);
      } else {
        // older OS: simple register (flags param not required)
        reactContext.registerReceiver(receiver, filter);
      }
    } catch (Exception e) {
      // fallback: attempt plain register (defensive)
      try {
        reactContext.registerReceiver(receiver, filter);
      } catch (Exception ex) {
        // log but don't crash the app
        Log.w(getName(), "Failed to register receiver: " + ex.getMessage());
      }
    }
  }

  // cleanup to avoid leaks
  @Override
  public void onCatalystInstanceDestroy() {
    try {
      reactContext.unregisterReceiver(receiver);
    } catch (Exception e) {
      // ignore if already unregistered / not registered
    }
    try {
      reactContext.removeActivityEventListener(mActivityEventListener);
    } catch (Exception e) {
      // ignore
    }
    super.onCatalystInstanceDestroy();
  }

  private static void sendEvent(String msg) {
    if (reactContext == null) return;
    reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("onScanSuccess", msg);
  }
}
