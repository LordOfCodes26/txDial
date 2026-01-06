package com.android.dialer

import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import com.goodwy.commons.RightApp
import com.goodwy.commons.extensions.notificationManager
import com.goodwy.commons.extensions.showErrorToast
import com.android.dialer.R
import com.android.dialer.extensions.*
import com.goodwy.commons.databases.PhoneNumberDatabase
import com.goodwy.commons.helpers.DatabasePhoneNumberFormatter
import com.goodwy.commons.helpers.PhoneNumberFormatManager
import com.goodwy.commons.helpers.PhonePrefixLocationHelper
import com.android.dialer.models.TimerEvent
import com.android.dialer.models.TimerState
import com.android.dialer.services.TimerStopService
import com.android.dialer.services.startTimerService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.TimeUnit

class App : RightApp(), LifecycleObserver {

    private var countDownTimers = mutableMapOf<Int, CountDownTimer>()

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        EventBus.getDefault().register(this)
        
        // Initialize phone number database
        PhoneNumberDatabase.getInstance(this)
        
        // Set database-based formatter as the custom formatter for PhoneNumberFormatManager
        // This integrates the database format system with the existing format manager
        PhoneNumberFormatManager.customFormatter = DatabasePhoneNumberFormatter(this)
        
        // Initialize phone prefix location data if not already loaded
        initializePhonePrefixLocations()
    }
    
    /**
     * Initialize phone prefix location data from database or load from assets/raw
     * This checks if data exists, and if not, loads it from a JSON file
     * Also loads district data
     */
    private fun initializePhonePrefixLocations() {
        val helper = PhonePrefixLocationHelper(this)
        
        // Load prefix locations (cities)
        helper.hasPrefixLocations { hasData ->
            if (!hasData) {
                // Option 1: Load from assets folder (create app/src/main/assets/phone_prefix_locations.json)
                // helper.loadFromAssets("phone_prefix_locations.json") { count ->
                //     android.util.Log.d("App", "Loaded $count prefix locations from assets")
                // }
                
                // Option 2: Load from raw folder (phone_prefix_locations.json already created)
                helper.loadFromRaw(R.raw.phone_prefix_locations) { count ->
                    android.util.Log.d("App", "Loaded $count prefix locations from raw")
                }
                
                // Option 3: Insert programmatically (see example below)
                // insertPrefixLocationsProgrammatically(helper)
            }
        }
        
        // Load districts
        helper.hasDistricts { hasData ->
            if (!hasData) {
                // Load from raw folder (phone_districts.json already created)
                helper.loadDistrictsFromRaw(R.raw.phone_districts) { count ->
                    android.util.Log.d("App", "Loaded $count districts from raw")
                }
            }
        }
        
        // Load phone number formats
        helper.hasFormats { hasData ->
            if (!hasData) {
                // Load from raw folder (phone_number_formats.json already created)
                helper.loadFormatsFromRaw(R.raw.phone_number_formats) { count ->
                    android.util.Log.d("App", "Loaded $count phone number formats from raw")
                }
            }
        }
    }
    
    /**
     * Example: Insert prefix locations programmatically
     * Replace this with your actual 200+ prefix to location mappings
     */
    private fun insertPrefixLocationsProgrammatically(helper: PhonePrefixLocationHelper) {
        val locations = listOf(
            com.goodwy.commons.models.PhonePrefixLocation(null, "01", ""),
            com.goodwy.commons.models.PhonePrefixLocation(null, "02", ""),
            com.goodwy.commons.models.PhonePrefixLocation(null, "03", ""),
            // ... add all 200+ mappings here
        )
        helper.insertPrefixLocations(locations) { count ->
            android.util.Log.d("App", "Inserted $count prefix locations programmatically")
        }
    }

    override fun onTerminate() {
        EventBus.getDefault().unregister(this)
        super.onTerminate()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    private fun onAppBackgrounded() {
        timerHelper.getTimers { timers ->
            if (timers.any { it.state is TimerState.Running }) {
                startTimerService(this)
            }
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    private fun onAppForegrounded() {
        EventBus.getDefault().post(TimerStopService)
        timerHelper.getTimers { timers ->
            val runningTimers = timers.filter { it.state is TimerState.Running }
            runningTimers.forEach { timer ->
                if (countDownTimers[timer.id] == null) {
                    EventBus.getDefault().post(
                        TimerEvent.Start(
                            timerId = timer.id!!,
                            duration = (timer.state as TimerState.Running).tick
                        )
                    )
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Reset) {
        updateTimerState(event.timerId, TimerState.Idle)
        countDownTimers[event.timerId]?.cancel()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Restart) {
        timerHelper.getTimer(event.timerId) { timer ->
            val duration = TimeUnit.SECONDS.toMillis(timer.seconds.toLong())
            updateTimerState(event.timerId, TimerState.Running(duration, duration))
        }
        countDownTimers[event.timerId]?.cancel()
        countDownTimers[event.timerId]?.start()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Delete) {
        countDownTimers[event.timerId]?.cancel()
        timerHelper.deleteTimer(event.timerId) {
            EventBus.getDefault().post(TimerEvent.Refresh)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Start) {
        val countDownTimer = object : CountDownTimer(event.duration, 1000) {
            override fun onTick(tick: Long) {
                updateTimerState(event.timerId, TimerState.Running(event.duration, tick))
            }

            override fun onFinish() {
                EventBus.getDefault().post(TimerEvent.Finish(event.timerId, event.duration))
                EventBus.getDefault().post(TimerStopService)
            }
        }.start()
        countDownTimers[event.timerId] = countDownTimer
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Finish) {
        timerHelper.getTimer(event.timerId) { timer ->
            val pendingIntent = getOpenTimerTabIntent(event.timerId)
            val notification = getTimerNotification(timer, pendingIntent)

            try {
                notificationManager.notify(event.timerId, notification)
            } catch (e: Exception) {
                showErrorToast(e)
            }

            updateTimerState(event.timerId, TimerState.Finished)
            Handler(Looper.getMainLooper()).postDelayed({
                hideNotification(event.timerId)
            }, config.timerMaxReminderSecs * 1000L)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Pause) {
        timerHelper.getTimer(event.timerId) { timer ->
            updateTimerState(
                event.timerId,
                TimerState.Paused(event.duration, (timer.state as TimerState.Running).tick)
            )
            countDownTimers[event.timerId]?.cancel()
        }
    }

    private fun updateTimerState(timerId: Int, state: TimerState) {
        timerHelper.getTimer(timerId) { timer ->
            val newTimer = timer.copy(state = state)
            if (newTimer.oneShot && state is TimerState.Idle) {
                timerHelper.deleteTimer(newTimer.id!!) {
                    EventBus.getDefault().post(TimerEvent.Refresh)
                }
            } else {
                timerHelper.insertOrUpdateTimer(newTimer) {
                    EventBus.getDefault().post(TimerEvent.Refresh)
                }
            }
        }
    }
}
