idf_component_register(
    SRCS
        "main.c"
        "motor_controller.c"
        "servo.c"
        "pca9685.c"
        "dc_motor.c"
        "stepper_motor.c"
        "frame_codec.c"
        "mpu6500.c"
        "vl53l8cx_pair.c"
        "encoder.c"
        "task_motor.c"
        "task_comm.c"
        "task_imu.c"
        "task_tof.c"
        "task_encoder.c"
        "task_telemetry.c"
    INCLUDE_DIRS "."
    REQUIRES
        esp_driver_i2c
        esp_driver_gpio
        esp_driver_gptimer
        esp_driver_pcnt
        esp_driver_uart
        esp_driver_spi
        esp_driver_usb_serial_jtag
        esp_timer
        # usb_serial_jtag_vfs.h pulls in esp_vfs.h, which its own component
        # does not declare, so the dependency has to be named here.
        vfs
)
