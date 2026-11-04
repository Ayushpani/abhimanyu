package dev.abhimanyu.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandParserTest {

    private fun cmd(s: String) = CommandParser.parse(s)?.command
    private fun lang(s: String) = CommandParser.parse(s)?.lang

    @Test fun flashlight() {
        assertEquals(Command.Flashlight(true), cmd("flashlight on"))
        assertEquals(Command.Flashlight(true), cmd("turn on the torch"))
        assertEquals(Command.Flashlight(false), cmd("torch band karo"))
        assertEquals(Command.Flashlight(false), cmd("torch bandh kro"))
        assertEquals(Command.Flashlight(false), cmd("टॉर्च बंद करो"))
        assertEquals(Command.Flashlight(true), cmd("टॉर्च जलाओ"))
        assertEquals(Command.Flashlight(false), cmd("flashlight off"))
    }

    @Test fun timers() {
        assertEquals(Command.SetTimer(600), cmd("timer 10 minutes"))
        assertEquals(Command.SetTimer(300), cmd("set a timer for five minutes"))
        assertEquals(Command.SetTimer(600), cmd("das minute ka timer lagao"))
        assertEquals(Command.SetTimer(600), cmd("दस मिनट का टाइमर लगाओ"))
        assertEquals(Command.SetTimer(5400), cmd("dedh ghante ka timer"))
        assertEquals(Command.SetTimer(1800), cmd("timer for half an hour"))
        assertEquals(Command.SetTimer(3600), cmd("timer for an hour"))
        assertEquals(Command.SetTimer(5400), cmd("timer for an hour and a half"))
        assertEquals(Command.SetTimer(5400), cmd("timer 1 hour 30 minutes"))
        assertEquals(Command.SetTimer(120), cmd("do minute ka timer"))
        assertEquals(Command.SetTimer(1500), cmd("timer twenty five minutes"))
        assertEquals(Command.SetTimer(6300), cmd("paune do ghante ka timer"))
        assertEquals(Command.SetTimer(45), cmd("timer 45 seconds"))
    }

    @Test fun alarms() {
        assertEquals(Command.SetAlarm(AlarmTime(6, 30, Meridiem.AM, tomorrow = true)), cmd("alarm 6:30 am tomorrow"))
        assertEquals(Command.SetAlarm(AlarmTime(6, 30, null, tomorrow = true)), cmd("alarm 6:30 tomorrow"))
        assertEquals(Command.SetAlarm(AlarmTime(6, 30, Meridiem.AM)), cmd("set an alarm for six thirty a.m."))
        assertEquals(Command.SetAlarm(AlarmTime(6, 30, Meridiem.AM, true)), cmd("kal subah saade chhe baje ka alarm lagao"))
        assertEquals(Command.SetAlarm(AlarmTime(7, 45, null)), cmd("paune aath baje alarm lagao"))
        assertEquals(Command.SetAlarm(AlarmTime(9, 15, Meridiem.PM)), cmd("raat sava nau baje ka alarm"))
        assertEquals(Command.SetAlarm(AlarmTime(7, 0, Meridiem.PM)), cmd("शाम सात बजे का अलार्म लगाओ"))
        assertEquals(Command.SetAlarm(AlarmTime(6, 30, null)), cmd("alarm half past six"))
        assertEquals(Command.SetAlarm(AlarmTime(6, 45, null)), cmd("alarm quarter to seven"))
        assertEquals(Command.SetAlarm(AlarmTime(6, 10, Meridiem.AM)), cmd("subah chhe bajkar das minute ka alarm"))
        assertEquals(Command.SetAlarm(AlarmTime(2, 0, Meridiem.AM)), cmd("raat do baje ka alarm"))
        assertEquals(Command.SetAlarm(AlarmTime(1, 30, null)), cmd("dedh baje ka alarm"))
        assertEquals(Command.SetAlarm(AlarmTime(18, 0, null)), cmd("wake me up at 18:00"))
        assertEquals(Command.CancelAlarm(null), cmd("cancel my alarm"))
        assertEquals(Command.CancelAlarm(AlarmTime(6, 30, null)), cmd("alarm 6:30 hatao"))
    }

    @Test fun alarmResolution() {
        val now = LocalDateTime.of(2026, 11, 4, 20, 0)
        assertEquals(LocalDateTime.of(2026, 11, 5, 7, 0), AlarmTime(7, 0).nextOccurrence(now))
        assertEquals(LocalDateTime.of(2026, 11, 4, 21, 0), AlarmTime(9, 0).nextOccurrence(now))
        assertEquals(LocalDateTime.of(2026, 11, 5, 6, 30), AlarmTime(6, 30, tomorrow = true).nextOccurrence(now))
        assertEquals(LocalDateTime.of(2026, 11, 5, 18, 0), AlarmTime(6, 0, Meridiem.PM, tomorrow = true).nextOccurrence(now))
        assertEquals(LocalDateTime.of(2026, 11, 5, 0, 0), AlarmTime(12, 0, Meridiem.AM).nextOccurrence(now))
    }

    @Test fun volumeAndRinger() {
        assertEquals(Command.Volume(VolumeStream.MEDIA, Adjust.Up), cmd("volume up"))
        assertEquals(Command.Volume(VolumeStream.MEDIA, Adjust.To(50)), cmd("media volume 50 percent"))
        assertEquals(Command.Volume(VolumeStream.MEDIA, Adjust.To(50)), cmd("volume 50%"))
        assertEquals(Command.Volume(VolumeStream.RING, Adjust.Down), cmd("ring volume kam karo"))
        assertEquals(Command.Volume(VolumeStream.MEDIA, Adjust.Up), cmd("awaaz badhao"))
        assertEquals(Command.Volume(VolumeStream.MEDIA, Adjust.Up), cmd("आवाज़ बढ़ाओ"))
        assertEquals(Command.Volume(VolumeStream.ALARM, Adjust.To(100)), cmd("alarm volume full"))
        assertEquals(Command.Mute(VolumeStream.MEDIA, true), cmd("mute"))
        assertEquals(Command.Mute(VolumeStream.MEDIA, false), cmd("unmute"))
        assertEquals(Command.Ringer(RingerMode.SILENT), cmd("silent"))
        assertEquals(Command.Ringer(RingerMode.SILENT), cmd("phone silent karo"))
        assertEquals(Command.Ringer(RingerMode.VIBRATE), cmd("vibrate"))
        assertEquals(Command.Ringer(RingerMode.NORMAL), cmd("ring"))
    }

    @Test fun dnd() {
        assertEquals(Command.Dnd(true, 60), cmd("DND on for one hour"))
        assertEquals(Command.Dnd(true, null), cmd("turn on do not disturb"))
        assertEquals(Command.Dnd(false), cmd("dnd off"))
        assertEquals(Command.Dnd(false), cmd("dnd band karo"))
        assertEquals(Command.Dnd(true, 30), cmd("aadhe ghante tak pareshan mat karo"))
    }

    @Test fun mediaInfoAndBrightness() {
        assertEquals(Command.Media(MediaAction.PAUSE), cmd("pause"))
        assertEquals(Command.Media(MediaAction.NEXT), cmd("next song"))
        assertEquals(Command.Media(MediaAction.PREVIOUS), cmd("previous"))
        assertEquals(Command.Media(MediaAction.PAUSE), cmd("gaana band karo"))
        assertEquals(Command.Media(MediaAction.PLAY), cmd("gaana chalao"))
        assertEquals(Command.Media(MediaAction.NEXT), cmd("agla gaana"))
        assertEquals(Command.TellTime, cmd("kitne baje hain"))
        assertEquals(Command.TellTime, cmd("कितने बजे हैं"))
        assertEquals(Command.TellTime, cmd("what time is it"))
        assertEquals(Command.TellBattery, cmd("battery?"))
        assertEquals(Command.TellDate, cmd("what's the date today"))
        assertEquals(Command.TellDate, cmd("aaj kya tareekh hai"))
        assertEquals(Command.Brightness(Adjust.To(15)), cmd("brightness low"))
        assertEquals(Command.Brightness(Adjust.To(70)), cmd("brightness 70"))
        assertEquals(Command.Brightness(Adjust.Down), cmd("brightness kam karo"))
    }

    @Test fun assistantControls() {
        assertEquals(Command.Stop, cmd("stop"))
        assertEquals(Command.Stop, cmd("ruko"))
        assertEquals(Command.Stop, cmd("bas"))
        assertEquals(Command.Repeat, cmd("phir se"))
        assertEquals(Command.Repeat, cmd("do that again"))
        assertEquals(Command.Help, cmd("help"))
        assertEquals(Command.ReplyLanguage(ReplyLanguagePref.HINDI), cmd("hindi mein bolo"))
        assertEquals(Command.ReplyLanguage(ReplyLanguagePref.ENGLISH), cmd("always reply in english"))
        assertEquals(Command.ReplyLanguage(ReplyLanguagePref.MATCH), cmd("match my language"))
        assertEquals(Command.LockPhone, cmd("lock the phone"))
        assertEquals(Command.Captions(false), cmd("captions off"))
    }

    @Test fun unknownIsNull() {
        assertNull(cmd(""))
        assertNull(cmd("tell me a joke"))
        assertNull(cmd("send a message to rahul"))
    }

    @Test fun languageDetection() {
        assertEquals(Lang.EN, lang("flashlight on"))
        assertEquals(Lang.HINGLISH, lang("torch band karo"))
        assertEquals(Lang.HI, lang("टॉर्च बंद करो"))
        assertEquals(Lang.HI, lang("दस मिनट का टाइमर लगाओ"))
        assertEquals(Lang.HINGLISH, lang("टॉर्च ऑन करो"))
        assertEquals(Lang.EN, lang("set a timer for ten minutes"))
    }

    /** The Phase 1 acceptance list from the design doc. All 20 must parse. */
    @Test fun phaseOneTwentyCommands() {
        val commands = listOf(
            "flashlight on", "torch band karo", "timer 5 minutes", "das minute ka timer lagao",
            "alarm 6:30 tomorrow", "kal subah saade chhe baje ka alarm", "volume up", "media volume 50 percent",
            "mute", "silent", "vibrate", "DND on for one hour", "dnd off", "kitne baje hain", "battery?",
            "pause", "next song", "brightness low", "phir se", "aaj kya tareekh hai",
        )
        val failed = commands.filter { CommandParser.parse(it) == null }
        assertEquals(emptyList(), failed)
    }
}
