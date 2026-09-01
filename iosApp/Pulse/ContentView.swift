import SwiftUI
import Shared

struct ContentView: View {
    @StateObject private var vm = PulseViewModel()
    @State private var tab: Int = 0

    var body: some View {
        ZStack {
            GradientBackground()
            TabView(selection: $tab) {
                DevicesView(vm: vm, tab: $tab)
                    .tabItem { Label(UiStrings.shared.tabDevices, systemImage: "dot.radiowaves.left.and.right") }
                    .tag(0)
                MonitorView(vm: vm, tab: $tab)
                    .tabItem { Label(UiStrings.shared.tabVitals, systemImage: "heart.fill") }
                    .tag(1)
            }
            .tint(Color(red: 1.0, green: 0.22, blue: 0.37))
        }
        .task {
            let auto = ProcessInfo.processInfo.environment["PULSE_AUTO"]
            if auto == "monitor" {
                vm.startScan()
                try? await Task.sleep(nanoseconds: 1_600_000_000)
                if let first = vm.devices.first {
                    vm.connect(id: first.id)
                    withAnimation { tab = 1 }
                }
            }
        }
    }
}

struct DevicesView: View {
    @ObservedObject var vm: PulseViewModel
    @Binding var tab: Int

    /// Heart-rate devices pinned first, then strongest signal first.
    private var sortedDevices: [DeviceInfo] {
        vm.devices.sorted { a, b in
            if a.hr != b.hr { return a.hr && !b.hr }
            if a.rssi != b.rssi { return a.rssi > b.rssi }
            return a.name < b.name
        }
    }

    private var listSignature: String {
        sortedDevices.map { "\($0.id)|\($0.rssi)|\($0.hr)" }.joined(separator: ";")
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(UiStrings.shared.appTitle)
                        .font(.system(size: 42, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                    Text(UiStrings.shared.appSubtitle)
                        .font(.system(size: 17, design: .rounded))
                        .foregroundStyle(.white.opacity(0.55))
                }
                .padding(.top, 18)

                Button {
                    vm.startScan()
                } label: {
                    HStack(spacing: 10) {
                        if vm.state == "scanning" {
                            ProgressView().tint(.white)
                            Text(UiStrings.shared.scanning)
                        } else {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                            Text(UiStrings.shared.scanForDevices)
                        }
                    }
                    .font(.system(size: 18, weight: .semibold, design: .rounded))
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .controlSize(.large)

                if sortedDevices.isEmpty && vm.state != "scanning" {
                    VStack(spacing: 16) {
                        Spacer().frame(height: 36)
                        Image(systemName: "heart.slash")
                            .font(.system(size: 44))
                            .foregroundStyle(.white.opacity(0.25))
                        Text(UiStrings.shared.noDevicesYet)
                            .font(.system(size: 15, design: .rounded))
                            .foregroundStyle(.white.opacity(0.45))
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .transition(.opacity)
                }

                VStack(spacing: 10) {
                    ForEach(sortedDevices, id: \.id) { device in
                        DeviceRow(device: device) {
                            vm.connect(id: device.id)
                            withAnimation { tab = 1 }
                        }
                        .transition(.opacity.combined(with: .scale(scale: 0.92)))
                    }
                }
                .animation(.snappy(duration: 0.5, extraBounce: 0.15), value: listSignature)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background(GradientBackground())
    }
}

struct DeviceRow: View {
    let device: DeviceInfo
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .fill(LinearGradient(colors: [
                            device.hr ? Color.pink.opacity(0.85) : Color.indigo.opacity(0.9),
                            device.hr ? Color(red: 0.80, green: 0.05, blue: 0.30) : Color(red: 0.15, green: 0.12, blue: 0.40),
                        ], startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(width: 46, height: 46)
                    Image(systemName: device.hr ? "heart.fill" : "wave.3.right")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(.white)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(device.name)
                        .font(.system(size: 16, weight: .medium, design: .rounded))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.up.arrow.down.square.fill")
                            .font(.system(size: 10))
                        Text("\(device.rssi) \(UiStrings.shared.dbm)")
                            .monospacedDigit()
                    }
                    .font(.system(size: 12, design: .rounded))
                    .foregroundStyle(.white.opacity(0.5))
                }
                Spacer()
                if device.hr {
                    Text(UiStrings.shared.hrBadge)
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 5)
                        .background(.pink.opacity(0.28), in: Capsule())
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.35))
            }
            .padding(16)
            .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 26))
            .contentShape(RoundedRectangle(cornerRadius: 26))
        }
        .buttonStyle(PressScaleStyle())
    }
}

struct PressScaleStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1.0)
            .animation(.spring(response: 0.3, dampingFraction: 0.55), value: configuration.isPressed)
    }
}
