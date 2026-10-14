package com.wakequest.app

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.wakequest.app.alarm.AlarmService
import com.wakequest.app.alarm.RingState
import com.wakequest.app.alarm.Scheduler
import com.wakequest.app.data.Alarm
import com.wakequest.app.data.Mission
import com.wakequest.app.data.MissionType
import com.wakequest.app.data.Store
import com.wakequest.app.sound.SleepSoundService
import com.wakequest.app.ui.AlarmActivity
import com.wakequest.app.ui.MainActivity
import com.wakequest.app.ui.missions.MathMission
import com.wakequest.app.ui.missions.MemoryMission
import com.wakequest.app.ui.missions.TypingMission
import org.hamcrest.Matcher
import org.hamcrest.Matchers.equalTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

object T {
    val inst get() = InstrumentationRegistry.getInstrumentation()
    val ctx: Context get() = inst.targetContext

    fun tag(t: String): Matcher<View> = withTagValue(equalTo<Any>(t))

    fun shell(cmd: String) {
        val pfd: ParcelFileDescriptor = inst.uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    /** Screenshots are pulled by CI from /data/local/tmp/screens for review. */
    fun shot(name: String) {
        inst.waitForIdleSync()
        Thread.sleep(600)
        shell("mkdir -p /data/local/tmp/screens")
        shell("screencap -p /data/local/tmp/screens/$name.png")
    }

    fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(150)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    /** Waits until a view with this tag is on screen (activities may still be starting). */
    fun waitForView(tag: String, timeoutMs: Long = 15_000) {
        val end = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < end) {
            try {
                onView(tag(tag)).check(matches(isDisplayed()))
                return
            } catch (t: Throwable) {
                last = t
                Thread.sleep(250)
            }
        }
        throw AssertionError("View '$tag' never appeared", last)
    }

    fun click(tag: String) {
        waitForView(tag)
        onView(tag(tag)).perform(click())
    }

    /** Fires through AlarmManager exactly like a real alarm would. */
    fun ringSoon(id: Int, kind: Int) {
        Scheduler.fireAt(ctx, id, kind, System.currentTimeMillis() + 1500)
        waitFor("alarm $id to ring", 30_000) { RingState.ringing && RingState.alarm?.id == id }
    }

    fun openRingScreen() {
        ctx.startActivity(Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun newAlarm(vararg missions: Mission, wakeCheck: Boolean = false, snooze: Int = 5, label: String = "Test"): Alarm {
        val store = Store.get(ctx)
        val a = Alarm(
            id = store.newAlarmId(), hour = 7, minute = 0, missions = missions.toList(),
            wakeCheck = wakeCheck, wakeCheckMinutes = 3, snoozeMinutes = snooze, maxSnoozes = 2, label = label,
        )
        store.saveAlarm(a)
        return a
    }

    fun solveMath(problems: Int) {
        repeat(problems) {
            waitForView("math_input")
            val answer = MathMission.currentAnswer
            onView(tag("math_input")).perform(replaceText(answer.toString()), closeSoftKeyboard())
            onView(tag("math_submit")).perform(click())
            Thread.sleep(300)
        }
    }

    fun stopRinging() {
        if (RingState.ringing) {
            AlarmService.send(ctx, AlarmService.ACTION_DISMISS)
            waitFor("ring to stop") { !RingState.ringing }
        }
    }

    fun reset() {
        stopRinging()
        val store = Store.get(ctx)
        store.alarms().forEach { Scheduler.cancelAll(ctx, it.id) }
        store.clearAll()
        store.askedNotifications = true
        RingState.wakeCheckWindowMs = 60_000L
    }
}

@RunWith(AndroidJUnit4::class)
@LargeTest
class AlarmFlowTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA)

    @Before fun setUp() = T.reset()
    @After fun tearDown() = T.reset()

    @Test
    fun alarmFiresFromAlarmManagerAndMathMissionTurnsItOff() {
        val a = T.newAlarm(Mission(MissionType.MATH, 1, 2), label = "Morning run")
        T.ringSoon(a.id, Scheduler.KIND_SNOOZE)
        T.openRingScreen()
        T.waitForView("dismiss")
        T.shot("01_ringing")
        T.click("dismiss")
        T.waitForView("math_input")
        T.shot("02_math_mission")

        // A wrong answer must not turn it off
        onView(T.tag("math_input")).perform(replaceText("0"), closeSoftKeyboard())
        onView(T.tag("math_submit")).perform(click())
        Thread.sleep(500)
        assertTrue("wrong answer must keep ringing", RingState.ringing)

        T.solveMath(2)
        T.waitFor("alarm to stop") { !RingState.ringing }
        T.waitForView("morning_done")
        T.shot("03_morning_screen")

        val history = Store.get(T.ctx).history()
        assertEquals(1, history.size)
        assertEquals("Woke up", history[0].result)
        T.click("morning_done")
    }

    @Test
    fun backButtonCannotEscapeTheAlarm() {
        val a = T.newAlarm(Mission(MissionType.MATH, 1, 1))
        T.ringSoon(a.id, Scheduler.KIND_SNOOZE)
        T.openRingScreen()
        T.waitForView("dismiss")
        pressBack()
        Thread.sleep(500)
        assertTrue(RingState.ringing)
        T.waitForView("dismiss")
    }

    @Test
    fun snoozeStopsAndSchedulesAnotherRing() {
        val a = T.newAlarm(snooze = 5)
        T.ringSoon(a.id, Scheduler.KIND_MAIN)
        T.openRingScreen()
        T.click("snooze")
        T.waitFor("snooze to stop the ring") { !RingState.ringing }
        assertEquals(1, Store.get(T.ctx).snoozeCount(a.id))
        val next = T.ctx.getSystemService(AlarmManager::class.java).nextAlarmClock
        assertNotNull("a snooze alarm must be scheduled", next)
        val inMin = (next!!.triggerTime - System.currentTimeMillis()) / 60_000.0
        assertTrue("snooze in $inMin min", inMin in 4.0..5.1)
        // one-time alarm switched itself off after firing
        assertEquals(false, Store.get(T.ctx).alarm(a.id)!!.enabled)
    }

    @Test
    fun dismissSchedulesWakeCheckAndAnsweringItPasses() {
        val a = T.newAlarm(wakeCheck = true)
        T.ringSoon(a.id, Scheduler.KIND_MAIN)
        T.openRingScreen()
        T.click("dismiss") // no missions: turns straight off
        T.waitFor("ring to stop") { !RingState.ringing }
        val next = T.ctx.getSystemService(AlarmManager::class.java).nextAlarmClock
        assertNotNull("wake-up check must be scheduled", next)
        val inMin = (next!!.triggerTime - System.currentTimeMillis()) / 60_000.0
        assertTrue("wake check in $inMin min", inMin in 2.0..3.1)

        T.ringSoon(a.id, Scheduler.KIND_WAKECHECK)
        T.openRingScreen()
        T.waitForView("awake")
        T.shot("04_wake_check")
        T.click("awake")
        T.waitFor("check to stop") { !RingState.ringing }
        assertTrue(Store.get(T.ctx).history().any { it.result == "Passed wake-up check" })
    }

    @Test
    fun ignoredWakeCheckRingsAgainAndNeedsAMission() {
        RingState.wakeCheckWindowMs = 4_000L
        val a = T.newAlarm(Mission(MissionType.MATH, 1, 1), wakeCheck = true)
        T.ringSoon(a.id, Scheduler.KIND_WAKECHECK)
        T.openRingScreen()
        T.waitForView("awake")
        T.waitFor("escalation", 15_000) { RingState.escalated }
        T.waitForView("dismiss")
        T.shot("05_wake_check_failed")
        T.click("dismiss")
        T.solveMath(1)
        T.waitFor("ring to stop") { !RingState.ringing }
        assertTrue(Store.get(T.ctx).history()[0].result.startsWith("Fell back asleep"))
    }

    @Test
    fun typingMissionCanBeCompleted() {
        val a = T.newAlarm(Mission(MissionType.TYPING, 1, 1))
        T.ringSoon(a.id, Scheduler.KIND_TEST)
        T.openRingScreen()
        T.click("dismiss")
        T.waitForView("typing_input")
        T.shot("10_mission_typing")
        onView(T.tag("typing_input")).perform(replaceText(TypingMission.currentPhrase.lowercase()), closeSoftKeyboard())
        T.waitFor("typing to finish") { !RingState.ringing }
    }

    @Test
    fun memoryMissionCanBeCompleted() {
        val a = T.newAlarm(Mission(MissionType.MEMORY, 1, 1))
        T.ringSoon(a.id, Scheduler.KIND_TEST)
        T.openRingScreen()
        T.click("dismiss")
        T.waitForView("tile_0")
        T.shot("11_mission_memory_show")
        T.waitFor("pattern to hide") { MemoryMission.acceptingInput }
        MemoryMission.pattern.forEach { onView(T.tag("tile_$it")).perform(click()) }
        T.waitFor("memory to finish") { !RingState.ringing }
    }

    @Test
    fun otherMissionsRenderAndOfferBackupWhenNeeded() {
        val cases = listOf(
            "12_mission_shake" to Mission(MissionType.SHAKE, 2, 40),
            "13_mission_walk" to Mission(MissionType.STEPS, 2, 30),
            "14_mission_photo_missing_ref" to Mission(MissionType.PHOTO, 2, 1, ""),
            "15_mission_barcode" to Mission(MissionType.BARCODE, 2, 1, "4006381333931"),
        )
        for ((name, m) in cases) {
            val a = T.newAlarm(m)
            T.ringSoon(a.id, Scheduler.KIND_TEST)
            T.openRingScreen()
            T.click("dismiss")
            T.waitForView("mission_title")
            if (m.type == MissionType.PHOTO) {
                // no reference photo -> the backup mission must be offered, and it must work
                T.waitForView("fallback")
                T.shot(name)
                T.click("fallback")
                T.solveMath(4)
                T.waitFor("backup mission to finish") { !RingState.ringing }
            } else {
                T.shot(name)
                T.stopRinging()
            }
            Thread.sleep(500)
        }
    }

    @Test
    fun alarmSurvivesActivityBeingClosedAndReopened() {
        val a = T.newAlarm(Mission(MissionType.MATH, 1, 1))
        T.ringSoon(a.id, Scheduler.KIND_SNOOZE)
        T.openRingScreen()
        T.waitForView("dismiss")
        // user goes to the home screen: alarm keeps ringing
        T.shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1500)
        assertTrue(RingState.ringing)
        T.openRingScreen()
        T.click("dismiss")
        T.solveMath(1)
        T.waitFor("ring to stop") { !RingState.ringing }
    }
}

@RunWith(AndroidJUnit4::class)
@LargeTest
class ScreensTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA)

    @Before fun setUp() = T.reset()
    @After fun tearDown() {
        SleepSoundService.stop(T.ctx)
        T.reset()
    }

    @Test
    fun everyTabOpensAndCoreActionsWork() {
        val store = Store.get(T.ctx)
        store.saveAlarm(Alarm(store.newAlarmId(), 6, 30, setOf(1, 2, 3, 4, 5), label = "Work", missions = listOf(Mission(MissionType.MATH, 2, 3), Mission(MissionType.STEPS, 2, 30))))
        store.saveAlarm(Alarm(store.newAlarmId(), 9, 0, setOf(6, 7), enabled = false, label = "Weekend"))

        ActivityScenario.launch(MainActivity::class.java).use {
            T.waitForView("alarm_card_0")
            T.shot("20_alarms_tab")

            // toggling an alarm off/on works
            T.click("alarm_switch_1")
            T.waitFor("alarm to turn on") { store.alarms().first { a -> a.label == "Weekend" }.enabled }

            // Tonight: add a note
            onView(withId(R.id.nav_tonight)).perform(click())
            T.waitForView("note_input")
            onView(T.tag("note_input")).perform(replaceText("Call the dentist"), closeSoftKeyboard())
            onView(T.tag("note_add")).perform(scrollTo(), click())
            T.waitFor("note saved") { store.notes().any { n -> n.text == "Call the dentist" } }
            T.shot("21_tonight_tab")

            // Sounds: play and stop
            onView(withId(R.id.nav_sounds)).perform(click())
            T.waitForView("sound_play")
            onView(T.tag("sound_play")).perform(scrollTo(), click())
            T.waitFor("sleep sound to start", 10_000) { SleepSoundService.playing != null }
            Thread.sleep(1500)
            T.shot("22_sounds_tab_playing")
            onView(T.tag("sound_play")).perform(scrollTo(), click())
            T.waitFor("sleep sound to stop", 10_000) { SleepSoundService.playing == null }

            onView(withId(R.id.nav_stats)).perform(click())
            Thread.sleep(500)
            T.shot("23_stats_tab")

            onView(withId(R.id.nav_settings)).perform(click())
            T.waitForView("city_input")
            T.shot("24_settings_tab")

            // Edit screen opens and saves
            onView(withId(R.id.nav_alarms)).perform(click())
            T.click("alarm_card_0")
            T.waitForView("save_alarm")
            T.shot("25_edit_alarm")
            onView(T.tag("add_mission")).perform(scrollTo())
            T.shot("26_edit_alarm_missions")
            T.click("save_alarm")
            T.waitForView("new_alarm")
        }
    }

    @Test
    fun newAlarmIsSavedAndScheduled() {
        ActivityScenario.launch(MainActivity::class.java).use {
            T.click("new_alarm")
            T.waitForView("save_alarm")
            T.click("save_alarm")
            T.waitForView("alarm_card_0")
            val alarms = Store.get(T.ctx).alarms()
            assertEquals(1, alarms.size)
            assertTrue(alarms[0].enabled)
            assertNotNull(T.ctx.getSystemService(AlarmManager::class.java).nextAlarmClock)
        }
    }
}
