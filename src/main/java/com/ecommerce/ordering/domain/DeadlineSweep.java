package com.ecommerce.ordering.domain;

import com.ecommerce.platform.HandlesTask;
import com.ecommerce.platform.TaskExecution;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every minute, acts on up to 100 processes past their deadline, the longest overdue first (LLD §6.7). Each runs in
 * its own transaction under the order's lock and checks the deadline again, so two sweeps at once never act twice.
 * Phase 10 moves the sweep to the Job Scheduler.
 */
@Component
class DeadlineSweep {


    private static final Logger log = LoggerFactory.getLogger(DeadlineSweep.class);
    static final String TASK = "ordering.sweep-deadlines";
    static final int BATCH = 100;

    private final OrderRepository orders;
    private final OrderProcesses processes;
    private final TransactionTemplate transactions;
    private final Clock clock;

    DeadlineSweep(OrderRepository orders, OrderProcesses processes, TransactionTemplate transactions, Clock clock) {
        this.orders = orders;
        this.processes = processes;
        this.transactions = transactions;
        this.clock = clock;
    }

    @HandlesTask(type = TASK, every = "1m")
    void sweep(TaskExecution<Void> task) {
        for (UUID orderId : orders.overdue(clock.instant(), BATCH)) {
            try {
                transactions.executeWithoutResult(status -> processes.handleDeadline(orderId));
            } catch (RuntimeException e) {
                // One order's failure must not hold back the deadlines of the orders after it.
                log.error("The deadline of order {} failed", orderId, e);
            }
        }
    }
}
