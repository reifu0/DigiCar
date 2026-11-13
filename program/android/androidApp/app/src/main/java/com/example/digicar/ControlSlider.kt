package com.example.digicar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Analog slider with its zero in the middle that springs back to zero on
 * release. A touch must start near the thumb, so a stray tap can't jump the
 * car to full speed. Value is -1..1: bottom or left is -1.
 */
class ControlSlider(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var onValueChanged: ((Float) -> Unit)? = null

    private val vertical: Boolean
    private val density = resources.displayMetrics.density
    private val thumbRadius = 30 * density
    private val trackThickness = 14 * density
    private val grabDistance = thumbRadius * 1.5f

    private val trackPaint = fillPaint(R.color.slider_track)
    private val markPaint = fillPaint(R.color.slider_mark)
    private val accentPaint = fillPaint(R.color.slider_accent)

    private var value = 0f
    private var activePointerId = NO_POINTER
    private var grabOffset = 0f

    init {
        val styled = context.obtainStyledAttributes(attrs, R.styleable.ControlSlider)
        vertical = styled.getBoolean(R.styleable.ControlSlider_vertical, false)
        styled.recycle()
    }

    private fun fillPaint(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(colorRes)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) release()
        alpha = if (enabled) 1f else 0.35f
    }

    override fun onDraw(canvas: Canvas) {
        val cross = (if (vertical) width else height) / 2f
        val center = axisLength() / 2f
        val thumb = positionOf(value)

        drawBar(canvas, cross, thumbRadius, axisLength() - thumbRadius, trackThickness, trackPaint)
        drawBar(canvas, cross, center - density, center + density, thumbRadius * 1.6f, markPaint)
        drawBar(canvas, cross, minOf(center, thumb), maxOf(center, thumb), trackThickness, accentPaint)

        val thumbX = if (vertical) cross else thumb
        val thumbY = if (vertical) thumb else cross
        canvas.drawCircle(thumbX, thumbY, thumbRadius, accentPaint)
    }

    private fun drawBar(canvas: Canvas, cross: Float, start: Float, end: Float, thickness: Float, paint: Paint) {
        val half = thickness / 2
        if (vertical) {
            canvas.drawRect(cross - half, start, cross + half, end, paint)
        } else {
            canvas.drawRect(start, cross - half, end, cross + half, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touch = axisPosition(event, 0)
                val thumb = positionOf(value)
                if (abs(touch - thumb) > grabDistance) return false
                activePointerId = event.getPointerId(0)
                grabOffset = thumb - touch
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0) setValue(valueAt(axisPosition(event, index) + grabOffset))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) release()
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                release()
            }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun release() {
        activePointerId = NO_POINTER
        setValue(0f)
    }

    private fun setValue(newValue: Float) {
        if (newValue == value) return
        value = newValue
        invalidate()
        onValueChanged?.invoke(newValue)
    }

    private fun axisLength() = (if (vertical) height else width).toFloat()

    private fun axisPosition(event: MotionEvent, index: Int) =
        if (vertical) event.getY(index) else event.getX(index)

    private fun travel() = (axisLength() - 2 * thumbRadius) / 2f

    private fun positionOf(sliderValue: Float): Float {
        val offset = sliderValue * travel()
        return axisLength() / 2f + if (vertical) -offset else offset
    }

    private fun valueAt(position: Float): Float {
        val travel = travel()
        if (travel <= 0f) return 0f
        val offset = (position - axisLength() / 2f) / travel
        return (if (vertical) -offset else offset).coerceIn(-1f, 1f)
    }

    private companion object {
        const val NO_POINTER = -1
    }
}
