idf_component_register(
    SRCS
        "main.c"
        "motor_controller.c"
        "servo.c"
        "pca9685.c"
        "dc_motor.c"
        "linear_motor.c"
        "task_motor.c"
        "task_comm.c"
    INCLUDE_DIRS "."
    REQUIRES
        esp_driver_i2c
        esp_driver_gpio
        esp_driver_uart
)
