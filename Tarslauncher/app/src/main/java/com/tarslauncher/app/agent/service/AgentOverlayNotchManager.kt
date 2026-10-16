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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
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
    private var inputEditText: EditText? = null
    private var stopButtonView: TextView? = null
    private var startButtonView: TextView? = null
    private var expandedStatusView: TextView? = null

    @Volatile
    var onStopRequested: (() -> Unit)? = null

    @Volatile
    var onTaskSubmitted: ((String) -> Unit)? = null

    private var currentPlan: AgentPlan? = null
    private var currentStepNum: Int = 1
    private var lastRenderedWord: String = ""
    private var currentState: NotchState = NotchState.IDLE
    private var isExpanded: Boolean = false

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

            if (!isExpanded) {
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
                    if (!isExpanded) {
                        showIdle()
                    }
                }
                autoIdleRunnable = toIdleTask
                mainHandler.postDelayed(toIdleTask, 2800L)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            autoIdleRunnable?.let { mainHandler.removeCallbacks(it) }
            autoIdleRunnable = null
            unregisterScreenStateReceiver()
            stopPulseAnimation()

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

            val view = rootContainer
            rootContainer = null
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

        // Dynamic Island Master Container: Solid Apple OLED Black (#000000)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(20)
                setColor(Color.parseColor("#FF000000")) // Pure Apple Black
                setStroke(dp(1), Color.parseColor("#242426"))
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

        // ----------------- COMPACT ROW -----------------
        val compactRow = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
        }
        compactRowView = compactRow

        // 1. Indicator Dot
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }
        statusDotView = dot
        compactRow.addView(dot)

        // 2. Single-Word Status Text
        val wordText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(7)
            }
            text = "TARS"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0f
            includeFontPadding = false
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
        }
        statusWordView = wordText
        compactRow.addView(wordText)

        // 3. Micro Stage Badge (e.g. "2/4" or "⌄")
        val stageBadge = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            text = ""
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, IslandIcon("down", Color.parseColor("#98989D")), null)
            setTextColor(Color.parseColor("#98989D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            includeFontPadding = false
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            letterSpacing = 0.03f
            maxLines = 1
        }
        stageBadgeView = stageBadge
        compactRow.addView(stageBadge)

        container.addView(compactRow)

        // ----------------- EXPANDED INPUT VIEW -----------------
        val expandedView = buildExpandedView()
        expandedContainerView = expandedView
        expandedView.visibility = View.GONE
        container.addView(expandedView)

        // Top-Center Placement LayoutParams
        val params = WindowManager.LayoutParams(
            dp(176),
            dp(40),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(14)
        }

        runCatching {
            wm.addView(container, params)
            rootContainer = container
            registerScreenStateReceiver()

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
        }
    }

    private fun buildExpandedView(): LinearLayout {
        val expanded = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(20))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        titles.addView(TextView(context).apply {
            text = "TARS"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = -0.025f
            includeFontPadding = false
        })
        expandedStatusView = TextView(context).apply {
            text = "Bir sonraki adım, sende."
            setTextColor(Color.parseColor("#98989D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(0, dp(5), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        titles.addView(expandedStatusView)
        header.addView(titles)
        header.addView(TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setCompoundDrawablesRelativeWithIntrinsicBounds(IslandIcon("up", Color.parseColor("#C7C7CC")), null, null, null)
            background = controlBackground(Color.parseColor("#161618"), 22)
            contentDescription = "Adacığı küçült"
            isFocusable = true
            setOnClickListener { collapseIsland() }
        })
        expanded.addView(header)

        val input = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(20)
                bottomMargin = dp(16)
            }
            background = roundedSurface(Color.parseColor("#111113"), 18, Color.parseColor("#29292C"))
            hint = "Ne yapmamı istersin?"
            setHintTextColor(Color.parseColor("#8E8E93"))
            setTextColor(Color.parseColor("#F5F5F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            minHeight = dp(88)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            contentDescription = "TARS için görev"
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitTaskFromInput()
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    startButtonView?.isEnabled = !s.isNullOrBlank()
                    startButtonView?.alpha = if (s.isNullOrBlank()) 0.45f else 1f
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        inputEditText = input
        expanded.addView(input)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stopButtonView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(48))
            text = "Durdur"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF6961"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), 0, dp(16), 0)
            background = controlBackground(Color.parseColor("#211010"), 24)
            visibility = View.GONE
            isFocusable = true
            setOnClickListener {
                onStopRequested?.invoke()
                collapseIsland()
            }
        }
        actions.addView(stopButtonView)
        actions.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        startButtonView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(48))
            text = "Gönder"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(20), 0, dp(16), 0)
            compoundDrawablePadding = dp(10)
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, IslandIcon("send", Color.BLACK), null)
            background = controlBackground(Color.parseColor("#F5F5F7"), 24, darkRipple = true)
            isEnabled = false
            alpha = 0.45f
            isFocusable = true
            setOnClickListener { submitTaskFromInput() }
        }
        actions.addView(startButtonView)
        expanded.addView(actions)
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
        lp.width = minOf(dp(360), context.resources.displayMetrics.widthPixels - dp(24))
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE

        wm.updateViewLayout(container, lp)

        // 2. Adjust background corner radius for expanded card
        (container.background as? GradientDrawable)?.cornerRadius = dpF(32)

        // 3. Switch views: hide compact row, show expanded view
        compactRowView?.visibility = View.GONE
        expandedContainerView?.visibility = View.VISIBLE
        updateExpandedState()

        // 4. Smooth spring expansion animation
        container.scaleX = 0.92f
        container.scaleY = 0.92f
        container.alpha = 0.9f
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
        val lp = container.layoutParams as? WindowManager.LayoutParams ?: return

        // 2. Restore compact WindowManager params with FLAG_NOT_FOCUSABLE and remove FLAG_WATCH_OUTSIDE_TOUCH
        lp.width = dp(176)
        lp.height = dp(40)
        lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()) or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        wm.updateViewLayout(container, lp)

        // 3. Restore stadium pill background
        (container.background as? GradientDrawable)?.cornerRadius = dpF(20)

        // 4. Switch views
        expandedContainerView?.visibility = View.GONE
        compactRowView?.visibility = View.VISIBLE

        // 5. Re-render compact state
        if (currentState == NotchState.IDLE) {
            renderCompactIdle()
        }
    }

    private fun submitTaskFromInput() {
        val taskText = inputEditText?.text?.toString()?.trim().orEmpty()
        if (taskText.isNotBlank()) {
            collapseIsland()
            inputEditText?.setText("")
            onTaskSubmitted?.invoke(taskText)
        }
    }

    private fun renderCompactIdle() {
        (statusDotView?.background as? GradientDrawable)?.setColor(Color.WHITE)
        stopPulseAnimation()
        statusDotView?.alpha = 0.8f
        statusWordView?.text = "TARS"
        lastRenderedWord = "TARS"
        stageBadgeView?.text = ""
        stageBadgeView?.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, IslandIcon("down", Color.parseColor("#98989D")), null)
        stageBadgeView?.visibility = View.VISIBLE
    }

    private fun updateExpandedState() {
        val isRunning = currentState == NotchState.ACTIVE || currentState == NotchState.THINKING
        stopButtonView?.visibility = if (isRunning) View.VISIBLE else View.GONE
        expandedStatusView?.text = when (currentState) {
            NotchState.IDLE -> "Bir sonraki adım, sende."
            NotchState.THINKING -> "Görevin üzerinde düşünüyorum…"
            NotchState.ACTIVE -> "Görevin üzerinde çalışıyorum…"
            NotchState.COMPLETED -> "Görev tamamlandı."
            NotchState.FAILED -> "Görev durduruldu."
        }
    }

    private fun updateUiContent(
        stepNumber: Int,
        plan: AgentPlan?,
        liveDetail: String?,
        state: NotchState
    ) {
        val (dotColor, defaultWord) = when (state) {
            NotchState.IDLE -> Color.WHITE to "TARS"
            NotchState.THINKING -> Color.parseColor("#FF9F0A") to "Düşünüyor" // Apple Amber
            NotchState.ACTIVE -> Color.parseColor("#30D158") to "İşliyor"     // Apple Green
            NotchState.COMPLETED -> Color.parseColor("#30D158") to "Tamamlandı"
            NotchState.FAILED -> Color.parseColor("#FF453A") to "Durduruldu"  // Apple Red
        }
        (statusDotView?.background as? GradientDrawable)?.setColor(dotColor)

        if (state == NotchState.ACTIVE || state == NotchState.THINKING) {
            startPulseAnimation()
        } else {
            stopPulseAnimation()
        }

        // Resolve single-word status
        val singleWord = if (state == NotchState.IDLE) "TARS" else extractSingleWord(state, liveDetail, defaultWord)
        if (singleWord != lastRenderedWord) {
            lastRenderedWord = singleWord
            statusWordView?.text = singleWord
        }

        // Micro Stage Count (e.g. "2/4" or "1/3")
        val steps = plan?.steps.orEmpty()
        if (steps.size > 1 && state != NotchState.COMPLETED && state != NotchState.FAILED && state != NotchState.IDLE) {
            val activeIndex = steps.firstOrNull { it.isInProgress }?.index
                ?: steps.firstOrNull { !it.isCompleted }?.index
                ?: steps.size
            stageBadgeView?.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            stageBadgeView?.text = "$activeIndex/${steps.size}"
            stageBadgeView?.visibility = View.VISIBLE
        } else if (state == NotchState.IDLE) {
            stageBadgeView?.text = ""
            stageBadgeView?.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, IslandIcon("down", Color.parseColor("#98989D")), null)
            stageBadgeView?.visibility = View.VISIBLE
        } else {
            stageBadgeView?.visibility = View.GONE
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
            context.registerReceiver(receiver, filter)
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
