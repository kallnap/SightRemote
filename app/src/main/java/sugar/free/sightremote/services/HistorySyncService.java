package sugar.free.sightremote.services;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.j256.ormlite.android.apptools.OpenHelperManager;

import java.sql.SQLException;
import java.sql.Time;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import sugar.free.sightparser.applayer.descriptors.HistoryReadingDirection;
import sugar.free.sightparser.applayer.descriptors.HistoryType;
import sugar.free.sightparser.applayer.history.HistoryFrame;
import sugar.free.sightparser.applayer.history.OpenHistoryReadingSessionMessage;
import sugar.free.sightparser.applayer.history.history_frames.BolusDeliveredFrame;
import sugar.free.sightparser.applayer.history.history_frames.BolusProgrammedFrame;
import sugar.free.sightparser.applayer.history.history_frames.CannulaFilledFrame;
import sugar.free.sightparser.applayer.history.history_frames.EndOfTBRFrame;
import sugar.free.sightparser.applayer.history.history_frames.PumpStatusChangedFrame;
import sugar.free.sightparser.applayer.history.history_frames.TimeChangedFrame;
import sugar.free.sightparser.applayer.status_param.ReadStatusParamBlockMessage;
import sugar.free.sightparser.applayer.status_param.blocks.SystemIdentificationBlock;
import sugar.free.sightparser.handling.HistoryBroadcast;
import sugar.free.sightparser.handling.ServiceConnectionCallback;
import sugar.free.sightparser.handling.SightServiceConnector;
import sugar.free.sightparser.handling.SingleMessageTaskRunner;
import sugar.free.sightparser.handling.StatusCallback;
import sugar.free.sightparser.handling.TaskRunner;
import sugar.free.sightparser.handling.taskrunners.ReadHistoryTaskRunner;
import sugar.free.sightparser.pipeline.Status;
import sugar.free.sightremote.database.BolusDelivered;
import sugar.free.sightremote.database.BolusProgrammed;
import sugar.free.sightremote.database.CannulaFilled;
import sugar.free.sightremote.database.DatabaseHelper;
import sugar.free.sightremote.database.EndOfTBR;
import sugar.free.sightremote.database.Offset;
import sugar.free.sightremote.database.PumpStatusChanged;
import sugar.free.sightremote.database.TimeChanged;

public class HistorySyncService extends Service implements StatusCallback, TaskRunner.ResultCallback, ServiceConnectionCallback {

    private DatabaseHelper databaseHelper = null;
    private SightServiceConnector connector;
    private PowerManager powerManager;
    private AlarmManager alarmManager;
    private PowerManager.WakeLock wakeLock;
    private String pumpSerialNumber;
    private boolean syncing;

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(broadcastReceiver);
        if (databaseHelper == null) {
            OpenHelperManager.releaseHelper();
            databaseHelper = null;
        }
    }

    public DatabaseHelper getDatabaseHelper() {
        if (databaseHelper == null) {
            databaseHelper = OpenHelperManager.getHelper(this, DatabaseHelper.class);
        }
        return databaseHelper;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HistorySyncService");
        connector = new SightServiceConnector(this);
        connector.addStatusCallback(this);
        connector.setConnectionCallback(this);
        getApplicationContext().registerReceiver(broadcastReceiver, new IntentFilter(HistoryBroadcast.ACTION_START_SYNC));
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, AlarmManager.INTERVAL_FIFTEEN_MINUTES, AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                PendingIntent.getBroadcast(this, 0, new Intent(HistoryBroadcast.ACTION_START_SYNC), 0));
        return START_STICKY;
    }

    @Override
    public void onStatusChange(Status status) {
        if (status == Status.CONNECTED) {
            connector.connect();
            ReadStatusParamBlockMessage readMessage = new ReadStatusParamBlockMessage();
            readMessage.setStatusBlockId(SystemIdentificationBlock.ID);
            new SingleMessageTaskRunner(connector, readMessage).fetch(this);
        } else if (status == Status.DISCONNECTED) {
            connector.disconnect();
            connector.disconnectFromService();
            syncing = false;
            sendBroadcast(new Intent(HistoryBroadcast.ACTION_SYNC_FINISHED));
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }

    @Override
    public void onResult(Object result){
        if (result instanceof ReadStatusParamBlockMessage) {
            pumpSerialNumber = ((SystemIdentificationBlock) ((ReadStatusParamBlockMessage) result).getStatusBlock()).getSerialNumber();
            new ReadHistoryTaskRunner(connector, createOpenMessage(HistoryType.ALL)).fetch(this);
        } else if (result instanceof ReadHistoryTaskRunner.HistoryResult) {
            ReadHistoryTaskRunner.HistoryResult historyResult = (ReadHistoryTaskRunner.HistoryResult) result;
            List<HistoryFrame> historyFrames = historyResult.getHistoryFrames();
            if (historyResult.getLatestEventNumber() > 0) Offset.setOffset(getDatabaseHelper(), pumpSerialNumber, HistoryType.ALL, historyResult.getLatestEventNumber());
            historyFrames.addAll(historyFrames);
            connector.disconnect();
            connector.disconnectFromService();
            processHistoryFrames(historyFrames);
            syncing = false;
            sendBroadcast(new Intent(HistoryBroadcast.ACTION_SYNC_FINISHED));
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }

    private void processHistoryFrames(List<HistoryFrame> historyFrames) {
        List<BolusDelivered> bolusDeliveredEntries = new ArrayList<>();
        List<BolusProgrammed> bolusProgrammedEntries = new ArrayList<>();
        List<EndOfTBR> endOfTBREntries = new ArrayList<>();
        List<PumpStatusChanged> pumpStatusChangedEntries = new ArrayList<>();
        List<CannulaFilled> cannulaFilledEntries = new ArrayList<>();
        List<TimeChanged> timeChangedEntries = new ArrayList<>();
        for (HistoryFrame historyFrame : historyFrames) {
            Log.d("HistorySyncService", "Received " + historyFrame.getClass().getSimpleName());
            if (historyFrame instanceof BolusDeliveredFrame)
                bolusDeliveredEntries.add(processBolusDeliveredFrame((BolusDeliveredFrame) historyFrame));
            else if (historyFrame instanceof BolusProgrammedFrame)
                bolusProgrammedEntries.add(processBolusProgrammedFrame((BolusProgrammedFrame) historyFrame));
            else if (historyFrame instanceof EndOfTBRFrame)
                endOfTBREntries.add(processEndOfTBRFrame((EndOfTBRFrame) historyFrame));
            else if (historyFrame instanceof PumpStatusChangedFrame)
                pumpStatusChangedEntries.add(processPumpStatusChangedFrame((PumpStatusChangedFrame) historyFrame));
            else if (historyFrame instanceof TimeChangedFrame)
                timeChangedEntries.add(processTimeChangedFrame((TimeChangedFrame) historyFrame));
            else if (historyFrame instanceof CannulaFilledFrame)
                cannulaFilledEntries.add(processCannulaFilledFrame((CannulaFilledFrame) historyFrame));
        }
        try {
            for (BolusDelivered bolusDelivered : bolusDeliveredEntries) {
                if (getDatabaseHelper().getBolusDeliveredDao().queryBuilder()
                        .where().eq("eventNumber", bolusDelivered.getEventNumber()).countOf() > 0) continue;
                getDatabaseHelper().getBolusDeliveredDao().create(bolusDelivered);
                Intent intent = new Intent();
                intent.setAction(HistoryBroadcast.ACTION_BOLUS_DELIVERED);
                intent.putExtra(HistoryBroadcast.EXTRA_BOLUS_ID, bolusDelivered.getBolusId());
                intent.putExtra(HistoryBroadcast.EXTRA_BOLUS_TYPE, bolusDelivered.getBolusType().toString());
                intent.putExtra(HistoryBroadcast.EXTRA_DURATION, bolusDelivered.getDuration());
                intent.putExtra(HistoryBroadcast.EXTRA_EVENT_NUMBER, bolusDelivered.getEventNumber());
                intent.putExtra(HistoryBroadcast.EXTRA_EXTENDED_AMOUNT, bolusDelivered.getExtendedAmount());
                intent.putExtra(HistoryBroadcast.EXTRA_IMMEDIATE_AMOUNT, bolusDelivered.getImmediateAmount());
                intent.putExtra(HistoryBroadcast.EXTRA_PUMP_SERIAL_NUMBER, bolusDelivered.getPump());
                intent.putExtra(HistoryBroadcast.EXTRA_EVENT_TIME, bolusDelivered.getDateTime());
                intent.putExtra(HistoryBroadcast.EXTRA_START_TIME, bolusDelivered.getStartTime());
                sendBroadcast(intent);
            }
            for (BolusProgrammed bolusProgrammed : bolusProgrammedEntries) {
                if (getDatabaseHelper().getBolusProgrammedDao().queryBuilder()
                        .where().eq("eventNumber", bolusProgrammed.getEventNumber()).query().size() > 0) continue;
                getDatabaseHelper().getBolusProgrammedDao().create(bolusProgrammed);
                Intent intent = new Intent();
                intent.setAction(HistoryBroadcast.ACTION_BOLUS_PROGRAMMED);
                intent.putExtra(HistoryBroadcast.EXTRA_BOLUS_ID, bolusProgrammed.getBolusId());
                intent.putExtra(HistoryBroadcast.EXTRA_BOLUS_TYPE, bolusProgrammed.getBolusType().toString());
                intent.putExtra(HistoryBroadcast.EXTRA_DURATION, bolusProgrammed.getDuration());
                intent.putExtra(HistoryBroadcast.EXTRA_EVENT_NUMBER, bolusProgrammed.getEventNumber());
                intent.putExtra(HistoryBroadcast.EXTRA_EXTENDED_AMOUNT, bolusProgrammed.getExtendedAmount());
                intent.putExtra(HistoryBroadcast.EXTRA_IMMEDIATE_AMOUNT, bolusProgrammed.getImmediateAmount());
                intent.putExtra(HistoryBroadcast.EXTRA_PUMP_SERIAL_NUMBER, bolusProgrammed.getPump());
                intent.putExtra(HistoryBroadcast.EXTRA_EVENT_TIME, bolusProgrammed.getDateTime());
                sendBroadcast(intent);
            }
            for (EndOfTBR endOfTBR : endOfTBREntries) {
                if (getDatabaseHelper().getEndOfTBRDao().queryBuilder()
                        .where().eq("eventNumber", endOfTBR.getEventNumber()).query().size() > 0) continue;
                getDatabaseHelper().getEndOfTBRDao().create(endOfTBR);
                Intent intent = new Intent();
                intent.setAction(HistoryBroadcast.ACTION_END_OF_TBR);
                intent.putExtra(HistoryBroadcast.EXTRA_DURATION, endOfTBR.getDuration());
                intent.putExtra(HistoryBroadcast.EXTRA_TBR_AMOUNT, endOfTBR.getAmount());
                intent.putExtra(HistoryBroadcast.EXTRA_EVENT_NUMBER, endOfTBR.getEventNumber());
                intent.putExtra(HistoryBroadcast.EXTRA_PUMP_SERIAL_NUMBER, endOfTBR.getPump());
                intent.putExtra(HistoryBroadcast.EXTRA_EVENT_TIME, endOfTBR.getDateTime());
                intent.putExtra(HistoryBroadcast.EXTRA_START_TIME, endOfTBR.getStartTime());
                sendBroadcast(intent);
            }
            for (PumpStatusChanged pumpStatusChanged : pumpStatusChangedEntries) {
                if (getDatabaseHelper().getPumpStatusChangedDao().queryBuilder()
                        .where().eq("eventNumber", pumpStatusChanged.getEventNumber()).query().size() > 0) continue;
                getDatabaseHelper().getPumpStatusChangedDao().create(pumpStatusChanged);
                Intent intent = new Intent();
                intent.setAction(HistoryBroadcast.ACTION_PUMP_STATUS_CHANGED);
                intent.putExtra(HistoryBroadcast.EXTRA_OLD_STATUS, pumpStatusChanged.getOldValue().toString());
                intent.putExtra(HistoryBroadcast.EXTRA_NEW_STATUS, pumpStatusChanged.getNewValue().toString());
                intent.putExtra(HistoryBroadcast.EXTRA_EVENT_NUMBER, pumpStatusChanged.getEventNumber());
                intent.putExtra(HistoryBroadcast.EXTRA_PUMP_SERIAL_NUMBER, pumpStatusChanged.getPump());
                intent.putExtra(HistoryBroadcast.EXTRA_EVENT_TIME, pumpStatusChanged.getDateTime());
                sendBroadcast(intent);
            }
            for (TimeChanged timeChanged : timeChangedEntries) {
                if (getDatabaseHelper().getTimeChangedDao().queryBuilder().
                        where().eq("eventNumber", timeChanged.getEventNumber()).query().size() > 0) continue;
                getDatabaseHelper().getTimeChangedDao().create(timeChanged);
                Intent intent = new Intent();
                intent.setAction(HistoryBroadcast.ACTION_TIME_CHANGED);
                intent.putExtra(HistoryBroadcast.EXTRA_EVENT_TIME, timeChanged.getDateTime());
                intent.putExtra(HistoryBroadcast.EXTRA_TIME_BEFORE, timeChanged.getTimeBefore());
                intent.putExtra(HistoryBroadcast.EXTRA_PUMP_SERIAL_NUMBER, timeChanged.getPump());
                intent.putExtra(HistoryBroadcast.EXTRA_EVENT_NUMBER, timeChanged.getEventNumber());
                sendBroadcast(intent);
            }
            for (CannulaFilled cannulaFilled : cannulaFilledEntries) {
                if (getDatabaseHelper().getCannulaFilledDao().queryBuilder()
                        .where().eq("eventNumber", cannulaFilled.getEventNumber()).query().size() > 0) continue;
                getDatabaseHelper().getCannulaFilledDao().create(cannulaFilled);
                Intent intent = new Intent();
                intent.setAction(HistoryBroadcast.ACTION_PUMP_STATUS_CHANGED);
                intent.putExtra(HistoryBroadcast.EXTRA_FILL_AMOUNT, cannulaFilled.getAmount());
                intent.putExtra(HistoryBroadcast.EXTRA_EVENT_NUMBER, cannulaFilled.getEventNumber());
                intent.putExtra(HistoryBroadcast.EXTRA_PUMP_SERIAL_NUMBER, cannulaFilled.getPump());
                intent.putExtra(HistoryBroadcast.EXTRA_EVENT_TIME, cannulaFilled.getDateTime());
                sendBroadcast(intent);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private EndOfTBR processEndOfTBRFrame(EndOfTBRFrame frame) {
        EndOfTBR endOfTBR = new EndOfTBR();
        endOfTBR.setDuration(frame.getDuration());
        endOfTBR.setAmount(frame.getAmount());
        endOfTBR.setEventNumber(frame.getEventNumber());
        endOfTBR.setPump(pumpSerialNumber);

        int eventTimeSeconds = frame.getEventHour() * 60 * 60 + frame.getEventMinute()  * 60 + frame.getEventSecond();
        int startTimeSeconds = frame.getStartHour() * 60 * 60 + frame.getStartMinute()  * 60 + frame.getStartSecond();
        boolean startedOnDayBefore = startTimeSeconds >= eventTimeSeconds;

        Date eventTime = parseDateTime(frame.getEventYear(), frame.getEventMonth(), frame.getEventDay(),
                frame.getEventHour(), frame.getEventMinute(), frame.getEventSecond());
        Date startTime = parseDateTime(frame.getEventYear(), frame.getEventMonth(), frame.getEventDay() - (startedOnDayBefore ? 1 : 0),
                frame.getStartHour(), frame.getStartMinute(), frame.getStartSecond());
        endOfTBR.setDateTime(eventTime);
        endOfTBR.setStartTime(startTime);
        return endOfTBR;
    }

    private PumpStatusChanged processPumpStatusChangedFrame(PumpStatusChangedFrame frame) {
        PumpStatusChanged pumpStatusChanged = new PumpStatusChanged();
        pumpStatusChanged.setOldValue(frame.getOldValue());
        pumpStatusChanged.setNewValue(frame.getNewValue());
        pumpStatusChanged.setEventNumber(frame.getEventNumber());
        pumpStatusChanged.setPump(pumpSerialNumber);

        Date eventTime = parseDateTime(frame.getEventYear(), frame.getEventMonth(), frame.getEventDay(),
                frame.getEventHour(), frame.getEventMinute(), frame.getEventSecond());
        pumpStatusChanged.setDateTime(eventTime);
        return pumpStatusChanged;
    }

    private BolusDelivered processBolusDeliveredFrame(BolusDeliveredFrame frame) {
        BolusDelivered bolusDelivered = new BolusDelivered();
        bolusDelivered.setBolusId(frame.getBolusId());
        bolusDelivered.setBolusType(frame.getBolusType());
        bolusDelivered.setDuration(frame.getDuration());
        bolusDelivered.setEventNumber(frame.getEventNumber());
        bolusDelivered.setExtendedAmount(frame.getExtendedAmount());
        bolusDelivered.setImmediateAmount(frame.getImmediateAmount());
        bolusDelivered.setPump(pumpSerialNumber);

        int eventTimeSeconds = frame.getEventHour() * 60 * 60 + frame.getEventMinute()  * 60 + frame.getEventSecond();
        int startTimeSeconds = frame.getStartHour() * 60 * 60 + frame.getStartMinute()  * 60 + frame.getStartSecond();
        boolean startedOnDayBefore = startTimeSeconds >= eventTimeSeconds;

        Date eventTime = parseDateTime(frame.getEventYear(), frame.getEventMonth(), frame.getEventDay(),
                frame.getEventHour(), frame.getEventMinute(), frame.getEventSecond());
        Date startTime = parseDateTime(frame.getEventYear(), frame.getEventMonth(), frame.getEventDay() - (startedOnDayBefore ? 1 : 0),
                frame.getStartHour(), frame.getStartMinute(), frame.getStartSecond());
        bolusDelivered.setDateTime(eventTime);
        bolusDelivered.setStartTime(startTime);
        return bolusDelivered;
    }

    private BolusProgrammed processBolusProgrammedFrame(BolusProgrammedFrame frame) {
        BolusProgrammed bolusProgrammed = new BolusProgrammed();
        bolusProgrammed.setBolusId(frame.getBolusId());
        bolusProgrammed.setBolusType(frame.getBolusType());
        bolusProgrammed.setDuration(frame.getDuration());
        bolusProgrammed.setEventNumber(frame.getEventNumber());
        bolusProgrammed.setExtendedAmount(frame.getExtendedAmount());
        bolusProgrammed.setImmediateAmount(frame.getImmediateAmount());
        bolusProgrammed.setPump(pumpSerialNumber);

        Date eventTime = parseDateTime(frame.getEventYear(), frame.getEventMonth(),
                frame.getEventDay(), frame.getEventHour(), frame.getEventMinute(), frame.getEventSecond());
        bolusProgrammed.setDateTime(eventTime);
        return bolusProgrammed;
    }

    private TimeChanged processTimeChangedFrame(TimeChangedFrame frame) {
        TimeChanged timeChanged = new TimeChanged();
        timeChanged.setEventNumber(frame.getEventNumber());
        timeChanged.setPump(pumpSerialNumber);

        Date eventTime = parseDateTime(frame.getEventYear(), frame.getEventMonth(),
                frame.getEventDay(), frame.getEventHour(), frame.getEventMinute(), frame.getEventSecond());
        timeChanged.setDateTime(eventTime);

        Date beforeTime = parseDateTime(frame.getBeforeYear(), frame.getBeforeMonth(),
                frame.getBeforeDay(), frame.getBeforeHour(), frame.getBeforeMinute(), frame.getBeforeSecond());
        timeChanged.setTimeBefore(beforeTime);

        return timeChanged;
    }

    private CannulaFilled processCannulaFilledFrame(CannulaFilledFrame frame) {
        CannulaFilled cannulaFilled = new CannulaFilled();
        cannulaFilled.setEventNumber(frame.getEventNumber());
        cannulaFilled.setPump(pumpSerialNumber);
        cannulaFilled.setAmount(frame.getAmount());

        Date eventTime = parseDateTime(frame.getEventYear(), frame.getEventMonth(),
                frame.getEventDay(), frame.getEventHour(), frame.getEventMinute(), frame.getEventSecond());
        cannulaFilled.setDateTime(eventTime);

        return cannulaFilled;
    }

    private OpenHistoryReadingSessionMessage createOpenMessage(HistoryType historyType) {
        OpenHistoryReadingSessionMessage openMessage = new OpenHistoryReadingSessionMessage();
        openMessage.setHistoryType(historyType);
        int offset = Offset.getOffset(getDatabaseHelper(), pumpSerialNumber, historyType);
        if (offset != -1) {
            openMessage.setOffset(offset + 1);
            openMessage.setReadingDirection(HistoryReadingDirection.FORWARD);
        } else {
            openMessage.setOffset(0xFFFFFFFF);
            openMessage.setReadingDirection(HistoryReadingDirection.BACKWARD);
        }
        return openMessage;
    }

    private Date parseDateTime(int year, int month, int day, int hour, int minute, int second) {
        Calendar calendar = new GregorianCalendar(TimeZone.getDefault(), Locale.getDefault());
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    @Override
    public void onError(Exception e) {
        e.printStackTrace();
        connector.disconnect();
        connector.disconnectFromService();
        syncing = false;
        sendBroadcast(new Intent(HistoryBroadcast.ACTION_SYNC_FINISHED));
        if (wakeLock.isHeld()) wakeLock.release();
    }

    private void startSync() {
        if (!wakeLock.isHeld()) wakeLock.acquire(60000);
        syncing = true;
        connector.connectToService();
        if (syncing) sendBroadcast(new Intent(HistoryBroadcast.ACTION_SYNC_STARTED));
    }

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(HistoryBroadcast.ACTION_START_SYNC)) {
                if (syncing) sendBroadcast(new Intent(HistoryBroadcast.ACTION_STILL_SYNCING));
                else startSync();
            }
        }
    };

    @Override
    public void onServiceConnected() {
        if (!connector.isUseable()) connector.disconnectFromService();
        else {
            connector.connect();
            if (connector.getStatus() == Status.CONNECTED) {
                onStatusChange(Status.CONNECTED);
            }
        }
    }

    @Override
    public void onServiceDisconnected() {
        syncing = false;
        if (wakeLock.isHeld()) wakeLock.release();
    }
}