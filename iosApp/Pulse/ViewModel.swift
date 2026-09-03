import SwiftUI
import Shared

final class PulseListenerBridge: NSObject, PulseListener {
    var onDevices: (([DeviceInfo]) -> Void)?
    var onHr: ((Int32) -> Void)?
    var onSpo2: ((Int32) -> Void)?
    var onState: ((String, String) -> Void)?
    var onHist: (([HrPoint]) -> Void)?
    var onSpo2Hist: (([Spo2Point]) -> Void)?

    func onDevicesChanged(devices: [DeviceInfo]) { onDevices?(devices) }
    func onHeartRate(bpm: Int32) { onHr?(bpm) }
    func onBloodOxygen(spo2: Int32) { onSpo2?(spo2) }
    func onStateChanged(state: String, detail: String) { onState?(state, detail) }
    func onHistory(history: [HrPoint]) { onHist?(history) }
    func onSpo2History(history: [Spo2Point]) { onSpo2Hist?(history) }
}

@MainActor
final class PulseViewModel: ObservableObject {
    @Published var devices: [DeviceInfo] = []
    @Published var hr: Int = 0
    @Published var spo2: Int = 0
    @Published var state: String = "idle"
    @Published var detail: String = ""
    @Published var history: [HrPoint] = []
    @Published var spo2History: [Spo2Point] = []
    @Published var selectedDeviceId: String?
    @Published var lastDeviceName: String = ""
    @Published var alertMessage: String?

    private let listener = PulseListenerBridge()
    let engine: PulseEngine

    var isMock: Bool { engine.isMock }

    private static var isSimulator: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    var isMonitoring: Bool { state == "connected" || state == "connecting" }

    init() {
        engine = PulseEngine(listener: listener, mock: Self.isSimulator)
        lastDeviceName = engine.savedDeviceName()
        listener.onDevices = { [weak self] in self?.devices = $0 }
        listener.onHr = { [weak self] in self?.hr = Int($0) }
        listener.onSpo2 = { [weak self] in self?.spo2 = Int($0) }
        listener.onState = { [weak self] state, detail in
            self?.state = state
            self?.detail = detail
            self?.lastDeviceName = self?.engine.savedDeviceName() ?? ""
            if state == "failed" {
                self?.alertMessage = detail
            }
        }
        listener.onHist = { [weak self] in self?.history = $0 }
        listener.onSpo2Hist = { [weak self] in self?.spo2History = $0 }
        engine.initialize(context: nil)
    }

    func startScan() { engine.startScan() }

    func connect(id: String) {
        selectedDeviceId = id
        engine.connect(deviceId: id)
    }

    func startLast() {
        lastDeviceName = engine.savedDeviceName()
        engine.connectLastDevice()
    }

    func stopMonitoring() {
        engine.disconnect()
    }

    func dismissAlert() {
        alertMessage = nil
    }
}

struct GradientBackground: View {
    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.06, green: 0.06, blue: 0.15),
                    Color(red: 0.13, green: 0.10, blue: 0.27),
                    Color(red: 0.07, green: 0.06, blue: 0.15),
                ],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
            RadialGradient(
                colors: [Color(red: 1.0, green: 0.30, blue: 0.45).opacity(0.24), Color(red: 1.0, green: 0.30, blue: 0.45).opacity(0.0)],
                center: UnitPoint(x: 0.80, y: 0.92),
                startRadius: 0, endRadius: 520
            )
            RadialGradient(
                colors: [Color(red: 0.50, green: 0.28, blue: 1.0).opacity(0.22), Color(red: 0.50, green: 0.28, blue: 1.0).opacity(0.0)],
                center: UnitPoint(x: 0.08, y: 0.12),
                startRadius: 0, endRadius: 600
            )
            RadialGradient(
                colors: [Color(red: 0.78, green: 0.32, blue: 0.95).opacity(0.12), Color(red: 0.78, green: 0.32, blue: 0.95).opacity(0.0)],
                center: UnitPoint(x: 0.45, y: 0.42),
                startRadius: 0, endRadius: 640
            )
        }
        .ignoresSafeArea()
    }
}
