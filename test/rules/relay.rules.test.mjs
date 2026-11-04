/**
 * Security rules tests for the relay branch.
 *
 * These run against the Firestore emulator with the REAL firestore.rules file,
 * so they prove the deployed behaviour rather than a description of it.
 *
 * The property that matters most: a third party who is signed in — and even one
 * who knows a relay's document id — must be denied everything.
 *
 * Run: npm test  (needs the emulator; see package.json script)
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} from '@firebase/rules-unit-testing';
import {
  doc,
  setDoc,
  getDoc,
  updateDoc,
  deleteDoc,
} from 'firebase/firestore';

const here = dirname(fileURLToPath(import.meta.url));
const RULES = readFileSync(resolve(here, '../../firestore.rules'), 'utf8');

const PROJECT = 'demo-pixelpal';
const RELAY = 'relayabc123';

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT,
    firestore: { rules: RULES, host: '127.0.0.1', port: 8080 },
  });
});

after(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

/** A signed-in context for an arbitrary uid. */
function as(uid) {
  return env.authenticatedContext(uid).firestore();
}

function anonDb(uid) {
  return env.unauthenticatedContext().firestore();
}

const HANDMADE = {
  bridgeUid: 'bridge-uid',
  phoneUid: null,
  verifier: 'deadbeef',
  createdAt: Date.now(),
  lastSeen: Date.now(),
  version: 1,
};

async function seedRelay(overrides = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'relays', RELAY), {
      ...HANDMADE,
      ...overrides,
    });
  });
}

describe('relay handshake document', () => {
  it('lets a signed-in bridge create its own relay', async () => {
    const db = as('bridge-uid');
    await assertSucceeds(
      setDoc(doc(db, 'relays', RELAY), { ...HANDMADE, bridgeUid: 'bridge-uid' })
    );
  });

  it('refuses to create a relay owned by someone else', async () => {
    const db = as('attacker');
    await assertFails(
      setDoc(doc(db, 'relays', RELAY), { ...HANDMADE, bridgeUid: 'bridge-uid' })
    );
  });

  it('denies unauthenticated reads and writes', async () => {
    await seedRelay();
    const db = anonDb();
    await assertFails(getDoc(doc(db, 'relays', RELAY)));
    await assertFails(
      setDoc(doc(db, 'relays', 'other'), { ...HANDMADE, bridgeUid: 'x' })
    );
  });

  it('THE KEY GUARANTEE: a third party who knows the doc id is denied', async () => {
    await seedRelay();
    const stranger = as('curious-user');
    await assertFails(getDoc(doc(stranger, 'relays', RELAY)));
    await assertFails(
      updateDoc(doc(stranger, 'relays', RELAY), { verifier: 'mine' })
    );
  });

  it('lets the bridge read and refresh lastSeen', async () => {
    await seedRelay();
    const db = as('bridge-uid');
    await assertSucceeds(getDoc(doc(db, 'relays', RELAY)));
    await assertSucceeds(
      updateDoc(doc(db, 'relays', RELAY), { lastSeen: Date.now() })
    );
  });

  it('lets the phone claim an unclaimed relay exactly once', async () => {
    await seedRelay();
    const phone = as('phone-uid');
    await assertSucceeds(
      updateDoc(doc(phone, 'relays', RELAY), { phoneUid: 'phone-uid' })
    );
  });

  it('prevents a second phone from hijacking the relay', async () => {
    await seedRelay({ phoneUid: 'phone-uid' });
    const rival = as('rival-uid');
    await assertFails(
      updateDoc(doc(rival, 'relays', RELAY), { phoneUid: 'rival-uid' })
    );
  });

  it('prevents the phone from reassigning bridgeUid', async () => {
    await seedRelay();
    const phone = as('phone-uid');
    await assertFails(
      updateDoc(doc(phone, 'relays', RELAY), {
        phoneUid: 'phone-uid',
        bridgeUid: 'me-now',
      })
    );
  });

  it('lets only the bridge delete the relay', async () => {
    await seedRelay({ phoneUid: 'phone-uid' });
    await assertFails(deleteDoc(doc(as('phone-uid'), 'relays', RELAY)));
    await assertSucceeds(deleteDoc(doc(as('bridge-uid'), 'relays', RELAY)));
  });
});

describe('relay state subdocument', () => {
  beforeEach(async () => {
    await seedRelay({ phoneUid: 'phone-uid' });
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'relays', RELAY, 'state', 'current'), {
        agents: '{"agents":[]}',
        updatedAt: Date.now(),
      });
    });
  });

  it('lets the phone read agent state', async () => {
    const db = as('phone-uid');
    await assertSucceeds(
      getDoc(doc(db, 'relays', RELAY, 'state', 'current'))
    );
  });

  it('lets the bridge publish agent state', async () => {
    const db = as('bridge-uid');
    await assertSucceeds(
      setDoc(doc(db, 'relays', RELAY, 'state', 'current'), {
        agents: '{"agents":[]}',
        updatedAt: Date.now(),
      })
    );
  });

  it('stops the phone from forging agent state', async () => {
    const db = as('phone-uid');
    await assertFails(
      setDoc(doc(db, 'relays', RELAY, 'state', 'current'), {
        agents: '{"agents":[{"message":"fake"}]}',
        updatedAt: Date.now(),
      })
    );
  });

  it('stops a stranger from reading agent state', async () => {
    const db = as('curious-user');
    await assertFails(getDoc(doc(db, 'relays', RELAY, 'state', 'current')));
  });
});

describe('approval verdicts (inbox)', () => {
  beforeEach(async () => {
    await seedRelay({ phoneUid: 'phone-uid' });
  });

  it('lets the phone write a verdict', async () => {
    const db = as('phone-uid');
    await assertSucceeds(
      setDoc(doc(db, 'relays', RELAY, 'inbox', 'current'), {
        approvalId: 'a1',
        decision: 'approve',
      })
    );
  });

  it('stops the bridge from writing its own approval answers', async () => {
    // The agent must never be able to approve itself.
    const db = as('bridge-uid');
    await assertFails(
      setDoc(doc(db, 'relays', RELAY, 'inbox', 'current'), {
        approvalId: 'a1',
        decision: 'approve',
      })
    );
  });

  it('stops a stranger from writing verdicts', async () => {
    const db = as('curious-user');
    await assertFails(
      setDoc(doc(db, 'relays', RELAY, 'inbox', 'current'), {
        approvalId: 'a1',
        decision: 'approve',
      })
    );
  });
});

describe('existing user data is unaffected', () => {
  it('still allows a user to write their own branch', async () => {
    const db = as('user-1');
    await assertSucceeds(
      setDoc(doc(db, 'users', 'user-1', 'tasks', 't1'), { title: 'hi' })
    );
  });

  it('still denies access to another users branch', async () => {
    const db = as('user-1');
    await assertFails(
      setDoc(doc(db, 'users', 'user-2', 'tasks', 't1'), { title: 'nope' })
    );
  });

  it('does not let the relay branch be used to reach user data', async () => {
    const db = as('bridge-uid');
    await assertFails(
      setDoc(doc(db, 'users', 'someone', 'tasks', 't1'), { title: 'x' })
    );
  });
});
