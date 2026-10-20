package ua.faric.aolithium.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import ua.faric.aolithium.core.Fmt
import ua.faric.aolithium.core.tr

/**
 * Комірки стовпчиками: «1 ▬▬▬▬▬▬▬ 3,329 В». Шкала — від [LOW_MV] до [HIGH_MV] (межі LiFePO4,
 * як у параметрах Aolithium: переразряд 2,5 В, перезаряд 3,65 В).
 * Найнижча — жовтим, найвища — акцентом, ті, що балансуються, — зеленим (пояснення — текстом під ними).
 */
class CellBarsView(context: Context) : View(context) {
    private var cells: List<Int> = emptyList()
    private var balancing: Set<Int> = emptySet()
    private var minIdx = -1
    private var maxIdx = -1

    private val dp = resources.displayMetrics.density
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted
        textSize = sp(14f)
        typeface = Ui.MEDIUM
        fontFeatureSettings = "tnum"
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.text
        textSize = sp(15f)
        typeface = Ui.MEDIUM
        textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "tnum"
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.track }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val rowH: Float get() = maxOf(34 * dp, value.textSize * 2.1f)

    fun set(cellsMv: List<Int>, balancingCells: Collection<Int>, min: Int?, max: Int?) {
        cells = cellsMv
        balancing = balancingCells.toSet()
        minIdx = if (cellsMv.size > 1) min ?: -1 else -1
        maxIdx = if (cellsMv.size > 1) max ?: -1 else -1
        contentDescription = cellsMv.mapIndexed { i, mv ->
            tr("комірка ${i + 1}: ${Fmt.cellVolts(mv)}", "cell ${i + 1}: ${Fmt.cellVolts(mv)}")
        }.joinToString("; ")
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (rowH * cells.size).toInt() + paddingTop + paddingBottom)
    }

    override fun onDraw(canvas: Canvas) {
        val labelW = label.measureText("32") + 10 * dp
        val valueW = value.measureText(Fmt.cellVolts(3333)) + 12 * dp // найширше число — для місця справа
        val left = paddingLeft + labelW
        val right = width - paddingRight - valueW
        val barH = 10 * dp
        cells.forEachIndexed { i, mv ->
            val top = paddingTop + i * rowH
            val mid = top + rowH / 2f
            canvas.drawText("${i + 1}", paddingLeft.toFloat(), mid + label.textSize * 0.35f, label)
            rect.set(left, mid - barH / 2, right, mid + barH / 2)
            canvas.drawRoundRect(rect, barH / 2, barH / 2, track)
            val k = ((mv - LOW_MV).toFloat() / (HIGH_MV - LOW_MV)).coerceIn(0.02f, 1f)
            fill.color = when {
                (i + 1) in balancing -> Palette.violet
                i == minIdx -> Palette.warning
                i == maxIdx -> Palette.accent
                else -> Palette.muted
            }
            rect.set(left, mid - barH / 2, left + (right - left) * k, mid + barH / 2)
            canvas.drawRoundRect(rect, barH / 2, barH / 2, fill)
            canvas.drawText(Fmt.cellVolts(mv), (width - paddingRight).toFloat(), mid + value.textSize * 0.35f, value)
        }
    }

    companion object {
        const val LOW_MV = 2500
        const val HIGH_MV = 3650
    }
}
