#!/usr/bin/env bash
set -euo pipefail

WORKSPACE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="/tmp/fk01_field_lap.pid"
LOG_FILE="/tmp/fk01_field_lap.log"
CSV_FILE="/tmp/fk01_slam_route.csv"

usage() {
  echo "usage: $0 start <origin_x_m> <origin_y_m> <origin_yaw_rad>"
  echo "       $0 status|log|stop|csv"
}

read_pid() {
  if [[ ! -f "$PID_FILE" ]]; then
    return 1
  fi
  local value
  value="$(<"$PID_FILE")"
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
    return 1
  fi
  printf '%s' "$value"
}

is_running() {
  local process_id
  process_id="$(read_pid)" || return 1
  kill -0 "$process_id" 2>/dev/null
}

validate_number() {
  [[ "$1" =~ ^-?([0-9]+([.][0-9]*)?|[.][0-9]+)$ ]]
}

source_ros() {
  # ROS Humble's generated setup scripts read optional variables before they
  # are defined, so they are incompatible with `set -u` while being sourced.
  set +u
  # shellcheck disable=SC1091
  source /opt/ros/humble/setup.bash
  # shellcheck disable=SC1091
  source "$WORKSPACE_DIR/install/setup.bash"
  set -u
}

command="${1:-}"
case "$command" in
  start)
    if [[ $# -ne 4 ]]; then
      usage
      exit 2
    fi
    if ! validate_number "$2" || ! validate_number "$3" || ! validate_number "$4"; then
      echo "origin values must be plain decimal numbers" >&2
      exit 2
    fi
    if is_running; then
      echo "field lap is already running (pid $(read_pid))"
      exit 1
    fi
    if [[ ! -f "$WORKSPACE_DIR/install/setup.bash" ]]; then
      echo "workspace is not built: $WORKSPACE_DIR/install/setup.bash" >&2
      exit 1
    fi

    # Keep the physical vehicle isolated from the simulator even when the SSH
    # login environment forgot to export the documented domain.
    export ROS_DOMAIN_ID="${ROS_DOMAIN_ID:-100}"
    source_ros

    # Preserve the previous run, but make it impossible to mistake stale
    # coordinates for samples from a launch that failed before telemetry came up.
    if [[ -f "$CSV_FILE" ]]; then
      cp -p -- "$CSV_FILE" "${CSV_FILE}.previous"
    fi
    : >"$CSV_FILE"

    nohup setsid ros2 launch forklift_teleop field_slam_nav2.launch.py \
      nav2_params_file:="$WORKSPACE_DIR/nav2_params.yaml" \
      drive_enabled:=true \
      auto_lap_enabled:=true \
      mqtt_enabled:=false \
      origin_configured:=true \
      origin_x_m:="$2" \
      origin_y_m:="$3" \
      origin_yaw_rad:="$4" \
      trajectory_csv:="$CSV_FILE" \
      >"$LOG_FILE" 2>&1 </dev/null &
    process_id=$!
    printf '%s\n' "$process_id" >"$PID_FILE"
    disown "$process_id" 2>/dev/null || true
    sleep 2
    if ! kill -0 "$process_id" 2>/dev/null; then
      echo "field lap exited during startup; log follows:" >&2
      sed -n '1,160p' "$LOG_FILE" >&2
      exit 1
    fi
    echo "field lap started: pid=$process_id domain=$ROS_DOMAIN_ID"
    echo "log: $LOG_FILE"
    echo "csv: $CSV_FILE"
    ;;

  status)
    if is_running; then
      process_id="$(read_pid)"
      echo "RUNNING pid=$process_id"
      export ROS_DOMAIN_ID="${ROS_DOMAIN_ID:-100}"
      source_ros
      timeout 5 ros2 topic echo --once /field_lap/status 2>/dev/null \
        || echo "lap status topic not ready; inspect '$LOG_FILE'"
    else
      echo "NOT RUNNING"
      exit 1
    fi
    ;;

  log)
    touch "$LOG_FILE"
    # Poll by line number. This avoids consuming another inotify watch on the
    # Jetson, where camera/ROS tooling can exhaust the per-user watch quota.
    total_lines="$(wc -l <"$LOG_FILE")"
    next_line=$(( total_lines > 100 ? total_lines - 99 : 1 ))
    while true; do
      total_lines="$(wc -l <"$LOG_FILE")"
      if (( total_lines >= next_line )); then
        sed -n "${next_line},${total_lines}p" "$LOG_FILE"
        next_line=$((total_lines + 1))
      fi
      sleep 1
    done
    ;;

  csv)
    if [[ ! -f "$CSV_FILE" ]]; then
      echo "CSV has not been created yet: $CSV_FILE" >&2
      exit 1
    fi
    exec tail -n 20 "$CSV_FILE"
    ;;

  stop)
    process_id="$(read_pid)" || {
      echo "NOT RUNNING"
      exit 1
    }
    if ! kill -0 "$process_id" 2>/dev/null; then
      echo "NOT RUNNING (stale pid file)"
      exit 1
    fi
    process_command="$(tr '\0' ' ' <"/proc/$process_id/cmdline")"
    session_id="$(ps -o sid= -p "$process_id" | tr -d ' ')"
    if [[ "$process_command" != *"field_slam_nav2.launch.py"* ]] \
      || [[ "$session_id" != "$process_id" ]]; then
      echo "refusing to signal unexpected process pid=$process_id" >&2
      exit 1
    fi
    kill -INT -- "-$process_id"
    echo "stop requested for field lap process group $process_id"
    ;;

  *)
    usage
    exit 2
    ;;
esac
