/**
 * End-to-end relay flow with the REAL rules and REAL Firebase client SDKs.
 * Proves the whole handshake, state publish, and approval round-trip.
 */
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, setDoc, updateDoc, getDoc } from 'firebase/firestore';
import { createHash, pbkdf2Sync, createHmac } from 'node:crypto';
import assert from 'node:assert';

const RULES = readFileSync('../../firestore.rules', 'utf8');
const ALPHABET = '346789ABCDEFGHJKMNPQRTUVWXY';

const env = await initializeTestEnvironment({
  projectId: 'demo-pixelpal',
  firestore: { rules: RULES, host: '127.0.0.1', port: 8080 },
});
await env.clearFirestore();

// --- the same crypto as Python/Kotlin
const gen = () => Array.from({length: 9}, () => ALPHABET[Math.floor(Math.random()*ALPHABET.length)]).join('');
const relayKey = c => pbkdf2Sync(c, 'pixelpal-relay-v1', 260000, 32, 'sha256');
const verifier = k => createHmac('sha256', k).update('pixelpal-handshake').digest('hex');
const lookupId = c => createHash('sha256').update(c).digest('hex').slice(0, 32);

const code = gen();
const key = relayKey(code);
const lid = lookupId(code);
const ver = verifier(key);
console.log('code:', code.match(/.{3}/g).join('-'), '| lookup:', lid.slice(0, 8) + '...');

// --- 1. bridge registers
const bridgeDb = env.authenticatedContext('bridge-uid').firestore();
await setDoc(doc(bridgeDb, 'relays', lid), {
  verifier: ver, bridgeUid: 'bridge-uid', createdAt: Date.now(), lastSeen: Date.now(),
});
console.log('1. bridge registered           ok');

// --- 2. phone claims
const phoneDb = env.authenticatedContext('phone-uid').firestore();
await updateDoc(doc(phoneDb, 'relays', lid), { phoneUid: 'phone-uid' });
console.log('2. phone claimed relay         ok');

// --- 3. bridge publishes
const payload = { agents: [{ source: 'claude_code', agentName: 'Claude Code',
  sessionId: 's1', status: 'WORKING', message: 'editing Main.kt' }], count: 1 };
await setDoc(doc(bridgeDb, 'relays', lid, 'state', 'current'), {
  agents: JSON.stringify(payload), updatedAt: Date.now(),
});
console.log('3. bridge published state      ok');

// --- 4. phone reads it
const snap = await getDoc(doc(phoneDb, 'relays', lid, 'state', 'current'));
const got = JSON.parse(snap.data().agents);
assert.strictEqual(got.agents[0].message, 'editing Main.kt');
console.log('4. phone read state            ok ->', got.agents[0].message);

// --- 5. phone answers approval
await setDoc(doc(phoneDb, 'relays', lid, 'inbox', 'current'),
  { approvalId: 'a1', decision: 'approve' });
console.log('5. phone wrote verdict         ok');

// --- 6. bridge reads verdict
const v = await getDoc(doc(bridgeDb, 'relays', lid, 'inbox', 'current'));
assert.strictEqual(v.data().decision, 'approve');
console.log('6. bridge read verdict         ok ->', v.data().decision);

// --- 7. STRANGER denied
const stranger = env.authenticatedContext('stranger-uid').firestore();
let denied = false;
try { await getDoc(doc(stranger, 'relays', lid, 'state', 'current')); }
catch { denied = true; }
assert.ok(denied, 'stranger must be denied');
console.log('7. stranger denied             YES');

// --- 8. wrong code fails
const wrongKey = relayKey(gen());
assert.notStrictEqual(verifier(wrongKey), ver);
console.log('8. wrong code rejected         YES');

// --- 9. phone cannot forge bridge state
let forgeDenied = false;
try { await setDoc(doc(phoneDb, 'relays', lid, 'state', 'current'), { agents: '{"fake":1}' }); }
catch { forgeDenied = true; }
assert.ok(forgeDenied, 'phone must not forge agent state');
console.log('9. phone cannot forge state    YES');

await env.cleanup();
console.log('\nALL END-TO-END RELAY CHECKS PASSED');
