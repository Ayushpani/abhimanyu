package dev.abhimanyu.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepliesTest {

    @Test fun timerInThreeLanguages() {
        val r = Reply.TimerSet(600)
        assertEquals("Timer set for 10 minutes.", ReplyRenderer.render(r, Lang.EN).text)
        val hi = ReplyRenderer.render(r, Lang.HI)
        assertEquals("10 मिनट का टाइमर लगा दिया।", hi.text)
        assertEquals("hi-IN", hi.locale)
        val hing = ReplyRenderer.render(r, Lang.HINGLISH)
        assertEquals("10 minute का timer लगा दिया।", hing.text)
        assertEquals("10 minute ka timer laga diya.", hing.caption)
    }

    @Test fun clockFormats() {
        assertEquals("6:30 AM", ReplyRenderer.clockEn(6, 30))
        assertEquals("7 PM", ReplyRenderer.clockEn(19, 0))
        assertEquals("12 AM", ReplyRenderer.clockEn(0, 0))
        assertEquals("सुबह 6 बजकर 30 मिनट", ReplyRenderer.clockHi(6, 30))
        assertEquals("शाम 7 बजे", ReplyRenderer.clockHi(19, 0))
        assertEquals("डेढ़ घंटे", ReplyRenderer.durationHi(5400))
        assertEquals("1 hour 30 minutes", ReplyRenderer.durationEn(5400))
    }

    @Test fun languagePreference() {
        assertEquals(Lang.EN, ReplyRenderer.resolveLang(Lang.HI, ReplyLanguagePref.ENGLISH))
        assertEquals(Lang.HI, ReplyRenderer.resolveLang(Lang.EN, ReplyLanguagePref.HINDI))
        assertEquals(Lang.HINGLISH, ReplyRenderer.resolveLang(Lang.HINGLISH, ReplyLanguagePref.MATCH))
    }

    @Test fun everyReplyRendersInEveryLanguage() {
        val replies = listOf(
            Reply.Flashlight(true), Reply.TimerSet(90), Reply.AlarmSet(6, 30, true), Reply.AlarmCancelRequested,
            Reply.VolumeSet(VolumeStream.RING, 40), Reply.Muted(VolumeStream.MEDIA, true), Reply.RingerSet(RingerMode.VIBRATE),
            Reply.DndSet(true, 60), Reply.TimeNow(18, 5), Reply.DateToday(LocalDate.of(2026, 11, 4)),
            Reply.Battery(80, true), Reply.MediaDone(MediaAction.NEXT), Reply.BrightnessSet(70),
            Reply.ReplyLanguageSet(ReplyLanguagePref.MATCH), Reply.CaptionsSet(false), Reply.Help, Reply.Stopped,
            Reply.NotUnderstood, Reply.NothingToRepeat, Reply.CantDoYet, Reply.LockNeedsControlMode,
            Reply.NeedsPermission(Reply.Permission.DND_ACCESS), Reply.Failed, Reply.ModelsMissing,
            Reply.TaskStarting("Clock in"), Reply.TaskDone("Clock in", true), Reply.TaskDone("Clock in", false),
            Reply.TaskUnknown("gym"), Reply.TaskDue("Clock in"), Reply.TasksScheduled(listOf("Clock in at 10 AM, Monday to Friday")),
            Reply.NeedsControlMode, Reply.Cancelled,
        ) + Reply.TaskFailure.entries.map { Reply.TaskFailed("Clock in", it, "Punch In") }
        for (r in replies) for (l in Lang.entries) {
            val u = ReplyRenderer.render(r, l)
            assertTrue(u.text.isNotBlank() && u.caption.isNotBlank() && u.fallback.isNotBlank(), "$r in $l")
            assertTrue(!Normalizer.containsDevanagari(u.fallback), "fallback must be Roman: $r in $l")
        }
    }

    @Test fun dateReply() {
        val d = LocalDate.of(2026, 11, 4)
        assertEquals("It's Wednesday, 4 November.", ReplyRenderer.render(Reply.DateToday(d), Lang.EN).text)
        assertEquals("आज बुधवार, 4 नवंबर है।", ReplyRenderer.render(Reply.DateToday(d), Lang.HI).text)
    }

    @Test fun understandingPrefersParsableConfidentTranscript() {
        val r = Understanding.choose(listOf(
            Transcript("torch band karo", 0.6f, "en"),
            Transcript("टॉर्च बंद करो", 0.9f, "hi"),
        ))
        assertEquals(Command.Flashlight(false), r.parsed?.command)
        assertEquals(Lang.HI, r.parsed?.lang)

        val r2 = Understanding.choose(listOf(
            Transcript("flashlight on", 0.8f, "en"),
            Transcript("फ्लैट", 0.95f, "hi"),
        ))
        assertEquals(Command.Flashlight(true), r2.parsed?.command)
        assertEquals(Lang.EN, r2.parsed?.lang)
    }

    @Test fun vocabularyIsClean() {
        assertTrue("flashlight" in Vocabulary.english())
        assertTrue("टॉर्च" in Vocabulary.hindi())
        assertTrue(Vocabulary.grammarJson(listOf("a", "b")) == "[\"a\", \"b\", \"[unk]\"]")
    }
}
