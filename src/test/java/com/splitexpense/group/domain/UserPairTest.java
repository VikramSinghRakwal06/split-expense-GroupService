package com.splitexpense.group.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the canonical pair ordering.
 *
 * <p>The tests that matter here are the ones about <em>unsigned</em> comparison. Java's
 * {@code UUID.compareTo} and PostgreSQL's {@code uuid} ordering disagree for any pair whose
 * most significant bits differ in the top bit, and the database's
 * {@code ck_group_balances_canonical} constraint judges by PostgreSQL's rule. If this class
 * ever drifted back to {@code compareTo}, roughly half of all first-time pair inserts would
 * be rejected by a constraint violation that looks like nothing the code did.
 *
 * <p>{@code GroupApplyIntegrationTest} pins the same property from the other side, by letting
 * a real PostgreSQL judge rows this class produced.
 */
class UserPairTest {

    /** Most significant bits are all zero — unambiguously the smallest under either rule. */
    private static final UUID LOW_MSB = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Most significant bits are all ones. Read as a signed long that is -1, so
     * {@code UUID.compareTo} sorts it <em>below</em> {@link #LOW_MSB}; read as unsigned, as
     * PostgreSQL does, it is the largest possible value and sorts above.
     */
    private static final UUID HIGH_MSB = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Nested
    @DisplayName("orders pairs the way PostgreSQL does, not the way UUID.compareTo does")
    class Ordering {

        @Test
        void putsTheUnsignedSmallerIdFirstEvenWhenCompareToDisagrees() {
            // Guard the premise: if this ever stops holding, the test below proves nothing.
            assertThat(LOW_MSB.compareTo(HIGH_MSB))
                    .as("UUID.compareTo is expected to disagree with PostgreSQL here")
                    .isGreaterThan(0);

            UserPair pair = UserPair.of(LOW_MSB, HIGH_MSB);

            assertThat(pair.low()).isEqualTo(LOW_MSB);
            assertThat(pair.high()).isEqualTo(HIGH_MSB);
        }

        @Test
        void producesTheSamePairWhicheverWayRoundTheArgumentsCome() {
            assertThat(UserPair.of(LOW_MSB, HIGH_MSB))
                    .isEqualTo(UserPair.of(HIGH_MSB, LOW_MSB));
        }

        @Test
        void fallsThroughToTheLeastSignificantBitsWhenTheMostSignificantMatch() {
            UUID a = UUID.fromString("11111111-1111-1111-0000-000000000001");
            UUID b = UUID.fromString("11111111-1111-1111-ffff-ffffffffffff");

            // Same trap in the lower half: b's least significant bits are -1 as a signed long.
            UserPair pair = UserPair.of(a, b);

            assertThat(pair.low()).isEqualTo(a);
            assertThat(pair.high()).isEqualTo(b);
        }

        @Test
        void sortsAListIdenticallyToPostgresqlsByteWiseComparison() {
            UUID zero = UUID.fromString("00000000-0000-0000-0000-000000000000");
            UUID mid = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
            UUID justOverHalf = UUID.fromString("80000000-0000-0000-0000-000000000000");
            UUID max = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

            List<UUID> sorted = java.util.stream.Stream.of(max, zero, justOverHalf, mid)
                    .sorted(UserPair.POSTGRES_UUID_ORDER)
                    .toList();

            // The 0x7f -> 0x80 boundary is exactly where signed comparison flips and
            // PostgreSQL's does not.
            assertThat(sorted).containsExactly(zero, mid, justOverHalf, max);
        }
    }

    @Nested
    @DisplayName("reports which direction a debt moves the stored amount")
    class Sign {

        @Test
        void addsWhenTheDebtorIsTheLowUser() {
            UserPair pair = UserPair.of(LOW_MSB, HIGH_MSB);
            assertThat(pair.signFor(pair.low())).isEqualTo(1);
        }

        @Test
        void subtractsWhenTheDebtorIsTheHighUser() {
            UserPair pair = UserPair.of(LOW_MSB, HIGH_MSB);
            assertThat(pair.signFor(pair.high())).isEqualTo(-1);
        }

        @Test
        void rejectsADebtorOutsideThePair() {
            UserPair pair = UserPair.of(LOW_MSB, HIGH_MSB);

            assertThatThrownBy(() -> pair.signFor(UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not part of the pair");
        }
    }

    @Nested
    @DisplayName("refuses inputs that are not a pair at all")
    class Rejections {

        @Test
        void rejectsAUserPairedWithThemselves() {
            UUID same = UUID.randomUUID();

            assertThatThrownBy(() -> UserPair.of(same, same))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot form a pair with themselves");
        }

        @Test
        void rejectsNulls() {
            assertThatThrownBy(() -> UserPair.of(null, LOW_MSB))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> UserPair.of(LOW_MSB, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
