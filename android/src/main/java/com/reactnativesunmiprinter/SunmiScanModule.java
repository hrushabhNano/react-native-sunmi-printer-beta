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

/**
 * SunmiScanModule - React Native bridge for Sunmi scanner broadcast integration.
 * Ensures scanner operates in broadcast mode (no keyboard input injection).
 */
public class SunmiScanModule extends ReactContextBaseJavaModule {
  private static ReactApplicationContext reactContext;
  private static final int START_SCAN = 0x1001;
  private static final String E_ACTIVITY_DOES_NOT_EXIST = "E_ACTIVITY_DOES_NOT_EXIST";
  private static final String E_FAILED_TO_SHOW_SCAN = "E_FAILED_TO_SHOW_SCAN";
  private static final String ACTION_DATA_CODE_RECEIVED = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED";
  private static final String DATA = "data";
  private static final String SOURCE = "source_byte";

  private Promise mPickerPromise;

  private final BroadcastReceiver receiver = new BroadcastReceiver() {
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
      if (requestCode != START_SCAN) {
        return;
      }

      if (resultCode != Activity.RESULT_OK || intent == null) {
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
        for (HashMap<String, String> hashMap : result) {
          Object value = hashMap.get("VALUE");
          if (value != null) {
            sendEvent(value.toString());
          }
        }
      }

      if (mPickerPromise != null) {
        mPickerPromise.resolve(true);
        mPickerPromise = null;
      }
    }
  };

  public SunmiScanModule(ReactApplicationContext context) {
    super(context);
    reactContext = context;
    reactContext.addActivityEventListener(mActivityEventListener);

    // ensure scanner works in broadcast mode
    setScannerToBroadcastMode();

    registerReceiver();
  }

  @Override
  public String getName() {
    return "SunmiScanModule";
  }

  /**
   * Starts the Sunmi scan activity explicitly.
   */
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

  /**
   * Programmatically sets Sunmi scanner to broadcast mode (disables keyboard input).
   */
  private void setScannerToBroadcastMode() {
    try {
      Intent intent = new Intent();
      intent.setAction("com.sunmi.scanner.SET_SCAN_MODE");
      intent.putExtra("scan_mode", 1); // 1 = broadcast, 0 = keyboard
      reactContext.sendBroadcast(intent);
      Log.i(getName(), "Scanner mode set to broadcast.");
    } catch (Exception e) {
      Log.w(getName(), "Unable to set scanner mode: " + e.getMessage());
    }
  }

  /**
   * Registers the receiver for scan broadcasts.
   */
  private void registerReceiver() {
    IntentFilter filter = new IntentFilter();
    filter.addAction(ACTION_DATA_CODE_RECEIVED);

    try {
      if (Build.VERSION.SDK_INT >= 34) {
        int flags = Context.RECEIVER_NOT_EXPORTED;
        ContextCompat.registerReceiver(reactContext, receiver, filter, flags);
      } else {
        reactContext.registerReceiver(receiver, filter);
      }
      Log.i(getName(), "Receiver registered for ACTION_DATA_CODE_RECEIVED");
    } catch (Exception e) {
      try {
        reactContext.registerReceiver(receiver, filter);
      } catch (Exception ex) {
        Log.w(getName(), "Failed to register receiver: " + ex.getMessage());
      }
    }
  }

  /**
   * Cleans up to avoid memory leaks.
   */
  @Override
  public void onCatalystInstanceDestroy() {
    try {
      reactContext.unregisterReceiver(receiver);
    } catch (Exception e) {
      Log.w(getName(), "Receiver already unregistered");
    }
    try {
      reactContext.removeActivityEventListener(mActivityEventListener);
    } catch (Exception e) {
      // ignore
    }
    super.onCatalystInstanceDestroy();
  }

  /**
   * Sends an event to JS side (DeviceEventEmitter)
   */
  private static void sendEvent(String msg) {
    if (reactContext == null) return;
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
      .emit("onScanSuccess", msg);
  }
}
