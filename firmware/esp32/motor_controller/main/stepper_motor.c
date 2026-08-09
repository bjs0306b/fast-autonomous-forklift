#include "stepper_motor.h"

#include <stdbool.h>
#include <stdint.h>

#include "config.h"

#include "driver/gpio.h"
#include "driver/gptimer.h"
#include "esp_attr.h"
#include "esp_check.h"
#include "esp_err.h"
#include "esp_log.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "STEPPER_MOTOR";

typedef struct {
    volatile bool busy;
    volatile bool timer_running;
    volatile bool stop_requested;
    volatile bool step_high;
    volatile bool timer_error;
    volatile bool homing;
    volatile bool lower_limit_hit;
    volatile stepper_motor_direction_t direction;
    volatile uint32_t total_steps;
    volatile uint32_t completed_steps;
    volatile uint32_t current_rate_sps;
    volatile uint32_t target_rate_sps;
    volatile uint32_t acceleration_sps2;
    volatile uint32_t step_period_us;
} stepper_motor_motion_t;

static gptimer_handle_t s_step_timer = NULL;
static TaskHandle_t s_profile_task = NULL;
static stepper_motor_motion_t s_motion = {0};
static portMUX_TYPE s_motion_lock = portMUX_INITIALIZER_UNLOCKED;
static bool s_initialized = false;
static volatile bool s_homed = false;

bool stepper_motor_is_lower_limit_active(void)
{
    return gpio_get_level(STEPPER_MOTOR_LOWER_LIMIT_GPIO) ==
        STEPPER_MOTOR_LOWER_LIMIT_ACTIVE_LEVEL;
}

static uint32_t integer_sqrt_u64(uint64_t value)
{
    uint64_t result = 0;
    uint64_t bit = UINT64_C(1) << 62;

    while (bit > value) {
        bit >>= 2;
    }

    while (bit != 0) {
        if (value >= result + bit) {
            value -= result + bit;
            result = (result >> 1) + bit;
        } else {
            result >>= 1;
        }

        bit >>= 2;
    }

    return result > UINT32_MAX ? UINT32_MAX : (uint32_t)result;
}

static uint32_t rate_to_period_us(uint32_t rate_sps)
{
    if (rate_sps < STEPPER_MOTOR_MIN_RATE_SPS) {
        rate_sps = STEPPER_MOTOR_MIN_RATE_SPS;
    }

    uint32_t period_us =
        STEPPER_MOTOR_TIMER_RESOLUTION_HZ / rate_sps;
    uint32_t minimum_period_us =
        STEPPER_MOTOR_STEP_PULSE_HIGH_US * 2U;

    return period_us < minimum_period_us
        ? minimum_period_us
        : period_us;
}

static void stepper_motor_set_enabled(bool enabled)
{
    int level = enabled
        ? STEPPER_MOTOR_ENABLE_ACTIVE_LEVEL
        : !STEPPER_MOTOR_ENABLE_ACTIVE_LEVEL;

    gpio_set_level(STEPPER_MOTOR_ENABLE_GPIO, level);
}

static bool IRAM_ATTR stepper_motor_notify_profile_task_from_isr(void)
{
    BaseType_t high_priority_task_woken = pdFALSE;

    if (s_profile_task != NULL) {
        vTaskNotifyGiveFromISR(
            s_profile_task,
            &high_priority_task_woken
        );
    }

    return high_priority_task_woken == pdTRUE;
}

static bool IRAM_ATTR stepper_motor_step_alarm_callback(
    gptimer_handle_t timer,
    const gptimer_alarm_event_data_t *event_data,
    void *user_context
)
{
    (void)user_context;

    uint32_t next_interval_us;

    if (s_motion.direction == STEPPER_MOTOR_DIRECTION_RETRACT &&
        stepper_motor_is_lower_limit_active()) {
        gpio_set_level(STEPPER_MOTOR_STEP_GPIO, 0);
        s_motion.step_high = false;
        s_motion.lower_limit_hit = true;
        s_motion.busy = false;
        s_homed = true;
        return stepper_motor_notify_profile_task_from_isr();
    }

    if (s_motion.stop_requested) {
        gpio_set_level(STEPPER_MOTOR_STEP_GPIO, 0);
        s_motion.step_high = false;
        s_motion.busy = false;
        return stepper_motor_notify_profile_task_from_isr();
    }

    if (!s_motion.step_high) {
        if (s_motion.completed_steps >= s_motion.total_steps) {
            s_motion.busy = false;
            return stepper_motor_notify_profile_task_from_isr();
        }

        gpio_set_level(STEPPER_MOTOR_STEP_GPIO, 1);
        s_motion.step_high = true;
        s_motion.completed_steps++;
        next_interval_us = STEPPER_MOTOR_STEP_PULSE_HIGH_US;
    } else {
        gpio_set_level(STEPPER_MOTOR_STEP_GPIO, 0);
        s_motion.step_high = false;

        if (s_motion.completed_steps >= s_motion.total_steps) {
            s_motion.busy = false;
            return stepper_motor_notify_profile_task_from_isr();
        }

        uint32_t period_us = s_motion.step_period_us;
        next_interval_us =
            period_us > STEPPER_MOTOR_STEP_PULSE_HIGH_US
                ? period_us - STEPPER_MOTOR_STEP_PULSE_HIGH_US
                : STEPPER_MOTOR_STEP_PULSE_HIGH_US;
    }

    gptimer_alarm_config_t alarm_config = {
        .alarm_count = event_data->alarm_value + next_interval_us,
        .flags.auto_reload_on_alarm = false
    };

    if (gptimer_set_alarm_action(timer, &alarm_config) != ESP_OK) {
        gpio_set_level(STEPPER_MOTOR_STEP_GPIO, 0);
        s_motion.step_high = false;
        s_motion.timer_error = true;
        s_motion.busy = false;
        return stepper_motor_notify_profile_task_from_isr();
    }

    return false;
}

static uint32_t stepper_motor_calculate_profile_rate(void)
{
    uint32_t completed = s_motion.completed_steps;
    uint32_t total = s_motion.total_steps;
    uint32_t target = s_motion.target_rate_sps;
    uint32_t acceleration = s_motion.acceleration_sps2;
    uint32_t remaining = completed < total ? total - completed : 0;
    uint64_t start_rate_squared =
        (uint64_t)STEPPER_MOTOR_START_RATE_SPS *
        STEPPER_MOTOR_START_RATE_SPS;

    uint32_t acceleration_limit = integer_sqrt_u64(
        start_rate_squared +
        (uint64_t)2U * acceleration * completed
    );
    uint32_t deceleration_limit = integer_sqrt_u64(
        start_rate_squared +
        (uint64_t)2U * acceleration * remaining
    );

    uint32_t rate = target;

    if (rate > acceleration_limit) {
        rate = acceleration_limit;
    }

    if (rate > deceleration_limit) {
        rate = deceleration_limit;
    }

    if (rate < STEPPER_MOTOR_MIN_RATE_SPS) {
        rate = STEPPER_MOTOR_MIN_RATE_SPS;
    }

    return rate;
}

static void stepper_motor_finish_motion(void)
{
    if (s_motion.timer_running) {
        esp_err_t result = gptimer_stop(s_step_timer);

        if (result != ESP_OK && result != ESP_ERR_INVALID_STATE) {
            ESP_LOGE(TAG, "Failed to stop STEP timer: %s",
                     esp_err_to_name(result));
        }

        s_motion.timer_running = false;
    }

    gpio_set_level(STEPPER_MOTOR_STEP_GPIO, 0);
    /*
     * ⚠️ **동작이 끝나도 드라이버를 끄지 않는다.**
     *
     * 끄면 유지 토크가 사라져 포크가 중력으로 조금씩 내려앉는다. 2026-08-07 에
     * 포크 높이를 파렛트 구멍에 맞춰 놓고 몇 분 뒤 정렬을 돌리면 **이미 내려가
     * 있어서** 구멍에 안 들어갔고, 그걸 "명령한 스텝만큼 안 올라간다" 로 오해해
     * 하루 종일 높이를 다시 맞췄다.
     *
     * 대가는 대기 전류(발열)다. 시연 시간에는 문제없지만 장시간 방치하면
     * 드라이버·모터가 따뜻해진다.
     */
    if (STEPPER_MOTOR_HOLD_AFTER_MOTION == 0) {
        stepper_motor_set_enabled(false);
    }

    if (s_motion.timer_error) {
        ESP_LOGE(TAG, "Motion aborted due to STEP timer error");
    } else if (s_motion.lower_limit_hit) {
        ESP_LOGI(TAG, "Lower limit reached; fork position homed to zero");
    } else if (s_motion.homing) {
        ESP_LOGE(
            TAG,
            "Homing failed: lower limit not reached within %lu steps",
            (unsigned long)s_motion.total_steps
        );
    } else if (s_motion.stop_requested) {
        ESP_LOGW(TAG, "Motion stopped at %lu/%lu steps",
                 (unsigned long)s_motion.completed_steps,
                 (unsigned long)s_motion.total_steps);
    } else {
        ESP_LOGI(TAG, "Motion completed: %lu steps",
                 (unsigned long)s_motion.completed_steps);
    }

    s_motion.stop_requested = false;
    s_motion.homing = false;
}

static void stepper_motor_profile_task(void *argument)
{
    (void)argument;

    ESP_LOGI(TAG, "Stepper profile task started");

    bool stable_limit = stepper_motor_is_lower_limit_active();
    bool last_sample = stable_limit;
    uint32_t stable_sample_count = 1;
    uint32_t debounce_samples =
        (STEPPER_MOTOR_LIMIT_DEBOUNCE_MS +
         STEPPER_MOTOR_PROFILE_PERIOD_MS - 1U) /
        STEPPER_MOTOR_PROFILE_PERIOD_MS;

    if (debounce_samples == 0) {
        debounce_samples = 1;
    }

    ESP_LOGI(
        TAG,
        "Lower limit initial state: %s (raw=%d)",
        stable_limit ? "ACTIVE" : "released",
        gpio_get_level(STEPPER_MOTOR_LOWER_LIMIT_GPIO)
    );

    while (true) {
        ulTaskNotifyTake(
            pdTRUE,
            pdMS_TO_TICKS(STEPPER_MOTOR_PROFILE_PERIOD_MS)
        );

        bool limit_sample = stepper_motor_is_lower_limit_active();

        if (limit_sample == last_sample) {
            if (stable_sample_count < debounce_samples) {
                stable_sample_count++;
            }
        } else {
            last_sample = limit_sample;
            stable_sample_count = 1;
        }

        if (stable_sample_count >= debounce_samples &&
            stable_limit != limit_sample) {
            stable_limit = limit_sample;
            ESP_LOGI(
                TAG,
                "Lower limit changed: %s (raw=%d)",
                stable_limit ? "ACTIVE" : "released",
                gpio_get_level(STEPPER_MOTOR_LOWER_LIMIT_GPIO)
            );

            if (stable_limit) {
                s_homed = true;
            }
        }

        if (s_motion.busy) {
            uint32_t rate = stepper_motor_calculate_profile_rate();
            s_motion.current_rate_sps = rate;
            s_motion.step_period_us = rate_to_period_us(rate);
        } else if (s_motion.timer_running) {
            stepper_motor_finish_motion();
        }
    }
}

esp_err_t stepper_motor_init(void)
{
    if (s_initialized) {
        return ESP_ERR_INVALID_STATE;
    }

    /*
     * Preload safe output levels before enabling the output drivers. A
     * physical pull-up on EN is still recommended to cover reset/boot time.
     */
    gpio_set_level(STEPPER_MOTOR_STEP_GPIO, 0);
    gpio_set_level(STEPPER_MOTOR_DIR_GPIO, 0);
    stepper_motor_set_enabled(false);

    gpio_config_t io_config = {
        .pin_bit_mask =
            (UINT64_C(1) << STEPPER_MOTOR_STEP_GPIO) |
            (UINT64_C(1) << STEPPER_MOTOR_DIR_GPIO) |
            (UINT64_C(1) << STEPPER_MOTOR_ENABLE_GPIO),
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE
    };

    ESP_RETURN_ON_ERROR(
        gpio_config(&io_config),
        TAG,
        "Failed to configure STEP/DIR/EN GPIOs"
    );

    gpio_set_level(STEPPER_MOTOR_STEP_GPIO, 0);
    gpio_set_level(STEPPER_MOTOR_DIR_GPIO, 0);
    stepper_motor_set_enabled(false);

    gpio_config_t limit_config = {
        .pin_bit_mask =
            UINT64_C(1) << STEPPER_MOTOR_LOWER_LIMIT_GPIO,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE
    };

    ESP_RETURN_ON_ERROR(
        gpio_config(&limit_config),
        TAG,
        "Failed to configure lower-limit GPIO"
    );

    gptimer_config_t timer_config = {
        .clk_src = GPTIMER_CLK_SRC_DEFAULT,
        .direction = GPTIMER_COUNT_UP,
        .resolution_hz = STEPPER_MOTOR_TIMER_RESOLUTION_HZ
    };

    ESP_RETURN_ON_ERROR(
        gptimer_new_timer(&timer_config, &s_step_timer),
        TAG,
        "Failed to create STEP timer"
    );

    gptimer_event_callbacks_t callbacks = {
        .on_alarm = stepper_motor_step_alarm_callback
    };
    esp_err_t result = gptimer_register_event_callbacks(
        s_step_timer,
        &callbacks,
        NULL
    );

    if (result != ESP_OK) {
        gptimer_del_timer(s_step_timer);
        s_step_timer = NULL;
        return result;
    }

    gptimer_alarm_config_t initial_alarm = {
        .alarm_count = rate_to_period_us(
            STEPPER_MOTOR_START_RATE_SPS
        ),
        .flags.auto_reload_on_alarm = false
    };
    result = gptimer_set_alarm_action(s_step_timer, &initial_alarm);

    if (result == ESP_OK) {
        result = gptimer_enable(s_step_timer);
    }

    if (result != ESP_OK) {
        gptimer_del_timer(s_step_timer);
        s_step_timer = NULL;
        return result;
    }

    BaseType_t task_result = xTaskCreate(
        stepper_motor_profile_task,
        "stepper_profile",
        STEPPER_MOTOR_TASK_STACK_SIZE,
        NULL,
        STEPPER_MOTOR_TASK_PRIORITY,
        &s_profile_task
    );

    if (task_result != pdPASS) {
        gptimer_disable(s_step_timer);
        gptimer_del_timer(s_step_timer);
        s_step_timer = NULL;
        return ESP_ERR_NO_MEM;
    }

    s_initialized = true;
    ESP_LOGI(
        TAG,
        "Initialized: STEP=%d DIR=%d EN=%d LIMIT=%d, driver disabled",
        STEPPER_MOTOR_STEP_GPIO,
        STEPPER_MOTOR_DIR_GPIO,
        STEPPER_MOTOR_ENABLE_GPIO,
        STEPPER_MOTOR_LOWER_LIMIT_GPIO
    );
    return ESP_OK;
}

static esp_err_t stepper_motor_start_motion(
    stepper_motor_direction_t direction,
    uint32_t steps,
    uint32_t target_rate_sps,
    uint32_t acceleration_sps2,
    bool homing
)
{
    if (!s_initialized) {
        return ESP_ERR_INVALID_STATE;
    }

    if ((direction != STEPPER_MOTOR_DIRECTION_EXTEND &&
         direction != STEPPER_MOTOR_DIRECTION_RETRACT) ||
        steps == 0 ||
        target_rate_sps < STEPPER_MOTOR_START_RATE_SPS ||
        target_rate_sps > STEPPER_MOTOR_MAX_RATE_SPS ||
        acceleration_sps2 == 0 ||
        acceleration_sps2 > STEPPER_MOTOR_MAX_ACCEL_SPS2) {
        return ESP_ERR_INVALID_ARG;
    }

    if (direction == STEPPER_MOTOR_DIRECTION_RETRACT &&
        stepper_motor_is_lower_limit_active()) {
        s_homed = true;
        ESP_LOGI(TAG, "Lower limit already active; retract skipped");
        return homing ? ESP_OK : ESP_ERR_INVALID_STATE;
    }

    portENTER_CRITICAL(&s_motion_lock);

    if (s_motion.busy || s_motion.timer_running) {
        portEXIT_CRITICAL(&s_motion_lock);
        return ESP_ERR_INVALID_STATE;
    }

    s_motion.busy = true;
    s_motion.timer_running = false;
    s_motion.stop_requested = false;
    s_motion.step_high = false;
    s_motion.timer_error = false;
    s_motion.homing = homing;
    s_motion.lower_limit_hit = false;
    s_motion.direction = direction;
    s_motion.total_steps = steps;
    s_motion.completed_steps = 0;
    s_motion.current_rate_sps = STEPPER_MOTOR_START_RATE_SPS;
    s_motion.target_rate_sps = target_rate_sps;
    s_motion.acceleration_sps2 = acceleration_sps2;
    s_motion.step_period_us = rate_to_period_us(
        STEPPER_MOTOR_START_RATE_SPS
    );

    portEXIT_CRITICAL(&s_motion_lock);

    int direction_level =
        direction == STEPPER_MOTOR_DIRECTION_EXTEND
            ? STEPPER_MOTOR_EXTEND_DIR_LEVEL
            : !STEPPER_MOTOR_EXTEND_DIR_LEVEL;

    gpio_set_level(STEPPER_MOTOR_STEP_GPIO, 0);
    gpio_set_level(STEPPER_MOTOR_DIR_GPIO, direction_level);
    stepper_motor_set_enabled(true);

    esp_err_t result = gptimer_set_raw_count(s_step_timer, 0);
    gptimer_alarm_config_t alarm_config = {
        .alarm_count = s_motion.step_period_us,
        .flags.auto_reload_on_alarm = false
    };

    if (result == ESP_OK) {
        result = gptimer_set_alarm_action(
            s_step_timer,
            &alarm_config
        );
    }

    if (result == ESP_OK) {
        result = gptimer_start(s_step_timer);
    }

    if (result != ESP_OK) {
        s_motion.busy = false;
        stepper_motor_set_enabled(false);
        return result;
    }

    s_motion.timer_running = true;
    ESP_LOGI(
        TAG,
        "Motion started: direction=%s steps=%lu rate=%lu sps accel=%lu sps^2",
        direction == STEPPER_MOTOR_DIRECTION_EXTEND
            ? "extend"
            : "retract",
        (unsigned long)steps,
        (unsigned long)target_rate_sps,
        (unsigned long)acceleration_sps2
    );
    return ESP_OK;
}

esp_err_t stepper_motor_move_steps(
    stepper_motor_direction_t direction,
    uint32_t steps,
    uint32_t target_rate_sps,
    uint32_t acceleration_sps2
)
{
    return stepper_motor_start_motion(
        direction,
        steps,
        target_rate_sps,
        acceleration_sps2,
        false
    );
}

esp_err_t stepper_motor_extend(void)
{
    return stepper_motor_move_steps(
        STEPPER_MOTOR_DIRECTION_EXTEND,
        STEPPER_MOTOR_DEFAULT_MOVE_STEPS,
        STEPPER_MOTOR_DEFAULT_RATE_SPS,
        STEPPER_MOTOR_DEFAULT_ACCEL_SPS2
    );
}

esp_err_t stepper_motor_retract(void)
{
    return stepper_motor_move_steps(
        STEPPER_MOTOR_DIRECTION_RETRACT,
        STEPPER_MOTOR_DEFAULT_MOVE_STEPS,
        STEPPER_MOTOR_DEFAULT_RATE_SPS,
        STEPPER_MOTOR_DEFAULT_ACCEL_SPS2
    );
}

esp_err_t stepper_motor_home(void)
{
    return stepper_motor_start_motion(
        STEPPER_MOTOR_DIRECTION_RETRACT,
        STEPPER_MOTOR_HOMING_MAX_STEPS,
        STEPPER_MOTOR_HOMING_RATE_SPS,
        STEPPER_MOTOR_HOMING_ACCEL_SPS2,
        true
    );
}

esp_err_t stepper_motor_stop(void)
{
    if (!s_initialized) {
        return ESP_ERR_INVALID_STATE;
    }

    if (!s_motion.busy) {
        gpio_set_level(STEPPER_MOTOR_STEP_GPIO, 0);
        stepper_motor_set_enabled(false);
        return ESP_OK;
    }

    s_motion.stop_requested = true;
    return ESP_OK;
}

esp_err_t stepper_motor_get_status(stepper_motor_status_t *status)
{
    if (status == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    if (!s_initialized) {
        return ESP_ERR_INVALID_STATE;
    }

    portENTER_CRITICAL(&s_motion_lock);
    status->busy = s_motion.busy;
    status->stop_requested = s_motion.stop_requested;
    status->homing = s_motion.homing;
    status->homed = s_homed;
    status->lower_limit_active =
        stepper_motor_is_lower_limit_active();
    status->direction = s_motion.direction;
    status->total_steps = s_motion.total_steps;
    status->completed_steps = s_motion.completed_steps;
    status->current_rate_sps = s_motion.current_rate_sps;
    status->target_rate_sps = s_motion.target_rate_sps;
    status->acceleration_sps2 = s_motion.acceleration_sps2;
    portEXIT_CRITICAL(&s_motion_lock);

    return ESP_OK;
}
