package tech.vsf.ea.archrules.fixtures.esrepo;

import tech.vsf.ea.archrules.fixtures.svc.outbound.client.InventoryClientOa;

/** VIOLATION: an event-sourced repo reaching another quantum synchronously to persist/rehydrate. */
public class LeakyRepo extends FakeRepo {
    @SuppressWarnings("unused")
    private InventoryClientOa crossQuantumClient;
}
