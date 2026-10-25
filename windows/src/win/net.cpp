#include "net.h"

#include <ws2tcpip.h>

#include <cstring>

#include "log.h"
#include "util.h"

namespace mango {

namespace {
constexpr double kSilenceMs = 8000;    // nothing from the receiver this long: it's gone
constexpr double kKeepaliveMs = 1000;  // we send something at least this often
constexpr int kSendBuffer = 256 * 1024;
}  // namespace

bool startWinsock(std::string* why) {
    static std::once_flag once;
    static int result = -1;
    std::call_once(once, [] {
        WSADATA data;
        result = WSAStartup(MAKEWORD(2, 2), &data);
    });
    if (result != 0) {
        if (why) *why = "Windows networking didn't start: " + win32ErrorText(static_cast<unsigned long>(result));
        return false;
    }
    return true;
}

Connection::~Connection() { close(); }

static std::string addressText(const sockaddr* sa) {
    char host[NI_MAXHOST] = {};
    char serv[NI_MAXSERV] = {};
    socklen_t len = sa->sa_family == AF_INET6 ? sizeof(sockaddr_in6) : sizeof(sockaddr_in);
    if (getnameinfo(sa, len, host, sizeof host, serv, sizeof serv, NI_NUMERICHOST | NI_NUMERICSERV) != 0) return "?";
    return sa->sa_family == AF_INET6 ? "[" + std::string(host) + "]:" + serv : std::string(host) + ":" + serv;
}

bool Connection::connect(const std::string& host, int port, int timeoutMs, std::string* error, bool* notFound) {
    if (notFound) *notFound = false;
    if (!startWinsock(error)) return false;
    addrinfoW hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    addrinfoW* list = nullptr;
    std::wstring whost = widen(host), wport = std::to_wstring(port);
    int rc = GetAddrInfoW(whost.c_str(), wport.c_str(), &hints, &list);
    if (rc != 0 || !list) {
        if (notFound) *notFound = true;
        if (error) *error = "Couldn't find " + host + " on the network (" + win32ErrorText(static_cast<unsigned long>(rc)) + ")";
        return false;
    }
    std::string lastError = "no addresses";
    for (addrinfoW* ai = list; ai; ai = ai->ai_next) {
        SOCKET s = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (s == INVALID_SOCKET) continue;
        u_long nonBlocking = 1;
        ioctlsocket(s, FIONBIO, &nonBlocking);
        int r = ::connect(s, ai->ai_addr, static_cast<int>(ai->ai_addrlen));
        bool ok = r == 0;
        if (!ok && WSAGetLastError() == WSAEWOULDBLOCK) {
            fd_set wr, ex;
            FD_ZERO(&wr);
            FD_ZERO(&ex);
            FD_SET(s, &wr);
            FD_SET(s, &ex);
            timeval tv{timeoutMs / 1000, (timeoutMs % 1000) * 1000};
            r = select(0, nullptr, &wr, &ex, &tv);
            if (r > 0 && FD_ISSET(s, &wr)) {
                ok = true;
            } else if (r == 0) {
                lastError = "no answer from " + addressText(ai->ai_addr) + " (is Mango Screen Cast open on the TV?)";
            } else {
                int soErr = 0;
                int len = sizeof soErr;
                getsockopt(s, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&soErr), &len);
                lastError = addressText(ai->ai_addr) + " refused the connection (" +
                            win32ErrorText(static_cast<unsigned long>(soErr)) + ")";
            }
        } else if (!ok) {
            lastError = addressText(ai->ai_addr) + ": " + win32ErrorText(static_cast<unsigned long>(WSAGetLastError()));
        }
        if (!ok) {
            closesocket(s);
            continue;
        }
        nonBlocking = 0;
        ioctlsocket(s, FIONBIO, &nonBlocking);
        BOOL noDelay = TRUE;
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&noDelay), sizeof noDelay);
        // A modest socket buffer, so a backlog shows up in our own queue
        // (where we can drop pictures) rather than hiding in the kernel's.
        int sndbuf = kSendBuffer;
        setsockopt(s, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&sndbuf), sizeof sndbuf);
        DWORD sendTimeout = static_cast<DWORD>(kSilenceMs);
        setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&sendTimeout), sizeof sendTimeout);
        sock_ = s;
        peer_ = addressText(ai->ai_addr);
        break;
    }
    FreeAddrInfoW(list);
    if (sock_ == INVALID_SOCKET) {
        if (error) *error = "Couldn't connect to " + host + ": " + lastError;
        return false;
    }
    lastHeardMs_ = lastSentMs_ = nowMs();
    originUs_ = qpcNowUs();
    LOG("Connected to %s", peer_.c_str());
    return true;
}

bool Connection::sendAll(const uint8_t* data, size_t size) {
    while (size > 0) {
        int chunk = static_cast<int>(size > (1u << 30) ? (1u << 30) : size);
        int n = ::send(sock_, reinterpret_cast<const char*>(data), chunk, 0);
        if (n <= 0) return false;
        data += n;
        size -= static_cast<size_t>(n);
    }
    lastSentMs_ = nowMs();
    return true;
}

bool Connection::recvAll(uint8_t* data, size_t size, int timeoutMs, Read* why) {
    double deadline = nowMs() + timeoutMs;
    while (size > 0) {
        if (stopReader_) {
            *why = Read::Closed;
            return false;
        }
        double left = deadline - nowMs();
        if (left <= 0) {
            *why = Read::Timeout;
            return false;
        }
        fd_set rd;
        FD_ZERO(&rd);
        FD_SET(sock_, &rd);
        int waitMs = left > 250 ? 250 : static_cast<int>(left) + 1;
        timeval tv{0, waitMs * 1000};
        int r = select(0, &rd, nullptr, nullptr, &tv);
        if (r < 0) {
            *why = Read::Error;
            return false;
        }
        if (r == 0) continue;
        int chunk = static_cast<int>(size > (1u << 20) ? (1u << 20) : size);
        int n = ::recv(sock_, reinterpret_cast<char*>(data), chunk, 0);
        if (n == 0) {
            *why = Read::Closed;
            return false;
        }
        if (n < 0) {
            *why = Read::Error;
            return false;
        }
        data += n;
        size -= static_cast<size_t>(n);
        lastHeardMs_ = nowMs();
        // Part of a message is in: the rest must be read whatever the
        // caller's timeout, or the stream would lose its framing.
        deadline = nowMs() + kSilenceMs;
    }
    return true;
}

bool Connection::sendNow(MsgType type, const std::string& payload, std::string* error) {
    std::vector<uint8_t> buf(kHeaderSize + payload.size());
    writeHeader(buf.data(), type, static_cast<uint32_t>(payload.size()));
    std::memcpy(buf.data() + kHeaderSize, payload.data(), payload.size());
    if (!sendAll(buf.data(), buf.size())) {
        if (error) *error = "Couldn't send to the receiver: " + win32ErrorText(static_cast<unsigned long>(WSAGetLastError()));
        return false;
    }
    return true;
}

Connection::Read Connection::readMessage(MsgType& type, std::vector<uint8_t>& payload, int timeoutMs,
                                         std::string* error) {
    uint8_t header[kHeaderSize];
    Read why = Read::Ok;
    if (!recvAll(header, kHeaderSize, timeoutMs, &why)) {
        if (error) {
            if (why == Read::Timeout) *error = "The receiver didn't answer";
            else if (why == Read::Closed) *error = "The receiver closed the connection";
            else *error = "Connection error: " + win32ErrorText(static_cast<unsigned long>(WSAGetLastError()));
        }
        return why;
    }
    type = static_cast<MsgType>(header[0]);
    uint32_t length = readU32(header + 1);
    if (length > kMaxPayload) {
        if (error) *error = "The receiver sent a message too big to be right (" + std::to_string(length) + " bytes)";
        return Read::Error;
    }
    payload.resize(length);
    // Once a message has started, the rest of it should follow promptly.
    if (length && !recvAll(payload.data(), length, static_cast<int>(kSilenceMs), &why)) {
        if (error) *error = "The connection broke in the middle of a message";
        return why == Read::Timeout ? Read::Error : why;
    }
    return Read::Ok;
}

void Connection::start(Handler handler) {
    handler_ = std::move(handler);
    stopWriter_ = false;
    stopReader_ = false;
    lastHeardMs_ = nowMs();
    reader_ = std::thread([this] { readerLoop(); });
    writer_ = std::thread([this] { writerLoop(); });
}

void Connection::send(MsgType type, const uint8_t* a, size_t na, const uint8_t* b, size_t nb) {
    Item item;
    item.bytes.resize(kHeaderSize + na + nb);
    writeHeader(item.bytes.data(), type, static_cast<uint32_t>(na + nb));
    if (na) std::memcpy(item.bytes.data() + kHeaderSize, a, na);
    if (nb) std::memcpy(item.bytes.data() + kHeaderSize + na, b, nb);
    item.queuedAt = nowMs();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (ended_ || stopWriter_) return;
        queuedBytes_ += item.bytes.size();
        queue_.push_back(std::move(item));
    }
    cv_.notify_one();
}

void Connection::sendText(MsgType type, const std::string& text) {
    send(type, reinterpret_cast<const uint8_t*>(text.data()), text.size());
}

size_t Connection::queuedBytes() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return queuedBytes_;
}

double Connection::oldestQueuedMs() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return queue_.empty() ? 0.0 : nowMs() - queue_.front().queuedAt;
}

void Connection::end(const std::string& reason) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ended_) {
        endReason_ = reason;
        ended_ = true;
        LOG("Connection ended: %s", reason.c_str());
    }
    cv_.notify_all();
}

bool Connection::ended(std::string* reason) const {
    if (!ended_) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    if (reason) *reason = endReason_;
    return true;
}

bool Connection::receiverSaidBye(std::string* reason) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (byeReceived_ && reason) *reason = byeReason_;
    return byeReceived_;
}

void Connection::writerLoop() {
    std::unique_lock<std::mutex> lock(mutex_);
    while (true) {
        cv_.wait_for(lock, std::chrono::milliseconds(200), [&] { return !queue_.empty() || stopWriter_ || ended_; });
        if (ended_) break;
        if (queue_.empty()) {
            if (stopWriter_) break;
            if (nowMs() - lastSentMs_ >= kKeepaliveMs) {
                // Nothing else going: a PING (also measures the round trip).
                uint8_t ts[8];
                writeU64(ts, static_cast<uint64_t>(qpcNowUs() - originUs_));
                Item item;
                item.bytes.resize(kHeaderSize + 8);
                writeHeader(item.bytes.data(), MsgType::Ping, 8);
                std::memcpy(item.bytes.data() + kHeaderSize, ts, 8);
                item.queuedAt = nowMs();
                queuedBytes_ += item.bytes.size();
                queue_.push_back(std::move(item));
            }
            continue;
        }
        // Keep the item queued (and counted) while it's being sent.
        std::vector<uint8_t> bytes = std::move(queue_.front().bytes);
        lock.unlock();
        bool ok = sendAll(bytes.data(), bytes.size());
        int err = WSAGetLastError();
        lock.lock();
        queuedBytes_ -= bytes.size();
        queue_.pop_front();
        cv_.notify_all();
        if (!ok) {
            lock.unlock();
            end(err == WSAETIMEDOUT ? "the receiver stopped taking data"
                                    : "couldn't send: " + win32ErrorText(static_cast<unsigned long>(err)));
            lock.lock();
            break;
        }
    }
}

void Connection::readerLoop() {
    while (!stopReader_ && !ended_) {
        MsgType type;
        std::vector<uint8_t> payload;
        std::string error;
        Read r = readMessage(type, payload, 500, &error);
        if (r == Read::Timeout) {
            if (nowMs() - lastHeardMs_ > kSilenceMs) {
                end("the receiver stopped answering (nothing for 8 seconds)");
                break;
            }
            continue;
        }
        if (r != Read::Ok) {
            if (!stopReader_) end(r == Read::Closed ? "the receiver closed the connection" : error);
            break;
        }
        switch (type) {
            case MsgType::Ping:
                send(MsgType::Pong, payload.data(), payload.size());
                break;
            case MsgType::Pong:
                if (payload.size() == 8) {
                    int64_t sent = static_cast<int64_t>(readU64(payload.data()));
                    rttMs_ = static_cast<double>(qpcNowUs() - originUs_ - sent) / 1000.0;
                }
                break;
            case MsgType::Bye: {
                std::string reason(payload.begin(), payload.end());
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    byeReceived_ = true;
                    byeReason_ = reason;
                }
                end(reason.empty() ? "the receiver ended the session" : "the receiver ended the session: " + reason);
                break;
            }
            default:
                if (handler_) handler_(type, payload);
                break;
        }
    }
}

bool Connection::flush(int timeoutMs) {
    std::unique_lock<std::mutex> lock(mutex_);
    return cv_.wait_for(lock, std::chrono::milliseconds(timeoutMs), [&] { return queue_.empty() || ended_; }) &&
           queue_.empty();
}

void Connection::bye(const std::string& reason, int timeoutMs) {
    if (sock_ == INVALID_SOCKET) return;
    if (!ended_) {
        sendText(MsgType::Bye, reason);
        flush(timeoutMs);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopWriter_ = true;
        }
        cv_.notify_all();
        if (writer_.joinable()) writer_.join();
        shutdown(sock_, SD_SEND);
        // The receiver closes once it has our BYE; wait a little for that.
        double until = nowMs() + 1000;
        while (!ended_ && nowMs() < until) Sleep(20);
    }
}

void Connection::close() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stopWriter_ = true;
    }
    stopReader_ = true;
    cv_.notify_all();
    if (sock_ != INVALID_SOCKET) shutdown(sock_, SD_BOTH);
    if (writer_.joinable()) writer_.join();
    if (reader_.joinable()) reader_.join();
    if (sock_ != INVALID_SOCKET) {
        closesocket(sock_);
        sock_ = INVALID_SOCKET;
    }
}

}  // namespace mango
