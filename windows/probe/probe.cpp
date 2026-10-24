// Reports what a Windows machine offers for Mango Cast: Media Foundation
// encoders, Windows.Graphics.Capture, the desktop, D3D11 video processing and
// audio. Used once on the CI runner to plan the tests.
#include <unknwn.h>
#include <windows.h>
#include <d3d11_4.h>
#include <dxgi1_6.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mftransform.h>
#include <mferror.h>
#include <codecapi.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <functiondiscoverykeys_devpkey.h>
#include <windns.h>
#include <wtsapi32.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Metadata.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>

#include <chrono>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

namespace wgc = winrt::Windows::Graphics::Capture;
namespace wgd = winrt::Windows::Graphics::DirectX;

static std::string narrow(const wchar_t* w) {
    if (!w) return "";
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    std::string s(n > 0 ? n - 1 : 0, '\0');
    if (n > 1) WideCharToMultiByte(CP_UTF8, 0, w, -1, s.data(), n, nullptr, nullptr);
    return s;
}

static void encoders(const GUID& subtype, const char* codec, UINT32 flags, const char* kind) {
    MFT_REGISTER_TYPE_INFO in{MFMediaType_Video, MFVideoFormat_NV12};
    MFT_REGISTER_TYPE_INFO out{MFMediaType_Video, subtype};
    IMFActivate** acts = nullptr;
    UINT32 count = 0;
    HRESULT hr = MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, flags, &in, &out, &acts, &count);
    printf("[mf] %s %s encoders: hr=0x%08lx count=%u\n", kind, codec, (unsigned long)hr, count);
    for (UINT32 i = 0; i < count; i++) {
        WCHAR* name = nullptr;
        UINT32 len = 0;
        acts[i]->GetAllocatedString(MFT_FRIENDLY_NAME_Attribute, &name, &len);
        UINT32 mflags = 0;
        acts[i]->GetUINT32(MF_TRANSFORM_FLAGS_Attribute, &mflags);
        UINT64 luid = 0;
        HRESULT hl = acts[i]->GetUINT64(MFT_ENUM_ADAPTER_LUID, &luid);
        printf("[mf]   %u: %s flags=0x%x luid=%s\n", i, narrow(name).c_str(), mflags,
               SUCCEEDED(hl) ? std::to_string(luid).c_str() : "-");
        CoTaskMemFree(name);
        IMFTransform* t = nullptr;
        hr = acts[i]->ActivateObject(IID_PPV_ARGS(&t));
        if (FAILED(hr)) {
            printf("[mf]      activate failed 0x%08lx\n", (unsigned long)hr);
        } else {
            IMFAttributes* attrs = nullptr;
            UINT32 async = 0;
            if (SUCCEEDED(t->GetAttributes(&attrs)) && attrs) {
                attrs->GetUINT32(MF_TRANSFORM_ASYNC, &async);
                if (async) attrs->SetUINT32(MF_TRANSFORM_ASYNC_UNLOCK, TRUE);
                attrs->Release();
            }
            IMFMediaType* ot = nullptr;
            MFCreateMediaType(&ot);
            ot->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
            ot->SetGUID(MF_MT_SUBTYPE, subtype);
            ot->SetUINT32(MF_MT_AVG_BITRATE, 8000000);
            MFSetAttributeSize(ot, MF_MT_FRAME_SIZE, 1280, 720);
            MFSetAttributeRatio(ot, MF_MT_FRAME_RATE, 60, 1);
            MFSetAttributeRatio(ot, MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
            ot->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
            HRESULT ho = t->SetOutputType(0, ot, 0);
            IMFMediaType* it = nullptr;
            MFCreateMediaType(&it);
            it->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
            it->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
            MFSetAttributeSize(it, MF_MT_FRAME_SIZE, 1280, 720);
            MFSetAttributeRatio(it, MF_MT_FRAME_RATE, 60, 1);
            MFSetAttributeRatio(it, MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
            it->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
            HRESULT hi = t->SetInputType(0, it, 0);
            ICodecAPI* api = nullptr;
            HRESULT hc = t->QueryInterface(IID_PPV_ARGS(&api));
            const char* force = "-";
            const char* lowlat = "-";
            if (SUCCEEDED(hc)) {
                force = api->IsSupported(&CODECAPI_AVEncVideoForceKeyFrame) == S_OK ? "yes" : "no";
                lowlat = api->IsSupported(&CODECAPI_AVLowLatencyMode) == S_OK ? "yes" : "no";
                api->Release();
            }
            printf("[mf]      async=%u setOutput=0x%08lx setInput=0x%08lx codecapi=0x%08lx forceKey=%s lowLatency=%s\n",
                   async, (unsigned long)ho, (unsigned long)hi, (unsigned long)hc, force, lowlat);
            it->Release();
            ot->Release();
            t->Release();
            acts[i]->ShutdownObject();
        }
        acts[i]->Release();
    }
    CoTaskMemFree(acts);
}

static void mf() {
    HMODULE m = LoadLibraryW(L"mfplat.dll");
    printf("[mf] mfplat.dll %s\n", m ? "present" : "MISSING");
    if (!m) return;
    HRESULT hr = MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET);
    printf("[mf] MFStartup hr=0x%08lx\n", (unsigned long)hr);
    if (FAILED(hr)) return;
    UINT32 hw = MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER;
    UINT32 sw = MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_ASYNCMFT | MFT_ENUM_FLAG_SORTANDFILTER;
    encoders(MFVideoFormat_H264, "H.264", hw, "hardware");
    encoders(MFVideoFormat_HEVC, "HEVC", hw, "hardware");
    encoders(MFVideoFormat_H264, "H.264", sw, "software");
    encoders(MFVideoFormat_HEVC, "HEVC", sw, "software");
    MFShutdown();
}

static winrt::com_ptr<ID3D11Device> d3d() {
    winrt::com_ptr<ID3D11Device> dev;
    winrt::com_ptr<ID3D11DeviceContext> ctx;
    D3D_FEATURE_LEVEL fl{};
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                                   D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT, nullptr, 0,
                                   D3D11_SDK_VERSION, dev.put(), &fl, ctx.put());
    printf("[d3d] hardware device hr=0x%08lx fl=0x%x\n", (unsigned long)hr, (unsigned)fl);
    if (FAILED(hr)) {
        hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr,
                               0, D3D11_SDK_VERSION, dev.put(), &fl, ctx.put());
        printf("[d3d] hardware device without video support hr=0x%08lx\n", (unsigned long)hr);
        if (FAILED(hr)) return nullptr;
    }
    auto dxgi = dev.as<IDXGIDevice>();
    winrt::com_ptr<IDXGIAdapter> ad;
    dxgi->GetAdapter(ad.put());
    DXGI_ADAPTER_DESC desc{};
    ad->GetDesc(&desc);
    printf("[d3d] adapter: %s vendor=0x%04x device=0x%04x vram=%zuMB luid=%lld\n", narrow(desc.Description).c_str(),
           desc.VendorId, desc.DeviceId, (size_t)(desc.DedicatedVideoMemory >> 20),
           (long long)(((UINT64)desc.AdapterLuid.HighPart << 32) | desc.AdapterLuid.LowPart));
    for (UINT i = 0;; i++) {
        winrt::com_ptr<IDXGIOutput> out;
        if (ad->EnumOutputs(i, out.put()) != S_OK) break;
        DXGI_OUTPUT_DESC od{};
        out->GetDesc(&od);
        printf("[d3d] output %u: %s attached=%d %ldx%ld\n", i, narrow(od.DeviceName).c_str(), od.AttachedToDesktop,
               od.DesktopCoordinates.right - od.DesktopCoordinates.left,
               od.DesktopCoordinates.bottom - od.DesktopCoordinates.top);
        auto o1 = out.try_as<IDXGIOutput1>();
        if (o1) {
            winrt::com_ptr<IDXGIOutputDuplication> dup;
            HRESULT hd = o1->DuplicateOutput(dev.get(), dup.put());
            printf("[d3d]   DuplicateOutput hr=0x%08lx\n", (unsigned long)hd);
        }
    }
    auto vd = dev.try_as<ID3D11VideoDevice>();
    printf("[d3d] ID3D11VideoDevice: %s\n", vd ? "yes" : "no");
    if (vd) {
        D3D11_VIDEO_PROCESSOR_CONTENT_DESC cd{};
        cd.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
        cd.InputWidth = 1920;
        cd.InputHeight = 1080;
        cd.OutputWidth = 1280;
        cd.OutputHeight = 720;
        cd.InputFrameRate = {60, 1};
        cd.OutputFrameRate = {60, 1};
        cd.Usage = D3D11_VIDEO_USAGE_OPTIMAL_SPEED;
        winrt::com_ptr<ID3D11VideoProcessorEnumerator> en;
        HRESULT he = vd->CreateVideoProcessorEnumerator(&cd, en.put());
        printf("[d3d] video processor enumerator hr=0x%08lx\n", (unsigned long)he);
        if (SUCCEEDED(he)) {
            UINT fin = 0, fout = 0;
            en->CheckVideoProcessorFormat(DXGI_FORMAT_B8G8R8A8_UNORM, &fin);
            en->CheckVideoProcessorFormat(DXGI_FORMAT_NV12, &fout);
            printf("[d3d] BGRA flags=0x%x NV12 flags=0x%x\n", fin, fout);
            winrt::com_ptr<ID3D11VideoProcessor> vp;
            HRESULT hp = vd->CreateVideoProcessor(en.get(), 0, vp.put());
            printf("[d3d] video processor hr=0x%08lx\n", (unsigned long)hp);
        }
    }
    UINT support = 0;
    dev->CheckFormatSupport(DXGI_FORMAT_NV12, &support);
    printf("[d3d] NV12 format support=0x%x (render target %s)\n", support,
           (support & D3D11_FORMAT_SUPPORT_RENDER_TARGET) ? "yes" : "no");
    return dev;
}

static void capture(const winrt::com_ptr<ID3D11Device>& dev) {
    using winrt::Windows::Foundation::Metadata::ApiInformation;
    bool supported = false;
    try {
        supported = wgc::GraphicsCaptureSession::IsSupported();
    } catch (const winrt::hresult_error& e) {
        printf("[wgc] IsSupported threw 0x%08lx\n", (unsigned long)e.code().value);
    }
    printf("[wgc] IsSupported=%d cursorProperty=%d borderProperty=%d\n", supported,
           ApiInformation::IsPropertyPresent(L"Windows.Graphics.Capture.GraphicsCaptureSession",
                                             L"IsCursorCaptureEnabled"),
           ApiInformation::IsPropertyPresent(L"Windows.Graphics.Capture.GraphicsCaptureSession",
                                             L"IsBorderRequired"));
    if (!supported || !dev) return;
    try {
        HMONITOR mon = MonitorFromPoint({0, 0}, MONITOR_DEFAULTTOPRIMARY);
        auto interop = winrt::get_activation_factory<wgc::GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
        wgc::GraphicsCaptureItem item{nullptr};
        winrt::check_hresult(interop->CreateForMonitor(mon, winrt::guid_of<wgc::GraphicsCaptureItem>(),
                                                       winrt::put_abi(item)));
        printf("[wgc] item %s %dx%d\n", winrt::to_string(item.DisplayName()).c_str(), item.Size().Width,
               item.Size().Height);
        winrt::com_ptr<::IInspectable> insp;
        winrt::check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dev.as<IDXGIDevice>().get(), insp.put()));
        auto device = insp.as<wgd::Direct3D11::IDirect3DDevice>();
        auto pool = wgc::Direct3D11CaptureFramePool::CreateFreeThreaded(
            device, wgd::DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, item.Size());
        auto session = pool.CreateCaptureSession(item);
        if (ApiInformation::IsPropertyPresent(L"Windows.Graphics.Capture.GraphicsCaptureSession",
                                              L"IsCursorCaptureEnabled"))
            session.IsCursorCaptureEnabled(true);
        if (ApiInformation::IsPropertyPresent(L"Windows.Graphics.Capture.GraphicsCaptureSession",
                                              L"IsBorderRequired")) {
            try {
                session.IsBorderRequired(false);
                printf("[wgc] border off\n");
            } catch (const winrt::hresult_error& e) {
                printf("[wgc] IsBorderRequired(false) threw 0x%08lx\n", (unsigned long)e.code().value);
            }
        }
        session.StartCapture();
        auto start = std::chrono::steady_clock::now();
        int frames = 0;
        wgc::Direct3D11CaptureFrame last{nullptr};
        while (std::chrono::steady_clock::now() - start < std::chrono::seconds(3)) {
            auto f = pool.TryGetNextFrame();
            if (f) {
                frames++;
                if (last) last.Close();
                last = f;
            } else {
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
            }
        }
        printf("[wgc] frames in 3 s: %d\n", frames);
        if (last) {
            auto cs = last.ContentSize();
            winrt::com_ptr<ID3D11Texture2D> tex;
            auto access = last.Surface().as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
            winrt::check_hresult(access->GetInterface(IID_PPV_ARGS(tex.put())));
            D3D11_TEXTURE2D_DESC td{};
            tex->GetDesc(&td);
            printf("[wgc] content %dx%d texture %ux%u fmt=%d bind=0x%x misc=0x%x\n", cs.Width, cs.Height, td.Width,
                   td.Height, (int)td.Format, td.BindFlags, td.MiscFlags);
            D3D11_TEXTURE2D_DESC sd = td;
            sd.BindFlags = 0;
            sd.MiscFlags = 0;
            sd.Usage = D3D11_USAGE_STAGING;
            sd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
            winrt::com_ptr<ID3D11Texture2D> st;
            winrt::check_hresult(dev->CreateTexture2D(&sd, nullptr, st.put()));
            winrt::com_ptr<ID3D11DeviceContext> ctx;
            dev->GetImmediateContext(ctx.put());
            ctx->CopyResource(st.get(), tex.get());
            D3D11_MAPPED_SUBRESOURCE m{};
            winrt::check_hresult(ctx->Map(st.get(), 0, D3D11_MAP_READ, 0, &m));
            double sum = 0;
            size_t n = 0;
            for (UINT y = 0; y < td.Height; y += 7) {
                auto row = static_cast<const uint8_t*>(m.pData) + (size_t)y * m.RowPitch;
                for (UINT x = 0; x < td.Width; x += 7) {
                    sum += row[x * 4] + row[x * 4 + 1] + row[x * 4 + 2];
                    n += 3;
                }
            }
            ctx->Unmap(st.get(), 0);
            printf("[wgc] mean pixel value %.1f\n", n ? sum / n : 0.0);
            last.Close();
        }
        session.Close();
        pool.Close();
    } catch (const winrt::hresult_error& e) {
        printf("[wgc] capture failed 0x%08lx %s\n", (unsigned long)e.code().value,
               winrt::to_string(e.message()).c_str());
    }
}

static void audio() {
    winrt::com_ptr<IMMDeviceEnumerator> en;
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(en.put()));
    printf("[audio] enumerator hr=0x%08lx\n", (unsigned long)hr);
    if (FAILED(hr)) return;
    winrt::com_ptr<IMMDeviceCollection> col;
    if (SUCCEEDED(en->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, col.put()))) {
        UINT n = 0;
        col->GetCount(&n);
        printf("[audio] active render endpoints: %u\n", n);
        for (UINT i = 0; i < n; i++) {
            winrt::com_ptr<IMMDevice> d;
            col->Item(i, d.put());
            winrt::com_ptr<IPropertyStore> ps;
            d->OpenPropertyStore(STGM_READ, ps.put());
            PROPVARIANT pv;
            PropVariantInit(&pv);
            ps->GetValue(PKEY_Device_FriendlyName, &pv);
            printf("[audio]   %s\n", pv.vt == VT_LPWSTR ? narrow(pv.pwszVal).c_str() : "?");
            PropVariantClear(&pv);
        }
    }
    winrt::com_ptr<IMMDevice> dev;
    hr = en->GetDefaultAudioEndpoint(eRender, eConsole, dev.put());
    printf("[audio] default render endpoint hr=0x%08lx\n", (unsigned long)hr);
    if (FAILED(hr)) return;
    winrt::com_ptr<IAudioClient> client;
    hr = dev->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, client.put_void());
    if (FAILED(hr)) return;
    WAVEFORMATEX* wf = nullptr;
    client->GetMixFormat(&wf);
    if (wf)
        printf("[audio] mix format tag=0x%x ch=%u rate=%lu bits=%u size=%u\n", wf->wFormatTag, wf->nChannels,
               wf->nSamplesPerSec, wf->wBitsPerSample, wf->cbSize);
    hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 200000, 0, wf, nullptr);
    printf("[audio] loopback Initialize hr=0x%08lx\n", (unsigned long)hr);
    CoTaskMemFree(wf);
}

static void desktop() {
    printf("[desk] screen %dx%d virtual %dx%d monitors=%d remote=%d\n", GetSystemMetrics(SM_CXSCREEN),
           GetSystemMetrics(SM_CYSCREEN), GetSystemMetrics(SM_CXVIRTUALSCREEN), GetSystemMetrics(SM_CYVIRTUALSCREEN),
           GetSystemMetrics(SM_CMONITORS), GetSystemMetrics(SM_REMOTESESSION));
    DWORD sid = 0;
    ProcessIdToSessionId(GetCurrentProcessId(), &sid);
    printf("[desk] session=%lu console session=%lu\n", sid, WTSGetActiveConsoleSessionId());
    HDESK d = OpenInputDesktop(0, FALSE, GENERIC_READ);
    if (d) {
        WCHAR name[256]{};
        DWORD len = 0;
        GetUserObjectInformationW(d, UOI_NAME, name, sizeof(name), &len);
        printf("[desk] input desktop: %s\n", narrow(name).c_str());
        CloseDesktop(d);
    } else {
        printf("[desk] OpenInputDesktop failed %lu\n", GetLastError());
    }
    HWINSTA ws = GetProcessWindowStation();
    USEROBJECTFLAGS f{};
    DWORD len = 0;
    GetUserObjectInformationW(ws, UOI_FLAGS, &f, sizeof(f), &len);
    printf("[desk] window station visible=%d\n", (f.dwFlags & WSF_VISIBLE) ? 1 : 0);
    typedef LONG(WINAPI * RtlGetVersionFn)(OSVERSIONINFOW*);
    auto rgv = reinterpret_cast<RtlGetVersionFn>(GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlGetVersion"));
    OSVERSIONINFOW vi{sizeof(vi)};
    if (rgv) rgv(&vi);
    printf("[desk] windows %lu.%lu.%lu\n", vi.dwMajorVersion, vi.dwMinorVersion, vi.dwBuildNumber);
    HMODULE dns = LoadLibraryW(L"dnsapi.dll");
    printf("[dns] DnsServiceBrowse %s\n", dns && GetProcAddress(dns, "DnsServiceBrowse") ? "present" : "missing");
}

int main() {
    winrt::init_apartment(winrt::apartment_type::multi_threaded);
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    desktop();
    mf();
    auto dev = d3d();
    capture(dev);
    audio();
    fflush(stdout);
    return 0;
}
