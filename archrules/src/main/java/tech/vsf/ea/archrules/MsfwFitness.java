package tech.vsf.ea.archrules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * The <b>msfw cohort profile</b>: binds the generic {@link FitnessRules} to the conventions of
 * services built on the msfw framework (hexagonal {@code domain/application/adapter} slices, the
 * msfw base types and outbox annotation). Every msfw reference here is a fully-qualified NAME
 * string, so this profile — and the whole library — keeps zero compile dependency on msfw.
 *
 * <p>A service's architecture test becomes ~5 lines:</p>
 * <pre>{@code
 * @AnalyzeClasses(packages = "vn.marketplace.order", importOptions = DoNotIncludeTests.class)
 * class OrderArchitectureTest {
 *     @ArchTest static final ArchRule domain  = MsfwFitness.domainIsPure("vn.marketplace.order");
 *     @ArchTest static final ArchRule slices  = MsfwFitness.useCaseSlices("vn.marketplace.order.application.(*)..");
 *     @ArchTest static final ArchRule aggs    = MsfwFitness.aggregatesEncapsulated();
 *     @ArchTest static final ArchRule ents    = MsfwFitness.entitiesEncapsulated();
 *     @ArchTest static final ArchRule outbox  = MsfwFitness.stateWritersPublish("vn.marketplace.order.application");
 * }
 * }</pre>
 *
 * <p>A future non-msfw cohort adds its own profile over the same {@link FitnessRules}.</p>
 */
public final class MsfwFitness {

    public static final String AGGREGATE = "tech.vsf.ptnt.msfw.domain.core.Aggregate";
    public static final String ENTITY = "tech.vsf.ptnt.msfw.domain.core.Entity";
    public static final String REPOSITORY = "tech.vsf.ptnt.msfw.domain.core.Repository";
    public static final String EVENT_PUBLISH_HANDLER = "tech.vsf.ptnt.msfw.event.handling.EventPublishHandler";
    public static final String IDENTITY = "tech.vsf.ptnt.msfw.domain.core.Identity";
    public static final String STRING_IDENTITY = "tech.vsf.ptnt.msfw.domain.core.StringIdentity";

    private MsfwFitness() {
    }

    /** {@code <serviceRoot>.domain..} must not see application/adapter, Spring/Jakarta, or msfw infra. */
    public static ArchRule domainIsPure(String serviceRoot) {
        return FitnessRules.packageDependsOnNothingOutward(serviceRoot + ".domain..",
                serviceRoot + ".application..",
                serviceRoot + ".adapter..",
                "org.springframework..",
                "jakarta..",
                "tech.vsf.ptnt.springcore..",
                "tech.vsf.ptnt.msfw.infrastructure..");
    }

    /** Vertical use-case slices (the {@code (*)} group in the matcher) must not cross-depend. */
    public static ArchRule useCaseSlices(String sliceMatcher) {
        return FitnessRules.useCaseSlicesDoNotCrossDepend(sliceMatcher);
    }

    /**
     * Use-case slices, ignoring legitimate dependencies on shared layers ({@code sharedPackages},
     * e.g. {@code "<root>.domain..", "<root>.common.."}) — for the flat layout where feature slices
     * sit as siblings of domain and the matcher would otherwise treat domain as a peer slice (F-2).
     */
    public static ArchRule useCaseSlices(String sliceMatcher, String... sharedPackages) {
        return FitnessRules.useCaseSlicesDoNotCrossDepend(sliceMatcher, sharedPackages);
    }

    public static ArchRule aggregatesEncapsulated() {
        return FitnessRules.noPublicSettersOn(AGGREGATE);
    }

    /**
     * Every concrete identity must extend {@code StringIdentity}, not {@code Identity} directly — so
     * the value/equals/hashCode/toString boilerplate is inherited, never re-implemented (drift). The
     * {@code serviceRoot} is accepted for call-site symmetry with {@link #domainIsPure(String)}; the
     * import scope is set by {@code @AnalyzeClasses} / the {@code ClassFileImporter}, as for every rule.
     */
    public static ArchRule identitiesUseStringBase(String serviceRoot) {
        return FitnessRules.concreteSubtypesUseBase(IDENTITY, STRING_IDENTITY);
    }

    public static ArchRule entitiesEncapsulated() {
        return FitnessRules.noPublicSettersOn(ENTITY);
    }

    /** Use-cases ({@code *Uc}) that write through the repository must carry {@code @EventPublishHandler}. */
    public static ArchRule stateWritersPublish(String applicationPackage) {
        return FitnessRules.stateWritingUseCasesArePublishHandlers(
                applicationPackage + "..", "Uc", REPOSITORY, EVENT_PUBLISH_HANDLER);
    }

    /**
     * Framework module-graph guardrail for msfw itself (run inside the msfw build): a module's
     * package must not reach the listed forbidden packages — e.g. {@code domain-core} may not see
     * {@code org.springframework}, the producer may not see the consumer.
     */
    public static ArchRule moduleStaysWithin(String modulePackage, String... forbiddenPackages) {
        return FitnessRules.packageDependsOnNothingOutward(modulePackage, forbiddenPackages);
    }

    /**
     * Architectural-quantum boundary (synchronous): the msfw convention puts synchronous outbound
     * clients in {@code ..outbound.client..} named {@code <Quantum>ClientOa}. Each must target a
     * quantum the architect declared the service may call synchronously ({@code allowedQuanta}, from
     * the registry's {@code spec.quantum.allowedSyncQuanta}). Typically:
     * {@code EA.evaluate("quantumSyncBoundary", MsfwFitness.quantumSyncBoundary(base, EA.allowedSyncQuanta()), classes)}.
     */
    public static ArchRule quantumSyncBoundary(String basePackage, java.util.Set<String> allowedQuanta) {
        return FitnessRules.outboundSyncClientsStayWithinQuanta(
                basePackage + "..outbound.client..", "ClientOa", allowedQuanta);
    }

    public static final String EVENT_SOURCED_REPOSITORY =
            "tech.vsf.ptnt.msfw.domain.eventsourcing.EventSourcedRepository";

    /**
     * Event-sourcing quantum boundary: an event-sourced aggregate's event store stays inside its
     * quantum — its {@code EventSourcedRepository} must not synchronously reach another quantum
     * ({@code ..outbound.client..}); the stream is rehydrated/persisted in-process (the aggregate's
     * own data). Command side + query side + event store = one quantum.
     */
    public static ArchRule eventStoreInQuantum(String basePackage) {
        return FitnessRules.eventStorePersistenceStaysInQuantum(
                EVENT_SOURCED_REPOSITORY, basePackage + "..outbound.client..");
    }
}
