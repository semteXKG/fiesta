#include "mqttcomm.h"
#include "alerts.h"
#include "mqtt_client.h"
#include "cJSON.h"
#include "esp_log.h"
#include <string.h>

#define BROKER_URI         "mqtt://broker:1883"
#define CLIENT_ID          "led-strip"
#define STATUS_TOPIC       "fiesta/device/led-strip/status"
#define STATUS_ONLINE      "{\"status\":\"online\"}"
#define STATUS_OFFLINE     "{\"status\":\"offline\"}"
#define EVENTS_TOPIC       "fiesta/events"
#define PIT_WINDOW_TOPIC   "fiesta/pit/window"

static const char* TAG = "mqttcomm";
static esp_mqtt_client_handle_t client = NULL;

static void handle_events_msg(const char *data, int data_len) {
    cJSON *root = cJSON_ParseWithLength(data, data_len);
    if (!root) return;

    const cJSON *type = cJSON_GetObjectItem(root, "type");
    if (!cJSON_IsString(type) || strcmp(type->valuestring, "telemetry_alert") != 0) {
        cJSON_Delete(root);
        return;
    }

    const cJSON *metric     = cJSON_GetObjectItem(root, "metric");
    const cJSON *transition = cJSON_GetObjectItem(root, "transition");
    const cJSON *level      = cJSON_GetObjectItem(root, "level");

    if (cJSON_IsString(metric) && cJSON_IsString(transition) && cJSON_IsString(level)) {
        if (strcmp(transition->valuestring, "entered") == 0) {
            alerts_set_metric(metric->valuestring, level->valuestring);
        } else if (strcmp(transition->valuestring, "left") == 0) {
            alerts_clear_metric(metric->valuestring);
        }
    }

    cJSON_Delete(root);
}

static void handle_pit_window_msg(const char *data, int data_len) {
    cJSON *root = cJSON_ParseWithLength(data, data_len);
    if (!root) return;

    const cJSON *state = cJSON_GetObjectItem(root, "state");
    if (cJSON_IsString(state) && strcmp(state->valuestring, "OPEN") == 0) {
        alerts_pit_window_flash();
    }

    cJSON_Delete(root);
}

static void mqtt_event_handler(void* handler_args, esp_event_base_t base, int32_t event_id, void* event_data) {
    esp_mqtt_event_handle_t event = (esp_mqtt_event_handle_t)event_data;

    switch (event_id) {
        case MQTT_EVENT_CONNECTED:
            ESP_LOGI(TAG, "MQTT connected");
            esp_mqtt_client_publish(client, STATUS_TOPIC, STATUS_ONLINE, 0, 1, 1);
            esp_mqtt_client_subscribe(client, EVENTS_TOPIC, 0);
            esp_mqtt_client_subscribe(client, PIT_WINDOW_TOPIC, 1);
            break;
        case MQTT_EVENT_DISCONNECTED:
            ESP_LOGE(TAG, "MQTT disconnected, retrying in ~5s...");
            break;
        case MQTT_EVENT_DATA:
            if (event->topic_len > 0 && event->data_len > 0) {
                if (strncmp(event->topic, EVENTS_TOPIC, event->topic_len) == 0) {
                    handle_events_msg(event->data, event->data_len);
                } else if (strncmp(event->topic, PIT_WINDOW_TOPIC, event->topic_len) == 0) {
                    handle_pit_window_msg(event->data, event->data_len);
                }
            }
            break;
        case MQTT_EVENT_ERROR:
            ESP_LOGE(TAG, "MQTT error");
            break;
        default:
            break;
    }
}

void mqttcomm_start(void) {
    const esp_mqtt_client_config_t mqtt_cfg = {
        .broker.address.uri = BROKER_URI,
        .credentials.client_id = CLIENT_ID,
        .session.last_will = {
            .topic = STATUS_TOPIC,
            .msg   = STATUS_OFFLINE,
            .qos   = 1,
            .retain = 1,
        },
        .network.reconnect_timeout_ms = 5000,
    };

    ESP_LOGI(TAG, "Connecting to MQTT broker at %s", BROKER_URI);
    client = esp_mqtt_client_init(&mqtt_cfg);
    esp_mqtt_client_register_event(client, ESP_EVENT_ANY_ID, mqtt_event_handler, NULL);
    esp_mqtt_client_start(client);
}

int mqttcomm_publish(const char* topic, const char* data, int len) {
    if (client == NULL) {
        ESP_LOGE(TAG, "MQTT client not initialized");
        return -1;
    }
    return esp_mqtt_client_publish(client, topic, data, len, 1, 0);
}
