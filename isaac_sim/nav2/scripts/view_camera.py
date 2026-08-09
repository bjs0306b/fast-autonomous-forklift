"""차량 카메라 영상을 MQTT 로 받아 본다.

    fast/v1/vehicle/{id}/camera      JPEG 바이트 (base64 아님)
    fast/v1/vehicle/{id}/camera/info JSON 메타 (선택)

MQTT 는 원래 영상용이 아니다. 화면 확인·디버깅 정도로 쓰고, 관제 화면에
크게 띄울 거라면 별도 스트리밍(MediaMTX 등)을 쓰는 편이 낫다.
그래도 320x240 / 5 fps 면 30 KB x 5 = 150 KB/s 라 충분히 견딘다.

사용:
    source /home/ubuntu/forklift_ws/nav2/config/mqtt.env
    python3 view_camera.py                 # fk01 을 창으로 본다
    python3 view_camera.py sim02           # 다른 차량
    python3 view_camera.py fk01 --save /tmp/cam   # 파일로 저장 (창 없이)
    python3 view_camera.py fk01 --stats           # 수신 통계만

종료: 창에서 q 또는 Ctrl+C
"""
import argparse
import os
import ssl
import sys
import time

try:
    import paho.mqtt.client as mqtt
except ImportError:
    raise SystemExit("paho-mqtt 가 없습니다:  pip3 install paho-mqtt")

HOST = os.environ.get("MQTT_HOST", "i15a304.p.ssafy.io")
PORT = int(os.environ.get("MQTT_PORT", 8883))
USER = os.environ.get("MQTT_USER")
PASS = os.environ.get("MQTT_PASS")
CA = os.environ.get("MQTT_CA")

BASE = "fast/v1/vehicle"


class Viewer:
    def __init__(self, vid, save_dir=None, show=True):
        self.vid = vid
        self.save_dir = save_dir
        self.show = show
        self.n = 0
        self.bytes = 0
        self.t0 = time.time()
        self.last = None

        self.cli = mqtt.Client(client_id=f"cam-view-{os.getpid()}")
        if USER:
            self.cli.username_pw_set(USER, PASS)
        if CA:
            self.cli.tls_set(ca_certs=CA)
        else:
            self.cli.tls_set(cert_reqs=ssl.CERT_NONE)
        self.cli.tls_insecure_set(True)          # 인증서 CN 이 IP 라서
        self.cli.on_connect = self._on_connect
        self.cli.on_message = self._on_message
        self.cli.connect(HOST, PORT, keepalive=30)
        self.cli.loop_start()

    def _on_connect(self, c, u, f, rc):
        if rc != 0:
            print(f"연결 실패 rc={rc} (4=아이디/비번 틀림)")
            return
        t = f"{BASE}/{self.vid}/camera"
        c.subscribe(t, qos=0)
        c.subscribe(t + "/info", qos=0)
        print(f"연결됨 {HOST}:{PORT}")
        print(f"구독: {t}")

    def _on_message(self, c, u, msg):
        if msg.topic.endswith("/info"):
            print(f"[info] {msg.payload.decode(errors='replace')[:120]}")
            return
        self.n += 1
        self.bytes += len(msg.payload)
        self.last = msg.payload
        if self.save_dir:
            path = os.path.join(self.save_dir, f"{self.vid}_{self.n:05d}.jpg")
            with open(path, "wb") as f:
                f.write(msg.payload)

    def stats(self):
        el = max(time.time() - self.t0, 1e-6)
        return (f"{self.n}장  {self.n/el:.1f} fps  "
                f"{self.bytes/el/1024:.0f} KB/s  "
                f"평균 {self.bytes/max(self.n,1)/1024:.0f} KB/장")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("vehicle", nargs="?", default="fk01",
                    help="fk01 | sim02 | sim03")
    ap.add_argument("--save", help="이 폴더에 JPEG 로 저장 (창 없이)")
    ap.add_argument("--stats", action="store_true", help="통계만 출력")
    a = ap.parse_args()

    if not USER:
        print("경고: MQTT_USER 없음. mqtt.env 를 source 하세요.")
    if a.save:
        os.makedirs(a.save, exist_ok=True)

    show = not (a.save or a.stats)
    cv2 = None
    if show:
        try:
            import cv2 as _cv2
            import numpy as np
            cv2 = _cv2
        except ImportError:
            print("opencv 가 없어 창을 못 엽니다. --save 나 --stats 를 쓰세요.")
            show = False

    v = Viewer(a.vehicle, a.save, show)
    print(f"{a.vehicle} 카메라 대기 중...  (Ctrl+C 로 종료)")
    try:
        last_log = 0.0
        while True:
            if show and v.last is not None:
                import numpy as np
                arr = np.frombuffer(v.last, np.uint8)
                img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                if img is not None:
                    cv2.imshow(f"{a.vehicle} camera", img)
                    if cv2.waitKey(30) & 0xFF == ord("q"):
                        break
                else:
                    time.sleep(0.05)
            else:
                time.sleep(0.2)
            if time.time() - last_log > 5.0:
                last_log = time.time()
                print("  " + v.stats())
    except KeyboardInterrupt:
        print("\n중단됨")
    finally:
        if v.n == 0:
            print("\n영상을 한 장도 못 받았습니다.")
            print(f"  - 차량이 {BASE}/{a.vehicle}/camera 로 발행 중인지")
            print("  - 아직 구현 전이라면 C팀에 docs/orin-camera-spec.md 전달")
        else:
            print(v.stats())
        v.cli.loop_stop()
        v.cli.disconnect()
        if show and cv2:
            cv2.destroyAllWindows()
    return 0


if __name__ == "__main__":
    sys.exit(main())
