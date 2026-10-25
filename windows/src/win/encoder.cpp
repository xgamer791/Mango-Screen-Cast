#include "encoder.h"

#include <mfapi.h>
#include <mferror.h>
#include <mftransform.h>
#include <strmif.h>
#include <codecapi.h>

#include <atomic>
#include <cstring>
#include <mutex>

#include "log.h"
#include "util.h"

namespace mango {

using Microsoft::WRL::ComPtr;

bool startMediaFoundation(std::string* why) {
    static std::once_flag once;
    static HRESULT result = E_FAIL;
    static bool present = false;
    std::call_once(once, [] {
        // mfplat.dll is delay-loaded: check it's there before calling into it.
        present = LoadLibraryExW(L"mfplat.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32) != nullptr;
        if (present) result = MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET);
    });
    if (!present) {
        if (why)
            *why = "Media Foundation isn't installed. On Windows N editions, add the Media Feature Pack "
                   "(Settings > Apps > Optional features).";
        return false;
    }
    if (FAILED(result)) {
        if (why) *why = "Media Foundation didn't start: " + hresultText(result);
        return false;
    }
    return true;
}

std::vector<EncoderCandidate> findEncoders(Codec codec, bool hardware) {
    std::vector<EncoderCandidate> list;
    if (!startMediaFoundation(nullptr)) return list;
    MFT_REGISTER_TYPE_INFO in{MFMediaType_Video, MFVideoFormat_NV12};
    MFT_REGISTER_TYPE_INFO out{MFMediaType_Video, codec == Codec::HEVC ? MFVideoFormat_HEVC : MFVideoFormat_H264};
    UINT32 flags = hardware ? MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER
                            : MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_ASYNCMFT | MFT_ENUM_FLAG_SORTANDFILTER;
    IMFActivate** acts = nullptr;
    UINT32 count = 0;
    if (FAILED(MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, flags, &in, &out, &acts, &count))) return list;
    for (UINT32 i = 0; i < count; i++) {
        EncoderCandidate c;
        c.codec = codec;
        c.activate.Attach(acts[i]);
        UINT32 mftFlags = 0;
        c.activate->GetUINT32(MF_TRANSFORM_FLAGS_Attribute, &mftFlags);
        c.hardware = (mftFlags & MFT_ENUM_FLAG_HARDWARE) != 0;
        if (c.hardware != hardware) continue;
        WCHAR* name = nullptr;
        UINT32 len = 0;
        if (SUCCEEDED(c.activate->GetAllocatedString(MFT_FRIENDLY_NAME_Attribute, &name, &len)) && name) {
            c.name = narrow(name);
            CoTaskMemFree(name);
        } else {
            c.name = hardware ? "hardware encoder" : "software encoder";
        }
        UINT64 luid = 0;
        if (SUCCEEDED(c.activate->GetUINT64(MFT_ENUM_ADAPTER_LUID, &luid))) {
            c.hasAdapter = true;
            c.adapter.LowPart = static_cast<DWORD>(luid & 0xFFFFFFFFu);
            c.adapter.HighPart = static_cast<LONG>(luid >> 32);
        }
        list.push_back(std::move(c));
    }
    CoTaskMemFree(acts);
    return list;
}

// ---------------------------------------------------------------------------

struct VideoEncoder::Impl {
    mutable std::mutex mutex;  // every call into the MFT
    ComPtr<IMFActivate> activate;
    ComPtr<IMFTransform> mft;
    ComPtr<ICodecAPI> codecApi;
    ComPtr<IMFMediaEventGenerator> events;
    ComPtr<IMFDXGIDeviceManager> manager;
    ComPtr<IMFAsyncCallback> callback;
    ComPtr<IMFSample> outSample;  // when the MFT wants us to supply output samples
    DWORD inId = 0, outId = 0;
    DWORD outBufferSize = 0;
    bool async = false, hardware = false, textures = false, providesSamples = false, canForceKey = false;
    bool closed = false;
    std::atomic<int> needInput{0};
    std::atomic<bool> failed{false};
    std::string failure;
    Output output;
    Config config;
    std::string name;
    std::vector<uint8_t> sequenceHeader;

    void fail(const std::string& why) {
        if (!failed.exchange(true)) {
            failure = why;
            LOG("Encoder error: %s", why.c_str());
        }
    }

    void setValue(const GUID& key, UINT32 value) {
        if (!codecApi) return;
        VARIANT v;
        VariantInit(&v);
        v.vt = VT_UI4;
        v.ulVal = value;
        codecApi->SetValue(&key, &v);
    }

    void setBool(const GUID& key, bool value) {
        if (!codecApi) return;
        VARIANT v;
        VariantInit(&v);
        v.vt = VT_BOOL;
        v.boolVal = value ? VARIANT_TRUE : VARIANT_FALSE;
        codecApi->SetValue(&key, &v);
    }

    void applySettings() {
        setValue(CODECAPI_AVEncCommonRateControlMode, eAVEncCommonRateControlMode_CBR);
        setValue(CODECAPI_AVEncCommonMeanBitRate, static_cast<UINT32>(config.bitrate));
        setBool(CODECAPI_AVLowLatencyMode, true);
        // A long GOP: keyframes come when asked for (start, size change,
        // KEYFRAME from the receiver), plus one every 10 seconds.
        setValue(CODECAPI_AVEncMPVGOPSize, static_cast<UINT32>(config.fps * 10));
        setValue(CODECAPI_AVEncMPVDefaultBPictureCount, 0);
        if (!hardware) setValue(CODECAPI_AVEncCommonQualityVsSpeed, 33);  // favour speed on the CPU
    }

    HRESULT readOutputType() {
        sequenceHeader.clear();
        ComPtr<IMFMediaType> type;
        HRESULT hr = mft->GetOutputCurrentType(outId, &type);
        if (FAILED(hr)) return hr;
        UINT32 size = 0;
        if (SUCCEEDED(type->GetBlobSize(MF_MT_MPEG_SEQUENCE_HEADER, &size)) && size > 0) {
            sequenceHeader.resize(size);
            type->GetBlob(MF_MT_MPEG_SEQUENCE_HEADER, sequenceHeader.data(), size, nullptr);
        }
        MFT_OUTPUT_STREAM_INFO info{};
        hr = mft->GetOutputStreamInfo(outId, &info);
        if (FAILED(hr)) return hr;
        providesSamples =
            (info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)) != 0;
        outBufferSize = info.cbSize;
        outSample.Reset();
        return S_OK;
    }

    void deliver(IMFSample* sample) {
        ComPtr<IMFMediaBuffer> buffer;
        if (FAILED(sample->ConvertToContiguousBuffer(&buffer))) return;
        BYTE* data = nullptr;
        DWORD length = 0;
        if (FAILED(buffer->Lock(&data, nullptr, &length))) return;
        LONGLONG time = 0;
        sample->GetSampleTime(&time);
        UINT32 clean = 0;
        sample->GetUINT32(MFSampleExtension_CleanPoint, &clean);
        if (length > 0 && output) output(data, length, time, clean != 0);
        buffer->Unlock();
        if (!providesSamples) buffer->SetCurrentLength(0);
    }

    // Takes what the encoder has made: all of it (synchronous), or the one
    // picture it said it has (asynchronous).
    HRESULT drain() {
        for (int guard = 0; guard < 64; guard++) {
            MFT_OUTPUT_DATA_BUFFER ob{};
            ob.dwStreamID = outId;
            if (!providesSamples) {
                if (!outSample) {
                    ComPtr<IMFMediaBuffer> buffer;
                    DWORD size = outBufferSize ? outBufferSize
                                               : static_cast<DWORD>(config.size.width * config.size.height * 3 / 2 + 65536);
                    HRESULT hr = MFCreateSample(&outSample);
                    if (SUCCEEDED(hr)) hr = MFCreateMemoryBuffer(size, &buffer);
                    if (SUCCEEDED(hr)) hr = outSample->AddBuffer(buffer.Get());
                    if (FAILED(hr)) return hr;
                }
                outSample->DeleteAllItems();  // no CleanPoint left over from the last picture
                ob.pSample = outSample.Get();
            }
            DWORD status = 0;
            HRESULT hr = mft->ProcessOutput(0, 1, &ob, &status);
            if (ob.pEvents) ob.pEvents->Release();
            if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) return S_OK;
            if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
                if (providesSamples && ob.pSample) ob.pSample->Release();
                ComPtr<IMFMediaType> type;
                hr = mft->GetOutputAvailableType(outId, 0, &type);
                if (SUCCEEDED(hr)) hr = mft->SetOutputType(outId, type.Get(), 0);
                if (SUCCEEDED(hr)) hr = readOutputType();
                if (FAILED(hr)) return hr;
                LOG("Encoder changed its output format");
                continue;
            }
            if (FAILED(hr)) {
                if (providesSamples && ob.pSample) ob.pSample->Release();
                return hr;
            }
            if (ob.pSample) {
                deliver(ob.pSample);
                if (providesSamples) ob.pSample->Release();
            }
            if (async) return S_OK;
        }
        return S_OK;
    }

    void forceKeyframe() { setValue(CODECAPI_AVEncVideoForceKeyFrame, 1); }

    bool submit(IMFMediaBuffer* buffer, int64_t time100ns, bool keyframe, std::string* error) {
        std::lock_guard<std::mutex> lock(mutex);
        if (closed || !mft) return false;
        if (async && needInput.load() <= 0) return false;
        ComPtr<IMFSample> sample;
        HRESULT hr = MFCreateSample(&sample);
        if (SUCCEEDED(hr)) hr = sample->AddBuffer(buffer);
        if (SUCCEEDED(hr)) hr = sample->SetSampleTime(time100ns);
        if (SUCCEEDED(hr)) hr = sample->SetSampleDuration(10000000LL / config.fps);
        if (FAILED(hr)) {
            if (error) *error = "Couldn't make an input sample: " + hresultText(hr);
            return false;
        }
        if (keyframe) forceKeyframe();
        hr = mft->ProcessInput(inId, sample.Get(), 0);
        if (hr == MF_E_NOTACCEPTING && !async) {
            hr = drain();
            if (SUCCEEDED(hr)) hr = mft->ProcessInput(inId, sample.Get(), 0);
        }
        if (FAILED(hr)) {
            if (error) *error = "The encoder didn't take the picture: " + hresultText(hr);
            return false;
        }
        if (async) {
            needInput--;
        } else {
            hr = drain();
            if (FAILED(hr)) {
                if (error) *error = "The encoder failed: " + hresultText(hr);
                return false;
            }
        }
        return true;
    }
};

namespace {

// Receives an asynchronous MFT's events (needs input, has output) on a
// Media Foundation work queue thread.
class EventCallback : public IMFAsyncCallback {
public:
    explicit EventCallback(std::weak_ptr<VideoEncoder::Impl> impl) : impl_(std::move(impl)) {}

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IMFAsyncCallback)) {
            *ppv = static_cast<IMFAsyncCallback*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return ++refs_; }
    STDMETHODIMP_(ULONG) Release() override {
        ULONG n = --refs_;
        if (n == 0) delete this;
        return n;
    }
    STDMETHODIMP GetParameters(DWORD*, DWORD*) override { return E_NOTIMPL; }

    STDMETHODIMP Invoke(IMFAsyncResult* result) override {
        auto impl = impl_.lock();
        if (!impl) return S_OK;
        // Under the lock: close() lets go of `events` under it too.
        std::lock_guard<std::mutex> lock(impl->mutex);
        if (impl->closed || !impl->events) return S_OK;
        ComPtr<IMFMediaEvent> event;
        HRESULT hr = impl->events->EndGetEvent(result, &event);
        if (FAILED(hr)) {
            impl->fail("lost the encoder's events: " + hresultText(hr));
            return S_OK;
        }
        MediaEventType type = MEUnknown;
        event->GetType(&type);
        HRESULT status = S_OK;
        event->GetStatus(&status);
        if (FAILED(status)) {
            impl->fail("the encoder reported " + hresultText(status));
        } else if (type == METransformNeedInput) {
            impl->needInput++;
        } else if (type == METransformHaveOutput) {
            hr = impl->drain();
            if (FAILED(hr)) impl->fail("couldn't get the encoded picture: " + hresultText(hr));
        } else if (type == MEError) {
            impl->fail("the encoder reported an error");
        }
        if (!impl->failed) {
            hr = impl->events->BeginGetEvent(this, nullptr);
            if (FAILED(hr)) impl->fail("couldn't wait for the encoder: " + hresultText(hr));
        }
        return S_OK;
    }

private:
    std::atomic<ULONG> refs_{1};
    std::weak_ptr<VideoEncoder::Impl> impl_;
};

ComPtr<IMFMediaType> videoType(const GUID& subtype, const VideoEncoder::Config& c) {
    ComPtr<IMFMediaType> t;
    if (FAILED(MFCreateMediaType(&t))) return nullptr;
    t->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    t->SetGUID(MF_MT_SUBTYPE, subtype);
    MFSetAttributeSize(t.Get(), MF_MT_FRAME_SIZE, static_cast<UINT32>(c.size.width), static_cast<UINT32>(c.size.height));
    MFSetAttributeRatio(t.Get(), MF_MT_FRAME_RATE, static_cast<UINT32>(c.fps), 1);
    MFSetAttributeRatio(t.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
    t->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    // What the converter makes: BT.709, limited range.
    t->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709);
    t->SetUINT32(MF_MT_VIDEO_PRIMARIES, MFVideoPrimaries_BT709);
    t->SetUINT32(MF_MT_TRANSFER_FUNCTION, MFVideoTransFunc_709);
    t->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235);
    return t;
}

}  // namespace

VideoEncoder::VideoEncoder() = default;

VideoEncoder::~VideoEncoder() { close(); }

bool VideoEncoder::open(const EncoderCandidate& candidate, const Config& config, ID3D11Device* device, Output output,
                        std::string* error) {
    close();
    auto impl = std::make_shared<Impl>();
    impl->config = config;
    impl->output = std::move(output);
    impl->hardware = candidate.hardware;
    impl->name = candidate.name;
    impl->activate = candidate.activate;
    auto fail = [&](const std::string& what, HRESULT hr) {
        if (error) *error = candidate.name + ": " + what + " (" + hresultText(hr) + ")";
        if (impl->activate) impl->activate->ShutdownObject();
        return false;
    };

    HRESULT hr = candidate.activate->ActivateObject(IID_PPV_ARGS(&impl->mft));
    if (FAILED(hr)) return fail("couldn't start it", hr);

    ComPtr<IMFAttributes> attrs;
    if (SUCCEEDED(impl->mft->GetAttributes(&attrs)) && attrs) {
        UINT32 async = 0;
        attrs->GetUINT32(MF_TRANSFORM_ASYNC, &async);
        impl->async = async != 0;
        if (impl->async) attrs->SetUINT32(MF_TRANSFORM_ASYNC_UNLOCK, TRUE);
        attrs->SetUINT32(MF_LOW_LATENCY, TRUE);
    }
    hr = impl->mft->GetStreamIDs(1, &impl->inId, 1, &impl->outId);
    if (hr == E_NOTIMPL) {
        impl->inId = impl->outId = 0;
    } else if (FAILED(hr)) {
        return fail("no stream IDs", hr);
    }

    // Hardware encoders take our NV12 textures through a DXGI device manager.
    if (device && candidate.hardware && attrs) {
        UINT32 aware = 0;
        attrs->GetUINT32(MF_SA_D3D11_AWARE, &aware);
        if (aware) {
            UINT token = 0;
            hr = MFCreateDXGIDeviceManager(&token, &impl->manager);
            if (SUCCEEDED(hr)) hr = impl->manager->ResetDevice(device, token);
            if (SUCCEEDED(hr))
                hr = impl->mft->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER,
                                               reinterpret_cast<ULONG_PTR>(impl->manager.Get()));
            impl->textures = SUCCEEDED(hr);
            if (!impl->textures) {
                LOG("%s doesn't take our Direct3D device (%s); feeding it pictures in memory", candidate.name.c_str(),
                    hresultText(hr).c_str());
                impl->manager.Reset();
            }
        }
    }
    if (device && config.forceTextures && !impl->textures) impl->textures = true;

    impl->mft.As(&impl->codecApi);
    impl->canForceKey = impl->codecApi && impl->codecApi->IsSupported(&CODECAPI_AVEncVideoForceKeyFrame) == S_OK;
    impl->applySettings();

    // Output type first (encoders want it before the input type).
    const GUID subtype = config.codec == Codec::HEVC ? MFVideoFormat_HEVC : MFVideoFormat_H264;
    const UINT32 profiles[] = {config.codec == Codec::HEVC ? static_cast<UINT32>(eAVEncH265VProfile_Main_420_8)
                                                           : static_cast<UINT32>(eAVEncH264VProfile_High),
                               config.codec == Codec::HEVC ? 0u : static_cast<UINT32>(eAVEncH264VProfile_Main), 0u};
    hr = E_FAIL;
    for (UINT32 profile : profiles) {
        ComPtr<IMFMediaType> out = videoType(subtype, config);
        if (!out) break;
        out->SetUINT32(MF_MT_AVG_BITRATE, static_cast<UINT32>(config.bitrate));
        if (profile) out->SetUINT32(MF_MT_MPEG2_PROFILE, profile);
        hr = impl->mft->SetOutputType(impl->outId, out.Get(), 0);
        if (SUCCEEDED(hr)) break;
    }
    if (FAILED(hr))
        return fail("doesn't take " + std::to_string(config.size.width) + " x " + std::to_string(config.size.height) +
                        " " + codecName(config.codec) + " at " + std::to_string(config.fps) + " fps",
                    hr);

    // NV12 in: the encoder's own NV12 type if it lists one, with our size.
    ComPtr<IMFMediaType> in;
    for (DWORD i = 0;; i++) {
        ComPtr<IMFMediaType> t;
        if (FAILED(impl->mft->GetInputAvailableType(impl->inId, i, &t))) break;
        GUID sub{};
        if (SUCCEEDED(t->GetGUID(MF_MT_SUBTYPE, &sub)) && sub == MFVideoFormat_NV12) {
            in = t;
            break;
        }
    }
    if (!in) {
        in = videoType(MFVideoFormat_NV12, config);
    } else {
        MFSetAttributeSize(in.Get(), MF_MT_FRAME_SIZE, static_cast<UINT32>(config.size.width),
                           static_cast<UINT32>(config.size.height));
        MFSetAttributeRatio(in.Get(), MF_MT_FRAME_RATE, static_cast<UINT32>(config.fps), 1);
        MFSetAttributeRatio(in.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
        in->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
        in->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709);
        in->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235);
    }
    if (!impl->textures) in->SetUINT32(MF_MT_DEFAULT_STRIDE, static_cast<UINT32>(config.size.width));
    hr = impl->mft->SetInputType(impl->inId, in.Get(), 0);
    if (FAILED(hr)) return fail("doesn't take NV12 pictures of that size", hr);

    impl->applySettings();  // some encoders only take them once the types are set
    hr = impl->readOutputType();
    if (FAILED(hr)) return fail("no output stream", hr);

    impl->mft->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
    hr = impl->mft->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    if (SUCCEEDED(hr)) hr = impl->mft->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    if (FAILED(hr)) return fail("didn't start streaming", hr);

    if (impl->async) {
        hr = impl->mft.As(&impl->events);
        if (FAILED(hr)) return fail("asynchronous, but has no events", hr);
        impl->callback.Attach(new EventCallback(impl));
        hr = impl->events->BeginGetEvent(impl->callback.Get(), nullptr);
        if (FAILED(hr)) return fail("couldn't wait for its events", hr);
    }
    LOG("Encoder: %s (%s, %s, %s%s), %d x %d %s at %d fps, %.1f Mbit/s", impl->name.c_str(),
        impl->hardware ? "hardware" : "software", impl->async ? "asynchronous" : "synchronous",
        impl->textures ? "Direct3D textures" : "pictures in memory",
        impl->canForceKey ? "" : ", restarted for keyframes", config.size.width, config.size.height,
        codecName(config.codec), config.fps, config.bitrate / 1e6);
    impl_ = std::move(impl);
    return true;
}

void VideoEncoder::close() {
    if (!impl_) return;
    auto impl = std::move(impl_);
    {
        std::lock_guard<std::mutex> lock(impl->mutex);
        impl->closed = true;
        if (impl->mft) {
            impl->mft->ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
            impl->mft->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
            impl->mft->ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
        }
    }
    if (impl->async && impl->mft) {
        // Ends the event queue, so the pending event wait completes.
        ComPtr<IMFShutdown> shutdown;
        if (SUCCEEDED(impl->mft.As(&shutdown))) shutdown->Shutdown();
    }
    if (impl->activate) impl->activate->ShutdownObject();
    std::lock_guard<std::mutex> lock(impl->mutex);
    impl->events.Reset();
    impl->codecApi.Reset();
    impl->mft.Reset();
    impl->manager.Reset();
    impl->callback.Reset();
}

bool VideoEncoder::isOpen() const { return impl_ != nullptr; }

const std::string& VideoEncoder::name() const {
    static const std::string none;
    return impl_ ? impl_->name : none;
}

bool VideoEncoder::hardware() const { return impl_ && impl_->hardware; }

bool VideoEncoder::takesTextures() const { return impl_ && impl_->textures; }

bool VideoEncoder::forcesKeyframes() const { return impl_ && impl_->canForceKey; }

const VideoEncoder::Config& VideoEncoder::config() const {
    static const Config none;
    return impl_ ? impl_->config : none;
}

bool VideoEncoder::ready() const { return impl_ && !impl_->failed && (!impl_->async || impl_->needInput.load() > 0); }

bool VideoEncoder::encodeTexture(ID3D11Texture2D* nv12, int64_t time100ns, bool keyframe, std::string* error) {
    if (!impl_) return false;
    ComPtr<IMFMediaBuffer> buffer;
    HRESULT hr = MFCreateDXGISurfaceBuffer(__uuidof(ID3D11Texture2D), nv12, 0, FALSE, &buffer);
    ComPtr<IMF2DBuffer> buffer2d;
    if (SUCCEEDED(hr)) hr = buffer.As(&buffer2d);
    DWORD length = 0;
    if (SUCCEEDED(hr)) hr = buffer2d->GetContiguousLength(&length);
    if (SUCCEEDED(hr)) hr = buffer->SetCurrentLength(length);
    if (FAILED(hr)) {
        if (error) *error = "Couldn't wrap the picture for the encoder: " + hresultText(hr);
        return false;
    }
    return impl_->submit(buffer.Get(), time100ns, keyframe, error);
}

bool VideoEncoder::encodeMemory(const uint8_t* nv12, int64_t time100ns, bool keyframe, std::string* error) {
    if (!impl_) return false;
    const DWORD size = static_cast<DWORD>(impl_->config.size.width * impl_->config.size.height * 3 / 2);
    ComPtr<IMFMediaBuffer> buffer;
    HRESULT hr = MFCreateMemoryBuffer(size, &buffer);
    BYTE* dst = nullptr;
    if (SUCCEEDED(hr)) hr = buffer->Lock(&dst, nullptr, nullptr);
    if (SUCCEEDED(hr)) {
        std::memcpy(dst, nv12, size);
        buffer->Unlock();
        hr = buffer->SetCurrentLength(size);
    }
    if (FAILED(hr)) {
        if (error) *error = "Couldn't make a picture buffer: " + hresultText(hr);
        return false;
    }
    return impl_->submit(buffer.Get(), time100ns, keyframe, error);
}

void VideoEncoder::setBitrate(double bps) {
    if (!impl_) return;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (impl_->closed) return;
    impl_->config.bitrate = bps;
    impl_->setValue(CODECAPI_AVEncCommonMeanBitRate, static_cast<UINT32>(bps));
}

bool VideoEncoder::failed(std::string* why) const {
    if (!impl_ || !impl_->failed) return false;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (why) *why = impl_->failure;
    return true;
}

std::vector<uint8_t> VideoEncoder::sequenceHeader() const {
    if (!impl_) return {};
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->sequenceHeader;
}

}  // namespace mango
