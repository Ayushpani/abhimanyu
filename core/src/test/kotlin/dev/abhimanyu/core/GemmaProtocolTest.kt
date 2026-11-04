package dev.abhimanyu.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GemmaProtocolTest {

    private fun run(json: String): Parsed = (Understanding.fromGemma(json) as Understanding.Decision.Run).parsed

    @Test fun schemaAndExamplesAreValidJson() {
        assertTrue(MiniJson.parse(GemmaProtocol.SCHEMA) is Map<*, *>)
        // Every example line in the prompt must itself be readable by the interpreter.
        val examples = GemmaProtocol.SYSTEM_PROMPT.lines().filter { "->" in it }.map { it.substringAfter("-> ").trim() }
        assertTrue(examples.size >= 5)
        for (e in examples) assertTrue(GemmaProtocol.read(e) != null, e)
    }

    @Test fun mapsTools() {
        assertEquals(Command.Flashlight(false), run("""{"transcript":"torch band karo","language":"hinglish","tool":"flashlight","on":false}""").command)
        assertEquals(Command.SetTimer(600), run("""{"transcript":"दस मिनट का टाइमर लगाओ","language":"hi","tool":"set_timer","duration_seconds":600}""").command)
        assertEquals(
            Command.SetAlarm(AlarmTime(6, 30, Meridiem.AM, tomorrow = true)),
            run("""{"transcript":"kal subah saade chhe baje ka alarm","language":"hinglish","tool":"set_alarm","hour":6,"minute":30,"meridiem":"am","day":"tomorrow"}""").command,
        )
        assertEquals(Command.Volume(VolumeStream.RING, Adjust.Down), run("""{"transcript":"ring volume kam karo","language":"hinglish","tool":"volume","stream":"ring","direction":"down"}""").command)
        assertEquals(Command.Dnd(true, 60), run("""{"transcript":"DND on for one hour","language":"en","tool":"dnd","on":true,"duration_seconds":3600}""").command)
        assertEquals(Command.SetAlarm(AlarmTime(18, 0)), run("""{"transcript":"alarm 18:00","language":"en","tool":"set_alarm","hour":18,"minute":0,"meridiem":"pm","day":"next"}""").command)
    }

    @Test fun languageComesFromGemma() {
        assertEquals(Lang.HI, run("""{"transcript":"टॉर्च चालू करो","language":"hi","tool":"flashlight","on":true}""").lang)
        assertEquals(Lang.HINGLISH, run("""{"transcript":"torch on karo","language":"hinglish","tool":"flashlight","on":true}""").lang)
    }

    @Test fun invalidArgumentsFallBackToRuleParser() {
        // Gemma picked the right tool but forgot the argument; the transcript still parses.
        assertEquals(Command.SetTimer(300), run("""{"transcript":"timer 5 minutes","language":"en","tool":"set_timer"}""").command)
        // Out-of-range values are rejected, never clamped into an action.
        val d = Understanding.fromGemma("""{"transcript":"","language":"en","tool":"set_timer","duration_seconds":999999}""")
        assertIs<Understanding.Decision.NotHeard>(d)
    }

    @Test fun unsupportedAndSilence() {
        assertIs<Understanding.Decision.OutOfScope>(Understanding.fromGemma("""{"transcript":"send a WhatsApp to Rahul","language":"en","tool":"unsupported"}"""))
        assertIs<Understanding.Decision.NotHeard>(Understanding.fromGemma("""{"transcript":"","language":"en","tool":"nothing_heard"}"""))
    }

    @Test fun inventedToolsAndGarbageAreRejected() {
        assertNull(Understanding.fromGemma("""{"transcript":"pay rahul 500","language":"en","tool":"send_payment"}"""))
        assertNull(Understanding.fromGemma("not json"))
        assertNull(Understanding.fromGemma("""{"transcript":"x"}"""))
    }

    @Test fun toleratesCodeFences() {
        assertEquals(Command.TellTime, run("```json\n{\"transcript\":\"kitne baje hain\",\"language\":\"hinglish\",\"tool\":\"tell_time\"}\n```").command)
    }

    @Test fun miniJsonBasics() {
        assertEquals(mapOf("a" to listOf(1.0, true, null), "b" to "x\"yé"), MiniJson.parse("""{"a":[1,true,null],"b":"x\"yé"}"""))
    }
}
