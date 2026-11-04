package dev.abhimanyu.core

import java.time.DayOfWeek
import java.time.LocalDate

/** What happened, independent of language. [ReplyRenderer] turns it into words. */
sealed interface Reply {
    data class Flashlight(val on: Boolean) : Reply
    data class TimerSet(val seconds: Int) : Reply
    data class AlarmSet(val hour: Int, val minute: Int, val tomorrow: Boolean) : Reply
    data object AlarmCancelRequested : Reply
    data class VolumeSet(val stream: VolumeStream, val percent: Int) : Reply
    data class Muted(val stream: VolumeStream, val mute: Boolean) : Reply
    data class RingerSet(val mode: RingerMode) : Reply
    data class DndSet(val on: Boolean, val minutes: Int?) : Reply
    data class TimeNow(val hour: Int, val minute: Int) : Reply
    data class DateToday(val date: LocalDate) : Reply
    data class Battery(val percent: Int, val charging: Boolean) : Reply
    data class MediaDone(val action: MediaAction) : Reply
    data class BrightnessSet(val percent: Int) : Reply
    data class ReplyLanguageSet(val pref: ReplyLanguagePref) : Reply
    data class CaptionsSet(val on: Boolean) : Reply
    data object Help : Reply
    data object Stopped : Reply
    data object NotUnderstood : Reply
    data object NothingToRepeat : Reply
    data object CantDoYet : Reply
    data object LockNeedsControlMode : Reply
    data object Locked : Reply
    data class NeedsPermission(val permission: Permission) : Reply
    data object Failed : Reply
    data object ModelsMissing : Reply

    // Taught tasks
    data class TaskStarting(val name: String) : Reply
    data class TaskDone(val name: String, val verified: Boolean) : Reply
    data class TaskFailed(val name: String, val reason: TaskFailure, val detail: String = "") : Reply
    data class TaskUnknown(val spoken: String) : Reply
    data class TaskDue(val name: String) : Reply
    data class TasksScheduled(val summaries: List<String>) : Reply
    data object NeedsControlMode : Reply
    data object Cancelled : Reply

    enum class Permission { MICROPHONE, DND_ACCESS, MODIFY_SETTINGS }
    enum class TaskFailure { NOT_FOUND, LOGIN_NEEDED, BLOCKED_APP, TIMEOUT, APP_MISSING, UNVERIFIED, CANCELLED }
}

/**
 * Words to speak. [locale] picks the TTS voice ("en-IN" or "hi-IN"). [caption] is what the
 * caption pill shows (Roman script for Hinglish). [fallback] is an English-voice-safe version
 * to speak if the Hindi voice isn't installed.
 */
data class Utterance(val text: String, val locale: String, val caption: String, val fallback: String)

/**
 * Fixed reply templates in English, Hindi and Hinglish. Replies are never free model text, so
 * nothing read off the screen can ever be spoken back.
 *
 * Hinglish is spoken by the Hindi voice, so its spoken text keeps Hindi words in Devanagari and
 * English words in Latin script; the caption is all Roman.
 */
object ReplyRenderer {

    fun resolveLang(detected: Lang, pref: ReplyLanguagePref): Lang = when (pref) {
        ReplyLanguagePref.MATCH -> detected
        ReplyLanguagePref.ENGLISH -> Lang.EN
        ReplyLanguagePref.HINDI -> if (detected == Lang.HINGLISH) Lang.HINGLISH else Lang.HI
    }

    fun render(reply: Reply, lang: Lang): Utterance {
        val en = english(reply)
        return when (lang) {
            Lang.EN -> Utterance(en, "en-IN", en, en)
            Lang.HI -> {
                val (spoken, roman) = hindi(reply)
                Utterance(spoken, "hi-IN", spoken, roman)
            }
            Lang.HINGLISH -> {
                val (spoken, roman) = hinglish(reply)
                Utterance(spoken, "hi-IN", roman, roman)
            }
        }
    }

    // ---------- English ----------

    private fun english(r: Reply): String = when (r) {
        is Reply.Flashlight -> if (r.on) "Flashlight on." else "Flashlight off."
        is Reply.TimerSet -> "Timer set for ${durationEn(r.seconds)}."
        is Reply.AlarmSet -> "Alarm set for ${clockEn(r.hour, r.minute)} ${if (r.tomorrow) "tomorrow" else "today"}."
        Reply.AlarmCancelRequested -> "Asked the Clock app to cancel the alarm."
        is Reply.VolumeSet -> "${streamEn(r.stream)} volume ${r.percent} percent."
        is Reply.Muted -> "${streamEn(r.stream)} ${if (r.mute) "muted" else "unmuted"}."
        is Reply.RingerSet -> when (r.mode) {
            RingerMode.SILENT -> "Phone on silent."
            RingerMode.VIBRATE -> "Phone on vibrate."
            RingerMode.NORMAL -> "Ringer on."
        }
        is Reply.DndSet -> when {
            !r.on -> "Do Not Disturb off."
            r.minutes != null -> "Do Not Disturb on for ${durationEn(r.minutes * 60)}."
            else -> "Do Not Disturb on."
        }
        is Reply.TimeNow -> "It's ${clockEn(r.hour, r.minute)}."
        is Reply.DateToday -> "It's ${dayEn(r.date.dayOfWeek)}, ${r.date.dayOfMonth} ${MONTHS_EN[r.date.monthValue - 1]}."
        is Reply.Battery -> "Battery is at ${r.percent} percent${if (r.charging) ", charging" else ""}."
        is Reply.MediaDone -> when (r.action) {
            MediaAction.PLAY -> "Playing."
            MediaAction.PAUSE -> "Paused."
            MediaAction.NEXT -> "Next track."
            MediaAction.PREVIOUS -> "Previous track."
        }
        is Reply.BrightnessSet -> "Brightness ${r.percent} percent."
        is Reply.ReplyLanguageSet -> when (r.pref) {
            ReplyLanguagePref.MATCH -> "Okay, I'll reply in the language you speak."
            ReplyLanguagePref.ENGLISH -> "Okay, I'll always reply in English."
            ReplyLanguagePref.HINDI -> "Okay, I'll always reply in Hindi."
        }
        is Reply.CaptionsSet -> if (r.on) "Captions on." else "Captions off."
        Reply.Help -> "I can do flashlight, volume, silent mode, Do Not Disturb, alarms, timers, " +
            "brightness, music controls, and tell you the time, date and battery."
        Reply.Stopped -> "Okay, stopped."
        Reply.NotUnderstood -> "Sorry, say that again?"
        Reply.NothingToRepeat -> "There's nothing to repeat yet."
        Reply.CantDoYet -> "I can't do that yet."
        Reply.LockNeedsControlMode -> "I can't lock the phone until control mode is turned on."
        Reply.Locked -> "Phone locked."
        is Reply.NeedsPermission -> when (r.permission) {
            Reply.Permission.MICROPHONE -> "I need microphone permission."
            Reply.Permission.DND_ACCESS -> "I need Do Not Disturb access. Opening settings."
            Reply.Permission.MODIFY_SETTINGS -> "I need permission to change system settings. Opening settings."
        }
        Reply.Failed -> "That didn't work."
        Reply.ModelsMissing -> "The speech model isn't installed yet. Import it in the app first."
        is Reply.TaskStarting -> "Starting ${r.name}."
        is Reply.TaskDone -> if (r.verified) "${r.name} done." else "${r.name}: I tapped every step, but couldn't confirm it worked. Please check."
        is Reply.TaskFailed -> "${r.name} stopped. " + when (r.reason) {
            Reply.TaskFailure.NOT_FOUND -> "I can't find ${r.detail}."
            Reply.TaskFailure.LOGIN_NEEDED -> "The app wants you to log in. Log in once, then try again."
            Reply.TaskFailure.BLOCKED_APP -> "I'm not allowed to use payment, banking or photo apps."
            Reply.TaskFailure.TIMEOUT -> "That took too long."
            Reply.TaskFailure.APP_MISSING -> "The app isn't installed."
            Reply.TaskFailure.UNVERIFIED -> "I didn't see ${r.detail}."
            Reply.TaskFailure.CANCELLED -> "Cancelled."
        }
        is Reply.TaskUnknown -> "I don't have a task called ${r.spoken}. Teach it in the app first."
        is Reply.TaskDue -> "Time to ${r.name.lowercase()}. Touch the fingerprint sensor to start."
        is Reply.TasksScheduled -> "Scheduled: ${r.summaries.joinToString("; ")}."
        Reply.NeedsControlMode -> "Control mode is off. Turn on Abhimanyu in Accessibility settings."
        Reply.Cancelled -> "Okay, cancelled."
    }

    // ---------- Hindi (spoken text, Roman fallback) ----------

    private fun hindi(r: Reply): Pair<String, String> = when (r) {
        is Reply.Flashlight -> if (r.on) "टॉर्च चालू कर दी।" to "Torch chalu kar di." else "टॉर्च बंद कर दी।" to "Torch band kar di."
        is Reply.TimerSet -> "${durationHi(r.seconds)} का टाइमर लगा दिया।" to "${durationRoman(r.seconds)} ka timer laga diya."
        is Reply.AlarmSet -> "${if (r.tomorrow) "कल" else "आज"} ${clockHi(r.hour, r.minute)} का अलार्म लगा दिया।" to
            "${if (r.tomorrow) "Kal" else "Aaj"} ${clockRoman(r.hour, r.minute)} ka alarm laga diya."
        Reply.AlarmCancelRequested -> "घड़ी ऐप से अलार्म हटाने को कह दिया।" to "Clock app se alarm hatane ko keh diya."
        is Reply.VolumeSet -> "${streamHi(r.stream)} की आवाज़ ${r.percent} प्रतिशत कर दी।" to
            "${streamRoman(r.stream)} ki awaaz ${r.percent} percent kar di."
        is Reply.Muted -> if (r.mute) "${streamHi(r.stream)} की आवाज़ बंद कर दी।" to "${streamRoman(r.stream)} ki awaaz band kar di."
            else "${streamHi(r.stream)} की आवाज़ चालू कर दी।" to "${streamRoman(r.stream)} ki awaaz chalu kar di."
        is Reply.RingerSet -> when (r.mode) {
            RingerMode.SILENT -> "फ़ोन साइलेंट पर कर दिया।" to "Phone silent par kar diya."
            RingerMode.VIBRATE -> "फ़ोन वाइब्रेट पर कर दिया।" to "Phone vibrate par kar diya."
            RingerMode.NORMAL -> "रिंगर चालू कर दिया।" to "Ringer chalu kar diya."
        }
        is Reply.DndSet -> when {
            !r.on -> "डू नॉट डिस्टर्ब बंद कर दिया।" to "Do Not Disturb band kar diya."
            r.minutes != null -> "${durationHi(r.minutes * 60)} के लिए डू नॉट डिस्टर्ब चालू कर दिया।" to
                "${durationRoman(r.minutes * 60)} ke liye Do Not Disturb chalu kar diya."
            else -> "डू नॉट डिस्टर्ब चालू कर दिया।" to "Do Not Disturb chalu kar diya."
        }
        is Reply.TimeNow -> "अभी ${clockHi(r.hour, r.minute)} हुए हैं।" to "Abhi ${clockRoman(r.hour, r.minute)} hue hain."
        is Reply.DateToday -> "आज ${DAYS_HI[r.date.dayOfWeek.value - 1]}, ${r.date.dayOfMonth} ${MONTHS_HI[r.date.monthValue - 1]} है।" to
            "Aaj ${DAYS_ROMAN[r.date.dayOfWeek.value - 1]}, ${r.date.dayOfMonth} ${MONTHS_EN[r.date.monthValue - 1]} hai."
        is Reply.Battery -> "बैटरी ${r.percent} प्रतिशत है${if (r.charging) ", चार्ज हो रही है" else ""}।" to
            "Battery ${r.percent} percent hai${if (r.charging) ", charge ho rahi hai" else ""}."
        is Reply.MediaDone -> when (r.action) {
            MediaAction.PLAY -> "चला दिया।" to "Chala diya."
            MediaAction.PAUSE -> "रोक दिया।" to "Rok diya."
            MediaAction.NEXT -> "अगला गाना।" to "Agla gaana."
            MediaAction.PREVIOUS -> "पिछला गाना।" to "Pichla gaana."
        }
        is Reply.BrightnessSet -> "रोशनी ${r.percent} प्रतिशत कर दी।" to "Roshni ${r.percent} percent kar di."
        is Reply.ReplyLanguageSet -> when (r.pref) {
            ReplyLanguagePref.MATCH -> "ठीक है, आप जिस भाषा में बोलेंगे, उसी में जवाब दूँगा।" to "Theek hai, aap jis bhasha mein bolenge, usi mein jawaab doonga."
            ReplyLanguagePref.ENGLISH -> "ठीक है, अब हमेशा अंग्रेज़ी में जवाब दूँगा।" to "Theek hai, ab hamesha English mein jawaab doonga."
            ReplyLanguagePref.HINDI -> "ठीक है, अब हमेशा हिंदी में जवाब दूँगा।" to "Theek hai, ab hamesha Hindi mein jawaab doonga."
        }
        is Reply.CaptionsSet -> if (r.on) "कैप्शन चालू।" to "Caption chalu." else "कैप्शन बंद।" to "Caption band."
        Reply.Help -> "मैं टॉर्च, आवाज़, साइलेंट मोड, डू नॉट डिस्टर्ब, अलार्म, टाइमर, रोशनी और गाने चला या रोक सकता हूँ, " +
            "और समय, तारीख और बैटरी बता सकता हूँ।" to
            "Main torch, awaaz, silent mode, Do Not Disturb, alarm, timer, roshni aur gaane sambhal sakta hoon, " +
            "aur samay, tareekh aur battery bata sakta hoon."
        Reply.Stopped -> "ठीक है, रोक दिया।" to "Theek hai, rok diya."
        Reply.NotUnderstood -> "माफ़ कीजिए, फिर से बोलिए?" to "Maaf kijiye, phir se boliye?"
        Reply.NothingToRepeat -> "दोहराने के लिए अभी कुछ नहीं है।" to "Dohraane ke liye abhi kuch nahi hai."
        Reply.CantDoYet -> "यह मैं अभी नहीं कर सकता।" to "Yeh main abhi nahi kar sakta."
        Reply.LockNeedsControlMode -> "कंट्रोल मोड चालू होने तक मैं फ़ोन लॉक नहीं कर सकता।" to "Control mode chalu hone tak main phone lock nahi kar sakta."
        Reply.Locked -> "फ़ोन लॉक कर दिया।" to "Phone lock kar diya."
        is Reply.NeedsPermission -> when (r.permission) {
            Reply.Permission.MICROPHONE -> "मुझे माइक्रोफ़ोन की अनुमति चाहिए।" to "Mujhe microphone ki anumati chahiye."
            Reply.Permission.DND_ACCESS -> "मुझे डू नॉट डिस्टर्ब की अनुमति चाहिए। सेटिंग खोल रहा हूँ।" to "Mujhe Do Not Disturb ki anumati chahiye. Settings khol raha hoon."
            Reply.Permission.MODIFY_SETTINGS -> "मुझे सिस्टम सेटिंग बदलने की अनुमति चाहिए। सेटिंग खोल रहा हूँ।" to "Mujhe system settings badalne ki anumati chahiye. Settings khol raha hoon."
        }
        Reply.Failed -> "यह नहीं हो पाया।" to "Yeh nahi ho paya."
        Reply.ModelsMissing -> "आवाज़ पहचानने वाला मॉडल अभी इंस्टॉल नहीं है।" to "Speech model abhi install nahi hai."
        is Reply.TaskStarting -> "${r.name} शुरू कर रहा हूँ।" to "${r.name} shuru kar raha hoon."
        is Reply.TaskDone -> if (r.verified) "${r.name} हो गया।" to "${r.name} ho gaya."
            else "${r.name}: सारे बटन दबा दिए, पर पक्का नहीं कर पाया। एक बार देख लीजिए।" to "${r.name}: saare button daba diye, par pakka nahi kar paya. Ek baar dekh lijiye."
        is Reply.TaskFailed -> taskFailedHi(r)
        is Reply.TaskUnknown -> "${r.spoken} नाम का कोई काम सेव नहीं है। पहले ऐप में सिखाइए।" to "${r.spoken} naam ka koi kaam save nahi hai. Pehle app mein sikhaiye."
        is Reply.TaskDue -> "${r.name} का समय हो गया। शुरू करने के लिए फ़िंगरप्रिंट लगाइए।" to "${r.name} ka samay ho gaya. Shuru karne ke liye fingerprint lagaiye."
        is Reply.TasksScheduled -> "सेट कर दिया: ${r.summaries.joinToString("; ")}।" to "Set kar diya: ${r.summaries.joinToString("; ")}."
        Reply.NeedsControlMode -> "कंट्रोल मोड बंद है। Accessibility सेटिंग में Abhimanyu चालू कीजिए।" to "Control mode band hai. Accessibility settings mein Abhimanyu chalu kijiye."
        Reply.Cancelled -> "ठीक है, रद्द कर दिया।" to "Theek hai, radd kar diya."
    }

    private fun taskFailedHi(r: Reply.TaskFailed): Pair<String, String> {
        val (hi, roman) = when (r.reason) {
            Reply.TaskFailure.NOT_FOUND -> "${r.detail} नहीं मिला।" to "${r.detail} nahi mila."
            Reply.TaskFailure.LOGIN_NEEDED -> "ऐप लॉगिन माँग रहा है। एक बार लॉगिन करके फिर कोशिश कीजिए।" to "App login maang raha hai. Ek baar login karke phir koshish kijiye."
            Reply.TaskFailure.BLOCKED_APP -> "पेमेंट, बैंक या फ़ोटो ऐप मैं नहीं चला सकता।" to "Payment, bank ya photo app main nahi chala sakta."
            Reply.TaskFailure.TIMEOUT -> "बहुत समय लग गया।" to "Bahut samay lag gaya."
            Reply.TaskFailure.APP_MISSING -> "ऐप इंस्टॉल नहीं है।" to "App install nahi hai."
            Reply.TaskFailure.UNVERIFIED -> "${r.detail} नहीं दिखा।" to "${r.detail} nahi dikha."
            Reply.TaskFailure.CANCELLED -> "रद्द कर दिया।" to "Radd kar diya."
        }
        return "${r.name} रुक गया। $hi" to "${r.name} ruk gaya. $roman"
    }

    // ---------- Hinglish (spoken mixed-script text, Roman caption) ----------

    private fun hinglish(r: Reply): Pair<String, String> = when (r) {
        is Reply.Flashlight -> if (r.on) "Torch on कर दी।" to "Torch on kar di." else "Torch off कर दी।" to "Torch off kar di."
        is Reply.TimerSet -> "${durationHinglish(r.seconds)} का timer लगा दिया।" to "${durationRoman(r.seconds)} ka timer laga diya."
        is Reply.AlarmSet -> "${if (r.tomorrow) "कल" else "आज"} ${clockHi(r.hour, r.minute)} का alarm लगा दिया।" to
            "${if (r.tomorrow) "Kal" else "Aaj"} ${clockRoman(r.hour, r.minute)} ka alarm laga diya."
        Reply.AlarmCancelRequested -> "Clock app को alarm cancel करने को कह दिया।" to "Clock app ko alarm cancel karne ko keh diya."
        is Reply.VolumeSet -> "${streamEn(r.stream)} volume ${r.percent} percent कर दिया।" to "${streamEn(r.stream)} volume ${r.percent} percent kar diya."
        is Reply.Muted -> if (r.mute) "${streamEn(r.stream)} mute कर दिया।" to "${streamEn(r.stream)} mute kar diya."
            else "${streamEn(r.stream)} unmute कर दिया।" to "${streamEn(r.stream)} unmute kar diya."
        is Reply.RingerSet -> when (r.mode) {
            RingerMode.SILENT -> "Phone silent पर कर दिया।" to "Phone silent par kar diya."
            RingerMode.VIBRATE -> "Phone vibrate पर कर दिया।" to "Phone vibrate par kar diya."
            RingerMode.NORMAL -> "Ringer on कर दिया।" to "Ringer on kar diya."
        }
        is Reply.DndSet -> when {
            !r.on -> "DND off कर दिया।" to "DND off kar diya."
            r.minutes != null -> "${durationHinglish(r.minutes * 60)} के लिए DND on कर दिया।" to "${durationRoman(r.minutes * 60)} ke liye DND on kar diya."
            else -> "DND on कर दिया।" to "DND on kar diya."
        }
        is Reply.TimeNow -> "अभी ${clockHi(r.hour, r.minute)} हुए हैं।" to "Abhi ${clockRoman(r.hour, r.minute)} hue hain."
        is Reply.DateToday -> "आज ${dayEn(r.date.dayOfWeek)}, ${r.date.dayOfMonth} ${MONTHS_EN[r.date.monthValue - 1]} है।" to
            "Aaj ${dayEn(r.date.dayOfWeek)}, ${r.date.dayOfMonth} ${MONTHS_EN[r.date.monthValue - 1]} hai."
        is Reply.Battery -> "Battery ${r.percent} percent है${if (r.charging) ", charge हो रही है" else ""}।" to
            "Battery ${r.percent} percent hai${if (r.charging) ", charge ho rahi hai" else ""}."
        is Reply.MediaDone -> when (r.action) {
            MediaAction.PLAY -> "Play कर दिया।" to "Play kar diya."
            MediaAction.PAUSE -> "Pause कर दिया।" to "Pause kar diya."
            MediaAction.NEXT -> "Next गाना।" to "Next gaana."
            MediaAction.PREVIOUS -> "पिछला गाना।" to "Pichla gaana."
        }
        is Reply.BrightnessSet -> "Brightness ${r.percent} percent कर दी।" to "Brightness ${r.percent} percent kar di."
        is Reply.ReplyLanguageSet -> when (r.pref) {
            ReplyLanguagePref.MATCH -> "ठीक है, आप जिस language में बोलोगे, उसी में reply करूँगा।" to "Theek hai, aap jis language mein bologe, usi mein reply karunga."
            ReplyLanguagePref.ENGLISH -> "ठीक है, अब हमेशा English में reply करूँगा।" to "Theek hai, ab hamesha English mein reply karunga."
            ReplyLanguagePref.HINDI -> "ठीक है, अब हमेशा Hindi में reply करूँगा।" to "Theek hai, ab hamesha Hindi mein reply karunga."
        }
        is Reply.CaptionsSet -> if (r.on) "Captions on." to "Captions on." else "Captions off." to "Captions off."
        Reply.Help -> "मैं torch, volume, silent mode, DND, alarm, timer, brightness और music control कर सकता हूँ, " +
            "और time, date और battery बता सकता हूँ।" to
            "Main torch, volume, silent mode, DND, alarm, timer, brightness aur music control kar sakta hoon, " +
            "aur time, date aur battery bata sakta hoon."
        Reply.Stopped -> "ठीक है, रोक दिया।" to "Theek hai, rok diya."
        Reply.NotUnderstood -> "Sorry, फिर से बोलो?" to "Sorry, phir se bolo?"
        Reply.NothingToRepeat -> "Repeat करने के लिए अभी कुछ नहीं है।" to "Repeat karne ke liye abhi kuch nahi hai."
        Reply.CantDoYet -> "यह मैं अभी नहीं कर सकता।" to "Yeh main abhi nahi kar sakta."
        Reply.LockNeedsControlMode -> "Control mode on होने तक phone lock नहीं कर सकता।" to "Control mode on hone tak phone lock nahi kar sakta."
        Reply.Locked -> "Phone lock कर दिया।" to "Phone lock kar diya."
        is Reply.NeedsPermission -> when (r.permission) {
            Reply.Permission.MICROPHONE -> "मुझे microphone permission चाहिए।" to "Mujhe microphone permission chahiye."
            Reply.Permission.DND_ACCESS -> "मुझे DND access चाहिए। Settings खोल रहा हूँ।" to "Mujhe DND access chahiye. Settings khol raha hoon."
            Reply.Permission.MODIFY_SETTINGS -> "मुझे system settings की permission चाहिए। Settings खोल रहा हूँ।" to "Mujhe system settings ki permission chahiye. Settings khol raha hoon."
        }
        Reply.Failed -> "यह नहीं हो पाया।" to "Yeh nahi ho paya."
        Reply.ModelsMissing -> "Speech model अभी install नहीं है।" to "Speech model abhi install nahi hai."
        is Reply.TaskStarting -> "${r.name} start कर रहा हूँ।" to "${r.name} start kar raha hoon."
        is Reply.TaskDone -> if (r.verified) "${r.name} done हो गया।" to "${r.name} done ho gaya."
            else "${r.name}: सारे steps कर दिए, पर confirm नहीं हुआ। एक बार check कर लो।" to "${r.name}: saare steps kar diye, par confirm nahi hua. Ek baar check kar lo."
        is Reply.TaskFailed -> taskFailedHi(r)
        is Reply.TaskUnknown -> "${r.spoken} नाम का कोई task नहीं है। पहले app में teach करो।" to "${r.spoken} naam ka koi task nahi hai. Pehle app mein teach karo."
        is Reply.TaskDue -> "${r.name} का time हो गया। Start करने के लिए fingerprint लगाओ।" to "${r.name} ka time ho gaya. Start karne ke liye fingerprint lagao."
        is Reply.TasksScheduled -> "Schedule कर दिया: ${r.summaries.joinToString("; ")}।" to "Schedule kar diya: ${r.summaries.joinToString("; ")}."
        Reply.NeedsControlMode -> "Control mode off है। Accessibility settings में Abhimanyu on करो।" to "Control mode off hai. Accessibility settings mein Abhimanyu on karo."
        Reply.Cancelled -> "ठीक है, cancel कर दिया।" to "Theek hai, cancel kar diya."
    }

    // ---------- formatting helpers ----------

    private val MONTHS_EN = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val MONTHS_HI = listOf(
        "जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर",
    )
    private val DAYS_HI = listOf("सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार")
    private val DAYS_ROMAN = listOf("Somvaar", "Mangalvaar", "Budhvaar", "Guruvaar", "Shukravaar", "Shanivaar", "Ravivaar")

    private fun dayEn(d: DayOfWeek) = d.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun streamEn(s: VolumeStream) = when (s) {
        VolumeStream.MEDIA -> "Media"
        VolumeStream.RING -> "Ring"
        VolumeStream.ALARM -> "Alarm"
    }
    private fun streamHi(s: VolumeStream) = when (s) {
        VolumeStream.MEDIA -> "मीडिया"
        VolumeStream.RING -> "रिंग"
        VolumeStream.ALARM -> "अलार्म"
    }
    private fun streamRoman(s: VolumeStream) = streamEn(s)

    private fun parts(seconds: Int): Triple<Int, Int, Int> = Triple(seconds / 3600, seconds % 3600 / 60, seconds % 60)

    fun durationEn(seconds: Int): String {
        val (h, m, s) = parts(seconds)
        val out = mutableListOf<String>()
        if (h > 0) out += "$h ${if (h == 1) "hour" else "hours"}"
        if (m > 0) out += "$m ${if (m == 1) "minute" else "minutes"}"
        if (s > 0) out += "$s ${if (s == 1) "second" else "seconds"}"
        return out.joinToString(" ")
    }

    fun durationHi(seconds: Int): String {
        if (seconds == 1800) return "आधे घंटे"
        if (seconds == 5400) return "डेढ़ घंटे"
        val (h, m, s) = parts(seconds)
        val out = mutableListOf<String>()
        if (h > 0) out += "$h घंटे"
        if (m > 0) out += "$m मिनट"
        if (s > 0) out += "$s सेकंड"
        return out.joinToString(" ")
    }

    private fun durationHinglish(seconds: Int): String {
        if (seconds == 1800) return "आधे घंटे"
        if (seconds == 5400) return "डेढ़ घंटे"
        val (h, m, s) = parts(seconds)
        val out = mutableListOf<String>()
        if (h > 0) out += "$h घंटे"
        if (m > 0) out += "$m minute"
        if (s > 0) out += "$s second"
        return out.joinToString(" ")
    }

    fun durationRoman(seconds: Int): String {
        if (seconds == 1800) return "aadhe ghante"
        if (seconds == 5400) return "dedh ghante"
        val (h, m, s) = parts(seconds)
        val out = mutableListOf<String>()
        if (h > 0) out += "$h ghante"
        if (m > 0) out += "$m minute"
        if (s > 0) out += "$s second"
        return out.joinToString(" ")
    }

    /** "Clock in at 10 AM, Monday to Friday". English only: used inside all three languages' replies. */
    fun scheduleSummary(name: String, days: Set<java.time.DayOfWeek>, hour: Int, minute: Int): String {
        val sorted = days.sorted()
        val weekdays = java.time.DayOfWeek.entries.take(5).toSet()
        val dayText = when {
            days.size == 7 -> "every day"
            days == weekdays -> "Monday to Friday"
            days == setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY) -> "weekends"
            else -> sorted.joinToString(", ") { dayEn(it) }
        }
        return "$name at ${clockEn(hour, minute)}, $dayText"
    }

    private fun h12(hour: Int) = (hour % 12).let { if (it == 0) 12 else it }

    fun clockEn(hour: Int, minute: Int): String {
        val ampm = if (hour < 12) "AM" else "PM"
        return if (minute == 0) "${h12(hour)} $ampm" else "${h12(hour)}:${"%02d".format(minute)} $ampm"
    }

    private fun daypartHi(hour: Int) = when (hour) {
        in 4..11 -> "सुबह"
        in 12..15 -> "दोपहर"
        in 16..19 -> "शाम"
        else -> "रात"
    }
    private fun daypartRoman(hour: Int) = when (hour) {
        in 4..11 -> "subah"
        in 12..15 -> "dopahar"
        in 16..19 -> "shaam"
        else -> "raat"
    }

    /** "सुबह 6 बजकर 30 मिनट", "शाम 7 बजे". */
    fun clockHi(hour: Int, minute: Int): String =
        if (minute == 0) "${daypartHi(hour)} ${h12(hour)} बजे" else "${daypartHi(hour)} ${h12(hour)} बजकर $minute मिनट"

    fun clockRoman(hour: Int, minute: Int): String =
        if (minute == 0) "${daypartRoman(hour)} ${h12(hour)} baje" else "${daypartRoman(hour)} ${h12(hour)}:${"%02d".format(minute)}"
}
