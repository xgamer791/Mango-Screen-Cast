// The TCP connection to a receiver: Mango Cast framing, a send queue (whose
// backlog tells us when the network can't keep up), a reader that answers
// PINGs, keepalives every second, and the 8 second timeout.
#pragma once

#include <winsock2.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "core/protocol.h"

namespace mango {

bool startWinsock(std::string* why);

class Connection {
public:
    // A message from the receiver other than PING/PONG (which are handled here).
    using Handler = std::function<void(MsgType type, const std::vector<uint8_t>& payload)>;

    ~Connection();

    // Tries each address the host resolves to. False and why on failure;
    // *notFound is set when the name doesn't resolve at all.
    bool connect(const std::string& host, int port, int timeoutMs, std::string* error, bool* notFound = nullptr);
    std::string peer() const { return peer_; }

    // Before start(): one message each way, synchronously.
    bool sendNow(MsgType type, const std::string& payload, std::string* error);
    enum class Read { Ok, Timeout, Closed, Error };
    Read readMessage(MsgType& type, std::vector<uint8_t>& payload, int timeoutMs, std::string* error);

    // Starts the reader and writer threads.
    void start(Handler handler);

    // Queues a message. Header and payload parts are joined into one buffer.
    void send(MsgType type, const uint8_t* a, size_t na, const uint8_t* b = nullptr, size_t nb = 0);
    void sendText(MsgType type, const std::string& text);

    // Bytes queued but not yet handed to the socket, and how long the oldest
    // of them has waited.
    size_t queuedBytes() const;
    double oldestQueuedMs() const;

    // Waits up to timeoutMs for the queue to empty.
    bool flush(int timeoutMs);
    // Sends BYE, waits for it to go and for the receiver to close.
    void bye(const std::string& reason, int timeoutMs);
    void close();

    // Set once the connection is over; why in *reason.
    bool ended(std::string* reason = nullptr) const;
    // The receiver said BYE (and why).
    bool receiverSaidBye(std::string* reason = nullptr) const;
    double roundTripMs() const { return rttMs_.load(); }

private:
    struct Item {
        std::vector<uint8_t> bytes;
        double queuedAt;
    };
    void readerLoop();
    void writerLoop();
    bool sendAll(const uint8_t* data, size_t size);
    bool recvAll(uint8_t* data, size_t size, int timeoutMs, Read* why);
    void end(const std::string& reason);

    SOCKET sock_ = INVALID_SOCKET;
    std::string peer_;
    Handler handler_;
    std::thread reader_, writer_;
    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<Item> queue_;
    size_t queuedBytes_ = 0;
    bool stopWriter_ = false;
    std::atomic<bool> ended_{false};
    std::atomic<bool> stopReader_{false};
    std::string endReason_;
    bool byeReceived_ = false;
    std::string byeReason_;
    std::atomic<double> lastHeardMs_{0};
    std::atomic<double> lastSentMs_{0};
    std::atomic<double> rttMs_{0};
    int64_t originUs_ = 0;
};

}  // namespace mango
