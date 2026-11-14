package com.nujan.helper.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nujan.helper.ui.AccentButton
import com.nujan.helper.ui.Card
import com.nujan.helper.ui.HelperTheme
import com.nujan.helper.ui.Midnight
import com.nujan.helper.ui.QuietButton
import com.nujan_agent.atp.provider.AtpUi
import com.nujan_agent.atp.provider.AtpUiOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Questions put to the person during a call, on the Helper's own screen. While one is up the phone tools refuse to act, and
 * they refuse to act on the Helper's own screens at any time, so a model cannot tap Allow for itself. A question leaves the
 * screen when its wait ends, however it ends: an answer, the time running out, or its call being cancelled or stopped.
 */
object PhoneAsks {
    /** The person's answer; [UNASKED] when the question could not be put on the screen, so nobody answered it. */
    enum class Answer { ONCE, FOR_A_WHILE, NO, UNASKED }

    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Answer>>()

    /** How to close each question on screen, by its id. */
    private val shown = ConcurrentHashMap<String, () -> Unit>()

    val asking: Boolean get() = waiting.isNotEmpty()

    /** What [ask] answers with nothing shown, for a device test, which has no person to ask. */
    @androidx.annotation.VisibleForTesting
    internal var answerWithoutAsking: Answer? = null

    /**
     * Puts [title] to the person, with [why] it is asked and [says], the lines of what it is about under [saysHeading]: the
     * screen it acts on, quoted so the person knows what they allow without seeing that screen, or the pins of a board.
     * [canLast] is the label of the answer that lasts a while, or null.
     */
    suspend fun ask(
        context: Context,
        ui: AtpUi,
        title: String,
        why: String,
        says: List<String>,
        canLast: String?,
        saysHeading: String = ON_THAT_SCREEN,
    ): Answer {
        answerWithoutAsking?.let { return it }
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<Answer>()
        waiting[id] = answer
        try {
            val intent = Intent(context, PhoneAskActivity::class.java)
                .putExtra(PhoneAskActivity.EXTRA_ID, id)
                .putExtra(PhoneAskActivity.EXTRA_TITLE, title)
                .putExtra(PhoneAskActivity.EXTRA_WHY, why)
                .putExtra(PhoneAskActivity.EXTRA_SAYS, says.toTypedArray())
                .putExtra(PhoneAskActivity.EXTRA_SAYS_HEADING, saysHeading)
                .putExtra(PhoneAskActivity.EXTRA_CAN_LAST, canLast)
            if (ui.show(intent, "NuJan Helper needs an answer on its own screen") != AtpUiOutcome.SHOWN) return Answer.UNASKED
            // Android can refuse the question's screen without a word to the app that asked: over its own installer, which
            // hides other apps' windows, the start is blocked. A question that has not come up soon will not.
            if (withTimeoutOrNull(SHOW_WAIT_MS) { while (!shown.containsKey(id) && !answer.isCompleted) delay(SHOW_POLL_MS) } == null) {
                return Answer.UNASKED
            }
            return withTimeoutOrNull(ANSWER_WAIT_MS) { answer.await() } ?: Answer.NO
        } finally {
            waiting.remove(id)
            shown.remove(id)?.let { close -> Handler(Looper.getMainLooper()).post(close) }
        }
    }

    internal fun answer(id: String?, answer: Answer) {
        waiting[id]?.complete(answer)
    }

    /** Records how to close the question [id] now on screen; false when its wait has already ended, so it is not shown. */
    internal fun showing(id: String?, close: () -> Unit): Boolean {
        if (id == null || !waiting.containsKey(id)) return false
        shown[id] = close
        // The wait may have ended between the check and the record.
        if (!waiting.containsKey(id)) {
            shown.remove(id)
            return false
        }
        return true
    }

    internal fun gone(id: String?) {
        if (id != null) shown.remove(id)
    }

    /** The heading over quoted lines from the screen an act is aimed at. */
    const val ON_THAT_SCREEN = "On that screen:"

    private const val ANSWER_WAIT_MS = 90_000L
    private const val SHOW_WAIT_MS = 6_000L
    private const val SHOW_POLL_MS = 100L
}

/** Asks one question and finishes; leaving without answering is a no. Touches through another app's window are ignored. */
class PhoneAskActivity : ComponentActivity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = true
        if (!PhoneAsks.showing(intent.getStringExtra(EXTRA_ID)) { finish() }) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val why = intent.getStringExtra(EXTRA_WHY).orEmpty()
        val says = intent.getStringArrayExtra(EXTRA_SAYS).orEmpty().toList()
        val saysHeading = intent.getStringExtra(EXTRA_SAYS_HEADING) ?: PhoneAsks.ON_THAT_SCREEN
        val canLast = intent.getStringExtra(EXTRA_CAN_LAST)
        setContent {
            HelperTheme { Question(title, why, says, saysHeading, canLast, ::finishWith) }
        }
    }

    private fun finishWith(answer: PhoneAsks.Answer) {
        answered = true
        PhoneAsks.answer(intent.getStringExtra(EXTRA_ID), answer)
        finish()
    }

    override fun onDestroy() {
        if (!answered && isFinishing) PhoneAsks.answer(intent.getStringExtra(EXTRA_ID), PhoneAsks.Answer.NO)
        PhoneAsks.gone(intent.getStringExtra(EXTRA_ID))
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_WHY = "why"
        const val EXTRA_SAYS = "says"
        const val EXTRA_SAYS_HEADING = "saysHeading"
        const val EXTRA_CAN_LAST = "canLast"
    }
}

@Composable
private fun Question(
    title: String,
    why: String,
    says: List<String>,
    saysHeading: String,
    canLast: String?,
    onAnswer: (PhoneAsks.Answer) -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Card {
            Text(title, color = Midnight.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(why, color = Midnight.TextDim, fontSize = 15.sp, lineHeight = 21.sp)
            if (says.isNotEmpty()) {
                Text(saysHeading, color = Midnight.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                says.forEach { line -> Text(line, color = Midnight.Text, fontSize = 15.sp, lineHeight = 21.sp) }
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("Don't allow", { onAnswer(PhoneAsks.Answer.NO) }, Modifier.fillMaxWidth())
                QuietButton("Allow once", { onAnswer(PhoneAsks.Answer.ONCE) }, Modifier.fillMaxWidth())
                if (canLast != null) AccentButton(canLast, { onAnswer(PhoneAsks.Answer.FOR_A_WHILE) }, Modifier.fillMaxWidth())
            }
        }
    }
}
