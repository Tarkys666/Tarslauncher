package com.tarslauncher.app.agent.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
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
 * Authentic Apple Dynamic Island Overlay powered by TYPE_ACCESSIBILITY_OVERLAY.
 * - Pure OLED Black (#000000) stadium pill at top-center.
 * - In compact mode: Displays single-word status, pulsing indicator dot, and micro stage badge.
 * - When tapped: Smoothly expands downwards into a rich Apple-style input sheet to give tasks.
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

    // Expanded view elements
    private var expandedContainerView: LinearLayout? = null
    private var inputEditText: EditText? = null
    private var stopButtonView: TextView? = null
    private var startButtonView: TextView? = null

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
            currentState = NotchState.IDLE
            currentPlan = null
            currentStepNum = 1

            if (rootContainer == null) {
                createAndAttachOverlay()
            }

            if (!isExpanded) {
                renderCompactIdle()
            }
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
            currentStepNum = stepNumber
            currentState = state
            if (plan != null) {
                currentPlan = plan
            }

            if (rootContainer == null) {
                createAndAttachOverlay()
            }

            updateUiContent(stepNumber, currentPlan, liveDetail, state)

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
            pulseAnimator?.cancel()
            pulseAnimator = null

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

    private fun createAndAttachOverlay() {
        val wm = windowManager ?: return

        // Dynamic Island Master Container: Solid Apple OLED Black (#000000)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(18) // Stadium capsule for compact 36dp height
                setColor(Color.parseColor("#FF000000")) // Pure Apple Black
                setStroke(dp(1), Color.parseColor("#1FFFFFFF")) // Ultra-subtle matte glass edge
            }
            elevation = dpF(8)
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
                dp(36)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.02f
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
            text = "⌄"
            setTextColor(Color.parseColor("#73FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
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
            dp(154),
            dp(36),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(14)
        }

        runCatching {
            wm.addView(container, params)
            rootContainer = container

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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }

        // Top Title Row
        val titleRow = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "✦ TARS Agent"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.03f
        }
        titleRow.addView(titleText)

        // Collapse chevron
        val collapseBtn = TextView(context).apply {
            text = "⌃"
            setTextColor(Color.parseColor("#80FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener { collapseIsland() }
        }
        titleRow.addView(collapseBtn)
        expanded.addView(titleRow)

        // Apple-style Search / Prompt Input Field
        val input = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(9)
                bottomMargin = dp(11)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(12)
                setColor(Color.parseColor("#1C1C1E")) // Apple secondary system fill
            }
            hint = "TARS'a bir görev verin..."
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setPadding(dp(13), dp(9), dp(13), dp(9))
            maxLines = 2
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitTaskFromInput()
                    true
                } else {
                    false
                }
            }
        }
        inputEditText = input
        expanded.addView(input)

        // Bottom Action Row (Stop + Start)
        val actionRow = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Durdur (Stop) Button
        val stopBtn = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "✕ Durdur"
            setTextColor(Color.parseColor("#FF453A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(12)
                setColor(Color.parseColor("#26FF453A"))
            }
            visibility = View.GONE
            setOnClickListener {
                onStopRequested?.invoke()
                collapseIsland()
            }
        }
        stopButtonView = stopBtn
        actionRow.addView(stopBtn)

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        actionRow.addView(spacer)

        // Başlat (Start) Button: Apple System Blue
        val startBtn = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "Başlat  ➔"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(13)
                setColor(Color.parseColor("#007AFF")) // Apple Blue
            }
            setOnClickListener {
                submitTaskFromInput()
            }
        }
        startButtonView = startBtn
        actionRow.addView(startBtn)

        expanded.addView(actionRow)
        return expanded
    }

    private fun expandIsland() {
        if (isExpanded) return
        isExpanded = true

        val wm = windowManager ?: return
        val container = rootContainer ?: return
        val lp = container.layoutParams as? WindowManager.LayoutParams ?: return

        // 1. Expand WindowManager params and remove FLAG_NOT_FOCUSABLE so EditText can receive keyboard
        lp.width = dp(330)
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE

        wm.updateViewLayout(container, lp)

        // 2. Adjust background corner radius for expanded card
        (container.background as? GradientDrawable)?.cornerRadius = dpF(24)

        // 3. Switch views: hide compact row, show expanded view
        compactRowView?.visibility = View.GONE
        expandedContainerView?.visibility = View.VISIBLE

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

        // 2. Restore compact WindowManager params with FLAG_NOT_FOCUSABLE
        lp.width = dp(154)
        lp.height = dp(36)
        lp.flags = lp.flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        wm.updateViewLayout(container, lp)

        // 3. Restore stadium pill background
        (container.background as? GradientDrawable)?.cornerRadius = dpF(18)

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
        pulseAnimator?.cancel()
        statusDotView?.alpha = 0.8f
        statusWordView?.text = "TARS"
        stageBadgeView?.text = "⌄"
        stageBadgeView?.visibility = View.VISIBLE
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
            if (pulseAnimator == null) {
                statusDotView?.let { dot ->
                    pulseAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.4f).apply {
                        duration = 800L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        start()
                    }
                }
            }
        } else {
            pulseAnimator?.cancel()
            pulseAnimator = null
            statusDotView?.alpha = 1f
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
            stageBadgeView?.text = "$activeIndex/${steps.size}"
            stageBadgeView?.visibility = View.VISIBLE
        } else if (state == NotchState.IDLE) {
            stageBadgeView?.text = "⌄"
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

    enum class NotchState {
        IDLE,
        THINKING,
        ACTIVE,
        COMPLETED,
        FAILED
    }
}
