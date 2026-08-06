#include "encoder.h"

#include "driver/pulse_cnt.h"
#include "esp_log.h"

#include "config.h"

static const char *TAG = "ENCODER";

static pcnt_unit_handle_t s_unit = NULL;

esp_err_t encoder_init(void)
{
    if (s_unit != NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    /*
     * The hardware counter is 16-bit. Watch points at both limits let the
     * driver fold each wrap into a 32-bit accumulator, so the value read back
     * keeps counting past the wrap instead of jumping by 32768.
     */
    pcnt_unit_config_t unit_config = {
        .high_limit = ENCODER_PCNT_LIMIT,
        .low_limit = -ENCODER_PCNT_LIMIT,
        .flags.accum_count = true,
    };
    esp_err_t result = pcnt_new_unit(&unit_config, &s_unit);

    if (result != ESP_OK) {
        ESP_LOGE(TAG, "pcnt_new_unit failed: %s", esp_err_to_name(result));
        return result;
    }

    pcnt_glitch_filter_config_t filter_config = {
        .max_glitch_ns = ENCODER_GLITCH_FILTER_NS,
    };
    result = pcnt_unit_set_glitch_filter(s_unit, &filter_config);

    if (result != ESP_OK) {
        goto fail;
    }

    /*
     * Both channels are counted on both edges, each gated by the other
     * channel's level. That is the full 4x decode: every transition on either
     * line moves the count, and the direction comes from which line led.
     */
    pcnt_chan_config_t channel_a_config = {
        .edge_gpio_num = ENCODER_A_GPIO,
        .level_gpio_num = ENCODER_B_GPIO,
    };
    pcnt_chan_config_t channel_b_config = {
        .edge_gpio_num = ENCODER_B_GPIO,
        .level_gpio_num = ENCODER_A_GPIO,
    };
    pcnt_channel_handle_t channel_a = NULL;
    pcnt_channel_handle_t channel_b = NULL;

    result = pcnt_new_channel(s_unit, &channel_a_config, &channel_a);

    if (result != ESP_OK) {
        goto fail;
    }

    result = pcnt_new_channel(s_unit, &channel_b_config, &channel_b);

    if (result != ESP_OK) {
        goto fail;
    }

    ESP_ERROR_CHECK(pcnt_channel_set_edge_action(
        channel_a,
        PCNT_CHANNEL_EDGE_ACTION_DECREASE,
        PCNT_CHANNEL_EDGE_ACTION_INCREASE));
    ESP_ERROR_CHECK(pcnt_channel_set_level_action(
        channel_a,
        PCNT_CHANNEL_LEVEL_ACTION_KEEP,
        PCNT_CHANNEL_LEVEL_ACTION_INVERSE));
    ESP_ERROR_CHECK(pcnt_channel_set_edge_action(
        channel_b,
        PCNT_CHANNEL_EDGE_ACTION_INCREASE,
        PCNT_CHANNEL_EDGE_ACTION_DECREASE));
    ESP_ERROR_CHECK(pcnt_channel_set_level_action(
        channel_b,
        PCNT_CHANNEL_LEVEL_ACTION_KEEP,
        PCNT_CHANNEL_LEVEL_ACTION_INVERSE));

    ESP_ERROR_CHECK(pcnt_unit_add_watch_point(s_unit, ENCODER_PCNT_LIMIT));
    ESP_ERROR_CHECK(pcnt_unit_add_watch_point(s_unit, -ENCODER_PCNT_LIMIT));

    result = pcnt_unit_enable(s_unit);

    if (result != ESP_OK) {
        goto fail;
    }

    ESP_ERROR_CHECK(pcnt_unit_clear_count(s_unit));
    ESP_ERROR_CHECK(pcnt_unit_start(s_unit));

    ESP_LOGI(TAG, "Quadrature encoder on A=GPIO%d B=GPIO%d, 4x decode",
             ENCODER_A_GPIO, ENCODER_B_GPIO);
    return ESP_OK;

fail:
    ESP_LOGE(TAG, "encoder init failed: %s", esp_err_to_name(result));
    pcnt_del_unit(s_unit);
    s_unit = NULL;
    return result;
}

esp_err_t encoder_read(int32_t *count)
{
    if (count == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    if (s_unit == NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    int value = 0;
    esp_err_t result = pcnt_unit_get_count(s_unit, &value);

    if (result == ESP_OK) {
        *count = (int32_t)value;
    }

    return result;
}
