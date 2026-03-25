#include "alerts.h"
#include "esp_log.h"
#include <string.h>

#define RING_LEDS     8
#define CENTER_LED    8
#define NUM_LEDS      9

// LED strip has RGB byte order but driver assumes GRB,
// so we pre-swap R and G to compensate.
#define AMBER_R 180
#define AMBER_G 255
#define AMBER_B 0

#define RED_R 0
#define RED_G 255
#define RED_B 0

#define GREEN_R 255
#define GREEN_G 0
#define GREEN_B 0

typedef enum { LEVEL_NONE, LEVEL_WARNING, LEVEL_CRITICAL } alert_level_t;

typedef enum {
    PATTERN_IDLE,
    PATTERN_RPM_WARNING,
    PATTERN_RPM_CRITICAL,
    PATTERN_OTHER_WARNING,
    PATTERN_OTHER_CRITICAL,
    PATTERN_PIT_WINDOW,
} pattern_t;

typedef struct {
    const char *name;
    alert_level_t level;
} metric_slot_t;

static const char *TAG = "alerts";
static led_strip_handle_t s_strip;

#define NUM_METRICS 5
static metric_slot_t s_metrics[NUM_METRICS] = {
    { "rpm",      LEVEL_NONE },
    { "coolant",  LEVEL_NONE },
    { "oil_temp", LEVEL_NONE },
    { "oil_pres", LEVEL_NONE },
    { "battery",  LEVEL_NONE },
};

static volatile int s_pit_flash_remaining = 0;
static uint32_t s_pit_flash_start_ms = 0;

void alerts_init(led_strip_handle_t strip) {
    s_strip = strip;
}

static metric_slot_t *find_metric(const char *name) {
    for (int i = 0; i < NUM_METRICS; i++) {
        if (strcmp(s_metrics[i].name, name) == 0) {
            return &s_metrics[i];
        }
    }
    return NULL;
}

void alerts_set_metric(const char *metric, const char *level) {
    metric_slot_t *slot = find_metric(metric);
    if (!slot) {
        ESP_LOGW(TAG, "unknown metric: %s", metric);
        return;
    }
    if (strcmp(level, "warning") == 0) {
        slot->level = LEVEL_WARNING;
    } else if (strcmp(level, "critical") == 0) {
        slot->level = LEVEL_CRITICAL;
    }
    ESP_LOGI(TAG, "%s -> %s", metric, level);
}

void alerts_clear_metric(const char *metric) {
    metric_slot_t *slot = find_metric(metric);
    if (!slot) return;
    slot->level = LEVEL_NONE;
    ESP_LOGI(TAG, "%s -> cleared", metric);
}

void alerts_pit_window_flash(void) {
    s_pit_flash_remaining = 6; // 3 on/off cycles
    s_pit_flash_start_ms = 0;  // will be set on next render
}

static pattern_t resolve_pattern(void) {
    // Check for pit window flash override
    if (s_pit_flash_remaining > 0) {
        return PATTERN_PIT_WINDOW;
    }

    bool rpm_warning = false;
    bool rpm_critical = false;
    bool other_warning = false;
    bool other_critical = false;

    for (int i = 0; i < NUM_METRICS; i++) {
        bool is_rpm = (strcmp(s_metrics[i].name, "rpm") == 0);
        if (s_metrics[i].level == LEVEL_CRITICAL) {
            if (is_rpm) rpm_critical = true;
            else other_critical = true;
        } else if (s_metrics[i].level == LEVEL_WARNING) {
            if (is_rpm) rpm_warning = true;
            else other_warning = true;
        }
    }

    // Priority: any critical > RPM warning > other warning > idle
    if (rpm_critical) return PATTERN_RPM_CRITICAL;
    if (other_critical) return PATTERN_OTHER_CRITICAL;
    if (rpm_warning) return PATTERN_RPM_WARNING;
    if (other_warning) return PATTERN_OTHER_WARNING;
    return PATTERN_IDLE;
}

static void clear_all(void) {
    for (int i = 0; i < NUM_LEDS; i++) {
        led_strip_set_pixel(s_strip, i, 0, 0, 0);
    }
}

static void render_rpm_warning(uint32_t tick_ms) {
    // Whole ring amber pulse ~1 Hz (500ms up, 500ms down)
    uint32_t phase = tick_ms % 1000;
    // Triangle wave: 0→255→0 over 1000ms
    uint32_t brightness;
    if (phase < 500) {
        brightness = (phase * 255) / 500;
    } else {
        brightness = ((1000 - phase) * 255) / 500;
    }

    uint8_t r = (AMBER_R * brightness) / 255;
    uint8_t g = (AMBER_G * brightness) / 255;
    uint8_t b = (AMBER_B * brightness) / 255;

    for (int i = 0; i < RING_LEDS; i++) {
        led_strip_set_pixel(s_strip, i, r, g, b);
    }
    led_strip_set_pixel(s_strip, CENTER_LED, 0, 0, 0);
}

static void render_rpm_critical(uint32_t tick_ms) {
    // All ring red flash ~4 Hz (125ms on, 125ms off)
    bool on = ((tick_ms / 125) % 2) == 0;

    for (int i = 0; i < RING_LEDS; i++) {
        if (on) {
            led_strip_set_pixel(s_strip, i, RED_R, RED_G, RED_B);
        } else {
            led_strip_set_pixel(s_strip, i, 0, 0, 0);
        }
    }
    led_strip_set_pixel(s_strip, CENTER_LED, RED_R, RED_G, RED_B);
}

static void render_other_warning(uint32_t tick_ms) {
    // Single amber LED chases around ring ~2 rev/s (500ms per revolution)
    uint32_t phase = tick_ms % 500;
    int active_led = (phase * RING_LEDS) / 500;

    for (int i = 0; i < RING_LEDS; i++) {
        if (i == active_led) {
            led_strip_set_pixel(s_strip, i, AMBER_R, AMBER_G, AMBER_B);
        } else {
            led_strip_set_pixel(s_strip, i, 0, 0, 0);
        }
    }
    led_strip_set_pixel(s_strip, CENTER_LED, AMBER_R, AMBER_G, AMBER_B);
}

static void render_other_critical(uint32_t tick_ms) {
    // Alternating halves red flash ~4 Hz
    bool first_half = ((tick_ms / 125) % 2) == 0;

    for (int i = 0; i < RING_LEDS; i++) {
        bool in_first_half = (i < RING_LEDS / 2);
        bool on = (in_first_half == first_half);
        if (on) {
            led_strip_set_pixel(s_strip, i, RED_R, RED_G, RED_B);
        } else {
            led_strip_set_pixel(s_strip, i, 0, 0, 0);
        }
    }
    led_strip_set_pixel(s_strip, CENTER_LED, RED_R, RED_G, RED_B);
}

static void render_pit_window(uint32_t tick_ms) {
    if (s_pit_flash_start_ms == 0) {
        s_pit_flash_start_ms = tick_ms;
    }

    uint32_t elapsed = tick_ms - s_pit_flash_start_ms;
    // Each blink cycle: 150ms on + 150ms off = 300ms per cycle, 3 cycles = 900ms
    uint32_t cycle_pos = elapsed % 300;
    bool on = (cycle_pos < 150);

    // Count completed cycles
    int completed_cycles = elapsed / 300;
    if (completed_cycles >= 3) {
        s_pit_flash_remaining = 0;
        return;
    }

    if (on) {
        for (int i = 0; i < NUM_LEDS; i++) {
            led_strip_set_pixel(s_strip, i, GREEN_R, GREEN_G, GREEN_B);
        }
    } else {
        clear_all();
    }
}

void alerts_render(uint32_t tick_ms) {
    pattern_t pattern = resolve_pattern();

    switch (pattern) {
        case PATTERN_RPM_WARNING:   render_rpm_warning(tick_ms);   break;
        case PATTERN_RPM_CRITICAL:  render_rpm_critical(tick_ms);  break;
        case PATTERN_OTHER_WARNING: render_other_warning(tick_ms); break;
        case PATTERN_OTHER_CRITICAL:render_other_critical(tick_ms);break;
        case PATTERN_PIT_WINDOW:    render_pit_window(tick_ms);    break;
        case PATTERN_IDLE:
        default:
            clear_all();
            break;
    }

    led_strip_refresh(s_strip);
}
