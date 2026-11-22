#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
typedef int BOOL;
#define TRUE 1
#define FALSE 0
typedef struct {int Pitch;void *pBits;} D3DLOCKED_RECT;
#define CAMERA_VISIBLE 1u
#define IN_OFFICE 2u
#define MONITOR_SWITCH 4u
#define MAINTENANCE_VISIBLE 8u
#define MAINTENANCE_SWITCH 16u
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


int main(void) {
  static uint32_t pixels[512*384];
  const char *names[]={"maint-closed.raw","maintenance-candidate.raw","dim-office.raw","arcade-actual.raw"};
  const unsigned wanted[]={18,10,18,0};
  unsigned failures=0;
  for (unsigned i=0;i<4;++i) {
    FILE *f=fopen(names[i],"rb");
    if (!f || fseek(f,64,SEEK_SET) || fread(pixels,sizeof(pixels),1,f)!=1) return 2;
    fclose(f);
    D3DLOCKED_RECT rect={512*4,pixels};
    unsigned actual=scene_flags(&rect);
    printf("%s -> %u expected %u\n",names[i],actual,wanted[i]);
    failures+=actual!=wanted[i];
  }
  return failures ? 1 : 0;
}
