# Firestore security rules tests

Proves the `/relays` branch in `firestore.rules` behaves as documented, using the
real rules file and the Firestore emulator (no live project touched).

```bash
cd test/rules
npm install
../..//firebase emulators:exec --only firestore --project demo-pixelpal \
  "npx mocha relay.rules.test.mjs --timeout 20000"    # 19 security assertions

../..//firebase emulators:exec --only firestore --project demo-pixelpal \
  "node relay_e2e.mjs"                                 # full handshake round-trip
```

`relay_e2e.mjs` walks the whole flow: bridge registers → phone claims → bridge
publishes agents → phone reads them → phone answers an approval → bridge reads
the verdict → a stranger is denied → a wrong code is rejected → the phone cannot
forge agent state.

## Rules that needed fixing (learned the hard way)

1. **An absent field is `undefined`, not `null`.** `resource.data.phoneUid == null`
   throws on an unclaimed document and silently denies the claim. Use
   `!resource.data.keys().hasAny([...])`.
2. **`updateDoc` re-sends untouched fields.** So
   `diff().affectedKeys().hasOnly(['phoneUid'])` is too strict for a claim;
   compare final *values* instead.
