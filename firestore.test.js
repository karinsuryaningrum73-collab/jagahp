const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const CAMERA_DEVICE_ID = "dev_camera_9a8b7c6d5e4f";
const MONITOR_DEVICE_ID = "dev_monitor_1z2y3x4w5v6u";
const UNKNOWN_DEVICE_ID = "dev_unknown_000000000000";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validRoomPayload(ownerDeviceId, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    ownerDeviceId,
    viewerDeviceIds: [ownerDeviceId],
    cameraName: "Kamera Teras",
    status: "WAITING",
    cameraFacing: "BACK",
    flashEnabled: false,
    resolution: "480p",
    targetFps: 15,
    motionDetectionEnabled: true,
    motionDetected: false,
    blackScreenEnabled: true,
    batteryPercent: 84,
    batteryTempC: 33.5,
    charging: false,
    viewerRequestSeq: 0,
    offerSeq: 0,
    answerSeq: 0,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Listing rooms collection is strictly denied to prevent room enumeration", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  await assertFails(db.collection("rooms").get());
  await assertFails(db.collection("rooms").where("ownerDeviceId", "==", CAMERA_DEVICE_ID).get());
});

test("Camera device can create room with persistent pairing code and update telemetry", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  const roomRef = db.collection("rooms").doc("JG4K8M2P");

  await assertSucceeds(roomRef.set(validRoomPayload(CAMERA_DEVICE_ID)));
  await assertSucceeds(roomRef.get());

  await assertSucceeds(
    roomRef.update({
      batteryPercent: 14,
      batteryTempC: 36.2,
      status: "STREAMING",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Rejects invalid room codes, short device IDs, ghost fields, and out-of-bounds values", async () => {
  const db = testEnv.unauthenticatedContext().firestore();

  await assertFails(db.collection("rooms").doc("bad_code").set(validRoomPayload(CAMERA_DEVICE_ID)));
  await assertFails(db.collection("rooms").doc("JG4K8M2P").set(validRoomPayload("short")));
  await assertFails(
    db.collection("rooms").doc("JG4K8M2P").set(validRoomPayload(CAMERA_DEVICE_ID, { id: "JG4K8M2P" }))
  );
  await assertFails(
    db.collection("rooms").doc("JG4K8M2P").set(validRoomPayload(CAMERA_DEVICE_ID, { batteryPercent: 150 }))
  );
  await assertFails(
    db.collection("rooms").doc("JG4K8M2P").set(
      validRoomPayload(CAMERA_DEVICE_ID, { updatedAt: new Date(Date.now() + 60000) })
    )
  );
});

test("Monitor device can join via pairing code, send remote controls, and exchange ICE candidates", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  const roomRef = db.collection("rooms").doc("JG4K8M2P");

  await assertSucceeds(roomRef.set(validRoomPayload(CAMERA_DEVICE_ID)));

  // Monitor joins using room code
  await assertSucceeds(
    roomRef.update({
      viewerDeviceIds: [CAMERA_DEVICE_ID, MONITOR_DEVICE_ID],
      viewerRequestSeq: 1,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Monitor toggles flashlight and flips camera
  await assertSucceeds(
    roomRef.update({
      flashEnabled: true,
      cameraFacing: "FRONT",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Immutable fields cannot be overwritten
  await assertFails(
    roomRef.update({
      ownerDeviceId: UNKNOWN_DEVICE_ID,
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // ICE candidates exchange
  const candTime = new Date(Date.now() - 300);
  await assertSucceeds(
    roomRef.collection("callerCandidates").doc("cand_1").set({
      ownerDeviceId: CAMERA_DEVICE_ID,
      senderDeviceId: CAMERA_DEVICE_ID,
      sdpMid: "0",
      sdpMLineIndex: 0,
      candidate: "candidate:1 1 UDP 2122252543 192.168.1.10 50000 typ host",
      sessionSeq: 1,
      createdAt: candTime,
    })
  );

  await assertSucceeds(roomRef.collection("callerCandidates").get());

  await assertSucceeds(
    roomRef.collection("calleeCandidates").doc("cand_2").set({
      ownerDeviceId: CAMERA_DEVICE_ID,
      senderDeviceId: MONITOR_DEVICE_ID,
      sdpMid: "0",
      sdpMLineIndex: 0,
      candidate: "candidate:2 1 UDP 2122252543 192.168.1.20 50001 typ host",
      sessionSeq: 1,
      createdAt: candTime,
    })
  );

  // Unpaired device cannot push callee candidates
  await assertFails(
    roomRef.collection("calleeCandidates").doc("cand_bad").set({
      ownerDeviceId: CAMERA_DEVICE_ID,
      senderDeviceId: UNKNOWN_DEVICE_ID,
      sdpMid: "0",
      sdpMLineIndex: 0,
      candidate: "candidate:99",
      sessionSeq: 1,
      createdAt: candTime,
    })
  );
});

test("Motion events can only be created by room owner device", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  const roomRef = db.collection("rooms").doc("JG4K8M2P");

  await assertSucceeds(roomRef.set(validRoomPayload(CAMERA_DEVICE_ID)));

  const eventTime = new Date(Date.now() - 400);
  await assertSucceeds(
    roomRef.collection("motionEvents").doc("evt_1").set({
      ownerDeviceId: CAMERA_DEVICE_ID,
      intensity: 35.0,
      batteryPercent: 84,
      createdAt: eventTime,
    })
  );

  await assertFails(
    roomRef.collection("motionEvents").doc("evt_2").set({
      ownerDeviceId: MONITOR_DEVICE_ID,
      intensity: 90.0,
      batteryPercent: 84,
      createdAt: eventTime,
    })
  );

  await assertSucceeds(roomRef.collection("motionEvents").get());
});
