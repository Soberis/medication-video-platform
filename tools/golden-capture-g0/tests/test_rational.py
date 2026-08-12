from fractions import Fraction

from golden_capture_g0.rational import round_half_away_from_zero, round_half_even
from golden_capture_g0.sync import build_timeline


def test_round_half_even_is_symmetric_and_ties_to_even() -> None:
    assert round_half_even(Fraction(1, 2)) == 0
    assert round_half_even(Fraction(3, 2)) == 2
    assert round_half_even(Fraction(5, 2)) == 2
    assert round_half_even(Fraction(-1, 2)) == 0
    assert round_half_even(Fraction(-3, 2)) == -2
    assert round_half_away_from_zero(Fraction(1, 2)) == 1
    assert round_half_away_from_zero(Fraction(-1, 2)) == -1


def test_30hz_timeline_uses_absolute_rational_rounding_without_interval_drift() -> None:
    timeline = build_timeline(Fraction(2), Fraction(30))

    assert len(timeline) == 60
    assert timeline[:4] == (0, 33_333_333, 66_666_667, 100_000_000)
    assert timeline[-1] == 1_966_666_667
    assert timeline[-1] != 59 * 33_333_333
