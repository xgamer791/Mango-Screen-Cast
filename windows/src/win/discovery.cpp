#include "discovery.h"

#include <winsock2.h>
#include <windows.h>
#include <windns.h>
#include <ws2tcpip.h>

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <mutex>
#include <set>

#include "core/json.h"
#include "core/protocol.h"
#include "log.h"
#include "net.h"
#include "util.h"

namespace mango {

namespace {
const wchar_t kService[] = L"_mangocast._tcp.local";
const char kSuffix[] = "._mangocast._tcp.local";
}  // namespace

std::string ReceiverInfo::id() const {
    auto it = txt.find("id");
    return it == txt.end() ? std::string() : it->second;
}

std::string ReceiverInfo::model() const {
    auto it = txt.find("model");
    return it == txt.end() ? std::string() : it->second;
}

std::string ReceiverInfo::target() const {
    if (address.find(':') != std::string::npos) return "[" + address + "]:" + std::to_string(port);
    return address + ":" + std::to_string(port);
}

std::string ReceiverInfo::toJson() const {
    auto num = [&](const char* key) -> std::string {
        auto it = txt.find(key);
        if (it == txt.end()) return "null";
        char* end = nullptr;
        long v = std::strtol(it->second.c_str(), &end, 10);
        return end && *end == '\0' && !it->second.empty() ? std::to_string(v) : "null";
    };
    std::string codecs = "[";
    auto c = txt.find("codecs");
    if (c != txt.end()) {
        size_t start = 0;
        bool first = true;
        while (start <= c->second.size()) {
            size_t comma = c->second.find(',', start);
            std::string item = c->second.substr(start, comma == std::string::npos ? std::string::npos : comma - start);
            if (!item.empty()) {
                codecs += (first ? "" : ", ") + json::quote(item);
                first = false;
            }
            if (comma == std::string::npos) break;
            start = comma + 1;
        }
    }
    codecs += "]";
    auto audio = txt.find("audio");
    std::string s = "{\"name\": " + json::quote(name) + ", \"instance\": " + json::quote(instance) +
                    ", \"host\": " + json::quote(host) + ", \"address\": " + json::quote(address) +
                    ", \"port\": " + std::to_string(port) + ", \"id\": " + json::quote(id()) +
                    ", \"model\": " + json::quote(model()) + ", \"v\": " + num("v") + ", \"w\": " + num("w") +
                    ", \"h\": " + num("h") + ", \"fps\": " + num("fps") + ", \"codecs\": " + codecs +
                    ", \"audio\": " + (audio == txt.end() ? "null" : audio->second == "1" ? "true" : "false") +
                    ", \"txt\": {";
    bool first = true;
    for (const auto& kv : txt) {
        s += (first ? "" : ", ") + json::quote(kv.first) + ": " + json::quote(kv.second);
        first = false;
    }
    s += "}}";
    return s;
}

std::string displayNameOf(const std::string& instance) {
    std::string label = instance;
    if (label.size() > sizeof(kSuffix) - 1) {
        std::string tail = label.substr(label.size() - (sizeof(kSuffix) - 1));
        std::string lowerTail = tail;
        std::transform(lowerTail.begin(), lowerTail.end(), lowerTail.begin(),
                       [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
        if (lowerTail == kSuffix) label.resize(label.size() - tail.size());
    }
    if (!label.empty() && label.back() == '.') label.pop_back();
    // DNS presentation escapes: \. \\ and \DDD (decimal byte).
    std::string out;
    for (size_t i = 0; i < label.size(); i++) {
        if (label[i] == '\\' && i + 1 < label.size()) {
            if (i + 3 < label.size() && std::isdigit(static_cast<unsigned char>(label[i + 1])) &&
                std::isdigit(static_cast<unsigned char>(label[i + 2])) &&
                std::isdigit(static_cast<unsigned char>(label[i + 3]))) {
                out += static_cast<char>(std::atoi(label.substr(i + 1, 3).c_str()));
                i += 3;
            } else {
                out += label[++i];
            }
        } else {
            out += label[i];
        }
    }
    return out;
}

struct Browser::State : std::enable_shared_from_this<Browser::State> {
    std::mutex mutex;
    bool running = false;
    DNS_SERVICE_CANCEL browseCancel{};
    std::map<std::wstring, DNS_SERVICE_CANCEL> resolving;  // instance -> its resolve
    std::map<std::wstring, ReceiverInfo> found;
    std::set<std::wstring> seen;
    Changed changed;
};

namespace {

struct ResolveContext {
    std::weak_ptr<Browser::State> state;
    std::wstring instance;
};

VOID WINAPI onResolved(DWORD status, PVOID context, PDNS_SERVICE_INSTANCE inst) {
    std::unique_ptr<ResolveContext> ctx(static_cast<ResolveContext*>(context));
    auto state = ctx->state.lock();
    if (!state) {
        if (inst) DnsServiceFreeInstance(inst);
        return;
    }
    Browser::Changed changed;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        state->resolving.erase(ctx->instance);
        if (status == ERROR_SUCCESS && inst) {
            ReceiverInfo r;
            r.instance = narrow(inst->pszInstanceName ? inst->pszInstanceName : ctx->instance.c_str());
            r.name = displayNameOf(r.instance);
            r.host = narrow(inst->pszHostName);
            if (!r.host.empty() && r.host.back() == '.') r.host.pop_back();
            r.port = inst->wPort;
            char buf[INET6_ADDRSTRLEN] = {};
            if (inst->ip4Address) {
                in_addr a;
                a.s_addr = *inst->ip4Address;
                inet_ntop(AF_INET, &a, buf, sizeof buf);
                r.address = buf;
            } else if (inst->ip6Address) {
                inet_ntop(AF_INET6, inst->ip6Address->IP6Byte, buf, sizeof buf);
                r.address = buf;
                if (inst->dwInterfaceIndex && r.address.rfind("fe80", 0) == 0)
                    r.address += "%" + std::to_string(inst->dwInterfaceIndex);
            } else {
                r.address = r.host;
            }
            for (DWORD i = 0; i < inst->dwPropertyCount; i++) {
                std::string key = narrow(inst->keys[i] ? inst->keys[i] : L"");
                std::string value = narrow(inst->values[i] ? inst->values[i] : L"");
                if (!key.empty()) r.txt[key] = value;
            }
            if (state->running) {
                state->found[ctx->instance] = r;
                changed = state->changed;
            }
        } else if (status != ERROR_SUCCESS && status != ERROR_CANCELLED) {
            LOG("Couldn't resolve %s: %s", narrow(ctx->instance).c_str(), win32ErrorText(status).c_str());
            state->seen.erase(ctx->instance);  // try again when it shows up again
        }
    }
    if (inst) DnsServiceFreeInstance(inst);
    if (changed) changed();
}

void resolve(const std::shared_ptr<Browser::State>& state, const std::wstring& instance) {
    // Called without state->mutex: the callback may come before this returns.
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        state->resolving[instance] = DNS_SERVICE_CANCEL{};  // placeholder, until we have the handle
    }
    auto* ctx = new ResolveContext{state, instance};
    DNS_SERVICE_RESOLVE_REQUEST req{};
    req.Version = DNS_QUERY_REQUEST_VERSION1;
    req.InterfaceIndex = 0;
    req.QueryName = const_cast<PWSTR>(ctx->instance.c_str());
    req.pResolveCompletionCallback = onResolved;
    req.pQueryContext = ctx;
    DNS_SERVICE_CANCEL cancel{};
    DNS_STATUS st = DnsServiceResolve(&req, &cancel);
    std::lock_guard<std::mutex> lock(state->mutex);
    if (st != DNS_REQUEST_PENDING) {
        LOG("Couldn't start resolving %s: %s", narrow(instance).c_str(), win32ErrorText(st).c_str());
        state->resolving.erase(instance);
        state->seen.erase(instance);
        delete ctx;
        return;
    }
    auto it = state->resolving.find(instance);
    if (it != state->resolving.end()) it->second = cancel;  // else it has already finished
}

VOID WINAPI onBrowse(DWORD status, PVOID context, PDNS_RECORD records) {
    auto* weak = static_cast<std::weak_ptr<Browser::State>*>(context);
    auto state = weak->lock();
    if (status == ERROR_CANCELLED) {
        if (records) DnsRecordListFree(records, DnsFreeRecordList);
        delete weak;  // the browse is over: nothing calls back after this
        return;
    }
    if (!state) {
        if (records) DnsRecordListFree(records, DnsFreeRecordList);
        return;
    }
    Browser::Changed changed;
    std::vector<std::wstring> fresh;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        for (PDNS_RECORD r = records; r && state->running; r = r->pNext) {
            if (r->wType != DNS_TYPE_PTR || !r->Data.PTR.pNameHost) continue;
            std::wstring instance = r->Data.PTR.pNameHost;
            if (r->dwTtl == 0) {
                // A goodbye: the receiver left.
                if (state->found.erase(instance)) changed = state->changed;
                state->seen.erase(instance);
                continue;
            }
            if (state->seen.insert(instance).second) fresh.push_back(instance);
        }
    }
    if (records) DnsRecordListFree(records, DnsFreeRecordList);
    for (const auto& instance : fresh) resolve(state, instance);
    if (changed) changed();
}

}  // namespace

Browser::Browser() : state_(std::make_shared<State>()) {}

Browser::~Browser() { stop(); }

bool Browser::start(Changed changed, std::string* error) {
    stop();
    state_ = std::make_shared<State>();
    state_->changed = std::move(changed);
    state_->running = true;
    auto* weak = new std::weak_ptr<State>(state_);
    DNS_SERVICE_BROWSE_REQUEST req{};
    req.Version = DNS_QUERY_REQUEST_VERSION1;
    req.InterfaceIndex = 0;
    req.QueryName = kService;
    req.pBrowseCallback = onBrowse;
    req.pQueryContext = weak;
    DNS_STATUS st = DnsServiceBrowse(&req, &state_->browseCancel);
    if (st != DNS_REQUEST_PENDING) {
        delete weak;
        std::lock_guard<std::mutex> lock(state_->mutex);
        state_->running = false;
        if (error) *error = "Couldn't look for receivers: " + win32ErrorText(st);
        return false;
    }
    return true;
}

void Browser::stop() {
    if (!state_) return;
    std::map<std::wstring, DNS_SERVICE_CANCEL> resolving;
    bool wasRunning;
    {
        std::lock_guard<std::mutex> lock(state_->mutex);
        wasRunning = state_->running;
        state_->running = false;
        resolving.swap(state_->resolving);
    }
    if (wasRunning) DnsServiceBrowseCancel(&state_->browseCancel);
    for (auto& kv : resolving) {
        if (kv.second.reserved) DnsServiceResolveCancel(&kv.second);
    }
}

std::vector<ReceiverInfo> Browser::receivers() const {
    std::vector<ReceiverInfo> list;
    if (!state_) return list;
    std::lock_guard<std::mutex> lock(state_->mutex);
    for (const auto& kv : state_->found) list.push_back(kv.second);
    std::sort(list.begin(), list.end(), [](const ReceiverInfo& a, const ReceiverInfo& b) { return a.name < b.name; });
    return list;
}

std::vector<ReceiverInfo> Browser::browseFor(int waitMs, std::string* error) {
    Browser b;
    if (!b.start(nullptr, error)) return {};
    Sleep(static_cast<DWORD>(waitMs > 0 ? waitMs : 0));
    // Give names found near the end a moment to resolve.
    for (int i = 0; i < 30; i++) {
        {
            std::lock_guard<std::mutex> lock(b.state_->mutex);
            if (b.state_->resolving.empty()) break;
        }
        Sleep(50);
    }
    auto list = b.receivers();
    b.stop();
    return list;
}

}  // namespace mango
