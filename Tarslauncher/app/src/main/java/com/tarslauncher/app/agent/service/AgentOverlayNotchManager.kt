package com.tarslauncher.app.agent.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.tarslauncher.app.agent.domain.model.AgentPlan
import java.util.Locale

/**
 * OLED-black Dynamic Island capsule powered by TYPE_ACCESSIBILITY_OVERLAY.
 * - Compact pill at the top center (shows live ticking countdown when an alarm is scheduled).
 * - Expands into an ultra-minimal, pure monochrome, zero-corner AI conversation & countdown card
 *   matching the rest of TARS Launcher.
 */
class AgentOverlayNotchManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager? by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    private val uiContext: Context by lazy {
        try {
            ContextThemeWrapper(context, com.tarslauncher.app.R.style.Theme_Tarslauncher)
        } catch (_: Exception) {
            context
        }
    }

    private var rootContainer: LinearLayout? = null
    private var compactRowView: LinearLayout? = null
    private var statusDotView: View? = null
    private var statusWordView: TextView? = null
    private var stageBadgeView: TextView? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var borderPulseAnimator: ValueAnimator? = null
    private var autoIdleRunnable: Runnable? = null
    private var screenStateReceiver: BroadcastReceiver? = null

    // Expanded view elements (Ultra-Minimal Monochrome)
    private var expandedContainerView: LinearLayout? = null
    private var countdownRowView: LinearLayout? = null
    private var countdownTextView: TextView? = null
    private var countdownCancelBtn: TextView? = null
    private var agentMessageView: TextView? = null
    private var questionActionRow: LinearLayout? = null
    private var confirmBtnView: TextView? = null
    private var cancelQuestionBtnView: TextView? = null
    private var alarmDismissBtn: TextView? = null
    private var inputCardView: LinearLayout? = null
    private var inputEditText: EditText? = null
    private var stopButtonView: TextView? = null
    private var startButtonView: TextView? = null
    private var expandedStatusView: TextView? = null

    @Volatile
    var onStopRequested: (() -> Unit)? = null

    @Volatile
    var onTaskSubmitted: ((String) -> Unit)? = null

    @Volatile
    private var pendingQuestionCallback: ((String) -> Unit)? = null

    private var activeQuestionText: String? = null
    private var activeReplyText: String? = null

    // Live Alarm / Countdown state
    private var countdownTargetMs: Long = 0L
    private var countdownLabel: String = ""
    private var countdownTimeFormatted: String = ""
    private var isAlarmRinging: Boolean = false
    private var countdownTicker: Runnable? = null

    private var currentPlan: AgentPlan? = null
    private var currentStepNum: Int = 1
    private var lastRenderedWord: String = ""
    private var currentState: NotchState = NotchState.IDLE
    private var isExpanded: Boolean = false
    private var isFullscreenSuppressed: Boolean = false

    fun setFullscreenSuppressed(suppressed: Boolean) {
        if (isFullscreenSuppressed == suppressed) return
        isFullscreenSuppressed = suppressed
        mainHandler.post {
            val container = rootContainer ?: return@post
            if (suppressed) {
                stopPulseAnimation()
                if (isExpanded) {
                    collapseIsland()
                }
                container.animate()
                    .alpha(0f)
                    .scaleX(0.7f)
                    .scaleY(0.7f)
                    .setDuration(160L)
                    .withEndAction {
                        container.visibility = View.GONE
                    }
                    .start()
            } else {
                if (com.tarslauncher.app.agent.data.AgentPreferences(context).isAgentEnabled()) {
                    container.visibility = View.VISIBLE
                    container.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180L)
                        .withEndAction(null)
                        .start()

                    if (currentState == NotchState.ACTIVE || currentState == NotchState.THINKING || countdownTargetMs > System.currentTimeMillis()) {
                        startPulseAnimation()
                    }
                }
            }
        }
    }

    fun showIdle() {
        mainHandler.post {
            autoIdleRunnable?.let { mainHandler.removeCallbacks(it) }
            autoIdleRunnable = null
            if (!com.tarslauncher.app.agent.data.AgentPreferences(context).isAgentEnabled()) {
                hide()
                return@post
            }
            currentState = NotchState.IDLE
            currentPlan = null
            currentStepNum = 1
            if (pendingQuestionCallback == null && !isAlarmRinging) {
                activeQuestionText = null
                activeReplyText = null
            }

            if (rootContainer == null) {
                createAndAttachOverlay()
            }

            if (isFullscreenSuppressed) {
                rootContainer?.visibility = View.GONE
            } else if (!isExpanded) {
                renderCompactIdle()
            }
            updateExpandedState()
        }
    }

    fun showOrUpdate(
        stepNumber: Int = currentStepNum,
        plan: AgentPlan? = currentPlan,
        liveDetail: String? = null,
        state: NotchState = NotchState.ACTIVE
    ) {
        mainHandler.post {
            autoIdleRunnable?.let { mainHandler.removeCallbacks(it) }
            autoIdleRunnable = null
            if (!com.tarslauncher.app.agent.data.AgentPreferences(context).isAgentEnabled()) {
                hide()
                return@post
            }
            currentStepNum = stepNumber
            currentState = state
            if (plan != null) {
                currentPlan = plan
            }

            if (state == NotchState.COMPLETED || state == NotchState.FAILED) {
                activeQuestionText = null
                pendingQuestionCallback = null
                inputEditText?.setText("")
            }

            if (rootContainer == null) {
                createAndAttachOverlay()
            }

            updateUiContent(stepNumber, currentPlan, liveDetail, state)
            updateExpandedState()

            stopButtonView?.visibility = if (state == NotchState.ACTIVE || state == NotchState.THINKING) {
                View.VISIBLE
            } else {
                View.GONE
            }

            if (state == NotchState.COMPLETED || state == NotchState.FAILED) {
                val delayMs = if (!activeReplyText.isNullOrBlank()) 2400L else 1500L
                val toIdleTask = Runnable {
                    if (pendingQuestionCallback == null && !isAlarmRinging) {
                        activeReplyText = null
                        activeQuestionText = null
                        val hasTypedText = !inputEditText?.text?.toString().isNullOrBlank()
                        if (isExpanded && !hasTypedText) {
                            collapseIsland()
                        }
                        showIdle()
                    }
                }
                autoIdleRunnable = toIdleTask
                mainHandler.postDelayed(toIdleTask, delayMs)
            }
        }
    }

    /**
     * Starts a live countdown inside the Dynamic Island for a scheduled alarm/reminder,
     * shows an animated notification banner on the Island, and triggers an animated alert when time is up.
     */
    fun startAlarmCountdown(targetTimeMillis: Long, label: String, timeFormatted: String) {
        mainHandler.post {
            if (!com.tarslauncher.app.agent.data.AgentPreferences(context).isAgentEnabled()) return@post
            if (rootContainer == null) {
                createAndAttachOverlay()
            }

            countdownTargetMs = targetTimeMillis
            countdownLabel = label
            countdownTimeFormatted = timeFormatted
            isAlarmRinging = false

            val initRemainingSec = ((targetTimeMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            activeReplyText = "- ALARM KURULDU // $timeFormatted\n- $label\n- KALAN SÜRE: ${formatDuration(initRemainingSec)}"
            activeQuestionText = null
            pendingQuestionCallback = null

            // Animated notification expansion on the Island when alarm is scheduled
            animateIslandNotificationBanner()

            startCountdownLoop()

            // Auto-collapse back to the compact live countdown pill after 2.6s if user isn't typing
            autoIdleRunnable?.let { mainHandler.removeCallbacks(it) }
            val collapseTask = Runnable {
                val hasInput = !inputEditText?.text?.toString().isNullOrBlank()
                if (pendingQuestionCallback == null && !isAlarmRinging) {
                    activeReplyText = null
                    if (isExpanded && !hasInput) {
                        collapseIsland()
                    }
                    showIdle()
                }
            }
            autoIdleRunnable = collapseTask
            mainHandler.postDelayed(collapseTask, 2600L)
        }
    }

    private fun startCountdownLoop() {
        countdownTicker?.let { mainHandler.removeCallbacks(it) }
        val ticker = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val diffMs = countdownTargetMs - now
                if (diffMs <= 0L) {
                    countdownTargetMs = 0L
                    countdownTicker = null
                    triggerAlarmRingingNotification()
                    return
                }
                val remSec = (diffMs / 1000L).coerceAtLeast(0L)
                val formattedCountdown = formatDuration(remSec)

                // Update expanded countdown row if visible
                countdownRowView?.visibility = View.VISIBLE
                countdownTextView?.text = "- $countdownTimeFormatted · ${countdownLabel.take(24)}  [$formattedCountdown]"

                // Update compact pill with live countdown if idle
                if (!isExpanded && currentState == NotchState.IDLE) {
                    renderCompactCountdown(formattedCountdown)
                }

                mainHandler.postDelayed(this, 1000L)
            }
        }
        countdownTicker = ticker
        mainHandler.post(ticker)
    }

    fun cancelAlarmCountdown() {
        mainHandler.post {
            countdownTicker?.let { mainHandler.removeCallbacks(it) }
            countdownTicker = null
            countdownTargetMs = 0L
            countdownLabel = ""
            countdownTimeFormatted = ""
            isAlarmRinging = false
            stopBorderPulseAnimation()
            countdownRowView?.visibility = View.GONE
            alarmDismissBtn?.visibility = View.GONE
            activeReplyText = null
            updateExpandedState()
            if (!isExpanded && currentState == NotchState.IDLE) {
                renderCompactIdle()
            }
        }
    }

    private fun triggerAlarmRingingNotification() {
        isAlarmRinging = true
        countdownRowView?.visibility = View.GONE
        activeQuestionText = null
        pendingQuestionCallback = null
        activeReplyText = "⏰ ZAMAN GELDİ // $countdownTimeFormatted\n- $countdownLabel"

        // Haptic + Sound alert
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 180, 350, 180, 500), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(800L)
            }
        }
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.play()
        }

        animateIslandNotificationBanner()
        startBorderPulseAnimation()
    }

    private fun animateIslandNotificationBanner() {
        if (!isExpanded) {
            expandIsland(popKeyboard = false)
        } else {
            updateExpandedState()
        }
        val container = rootContainer ?: return
        container.scaleX = 0.86f
        container.scaleY = 0.86f
        container.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(300L)
            .setInterpolator(OvershootInterpolator(1.35f))
            .start()
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Expands the Dynamic Island to ask the user a question from the LLM and waits for their reply.
     */
    fun promptUserQuestion(question: String, onAnswer: (String) -> Unit) {
        mainHandler.post {
            autoIdleRunnable?.let { mainHandler.removeCallbacks(it) }
            autoIdleRunnable = null
            if (!com.tarslauncher.app.agent.data.AgentPreferences(context).isAgentEnabled()) {
                onAnswer("")
                return@post
            }
            if (rootContainer == null) {
                createAndAttachOverlay()
            }
            pendingQuestionCallback = onAnswer
            activeQuestionText = question
            activeReplyText = null
            currentState = NotchState.THINKING

            updateUiContent(currentStepNum, currentPlan, "Onay", NotchState.THINKING)
            if (!isExpanded) {
                expandIsland(popKeyboard = true)
            } else {
                ensureExpandedFocusable(popKeyboard = true)
                updateExpandedState()
                inputEditText?.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                inputEditText?.let { imm?.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
            }
        }
    }

    /**
     * Displays an AI Second Brain reply/summary directly inside the Dynamic Island.
     */
    fun showAgentReply(reply: String) {
        mainHandler.post {
            autoIdleRunnable?.let { mainHandler.removeCallbacks(it) }
            autoIdleRunnable = null
            if (!com.tarslauncher.app.agent.data.AgentPreferences(context).isAgentEnabled()) return@post
            if (rootContainer == null) {
                createAndAttachOverlay()
            }
            currentState = NotchState.IDLE
            currentPlan = null
            currentStepNum = 1
            activeReplyText = reply
            activeQuestionText = null
            pendingQuestionCallback = null
            inputEditText?.setText("")
            animateIslandNotificationBanner()

            val cleanTask = Runnable {
                val hasInput = !inputEditText?.text?.toString().isNullOrBlank()
                if (pendingQuestionCallback == null && !isAlarmRinging && !hasInput) {
                    activeReplyText = null
                    if (isExpanded) {
                        collapseIsland()
                    }
                    showIdle()
                }
            }
            autoIdleRunnable = cleanTask
            mainHandler.postDelayed(cleanTask, 9000L)
        }
    }

    fun hide() {
        mainHandler.post {
            autoIdleRunnable?.let { mainHandler.removeCallbacks(it) }
            autoIdleRunnable = null
            countdownTicker?.let { mainHandler.removeCallbacks(it) }
            countdownTicker = null
            unregisterScreenStateReceiver()
            stopPulseAnimation()
            stopBorderPulseAnimation()

            pendingQuestionCallback?.invoke("")
            pendingQuestionCallback = null
            activeQuestionText = null
            activeReplyText = null

            val view = rootContainer ?: return@post
            rootContainer = null
            isExpanded = false

            view.animate()
                .scaleX(0.7f)
                .scaleY(0.7f)
                .alpha(0f)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    runCatching {
                        windowManager?.removeViewImmediate(view)
                    }
                }
                .start()
        }
    }

    fun destroyImmediately() {
        val cleanup = {
            mainHandler.removeCallbacksAndMessages(null)
            autoIdleRunnable = null
            countdownTicker = null
            unregisterScreenStateReceiver()
            stopPulseAnimation()
            stopBorderPulseAnimation()

            pendingQuestionCallback?.invoke("")
            pendingQuestionCallback = null
            activeQuestionText = null
            activeReplyText = null

            val view = rootContainer
            rootContainer = null
            compactRowView = null
            statusDotView = null
            statusWordView = null
            stageBadgeView = null
            expandedContainerView = null
            countdownRowView = null
            countdownTextView = null
            countdownCancelBtn = null
            agentMessageView = null
            questionActionRow = null
            confirmBtnView = null
            cancelQuestionBtnView = null
            alarmDismissBtn = null
            inputCardView = null
            inputEditText = null
            stopButtonView = null
            startButtonView = null
            expandedStatusView = null
            isExpanded = false

            if (view != null) {
                view.animate().cancel()
                view.clearAnimation()
                runCatching {
                    windowManager?.removeViewImmediate(view)
                }
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup()
        } else {
            mainHandler.post(cleanup)
        }
    }

    private fun createAndAttachOverlay() {
        val wm = windowManager ?: return
        if (rootContainer != null) return

        runCatching {
            val c = uiContext

            // Dynamic Island Master Container: Pure Black (#000000)
            val container = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpF(14)
                    setColor(Color.BLACK)
                    setStroke(dp(1), Color.parseColor("#222222"))
                }
                elevation = 0f
            }

            // Tap compact pill to expand downwards
            container.setOnClickListener {
                if (!isExpanded) {
                    expandIsland(popKeyboard = true)
                }
            }

            // Outside touch detection when expanded to collapse smoothly (do not auto-dismiss while awaiting confirmation)
            container.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE && isExpanded) {
                    if (pendingQuestionCallback == null) {
                        collapseIsland()
                    }
                    true
                } else {
                    false
                }
            }

            // ----------------- COMPACT ROW (Sleek Pure Black Island) -----------------
            val compactRow = LinearLayout(c).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(28)
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
            }
            compactRowView = compactRow

            // 1. Monochrome Indicator Dot
            val dot = View(c).apply {
                layoutParams = LinearLayout.LayoutParams(dp(5), dp(5))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                visibility = View.GONE
            }
            statusDotView = dot
            compactRow.addView(dot)

            // 2. Status / Live Countdown Text (Monochrome)
            val wordText = TextView(c).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(6)
                }
                text = ""
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.04f
                includeFontPadding = false
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                visibility = View.GONE
            }
            statusWordView = wordText
            compactRow.addView(wordText)

            // 3. Micro Stage Badge
            val stageBadge = TextView(c).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(4)
                }
                text = ""
                setTextColor(Color.parseColor("#888888"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                includeFontPadding = false
                typeface = Typeface.MONOSPACE
                maxLines = 1
                visibility = View.GONE
            }
            stageBadgeView = stageBadge
            compactRow.addView(stageBadge)

            container.addView(compactRow)

            // ----------------- EXPANDED ULTRA-MINIMAL MONOCHROME CARD -----------------
            val expandedView = buildExpandedView(c)
            expandedContainerView = expandedView
            expandedView.visibility = View.GONE
            container.addView(expandedView)

            // Top-Center Placement LayoutParams - Compact 88x28 Pill
            val params = WindowManager.LayoutParams(
                dp(88),
                dp(28),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(10)
            }

            wm.addView(container, params)
            rootContainer = container
            registerScreenStateReceiver()

            if (isFullscreenSuppressed) {
                container.visibility = View.GONE
                return@runCatching
            }

            container.scaleX = 0.7f
            container.scaleY = 0.7f
            container.alpha = 0f
            container.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }.onFailure { e ->
            Log.e("TarsNotchOverlay", "Failed to create or attach overlay", e)
        }
    }

    /**
     * Ultra-minimal TARS Launcher style expanded Dynamic Island card:
     * - Pure `#000000` surface, single hairline border (`#1F1F1F`), zero nested boxes
     * - Wide-spaced `T A R S` light typography (`sans-serif-light`, `letterSpacing = 0.35f`)
     * - Clean `- ` prefixed conversation & input rows separated only by `1px` `#181818` lines
     */
    private fun buildExpandedView(c: Context = uiContext): LinearLayout {
        val lightTypeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

        val expanded = LinearLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
        }

        // --- 1. Minimal TARS Header: T A R S ................. × ---
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
        }

        val title = TextView(c).apply {
            text = "T A R S"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = lightTypeface
            letterSpacing = 0.32f
            includeFontPadding = false
        }
        header.addView(title)

        expandedStatusView = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(12)
            }
            text = ""
            setTextColor(Color.parseColor("#666666"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = lightTypeface
            letterSpacing = 0.18f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        header.addView(expandedStatusView)

        val closeBtn = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(24))
            gravity = Gravity.CENTER or Gravity.END
            text = "×"
            setTextColor(Color.parseColor("#777777"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = lightTypeface
            background = null
            contentDescription = "Kapat"
            isFocusable = true
            setOnClickListener {
                val cb = pendingQuestionCallback
                pendingQuestionCallback = null
                activeQuestionText = null
                activeReplyText = null
                inputEditText?.setText("")
                if (isAlarmRinging) {
                    isAlarmRinging = false
                    stopBorderPulseAnimation()
                }
                collapseIsland()
                cb?.invoke("")
            }
        }
        header.addView(closeBtn)
        expanded.addView(header)

        // Hairline separator
        expanded.addView(View(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 1).apply {
                bottomMargin = dp(8)
            }
            setBackgroundColor(Color.parseColor("#1C1C1C"))
        })

        // --- 2. Live Countdown Row (Borderless, visible only when an alarm is active) ---
        val cdRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            }
            setPadding(0, dp(4), 0, dp(6))
            visibility = if (countdownTargetMs > System.currentTimeMillis()) View.VISIBLE else View.GONE
        }

        val cdText = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            text = ""
            setTextColor(Color.parseColor("#CCCCCC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = lightTypeface
            letterSpacing = 0.04f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        countdownTextView = cdText
        cdRow.addView(cdText)

        val cdCancel = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(10)
            }
            text = "İPTAL"
            setTextColor(Color.parseColor("#666666"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = lightTypeface
            letterSpacing = 0.16f
            setPadding(dp(6), dp(2), dp(2), dp(2))
            setOnClickListener { cancelAlarmCountdown() }
        }
        countdownCancelBtn = cdCancel
        cdRow.addView(cdCancel)

        countdownRowView = cdRow
        expanded.addView(cdRow)

        // --- 3. Agent Reply / Question Text (Clean borderless '-' text on pure black) ---
        val messageBox = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
            background = null
            setPadding(0, dp(4), 0, dp(6))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = lightTypeface
            setLineSpacing(dpF(3), 1.0f)
            maxLines = 8
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        agentMessageView = messageBox
        expanded.addView(messageBox)

        // --- 3b. Minimal Confirmation / Cancel Row (TARS Ultra-Minimal Dialog style) ---
        val qRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
            visibility = View.GONE
        }

        val confirmBtn = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                marginEnd = dp(8)
            }
            gravity = Gravity.CENTER
            text = "ONAYLA"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = lightTypeface
            letterSpacing = 0.2f
            background = sharpSurface(Color.BLACK, Color.parseColor("#333333"))
            isFocusable = true
            setOnClickListener {
                val cb = pendingQuestionCallback
                pendingQuestionCallback = null
                activeQuestionText = null
                activeReplyText = null
                inputEditText?.setText("")
                updateExpandedState()
                collapseIsland()
                cb?.invoke("Evet")
            }
        }
        confirmBtnView = confirmBtn
        qRow.addView(confirmBtn)

        val cancelQBtn = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f)
            gravity = Gravity.CENTER
            text = "İPTAL"
            setTextColor(Color.parseColor("#777777"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = lightTypeface
            letterSpacing = 0.2f
            background = sharpSurface(Color.BLACK, Color.parseColor("#1F1F1F"))
            isFocusable = true
            setOnClickListener {
                val cb = pendingQuestionCallback
                pendingQuestionCallback = null
                activeQuestionText = null
                activeReplyText = null
                inputEditText?.setText("")
                updateExpandedState()
                collapseIsland()
                cb?.invoke("")
            }
        }
        cancelQuestionBtnView = cancelQBtn
        qRow.addView(cancelQBtn)

        questionActionRow = qRow
        expanded.addView(qRow)

        // Dismiss button when alarm is ringing
        val dismissAlarm = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(34)).apply {
                bottomMargin = dp(10)
            }
            gravity = Gravity.CENTER
            text = "ALARMI KAPAT"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = lightTypeface
            letterSpacing = 0.22f
            background = sharpSurface(Color.BLACK, Color.parseColor("#444444"))
            visibility = View.GONE
            setOnClickListener {
                cancelAlarmCountdown()
                collapseIsland()
            }
        }
        alarmDismissBtn = dismissAlarm
        expanded.addView(dismissAlarm)

        // --- 4. Borderless Minimal Input Row: - Not veya soru yaz...       ↑ ---
        val inputCard = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            background = null
            setPadding(0, dp(2), 0, dp(2))
        }
        inputCardView = inputCard

        val dashPrefix = TextView(c).apply {
            text = "- "
            setTextColor(Color.parseColor("#666666"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = lightTypeface
            includeFontPadding = false
        }
        inputCard.addView(dashPrefix)

        val input = EditText(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            background = null
            hint = "Not veya soru yaz..."
            setHintTextColor(Color.parseColor("#444444"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = lightTypeface
            setPadding(dp(2), dp(6), dp(8), dp(6))
            minHeight = dp(34)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            contentDescription = "Görev metni"
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitTaskFromInput()
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    refreshSendButtonVisual()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        inputEditText = input
        inputCard.addView(input)

        stopButtonView = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(30)).apply {
                marginEnd = dp(6)
            }
            text = "DUR"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = lightTypeface
            letterSpacing = 0.16f
            setPadding(dp(8), 0, dp(8), 0)
            background = null
            visibility = View.GONE
            isFocusable = true
            setOnClickListener {
                onStopRequested?.invoke()
                collapseIsland()
            }
        }
        inputCard.addView(stopButtonView)

        startButtonView = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            gravity = Gravity.CENTER
            text = "↑"
            setTextColor(Color.parseColor("#333333"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = lightTypeface
            background = null
            isEnabled = false
            isFocusable = true
            setOnClickListener { submitTaskFromInput() }
        }
        inputCard.addView(startButtonView)

        expanded.addView(inputCard)

        return expanded
    }

    private fun refreshSendButtonVisual() {
        val hasText = !inputEditText?.text?.toString().isNullOrBlank()
        val canSubmit = hasText || pendingQuestionCallback != null
        startButtonView?.isEnabled = canSubmit
        startButtonView?.setTextColor(
            if (canSubmit) Color.WHITE else Color.parseColor("#333333")
        )
    }

    private fun sharpSurface(color: Int, borderColor: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 0f
        setColor(color)
        setStroke(1, borderColor)
    }

    private fun ensureExpandedFocusable(popKeyboard: Boolean) {
        val wm = windowManager ?: return
        val container = rootContainer ?: return
        val lp = container.layoutParams as? WindowManager.LayoutParams ?: return
        lp.width = minOf(dp(340), context.resources.displayMetrics.widthPixels - dp(28))
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            if (popKeyboard) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        wm.updateViewLayout(container, lp)
    }

    private fun expandIsland(popKeyboard: Boolean = true) {
        if (isExpanded) return
        if (!com.tarslauncher.app.agent.data.AgentPreferences(context).isAgentEnabled()) {
            hide()
            return
        }

        // If user taps Island after a task completed/stopped, clean up stale status & messages immediately
        if (popKeyboard && (currentState == NotchState.COMPLETED || currentState == NotchState.FAILED)) {
            autoIdleRunnable?.let { mainHandler.removeCallbacks(it) }
            autoIdleRunnable = null
            currentState = NotchState.IDLE
            currentPlan = null
            currentStepNum = 1
            if (pendingQuestionCallback == null && !isAlarmRinging) {
                activeReplyText = null
                activeQuestionText = null
            }
        }

        isExpanded = true

        val container = rootContainer ?: return
        ensureExpandedFocusable(popKeyboard)

        // Expanded card: Pure Pitch Black (#000000), 0dp sharp corners, 1px hairline border
        (container.background as? GradientDrawable)?.apply {
            cornerRadius = 0f
            setColor(Color.BLACK)
            setStroke(1, Color.parseColor("#222222"))
        }

        compactRowView?.visibility = View.GONE
        expandedContainerView?.visibility = View.VISIBLE
        updateExpandedState()

        container.scaleX = 0.96f
        container.scaleY = 0.96f
        container.alpha = 0.94f

        container.isFocusableInTouchMode = true
        container.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (isExpanded) {
                    val cb = pendingQuestionCallback
                    pendingQuestionCallback = null
                    activeQuestionText = null
                    collapseIsland()
                    cb?.invoke("")
                    return@setOnKeyListener true
                }
            }
            false
        }

        container.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        if (popKeyboard) {
            inputEditText?.requestFocus()
            mainHandler.postDelayed({
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                inputEditText?.let { imm?.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
            }, 80L)
        }
    }

    private fun collapseIsland() {
        if (!isExpanded) return
        isExpanded = false
        stopBorderPulseAnimation()

        // Clean up finished state and old replies whenever collapsing so "Tamamlandı" never lingers
        if (currentState == NotchState.COMPLETED || currentState == NotchState.FAILED) {
            autoIdleRunnable?.let { mainHandler.removeCallbacks(it) }
            autoIdleRunnable = null
            currentState = NotchState.IDLE
            currentPlan = null
            currentStepNum = 1
        }
        if (pendingQuestionCallback == null && !isAlarmRinging) {
            activeReplyText = null
            activeQuestionText = null
        }

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputEditText?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
        inputEditText?.clearFocus()

        val wm = windowManager ?: return
        val container = rootContainer ?: return
        container.setOnKeyListener(null)
        val lp = container.layoutParams as? WindowManager.LayoutParams ?: return

        val hasCountdown = countdownTargetMs > System.currentTimeMillis()
        val isIdle = currentState == NotchState.IDLE
        lp.width = when {
            !isIdle -> dp(122)
            hasCountdown -> dp(112)
            else -> dp(88)
        }
        lp.height = dp(28)
        lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()) or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        wm.updateViewLayout(container, lp)

        // Restore compact pill background
        (container.background as? GradientDrawable)?.apply {
            cornerRadius = dpF(14)
            setColor(Color.BLACK)
            setStroke(dp(1), Color.parseColor("#222222"))
        }

        expandedContainerView?.visibility = View.GONE
        compactRowView?.visibility = View.VISIBLE

        if (isIdle) {
            renderCompactIdle()
        } else {
            updateUiContent(currentStepNum, currentPlan, null, currentState)
        }
    }

    private fun submitTaskFromInput() {
        val taskText = inputEditText?.text?.toString()?.trim().orEmpty()
        val questionCb = pendingQuestionCallback
        if (questionCb != null) {
            inputEditText?.setText("")
            pendingQuestionCallback = null
            activeQuestionText = null
            activeReplyText = null
            updateExpandedState()
            collapseIsland()
            questionCb.invoke(taskText.ifBlank { "Evet" })
            return
        }
        if (taskText.isNotBlank()) {
            inputEditText?.setText("")
            activeReplyText = null
            activeQuestionText = null
            updateExpandedState()
            collapseIsland()
            onTaskSubmitted?.invoke(taskText)
        }
    }

    private fun renderCompactCountdown(formattedCountdown: String) {
        statusDotView?.visibility = View.VISIBLE
        (statusDotView?.background as? GradientDrawable)?.setColor(Color.WHITE)
        startPulseAnimation()

        statusWordView?.text = formattedCountdown
        statusWordView?.visibility = View.VISIBLE
        lastRenderedWord = formattedCountdown
        stageBadgeView?.visibility = View.GONE

        if (!isExpanded) {
            rootContainer?.let { container ->
                val lp = container.layoutParams as? WindowManager.LayoutParams
                val targetW = if (formattedCountdown.length > 5) dp(116) else dp(98)
                if (lp != null && (lp.width != targetW || lp.height != dp(28))) {
                    lp.width = targetW
                    lp.height = dp(28)
                    windowManager?.updateViewLayout(container, lp)
                }
            }
        }
    }

    private fun renderCompactIdle() {
        val now = System.currentTimeMillis()
        if (countdownTargetMs > now) {
            val remSec = ((countdownTargetMs - now) / 1000L).coerceAtLeast(0L)
            renderCompactCountdown(formatDuration(remSec))
            return
        }

        stopPulseAnimation()
        statusDotView?.visibility = View.GONE
        statusWordView?.visibility = View.GONE
        statusWordView?.text = ""
        lastRenderedWord = ""
        stageBadgeView?.visibility = View.GONE
        stageBadgeView?.text = ""

        if (!isExpanded) {
            rootContainer?.let { container ->
                val lp = container.layoutParams as? WindowManager.LayoutParams
                if (lp != null && (lp.width != dp(88) || lp.height != dp(28))) {
                    lp.width = dp(88)
                    lp.height = dp(28)
                    windowManager?.updateViewLayout(container, lp)
                }
            }
        }
    }

    private fun updateExpandedState() {
        val isRunning = (currentState == NotchState.ACTIVE || currentState == NotchState.THINKING) && pendingQuestionCallback == null
        stopButtonView?.visibility = if (isRunning) View.VISIBLE else View.GONE
        alarmDismissBtn?.visibility = if (isAlarmRinging) View.VISIBLE else View.GONE

        val hasActiveCountdown = countdownTargetMs > System.currentTimeMillis()
        countdownRowView?.visibility = if (hasActiveCountdown && !isAlarmRinging) View.VISIBLE else View.GONE

        val question = activeQuestionText
        val reply = activeReplyText
        val hasPendingQuestion = !question.isNullOrBlank() && pendingQuestionCallback != null
        questionActionRow?.visibility = if (hasPendingQuestion) View.VISIBLE else View.GONE

        if (hasPendingQuestion) {
            expandedStatusView?.text = ""
            agentMessageView?.text = if (question!!.startsWith("-")) question else "- $question"
            agentMessageView?.visibility = View.VISIBLE
            inputEditText?.hint = "Yanıt yaz..."
        } else if (!reply.isNullOrBlank()) {
            expandedStatusView?.text = ""
            val formattedReply = if (reply.startsWith("-") || reply.startsWith("⏰")) reply else "- $reply"
            agentMessageView?.text = formattedReply
            agentMessageView?.visibility = View.VISIBLE
            inputEditText?.hint = "Not veya soru yaz..."
        } else {
            agentMessageView?.visibility = View.GONE
            agentMessageView?.text = ""
            inputEditText?.hint = "Not veya soru yaz..."
            expandedStatusView?.text = when (currentState) {
                NotchState.THINKING -> "DÜŞÜNÜYOR"
                NotchState.ACTIVE -> "İŞLİYOR"
                else -> ""
            }
        }
        refreshSendButtonVisual()
    }

    private fun updateUiContent(
        stepNumber: Int,
        plan: AgentPlan?,
        liveDetail: String?,
        state: NotchState
    ) {
        if (state == NotchState.IDLE) {
            renderCompactIdle()
            return
        }

        // Pure Monochrome Dot & Status Word
        val defaultWord = when (state) {
            NotchState.IDLE -> ""
            NotchState.THINKING -> "Düşünüyor"
            NotchState.ACTIVE -> "İşliyor"
            NotchState.COMPLETED -> "Tamamlandı"
            NotchState.FAILED -> "Durduruldu"
        }
        (statusDotView?.background as? GradientDrawable)?.setColor(Color.WHITE)
        statusDotView?.visibility = View.VISIBLE

        if (state == NotchState.ACTIVE || state == NotchState.THINKING) {
            startPulseAnimation()
        } else {
            stopPulseAnimation()
        }

        val singleWord = extractSingleWord(state, liveDetail, defaultWord)
        if (singleWord != lastRenderedWord) {
            lastRenderedWord = singleWord
            statusWordView?.text = singleWord
        }
        statusWordView?.visibility = View.VISIBLE

        val steps = plan?.steps.orEmpty()
        if (steps.size > 1 && state != NotchState.COMPLETED && state != NotchState.FAILED) {
            val activeIndex = steps.firstOrNull { it.isInProgress }?.index
                ?: steps.firstOrNull { !it.isCompleted }?.index
                ?: steps.size
            stageBadgeView?.text = "$activeIndex/${steps.size}"
            stageBadgeView?.visibility = View.VISIBLE
        } else {
            stageBadgeView?.visibility = View.GONE
        }

        if (!isExpanded) {
            rootContainer?.let { container ->
                val lp = container.layoutParams as? WindowManager.LayoutParams
                val targetWidth = if (stageBadgeView?.visibility == View.VISIBLE) dp(132) else dp(120)
                if (lp != null && (lp.width != targetWidth || lp.height != dp(28))) {
                    lp.width = targetWidth
                    lp.height = dp(28)
                    windowManager?.updateViewLayout(container, lp)
                }
            }
        }
    }

    private fun extractSingleWord(state: NotchState, liveDetail: String?, defaultWord: String): String {
        if (state == NotchState.COMPLETED) return "Tamamlandı"
        if (state == NotchState.FAILED) return "Durduruldu"

        val trimmed = liveDetail?.trim().orEmpty()
        if (trimmed.isEmpty()) return defaultWord

        if (!trimmed.contains(" ") && trimmed.length in 3..14) {
            return trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        val lower = trimmed.lowercase()
        return when {
            lower.contains("alarm") || lower.contains("saat") -> "Alarm"
            lower.contains("sayaç") || lower.contains("timer") || lower.contains("geri") -> "Sayaç"
            lower.contains("kayde") -> "Kaydediyor"
            lower.contains("özet") -> "Özetliyor"
            lower.contains("soru") || lower.contains("?") -> "Soru"
            lower.contains("onay") -> "Onay"
            lower.contains("plan") || lower.contains("analiz") -> "Planlıyor"
            state == NotchState.THINKING -> "Düşünüyor"
            state == NotchState.ACTIVE -> "İşliyor"
            else -> defaultWord
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun dpF(value: Int): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        )
    }

    private fun isScreenInteractive(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive ?: true
    }

    private fun startPulseAnimation() {
        if (!isScreenInteractive()) {
            stopPulseAnimation()
            return
        }
        if (pulseAnimator != null) return
        statusDotView?.let { dot ->
            pulseAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
                duration = 800L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        statusDotView?.alpha = 1f
    }

    private fun startBorderPulseAnimation() {
        stopBorderPulseAnimation()
        val bg = rootContainer?.background as? GradientDrawable ?: return
        borderPulseAnimator = ValueAnimator.ofArgb(Color.WHITE, Color.parseColor("#333333")).apply {
            duration = 550L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val c = anim.animatedValue as Int
                bg.setStroke(dp(1), c)
            }
            start()
        }
    }

    private fun stopBorderPulseAnimation() {
        borderPulseAnimator?.cancel()
        borderPulseAnimator = null
        val bg = rootContainer?.background as? GradientDrawable
        bg?.setStroke(dp(1), if (isExpanded) Color.parseColor("#333333") else Color.parseColor("#222222"))
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        stopPulseAnimation()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        if (currentState == NotchState.ACTIVE || currentState == NotchState.THINKING || countdownTargetMs > System.currentTimeMillis()) {
                            startPulseAnimation()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            screenStateReceiver = receiver
        }
    }

    private fun unregisterScreenStateReceiver() {
        screenStateReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            screenStateReceiver = null
        }
    }

    enum class NotchState {
        IDLE,
        THINKING,
        ACTIVE,
        COMPLETED,
        FAILED
    }
}
