#include "task_telemetry.h"

#include <stdio.h>

#include "config.h"
#include "frame_codec.h"

#include "driver/usb_serial_jtag.h"
#include "driver/usb_serial_jtag_vfs.h"

#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"

#include "esp_err.h"
#include "esp_log.h"

static const char *TAG = "TELEMETRY";

static QueueHandle_t s_imu_queue = NULL;
static QueueHandle_t s_imu_status_queue = NULL;

/*
 * Incremented from both the producer and this task, so the count is a
 * diagnostic approximation rather than an exact tally. Locking a control-path
 * counter is not worth it when the only consumer is a log line.
 */
static volatile uint32_t s_dropped_samples = 0U;

static esp_err_t usb_link_init(void)
{
    usb_serial_jtag_driver_config_t driver_config = {
        .tx_buffer_size = TELEMETRY_USB_TX_BUFFER_SIZE,
        .rx_buffer_size = TELEMETRY_USB_RX_BUFFER_SIZE
    };

    esp_err_t result = usb_serial_jtag_driver_install(&driver_config);

    if (result != ESP_OK) {
        return result;
    }

    /*
     * Route console output through the same driver. Both paths then share one
     * ring buffer, and because usb_serial_jtag_write_bytes() commits a buffer
     * all-or-nothing, a log line can never land inside a telemetry frame.
     */
    usb_serial_jtag_vfs_use_driver();
    return ESP_OK;
}

static void telemetry_write_frame(const char *body)
{
    char frame[TELEMETRY_FRAME_MAX_LENGTH];
    size_t frame_length = frame_codec_wrap(body, frame, sizeof(frame));

    if (frame_length == 0U) {
        s_dropped_samples++;
        return;
    }

    /*
     * Never block: with no host attached the ring buffer stays full and the
     * whole frame is discarded rather than being partially written.
     */
    int written = usb_serial_jtag_write_bytes(frame, frame_length, 0);

    if (written != (int)frame_length) {
        s_dropped_samples++;
    }
}

static void telemetry_write_imu(const imu_sample_t *sample)
{
    char body[TELEMETRY_FRAME_MAX_LENGTH];
    int body_length = snprintf(
        body,
        sizeof(body),
        "IMU,%lu,%lld,%ld,%ld",
        (unsigned long)sample->sequence,
        (long long)sample->timestamp_us,
        (long)sample->gyro_z_mdps,
        (long)sample->temperature_cdeg
    );

    if (body_length <= 0 || (size_t)body_length >= sizeof(body)) {
        s_dropped_samples++;
        return;
    }

    telemetry_write_frame(body);
}

static void telemetry_write_imu_status(const imu_status_t *status)
{
    char body[TELEMETRY_FRAME_MAX_LENGTH];
    int body_length = snprintf(
        body,
        sizeof(body),
        "IMS,%u,%ld,%u,%lu,%lu",
        (unsigned)status->who_am_i,
        (long)status->bias_mdps,
        status->idle ? 1U : 0U,
        (unsigned long)s_dropped_samples,
        (unsigned long)status->sequence
    );

    if (body_length <= 0 || (size_t)body_length >= sizeof(body)) {
        return;
    }

    telemetry_write_frame(body);
}

static void telemetry_task(void *argument)
{
    (void)argument;

    esp_err_t result = usb_link_init();

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "USB CDC initialization failed: %s",
                 esp_err_to_name(result));
        vTaskDelete(NULL);
        return;
    }

    ESP_LOGI(TAG, "USB sensor uplink ready");

    TickType_t last_status_tick = xTaskGetTickCount();

    while (true) {
        imu_sample_t sample;

        if (xQueueReceive(
                s_imu_queue,
                &sample,
                pdMS_TO_TICKS(20)
            ) == pdTRUE) {
            telemetry_write_imu(&sample);
        }

        TickType_t elapsed = xTaskGetTickCount() - last_status_tick;

        if (elapsed >= pdMS_TO_TICKS(TELEMETRY_STATUS_PERIOD_MS)) {
            imu_status_t status;

            if (xQueuePeek(s_imu_status_queue, &status, 0) == pdTRUE) {
                telemetry_write_imu_status(&status);
            }

            last_status_tick = xTaskGetTickCount();
        }
    }
}

esp_err_t telemetry_submit_imu(const imu_sample_t *sample)
{
    if (sample == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    if (s_imu_queue == NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    if (xQueueSend(s_imu_queue, sample, 0) != pdPASS) {
        s_dropped_samples++;
        return ESP_ERR_NO_MEM;
    }

    return ESP_OK;
}

void telemetry_publish_imu_status(const imu_status_t *status)
{
    if (status == NULL || s_imu_status_queue == NULL) {
        return;
    }

    xQueueOverwrite(s_imu_status_queue, status);
}

esp_err_t telemetry_task_start(void)
{
    if (s_imu_queue != NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    s_imu_queue = xQueueCreate(
        TELEMETRY_QUEUE_LENGTH,
        sizeof(imu_sample_t)
    );

    if (s_imu_queue == NULL) {
        ESP_LOGE(TAG, "Failed to create telemetry queue");
        return ESP_ERR_NO_MEM;
    }

    s_imu_status_queue = xQueueCreate(1, sizeof(imu_status_t));

    if (s_imu_status_queue == NULL) {
        ESP_LOGE(TAG, "Failed to create telemetry status queue");
        vQueueDelete(s_imu_queue);
        s_imu_queue = NULL;
        return ESP_ERR_NO_MEM;
    }

    BaseType_t task_result = xTaskCreatePinnedToCore(
        telemetry_task,
        "telemetry_task",
        TELEMETRY_TASK_STACK_SIZE,
        NULL,
        TELEMETRY_TASK_PRIORITY,
        NULL,
        TELEMETRY_TASK_CORE_ID
    );

    if (task_result != pdPASS) {
        ESP_LOGE(TAG, "Failed to create telemetry task");
        vQueueDelete(s_imu_status_queue);
        vQueueDelete(s_imu_queue);
        s_imu_status_queue = NULL;
        s_imu_queue = NULL;
        return ESP_FAIL;
    }

    ESP_LOGI(TAG, "Telemetry task created successfully");
    return ESP_OK;
}
