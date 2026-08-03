#include "task_comm.h"

#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "config.h"
#include "frame_codec.h"
#include "stepper_motor.h"
#include "task_motor.h"

#include "driver/uart.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_err.h"
#include "esp_log.h"

static const char *TAG = "COMM_TASK";

typedef enum {
    LIFT_ACTION_UP,
    LIFT_ACTION_DOWN,
    /* Retract until the lower home switch is reached, not a fixed distance. */
    LIFT_ACTION_HOME,
    /* Center steering, stop traction, then run the lower-switch homing. */
    LIFT_ACTION_INITIALIZE,
    LIFT_ACTION_STOP
} lift_action_t;

typedef struct {
    uint32_t sequence;
    lift_action_t action;
} lift_command_t;

static bool is_decimal_digits(const char *text)
{
    if (text == NULL || *text == '\0') {
        return false;
    }

    for (const char *cursor = text; *cursor != '\0'; cursor++) {
        if (*cursor < '0' || *cursor > '9') {
            return false;
        }
    }

    return true;
}

static bool is_signed_decimal(const char *text)
{
    if (text != NULL && *text == '-') {
        text++;
    }

    return is_decimal_digits(text);
}

static esp_err_t parse_command_frame(
    char *frame,
    motor_command_t *command
)
{
    if (frame == NULL || command == NULL || frame[0] != '@') {
        return ESP_ERR_INVALID_ARG;
    }

    char *separator = strrchr(frame, '*');

    if (separator == NULL || strlen(separator + 1) != 4U) {
        return ESP_ERR_INVALID_ARG;
    }

    char *crc_end = NULL;
    unsigned long received_crc = strtoul(separator + 1, &crc_end, 16);

    if (crc_end == NULL || *crc_end != '\0' || received_crc > 0xFFFFUL) {
        return ESP_ERR_INVALID_ARG;
    }

    *separator = '\0';
    const char *body = frame + 1;
    uint16_t calculated_crc = crc16_ccitt_false(
        (const uint8_t *)body,
        strlen(body)
    );

    if ((uint16_t)received_crc != calculated_crc) {
        return ESP_ERR_INVALID_CRC;
    }

    if (strncmp(body, "CMD,", 4U) != 0) {
        return ESP_ERR_INVALID_ARG;
    }

    char *sequence_text = (char *)body + 4;
    char *first_comma = strchr(sequence_text, ',');

    if (first_comma == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    *first_comma = '\0';
    char *drive_text = first_comma + 1;
    char *second_comma = strchr(drive_text, ',');

    if (second_comma == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    *second_comma = '\0';
    char *steering_text = second_comma + 1;

    if (strchr(steering_text, ',') != NULL ||
        !is_decimal_digits(sequence_text) ||
        !is_signed_decimal(drive_text) ||
        !is_decimal_digits(steering_text)) {
        return ESP_ERR_INVALID_ARG;
    }

    char *field_end = NULL;
    errno = 0;
    unsigned long sequence = strtoul(sequence_text, &field_end, 10);

    if (errno == ERANGE || *field_end != '\0' || sequence > UINT32_MAX) {
        return ESP_ERR_INVALID_ARG;
    }

    errno = 0;
    long drive_percent = strtol(drive_text, &field_end, 10);

    if (errno == ERANGE || *field_end != '\0' ||
        drive_percent < -TELEOP_MAX_DRIVE_PERCENT ||
        drive_percent > TELEOP_MAX_DRIVE_PERCENT) {
        return ESP_ERR_INVALID_ARG;
    }

    errno = 0;
    unsigned long steering_cdeg = strtoul(
        steering_text,
        &field_end,
        10
    );

    if (errno == ERANGE || *field_end != '\0' ||
        steering_cdeg < TELEOP_STEERING_MIN_CDEG ||
        steering_cdeg > TELEOP_STEERING_MAX_CDEG) {
        return ESP_ERR_INVALID_ARG;
    }

    command->sequence = (uint32_t)sequence;
    command->drive_percent = (int8_t)drive_percent;
    command->steering_cdeg = (uint16_t)steering_cdeg;
    return ESP_OK;
}

static esp_err_t parse_lift_frame(
    char *frame,
    lift_command_t *command
)
{
    if (frame == NULL || command == NULL || frame[0] != '@') {
        return ESP_ERR_INVALID_ARG;
    }

    char *separator = strrchr(frame, '*');

    if (separator == NULL || strlen(separator + 1) != 4U) {
        return ESP_ERR_INVALID_ARG;
    }

    char *crc_end = NULL;
    unsigned long received_crc = strtoul(separator + 1, &crc_end, 16);

    if (crc_end == NULL || *crc_end != '\0' || received_crc > 0xFFFFUL) {
        return ESP_ERR_INVALID_ARG;
    }

    *separator = '\0';
    const char *body = frame + 1;
    uint16_t calculated_crc = crc16_ccitt_false(
        (const uint8_t *)body,
        strlen(body)
    );

    if ((uint16_t)received_crc != calculated_crc) {
        return ESP_ERR_INVALID_CRC;
    }

    if (strncmp(body, "LIFT,", 5U) != 0) {
        return ESP_ERR_INVALID_ARG;
    }

    char *sequence_text = (char *)body + 5;
    char *comma = strchr(sequence_text, ',');

    if (comma == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    *comma = '\0';
    char *action_text = comma + 1;

    if (strchr(action_text, ',') != NULL ||
        !is_decimal_digits(sequence_text)) {
        return ESP_ERR_INVALID_ARG;
    }

    char *field_end = NULL;
    errno = 0;
    unsigned long sequence = strtoul(sequence_text, &field_end, 10);

    if (errno == ERANGE || *field_end != '\0' || sequence > UINT32_MAX) {
        return ESP_ERR_INVALID_ARG;
    }

    lift_action_t action;

    if (strcmp(action_text, "UP") == 0) {
        action = LIFT_ACTION_UP;
    } else if (strcmp(action_text, "DOWN") == 0) {
        action = LIFT_ACTION_DOWN;
    } else if (strcmp(action_text, "HOME") == 0) {
        action = LIFT_ACTION_HOME;
    } else if (strcmp(action_text, "INITIALIZE") == 0) {
        action = LIFT_ACTION_INITIALIZE;
    } else if (strcmp(action_text, "STOP") == 0) {
        action = LIFT_ACTION_STOP;
    } else {
        return ESP_ERR_INVALID_ARG;
    }

    command->sequence = (uint32_t)sequence;
    command->action = action;
    return ESP_OK;
}

static void send_ack_status(uint32_t sequence, const char *status)
{
    char body[40];
    int body_length = snprintf(
        body,
        sizeof(body),
        "ACK,%lu,%s",
        (unsigned long)sequence,
        status
    );

    if (body_length <= 0 || (size_t)body_length >= sizeof(body)) {
        return;
    }

    char frame[56];
    size_t frame_length = frame_codec_wrap(body, frame, sizeof(frame));

    if (frame_length > 0U) {
        uart_write_bytes(
            JETSON_UART_PORT,
            frame,
            frame_length
        );
    }
}

static void send_ack(uint32_t sequence)
{
    send_ack_status(sequence, "OK");
}

static void send_error_ack(uint32_t sequence)
{
    send_ack_status(sequence, "ERROR");
}

static void send_lift_status(
    uint32_t sequence,
    const char *state,
    const stepper_motor_status_t *status
)
{
    if (state == NULL || status == NULL) {
        return;
    }

    char body[80];
    int body_length = snprintf(
        body,
        sizeof(body),
        "LIFT_STATUS,%lu,%s,%lu,%lu,%u",
        (unsigned long)sequence,
        state,
        (unsigned long)status->completed_steps,
        (unsigned long)status->total_steps,
        status->lower_limit_active ? 1U : 0U
    );

    if (body_length <= 0 || (size_t)body_length >= sizeof(body)) {
        return;
    }

    char frame[96];
    size_t frame_length = frame_codec_wrap(body, frame, sizeof(frame));

    if (frame_length > 0U) {
        uart_write_bytes(JETSON_UART_PORT, frame, frame_length);
    }
}

static esp_err_t apply_lift_command(const lift_command_t *command)
{
    if (command == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    switch (command->action) {
        case LIFT_ACTION_UP:
            return stepper_motor_extend();
        case LIFT_ACTION_DOWN:
            return stepper_motor_retract();
        case LIFT_ACTION_HOME:
            return stepper_motor_home();
        case LIFT_ACTION_INITIALIZE: {
            /*
             * Route this through the motor task so PCA9685 access remains
             * serialized. The ROS bridge holds its drive command at zero
             * until this homing request reaches a terminal status.
             */
            motor_command_t safe_command = {
                .drive_percent = 0,
                .steering_cdeg = TELEOP_STEERING_CENTER_CDEG,
                .sequence = command->sequence
            };
            esp_err_t result = motor_task_submit_command(&safe_command);

            if (result != ESP_OK) {
                return result;
            }

            return stepper_motor_home();
        }
        case LIFT_ACTION_STOP:
            return stepper_motor_stop();
        default:
            return ESP_ERR_INVALID_ARG;
    }
}

static esp_err_t uart_initialize(void)
{
    uart_config_t uart_config = {
        .baud_rate = JETSON_UART_BAUD_RATE,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT
    };

    esp_err_t result = uart_param_config(
        JETSON_UART_PORT,
        &uart_config
    );

    if (result != ESP_OK) {
        return result;
    }

    result = uart_set_pin(
        JETSON_UART_PORT,
        JETSON_UART_TX_GPIO,
        JETSON_UART_RX_GPIO,
        UART_PIN_NO_CHANGE,
        UART_PIN_NO_CHANGE
    );

    if (result != ESP_OK) {
        return result;
    }

    return uart_driver_install(
        JETSON_UART_PORT,
        JETSON_UART_RX_BUFFER_SIZE,
        JETSON_UART_TX_BUFFER_SIZE,
        0,
        NULL,
        0
    );
}

static void communication_task(void *argument)
{
    (void)argument;

    esp_err_t result = uart_initialize();

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "UART initialization failed: %s",
                 esp_err_to_name(result));
        vTaskDelete(NULL);
        return;
    }

    ESP_LOGI(TAG, "UART ready: port=%d, TX=%d, RX=%d, baud=%d",
             JETSON_UART_PORT,
             JETSON_UART_TX_GPIO,
             JETSON_UART_RX_GPIO,
             JETSON_UART_BAUD_RATE);

    uint8_t rx_buffer[64];
    char frame[JETSON_UART_FRAME_MAX_LENGTH];
    size_t frame_length = 0;
    bool receiving_frame = false;
    bool lift_command_pending = false;
    bool lift_backoff_waiting = false;
    bool lift_requires_home = false;
    uint32_t lift_sequence = 0;
    lift_action_t lift_action = LIFT_ACTION_STOP;
    TickType_t lift_backoff_deadline = 0;

    while (true) {
        int received = uart_read_bytes(
            JETSON_UART_PORT,
            rx_buffer,
            sizeof(rx_buffer),
            pdMS_TO_TICKS(100)
        );

        if (received < 0) {
            ESP_LOGE(TAG, "UART read failed");
            continue;
        }

        for (int index = 0; index < received; index++) {
            char value = (char)rx_buffer[index];

            if (value == '@') {
                receiving_frame = true;
                frame_length = 0;
                frame[frame_length++] = value;
                continue;
            }

            if (!receiving_frame) {
                continue;
            }

            if (value == '\r') {
                continue;
            }

            if (value == '\n') {
                frame[frame_length] = '\0';
                if (strncmp(frame, "@CMD,", 5U) == 0) {
                    motor_command_t command;
                    result = parse_command_frame(frame, &command);

                    if (result == ESP_OK) {
                        result = motor_task_submit_command(&command);

                        if (result == ESP_OK) {
                            send_ack(command.sequence);
                        } else {
                            send_error_ack(command.sequence);
                            ESP_LOGW(
                                TAG,
                                "Command %lu rejected: %s",
                                (unsigned long)command.sequence,
                                esp_err_to_name(result)
                            );
                        }
                    } else {
                        ESP_LOGW(TAG, "Invalid drive frame: %s",
                                 esp_err_to_name(result));
                    }
                } else if (strncmp(frame, "@LIFT,", 6U) == 0) {
                    lift_command_t command = {0};
                    result = parse_lift_frame(frame, &command);
                    bool parsed = result == ESP_OK;

                    if (parsed) {
                        result = apply_lift_command(&command);
                    }

                    if (result == ESP_OK) {
                        stepper_motor_status_t status;
                        result = stepper_motor_get_status(&status);

                        if (result == ESP_OK) {
                            send_ack(command.sequence);
                            send_lift_status(
                                command.sequence,
                                status.busy ? "RUNNING" : "DONE",
                                &status
                            );
                            lift_command_pending = status.busy;
                            lift_sequence = command.sequence;
                            lift_action = command.action;
                            lift_backoff_waiting = false;
                            lift_requires_home =
                                command.action == LIFT_ACTION_HOME ||
                                command.action == LIFT_ACTION_INITIALIZE;

                            /*
                             * HOME can complete synchronously when the fork
                             * is already pressing the lower switch. Keep it
                             * pending so the normal backoff still releases
                             * the switch before DONE is reported.
                             */
                            if (lift_requires_home &&
                                status.lower_limit_active) {
                                lift_command_pending = true;
                            }
                        }
                    }

                    if (result != ESP_OK) {
                        if (parsed) {
                            send_error_ack(command.sequence);
                        }
                        ESP_LOGW(TAG, "Lift command rejected: %s",
                                 esp_err_to_name(result));
                    }
                } else {
                    ESP_LOGW(TAG, "Unknown UART frame type");
                }

                receiving_frame = false;
                frame_length = 0;
                continue;
            }

            if (frame_length >= sizeof(frame) - 1U) {
                ESP_LOGW(TAG, "UART frame exceeded maximum length");
                receiving_frame = false;
                frame_length = 0;
                continue;
            }

            frame[frame_length++] = value;
        }

        if (lift_command_pending && lift_backoff_waiting &&
            xTaskGetTickCount() >= lift_backoff_deadline) {
            result = stepper_motor_move_steps(
                STEPPER_MOTOR_DIRECTION_EXTEND,
                STEPPER_MOTOR_HOME_BACKOFF_STEPS,
                STEPPER_MOTOR_HOME_BACKOFF_RATE_SPS,
                STEPPER_MOTOR_HOME_BACKOFF_ACCEL_SPS2
            );

            if (result == ESP_OK) {
                lift_backoff_waiting = false;
                lift_action = LIFT_ACTION_UP;
            } else {
                stepper_motor_status_t status;

                if (stepper_motor_get_status(&status) == ESP_OK) {
                    send_lift_status(lift_sequence, "ERROR", &status);
                }
                lift_command_pending = false;
                lift_backoff_waiting = false;
                lift_requires_home = false;
                ESP_LOGE(TAG, "Lift lower-limit backoff failed: %s",
                         esp_err_to_name(result));
            }
        }

        if (lift_command_pending && !lift_backoff_waiting) {
            stepper_motor_status_t status;
            result = stepper_motor_get_status(&status);

            if (result != ESP_OK) {
                ESP_LOGE(TAG, "Failed to read lift status: %s",
                         esp_err_to_name(result));
                lift_command_pending = false;
            } else if (!status.busy) {
                if ((lift_action == LIFT_ACTION_DOWN ||
                     lift_requires_home) && status.lower_limit_active) {
                    lift_backoff_waiting = true;
                    /* The home switch was reached; after backoff this is a
                     * normal completion, not another homing result check. */
                    lift_requires_home = false;
                    lift_backoff_deadline =
                        xTaskGetTickCount() +
                        pdMS_TO_TICKS(
                            STEPPER_MOTOR_HOME_BACKOFF_DELAY_MS
                        );
                    send_lift_status(
                        lift_sequence,
                        "RUNNING",
                        &status
                    );
                } else if (lift_requires_home) {
                    send_lift_status(lift_sequence, "ERROR", &status);
                    lift_command_pending = false;
                    lift_requires_home = false;
                    ESP_LOGE(TAG,
                             "Homing failed before lower limit was reached");
                } else {
                    send_lift_status(lift_sequence, "DONE", &status);
                    lift_command_pending = false;
                }
            }
        }
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
