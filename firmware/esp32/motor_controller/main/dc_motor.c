#include "dc_motor.h"

#include "config.h"
#include "pca9685.h"

#include "esp_log.h"

static const char *TAG = "DC_MOTOR";

static pca9685_device_t s_motor_hat_pca9685;
static bool s_dc_motor_initialized = false;

static esp_err_t dc_motor_set_direction(
    dc_motor_direction_t direction
)
{
    esp_err_t result;

    switch (direction) {
        case DC_MOTOR_DIRECTION_FORWARD:
            /*
             * BIN1 = LOW
             * BIN2 = HIGH
             */
            result = pca9685_set_level(
                &s_motor_hat_pca9685,
                MOTOR_B_BIN1_CHANNEL,
                false
            );

            if (result != ESP_OK) {
                return result;
            }

            return pca9685_set_level(
                &s_motor_hat_pca9685,
                MOTOR_B_BIN2_CHANNEL,
                true
            );

        case DC_MOTOR_DIRECTION_REVERSE:
            /*
             * BIN1 = HIGH
             * BIN2 = LOW
             */
            result = pca9685_set_level(
                &s_motor_hat_pca9685,
                MOTOR_B_BIN1_CHANNEL,
                true
            );

            if (result != ESP_OK) {
                return result;
            }

            return pca9685_set_level(
                &s_motor_hat_pca9685,
                MOTOR_B_BIN2_CHANNEL,
                false
            );

        default:
            return ESP_ERR_INVALID_ARG;
    }
}

esp_err_t dc_motor_init(void)
{
    esp_err_t result = pca9685_device_init(
        &s_motor_hat_pca9685,
        MOTOR_HAT_PCA9685_ADDRESS,
        MOTOR_HAT_PWM_FREQUENCY_HZ
    );

    if (result != ESP_OK) {
        ESP_LOGE(
            TAG,
            "Failed to initialize Motor Driver HAT: %s",
            esp_err_to_name(result)
        );
        return result;
    }

    s_dc_motor_initialized = true;

    result = dc_motor_stop();

    if (result != ESP_OK) {
        s_dc_motor_initialized = false;
        return result;
    }

    ESP_LOGI(
        TAG,
        "Motor B initialized: address=0x%02X, PWMB=%d, BIN1=%d, BIN2=%d",
        MOTOR_HAT_PCA9685_ADDRESS,
        MOTOR_B_PWMB_CHANNEL,
        MOTOR_B_BIN1_CHANNEL,
        MOTOR_B_BIN2_CHANNEL
    );

    return ESP_OK;
}

esp_err_t dc_motor_run(
    dc_motor_direction_t direction,
    uint8_t speed_percent
)
{
    if (!s_dc_motor_initialized) {
        return ESP_ERR_INVALID_STATE;
    }

    if (speed_percent > 100U) {
        return ESP_ERR_INVALID_ARG;
    }

    if (speed_percent == 0U) {
        return dc_motor_stop();
    }

    /*
     * 방향 변경 전에 PWM을 잠시 0으로 만들어
     * H-브리지 전환 충격을 줄인다.
     */
    esp_err_t result = pca9685_set_duty_percent(
        &s_motor_hat_pca9685,
        MOTOR_B_PWMB_CHANNEL,
        0U
    );

    if (result != ESP_OK) {
        return result;
    }

    result = dc_motor_set_direction(direction);

    if (result != ESP_OK) {
        return result;
    }

    result = pca9685_set_duty_percent(
        &s_motor_hat_pca9685,
        MOTOR_B_PWMB_CHANNEL,
        speed_percent
    );

    if (result == ESP_OK) {
        ESP_LOGI(
            TAG,
            "Motor B: %s, speed=%u%%",
            direction == DC_MOTOR_DIRECTION_FORWARD
                ? "FORWARD"
                : "REVERSE",
            speed_percent
        );
    }

    return result;
}

esp_err_t dc_motor_stop(void)
{
    if (!s_dc_motor_initialized) {
        return ESP_ERR_INVALID_STATE;
    }

    esp_err_t result = pca9685_set_duty_percent(
        &s_motor_hat_pca9685,
        MOTOR_B_PWMB_CHANNEL,
        0U
    );

    if (result != ESP_OK) {
        return result;
    }

    result = pca9685_set_level(
        &s_motor_hat_pca9685,
        MOTOR_B_BIN1_CHANNEL,
        false
    );

    if (result != ESP_OK) {
        return result;
    }

    result = pca9685_set_level(
        &s_motor_hat_pca9685,
        MOTOR_B_BIN2_CHANNEL,
        false
    );

    if (result == ESP_OK) {
        ESP_LOGI(TAG, "Motor B stopped");
    }

    return result;
}

esp_err_t dc_motor_brake(void)
{
    if (!s_dc_motor_initialized) {
        return ESP_ERR_INVALID_STATE;
    }

    esp_err_t result = pca9685_set_level(
        &s_motor_hat_pca9685,
        MOTOR_B_BIN1_CHANNEL,
        true
    );

    if (result != ESP_OK) {
        return result;
    }

    result = pca9685_set_level(
        &s_motor_hat_pca9685,
        MOTOR_B_BIN2_CHANNEL,
        true
    );

    if (result != ESP_OK) {
        return result;
    }

    result = pca9685_set_duty_percent(
        &s_motor_hat_pca9685,
        MOTOR_B_PWMB_CHANNEL,
        100U
    );

    if (result == ESP_OK) {
        ESP_LOGI(TAG, "Motor B brake applied");
    }

    return result;
}