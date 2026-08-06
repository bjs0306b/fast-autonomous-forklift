#ifndef TASK_ENCODER_H
#define TASK_ENCODER_H

#include "esp_err.h"

/*
 * Samples the wheel encoder at a fixed rate and submits it to telemetry.
 *
 * A fixed rate matters: the host turns count differences into a velocity by
 * dividing by the elapsed time, and an irregular period puts that jitter
 * straight into the velocity it hands the EKF.
 */
esp_err_t encoder_task_start(void);

#endif
