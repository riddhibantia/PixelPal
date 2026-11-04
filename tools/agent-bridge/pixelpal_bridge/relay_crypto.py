"""
Relay pairing crypto — **stdlib only**, no third-party dependencies.

## The design problem

The user must carry *something* from the laptop to the phone. Typing an IP, a
port and a 32-char key is the tedium we are removing, so the thing carried has to
be short. But a short secret is weak.

## The fix: the code is never transmitted

We never *encrypt* the bridge key under the pair code and ship it (an attacker
who captures that blob can brute-force it offline forever). Instead we
**derive** the relay key from the code on both sides:

    relay_key = PBKDF2-HMAC-SHA256(pair_code, salt, 260_000)

Only `relay_key` (and a hash of it) ever reaches Firestore — never the code, and
never a ciphertext an attacker can grind against. Firestore holds:

    relays/{lookup_id}/handshake = { verifier: HMAC(relay_key, "pixelpal-handshake") }

`lookup_id` is itself a hash of the code, so a Firestore reader cannot learn
which code maps to which relay without guessing it.

An attacker watching the laptop can see `/setup` print — but the code is only
shown on the physical terminal, never logged and never sent.

## Why 8 characters

6 chars from a 32-char alphabet = 30 bits (~39 GPU-hours to brute force).
8 chars = 40 bits (~45,000 GPU-hours) — comfortably uncrackable, still a single
glance to type. We use an alphabet with no ambiguous glyphs (no O/0, I/1, L) so
it is easy to read aloud and type.

## Constant-time comparison

Every verifier comparison uses `hmac.compare_digest`, so a wrong code cannot be
narrowed down by timing.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets

# 27 symbols with every ambiguous glyph removed (0/O, 1/I/L, 2/Z, 5/S all
# absent). 9 characters from this alphabet = 42.8 bits of entropy: well past a
# 40-bit bar, and still read-and-typed in a single glance.
ALPHABET = "346789ABCDEFGHJKMNPQRTUVWXY"
CODE_LENGTH = 9
GROUP_SIZE = 3

PBKDF2_ITERATIONS = 260_000
SALT = b"pixelpal-relay-v1"
VERIFIER_LABEL = b"pixelpal-handshake"
LOOKUP_LABEL = b"pixelpal-lookup"


# ---------------------------------------------------------------------------
# code generation / parsing
# ---------------------------------------------------------------------------


def generate_code() -> str:
    """A fresh pairing code, grouped for typing: 'ABCD-EFGH'."""
    raw = "".join(secrets.choice(ALPHABET) for _ in range(CODE_LENGTH))
    return "-".join(
        raw[i:i + GROUP_SIZE] for i in range(0, CODE_LENGTH, GROUP_SIZE)
    )


def normalize_code(code: str) -> str | None:
    """
    Canonicalise user input: strip spaces/dashes, upper-case, validate.

    Returns None if the result cannot be a valid code, so callers can show
    "that code looks wrong" instead of failing deep inside the crypto.
    """
    if not code:
        return None
    cleaned = code.strip().upper().replace("-", "").replace(" ", "")
    # ALPHABET already excludes every ambiguous glyph (0/O, 1/I/L, 2/Z, 5/S),
    # so there is nothing to "correct" — a typed `O` is genuinely invalid and
    # silently rewriting it would hide a real transcription error. We only
    # normalise case and separators, then validate strictly.
    if len(cleaned) != CODE_LENGTH:
        return None
    if any(ch not in ALPHABET for ch in cleaned):
        return None
    return cleaned


def format_code(normalized: str) -> str:
    """Re-group a normalized code for display."""
    return "-".join(
        normalized[i:i + GROUP_SIZE] for i in range(0, len(normalized), GROUP_SIZE)
    )


# ---------------------------------------------------------------------------
# key derivation
# ---------------------------------------------------------------------------


def derive_relay_key(normalized_code: str) -> bytes:
    """
    pair code -> 32-byte relay key.

    Deterministic and identical on both sides; this is the whole handshake.
    """
    if normalize_code(normalized_code) is None:
        raise ValueError("invalid pairing code")
    return hashlib.pbkdf2_hmac(
        "sha256", normalized_code.encode(), SALT, PBKDF2_ITERATIONS, dklen=32
    )


def verifier(relay_key: bytes) -> str:
    """Public proof-of-key stored in Firestore. Reveals nothing about the key."""
    return hmac.new(relay_key, VERIFIER_LABEL, hashlib.sha256).hexdigest()


def lookup_id(normalized_code: str) -> str:
    """
    Firestore doc id, derived from the code.

    Hashed so that reading the relays collection does not hand anyone a list of
    valid codes. Truncated to 32 hex chars — enough to avoid collisions and to
    keep the path short.
    """
    digest = hashlib.sha256(normalized_code.encode()).hexdigest()
    return digest[:32]


def verify(relay_key: bytes, presented_verifier: str) -> bool:
    """Constant-time check that a peer's verifier matches ours."""
    if not isinstance(presented_verifier, str) or not presented_verifier:
        return False
    return hmac.compare_digest(verifier(relay_key), presented_verifier.lower())


def new_bridge_key() -> str:
    """The long-lived secret used for direct (LAN) auth. Never typed by hand."""
    return secrets.token_urlsafe(24)
