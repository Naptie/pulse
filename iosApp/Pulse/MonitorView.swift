import SwiftUI
import Charts
import Shared

struct MonitorView: View {
    @ObservedObject var vm: PulseViewModel
    @Binding var tab: Int
    @State private var cursorTime: Date?
    @State private var cursorPoint: HrPoint?
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

    private func nearestPoint(to date: Date) -> HrPoint? {
        let ms = Int64(date.timeIntervalSince1970 * 1000)
        return history.min(by: { abs($0.t - ms) < abs($1.t - ms) })
    }

    private var history: [HrPoint] { vm.history.filter { $0.bpm > 0 } }

    private var domain: ClosedRange<Int32> {
        let bs = history.map(\.bpm)
        guard let mn = bs.min(), let mx = bs.max() else { return 40...140 }
        return max(30, mn - 10)...min(230, mx + 12)
    }

    /// Splits history into continuous runs so inactive windows render as blank area.
    private var segments: [[HrPoint]] {
        guard !history.isEmpty else { return [] }
        var result: [[HrPoint]] = []
        var current: [HrPoint] = [history[0]]
        for i in 1..<history.count {
            if history[i].t - history[i - 1].t > 2600 {
                result.append(current)
                current = [history[i]]
            } else {
                current.append(history[i])
            }
        }
        result.append(current)
        return result
    }

    private var xDomain: ClosedRange<Date> {
        // Adaptive: window starts when monitoring started (capped at 5 min by history).
        // Time-based x positions keep inactive windows as blank channels between segments.
        guard history.count >= 2, let first = history.first, let last = history.last else {
            let now = Date()
            return now.addingTimeInterval(-10)...now.addingTimeInterval(10)
        }
        let from = Date(timeIntervalSince1970: Double(first.t) / 1000)
        let to = Date(timeIntervalSince1970: Double(last.t) / 1000).addingTimeInterval(2)
        return from...to
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
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

                // big heart rate
                VStack(spacing: 2) {
                    if vm.isMonitoring {
                        HeartPulse(bpm: vm.hr)
                        Text("\(vm.hr)")
                            .font(.system(size: 104, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                            .contentTransition(.numericText())
                            .animation(.snappy(duration: 0.35), value: vm.hr)
                        Text(UiStrings.shared.bpm)
                            .font(.system(size: 16, weight: .semibold, design: .rounded))
                            .tracking(8)
                            .foregroundStyle(.white.opacity(0.45))
                        zoneLabel(vm.hr)
                    } else {
                        HeartPulse(bpm: 0)
                        Text("—")
                            .font(.system(size: 104, weight: .bold, design: .rounded))
                            .foregroundStyle(.white.opacity(0.30))
                        Text(UiStrings.shared.bpm)
                            .font(.system(size: 16, weight: .semibold, design: .rounded))
                            .tracking(8)
                            .foregroundStyle(.white.opacity(0.25))
                        Text(vm.lastDeviceName.isEmpty ? UiStrings.shared.noDeviceYet : UiStrings.shared.tapStartToMeasure)
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundStyle(.white.opacity(0.4))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(.white.opacity(0.08), in: Capsule())
                            .padding(.top, 8)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)

                // history card
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        Text(UiStrings.shared.last5Minutes)
                            .font(.system(size: 12, weight: .bold, design: .rounded))
                            .tracking(2)
                            .foregroundStyle(.white.opacity(0.55))
                        Spacer()
                        if vm.isMonitoring {
                            Text("1s · \(history.count) pts")
                                .font(.system(size: 12, design: .rounded))
                                .foregroundStyle(.white.opacity(0.35))
                        }
                    }
                    if history.count >= 2 {
                        chart
                            .frame(height: 200)
                    } else {
                        VStack(spacing: 12) {
                            Image(systemName: "waveform.path.ecg")
                                .font(.system(size: 34))
                                .foregroundStyle(.white.opacity(0.22))
                            Text(vm.isMonitoring
                                 ? (vm.isMock ? UiStrings.shared.simulatingVitals : UiStrings.shared.waitingForData)
                                 : (vm.lastDeviceName.isEmpty ? UiStrings.shared.findYourSensor : UiStrings.shared.startMeasuringToRecord))
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

    private var statusText: String {
        if vm.isMonitoring { return vm.detail }
        return vm.lastDeviceName.isEmpty
            ? UiStrings.shared.notMonitoring
            : UiStrings.shared.nameNotMeasuring(name: vm.lastDeviceName)
    }

    @ViewBuilder
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
                if let bpm = value.as(Int.self) {
                    AxisValueLabel {
                        Text("\(bpm)")
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
        .onChange(of: vm.history.count) { _, _ in
            sweepCursor()
            if let ct = cursorTime {
                cursorPoint = nearestPoint(to: ct)
            }
        }
        .task {
            guard ProcessInfo.processInfo.environment["PULSE_CURSOR_TEST"] == "1" else { return }
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            setCursor(Date())
        }
    }

    @ChartContentBuilder
    private var chartMarks: some ChartContent {
        let lo = domain.lowerBound
        ForEach(segments, id: \.first!.t) { seg in
            ForEach(seg, id: \.t) { pt in
                let date = Date(timeIntervalSince1970: Double(pt.t) / 1000)
                AreaMark(
                    x: .value("Time", date),
                    yStart: .value("Base", lo),
                    yEnd: .value("BPM", pt.bpm)
                )
                .interpolationMethod(.catmullRom)
                .foregroundStyle(
                    LinearGradient(
                        colors: [.pink.opacity(0.45), .pink.opacity(0.06)],
                        startPoint: .top, endPoint: .bottom
                    )
                )
                LineMark(
                    x: .value("Time", date),
                    y: .value("BPM", pt.bpm)
                )
                .interpolationMethod(.catmullRom)
                .foregroundStyle(
                    LinearGradient(
                        colors: [Color(red: 1.0, green: 0.38, blue: 0.50), .pink.opacity(0.80)],
                        startPoint: .top, endPoint: .bottom
                    )
                )
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
            let yFrac = (Double(cp.bpm) - lo) / (hi - lo)
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
                    .fill(.pink)
                    .frame(width: 9, height: 9)
                    .position(x: x, y: y)

                Text("\(cp.bpm) \(UiStrings.shared.bpm)")
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
        if bpm <= 0 { return ("—", .white.opacity(0.4)) }
        switch bpm {
        case ..<60: return ("Rest", Color(red: 0.45, green: 0.80, blue: 1.0))
        case 60..<100: return ("Normal", .green.opacity(0.9))
        case 100..<120: return ("Elevated", .orange)
        case 120..<160: return ("Exercise", .pink)
        default: return ("Peak", .red)
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


