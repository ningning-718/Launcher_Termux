package org.sharpai.boot;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;

public class BootJobService extends JobService {

    public static final String SCRIPT_FILE_PATH = "org.sharpai.boot.script_path";

    // Constants from TermuxService.
    private static final String TERMUX_SERVICE = "org.sharpai.app.TermuxService";
    private static final String ACTION_EXECUTE = "org.sharpai.termux.service_execute";
    private static final String EXTRA_EXECUTE_IN_BACKGROUND = "org.sharpai.termux.execute.background";

    @Override
    public boolean onStartJob(JobParameters params) {
        PersistableBundle extras = params.getExtras();
        String filePath = extras.getString(SCRIPT_FILE_PATH);

        Uri scriptUri = new Uri.Builder().scheme("org.sharpai.termux.file").path(filePath).build();
        Intent executeIntent = new Intent(ACTION_EXECUTE, scriptUri);
        executeIntent.setClassName("org.sharpai.termux", TERMUX_SERVICE);
        executeIntent.putExtra(EXTRA_EXECUTE_IN_BACKGROUND, true);

        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // https://developer.android.com/about/versions/oreo/background.html
            context.startForegroundService(executeIntent);
        } else {
            context.startService(executeIntent);
        }

        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
