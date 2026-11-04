"""
Tests for the relay pairing crypto.

The security properties here are the whole reason a short code is acceptable, so
each one gets an explicit test rather than being assumed.
"""

from __future__ import annotations

import itertools

import pytest

from pixelpal_bridge import relay_crypto as rc


class TestCodeFormat:
    def test_generated_code_is_valid(self):
        code = rc.generate_code()
        assert rc.normalize_code(code) is not None
        assert len(code.replace("-", "")) == rc.CODE_LENGTH

    def test_code_is_grouped_for_typing(self):
        assert rc.generate_code().count("-") == rc.CODE_LENGTH // rc.GROUP_SIZE - 1

    def test_alphabet_has_no_ambiguous_glyphs(self):
        """Reading a code aloud must never be ambiguous."""
        for bad in "01OIL2Z5S":
            assert bad not in rc.ALPHABET, f"{bad} is ambiguous"

    def test_alphabet_is_unique(self):
        assert len(set(rc.ALPHABET)) == len(rc.ALPHABET)

    def test_codes_are_random(self):
        codes = {rc.generate_code() for _ in range(200)}
        assert len(codes) > 190, "code generation looks non-random"

    def test_lowercase_and_spaces_are_accepted(self):
        code = rc.generate_code()
        n = rc.normalize_code(code)
        assert rc.normalize_code(code.lower()) == n
        assert rc.normalize_code(code.replace("-", " ")) == n
        assert rc.normalize_code(f"  {code}  ") == n

    @pytest.mark.parametrize("bad", ["", "   ", "abc", "TOOSHORT", "WAYTOOLONGCODE"])
    def test_malformed_codes_rejected(self, bad):
        assert rc.normalize_code(bad) is None

    def test_ambiguous_glyph_is_rejected_not_silently_rewritten(self):
        """
        A typed 'O' is a real transcription error. Silently mapping it to
        something else would pair with the WRONG key and fail confusingly later.
        """
        assert rc.normalize_code("OOOOOOOO") is None
        assert rc.normalize_code("00000000") is None

    def test_format_roundtrip(self):
        n = rc.normalize_code(rc.generate_code())
        assert rc.normalize_code(rc.format_code(n)) == n


class TestKeyDerivation:
    def test_same_code_same_key(self):
        n = rc.normalize_code(rc.generate_code())
        assert rc.derive_relay_key(n) == rc.derive_relay_key(n)

    def test_different_codes_different_keys(self):
        a = rc.derive_relay_key(rc.normalize_code(rc.generate_code()))
        b = rc.derive_relay_key(rc.normalize_code(rc.generate_code()))
        assert a != b

    def test_key_is_32_bytes(self):
        assert len(rc.derive_relay_key(rc.normalize_code(rc.generate_code()))) == 32

    def test_invalid_code_raises(self):
        with pytest.raises(ValueError):
            rc.derive_relay_key("not-a-code")

    def test_neighbouring_codes_are_far_apart(self):
        """A one-character typo must not produce a near-identical key."""
        code = rc.normalize_code(rc.generate_code())
        base = rc.derive_relay_key(code)
        for i in range(len(code)):
            for alt in rc.ALPHABET[:4]:
                if alt == code[i]:
                    continue
                mutated = code[:i] + alt + code[i + 1:]
                other = rc.derive_relay_key(mutated)
                diff = sum(bin(x ^ y).count("1") for x, y in zip(base, other))
                # PBKDF2 output should look random: ~half the bits differ.
                assert diff > 80, f"mutating position {i} gave only {diff} differing bits"


class TestVerifier:
    def test_both_sides_agree(self):
        code = rc.generate_code()
        bridge = rc.derive_relay_key(rc.normalize_code(code))
        phone = rc.derive_relay_key(rc.normalize_code(code))
        assert rc.verify(phone, rc.verifier(bridge))

    def test_wrong_code_is_denied(self):
        bridge = rc.derive_relay_key(rc.normalize_code(rc.generate_code()))
        attacker = rc.derive_relay_key(rc.normalize_code(rc.generate_code()))
        assert not rc.verify(attacker, rc.verifier(bridge))

    def test_verifier_does_not_leak_the_key(self):
        key = rc.derive_relay_key(rc.normalize_code(rc.generate_code()))
        v = rc.verifier(key)
        assert key.hex() not in v
        assert v != key.hex()

    @pytest.mark.parametrize("presented", ["", None, "deadbeef", "x" * 64])
    def test_bad_verifiers_are_denied(self, presented):
        key = rc.derive_relay_key(rc.normalize_code(rc.generate_code()))
        assert not rc.verify(key, presented)

    def test_comparison_is_case_insensitive_for_presentation(self):
        """Verifiers travel through Firestore; case should not break pairing."""
        key = rc.derive_relay_key(rc.normalize_code(rc.generate_code()))
        v = rc.verifier(key)
        assert rc.verify(key, v.upper())


class TestLookupId:
    def test_stable_for_same_code(self):
        n = rc.normalize_code(rc.generate_code())
        assert rc.lookup_id(n) == rc.lookup_id(n)

    def test_differs_for_different_codes(self):
        a = rc.lookup_id(rc.normalize_code(rc.generate_code()))
        b = rc.lookup_id(rc.normalize_code(rc.generate_code()))
        assert a != b

    def test_does_not_contain_the_code(self):
        """Reading the relays collection must not reveal valid codes."""
        n = rc.normalize_code(rc.generate_code())
        assert n.lower() not in rc.lookup_id(n)
        assert n not in rc.lookup_id(n).upper()

    def test_is_filesystem_and_url_safe(self):
        n = rc.normalize_code(rc.generate_code())
        lid = rc.lookup_id(n)
        assert all(ch in "0123456789abcdef" for ch in lid)


class TestBridgeKey:
    def test_bridge_key_is_long_and_random(self):
        keys = {rc.new_bridge_key() for _ in range(100)}
        assert len(keys) == 100
        assert all(len(k) >= 30 for k in keys)

    def test_bridge_key_differs_from_relay_key(self):
        """Direct (LAN) auth and relay auth must not share a secret."""
        assert rc.new_bridge_key().encode() != rc.derive_relay_key(
            rc.normalize_code(rc.generate_code())
        )


class TestBruteForceCost:
    """
    Documents the security margin so a future change to CODE_LENGTH or the
    alphabet has to re-justify itself.
    """

    def test_code_has_at_least_40_bits_of_entropy(self):
        import math

        assert rc.CODE_LENGTH * math.log2(len(rc.ALPHABET)) >= 40

    def test_iteration_count_is_not_weakened(self):
        assert rc.PBKDF2_ITERATIONS >= 200_000
