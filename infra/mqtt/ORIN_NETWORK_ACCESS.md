# Orin에서 GPU 서버 Mosquitto에 접속하기 — 네트워크·보안 안내

> # 🛑 전제가 폐기됐다 (2026-08-03)
>
> **GPU 서버(`70.12.130.106`)의 Mosquitto 는 내려갔다** — 같은 날 실측에서 프로세스 0개
> (서버 uptime 22주라 재부팅 탓이 아니다). GPU 서버는 **학습 전용**이 됐고, 그 위에서
> 돌던 Isaac Sim·Nav2 도 함께 내려갔다.
>
> **MQTT 브로커는 EC2 로 일원화됐다** — `i15a304.p.ssafy.io:8883`, **TLS + 계정 인증**
> (`allow_anonymous false`). 백엔드·스테이션·ROS2 가 전부 여기를 본다.
> 평문 `1883` 은 EC2 에서 **아무것도 리스닝하지 않는다.**
>
> 즉 아래 §2~§4 의 "GPU 서버 포트를 열어 Orin 에서 붙는다" 는 **적용 대상이 없다.**
> 옛 주소를 가리키는 설정이 남아 있으면 **발행이 조용히 허공으로 간다**(Jira 188·191).
>
> ## 그래도 남겨두는 이유
>
> §1(왜 `127.0.0.1` 바인딩인가)과 §5 이후의 보안 판단 — **"익명 허용 + 전체 노출"
> 조합을 만들지 않는다** — 은 브로커가 어디 있든 유효하다. 로컬 개발 브로커를 띄울 때
> 그대로 적용된다.
>
> *(폐기 표시: 2026-08-05, 문서 정합 점검. Jira S15P11A304-178)*

> **이 문서는 안내다. 이번 작업에서 `docker-compose.yml`·`mosquitto.conf`를 변경하지 않았다.**
> 아래 설정은 **팀 승인 후** 적용해야 한다.

---

## 1. 현재 localhost 바인딩인 이유

`infra/mqtt/docker-compose.yml`은 브로커 포트를 이렇게 노출한다.

```yaml
ports:
  - "127.0.0.1:1883:1883"
```

`"1883:1883"`이 아니라 **`127.0.0.1`을 앞에 붙인 것은 의도적**이다. 파일 주석에도 근거가 적혀 있다.

> `"1883:1883"` 으로 쓰면 개발 PC 의 모든 네트워크 인터페이스에 브로커가 노출되고,
> 익명 접속을 허용한 이 로컬 설정과 합쳐지면 같은 네트워크의 누구나 차량 명령 토픽에 발행할 수 있게 된다.

즉 **"익명 허용 + 전체 노출" 조합을 막기 위한 안전장치**다.

---

## 2. Orin에서 접속할 수 없는 이유

컨테이너 **안쪽** `mosquitto.conf`는 이미 모든 인터페이스를 듣고 있다.

```conf
listener 1883 0.0.0.0
allow_anonymous true
```

하지만 **Docker 포트 매핑이 그 앞에서 잘라낸다.** 호스트의 `127.0.0.1:1883`에만 바인딩되므로
호스트 자신에서 온 연결만 컨테이너로 전달된다.

| 시나리오 | 현재 설정 | 결과 |
|---|---|---|
| GPU 서버의 Spring Boot → 브로커 | 같은 호스트 | ✅ 접속됨 |
| GPU 서버의 Isaac Sim → 브로커 | 같은 호스트 | ✅ 접속됨 |
| **Orin(다른 장비) → 브로커** | 다른 호스트 | ❌ **Connection refused** |

`mosquitto.conf`만 보고 "0.0.0.0이니까 열려 있다"고 판단하면 안 된다. **막는 것은 Docker 쪽이다.**

---

## 3. 외부 바인딩 변경 예시 (미적용)

### 방법 A — 내부 IP에만 바인딩 (권장)

```yaml
ports:
  - "<GPU_INTERNAL_IP>:1883:1883"
```

특정 인터페이스에만 노출한다. 공인 IP가 함께 있는 서버라면 **내부망 인터페이스만** 지정해
외부 인터넷 노출을 구조적으로 차단할 수 있다.

### 방법 B — 전체 인터페이스 바인딩 (방화벽 필수)

```yaml
ports:
  - "1883:1883"
```

모든 인터페이스에 노출된다. **§4 방화벽 제한을 반드시 함께 적용**해야 한다.

> ⚠ Docker는 보통 `iptables`를 직접 조작하며, 이 때문에 **UFW 규칙을 우회할 수 있다.**
> 방법 B를 쓰면서 UFW만 믿으면 실제로는 열려 있을 수 있다.
> 그래서 **방법 A(내부 IP 바인딩)를 권장한다.**

---

## 4. 반드시 함께 적용해야 하는 방화벽 제한

Orin의 IP만 1883을 쓸 수 있게 좁힌다.

```bash
# Orin 한 대만 허용
sudo ufw allow from <ORIN_IP> to any port 1883 proto tcp

# 교육장 내부망 대역만 허용하는 경우 (예: /24)
sudo ufw allow from <내부망_대역>/24 to any port 1883 proto tcp

# 확인
sudo ufw status numbered
sudo ss -lntp | grep 1883
```

Orin 쪽에서 도달 여부 확인:

```bash
ping <GPU_IP>
nc -vz <GPU_IP> 1883
mosquitto_sub -h <GPU_IP> -p 1883 -t 'integration/test' -v
```

> 위 명령의 `integration/test`는 **비제어 토픽**이다. 검증에는 이런 토픽만 사용한다.

---

## 5. 인증 도입 전에는 "내부망 임시 테스트"로만

현재 브로커는 이 상태다.

```conf
allow_anonymous true      # 누구나 인증 없이 접속 가능
```

**이 상태로 외부에 공개하면 안 된다.** 다음 조건에서만 임시 허용한다.

- 교육장 내부망에 한정
- 방화벽으로 접근 IP를 제한
- 통합 테스트 기간 한정
- **시연·외부 노출 전에는 반드시 인증을 켠다**

인증을 켤 때(예시, 미적용):

```bash
# 컨테이너 안에서 비밀번호 파일 생성
docker exec -it fast-mosquitto mosquitto_passwd -c /mosquitto/config/passwd <USERNAME>
```

```conf
# mosquitto.conf
allow_anonymous false
password_file /mosquitto/config/passwd
```

`docker-compose.yml`에 이미 주석 처리된 마운트 줄이 있으니 그때 해제한다.

```yaml
# - ./config/passwd:/mosquitto/config/passwd:ro
```

인증을 켜면 **접속하는 모든 쪽에 자격증명을 넣어야 한다.**

| 대상 | 설정 |
|---|---|
| Spring Boot | `MQTT_USERNAME` / `MQTT_PASSWORD` |
| ROS2 브리지 | `mqtt_username` / `mqtt_password_env: MQTT_PASSWORD` |
| Isaac Sim | Python MQTT 클라이언트 `username_pw_set()` |

---

## 6. `forklift/+/command` 토픽의 위험성

이 토픽은 **차량을 실제로 움직이거나 멈추는 명령**이 오간다.

```
forklift/{vehicleId}/command
```

여기에는 `MOVE`, `STOP`, `FORK_UP/DOWN`, `LOAD/UNLOAD`, **`EMERGENCY_STOP`**, `RESET_ESTOP`이 실린다.

익명 접속이 허용된 브로커가 네트워크에 열려 있으면:

- 누구나 임의 차량에 **이동 명령**을 보낼 수 있다
- 누구나 **비상정지를 임의로 발동**할 수 있다 — 현재 화면에서는 **해제 수단이 없다**(RESET_ESTOP 미구현)
- 누구나 가짜 `status`/`location`을 발행해 **관제 화면을 속일 수 있다**

마지막 항목은 특히 위험하다. 관제 화면은 브로커에서 온 상태를 신뢰하므로,
위조된 상태가 들어오면 조작자가 잘못된 판단을 하게 된다.

---

## 7. 변경 전 확인 목록

- [ ] 팀 승인 (네트워크·보안 담당 포함)
- [ ] 노출 범위 결정 — 내부 IP 바인딩(A) vs 전체 바인딩+방화벽(B)
- [ ] Orin IP 또는 허용 대역 확정
- [ ] 방화벽 규칙 적용 및 `ufw status`로 확인
- [ ] Docker의 iptables 우회 가능성 점검 (방법 B를 택한 경우)
- [ ] 시연 일정 기준으로 인증(`allow_anonymous false`) 도입 시점 결정
- [ ] 변경 후 `mosquitto_sub`로 **Orin에서** 접속 확인
- [ ] 제어 토픽(`forklift/+/command`)에는 검증 메시지를 발행하지 않기

---

## 8. 이번 작업에서 하지 않은 것

```
docker-compose.yml 포트 바인딩 변경 : 없음
mosquitto.conf 변경                : 없음
방화벽 규칙 변경                   : 없음
인증(passwd) 파일 생성             : 없음
Mosquitto 실행/중지                : 없음
MQTT 메시지 발행                   : 없음
```

실제 설정 변경은 팀 승인 후 별도로 진행한다.
