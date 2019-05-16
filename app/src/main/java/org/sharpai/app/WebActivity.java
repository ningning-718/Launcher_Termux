package org.sharpai.app;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.sharpai.termux.R;

import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class WebActivity extends Activity {

    public static final String URL_BASE = "http://192.168.31.148:3002/visitor/devices/";

    private static String getMacAddr() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : all) {
                if ( !nif.getName().equalsIgnoreCase("eth0") &&
                    !nif.getName().equalsIgnoreCase("wlan0")) continue;

                byte[] macBytes = nif.getHardwareAddress();
                if (macBytes == null) {
                    return "";
                }

                StringBuilder res1 = new StringBuilder();
                for (byte b : macBytes) {
                    // res1.append(Integer.toHexString(b & 0xFF) + ":");
                    res1.append(String.format("%02X",b));
                }

                //if (res1.length() > 0) {
                //    res1.deleteCharAt(res1.length() - 1);
                //}
                return res1.toString();
            }
        } catch (Exception ex) {
            //handle exception
        }
        return "";
    }
    private String getUniqueSerialNO(){
        String UDID = getMacAddr();
        if (UDID == null || UDID.length() == 0) {
            UDID = Settings.Secure.getString(this.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        }
        if (UDID == null || UDID.length() == 0) {
            UDID = "0000000";
        }

        return UDID.toLowerCase();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web);

        WebView wv_sharpai = (WebView)findViewById(R.id.wv_sharpai);
        wv_sharpai.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        wv_sharpai.getSettings().setJavaScriptEnabled(true);
        wv_sharpai.getSettings().setUseWideViewPort(true);
        wv_sharpai.getSettings().setLoadWithOverviewMode(true);
        wv_sharpai.getSettings().setAppCacheEnabled(true);
        wv_sharpai.getSettings().setDomStorageEnabled(true);
        wv_sharpai.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });
        String uuid = getUniqueSerialNO();
        if (!TextUtils.isEmpty(uuid)) {
            wv_sharpai.loadUrl(URL_BASE + uuid);
        }
    }
}


