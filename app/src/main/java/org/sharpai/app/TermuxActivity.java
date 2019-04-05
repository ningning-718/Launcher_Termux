package org.sharpai.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Vibrator;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import org.sharpai.termux.R;
import com.termux.terminal.EmulatorDebug;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSession.SessionChangedCallback;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalView;

import org.sharpai.aicamera.CameraControl;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.graphics.Color.BLACK;
import static android.graphics.Color.WHITE;
import static org.sharpai.app.TermuxInstaller.determineTermuxArchName;
import static org.sharpai.app.TermuxService.FILES_PATH;
import static org.sharpai.app.TermuxService.HOME_PATH;
import static org.sharpai.app.TermuxService.PREFIX_PATH;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends Activity implements ServiceConnection {

    private static final int CONTEXTMENU_SELECT_URL_ID = 0;
    private static final int CONTEXTMENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXTMENU_PASTE_ID = 3;
    private static final int CONTEXTMENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXTMENU_RESET_TERMINAL_ID = 5;
    private static final int CONTEXTMENU_STYLING_ID = 6;
    private static final int CONTEXTMENU_HELP_ID = 8;

    private static final int MAX_SESSIONS = 8;

    private static final int REQUESTCODE_PERMISSION_STORAGE = 1234;

    private static final String RELOAD_STYLE_ACTION = "org.sharpai.app.reload_style";

    /** The main view of the activity showing the terminal. Initialized in onCreate(). */
    @SuppressWarnings("NullableProblems")
    @NonNull
    TerminalView mTerminalView;

    ExtraKeysView mExtraKeysView;

    TermuxPreferences mSettings;

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermService;

    /** Initialized in {@link #onServiceConnected(ComponentName, IBinder)}. */
    ArrayAdapter<TerminalSession> mListViewAdapter;

    /** The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}. */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    boolean mIsVisible;

    final SoundPool mBellSoundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build();
    int mBellSoundId;

    public static final String DEEPCAMERA_DEV_ALL_IN_ONE_DOWNLOAD_URL = "https://github.com/SharpAI/DeepCamera/releases/download/1.2/DeepCamera_Dev_All_In_One_03262019.bz2";

    CameraControl mCameraControl;

    /**
     * Async Task to download file from URL
     */
    private class DownloadFile extends AsyncTask<String, String, String> {

        private ProgressDialog progressDialog;
        private String fileName;
        private String folder;
        private boolean isDownloaded;
        private Activity activity = null;

        public DownloadFile(final Activity activity) {
            super();
            this.activity = activity;
        }

        /**
         * Before starting background thread
         * Show Progress Bar Dialog
         */
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            this.progressDialog = new ProgressDialog(activity);
            this.progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            this.progressDialog.setCancelable(false);
            this.progressDialog.show();
        }

        /**
         * Downloading file in background thread
         */
        @Override
        protected String doInBackground(String... f_url) {
            int count;
            try {
                URL url = new URL(f_url[0]);
                URLConnection connection = url.openConnection();
                connection.connect();
                // getting file length
                int lengthOfFile = connection.getContentLength();

                // input stream to read file - with 8k buffer
                InputStream input = new BufferedInputStream(url.openStream(), 8192);

                //Extract file name from URL
                fileName = f_url[0].substring(f_url[0].lastIndexOf('/') + 1, f_url[0].length());

                //External directory path to save file
                folder = Environment.getExternalStorageDirectory() + File.separator + "sharpaidownload/";

                //Create androiddeft folder if it does not exist
                File directory = new File(folder);

                if (!directory.exists()) {
                    directory.mkdirs();
                }

                // Output stream to write file
                OutputStream output = new FileOutputStream(folder + fileName);

                byte data[] = new byte[1024];

                long total = 0;

                while ((count = input.read(data)) != -1) {
                    total += count;
                    // publishing the progress....
                    // After this onProgressUpdate will be called
                    publishProgress("" + (int) ((total * 100) / lengthOfFile));
                    Log.d("sharpai", "Progress: " + (int) ((total * 100) / lengthOfFile));

                    // writing data to file
                    output.write(data, 0, count);
                }

                // flushing output
                output.flush();

                // closing streams
                output.close();
                input.close();
                return "" + folder + fileName;

            } catch (Exception e) {
                Log.e("Error: ", e.getMessage());
            }
            return "Something went wrong";

            //Debug only code:
            //Extract file name from URL
            //fileName = f_url[0].substring(f_url[0].lastIndexOf('/') + 1, f_url[0].length());

            //External directory path to save file
            //folder = Environment.getExternalStorageDirectory() + File.separator + "sharpaidownload/";
            //return "" + folder + fileName;
        }

        /**
         * Updating progress bar
         */
        protected void onProgressUpdate(String... progress) {
            // setting progress percentage
            progressDialog.setProgress(Integer.parseInt(progress[0]));
        }


        @Override
        protected void onPostExecute(String downloadedFile) {
            // dismiss the dialog after the file was downloaded
            this.progressDialog.dismiss();

            // Display File path after downloading
            Toast.makeText(activity,
                "downloaded to " + downloadedFile, Toast.LENGTH_LONG).show();
            extractDownloadedFile(downloadedFile);
        }

        private void extractDownloadedFile(String downloadedFile) {
            final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.sharpai_extract_body), true, false);
            new Thread() {
                @Override
                public void run() {
                    //Create androiddeft folder if it does not exist
                    String termuxRootDir = TermuxService.FILES_PATH+"/termuxroot/";
                    File directory = new File(termuxRootDir);

                    if (!directory.exists()) {
                        directory.mkdirs();
                    }
                    try {
                        try {
                            String tarCmd = TermuxService.FILES_PATH+"/usr/bin/busybox tar -xmf " + downloadedFile + " -C " + TermuxService.FILES_PATH+"/termuxroot/" + "/\n";
                            Process untar = Runtime.getRuntime().exec(tarCmd);
                            DataOutputStream outputStream = new DataOutputStream(untar.getOutputStream());

                            outputStream.writeBytes("exit\n");
                            outputStream.flush();
                            untar.waitFor();
                        }catch(IOException e){
                            e.printStackTrace();
                            //TermuxInstaller.deleteFile(downloadedFile);
                            throw new Exception(e);
                        }catch(InterruptedException e){
                            e.printStackTrace();
                            //TermuxInstaller.deleteFile(downloadedFile);
                            throw new Exception(e);
                        } finally {
                            //TermuxInstaller.deleteFile(downloadedFile);
                            Log.i(EmulatorDebug.LOG_TAG, "Decompress of deep camera dev runtime done");
                            activity.runOnUiThread(() -> {
                                try {
                                    Toast.makeText(activity,R.string.decompress_success_text, Toast.LENGTH_LONG).show();
                                    askIfRunDeepCameraService();
                                } catch (WindowManager.BadTokenException e1) {
                                    // Activity already dismissed - ignore.
                                }
                            });
                        };
                    } catch (final Exception e) {
                        e.printStackTrace();
                        Log.e(EmulatorDebug.LOG_TAG, "Error: Decompress of deep camera dev runtime");
                        activity.runOnUiThread(() -> {
                            try {
                                Toast.makeText(activity,
                                    "Can't decompress file " + downloadedFile + ".", Toast.LENGTH_LONG).show();
                            } catch (WindowManager.BadTokenException e1) {
                                // Activity already dismissed - ignore.
                            }
                        });
                    } finally {
                        activity.runOnUiThread(() -> {
                            try {
                                progress.dismiss();
                            } catch (RuntimeException e) {
                                // Activity already dismissed - ignore.
                            }
                        });
                    };
                }
            }.start();
        }
    }

    public class SharpAIRunnable implements Runnable {
        public final String CWD = FILES_PATH +"/termuxroot";
        public final String SCRIPT_PATH = FILES_PATH+"/usr/bin/bash";
        private String scriptPath = null;

        public SharpAIRunnable(String path) {
            this.scriptPath = path;
        }
        @Override
        public void run() {
            final Uri scriptUri = new Uri.Builder().scheme("file").path(SCRIPT_PATH).build();
            Intent executeIntent = new Intent(TermuxService.ACTION_EXECUTE, scriptUri);
            executeIntent.setClass(TermuxActivity.this, TermuxService.class);
            executeIntent.putExtra(TermuxService.EXTRA_ARGUMENTS, new String[]{scriptPath});
            executeIntent.putExtra(TermuxService.EXTRA_CURRENT_WORKING_DIRECTORY, CWD);
            executeIntent.putExtra(TermuxService.EXTRA_EXECUTE_IN_BACKGROUND, true);

            startService(executeIntent);
        }
    };

    Handler sharpAIHandler = new Handler();

    private final BroadcastReceiver mBroadcastReceiever = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mIsVisible) {
                String whatToReload = intent.getStringExtra(RELOAD_STYLE_ACTION);
                if ("storage".equals(whatToReload)) {
                    if (ensureStoragePermissionGranted())
                        TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                    return;
                }
                checkForFontAndColors();
                mSettings.reloadFromProperties(TermuxActivity.this);
            }
        }
    };

    void checkForFontAndColors() {
        try {
            @SuppressLint("SdCardPath") File fontFile = new File(HOME_PATH + "/.termux/font.ttf");
            @SuppressLint("SdCardPath") File colorsFile = new File(HOME_PATH + "/.termux/colors.properties");

            final Properties props = new Properties();
            if (colorsFile.isFile()) {
                try (InputStream in = new FileInputStream(colorsFile)) {
                    props.load(in);
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(props);
            TerminalSession session = getCurrentTermSession();
            if (session != null && session.getEmulator() != null) {
                session.getEmulator().mColors.reset();
            }
            updateBackgroundColor();

            final Typeface newTypeface = (fontFile.exists() && fontFile.length() > 0) ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
            mTerminalView.setTypeface(newTypeface);
        } catch (Exception e) {
            Log.e(EmulatorDebug.LOG_TAG, "Error in checkForFontAndColors()", e);
        }
    }

    void updateBackgroundColor() {
        TerminalSession session = getCurrentTermSession();
        if (session != null && session.getEmulator() != null) {
            getWindow().getDecorView().setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        }
    }

    /** For processes to access shared internal storage (/sdcard) we need this permission. */
    @TargetApi(Build.VERSION_CODES.M)
    public boolean ensureStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                return true;
            } else {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUESTCODE_PERMISSION_STORAGE);
                return false;
            }
        } else {
            // Always granted before Android 6.0.
            return true;
        }
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        mSettings = new TermuxPreferences(this);

        setContentView(R.layout.drawer_layout);
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setOnKeyListener(new TermuxViewClient(this));

        mTerminalView.setTextSize(mSettings.getFontSize());

        final ViewPager viewPager = findViewById(R.id.viewpager);
        if (mSettings.isShowExtraKeys()) viewPager.setVisibility(View.VISIBLE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ImageView imageView =  findViewById(R.id.qrcode_view);
        try {
            // generate a 150x150 QR code
            Bitmap bm = encodeAsBitmap(getUniqueSerialNO(), 150, 150);

            if(bm != null) {
                imageView.setImageBitmap(bm);
            }
        } catch (WriterException e) {
            e.printStackTrace();
        }
        Button buttonStop = findViewById(R.id.btn_stop);
        buttonStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view)
            {
                // Do something
                SharpAIRunnable startRunnableStop = new SharpAIRunnable(HOME_PATH+"/DeepCamera/stop_all.sh");
                sharpAIHandler.postDelayed(startRunnableStop, 100);
                stopCameraPreview();
            }
        });

        Button buttonStart = findViewById(R.id.btn_start);
        buttonStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view)
            {
                // Do something
                SharpAIRunnable startRunnableStart = new SharpAIRunnable(HOME_PATH+"/DeepCamera/start_service.sh");
                sharpAIHandler.postDelayed(startRunnableStart, 100);
                startCameraPreview();
            }
        });
        buttonStart.setVisibility(View.GONE);

        ViewGroup.LayoutParams layoutParams = viewPager.getLayoutParams();
        layoutParams.height = layoutParams.height * mSettings.mExtraKeys.length;
        viewPager.setLayoutParams(layoutParams);

        viewPager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return 2;
            }

            @Override
            public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                return view == object;
            }

            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup collection, int position) {
                LayoutInflater inflater = LayoutInflater.from(TermuxActivity.this);
                View layout;
                if (position == 0) {
                    layout = mExtraKeysView = (ExtraKeysView) inflater.inflate(R.layout.extra_keys_main, collection, false);
                    mExtraKeysView.reload(mSettings.mExtraKeys, ExtraKeysView.defaultCharDisplay);
                } else {
                    layout = inflater.inflate(R.layout.extra_keys_right, collection, false);
                    final EditText editText = layout.findViewById(R.id.text_input);
                    editText.setOnEditorActionListener((v, actionId, event) -> {
                        TerminalSession session = getCurrentTermSession();
                        if (session != null) {
                            if (session.isRunning()) {
                                String textToSend = editText.getText().toString();
                                if (textToSend.length() == 0) textToSend = "\n";
                                session.write(textToSend);
                            } else {
                                removeFinishedSession(session);
                            }
                            editText.setText("");
                        }
                        return true;
                    });
                }
                collection.addView(layout);
                return layout;
            }

            @Override
            public void destroyItem(@NonNull ViewGroup collection, int position, @NonNull Object view) {
                collection.removeView((View) view);
            }
        });

        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (position == 0) {
                    mTerminalView.requestFocus();
                } else {
                    final EditText editText = viewPager.findViewById(R.id.text_input);
                    if (editText != null) editText.requestFocus();
                }
            }
        });

        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            DialogUtils.textInput(TermuxActivity.this, R.string.session_new_named_title, null, R.string.session_new_named_positive_button,
                text -> addNewSession(false, text), R.string.new_session_failsafe, text -> addNewSession(true, text)
                , -1, null, null);
            return true;
        });

        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleShowExtraKeys();
            return true;
        });

        registerForContextMenu(mTerminalView);

        Intent serviceIntent = new Intent(this, TermuxService.class);
        // Start the service and make it run regardless of who is bound to it:
        startService(serviceIntent);
        if (!bindService(serviceIntent, this, 0))
            throw new RuntimeException("bindService() failed");

        checkForFontAndColors();

        mBellSoundId = mBellSoundPool.load(this, R.raw.bell, 1);

        String archName = determineTermuxArchName();
        if(!archName.equals("aarch64")){
            showARM32Warning();
            return;
        }

        File openclUserland = new File("/system/vendor/lib64/libOpenCL.so");
        if(!openclUserland.exists()){
            showOpenCLWarningAndContinue();
        } else {
            startDeepCamera();
        }
        //startCameraPreview();
    }
    private void startCameraPreview(){
        FrameLayout preview = findViewById(R.id.camera_preview);
        ImageView imageView =  findViewById(R.id.qrcode_view);
        mCameraControl = new CameraControl(this,preview,imageView);
    }
    private void stopCameraPreview(){
        mCameraControl.stop();
    }
    private void startDeepCamera(){
        if(checkIfHasDeepCameraDevFile() == true){
            Log.d(EmulatorDebug.LOG_TAG,"we have development environment");
            askIfRunDeepCameraService();
            return;
        }
        else { //no dev env, ask if to download
            new AlertDialog.Builder(this).setTitle(R.string.sharpai_install_title).setMessage(R.string.sharpai_install_msg)
                .setNegativeButton(R.string.sharpai_dialog_abort, (dialog, which) -> {
                    dialog.dismiss();
                }).setPositiveButton(R.string.sharpai_dialog_ok, (dialog, which) -> {
                dialog.dismiss();
                installSharpAISystem();
            }).show();
        }
    }
    private void installSharpAISystem() {
        new DownloadFile(this).execute(DEEPCAMERA_DEV_ALL_IN_ONE_DOWNLOAD_URL);
    }
    private Boolean checkIfHasDeepCameraDevFile(){
        File file = new File(FILES_PATH+"/termuxroot/home/DeepCamera/start_service.sh");
        return file.exists();
    }
    private void askIfRunCameraPreview(){

        AlertDialog.Builder builder1 = new AlertDialog.Builder(TermuxActivity.this);
        builder1.setMessage(R.string.dialog_if_testing_with_builtin_camera_text);
        builder1.setCancelable(true);

        builder1.setPositiveButton(
            R.string.dialog_yes,
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    startCameraPreview();
                    dialog.cancel();
                }
            });

        builder1.setNegativeButton(
            R.string.dialog_no,
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    mTerminalView.requestFocus();
                    dialog.cancel();
                }
            });

        AlertDialog alert11 = builder1.create();
        alert11.show();

    }
    private void startDeepCameraService(){
        SharpAIRunnable startRunnable = new SharpAIRunnable(FILES_PATH+"/usr/bin/sharpai-start");
        sharpAIHandler.postDelayed(startRunnable, 100);
    }
    private void askIfRunDeepCameraService(){
        startDeepCameraService();
        askIfRunCameraPreview();
        /*
        AlertDialog.Builder builder1 = new AlertDialog.Builder(TermuxActivity.this);
        builder1.setMessage(R.string.dialog_if_run_text);
        builder1.setCancelable(true);

        builder1.setPositiveButton(
            R.string.dialog_yes,
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
dialog.cancel();
                    startDeepCameraService();
                    askIfRunCameraPreview();
                }
            });

        builder1.setNegativeButton(
            R.string.dialog_no,
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();
                }
            });

        AlertDialog alert11 = builder1.create();
        alert11.show();
        */
    }
    private void showOpenCLWarningAndContinue(){

        AlertDialog.Builder builder = new AlertDialog.Builder(TermuxActivity.this);
        builder.setMessage(R.string.opencl_warning_text);
        builder.setCancelable(false);

        builder.setPositiveButton(
            R.string.dialog_confirm,
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    Log.i(EmulatorDebug.LOG_TAG,"Yes for opencl warning");
                    startDeepCamera();
                    dialog.cancel();
                }
            });

        AlertDialog alert = builder.create();
        alert.show();
    }
    private void showARM32Warning(){
        /*
        final Dialog dialog = new Dialog(TermuxActivity.this); // Context, this, etc.
        dialog.setContentView(R.layout.dialog_arch_warning);
        dialog.setTitle(R.string.dialog_arch_warning_title);
        dialog.show();
        */

        AlertDialog.Builder builder = new AlertDialog.Builder(TermuxActivity.this);
        builder.setMessage(R.string.arch_warning_text);
        builder.setCancelable(false);

        builder.setPositiveButton(
            R.string.dialog_confirm,
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    Log.i(EmulatorDebug.LOG_TAG,"Yes for arch warning");
                    dialog.cancel();
                }
            });

        AlertDialog alert = builder.create();
        alert.show();
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
    void toggleShowExtraKeys() {
        final ViewPager viewPager = findViewById(R.id.viewpager);
        final boolean showNow = mSettings.toggleShowExtraKeys(TermuxActivity.this);
        viewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && viewPager.getCurrentItem() == 1) {
            // Focus the text input view if just revealed.
            findViewById(R.id.text_input).requestFocus();
        }
    }

    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        mTermService = ((TermuxService.LocalBinder) service).service;

        mTermService.mSessionChangeCallback = new SessionChangedCallback() {
            @Override
            public void onTextChanged(TerminalSession changedSession) {
                if (!mIsVisible) return;
                if (getCurrentTermSession() == changedSession) mTerminalView.onScreenUpdated();
            }

            @Override
            public void onTitleChanged(TerminalSession updatedSession) {
                if (!mIsVisible) return;
                if (updatedSession != getCurrentTermSession()) {
                    // Only show toast for other sessions than the current one, since the user
                    // probably consciously caused the title change to change in the current session
                    // and don't want an annoying toast for that.
                    showToast(toToastTitle(updatedSession), false);
                }
                mListViewAdapter.notifyDataSetChanged();
            }

            @Override
            public void onSessionFinished(final TerminalSession finishedSession) {
                if (mTermService.mWantsToStop) {
                    // The service wants to stop as soon as possible.
                    finish();
                    return;
                }
                if (mIsVisible && finishedSession != getCurrentTermSession()) {
                    // Show toast for non-current sessions that exit.
                    int indexOfSession = mTermService.getSessions().indexOf(finishedSession);
                    // Verify that session was not removed before we got told about it finishing:
                    if (indexOfSession >= 0)
                        showToast(toToastTitle(finishedSession) + " - exited", true);
                }

                if (mTermService.getSessions().size() > 1) {
                    removeFinishedSession(finishedSession);
                }

                mListViewAdapter.notifyDataSetChanged();
            }

            @Override
            public void onClipboardText(TerminalSession session, String text) {
                if (!mIsVisible) return;
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"}, new ClipData.Item(text)));
            }

            @Override
            public void onBell(TerminalSession session) {
                if (!mIsVisible) return;

                switch (mSettings.mBellBehaviour) {
                    case TermuxPreferences.BELL_BEEP:
                        mBellSoundPool.play(mBellSoundId, 1.f, 1.f, 1, 0, 1.f);
                        break;
                    case TermuxPreferences.BELL_VIBRATE:
                        ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(50);
                        break;
                    case TermuxPreferences.BELL_IGNORE:
                        // Ignore the bell character.
                        break;
                }

            }

            @Override
            public void onColorsChanged(TerminalSession changedSession) {
                if (getCurrentTermSession() == changedSession) updateBackgroundColor();
            }
        };

        ListView listView = findViewById(R.id.left_drawer_list);
        mListViewAdapter = new ArrayAdapter<TerminalSession>(getApplicationContext(), R.layout.line_in_drawer, mTermService.getSessions()) {
            final StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
            final StyleSpan italicSpan = new StyleSpan(Typeface.ITALIC);

            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View row = convertView;
                if (row == null) {
                    LayoutInflater inflater = getLayoutInflater();
                    row = inflater.inflate(R.layout.line_in_drawer, parent, false);
                }

                TerminalSession sessionAtRow = getItem(position);
                boolean sessionRunning = sessionAtRow.isRunning();

                TextView firstLineView = row.findViewById(R.id.row_line);

                String name = sessionAtRow.mSessionName;
                String sessionTitle = sessionAtRow.getTitle();

                String numberPart = "[" + (position + 1) + "] ";
                String sessionNamePart = (TextUtils.isEmpty(name) ? "" : name);
                String sessionTitlePart = (TextUtils.isEmpty(sessionTitle) ? "" : ((sessionNamePart.isEmpty() ? "" : "\n") + sessionTitle));

                String text = numberPart + sessionNamePart + sessionTitlePart;
                SpannableString styledText = new SpannableString(text);
                styledText.setSpan(boldSpan, 0, numberPart.length() + sessionNamePart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styledText.setSpan(italicSpan, numberPart.length() + sessionNamePart.length(), text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                firstLineView.setText(styledText);

                if (sessionRunning) {
                    firstLineView.setPaintFlags(firstLineView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                } else {
                    firstLineView.setPaintFlags(firstLineView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                }
                int color = sessionRunning || sessionAtRow.getExitStatus() == 0 ? BLACK : Color.RED;
                firstLineView.setTextColor(color);
                return row;
            }
        };
        listView.setAdapter(mListViewAdapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            TerminalSession clickedSession = mListViewAdapter.getItem(position);
            switchToSession(clickedSession);
            getDrawer().closeDrawers();
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            final TerminalSession selectedSession = mListViewAdapter.getItem(position);
            renameSession(selectedSession);
            return true;
        });

        if (mTermService.getSessions().isEmpty()) {
            if (mIsVisible) {
                if (ensureStoragePermissionGranted()){
                    TermuxInstaller.setupIfNeeded(TermuxActivity.this, () -> {
                        if (mTermService == null) return; // Activity might have been destroyed.
                        try {
                            addNewSession(false, null);
                        } catch (WindowManager.BadTokenException e) {
                            // Activity finished - ignore.
                        }
                    });
                }
            } else {
                // The service connected while not in foreground - just bail out.
                finish();
            }
        } else {
            Intent i = getIntent();
            if (i != null && Intent.ACTION_RUN.equals(i.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                addNewSession(false, null);
            } else {
                switchToSession(getStoredCurrentSessionOrLast());
            }
        }
    }

    public void switchToSession(boolean forward) {
        TerminalSession currentSession = getCurrentTermSession();
        int index = mTermService.getSessions().indexOf(currentSession);
        if (forward) {
            if (++index >= mTermService.getSessions().size()) index = 0;
        } else {
            if (--index < 0) index = mTermService.getSessions().size() - 1;
        }
        switchToSession(mTermService.getSessions().get(index));
    }

    @SuppressLint("InflateParams")
    void renameSession(final TerminalSession sessionToRename) {
        DialogUtils.textInput(this, R.string.session_rename_title, sessionToRename.mSessionName, R.string.session_rename_positive_button, text -> {
            sessionToRename.mSessionName = text;
            mListViewAdapter.notifyDataSetChanged();
        }, -1, null, -1, null, null);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // Respect being stopped from the TermuxService notification action.
        finish();
    }

    @Nullable
    TerminalSession getCurrentTermSession() {
        return mTerminalView.getCurrentSession();
    }

    @Override
    public void onStart() {
        super.onStart();
        mIsVisible = true;

        if (mTermService != null) {
            // The service has connected, but data may have changed since we were last in the foreground.
            switchToSession(getStoredCurrentSessionOrLast());
            mListViewAdapter.notifyDataSetChanged();
        }

        registerReceiver(mBroadcastReceiever, new IntentFilter(RELOAD_STYLE_ACTION));

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal:
        mTerminalView.onScreenUpdated();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mIsVisible = false;
        TerminalSession currentSession = getCurrentTermSession();
        if (currentSession != null) TermuxPreferences.storeCurrentSession(this, currentSession);
        unregisterReceiver(mBroadcastReceiever);
        getDrawer().closeDrawers();
    }

    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else {
            finish();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mTermService != null) {
            // Do not leave service with references to activity.
            mTermService.mSessionChangeCallback = null;
            mTermService = null;
        }
        unbindService(this);
    }

    DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
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
            UDID = Settings.Secure.getString(this.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        }
        if (UDID == null || UDID.length() == 0) {
            UDID = "0000000";
        }

        return UDID.toLowerCase();
    }
    void addNewSession(boolean failSafe, String sessionName) {
        String homePath = HOME_PATH;
        File roSerialFile = new File(homePath,".ro_serialno");
        if(!roSerialFile.exists()){
            FileOutputStream stream = null;
            try {
                stream = new FileOutputStream(roSerialFile);
                stream.write(getUniqueSerialNO().getBytes());
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    stream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        if (mTermService.getSessions().size() >= MAX_SESSIONS) {
            new AlertDialog.Builder(this).setTitle(R.string.max_terminals_reached_title).setMessage(R.string.max_terminals_reached_message)
                .setPositiveButton(android.R.string.ok, null).show();
        } else {
            if (mTermService.getSessions().size() == 0 && !mTermService.isWakelockEnabled()) {
                File termuxTmpDir = new File(PREFIX_PATH + "/tmp");
                if (termuxTmpDir.exists()) {
                    try {
                        TermuxInstaller.deleteFolder(termuxTmpDir);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    termuxTmpDir.mkdirs();
                }
            }
            String executablePath = (failSafe ? "/system/bin/sh" : null);

            String sharpaiChrootPath = FILES_PATH+"/usr/bin/sharpai-chroot";
            File chrootFile = new File(sharpaiChrootPath);

            String termuxRootPath = FILES_PATH+"/termuxroot";
            File termuxRootFile = new File(termuxRootPath);

            File shellFile = new File(PREFIX_PATH + "/bin/bash" );
            TerminalSession newSession = null;
            if(shellFile.exists() && shellFile.canExecute() && chrootFile.exists() && termuxRootFile.exists()){
                newSession = mTermService.createTermSession(shellFile.getAbsolutePath(), new String[]{sharpaiChrootPath}, null, failSafe);
            } else {

                if (shellFile.canExecute()) {
                    executablePath = shellFile.getAbsolutePath();
                }

                newSession = mTermService.createTermSession(executablePath, null, null, failSafe);
            }
            if (sessionName != null) {
                newSession.mSessionName = sessionName;
            }
            switchToSession(newSession);
            getDrawer().closeDrawers();
        }
    }

    /** Try switching to session and note about it, but do nothing if already displaying the session. */
    void switchToSession(TerminalSession session) {
        if (mTerminalView.attachSession(session)) {
            noteSessionInfo();
            updateBackgroundColor();
        }
    }

    String toToastTitle(TerminalSession session) {
        final int indexOfSession = mTermService.getSessions().indexOf(session);
        StringBuilder toastTitle = new StringBuilder("[" + (indexOfSession + 1) + "]");
        if (!TextUtils.isEmpty(session.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName);
        }
        String title = session.getTitle();
        if (!TextUtils.isEmpty(title)) {
            // Space to "[${NR}] or newline after session name:
            toastTitle.append(session.mSessionName == null ? " " : "\n");
            toastTitle.append(title);
        }
        return toastTitle.toString();
    }

    void noteSessionInfo() {
        if (!mIsVisible) return;
        TerminalSession session = getCurrentTermSession();
        final int indexOfSession = mTermService.getSessions().indexOf(session);
        showToast(toToastTitle(session), false);
        mListViewAdapter.notifyDataSetChanged();
        final ListView lv = findViewById(R.id.left_drawer_list);
        lv.setItemChecked(indexOfSession, true);
        lv.smoothScrollToPosition(indexOfSession);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentTermSession();
        if (currentSession == null) return;

        menu.add(Menu.NONE, CONTEXTMENU_SELECT_URL_ID, Menu.NONE, R.string.select_url);
        menu.add(Menu.NONE, CONTEXTMENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.select_all_and_share);
        menu.add(Menu.NONE, CONTEXTMENU_RESET_TERMINAL_ID, Menu.NONE, R.string.reset_terminal);
        menu.add(Menu.NONE, CONTEXTMENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.kill_process, getCurrentTermSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXTMENU_STYLING_ID, Menu.NONE, R.string.style_terminal);
        menu.add(Menu.NONE, CONTEXTMENU_HELP_ID, Menu.NONE, R.string.help);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    static LinkedHashSet<CharSequence> extractUrls(String text) {
        // Pattern for recognizing a URL, based off RFC 3986
        // http://stackoverflow.com/questions/5713558/detect-and-extract-url-from-a-string
        final Pattern urlPattern = Pattern.compile(
            "(?:^|[\\W])((ht|f)tp(s?)://|www\\.)" + "(([\\w\\-]+\\.)+?([\\w\\-.~]+/?)*" + "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
        LinkedHashSet<CharSequence> urlSet = new LinkedHashSet<>();
        Matcher matcher = urlPattern.matcher(text);
        while (matcher.find()) {
            int matchStart = matcher.start(1);
            int matchEnd = matcher.end();
            String url = text.substring(matchStart, matchEnd);
            urlSet.add(url);
        }
        return urlSet;
    }

    void showUrlSelection() {
        String text = getCurrentTermSession().getEmulator().getScreen().getTranscriptText();
        LinkedHashSet<CharSequence> urlSet = extractUrls(text);
        if (urlSet.isEmpty()) {
            new AlertDialog.Builder(this).setMessage(R.string.select_url_no_found).show();
            return;
        }

        final CharSequence[] urls = urlSet.toArray(new CharSequence[urlSet.size()]);
        Collections.reverse(Arrays.asList(urls)); // Latest first.

        // Click to copy url to clipboard:
        final AlertDialog dialog = new AlertDialog.Builder(TermuxActivity.this).setItems(urls, (di, which) -> {
            String url = (String) urls[which];
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"}, new ClipData.Item(url)));
            Toast.makeText(TermuxActivity.this, R.string.select_url_copied_to_clipboard, Toast.LENGTH_LONG).show();
        }).setTitle(R.string.select_url_dialog_title).create();

        // Long press to open URL:
        dialog.setOnShowListener(di -> {
            ListView lv = dialog.getListView(); // this is a ListView with your "buds" in it
            lv.setOnItemLongClickListener((parent, view, position, id) -> {
                dialog.dismiss();
                String url = (String) urls[position];
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                try {
                    startActivity(i, null);
                } catch (ActivityNotFoundException e) {
                    // If no applications match, Android displays a system message.
                    startActivity(Intent.createChooser(i, null));
                }
                return true;
            });
        });

        dialog.show();
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentTermSession();

        switch (item.getItemId()) {
            case CONTEXTMENU_SELECT_URL_ID:
                showUrlSelection();
                return true;
            case CONTEXTMENU_SHARE_TRANSCRIPT_ID:
                if (session != null) {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, session.getEmulator().getScreen().getTranscriptText().trim());
                    intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_transcript_title));
                    startActivity(Intent.createChooser(intent, getString(R.string.share_transcript_chooser_title)));
                }
                return true;
            case CONTEXTMENU_PASTE_ID:
                doPaste();
                return true;
            case CONTEXTMENU_KILL_PROCESS_ID:
                final AlertDialog.Builder b = new AlertDialog.Builder(this);
                b.setIcon(android.R.drawable.ic_dialog_alert);
                b.setMessage(R.string.confirm_kill_process);
                b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
                    dialog.dismiss();
                    getCurrentTermSession().finishIfRunning();
                });
                b.setNegativeButton(android.R.string.no, null);
                b.show();
                return true;
            case CONTEXTMENU_RESET_TERMINAL_ID: {
                if (session != null) {
                    session.reset();
                    showToast(getResources().getString(R.string.reset_toast_notification), true);
                }
                return true;
            }
            case CONTEXTMENU_STYLING_ID: {
                Intent stylingIntent = new Intent();
                stylingIntent.setClassName("org.sharpai.termux.styling", "org.sharpai.termux.styling.TermuxStyleActivity");
                try {
                    startActivity(stylingIntent);
                } catch (ActivityNotFoundException | IllegalArgumentException e) {
                    // The startActivity() call is not documented to throw IllegalArgumentException.
                    // However, crash reporting shows that it sometimes does, so catch it here.
                    new AlertDialog.Builder(this).setMessage(R.string.styling_not_installed)
                        .setPositiveButton(R.string.styling_install, (dialog, which) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=org.sharpai.termux.styling")))).setNegativeButton(android.R.string.cancel, null).show();
                }
                return true;
            }
            case CONTEXTMENU_HELP_ID:
                startActivity(new Intent(this, TermuxHelpActivity.class));
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        if (requestCode == REQUESTCODE_PERMISSION_STORAGE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            TermuxInstaller.setupStorageSymlinks(this);
            TermuxInstaller.setupIfNeeded(TermuxActivity.this, () -> {
                if (mTermService == null) return; // Activity might have been destroyed.
                try {
                    addNewSession(false, null);
                } catch (WindowManager.BadTokenException e) {
                    // Activity finished - ignore.
                }
            });
        }
    }

    void changeFontSize(boolean increase) {
        mSettings.changeFontSize(this, increase);
        mTerminalView.setTextSize(mSettings.getFontSize());
    }

    void doPaste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clipData = clipboard.getPrimaryClip();
        if (clipData == null) return;
        CharSequence paste = clipData.getItemAt(0).coerceToText(this);
        if (!TextUtils.isEmpty(paste))
            getCurrentTermSession().getEmulator().paste(paste.toString());
    }

    /** The current session as stored or the last one if that does not exist. */
    public TerminalSession getStoredCurrentSessionOrLast() {
        TerminalSession stored = TermuxPreferences.getCurrentSession(this);
        if (stored != null) return stored;
        List<TerminalSession> sessions = mTermService.getSessions();
        return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
    }

    /** Show a toast and dismiss the last one if still visible. */
    void showToast(String text, boolean longDuration) {
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }

    public void removeFinishedSession(TerminalSession finishedSession) {
        // Return pressed with finished session - remove it.
        TermuxService service = mTermService;

        int index = service.removeTermSession(finishedSession);
        mListViewAdapter.notifyDataSetChanged();
        if (mTermService.getSessions().isEmpty()) {
            // There are no sessions to show, so finish the activity.
            finish();
        } else {
            if (index >= service.getSessions().size()) {
                index = service.getSessions().size() - 1;
            }
            switchToSession(service.getSessions().get(index));
        }
    }

}
