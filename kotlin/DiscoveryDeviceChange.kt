package your.package.discovery

data class DiscoveryDeviceChange(
    val type: Type,
    val device: DiscoveryDevice,
) {
    enum class Type {
        ADDED,
        UPDATED,
        REMOVED,
    }
}

interface DiscoveryDeviceChangeListener {
    fun onChanged(change: DiscoveryDeviceChange)
}
