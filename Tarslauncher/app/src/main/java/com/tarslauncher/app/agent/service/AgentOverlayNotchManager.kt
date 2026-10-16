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
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.tarslauncher.app.agent.domain.model.AgentPlan

/**
 * Apple Dynamic Island style minimal pill HUD powered by TYPE_ACCESSIBILITY_OVERLAY.
 * Placed top-center with fixed width (no jumping/resizing), matte graphite background,
 * single-word status, micro stage progression, and tap-to-dismiss (no X button).
 */
class AgentOverlayNotchManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager? by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    private var rootContainer: LinearLayout? = null
    private var statusDotView: View? = null
    private var statusWordView: TextView? = null
    private var stageBadgeView: TextView? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var autoHideRunnable: Runnable? = null

    @Volatile
    var onStopRequested: (() -> Unit)? = null

    private var currentPlan: AgentPlan? = null
    private var currentStepNum: Int = 1
    private var lastRenderedWord: String = ""

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
                mainHandler.postDelayed(hideTask, 2600L)
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

            // Smooth shrink & fade exit
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

        // Apple Dynamic Island: Matte graphite, fixed capsule size (no jumping/resizing), centered content
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(18) // Stadium capsule for 36dp height
                setColor(Color.parseColor("#1C1C1E")) // Apple matte graphite/charcoal
                setStroke(dp(1), Color.parseColor("#2C2C2E")) // Subtle matte border
            }
            elevation = dpF(4)
            // Tap the pill to stop/dismiss without an intrusive 'X' button
            setOnClickListener {
                onStopRequested?.invoke()
                hide()
            }
        }

        // 1. Glowing Status Indicator Dot
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#30D158")) // Apple bright green default
            }
        }
        statusDotView = dot
        container.addView(dot)

        // 2. Single-Word Status Label
        val wordText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(7)
            }
            text = "Başlatılıyor"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.01f
            maxLines = 1
        }
        statusWordView = wordText
        container.addView(wordText)

        // 3. Micro Stage Badge (e.g. "2/4" or "1/3")
        val stageBadge = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            setTextColor(Color.parseColor("#73FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            letterSpacing = 0.03f
            maxLines = 1
        }
        stageBadgeView = stageBadge
        container.addView(stageBadge)

        // Top-Center Placement with FIXED width and height: completely prevents resizing/jumping!
        val params = WindowManager.LayoutParams(
            dp(154),
            dp(36),
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

            // Dynamic Island Pop Entrance Animation
            container.scaleX = 0.7f
            container.scaleY = 0.7f
            container.alpha = 0f
            container.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(200L)
                .setInterpolator(DecelerateInterpolator())
                .start()

            // Breathing pulse animation on the indicator dot
            pulseAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.4f).apply {
                duration = 800L
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
        val (dotColor, defaultWord) = when (state) {
            NotchState.THINKING -> Color.parseColor("#FF9F0A") to "Düşünüyor" // Apple Amber
            NotchState.ACTIVE -> Color.parseColor("#30D158") to "İşliyor"     // Apple Green
            NotchState.COMPLETED -> Color.parseColor("#30D158") to "Tamamlandı"
            NotchState.FAILED -> Color.parseColor("#FF453A") to "Durduruldu"  // Apple Red
        }
        (statusDotView?.background as? GradientDrawable)?.setColor(dotColor)

        // Resolve single-word status
        val singleWord = extractSingleWord(state, liveDetail, defaultWord)
        if (singleWord != lastRenderedWord) {
            lastRenderedWord = singleWord
            statusWordView?.text = singleWord
        }

        // Micro Stage Count (e.g. "2/4" or "1/3")
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
        THINKING,
        ACTIVE,
        COMPLETED,
        FAILED
    }
}
