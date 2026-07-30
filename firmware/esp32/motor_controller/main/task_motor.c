#include "task_motor.h"

#include <stdbool.h>
#include <stdlib.h>

#include "config.h"
#include "dc_motor.h"
#include "servo.h"

#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"

#include "esp_err.h"
#include "esp_log.h"

static const char *TAG = "MOTOR_TASK";

static QueueHandle_t s_motor_command_queue = NULL;
static volatile bool s_drive_idle = true;

static esp_err_t motor_apply_safe_stop(void)
{
    s_drive_idle = true;

    esp_err_t stop_result = dc_motor_stop();
    esp_err_t center_result =
        servo_set_angle(DRIVE_REAR_STEER_CENTER_ANGLE_DEG);

    if (stop_result != ESP_OK) {
        return stop_result;
    }

    return center_result;
}

static esp_err_t motor_apply_command(
    const motor_command_t *command,
    motor_command_t *applied_command
)
{
    if (command == NULL || applied_command == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    /* Published before the motor actually spins, so idle never lags motion */
    s_drive_idle = command->drive_percent == 0;

    if (command->drive_percent == 0) {
        if (applied_command->drive_percent != 0) {
            esp_err_t result = dc_motor_stop();

            if (result != ESP_OK) {
                return result;
            }
        }

        if (command->steering_cdeg != applied_command->steering_cdeg) {
            esp_err_t result = servo_set_angle(
                (float)command->steering_cdeg / 100.0f
            );

            if (result != ESP_OK) {
                return result;
            }
        }

        *applied_command = *command;
        return ESP_OK;
    }

    if (command->steering_cdeg != applied_command->steering_cdeg) {
        esp_err_t result = servo_set_angle(
            (float)command->steering_cdeg / 100.0f
        );

        if (result != ESP_OK) {
            return result;
        }
    }

    if (command->drive_percent != applied_command->drive_percent) {
        dc_motor_direction_t direction =
            command->drive_percent > 0
                ? DC_MOTOR_DIRECTION_REVERSE
                : DC_MOTOR_DIRECTION_FORWARD;

        esp_err_t result = dc_motor_run(
            direction,
            (uint8_t)abs((int)command->drive_percent)
        );

        if (result != ESP_OK) {
            return result;
        }
    }

    *applied_command = *command;
    return ESP_OK;
}

static void motor_task(void *argument)
{
    (void)argument;

    ESP_LOGI(TAG, "Command-driven motor task started");

    esp_err_t result = servo_init();

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "Servo initialization failed: %s",
                 esp_err_to_name(result));
        vTaskDelete(NULL);
        return;
    }

    result = dc_motor_init();

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "DC motor initialization failed: %s",
                 esp_err_to_name(result));
        vTaskDelete(NULL);
        return;
    }

    motor_command_t applied_command = {
        .drive_percent = 0,
        .steering_cdeg = TELEOP_STEERING_CENTER_CDEG,
        .sequence = 0
    };
    TickType_t last_valid_command_tick = xTaskGetTickCount();
    bool watchdog_stopped = true;

    ESP_LOGI(TAG, "Actuators ready; waiting for UART commands");

    while (true) {
        motor_command_t command;

        if (xQueueReceive(
                s_motor_command_queue,
                &command,
                pdMS_TO_TICKS(50)
            ) == pdTRUE) {
            result = motor_apply_command(&command, &applied_command);

            if (result != ESP_OK) {
                ESP_LOGE(TAG, "Command %lu failed: %s",
                         (unsigned long)command.sequence,
                         esp_err_to_name(result));
                break;
            }

            last_valid_command_tick = xTaskGetTickCount();
            watchdog_stopped = false;
            ESP_LOGD(TAG, "Applied command %lu: drive=%d%%, steering=%.2f deg",
                     (unsigned long)command.sequence,
                     command.drive_percent,
                     (double)command.steering_cdeg / 100.0);
        }

        TickType_t elapsed =
            xTaskGetTickCount() - last_valid_command_tick;

        if (!watchdog_stopped &&
            elapsed >= pdMS_TO_TICKS(TELEOP_WATCHDOG_TIMEOUT_MS)) {
            ESP_LOGW(TAG, "Command watchdog expired; stopping actuators");
            result = motor_apply_safe_stop();

            if (result != ESP_OK) {
                ESP_LOGE(TAG, "Watchdog safe stop failed: %s",
                         esp_err_to_name(result));
                break;
            }

            applied_command.drive_percent = 0;
            applied_command.steering_cdeg =
                TELEOP_STEERING_CENTER_CDEG;
            watchdog_stopped = true;
        }
    }

    ESP_LOGE(TAG, "Motor task aborted; applying fail-safe stop");
    result = motor_apply_safe_stop();

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "Fail-safe stop failed: %s",
                 esp_err_to_name(result));
    }

    vTaskDelete(NULL);
}

bool motor_task_drive_is_idle(void)
{
    return s_drive_idle;
}

esp_err_t motor_task_submit_command(const motor_command_t *command)
{
    if (command == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    if (s_motor_command_queue == NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    if (command->drive_percent < -TELEOP_MAX_DRIVE_PERCENT ||
        command->drive_percent > TELEOP_MAX_DRIVE_PERCENT ||
        command->steering_cdeg < TELEOP_STEERING_MIN_CDEG ||
        command->steering_cdeg > TELEOP_STEERING_MAX_CDEG) {
        return ESP_ERR_INVALID_ARG;
    }

    return xQueueOverwrite(s_motor_command_queue, command) == pdPASS
        ? ESP_OK
        : ESP_FAIL;
}

esp_err_t motor_task_start(void)
{
    if (s_motor_command_queue != NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    s_motor_command_queue = xQueueCreate(1, sizeof(motor_command_t));

    if (s_motor_command_queue == NULL) {
        ESP_LOGE(TAG, "Failed to create motor command queue");
        return ESP_ERR_NO_MEM;
    }

    BaseType_t task_result = xTaskCreate(
        motor_task,
        "motor_task",
        MOTOR_TASK_STACK_SIZE,
        NULL,
        MOTOR_TASK_PRIORITY,
        NULL
    );

    if (task_result != pdPASS) {
        ESP_LOGE(TAG, "Failed to create motor task");
        vQueueDelete(s_motor_command_queue);
        s_motor_command_queue = NULL;
        return ESP_FAIL;
    }

    ESP_LOGI(TAG, "Motor task created successfully");
    return ESP_OK;
}
