package balti.migrate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;

import java.io.File;
import java.util.Objects;
import java.util.Vector;

/**
 * Created by sayantan on 15/10/17.
 */

public class BackupService extends Service {

    BroadcastReceiver cancelReceiver, progressBroadcast, requestProgress;
    IntentFilter cancelReceiverIF, progressBroadcastIF, requestProgressIF;

    int p;
    public static final String CHANNEL = "Backup notification";

    BackupEngine backupEngine = null;

    Intent toReturnIntent;

    static Vector<BackupBatch> batches;
    static String backupName, destination, busyboxBinaryFile;
    static Vector<ContactsDataPacket> contactsList;
    static Vector<CallsDataPacket> callsList;
    static Vector<SmsDataPacket> smsList;
    static boolean doBackupContacts, doBackupCalls, doBackupSms;
    static Vector<File> backupSummaries;

    BroadcastReceiver triggerBatchBackupReceiver;

    SharedPreferences main;

    int runningBatchCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        main = getSharedPreferences("main", MODE_PRIVATE);

        triggerBatchBackupReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                runNextBatch();
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(triggerBatchBackupReceiver, new IntentFilter("start batch backup"));
        toReturnIntent = new Intent("Migrate progress broadcast");
        progressBroadcast = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.hasExtra("type") && intent.getStringExtra("type").equals("finished")) {
                    if (++runningBatchCount < batches.size()) runNextBatch();
                }
                toReturnIntent = intent;
            }
        };
        progressBroadcastIF = new IntentFilter("Migrate progress broadcast");
        LocalBroadcastManager.getInstance(this).registerReceiver(progressBroadcast, progressBroadcastIF);
        cancelReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    if (backupEngine != null)
                    backupEngine.cancelProcess();
                } catch (Exception ignored) {
                }
            }
        };
        cancelReceiverIF = new IntentFilter("Migrate backup cancel broadcast");
        registerReceiver(cancelReceiver, cancelReceiverIF);
        LocalBroadcastManager.getInstance(this).registerReceiver(cancelReceiver, cancelReceiverIF);
        requestProgress = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                LocalBroadcastManager.getInstance(BackupService.this).sendBroadcast(toReturnIntent.setAction("Migrate progress broadcast"));
            }
        };
        requestProgressIF = new IntentFilter("get data");
        LocalBroadcastManager.getInstance(this).registerReceiver(requestProgress, requestProgressIF);

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL, CHANNEL, NotificationManager.IMPORTANCE_DEFAULT);
            ((NotificationManager) Objects.requireNonNull(getSystemService(NOTIFICATION_SERVICE))).createNotificationChannel(notificationChannel);
            notification = new Notification.Builder(this, CHANNEL)
                    .setContentTitle(getString(R.string.loading))
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle(getString(R.string.loading))
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .build();
        }

        startForeground(BackupEngine.NOTIFICATION_ID, notification);

        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("backup service started"));
    }

    void runNextBatch(){

        String name = (batches.size() > 1)? backupName + getString(R.string.part) + (runningBatchCount+1) : backupName;

        backupEngine = new BackupEngine(name, main.getInt("compressionLevel", 0), destination, busyboxBinaryFile,
                this, batches.get(runningBatchCount).batchSystemSize, batches.get(runningBatchCount).batchDataSize,
                runningBatchCount == batches.size()-1, backupSummaries.get(runningBatchCount));
        backupEngine.startBackup(doBackupContacts, contactsList, doBackupSms, smsList, doBackupCalls, callsList);
    }

    static void getBackupBatches(Vector<BackupBatch> batches, String backupName, String destination, String busyboxBinaryFile,
                                 Vector<ContactsDataPacket> contactsList, boolean doBackupContacts,
                                 Vector<CallsDataPacket> callsList, boolean doBackupCalls,
                                 Vector<SmsDataPacket> smsList, boolean doBackupSms,
                                 Vector<File> backupSummaries
                                 ){

        BackupService.batches = batches;
        BackupService.backupName = backupName;
        BackupService.destination = destination;
        BackupService.busyboxBinaryFile = busyboxBinaryFile;
        BackupService.contactsList = contactsList;
        BackupService.doBackupContacts = doBackupContacts;
        BackupService.callsList = callsList;
        BackupService.doBackupCalls = doBackupCalls;
        BackupService.smsList = smsList;
        BackupService.doBackupSms = doBackupSms;
        BackupService.backupSummaries = backupSummaries;

    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(cancelReceiver);
        } catch (Exception ignored){}
        try {
            unregisterReceiver(cancelReceiver);
        } catch (Exception ignored){}
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(progressBroadcast);
        } catch (Exception ignored){}
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(requestProgress);
        } catch (Exception ignored){}
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(triggerBatchBackupReceiver);
        } catch (Exception ignored){}
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
