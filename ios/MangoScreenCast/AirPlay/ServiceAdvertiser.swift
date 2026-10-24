import Foundation

/// Publishes the AirPlay server's Bonjour services so Macs, iPhones and
/// iPads list this device in Screen Mirroring.
///
/// UxPlay builds the service records; the dns_sd shim hands them over here
/// (see mango_bridge.h) and they're published with NetService. Main thread.
final class ServiceAdvertiser: NSObject, NetServiceDelegate {
    var onFailure: ((String) -> Void)?
    var onPublished: ((_ type: String, _ name: String) -> Void)?

    private var services: [String: NetService] = [:]
    private var records: [ObjectIdentifier: Data] = [:]

    /// `type` is e.g. "_airplay._tcp"; `txt` is the wire-format TXT record.
    func register(type: String, name: String, port: Int, txt: Data) {
        unregister(type: type)
        let service = NetService(domain: "local.", type: type.hasSuffix(".") ? type : type + ".", name: name,
                                 port: Int32(port))
        service.delegate = self
        records[ObjectIdentifier(service)] = txt
        service.setTXTRecord(txt)
        service.publish()
        services[type] = service
    }

    func unregister(type: String) {
        guard let service = services.removeValue(forKey: type) else { return }
        records[ObjectIdentifier(service)] = nil
        service.delegate = nil
        service.stop()
    }

    func unregisterAll() {
        for type in Array(services.keys) { unregister(type: type) }
    }

    func netServiceDidPublish(_ sender: NetService) {
        // Some systems drop a TXT record set before publishing; set it again.
        if let txt = records[ObjectIdentifier(sender)], sender.txtRecordData() != txt {
            sender.setTXTRecord(txt)
        }
        onPublished?(sender.type, sender.name)
    }

    func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        let code = errorDict[NetService.errorCode]?.intValue ?? 0
        if code == NetService.ErrorCode.collisionError.rawValue {
            onFailure?("another device on the network already uses the name “\(sender.name)”")
        } else {
            onFailure?("error \(code) for \(sender.type); check that Local Network access is on for Mango "
                + "(Settings → Privacy & Security → Local Network)")
        }
    }
}
