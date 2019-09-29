package balti.migrate;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.StatFs;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import java.io.File;
import java.io.IOException;

import balti.migrate.inAppRestore.ZipPicker;

import static android.os.Environment.getExternalStorageDirectory;
import static balti.migrate.CommonTools.DEFAULT_INTERNAL_STORAGE_DIR;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    SharedPreferences main;
    SharedPreferences.Editor editor;

    Button backup, restore, inAppRestore;
    ImageButton drawerButton;
    TableRow internalStorageUse, sdCardStorageUse;
    ProgressBar internalStorageBar, sdCardStorageBar;
    TextView internalStorageText, sdCardStorageText, sdCardName;
    TextView learnAboutSdCardSupport;

    DrawerLayout drawer;
    NavigationView navigationView;

    CommonTools commonTools;

    AlertDialog loadingDialog;
    int REQUEST_CODE = 43;
    int RESTORE_STORAGE_ACCESS_CODE = 5443;

    static int THIS_VERSION = 13;

    String rootErrorMessage = "";

    Handler storageHandler;
    Runnable storageRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) { //ok
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        main = getSharedPreferences("main", MODE_PRIVATE);
        editor = main.edit();

        commonTools = new CommonTools(this);

        drawer = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.navigationDrawer);

        if (main.getBoolean("firstRun", true)) {

            getExternalCacheDir();

            editor.putInt("version", THIS_VERSION);
            editor.commit();

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel channel = new NotificationChannel(BackupService.BACKUP_START_NOTIFICATION,
                        BackupService.BACKUP_START_NOTIFICATION, NotificationManager.IMPORTANCE_DEFAULT);
                channel.setSound(null, null);
                assert notificationManager != null;
                notificationManager.createNotificationChannel(channel);
            }

            startActivity(new Intent(this, InitialGuide.class));
            finish();
        } else showChangeLog(true);

        backup = findViewById(R.id.backupMain);
        backup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                View v = View.inflate(MainActivity.this, R.layout.please_wait, null);
                Button cancel = v.findViewById(R.id.waiting_cancel);
                v.findViewById(R.id.waiting_details).setVisibility(View.GONE);
                TextView head = v.findViewById(R.id.waiting_progress);
                cancel.setVisibility(View.GONE);

                head.setText(R.string.checking_permissions);

                try {
                    loadingDialog.dismiss();
                } catch (Exception ignored) {
                }

                loadingDialog = new AlertDialog.Builder(MainActivity.this)
                        .setView(v)
                        .setCancelable(false)
                        .create();

                loadingDialog.show();

                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE);

            }
        });

        restore = findViewById(R.id.restoreMain);
        restore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, HowToRestore.class));
            }
        });

        inAppRestore = findViewById(R.id.inAppRestore);
        inAppRestore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, RESTORE_STORAGE_ACCESS_CODE);
            }
        });

        drawerButton = findViewById(R.id.drawerButton);
        drawerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                drawer.openDrawer(GravityCompat.START);
            }
        });

        internalStorageUse = findViewById(R.id.internal_storage_use_view);
        internalStorageBar = findViewById(R.id.internal_storage_bar);
        internalStorageText = findViewById(R.id.internal_storage_text);

        sdCardStorageUse = findViewById(R.id.sd_card_storage_use_view);
        sdCardStorageBar = findViewById(R.id.sd_card_storage_bar);
        sdCardStorageText = findViewById(R.id.sd_card_storage_text);
        sdCardName = findViewById(R.id.sd_card_name);

        learnAboutSdCardSupport = findViewById(R.id.learn_sd_card_support);
        learnAboutSdCardSupport.setPaintFlags(Paint.UNDERLINE_TEXT_FLAG);
        learnAboutSdCardSupport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commonTools.showSdCardSupportDialog();
            }
        });

        navigationView.setNavigationItemSelectedListener(this);

        final String cpu_abi = Build.SUPPORTED_ABIS[0];

        if (!(cpu_abi.equals("armeabi-v7a") || cpu_abi.equals("arm64-v8a") || cpu_abi.equals("x86") || cpu_abi.equals("x86_64"))) {

            backup.setVisibility(View.GONE);

            new AlertDialog.Builder(this)
                    .setTitle(R.string.unsupported_device)
                    .setMessage(getString(R.string.cpu_arch_is) + "\n" + cpu_abi + "\n\n" + getString(R.string.currently_supported_cpu))
                    .setPositiveButton(R.string.close, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .setNegativeButton(R.string.contact, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            String body = commonTools.getDeviceSpecifications();

                            Intent email = new Intent(Intent.ACTION_SENDTO);
                            email.setData(Uri.parse("mailto:"));
                            email.putExtra(Intent.EXTRA_EMAIL, new String[]{"help.baltiapps@gmail.com"});
                            email.putExtra(Intent.EXTRA_SUBJECT, "Unsupported device");
                            email.putExtra(Intent.EXTRA_TEXT, body);

                            try {
                                startActivity(Intent.createChooser(email, getString(R.string.select_mail)));
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setCancelable(false)
                    .show();
        } else if (Build.VERSION.SDK_INT > 28 && !main.getBoolean("android_version_warning", false)) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.too_fast)
                    .setMessage(R.string.too_fast_desc)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(R.string.dont_show_again, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            editor.putBoolean("android_version_warning", true);
                            editor.commit();
                        }
                    })
                    .show();
        }


        /*final AdView adView = findViewById(R.id.main_activity_adView);
        AdRequest adRequest = new AdRequest.Builder().build();
        adView.loadAd(adRequest);

        adView.setAdListener(new AdListener(){
            @Override
            public void onAdFailedToLoad(int i) {
                super.onAdFailedToLoad(i);
                adView.setVisibility(View.GONE);
            }
        });*/

        refreshStorageSizes();

        storageHandler = new Handler();
        storageRunnable = new Runnable() {
            @Override
            public void run() {
                refreshStorageSizes();
                storageHandler.postDelayed(storageRunnable, 1000);
            }
        };

        storageHandler.post(storageRunnable);
    }

    void refreshStorageSizes() { //ok
        StatFs statFs = new StatFs(getExternalStorageDirectory().getAbsolutePath());

        long availableKb = statFs.getBlockSizeLong() * statFs.getAvailableBlocksLong();
        availableKb = availableKb / 1024;
        long fullKb = statFs.getBlockSizeLong() * statFs.getBlockCountLong();
        fullKb = fullKb / 1024;
        long consumedKb = fullKb - availableKb;
        internalStorageBar.setProgress((int) ((consumedKb * 100) / fullKb));
        internalStorageText.setText(commonTools.getHumanReadableStorageSpace(consumedKb) + "/" + commonTools.getHumanReadableStorageSpace(fullKb));

        String defaultPath = main.getString("defaultBackupPath", DEFAULT_INTERNAL_STORAGE_DIR);

        File sdCardRoot = null;

        if (!defaultPath.equals(DEFAULT_INTERNAL_STORAGE_DIR) && new File(defaultPath).canWrite()) {

            sdCardRoot = new File(defaultPath).getParentFile();

        } else {
            String sdCardPaths[] = commonTools.getSdCardPaths();
            if (sdCardPaths.length == 1 && new File(sdCardPaths[0]).canWrite()) {
                sdCardRoot = new File(sdCardPaths[0]);
            }
        }

        if (sdCardRoot != null) {

            sdCardStorageUse.setVisibility(View.VISIBLE);

            statFs = new StatFs(sdCardRoot.getAbsolutePath());
            availableKb = statFs.getBlockSizeLong() * statFs.getAvailableBlocksLong();
            availableKb = availableKb / 1024;
            fullKb = statFs.getBlockSizeLong() * statFs.getBlockCountLong();
            fullKb = fullKb / 1024;
            consumedKb = fullKb - availableKb;
            sdCardName.setText(sdCardRoot.getName());
            sdCardStorageBar.setProgress((int) ((consumedKb * 100) / fullKb));
            sdCardStorageText.setText(commonTools.getHumanReadableStorageSpace(consumedKb) + "/" + commonTools.getHumanReadableStorageSpace(fullKb));
        } else {
            sdCardStorageUse.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() { //ok
        super.onResume();

        boolean authetic = getPackageName().equals("balti.migrate");

        if (!authetic) {
            AlertDialog.Builder ad = new AlertDialog.Builder(this);
            ad.setTitle(R.string.copied_app);
            ad.setMessage(R.string.copied_app_exp);
            ad.setCancelable(false);
            ad.setNegativeButton(R.string.close, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            });
            ad.setPositiveButton(R.string.install, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    commonTools.openWeblink("market://details?id=balti.migrate");
                }
            });
            ad.show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) { //ok
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE) {

            if (grantResults.length == 2 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {

                if (isRootPermissionGranted()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isUsageAccessGranted()) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.use_usage_access_permission)
                                .setMessage(R.string.usage_access_permission_needed_desc)
                                .setPositiveButton(R.string.proceed, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                                        startActivity(intent);
                                        Toast.makeText(MainActivity.this, R.string.usage_permission_toast, Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    } else {
                        startActivity(new Intent(MainActivity.this, BackupActivity.class));
                    }
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.root_permission_denied)
                            .setMessage(getString(R.string.root_permission_denied_desc) + "\n\n" + rootErrorMessage)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            } else {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.storage_access_required)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
            try {
                loadingDialog.dismiss();
            } catch (Exception ignored) {
            }
        } else if (requestCode == RESTORE_STORAGE_ACCESS_CODE) {
            if (grantResults.length == 2 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                startActivity(new Intent(this, ZipPicker.class));
            }
            else {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.storage_access_required_restore)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        }
    }


    @Override
    public void onBackPressed() { //ok
        if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START);
        else if (!main.getBoolean("firstRun", true) && main.getBoolean("askForRating", true))
            askForRating(false);
        else super.onBackPressed();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) { //ok
        int id = item.getItemId();
        switch (id) {
            case R.id.about:
                AlertDialog.Builder about = new AlertDialog.Builder(this);
                View v = getLayoutInflater().inflate(R.layout.about_app, null);
                about.setView(v)
                        .setTitle(getString(R.string.about))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                break;

            case R.id.helpPage:
                startActivity(new Intent(this, HelpPage.class));
                break;

            case R.id.changelog:
                showChangeLog(false);
                break;

            case R.id.lastLog:
                showLog();
                break;

            case R.id.older_builds:
                commonTools.openWeblink("https://www.androidfilehost.com/?w=files&flid=285270");
                break;

            case R.id.appIntro:
                startActivity(new Intent(this, InitialGuide.class).putExtra("manual", true));
                break;

            case R.id.thanks:
                AlertDialog.Builder specialThanks = new AlertDialog.Builder(this);
                specialThanks.setView(View.inflate(MainActivity.this, R.layout.thanks_layout, null))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                break;

            case R.id.preferences:
                startActivity(new Intent(this, PreferenceScreen.class));
                break;

            case R.id.rate:
                askForRating(true);
                break;

            case R.id.contact:
                android.app.AlertDialog.Builder confirmEmail;
                confirmEmail = new android.app.AlertDialog.Builder(MainActivity.this);
                confirmEmail.setTitle(getString(R.string.sure_to_mail))
                        .setMessage(getString(R.string.sure_to_mail_exp))
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {

                                Intent email = new Intent(Intent.ACTION_SENDTO);
                                email.setData(Uri.parse("mailto:"));
                                email.putExtra(Intent.EXTRA_EMAIL, new String[]{"help.baltiapps@gmail.com"});
                                email.putExtra(Intent.EXTRA_SUBJECT, "Bug report for Migrate");
                                email.putExtra(Intent.EXTRA_TEXT, commonTools.getDeviceSpecifications());

                                try {
                                    startActivity(Intent.createChooser(email, getString(R.string.select_mail)));
                                } catch (Exception e) {
                                    Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(R.string.help, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                startActivity(new Intent(MainActivity.this, HelpPage.class));
                            }
                        })
                        .show();
                break;

            case R.id.contact_telegram:
                commonTools.openWeblink("https://t.me/migrateApp");
                break;

            case R.id.xda_thread:
                commonTools.openWeblink("https://forum.xda-developers.com/android/apps-games/app-migrate-custom-rom-migration-tool-t3862763");
                break;

            case R.id.otherApps:
                View view = getLayoutInflater().inflate(R.layout.other_apps, null);
                android.app.AlertDialog.Builder a = new android.app.AlertDialog.Builder(MainActivity.this);
                a.setView(view);
                a.setTitle(R.string.other_apps);
                a.setPositiveButton(android.R.string.ok, null);
                a.show();
                otherAppsClickManager(view);
                break;
        }
        drawer.closeDrawer(GravityCompat.START);

        return true;
    }


    boolean isRootPermissionGranted() { //ok

        boolean p = false;

        try {
            Object r[] = commonTools.suEcho();
            p = (boolean) r[0];
            rootErrorMessage = (String) r[1];
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            p = false;
        }

        return p;
    }

    private boolean isUsageAccessGranted() { //ok
        try {

            PackageManager packageManager = getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(getPackageName(), 0);
            AppOpsManager appOpsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    applicationInfo.uid, applicationInfo.packageName);

            return (mode == AppOpsManager.MODE_ALLOWED);

        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }


    void showChangeLog(boolean onlyLatest) { //ok
        int currVer = main.getInt("version", 1);
        androidx.appcompat.app.AlertDialog.Builder changelog = new androidx.appcompat.app.AlertDialog.Builder(this);
        String message = "";
        String title = "";
        if (onlyLatest) {
            if (currVer < THIS_VERSION) {
                /*Put only the latest version here*/
                title = getString(R.string.version_3_0);
                message = getString(R.string.version_3_0_content);
                changelog.setTitle(title)
                        .setMessage(message)
                        .setPositiveButton(R.string.close, null)
                        .show();

                editor.putInt("version", THIS_VERSION);
                editor.commit();
            }
        } else {
            title = getString(R.string.changelog);

            int padding = 20;

            ScrollView scrollView = new ScrollView(this);
            scrollView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView allVersions = new TextView(this);
            allVersions.setPadding(padding, padding, padding, padding);
            allVersions.setText("");
            allVersions.setTextSize(15);

            scrollView.addView(allVersions);

            /*Add increasing versions here*/

            allVersions.append("\n" + getString(R.string.version_3_0) + "\n" + getString(R.string.version_3_0_content) + "\n");
            allVersions.append("\n" + getString(R.string.version_2_1) + "\n" + getString(R.string.version_2_1_content) + "\n");
            allVersions.append("\n" + getString(R.string.version_2_0_1) + "\n" + getString(R.string.version_2_0_1_content) + "\n");
            allVersions.append("\n" + getString(R.string.version_2_0) + "\n" + getString(R.string.version_2_0_content) + "\n");
            allVersions.append("\n" + getString(R.string.version_1_2) + "\n" + getString(R.string.version_1_2_content) + "\n");
            allVersions.append("\n" + getString(R.string.version_1_1_1) + "\n" + getString(R.string.version_1_1_1_only_content) + "\n");
            allVersions.append("\n" + getString(R.string.version_1_1) + "\n" + getString(R.string.version_1_1_content) + "\n");
            allVersions.append("\n" + getString(R.string.version_1_0_5) + "\n" + getString(R.string.version_1_0_5_content) + "\n");
            allVersions.append("\n" + getString(R.string.version_1_0_4) + "\n" + getString(R.string.version_1_0_4_content) + "\n");
            allVersions.append("\n" + getString(R.string.version_1_0_3) + "\n" + getString(R.string.version_1_0_3_content) + "\n");
            allVersions.append("\n" + getString(R.string.version_1_0_2) + "\n" + getString(R.string.version_1_0_2_content) + "\n");
            allVersions.append("\n" + getString(R.string.version_1_0_1) + "\n" + getString(R.string.version_1_0_1_content) + "\n");
            allVersions.append("\n" + getString(R.string.version_1_0) + "\n" + getString(R.string.version_1_0_content) + "\n");

            changelog.setTitle(title)
                    .setView(scrollView)
                    .setPositiveButton(R.string.close, null)
                    .show();
        }

    }

    public void otherAppsClickManager(View layout) { //ok
        //other apps links

        LinearLayout mdh = layout.findViewById(R.id.motodisplay_handwave);

        mdh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commonTools.openWeblink("market://details?id=sayantanrc.motodisplayhandwave");
            }
        });

        LinearLayout opc8085 = layout.findViewById(R.id.opcode_8085);

        opc8085.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commonTools.openWeblink("market://details?id=balti.opcode8085");
            }
        });

        LinearLayout prs = layout.findViewById(R.id.pickRingStop);

        prs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commonTools.openWeblink("market://details?id=balti.pickringstop");
            }
        });
    }


    void askForRating(boolean manual) { //ok

        AlertDialog.Builder rate = new AlertDialog.Builder(this);
        rate.setTitle(getString(R.string.rate_dialog_title))
                .setIcon(R.drawable.ic_rate)
                .setMessage(getString(R.string.rate_dialog_message))
                .setPositiveButton(getString(R.string.sure), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        Uri uri = Uri.parse("market://details?id=balti.migrate");
                        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                        startActivity(intent);
                        editor.putBoolean("askForRating", false);
                        editor.commit();
                    }
                });
        if (!manual) {
            rate.setNeutralButton(getString(R.string.never_show), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    editor.putBoolean("askForRating", false);
                    editor.commit();
                    finish();
                }
            })
                    .setNegativeButton(getString(R.string.later), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            finish();
                        }
                    });
        } else {
            rate.setNegativeButton("Cancel", null);
        }
        rate.show();
    }

    void showLog() { //ok
        View lView = View.inflate(this, R.layout.last_log_report, null);
        Button pLog = lView.findViewById(R.id.view_progress_log);
        Button eLog = lView.findViewById(R.id.view_error_log);
        Button report = lView.findViewById(R.id.report_logs);

        final AlertDialog ad = new AlertDialog.Builder(this, R.style.DarkAlert)
                .setTitle(R.string.lastLog)
                .setIcon(R.drawable.ic_log)
                .setView(lView)
                .setNegativeButton(R.string.close, null)
                .create();

        pLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File f = new File(getExternalCacheDir(), "progressLog.txt");
                if (f.exists())
                    startActivity(
                            new Intent(MainActivity.this, SimpleLogDisplay.class)
                                    .putExtra("head", getString(R.string.progressLog))
                                    .putExtra("filePath", f.getAbsolutePath())
                    );
                else
                    Toast.makeText(MainActivity.this, getString(R.string.progress_log_does_not_exist), Toast.LENGTH_SHORT).show();
            }
        });

        eLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File f = new File(getExternalCacheDir(), "errorLog.txt");
                if (f.exists())
                    startActivity(
                            new Intent(MainActivity.this, SimpleLogDisplay.class)
                                    .putExtra("head", getString(R.string.errorLog))
                                    .putExtra("filePath", f.getAbsolutePath())
                    );
                else
                    Toast.makeText(MainActivity.this, getString(R.string.error_log_does_not_exist), Toast.LENGTH_SHORT).show();
            }
        });

        report.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commonTools.reportLogs(false);
                ad.dismiss();
            }
        });

        ad.show();
    }

    @Override
    protected void onDestroy() { //ok
        super.onDestroy();
        try {
            loadingDialog.dismiss();
        } catch (Exception ignored) {
        }
        try {
            storageHandler.removeCallbacks(storageRunnable);
        } catch (Exception ignored) {
        }
    }
}
