package tech.vsf.ea.archrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class FitnessResultTest {

    @Test
    void json_wire_form_is_flat_and_stable() {
        FitnessResult r = new FitnessResult(
                "ecommerce-inventory", "useCaseSliceIsolation",
                FitnessResult.Layer.CODE, FitnessResult.Verdict.PASS, "enforce",
                0, null, Instant.parse("2026-06-13T10:00:00Z"), "ea-archrules");

        assertEquals(
                "{\"schemaVersion\":\"1\",\"system\":\"ecommerce-inventory\","
                        + "\"rule\":\"useCaseSliceIsolation\",\"layer\":\"code\",\"verdict\":\"pass\","
                        + "\"mode\":\"enforce\",\"violations\":0,\"waiverExpires\":null,"
                        + "\"ts\":\"2026-06-13T10:00:00Z\",\"source\":\"ea-archrules\"}",
                r.toJson());
    }

    @Test
    void waiver_expiry_is_rendered_as_iso_date() {
        FitnessResult r = FitnessResult.code("s", "r", FitnessResult.Verdict.WAIVED, "enforce",
                3, LocalDate.parse("2026-09-30"));

        assertTrue(r.toJson().contains("\"waiverExpires\":\"2026-09-30\""));
        assertTrue(r.toJson().contains("\"violations\":3"));
        assertTrue(r.toJson().contains("\"verdict\":\"waived\""));
    }

    @Test
    void verdict_and_layer_wire_tokens_are_lowercase() {
        assertEquals("skipped", FitnessResult.Verdict.SKIPPED.wire());
        assertEquals("runtime", FitnessResult.Layer.RUNTIME.wire());
    }
}
