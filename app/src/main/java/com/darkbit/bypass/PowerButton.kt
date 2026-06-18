package com.darkbit.bypass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class PowerButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    var state: State = State.DISCONNECTED
        set(value) {
            if (field != value) {
                field = value
                updateAnimations()
                invalidate()
            }
        }

    var onClickListener: (() -> Unit)? = null

    private val paintInner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintIcon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG)

    private var glowPulse = 0f
    private var glowAnimator: ValueAnimator? = null
    private var spinAngle = 0f
    private var spinAnimator: ValueAnimator? = null
    private var pressScale = 1f

    // New premium palette
    private val colorDisconnectedOuter = Color.parseColor("#121118")
    private val colorDisconnectedInner = Color.parseColor("#1C1A24")

    private val colorConnectedCyan = Color.parseColor("#C4C0FF")
    private val colorConnectedBlue = Color.parseColor("#8E88FA")

    private val colorConnectingPurple1 = Color.parseColor("#B5B0FF")
    private val colorConnectingPurple2 = Color.parseColor("#887FFF")

    private val colorRingDim = Color.parseColor("#2E2A3C")
    private val colorIconDim = Color.parseColor("#565370")

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
        startBreathingAnimation()
    }

    private fun startBreathingAnimation() {
        glowAnimator?.cancel()
        glowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                glowPulse = it.animatedFraction
                invalidate()
            }
            start()
        }
    }

    private fun startSpinAnimation() {
        spinAnimator?.cancel()
        spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSpinAnimation() {
        spinAnimator?.cancel()
        spinAnimator = null
        spinAngle = 0f
    }

    private fun updateAnimations() {
        when (state) {
            State.CONNECTING -> startSpinAnimation()
            else -> stopSpinAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) / 2f
        val radius = maxRadius * 0.52f * pressScale

        // 1. Outer glow
        if (state != State.DISCONNECTED) {
            val glowColor = if (state == State.CONNECTED) colorConnectedCyan else colorConnectingPurple1
            val glowMaxR = radius * 1.8f
            val alpha = (40 + 40 * glowPulse).toInt()

            val shader = RadialGradient(
                cx, cy, glowMaxR,
                intArrayOf(glowColor, Color.TRANSPARENT),
                floatArrayOf(0.2f, 1.0f),
                Shader.TileMode.CLAMP
            )
            paintGlow.shader = shader
            paintGlow.alpha = alpha
            canvas.drawCircle(cx, cy, glowMaxR, paintGlow)
        }

        // 2. Outer ring
        paintRing.apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            shader = null
        }
        when (state) {
            State.DISCONNECTED -> {
                paintRing.color = colorRingDim
                paintRing.alpha = 120
                canvas.drawCircle(cx, cy, radius, paintRing)
            }
            State.CONNECTING -> {
                // Spinning arc segments
                val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
                paintRing.strokeWidth = 3f
                paintRing.shader = SweepGradient(
                    cx, cy,
                    intArrayOf(Color.TRANSPARENT, colorConnectingPurple1, colorConnectingPurple2, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.25f, 0.5f, 1f)
                )
                paintRing.alpha = 200
                canvas.save()
                canvas.rotate(spinAngle, cx, cy)
                canvas.drawArc(arcRect, 0f, 270f, false, paintRing)
                canvas.restore()
            }
            State.CONNECTED -> {
                paintRing.color = colorConnectedCyan
                paintRing.alpha = (80 + 40 * glowPulse).toInt()
                paintRing.shader = null
                canvas.drawCircle(cx, cy, radius, paintRing)
            }
        }

        // 3. Second decorative ring (subtle)
        if (state != State.DISCONNECTED) {
            paintRing.apply {
                strokeWidth = 1f
                shader = null
                color = if (state == State.CONNECTED) colorConnectedCyan else colorConnectingPurple2
                alpha = (20 + 15 * glowPulse).toInt()
            }
            canvas.drawCircle(cx, cy, radius * 1.15f, paintRing)
        }

        // 4. Inner filled circle with gradient
        val innerR = radius * 0.88f
        val shader2 = when (state) {
            State.DISCONNECTED -> LinearGradient(
                cx, cy - innerR, cx, cy + innerR,
                colorDisconnectedInner, colorDisconnectedOuter,
                Shader.TileMode.CLAMP
            )
            State.CONNECTING -> LinearGradient(
                cx - innerR, cy - innerR, cx + innerR, cy + innerR,
                colorConnectingPurple1, colorConnectingPurple2,
                Shader.TileMode.CLAMP
            )
            State.CONNECTED -> LinearGradient(
                cx - innerR, cy + innerR, cx + innerR, cy - innerR,
                colorConnectedCyan, colorConnectedBlue,
                Shader.TileMode.CLAMP
            )
        }
        paintInner.apply {
            style = Paint.Style.FILL
            setShader(shader2)
        }
        canvas.drawCircle(cx, cy, innerR, paintInner)

        // 5. Inner highlight (glass reflection)
        val highlightR = innerR * 0.75f
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy - innerR * 0.3f, highlightR,
                intArrayOf(Color.argb(25, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy - innerR * 0.15f, highlightR, highlightPaint)

        // 6. Power icon
        drawPowerIcon(canvas, cx, cy, innerR * 0.38f)

        // 7. Small dots around ring when connected
        if (state == State.CONNECTED) {
            drawOrbitalDots(canvas, cx, cy, radius * 1.15f)
        }
    }

    private fun drawPowerIcon(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val iconColor = when (state) {
            State.DISCONNECTED -> colorIconDim
            State.CONNECTING -> Color.WHITE
            State.CONNECTED -> Color.WHITE
        }

        paintIcon.apply {
            color = iconColor
            strokeWidth = size * 0.13f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            shader = null
            alpha = 255
        }

        // Arc
        val arcRect = RectF(cx - size, cy - size, cx + size, cy + size)
        canvas.drawArc(arcRect, -60f, 300f, false, paintIcon)

        // Vertical line
        paintIcon.strokeWidth = size * 0.14f
        canvas.drawLine(cx, cy - size * 1.15f, cx, cy - size * 0.25f, paintIcon)
    }

    private fun drawOrbitalDots(canvas: Canvas, cx: Float, cy: Float, orbitR: Float) {
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorConnectedCyan
            alpha = (60 + 40 * glowPulse).toInt()
        }
        val dotCount = 8
        val dotRadius = 2f
        for (i in 0 until dotCount) {
            val angle = Math.toRadians((i * 360.0 / dotCount) + (glowPulse * 15))
            val dx = cx + orbitR * cos(angle).toFloat()
            val dy = cy + orbitR * sin(angle).toFloat()
            canvas.drawCircle(dx, dy, dotRadius, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> animatePress(0.92f)
            MotionEvent.ACTION_UP -> {
                animatePress(1f)
                if (event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                    onClickListener?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> animatePress(1f)
        }
        return true
    }

    private fun animatePress(target: Float) {
        ValueAnimator.ofFloat(pressScale, target).apply {
            duration = 120
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glowAnimator?.cancel()
        spinAnimator?.cancel()
    }
}
