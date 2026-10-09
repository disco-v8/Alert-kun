package direct.kabu.alertkun.alarm

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType
import direct.kabu.alertkun.model.VibrationPattern
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AlarmOncePlaybackTest {
    // Notification/ringtone audio on Android 17 needs a foreground user context, unlike alarm audio.
    @get:Rule val visibleActivity = createComposeRule()
    private val playbackLabel = mutableStateOf("AlertKun playback test")
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val controller = AlarmController.getInstance(context)
    private val fixture = File(context.cacheDir, "alarm-once-test.wav")

    @Before fun prepare() {
        visibleActivity.setContent { Text(playbackLabel.value) }
        assertNull("Do not replace an existing live alarm", controller.pendingToken())
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        if (Build.VERSION.SDK_INT >= 31) shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        controller.stop()
        // One second PCM tone, independent of vendor ringtone duration and loop metadata.
        val samples = 8_000
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 8_000) * 1_000).toInt().toShort()) }
        fixture.writeBytes(bytes.array())
    }

    @After fun cleanup() {
        val current = PendingAlarmStore(context).load()?.event?.mailId
        if (current == null || current == "playback-test" || current.startsWith("continuous-")) controller.stop()
        fixture.delete()
    }

    @Test fun onceDoesNotLoopAndCompletionReleasesPlayerWithoutService() {
        assertEquals(AlarmDelivery.ONCE, controller.start(event(AlarmMode.ONCE)).delivery)
        val playing = player()!!
        assertFalse(playing.isLooping)
        assertTrue(playing.isPlaying)
        assertNull(controller.pendingToken())
        assertFalse(serviceRunning())
        await { player() == null }
        // Accessing a released player must fail, proving release rather than clearing the field.
        assertThrows(IllegalStateException::class.java) { playing.start() }
        assertEquals(AlarmState.IDLE, controller.currentState())
        assertFalse(serviceRunning())
    }

    @Test fun allSoundTypesPreserveOnceReleaseAndContinuousLooping() {
        val listeningType = InstrumentationRegistry.getArguments().getString("continuousListeningType")
        val continuousTypes = AlarmSoundType.entries.filter { listeningType == null || it.name == listeningType }
        assertTrue("Unknown listening sound type", continuousTypes.isNotEmpty())
        val listeningMs = InstrumentationRegistry.getArguments()
            .getString("continuousListeningSeconds")?.toLongOrNull()?.coerceIn(3, 30)?.times(1_000) ?: 3_000L
        for (type in if (listeningType == null) AlarmSoundType.entries else emptyList()) {
            assertEquals(AlarmDelivery.ONCE, controller.start(event(AlarmMode.ONCE).copy(soundType = type)).delivery)
            val once = player()!!
            assertFalse(once.isLooping)
            assertFalse(serviceRunning())
            assertPlaybackUsage(once, type, AlarmMode.ONCE)
            await { player() == null }
            assertThrows(IllegalStateException::class.java) { once.start() }
        }
        for (type in continuousTypes) {
            visibleActivity.runOnIdle { playbackLabel.value = "UNTIL_STOPPED: ${type.name}" }
            visibleActivity.waitForIdle()
            controller.start(event(AlarmMode.UNTIL_STOPPED).copy(
                mailId = "continuous-$type", soundType = type,
                soundUri = if (listeningType == null) fixture.toURI().toString() else null,
            ))
            await(90_000) { controller.currentState() == AlarmState.ACTIVE && player() != null }
            val continuous = player()!!
            assertEquals(type, PendingAlarmStore(context).load()!!.event.soundType)
            assertTrue(continuous.isLooping)
            assertPlaybackUsage(continuous, type, AlarmMode.UNTIL_STOPPED)
            if (Build.VERSION.SDK_INT >= 26) {
                val focus = AlarmController::class.java.getDeclaredField("focusRequest")
                    .apply { isAccessible = true }.get(controller) as AudioFocusRequest
                assertEquals(AlarmSoundMapping.usage(type), focus.audioAttributes.usage)
            }
            val audio = context.getSystemService(AudioManager::class.java)
            val stream = AlarmSoundMapping.legacyStreamType(type)
            Log.i("AlarmUsageTest", "UNTIL_STOPPED type=$type stream=$stream volume=${audio.getStreamVolume(stream)} muted=${audio.isStreamMute(stream)}")
            SystemClock.sleep(listeningMs)
            assertPlaybackUsage(continuous, type, AlarmMode.UNTIL_STOPPED)
            assertSame(continuous, player())
            assertTrue(continuous.isPlaying)
        }
        context.sendBroadcast(Intent(context, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.ACTION_STOP))
        await { player() == null && controller.pendingToken() == null && !serviceRunning() }
    }

    @Test fun deviceDefaultsAndInvalidUriFallbackPlayWithEachUsageWithoutChangingDeviceSettings() {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val ringerMode = audio.ringerMode
        val interruptionFilter = notifications.currentInterruptionFilter
        val volumes = AlarmSoundType.entries.associateWith { audio.getStreamVolume(AlarmSoundMapping.legacyStreamType(it)) }
        for (type in AlarmSoundType.entries) {
            for (uri in listOf(null, "content://direct.kabu.alertkun.invalid/sound")) {
                controller.start(event(AlarmMode.ONCE).copy(soundType = type, soundUri = uri))
                val playing = player()!!
                assertFalse(playing.isLooping)
                assertPlaybackUsage(playing, type, AlarmMode.ONCE)
                controller.stop()
                assertNull(player())
                assertThrows(IllegalStateException::class.java) { playing.start() }
            }
        }
        assertEquals(ringerMode, audio.ringerMode)
        assertEquals(interruptionFilter, notifications.currentInterruptionFilter)
        for ((type, volume) in volumes) assertEquals(volume, audio.getStreamVolume(AlarmSoundMapping.legacyStreamType(type)))
        assertFalse(serviceRunning())
    }

    private fun assertPlaybackUsage(playing: MediaPlayer, type: AlarmSoundType, mode: AlarmMode) {
        val expected = when (type) {
            AlarmSoundType.ALARM -> "USAGE_ALARM"
            AlarmSoundType.NOTIFICATION -> "USAGE_NOTIFICATION"
            AlarmSoundType.RINGTONE -> "USAGE_NOTIFICATION_RINGTONE"
        }
        val session = playing.audioSessionId
        val identity = "u/pid:${Process.myUid()}/${Process.myPid()}"
        val record = shell("dumpsys audio").lineSequence().firstOrNull {
            it.contains("AudioPlaybackConfiguration") && it.contains(identity) &&
                it.contains("sessionId:$session ") && it.contains("state:started")
        }
        assertNotNull("No active Android playback record for $type / $mode", record)
        assertTrue("Unexpected Android playback usage: $record", record!!.contains("usage=$expected "))
        Log.i("AlarmUsageTest", "$type / $mode: ${record.trim()}")
    }

    @Test fun deviceDefaultSoundIsNotLoopingAndCompletes() {
        controller.start(event(AlarmMode.ONCE).copy(soundUri = null))
        val playing = player()!!
        Log.i("AlarmOnceTest", "default durationMs=${playing.duration} looping=${playing.isLooping}")
        assertFalse(playing.isLooping)
        val duration = playing.duration
        shell("dumpsys media.player").lineSequence().filter { it.contains("autoLoop(") }
            .forEach { Log.i("AlarmOnceTest", "native flags: ${it.trim()}") }
        await(duration.toLong() + 5_000) { player() == null }
        assertThrows(IllegalStateException::class.java) { playing.start() }
        assertFalse(serviceRunning())
    }

    @Test fun finiteCountsPlayExactCyclesAndVibrationsThenReleaseWithoutService() {
        for (mode in listOf(AlarmMode.ONCE, AlarmMode.THREE_TIMES, AlarmMode.FIVE_TIMES)) {
            val before = vibrationHistory().toSet()
            val alarm = event(mode).copy(vibrationPattern = VibrationPattern.PATTERN_3)
            val players = collectFinitePlayers(alarm)
            assertEquals(mode.finiteCount, players.size)
            assertNull(player())
            assertFalse(serviceRunning())
            for (completed in players) assertThrows(IllegalStateException::class.java) { completed.start() }
            if (Build.VERSION.SDK_INT >= 31) {
                val cycles = vibrationHistory().filter { it !in before }
                assertEquals(mode.finiteCount, cycles.size)
                assertTrue(cycles.all { !it.contains("repeat=") })
                assertFalse(currentVibration().contains(context.packageName))
            }
        }
    }

    @Test fun autoLoopDefaultSoundFinishesExactlyOneThreeAndFiveCycles() {
        for (mode in listOf(AlarmMode.ONCE, AlarmMode.THREE_TIMES, AlarmMode.FIVE_TIMES)) {
            val players = collectFinitePlayers(event(mode).copy(soundUri = null, vibrationPattern = VibrationPattern.NONE), 90_000)
            assertEquals(mode.finiteCount, players.size)
            for (completed in players) assertThrows(IllegalStateException::class.java) { completed.start() }
            assertNull(player())
            assertFalse(serviceRunning())
        }
    }

    @Test fun newAlarmAndStopDiscardOldFiniteCycles() {
        controller.start(event(AlarmMode.FIVE_TIMES))
        val replaced = player()!!
        SystemClock.sleep(200)
        val replacement = collectFinitePlayers(event(AlarmMode.ONCE))
        assertEquals(1, replacement.size)
        assertThrows(IllegalStateException::class.java) { replaced.start() }
        SystemClock.sleep(1_300)
        assertNull(player())
        assertNull(finiteSequence())

        controller.start(event(AlarmMode.THREE_TIMES))
        val stopped = player()!!
        context.sendBroadcast(Intent(context, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.ACTION_STOP))
        await { player() == null && finiteSequence() == null }
        SystemClock.sleep(1_600)
        assertNull(player())
        assertThrows(IllegalStateException::class.java) { stopped.start() }
        assertFalse(serviceRunning())
    }

    @Test fun everyFiniteModeIsRejectedByExactAlarmAndServiceActivation() {
        for (mode in AlarmMode.entries.filter { it != AlarmMode.UNTIL_STOPPED }) {
            PendingAlarmStore(context).save(PendingAlarm("finite-token", event(mode)))
            controller.startFromExactAlarm("finite-token")
            assertFalse(controller.activate(AlarmPlaybackService(), "finite-token"))
            assertNull(player())
            assertFalse(serviceRunning())
        }
    }

    private fun collectFinitePlayers(alarm: AlarmEvent, timeout: Long = 20_000): Set<MediaPlayer> {
        assertEquals(AlarmDelivery.ONCE, controller.start(alarm).delivery)
        assertNull(controller.pendingToken())
        assertFalse(serviceRunning())
        val players = linkedSetOf<MediaPlayer>()
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (finiteSequence() != null && SystemClock.elapsedRealtime() < deadline) {
            synchronized(controller) {
                player()?.let { current ->
                    assertFalse(current.isLooping)
                    players.add(current)
                }
            }
            SystemClock.sleep(10)
        }
        assertNull("Finite alarm did not finish", finiteSequence())
        Log.i("AlarmCountTest", "${alarm.alarmMode}: cycles=${players.size}, released=${player() == null}")
        return players
    }

    private fun finiteSequence(): FiniteAlarmSequence? = synchronized(controller) {
        AlarmController::class.java.getDeclaredField("finiteSequence").apply { isAccessible = true }
            .get(controller) as FiniteAlarmSequence?
    }

    @Test fun ordinarySoundCompletionListenerReleasesPlayer() {
        controller.start(event(AlarmMode.ONCE))
        val playing = player()!!
        synchronized(controller) {
            // Disable only the duration safeguard to exercise the normal completion listener.
            val completion = AlarmController::class.java.getDeclaredField("onceCompletion").apply { isAccessible = true }
            val handler = AlarmController::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(controller) as Handler
            (completion.get(controller) as? Runnable)?.let(handler::removeCallbacks)
            completion.set(controller, null)
        }
        await { player() == null }
        assertThrows(IllegalStateException::class.java) { playing.start() }
    }

    @Test fun replacingOnceCancelsThePreviousDurationCallback() {
        controller.start(event(AlarmMode.ONCE))
        val previous = player()!!
        SystemClock.sleep(600)
        controller.start(event(AlarmMode.ONCE))
        val replacement = player()!!
        assertNotSame(previous, replacement)
        assertThrows(IllegalStateException::class.java) { previous.start() }
        SystemClock.sleep(550)
        assertSame(replacement, player())
        await { player() == null }
        assertThrows(IllegalStateException::class.java) { replacement.start() }
    }

    @Test fun onceHandoffCannotEnterExactAlarmOrServiceActivation() {
        PendingAlarmStore(context).save(PendingAlarm("once-token", event(AlarmMode.ONCE)))
        controller.startFromExactAlarm("once-token")
        assertFalse(controller.activate(AlarmPlaybackService(), "once-token"))
        assertEquals(AlarmState.IDLE, controller.currentState())
        assertNull(player())
        assertFalse(serviceRunning())
    }

    @Test fun untilStoppedLoopsPastSoundDurationAndStopBroadcastReleasesIt() {
        assertEquals(AlarmDelivery.EXACT_ALARM, controller.start(event(AlarmMode.UNTIL_STOPPED)).delivery)
        // setExactAndAllowWhileIdle is subject to OS delivery throttling after earlier tests.
        await(90_000) { controller.currentState() == AlarmState.ACTIVE && player() != null }
        val playing = player()!!
        assertTrue(playing.isLooping)
        assertTrue(serviceRunning())
        SystemClock.sleep(2_200)
        assertSame(playing, player())
        assertTrue(playing.isPlaying)
        context.sendBroadcast(Intent(context, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.ACTION_STOP))
        await { player() == null && controller.pendingToken() == null && !serviceRunning() }
        assertThrows(IllegalStateException::class.java) { playing.start() }
    }

    @Test fun onceVibrationFinishesAndUntilStoppedRepeatsUntilStop() {
        if (Build.VERSION.SDK_INT < 31) return
        val before = vibrationHistory()
        controller.start(event(AlarmMode.ONCE))
        await { vibrationHistory() != before }
        SystemClock.sleep(1_600)
        assertFalse(currentVibration().contains(context.packageName))
        assertTrue(vibrationHistory().last().contains("finished"))
        controller.start(event(AlarmMode.UNTIL_STOPPED))
        await(90_000) { controller.currentState() == AlarmState.ACTIVE }
        await { currentVibration().contains(context.packageName) }
        SystemClock.sleep(2_200)
        assertTrue(currentVibration().contains(context.packageName))
        context.sendBroadcast(Intent(context, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.ACTION_STOP))
        await { controller.currentState() == AlarmState.IDLE && !currentVibration().contains(context.packageName) }
        SystemClock.sleep(1_600)
        assertFalse(currentVibration().contains(context.packageName))
    }

    private fun currentVibration() = shell("dumpsys vibrator_manager")
        .substringAfter("CurrentVibration:").substringBefore("NextVibration:")

    private fun vibrationHistory() = shell("dumpsys vibrator_manager").lineSequence()
        .filter { it.contains("| ${context.packageName} (") }
        .distinct().sortedBy { it.trim().take(18) }.toList()

    private fun event(mode: AlarmMode) = AlarmEvent("playback-test", "rule", "Playback test", null,
        Uri.fromFile(fixture).toString(), VibrationPattern.PATTERN_1, mode)

    private fun player(): MediaPlayer? = synchronized(controller) {
        AlarmController::class.java.getDeclaredField("player").apply { isAccessible = true }.get(controller) as MediaPlayer?
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == AlarmPlaybackService::class.java.name }

    private fun await(timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Playback condition was not satisfied", condition())
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
        }
}
