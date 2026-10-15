package com.db.api;

/**
 * Фасад инстанса БД: жизненный цикл, управление коллекциями, персистентность.
 * Операции с векторами выполняются через {@link ICollection}.
 */
public interface IVectorDB
        extends IDatabase, ICollectionManager, IPersistable {
}
