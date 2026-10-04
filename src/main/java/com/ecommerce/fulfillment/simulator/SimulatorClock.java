package com.ecommerce.fulfillment.simulator;

import com.ecommerce.fulfillment.carrier.SimulatedCarrier;
import com.ecommerce.fulfillment.carrier.SimulatedCarrier.SimulatedParcel;
import com.ecommerce.platform.HandlesTask;
import com.ecommerce.platform.TaskExecution;
import com.ecommerce.platform.WorkerComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Moves the simulator's parcels on by themselves, one scan per parcel every 5 seconds, once the warehouse has handed
 * them over (LLD §8.9). Only when {@code ecom.fulfillment.simulator.auto-advance} is on, as in the compose stacks.
 */
@WorkerComponent
@ConditionalOnProperty(name = "ecom.fulfillment.simulator.auto-advance", havingValue = "true")
class SimulatorClock {

    static final String TASK_TYPE = "fulfillment.simulator.advance";

    private final SimulatedCarrier carrier;

    SimulatorClock(SimulatedCarrier carrier) {
        this.carrier = carrier;
    }

    @HandlesTask(type = TASK_TYPE, every = "5s")
    void advance(TaskExecution<Void> task) {
        for (SimulatedParcel parcel : carrier.handedOverParcelsWithScansLeft()) {
            carrier.advance(parcel.reference());
        }
    }
}
