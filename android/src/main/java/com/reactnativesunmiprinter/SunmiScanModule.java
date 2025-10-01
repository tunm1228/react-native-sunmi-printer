package com.reactnativesunmiprinter;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
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
import android.os.Build;

public class SunmiScanModule extends ReactContextBaseJavaModule {
  private static ReactApplicationContext reactContext;

  // ĐỪNG dùng 0x0000 – rất dễ va chạm. Dùng một mã riêng ổn định.
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
      if (intent == null) return;
      final String action = intent.getAction();
      if (ACTION_DATA_CODE_RECEIVED.equals(action)) {
        final String code = intent.getStringExtra(DATA);
        // byte[] arr = intent.getByteArrayExtra(SOURCE); // nếu cần
        if (code != null && !code.isEmpty()) {
          sendEvent(code);
          // Nếu đang chờ Promise (flow scan bằng broadcast), resolve luôn để JS biết đã xong
          if (mPickerPromise != null) {
            mPickerPromise.resolve(code);
            mPickerPromise = null;
          }
        }
      }
    }
  };

  private final ActivityEventListener mActivityEventListener = new BaseActivityEventListener() {
    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent intent) {
      try {
        // 1) Không phải request do module này tạo -> BỎ, tránh va chạm với ImageCropPicker
        if (requestCode != START_SCAN) return;

        // 2) Không OK hoặc intent null -> kết thúc êm, không crash
        if (resultCode != Activity.RESULT_OK || intent == null) {
          if (mPickerPromise != null) {
            mPickerPromise.reject(E_FAILED_TO_SHOW_SCAN, "Scan canceled or no data");
            mPickerPromise = null;
          }
          return;
        }

        // 3) Một số máy trả qua action
        if (ACTION_DATA_CODE_RECEIVED.equals(intent.getAction())) {
          final String code = intent.getStringExtra(DATA);
          if (code != null && !code.isEmpty()) {
            sendEvent(code);
            if (mPickerPromise != null) {
              mPickerPromise.resolve(code);
              mPickerPromise = null;
            }
          }
          return;
        }

        // 4) Hoặc trả qua extras – PHẢI check null kỹ, và ưu tiên getString
        final Bundle extras = intent.getExtras();
        if (extras != null) {
          // Nhiều ROM đưa mã vào key "data" dạng String
          final String codeStr = extras.getString(DATA);
          if (codeStr != null && !codeStr.isEmpty()) {
            sendEvent(codeStr);
            if (mPickerPromise != null) {
              mPickerPromise.resolve(codeStr);
              mPickerPromise = null;
            }
            return;
          }

          // Một số ROM cũ trả về danh sách map trong "data" – cần kiểm tra kiểu an toàn
          Object serial = extras.get("data");
          if (serial instanceof ArrayList) {
            ArrayList list = (ArrayList) serial;
            for (Object item : list) {
              if (item instanceof HashMap) {
                Object value = ((HashMap) item).get("VALUE");
                if (value != null) {
                  String v = String.valueOf(value);
                  sendEvent(v);
                  if (mPickerPromise != null) {
                    mPickerPromise.resolve(v);
                    mPickerPromise = null;
                  }
                  // nếu nhiều mã, có thể phát tất cả; ở đây resolve mã đầu tiên
                  return;
                }
              }
            }
          }
        }

        // 5) Nếu không lấy được gì, reject nhẹ nhàng
        if (mPickerPromise != null) {
          mPickerPromise.reject(E_FAILED_TO_SHOW_SCAN, "No scan data in result");
          mPickerPromise = null;
        }
      } catch (Exception e) {
        // Không để app crash vì scan
        if (mPickerPromise != null) {
          mPickerPromise.reject(E_FAILED_TO_SHOW_SCAN, e);
          mPickerPromise = null;
        }
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      reactContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
    } else {
      reactContext.registerReceiver(receiver, filter);
    }
  }

  @Override
  public void onCatalystInstanceDestroy() {
    // tránh memory leak
    try {
      reactContext.unregisterReceiver(receiver);
    } catch (Exception ignore) {}
    super.onCatalystInstanceDestroy();
  }

  private static void sendEvent(String msg) {
    if (reactContext == null) return;
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
      .emit("onScanSuccess", msg);
  }
}
