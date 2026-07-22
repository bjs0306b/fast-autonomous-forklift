#include "task_comm.h"
#include "task_motor.h"

#include "esp_log.h"

static const char *TAG = "MAIN";

void app_main(void)
{
    ESP_LOGI(TAG, "Forklift motor controller starting");

    ESP_ERROR_CHECK(motor_task_start());
    ESP_ERROR_CHECK(comm_task_start());

    ESP_LOGI(TAG, "All tasks created successfully");
}