package your.package.discovery

import android.util.Log

private const val TAG = "DiscoveryDeviceStore"

internal class DiscoveryDeviceStore {
    private val lock = Any()

    // The visible list and the MAC lookup index always change together.
    private val devices = linkedMapOf<Long, DiscoveryDevice>()
    private val macIndex = mutableMapOf<MacKey, Long>()
    private val pendingChanges = mutableListOf<DiscoveryDeviceChange>()

    private var initialDevicesApplied = false
    private var nextId = 0L

    fun applyInitialDevices(
        initialDevices: List<DiscoveryDevice>,
    ): List<DiscoveryDevice> =
        synchronized(lock) {
            val pendingCount = pendingChanges.size

            clearStateLocked()
            initialDevices.forEach { upsertDeviceLocked(it, allowInsertWithoutMac = true) }
            pendingChanges.forEach(::applyChangeLocked)
            pendingChanges.clear()
            initialDevicesApplied = true

            if (pendingCount > 0) {
                Log.d(TAG, "Applied initial devices with pending changes. initial=${initialDevices.size}, pending=$pendingCount, total=${devices.size}")
            }

            currentDevicesLocked()
        }

    fun applyChange(
        change: DiscoveryDeviceChange,
    ): List<DiscoveryDevice>? =
        synchronized(lock) {
            if (!initialDevicesApplied) {
                pendingChanges += change
                if (pendingChanges.size == 1) {
                    Log.d(TAG, "Queued discovery changes until initial devices are applied.")
                }
                return@synchronized null
            }

            if (!applyChangeLocked(change)) {
                return@synchronized null
            }

            currentDevicesLocked()
        }

    fun clear() {
        synchronized(lock) {
            clearStateLocked()
            pendingChanges.clear()
            initialDevicesApplied = false
        }
    }

    private fun applyChangeLocked(
        change: DiscoveryDeviceChange,
    ): Boolean =
        when (change.type) {
            DiscoveryDeviceChange.Type.ADDED -> upsertDeviceLocked(change.device, allowInsertWithoutMac = true)
            DiscoveryDeviceChange.Type.UPDATED -> upsertDeviceLocked(change.device, allowInsertWithoutMac = false)
            DiscoveryDeviceChange.Type.REMOVED -> removeDeviceLocked(change.device)
        }

    private fun upsertDeviceLocked(
        eventDevice: DiscoveryDevice,
        allowInsertWithoutMac: Boolean,
    ): Boolean {
        val eventKeys = eventDevice.macKeys()
        val matchedIds = eventKeys.mapNotNull(macIndex::get).toSet()

        if (matchedIds.isEmpty()) {
            if (eventKeys.isEmpty() && !allowInsertWithoutMac) {
                return false
            }
            if (eventKeys.isEmpty() && containsSameDeviceWithoutMacLocked(eventDevice)) {
                return false
            }

            addDeviceLocked(eventDevice)
            return true
        }

        val retainedDeviceId = devices.keys.first { it in matchedIds }
        val resolved =
            matchedIds
                .map(devices::getValue)
                .fold(eventDevice) { latest, stored ->
                    latest.keepKnownMacs(stored)
                }

        val changed = matchedIds.size > 1 || devices[retainedDeviceId] != resolved

        replaceDeviceLocked(
            deviceId = retainedDeviceId,
            device = resolved,
        )
        matchedIds
            .filterNot { it == retainedDeviceId }
            .forEach(::removeDeviceByIdLocked)

        if (matchedIds.size > 1) {
            Log.d(TAG, "Collapsed duplicated discovery devices. merged=${matchedIds.size}, total=${devices.size}")
        }

        return changed
    }

    private fun removeDeviceLocked(
        device: DiscoveryDevice,
    ): Boolean {
        val matchedIds = device.macKeys().mapNotNull(macIndex::get).toSet()
        if (matchedIds.isEmpty()) {
            return false
        }

        // DELETE can arrive with only part of the MAC information.
        matchedIds.forEach(::removeDeviceByIdLocked)
        return true
    }

    private fun addDeviceLocked(
        device: DiscoveryDevice,
    ) {
        val deviceId = ++nextId
        devices[deviceId] = device
        addMacIndexEntriesLocked(
            deviceId = deviceId,
            device = device,
        )
    }

    private fun replaceDeviceLocked(
        deviceId: Long,
        device: DiscoveryDevice,
    ) {
        val previous = devices.put(deviceId, device) ?: return
        removeMacIndexEntriesLocked(
            deviceId = deviceId,
            device = previous,
        )
        addMacIndexEntriesLocked(
            deviceId = deviceId,
            device = device,
        )
    }

    private fun removeDeviceByIdLocked(
        deviceId: Long,
    ) {
        val removed = devices.remove(deviceId) ?: return
        removeMacIndexEntriesLocked(
            deviceId = deviceId,
            device = removed,
        )
    }

    private fun addMacIndexEntriesLocked(
        deviceId: Long,
        device: DiscoveryDevice,
    ) {
        device.macKeys().forEach { key ->
            macIndex[key] = deviceId
        }
    }

    private fun removeMacIndexEntriesLocked(
        deviceId: Long,
        device: DiscoveryDevice,
    ) {
        device.macKeys().forEach { key ->
            if (macIndex[key] == deviceId) {
                macIndex.remove(key)
            }
        }
    }

    private fun containsSameDeviceWithoutMacLocked(
        device: DiscoveryDevice,
    ): Boolean =
        devices.values.any { storedDevice ->
            storedDevice.macKeys().isEmpty() && storedDevice == device
        }

    private fun clearStateLocked() {
        devices.clear()
        macIndex.clear()
        nextId = 0L
    }

    private fun currentDevicesLocked(): List<DiscoveryDevice> =
        devices.values.toList()
}

internal object DiscoveryDeviceIdentity {
    fun isSameDevice(
        first: DiscoveryDevice,
        second: DiscoveryDevice,
    ): Boolean {
        val secondKeys = second.macKeys().toSet()
        return first.macKeys().any(secondKeys::contains)
    }
}

private fun DiscoveryDevice.macKeys(): List<MacKey> =
    listOfNotNull(
        MacKey.of(MacType.BT, deviceIDs.btMac),
        MacKey.of(MacType.BLE, deviceIDs.bleMac),
        MacKey.of(MacType.WIFI, deviceIDs.wifiMac),
        MacKey.of(MacType.P2P, deviceIDs.p2pMac),
    )

private fun DiscoveryDevice.keepKnownMacs(
    previous: DiscoveryDevice,
): DiscoveryDevice =
    copy(
        // Partial scan events may omit identifiers seen earlier.
        // Keep already-known MACs so future events can still match the same device.
        deviceIDs = deviceIDs.copy(
            btMac = deviceIDs.btMac ?: previous.deviceIDs.btMac,
            bleMac = deviceIDs.bleMac ?: previous.deviceIDs.bleMac,
            wifiMac = deviceIDs.wifiMac ?: previous.deviceIDs.wifiMac,
            p2pMac = deviceIDs.p2pMac ?: previous.deviceIDs.p2pMac,
        ),
    )

private enum class MacType {
    BT,
    BLE,
    WIFI,
    P2P,
}

private data class MacKey(
    val type: MacType,
    val value: String,
) {
    companion object {
        fun of(
            type: MacType,
            rawMac: String?,
        ): MacKey? {
            val normalized =
                rawMac
                    ?.trim()
                    ?.filter(Char::isLetterOrDigit)
                    ?.lowercase()
                    ?.takeIf(String::isNotEmpty)
                    ?: return null

            return MacKey(
                type = type,
                value = normalized,
            )
        }
    }
}
