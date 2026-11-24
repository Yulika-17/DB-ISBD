package com.db.exception;

/** Запись на ведомом отклоняется: клиентские мутации принимает только ведущий. */
public class ReplicaReadOnlyException extends DbException {

    public ReplicaReadOnlyException() {
        super("replica is read-only; writes go to the master (or call PROMOTE to failover)");
    }
}
