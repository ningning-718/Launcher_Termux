package org.sharpai.aicamera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

import static android.content.Context.SENSOR_SERVICE;
import static android.graphics.Color.BLACK;
import static android.graphics.Color.WHITE;

/**
 * Created by jerikc on 15/10/6.
 */
public class CameraControl {

    private static final String TAG = "CameraControl";

    private static final int PERMISSIONS_REQUEST = 1;

    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    private static final String PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;

    private Camera mCamera;
    private CameraPreview mPreview;
    private Camera.CameraInfo mCameraInfo;

    private long mLastNotifyStamp = System.currentTimeMillis();
    private boolean mNotifyPopupShown = false;
    private PopupWindow mNotifyPopupWindow = null;

    private Activity mActivity;
    private FrameLayout mFramePreview;
    private ImageView mQRCodeImage;
    private ImageView mPersonView;
    private ImageView mFaceView;

    public CameraControl(Activity activity,FrameLayout framePreview,ImageView qrCodeView,
                         ImageView detectedPersonView ,ImageView detectedFaceView){
        mActivity = activity;
        mFramePreview = framePreview;
        mQRCodeImage = qrCodeView;
        mPersonView = detectedPersonView;
        mFaceView = detectedFaceView;

        mActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        //mActivity.setContentView(R.layout.activity_main);
        if (hasPermission()) {
            setFragment();
        } else {
            requestPermission();
        }

    }

    /** A safe way to get an instance of the Camera object. */
    public Camera getCameraInstance(){
        Camera c = null;
        try {
            c = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT); // attempt to get a Camera instance
        }
        catch (Exception e){
            // Camera is not available (in use or does not exist)
            e.printStackTrace();
        }
        mCameraInfo = new Camera.CameraInfo();
        Camera.getCameraInfo(Camera.CameraInfo.CAMERA_FACING_FRONT, mCameraInfo);

        Camera.Parameters camParams = c.getParameters();

        camParams.set("iso-speed", 400);
        c.setParameters(camParams);

        /*camParams = c.getParameters();
        String pams = camParams.flatten();
        String supportedIsoValues = camParams.get("iso-values");*/

        return c; // returns null if camera is unavailable
    }

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
            UDID = Settings.Secure.getString(mActivity.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
        }
        if (UDID == null || UDID.length() == 0) {
            UDID = "0000000";
        }

        return UDID.toLowerCase();
    }
    private void setFragment(){
        // Create an instance of Camera
        mCamera = getCameraInstance();

        // Create our Preview view and set it as the content of our activity.
        mPreview = new CameraPreview(mActivity, mCamera, mCameraInfo,mPersonView,mFaceView);
        //FrameLayout preview = (FrameLayout) mActivity.findViewById(R.id.camera_preview);
        mFramePreview.addView(mPreview);

        //ImageView imageView =  mActivity.findViewById(R.id.qrcode_view);
        try {
            // generate a 150x150 QR code
            Bitmap bm = encodeAsBitmap(getUniqueSerialNO(), 150, 150);

            if(bm != null) {
                mQRCodeImage.setImageBitmap(bm);
            }
            //preview.addView(imageView);
        } catch (WriterException e) {
            e.printStackTrace();
        }
    }

    Bitmap encodeAsBitmap(String str,int width,int height) throws WriterException {
        BitMatrix result;
        try {
            result = new MultiFormatWriter().encode(str,
                    BarcodeFormat.QR_CODE, width, height, null);
        } catch (IllegalArgumentException iae) {
            // Unsupported format
            return null;
        }
        int w = result.getWidth();
        int h = result.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int offset = y * w;
            for (int x = 0; x < w; x++) {
                pixels[offset + x] = result.get(x, y) ? BLACK : WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, w, h);
        return bitmap;
    }

    protected void onResume() {
        if (mCamera == null) {
            mCamera = getCameraInstance();
        }

        SensorManager sm = (SensorManager)mActivity.getSystemService(SENSOR_SERVICE);
        Sensor accelerator = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        //sm.registerListener(mSel, accelerator, SensorManager.SENSOR_DELAY_UI);
    }

    protected void onPause() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
        mActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SensorManager sm = (SensorManager)mActivity.getSystemService(SENSOR_SERVICE);
        //sm.unregisterListener(mSel);
    }
    public void stop(){
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }

        SensorManager sm = (SensorManager)mActivity.getSystemService(SENSOR_SERVICE);
        Sensor accelerator = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public void onRequestPermissionsResult(
            final int requestCode, final String[] permissions, final int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                setFragment();
            } else {
                requestPermission();
            }
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return mActivity.checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED &&
                mActivity.checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (mActivity.shouldShowRequestPermissionRationale(PERMISSION_CAMERA) ||
                mActivity.shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
                Toast.makeText(mActivity,
                        "Camera AND storage permission are required for this demo", Toast.LENGTH_LONG).show();
            }
            mActivity.requestPermissions(new String[] {PERMISSION_CAMERA, PERMISSION_STORAGE}, PERMISSIONS_REQUEST);
        }
    }
}
