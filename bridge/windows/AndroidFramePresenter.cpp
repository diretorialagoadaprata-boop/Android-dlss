#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <d3d11.h>
#include <dxgi.h>
#include <d3dcompiler.h>
#include <cstdint>
#include <cstdio>
#include <vector>
#include <string>
#include <algorithm>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "d3dcompiler.lib")

#pragma pack(push, 1)
struct FrameHeader {
    char magic[8];              // "ADLSS01\0"
    uint32_t headerBytes;       // 64
    uint32_t version;           // 1
    uint64_t sequence;
    uint32_t width;
    uint32_t height;
    uint32_t rowStride;
    uint32_t pixelFormat;       // 1 = RGBA8888
    uint64_t timestampNs;
    uint32_t payloadBytes;
    uint32_t flags;
    uint8_t reserved[8];
};
#pragma pack(pop)
static_assert(sizeof(FrameHeader) == 64, "FrameHeader must stay 64 bytes");

struct Vertex { float x, y, z, u, v; };

static FILE *gLog = nullptr;
static HWND gWnd = nullptr;
static ID3D11Device *gDev = nullptr;
static ID3D11DeviceContext *gCtx = nullptr;
static IDXGISwapChain *gSwap = nullptr;
static ID3D11RenderTargetView *gRtv = nullptr;
static ID3D11Texture2D *gDepth = nullptr;
static ID3D11DepthStencilView *gDsv = nullptr;
static ID3D11DepthStencilState *gDepthState = nullptr;
static ID3D11VertexShader *gVs = nullptr;
static ID3D11PixelShader *gPs = nullptr;
static ID3D11InputLayout *gLayout = nullptr;
static ID3D11Buffer *gVb = nullptr;
static ID3D11SamplerState *gSampler = nullptr;
static ID3D11Texture2D *gFrameTex = nullptr;
static ID3D11ShaderResourceView *gFrameSrv = nullptr;
static uint32_t gFrameW = 0, gFrameH = 0;

static void Log(const char *fmt, ...) {
    if (!gLog) return;
    va_list ap; va_start(ap, fmt); vfprintf(gLog, fmt, ap); va_end(ap);
    fputc('\n', gLog); fflush(gLog);
}

template<class T> static void Release(T *&p) { if (p) { p->Release(); p = nullptr; } }

static LRESULT CALLBACK WndProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == WM_CLOSE) { DestroyWindow(h); return 0; }
    if (m == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcW(h, m, w, l);
}

static bool MakeTargets(UINT w, UINT h) {
    Release(gRtv); Release(gDepth); Release(gDsv);
    ID3D11Texture2D *bb = nullptr;
    if (FAILED(gSwap->GetBuffer(0, __uuidof(ID3D11Texture2D), (void **)&bb))) return false;
    HRESULT hr = gDev->CreateRenderTargetView(bb, nullptr, &gRtv);
    Release(bb);
    if (FAILED(hr)) return false;

    D3D11_TEXTURE2D_DESC dd{};
    dd.Width = std::max<UINT>(1, w); dd.Height = std::max<UINT>(1, h);
    dd.MipLevels = 1; dd.ArraySize = 1; dd.Format = DXGI_FORMAT_D24_UNORM_S8_UINT;
    dd.SampleDesc.Count = 1; dd.Usage = D3D11_USAGE_DEFAULT; dd.BindFlags = D3D11_BIND_DEPTH_STENCIL;
    if (FAILED(gDev->CreateTexture2D(&dd, nullptr, &gDepth))) return false;
    if (FAILED(gDev->CreateDepthStencilView(gDepth, nullptr, &gDsv))) return false;
    return true;
}

static bool InitD3D(HWND hwnd) {
    RECT rc{}; GetClientRect(hwnd, &rc);
    DXGI_SWAP_CHAIN_DESC sd{};
    sd.BufferCount = 2;
    sd.BufferDesc.Width = std::max<LONG>(1, rc.right - rc.left);
    sd.BufferDesc.Height = std::max<LONG>(1, rc.bottom - rc.top);
    sd.BufferDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd.OutputWindow = hwnd;
    sd.SampleDesc.Count = 1;
    sd.Windowed = TRUE;
    sd.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;
    D3D_FEATURE_LEVEL fl;
    HRESULT hr = D3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0,
        nullptr, 0, D3D11_SDK_VERSION, &sd, &gSwap, &gDev, &fl, &gCtx);
    if (FAILED(hr)) {
        Log("hardware D3D11 failed 0x%08X; trying WARP", (unsigned)hr);
        hr = D3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, 0,
            nullptr, 0, D3D11_SDK_VERSION, &sd, &gSwap, &gDev, &fl, &gCtx);
    }
    if (FAILED(hr)) { Log("D3D11CreateDeviceAndSwapChain failed 0x%08X", (unsigned)hr); return false; }
    if (!MakeTargets(sd.BufferDesc.Width, sd.BufferDesc.Height)) return false;

    static const char *vsSrc =
        "struct V{float3 p:POSITION;float2 uv:TEXCOORD0;};"
        "struct O{float4 p:SV_POSITION;float2 uv:TEXCOORD0;};"
        "O main(V i){O o;o.p=float4(i.p,1);o.uv=i.uv;return o;}";
    static const char *psSrc =
        "Texture2D t0:register(t0);SamplerState s0:register(s0);"
        "float4 main(float4 p:SV_POSITION,float2 uv:TEXCOORD0):SV_TARGET{return t0.Sample(s0,uv);}";
    ID3DBlob *vsb = nullptr, *psb = nullptr, *err = nullptr;
    if (FAILED(D3DCompile(vsSrc, strlen(vsSrc), nullptr, nullptr, nullptr, "main", "vs_4_0", 0, 0, &vsb, &err))) {
        Log("vertex shader compile failed"); Release(err); return false;
    }
    Release(err);
    if (FAILED(D3DCompile(psSrc, strlen(psSrc), nullptr, nullptr, nullptr, "main", "ps_4_0", 0, 0, &psb, &err))) {
        Log("pixel shader compile failed"); Release(vsb); Release(err); return false;
    }
    Release(err);
    if (FAILED(gDev->CreateVertexShader(vsb->GetBufferPointer(), vsb->GetBufferSize(), nullptr, &gVs))) return false;
    if (FAILED(gDev->CreatePixelShader(psb->GetBufferPointer(), psb->GetBufferSize(), nullptr, &gPs))) return false;
    D3D11_INPUT_ELEMENT_DESC il[] = {
        {"POSITION",0,DXGI_FORMAT_R32G32B32_FLOAT,0,0,D3D11_INPUT_PER_VERTEX_DATA,0},
        {"TEXCOORD",0,DXGI_FORMAT_R32G32_FLOAT,0,12,D3D11_INPUT_PER_VERTEX_DATA,0}
    };
    if (FAILED(gDev->CreateInputLayout(il, 2, vsb->GetBufferPointer(), vsb->GetBufferSize(), &gLayout))) return false;
    Release(vsb); Release(psb);

    const Vertex v[] = {
        {-1, 1, .5f, 0,0}, {1, 1, .5f, 1,0}, {-1,-1,.5f,0,1},
        {-1,-1,.5f,0,1}, {1, 1, .5f,1,0}, {1,-1,.5f,1,1}
    };
    D3D11_BUFFER_DESC bd{}; bd.ByteWidth = sizeof(v); bd.Usage = D3D11_USAGE_IMMUTABLE; bd.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    D3D11_SUBRESOURCE_DATA init{}; init.pSysMem = v;
    if (FAILED(gDev->CreateBuffer(&bd, &init, &gVb))) return false;

    D3D11_SAMPLER_DESC ss{}; ss.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
    ss.AddressU = ss.AddressV = ss.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
    if (FAILED(gDev->CreateSamplerState(&ss, &gSampler))) return false;

    D3D11_DEPTH_STENCIL_DESC ds{}; ds.DepthEnable = TRUE; ds.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL; ds.DepthFunc = D3D11_COMPARISON_LESS;
    if (FAILED(gDev->CreateDepthStencilState(&ds, &gDepthState))) return false;
    return true;
}

static bool EnsureFrameTexture(uint32_t w, uint32_t h) {
    if (gFrameTex && w == gFrameW && h == gFrameH) return true;
    Release(gFrameSrv); Release(gFrameTex); gFrameW = gFrameH = 0;
    D3D11_TEXTURE2D_DESC td{};
    td.Width = w; td.Height = h; td.MipLevels = 1; td.ArraySize = 1;
    td.Format = DXGI_FORMAT_R8G8B8A8_UNORM; td.SampleDesc.Count = 1;
    td.Usage = D3D11_USAGE_DYNAMIC; td.BindFlags = D3D11_BIND_SHADER_RESOURCE; td.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    if (FAILED(gDev->CreateTexture2D(&td, nullptr, &gFrameTex))) return false;
    if (FAILED(gDev->CreateShaderResourceView(gFrameTex, nullptr, &gFrameSrv))) return false;
    gFrameW = w; gFrameH = h;
    Log("frame texture %ux%u", w, h);
    return true;
}

static bool ReadNewestFrame(const std::wstring &path, uint64_t &lastSeq, FrameHeader &hdr, std::vector<uint8_t> &pixels) {
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    DWORD got = 0;
    FrameHeader h{};
    bool ok = ReadFile(f, &h, sizeof(h), &got, nullptr) && got == sizeof(h);
    if (!ok || memcmp(h.magic, "ADLSS01", 7) != 0 || h.headerBytes != sizeof(FrameHeader) || h.version != 1 ||
        h.pixelFormat != 1 || h.sequence == 0 || h.sequence == lastSeq || h.width < 16 || h.height < 16 ||
        h.width > 8192 || h.height > 8192 || h.rowStride < h.width * 4 ||
        h.payloadBytes < h.rowStride * (h.height - 1) + h.width * 4 || h.payloadBytes > 512u * 1024u * 1024u) {
        CloseHandle(f); return false;
    }
    pixels.resize(h.payloadBytes);
    LARGE_INTEGER pos{}; pos.QuadPart = h.headerBytes;
    SetFilePointerEx(f, pos, nullptr, FILE_BEGIN);
    got = 0;
    ok = ReadFile(f, pixels.data(), h.payloadBytes, &got, nullptr) && got == h.payloadBytes;
    CloseHandle(f);
    if (!ok) return false;

    // The Android writer publishes the header only after the payload. Re-read sequence
    // once to reject a frame that changed while Wine was reading it.
    f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                    nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    FrameHeader verify{}; got = 0;
    ok = ReadFile(f, &verify, sizeof(verify), &got, nullptr) && got == sizeof(verify);
    CloseHandle(f);
    if (!ok || verify.sequence != h.sequence) return false;
    hdr = h; lastSeq = h.sequence; return true;
}

static bool UploadFrame(const FrameHeader &h, const std::vector<uint8_t> &p) {
    if (!EnsureFrameTexture(h.width, h.height)) return false;
    D3D11_MAPPED_SUBRESOURCE m{};
    if (FAILED(gCtx->Map(gFrameTex, 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) return false;
    const uint8_t *src = p.data();
    uint8_t *dst = static_cast<uint8_t *>(m.pData);
    for (uint32_t y = 0; y < h.height; ++y)
        memcpy(dst + size_t(y) * m.RowPitch, src + size_t(y) * h.rowStride, size_t(h.width) * 4);
    gCtx->Unmap(gFrameTex, 0);
    return true;
}

static void Render() {
    RECT rc{}; GetClientRect(gWnd, &rc);
    float cw = float(std::max<LONG>(1, rc.right - rc.left)), ch = float(std::max<LONG>(1, rc.bottom - rc.top));
    float srcAspect = gFrameH ? float(gFrameW) / float(gFrameH) : 1.0f;
    float dstAspect = cw / ch;
    D3D11_VIEWPORT vp{};
    if (dstAspect > srcAspect) { vp.Height = ch; vp.Width = ch * srcAspect; vp.TopLeftX = (cw - vp.Width) * .5f; }
    else { vp.Width = cw; vp.Height = cw / srcAspect; vp.TopLeftY = (ch - vp.Height) * .5f; }
    vp.MinDepth = 0; vp.MaxDepth = 1;
    const float clear[4] = {0,0,0,1};
    gCtx->ClearRenderTargetView(gRtv, clear);
    gCtx->ClearDepthStencilView(gDsv, D3D11_CLEAR_DEPTH | D3D11_CLEAR_STENCIL, 1.0f, 0);
    gCtx->RSSetViewports(1, &vp);
    gCtx->OMSetRenderTargets(1, &gRtv, gDsv);
    gCtx->OMSetDepthStencilState(gDepthState, 0);
    UINT stride = sizeof(Vertex), off = 0;
    gCtx->IASetInputLayout(gLayout); gCtx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    gCtx->IASetVertexBuffers(0, 1, &gVb, &stride, &off);
    gCtx->VSSetShader(gVs, nullptr, 0); gCtx->PSSetShader(gPs, nullptr, 0);
    gCtx->PSSetShaderResources(0, 1, &gFrameSrv); gCtx->PSSetSamplers(0, 1, &gSampler);
    gCtx->Draw(6, 0);
    gSwap->Present(1, 0);
}

static void Cleanup() {
    if (gCtx) gCtx->ClearState();
    Release(gFrameSrv); Release(gFrameTex); Release(gSampler); Release(gVb); Release(gLayout);
    Release(gPs); Release(gVs); Release(gDepthState); Release(gDsv); Release(gDepth); Release(gRtv);
    Release(gSwap); Release(gCtx); Release(gDev);
    if (gLog) { fclose(gLog); gLog = nullptr; }
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE, PWSTR, int) {
    _wfopen_s(&gLog, L"android-frame-presenter.log", L"wb");
    Log("AndroidFramePresenter starting");

    int argc = 0; LPWSTR *argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    std::wstring framePath = (argc > 1) ? argv[1] : L"C:\\DLSS5ForAll\\android-frame.bin";
    if (argv) LocalFree(argv);
    Log("frame path: %ls", framePath.c_str());

    WNDCLASSEXW wc{sizeof(wc)}; wc.lpfnWndProc = WndProc; wc.hInstance = inst; wc.lpszClassName = L"AndroidDLSSFramePresenter";
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW); RegisterClassExW(&wc);
    gWnd = CreateWindowExW(0, wc.lpszClassName, L"Android DLSS Bridge", WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                           CW_USEDEFAULT, CW_USEDEFAULT, 1280, 720, nullptr, nullptr, inst, nullptr);
    if (!gWnd || !InitD3D(gWnd)) { MessageBoxW(nullptr, L"Falha ao iniciar D3D11.", L"Android DLSS Bridge", MB_ICONERROR); Cleanup(); return 2; }

    uint64_t lastSeq = 0; FrameHeader hdr{}; std::vector<uint8_t> pixels;
    MSG msg{};
    while (msg.message != WM_QUIT) {
        while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) { TranslateMessage(&msg); DispatchMessageW(&msg); }
        if (ReadNewestFrame(framePath, lastSeq, hdr, pixels)) {
            if (UploadFrame(hdr, pixels)) Render();
            else Log("upload failed seq=%llu", (unsigned long long)hdr.sequence);
        } else Sleep(1);
    }
    Cleanup(); return 0;
}
