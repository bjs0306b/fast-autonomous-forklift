#ifndef TASK_TOF_H
#define TASK_TOF_H

#include "esp_err.h"

/*
 * Start front-obstacle ranging. Requires telemetry_task_start() first, since
 * frames are handed to the telemetry queue.
 *
 * As with the IMU, a failure here must not be fatal: driving and the fork have
 * to keep working without the ToF pair.
 */
esp_err_t tof_task_start(void);

#endif
