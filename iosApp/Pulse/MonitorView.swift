import SwiftUI
import Charts
import Shared

enum VitalKind {
    case heartRate
    case bloodOxygen
}

struct HeartRateView: View {
    @ObservedObject var vm: PulseViewModel
    @Binding var tab: Int

    var body: some View {
        VitalPageView(vm: vm, tab: $tab, kind: .heartRate)
    }
}

struct BloodOxygenView: View {
    @ObservedObject var vm: PulseViewModel
    @Binding var tab: Int

    var body: some View {
        VitalPageView(vm: vm, tab: $tab, kind: .bloodOxygen)
    }
}

struct VitalPageView: View {
    @ObservedObject var vm: PulseViewModel
    @Binding var tab: Int
    let kind: VitalKind

    private var isHeart: Bool { kind == .heartRate }

    private var hrSamples: [(Date, Int32)] {
        vm.history.filter { $0.bpm > 0 }
            .map { (Date(timeIntervalSince1970: Double($0.t) / 1000), $0.bpm) }
    }

    private var spo2Samples: [(Date, Int32)] {
        vm.spo2History.filter { $0.spo2 > 0 }
            .map { (Date(timeIntervalSince1970: Double($0.t) / 1000), $0.spo2) }
    }

    private var hrDomain: ClosedRange<Int32> {
        let bs = vm.history.map(\.bpm)
        guard let mn = bs.min(), let mx = bs.max() else { return 40...140 }
        return max(30, mn - 10)...min(230, mx + 12)
    }

    private var placeholderText: String {
        if vm.isMonitoring { return vm.isMock ? UiStrings.shared.simulatingVitals : UiStrings.shared.waitingForData }
        return vm.lastDeviceName.isEmpty ? UiStrings.shared.findYourSensor : UiStrings.shared.startMeasuringToRecord
    }

    private var spo2Color: Color {
        Color(red: 0.30, green: 0.79, blue: 0.69)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                // status pill
                HStack(spacing: 8) {
                    Circle()
                        .fill(vm.isMonitoring ? Color.green : Color.orange)
                        .frame(width: 9, height: 9)
                        .shadow(color: (vm.isMonitoring ? Color.green : Color.orange).opacity(0.9), radius: 5)
                    Text(statusText)
                        .font(.system(size: 14, weight: .medium, design: .rounded))
                        .foregroundStyle(.white.opacity(0.85))
                        .lineLimit(1)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 9)
                .glassEffect(.regular, in: Capsule())
                .padding(.top, 18)

                bigMetric

                VitalChartCard(
                    samples: samples,
                    domain: domain,
                    color: accent,
                    unit: unitText,
                    placeholder: placeholderText,
                    monitoring: vm.isMonitoring,
                    autoCursorTest: isHeart
                )

                // primary action
                if vm.isMonitoring {
                    Button {
                        vm.stopMonitoring()
                    } label: {
                        Label(UiStrings.shared.stopMeasuring, systemImage: "xmark")
                            .font(.system(size: 17, weight: .semibold, design: .rounded))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.glass)
                    .controlSize(.large)
                    .padding(.bottom, 12)
                } else if !vm.lastDeviceName.isEmpty {
                    Button {
                        vm.startLast()
                    } label: {
                        Label(UiStrings.shared.startMeasuring, systemImage: "play.fill")
                            .font(.system(size: 17, weight: .semibold, design: .rounded))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.glassProminent)
                    .controlSize(.large)
                    .padding(.bottom, 12)
                } else {
                    Button {
                        withAnimation { tab = 0 }
                    } label: {
                        Label(UiStrings.shared.findADevice, systemImage: "magnifyingglass")
                            .font(.system(size: 17, weight: .semibold, design: .rounded))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.glassProminent)
                    .controlSize(.large)
                    .padding(.bottom, 12)
                }
            }
            .padding(.horizontal, 20)
        }
        .scrollIndicators(.hidden)
        .background(GradientBackground())
        .alert(UiStrings.shared.couldntConnect, isPresented: Binding(
            get: { vm.alertMessage != nil },
            set: { if !$0 { vm.dismissAlert() } }
        )) {
            Button(UiStrings.shared.tryAgain) { vm.startLast() }
            Button(UiStrings.shared.ok, role: .cancel) { vm.dismissAlert() }
        } message: {
            Text(vm.alertMessage ?? "")
        }
    }

    private var samples: [(Date, Int32)] { isHeart ? hrSamples : spo2Samples }
    private var domain: ClosedRange<Int32> { isHeart ? hrDomain : 80...100 }
    private var accent: Color { isHeart ? .pink : spo2Color }
    private var unitText: String {
        isHeart ? UiStrings.shared.bpm : UiStrings.shared.percent
    }

    @ViewBuilder
    private var bigMetric: some View {
        VStack(spacing: 2) {
            if isHeart {
                HeartPulse(bpm: vm.isMonitoring && vm.hr > 0 ? vm.hr : 0)
                Text(vm.hr > 0 ? "\(vm.hr)" : "—")
                    .font(.system(size: 104, weight: .bold, design: .rounded))
                    .foregroundStyle(vm.hr > 0 ? .white : .white.opacity(0.30))
                    .contentTransition(.numericText())
                    .animation(.snappy(duration: 0.35), value: vm.hr)
                Text(UiStrings.shared.bpm)
                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                    .tracking(8)
                    .foregroundStyle(vm.hr > 0 ? .white.opacity(0.45) : .white.opacity(0.25))
                if vm.hr > 0 {
                    zoneLabel(vm.hr)
                } else if !vm.isMonitoring {
                    Text(vm.lastDeviceName.isEmpty ? UiStrings.shared.noDeviceYet : UiStrings.shared.tapStartToMeasure)
                        .font(.system(size: 13, weight: .semibold, design: .rounded))
                        .foregroundStyle(.white.opacity(0.4))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(.white.opacity(0.08), in: Capsule())
                        .padding(.top, 8)
                }
            } else {
                Text("O₂")
                    .font(.system(size: 52, weight: .semibold, design: .rounded))
                    .foregroundStyle(vm.spo2 > 0 ? spo2Color : .white.opacity(0.22))
                Text(vm.spo2 > 0 ? "\(vm.spo2)" : "—")
                    .font(.system(size: 104, weight: .bold, design: .rounded))
                    .foregroundStyle(vm.spo2 > 0 ? .white : .white.opacity(0.30))
                    .contentTransition(.numericText())
                    .animation(.snappy(duration: 0.35), value: vm.spo2)
                Text(UiStrings.shared.percent)
                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                    .tracking(8)
                    .foregroundStyle(vm.spo2 > 0 ? .white.opacity(0.45) : .white.opacity(0.25))
                if vm.spo2 > 0 {
                    spo2ZoneLabel(vm.spo2)
                } else if !vm.isMonitoring {
                    Text(vm.lastDeviceName.isEmpty ? UiStrings.shared.noDeviceYet : UiStrings.shared.tapStartToMeasure)
                        .font(.system(size: 13, weight: .semibold, design: .rounded))
                        .foregroundStyle(.white.opacity(0.4))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(.white.opacity(0.08), in: Capsule())
                        .padding(.top, 8)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
    }

    private var statusText: String {
        if vm.isMonitoring { return vm.detail }
        return vm.lastDeviceName.isEmpty
            ? UiStrings.shared.notMonitoring
            : UiStrings.shared.nameNotMeasuring(name: vm.lastDeviceName)
    }

    @ViewBuilder
    private func zoneLabel(_ bpm: Int) -> some View {
        let (text, color) = zone(bpm)
        Text(text)
            .font(.system(size: 13, weight: .semibold, design: .rounded))
            .foregroundStyle(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(color.opacity(0.16), in: Capsule())
            .padding(.top, 8)
    }

    private func zone(_ bpm: Int) -> (String, Color) {
        switch bpm {
        case ..<60: return (UiStrings.shared.zoneRest, Color(red: 0.45, green: 0.80, blue: 1.0))
        case 60..<100: return (UiStrings.shared.zoneNormal, .green.opacity(0.9))
        case 100..<120: return (UiStrings.shared.zoneElevated, .orange)
        case 120..<160: return (UiStrings.shared.zoneExercise, spo2Color)
        default: return (UiStrings.shared.zonePeak, .red)
        }
    }

    @ViewBuilder
    private func spo2ZoneLabel(_ spo2: Int) -> some View {
        let (text, color) = spo2Zone(spo2)
        Text(text)
            .font(.system(size: 13, weight: .semibold, design: .rounded))
            .foregroundStyle(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(color.opacity(0.16), in: Capsule())
            .padding(.top, 8)
    }

    private func spo2Zone(_ spo2: Int) -> (String, Color) {
        switch spo2 {
        case 95...: return (UiStrings.shared.zoneNormal, .green.opacity(0.9))
        case 91...94: return (UiStrings.shared.zoneAttention, .orange)
        default: return (UiStrings.shared.zoneLow, .red)
        }
    }
}

struct VitalChartCard: View {
    let samples: [(Date, Int32)]
    let domain: ClosedRange<Int32>
    let color: Color
    let unit: String
    let placeholder: String
    let monitoring: Bool
    let autoCursorTest: Bool

    @State private var cursorTime: Date?
    @State private var cursorPoint: (Date, Int32)?
    @State private var isTouching = false
    @State private var dismissDeadline: Date?
    @State private var dismissWork: DispatchWorkItem?

    private static let CURSOR_LIFETIME: Double = 3.0

    private func clearCursor() {
        dismissWork?.cancel()
        dismissWork = nil
        dismissDeadline = nil
        withAnimation(.easeOut(duration: 0.3)) {
            cursorTime = nil
            cursorPoint = nil
        }
    }

    private func armCursorDismiss() {
        dismissDeadline = Date().addingTimeInterval(Self.CURSOR_LIFETIME)
        dismissWork?.cancel()
        let work = DispatchWorkItem { [self] in
            if isTouching {
                armCursorDismiss()
            } else {
                sweepCursor()
            }
        }
        dismissWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.CURSOR_LIFETIME + 1.0, execute: work)
    }

    private func sweepCursor() {
        guard cursorTime != nil, !isTouching else { return }
        if let d = dismissDeadline, Date() >= d {
            clearCursor()
        }
    }

    private func setCursor(_ date: Date) {
        cursorTime = date
        cursorPoint = nearestPoint(to: date)
        armCursorDismiss()
    }

    private func nearestPoint(to date: Date) -> (Date, Int32)? {
        samples.min(by: { abs($0.0.timeIntervalSince1970 - date.timeIntervalSince1970) < abs($1.0.timeIntervalSince1970 - date.timeIntervalSince1970) })
    }

    private var segments: [[(Date, Int32)]] {
        guard !samples.isEmpty else { return [] }
        var result: [[(Date, Int32)]] = []
        var current: [(Date, Int32)] = [samples[0]]
        for i in 1..<samples.count {
            if samples[i].0.timeIntervalSince1970 - samples[i - 1].0.timeIntervalSince1970 > 2.6 {
                result.append(current)
                current = [samples[i]]
            } else {
                current.append(samples[i])
            }
        }
        result.append(current)
        return result
    }

    private var xDomain: ClosedRange<Date> {
        guard samples.count >= 2, let first = samples.first, let last = samples.last else {
            let now = Date()
            return now.addingTimeInterval(-10)...now.addingTimeInterval(10)
        }
        return first.0...last.0.addingTimeInterval(2)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(UiStrings.shared.last5Minutes)
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .tracking(2)
                    .foregroundStyle(.white.opacity(0.55))
                Spacer()
                if monitoring {
                    Text("1s · \(samples.count) pts")
                        .font(.system(size: 12, design: .rounded))
                        .foregroundStyle(.white.opacity(0.35))
                }
            }
            if samples.count >= 2 {
                chart
                    .frame(height: 200)
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "waveform.path.ecg")
                        .font(.system(size: 34))
                        .foregroundStyle(.white.opacity(0.22))
                    Text(placeholder)
                        .font(.system(size: 14, design: .rounded))
                        .foregroundStyle(.white.opacity(0.4))
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 200)
            }
        }
        .padding(18)
        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 28))
    }

    private var chart: some View {
        Chart {
            chartMarks
        }
        .chartXScale(domain: xDomain)
        .chartYScale(domain: domain)
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                AxisGridLine().foregroundStyle(.white.opacity(0.05))
                AxisValueLabel(format: .dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
                    .font(.system(size: 10, design: .rounded))
                    .foregroundStyle(.white.opacity(0.45))
            }
        }
        .chartYAxis {
            AxisMarks(position: .trailing, values: .automatic(desiredCount: 4)) { value in
                AxisGridLine().foregroundStyle(.white.opacity(0.05))
                if let v = value.as(Int.self) {
                    AxisValueLabel {
                        Text("\(v)")
                            .font(.system(size: 10, design: .rounded))
                            .foregroundStyle(.white.opacity(0.4))
                    }
                }
            }
        }
        .chartOverlay { proxy in
            GeometryReader { geo in
                let pf: CGRect = {
                    if let anchor = proxy.plotFrame { return geo[anchor] }
                    return geo.frame(in: .local)
                }()
                areaTouch(proxy: proxy, pf: pf)
                    .overlay { cursorLayer(pf: pf, full: geo.frame(in: .local)) }
            }
        }
        .onChange(of: cursorTime) { _, t in
            if let t {
                cursorPoint = nearestPoint(to: t)
                armCursorDismiss()
            } else {
                dismissWork?.cancel()
                dismissWork = nil
            }
        }
        .onChange(of: samples.count) { _, _ in
            sweepCursor()
            if let ct = cursorTime {
                cursorPoint = nearestPoint(to: ct)
            }
        }
        .task {
            guard autoCursorTest,
                  ProcessInfo.processInfo.environment["PULSE_CURSOR_TEST"] == "1" else { return }
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            setCursor(Date())
        }
    }

    @ChartContentBuilder
    private var chartMarks: some ChartContent {
        let lo = domain.lowerBound
        ForEach(segments, id: \.first!.0) { seg in
            ForEach(seg, id: \.0) { pt in
                AreaMark(
                    x: .value("Time", pt.0),
                    yStart: .value("Base", lo),
                    yEnd: .value("Vital", pt.1)
                )
                .interpolationMethod(.catmullRom)
                .foregroundStyle(
                    LinearGradient(
                        colors: [color.opacity(0.45), color.opacity(0.06)],
                        startPoint: .top, endPoint: .bottom
                    )
                )
                LineMark(
                    x: .value("Time", pt.0),
                    y: .value("Vital", pt.1)
                )
                .interpolationMethod(.catmullRom)
                .foregroundStyle(color)
                .lineStyle(StrokeStyle(lineWidth: 2.6, lineCap: .round))
            }
        }
    }

    private func areaTouch(proxy: ChartProxy, pf: CGRect) -> some View {
        Rectangle()
            .fill(.clear)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        isTouching = true
                        let x = g.location.x - pf.minX
                        if let date: Date = proxy.value(atX: x) {
                            withAnimation(.easeOut(duration: 0.1)) { setCursor(date) }
                        }
                    }
                    .onEnded { _ in
                        isTouching = false
                        armCursorDismiss()
                    }
            )
    }

    @ViewBuilder
    private func cursorLayer(pf: CGRect, full: CGRect) -> some View {
        if let ct = cursorTime, let cp = cursorPoint {
            let s0 = xDomain.lowerBound
            let s1 = xDomain.upperBound
            let lo = Double(domain.lowerBound)
            let hi = Double(domain.upperBound)
            let t0 = s0.timeIntervalSince1970
            let t1 = s1.timeIntervalSince1970
            let t = ct.timeIntervalSince1970
            let xFrac = (t - t0) / (t1 - t0)
            let yFrac = (Double(cp.1) - lo) / (hi - lo)
            let w = CGFloat(pf.width)
            let h = CGFloat(pf.height)
            let x = CGFloat(xFrac) * w + pf.minX
            let y = (1 - CGFloat(yFrac)) * h + pf.minY
            let chipW = 66.0
            let chipX = min(max(x, pf.minX + chipW / 2 + 2), pf.minX + w - chipW / 2 - 2)
            let chipY = y - 30 >= pf.minY + 4 ? y - 30 : y + 30

            ZStack {
                Path { p in
                    p.move(to: CGPoint(x: 0.7, y: 0))
                    p.addLine(to: CGPoint(x: 0.7, y: h))
                }
                .stroke(.white.opacity(0.55), style: StrokeStyle(lineWidth: 1.4, dash: [4, 3]))
                .frame(width: 1.4, height: h)
                .position(x: x, y: pf.minY + h / 2)

                Path { p in
                    p.move(to: CGPoint(x: 0, y: 0.4))
                    p.addLine(to: CGPoint(x: w, y: 0.4))
                }
                .stroke(.white.opacity(0.18), lineWidth: 0.8)
                .frame(width: w, height: 0.8)
                .position(x: pf.minX + w / 2, y: y)

                Circle()
                    .fill(color)
                    .frame(width: 9, height: 9)
                    .position(x: x, y: y)

                Text("\(cp.1) \(unit)")
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .glassEffect(.regular, in: Capsule())
                    .position(x: chipX, y: chipY)
            }
            .transition(.opacity)
            .frame(width: CGFloat(full.width), height: CGFloat(full.height))
        }
    }
}

struct HeartPulse: View {
    let bpm: Int

    var body: some View {
        Image(systemName: "heart.fill")
            .font(.system(size: 52))
            .foregroundStyle(
                bpm > 0
                    ? LinearGradient(colors: [.pink, Color(red: 0.85, green: 0.10, blue: 0.32)],
                                     startPoint: .top, endPoint: .bottom)
                    : LinearGradient(colors: [.white.opacity(0.22), .white.opacity(0.10)],
                                     startPoint: .top, endPoint: .bottom)
            )
            .symbolEffect(.pulse, options: .repeating.speed(max(0.15, Double(bpm) / 60.0)))
            .shadow(color: bpm > 0 ? .pink.opacity(0.55) : .clear, radius: 18)
            .padding(.bottom, 2)
            .frame(height: 64)
    }
}
