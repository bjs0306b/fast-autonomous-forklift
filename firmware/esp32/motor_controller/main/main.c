#include "task_comm.h"
#include "task_imu.h"
#include "task_motor.h"
#include "task_telemetry.h"
#include "task_tof.h"
#include "task_encoder.h"
#include "stepper_motor.h"
#include "config.h"

#include "esp_err.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "MAIN";

static esp_err_t wait_for_stepper_idle(
    stepper_motor_status_t *status
)
{
    do {
        esp_err_t result = stepper_motor_get_status(status);

        if (result != ESP_OK) {
            return result;
        }

        if (status->busy) {
            vTaskDelay(pdMS_TO_TICKS(
                STEPPER_MOTOR_PROFILE_PERIOD_MS
            ));
        }
    } while (status->busy);

    return ESP_OK;
}

static esp_err_t run_guarded_homing_sequence(void)
{
    stepper_motor_status_t status;

    ESP_LOGW(TAG, "Starting guarded lower-limit homing");
    esp_err_t result = stepper_motor_home();

    if (result != ESP_OK) {
        return result;
    }

    result = wait_for_stepper_idle(&status);

    if (result != ESP_OK) {
        return result;
    }

    if (!status.homed) {
        ESP_LOGE(TAG, "Lower-limit homing did not establish home");
        return ESP_FAIL;
    }

    if (!status.lower_limit_active) {
        ESP_LOGI(TAG, "Homing completed with lower limit released");
        return ESP_OK;
    }

    ESP_LOGI(
        TAG,
        "Lower limit active; backing off after %u ms",
        STEPPER_MOTOR_HOME_BACKOFF_DELAY_MS
    );
    vTaskDelay(pdMS_TO_TICKS(
        STEPPER_MOTOR_HOME_BACKOFF_DELAY_MS
    ));

    result = stepper_motor_move_steps(
        STEPPER_MOTOR_DIRECTION_EXTEND,
        STEPPER_MOTOR_HOME_BACKOFF_STEPS,
        STEPPER_MOTOR_HOME_BACKOFF_RATE_SPS,
        STEPPER_MOTOR_HOME_BACKOFF_ACCEL_SPS2
    );

    if (result != ESP_OK) {
        return result;
    }

    result = wait_for_stepper_idle(&status);

    if (result != ESP_OK) {
        return result;
    }

    if (status.lower_limit_active) {
        ESP_LOGE(
            TAG,
            "Backoff completed but lower limit is still active"
        );
        return ESP_FAIL;
    }

    ESP_LOGI(
        TAG,
        "Homing backoff completed: %u steps, lower limit released",
        STEPPER_MOTOR_HOME_BACKOFF_STEPS
    );
    return ESP_OK;
}

void app_main(void)
{
    ESP_LOGI(TAG, "Forklift motor controller starting");

    ESP_ERROR_CHECK(stepper_motor_init());
    ESP_ERROR_CHECK(motor_task_start());
    ESP_ERROR_CHECK(comm_task_start());

    /*
     * The sensor uplink is best-effort: a missing USB host or a missing gyro
     * must never keep the drive and fork from running. Start the uplink before
     * homing so its log output already goes through the USB driver, and start
     * the IMU afterwards so bias is measured with the fork at rest.
     */
    esp_err_t telemetry_result = telemetry_task_start();

    if (telemetry_result != ESP_OK) {
        ESP_LOGE(TAG, "Telemetry uplink unavailable: %s",
                 esp_err_to_name(telemetry_result));
    }

#if STEPPER_MOTOR_STARTUP_HOME_ENABLED
    for (int seconds = 10; seconds > 0; --seconds) {
        ESP_LOGW(TAG,
                 "Stepper homing starts in %d second(s); "
                 "keep power cutoff ready",
                 seconds);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
    /*
     * ⚠️ **여기에 ESP_ERROR_CHECK 를 쓰지 않는다.**
     *
     * 2026-08-09: 드라이버 열보호로 포크가 안 내려가 호밍이 실패했는데,
     * ESP_ERROR_CHECK 가 패닉을 내 보드가 리셋되고 10 초 뒤 또 호밍을 걸었다.
     * 뜨거운 드라이버에 통전이 끊기지 않고 반복돼 **과열을 오히려 키웠다.**
     *
     * 호밍 실패는 치명적이지 않다 — 포크 높이 기준만 없는 상태이고, 주행·통신은
     * 그대로 살아 있어야 사람이 원인을 볼 수 있다. 그래서 드라이버를 끄고
     * 시끄럽게 남긴 뒤 계속 부팅한다. 원인이 풀리면 `/fork/command` 의 `HOME`
     * 으로 다시 걸면 된다.
     */
    esp_err_t homing_result = run_guarded_homing_sequence();

    if (homing_result != ESP_OK) {
        ESP_LOGE(
            TAG,
            "Startup homing failed: %s — driver disabled, fork height "
            "is UNKNOWN. Check the lower-limit wiring, driver temperature "
            "(TMC2209 shuts down when hot), and whether the fork is stuck. "
            "Re-run with the HOME fork command once fixed.",
            esp_err_to_name(homing_result)
        );
        (void)stepper_motor_stop();
    }
#elif STEPPER_MOTOR_STARTUP_TEST_ENABLED
    for (int seconds = 10; seconds > 0; --seconds) {
        ESP_LOGW(TAG,
                 "Stepper retract test starts in %d second(s); "
                 "keep power cutoff ready",
                 seconds);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
    ESP_LOGW(TAG, "Starting load-free stepper retract test");
    ESP_ERROR_CHECK(stepper_motor_move_steps(
        STEPPER_MOTOR_DIRECTION_RETRACT,
        STEPPER_MOTOR_STARTUP_TEST_STEPS,
        STEPPER_MOTOR_STARTUP_TEST_RATE_SPS,
        STEPPER_MOTOR_DEFAULT_ACCEL_SPS2));
#endif

    if (telemetry_result == ESP_OK) {
        esp_err_t imu_result = imu_task_start();

        if (imu_result != ESP_OK) {
            ESP_LOGE(TAG, "IMU unavailable, continuing without gyro: %s",
                     esp_err_to_name(imu_result));
        }

        /*
         * Started last: each sensor takes about a second to accept its firmware
         * over I2C, and nothing else should wait on that.
         */
        esp_err_t tof_result = tof_task_start();

        if (tof_result != ESP_OK) {
            ESP_LOGE(TAG,
                     "Front ToF unavailable, continuing without it: %s",
                     esp_err_to_name(tof_result));
        }

        esp_err_t encoder_result = encoder_task_start();

        if (encoder_result != ESP_OK) {
            ESP_LOGE(TAG,
                     "Wheel encoder unavailable, continuing without it: %s",
                     esp_err_to_name(encoder_result));
        }
    } else {
        ESP_LOGW(TAG, "Skipping IMU and ToF start; nowhere to publish");
    }

    ESP_LOGI(TAG, "All tasks created successfully");
}
