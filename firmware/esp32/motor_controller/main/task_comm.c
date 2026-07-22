#include "task_comm.h"

#include "config.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_err.h"
#include "esp_log.h"

static const char *TAG = "COMM_TASK";

static void communication_task(void *argument)
{
    (void)argument;

    ESP_LOGI(TAG, "Communication task started");

    while (true) {
        ESP_LOGI(TAG, "Waiting for communication data");

        vTaskDelay(
            pdMS_TO_TICKS(COMM_TASK_PERIOD_MS)
        );
    }
}

esp_err_t comm_task_start(void)
{
    BaseType_t task_result = xTaskCreate(
        communication_task,
        "communication_task",
        COMM_TASK_STACK_SIZE,
        NULL,
        COMM_TASK_PRIORITY,
        NULL
    );

    if (task_result != pdPASS) {
        ESP_LOGE(TAG, "Failed to create communication task");
        return ESP_FAIL;
    }

    ESP_LOGI(TAG, "Communication task created successfully");

    return ESP_OK;
}