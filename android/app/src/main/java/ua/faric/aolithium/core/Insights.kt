package ua.faric.aolithium.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Струм, з якого рахуємо «вистачить на» / «до повної» (faric 19.10 03:02): не кожну секунду,
 * а середнє за [windowMs]; показане число міняється, лише коли середнє стабільно відійшло більше ніж на
 * [tolerance] (10 %) або змінився напрям (заряд ↔ розряд).
 */
class SteadyCurrent(private val windowMs: Long = 120_000L, private val tolerance: Double = 0.10) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    /** Струм, яким зараз рахуємо час; null — ще не було жодного виміру. */
    var shown: Double? = null
        private set

    fun add(t: Long, amps: Double): Double {
        samples.addLast(t to amps)
        while (samples.isNotEmpty() && samples.first().first < t - windowMs) samples.removeFirst()
        val avg = samples.sumOf { it.second } / samples.size
        val old = shown
        // Усе вікно рівне (розкид ≤ 10 %) — струм устоявся: підтягуємо число до нього, якщо відрізняється > 5 %.
        val steady = samples.maxOf { it.second } - samples.minOf { it.second } <= tolerance * abs(avg)
        shown = when {
            old == null -> avg
            sign(avg) != sign(old) -> avg
            abs(old) < MIN_A -> avg
            abs(avg - old) / abs(old) > tolerance -> avg
            steady && abs(avg - old) / abs(old) > SETTLE -> avg
            else -> old
        }
        return shown!!
    }

    fun reset() {
        samples.clear()
        shown = null
    }

    private fun sign(a: Double) = when {
        a > BatterySnapshot.IDLE_A -> 1
        a < -BatterySnapshot.IDLE_A -> -1
        else -> 0
    }

    private companion object {
        const val MIN_A = 0.2
        const val SETTLE = 0.05
    }
}

/** Скільки часу: при розряді — до 20 / 10 / 5 % і до кінця; при заряді — до повної і скільки дасть година. */
object TimeLeft {
    val THRESHOLDS = listOf(20, 10, 5)

    data class Discharge(
        /** До повного розряду (до 0 %), год. */
        val totalH: Double,
        /** Поріг % → години до нього; null — заряд уже нижче цього порогу. */
        val toThreshold: List<Pair<Int, Double?>>,
    )

    data class Charge(
        /** До повної, год (0 — уже повна). */
        val toFullH: Double,
        /** Скільки відсотків додасть 1 година такого заряду. */
        val percentPerHour: Double,
    )

    fun discharge(remainAh: Double, fullAh: Double, amps: Double): Discharge? {
        if (amps >= -BatterySnapshot.IDLE_A || fullAh <= 0 || remainAh < 0) return null
        val a = -amps
        return Discharge(
            totalH = remainAh / a,
            toThreshold = THRESHOLDS.map { p ->
                val ah = remainAh - fullAh * p / 100.0
                p to (if (ah > 0) ah / a else null)
            },
        )
    }

    fun charge(remainAh: Double, fullAh: Double, amps: Double): Charge? {
        if (amps <= BatterySnapshot.IDLE_A || fullAh <= 0) return null
        return Charge(
            toFullH = ((fullAh - remainAh).coerceAtLeast(0.0)) / amps,
            percentPerHour = amps / fullAh * 100.0,
        )
    }
}

/** Комірки: на скільки кожна від середньої і як змінилась за хвилину (з живих вимірів). */
object CellTrend {
    fun fromAverage(cellsMv: List<Int>): List<Int> {
        if (cellsMv.isEmpty()) return emptyList()
        val avg = cellsMv.average()
        return cellsMv.map { (it - avg).roundToInt() }
    }

    /**
     * Зміна кожної комірки за хвилину, мВ. [samples] — (час, комірки) від старих до нових.
     * Беремо вимір 40…120 с тому, найближчий до 60 с, і переводимо в «за хвилину». Немає такого — null.
     */
    fun perMinute(samples: List<Pair<Long, List<Int>>>): List<Int>? {
        val (now, cur) = samples.lastOrNull() ?: return null
        val old = samples.filter { now - it.first in 40_000L..120_000L && it.second.size == cur.size }
            .minByOrNull { abs(now - it.first - 60_000L) } ?: return null
        val dt = (now - old.first).toDouble()
        return cur.indices.map { i -> ((cur[i] - old.second[i]) * 60_000.0 / dt).roundToInt() }
    }

    /**
     * Плата міряє з точністю 1 мВ, тож ±1 мВ за хвилину — шум, а не зміна: показуємо «без змін».
     * (Відео faric 19.10 13:57: «за 1 хв» скакало «▼ −1 мВ» ↔ «без змін» кожні 2 с.)
     */
    const val NOISE_MV = 1

    fun shown(perMin: Int): Int = if (abs(perMin) <= NOISE_MV) 0 else perMin

    /**
     * Найвища й найнижча комірка (T19, виправлено T24).
     *
     * Мітка завжди на **справжній** найвищій / найнижчій. **Рівні — однаково** (T33, відео faric 20.10 06:21):
     * при 3,326 / 3,329 / 3,328 / 3,329 «найвища» стояла лише на №2, а №4 з тим самим числом — без рамки, і це
     * виглядало як помилка. Раніше (T19, T24) мітка між рівними була «липкою» — трималась на одній, щоб не стрибала
     * №3 ↔ №4 (відео 19.10 13:57). Тепер рамку мають **усі** рівні: стрибка нема, бо нема вибору між ними, а мітка
     * змінюється лише разом із числом на плитці.
     * Помилка v0.2.6 (скрін faric 19.10 22:06): мітка трималась, доки інша не стане нижчою на 2 мВ, — і при
     * 3,332 / 3,331 / 3,330 / 3,329 «найнижча» стояла на №3, хоча найнижча №4. Правило «±1 мВ — шум» тут не годиться:
     * число на плитці видно, і мітка мусить з ним збігатись.
     * Різниця всього 1 мВ або 0 — це точність плати, «найвищої» й «найнижчої» нема: міток нема.
     * Повертає (найвищі, найнижчі) — множини індексів (порожні — міток нема).
     */
    const val NO_MARKS_MV = 1

    fun marks(cellsMv: List<Int>): Pair<Set<Int>, Set<Int>> {
        if (cellsMv.size < 2) return emptySet<Int>() to emptySet()
        val max = cellsMv.max()
        val min = cellsMv.min()
        if (max - min <= NO_MARKS_MV) return emptySet<Int>() to emptySet()
        return cellsMv.indices.filter { cellsMv[it] == max }.toSet() to cellsMv.indices.filter { cellsMv[it] == min }.toSet()
    }
}

/**
 * Відключення світла з історії (faric 19.10: «щоб він писав, коли світло є, коли нема»).
 * Батарея віддає помітний струм кілька хвилин — світла нема; струм знову ≥ ~0 (заряд або стоїть) кілька хвилин —
 * світло повернулось. faric 03:31: при світлі інвертор лише заряджає, або батарея стоїть повна.
 */
object Outages {
    /** Віддає більше — це вже «світла нема» (поріг за замовчуванням; кожна батарея може мати свій — T85). */
    const val START_A = 0.5

    /**
     * T85 (faric 25.10 20:01, мамин сторож: «бот бреше — світла нема, а світло було»): 19:16–19:48 повна батарея (100 %)
     * віддавала 1,1–1,3 А (~16 Вт) — інвертор Logic Power після заряду живить себе від батареї, поки напруга не впаде;
     * справжні відключення там — 2,4–5 А. Тому: (А) при заряді від [FULL_SOC] % поріг щонайменше [FULL_MIN_A];
     * (Б) свій поріг для кожної батареї — [CHOICES], вибір у «Сповіщеннях» (`data/AlertSettings.outageA`).
     */
    const val FULL_SOC = 98
    const val FULL_MIN_A = 2.0
    val CHOICES = listOf(0.5, 1.0, 2.0, 3.0)

    /** Поріг «світла нема» для цього знімка: свій поріг батареї [userA], а при повній — не менше [FULL_MIN_A]. */
    fun startA(userA: Double, soc: Int): Double = if (soc >= FULL_SOC) maxOf(userA, FULL_MIN_A) else userA

    /** Батарея віддає стільки, що це схоже на відключення світла (а не на інвертор, що живиться від повної батареї). */
    fun isOutageDraw(current: Double, soc: Int, userA: Double = START_A): Boolean = -current >= startA(userA, soc)
    /** Струм вище цього (майже нуль або заряд) — розряд скінчився. */
    const val END_A = -0.2
    /** Стільки має тривати розряд (і повернення), щоб рахувалось — короткі стрибки не відключення. */
    const val MIN_MS = 3 * 60_000L
    /** Довша перерва в записі — не знаємо, що було: період закінчуємо на останній точці. */
    const val MAX_GAP_MS = 20 * 60_000L

    data class Outage(
        val start: Long,
        val end: Long,
        /** Триває досі (остання точка історії — ще розряд). */
        val ongoing: Boolean,
        val socFrom: Int,
        val socTo: Int,
        val ah: Double,
        val wh: Double,
        val peakW: Double,
    ) {
        val durationMs: Long get() = end - start
        val avgW: Double get() = if (durationMs > 0) wh / (durationMs / 3_600_000.0) else 0.0
    }

    fun find(points: List<HistoryPoint>, userA: Double = START_A): List<Outage> {
        val pts = points.sortedBy { it.t }
        val out = ArrayList<Outage>()
        var i = 0
        while (i < pts.size) {
            if (!isOutageDraw(pts[i].current, pts[i].soc, userA)) {
                i++
                continue
            }
            val startIdx = i
            var lastDis = i
            var j = i + 1
            while (j < pts.size) {
                val p = pts[j]
                if (p.t - pts[j - 1].t > MAX_GAP_MS) break
                if (p.current <= END_A) {
                    lastDis = j
                } else if (p.t - pts[lastDis].t >= MIN_MS) {
                    break
                }
                j++
            }
            val seg = pts.subList(startIdx, lastDis + 1)
            val start = seg.first().t
            val end = seg.last().t
            if (end - start >= MIN_MS) {
                var ah = 0.0
                var wh = 0.0
                for (k in 1 until seg.size) {
                    val dt = seg[k].t - seg[k - 1].t
                    if (dt <= 0 || dt > MAX_GAP_MS) continue
                    val h = dt / 3_600_000.0
                    ah += -(seg[k].current + seg[k - 1].current) / 2 * h
                    wh += -(seg[k].power + seg[k - 1].power) / 2 * h
                }
                out += Outage(
                    start = start,
                    end = end,
                    ongoing = lastDis == pts.lastIndex,
                    socFrom = seg.first().soc,
                    socTo = seg.last().soc,
                    ah = ah.coerceAtLeast(0.0),
                    wh = wh.coerceAtLeast(0.0),
                    peakW = seg.maxOf { -it.power }.coerceAtLeast(0.0),
                )
            }
            i = maxOf(lastDis + 1, startIdx + 1)
        }
        return out
    }
}

/** Найнижчий заряд за кожен день — видно «хвилі» (щодня нижче → генератор → вгору). */
object DailyMin {
    data class Day(val dayStart: Long, val minSoc: Int)

    /** [offsetMs] — зсув місцевого часу від UTC (Київ: +2 або +3 год). */
    fun of(points: List<HistoryPoint>, offsetMs: Long): List<Day> =
        points.groupBy { Math.floorDiv(it.t + offsetMs, DAY_MS) }
            .map { (d, pts) -> Day(d * DAY_MS - offsetMs, pts.minOf { it.soc }) }
            .sortedBy { it.dayStart }

    const val DAY_MS = 86_400_000L
}

/**
 * Хто відстає наприкінці заряду: у кожному «кінці заряду» (заряджається і ≥ 95 % або комірка ≥ 3,45 В)
 * беремо мить з найвищою коміркою і дивимось, яка найнижча. faric-ові скріни Logic Power: №3 — 6 разів із 7.
 */
object Laggard {
    data class Result(
        /** Скільки разів кожна комірка була найнижчою (індекс — номер комірки − 1). */
        val lowestCount: List<Int>,
        val episodes: Int,
        /** Різниця комірок у кожному кінці заряду, мВ (від старих до нових). */
        val deltas: List<Int>,
    ) {
        val leader: Int? get() = lowestCount.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
    }

    const val TOP_MV = 3450
    const val TOP_SOC = 95
    private const val SPLIT_MS = 30 * 60_000L

    fun find(points: List<HistoryPoint>): Result? {
        val top = points.sortedBy { it.t }.filter {
            it.cellsMv.size >= 2 && it.current > 0.3 && (it.soc >= TOP_SOC || it.cellsMv.max() >= TOP_MV)
        }
        if (top.isEmpty()) return null
        val n = top.last().cellsMv.size
        val counts = IntArray(n)
        val deltas = ArrayList<Int>()
        var episodes = 0
        var group = ArrayList<HistoryPoint>()
        fun close() {
            val peak = group.filter { it.cellsMv.size == n }.maxByOrNull { it.cellsMv.max() } ?: return
            counts[peak.cellsMv.withIndex().minBy { it.value }.index]++
            deltas += peak.cellsMv.max() - peak.cellsMv.min()
            episodes++
        }
        for (p in top) {
            if (group.isNotEmpty() && p.t - group.last().t > SPLIT_MS) {
                close()
                group = ArrayList()
            }
            group += p
        }
        close()
        return if (episodes == 0) null else Result(counts.toList(), episodes, deltas)
    }
}

/** Зарядний: до скількох вольт доводить батарею (найвища напруга під час заряду). Орієнтир для 4 комірок LiFePO4. */
object ChargerCheck {
    data class Result(val maxV: Double, val maxCellMv: Int?, val verdict: Verdict)

    enum class Verdict {
        LOW, OK, HIGH;

        /** T12: getter, не аргумент enum — щоб після перемикання мови було новою мовою. */
        val title: String
            get() = when (this) {
                LOW -> tr("не доходить до повної", "does not reach full")
                OK -> tr("в нормі", "normal")
                HIGH -> tr("забагато", "too high")
            }
    }

    /** Нижче — зарядний, мабуть, не доводить до кінця (плата рідко уточнює відсоток). */
    const val LOW_V = 13.8
    /** Вище — для 4 комірок LiFePO4 забагато (3,65 В на комірку). */
    const val HIGH_V = 14.6

    fun of(points: List<HistoryPoint>): Result? {
        val charging = points.filter { it.current > 0.3 }
        val top = charging.maxByOrNull { it.voltage } ?: return null
        val v = top.voltage
        val verdict = when {
            v > HIGH_V -> Verdict.HIGH
            v < LOW_V -> Verdict.LOW
            else -> Verdict.OK
        }
        return Result(v, charging.mapNotNull { it.cellsMv.maxOrNull() }.maxOrNull(), verdict)
    }
}
