from __future__ import annotations

from fractions import Fraction


def round_half_even(value: Fraction) -> int:
    """Round an exact rational to an integer using symmetric ties-to-even."""
    quotient, remainder = divmod(abs(value.numerator), value.denominator)
    doubled = remainder * 2
    if doubled > value.denominator or (doubled == value.denominator and quotient % 2 == 1):
        quotient += 1
    return -quotient if value < 0 else quotient


def round_half_away_from_zero(value: Fraction) -> int:
    quotient, remainder = divmod(abs(value.numerator), value.denominator)
    if remainder * 2 >= value.denominator:
        quotient += 1
    return -quotient if value < 0 else quotient
