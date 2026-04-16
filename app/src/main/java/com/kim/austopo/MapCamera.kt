package com.kim.austopo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/**
 * Shared camera model in Web Mercator coordinates (meters).
 * centerX/Y are in Web Mercator; zoom is screen pixels per meter.
 */
class MapCamera(context: Context, private val invalidate: () -> Unit) {

    // Viewport center in Web Mercator meters
    var centerX: Double = 0.0
    var centerY: Double = 0.0
    // Screen pixels per Web Mercator meter
    var zoom: Float = 1.0f

    // Bounds for clamping (Web Mercator meters)
    var minX: Double = -CoordinateConverter.HALF_CIRCUMFERENCE
    var minY: Double = -CoordinateConverter.HALF_CIRCUMFERENCE
    var maxX: Double = CoordinateConverter.HALF_CIRCUMFERENCE
    var maxY: Double = CoordinateConverter.HALF_CIRCUMFERENCE
    var clampEnabled: Boolean = true

    // View dimensions — updated by the hosting View
    var viewWidth: Int = 0
    var viewHeight: Int = 0

    private val scroller = OverScroller(context)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Fires once per touch sequence the first time the user's finger moves past
     * the platform touch slop, or starts a scale gesture. Used by
     * [TiledMapView]/[MapActivity] to auto-hide the overlay toolbar.
     */
    var onInteractionStart: (() -> Unit)? = null

    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var cumulativeScrollPx = 0f
    private var interactionFired = false

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                // Fire interaction-start once we pass touch slop so that
                // taps (which also produce tiny onScroll deltas) don't
                // flash the overlay toolbar.
                cumulativeScrollPx += abs(distanceX) + abs(distanceY)
                if (!interactionFired && cumulativeScrollPx > touchSlop) {
                    interactionFired = true
                    onInteractionStart?.invoke()
                }
                // In Mercator, Y increases upward, but screen Y increases downward
                centerX += distanceX / zoom
                centerY -= distanceY / zoom
                clamp()
                invalidate()
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                // Fling in screen coordinates; we scale to Mercator in the animation
                scroller.fling(
                    0, 0,
                    -velocityX.toInt(), velocityY.toInt(), // flip Y for Mercator
                    Int.MIN_VALUE, Int.MAX_VALUE,
                    Int.MIN_VALUE, Int.MAX_VALUE
                )
                flingStartX = centerX
                flingStartY = centerY
                flingPrevX = 0
                flingPrevY = 0
                postFlingAnimation()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val tapWorldX = screenToWorldX(e.x)
                val tapWorldY = screenToWorldY(e.y)
                zoom = (zoom * 2f).coerceAtMost(maxZoom())
                centerX = tapWorldX
                centerY = tapWorldY
                clamp()
                invalidate()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                cumulativeScrollPx = 0f
                interactionFired = false
                return true
            }
        })

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!interactionFired) {
                    interactionFired = true
                    onInteractionStart?.invoke()
                }
                val focusWorldX = screenToWorldX(detector.focusX)
                val focusWorldY = screenToWorldY(detector.focusY)

                zoom = (zoom * detector.scaleFactor).coerceIn(minZoom(), maxZoom())

                // Keep the focus point under the finger
                centerX = focusWorldX - (detector.focusX - viewWidth / 2f) / zoom
                centerY = focusWorldY + (detector.focusY - viewHeight / 2f) / zoom
                clamp()
                invalidate()
                return true
            }
        })

    // Fling tracking
    private var flingStartX = 0.0
    private var flingStartY = 0.0
    private var flingPrevX = 0
    private var flingPrevY = 0

    /** Convert world X to screen X. */
    fun worldToScreenX(wx: Double): Float =
        ((wx - centerX) * zoom + viewWidth / 2f).toFloat()

    /** Convert world Y to screen Y. Mercator Y-up → screen Y-down. */
    fun worldToScreenY(wy: Double): Float =
        (-(wy - centerY) * zoom + viewHeight / 2f).toFloat()

    fun screenToWorldX(sx: Float): Double =
        centerX + (sx - viewWidth / 2f) / zoom

    fun screenToWorldY(sy: Float): Double =
        centerY - (sy - viewHeight / 2f) / zoom

    /** Half the viewport width in world units (meters). */
    fun halfViewW(): Double = viewWidth / 2.0 / zoom
    /** Half the viewport height in world units (meters). */
    fun halfViewH(): Double = viewHeight / 2.0 / zoom

    /** Meters per screen pixel at current zoom. */
    fun metersPerPixel(): Double = 1.0 / zoom

    fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }

    /** Animate to a position + zoom. */
    fun setPosition(mx: Double, my: Double, newZoom: Float) {
        centerX = mx
        centerY = my
        zoom = newZoom.coerceIn(minZoom(), maxZoom())
        clamp()
        invalidate()
    }

    private fun minZoom(): Float {
        // At least 1 pixel per 100 km
        return 0.00001f
    }

    private fun maxZoom(): Float {
        // At most 100 pixels per meter
        return 100f
    }

    private fun clamp() {
        if (!clampEnabled) return
        centerX = centerX.coerceIn(minX, maxX)
        centerY = centerY.coerceIn(minY, maxY)
    }

    private fun postFlingAnimation() {
        if (scroller.computeScrollOffset()) {
            val dx = scroller.currX - flingPrevX
            val dy = scroller.currY - flingPrevY
            flingPrevX = scroller.currX
            flingPrevY = scroller.currY
            centerX += dx / zoom.toDouble()
            centerY += dy / zoom.toDouble()
            clamp()
            invalidate()
            handler.post { postFlingAnimation() }
        }
    }
}
