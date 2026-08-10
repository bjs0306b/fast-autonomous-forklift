#!/usr/bin/env bash
# fastdds_remote.xml 의 interfaceWhiteList 를 **지금** 이 기기의 랜 주소로 맞춘다.
#
# ⚠️ 화이트리스트에 이 기기에 없는 주소가 적혀 있으면 그 트랜스포트가 죽어서
#    **노드끼리도 못 붙는다.** 증상은 "토픽이 /rosout 과 /parameter_events
#    둘만 보인다" 인데, 노드는 멀쩡히 떠 있어서 원인을 찾기 어렵다.
#    2026-08-10 에 두 번 겪었다 -- 오린이 AP 를 옮길 때마다 재현된다.
#
# 스택을 올리기 **전에** 실행할 것. colcon build 로 install 에 반영된다.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import pathlib, re, subprocess
out = subprocess.run(["ip","-4","-o","addr","show"], capture_output=True, text=True).stdout
addr = None
for line in out.splitlines():
    parts = line.split()
    iface, cidr = parts[1], parts[3].split("/")[0]
    if iface.startswith(("lo", "docker", "l4tbr", "tailscale")):
        continue
    addr = cidr
    break
if not addr:
    raise SystemExit("랜 주소를 못 찾았다 -- ip -4 addr show 로 확인할 것")
p = pathlib.Path("src/forklift_teleop/config/fastdds_remote.xml")
t = p.read_text(encoding="utf-8")
# ⚠️ 파일 맨 위 주석에 **노트북용 예시 프로파일**이 들어 있고 거기에도
#    <address> 가 있다. 단순히 첫 일치를 바꾸면 주석만 고치고 실제
#    화이트리스트는 그대로 남는다 -- 로컬은 127.0.0.1 로 돌아가서 눈치채기
#    어렵고, 원격만 조용히 죽는다. 2026-08-10 에 실제로 그렇게 당했다.
#    그래서 interfaceWhiteList 블록 안에서만 찾는다.
block = re.search(r"<interfaceWhiteList>.*?</interfaceWhiteList>", t, re.S)
if not block:
    raise SystemExit("interfaceWhiteList 를 못 찾았다")
found = re.search(r"<address>(\d+\.\d+\.\d+\.\d+)</address>", block.group(0))
if found.group(1) == addr:
    print(f"이미 {addr} -- 바꿀 것 없음")
else:
    fixed = block.group(0).replace(found.group(0), f"<address>{addr}</address>", 1)
    p.write_text(t[:block.start()] + fixed + t[block.end():], encoding="utf-8")
    print(f"{found.group(1)} -> {addr}  (colcon build 필요)")
PY
