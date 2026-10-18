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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.TypedValue
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.Editable
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

/**
 * OLED-black task capsule powered by TYPE_ACCESSIBILITY_OVERLAY.
 * Expands into a focused task composer with accessible native controls.
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
    private var autoIdleRunnable: Runnable? = null
    private var screenStateReceiver: BroadcastReceiver? = null

    // Expanded view elements
    private var expandedContainerView: LinearLayout? = null
    private var agentMessageView: TextView? = null
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

                    if (currentState == NotchState.ACTIVE || currentState == NotchState.THINKING) {
                        startPulseAnimation()
                    }
                }
            }
        }
    }

    fun showIdle() {
        mainHandler.post {
            autoIdleRunnable?.let { mainHandler.removeCallbacks(it) }
            if (!com.tarslauncher.app.agent.data.AgentPreferences(context).isAgentEnabled()) {
                hide()
                return@post
            }
            currentState = NotchState.IDLE
            currentPlan = null
            currentStepNum = 1

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
            if (!com.tarslauncher.app.agent.data.AgentPreferences(context).isAgentEnabled()) {
                hide()
                return@post
            }
            currentStepNum = stepNumber
            currentState = state
            if (plan != null) {
                currentPlan = plan
            }

            if (rootContainer == null) {
                createAndAttachOverlay()
            }

            updateUiContent(stepNumber, currentPlan, liveDetail, state)
            updateExpandedState()

            // Update stop button state in expanded view if visible
            stopButtonView?.visibility = if (state == NotchState.ACTIVE || state == NotchState.THINKING) {
                View.VISIBLE
            } else {
                View.GONE
            }

            if (state == NotchState.COMPLETED || state == NotchState.FAILED) {
                val toIdleTask = Runnable {
                    if (!isExpanded && pendingQuestionCallback == null) {
                        showIdle()
                    }
                }
                autoIdleRunnable = toIdleTask
                mainHandler.postDelayed(toIdleTask, 2800L)
            }
        }
    }

    /**
     * Expands the Dynamic Island to ask the user a question from the LLM and waits for their reply.
     */
    fun promptUserQuestion(question: String, onAnswer: (String) -> Unit) {
        mainHandler.post {
            autoIdleRunnable?.let { mainHandler.removeCallbacks(it) }
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

            updateUiContent(currentStepNum, currentPlan, "Soru", NotchState.THINKING)
            if (!isExpanded) {
                expandIsland()
            } else {
                updateExpandedState()
                inputEditText?.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                inputEditText?.let { imm?.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
            }
        }
    }

    /**
     * Displays an AI reply/summary directly inside the Dynamic Island.
     */
    fun showAgentReply(reply: String) {
        mainHandler.post {
            if (!com.tarslauncher.app.agent.data.AgentPreferences(context).isAgentEnabled()) return@post
            if (rootContainer == null) {
                createAndAttachOverlay()
            }
            activeReplyText = reply
            activeQuestionText = null
            pendingQuestionCallback = null
            updateExpandedState()
        }
    }

    fun hide() {
        mainHandler.post {
            autoIdleRunnable?.let { mainHandler.removeCallbacks(it) }
            autoIdleRunnable = null
            unregisterScreenStateReceiver()
            stopPulseAnimation()

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
            unregisterScreenStateReceiver()
            stopPulseAnimation()

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
            agentMessageView = null
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

            // Dynamic Island Master Container: Pure OLED Black (#000000)
            val container = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpF(14)
                    setColor(Color.parseColor("#FF000000")) // Pure Apple Black
                    setStroke(dp(1), Color.parseColor("#1F1F23"))
                }
                elevation = 0f
            }

            // Tap compact pill to expand downwards
            container.setOnClickListener {
                if (!isExpanded) {
                    expandIsland()
                }
            }

            // Outside touch detection when expanded to collapse smoothly
            container.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE && isExpanded) {
                    collapseIsland()
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

            // 1. Indicator Dot (Hidden when idle, pulses when active)
            val dot = View(c).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                visibility = View.GONE
            }
            statusDotView = dot
            compactRow.addView(dot)

            // 2. Single-Word Status Text (Hidden when idle, no "TARS" text on island)
            val wordText = TextView(c).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(5)
                }
                text = ""
                setTextColor(Color.parseColor("#E4E4E7"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0f
                includeFontPadding = false
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                visibility = View.GONE
            }
            statusWordView = wordText
            compactRow.addView(wordText)

            // 3. Micro Stage Badge (Hidden when idle)
            val stageBadge = TextView(c).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(4)
                }
                text = ""
                setTextColor(Color.parseColor("#71717A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                includeFontPadding = false
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                maxLines = 1
                visibility = View.GONE
            }
            stageBadgeView = stageBadge
            compactRow.addView(stageBadge)

            container.addView(compactRow)

            // ----------------- EXPANDED INPUT VIEW -----------------
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

            // Entrance Pop Animation
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

    private fun buildExpandedView(c: Context = uiContext): LinearLayout {
        val expanded = LinearLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // --- Header Row: [Dot + "TARS"] [Status] ----- [Close Button] ---
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            }
        }

        val statusDot = View(c).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                marginEnd = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
        }
        header.addView(statusDot)

        val title = TextView(c).apply {
            text = "TARS"
            setTextColor(Color.parseColor("#71717A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            includeFontPadding = false
        }
        header.addView(title)

        val divider = TextView(c).apply {
            text = "·"
            setTextColor(Color.parseColor("#52525B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(6), 0, dp(6), 0)
            includeFontPadding = false
        }
        header.addView(divider)

        expandedStatusView = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            text = "Bir sonraki adım sende."
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        header.addView(expandedStatusView)

        // Minimal close icon (28dp square rounded button)
        val closeBtn = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setCompoundDrawablesRelativeWithIntrinsicBounds(IslandIcon("close", Color.parseColor("#A1A1AA")), null, null, null)
            background = controlBackground(Color.parseColor("#18181B"), 14)
            contentDescription = "Kapat"
            isFocusable = true
            setOnClickListener {
                pendingQuestionCallback?.invoke("")
                pendingQuestionCallback = null
                activeQuestionText = null
                activeReplyText = null
                collapseIsland()
            }
        }
        header.addView(closeBtn)

        expanded.addView(header)

        // --- Agent Question / Reply Bubble inside Dynamic Island ---
        val messageBox = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
            background = roundedSurface(Color.parseColor("#141418"), 12, Color.parseColor("#2E2E36"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(Color.parseColor("#FAFAFA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        agentMessageView = messageBox
        expanded.addView(messageBox)

        // --- 21st.dev Style Integrated Input Card ---
        val inputCard = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            background = roundedSurface(Color.parseColor("#121215"), 16, Color.parseColor("#27272A"))
            setPadding(dp(12), dp(10), dp(10), dp(10))
        }

        val input = EditText(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            background = null
            hint = "Mesajınızı yazın veya bir görev verin..."
            setHintTextColor(Color.parseColor("#52525B"))
            setTextColor(Color.parseColor("#F4F4F5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(dp(4), dp(4), dp(4), dp(8))
            minHeight = dp(56)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
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
                    val hasText = !s.isNullOrBlank()
                    startButtonView?.isEnabled = hasText
                    startButtonView?.alpha = if (hasText) 1f else 0.4f
                    if (hasText) {
                        startButtonView?.background = controlBackground(Color.WHITE, 16, darkRipple = true)
                        startButtonView?.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, IslandIcon("send", Color.BLACK), null)
                    } else {
                        startButtonView?.background = controlBackground(Color.parseColor("#27272A"), 16)
                        startButtonView?.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, IslandIcon("send", Color.parseColor("#71717A")), null)
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        inputEditText = input
        inputCard.addView(input)

        // Bottom Actions Bar inside Card
        val cardActions = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        stopButtonView = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(30))
            text = "Durdur"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F87171"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), 0, dp(12), 0)
            background = controlBackground(Color.parseColor("#221214"), 15)
            visibility = View.GONE
            isFocusable = true
            setOnClickListener {
                onStopRequested?.invoke()
                collapseIsland()
            }
        }
        cardActions.addView(stopButtonView)

        cardActions.addView(View(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })

        startButtonView = TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, IslandIcon("send", Color.parseColor("#71717A")), null)
            background = controlBackground(Color.parseColor("#27272A"), 16)
            isEnabled = false
            alpha = 0.4f
            isFocusable = true
            setOnClickListener { submitTaskFromInput() }
        }
        cardActions.addView(startButtonView)

        inputCard.addView(cardActions)
        expanded.addView(inputCard)

        return expanded
    }

    private fun roundedSurface(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(radius)
        setColor(color)
        border?.let { setStroke(dp(1), it) }
    }

    private fun controlBackground(color: Int, radius: Int, darkRipple: Boolean = false): RippleDrawable {
        val ripple = if (darkRipple) 0x24000000 else 0x24FFFFFF
        return RippleDrawable(ColorStateList.valueOf(ripple), roundedSurface(color, radius), roundedSurface(Color.WHITE, radius))
    }

    /** Density-independent line icons, avoiding font-dependent symbol glyphs. */
    private inner class IslandIcon(private val kind: String, color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        override fun getIntrinsicWidth() = dp(20)
        override fun getIntrinsicHeight() = dp(20)
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 20f, bounds.height() / 20f)
            val path = Path()
            when (kind) {
                "send" -> {
                    path.moveTo(10f, 15f); path.lineTo(10f, 5f)
                    path.moveTo(5.5f, 9.5f); path.lineTo(10f, 5f); path.lineTo(14.5f, 9.5f)
                }
                "up" -> {
                    path.moveTo(6f, 12f); path.lineTo(10f, 8f); path.lineTo(14f, 12f)
                }
                "close" -> {
                    path.moveTo(6.5f, 6.5f); path.lineTo(13.5f, 13.5f)
                    path.moveTo(13.5f, 6.5f); path.lineTo(6.5f, 13.5f)
                }
                else -> {
                    path.moveTo(6f, 8f); path.lineTo(10f, 12f); path.lineTo(14f, 8f)
                }
            }
            canvas.drawPath(path, paint)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun expandIsland() {
        if (isExpanded) return
        if (!com.tarslauncher.app.agent.data.AgentPreferences(context).isAgentEnabled()) {
            hide()
            return
        }
        isExpanded = true

        val wm = windowManager ?: return
        val container = rootContainer ?: return
        val lp = container.layoutParams as? WindowManager.LayoutParams ?: return

        // 1. Expand WindowManager params and remove FLAG_NOT_FOCUSABLE so EditText can receive keyboard
        lp.width = minOf(dp(340), context.resources.displayMetrics.widthPixels - dp(24))
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE

        wm.updateViewLayout(container, lp)

        // 2. Adjust background corner radius and color for expanded 21st.dev card
        (container.background as? GradientDrawable)?.apply {
            cornerRadius = dpF(20)
            setColor(Color.parseColor("#09090B"))
            setStroke(dp(1), Color.parseColor("#27272A"))
        }

        // 3. Switch views: hide compact row, show expanded view
        compactRowView?.visibility = View.GONE
        expandedContainerView?.visibility = View.VISIBLE
        updateExpandedState()

        // 4. Smooth spring expansion animation
        container.scaleX = 0.92f
        container.scaleY = 0.92f
        container.alpha = 0.9f

        container.isFocusableInTouchMode = true
        container.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (isExpanded) {
                    collapseIsland()
                    return@setOnKeyListener true
                }
            }
            false
        }

        container.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(220L)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        // 5. Request focus on input and pop software keyboard
        inputEditText?.requestFocus()
        mainHandler.postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputEditText?.let { imm?.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
        }, 80L)
    }

    private fun collapseIsland() {
        if (!isExpanded) return
        isExpanded = false

        // 1. Hide keyboard
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputEditText?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
        inputEditText?.clearFocus()

        val wm = windowManager ?: return
        val container = rootContainer ?: return
        container.setOnKeyListener(null)
        val lp = container.layoutParams as? WindowManager.LayoutParams ?: return

        // 2. Restore compact WindowManager params with FLAG_NOT_FOCUSABLE and remove FLAG_WATCH_OUTSIDE_TOUCH
        val isIdle = currentState == NotchState.IDLE
        lp.width = if (isIdle) dp(88) else dp(118)
        lp.height = dp(28)
        lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()) or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        wm.updateViewLayout(container, lp)

        // 3. Restore compact pill background (Pure OLED Black)
        (container.background as? GradientDrawable)?.apply {
            cornerRadius = dpF(14)
            setColor(Color.parseColor("#FF000000"))
            setStroke(dp(1), Color.parseColor("#1F1F23"))
        }

        // 4. Switch views
        expandedContainerView?.visibility = View.GONE
        compactRowView?.visibility = View.VISIBLE

        // 5. Re-render compact state
        if (isIdle) {
            renderCompactIdle()
        } else {
            updateUiContent(currentStepNum, currentPlan, null, currentState)
        }
    }

    private fun submitTaskFromInput() {
        val taskText = inputEditText?.text?.toString()?.trim().orEmpty()
        if (taskText.isNotBlank()) {
            inputEditText?.setText("")
            val questionCb = pendingQuestionCallback
            if (questionCb != null) {
                pendingQuestionCallback = null
                activeQuestionText = null
                activeReplyText = null
                updateExpandedState()
                collapseIsland()
                questionCb.invoke(taskText)
            } else {
                activeReplyText = null
                updateExpandedState()
                collapseIsland()
                onTaskSubmitted?.invoke(taskText)
            }
        }
    }

    private fun renderCompactIdle() {
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
        val isRunning = currentState == NotchState.ACTIVE || currentState == NotchState.THINKING
        stopButtonView?.visibility = if (isRunning) View.VISIBLE else View.GONE

        val question = activeQuestionText
        val reply = activeReplyText

        if (!question.isNullOrBlank()) {
            expandedStatusView?.text = "Yanıtınız bekleniyor…"
            agentMessageView?.text = question
            agentMessageView?.visibility = View.VISIBLE
            inputEditText?.hint = "Yanıtınızı yazın..."
        } else if (!reply.isNullOrBlank()) {
            expandedStatusView?.text = "TARS Yanıtı"
            agentMessageView?.text = reply
            agentMessageView?.visibility = View.VISIBLE
            inputEditText?.hint = "Mesajınızı yazın veya bir görev verin..."
        } else {
            agentMessageView?.visibility = View.GONE
            agentMessageView?.text = ""
            inputEditText?.hint = "Mesajınızı yazın veya bir görev verin..."
            expandedStatusView?.text = when (currentState) {
                NotchState.IDLE -> "Bir sonraki adım sende."
                NotchState.THINKING -> "Görevin üzerinde düşünüyorum…"
                NotchState.ACTIVE -> "Görevin üzerinde çalışıyorum…"
                NotchState.COMPLETED -> "Görev tamamlandı."
                NotchState.FAILED -> "Görev durduruldu."
            }
        }
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

        val (dotColor, defaultWord) = when (state) {
            NotchState.IDLE -> Color.WHITE to ""
            NotchState.THINKING -> Color.parseColor("#F59E0B") to "Düşünüyor" // Amber
            NotchState.ACTIVE -> Color.parseColor("#10B981") to "İşliyor"     // Emerald
            NotchState.COMPLETED -> Color.parseColor("#10B981") to "Tamamlandı"
            NotchState.FAILED -> Color.parseColor("#EF4444") to "Durduruldu"  // Red
        }
        (statusDotView?.background as? GradientDrawable)?.setColor(dotColor)
        statusDotView?.visibility = View.VISIBLE

        if (state == NotchState.ACTIVE || state == NotchState.THINKING) {
            startPulseAnimation()
        } else {
            stopPulseAnimation()
        }

        // Resolve single-word status
        val singleWord = extractSingleWord(state, liveDetail, defaultWord)
        if (singleWord != lastRenderedWord) {
            lastRenderedWord = singleWord
            statusWordView?.text = singleWord
        }
        statusWordView?.visibility = View.VISIBLE

        // Micro Stage Count (e.g. "2/4" or "1/3")
        val steps = plan?.steps.orEmpty()
        if (steps.size > 1 && state != NotchState.COMPLETED && state != NotchState.FAILED) {
            val activeIndex = steps.firstOrNull { it.isInProgress }?.index
                ?: steps.firstOrNull { !it.isCompleted }?.index
                ?: steps.size
            stageBadgeView?.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            stageBadgeView?.text = "$activeIndex/${steps.size}"
            stageBadgeView?.visibility = View.VISIBLE
        } else {
            stageBadgeView?.visibility = View.GONE
        }

        if (!isExpanded) {
            rootContainer?.let { container ->
                val lp = container.layoutParams as? WindowManager.LayoutParams
                val targetWidth = if (stageBadgeView?.visibility == View.VISIBLE) dp(130) else dp(118)
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

        // If it's already a single clean word, return capitalized
        if (!trimmed.contains(" ") && trimmed.length in 3..14) {
            return trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        val lower = trimmed.lowercase()
        return when {
            lower.contains("tıklan") || lower.contains("dokun") || lower.contains("tap") || lower.contains("click") -> "Tıklıyor"
            lower.contains("yazıl") || lower.contains("metin") || lower.contains("type") || lower.contains("input") -> "Yazıyor"
            lower.contains("kaydır") || lower.contains("scroll") || lower.contains("swipe") -> "Kaydırıyor"
            lower.contains("açıl") || lower.contains("launch") || lower.contains("open") -> "Açıyor"
            lower.contains("ses") || lower.contains("volume") -> "Ses"
            lower.contains("medya") || lower.contains("müzik") -> "Medya"
            lower.contains("fener") || lower.contains("ışık") || lower.contains("torch") -> "Fener"
            lower.contains("görüntü") || lower.contains("screenshot") -> "Görüntü"
            lower.contains("kilit") || lower.contains("lock") -> "Kilitliyor"
            lower.contains("ayar") || lower.contains("settings") -> "Ayarlar"
            lower.contains("alarm") -> "Alarm"
            lower.contains("sayaç") || lower.contains("timer") -> "Sayaç"
            lower.contains("ara") || lower.contains("search") -> "Arıyor"
            lower.contains("bekle") || lower.contains("wait") -> "Bekliyor"
            lower.contains("geri") || lower.contains("back") -> "Geri"
            lower.contains("ana ekran") || lower.contains("home") -> "Ana Ekran"
            lower.contains("soru") || lower.contains("?") -> "Soru"
            lower.contains("onay") -> "Onay"
            lower.contains("incele") || lower.contains("ekran") || lower.contains("karar") -> "İnceliyor"
            lower.contains("plan") || lower.contains("hedef") || lower.contains("analiz") -> "Planlıyor"
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
            pulseAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.4f).apply {
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

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        stopPulseAnimation()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        if (currentState == NotchState.ACTIVE || currentState == NotchState.THINKING) {
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
