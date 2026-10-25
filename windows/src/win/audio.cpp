#include "audio.h"

#include <windows.h>
#include <audioclient.h>
#include <functiondiscoverykeys_devpkey.h>
#include <ksmedia.h>
#include <mmdeviceapi.h>
#include <wrl/client.h>

#include <vector>

#include "core/audio_convert.h"
#include "log.h"
#include "util.h"

namespace mango {

using Microsoft::WRL::ComPtr;

namespace {

ComPtr<IMMDevice> defaultRenderDevice(std::wstring* id) {
    ComPtr<IMMDeviceEnumerator> en;
    if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&en)))) return nullptr;
    ComPtr<IMMDevice> device;
    if (FAILED(en->GetDefaultAudioEndpoint(eRender, eConsole, &device))) return nullptr;
    if (id) {
        LPWSTR s = nullptr;
        if (SUCCEEDED(device->GetId(&s)) && s) {
            *id = s;
            CoTaskMemFree(s);
        }
    }
    return device;
}

std::string friendlyName(IMMDevice* device) {
    ComPtr<IPropertyStore> props;
    if (FAILED(device->OpenPropertyStore(STGM_READ, &props))) return "sound device";
    PROPVARIANT pv;
    PropVariantInit(&pv);
    std::string name = "sound device";
    if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &pv)) && pv.vt == VT_LPWSTR) name = narrow(pv.pwszVal);
    PropVariantClear(&pv);
    return name;
}

bool describe(const WAVEFORMATEX* wf, InputAudioFormat& f, std::string* why) {
    f.channels = wf->nChannels;
    f.rate = static_cast<int>(wf->nSamplesPerSec);
    f.blockAlign = wf->nBlockAlign;
    f.channelMask = 0;
    WORD tag = wf->wFormatTag;
    WORD bits = wf->wBitsPerSample;
    WORD valid = bits;
    if (tag == WAVE_FORMAT_EXTENSIBLE && wf->cbSize >= 22) {
        auto ext = reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(wf);
        f.channelMask = ext->dwChannelMask;
        valid = ext->Samples.wValidBitsPerSample ? ext->Samples.wValidBitsPerSample : bits;
        if (IsEqualGUID(ext->SubFormat, KSDATAFORMAT_SUBTYPE_IEEE_FLOAT)) tag = WAVE_FORMAT_IEEE_FLOAT;
        else if (IsEqualGUID(ext->SubFormat, KSDATAFORMAT_SUBTYPE_PCM)) tag = WAVE_FORMAT_PCM;
    }
    if (tag == WAVE_FORMAT_IEEE_FLOAT && bits == 32) {
        f.sample = SampleFormat::Float32;
    } else if (tag == WAVE_FORMAT_PCM && bits == 16) {
        f.sample = SampleFormat::Int16;
    } else if (tag == WAVE_FORMAT_PCM && bits == 24) {
        f.sample = SampleFormat::Int24;
    } else if (tag == WAVE_FORMAT_PCM && bits == 32) {
        // Left-justified in 32 bits whether 24 or 32 of them are used.
        f.sample = valid == 24 ? SampleFormat::Int24 : SampleFormat::Int32;
    } else {
        if (why) *why = "unsupported mix format (tag " + std::to_string(tag) + ", " + std::to_string(bits) + " bits)";
        return false;
    }
    return f.channels > 0 && f.rate > 0 && f.blockAlign > 0;
}

}  // namespace

bool LoopbackAudio::defaultDevice(std::string* name) {
    ComScope com;
    auto device = defaultRenderDevice(nullptr);
    if (!device) return false;
    if (name) *name = friendlyName(device.Get());
    return true;
}

LoopbackAudio::~LoopbackAudio() { stop(); }

void LoopbackAudio::start(Sink sink) {
    stop();
    sink_ = std::move(sink);
    stop_ = false;
    thread_ = std::thread([this] { run(); });
}

void LoopbackAudio::stop() {
    stop_ = true;
    if (thread_.joinable()) thread_.join();
}

void LoopbackAudio::run() {
    ComScope com;
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL);
    bool saidNone = false;
    while (!stop_) {
        if (!captureOnce()) {
            if (!saidNone) LOG("Sound: no playback device; sending the picture only until there is one");
            saidNone = true;
            for (int i = 0; i < 20 && !stop_; i++) Sleep(100);  // look again in 2 s
        } else {
            saidNone = false;
        }
    }
}

bool LoopbackAudio::captureOnce() {
    std::wstring deviceId;
    ComPtr<IMMDevice> device = defaultRenderDevice(&deviceId);
    if (!device) return false;
    std::string name = friendlyName(device.Get());

    ComPtr<IAudioClient> capture, render;
    HRESULT hr = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, &capture);
    if (FAILED(hr)) {
        LOG("Sound: couldn't open %s: %s", name.c_str(), hresultText(hr).c_str());
        return false;
    }
    WAVEFORMATEX* mix = nullptr;
    hr = capture->GetMixFormat(&mix);
    if (FAILED(hr) || !mix) {
        LOG("Sound: no mix format for %s: %s", name.c_str(), hresultText(hr).c_str());
        return false;
    }
    InputAudioFormat format;
    std::string why;
    if (!describe(mix, format, &why)) {
        LOG("Sound: %s: %s", name.c_str(), why.c_str());
        CoTaskMemFree(mix);
        return false;
    }
    const REFERENCE_TIME bufferTime = 1000000;  // 100 ms
    hr = capture->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, bufferTime, 0, mix, nullptr);
    ComPtr<IAudioCaptureClient> captureClient;
    if (SUCCEEDED(hr)) hr = capture->GetService(IID_PPV_ARGS(&captureClient));
    if (FAILED(hr)) {
        LOG("Sound: couldn't capture %s: %s", name.c_str(), hresultText(hr).c_str());
        CoTaskMemFree(mix);
        return false;
    }

    // Silence played on the same device keeps the loopback stream going.
    ComPtr<IAudioRenderClient> renderClient;
    UINT32 renderFrames = 0;
    if (SUCCEEDED(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, &render)) &&
        SUCCEEDED(render->Initialize(AUDCLNT_SHAREMODE_SHARED, 0, bufferTime, 0, mix, nullptr)) &&
        SUCCEEDED(render->GetBufferSize(&renderFrames)) && SUCCEEDED(render->GetService(IID_PPV_ARGS(&renderClient)))) {
        BYTE* data = nullptr;
        if (SUCCEEDED(renderClient->GetBuffer(renderFrames, &data)))
            renderClient->ReleaseBuffer(renderFrames, AUDCLNT_BUFFERFLAGS_SILENT);
        render->Start();
    } else {
        renderClient.Reset();
        render.Reset();
    }
    CoTaskMemFree(mix);

    AudioConverter converter;
    converter.reset(format);
    LOG("Sound: capturing %s (%d Hz, %d channels, %s)", name.c_str(), format.rate, format.channels,
        format.sample == SampleFormat::Float32 ? "float" : "integer");
    hr = capture->Start();
    if (FAILED(hr)) {
        LOG("Sound: couldn't start capturing: %s", hresultText(hr).c_str());
        if (render) render->Stop();
        return false;
    }

    std::vector<int16_t> pending;  // converted, not yet sent
    int64_t pendingTimeUs = 0;     // when pending[0] was captured
    double lastCheck = nowMs();
    bool ok = true;
    while (!stop_) {
        Sleep(5);
        if (renderClient) {
            UINT32 padding = 0;
            if (SUCCEEDED(render->GetCurrentPadding(&padding)) && padding < renderFrames) {
                BYTE* data = nullptr;
                UINT32 n = renderFrames - padding;
                if (SUCCEEDED(renderClient->GetBuffer(n, &data))) renderClient->ReleaseBuffer(n, AUDCLNT_BUFFERFLAGS_SILENT);
            }
        }
        for (;;) {
            UINT32 packet = 0;
            hr = captureClient->GetNextPacketSize(&packet);
            if (FAILED(hr) || packet == 0) break;
            BYTE* data = nullptr;
            UINT32 frames = 0;
            DWORD flags = 0;
            UINT64 qpcPosition = 0;
            hr = captureClient->GetBuffer(&data, &frames, &flags, nullptr, &qpcPosition);
            if (FAILED(hr)) break;
            if (pending.empty()) {
                // QPC position is in 100 ns units.
                pendingTimeUs = qpcPosition ? static_cast<int64_t>(qpcPosition / 10) : qpcNowUs();
            }
            converter.process((flags & AUDCLNT_BUFFERFLAGS_SILENT) ? nullptr : data, frames, pending);
            captureClient->ReleaseBuffer(frames);
        }
        if (hr == AUDCLNT_E_DEVICE_INVALIDATED || hr == AUDCLNT_E_SERVICE_NOT_RUNNING) {
            LOG("Sound: %s went away", name.c_str());
            break;
        }
        size_t sent = 0;
        while (pending.size() - sent >= kChunkFrames * 2) {
            sink_(pendingTimeUs, pending.data() + sent, kChunkFrames);
            sent += kChunkFrames * 2;
            pendingTimeUs += static_cast<int64_t>(kChunkFrames) * 1000000 / kRate;
        }
        if (sent) pending.erase(pending.begin(), pending.begin() + static_cast<std::ptrdiff_t>(sent));
        if (nowMs() - lastCheck > 2000) {
            // Follow the default device (headphones plugged in, a new choice in Settings).
            lastCheck = nowMs();
            std::wstring now;
            defaultRenderDevice(&now);
            if (now != deviceId) {
                LOG("Sound: the default playback device changed");
                break;
            }
        }
    }
    capture->Stop();
    if (render) render->Stop();
    return ok;
}

}  // namespace mango
