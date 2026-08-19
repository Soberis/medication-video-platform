package com.medicationvideo.platform.assetcapture;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Exact rational arithmetic used by the capture timeline and stream mappings. Rounding is
 * deliberately kept at the boundary where an integer nanosecond value is required.
 */
public record Rational(BigInteger numerator, BigInteger denominator) implements Comparable<Rational> {

	public Rational {
		Objects.requireNonNull(numerator, "numerator");
		Objects.requireNonNull(denominator, "denominator");
		if (denominator.signum() == 0) {
			throw new IllegalArgumentException("A rational denominator must not be zero.");
		}
		if (denominator.signum() < 0) {
			numerator = numerator.negate();
			denominator = denominator.negate();
		}
		var divisor = numerator.gcd(denominator);
		if (divisor.signum() > 0) {
			numerator = numerator.divide(divisor);
			denominator = denominator.divide(divisor);
		}
	}

	public Rational(long numerator, long denominator) {
		this(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
	}

	public static Rational of(long numerator, long denominator) {
		return new Rational(numerator, denominator);
	}

	public static Rational whole(long value) {
		return new Rational(value, 1);
	}

	public Rational add(Rational other) {
		Objects.requireNonNull(other, "other");
		return new Rational(this.numerator.multiply(other.denominator).add(other.numerator.multiply(this.denominator)),
				this.denominator.multiply(other.denominator));
	}

	public Rational subtract(Rational other) {
		return add(other.negate());
	}

	public Rational multiply(Rational other) {
		Objects.requireNonNull(other, "other");
		return new Rational(this.numerator.multiply(other.numerator), this.denominator.multiply(other.denominator));
	}

	public Rational divide(Rational other) {
		Objects.requireNonNull(other, "other");
		if (other.numerator.signum() == 0) {
			throw new IllegalArgumentException("A rational cannot be divided by zero.");
		}
		return new Rational(this.numerator.multiply(other.denominator), this.denominator.multiply(other.numerator));
	}

	public Rational negate() {
		return new Rational(this.numerator.negate(), this.denominator);
	}

	public int signum() {
		return this.numerator.signum();
	}

	public Rational abs() {
		return this.signum() < 0 ? negate() : this;
	}

	public long ceilLong() {
		var division = this.numerator.divideAndRemainder(this.denominator);
		if (division[1].signum() == 0) {
			return division[0].longValueExact();
		}
		return division[0].add(this.numerator.signum() > 0 ? BigInteger.ONE : BigInteger.ZERO).longValueExact();
	}

	@Override
	public int compareTo(Rational other) {
		Objects.requireNonNull(other, "other");
		return this.numerator.multiply(other.denominator).compareTo(other.numerator.multiply(this.denominator));
	}

	/**
	 * Rounds this value to an integer using IEEE 754 round-half-even semantics, including
	 * for negative values.
	 */
	public BigInteger roundHalfEven() {
		var division = this.numerator.divideAndRemainder(this.denominator);
		var quotient = division[0];
		var remainder = division[1];
		if (remainder.signum() == 0) {
			return quotient;
		}
		var comparison = remainder.abs().shiftLeft(1).compareTo(this.denominator);
		if (comparison < 0 || (comparison == 0 && !quotient.testBit(0))) {
			return quotient;
		}
		return quotient.add(BigInteger.valueOf(remainder.signum()));
	}

	public long roundHalfEvenLong() {
		return roundHalfEven().longValueExact();
	}

	public long numeratorLongExact() {
		return this.numerator.longValueExact();
	}

	public long denominatorLongExact() {
		return this.denominator.longValueExact();
	}

	@Override
	public String toString() {
		return this.numerator + "/" + this.denominator;
	}

}
