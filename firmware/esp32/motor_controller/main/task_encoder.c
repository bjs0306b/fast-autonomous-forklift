#include "task_encoder.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_log.h"
#include "esp_timer.h"

#include "config.h"
#include "encoder.h"
#include "task_telemetry.h"

static const char *TAG = "ENCODER_TASK";

static TaskHandle_t s_task_handle = NULL;

static void encoder_task(void *argument)
{
    (void)argument;

    uint32_t sequence = 0U;
    uint32_t read_errors = 0U;
    TickType_t last_wake = xTaskGetTickCount();

    for (;;) {
        int32_t count = 0;
        esp_err_t result = encoder_read(&count);

        if (result == ESP_OK) {
            /*
             * Stamped after the read, not before: the host maps this onto its
             * own clock, and the count is what the wheel had done by now.
             */
            encoder_sample_t sample = {
                .sequence = sequence++,
                .timestamp_us = esp_timer_get_time(),
                .count = count,
                .read_errors = read_errors,
            };
            (void)telemetry_submit_encoder(&sample);
        } else if (read_errors++ % ENCODER_ERROR_LOG_INTERVAL == 0U) {
            ESP_LOGW(TAG, "encoder read failed: %s", esp_err_to_name(result));
        }

        vTaskDelayUntil(&last_wake,
                        pdMS_TO_TICKS(1000U / ENCODER_PUBLISH_RATE_HZ));
    }
}

esp_err_t encoder_task_start(void)
{
    if (s_task_handle != NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    esp_err_t result = encoder_init();

    if (result != ESP_OK) {
        return result;
    }

    BaseType_t created = xTaskCreate(
        encoder_task,
        "encoder",
        ENCODER_TASK_STACK_SIZE,
        NULL,
        ENCODER_TASK_PRIORITY,
        &s_task_handle);

    if (created != pdPASS) {
        ESP_LOGE(TAG, "failed to create encoder task");
        return ESP_ERR_NO_MEM;
    }

    ESP_LOGI(TAG, "Wheel encoder task started at %u Hz",
             (unsigned)ENCODER_PUBLISH_RATE_HZ);
    return ESP_OK;
}
