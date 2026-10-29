import AVFoundation
import CryptoKit
import Foundation
import Security
import UIKit
import Shared

/// All engine state is owned by the main queue. PCM conversion is serialized separately.
final class AppleServices: NSObject, IosNativeServices {
    private let recorder = PcmRecorder()
    // Preserve the original service identifier so updates retain encrypted device secrets.
    private let keychainService = "app.livelingo.relay"

    func startMicrophone(
        onPcm: @escaping (KotlinByteArray) -> KotlinUnit,
        onFailure: @escaping (String) -> KotlinUnit,
        onStarted: @escaping (String?) -> KotlinUnit
    ) {
        recorder.start(onPcm: { data in
            let bytes = KotlinByteArray(size: Int32(data.count))
            data.withUnsafeBytes { raw in
                let source = raw.bindMemory(to: UInt8.self)
                for index in source.indices {
                    bytes.set(index: Int32(index), value: Int8(bitPattern: source[index]))
                }
            }
            _ = onPcm(bytes)
        }, onFailure: { _ = onFailure($0) }, onStarted: { _ = onStarted($0) })
    }

    func stopMicrophone() { recorder.stop() }

    func readValue(key: String, secret: Bool) -> IosReadResult {
        do {
            if secret {
                var query = keychainQuery(key)
                query[kSecReturnData as String] = true
                query[kSecMatchLimit as String] = kSecMatchLimitOne
                var item: CFTypeRef?
                let status = SecItemCopyMatching(query as CFDictionary, &item)
                if status == errSecItemNotFound { return IosReadResult(value: nil, error: nil) }
                guard status == errSecSuccess, let data = item as? Data,
                      let value = String(data: data, encoding: .utf8) else {
                    return IosReadResult(value: nil, error: "無法讀取安全儲存區（\(status)）。請解鎖裝置後重試。")
                }
                return IosReadResult(value: value, error: nil)
            }
            let file = try storageFile(key)
            guard FileManager.default.fileExists(atPath: file.path) else {
                return IosReadResult(value: nil, error: nil)
            }
            return IosReadResult(value: try String(contentsOf: file, encoding: .utf8), error: nil)
        } catch {
            return IosReadResult(value: nil, error: "無法讀取本機資料。請確認可用空間並重試。")
        }
    }

    func writeValue(key: String, value: String?, secret: Bool) -> String? {
        if secret {
            let query = keychainQuery(key)
            guard let value = value else {
                let status = SecItemDelete(query as CFDictionary)
                return status == errSecSuccess || status == errSecItemNotFound ? nil : "無法刪除安全儲存資料（\(status)）。"
            }
            let data = Data(value.utf8)
            let updates: [String: Any] = [kSecValueData as String: data]
            var status = SecItemUpdate(query as CFDictionary, updates as CFDictionary)
            if status == errSecItemNotFound {
                var attributes = query
                attributes[kSecValueData as String] = data
                attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
                status = SecItemAdd(attributes as CFDictionary, nil)
            }
            return status == errSecSuccess ? nil : "無法寫入安全儲存區（\(status)）。請解鎖裝置後重試。"
        }
        do {
            let file = try storageFile(key)
            if let value = value {
                try Data(value.utf8).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            } else if FileManager.default.fileExists(atPath: file.path) {
                try FileManager.default.removeItem(at: file)
            }
            return nil
        } catch {
            return "本機儲存失敗。請確認可用空間後重試。"
        }
    }

    func exportText(fileName: String, mimeType: String, text: String) -> String? {
        guard Thread.isMainThread else { return "請從畫面重新執行匯出。" }
        guard let presenter = currentPresenter() else { return "目前無法顯示分享畫面，請稍後重試。" }
        do {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("Export-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let safeName = URL(fileURLWithPath: fileName).lastPathComponent
            guard !safeName.isEmpty, safeName != ".", safeName != ".." else { return "匯出檔名無效。" }
            let file = directory.appendingPathComponent(safeName)
            try Data(text.utf8).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            let sheet = UIActivityViewController(activityItems: [file], applicationActivities: nil)
            sheet.completionWithItemsHandler = { _, _, _, _ in
                try? FileManager.default.removeItem(at: directory)
            }
            // Required for UIActivityViewController on iPad.
            if let popover = sheet.popoverPresentationController {
                popover.sourceView = presenter.view
                popover.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 1, height: 1)
                popover.permittedArrowDirections = []
            }
            presenter.present(sheet, animated: true)
            return nil
        } catch { return "無法建立匯出檔案。請確認可用空間後重試。" }
    }

    private func keychainQuery(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: keychainService,
         kSecAttrAccount as String: key]
    }

    private func storageFile(_ key: String) throws -> URL {
        var directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("LiveLingo", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        let name = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(name + ".json")
    }

    private func currentPresenter() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let window = scenes.filter({ $0.activationState == .foregroundActive }).flatMap(\.windows).first(where: \.isKeyWindow) else { return nil }
        var controller = window.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
    }
}

private final class PcmRecorder {
    private var engine: AVAudioEngine?
    private var tapInstalled = false
    private var onFailure: ((String) -> Void)?
    private var onPcm: ((Data) -> Void)?
    private var observers: [NSObjectProtocol] = []
    private var generation: UInt64 = 0
    private var starting = false
    private var running = false
    private let queue = DispatchQueue(label: "app.livelingo.pcm", qos: .userInitiated)
    private let lock = NSLock()
    private var accepting = false
    private var pendingCount = 0
    private var overflowReported = false
    // Only accessed on queue; keeps <=99ms of valid samples between conversions.
    private var pending = Data()
    private var converter: AVAudioConverter?
    private var target: AVAudioFormat?

    init() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.fail("App 已進入背景，翻譯已停止；返回後可重新開始。")
        })
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
            let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            if raw == AVAudioSession.InterruptionType.began.rawValue {
                self?.fail("麥克風被來電或其他 App 中斷，翻譯已停止。")
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] notification in
            let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            if raw == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue || raw == AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue {
                self?.fail("音訊裝置已變更，請重新啟動翻譯。")
            }
        })
    }

    deinit {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        engine?.stop()
        if tapInstalled { engine?.inputNode.removeTap(onBus: 0) }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    func start(onPcm: @escaping (Data) -> Void, onFailure: @escaping (String) -> Void, onStarted: @escaping (String?) -> Void) {
        onMain { [weak self] in
            guard let self = self else { onStarted("錄音元件已關閉。"); return }
            guard !self.running, !self.starting else { onStarted("麥克風正在使用中。"); return }
            self.starting = true
            self.onFailure = onFailure
            self.generation &+= 1
            let attempt = self.generation
            AVAudioSession.sharedInstance().requestRecordPermission { [weak self] allowed in
                DispatchQueue.main.async {
                    guard let self = self, attempt == self.generation, self.starting else {
                        onStarted("錄音啟動已取消。")
                        return
                    }
                    guard allowed else {
                        self.starting = false
                        self.onFailure = nil
                        onStarted("尚未取得麥克風權限。請至 iOS 設定 → 即時譯 → 麥克風開啟權限。")
                        return
                    }
                    self.begin(onPcm: onPcm, onFailure: onFailure, onStarted: onStarted)
                }
            }
        }
    }

    private func begin(onPcm: @escaping (Data) -> Void, onFailure: @escaping (String) -> Void, onStarted: @escaping (String?) -> Void) {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: [.allowBluetooth])
            try session.setPreferredSampleRate(24_000)
            try session.setPreferredIOBufferDuration(0.02)
            try session.setActive(true)
            let engine = AVAudioEngine()
            self.engine = engine
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0,
                  let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 24_000, channels: 1, interleaved: true),
                  let converter = AVAudioConverter(from: format, to: target) else {
                throw RecorderError.invalidFormat
            }
            queue.sync {
                self.pending.removeAll(keepingCapacity: true)
                self.target = target
                self.converter = converter
            }
            self.onPcm = onPcm
            self.onFailure = onFailure
            lock.lock()
            accepting = true
            pendingCount = 0
            overflowReported = false
            lock.unlock()
            input.installTap(onBus: 0, bufferSize: 1_024, format: format) { [weak self] buffer, _ in
                self?.enqueue(buffer)
            }
            tapInstalled = true
            engine.prepare()
            try engine.start()
            starting = false
            running = true
            onStarted(nil)
        } catch {
            stopOnMain(flush: false)
            onStarted("無法啟動麥克風，請確認未被其他 App 佔用後重試。")
        }
    }

    private func enqueue(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        guard accepting else { lock.unlock(); return }
        guard pendingCount < 12 else {
            let shouldReport = !overflowReported
            overflowReported = true
            lock.unlock()
            if shouldReport { DispatchQueue.main.async { [weak self] in self?.fail("音訊處理來不及完成，已停止翻譯以避免遺漏。") } }
            return
        }
        pendingCount += 1
        lock.unlock()
        guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength) else {
            lock.lock(); pendingCount -= 1; lock.unlock()
            DispatchQueue.main.async { [weak self] in self?.fail("音訊記憶體不足，翻譯已停止。") }
            return
        }
        copy.frameLength = buffer.frameLength
        let source = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: buffer.audioBufferList))
        let destination = UnsafeMutableAudioBufferListPointer(copy.mutableAudioBufferList)
        for index in source.indices {
            guard let from = source[index].mData, let to = destination[index].mData else { continue }
            memcpy(to, from, Int(source[index].mDataByteSize))
        }
        queue.async { [weak self] in
            guard let self = self else { return }
            defer { self.lock.lock(); self.pendingCount -= 1; self.lock.unlock() }
            self.convert(copy)
        }
    }

    private func convert(_ input: AVAudioPCMBuffer) {
        guard let converter = converter, let target = target else { return }
        let capacity = AVAudioFrameCount(ceil(Double(input.frameLength) * target.sampleRate / input.format.sampleRate)) + 32
        guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else {
            DispatchQueue.main.async { [weak self] in self?.fail("音訊轉換記憶體不足，翻譯已停止。") }
            return
        }
        var supplied = false
        var conversionError: NSError?
        let status = converter.convert(to: output, error: &conversionError) { _, inputStatus in
            // AVAudioConverter may request the input more than once. Reusing it would duplicate speech.
            if supplied { inputStatus.pointee = .noDataNow; return nil }
            supplied = true
            inputStatus.pointee = .haveData
            return input
        }
        guard status != .error, conversionError == nil else {
            DispatchQueue.main.async { [weak self] in self?.fail("麥克風音訊格式轉換失敗，翻譯已停止。") }
            return
        }
        appendOutput(output)
    }

    private func appendOutput(_ output: AVAudioPCMBuffer) {
        let length = Int(output.frameLength) * MemoryLayout<Int16>.size
        if length > 0, let pointer = output.audioBufferList.pointee.mBuffers.mData {
            // Apple arm64/x86_64 are little endian; the converter output is interleaved mono Int16.
            pending.append(pointer.assumingMemoryBound(to: UInt8.self), count: length)
        }
        // 24,000 samples/s * 2 bytes/sample * 100 ms = 4,800 bytes.
        while pending.count >= 4_800 {
            let chunk = Data(pending.prefix(4_800))
            pending.removeFirst(4_800)
            onPcm?(chunk)
        }
    }

    private func drainConverter() {
        guard let converter = converter, let target = target else { return }
        // Signal end-of-stream so the sample-rate converter releases its filter delay.
        // The bound also protects stop() if an unexpected converter never reports completion.
        for _ in 0..<16 {
            guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: 4_096) else { return }
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { _, inputStatus in
                inputStatus.pointee = .endOfStream
                return nil
            }
            guard status != .error, error == nil else { return }
            appendOutput(output)
            if status == .endOfStream || output.frameLength == 0 { return }
        }
    }

    func stop() { onMain { [weak self] in self?.stopOnMain(flush: true) } }

    private func stopOnMain(flush: Bool) {
        generation &+= 1
        starting = false
        running = false
        lock.lock(); accepting = false; lock.unlock()
        engine?.stop()
        if tapInstalled { engine?.inputNode.removeTap(onBus: 0); tapInstalled = false }
        // Drain captured samples before stop returns, so the shared controller can then enqueue Stop.
        queue.sync {
            if flush { drainConverter() }
            if flush, !pending.isEmpty { onPcm?(pending) }
            pending.removeAll(keepingCapacity: false)
            converter = nil
            target = nil
            onPcm = nil
        }
        engine = nil
        onFailure = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func fail(_ message: String) {
        guard running || starting else { return }
        let callback = onFailure
        stopOnMain(flush: false)
        callback?(message)
    }

    private func onMain(_ action: @escaping () -> Void) {
        if Thread.isMainThread { action() } else { DispatchQueue.main.sync(execute: action) }
    }

    private enum RecorderError: Error { case invalidFormat }
}
