package de.tobias30.claims;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LimitsTest {
    @Test void highestGrantedNumberWinsAndOverridesDefault() {
        assertEquals(8, Limits.resolve(Map.of("claims.homes.2", true, "claims.homes.8", true, "claims.homes.99", false), "claims.homes.", 20));
    }
    @Test void zeroCanDisableHomes() { assertEquals(0, Limits.resolve(Map.of("claims.homes.0", true), "claims.homes.", 3)); }
    @Test void ignoresWrongPrefixesNegativeMalformedAndOverflow() {
        assertEquals(3, Limits.resolve(Map.of("claims.claims.10", true, "claims.homes.-1", true, "claims.homes.*", true, "claims.homes.999999999999", true), "claims.homes.", 3));
    }
    @Test void supportsAnyNonnegativeInteger() { assertEquals(12345, Limits.resolve(Map.of("claims.claims.12345", true), "claims.claims.", 8)); }
}
