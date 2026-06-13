package tech.vsf.ea.archrules;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/**
 * The canonical, emitter-agnostic result of a single fitness-function evaluation — the unit the EA
 * portfolio scorecard is built from. <b>Both</b> the static layer ({@link FitnessHarness}, ArchUnit /
 * OPA-style gates) and the dynamic layer (an SLO exporter turning a latency/error budget into a
 * verdict) emit the same shape, keyed by {@code system} so the registry can roll results up per
 * P&amp;L / domain over time.
 *
 * <p>The wire form is one flat JSON object (see {@link #toJson()} and {@code docs/fitness-result.md}):
 * <pre>{@code
 * {"schemaVersion":"1","system":"ecommerce-inventory","rule":"useCaseSliceIsolation",
 *  "layer":"code","verdict":"pass","mode":"enforce","violations":0,
 *  "waiverExpires":null,"ts":"2026-06-13T10:00:00Z","source":"ea-archrules"}
 * }</pre>
 * Keeping it dependency-free (hand-rolled JSON, no Jackson) preserves the library's zero-transitive
 * footprint at the consuming service's test scope.
 */
public record FitnessResult(
        String system,
        String rule,
        Layer layer,
        Verdict verdict,
        String mode,
        int violations,
        LocalDate waiverExpires,
        Instant ts,
        String source) {

    public static final String SCHEMA_VERSION = "1";

    /** Where in the delivery path the function ran — the scorecard slices conformance by this. */
    public enum Layer {
        CODE, CONTRACT, RUNTIME;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The verdict of one evaluation. {@code WAIVED} and {@code SKIPPED} are distinct from a clean
     *  {@code PASS} so the scorecard can tell "green" from "knowingly not enforced". */
    public enum Verdict {
        PASS, WARN, FAIL, WAIVED, SKIPPED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A static-layer (code) result stamped now. The harness uses this for every evaluation. */
    public static FitnessResult code(String system, String rule, Verdict verdict, String mode,
                                     int violations, LocalDate waiverExpires) {
        return new FitnessResult(system, rule, Layer.CODE, verdict, mode, violations,
                waiverExpires, Instant.now(), "ea-archrules");
    }

    /** The flat JSON wire form — one line, stable key order, safe to scrape from a log stream. */
    public String toJson() {
        StringBuilder b = new StringBuilder(192);
        b.append('{');
        str(b, "schemaVersion", SCHEMA_VERSION).append(',');
        str(b, "system", system).append(',');
        str(b, "rule", rule).append(',');
        str(b, "layer", layer.wire()).append(',');
        str(b, "verdict", verdict.wire()).append(',');
        str(b, "mode", mode).append(',');
        b.append("\"violations\":").append(violations).append(',');
        b.append("\"waiverExpires\":");
        if (waiverExpires == null) {
            b.append("null");
        } else {
            b.append('"').append(waiverExpires).append('"');
        }
        b.append(',');
        str(b, "ts", ts.toString()).append(',');
        str(b, "source", source);
        return b.append('}').toString();
    }

    private static StringBuilder str(StringBuilder b, String key, String value) {
        b.append('"').append(key).append("\":");
        if (value == null) {
            return b.append("null");
        }
        b.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.append('"');
    }
}
