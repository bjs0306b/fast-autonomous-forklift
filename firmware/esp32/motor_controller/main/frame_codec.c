#include "frame_codec.h"

#include <stdio.h>
#include <string.h>

uint16_t crc16_ccitt_false(
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

size_t frame_codec_wrap(
    const char *body,
    char *out,
    size_t out_size
)
{
    if (body == NULL || out == NULL || out_size == 0U) {
        return 0U;
    }

    size_t body_length = strlen(body);
    uint16_t crc = crc16_ccitt_false(
        (const uint8_t *)body,
        body_length
    );
    int written = snprintf(out, out_size, "@%s*%04X\n", body, crc);

    if (written <= 0 || (size_t)written >= out_size) {
        return 0U;
    }

    return (size_t)written;
}
