package ua.faric.aolithium.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import ua.faric.aolithium.core.tr

/**
 * Що потрібно, щоб шукати й читати батарею по Bluetooth. Різне для різних Android:
 *
 * - Android 12 і новіші: дозволи «Пристрої поблизу» (`BLUETOOTH_SCAN` з позначкою «не для місцезнаходження»
 *   і `BLUETOOTH_CONNECT`). Геолокація не потрібна.
 * - Android 8–11 (Huawei Mate 10 Lite, Redmi 9T…): пошук Bluetooth LE Android видає лише з дозволом
 *   на **місцезнаходження** і з **увімкненою геолокацією** — інакше пошук мовчки нічого не знаходить.
 *   Місцезнаходження застосунок нікуди не передає: інтернет він використовує лише для оновлення з Google Диска автора (T37).
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

    enum class Need {
        NO_BLE,
        PERMISSION,
        BLUETOOTH_OFF,
        LOCATION_OFF,
        ;

        val title: String
            get() = when (this) {
                NO_BLE -> tr("Немає Bluetooth LE", "No Bluetooth LE")
                PERMISSION -> tr("Дозвіл на Bluetooth", "Bluetooth permission")
                BLUETOOTH_OFF -> tr("Bluetooth вимкнено", "Bluetooth is off")
                LOCATION_OFF -> tr("Геолокація вимкнена", "Location is off")
            }

        val explain: String
            get() = when (this) {
                NO_BLE -> tr(
                    "На цьому телефоні немає Bluetooth LE — з батареєю він говорити не зможе.",
                    "This phone has no Bluetooth LE. It cannot talk to the battery.",
                )
                PERMISSION -> tr(
                    "Android має дозволити застосунку шукати й підключати пристрої поблизу.",
                    "Android must allow the app to find and connect to nearby devices.",
                )
                BLUETOOTH_OFF -> tr(
                    "Увімкни Bluetooth — батарея говорить лише через нього.",
                    "Turn on Bluetooth. The battery talks only through it.",
                )
                LOCATION_OFF -> tr(
                    "На Android 11 і старіших без геолокації пошук Bluetooth нічого не знаходить. " +
                        "Місцезнаходження застосунок нікуди не передає — інтернет він використовує лише для оновлень застосунку.",
                    "On Android 11 and older, Bluetooth search finds nothing without location. " +
                        "The app does not send your location anywhere. It uses the internet only for app updates.",
                )
            }
    }
}
