#include <stdio.h>
#include "esp_err.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "wlan.h"
#include "mqttcomm.h"
#include "alerts.h"
#include "led_strip.h"

#define NUM_LEDS  9
#define LED_GPIO  12

static const char* TAG = "main";
static led_strip_handle_t led_strip;

static void led_strip_init(void) {
    led_strip_config_t strip_config = {
        .strip_gpio_num = LED_GPIO,
        .max_leds = NUM_LEDS,
        .led_model = LED_MODEL_WS2812,
        .flags.invert_out = false,
    };

    led_strip_rmt_config_t rmt_config = {
        .clk_src = RMT_CLK_SRC_DEFAULT,
        .resolution_hz = 10 * 1000 * 1000,
        .flags.with_dma = false,
    };

    ESP_ERROR_CHECK(led_strip_new_rmt_device(&strip_config, &rmt_config, &led_strip));
    led_strip_clear(led_strip);
    ESP_LOGI(TAG, "LED strip initialized (%d LEDs on GPIO %d)", NUM_LEDS, LED_GPIO);
}

static void led_alert_task(void *pv) {
    while (1) {
        alerts_render(xTaskGetTickCount() * portTICK_PERIOD_MS);
        vTaskDelay(pdMS_TO_TICKS(33));
    }
}

void app_main(void)
{
    ESP_ERROR_CHECK(nvs_flash_init());
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    led_strip_init();
    alerts_init(led_strip);
    xTaskCreate(led_alert_task, "led_alert", 4096, NULL, 5, NULL);
    wlan_start();
    mqttcomm_start();
}
