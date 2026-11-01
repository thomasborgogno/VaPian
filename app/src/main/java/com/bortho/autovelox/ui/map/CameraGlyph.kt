package com.bortho.autovelox.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import kotlin.math.max
import kotlin.math.roundToInt

/** Marker palette (same as the Flutter app, features.md 2.1). ARGB ints. */
object CameraColors {
    /** Official OpenStreetMap camera. */
    const val OSM = 0xFF1565C0.toInt()

    /** Approved user report. */
    const val USER = 0xFFCF0303.toInt()

    /** Pending user report, and any camera being placed/edited. */
    const val DRAFT = 0xFFEF7F00.toInt()

    /** Hidden-from-normal-view cameras (the user's personal blacklist). */
    const val GRAY = 0xFF9E9E9E.toInt()

    /** Handle used to rotate a camera's direction in the draft overlay. */
    const val HANDLE = 0xFF673AB7.toInt()

    fun of(camera: SpeedCamera, ignored: Boolean = false): Int = when {
        ignored -> GRAY
        camera.source == CameraSource.OPENSTREETMAP -> OSM
        camera.status == CameraStatus.APPROVED -> USER
        else -> DRAFT
    }
}

/**
 * The speed-camera marker graphic, a port of the Flutter `SpeedCameraMarkerIcons`: a coloured circular badge
 * with the camera pictogram, and — when a direction is known — an arrow towards the direction of travel the
 * camera monitors.
 *
 * The badge and the arrow are drawn as **two independent pieces** on purpose: on a real map the badge must
 * stay upright on screen whatever the map's bearing or the camera's direction, while the arrow must turn with
 * the true bearing. One bitmap with one marker rotation cannot do both, so the map places two markers at the
 * same position ([iconBitmap] never rotated, [arrowBitmap] flat and rotated). The draft overlay paints
 * both on one canvas instead ([paintCombined]).
 *
 * All sizes are in dp; [scale] converts to the pixels of the canvas being painted (the screen density).
 */
object CameraGlyph {
    const val CIRCLE_RADIUS = 11f
    const val ARROW_LENGTH = 30f // circle centre to shaft tip
    const val ARROW_HEAD_WIDTH = 11f
    const val ARROW_HEAD_LENGTH = 11f
    private const val ARROW_HEAD_APEX = ARROW_HEAD_LENGTH * 0.6f // the head pokes past the shaft tip

    // white ring (same as the badge's) plus a very thin grey hairline just outside it, on both pieces
    private const val WHITE_BORDER = CIRCLE_RADIUS * 0.16f
    private const val GRAY_BORDER = 0.6f
    private const val BORDER_MARGIN = WHITE_BORDER + GRAY_BORDER
    private const val GRAY_HAIRLINE = 0xFF9E9E9E.toInt()
    private const val SHAFT_CORE_WIDTH = 4.5f

    /** Standalone badge bitmap: a square centred on the camera. */
    const val ICON_SIZE = (CIRCLE_RADIUS + BORDER_MARGIN) * 2

    /** Standalone arrow bitmap, anchored bottom-centre exactly on the camera, so it grows outward from the badge. */
    const val ARROW_WIDTH = ARROW_HEAD_WIDTH + BORDER_MARGIN * 2
    const val ARROW_HEIGHT = CIRCLE_RADIUS + ARROW_LENGTH + ARROW_HEAD_APEX + BORDER_MARGIN

    /** Bounding box of both pieces together (draft overlay): widest = badge, tallest = badge centre to arrow tip. */
    const val WIDTH = ICON_SIZE
    const val HEIGHT = (CIRCLE_RADIUS + BORDER_MARGIN) + (ARROW_LENGTH + ARROW_HEAD_APEX + BORDER_MARGIN)
    const val ANCHOR_Y = (ARROW_LENGTH + ARROW_HEAD_APEX + BORDER_MARGIN) / HEIGHT

    const val DOT_RADIUS = 6f

    // pictogram: the SVG of the Flutter app (viewBox 144x144), white on the coloured badge
    private const val SVG_SIZE = 144f
    private const val BASE_LINE = "M56.71,128.729l31.331,0"
    private const val ARCH = "M11.294,60.238l0,-26.606c0,-8.404 6.813,-15.217 15.217,-15.217l91.728,-0c8.404,0 15.217,6.813 15.217,15.217l-0,26.606"
    private const val BODY =
        "M109.419,32.684c2.849,0 5.582,1.132 7.596,3.147c2.015,2.015 3.147,4.747 3.147,7.596l0,60.218c0,2.849 -1.132,5.582 -3.147,7.596c-2.015,2.015 -4.747,3.147 -7.596,3.147l-74.087,-0c-2.849,-0 -5.582,-1.132 -7.596,-3.147c-2.015,-2.015 -3.147,-4.747 -3.147,-7.596l-0,-60.218c-0,-2.849 1.132,-5.582 3.147,-7.596c2.015,-2.015 4.747,-3.147 7.596,-3.147l74.087,0Z" +
            "m-37.044,20.767c-11.085,0 -20.085,9 -20.085,20.085c0,11.085 9,20.085 20.085,20.085c11.085,0 20.085,-9 20.085,-20.085c0,-11.085 -9,-20.085 -20.085,-20.085Z"

    private val baseLine: Path by lazy { requireNotNull(PathParser.createPathFromPathData(BASE_LINE)) }
    private val arch: Path by lazy { requireNotNull(PathParser.createPathFromPathData(ARCH)) }
    private val body: Path by lazy {
        requireNotNull(PathParser.createPathFromPathData(BODY)).apply { fillType = Path.FillType.EVEN_ODD } // the lens is a hole
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** The badge (ring, disc and pictogram) centred at ([cx], [cy]); never rotated. */
    fun paintIcon(canvas: Canvas, cx: Float, cy: Float, color: Int, scale: Float) {
        canvas.drawCircle(cx, cy, (CIRCLE_RADIUS + BORDER_MARGIN) * scale, fill(GRAY_HAIRLINE))
        canvas.drawCircle(cx, cy, (CIRCLE_RADIUS + WHITE_BORDER) * scale, fill(0xFFFFFFFF.toInt()))
        canvas.drawCircle(cx, cy, CIRCLE_RADIUS * scale, fill(color))

        // the pictogram is inset so the coloured ring stays visible all around it
        val glyphDiameter = CIRCLE_RADIUS * 2 * 0.62f * scale
        val k = glyphDiameter / SVG_SIZE
        canvas.save()
        canvas.translate(cx - SVG_SIZE * k / 2, cy - SVG_SIZE * k / 2)
        canvas.scale(k, k)
        val white = 0xFFFFFFFF.toInt()
        canvas.drawPath(baseLine, stroke(white, 14f))
        canvas.drawPath(arch, stroke(white, 14f))
        canvas.drawPath(body, fill(white))
        canvas.restore()
    }

    /**
     * Only the arrow, rotated [rotationDeg] around the badge centre ([ox], [oy]); 0 points straight up, so a
     * compass bearing (corrected for the map's rotation) points it at that bearing.
     */
    fun paintArrow(canvas: Canvas, ox: Float, oy: Float, color: Int, rotationDeg: Float, scale: Float) {
        canvas.save()
        canvas.rotate(rotationDeg, ox, oy)

        val tipY = oy - ARROW_LENGTH * scale
        val shaftStartY = oy - CIRCLE_RADIUS * scale
        borderedLine(canvas, ox, shaftStartY, ox, tipY, color, scale)

        val head = Path().apply {
            moveTo(ox, tipY - ARROW_HEAD_LENGTH * 0.6f * scale)
            lineTo(ox - ARROW_HEAD_WIDTH / 2 * scale, tipY + ARROW_HEAD_LENGTH * 0.4f * scale)
            lineTo(ox + ARROW_HEAD_WIDTH / 2 * scale, tipY + ARROW_HEAD_LENGTH * 0.4f * scale)
            close()
        }
        borderedPath(canvas, head, color, scale)
        canvas.restore()
    }

    /** A stroked line with the badge's white-ring + grey-hairline border: widest to narrowest. */
    private fun borderedLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, scale: Float) {
        canvas.drawLine(x1, y1, x2, y2, stroke(GRAY_HAIRLINE, (SHAFT_CORE_WIDTH + 2 * BORDER_MARGIN) * scale))
        canvas.drawLine(x1, y1, x2, y2, stroke(0xFFFFFFFF.toInt(), (SHAFT_CORE_WIDTH + 2 * WHITE_BORDER) * scale))
        canvas.drawLine(x1, y1, x2, y2, stroke(color, SHAFT_CORE_WIDTH * scale))
    }

    /** A filled path with the same border, by stroking its own outline wider than the fill: the outer half shows. */
    private fun borderedPath(canvas: Canvas, path: Path, color: Int, scale: Float) {
        canvas.drawPath(path, stroke(GRAY_HAIRLINE, 2 * BORDER_MARGIN * scale))
        canvas.drawPath(path, stroke(0xFFFFFFFF.toInt(), 2 * WHITE_BORDER * scale))
        canvas.drawPath(path, fill(color))
    }

    /** Badge + arrow on one canvas, for the live draft overlay. [rotationDeg] only ever turns the arrow. */
    fun paintCombined(canvas: Canvas, cx: Float, cy: Float, color: Int, rotationDeg: Float, hasDirection: Boolean, scale: Float) {
        if (hasDirection) paintArrow(canvas, cx, cy, color, rotationDeg, scale)
        paintIcon(canvas, cx, cy, color, scale)
    }

    // ---- bitmaps for map markers (pixel size = dp * density) -------------------------------------------------

    private data class Key(val kind: Int, val color: Int, val density: Int)

    private val cache = HashMap<Key, Bitmap>()

    @Synchronized
    private fun cached(kind: Int, color: Int, density: Float, build: () -> Bitmap): Bitmap =
        cache.getOrPut(Key(kind, color, (density * 100).roundToInt()), build)

    fun iconBitmap(color: Int, density: Float): Bitmap = cached(0, color, density) {
        val px = max(1, (ICON_SIZE * density).roundToInt())
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
            paintIcon(Canvas(it), px / 2f, px / 2f, color, density)
        }
    }

    fun arrowBitmap(color: Int, density: Float): Bitmap = cached(1, color, density) {
        val w = max(1, (ARROW_WIDTH * density).roundToInt())
        val h = max(1, (ARROW_HEIGHT * density).roundToInt())
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            // the camera sits at the bottom-centre of this bitmap, the arrow grows upward from there
            paintArrow(Canvas(it), w / 2f, h.toFloat(), color, 0f, density)
        }
    }

    /** The small dot shown instead of the full glyph when zoomed far out (it would be unreadable anyway). */
    fun dotBitmap(color: Int, density: Float): Bitmap = cached(2, color, density) {
        val px = max(1, (DOT_RADIUS * 2 * density).roundToInt())
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            c.drawCircle(px / 2f, px / 2f, DOT_RADIUS * density, fill(color))
            c.drawCircle(px / 2f, px / 2f, DOT_RADIUS * density, stroke(0xFFFFFFFF.toInt(), 1.5f * density))
        }
    }
}
