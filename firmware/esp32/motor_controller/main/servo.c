#include "servo.h"

#include "config.h"
#include "pca9685.h"

#include "esp_log.h"

static const char *TAG = "SERVO";

static pca9685_device_t s_servo_pca9685;

static uint16_t servo_pulse_us_to_count(uint32_t pulse_us)
{
    /*
     * 서보 PWM 50 Hz:
     * 1주기 = 20,000 us
     */
    uint32_t count =
        (pulse_us * 4096U) / 20000U;

    if (count > 4095U) {
        count = 4095U;
    }

    return (uint16_t)count;
}

esp_err_t servo_init(void)
{
    esp_err_t result = pca9685_device_init(
        &s_servo_pca9685,
        SERVO_PCA9685_ADDRESS,
        SERVO_PCA9685_FREQUENCY_HZ
    );

    if (result != ESP_OK) {
        ESP_LOGE(
            TAG,
            "Failed to initialize servo PCA9685: %s",
            esp_err_to_name(result)
        );
        return result;
    }

    ESP_LOGI(TAG, "Servo initialized");

    return servo_set_angle(DRIVE_REAR_STEER_CENTER_ANGLE_DEG);
}

esp_err_t servo_set_pulse_us(uint32_t pulse_us)
{
    if (pulse_us < SERVO_MIN_PULSE_US ||
        pulse_us > SERVO_MAX_PULSE_US) {
        return ESP_ERR_INVALID_ARG;
    }

    uint16_t off_count =
        servo_pulse_us_to_count(pulse_us);

    ESP_LOGI(
        TAG,
        "Pulse=%lu us, count=%u",
        (unsigned long)pulse_us,
        off_count
    );

    return pca9685_set_pwm(
        &s_servo_pca9685,
        SERVO_PCA9685_CHANNEL,
        0U,
        off_count
    );
}

esp_err_t servo_set_angle(float angle_deg)
{
    if (angle_deg < SERVO_MIN_ANGLE_DEG ||
        angle_deg > SERVO_MAX_ANGLE_DEG) {
        ESP_LOGE(
            TAG,
            "Angle %.1f outside safe range",
            angle_deg
        );
        return ESP_ERR_INVALID_ARG;
    }

    float normalized =
        angle_deg / 180.0f;

    uint32_t pulse_us =
        SERVO_MIN_PULSE_US +
        (uint32_t)(
            normalized *
            (SERVO_MAX_PULSE_US -
             SERVO_MIN_PULSE_US)
        );

    ESP_LOGI(TAG, "Angle=%.1f deg", angle_deg);

    return servo_set_pulse_us(pulse_us);
}

esp_err_t servo_stop(void)
{
    return pca9685_set_duty_percent(
        &s_servo_pca9685,
        SERVO_PCA9685_CHANNEL,
        0U
    );
}
