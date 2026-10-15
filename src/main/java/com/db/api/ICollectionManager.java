package com.db.api;

import com.db.config.CollectionConfig;
import com.db.config.DistanceMetric;

import java.util.List;
import java.util.Optional;

/** Управление коллекциями внутри одного инстанса БД. */
public interface ICollectionManager {

    /**
     * @throws com.db.exception.CollectionAlreadyExistsException если имя занято
     * @throws com.db.exception.InvalidCollectionNameException   если имя недопустимо
     */
    ICollection createCollection(CollectionConfig config);

    default ICollection createCollection(String name, int dimension, DistanceMetric metric) {
        return createCollection(CollectionConfig.of(name, dimension, metric));
    }

    /** @throws com.db.exception.CollectionNotFoundException если коллекции нет */
    ICollection getCollection(String name);

    Optional<ICollection> findCollection(String name);

    default boolean hasCollection(String name) {
        return findCollection(name).isPresent();
    }

    /** Удаляет коллекцию вместе с данными. @return false, если коллекции не было */
    boolean dropCollection(String name);

    List<String> listCollections();
}
