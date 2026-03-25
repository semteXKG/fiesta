#pragma once

#include "led_strip.h"
#include <stdint.h>

void alerts_init(led_strip_handle_t strip);
void alerts_set_metric(const char *metric, const char *level);
void alerts_clear_metric(const char *metric);
void alerts_pit_window_flash(void);
void alerts_render(uint32_t tick_ms);
