package dev.abhimanyu.core

import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasksTest {

    private val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
    private val pkg = "com.example.hr"

    private fun node(text: String? = null, id: String? = null, desc: String? = null, x: Float = 0.5f, y: Float = 0.5f) =
        ScreenNode(id, text, desc, "android.widget.Button", x, y, clickable = true)

    @Test fun matcherPrefersSameLabelEvenIfMoved() {
        val step = TaskStep(pkg, viewId = "$pkg:id/punch_in", text = "Punch In", className = "android.widget.Button", x = 0.5f, y = 0.8f)
        val moved = node("Punch In", "$pkg:id/punch_in", x = 0.5f, y = 0.6f)
        val other = node("Punch Out", "$pkg:id/punch_out", x = 0.5f, y = 0.8f)
        assertEquals(moved, StepMatcher.best(step, listOf(other, moved)))
    }

    @Test fun matcherNeverPicksAWrongLabelByPosition() {
        val step = TaskStep(pkg, text = "Punch In", x = 0.5f, y = 0.8f)
        assertNull(StepMatcher.best(step, listOf(node("Delete account", x = 0.5f, y = 0.8f))))
    }

    @Test fun unlabelledStepsNeedAVeryCloseMatch() {
        val step = TaskStep(pkg, className = "android.widget.ImageView", x = 0.9f, y = 0.1f)
        assertTrue(StepMatcher.best(step, listOf(node(x = 0.91f, y = 0.1f))) != null)
        assertNull(StepMatcher.best(step, listOf(node(x = 0.7f, y = 0.1f))))
    }

    @Test fun scheduleNextFire() {
        val s = TaskSchedule("t1", weekdays, 10, 0)
        // Wednesday 4 Nov 2026, 09:00 -> today 10:00
        assertEquals(LocalDateTime.of(2026, 11, 4, 10, 0), s.nextFire(LocalDateTime.of(2026, 11, 4, 9, 0)))
        // Friday 6 Nov 10:00 exactly -> next Monday
        assertEquals(LocalDateTime.of(2026, 11, 9, 10, 0), s.nextFire(LocalDateTime.of(2026, 11, 6, 10, 0)))
        // Saturday -> Monday
        assertEquals(LocalDateTime.of(2026, 11, 9, 10, 0), s.nextFire(LocalDateTime.of(2026, 11, 7, 12, 0)))
        assertNull(TaskSchedule("t1", emptySet(), 10, 0).nextFire(LocalDateTime.of(2026, 11, 4, 9, 0)))
        assertNull(s.copy(enabled = false).nextFire(LocalDateTime.of(2026, 11, 4, 9, 0)))
    }

    @Test fun codecRoundTrip() {
        val task = AppTask(
            "t1", "Clock in", pkg, "Spine HR",
            listOf(TaskStep(pkg, "$pkg:id/x", "Punch \"In\"", null, "android.widget.Button", 0.25f, 0.75f)),
            successText = "Punched in",
        )
        val sched = TaskSchedule("t1", weekdays, 19, 30)
        val decoded = TaskCodec.decode(TaskCodec.encode(listOf(task), listOf(sched)))
        assertEquals(listOf(task), decoded.tasks)
        assertEquals(listOf(sched), decoded.schedules)
        assertEquals(TaskCodec.Decoded(emptyList(), emptyList()), TaskCodec.decode("garbage"))
    }

    @Test fun blockedApps() {
        assertTrue(BlockedApps.isBlocked("com.phonepe.app"))
        assertTrue(BlockedApps.isBlocked("com.google.android.apps.photos"))
        assertTrue(BlockedApps.isBlocked("com.someone.mobilebanking"))
        assertTrue(BlockedApps.isBlocked("in.example.upi"))
        assertFalse(BlockedApps.isBlocked("com.whatsapp"))
        assertFalse(BlockedApps.isBlocked("com.spine.hrms"))
        assertTrue(BlockedApps.looksBlocked("XYZ Bank"))
        assertFalse(BlockedApps.looksBlocked("Spine HR"))
        assertFalse(BlockedApps.looksBlocked("Payroll Viewer"))
    }

    @Test fun taskNames() {
        val names = listOf("Clock in", "Clock out")
        assertEquals("Clock in", TaskNames.resolve("clock in karo", names))
        assertEquals("Clock out", TaskNames.resolve("please clock out", names))
        assertNull(TaskNames.resolve("clock", names))
        assertNull(TaskNames.resolve("flashlight on", names))
        assertEquals("Clock in late", TaskNames.resolve("clock in late", names + "Clock in late"))
    }

    @Test fun spokenTaskRunsThroughUnderstanding() {
        val p = Understanding.parseWithTasks("clock in karo", listOf("Clock in"))
        assertEquals(Command.RunTask("Clock in"), p?.command)
        assertEquals(Tier.SENSITIVE, p?.command?.tier)
        // Built-in commands still win.
        assertEquals(Command.Flashlight(true), Understanding.parseWithTasks("flashlight on", listOf("Clock in"))?.command)
    }

    @Test fun gemmaScheduleTasks() {
        val json = """{"transcript":"Monday to Friday clock in at 10 and clock out at 7:30 pm","language":"en","tool":"schedule_tasks",
            "schedules":[{"task_name":"Clock in","days":["mon","tue","wed","thu","fri"],"hour":10,"minute":0},
            {"task_name":"Clock out","days":["mon","tue","wed","thu","fri"],"hour":19,"minute":30}]}"""
        val d = Understanding.fromGemma(json, listOf("Clock in", "Clock out"))
        assertIs<Understanding.Decision.Run>(d)
        assertEquals(
            Command.ScheduleTasks(listOf(SpokenSchedule("Clock in", weekdays, 10, 0), SpokenSchedule("Clock out", weekdays, 19, 30))),
            d.parsed.command,
        )
        // A schedule with a bad entry is rejected as a whole, never half-applied.
        val bad = """{"transcript":"x","language":"en","tool":"schedule_tasks","schedules":[{"task_name":"Clock in","days":["mon"],"hour":10,"minute":0},{"task_name":"Clock out","days":[],"hour":19,"minute":30}]}"""
        assertNull(GemmaProtocol.read(bad)?.command)
    }

    @Test fun gemmaRunTask() {
        val d = Understanding.fromGemma("""{"transcript":"clock in karo","language":"hinglish","tool":"run_task","task_name":"Clock in"}""")
        assertEquals(Command.RunTask("Clock in"), (d as Understanding.Decision.Run).parsed.command)
        assertTrue("\"Clock in\"" in GemmaProtocol.systemPrompt(listOf("Clock in")))
    }

    @Test fun scheduleSummary() {
        assertEquals("Clock in at 10 AM, Monday to Friday", ReplyRenderer.scheduleSummary("Clock in", weekdays, 10, 0))
        assertEquals("Clock out at 7:30 PM, every day", ReplyRenderer.scheduleSummary("Clock out", DayOfWeek.entries.toSet(), 19, 30))
        assertEquals("Gym at 6 AM, Monday, Saturday", ReplyRenderer.scheduleSummary("Gym", setOf(SATURDAY, MONDAY), 6, 0))
    }
}
