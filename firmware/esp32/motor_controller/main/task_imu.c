#include "task_imu.h"

#include <stdbool.h>
#include <stdint.h>

#include "config.h"
#include "mpu6500.h"
#include "task_motor.h"
#include "task_telemetry.h"

#include "driver/gpio.h"

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include "esp_err.h"
#include "esp_log.h"
#include "esp_timer.h"

#define IMU_SAMPLE_PERIOD_MS        (1000U / IMU_SAMPLE_RATE_HZ)
#define IMU_SAMPLE_TIMEOUT_MS       200U
#define IMU_LOST_INTERRUPT_LOG_MS   5000U

static const char *TAG = "IMU_TASK";

static SemaphoreHandle_t s_data_ready = NULL;
static volatile int64_t s_data_ready_timestamp_us = 0;
static uint8_t s_who_am_i = 0U;

static void IRAM_ATTR imu_data_ready_isr(void *argument)
{
    (void)argument;

    /*
     * Stamp inside the ISR. Doing it after the SPI read would fold bus and
     * scheduling latency into the timestamp, which is exactly the jitter the
     * interrupt-driven design exists to avoid.
     */
    s_data_ready_timestamp_us = esp_timer_get_time();
    xSemaphoreGiveFromISR(s_data_ready, NULL);
}

static esp_err_t imu_interrupt_init(void)
{
    gpio_config_t int_config = {
        .pin_bit_mask = 1ULL << IMU_INT_GPIO,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_ENABLE,
        .intr_type = GPIO_INTR_POSEDGE
    };

    esp_err_t result = gpio_config(&int_config);

    if (result != ESP_OK) {
        return result;
    }

    result = gpio_install_isr_service(0);

    if (result != ESP_OK && result != ESP_ERR_INVALID_STATE) {
        return result;
    }

    return gpio_isr_handler_add(
        IMU_INT_GPIO,
        imu_data_ready_isr,
        NULL
    );
}

static int32_t imu_lsb_to_mdps(float gyro_lsb)
{
    return (int32_t)((gyro_lsb / IMU_GYRO_SENSITIVITY_LSB_DPS) * 1000.0f);
}

static esp_err_t imu_wait_for_sample(
    int16_t gyro_raw[3],
    int16_t *temp_raw,
    int64_t *timestamp_us
)
{
    if (xSemaphoreTake(
            s_data_ready,
            pdMS_TO_TICKS(IMU_SAMPLE_TIMEOUT_MS)
        ) != pdTRUE) {
        return ESP_ERR_TIMEOUT;
    }

    *timestamp_us = s_data_ready_timestamp_us;
    return mpu6500_read_gyro_temp(gyro_raw, temp_raw);
}

/*
 * Initial bias with the robot stationary. If the robot is moving during this
 * window the bias is poisoned and heading will drift one way forever, so this
 * runs at boot before any command can be applied.
 */
static esp_err_t imu_calibrate_bias(float *bias_lsb)
{
    int16_t gyro_raw[3] = { 0 };
    int64_t timestamp_us = 0;
    float sum = 0.0f;

    ESP_LOGI(TAG, "Measuring gyro bias over %u samples; keep the robot still",
             (unsigned)IMU_BIAS_CALIBRATION_SAMPLES);

    for (uint32_t index = 0; index < IMU_BIAS_CALIBRATION_SAMPLES; index++) {
        esp_err_t result = imu_wait_for_sample(
            gyro_raw,
            NULL,
            &timestamp_us
        );

        if (result != ESP_OK) {
            ESP_LOGE(TAG, "Bias measurement aborted at sample %lu: %s",
                     (unsigned long)index,
                     esp_err_to_name(result));
            return result;
        }

        sum += (float)gyro_raw[2];
    }

    *bias_lsb = sum / (float)IMU_BIAS_CALIBRATION_SAMPLES;
    ESP_LOGI(TAG, "Gyro Z bias: %ld mdps",
             (long)imu_lsb_to_mdps(*bias_lsb));
    return ESP_OK;
}

/*
 * Slow, idle-gated bias tracking. The vehicle is car-like, so a zero drive
 * command means zero yaw rate regardless of steering angle. The alpha is
 * deliberately tiny: it keeps a coasting robot, which reports idle while still
 * rotating, from poisoning the estimate.
 */
static void imu_update_bias(float *bias_lsb, float gyro_raw_lsb,
                            uint32_t *idle_ms)
{
    if (!motor_task_drive_is_idle()) {
        *idle_ms = 0U;
        return;
    }

    *idle_ms += IMU_SAMPLE_PERIOD_MS;

    if (*idle_ms <= IMU_BIAS_IDLE_HOLD_MS) {
        return;
    }

    *bias_lsb = ((1.0f - IMU_BIAS_UPDATE_ALPHA) * (*bias_lsb)) +
                (IMU_BIAS_UPDATE_ALPHA * gyro_raw_lsb);
}

static void imu_task(void *argument)
{
    (void)argument;

    float bias_lsb = 0.0f;

    if (imu_calibrate_bias(&bias_lsb) != ESP_OK) {
        ESP_LOGE(TAG, "IMU task stopping; no gyro data will be published");
        vTaskDelete(NULL);
        return;
    }

    uint32_t sequence = 0U;
    uint32_t idle_ms = 0U;
    TickType_t last_status_tick = xTaskGetTickCount();
    TickType_t last_timeout_log_tick = 0;

    while (true) {
        int16_t gyro_raw[3] = { 0 };
        int16_t temp_raw = 0;
        int64_t timestamp_us = 0;
        esp_err_t result = imu_wait_for_sample(
            gyro_raw,
            &temp_raw,
            &timestamp_us
        );

        if (result != ESP_OK) {
            TickType_t now = xTaskGetTickCount();

            if (now - last_timeout_log_tick >=
                pdMS_TO_TICKS(IMU_LOST_INTERRUPT_LOG_MS)) {
                ESP_LOGW(TAG, "No gyro sample: %s",
                         esp_err_to_name(result));
                last_timeout_log_tick = now;
            }

            continue;
        }

        float gyro_z_lsb = (float)gyro_raw[2];
        imu_sample_t sample = {
            .sequence = sequence,
            .timestamp_us = timestamp_us,
            .gyro_z_mdps = imu_lsb_to_mdps(gyro_z_lsb - bias_lsb),
            .temperature_cdeg = mpu6500_temperature_cdeg(temp_raw)
        };

        telemetry_submit_imu(&sample);
        sequence++;

        imu_update_bias(&bias_lsb, gyro_z_lsb, &idle_ms);

        TickType_t elapsed = xTaskGetTickCount() - last_status_tick;

        if (elapsed >= pdMS_TO_TICKS(TELEMETRY_STATUS_PERIOD_MS)) {
            imu_status_t status = {
                .who_am_i = s_who_am_i,
                .bias_mdps = imu_lsb_to_mdps(bias_lsb),
                .idle = idle_ms > IMU_BIAS_IDLE_HOLD_MS,
                .sequence = sequence
            };

            telemetry_publish_imu_status(&status);
            last_status_tick = xTaskGetTickCount();
        }
    }
}

esp_err_t imu_task_start(void)
{
    if (s_data_ready != NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    s_data_ready = xSemaphoreCreateBinary();

    if (s_data_ready == NULL) {
        ESP_LOGE(TAG, "Failed to create data-ready semaphore");
        return ESP_ERR_NO_MEM;
    }

    esp_err_t result = mpu6500_init(&s_who_am_i);

    if (result != ESP_OK) {
        vSemaphoreDelete(s_data_ready);
        s_data_ready = NULL;
        return result;
    }

    result = imu_interrupt_init();

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "Data-ready interrupt setup failed: %s",
                 esp_err_to_name(result));
        vSemaphoreDelete(s_data_ready);
        s_data_ready = NULL;
        return result;
    }

    BaseType_t task_result = xTaskCreatePinnedToCore(
        imu_task,
        "imu_task",
        IMU_TASK_STACK_SIZE,
        NULL,
        IMU_TASK_PRIORITY,
        NULL,
        IMU_TASK_CORE_ID
    );

    if (task_result != pdPASS) {
        ESP_LOGE(TAG, "Failed to create IMU task");
        gpio_isr_handler_remove(IMU_INT_GPIO);
        vSemaphoreDelete(s_data_ready);
        s_data_ready = NULL;
        return ESP_FAIL;
    }

    ESP_LOGI(TAG, "IMU task created successfully");
    return ESP_OK;
}
