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
/*
 * A failed block read can leave the active-low INT asserted.  There is then
 * no next falling edge, so an interrupt-driven sensor would otherwise remain
 * silent until reboot.  Poll after three missed 15 Hz frames to read and clear
 * the pending frame; the next interrupt edge resumes normal operation.
 */
#define TOF_INTERRUPT_RECOVERY_MS   200U

static const char *TAG = "TOF_TASK";

static SemaphoreHandle_t s_data_ready = NULL;
static volatile uint32_t s_pending_mask = 0U;
static volatile int64_t s_timestamp_us[TOF_SENSOR_COUNT];
static volatile uint32_t s_isr_count[TOF_SENSOR_COUNT];

/*
 * Level of the INT pin at the moment the sensor said it had data. The sensor
 * drives this low when a frame is ready, so a high reading here means the pin
 * is not carrying that sensor's INT output at all.
 */
static int s_level_when_ready[TOF_SENSOR_COUNT] = { -1, -1 };

/* Poll outcomes, to separate "sensor has nothing" from "sensor will not talk" */
static uint32_t s_poll_ready[TOF_SENSOR_COUNT];
static uint32_t s_poll_idle[TOF_SENSOR_COUNT];
static uint32_t s_poll_error[TOF_SENSOR_COUNT];
static uint32_t s_published[TOF_SENSOR_COUNT];

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
    s_isr_count[sensor]++;
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
        /* A sensor that failed to start is off the bus and never interrupts */
        if (!tof_pair_is_present((tof_sensor_id_t)sensor)) {
            continue;
        }

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

static bool tof_publish(
    uint32_t sensor,
    uint32_t *sequence,
    int64_t timestamp_us
)
{
    tof_zone_data_t zones;
    esp_err_t result = tof_pair_read((tof_sensor_id_t)sensor, &zones);

    if (result != ESP_OK) {
        ESP_LOGW(TAG, "Sensor %lu read failed: %s",
                 (unsigned long)sensor, esp_err_to_name(result));
        return false;
    }

    tof_sample_t sample = {
        .sensor_id = (uint8_t)sensor,
        .sequence = sequence[sensor],
        .timestamp_us = timestamp_us
    };

    memcpy(sample.distance_mm, zones.distance_mm,
           sizeof(sample.distance_mm));
    memcpy(sample.status, zones.status, sizeof(sample.status));

    telemetry_submit_tof(&sample);
    s_published[sensor]++;
    sequence[sensor]++;
    return true;
}

static void tof_task(void *argument)
{
    (void)argument;

    uint32_t sequence[TOF_SENSOR_COUNT] = { 0 };
    bool interrupt_seen[TOF_SENSOR_COUNT] = { false };
    /*
     * Started now, not at zero. A sensor running at 15 Hz needs 67 ms to raise
     * its first interrupt, and warning before that just reports a fault that
     * has not had a chance to not happen yet.
     */
    TickType_t last_warning_tick = xTaskGetTickCount();
    TickType_t last_status_tick = xTaskGetTickCount();
    TickType_t last_publish_tick[TOF_SENSOR_COUNT] = {
        last_status_tick, last_status_tick
    };

    ESP_LOGI(TAG, "Front ToF ranging started");

    while (true) {
        if (xSemaphoreTake(
                s_data_ready,
                pdMS_TO_TICKS(TOF_POLL_PERIOD_MS)
            ) == pdTRUE) {
            /*
             * Both sensors share the bus, so their readouts serialise here
             * anyway. Claim the whole mask at once rather than once per sensor.
             */
            uint32_t pending = s_pending_mask;
            s_pending_mask &= ~pending;

            for (uint32_t sensor = 0; sensor < TOF_SENSOR_COUNT; sensor++) {
                if ((pending & (1UL << sensor)) == 0U) {
                    continue;
                }

                interrupt_seen[sensor] = true;
                if (tof_publish(
                        sensor, sequence, s_timestamp_us[sensor]
                    )) {
                    last_publish_tick[sensor] = xTaskGetTickCount();
                }
            }
        }

        TickType_t now = xTaskGetTickCount();

        /*
         * Anything the interrupt never delivers is picked up here. A sensor
         * that asserted INT before the handler was installed has no edge left
         * to give, and one with no INT wire at all never had one; polling keeps
         * both usable while the warning below keeps the fault visible.
         */
        for (uint32_t sensor = 0; sensor < TOF_SENSOR_COUNT; sensor++) {
            bool recovery_due =
                now - last_publish_tick[sensor] >=
                pdMS_TO_TICKS(TOF_INTERRUPT_RECOVERY_MS);

            if ((!recovery_due && interrupt_seen[sensor]) ||
                !tof_pair_is_present((tof_sensor_id_t)sensor)) {
                continue;
            }

            bool ready = false;

            if (tof_pair_data_ready((tof_sensor_id_t)sensor, &ready)
                != ESP_OK) {
                s_poll_error[sensor]++;
                continue;
            }

            if (!ready) {
                s_poll_idle[sensor]++;
                continue;
            }

            s_poll_ready[sensor]++;

            /* Sampled before the read clears the sensor's INT output */
            s_level_when_ready[sensor] = gpio_get_level(s_int_gpio[sensor]);
            if (tof_publish(sensor, sequence, esp_timer_get_time())) {
                last_publish_tick[sensor] = xTaskGetTickCount();
            }
        }

        if (now - last_status_tick >=
            pdMS_TO_TICKS(TELEMETRY_STATUS_PERIOD_MS)) {
            last_status_tick = now;

            for (uint32_t sensor = 0; sensor < TOF_SENSOR_COUNT; sensor++) {
                tof_bus_stats_t bus;

                tof_pair_get_stats((tof_sensor_id_t)sensor, &bus);

                tof_status_t status = {
                    .sensor_id = (uint8_t)sensor,
                    .present = tof_pair_is_present((tof_sensor_id_t)sensor),
                    .read_errors = bus.read_errors,
                    .data_ready_errors = bus.data_ready_errors,
                    .interrupts = s_isr_count[sensor],
                    .published = s_published[sensor],
                    .polled = s_poll_ready[sensor]
                };

                telemetry_publish_tof_status(&status);
            }
        }

        if (now - last_warning_tick <
            pdMS_TO_TICKS(TOF_LOST_INTERRUPT_LOG_MS)) {
            continue;
        }

        for (uint32_t sensor = 0; sensor < TOF_SENSOR_COUNT; sensor++) {
            if (!tof_pair_is_present((tof_sensor_id_t)sensor) ||
                interrupt_seen[sensor]) {
                continue;
            }

            ESP_LOGW(TAG,
                     "Sensor %lu polled, not interrupt driven. GPIO%d: idle "
                     "level %d, level when data was ready %d, ISR count %lu",
                     (unsigned long)sensor,
                     (int)s_int_gpio[sensor],
                     gpio_get_level(s_int_gpio[sensor]),
                     s_level_when_ready[sensor],
                     (unsigned long)s_isr_count[sensor]);
            ESP_LOGW(TAG,
                     "  poll: ready %lu, not-ready %lu, i2c error %lu",
                     (unsigned long)s_poll_ready[sensor],
                     (unsigned long)s_poll_idle[sensor],
                     (unsigned long)s_poll_error[sensor]);
            ESP_LOGW(TAG,
                     "  ready 0 + not-ready high = sensor is not producing "
                     "frames at all, which is not an INT problem; "
                     "level-when-ready 1 = the pin is not carrying that "
                     "sensor's INT output");
            last_warning_tick = now;
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
