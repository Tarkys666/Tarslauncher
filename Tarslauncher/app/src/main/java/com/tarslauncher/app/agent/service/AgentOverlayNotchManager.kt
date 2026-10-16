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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.tarslauncher.app.agent.domain.model.AgentPlan

/**
 * Floating Edge Notch / HUD powered by TYPE_ACCESSIBILITY_OVERLAY.
 * Stays visible on the screen edge while TARS operates across external apps,
 * displaying the active plan steps and live reasoning status one by one.
 */
class AgentOverlayNotchManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager? by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    private var rootContainer: LinearLayout? = null
    private var statusDotView: View? = null
    private var headerTextView: TextView? = null
    private var stepPillsTextView: TextView? = null
    private var stageTitleTextView: TextView? = null
    private var detailTextView: TextView? = null
    private var stopButtonView: TextView? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var autoHideRunnable: Runnable? = null

    @Volatile
    var onStopRequested: (() -> Unit)? = null

    private var currentPlan: AgentPlan? = null
    private var currentStepNum: Int = 1

    fun showOrUpdate(
        stepNumber: Int = currentStepNum,
        plan: AgentPlan? = currentPlan,
        liveDetail: String? = null,
        state: NotchState = NotchState.ACTIVE
    ) {
        mainHandler.post {
            autoHideRunnable?.let { mainHandler.removeCallbacks(it) }
            currentStepNum = stepNumber
            if (plan != null) {
                currentPlan = plan
            }

            if (rootContainer == null) {
                createAndAttachOverlay()
            }

            updateUiContent(stepNumber, currentPlan, liveDetail, state)

            if (state == NotchState.COMPLETED || state == NotchState.FAILED) {
                val hideTask = Runnable { hide() }
                autoHideRunnable = hideTask
                mainHandler.postDelayed(hideTask, 3500L)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            autoHideRunnable?.let { mainHandler.removeCallbacks(it) }
            autoHideRunnable = null
            pulseAnimator?.cancel()
            pulseAnimator = null

            val view = rootContainer ?: return@post
            rootContainer = null
            runCatching {
                windowManager?.removeViewImmediate(view)
            }
        }
    }

    private fun createAndAttachOverlay() {
        val wm = windowManager ?: return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // Sleek asymmetric pill: sharper on the right screen edge, rounded 16dp on the inner left side
                cornerRadii = floatArrayOf(
                    dpF(16), dpF(16), // top-left
                    dpF(6), dpF(6),   // top-right
                    dpF(6), dpF(6),   // bottom-right
                    dpF(16), dpF(16)  // bottom-left
                )
                setColor(Color.parseColor("#EB09090B"))
                setStroke(dp(1), Color.parseColor("#4DFFFFFF"))
            }
            elevation = dpF(10)
        }

        // Top row: Pulsing Dot + Step Header + Plan Dots + Stop Button
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#69F0AE"))
            }
        }
        statusDotView = dot
        topRow.addView(dot)

        val headerText = TextView(context).apply {
            text = "TARS // ADIM 1"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.06f
        }
        headerTextView = headerText
        topRow.addView(headerText)

        val pillsText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(6)
            }
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(Color.parseColor("#99FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
        }
        stepPillsTextView = pillsText
        topRow.addView(pillsText)

        val stopBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#CCFF5252"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(5), dp(1), dp(3), dp(1))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(6)
                setColor(Color.parseColor("#26FF5252"))
            }
            setOnClickListener {
                onStopRequested?.invoke()
                hide()
            }
        }
        stopButtonView = stopBtn
        topRow.addView(stopBtn)

        container.addView(topRow)

        // Second row: Current Plan Stage Title
        val stageTitle = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
            maxWidth = dp(215)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        stageTitleTextView = stageTitle
        container.addView(stageTitle)

        // Third row: Live Action / Thought Detail
        val detailText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
            }
            maxWidth = dp(215)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#B8E4E4E7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.MONOSPACE
        }
        detailTextView = detailText
        container.addView(detailText)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(6)
            y = dp(72)
        }

        runCatching {
            wm.addView(container, params)
            rootContainer = container

            // Smooth slide-in animation from the right edge
            container.translationX = dpF(120)
            container.alpha = 0f
            container.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(240L)
                .setInterpolator(DecelerateInterpolator())
                .start()

            // Subtle pulse animation on the status dot
            pulseAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
                duration = 750L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    private fun updateUiContent(
        stepNumber: Int,
        plan: AgentPlan?,
        liveDetail: String?,
        state: NotchState
    ) {
        val dotColor = when (state) {
            NotchState.ACTIVE -> Color.parseColor("#69F0AE")
            NotchState.THINKING -> Color.parseColor("#FFD740")
            NotchState.COMPLETED -> Color.parseColor("#00E676")
            NotchState.FAILED -> Color.parseColor("#FF5252")
        }
        (statusDotView?.background as? GradientDrawable)?.setColor(dotColor)

        val steps = plan?.steps.orEmpty()
        val activeStage = steps.firstOrNull { it.isInProgress }
            ?: steps.firstOrNull { !it.isCompleted }
            ?: steps.lastOrNull()

        val stageIndex = activeStage?.index ?: 1
        val totalStages = steps.size.coerceAtLeast(1)

        headerTextView?.text = when (state) {
            NotchState.COMPLETED -> "TARS // TAMAMLANDI"
            NotchState.FAILED -> "TARS // DURDURULDU"
            else -> "AŞAMA $stageIndex/$totalStages • ADIM $stepNumber"
        }

        if (steps.isNotEmpty()) {
            val pills = steps.joinToString(" ") { step ->
                when {
                    step.isCompleted || state == NotchState.COMPLETED -> "●"
                    step.isInProgress -> "◉"
                    else -> "○"
                }
            }
            stepPillsTextView?.text = pills
            stepPillsTextView?.visibility = View.VISIBLE
        } else {
            stepPillsTextView?.visibility = View.GONE
        }

        val stageTitleStr = activeStage?.title ?: plan?.goal ?: "Görev yürütülüyor"
        stageTitleTextView?.text = "▸ $stageTitleStr"

        if (!liveDetail.isNullOrBlank()) {
            detailTextView?.text = liveDetail
            detailTextView?.visibility = View.VISIBLE
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
        THINKING,
        ACTIVE,
        COMPLETED,
        FAILED
    }
}
