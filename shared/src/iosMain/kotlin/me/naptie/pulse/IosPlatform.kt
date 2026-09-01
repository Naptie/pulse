package me.naptie.pulse

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlin.experimental.ExperimentalObjCName
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBUUID
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.posix.memcpy

private const val USERDEFAULTS_ID_KEY = "pulse.lastDeviceId"
private const val USERDEFAULTS_NAME_KEY = "pulse.lastDeviceName"

private val HR_SERVICE = "180D"
private val HR_MEASURE = "2A37"
private val CCCD = "2902"

actual fun createPlatform(engine: PulseEngine): PulsePlatform = IosPlatform(engine)

actual fun nowMs(): Long =
    (platform.Foundation.NSDate().timeIntervalSince1970 * 1000.0).toLong()

@OptIn(ExperimentalForeignApi::class)
class IosPlatform(private val engine: PulseEngine) : PulsePlatform {
    private val mainThread: Any? = null
    private var manager: CBCentralManager? = null
    private val peripherals = HashMap<String, CBPeripheral>()
    private val devices = LinkedHashMap<String, DeviceInfo>()
    private var current: CBPeripheral? = null
    private var currentName: String = ""
    private var delegate: ManagerDelegate? = null
    private var peripheralDelegate: PeripheralDelegate? = null

    override fun initialize(context: Any?) = start()

    private fun start() {
        if (manager != null) return
        val d = ManagerDelegate(this)
        delegate = d
        manager = CBCentralManager(delegate = d, queue = null)
    }

    internal fun fireDevices() {
        engine.onDevices(devices.values.toList())
    }

    override fun startScan() {
        start()
        devices.clear()
        fireDevices()
        manager?.scanForPeripheralsWithServices(
            null,
            options = mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to false)
        )
    }

    override fun stopScan() {
        manager?.stopScan()
    }

    override fun connect(deviceId: String) {
        start()
        val p = peripherals[deviceId] ?: return
        stopScan()
        val pd = PeripheralDelegate(this)
        peripheralDelegate = pd
        p.delegate = pd
        current = p
        manager?.connectPeripheral(p, options = null)
    }

    override fun disconnect() {
        val p = current
        if (p != null) {
            manager?.cancelPeripheralConnection(p)
            current = null
        }
        engine.onState("disconnected", "Stopped")
    }

    override fun release() {
        disconnect()
        manager?.stopScan()
        manager = null
    }

    override fun saveSavedDevice(id: String, name: String) {
        NSUserDefaults.standardUserDefaults.setObject(id, forKey = USERDEFAULTS_ID_KEY)
        NSUserDefaults.standardUserDefaults.setObject(name, forKey = USERDEFAULTS_NAME_KEY)
    }

    override fun loadSavedDeviceId(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(USERDEFAULTS_ID_KEY)

    override fun loadSavedDeviceName(): String =
        NSUserDefaults.standardUserDefaults.stringForKey(USERDEFAULTS_NAME_KEY) ?: ""

    internal fun found(peripheral: CBPeripheral, advertisementData: Map<Any?, *>, rssi: NSNumber) {
        val id = peripheral.identifier?.UUIDString ?: return
        val name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: peripheral.name
            ?: "Unnamed device"
        val hr = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>)?.any {
            (it as? CBUUID)?.UUIDString?.uppercase()?.startsWith(HR_SERVICE) == true
        } == true || (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? CBUUID)
            ?.UUIDString?.uppercase()?.startsWith(HR_SERVICE) == true
        peripherals[id] = peripheral
        devices[id] = DeviceInfo(id, name, rssi.intValue, hr)
        fireDevices()
    }

    internal fun connected(p: CBPeripheral) {
        p.discoverServices(listOf(CBUUID.UUIDWithString(HR_SERVICE)))
        val name = p.name ?: p.identifier?.UUIDString?.take(8) ?: "device"
        currentName = name
        p.identifier?.UUIDString?.let { engine.rememberLastDevice(it, name) }
        engine.onState("connected", "Connected · $name")
    }

    internal fun disconnected() {
        engine.onState("disconnected", "Connection lost")
    }

    internal fun peripheralDiscovered(peripheral: CBPeripheral) {
        val svc = peripheral.services?.filterIsInstance<CBService>()
            ?.firstOrNull { it.UUID.UUIDString == HR_SERVICE } ?: return
        peripheral.discoverCharacteristics(listOf(CBUUID.UUIDWithString(HR_MEASURE)), svc)
    }

    internal fun characteristicsFound(peripheral: CBPeripheral, service: CBService) {
        val ch = service.characteristics?.filterIsInstance<CBCharacteristic>()
            ?.firstOrNull { it.UUID.UUIDString == HR_MEASURE } ?: return
        peripheral.setNotifyValue(true, forCharacteristic = ch)
        val name = currentName.ifEmpty { peripheral.name ?: "device" }
        engine.onState("connected", "Connected · $name")
    }

    internal fun valueUpdated(characteristic: CBCharacteristic) {
        val data = characteristic.value ?: return
        engine.onBpm(HrParser.bpm(data.toByteArray()))
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class ManagerDelegate(private val platform: IosPlatform) :
    NSObject(), CBCentralManagerDelegateProtocol {

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        platform.fireDevices()
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        platform.found(didDiscoverPeripheral, advertisementData, RSSI)
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        platform.connected(didConnectPeripheral)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        platform.disconnected()
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        platform.disconnected()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class PeripheralDelegate(private val platform: IosPlatform) :
    NSObject(), CBPeripheralDelegateProtocol {

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverServices: NSError?,
    ) {
        platform.peripheralDiscovered(peripheral)
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?,
    ) {
        platform.characteristicsFound(peripheral, didDiscoverCharacteristicsForService)
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        platform.valueUpdated(didUpdateValueForCharacteristic)
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didWriteValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned {
        memcpy(it.addressOf(0), bytes, length)
    }
    return out
}
