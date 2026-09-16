package com.crylo.slimpdf

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/**
 * A list row that can be flung aside to dismiss it.
 *
 * Child 0 is the backdrop revealed by the drag; child 1 is the row itself and is the view
 * that moves. The row consumes its own touches rather than leaning on ListView's item
 * click, because a child that consumes MOVE events never gets them back from AbsListView.
 * Vertical drags are left alone until the gesture is clearly horizontal, so the list still
 * scrolls normally.
 */
class SwipeRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        /** Fraction of the row width past which release dismisses instead of springing back. */
        private const val DISMISS_AT = 0.35f
        private const val SETTLE_MS = 160L
    }

    var onDismiss: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    private val backdrop: View get() = getChildAt(0)
    private val row: View get() = getChildAt(1)

    init {
        isClickable = true
    }

    /** Called by the adapter on rebind, since ListView recycles views mid-animation. */
    fun reset() {
        dragging = false
        row.animate().cancel()
        row.translationX = 0f
        row.alpha = 1f
        row.isPressed = false
        backdrop.alpha = 0f
    }

    // A tap that is not a drag is dispatched through performClick() below, which is what
    // the accessibility contract asks for; there is nothing extra to do in an override.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                row.drawableHotspotChanged(event.x, event.y)
                row.isPressed = true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    row.isPressed = false
                    // Only now is it safe to lock the list out of the gesture.
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    row.translationX = dx
                    (backdrop as? TextView)?.gravity =
                        Gravity.CENTER_VERTICAL or (if (dx < 0) Gravity.END else Gravity.START)
                    backdrop.alpha = min(1f, abs(dx) / (width * DISMISS_AT))
                }
            }

            MotionEvent.ACTION_UP -> {
                row.isPressed = false
                if (dragging) {
                    settle(row.translationX)
                } else if (abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
                    performClick()
                }
                dragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                row.isPressed = false
                if (dragging) settle(0f)
                dragging = false
            }
        }
        return true
    }

    private fun settle(dx: Float) {
        if (abs(dx) > width * DISMISS_AT) {
            val target = if (dx < 0) -width.toFloat() else width.toFloat()
            row.animate()
                .translationX(target)
                .alpha(0f)
                .setDuration(SETTLE_MS)
                .withEndAction { onDismiss?.invoke() }
                .start()
        } else {
            row.animate().translationX(0f).setDuration(SETTLE_MS).start()
            backdrop.animate().alpha(0f).setDuration(SETTLE_MS).start()
        }
    }
}
