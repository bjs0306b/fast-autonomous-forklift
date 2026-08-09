import assert from "node:assert/strict"
import test from "node:test"

import { streamKind } from "./streamKind.ts"

test("우리 송출 서버 주소는 MJPEG 으로 본다", () => {
  assert.equal(streamKind("http://70.12.247.121:8879/stream"), "mjpeg")
  assert.equal(streamKind("http://192.168.0.5:8879/stream/"), "mjpeg")
  assert.equal(streamKind("http://cam.local/video_feed"), "mjpeg")
  assert.equal(streamKind("http://cam.local/live.mjpg"), "mjpeg")
})

test("재생 페이지는 page 로 본다", () => {
  assert.equal(streamKind("http://3.38.178.143:8889/station"), "page")
  assert.equal(streamKind("http://mediamtx.local:8889/cam/"), "page")
})

test("주소가 없으면 page — 임의로 MJPEG 이라고 하지 않는다", () => {
  assert.equal(streamKind(null), "page")
  assert.equal(streamKind(undefined), "page")
})

test("override 가 추정을 이긴다", () => {
  // WebRTC 페이지 경로가 우연히 /stream 인 배포를 코드 수정 없이 넘기기 위한 탈출구.
  assert.equal(streamKind("http://host/stream", "page"), "page")
  assert.equal(streamKind("http://host/whatever", "mjpeg"), "mjpeg")
  assert.equal(streamKind("http://host/whatever", "MJPEG"), "mjpeg")
})

test("모르는 override 값은 무시하고 추정으로 돌아간다", () => {
  assert.equal(streamKind("http://host/stream", "webrtc"), "mjpeg")
  assert.equal(streamKind("http://host/stream", ""), "mjpeg")
})

test("형식이 깨진 주소도 판단을 포기하지 않는다", () => {
  assert.equal(streamKind("//?/stream"), "mjpeg")
})
