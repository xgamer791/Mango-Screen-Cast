// Finding Mango receivers: DNS-SD over mDNS for _mangocast._tcp.local with
// Windows' own resolver (DnsServiceBrowse / DnsServiceResolve, Windows 10
// 1903 and newer).
#pragma once

#include <functional>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace mango {

struct ReceiverInfo {
    std::string instance;  // full DNS-SD name: "Living Room._mangocast._tcp.local"
    std::string name;      // "Living Room"
    std::string host;      // its host name
    std::string address;   // IPv4 (or IPv6) to connect to
    int port = 0;
    std::map<std::string, std::string> txt;

    std::string id() const;
    std::string model() const;
    std::string target() const;  // "address:port", for Connection
    std::string toJson() const;  // one line, for --list
};

// Instance name to the name people see: "Living\032Room._mangocast._tcp.local" -> "Living Room".
std::string displayNameOf(const std::string& instance);

class Browser {
public:
    using Changed = std::function<void()>;

    Browser();
    ~Browser();
    Browser(const Browser&) = delete;
    Browser& operator=(const Browser&) = delete;

    // Starts looking (again); `changed` runs on a worker thread when the list changes.
    bool start(Changed changed, std::string* error);
    void stop();
    std::vector<ReceiverInfo> receivers() const;

    // Looks for waitMs, then gives what it found.
    static std::vector<ReceiverInfo> browseFor(int waitMs, std::string* error);

    struct State;

private:
    std::shared_ptr<State> state_;
};

}  // namespace mango
