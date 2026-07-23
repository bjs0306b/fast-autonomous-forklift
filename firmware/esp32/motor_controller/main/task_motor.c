#include "task_motor.h"

#include "config.h"
#include "dc_motor.h"
#include "servo.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_err.h"
#include "esp_log.h"

static const char *TAG = "MOTOR_TASK";
static const dc_motor_direction_t FORKLIFT_FORWARD_DIRECTION =
    DC_MOTOR_DIRECTION_REVERSE;

static esp_err_t motor_ramp_speed(
    uint8_t start_percent,
    uint8_t target_percent
)
{
    int current_percent = start_percent;
    const int target = target_percent;

    while (current_percent != target) {
        if (current_percent < target) {
            current_percent += DRIVE_SPEED_STEP_PERCENT;

            if (current_percent > target) {
                current_percent = target;
            }
        } else {
            current_percent -= DRIVE_SPEED_STEP_PERCENT;

            if (current_percent < target) {
                current_percent = target;
            }
        }

        esp_err_t result = dc_motor_run(
            FORKLIFT_FORWARD_DIRECTION,
            (uint8_t)current_percent
        );

        if (result != ESP_OK) {
            return result;
        }

        vTaskDelay(pdMS_TO_TICKS(DRIVE_SPEED_STEP_DELAY_MS));
    }

    return ESP_OK;
}

static void motor_task(void *argument)
{
    (void)argument;

    ESP_LOGI(TAG, "Motor task started");

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

    ESP_LOGI(
        TAG,
        "Servo and DC motor initialized: forklift forward=REVERSE, rear steering center=%.1f deg",
        DRIVE_REAR_STEER_CENTER_ANGLE_DEG
    );

    while (true) {
        ESP_LOGI(TAG, "Forklift forward: rear steering=%.1f deg, motor=REVERSE, speed=%u%%",
                 DRIVE_REAR_STEER_CENTER_ANGLE_DEG,
                 DRIVE_STRAIGHT_SPEED_PERCENT);

        result = dc_motor_run(
            FORKLIFT_FORWARD_DIRECTION,
            DRIVE_STRAIGHT_SPEED_PERCENT
        );

        if (result != ESP_OK) {
            ESP_LOGE(TAG, "Failed to start straight drive: %s",
                     esp_err_to_name(result));
            goto fail_safe;
        }

        vTaskDelay(pdMS_TO_TICKS(DRIVE_PHASE_DURATION_MS));

        ESP_LOGI(TAG, "Decelerating for right turn");

        result = motor_ramp_speed(
            DRIVE_STRAIGHT_SPEED_PERCENT,
            DRIVE_TURN_SPEED_PERCENT
        );

        if (result != ESP_OK) {
            ESP_LOGE(TAG, "Failed to decelerate for right turn: %s",
                     esp_err_to_name(result));
            goto fail_safe;
        }

        result = servo_set_angle(
            DRIVE_REAR_STEER_RIGHT_TURN_ANGLE_DEG
        );

        if (result != ESP_OK) {
            ESP_LOGE(TAG, "Failed to steer right: %s",
                     esp_err_to_name(result));
            goto fail_safe;
        }

        ESP_LOGI(TAG, "Forklift right turn: rear steering=%.1f deg, motor=REVERSE, speed=%u%%",
                 DRIVE_REAR_STEER_RIGHT_TURN_ANGLE_DEG,
                 DRIVE_TURN_SPEED_PERCENT);
        vTaskDelay(pdMS_TO_TICKS(DRIVE_PHASE_DURATION_MS));

        result = servo_set_angle(DRIVE_REAR_STEER_CENTER_ANGLE_DEG);

        if (result != ESP_OK) {
            ESP_LOGE(TAG, "Failed to center steering: %s",
                     esp_err_to_name(result));
            goto fail_safe;
        }

        ESP_LOGI(TAG, "Accelerating for straight drive");

        result = motor_ramp_speed(
            DRIVE_TURN_SPEED_PERCENT,
            DRIVE_STRAIGHT_SPEED_PERCENT
        );

        if (result != ESP_OK) {
            ESP_LOGE(TAG, "Failed to accelerate after right turn: %s",
                     esp_err_to_name(result));
            goto fail_safe;
        }

        ESP_LOGI(TAG, "Forklift forward: rear steering=%.1f deg, motor=REVERSE, speed=%u%%",
                 DRIVE_REAR_STEER_CENTER_ANGLE_DEG,
                 DRIVE_STRAIGHT_SPEED_PERCENT);
        vTaskDelay(pdMS_TO_TICKS(DRIVE_PHASE_DURATION_MS));

        ESP_LOGI(TAG, "Decelerating for left turn");

        result = motor_ramp_speed(
            DRIVE_STRAIGHT_SPEED_PERCENT,
            DRIVE_TURN_SPEED_PERCENT
        );

        if (result != ESP_OK) {
            ESP_LOGE(TAG, "Failed to decelerate for left turn: %s",
                     esp_err_to_name(result));
            goto fail_safe;
        }

        result = servo_set_angle(
            DRIVE_REAR_STEER_LEFT_TURN_ANGLE_DEG
        );

        if (result != ESP_OK) {
            ESP_LOGE(TAG, "Failed to steer left: %s",
                     esp_err_to_name(result));
            goto fail_safe;
        }

        ESP_LOGI(TAG, "Forklift left turn: rear steering=%.1f deg, motor=REVERSE, speed=%u%%",
                 DRIVE_REAR_STEER_LEFT_TURN_ANGLE_DEG,
                 DRIVE_TURN_SPEED_PERCENT);
        vTaskDelay(pdMS_TO_TICKS(DRIVE_PHASE_DURATION_MS));

        result = servo_set_angle(DRIVE_REAR_STEER_CENTER_ANGLE_DEG);

        if (result != ESP_OK) {
            ESP_LOGE(TAG, "Failed to center steering: %s",
                     esp_err_to_name(result));
            goto fail_safe;
        }

        ESP_LOGI(TAG, "DC motor stop");

        result = dc_motor_stop();

        if (result != ESP_OK) {
            ESP_LOGE(TAG, "Failed to stop motor: %s",
                     esp_err_to_name(result));
            goto fail_safe;
        }

        vTaskDelay(pdMS_TO_TICKS(DRIVE_LOOP_PAUSE_MS));
    }

fail_safe:
    ESP_LOGE(TAG, "Drive sequence aborted; applying fail-safe stop");

    esp_err_t stop_result = dc_motor_stop();

    if (stop_result != ESP_OK) {
        ESP_LOGE(TAG, "Fail-safe motor stop failed: %s",
                 esp_err_to_name(stop_result));
    }

    esp_err_t center_result =
        servo_set_angle(DRIVE_REAR_STEER_CENTER_ANGLE_DEG);

    if (center_result != ESP_OK) {
        ESP_LOGE(TAG, "Fail-safe steering center failed: %s",
                 esp_err_to_name(center_result));
    }

    vTaskDelete(NULL);
}

esp_err_t motor_task_start(void)
{
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
        return ESP_FAIL;
    }

    ESP_LOGI(TAG, "Motor task created successfully");

    return ESP_OK;
}
