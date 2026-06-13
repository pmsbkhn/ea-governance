package tech.vsf.ea.archrules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;

/**
 * The quantum-boundary rule turns the architect's declared synchronous coupling into an enforceable
 * invariant: a synchronous outbound client to an UNdeclared quantum must fail; declaring it passes.
 */
class QuantumBoundaryTest {

    private static final JavaClasses CLASSES =
            new ClassFileImporter().importPackages("tech.vsf.ea.archrules.fixtures");
    private static final String CLIENT_PKG = "tech.vsf.ea.archrules.fixtures..outbound.client..";

    @Test
    void undeclared_sync_quantum_is_a_violation() {
        EvaluationResult r = FitnessRules
                .outboundSyncClientsStayWithinQuanta(CLIENT_PKG, "ClientOa", Set.of("inventory"))
                .evaluate(CLASSES);

        assertTrue(r.hasViolation(), "ShippingClientOa targets an undeclared quantum");
        assertTrue(r.getFailureReport().toString().contains("ShippingClientOa"));
        assertFalse(r.getFailureReport().toString().contains("InventoryClientOa"));
    }

    @Test
    void all_declared_quanta_pass() {
        EvaluationResult r = FitnessRules
                .outboundSyncClientsStayWithinQuanta(CLIENT_PKG, "ClientOa", Set.of("inventory", "shipping"))
                .evaluate(CLASSES);

        assertFalse(r.hasViolation());
    }
}
