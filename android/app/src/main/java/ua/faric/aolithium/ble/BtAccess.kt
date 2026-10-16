package ua.faric.aolithium.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings

/**
 * Що потрібно, щоб шукати й читати батарею по Bluetooth. Різне для різних Android:
 *
 * - Android 12 і новіші: дозволи «Пристрої поблизу» (`BLUETOOTH_SCAN` з позначкою «не для місцезнаходження»
 *   і `BLUETOOTH_CONNECT`). Геолокація не потрібна.
 * - Android 8–11 (Huawei Mate 10 Lite, Redmi 9T…): пошук Bluetooth LE Android видає лише з дозволом
 *   на **місцезнаходження** і з **увімкненою геолокацією** — інакше пошук мовчки нічого не знаходить.
 *   Місцезнаходження застосунок нікуди не передає: інтернету в нього немає взагалі.
 */
object BtAccess {
    val modernBluetooth: Boolean get() = Build.VERSION.SDK_INT >= 31

    /** Дозволи, які Android має спитати в людини. */
    fun runtimePermissions(): Array<String> =
        if (modernBluetooth) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun missingPermissions(ctx: Context): List<String> =
        runtimePermissions().filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun hasBluetoothLe(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter(ctx) != null

    fun bluetoothOn(ctx: Context): Boolean = runCatching { adapter(ctx)?.isEnabled == true }.getOrDefault(false)

    /** Геолокація потрібна лише на Android 8–11. */
    val locationNeeded: Boolean get() = !modernBluetooth

    fun locationOn(ctx: Context): Boolean {
        if (!locationNeeded) return true
        return if (Build.VERSION.SDK_INT >= 28) {
            (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) !=
                Settings.Secure.LOCATION_MODE_OFF
        }
    }

    /** Чого бракує, людською мовою; порожньо — усе готово. */
    fun problems(ctx: Context): List<Need> = buildList {
        if (!hasBluetoothLe(ctx)) {
            add(Need.NO_BLE)
            return@buildList
        }
        if (missingPermissions(ctx).isNotEmpty()) add(Need.PERMISSION)
        if (!bluetoothOn(ctx)) add(Need.BLUETOOTH_OFF)
        if (!locationOn(ctx)) add(Need.LOCATION_OFF)
    }

    enum class Need(val title: String, val explain: String) {
        NO_BLE("Немає Bluetooth LE", "На цьому телефоні немає Bluetooth LE — з батареєю він говорити не зможе."),
        PERMISSION(
            "Дозвіл на Bluetooth",
            "Android має дозволити застосунку шукати й підключати пристрої поблизу.",
        ),
        BLUETOOTH_OFF("Bluetooth вимкнено", "Увімкни Bluetooth — батарея говорить лише через нього."),
        LOCATION_OFF(
            "Геолокація вимкнена",
            "На Android 11 і старіших без геолокації пошук Bluetooth нічого не знаходить. " +
                "Місцезнаходження застосунок нікуди не передає — інтернету в нього немає.",
        ),
    }
}
