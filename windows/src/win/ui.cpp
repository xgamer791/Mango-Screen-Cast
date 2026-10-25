#include "ui.h"

#include <winsock2.h>
#include <windows.h>
#include <commctrl.h>
#include <shellapi.h>

#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "discovery.h"
#include "log.h"
#include "monitors.h"
#include "net.h"
#include "session.h"
#include "util.h"

namespace mango {

namespace {

constexpr UINT WM_APP_RECEIVERS = WM_APP + 1;
constexpr UINT WM_APP_STATUS = WM_APP + 2;
constexpr UINT WM_APP_TRAY = WM_APP + 3;
constexpr UINT_PTR kRefreshTimer = 1;
constexpr int kIconId = 1;

enum Control {
    IdList = 100,
    IdRefresh,
    IdAddress,
    IdScreen,
    IdSound,
    IdConnect,
    IdStop,
    IdStatus,
    IdListLabel,
    IdAddressLabel,
    IdScreenLabel,
    IdTrayShow = 200,
    IdTrayStop,
    IdTrayQuit,
};

const wchar_t kRegistryKey[] = L"Software\\Mango Screen Cast\\Windows";

std::wstring readSetting(const wchar_t* name, const std::wstring& fallback = L"") {
    wchar_t buf[512];
    DWORD size = sizeof buf;
    if (RegGetValueW(HKEY_CURRENT_USER, kRegistryKey, name, RRF_RT_REG_SZ, nullptr, buf, &size) == ERROR_SUCCESS)
        return buf;
    return fallback;
}

DWORD readNumber(const wchar_t* name, DWORD fallback) {
    DWORD v = 0, size = sizeof v;
    if (RegGetValueW(HKEY_CURRENT_USER, kRegistryKey, name, RRF_RT_REG_DWORD, nullptr, &v, &size) == ERROR_SUCCESS)
        return v;
    return fallback;
}

void writeSetting(const wchar_t* name, const std::wstring& value) {
    RegSetKeyValueW(HKEY_CURRENT_USER, kRegistryKey, name, REG_SZ, value.c_str(),
                    static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
}

void writeNumber(const wchar_t* name, DWORD value) {
    RegSetKeyValueW(HKEY_CURRENT_USER, kRegistryKey, name, REG_DWORD, &value, sizeof value);
}

class MainWindow {
public:
    explicit MainWindow(const Options& options) : options_(options) {}

    bool create(HINSTANCE instance, int show) {
        instance_ = instance;
        WNDCLASSEXW wc{};
        wc.cbSize = sizeof wc;
        wc.lpfnWndProc = &MainWindow::proc;
        wc.hInstance = instance;
        wc.hIcon = LoadIconW(instance, MAKEINTRESOURCEW(kIconId));
        wc.hIconSm = static_cast<HICON>(LoadImageW(instance, MAKEINTRESOURCEW(kIconId), IMAGE_ICON,
                                                   GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), 0));
        wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
        wc.lpszClassName = L"MangoCastWindow";
        RegisterClassExW(&wc);
        hwnd_ = CreateWindowExW(0, wc.lpszClassName, L"Mango Cast", WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
                                CW_USEDEFAULT, CW_USEDEFAULT, 520, 420, nullptr, nullptr, instance, this);
        if (!hwnd_) return false;
        ShowWindow(hwnd_, show);
        UpdateWindow(hwnd_);
        return true;
    }

private:
    static LRESULT CALLBACK proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
        MainWindow* self = reinterpret_cast<MainWindow*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        if (msg == WM_NCCREATE) {
            self = static_cast<MainWindow*>(reinterpret_cast<CREATESTRUCTW*>(lp)->lpCreateParams);
            self->hwnd_ = hwnd;
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
        }
        return self ? self->handle(msg, wp, lp) : DefWindowProcW(hwnd, msg, wp, lp);
    }

    int px(int dips) const { return MulDiv(dips, static_cast<int>(dpi_), 96); }

    HWND make(const wchar_t* cls, const wchar_t* text, DWORD style, int id, DWORD ex = 0) {
        HWND h = CreateWindowExW(ex, cls, text, WS_CHILD | WS_VISIBLE | style, 0, 0, 10, 10, hwnd_,
                                 reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)), instance_, nullptr);
        return h;
    }

    void onCreate() {
        dpi_ = GetDpiForWindow(hwnd_);
        listLabel_ = make(L"STATIC", L"Mango receivers on your network:", 0, IdListLabel);
        refresh_ = make(L"BUTTON", L"Refresh", WS_TABSTOP | BS_PUSHBUTTON, IdRefresh);
        list_ = make(WC_LISTVIEWW, L"", WS_TABSTOP | WS_BORDER | LVS_REPORT | LVS_SINGLESEL | LVS_SHOWSELALWAYS |
                                           LVS_NOSORTHEADER, IdList);
        ListView_SetExtendedListViewStyle(list_, LVS_EX_FULLROWSELECT | LVS_EX_DOUBLEBUFFER);
        addressLabel_ = make(L"STATIC", L"Or an address:", 0, IdAddressLabel);
        address_ = make(L"EDIT", L"", WS_TABSTOP | WS_BORDER | ES_AUTOHSCROLL, IdAddress);
        SendMessageW(address_, EM_SETCUEBANNER, TRUE, reinterpret_cast<LPARAM>(L"192.168.1.20 or tv.local:7270"));
        screenLabel_ = make(L"STATIC", L"Screen:", 0, IdScreenLabel);
        screen_ = make(L"COMBOBOX", L"", WS_TABSTOP | CBS_DROPDOWNLIST | WS_VSCROLL, IdScreen);
        sound_ = make(L"BUTTON", L"Send sound", WS_TABSTOP | BS_AUTOCHECKBOX, IdSound);
        connect_ = make(L"BUTTON", L"Connect", WS_TABSTOP | BS_DEFPUSHBUTTON, IdConnect);
        stop_ = make(L"BUTTON", L"Stop", WS_TABSTOP | BS_PUSHBUTTON, IdStop);
        status_ = make(L"STATIC", L"Looking for receivers...", SS_LEFT | SS_NOPREFIX, IdStatus);

        LVCOLUMNW col{};
        col.mask = LVCF_TEXT | LVCF_WIDTH;
        const wchar_t* names[] = {L"Name", L"Model", L"Address"};
        for (int i = 0; i < 3; i++) {
            col.pszText = const_cast<LPWSTR>(names[i]);
            col.cx = 100;
            ListView_InsertColumn(list_, i, &col);
        }
        address_text(readSetting(L"LastAddress"));
        SendMessageW(sound_, BM_SETCHECK, readNumber(L"Sound", 1) && !options_.noAudio ? BST_CHECKED : BST_UNCHECKED, 0);
        fillScreens();
        applyDpi();
        updateButtons();
        startBrowsing();
        SetTimer(hwnd_, kRefreshTimer, 15000, nullptr);
        addTrayIcon();
    }

    void address_text(const std::wstring& s) { SetWindowTextW(address_, s.c_str()); }

    void applyDpi() {
        if (font_) DeleteObject(font_);
        NONCLIENTMETRICSW ncm{};
        ncm.cbSize = sizeof ncm;
        SystemParametersInfoForDpi(SPI_GETNONCLIENTMETRICS, sizeof ncm, &ncm, 0, dpi_);
        font_ = CreateFontIndirectW(&ncm.lfMessageFont);
        for (HWND h : {listLabel_, refresh_, list_, addressLabel_, address_, screenLabel_, screen_, sound_, connect_,
                       stop_, status_})
            SendMessageW(h, WM_SETFONT, reinterpret_cast<WPARAM>(font_), TRUE);
        // The client area we want, as a window size at this DPI.
        RECT rc{0, 0, px(500), px(372)};
        AdjustWindowRectExForDpi(&rc, static_cast<DWORD>(GetWindowLongW(hwnd_, GWL_STYLE)), FALSE, 0, dpi_);
        SetWindowPos(hwnd_, nullptr, 0, 0, rc.right - rc.left, rc.bottom - rc.top, SWP_NOMOVE | SWP_NOZORDER);
        layout();
    }

    void layout() {
        const int m = px(12), w = px(500) - 2 * m;
        auto place = [](HWND h, int x, int y, int cx, int cy) { MoveWindow(h, x, y, cx, cy, TRUE); };
        place(listLabel_, m, m + px(4), w - px(100), px(20));
        place(refresh_, m + w - px(90), m, px(90), px(28));
        place(list_, m, m + px(34), w, px(150));
        ListView_SetColumnWidth(list_, 0, (w - px(4)) * 40 / 100);
        ListView_SetColumnWidth(list_, 1, (w - px(4)) * 35 / 100);
        ListView_SetColumnWidth(list_, 2, (w - px(4)) * 25 / 100);
        int y = m + px(196);
        place(addressLabel_, m, y + px(3), px(100), px(20));
        place(address_, m + px(104), y, w - px(104), px(24));
        y += px(36);
        place(screenLabel_, m, y + px(3), px(100), px(20));
        place(screen_, m + px(104), y, px(220), px(200));
        place(sound_, m + px(340), y + px(1), w - px(340), px(22));
        y += px(40);
        place(connect_, m, y, px(110), px(30));
        place(stop_, m + px(120), y, px(110), px(30));
        y += px(42);
        place(status_, m, y, w, px(40));
    }

    void fillScreens() {
        SendMessageW(screen_, CB_RESETCONTENT, 0, 0);
        monitors_ = listMonitors();
        for (const auto& mon : monitors_)
            SendMessageW(screen_, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(widen(mon.label()).c_str()));
        DWORD saved = options_.monitor > 0 ? static_cast<DWORD>(options_.monitor) : readNumber(L"Monitor", 1);
        if (saved < 1 || saved > monitors_.size()) saved = 1;
        SendMessageW(screen_, CB_SETCURSEL, saved - 1, 0);
    }

    void startBrowsing() {
        std::string why;
        HWND hwnd = hwnd_;
        if (!browser_.start([hwnd] { PostMessageW(hwnd, WM_APP_RECEIVERS, 0, 0); }, &why)) {
            LOG("%s", why.c_str());
            if (!streaming()) setStatus(why + " You can still type a receiver's address.");
        } else if (!streaming()) {
            setStatus("Looking for receivers...");
        }
    }

    void showReceivers() {
        std::string selected = selectedInstance();
        receivers_ = browser_.receivers();
        ListView_DeleteAllItems(list_);
        std::string rememberedId = narrow(readSetting(L"LastReceiverId"));
        int select = -1;
        for (size_t i = 0; i < receivers_.size(); i++) {
            const auto& r = receivers_[i];
            std::wstring name = widen(r.name), model = widen(r.model()), addr = widen(r.target());
            LVITEMW item{};
            item.mask = LVIF_TEXT;
            item.iItem = static_cast<int>(i);
            item.pszText = name.data();
            int at = ListView_InsertItem(list_, &item);
            ListView_SetItemText(list_, at, 1, model.data());
            ListView_SetItemText(list_, at, 2, addr.data());
            if (r.instance == selected) select = at;
            if (select < 0 && selected.empty() && !rememberedId.empty() && r.id() == rememberedId) select = at;
        }
        if (select >= 0) {
            ListView_SetItemState(list_, select, LVIS_SELECTED | LVIS_FOCUSED, LVIS_SELECTED | LVIS_FOCUSED);
            ListView_EnsureVisible(list_, select, FALSE);
        }
        if (!streaming()) {
            setStatus(receivers_.empty() ? "Looking for receivers... (open Mango Screen Cast on the TV)"
                                         : std::to_string(receivers_.size()) +
                                               (receivers_.size() == 1 ? " receiver found." : " receivers found."));
        }
    }

    std::string selectedInstance() const {
        int i = ListView_GetNextItem(list_, -1, LVNI_SELECTED);
        if (i < 0 || static_cast<size_t>(i) >= receivers_.size()) return {};
        return receivers_[static_cast<size_t>(i)].instance;
    }

    bool streaming() const { return thread_.joinable() && !sessionDone_; }

    void setStatus(const std::string& text) {
        SetWindowTextW(status_, widen(text).c_str());
        if (tray_) {
            NOTIFYICONDATAW nid = trayData();
            nid.uFlags = NIF_TIP;
            std::wstring tip = widen(text).substr(0, 127);
            wcsncpy_s(nid.szTip, tip.c_str(), _TRUNCATE);
            Shell_NotifyIconW(NIM_MODIFY, &nid);
        }
    }

    void updateButtons() {
        bool busy = streaming();
        EnableWindow(connect_, !busy);
        EnableWindow(stop_, busy);
        EnableWindow(screen_, !busy);
        EnableWindow(sound_, !busy);
        EnableWindow(address_, !busy);
        EnableWindow(list_, !busy);
    }

    void connect() {
        if (streaming()) return;
        joinSession();
        SessionOptions so;
        int i = ListView_GetNextItem(list_, -1, LVNI_SELECTED);
        wchar_t text[256] = {};
        GetWindowTextW(address_, text, 256);
        std::string typed = narrow(text);
        while (!typed.empty() && typed.back() == ' ') typed.pop_back();
        while (!typed.empty() && typed.front() == ' ') typed.erase(typed.begin());
        if (!typed.empty() && GetFocus() == address_) i = -1;  // just typed an address: use it
        if (i >= 0 && static_cast<size_t>(i) < receivers_.size()) {
            const auto& r = receivers_[static_cast<size_t>(i)];
            so.target = r.target();
            so.targetName = r.name;
            writeSetting(L"LastReceiverId", widen(r.id()));
            writeSetting(L"LastReceiverName", widen(r.name));
        } else if (!typed.empty()) {
            so.target = typed;
            writeSetting(L"LastAddress", widen(typed));
            writeSetting(L"LastReceiverId", L"");
        } else {
            setStatus("Choose a receiver, or type its address.");
            return;
        }
        int screen = static_cast<int>(SendMessageW(screen_, CB_GETCURSEL, 0, 0));
        so.monitor = screen >= 0 ? screen + 1 : 0;
        so.audio = SendMessageW(sound_, BM_GETCHECK, 0, 0) == BST_CHECKED;
        so.codec = options_.codec;
        so.fps = options_.fps;
        so.bitrateMbps = options_.bitrateMbps;
        so.encoder = options_.encoder;
        writeNumber(L"Monitor", static_cast<DWORD>(so.monitor));
        writeNumber(L"Sound", so.audio ? 1 : 0);

        session_ = std::make_unique<Session>();
        sessionDone_ = false;
        HWND hwnd = hwnd_;
        Session* session = session_.get();
        thread_ = std::thread([hwnd, session, so] {
            ComScope com;
            std::string message;
            session->run(
                so,
                [hwnd](const SessionStatus& s) {
                    PostMessageW(hwnd, WM_APP_STATUS, 0, reinterpret_cast<LPARAM>(new SessionStatus(s)));
                },
                &message);
        });
        updateButtons();
    }

    void stopSession() {
        if (session_) session_->stop();
    }

    void joinSession() {
        if (thread_.joinable()) thread_.join();
        session_.reset();
    }

    void onStatus(const SessionStatus& s) {
        if (s.state == SessionStatus::State::Ended) {
            sessionDone_ = true;
            joinSession();
            setStatus(s.text);
            updateButtons();
            showReceiversQuietly();
        } else {
            setStatus(s.text);
            updateButtons();
        }
    }

    void showReceiversQuietly() {
        // Refresh the list without replacing the session's last words.
        std::wstring keep(512, L'\0');
        keep.resize(static_cast<size_t>(GetWindowTextW(status_, keep.data(), 512)));
        showReceivers();
        SetWindowTextW(status_, keep.c_str());
    }

    NOTIFYICONDATAW trayData() const {
        NOTIFYICONDATAW nid{};
        nid.cbSize = sizeof nid;
        nid.hWnd = hwnd_;
        nid.uID = 1;
        return nid;
    }

    void addTrayIcon() {
        NOTIFYICONDATAW nid = trayData();
        nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
        nid.uCallbackMessage = WM_APP_TRAY;
        nid.hIcon = static_cast<HICON>(LoadImageW(instance_, MAKEINTRESOURCEW(kIconId), IMAGE_ICON,
                                                  GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), 0));
        wcsncpy_s(nid.szTip, L"Mango Cast", _TRUNCATE);
        tray_ = Shell_NotifyIconW(NIM_ADD, &nid) != FALSE;
    }

    void removeTrayIcon() {
        if (!tray_) return;
        NOTIFYICONDATAW nid = trayData();
        Shell_NotifyIconW(NIM_DELETE, &nid);
        tray_ = false;
    }

    void trayMenu() {
        HMENU menu = CreatePopupMenu();
        AppendMenuW(menu, MF_STRING, IdTrayShow, L"Show Mango Cast");
        AppendMenuW(menu, MF_STRING | (streaming() ? 0 : MF_GRAYED), IdTrayStop, L"Stop");
        AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
        AppendMenuW(menu, MF_STRING, IdTrayQuit, L"Quit");
        POINT pt;
        GetCursorPos(&pt);
        SetForegroundWindow(hwnd_);
        TrackPopupMenu(menu, TPM_RIGHTBUTTON, pt.x, pt.y, 0, hwnd_, nullptr);
        DestroyMenu(menu);
    }

    void showWindow() {
        ShowWindow(hwnd_, SW_SHOW);
        ShowWindow(hwnd_, SW_RESTORE);
        SetForegroundWindow(hwnd_);
    }

    LRESULT handle(UINT msg, WPARAM wp, LPARAM lp) {
        switch (msg) {
            case WM_CREATE:
                onCreate();
                return 0;
            case WM_DPICHANGED: {
                dpi_ = HIWORD(wp);
                auto* r = reinterpret_cast<RECT*>(lp);
                SetWindowPos(hwnd_, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top,
                             SWP_NOZORDER | SWP_NOACTIVATE);
                applyDpi();
                return 0;
            }
            case WM_COMMAND:
                switch (LOWORD(wp)) {
                    case IdConnect: case IDOK: connect(); return 0;
                    case IdStop: case IdTrayStop: stopSession(); return 0;
                    case IdRefresh:
                        browser_.stop();
                        startBrowsing();
                        fillScreensIfIdle();
                        return 0;
                    case IdTrayShow: showWindow(); return 0;
                    case IdTrayQuit: DestroyWindow(hwnd_); return 0;
                    case IdAddress:
                        if (HIWORD(wp) == EN_SETFOCUS) ListView_SetItemState(list_, -1, 0, LVIS_SELECTED);
                        return 0;
                }
                break;
            case WM_NOTIFY: {
                auto* hdr = reinterpret_cast<NMHDR*>(lp);
                if (hdr->idFrom == IdList && hdr->code == NM_DBLCLK) {
                    connect();
                    return 0;
                }
                break;
            }
            case WM_TIMER:
                if (wp == kRefreshTimer && !streaming()) {
                    // Look again now and then: receivers come and go.
                    browser_.stop();
                    startBrowsing();
                    showReceiversQuietly();
                }
                return 0;
            case WM_APP_RECEIVERS:
                if (streaming()) {
                    receivers_ = browser_.receivers();
                } else {
                    showReceivers();
                }
                return 0;
            case WM_APP_STATUS: {
                std::unique_ptr<SessionStatus> s(reinterpret_cast<SessionStatus*>(lp));
                onStatus(*s);
                return 0;
            }
            case WM_APP_TRAY:
                if (LOWORD(lp) == WM_RBUTTONUP || LOWORD(lp) == WM_CONTEXTMENU) trayMenu();
                else if (LOWORD(lp) == WM_LBUTTONUP || LOWORD(lp) == WM_LBUTTONDBLCLK) showWindow();
                return 0;
            case WM_CLOSE:
                DestroyWindow(hwnd_);
                return 0;
            case WM_DESTROY:
                KillTimer(hwnd_, kRefreshTimer);
                stopSession();
                joinSession();
                browser_.stop();
                removeTrayIcon();
                if (font_) DeleteObject(font_);
                PostQuitMessage(0);
                return 0;
        }
        return DefWindowProcW(hwnd_, msg, wp, lp);
    }

    void fillScreensIfIdle() {
        if (!streaming()) fillScreens();
    }

    Options options_;
    HINSTANCE instance_ = nullptr;
    HWND hwnd_ = nullptr;
    HWND listLabel_ = nullptr, refresh_ = nullptr, list_ = nullptr, addressLabel_ = nullptr, address_ = nullptr,
         screenLabel_ = nullptr, screen_ = nullptr, sound_ = nullptr, connect_ = nullptr, stop_ = nullptr,
         status_ = nullptr;
    HFONT font_ = nullptr;
    UINT dpi_ = 96;
    bool tray_ = false;
    Browser browser_;
    std::vector<ReceiverInfo> receivers_;
    std::vector<MonitorInfo> monitors_;
    std::unique_ptr<Session> session_;
    std::thread thread_;
    bool sessionDone_ = true;
};

}  // namespace

int runWindow(HINSTANCE instance, const Options& options, int show) {
    log::openFile(options.logFile.empty() ? log::defaultPath() : widen(options.logFile));
    LOG("Mango Cast for Windows %s on %s", MANGO_VERSION, windowsName().c_str());
    ComScope com(false);
    INITCOMMONCONTROLSEX icc{sizeof icc, ICC_LISTVIEW_CLASSES | ICC_STANDARD_CLASSES};
    InitCommonControlsEx(&icc);
    std::string why;
    startWinsock(&why);
    MainWindow window(options);
    if (!window.create(instance, show)) return 1;
    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        if (!IsDialogMessageW(GetActiveWindow(), &msg)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
    return 0;
}

}  // namespace mango
