#include "task_tof.h"

#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include "config.h"
#include "task_telemetry.h"
#include "vl53l8cx_pair.h"

#include "driver/gpio.h"

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include "esp_err.h"
#include "esp_log.h"
#include "esp_timer.h"

#define TOF_WAIT_TIMEOUT_MS         500U
#define TOF_LOST_INTERRUPT_LOG_MS   5000U

static const char *TAG = "TOF_TASK";

static SemaphoreHandle_t s_data_ready = NULL;
static volatile uint32_t s_pending_mask = 0U;
static volatile int64_t s_timestamp_us[TOF_SENSOR_COUNT];

static const gpio_num_t s_int_gpio[TOF_SENSOR_COUNT] = {
    [TOF_SENSOR_LEFT] = TOF_LEFT_INT_GPIO,
    [TOF_SENSOR_RIGHT] = TOF_RIGHT_INT_GPIO
};

/*
 * One handler for both sensors: the argument carries the sensor id, the
 * timestamp is taken here rather than in the task so bus contention between the
 * two readouts does not leak into it, and a bit marks who has data waiting.
 */
static void IRAM_ATTR tof_data_ready_isr(void *argument)
{
    uint32_t sensor = (uint32_t)argument;

    s_timestamp_us[sensor] = esp_timer_get_time();
    s_pending_mask |= 1UL << sensor;
    xSemaphoreGiveFromISR(s_data_ready, NULL);
}

static esp_err_t tof_interrupt_init(void)
{
    gpio_config_t int_config = {
        .pin_bit_mask = (1ULL << TOF_LEFT_INT_GPIO) |
                        (1ULL << TOF_RIGHT_INT_GPIO),
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        /* The VL53L8CX interrupt output is active low */
        .intr_type = GPIO_INTR_NEGEDGE
    };

    esp_err_t result = gpio_config(&int_config);

    if (result != ESP_OK) {
        return result;
    }

    /* The IMU task may have installed this already */
    result = gpio_install_isr_service(0);

    if (result != ESP_OK && result != ESP_ERR_INVALID_STATE) {
        return result;
    }

    for (uint32_t sensor = 0; sensor < TOF_SENSOR_COUNT; sensor++) {
        result = gpio_isr_handler_add(
            s_int_gpio[sensor],
            tof_data_ready_isr,
            (void *)sensor
        );

        if (result != ESP_OK) {
            return result;
        }
    }

    return ESP_OK;
}

static void tof_publish(uint32_t sensor, uint32_t *sequence)
{
    tof_zone_data_t zones;
    esp_err_t result = tof_pair_read((tof_sensor_id_t)sensor, &zones);

    if (result != ESP_OK) {
        ESP_LOGW(TAG, "Sensor %lu read failed: %s",
                 (unsigned long)sensor, esp_err_to_name(result));
        return;
    }

    tof_sample_t sample = {
        .sensor_id = (uint8_t)sensor,
        .sequence = sequence[sensor],
        .timestamp_us = s_timestamp_us[sensor]
    };

    memcpy(sample.distance_mm, zones.distance_mm,
           sizeof(sample.distance_mm));
    memcpy(sample.status, zones.status, sizeof(sample.status));

    telemetry_submit_tof(&sample);
    sequence[sensor]++;
}

static void tof_task(void *argument)
{
    (void)argument;

    uint32_t sequence[TOF_SENSOR_COUNT] = { 0 };
    TickType_t last_timeout_log_tick = 0;

    ESP_LOGI(TAG, "Front ToF ranging started");

    while (true) {
        if (xSemaphoreTake(
                s_data_ready,
                pdMS_TO_TICKS(TOF_WAIT_TIMEOUT_MS)
            ) != pdTRUE) {
            TickType_t now = xTaskGetTickCount();

            if (now - last_timeout_log_tick >=
                pdMS_TO_TICKS(TOF_LOST_INTERRUPT_LOG_MS)) {
                ESP_LOGW(TAG, "No ToF frame within %u ms",
                         (unsigned)TOF_WAIT_TIMEOUT_MS);
                last_timeout_log_tick = now;
            }

            continue;
        }

        /*
         * Both sensors share the bus, so their readouts serialise here anyway.
         * Claim the whole mask at once rather than once per sensor.
         */
        uint32_t pending = s_pending_mask;
        s_pending_mask &= ~pending;

        for (uint32_t sensor = 0; sensor < TOF_SENSOR_COUNT; sensor++) {
            if ((pending & (1UL << sensor)) == 0U) {
                continue;
            }

            tof_publish(sensor, sequence);
        }
    }
}

esp_err_t tof_task_start(void)
{
    if (s_data_ready != NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    s_data_ready = xSemaphoreCreateBinary();

    if (s_data_ready == NULL) {
        ESP_LOGE(TAG, "Failed to create data-ready semaphore");
        return ESP_ERR_NO_MEM;
    }

    esp_err_t result = tof_pair_init();

    if (result != ESP_OK) {
        vSemaphoreDelete(s_data_ready);
        s_data_ready = NULL;
        return result;
    }

    result = tof_interrupt_init();

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "Data-ready interrupt setup failed: %s",
                 esp_err_to_name(result));
        vSemaphoreDelete(s_data_ready);
        s_data_ready = NULL;
        return result;
    }

    BaseType_t task_result = xTaskCreatePinnedToCore(
        tof_task,
        "tof_task",
        TOF_TASK_STACK_SIZE,
        NULL,
        TOF_TASK_PRIORITY,
        NULL,
        TOF_TASK_CORE_ID
    );

    if (task_result != pdPASS) {
        ESP_LOGE(TAG, "Failed to create ToF task");
        vSemaphoreDelete(s_data_ready);
        s_data_ready = NULL;
        return ESP_FAIL;
    }

    ESP_LOGI(TAG, "ToF task created successfully");
    return ESP_OK;
}
