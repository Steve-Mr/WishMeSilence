package com.maary.liveinpeace.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.maary.liveinpeace.Constants.Companion.ACTION_NAME_SETTINGS
import com.maary.liveinpeace.Constants.Companion.ALERT_TIME
import com.maary.liveinpeace.Constants.Companion.BROADCAST_ACTION_FOREGROUND
import com.maary.liveinpeace.Constants.Companion.BROADCAST_ACTION_MUTE
import com.maary.liveinpeace.Constants.Companion.BROADCAST_FOREGROUND_INTENT_EXTRA
import com.maary.liveinpeace.Constants.Companion.CHANNEL_ID_DEFAULT
import com.maary.liveinpeace.Constants.Companion.ID_NOTIFICATION_ALERT
import com.maary.liveinpeace.Constants.Companion.ID_NOTIFICATION_FOREGROUND
import com.maary.liveinpeace.Constants.Companion.ID_NOTIFICATION_GROUP_FORE
import com.maary.liveinpeace.Constants.Companion.MODE_IMG
import com.maary.liveinpeace.Constants.Companion.MODE_NUM
import com.maary.liveinpeace.Constants.Companion.PREF_ICON
import com.maary.liveinpeace.Constants.Companion.PREF_NOTIFY_TEXT_SIZE
import com.maary.liveinpeace.Constants.Companion.PREF_WATCHING_CONNECTING_TIME
import com.maary.liveinpeace.Constants.Companion.SHARED_PREF
import com.maary.liveinpeace.DeviceMapChangeListener
import com.maary.liveinpeace.DeviceTimer
import com.maary.liveinpeace.HistoryActivity
import com.maary.liveinpeace.R
import com.maary.liveinpeace.database.Connection
import com.maary.liveinpeace.database.ConnectionDao
import com.maary.liveinpeace.database.ConnectionRoomDatabase
import com.maary.liveinpeace.receiver.MuteMediaReceiver
import com.maary.liveinpeace.receiver.SettingsReceiver
import com.maary.liveinpeace.receiver.VolumeReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

class ForegroundService: Service() {

    private lateinit var database: ConnectionRoomDatabase
    private lateinit var connectionDao: ConnectionDao

    private val deviceTimerMap: MutableMap<String, DeviceTimer> = mutableMapOf()

    private val volumeDrawableIds = intArrayOf(
        R.drawable.ic_volume_silent,
        R.drawable.ic_volume_low,
        R.drawable.ic_volume_middle,
        R.drawable.ic_volume_high,
        R.drawable.ic_volume_mega
    )

    private lateinit var volumeComment: Array<String>

    private lateinit var audioManager: AudioManager

    companion object {
        private var isForegroundServiceRunning = false

        @JvmStatic
        fun isForegroundServiceRunning(): Boolean {
            return isForegroundServiceRunning
        }

        private val deviceMap: MutableMap<String, Connection> = mutableMapOf()

        // 在伴生对象中定义一个静态方法，用于其他类访问deviceMap
        fun getConnections(): MutableList<Connection> {
            return deviceMap.values.toMutableList()
        }

        private val deviceMapChangeListeners: MutableList<DeviceMapChangeListener> = mutableListOf()

        fun addDeviceMapChangeListener(listener: DeviceMapChangeListener) {
            deviceMapChangeListeners.add(listener)
        }

        fun removeDeviceMapChangeListener(listener: DeviceMapChangeListener) {
            deviceMapChangeListeners.remove(listener)
        }
    }

    private fun notifyDeviceMapChange() {
        deviceMapChangeListeners.forEach { listener ->
            listener.onDeviceMapChanged(deviceMap)
        }
    }

    private fun getVolumePercentage(context: Context): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return 100 * currentVolume / maxVolume
    }

    private fun getVolumeLevel(percent: Int): Int {
        return when(percent) {
            in 0..0 -> 0
            in 1..25 -> 1
            in 26..50 -> 2
            in 50..80 -> 3
            else -> 4
        }
    }

    private val volumeChangeReceiver = object : VolumeReceiver() {
        @SuppressLint("MissingPermission")
        override fun updateNotification(context: Context) {
            Log.v("MUTE_TEST", "VOLUME_CHANGE_RECEIVER")
            with(NotificationManagerCompat.from(applicationContext)){
                notify(ID_NOTIFICATION_FOREGROUND, createForegroundNotification(applicationContext))
            }
        }
    }

    private fun saveDataWhenStop(){
        val disconnectedTime = System.currentTimeMillis()

        for ( (deviceName, connection) in deviceMap){

            val connectedTime = connection.connectedTime
            val connectionTime = disconnectedTime - connectedTime!!

            CoroutineScope(Dispatchers.IO).launch {
                connectionDao.insert(
                    Connection(
                        name = connection.name,
                        type = connection.type,
                        connectedTime = connection.connectedTime,
                        disconnectedTime = disconnectedTime,
                        duration = connectionTime,
                        date = connection.date
                    )
                )
            }
            deviceMap.remove(deviceName)
        }
        return
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        @SuppressLint("MissingPermission")
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {

            val connectedTime = System.currentTimeMillis()

            // 在设备连接时记录设备信息和接入时间
            addedDevices?.forEach { deviceInfo ->
                if (deviceInfo.type in listOf(
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                        AudioDeviceInfo.TYPE_BUILTIN_MIC,
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
                        AudioDeviceInfo.TYPE_FM_TUNER,
                        AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
                        AudioDeviceInfo.TYPE_TELEPHONY,
                        28,
                    )
                ) { return@forEach }
                val deviceName = deviceInfo.productName.toString().trim()
                if (deviceName == android.os.Build.MODEL) return@forEach
                Log.v("MUTE_DEVICE", deviceName)
                Log.v("MUTE_TYPE", deviceInfo.type.toString())
                deviceMap[deviceName] = Connection(
                    id=1,
                    name = deviceInfo.productName.toString(),
                    type = deviceInfo.type,
                    connectedTime = connectedTime,
                    disconnectedTime = null,
                    duration = null,
                    date = LocalDate.now().toString()
                )
                notifyDeviceMapChange()
                // 执行其他逻辑，比如将设备信息保存到数据库或日志中
            }

            val sharedPreferences = getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE)
            if (sharedPreferences.getBoolean(PREF_WATCHING_CONNECTING_TIME, false)){
                for ((productName, _) in deviceMap){
                    if (deviceTimerMap.containsKey(productName)) continue
                    val deviceTimer = DeviceTimer(context = applicationContext, deviceName = productName)
                    Log.v("MUTE_DEVICEMAP", productName)
                    deviceTimer.start()
                    deviceTimerMap[productName] = deviceTimer
                }
            }

            Log.v("MUTE_MAP", deviceMap.toString())

            // Handle newly added audio devices
            with(NotificationManagerCompat.from(applicationContext)){
                notify(ID_NOTIFICATION_FOREGROUND, createForegroundNotification(applicationContext))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {

            // 在设备连接时记录设备信息和接入时间
            removedDevices?.forEach { deviceInfo ->
                val deviceName = deviceInfo.productName.toString()
                val disconnectedTime = System.currentTimeMillis()

                if (deviceMap.containsKey(deviceName)){

                    val connectedTime = deviceMap[deviceName]?.connectedTime
                    val connectionTime = disconnectedTime - connectedTime!!

                    if (connectionTime > ALERT_TIME){
                        val notificationManager: NotificationManager =
                            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(ID_NOTIFICATION_ALERT)
                    }

                    val baseConnection = deviceMap[deviceName]
                    CoroutineScope(Dispatchers.IO).launch {
                        if (baseConnection != null) {
                            connectionDao.insert(
                                Connection(
                                    name = baseConnection.name,
                                    type = baseConnection.type,
                                    connectedTime = baseConnection.connectedTime,
                                    disconnectedTime = disconnectedTime,
                                    duration = connectionTime,
                                    date = baseConnection.date
                                    )
                            )
                        }
                    }

                    deviceMap.remove(deviceName)
                    notifyDeviceMapChange()
                }

                val sharedPreferences = getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE)
                if (sharedPreferences.getBoolean(PREF_WATCHING_CONNECTING_TIME, false)){
                    if (deviceTimerMap.containsKey(deviceName)){
                        deviceTimerMap[deviceName]?.stop()
                        deviceTimerMap.remove(deviceName)
                    }
                }
                // 执行其他逻辑，比如将设备信息保存到数据库或日志中
            }

            // Handle removed audio devices
            with(NotificationManagerCompat.from(applicationContext)){
                notify(ID_NOTIFICATION_FOREGROUND, createForegroundNotification(applicationContext))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        Log.v("MUTE_TEST", "ON_CREATE")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        // 注册音量变化广播接收器
        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        registerReceiver(volumeChangeReceiver, filter)

        database = ConnectionRoomDatabase.getDatabase(applicationContext)
        connectionDao = database.connectionDao()
        startForeground(ID_NOTIFICATION_FOREGROUND, createForegroundNotification(context = applicationContext))
        notifyForegroundServiceState(true)
        Log.v("MUTE_TEST", "ON_CREATE_FINISH")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.v("MUTE_TEST", "ON_START_COMMAND")
        // 返回 START_STICKY，以确保 Service 在被终止后能够自动重启
        return START_STICKY
    }

    override fun onDestroy() {
        notifyForegroundServiceState(false)

        Log.v("MUTE_TEST", "ON_DESTROY")

        saveDataWhenStop()
        // 取消注册音量变化广播接收器
        unregisterReceiver(volumeChangeReceiver)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ID_NOTIFICATION_FOREGROUND)
//        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.v("MUTE_TEST", "ON_DESTROY_FINISH")
        super.onDestroy()
    }

    override fun onBind(p0: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    private fun notifyForegroundServiceState(isRunning: Boolean) {
        isForegroundServiceRunning = isRunning
        val intent = Intent(BROADCAST_ACTION_FOREGROUND)
        intent.putExtra(BROADCAST_FOREGROUND_INTENT_EXTRA, isRunning)
        sendBroadcast(intent)
    }

    @SuppressLint("LaunchActivityFromNotification")
    fun createForegroundNotification(context: Context): Notification {
        val currentVolume = getVolumePercentage(context)
        val currentVolumeLevel = getVolumeLevel(currentVolume)
        volumeComment = resources.getStringArray(R.array.array_volume_comment)
        val nIcon = generateNotificationIcon(context,
            getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE).getInt(PREF_ICON, MODE_IMG))

        val settingsIntent = Intent(this, SettingsReceiver::class.java).apply {
            action = ACTION_NAME_SETTINGS
        }
        val snoozePendingIntent: PendingIntent =
            PendingIntent.getBroadcast(this, 0, settingsIntent, PendingIntent.FLAG_IMMUTABLE)

        val actionSettings : NotificationCompat.Action = NotificationCompat.Action.Builder(
            R.drawable.ic_baseline_settings_24,
            resources.getString(R.string.settings),
            snoozePendingIntent
        ).build()

        val historyIntent = Intent(this, HistoryActivity::class.java)
        historyIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

        val pendingHistoryIntent = PendingIntent.getActivity(context, 0, historyIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val actionHistory: NotificationCompat.Action = NotificationCompat.Action.Builder(
            R.drawable.ic_action_history,
            resources.getString(R.string.history),
            pendingHistoryIntent
        ).build()

        val muteMediaIntent = Intent(context, MuteMediaReceiver::class.java)
        muteMediaIntent.action = BROADCAST_ACTION_MUTE
        val pendingMuteIntent = PendingIntent.getBroadcast(context, 0, muteMediaIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // 将 Service 设置为前台服务，并创建一个通知
        return NotificationCompat.Builder(this, CHANNEL_ID_DEFAULT)
            .setContentTitle(getString(R.string.to_be_or_not))
            .setOnlyAlertOnce(true)
            .setContentText(String.format(
                resources.getString(R.string.current_volume_percent),
                volumeComment[currentVolumeLevel],
                currentVolume))
            .setSmallIcon(nIcon)
            .setOngoing(true)
            .setContentIntent(pendingMuteIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(actionSettings)
            .addAction(actionHistory)
            .setGroup(ID_NOTIFICATION_GROUP_FORE)
            .setGroupSummary(false)
            .build()
    }

    private val textBounds = Rect()

    private fun generateNotificationIcon(context: Context, iconMode: Int): IconCompat {
        var currentVolume = getVolumePercentage(context)
        val currentVolumeLevel = getVolumeLevel(currentVolume)
        if (iconMode == MODE_NUM) {

            val iconSize =
                resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
            val background = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)

            val sharedPref = getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE)
            val textSizePref = sharedPref.getFloat(
                PREF_NOTIFY_TEXT_SIZE, 0.0f
            )

            val paint = Paint().apply {
                color = Color.WHITE
                typeface = context.resources.getFont(R.font.ndot_45)
                isFakeBoldText = true
                isAntiAlias = true
            }

            val canvas = Canvas(background)
            val canvasWidth = canvas.width
            val canvasHeight = canvas.height

            if (textSizePref == 0.0f) {

                paint.getTextBounds(99.toString(), 0, 99.toString().length, textBounds)
                val textWidth = textBounds.width()
                val textHeight = textBounds.height()
                val textSize = (canvasWidth / textWidth * textHeight).coerceAtMost(canvasHeight)
                paint.textSize = textSize.toFloat()
                with(sharedPref.edit()) {
                    putFloat(PREF_NOTIFY_TEXT_SIZE, textSize.toFloat())
                }
            } else {
                paint.textSize = textSizePref
            }

            var textToDraw = currentVolume.toString()
            if (currentVolume == 100) {
                currentVolume--
                textToDraw = "!!"
            }
            paint.getTextBounds(
                currentVolume.toString(), 0,
                currentVolume.toString().length, textBounds)
            canvas.drawText(
                textToDraw,
                (canvasWidth - textBounds.width()) / 2f,
                (canvasHeight + textBounds.height()) / 2f,
                paint
            )

            return IconCompat.createWithBitmap(background)
        }
        else {
            return IconCompat.createWithResource(context, volumeDrawableIds[currentVolumeLevel])
        }
    }
}