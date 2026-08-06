#ifndef ENCODER_H
#define ENCODER_H

#include <stdint.h>

#include "esp_err.h"

/*
 * Quadrature wheel encoder on the drive motor, decoded by the PCNT peripheral.
 *
 * The counting happens in hardware, so no interrupt fires per edge. At full
 * speed the two channels together produce a few thousand edges a second, which
 * is enough to matter if every one of them had to be serviced but costs
 * nothing here.
 *
 * The count is signed and free-running: forward travel raises it, reverse
 * lowers it, and it is never reset. Consumers difference successive readings,
 * which keeps a dropped frame from turning into a lost distance.
 */
esp_err_t encoder_init(void);

/* Accumulated edge count since boot. Safe to call from any task. */
esp_err_t encoder_read(int32_t *count);

#endif
