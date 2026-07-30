#ifndef FRAME_CODEC_H
#define FRAME_CODEC_H

#include <stddef.h>
#include <stdint.h>

/*
 * ASCII line framing shared by the Jetson command link (UART1) and the
 * sensor telemetry link (USB CDC): "@<body>*<CRC16>\n".
 * The CRC covers <body> only and is CRC-16/CCITT-FALSE.
 */

uint16_t crc16_ccitt_false(const uint8_t *data, size_t length);

/*
 * Wrap body into a complete frame. Returns the number of bytes written,
 * or 0 when the output buffer is too small.
 */
size_t frame_codec_wrap(const char *body, char *out, size_t out_size);

#endif
