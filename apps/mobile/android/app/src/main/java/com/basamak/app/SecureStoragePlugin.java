package com.basamak.app;

import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Refresh and remembered-device tokens in EncryptedSharedPreferences: values are AES-256-GCM encrypted with a key
 * that lives in the Android Keystore (hardware-backed where available) and never leaves it. allowBackup=false
 * keeps the file out of cloud/adb backups as well.
 */
@CapacitorPlugin(name = "SecureStorage")
public class SecureStoragePlugin extends Plugin {
    private SharedPreferences prefs;

    private SharedPreferences prefs() throws Exception {
        if (prefs == null) {
            MasterKey key = new MasterKey.Builder(getContext()).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            prefs = EncryptedSharedPreferences.create(getContext(), "basamak_secure", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        }
        return prefs;
    }

    @PluginMethod
    public void get(PluginCall call) {
        try {
            JSObject r = new JSObject();
            r.put("value", prefs().getString(call.getString("key"), null));
            call.resolve(r);
        } catch (Exception e) {
            call.reject("secure storage unavailable", e);
        }
    }

    @PluginMethod
    public void set(PluginCall call) {
        try {
            prefs().edit().putString(call.getString("key"), call.getString("value")).apply();
            call.resolve();
        } catch (Exception e) {
            call.reject("secure storage unavailable", e);
        }
    }

    @PluginMethod
    public void remove(PluginCall call) {
        try {
            prefs().edit().remove(call.getString("key")).apply();
            call.resolve();
        } catch (Exception e) {
            call.reject("secure storage unavailable", e);
        }
    }
}
