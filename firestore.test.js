const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const VALID_SHA256 = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastTimestamp(offsetMs = 2000) {
  return new Date(Date.now() - offsetMs);
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

test("Unauthenticated user cannot read or write any user profile, trips, bookings, wallet, email, browser, or AI messages", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("trips").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("bookings").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("saved_items").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("ai_messages").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("wallet_accounts").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("wallet_transactions").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("email_connections").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("email_items").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("browser_tabs").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("browser_records").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("permission_policies").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("security_audit").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("ai_preferences").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("ai_failover_logs").get());
  await assertFails(unauthDb.collection("ai_model_configs").get());
});

test("Authenticated owner can create, update, and read their own profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const createdAt = pastTimestamp(5000);
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(
    profileRef.set({
      userId: ALICE_UID,
      displayName: "Alice Traveler",
      preferredCurrency: "USD",
      temperatureUnit: "METRIC",
      preferredCabinClass: "BUSINESS",
      homeAirportIata: "SFO",
      biometricThresholdUsd: 500,
      darkGlassTheme: true,
      createdAt,
      updatedAt: createdAt,
    })
  );
  await assertSucceeds(profileRef.get());
  await assertSucceeds(
    profileRef.update({
      displayName: "Alice Updated",
      updatedAt: pastTimestamp(1000),
    })
  );
});

test("Authenticated owner can create trips, manage itinerary slots, and reject orphan slots", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const createdAt = pastTimestamp(5000);

  const orphanSlotRef = aliceDb
    .collection("users").doc(ALICE_UID)
    .collection("trips").doc("missing_trip")
    .collection("itinerary_slots").doc("slot_orphan");
  await assertFails(
    orphanSlotRef.set({
      userId: ALICE_UID,
      tripId: "missing_trip",
      dayNumber: 1,
      timeRange: "09:00 AM",
      title: "Meiji Jingu",
      location: "Shibuya",
      category: "Sights",
      durationMinutes: 90,
      routeSequence: 1,
      notes: "",
      estimatedCostUsd: 0,
      calSyncStatus: "Cloud Synced",
      createdAt,
      updatedAt: createdAt,
    })
  );

  const tripRef = aliceDb.collection("users").doc(ALICE_UID).collection("trips").doc("trip_1");
  await assertSucceeds(
    tripRef.set({
      userId: ALICE_UID,
      title: "Tokyo Serenity",
      destination: "Tokyo, Japan",
      startDate: "2026-12-10",
      endDate: "2026-12-16",
      travellersCount: 2,
      budgetUsd: 4200,
      style: "Luxury & Wellness",
      status: "PLANNED",
      createdAt,
      updatedAt: createdAt,
    })
  );

  const slotRef = tripRef.collection("itinerary_slots").doc("slot_1");
  await assertSucceeds(
    slotRef.set({
      userId: ALICE_UID,
      tripId: "trip_1",
      dayNumber: 1,
      timeRange: "09:00 AM",
      title: "Meiji Jingu Shrine",
      location: "Shibuya, Tokyo",
      latitude: 35.6764,
      longitude: 139.6993,
      category: "Sights",
      durationMinutes: 90,
      routeSequence: 1,
      notes: "Arrive early",
      estimatedCostUsd: 0,
      calSyncStatus: "Cloud Synced",
      createdAt,
      updatedAt: createdAt,
    })
  );
  await assertSucceeds(
    slotRef.update({
      notes: "Confirmed early morning walk",
      updatedAt: pastTimestamp(1000),
    })
  );
  await assertSucceeds(slotRef.delete());

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("trips").doc("trip_1").collection("itinerary_slots").get()
  );
});

test("Client cannot create CONFIRMED booking or fabricate providerPnr, and terminal CANCELLED lock applies", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastTimestamp(5000);
  const bookingsCol = aliceDb.collection("users").doc(ALICE_UID).collection("bookings");

  await assertFails(
    bookingsCol.doc("bk_fake_confirm").set({
      userId: ALICE_UID,
      referenceCode: "ALR-999-FLT",
      bookingType: "FLIGHT",
      title: "SFO to HND",
      providerSource: "Amadeus Sample",
      providerPnr: null,
      originOrLocation: "SFO",
      totalAmountUsd: 920,
      status: "CONFIRMED",
      sha256AuditHash: VALID_SHA256,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    bookingsCol.doc("bk_fake_pnr").set({
      userId: ALICE_UID,
      referenceCode: "ALR-998-FLT",
      bookingType: "FLIGHT",
      title: "SFO to HND",
      providerSource: "Amadeus Sample",
      providerPnr: "FAKEPNR9",
      originOrLocation: "SFO",
      totalAmountUsd: 920,
      status: "HELD",
      sha256AuditHash: VALID_SHA256,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  const holdRef = bookingsCol.doc("bk_hold_1");
  await assertSucceeds(
    holdRef.set({
      userId: ALICE_UID,
      referenceCode: "ALR-123-CAR",
      bookingType: "CAR_RENTAL",
      title: "Porsche Taycan",
      providerSource: "Sample Catalog",
      providerPnr: null,
      originOrLocation: "Tokyo Haneda",
      totalAmountUsd: 645,
      status: "HELD",
      sha256AuditHash: VALID_SHA256,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(holdRef.update({ status: "CONFIRMED", updatedAt: pastTimestamp(1500) }));
  await assertSucceeds(holdRef.update({ status: "USER_APPROVED", updatedAt: pastTimestamp(1400) }));
  await assertSucceeds(holdRef.update({ status: "CANCELLED", updatedAt: pastTimestamp(1200) }));

  await assertFails(holdRef.update({ status: "HELD", updatedAt: pastTimestamp(1000) }));
  await assertFails(holdRef.delete());
});

test("AI messages allow owner create/read/delete and forbid update or cross-user access", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastTimestamp(3000);
  const msgRef = aliceDb.collection("users").doc(ALICE_UID).collection("ai_messages").doc("msg_1");

  await assertSucceeds(
    msgRef.set({
      userId: ALICE_UID,
      isUser: true,
      text: "Check Tokyo weather",
      toolTraceSummary: null,
      isError: false,
      createdAt: ts,
    })
  );
  await assertSucceeds(msgRef.get());
  await assertFails(msgRef.update({ text: "Tampered text" }));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("ai_messages").doc("msg_1").get());
  await assertSucceeds(msgRef.delete());

  const clientSubMsgRef = aliceDb.collection("users").doc(ALICE_UID).collection("ai_messages").doc("msg-u-sub_1b7a9985aa4d");
  await assertSucceeds(
    clientSubMsgRef.set({
      userId: ALICE_UID,
      isUser: true,
      text: "Hello",
      isError: false,
      createdAt: ts,
    })
  );
  await assertFails(
    clientSubMsgRef.set({
      userId: ALICE_UID,
      isUser: true,
      text: "Hello",
      isError: false,
      createdAt: pastTimestamp(1000),
    })
  );
  await assertSucceeds(clientSubMsgRef.delete());
});

test("Cross-user access to trips and bookings is rejected", async () => {
  const createdAt = pastTimestamp(5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(BOB_UID).collection("bookings").doc("bk_1").set({
      userId: BOB_UID,
      referenceCode: "ALR-999-FLT",
      bookingType: "FLIGHT",
      title: "SFO to NRT",
      providerSource: "Amadeus GDS",
      originOrLocation: "SFO",
      totalAmountUsd: 920,
      status: "REQUESTED",
      sha256AuditHash: VALID_SHA256,
      createdAt,
      updatedAt: createdAt,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("bookings").get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("bookings").doc("bk_1").get());
});

test("Shadow update with ghost field on UserProfile is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastTimestamp(5000);
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      preferredCurrency: "USD",
      temperatureUnit: "METRIC",
      preferredCabinClass: "ECONOMY",
      homeAirportIata: "SFO",
      biometricThresholdUsd: 250,
      darkGlassTheme: true,
      isAdmin: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("Autonomous tasks and workspace notes enforce strict owner access, schema validation, and terminal COMPLETED lock", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const ts = pastTimestamp(5000);

  const taskRef = aliceDb.collection("users").doc(ALICE_UID).collection("autonomous_tasks").doc("task_1");
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("autonomous_tasks").get());

  await assertSucceeds(
    taskRef.set({
      userId: ALICE_UID,
      title: "Research laptops under $1200",
      goal: "Research best laptops under $1200 and save comparison report",
      understandingSummary: "Compare laptop specs and produce ranked report",
      status: "RUNNING",
      totalSteps: 4,
      completedSteps: 1,
      checkpointJson: "{\"step\":1}",
      finalOutcome: "",
      reportMarkdown: "",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("autonomous_tasks").doc("task_1").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("autonomous_tasks").doc("task_1").update({
      status: "CANCELLED",
      updatedAt: pastTimestamp(2000),
    })
  );
  await assertFails(taskRef.update({ goal: "Tampered goal", updatedAt: pastTimestamp(2000) }));
  await assertSucceeds(
    taskRef.update({
      status: "COMPLETED",
      completedSteps: 4,
      finalOutcome: "Comparison report saved",
      reportMarkdown: "# Laptop Comparison\n...",
      updatedAt: pastTimestamp(1500),
    })
  );
  await assertFails(taskRef.update({ status: "RUNNING", updatedAt: pastTimestamp(1000) }));
  await assertSucceeds(taskRef.delete());

  const noteRef = aliceDb.collection("users").doc(ALICE_UID).collection("workspace_notes").doc("note_1");
  await assertSucceeds(
    noteRef.set({
      userId: ALICE_UID,
      taskId: "task_1",
      noteType: "RESEARCH_REPORT",
      title: "Laptop Shortlist",
      content: "1. Option A\n2. Option B",
      tagsCsv: "laptops,research",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(noteRef.update({ title: "Updated Shortlist", updatedAt: pastTimestamp(1000) }));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("workspace_notes").get());
  await assertSucceeds(noteRef.delete());
});

test("Personal Wallet enforces strict UID isolation, forbids fake balance without provider, blocks unauthorized/unverified purchases, and locks immutable receipts", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastTimestamp(5000);

  const walletRef = aliceDb.collection("users").doc(ALICE_UID).collection("wallet_accounts").doc("primary");

  // Reject creating fake balance when provider is UNCONFIGURED
  await assertFails(
    walletRef.set({
      userId: ALICE_UID,
      currency: "USD",
      verifiedBalanceMinor: 50000,
      pendingBalanceMinor: 0,
      perTxLimitMinor: 25000,
      dailyLimitMinor: 100000,
      monthlyLimitMinor: 300000,
      dailySpentMinor: 0,
      monthlySpentMinor: 0,
      emergencyLocked: false,
      isMinorAccount: false,
      guardianApproved: true,
      providerName: "UNCONFIGURED",
      reconciliationStatus: "UNCONFIGURED",
      reconciliationSha256: VALID_SHA256,
      lastReconciledAtMs: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Valid reconciled wallet account from payment provider
  await assertSucceeds(
    walletRef.set({
      userId: ALICE_UID,
      currency: "USD",
      verifiedBalanceMinor: 25000,
      pendingBalanceMinor: 0,
      perTxLimitMinor: 20000,
      dailyLimitMinor: 50000,
      monthlyLimitMinor: 200000,
      dailySpentMinor: 0,
      monthlySpentMinor: 0,
      emergencyLocked: false,
      isMinorAccount: false,
      guardianApproved: true,
      providerName: "Stripe Connect",
      providerAccountRef: "acct_123",
      reconciliationStatus: "RECONCILED",
      reconciliationSha256: VALID_SHA256,
      lastReconciledAtMs: Date.now() - 3000,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Cross-user wallet access rejected
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("wallet_accounts").doc("primary").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("wallet_accounts").doc("primary").update({
      emergencyLocked: true,
      updatedAt: pastTimestamp(1000),
    })
  );

  // Transaction cannot mark PURCHASE SUCCEEDED without ownerAuthorized=true and VERIFIED providerTransactionId
  const txCol = aliceDb.collection("users").doc(ALICE_UID).collection("wallet_transactions");
  await assertFails(
    txCol.doc("tx_unauth").set({
      userId: ALICE_UID,
      txType: "PURCHASE",
      status: "SUCCEEDED",
      idempotencyKey: "idem_unauth_1",
      providerTransactionId: "pi_123",
      providerVerificationStatus: "VERIFIED",
      merchant: "BookStore",
      productTitle: "Hardcover",
      deliveryOrBookingDetails: "Ship to SFO",
      subtotalMinor: 2000,
      taxesAndFeesMinor: 200,
      totalMinor: 2200,
      balanceBeforeMinor: 25000,
      balanceAfterMinor: 22800,
      refundedMinor: 0,
      receiptSha256: VALID_SHA256,
      ownerAuthorized: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Valid pending -> authorized -> succeeded purchase, then refund allowed, delete forbidden
  const txRef = txCol.doc("tx_valid_1");
  await assertSucceeds(
    txRef.set({
      userId: ALICE_UID,
      txType: "PURCHASE",
      status: "PENDING",
      idempotencyKey: "idem_order_100",
      providerVerificationStatus: "UNVERIFIED",
      merchant: "Boutique Hotel",
      productTitle: "2 Nights King Room",
      deliveryOrBookingDetails: "Check-in Dec 12",
      subtotalMinor: 10000,
      taxesAndFeesMinor: 1500,
      totalMinor: 11500,
      balanceBeforeMinor: 25000,
      balanceAfterMinor: 13500,
      refundedMinor: 0,
      receiptSha256: VALID_SHA256,
      ownerAuthorized: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    txRef.update({
      status: "SUCCEEDED",
      ownerAuthorized: true,
      providerTransactionId: "pi_verified_100",
      providerVerificationStatus: "VERIFIED",
      updatedAt: pastTimestamp(1200),
    })
  );
  // Cannot delete transaction receipt
  await assertFails(txRef.delete());
  // Cross-user cannot read transaction
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("wallet_transactions").get());
});

test("Email connections forbid storing passwords/tokens, Browser & Permission policies & Security audit isolate users", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastTimestamp(5000);

  const emailConnRef = aliceDb.collection("users").doc(ALICE_UID).collection("email_connections").doc("primary");
  // Reject ghost password/accessToken fields
  await assertFails(
    emailConnRef.set({
      userId: ALICE_UID,
      connectedEmail: "alice@gmail.com",
      alloraAlias: "alice@allora.local",
      mailboxProvisioned: false,
      connectionStatus: "OAUTH_CONNECTED",
      grantedScopesCsv: "gmail.readonly,gmail.compose",
      allowNarrowAutoSend: false,
      allowedAutoSendRecipientsCsv: "",
      accessToken: "ya29.secret",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    emailConnRef.set({
      userId: ALICE_UID,
      connectedEmail: "alice@gmail.com",
      alloraAlias: "alice@workspace.allora.local",
      mailboxProvisioned: false,
      connectionStatus: "OAUTH_CONNECTED",
      grantedScopesCsv: "gmail.readonly,gmail.compose",
      allowNarrowAutoSend: false,
      allowedAutoSendRecipientsCsv: "",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("email_connections").doc("primary").get());

  // Security Audit log is append-only (no update/delete even by owner)
  const auditRef = aliceDb.collection("users").doc(ALICE_UID).collection("security_audit").doc("audit_1");
  await assertSucceeds(
    auditRef.set({
      userId: ALICE_UID,
      category: "PERMISSION",
      action: "WALLET_LOCK_ENABLED",
      outcome: "VERIFIED",
      detail: "Owner enabled emergency wallet lock",
      createdAt: ts,
    })
  );
  await assertFails(auditRef.update({ outcome: "DENIED" }));
  await assertFails(auditRef.delete());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("security_audit").get());

  // Browser tab & Permission policy isolation
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("browser_tabs").doc("tab_1").set({
      userId: ALICE_UID,
      url: "https://duckduckgo.com",
      title: "DuckDuckGo",
      isActive: true,
      isAutomationActive: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("browser_tabs").get());
});

test("AI model configs, AI preferences, and immutable failover telemetry enforce strict isolation and schema bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastTimestamp(5000);

  // Global ai_model_configs catalog is readable by signed-in users, read-only to clients
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("ai_model_configs").doc("firebase_gemini_3_8_flash").set({
      modelId: "firebase_gemini_3_8_flash",
      provider: "FIREBASE_AI_LOGIC",
      displayName: "Gemini 3.8 Flash",
      modelName: "gemini-2.5-flash",
      enabled: true,
      priority: 1,
      contextWindow: 1000000,
      maxTimeoutMs: 12000,
    });
  });
  await assertSucceeds(aliceDb.collection("ai_model_configs").get());
  await assertFails(
    aliceDb.collection("ai_model_configs").doc("tampered_model").set({
      modelId: "tampered_model",
      provider: "OPENROUTER",
    })
  );

  // User AI preferences: owner create, read, and partial update succeed
  const prefRef = aliceDb.collection("users").doc(ALICE_UID).collection("ai_preferences").doc("default");
  await assertSucceeds(
    prefRef.set({
      userId: ALICE_UID,
      selectionMode: "AUTOMATIC",
      preferredModelId: "firebase_gemini_3_8_flash",
      manualModelId: "firebase_gemini_3_8_flash",
      autoSwitchOnFailure: true,
      updatedAt: ts,
    })
  );
  await assertSucceeds(prefRef.get());
  await assertSucceeds(
    prefRef.update({
      selectionMode: "PREFERRED_MODEL",
      preferredModelId: "openrouter_claude_sonnet",
      manualModelId: "firebase_gemini_3_8_flash",
      autoSwitchOnFailure: true,
      updatedAt: pastTimestamp(1000),
    })
  );

  // Reject invalid selectionMode, ghost key, future timestamp, and cross-user access
  await assertFails(
    prefRef.update({
      selectionMode: "INVALID_MODE",
      preferredModelId: "firebase_gemini_3_8_flash",
      manualModelId: "firebase_gemini_3_8_flash",
      autoSwitchOnFailure: true,
      updatedAt: pastTimestamp(500),
    })
  );
  await assertFails(
    prefRef.update({
      selectionMode: "AUTOMATIC",
      preferredModelId: "firebase_gemini_3_8_flash",
      manualModelId: "firebase_gemini_3_8_flash",
      autoSwitchOnFailure: true,
      updatedAt: new Date(Date.now() + 60000),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("ai_preferences").doc("extra_doc").set({
      userId: ALICE_UID,
      selectionMode: "AUTOMATIC",
      preferredModelId: "firebase_gemini_3_8_flash",
      manualModelId: "firebase_gemini_3_8_flash",
      autoSwitchOnFailure: true,
      apiKeySecret: "sk-forbidden",
      updatedAt: ts,
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("ai_preferences").doc("default").get());

  // AI failover telemetry logs: owner append-only create & read, no update/delete, no cross-user access
  const logRef = aliceDb.collection("users").doc(ALICE_UID).collection("ai_failover_logs").doc("fo_1");
  await assertSucceeds(
    logRef.set({
      userId: ALICE_UID,
      requestId: "req_abc123",
      taskProfile: "SIMPLE_CHAT",
      fromModelId: "firebase_gemini_3_8_flash",
      toModelId: "openrouter_gpt_4o_mini",
      reasonCategory: "RATE_LIMIT",
      statusCode: 429,
      detail: "Switched after HTTP 429",
      delayMs: 320,
      createdAt: ts,
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("ai_failover_logs").get());
  await assertFails(logRef.update({ detail: "Tampered" }));
  await assertFails(logRef.delete());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("ai_failover_logs").get());
});

test("ALLORA Memory, Projects, and Personality Profiles enforce strict UID isolation and schema rules", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const ts = pastTimestamp(4000);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("memories").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("projects").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("personality_profiles").get());

  // Memories
  const memRef = aliceDb.collection("users").doc(ALICE_UID).collection("memories").doc("mem_1");
  await assertSucceeds(
    memRef.set({
      userId: ALICE_UID,
      category: "USER_PREFERENCES",
      title: "Report Format",
      content: "Prefer structured bullet summaries with clear next actions.",
      source: "USER",
      userApproved: true,
      retentionDays: 90,
      expiresAt: Date.now() + 86400000,
      usageCount: 1,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(memRef.update({ content: "Updated concise format", updatedAt: pastTimestamp(1000) }));
  await assertFails(memRef.update({ category: "UNKNOWN_CATEGORY", updatedAt: pastTimestamp(500) }));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("memories").get());
  await assertSucceeds(memRef.delete());

  // Projects
  const projRef = aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1");
  await assertSucceeds(
    projRef.set({
      userId: ALICE_UID,
      title: "Q4 Architecture Upgrade",
      description: "Consolidate core services and telemetry.",
      status: "ACTIVE",
      progressPercent: 35,
      linkedTaskIdsCsv: "task_1",
      linkedFileIdsCsv: "",
      linkedThreadId: "thread_main",
      tagsCsv: "engineering,priority",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(projRef.update({ progressPercent: 80, status: "COMPLETED", updatedAt: pastTimestamp(900) }));
  await assertFails(projRef.update({ progressPercent: 150, updatedAt: pastTimestamp(400) }));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").get());
  await assertSucceeds(projRef.delete());

  // Personality Profiles (decoupled from permissions; invalid ghost fields like killSwitchOverride rejected)
  const profRef = aliceDb.collection("users").doc(ALICE_UID).collection("personality_profiles").doc("default");
  await assertSucceeds(
    profRef.set({
      userId: ALICE_UID,
      presetMode: "BALANCED_COMPANION",
      warmthLevel: 75,
      concisenessLevel: 65,
      proactivityLevel: 60,
      explanationTransparency: true,
      voiceConsistencyEnabled: true,
      customSignatureNote: "Calm and direct",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("personality_profiles").doc("bad").set({
      userId: ALICE_UID,
      presetMode: "BALANCED_COMPANION",
      warmthLevel: 75,
      concisenessLevel: 65,
      proactivityLevel: 60,
      explanationTransparency: true,
      voiceConsistencyEnabled: true,
      customSignatureNote: "",
      bypassPermissions: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("personality_profiles").get());

  // Autonomous task with RECEIVED -> VERIFYING lifecycle & executionId
  const execTaskRef = aliceDb.collection("users").doc(ALICE_UID).collection("autonomous_tasks").doc("task_exec_1");
  await assertSucceeds(
    execTaskRef.set({
      userId: ALICE_UID,
      executionId: "exec_abc123",
      projectId: "proj_1",
      title: "Verify Spec",
      goal: "Verify task lifecycle and execution ID",
      understandingSummary: "Verify transitions",
      status: "RECEIVED",
      totalSteps: 2,
      completedSteps: 0,
      checkpointJson: "{}",
      finalOutcome: "",
      reportMarkdown: "",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    execTaskRef.update({
      status: "VERIFYING",
      completedSteps: 2,
      updatedAt: pastTimestamp(500),
    })
  );
});


