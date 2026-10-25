// C++/WinRT needs the classic COM declarations first, for .as<> to COM interfaces.
#include <unknwn.h>
#include <windows.h>

#include "capture.h"

#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Metadata.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>

#include <algorithm>
#include <atomic>
#include <mutex>

#include "log.h"
#include "util.h"

namespace mango {

namespace wgc = winrt::Windows::Graphics::Capture;
namespace wgd = winrt::Windows::Graphics::DirectX;
using winrt::Windows::Foundation::Metadata::ApiInformation;

static const wchar_t* kSessionClass = L"Windows.Graphics.Capture.GraphicsCaptureSession";
constexpr int kBuffers = 3;

struct ScreenCapture::Impl {
    wgd::Direct3D11::IDirect3DDevice device{nullptr};
    wgc::GraphicsCaptureItem item{nullptr};
    wgc::Direct3D11CaptureFramePool pool{nullptr};
    wgc::GraphicsCaptureSession session{nullptr};
    wgc::Direct3D11CaptureFrame held{nullptr};  // the frame handed out last
    winrt::event_token closedToken{};
    std::atomic<bool> closed{false};
    winrt::Windows::Graphics::SizeInt32 poolSize{0, 0};
    std::mutex mutex;
};

ScreenCapture::ScreenCapture() = default;

ScreenCapture::~ScreenCapture() { stop(); }

bool ScreenCapture::supported(std::string* why) {
    try {
        if (!ApiInformation::IsTypePresent(kSessionClass)) {
            if (why) *why = "Windows.Graphics.Capture isn't in this version of Windows (it needs Windows 10 1903 or newer)";
            return false;
        }
        if (!wgc::GraphicsCaptureSession::IsSupported()) {
            if (why) *why = "Windows.Graphics.Capture isn't supported on this PC";
            return false;
        }
        return true;
    } catch (const winrt::hresult_error& e) {
        if (why) *why = "Windows.Graphics.Capture isn't available: " + hresultText(e.code());
        return false;
    }
}

bool ScreenCapture::start(ID3D11Device* d3d, HMONITOR monitor, std::string* error) {
    stop();
    auto impl = std::make_unique<Impl>();
    try {
        Microsoft::WRL::ComPtr<IDXGIDevice> dxgi;
        winrt::check_hresult(d3d->QueryInterface(IID_PPV_ARGS(&dxgi)));
        winrt::com_ptr<::IInspectable> inspectable;
        winrt::check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dxgi.Get(), inspectable.put()));
        impl->device = inspectable.as<wgd::Direct3D11::IDirect3DDevice>();

        auto interop = winrt::get_activation_factory<wgc::GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
        winrt::check_hresult(
            interop->CreateForMonitor(monitor, winrt::guid_of<wgc::GraphicsCaptureItem>(), winrt::put_abi(impl->item)));
        impl->poolSize = impl->item.Size();
        Impl* raw = impl.get();
        impl->closedToken = impl->item.Closed([raw](auto&&, auto&&) { raw->closed = true; });

        impl->pool = wgc::Direct3D11CaptureFramePool::CreateFreeThreaded(
            impl->device, wgd::DirectXPixelFormat::B8G8R8A8UIntNormalized, kBuffers, impl->poolSize);
        impl->session = impl->pool.CreateCaptureSession(impl->item);
        if (ApiInformation::IsPropertyPresent(kSessionClass, L"IsCursorCaptureEnabled"))
            impl->session.IsCursorCaptureEnabled(true);
        if (ApiInformation::IsPropertyPresent(kSessionClass, L"IsBorderRequired")) {
            try {
                impl->session.IsBorderRequired(false);  // no yellow frame round the screen (Windows 11)
            } catch (const winrt::hresult_error&) {
            }
        }
        impl->session.StartCapture();
        LOG("Capturing %s, %d x %d", narrow(std::wstring(impl->item.DisplayName())).c_str(), impl->poolSize.Width,
            impl->poolSize.Height);
    } catch (const winrt::hresult_error& e) {
        if (error) *error = "Couldn't start capturing the screen: " + hresultText(e.code());
        return false;
    }
    impl_ = std::move(impl);
    return true;
}

void ScreenCapture::stop() {
    if (!impl_) return;
    try {
        if (impl_->held) impl_->held.Close();
        if (impl_->item) impl_->item.Closed(impl_->closedToken);
        if (impl_->session) impl_->session.Close();
        if (impl_->pool) impl_->pool.Close();
    } catch (const winrt::hresult_error& e) {
        LOG("Stopping the capture: %s", hresultText(e.code()).c_str());
    }
    impl_.reset();
}

bool ScreenCapture::latest(Frame& out) {
    if (!impl_) return false;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    try {
        if (impl_->held) {
            impl_->held.Close();
            impl_->held = nullptr;
        }
        wgc::Direct3D11CaptureFrame newest{nullptr};
        for (;;) {
            auto f = impl_->pool.TryGetNextFrame();
            if (!f) break;
            if (newest) newest.Close();
            newest = f;
        }
        if (!newest) return false;
        auto content = newest.ContentSize();
        if (content.Width != impl_->poolSize.Width || content.Height != impl_->poolSize.Height) {
            // The screen changed size: new buffers from the next frame on.
            LOG("Screen size changed to %d x %d", content.Width, content.Height);
            impl_->poolSize = content;
            impl_->pool.Recreate(impl_->device, wgd::DirectXPixelFormat::B8G8R8A8UIntNormalized, kBuffers, content);
        }
        auto access =
            newest.Surface().as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
        out.texture.Reset();
        winrt::check_hresult(access->GetInterface(IID_PPV_ARGS(&out.texture)));
        D3D11_TEXTURE2D_DESC desc{};
        out.texture->GetDesc(&desc);
        out.content.width = std::min(static_cast<int>(desc.Width), content.Width);
        out.content.height = std::min(static_cast<int>(desc.Height), content.Height);
        out.timeUs = newest.SystemRelativeTime().count() / 10;  // 100 ns QPC units
        impl_->held = newest;
        return out.content.width > 0 && out.content.height > 0;
    } catch (const winrt::hresult_error& e) {
        LOG("Capture: %s", hresultText(e.code()).c_str());
        return false;
    }
}

bool ScreenCapture::closed() const { return impl_ && impl_->closed.load(); }

Size ScreenCapture::size() const {
    if (!impl_) return {};
    return {impl_->poolSize.Width, impl_->poolSize.Height};
}

}  // namespace mango
