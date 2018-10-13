package balti.migrate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;

import static android.view.View.VISIBLE;

/**
 * Created by sayantan on 22/1/18.
 */

public class BackupProgressLayout extends AppCompatActivity {

    BroadcastReceiver progressReceiver;
    IntentFilter progressReceiverIF;

    TextView task, partName;
    ImageView appIcon;
    TextView progress;
    TextView progressLog;
    TextView errorLog;
    ProgressBar progressBar;
    Button actionButton, reportLog;

    String lastLog = "";
    String lastIconString = "";

    String lastType = "";

    long totalTasksTime = 0;

    CommonTools commonTools;

    class SetAppIcon extends AsyncTask<String, Void, Bitmap>{


        @Override
        protected Bitmap doInBackground(String... strings) {

            Bitmap bmp = null;
            String[] bytes = strings[0].split("_");

            try {
                byte imageData[] = new byte[bytes.length];
                for (int i = 0; i < bytes.length; i++) {
                    imageData[i] = Byte.parseByte(bytes[i]);
                }
                bmp = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
            }
            catch (Exception e){
                e.printStackTrace();
            }
            return bmp;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            if (bitmap != null) {
                appIcon.setImageBitmap(bitmap);
            }
            else {
                appIcon.setImageResource(R.drawable.ic_backup);
            }
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.backup_progress_layout);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        task = findViewById(R.id.progressTask);
        partName = findViewById(R.id.part_name);
        appIcon = findViewById(R.id.app_icon);
        progress = findViewById(R.id.progressPercent);
        progressBar = findViewById(R.id.progressBar);
        progressLog = findViewById(R.id.progressLogTextView);
        errorLog = findViewById(R.id.errorLogTextView);
        actionButton = findViewById(R.id.progressActionButton);
        reportLog = findViewById(R.id.reportLogButton);

        reportLog.setVisibility(View.GONE);

        progressLog.setGravity(Gravity.BOTTOM);
        progressLog.setMovementMethod(new ScrollingMovementMethod());

        actionButton.setText(getString(android.R.string.cancel));
        actionButton.setBackground(getResources().getDrawable(R.drawable.cancel_backup));
        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LocalBroadcastManager.getInstance(BackupProgressLayout.this).sendBroadcast(new Intent("Migrate backup cancel broadcast"));
            }
        });

        reportLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                commonTools.reportLogs(true);

            }
        });

        if (getIntent().getExtras() != null){
            handleProgress(getIntent());
        }

        progressReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleProgress(intent);
            }

        };

        progressReceiverIF = new IntentFilter("Migrate progress broadcast");
        LocalBroadcastManager.getInstance(this).registerReceiver(progressReceiver, progressReceiverIF);

        commonTools = new CommonTools(this);
    }

    void handleProgress(Intent intent){
        try {
            String type = "";
            String partName = "";

            if (intent.hasExtra("type"))
                type = intent.getStringExtra("type");

            if (intent.hasExtra("part_name"))
                partName = intent.getStringExtra("part_name");

            this.partName.setText(partName);

            if (!type.equals(lastType)){
                lastType = type;
                progressLog.append("\n\n");
            }

            if (type.equals("ready")){

                int tp = intent.getIntExtra("total_parts", 1);
                if (tp > 1) {
                    String head;
                    new AlertDialog.Builder(this)
                            .setTitle(head = tp + " " + getString(R.string.parts))
                            .setMessage(getString(R.string.split_desc_1) + " " + head + "\n\n" + getString(R.string.split_desc_2))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }

            }
            else if (type.equals("finished")){

                totalTasksTime += intent.getLongExtra("total_time", 0);

                if (intent.getBooleanExtra("final_process", true)) {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                    task.setText(intent.getStringExtra("finishedMessage"));

                    if (intent.hasExtra("errors")) {
                        setError(intent.getStringArrayListExtra("errors"));
                        if (intent.getStringArrayListExtra("errors").size() > 0) {
                            appIcon.setImageResource(R.drawable.ic_error);
                            reportLog.setVisibility(VISIBLE);
                        }
                    }

                    if (intent.getStringExtra("finishedMessage").equals(getString(R.string.backupCancelled))) {
                        appIcon.setImageResource(R.drawable.ic_cancelled);
                    } else {
                        progressBar.setProgress(100);
                        progress.setText("100%");
                        appIcon.setImageResource(R.drawable.ic_finished);
                    }

                    addLog("finishedMessage", intent);

                    task.append("\n(" + calendarDifference(totalTasksTime) + ")");

                    actionButton.setText(getString(R.string.close));
                    actionButton.setBackground(getResources().getDrawable(R.drawable.log_action_button));
                    actionButton.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            finishThis();
                        }
                    });
                }

            }
            else {

                actionButton.setText(getString(android.R.string.cancel));
                actionButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        LocalBroadcastManager.getInstance(BackupProgressLayout.this).sendBroadcast(new Intent("Migrate backup cancel broadcast"));
                    }
                });

                if (type.equals("contact_progress")) {

                    appIcon.setImageResource(R.drawable.ic_contacts_icon);

                    task.setText(R.string.backing_contacts);

                    setProgress("progress", intent);

                    addLog("contact_name", intent);

                } else if (type.equals("sms_reading")) {

                    appIcon.setImageResource(R.drawable.ic_sms_icon);

                    task.setText(R.string.reading_sms);

                    progressBar.setIndeterminate(true);

                } else if (type.equals("sms_progress")) {

                    appIcon.setImageResource(R.drawable.ic_sms_icon);

                    task.setText(R.string.backing_sms);

                    setProgress("progress", intent);

                    addLog("sms_address", intent);

                } else if (type.equals("calls_reading")) {

                    appIcon.setImageResource(R.drawable.ic_call_log_icon);

                    task.setText(R.string.reading_calls);

                    progressBar.setIndeterminate(true);

                } else if (type.equals("calls_progress")) {

                    appIcon.setImageResource(R.drawable.ic_call_log_icon);

                    task.setText(R.string.backing_calls);

                    setProgress("progress", intent);

                    addLog("calls_name", intent);

                } else if (type.equals("dpi_progress")) {

                    appIcon.setImageResource(R.drawable.ic_dpi_icon);

                    task.setText(R.string.backing_dpi);

                    progressBar.setIndeterminate(true);

                    progressLog.append(getString(R.string.backing_dpi));

                } else if (type.equals("app_progress")) {

                    if (intent.hasExtra("app_name")) {
                        task.setText(intent.getStringExtra("app_name"));
                    }

                    if (intent.hasExtra("app_icon")) {
                        String iconString = "";
                        try {
                            iconString = intent.getStringExtra("app_icon");
                        } catch (Exception ignored) {
                        }
                        if (!iconString.equals("") && !iconString.equals(lastIconString)) {
                            SetAppIcon obj = new SetAppIcon();
                            lastIconString = iconString;
                            obj.execute(lastIconString);
                        }
                    } else {
                        appIcon.setImageResource(R.drawable.ic_backup);
                    }

                    setProgress("progress", intent);

                    addLog("app_log", intent);

                } else if (type.equals("zip_progress")) {

                    appIcon.setImageResource(R.drawable.ic_combine);

                    task.setText(R.string.combining);

                    setProgress("progress", intent);

                    addLog("zip_log", intent);

                }
            }

        }
        catch (Exception e){
            e.printStackTrace();
        }
    }

    void addLog(String key, Intent intent){
        try {

            if (!intent.hasExtra(key))
                return;

            String logMsg = intent.getStringExtra(key);
            if (!logMsg.trim().equals(lastLog.trim())) {
                lastLog = logMsg;
                progressLog.append(lastLog + "\n");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void setProgress(String key, Intent intent){

        if (!intent.hasExtra(key))
            return;

        int pr = intent.getIntExtra(key, 0);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(pr);
        progress.setText(pr + "%");
    }


    void setError(ArrayList<String> errors){
        if (errors.size() > 0) {
            errorLog.setVisibility(VISIBLE);
            for (int i = 0; i < errors.size(); i++) {
                errorLog.append(errors.get(i) + "\n");
            }
        }
    }

    @Override
    public void onBackPressed() {
        finishThis();
    }


    void finishThis(){
        try {
            if (progressReceiver != null) LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver);
        }
        catch (IllegalArgumentException ignored){}


        if (!commonTools.isServiceRunning(BackupService.class.getName())){
            startActivity(new Intent(BackupProgressLayout.this, MainActivity.class));
        }
        finish();
    }

    String calendarDifference(long longDiff){
        String diff = "";

        try {

            longDiff = longDiff / 1000;

            long d = longDiff / (60 * 60 * 24);
            if (d != 0) diff = diff + d + "days ";
            longDiff = longDiff % (60 * 60 * 24);

            long h = longDiff / (60 * 60);
            if (h != 0) diff = diff + h + "hrs ";
            longDiff = longDiff % (60 * 60);

            long m = longDiff / 60;
            if (m != 0) diff = diff + m + "mins ";
            longDiff = longDiff % 60;

            long s = longDiff;
            diff = diff + s + "secs";

        }
        catch (Exception ignored){}

        return diff;
    }
}
