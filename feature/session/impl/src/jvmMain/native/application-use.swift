import AppKit
import ApplicationServices
import ScreenCaptureKit
import CryptoKit

// Line-delimited JSON over inherited pipes; capabilities live only in this process.
// ScreenCaptureKit captures the selected window even when another application covers it.
@available(macOS 14.0, *)
final class ApplicationAdapter {
    struct Window {
        let ax: AXUIElement
        let screen: SCWindow
        let pid: pid_t
        let launched: Date
    }
    struct Element {
        let ax: AXUIElement
        let window: String
        let fingerprint: String
        let actions: [String: String]
    }
    var windows: [String: Window] = [:]
    var elements: [String: Element] = [:]

    enum Failure: String, Error { case permission, stale, unsupported, capture, unavailable }

    func attribute(_ element: AXUIElement, _ name: String) -> CFTypeRef? {
        var result: CFTypeRef?
        return AXUIElementCopyAttributeValue(element, name as CFString, &result) == .success ? result : nil
    }
    func string(_ element: AXUIElement, _ name: String) -> String {
        String(describing: attribute(element, name) ?? "" as CFString).prefix(1000).description
    }
    func frame(_ element: AXUIElement) -> CGRect? {
        guard let position = attribute(element, kAXPositionAttribute), CFGetTypeID(position) == AXValueGetTypeID(),
              let size = attribute(element, kAXSizeAttribute), CFGetTypeID(size) == AXValueGetTypeID() else { return nil }
        var point = CGPoint.zero
        var dimensions = CGSize.zero
        guard AXValueGetValue(unsafeBitCast(position, to: AXValue.self), .cgPoint, &point),
              AXValueGetValue(unsafeBitCast(size, to: AXValue.self), .cgSize, &dimensions) else { return nil }
        return CGRect(origin: point, size: dimensions)
    }
    func secured(_ element: AXUIElement) -> Bool { string(element, kAXSubroleAttribute) == kAXSecureTextFieldSubrole }
    func fingerprint(_ element: AXUIElement) -> String {
        // Display strings are bounded, but identity must include the entire value (including suffix edits).
        let value = [kAXRoleAttribute, kAXTitleAttribute, kAXDescriptionAttribute, kAXValueAttribute, kAXEnabledAttribute]
            .map { String(describing: attribute(element, $0) ?? "" as CFString) }.joined(separator: "\u{0}")
        return SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    func actions(_ element: AXUIElement) -> [String: String] {
        if secured(element) || (attribute(element, kAXEnabledAttribute) as? Bool) == false { return [:] }
        var native: CFArray?
        guard AXUIElementCopyActionNames(element, &native) == .success else { return [:] }
        let names = native as? [String] ?? []
        var result: [String: String] = [:]
        for (tool, ax) in [("invoke", kAXPressAction), ("increment", kAXIncrementAction), ("decrement", kAXDecrementAction)] {
            if names.contains(ax) { result[tool] = ax }
        }
        var settable = DarwinBoolean(false)
        if AXUIElementIsAttributeSettable(element, kAXValueAttribute as CFString, &settable) == .success && settable.boolValue &&
            [kAXTextFieldRole, kAXTextAreaRole].contains(string(element, kAXRoleAttribute)) { result["set_value"] = kAXValueAttribute }
        return result
    }
    func validate(_ id: String) throws -> Window {
        guard let window = windows[id], let app = NSRunningApplication(processIdentifier: window.pid),
              !app.isTerminated, app.launchDate == window.launched,
              let current = attribute(AXUIElementCreateApplication(window.pid), kAXWindowsAttribute) as? [AXUIElement],
              current.contains(where: { CFEqual($0, window.ax) }) else { throw Failure.stale }
        return window
    }
    func list() async throws -> [String: Any] {
        windows.removeAll(); elements.removeAll()
        // Standard OS consent prompts; only the user can grant these permissions.
        if !AXIsProcessTrusted() {
            _ = AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue(): true] as CFDictionary)
        }
        if !CGPreflightScreenCaptureAccess() { _ = CGRequestScreenCaptureAccess() }
        guard AXIsProcessTrusted(), CGPreflightScreenCaptureAccess() else { throw Failure.permission }
        let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        var output: [[String: Any]] = []
        var seen = Set<pid_t>()
        for candidate in content.windows {
            guard let owner = candidate.owningApplication, owner.processID != getpid(), seen.insert(owner.processID).inserted,
                  let launched = NSRunningApplication(processIdentifier: owner.processID)?.launchDate else { continue }
            let app = AXUIElementCreateApplication(owner.processID)
            AXUIElementSetMessagingTimeout(app, 2)
            guard let available = attribute(app, kAXWindowsAttribute) as? [AXUIElement] else { continue }
            for ax in available {
                guard let bounds = frame(ax) else { continue }
                let title = string(ax, kAXTitleAttribute)
                // Public APIs do not expose an AX -> CG window id. Reject ambiguous matches, never guess.
                let matches = content.windows.filter { $0.owningApplication?.processID == owner.processID &&
                    $0.windowLayer == 0 && $0.title == title && abs($0.frame.minX - bounds.minX) < 2 &&
                    abs($0.frame.minY - bounds.minY) < 2 && abs($0.frame.width - bounds.width) < 2 && abs($0.frame.height - bounds.height) < 2 }
                guard matches.count == 1, bounds.width > 0, bounds.height > 0 else { continue }
                let id = UUID().uuidString
                windows[id] = Window(ax: ax, screen: matches[0], pid: owner.processID, launched: launched)
                output.append(["window_id": id, "application": owner.applicationName, "title": title])
            }
        }
        return ["windows": output, "selection": "Select the window requested by the user; ambiguous/inaccessible windows are omitted."]
    }
    func inspect(_ id: String) throws -> [String: Any] {
        let window = try validate(id)
        elements.removeAll()
        var output: [[String: Any]] = []
        var visited: [AXUIElement] = []
        func visit(_ ax: AXUIElement, _ parent: String?, _ depth: Int) {
            guard output.count < 500, depth < 24, !visited.contains(where: { CFEqual($0, ax) }) else { return }
            visited.append(ax)
            let key = UUID().uuidString
            let secure = secured(ax)
            let available = actions(ax)
            elements[key] = Element(ax: ax, window: id, fingerprint: secure ? "" : fingerprint(ax), actions: available)
            var node: [String: Any] = ["element_id": key, "role": string(ax, kAXRoleAttribute),
                "name": secure ? "Protected field" : string(ax, kAXTitleAttribute),
                "description": secure ? "" : string(ax, kAXDescriptionAttribute),
                "value": secure ? "" : string(ax, kAXValueAttribute), "actions": Array(available.keys).sorted()]
            if let parent { node["parent_id"] = parent }
            output.append(node)
            if !secure, let children = attribute(ax, kAXChildrenAttribute) as? [AXUIElement] {
                for child in children { visit(child, key, depth + 1) }
            }
        }
        visit(window.ax, nil, 0)
        return ["window_id": id, "elements": output, "bounded": true, "limit": 500]
    }
    func screenshot(_ id: String) async throws -> [String: Any] {
        let window = try validate(id)
        let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        guard let current = content.windows.first(where: { $0.windowID == window.screen.windowID && $0.owningApplication?.processID == window.pid }),
              let bounds = frame(window.ax), current.title == string(window.ax, kAXTitleAttribute),
              abs(current.frame.minX - bounds.minX) < 2, abs(current.frame.minY - bounds.minY) < 2,
              abs(current.frame.width - bounds.width) < 2, abs(current.frame.height - bounds.height) < 2,
              (attribute(window.ax, kAXMinimizedAttribute) as? Bool) != true else { throw Failure.capture }
        let scale = min(1, 1600 / max(current.frame.width, current.frame.height))
        let config = SCStreamConfiguration()
        config.width = max(1, Int(current.frame.width * scale)); config.height = max(1, Int(current.frame.height * scale))
        config.showsCursor = false
        let image = try await SCScreenshotManager.captureImage(contentFilter: SCContentFilter(desktopIndependentWindow: current), configuration: config)
        _ = try validate(id)
        guard let png = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) else { throw Failure.capture }
        return ["window_id": id, "png": png.base64EncodedString(), "width": image.width, "height": image.height]
    }
    func request(_ args: [String: Any]) async throws -> [String: Any] {
        guard let action = args["action"] as? String else { throw Failure.unsupported }
        // Internal preflight for native acceptance/diagnostics; never opens a consent prompt.
        if action == "permissions" {
            return ["accessibility": AXIsProcessTrusted(), "screen_capture": CGPreflightScreenCaptureAccess()]
        }
        if action == "windows" { return try await list() }
        guard let id = args["window_id"] as? String else { throw Failure.stale }
        if action == "inspect" { return try inspect(id) }
        if action == "screenshot" { return try await screenshot(id) }
        let window = try validate(id)
        guard let key = args["element_id"] as? String, let element = elements[key], element.window == id,
              let native = element.actions[action], actions(element.ax)[action] == native,
              !secured(element.ax), fingerprint(element.ax) == element.fingerprint,
              let parentWindow = attribute(element.ax, kAXWindowAttribute), CFEqual(parentWindow, window.ax) else { throw Failure.stale }
        elements.removeAll() // No repeat even when the AX provider times out after applying a change.
        let result: AXError
        if action == "set_value", let text = args["text"] as? String, text.count <= 10000 {
            result = AXUIElementSetAttributeValue(element.ax, native as CFString, text as CFString)
        } else if action != "set_value" {
            result = AXUIElementPerformAction(element.ax, native as CFString)
        } else { throw Failure.unsupported }
        // AX timeouts can follow an applied effect. Never describe that outcome as safely retryable.
        guard result == .success else { throw result == .actionUnsupported ? Failure.unsupported : Failure.unavailable }
        return ["performed": true]
    }
}

@main
struct Main {
    static func main() async {
        guard #available(macOS 14.0, *) else { print("{\"error\":\"unsupported\"}"); return }
        let adapter = ApplicationAdapter()
        while let line = readLine() {
            var response: [String: Any]
            do {
                guard line.utf8.count <= 65536, let bytes = line.data(using: .utf8),
                      let args = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { throw ApplicationAdapter.Failure.unsupported }
                response = try await adapter.request(args)
            } catch let failure as ApplicationAdapter.Failure { response = ["error": failure.rawValue] }
            catch { response = ["error": "unavailable"] }
            do {
                let bytes = try JSONSerialization.data(withJSONObject: response, options: [.sortedKeys])
                FileHandle.standardOutput.write(bytes); FileHandle.standardOutput.write(Data([10]))
            } catch { return }
        }
    }
}
