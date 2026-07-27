#include "task_comm.h"

#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "config.h"
#include "task_motor.h"

#include "driver/uart.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_err.h"
#include "esp_log.h"

static const char *TAG = "COMM_TASK";

static uint16_t crc16_ccitt_false(
    const uint8_t *data,
    size_t length
)
{
    uint16_t crc = 0xFFFFU;

    for (size_t index = 0; index < length; index++) {
        crc ^= (uint16_t)data[index] << 8;

        for (uint8_t bit = 0; bit < 8; bit++) {
            crc = (crc & 0x8000U) != 0U
                ? (uint16_t)((crc << 1) ^ 0x1021U)
                : (uint16_t)(crc << 1);
        }
    }

    return crc;
}

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

static void send_ack(uint32_t sequence)
{
    char body[40];
    int body_length = snprintf(
        body,
        sizeof(body),
        "ACK,%lu,OK",
        (unsigned long)sequence
    );

    if (body_length <= 0 || (size_t)body_length >= sizeof(body)) {
        return;
    }

    uint16_t crc = crc16_ccitt_false(
        (const uint8_t *)body,
        (size_t)body_length
    );
    char frame[56];
    int frame_length = snprintf(
        frame,
        sizeof(frame),
        "@%s*%04X\n",
        body,
        crc
    );

    if (frame_length > 0 && (size_t)frame_length < sizeof(frame)) {
        uart_write_bytes(
            JETSON_UART_PORT,
            frame,
            (size_t)frame_length
        );
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
                motor_command_t command;
                result = parse_command_frame(frame, &command);

                if (result == ESP_OK) {
                    result = motor_task_submit_command(&command);

                    if (result == ESP_OK) {
                        send_ack(command.sequence);
                    } else {
                        ESP_LOGW(TAG, "Command %lu rejected: %s",
                                 (unsigned long)command.sequence,
                                 esp_err_to_name(result));
                    }
                } else {
                    ESP_LOGW(TAG, "Invalid UART frame: %s",
                             esp_err_to_name(result));
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
