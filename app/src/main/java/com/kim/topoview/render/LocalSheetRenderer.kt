package com.kim.topoview.render

import android.graphics.*
import com.kim.topoview.CoordinateConverter
import com.kim.topoview.MapCamera
import com.kim.topoview.MapMetadata
import java.io.File

class LocalSheetRenderer {

    private var decoder: BitmapRegionDecoder? = null
    private var metadata: MapMetadata? = null
    // Bounding box in Web Mercator: minMX, minMY, maxMX, maxMY
    private var bbox: DoubleArray? = null

    private var cachedBitmap: Bitmap? = null
    private var cachedRect: Rect? = null
    private var cachedSampleSize: Int = 1

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setMap(file: File, meta: MapMetadata) {
        decoder?.recycle()
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedRect = null
        decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
        metadata = meta
        bbox = CoordinateConverter.metadataBboxMercator(meta)
    }

    fun hasMap(): Boolean = decoder != null

    fun getMetadata(): MapMetadata? = metadata

    fun getBboxMercator(): DoubleArray? = bbox

    fun draw(canvas: Canvas, camera: MapCamera) {
        val dec = decoder ?: return
        val meta = metadata ?: return

        val viewWidth = camera.viewWidth
        val viewHeight = camera.viewHeight
        if (viewWidth == 0 || viewHeight == 0) return

        // Compute visible region in Web Mercator
        val halfW = camera.halfViewW()
        val halfH = camera.halfViewH()
        val visMinMX = camera.centerX - halfW
        val visMaxMX = camera.centerX + halfW
        val visMinMY = camera.centerY - halfH
        val visMaxMY = camera.centerY + halfH

        // Convert visible Mercator region to image pixel coords
        val (pxLeftD, pyTopD) = CoordinateConverter.webMercatorToPixel(visMinMX, visMaxMY, meta)
        val (pxRightD, pyBottomD) = CoordinateConverter.webMercatorToPixel(visMaxMX, visMinMY, meta)

        // Clamp to image bounds (note: pixel Y may be swapped due to negative pixelSizeY)
        val pxLeft = minOf(pxLeftD, pxRightD)
        val pxRight = maxOf(pxLeftD, pxRightD)
        val pyTop = minOf(pyTopD, pyBottomD)
        val pyBottom = maxOf(pyTopD, pyBottomD)

        val imgLeft = pxLeft.toInt().coerceAtLeast(0)
        val imgTop = pyTop.toInt().coerceAtLeast(0)
        val imgRight = pxRight.toInt().coerceAtMost(meta.width)
        val imgBottom = pyBottom.toInt().coerceAtMost(meta.height)

        if (imgRight <= imgLeft || imgBottom <= imgTop) return

        // Sample size for efficient decoding
        val regionW = imgRight - imgLeft
        val regionH = imgBottom - imgTop
        val sampleSize = computeSampleSize(
            (regionW.toFloat() / viewWidth).coerceAtLeast(regionH.toFloat() / viewHeight)
        )

        // Buffer zone (1.5x visible area)
        val bufW = ((pxRight - pxLeft) * 0.25).toInt()
        val bufH = ((pyBottom - pyTop) * 0.25).toInt()
        val decLeft = (imgLeft - bufW).coerceAtLeast(0)
        val decTop = (imgTop - bufH).coerceAtLeast(0)
        val decRight = (imgRight + bufW).coerceAtMost(meta.width)
        val decBottom = (imgBottom + bufH).coerceAtMost(meta.height)

        val decRect = Rect(decLeft, decTop, decRight, decBottom)

        // Re-decode if cache miss
        val cached = cachedRect
        if (cachedBitmap == null || cached == null || cachedSampleSize != sampleSize
            || !cached.contains(Rect(imgLeft, imgTop, imgRight, imgBottom))
        ) {
            cachedBitmap?.recycle()
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            cachedBitmap = try {
                dec.decodeRegion(decRect, opts)
            } catch (e: Exception) {
                null
            }
            cachedRect = decRect
            cachedSampleSize = sampleSize
        }

        val bmp = cachedBitmap ?: return
        val cr = cachedRect ?: return

        // Convert decoded image-pixel rect corners back to Mercator for screen positioning
        val (crMinMX, crMaxMY) = CoordinateConverter.pixelToWebMercator(
            cr.left.toDouble(), cr.top.toDouble(), meta
        )
        val (crMaxMX, crMinMY) = CoordinateConverter.pixelToWebMercator(
            cr.right.toDouble(), cr.bottom.toDouble(), meta
        )

        val srcRect = Rect(0, 0, bmp.width, bmp.height)
        val dstLeft = camera.worldToScreenX(crMinMX)
        val dstTop = camera.worldToScreenY(crMaxMY)
        val dstRight = camera.worldToScreenX(crMaxMX)
        val dstBottom = camera.worldToScreenY(crMinMY)
        val dstRect = RectF(dstLeft, dstTop, dstRight, dstBottom)

        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }

    private fun computeSampleSize(ratio: Float): Int {
        var s = 1
        while (s * 2 <= ratio) s *= 2
        return s.coerceIn(1, 32)
    }

    fun recycle() {
        decoder?.recycle()
        cachedBitmap?.recycle()
    }
}
