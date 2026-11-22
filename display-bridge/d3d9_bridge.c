#define COBJMACROS
#include <windows.h>
#include <d3d9.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#define FRAME_MAGIC 0x33465254u
#define MAX_WIDTH 1048u
#define MAX_HEIGHT 790u
/* Minimum spacing between captured frames: every frame up to 60 fps (the game's own rate with DXVK). */
#define SAMPLE_INTERVAL_MS 15u
#define CAMERA_VISIBLE 1u
#define IN_OFFICE 2u
#define MONITOR_SWITCH 4u
#define MAINTENANCE_VISIBLE 8u
#define MAINTENANCE_SWITCH 16u

typedef struct FrameExchange {
    uint32_t magic, version;
    volatile LONG sequence;
    uint32_t width, height, presentation, flags, capture_us;
    uint32_t reserved[8];
    uint32_t pixels[MAX_WIDTH * MAX_HEIGHT];
} FrameExchange;

_Static_assert(offsetof(FrameExchange, pixels) == 64, "frame data must start on a cache line");

typedef IDirect3D9 *(WINAPI *Create9Fn)(UINT);
typedef HRESULT (WINAPI *Create9ExFn)(UINT, IDirect3D9Ex **);
typedef HRESULT (STDMETHODCALLTYPE *CreateDeviceFn)(IDirect3D9 *, UINT, D3DDEVTYPE, HWND,
    DWORD, D3DPRESENT_PARAMETERS *, IDirect3DDevice9 **);
typedef HRESULT (STDMETHODCALLTYPE *SetRenderTargetFn)(IDirect3DDevice9 *, DWORD, IDirect3DSurface9 *);
typedef HRESULT (STDMETHODCALLTYPE *PresentFn)(IDirect3DDevice9 *, const RECT *, const RECT *, HWND, const RGNDATA *);
typedef HRESULT (STDMETHODCALLTYPE *ResetFn)(IDirect3DDevice9 *, D3DPRESENT_PARAMETERS *);

static HMODULE real_d3d9;
static CreateDeviceFn original_create_device;
static SetRenderTargetFn original_set_render_target;
static PresentFn original_present;
static ResetFn original_reset;
static uint32_t presentation;
static DWORD last_sample_at, last_present_at, frame_interval = 33;
static BOOL sample_frame;
static BOOL reader_active;
static DWORD last_reader_check;
static IDirect3DDevice9 *cached_device;
static IDirect3DSurface9 *pending_office, *pending_scene;

static void log_event(const char *label, unsigned first, unsigned second) {
    HANDLE file = CreateFileW(L"D:\\thor-render.log", FILE_APPEND_DATA,
        FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return;
    char line[160];
    int size = snprintf(line, sizeof(line), "%s %u %u\r\n", label, first, second);
    DWORD written;
    if (size > 0 && (size_t)size < sizeof(line)) WriteFile(file, line, size, &written, NULL);
    CloseHandle(file);
}

static FARPROC get_proc(const char *name) {
    if (!real_d3d9) {
        /* DXVK (Direct3D 9 straight to Vulkan) when the app installed it, else Wine's own d3d9. */
        real_d3d9 = LoadLibraryW(L"D:\\thor-dxvk-d3d9.dll");
        if (real_d3d9) log_event("backend_dxvk", 0, 0);
        else real_d3d9 = LoadLibraryW(L"D:\\thor-wined3d9.dll");
        if (!real_d3d9) log_event("backend_load_error", GetLastError(), 0);
    }
    return real_d3d9 ? GetProcAddress(real_d3d9, name) : NULL;
}

static FrameExchange *map_frames(const wchar_t *path) {
    HANDLE file = CreateFileW(path, GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) {
        log_event("frame_file_error", GetLastError(), 0);
        return NULL;
    }
    HANDLE mapping = CreateFileMappingW(file, NULL, PAGE_READWRITE, 0, sizeof(FrameExchange), NULL);
    CloseHandle(file);
    if (!mapping) {
        log_event("frame_mapping_error", GetLastError(), 0);
        return NULL;
    }
    FrameExchange *frames = MapViewOfFile(mapping, FILE_MAP_WRITE, 0, 0, sizeof(FrameExchange));
    CloseHandle(mapping);
    if (frames) {
        frames->magic = FRAME_MAGIC;
        frames->version = 1;
        InterlockedExchange(&frames->sequence, 0);
    }
    return frames;
}

static void set_pending(IDirect3DSurface9 **slot, IDirect3DSurface9 *surface) {
    if (*slot) IDirect3DSurface9_Release(*slot);
    *slot = surface;
    if (surface) IDirect3DSurface9_AddRef(surface);
}

static void release_pending(void) {
    set_pending(&pending_office, NULL);
    set_pending(&pending_scene, NULL);
}


/* When ventilation fails the game darkens the whole picture: roughly a multiply down to a fifth plus
   a faint blue fog. The HUD text is the brightest thing on screen, so its level (255 normally) scales
   every threshold below. At full brightness the scaled thresholds equal the original fixed ones. */
#define SCALED(value, reference) (((value) * (reference) + 254u) / 255u)

/* When ventilation fails the game darkens the whole picture: roughly a multiply down to a fifth plus
   a faint blue fog. The HUD text is the brightest thing on screen, so its level (255 normally) scales
   every threshold below. At full brightness the scaled thresholds equal the original fixed ones. */
#define SCALED(value, reference) (((value) * (reference) + 254u) / 255u)

/* When ventilation fails the game darkens the whole picture: roughly a multiply down to a fifth plus
   a faint blue fog. The HUD text is the brightest thing on screen, so its level (255 normally) scales
   every threshold below. At full brightness the scaled thresholds equal the original fixed ones. */
#define SCALED(value, reference) (((value) * (reference) + 254u) / 255u)

/* When ventilation fails the game darkens the whole picture: roughly a multiply down to a fifth plus
   a faint blue fog. The HUD text is the brightest thing on screen, so its level (255 normally) scales
   every threshold below. At full brightness the scaled thresholds equal the original fixed ones. */
#define SCALED(value, reference) (((value) * (reference) + 254u) / 255u)

static BOOL monitor_visible(const D3DLOCKED_RECT *image, unsigned reference) {
    /* The verified Steam build draws a continuous brass frame at source y=76–79. */
    const BOOL dim = reference < 200u;
    for (unsigned row = 38; row <= 39; ++row) {
        const uint32_t *rgb = (const uint32_t *)((const char *)image->pBits + row * image->Pitch);
        unsigned matches = 0;
        for (unsigned x = 96; x <= 488; x += 24) {
            unsigned red = (rgb[x] >> 16) & 255;
            unsigned green = (rgb[x] >> 8) & 255;
            unsigned blue = rgb[x] & 255;
            BOOL edge = TRUE;
            if (dim) {
                /* A dark office wall can share the brass colour; the frame is a thin bright line, so in
                   a dark picture it must stand clearly above the rows three above and three below. */
                const uint32_t *above = (const uint32_t *)((const char *)rgb - 3 * image->Pitch);
                const uint32_t *below = (const uint32_t *)((const char *)rgb + 3 * image->Pitch);
                unsigned up = (above[x] >> 16) & 255, down = (below[x] >> 16) & 255;
                edge = red >= up + red / 3 && red >= down + red / 3;
            }
            if (edge && red >= SCALED(82u, reference) && red <= green + 16 && green <= red + 8
                    && blue * 3 >= red && blue * 5 <= red * 3 + (dim ? 40u : 0u)) ++matches;
        }
        if (matches >= 14) return TRUE;
    }
    return FALSE;
}

static unsigned gray_text_strength(uint32_t pixel) {
    int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
    return g >= 24 && abs(r - g) <= 9 && abs(b - g) <= 9 ? (unsigned)g : 0;
}

static uint32_t scene_pixel(const D3DLOCKED_RECT *image, unsigned x, unsigned y) {
    const uint32_t *row = (const uint32_t *)((const char *)image->pBits + y * image->Pitch);
    return row[x];
}

static unsigned brightest_channel(uint32_t pixel) {
    unsigned r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
    unsigned brightest = r > g ? r : g;
    return brightest > b ? brightest : b;
}

static BOOL maintenance_visible(const D3DLOCKED_RECT *image, unsigned reference) {
    const BOOL dim = reference < 200u;
    unsigned dark = 0;
    for (unsigned x = 40; x <= 395; x += 31) {
        uint32_t pixel = scene_pixel(image, x, 91);
        if (dim ? brightest_channel(pixel) <= 12u : (pixel & 0x00fcfcfcu) == 0) ++dark;
    }
    if (dark < 11) return FALSE;

    unsigned green_title = 0;
    for (unsigned y = 112; y <= 146; y += 3) {
        for (unsigned x = 57; x <= 211; x += 3) {
            uint32_t pixel = scene_pixel(image, x, y);
            unsigned r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
            if (g >= SCALED(90u, reference) && g >= r + SCALED(20u, reference)
                    && g >= b + SCALED(20u, reference)) ++green_title;
        }
    }
    return green_title >= 35;
}

static BOOL maintenance_switch_visible(const D3DLOCKED_RECT *image, unsigned hud_brightness) {
    unsigned amber = 0;
    for (unsigned x = 152; x <= 248; x += 16) {
        uint32_t pixel = scene_pixel(image, x, 360);
        unsigned r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
        /* In a dark picture the blue fog lifts blue by a few levels, so the amber margin allows for it. */
        unsigned fog = hud_brightness < 200u ? 8u : 0u;
        if (r >= 48 * hud_brightness / 255 && r >= g + SCALED(5u, hud_brightness)
                && g >= 32 * hud_brightness / 255 && g + fog >= b + SCALED(16u, hud_brightness)) ++amber;
    }
    return amber >= 6;
}

/* Brightness of the "Night N" HUD text, or 0 when the office HUD is not on screen. */
static unsigned hud_brightness(const D3DLOCKED_RECT *image) {
    static const uint16_t night_text[][2] = {
        {465, 7}, {475, 8}, {457, 9}, {465, 10}, {469, 10}, {475, 11}, {457, 12}
    };
    unsigned matching_text = 0, text_brightness = 0;
    for (unsigned i = 0; i < sizeof(night_text) / sizeof(night_text[0]); ++i) {
        unsigned brightness = gray_text_strength(scene_pixel(image, night_text[i][0], night_text[i][1]));
        if (brightness && (!text_brightness || abs((int)brightness - (int)text_brightness) <= 9)) {
            text_brightness = brightness;
            ++matching_text;
        }
    }
    if (matching_text < 5) return 0;
    static const uint16_t hud_nearby[][2] = {{455, 14}, {483, 8}, {485, 13}};
    unsigned contrast = 0;
    for (unsigned i = 0; i < sizeof(hud_nearby) / sizeof(hud_nearby[0]); ++i) {
        /* 30 levels below the text at full brightness, proportionally less when the picture is dark. */
        if (brightest_channel(scene_pixel(image, hud_nearby[i][0], hud_nearby[i][1]))
                + SCALED(30u, text_brightness) < text_brightness) ++contrast;
    }
    return contrast >= 2 ? text_brightness : 0;
}

/* The camera flip briefly dims the HUD too, for about a quarter of a second. Dark mode only starts
   once the HUD has stayed dim for DIM_FRAMES samples in a row (about 0.4 s), as in a ventilation failure. */
#define DIM_FRAMES 16u
static unsigned dim_streak;

static uint32_t scene_flags(const D3DLOCKED_RECT *image) {
    const unsigned text_brightness = hud_brightness(image);
    if (text_brightness >= 200u) dim_streak = 0;
    else if (text_brightness && dim_streak < DIM_FRAMES) ++dim_streak;
    const unsigned reference = text_brightness && dim_streak >= DIM_FRAMES ? text_brightness : 255u;
    uint32_t flags = monitor_visible(image, reference) ? CAMERA_VISIBLE : 0u;
    if (!text_brightness) return flags;
    flags |= IN_OFFICE;

    if (!(flags & CAMERA_VISIBLE)) {
        if (maintenance_visible(image, reference)) return flags | MAINTENANCE_VISIBLE;
        if (maintenance_switch_visible(image, text_brightness)) flags |= MAINTENANCE_SWITCH;
        static const uint16_t blue_switch[][2] = {{450, 47}, {450, 55}, {470, 62}, {471, 67}};
        unsigned blue_samples = 0;
        for (unsigned i = 0; i < sizeof(blue_switch) / sizeof(blue_switch[0]); ++i) {
            const uint32_t *row = (const uint32_t *)((const char *)image->pBits
                + blue_switch[i][1] * image->Pitch);
            uint32_t pixel = row[blue_switch[i][0]];
            unsigned r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
            if (b >= 32 * text_brightness / 255 && b >= g + 4 && g >= r + 4) ++blue_samples;
        }
        if (blue_samples >= 3) flags |= MONITOR_SWITCH;
    }
    return flags;
}

/* Each layer is copied in two steps. On a sampled frame the game's surface is scaled into a small
   render target and an event query is issued; on the next sampled frame, by which time the GPU has
   normally finished, that target is read back. The game no longer waits for its own frame to
   finish rendering every time a frame is captured. */
typedef struct CaptureLayer {
    IDirect3DSurface9 *target, *readback;
    IDirect3DQuery9 *done;
    BOOL queued, failed, no_query;
    uint32_t presentation;
    unsigned width, height;
} CaptureLayer;

/* Two copies per layer can be in flight, so one can start on every frame while the last finishes. */
#define CAPTURE_SLOTS 2
static CaptureLayer layers[2][CAPTURE_SLOTS]; /* [0]: scene, [1]: office */
static uint32_t last_scene_flags;

static void release_layer(CaptureLayer *layer) {
    if (layer->done) IDirect3DQuery9_Release(layer->done);
    if (layer->readback) IDirect3DSurface9_Release(layer->readback);
    if (layer->target) IDirect3DSurface9_Release(layer->target);
    layer->done = NULL;
    layer->readback = layer->target = NULL;
    layer->queued = FALSE;
}

static void release_readback(void) {
    release_pending();
    for (unsigned slot = 0; slot < CAPTURE_SLOTS; ++slot) {
        release_layer(&layers[0][slot]);
        release_layer(&layers[1][slot]);
    }
    last_scene_flags = 0;
    cached_device = NULL;
}

/* Reads back the frame queued earlier, if the GPU has finished it (or always, when wait is set). */
static void collect_layer(IDirect3DDevice9 *device, BOOL office, unsigned slot, BOOL wait) {
    CaptureLayer *layer = &layers[office][slot];
    if (!layer->queued) return;
    if (layer->done && !wait) {
        HRESULT state = IDirect3DQuery9_GetData(layer->done, NULL, 0, D3DGETDATA_FLUSH);
        if (state == S_FALSE) return;
    }
    layer->queued = FALSE;
    LARGE_INTEGER begin, end, frequency;
    QueryPerformanceCounter(&begin);
    HRESULT result = IDirect3DDevice9_GetRenderTargetData(device, layer->target, layer->readback);
    if (FAILED(result)) {
        if (!layer->failed) {
            log_event("scaled_readback_failed", office, (unsigned)result);
            layer->failed = TRUE;
        }
        return;
    }
    D3DLOCKED_RECT locked;
    if (FAILED(IDirect3DSurface9_LockRect(layer->readback, &locked, NULL, D3DLOCK_READONLY))) return;
    const uint32_t flags = office ? 0u : scene_flags(&locked);
    if (!office) last_scene_flags = flags;
    static FrameExchange *exchange[2];
    if (!exchange[office]) exchange[office] = map_frames(office ? L"D:\\thor-office.frames" : L"D:\\thor-scene.frames");
    FrameExchange *data = exchange[office];
    if (data) {
        InterlockedIncrement(&data->sequence);
        data->width = layer->width;
        data->height = layer->height;
        data->presentation = layer->presentation;
        data->flags = flags;
        for (unsigned y = 0; y < layer->height; ++y) {
            const uint32_t *src = (const uint32_t *)((const char *)locked.pBits + y * locked.Pitch);
            uint32_t *dst = data->pixels + y * layer->width;
            for (unsigned x = 0; x < layer->width; ++x) dst[x] = src[x] | 0xff000000u;
        }
        QueryPerformanceCounter(&end);
        QueryPerformanceFrequency(&frequency);
        data->capture_us = (uint32_t)((end.QuadPart - begin.QuadPart) * 1000000 / frequency.QuadPart);
        InterlockedIncrement(&data->sequence);
    }
    IDirect3DSurface9_UnlockRect(layer->readback);
}

/* Scales the game's surface into the layer's target and marks it for reading on a later frame. */
static BOOL queue_layer(IDirect3DDevice9 *device, IDirect3DSurface9 *surface, BOOL office, unsigned slot) {
    CaptureLayer *layer = &layers[office][slot];
    if (layer->queued) return FALSE;
    D3DSURFACE_DESC info;
    if (FAILED(IDirect3DSurface9_GetDesc(surface, &info))) return FALSE;
    if (info.Format != D3DFMT_X8R8G8B8 && info.Format != D3DFMT_A8R8G8B8) return FALSE;
    const unsigned width = info.Width / 2, height = info.Height / 2;
    if (layer->target) {
        D3DSURFACE_DESC previous;
        if (FAILED(IDirect3DSurface9_GetDesc(layer->target, &previous)) || previous.Format != info.Format
                || previous.Width != width || previous.Height != height) release_layer(layer);
    }
    if (!layer->target && FAILED(IDirect3DDevice9_CreateRenderTarget(device, width, height, info.Format,
            D3DMULTISAMPLE_NONE, 0, FALSE, &layer->target, NULL))) return FALSE;
    if (!layer->readback && FAILED(IDirect3DDevice9_CreateOffscreenPlainSurface(device, width, height,
            info.Format, D3DPOOL_SYSTEMMEM, &layer->readback, NULL))) return FALSE;
    if (!layer->done && !layer->no_query
            && FAILED(IDirect3DDevice9_CreateQuery(device, D3DQUERYTYPE_EVENT, &layer->done))) {
        layer->no_query = TRUE;
        log_event("event_query_unavailable", office, 0);
    }
    HRESULT result = IDirect3DDevice9_StretchRect(device, surface, NULL, layer->target, NULL, D3DTEXF_LINEAR);
    if (FAILED(result)) {
        if (!layer->failed) {
            log_event("scaled_copy_failed", office, (unsigned)result);
            layer->failed = TRUE;
        }
        return FALSE;
    }
    if (layer->done) IDirect3DQuery9_Issue(layer->done, D3DISSUE_END);
    layer->width = width;
    layer->height = height;
    layer->presentation = presentation;
    layer->queued = TRUE;
    /* Without an event query there is no way to tell when the copy is done: read it back now. */
    if (!layer->done) collect_layer(device, office, slot, TRUE);
    return TRUE;
}

static HRESULT STDMETHODCALLTYPE capture_set_render_target(IDirect3DDevice9 *device,
        DWORD index, IDirect3DSurface9 *next) {
    IDirect3DSurface9 *surface = NULL;
    BOOL office = FALSE, scene = FALSE;
    if (index == 0 && sample_frame && next) {
        D3DSURFACE_DESC current, upcoming;
        if (SUCCEEDED(IDirect3DDevice9_GetRenderTarget(device, 0, &surface))
                && surface != next && SUCCEEDED(IDirect3DSurface9_GetDesc(surface, &current))
                && SUCCEEDED(IDirect3DSurface9_GetDesc(next, &upcoming))) {
            office = current.Width == MAX_WIDTH && current.Height == MAX_HEIGHT
                && upcoming.Width == 1024 && upcoming.Height == 768;
            scene = current.Width == 1024 && current.Height == 768
                && (upcoming.Width != MAX_WIDTH || upcoming.Height != MAX_HEIGHT);
        }
    }
    HRESULT result = original_set_render_target(device, index, next);
    if (SUCCEEDED(result)) {
        if (office) set_pending(&pending_office, surface);
        if (scene) set_pending(&pending_scene, surface);
    }
    if (surface) IDirect3DSurface9_Release(surface);
    return result;
}

static HRESULT STDMETHODCALLTYPE capture_present(IDirect3DDevice9 *device,
        const RECT *from, const RECT *to, HWND window, const RGNDATA *damage) {
    HRESULT result = original_present(device, from, to, window, damage);
    if (sample_frame) {
        last_sample_at = GetTickCount();
        if (SUCCEEDED(result)) {
            /* Read back finished pairs, oldest first, then queue this frame in a free slot. */
            for (unsigned pass = 0; pass < CAPTURE_SLOTS; ++pass) {
                int oldest = -1;
                for (unsigned slot = 0; slot < CAPTURE_SLOTS; ++slot) {
                    if (layers[0][slot].queued && (oldest < 0
                            || layers[0][slot].presentation < layers[0][oldest].presentation)) oldest = (int)slot;
                }
                if (oldest < 0) break;
                CaptureLayer *scene = &layers[0][oldest];
                if (scene->done && IDirect3DQuery9_GetData(scene->done, NULL, 0, D3DGETDATA_FLUSH) == S_FALSE) break;
                collect_layer(device, FALSE, (unsigned)oldest, TRUE);
                collect_layer(device, TRUE, (unsigned)oldest, TRUE);
            }
            int free_slot = -1;
            for (unsigned slot = 0; slot < CAPTURE_SLOTS && free_slot < 0; ++slot) {
                if (!layers[0][slot].queued && !layers[1][slot].queued) free_slot = (int)slot;
            }
            if (free_slot >= 0 && pending_scene && queue_layer(device, pending_scene, FALSE, (unsigned)free_slot)
                    && pending_office && (last_scene_flags & (CAMERA_VISIBLE | MAINTENANCE_VISIBLE)))
                queue_layer(device, pending_office, TRUE, (unsigned)free_slot);
        }
        release_pending();
    }
    ++presentation;
    DWORD now = GetTickCount();
    if (last_present_at && now - last_present_at < 200) frame_interval = (frame_interval * 3 + (now - last_present_at)) / 4;
    last_present_at = now;
    if (last_reader_check == 0 || now - last_reader_check >= 240) {
        reader_active = GetFileAttributesW(L"D:\\thor-display-active.flag") != INVALID_FILE_ATTRIBUTES;
        last_reader_check = now;
    }
    /* Decide for the next frame, which arrives about one frame interval from now. Measuring from the
       end of this frame's capture instead skipped every other frame. */
    sample_frame = reader_active && now + frame_interval - last_sample_at >= SAMPLE_INTERVAL_MS;
    return result;
}

static HRESULT STDMETHODCALLTYPE capture_reset(IDirect3DDevice9 *device, D3DPRESENT_PARAMETERS *params) {
    release_readback();
    log_event("device_reset", params->BackBufferWidth, params->BackBufferHeight);
    return original_reset(device, params);
}

static void *hook_method(void **table, size_t index, void *replacement) {
    void **entry = table + index;
    if (*entry == replacement) return NULL;
    DWORD protection;
    if (!VirtualProtect(entry, sizeof(*entry), PAGE_READWRITE, &protection)) return NULL;
    void *previous = InterlockedExchangePointer(entry, replacement);
    DWORD unused;
    VirtualProtect(entry, sizeof(*entry), protection, &unused);
    return previous;
}

#define HOOK(table, type, name, replacement, original) do { \
    void *previous = hook_method((void **)(table), offsetof(type, name) / sizeof(void *), (void *)(replacement)); \
    if (previous) (original) = (__typeof__(original))previous; \
} while (0)

static HRESULT STDMETHODCALLTYPE capture_create_device(IDirect3D9 *d3d, UINT adapter, D3DDEVTYPE kind,
        HWND window, DWORD flags, D3DPRESENT_PARAMETERS *params, IDirect3DDevice9 **device) {
    HRESULT result = original_create_device(d3d, adapter, kind, window, flags, params, device);
    if (SUCCEEDED(result) && device && *device) {
        if (cached_device != *device) {
            release_readback();
            cached_device = *device;
        }
        log_event("device_created", params->BackBufferWidth, params->BackBufferHeight);
        const IDirect3DDevice9Vtbl *table = (*device)->lpVtbl;
        HOOK(table, IDirect3DDevice9Vtbl, SetRenderTarget, capture_set_render_target, original_set_render_target);
        HOOK(table, IDirect3DDevice9Vtbl, Present, capture_present, original_present);
        HOOK(table, IDirect3DDevice9Vtbl, Reset, capture_reset, original_reset);
    }
    return result;
}

__declspec(dllexport) IDirect3D9 *WINAPI Direct3DCreate9(UINT sdk) {
    Create9Fn create = (Create9Fn)get_proc("Direct3DCreate9");
    IDirect3D9 *d3d = create ? create(sdk) : NULL;
    if (d3d) {
        log_event("direct3d_create9", sdk, 0);
        HOOK(d3d->lpVtbl, IDirect3D9Vtbl, CreateDevice, capture_create_device, original_create_device);
    }
    return d3d;
}

__declspec(dllexport) HRESULT WINAPI Direct3DCreate9Ex(UINT sdk, IDirect3D9Ex **d3d) {
    Create9ExFn create = (Create9ExFn)get_proc("Direct3DCreate9Ex");
    return create ? create(sdk, d3d) : D3DERR_NOTAVAILABLE;
}

__declspec(dllexport) void WINAPI DebugSetMute(void) {
    typedef void (WINAPI *DebugFn)(void);
    DebugFn debug = (DebugFn)get_proc("DebugSetMute");
    if (debug) debug();
}