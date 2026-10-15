package com.db.api;

import com.db.config.CollectionConfig;
import com.db.config.DistanceMetric;
import com.db.model.CollectionStats;

/**
 * Коллекция — именованный набор векторов одной размерности и одной метрики.
 * Реализация обязана проверять размерность в {@code put} и {@code getNearest}
 * и бросать {@link com.db.exception.DimensionMismatchException}.
 */
public interface ICollection extends IRamDB, INearestSearch {

    CollectionConfig config();

    default String name()              { return config().name(); }
    default int dimension()            { return config().dimension(); }
    default DistanceMetric metric()    { return config().metric(); }

    CollectionStats stats();
}
