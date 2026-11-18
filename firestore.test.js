const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_conductor_1";
const BOB_UID = "bob_conductor_2";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validPresetPayload(uid, overrides = {}) {
  const past = new Date(Date.now() - 5000);
  return {
    userId: uid,
    title: "Holst First Suite - Chaconne",
    notes: "Tune Eb major triad in Pure mode",
    bpm: 108,
    timeSignature: "THREE_FOUR",
    metronomeSound: "WOODBLOCK",
    accentLevel: 1.0,
    quarterLevel: 0.85,
    eighthLevel: 0.25,
    tripletLevel: 0.0,
    sixteenthLevel: 0.0,
    alternateMuteBar: false,
    referencePitchHz: 442.0,
    temperament: "PURE",
    rootKeyIndex: 3,
    autoDetectRoot: false,
    transposition: "BB",
    voice: "ORGAN",
    createdAt: past,
    updatedAt: past,
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

test("Unauthenticated user cannot list, read, or write rehearsal_presets", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/rehearsal_presets`).get());
  await assertFails(
    unauthDb
      .collection(`users/${ALICE_UID}/rehearsal_presets`)
      .doc("preset_1")
      .set(validPresetPayload(ALICE_UID))
  );
});

test("Owner can list empty collection, create, partial update, and delete preset", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const col = aliceDb.collection(`users/${ALICE_UID}/rehearsal_presets`);

  // Empty list query must succeed
  await assertSucceeds(col.get());

  // Valid create matching Kotlin payload (no @DocumentId `id` field)
  await assertSucceeds(col.doc("preset_holst").set(validPresetPayload(ALICE_UID)));

  // Valid partial update preserving createdAt and userId
  await assertSucceeds(
    col.doc("preset_holst").update({
      bpm: 112,
      temperament: "EQUAL",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // List non-empty collection
  await assertSucceeds(col.orderBy("updatedAt", "desc").get());

  // Valid delete
  await assertSucceeds(col.doc("preset_holst").delete());
});

test("Cross-user isolation blocks Bob from reading, updating, or deleting Alice's presets", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context
      .firestore()
      .collection(`users/${ALICE_UID}/rehearsal_presets`)
      .doc("alice_preset")
      .set(validPresetPayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceCol = bobDb.collection(`users/${ALICE_UID}/rehearsal_presets`);

  await assertFails(aliceCol.get());
  await assertFails(aliceCol.doc("alice_preset").get());
  await assertFails(aliceCol.doc("alice_preset").update({ bpm: 90, updatedAt: new Date(Date.now() - 1000) }));
  await assertFails(aliceCol.doc("alice_preset").delete());
});

test("Security rules reject ghost fields, @DocumentId `id` field, out-of-range values, and immutable tampering", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const col = aliceDb.collection(`users/${ALICE_UID}/rehearsal_presets`);

  // Reject ghost field
  await assertFails(
    col.doc("bad_ghost").set(validPresetPayload(ALICE_UID, { isVerified: true }))
  );

  // Reject accidental `@DocumentId` `id` inside payload
  await assertFails(
    col.doc("bad_id_field").set(validPresetPayload(ALICE_UID, { id: "bad_id_field" }))
  );

  // Reject out-of-range BPM or referencePitchHz
  await assertFails(col.doc("bad_bpm").set(validPresetPayload(ALICE_UID, { bpm: 350 })));
  await assertFails(col.doc("bad_pitch").set(validPresetPayload(ALICE_UID, { referencePitchHz: 460.0 })));

  // Reject future timestamp
  await assertFails(
    col.doc("bad_time").set(validPresetPayload(ALICE_UID, { createdAt: new Date(Date.now() + 60000) }))
  );

  // Create valid doc, then reject createdAt mutation on update
  await assertSucceeds(col.doc("valid_1").set(validPresetPayload(ALICE_UID)));
  await assertFails(
    col.doc("valid_1").update({
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});
